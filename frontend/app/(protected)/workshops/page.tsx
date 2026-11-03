"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { MapPinned } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { OpenableRow, OpenableRowLink } from "@/components/data/OpenableRow";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { EmptyState } from "@/components/EmptyState";
import { FieldProvenance } from "@/components/FieldProvenance";
import { Field, MultiNoteField, Select, TextArea, TextInput } from "@/components/FormControls";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DateRangeField } from "@/components/forms/DateRangeField";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { useEditDeepLink } from "@/components/hooks/useEditDeepLink";
import { useListMemory } from "@/components/hooks/useListMemory";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { PageHeader } from "@/components/PageHeader";
import { CompletenessFilter } from "@/components/records/CompletenessFilter";
import { RowCompletenessRing } from "@/components/records/CompletenessRing";
import { RecordCodeCard } from "@/components/RecordCode";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { WorkshopMappingPanel } from "@/components/settings/WorkshopMappingPanel";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { parseCompletenessFilter, type CompletenessFilter as CompletenessValue } from "@/lib/completenessCore";
import { countNoun, formatDate, formatDateTime } from "@/lib/format";
import { RECORD_VIEW_HREF } from "@/lib/recordView";
import { locationFromForm, requiredText, textValue, useFocusField } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { inferMediaType, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { LIST_PAGE_CEILING } from "@/components/data/cappedList";
import { RENDER_CAP } from "@/components/ui/selectFilter";
import {
  deviceLooksOffline,
  workshopEmptyLabel,
  workshopListNotice,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import { canCreateWorkshops, canManageWorkshops, hasRank, isAdmin, isContentAdmin } from "@/lib/permissions";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import { WORKSHOP_TYPE_LABELS } from "@/lib/types";
import type {
  Artisan,
  Craft,
  PageResult,
  RecordStatus,
  User,
  Workshop,
  WorkshopAssignment,
  WorkshopType
} from "@/lib/types";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { LiveCompleteness } from "@/components/records/LiveCompleteness";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

type WorkshopWithCrafts = Workshop & { crafts?: Array<{ craftId?: string; craft?: { id: string; name?: string } }> };

function linkedCraftNames(workshop: Workshop | null): Record<string, string> {
  const names: Record<string, string> = {};
  for (const item of (workshop as WorkshopWithCrafts | null)?.crafts ?? []) {
    if (item.craft?.id && item.craft.name) names[item.craft.id] = item.craft.name;
  }
  return names;
}

function linkedArtisanIds(workshop: Workshop | null): string[] {
  return workshop?.artisans?.map((item) => item.artisan.id) ?? [];
}

function linkedCraftIds(workshop: Workshop | null): string[] {
  return ((workshop as WorkshopWithCrafts | null)?.crafts ?? [])
    .map((item) => item.craft?.id ?? item.craftId)
    .filter((id): id is string => Boolean(id));
}

function sameIdSet(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  const seen = new Set(a);
  return b.every((id) => seen.has(id));
}

const statusOptions: RecordStatus[] = ["DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"];

const MEDIA_SECTION = "workshop-media";
const MEDIA_SECTION_LABEL = "Workshop media";

export default function WorkshopsPage() {
  return (
    <UploadsProvider>
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
        <WorkshopsPageBody />
      </Suspense>
      <UploadTray />
    </UploadsProvider>
  );
}

function WorkshopsPageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { canDeleteRecords } = useAdminView();
  const { addCompleted } = useUploads();
  const allowManage = canManageWorkshops(user);
  const allowCreate = canCreateWorkshops(user);
  const allowAssign = isAdmin(user);
  const canSetStatus = hasRank(user, "PROFESSOR");
  const [data, setData] = useState<PageResult<Workshop> | null>(null);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [pickerLoad, setPickerLoad] = useState<"loading" | "failed" | "ok">("loading");
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Workshop | null>(null);
  const editingCraftNames = linkedCraftNames(editing);
  const [artisanIds, setArtisanIds] = useState<string[]>([]);
  const [craftIds, setCraftIds] = useState<string[]>([]);
  const [title, setTitle] = useState("");
  const [place, setPlace] = useState("");
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [completeness, setCompleteness] = useState<CompletenessValue>("");
  const [error, setError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const router = useRouter();
  const [dirty, setDirty] = useState(false);
  const [workshopType, setWorkshopType] = useState<WorkshopType>("OTHER");
  const [confirmAction, setConfirmAction] = useState<(() => void) | null>(null);
  const [saving, setSaving] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const afterSaveRef = useRef<(() => void) | null>(null);
  const skipFirstDebounce = useRef(true);
  const [assigning, setAssigning] = useState<Workshop | null>(null);
  const [researchers, setResearchers] = useState<User[]>([]);
  const [assignIds, setAssignIds] = useState<Set<string>>(new Set());
  const [assignBusy, setAssignBusy] = useState(false);

  async function openAssign(workshop: Workshop) {
    setAssigning(workshop);
    setError(null);
    try {
      const [assignments, users] = await Promise.all([
        apiFetch<WorkshopAssignment[]>(`/workshops/${workshop.id}/assignments`),
        researchers.length ? Promise.resolve({ items: researchers }) : listResource<User>("/users", { pageSize: LIST_PAGE_CEILING })
      ]);
      if (!researchers.length) setResearchers((users as { items: User[] }).items ?? []);
      setAssignIds(new Set(assignments.filter((a) => a.status === "GRANTED").map((a) => a.userId)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load assignments");
    }
  }

  async function saveAssignments() {
    if (!assigning) return;
    setAssignBusy(true);
    try {
      await apiFetch(`/workshops/${assigning.id}/assignments`, { method: "PUT", body: JSON.stringify({ userIds: Array.from(assignIds) }) });
      setAssigning(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save assignments");
    } finally {
      setAssignBusy(false);
    }
  }

  function toggleAssign(id: string) {
    setAssignIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function load() {
    try {
      setData(await listResource<Workshop>("/workshops", {
          search: applied || undefined,
          completeness: completeness || undefined,
          page,
          pageSize: 20
        }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load workshops");
    }
  }

  const memory = useListMemory("workshops", { query, applied, page, filters: { completeness } }, data !== null);
  const [placeRestored, setPlaceRestored] = useState(false);
  useEffect(() => {
    if (!placeRestored) return;
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, applied, placeRestored, completeness]);

  useEffect(() => {
    (async () => {
      try {
        const [artisanResult] = await Promise.all([
          listResource<Artisan>("/artisans", { pageSize: RENDER_CAP })
        ]);
        setArtisans(artisanResult.items);
        setPickerLoad("ok");
      } catch {
        setPickerLoad("failed");
      }
    })();
  }, []);

  useEffect(() => {
    if (skipFirstDebounce.current) {
      skipFirstDebounce.current = false;
      return;
    }
    const timer = setTimeout(() => {
      setApplied(query);
      setPage(1);
    }, 350);
    return () => clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    const remembered = memory.recall();
    if (remembered) {
      if ((remembered.query ?? "") !== "") skipFirstDebounce.current = true;
      setQuery(remembered.query ?? "");
      setApplied(remembered.applied ?? "");
      setPage(remembered.page ?? 1);
      setCompleteness(parseCompletenessFilter(remembered.filters?.completeness));
    }
    setPlaceRestored(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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

  function guard(action: () => void) {
    if (dirty) setConfirmAction(() => action);
    else action();
  }

  function resetForm(next: Workshop | null) {
    setEditing(next);
    setWorkshopType(next?.workshopType ?? "OTHER");
    setTitle(next?.title ?? "");
    setPlace(next?.place ?? "");
    setArtisanIds(linkedArtisanIds(next));
    setCraftIds(linkedCraftIds(next));
    setMediaFiles([]);
    setDirty(false);
  }

  useFocusField(Boolean(editing));
  const { loading: deepLinkLoading } = useEditDeepLink<Workshop>({
    endpoint: "/workshops",
    basePath: "/workshops",
    targetRef: formRef,
    onEdit: (record) => guard(() => resetForm(record)),
    onNew: () => guard(() => resetForm(null)),
    onError: setError,
    allowed: allowManage,
    errorMessage: "Unable to load that workshop"
  });

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    setSaving(true);
    try {
      const title = requiredText(form, "title");
      const location = locationFromForm(form);
      const payload: Record<string, unknown> = {
        title,
        workshopType,
        date: requiredText(form, "date"),
        startDate: requiredText(form, "startDate"),
        endDate: requiredText(form, "endDate"),
        place: requiredText(form, "place"),
        description: textValue(form, "description"),
        notes: textValue(form, "notes"),
        status: canSetStatus ? requiredText(form, "status") : "PENDING",
        location
      };
      if (!editing || !sameIdSet(artisanIds, linkedArtisanIds(editing))) payload.artisanIds = artisanIds;
      if (!editing || !sameIdSet(craftIds, linkedCraftIds(editing))) payload.craftIds = craftIds;
      const outcome = await saveOrQueue<Workshop>({
        label: `Workshop · ${title || "Untitled"}`,
        endpoint: editing ? `/workshops/${editing.id}` : "/workshops",
        method: editing ? "PATCH" : "POST",
        body: payload,
        media: [
          {
            files: mediaFiles,
            linkedRecordType: "workshop",
            caption: `Field media for ${title || "workshop"}`,
            location
          }
        ]
      });
      if (outcome.queued) {
        resetForm(null);
        setConfirmAction(null);
        formElement.reset();
        setSaving(false);
        const after = afterSaveRef.current;
        afterSaveRef.current = null;
        if (after) after();
        else if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
      if (mediaFiles.length) {
        const { outcomes } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "workshop",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.title}`,
          location,
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
          setMediaFiles(stranded.map((outcome) => outcome.file));
          setError(
            `${stranded.length} of ${countNoun(mediaFiles.length, "file")} failed to upload: ${stranded
              .map((outcome) => outcome.file.name)
              .join(", ")}. ` +
              "The workshop was saved. Retry them before leaving this form, or they're lost."
          );
          setSaving(false);
          return;
        }
      }
      resetForm(null);
      setConfirmAction(null);
      formElement.reset();
      load();
      const after = afterSaveRef.current;
      afterSaveRef.current = null;
      after?.();
    } catch (err) {
      afterSaveRef.current = null;
      setConfirmAction(null);
      setError(err instanceof Error ? err.message : "Unable to save workshop");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this workshop?",
        "The workshop and its assignments are deleted. Its records are kept.",
        "This can't be undone."
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/workshops/${id}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete workshop");
    }
  }

  const artisanOptions = artisans.map((artisan) => ({ value: artisan.id, label: `${artisan.name} · ${artisan.place}` }));

  const artisanList: WorkshopListState<Artisan> =
    pickerLoad === "ok" ? { kind: "ok", rows: artisans, total: artisans.length } : { kind: pickerLoad };
  const listsOnline = !deviceLooksOffline();
  const artisanVoice: WorkshopListVoice = {
    table: "field",
    noun: "artisans",
    scoped: false,
    accessList: false,
    online: listsOnline
  };
  const artisanNotice = workshopListNotice(artisanList, artisanVoice);

  const rows = data ? [...data.items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? "")) : [];

  return (
    <>
      <PageHeader
        title="Workshops"
        description="Field workshops: dates, place, artisans and notes."
        icon={<MapPinned className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {deepLinkLoading ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-muted">
          Loading the workshop you asked to edit...
        </div>
      ) : null}

      {isContentAdmin(user) ? (
        <div className="mb-5">
          <WorkshopMappingPanel />
        </div>
      ) : null}

      {allowManage && (editing || allowCreate) ? (
      <form
        ref={formRef}
        key={editing?.id ?? "new"}
        onSubmit={submit}
        onInput={() => setDirty(true)}
        onKeyDown={handleFormEnter}
        className="panel mb-5 grid scroll-mt-28 gap-4 p-4"
      >
        {editing ? (
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">
              Editing: {editing.title}
            </span>
          </div>
        ) : null}
        <LiveCompleteness
          kind="workshop"
          formRef={formRef}
          stored={editing}
          counts={{
            artisans: artisanIds.length,
            crafts: craftIds.length,
            photos: mediaFiles.filter((file) => inferMediaType(file) === "IMAGE").length
          }}
        />
        <Field label="Kind of workshop" required>
          <Dropdown
            value={workshopType}
            onChange={(next: string) => {
              setWorkshopType(next as WorkshopType);
              setDirty(true);
            }}
            options={(Object.keys(WORKSHOP_TYPE_LABELS) as WorkshopType[]).map((key) => ({
              value: key,
              label: WORKSHOP_TYPE_LABELS[key]
            }))}
            ariaLabel="Kind of workshop"
          />
          <p className="text-xs leading-5 text-ink-500">
            Only design &amp; prototype workshops can start a 22-stage record.
          </p>
        </Field>

        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <DictatedTextInput
            name="title"
            label="Workshop title"
            required
            value={title}
            onChange={(next) => {
              setTitle(next);
              setDirty(true);
            }}
            explainWhenUnavailable={false}
          />
          <DictatedTextInput
            name="place"
            label="Place"
            required
            value={place}
            onChange={(next) => {
              setPlace(next);
              setDirty(true);
            }}
            explainWhenUnavailable={false}
          />
          <div className="md:col-span-2" data-field="startDate">
            <div data-field="endDate">
              <DateRangeField start={editing?.startDate ?? editing?.date} end={editing?.endDate ?? editing?.date} />
            </div>
          </div>
          <Field label="Status">
            {canSetStatus ? (
              <Select name="status" defaultValue={editing?.status ?? "APPROVED"} onChange={() => setDirty(true)}>
                {statusOptions.map((status) => (
                  <option key={status}>{status}</option>
                ))}
              </Select>
            ) : (
              <div className="py-1.5">
                <StatusBadge status="PENDING" />
              </div>
            )}
          </Field>
          <DictatedTextArea
            name="description"
            label="Description"
            defaultValue={editing?.description ?? ""}
            onDirty={() => setDirty(true)}
            explainWhenUnavailable={false}
          />
          <MultiNoteField defaultValue={editing?.notes ?? ""} />
          <Field label="Linked artisans" fieldKey="artisans">
            <MultiSelectDropdown
              values={artisanIds}
              onChange={(next) => {
                setArtisanIds(next);
                setDirty(true);
              }}
              options={artisanOptions}
              searchable
              placeholder="Link the artisans who took part"
              emptyLabel={workshopEmptyLabel(artisanList, artisanVoice)}
              confirmLabel="Link artisans"
              disabled={pickerLoad !== "loading" && artisans.length === 0}
            />
          </Field>
          {artisanNotice ? <p className="mt-1 text-xs leading-5 text-ink-500">{artisanNotice}</p> : null}
          <FieldBlock label="Crafts covered" fieldKey="crafts">
            <CraftSelect
              multiple
              ariaLabel="Crafts covered"
              placeholder="Pick the crafts this workshop covered"
              value={craftIds}
              fallbackLabels={editingCraftNames}
              onChange={(next) => {
                setCraftIds(next);
                setDirty(true);
              }}
            />
          </FieldBlock>
        </div>
        <div data-field="photo" className="grid min-w-0">
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={(files) => {
              setMediaFiles(files);
              setDirty(true);
            }}
            title="Workshop media"
            description="Attach workshop images, videos, audio notes, attendance references, and documents."
          />
        </div>
        <UploadProgress progress={uploadProgress} sectionId={MEDIA_SECTION} label={MEDIA_SECTION_LABEL} files={mediaFiles} />
        {editing ? (
          <ExistingMedia
            linkedRecordType="workshop"
            linkedRecordId={editing.id}
            recordHold={null}
            title="Previously uploaded workshop media"
          />
        ) : null}
        <LocationFields
          initial={editing ? ((editing as Workshop & { location?: LocationInitialValues | null }).location ?? null) : undefined}
          onDirty={() => setDirty(true)}
        />
        <ActionGroup>
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : editing ? "Update workshop" : "Create workshop"}
          </button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => guard(() => resetForm(null))}>
              Cancel edit
            </button>
          ) : null}
        </ActionGroup>
      </form>
      ) : (
        <div className="panel mb-5 p-4 text-sm text-ink-muted">
          {allowManage ? (
            <>
              The ministry opens workshops. Use Edit on a row to correct one.
            </>
          ) : (
            <>
              The ministry opens workshops. Ask a Ministry Admin to add yours.
            </>
          )}
        </div>
      )}
      {editing ? <RecordCodeCard recordType="workshop" id={editing.id} title={editing.title} className="mb-5" /> : null}
      {editing ? (
        <div className="mb-5 empty:hidden">
          <FieldProvenance extraMetadata={editing.extraMetadata} title="Workshop field contributions" />
        </div>
      ) : null}
      <div className="mb-4">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          placeholder="Search workshops by title, place or description"
        />
      </div>
      <div className="mb-3">
        <CompletenessFilter
          value={completeness}
          onChange={(next) => {
            setCompleteness(next);
            setPage(1);
          }}
        />
      </div>
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No workshops found" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Workshop</ResizableTh>
                  <ResizableTh>Date</ResizableTh>
                  <ResizableTh>Place</ResizableTh>
                  <ResizableTh>Artisans</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh>Created</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((workshop) => (
                  <OpenableRow key={workshop.id} href={RECORD_VIEW_HREF.workshop(workshop.id)}>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <RowCompletenessRing block={workshop.completeness} />
                        <OpenableRowLink href={RECORD_VIEW_HREF.workshop(workshop.id)}>{workshopDisplayTitle(workshop)}</OpenableRowLink>
                      </div>
                      <div className="text-xs text-ink-500">{workshop.description ?? "-"}</div>
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {formatDateTime(workshop.startDate ?? workshop.date)}
                      {workshop.endDate ? <span className="block text-xs text-ink-500">to {formatDateTime(workshop.endDate)}</span> : null}
                    </td>
                    <td className="px-4 py-3 text-ink-700">{workshop.place}</td>
                    <td className="px-4 py-3 text-ink-700">{workshop.artisans?.map((item) => item.artisan.name).join(", ") || "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={workshop.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{formatDate(workshop.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        {allowManage ? (
                          <button className={rowAction("edit")} onClick={() => guard(() => resetForm(workshop))}>
                            Update
                          </button>
                        ) : null}
                        <button className={rowAction("neutral")} onClick={() => setCollabId(workshop.id)}>
                          Discuss
                        </button>
                        {allowAssign ? (
                          <button className={rowAction("neutral")} onClick={() => openAssign(workshop)}>
                            Assign
                          </button>
                        ) : null}
                        {canDeleteRecords ? (
                          <button data-danger className={rowAction("danger")} onClick={() => remove(workshop.id)}>
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
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>

      <FieldDialog
        open={assigning !== null}
        onClose={() => setAssigning(null)}
        title="Assign researchers"
        description={
          <>
            Only assigned researchers can create entries for <span className="font-medium">{assigning?.title}</span>. Submissions
            outside the workshop dates are flagged for admin approval. Leave empty to keep the workshop open to everyone.
          </>
        }
        className="max-w-lg"
        dismissOnBackdrop={false}
        busy={assignBusy}
        footer={
          <>
            <button className="field-button-secondary" onClick={() => setAssigning(null)}>
              Cancel
            </button>
            <button className="field-button" disabled={assignBusy} onClick={saveAssignments}>
              {assignBusy ? "Saving…" : `Save (${assignIds.size})`}
            </button>
          </>
        }
      >
        <div className="mt-3 grid max-h-72 gap-1 overflow-y-auto rounded-md border border-line-200 bg-field-50 p-2">
          {researchers.length === 0 ? (
            <p className="px-2 py-1 text-sm text-ink-muted">No users to assign.</p>
          ) : (
            researchers.map((r) => (
              <label key={r.id} className="flex items-center gap-2 rounded px-2 py-1 hover:bg-field-100">
                <input type="checkbox" checked={assignIds.has(r.id)} onChange={() => toggleAssign(r.id)} />
                <span className="min-w-0 flex-1 truncate text-sm text-ink">
                  {r.name} <span className="text-ink-muted">· {r.email}</span>
                </span>
                <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs text-ink-muted">{r.role}</span>
              </label>
            ))
          )}
        </div>
      </FieldDialog>
      <CollabDialog recordType="workshop" recordId={collabId} onClose={() => setCollabId(null)} />
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
