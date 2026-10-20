"use client";

import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Landmark } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { OpenableRow, OpenableRowLink } from "@/components/data/OpenableRow";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { FieldProvenance } from "@/components/FieldProvenance";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { useEditDeepLink } from "@/components/hooks/useEditDeepLink";
import { useListMemory } from "@/components/hooks/useListMemory";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { PageHeader } from "@/components/PageHeader";
import { RecordCodeCard } from "@/components/RecordCode";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { CraftCategoriesTab } from "@/components/crafts/CraftCategoriesTab";
import { CraftReviewQueue } from "@/components/crafts/CraftReviewQueue";
import { CraftSubCategorySelect } from "@/components/crafts/CraftTaxonomyFields";
import { CRAFT_STATE_OPTIONS, GI_LABEL, canAdministerCraftList, deleteCraft } from "@/components/crafts/craftAdmin";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { apiFetch } from "@/lib/api";
import {
  craftCategoryName,
  craftIndex,
  craftSubCategoryName,
  foldCraftText,
  invalidateCraftCatalogue,
  loadFullCraftCatalogue,
  searchCraftCatalogue,
  useCraftCatalogue,
  type CatalogueCraft,
  type CraftCatalogue,
  type GiStatus
} from "@/lib/craftCatalogue";
import { countNoun } from "@/lib/format";
import { RECORD_VIEW_HREF } from "@/lib/recordView";
import { requiredText, textValue } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { canManageCrafts, isAdmin } from "@/lib/permissions";
import { UnmappedCraftsPanel } from "@/components/crafts/UnmappedCraftsPanel";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import type { Craft } from "@/lib/types";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { cn } from "@/lib/utils";

/** Section id the craft media batch publishes under, so the page-level tray can aggregate it. */
const MEDIA_SECTION = "craft-media";
const MEDIA_SECTION_LABEL = "Craft media";

/** Rows per page of the craft table — the whole list is in the browser, so this only paces reading. */
const PAGE_SIZE = 25;

type Tab = "crafts" | "categories" | "review";

export default function CraftsPage() {
  return (
    <UploadsProvider>
      {/* Next 16: `useEditDeepLink` reads useSearchParams, which must sit inside a Suspense boundary. */}
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
        <CraftsPageBody />
      </Suspense>
      <UploadTray />
    </UploadsProvider>
  );
}

/**
 * The craft master list (REQUIREMENTS_2026-10-20 §2.6): Crafts | Categories | Needs review.
 *
 * The list is the catalogue (`GET /crafts/catalogue`), the one source every picker reads, so this
 * table and every dropdown in both portals show the same crafts. Adding or editing a craft takes
 * `canManageCrafts`; categories, merges and the review queue are content-admin work; Delete is drawn
 * in admin view only.
 *
 * DICTATED: Craft name · Local name · Description. Category, sub-category, states and GI status are
 * closed vocabularies behind dropdowns.
 */
function CraftsPageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const searchParams = useSearchParams();
  const allowManage = canManageCrafts(user);
  const contentAdmin = canAdministerCraftList(user);
  const { catalogue, error: catalogueError, reload: reloadCatalogue } = useCraftCatalogue();
  /** Reference counts per craft, for content admins (the full catalogue carries them). */
  const [referenceCounts, setReferenceCounts] = useState<Map<string, number> | null>(null);
  const [tab, setTab] = useState<Tab>("crafts");

  // Filters over the list. All client-side: the catalogue is the whole list.
  const [query, setQuery] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("");
  const [subCategoryFilter, setSubCategoryFilter] = useState("");
  const [giFilter, setGiFilter] = useState("");
  const [stateFilter, setStateFilter] = useState("");
  const [page, setPage] = useState(1);

  const [editing, setEditing] = useState<Craft | null>(null);
  // Page state, seeded in `resetForm` only — `formElement.reset()` reaches the DOM and not React.
  const [name, setName] = useState("");
  const [localName, setLocalName] = useState("");
  const [subCategoryId, setSubCategoryId] = useState("");
  const [stateOfOrigin, setStateOfOrigin] = useState<string[]>([]);
  const [stateOfPractice, setStateOfPractice] = useState<string[]>([]);
  const [giStatus, setGiStatus] = useState<GiStatus>("NON_GI");
  // Remounting on every reset is what clears the children that hold their own state (the
  // description box above all), which no amount of `form.reset()` can reach.
  const [formKey, setFormKey] = useState(0);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const router = useRouter();
  const [dirty, setDirty] = useState(false);
  const [confirmAction, setConfirmAction] = useState<(() => void) | null>(null);
  const [saving, setSaving] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const afterSaveRef = useRef<(() => void) | null>(null);
  const workshop = useWorkshopSelection({
    initialWorkshopId: editing?.workshopId,
    isEdit: Boolean(editing),
    resetKey: editing?.id ?? null
  });

  // Content admins see how many records use each craft — the number that decides merge or delete.
  useEffect(() => {
    if (!contentAdmin || !catalogue) return;
    let cancelled = false;
    loadFullCraftCatalogue()
      .then((full) => {
        if (cancelled) return;
        setReferenceCounts(new Map(full.crafts.map((craft) => [craft.id, craft.referenceCount ?? 0])));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [contentAdmin, catalogue]);

  /** Search, filters and page, so Back from a craft's read-only page lands on the same rows. */
  const memory = useListMemory(
    "crafts",
    {
      query,
      applied: query,
      page,
      filters: { category: categoryFilter, subCategory: subCategoryFilter, gi: giFilter, state: stateFilter, tab }
    },
    catalogue !== null
  );
  useEffect(() => {
    const remembered = memory.recall();
    if (remembered) {
      setQuery(remembered.query ?? "");
      setPage(remembered.page ?? 1);
      const filters = remembered.filters ?? {};
      setCategoryFilter(filters.category ?? "");
      setSubCategoryFilter(filters.subCategory ?? "");
      setGiFilter(filters.gi ?? "");
      setStateFilter(filters.state ?? "");
      if (filters.tab === "categories" || filters.tab === "review") setTab(filters.tab);
    }
    // Once, on arrival.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Warn on hard navigation (close tab / reload) while the craft form has unsaved edits.
  useEffect(() => {
    if (!dirty) return;
    function onBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = "";
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  useLeaveGuard(dirty, () => guard(() => router.back()));

  /** Run `action` now, or park it behind the unsaved-changes dialog when the form is dirty. */
  function guard(action: () => void) {
    if (dirty) setConfirmAction(() => action);
    else action();
  }

  function resetForm(next: Craft | null, typedName = "") {
    setEditing(next);
    setMediaFiles([]);
    setDirty(false);
    setName(next?.name ?? typedName);
    setLocalName(next?.localName ?? "");
    setSubCategoryId(next?.subCategoryId ?? "");
    setStateOfOrigin(next?.stateOfOrigin ?? []);
    setStateOfPractice(next?.stateOfPractice ?? []);
    setGiStatus(next?.giStatus ?? "NON_GI");
    setFormKey((key) => key + 1);
  }

  // `/crafts?edit=<id>` loads that craft into the form; `/crafts?new=1` opens a blank one.
  const { loading: deepLinkLoading } = useEditDeepLink<Craft>({
    endpoint: "/crafts",
    basePath: "/crafts",
    targetRef: formRef,
    onEdit: (record) => guard(() => resetForm(record)),
    onNew: () => guard(() => resetForm(null)),
    onError: setError,
    allowed: allowManage,
    errorMessage: "Unable to load that craft"
  });

  // `/crafts?new=<name>`: "Add it to the list" from a craft picker, with the name already typed.
  const typedNew = searchParams.get("new");
  const typedNewHandled = useRef(false);
  useEffect(() => {
    if (typedNewHandled.current || !typedNew || typedNew === "1" || !allowManage) return;
    typedNewHandled.current = true;
    setTab("crafts");
    resetForm(null, typedNew);
    router.replace("/crafts");
    requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start" }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [typedNew, allowManage]);

  async function openForUpdate(id: string) {
    try {
      const record = await apiFetch<Craft>(`/crafts/${encodeURIComponent(id)}`);
      guard(() => {
        resetForm(record);
        requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start" }));
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load that craft");
    }
  }

  function afterWrite() {
    invalidateCraftCatalogue();
    reloadCatalogue();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    if (!(await workshop.confirmSubmission())) {
      afterSaveRef.current = null;
      return;
    }
    setSaving(true);
    try {
      const exifItems = await collectExifMetadata(mediaFiles);
      const exifRemark = exifMetadataToRemark(exifItems);
      const description = [textValue(form, "description"), exifRemark].filter(Boolean).join("\n\n") || null;
      const payload: Record<string, unknown> = {
        name: requiredText(form, "name"),
        localName: textValue(form, "localName"),
        subCategoryId: requiredText(form, "subCategoryId"),
        stateOfOrigin,
        stateOfPractice,
        giStatus,
        workshopId: workshop.workshopId || null,
        description
      };
      if (exifItems.length) payload.extraMetadata = { mediaExif: exifItems };
      // Offline this queues to the outbox with its media rather than failing at the Save button.
      const outcome = await saveOrQueue<Craft>({
        label: `Craft · ${payload.name || "Untitled"}`,
        endpoint: editing ? `/crafts/${editing.id}` : "/crafts",
        method: editing ? "PATCH" : "POST",
        body: payload,
        media: [
          {
            files: mediaFiles,
            linkedRecordType: "craft",
            caption: `Field media for ${payload.name || "craft"}`,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          }
        ]
      });
      if (outcome.queued) {
        resetForm(null);
        setSaving(false);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
      afterWrite();
      if (mediaFiles.length) {
        const { outcomes } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "craft",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.name}`,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        addCompleted(
          MEDIA_SECTION,
          MEDIA_SECTION_LABEL,
          outcomes.flatMap((outcome) => (outcome.media ? [outcome.media] : []))
        );
        const stranded = outcomes.filter((outcome) => outcome.failure !== null);
        if (stranded.length) {
          // Keep only the bytes that did not land, so the capture card means what it says.
          setMediaFiles(stranded.map((outcome) => outcome.file));
          setError(
            `${stranded.length} of ${countNoun(mediaFiles.length, "file")} failed to upload: ${stranded
              .map((outcome) => outcome.file.name)
              .join(", ")}. ` + "The craft was saved. Retry them before leaving this form, or they're lost."
          );
          setSaving(false);
          return;
        }
      }
      resetForm(null);
      setConfirmAction(null);
      formElement.reset();
      const after = afterSaveRef.current;
      afterSaveRef.current = null;
      after?.();
    } catch (err) {
      afterSaveRef.current = null;
      setConfirmAction(null);
      setError(err instanceof Error ? err.message : "Unable to save craft");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  async function remove(craft: CatalogueCraft) {
    const ok = await confirm(deleteConfirm(`Delete “${craft.name}”?`, "This permanently deletes the craft. It cannot be undone."));
    if (!ok) return;
    try {
      await deleteCraft(craft.id);
      reloadCatalogue();
    } catch (err) {
      // 409 CRAFT_IN_USE carries the server's sentence: "Used by {n} records. Merge it into another craft instead."
      setError(err instanceof Error ? err.message : "Unable to delete craft");
    }
  }

  const index = catalogue ? craftIndex(catalogue) : null;
  const rows = useMemo(() => (catalogue ? filterCrafts(catalogue, { query, categoryFilter, subCategoryFilter, giFilter, stateFilter }) : []), [
    catalogue,
    query,
    categoryFilter,
    subCategoryFilter,
    giFilter,
    stateFilter
  ]);
  const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const currentPage = Math.min(page, pages);
  const pageRows = rows.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);
  const sortedCategories = catalogue ? [...catalogue.categories].sort((a, b) => a.sortOrder - b.sortOrder) : [];
  const filterSubs = sortedCategories.find((category) => category.id === categoryFilter)?.subCategories ?? [];

  const tabs: { key: Tab; label: string }[] = [
    { key: "crafts", label: "Crafts" },
    { key: "categories", label: "Categories" },
    ...(contentAdmin ? [{ key: "review" as const, label: "Needs review" }] : [])
  ];

  return (
    <>
      <PageHeader
        title="Crafts"
        description={catalogue ? `${catalogue.crafts.length} crafts in ${catalogue.categories.length} categories.` : "The craft list."}
        icon={<Landmark className="h-5 w-5" aria-hidden />}
      />
      {/* C26: an administrator maps crafts to the shared code both portals carry. */}
      {isAdmin(user) && adminMode ? <UnmappedCraftsPanel /> : null}
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {deepLinkLoading ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-muted">Loading the craft…</div>
      ) : null}

      {/* Segmented tabs, not an action row. */}
      <div role="tablist" aria-label="Craft list" className="mb-4 inline-flex rounded-full border border-line-200 bg-card p-1">
        {tabs.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={tab === item.key}
            onClick={() => setTab(item.key)}
            className={cn(
              "rounded-full px-4 py-1.5 text-sm font-medium transition pointer-coarse:py-2.5",
              tab === item.key ? "bg-purple-700 text-white" : "text-ink-700 hover:bg-purple-50"
            )}
          >
            {item.label}
          </button>
        ))}
      </div>

      {!catalogue ? (
        <div className="panel flex flex-wrap items-center justify-between gap-3 p-4 text-sm text-ink-700">
          <span>{catalogueError ?? "Loading…"}</span>
          {catalogueError ? (
            <button type="button" className="field-button-secondary" onClick={reloadCatalogue}>
              Try again
            </button>
          ) : null}
        </div>
      ) : tab === "categories" ? (
        <CraftCategoriesTab catalogue={catalogue} canEdit={contentAdmin} canDelete={contentAdmin && adminMode} onChanged={reloadCatalogue} />
      ) : tab === "review" && contentAdmin ? (
        <CraftReviewQueue catalogue={catalogue} onChanged={reloadCatalogue} />
      ) : (
        <>
          {allowManage ? (
            <form
              ref={formRef}
              key={`${editing?.id ?? "new"}-${formKey}`}
              onSubmit={submit}
              onInput={() => setDirty(true)}
              onKeyDown={handleFormEnter}
              // scroll-mt-28 clears the island nav when the form is scrolled into view.
              className="panel mb-5 grid scroll-mt-28 gap-3 p-4 md:grid-cols-2 lg:grid-cols-4"
            >
              <div className="flex flex-wrap items-center gap-2 md:col-span-2 lg:col-span-4">
                <span className="rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">
                  {editing ? `Editing: ${editing.name}` : "Add craft"}
                </span>
              </div>
              <WorkshopSelect state={workshop} onDirty={() => setDirty(true)} saving={saving} />
              <DictationUnavailableNotice className="md:col-span-2 lg:col-span-4" />
              <DictatedTextInput
                name="name"
                label="Craft name"
                required
                titleCased
                explainWhenUnavailable={false}
                value={name}
                onChange={(next) => {
                  setName(next);
                  setDirty(true);
                }}
              />
              {/* Not title-cased: a local script has no capitals. */}
              <DictatedTextInput
                name="localName"
                label="Local name"
                explainWhenUnavailable={false}
                value={localName}
                onChange={(next) => {
                  setLocalName(next);
                  setDirty(true);
                }}
              />
              <CraftSubCategorySelect
                catalogue={catalogue}
                name="subCategoryId"
                required
                value={subCategoryId}
                disabled={saving}
                onChange={(next) => {
                  setSubCategoryId(next);
                  setDirty(true);
                }}
              />
              <FieldBlock label="State of origin">
                <MultiSelectDropdown
                  ariaLabel="State of origin"
                  values={stateOfOrigin}
                  confirmOnSelect={false}
                  bulk={false}
                  searchable
                  placeholder="Choose states"
                  options={CRAFT_STATE_OPTIONS.map((state) => ({ value: state, label: state }))}
                  onChange={(next) => {
                    setStateOfOrigin(next);
                    setDirty(true);
                  }}
                />
              </FieldBlock>
              <FieldBlock label="State of practice">
                <MultiSelectDropdown
                  ariaLabel="State of practice"
                  values={stateOfPractice}
                  confirmOnSelect={false}
                  bulk={false}
                  searchable
                  placeholder="Choose states"
                  options={CRAFT_STATE_OPTIONS.map((state) => ({ value: state, label: state }))}
                  onChange={(next) => {
                    setStateOfPractice(next);
                    setDirty(true);
                  }}
                />
              </FieldBlock>
              <FieldBlock label="GI status" required>
                {/* Two named modes, a segmented choice — not an on/off setting. */}
                <div role="radiogroup" aria-label="GI status" className="inline-flex w-fit rounded-full border border-line-200 bg-card p-1">
                  {(["GI", "NON_GI"] as const).map((value) => (
                    <button
                      key={value}
                      type="button"
                      role="radio"
                      aria-checked={giStatus === value}
                      onClick={() => {
                        setGiStatus(value);
                        setDirty(true);
                      }}
                      className={cn(
                        "rounded-full px-4 py-1.5 text-sm font-medium transition pointer-coarse:py-2.5",
                        giStatus === value ? "bg-purple-700 text-white" : "text-ink-700 hover:bg-purple-50"
                      )}
                    >
                      {GI_LABEL[value]}
                    </button>
                  ))}
                </div>
              </FieldBlock>
              <div className="md:col-span-2 lg:col-span-4">
                <DictatedTextArea
                  name="description"
                  label="Description"
                  defaultValue={editing?.description ?? ""}
                  explainWhenUnavailable={false}
                  onDirty={() => setDirty(true)}
                />
              </div>
              <div className="md:col-span-2 lg:col-span-4">
                <MediaCaptureField
                  files={mediaFiles}
                  onFilesChange={(files) => {
                    setMediaFiles(files);
                    setDirty(true);
                  }}
                  title="Craft media"
                  description="Reference images, audio notes, video and documents."
                />
              </div>
              <UploadProgress
                progress={uploadProgress}
                sectionId={MEDIA_SECTION}
                label={MEDIA_SECTION_LABEL}
                files={mediaFiles}
                className="md:col-span-2 lg:col-span-4"
              />
              {editing ? (
                <div className="md:col-span-2 lg:col-span-4">
                  {/* `recordHold={null}`: a craft is never filed under a design workshop. */}
                  <ExistingMedia linkedRecordType="craft" linkedRecordId={editing.id} recordHold={null} title="Previously uploaded craft media" />
                </div>
              ) : null}
              <ActionGroup className="md:col-span-2 lg:col-span-4">
                <button className="field-button" disabled={saving}>
                  {saving ? "Saving..." : editing ? "Update craft" : "Add craft"}
                </button>
                {editing ? (
                  <button type="button" className="field-button-secondary" onClick={() => guard(() => resetForm(null))}>
                    Cancel
                  </button>
                ) : null}
              </ActionGroup>
            </form>
          ) : null}
          {editing ? <RecordCodeCard recordType="craft" id={editing.id} title={editing.name} className="mb-5" /> : null}
          {editing ? (
            <div className="mb-5 empty:hidden">
              <FieldProvenance extraMetadata={editing.extraMetadata} title="Craft field contributions" />
            </div>
          ) : null}

          <div className="mb-4 grid gap-3 md:grid-cols-2 lg:grid-cols-5">
            <div className="md:col-span-2 lg:col-span-1">
              <SearchInput
                value={query}
                onChange={(next) => {
                  setQuery(next);
                  setPage(1);
                }}
                placeholder="Search crafts"
                ariaLabel="Search crafts"
              />
            </div>
            <Dropdown
              ariaLabel="Category"
              value={categoryFilter}
              advanceOnSelect={false}
              onChange={(next) => {
                setCategoryFilter(next);
                setSubCategoryFilter("");
                setPage(1);
              }}
              options={[{ value: "", label: "All categories" }, ...sortedCategories.map((category) => ({ value: category.id, label: category.name }))]}
            />
            <Dropdown
              ariaLabel="Sub-category"
              value={subCategoryFilter}
              advanceOnSelect={false}
              disabled={!categoryFilter}
              onChange={(next) => {
                setSubCategoryFilter(next);
                setPage(1);
              }}
              options={[
                { value: "", label: "All sub-categories" },
                ...[...filterSubs].sort((a, b) => a.sortOrder - b.sortOrder).map((sub) => ({ value: sub.id, label: sub.name }))
              ]}
            />
            <Dropdown
              ariaLabel="GI status"
              value={giFilter}
              advanceOnSelect={false}
              onChange={(next) => {
                setGiFilter(next);
                setPage(1);
              }}
              options={[
                { value: "", label: "GI and Non GI" },
                { value: "GI", label: "GI" },
                { value: "NON_GI", label: "Non GI" }
              ]}
            />
            <Dropdown
              ariaLabel="State"
              value={stateFilter}
              advanceOnSelect={false}
              searchable
              onChange={(next) => {
                setStateFilter(next);
                setPage(1);
              }}
              options={[{ value: "", label: "All states" }, ...CRAFT_STATE_OPTIONS.map((state) => ({ value: state, label: state }))]}
            />
          </div>
          <section className="panel overflow-hidden">
            {rows.length === 0 ? (
              <div className="p-4">
                <EmptyState title="No crafts match" />
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[1100px] text-left text-sm">
                  <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                    <tr>
                      <ResizableTh>Serial</ResizableTh>
                      <ResizableTh>Craft</ResizableTh>
                      <ResizableTh>Sub-category</ResizableTh>
                      <ResizableTh>Category</ResizableTh>
                      <ResizableTh>GI</ResizableTh>
                      <ResizableTh>State of origin</ResizableTh>
                      <ResizableTh>State of practice</ResizableTh>
                      {referenceCounts ? <ResizableTh>Records</ResizableTh> : null}
                      <ResizableTh className="text-right">Actions</ResizableTh>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line-200">
                    {pageRows.map((craft) => (
                      // The whole row opens the craft read-only; Update loads it into the form above.
                      <OpenableRow key={craft.id} href={RECORD_VIEW_HREF.craft(craft.id)}>
                        <td className="px-4 py-3 tabular-nums text-ink-500">{craft.sourceSerial ?? "—"}</td>
                        <td className="px-4 py-3">
                          <OpenableRowLink href={RECORD_VIEW_HREF.craft(craft.id)}>{craft.name}</OpenableRowLink>
                        </td>
                        <td className="px-4 py-3 text-ink-700">{index ? craftSubCategoryName(index, craft) : ""}</td>
                        <td className="px-4 py-3 text-ink-700">{index ? craftCategoryName(index, craft) : ""}</td>
                        <td className="px-4 py-3 text-ink-700">{craft.giStatus ? GI_LABEL[craft.giStatus] : "—"}</td>
                        <td className="max-w-xs px-4 py-3 text-ink-700">{craft.stateOfOrigin.join(", ") || "—"}</td>
                        <td className="max-w-xs px-4 py-3 text-ink-700">{craft.stateOfPractice.join(", ") || "—"}</td>
                        {referenceCounts ? (
                          <td className="px-4 py-3 tabular-nums text-ink-700">{referenceCounts.get(craft.id) ?? 0}</td>
                        ) : null}
                        <td className="px-4 py-3 text-right">
                          <RowActions>
                            {allowManage ? (
                              <button className={rowAction("edit")} onClick={() => void openForUpdate(craft.id)}>
                                Update
                              </button>
                            ) : null}
                            <button className={rowAction("neutral")} onClick={() => setCollabId(craft.id)}>
                              Discuss
                            </button>
                            {adminMode ? (
                              <button data-danger className={rowAction("danger")} onClick={() => void remove(craft)}>
                                Delete
                              </button>
                            ) : null}
                          </RowActions>
                        </td>
                      </OpenableRow>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <Pagination page={currentPage} pages={pages} total={rows.length} onPage={setPage} />
          </section>
        </>
      )}
      <CollabDialog recordType="craft" recordId={collabId} onClose={() => setCollabId(null)} />
      <UnsavedChangesDialog
        open={confirmAction !== null}
        saving={saving}
        onKeepEditing={() => setConfirmAction(null)}
        onDiscard={() => {
          const action = confirmAction;
          setConfirmAction(null);
          setDirty(false);
          action?.();
        }}
        onSave={() => {
          afterSaveRef.current = confirmAction;
          formRef.current?.requestSubmit();
        }}
      />
    </>
  );
}

/** The table's rows: the catalogue narrowed by the filters, ranked by the search when there is one. */
function filterCrafts(
  catalogue: CraftCatalogue,
  filters: { query: string; categoryFilter: string; subCategoryFilter: string; giFilter: string; stateFilter: string }
): CatalogueCraft[] {
  const index = craftIndex(catalogue);
  const state = foldCraftText(filters.stateFilter);
  return searchCraftCatalogue(catalogue, filters.query).filter((craft) => {
    if (filters.subCategoryFilter && craft.subCategoryId !== filters.subCategoryFilter) return false;
    if (filters.categoryFilter) {
      const categoryId = craft.categoryId ?? (craft.subCategoryId ? index.subCategoryById.get(craft.subCategoryId)?.categoryId : null);
      if (categoryId !== filters.categoryFilter) return false;
    }
    if (filters.giFilter && craft.giStatus !== filters.giFilter) return false;
    if (state && ![...craft.stateOfOrigin, ...craft.stateOfPractice].some((value) => foldCraftText(value) === state)) return false;
    return true;
  });
}
