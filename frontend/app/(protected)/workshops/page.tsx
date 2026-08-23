"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { MapPinned } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { EmptyState } from "@/components/EmptyState";
import { FieldProvenance } from "@/components/FieldProvenance";
import { Field, MultiNoteField, Select, TextArea, TextInput } from "@/components/FormControls";
import { DateRangeField } from "@/components/forms/DateRangeField";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { useEditDeepLink } from "@/components/hooks/useEditDeepLink";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { PageHeader } from "@/components/PageHeader";
import { RecordCodeCard } from "@/components/RecordCode";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { WorkshopMappingPanel } from "@/components/settings/WorkshopMappingPanel";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import { locationFromForm, requiredText, textValue } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { canManageWorkshops, hasRank, isAdmin } from "@/lib/permissions";
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

// The API includes the workshop's linked crafts (not yet in the Workshop TS type); used to pre-fill
// the "Crafts covered" selection when editing.
type WorkshopWithCrafts = Workshop & { crafts?: Array<{ craftId?: string; craft?: { id: string } }> };

/** The artisan ids a workshop is already linked to, for pre-filling the picker on edit. */
function linkedArtisanIds(workshop: Workshop | null): string[] {
  return workshop?.artisans?.map((item) => item.artisan.id) ?? [];
}

/** The craft ids a workshop already covers. The API returns either a nested craft or a bare id. */
function linkedCraftIds(workshop: Workshop | null): string[] {
  return ((workshop as WorkshopWithCrafts | null)?.crafts ?? [])
    .map((item) => item.craft?.id ?? item.craftId)
    .filter((id): id is string => Boolean(id));
}

/**
 * Do these two id lists name the same set? ORDER-INSENSITIVE on purpose: the multi-selects hand back
 * the order the researcher ticked boxes in, while the stored list comes back in the join table's own
 * order, so comparing sequences would report "changed" for a roster nobody touched — which is the
 * exact 403 the diff in `submit` exists to avoid. Both inputs are already duplicate-free (the join
 * table is unique on the pair; the pickers cannot tick a row twice), so a length check plus
 * membership is a true set comparison here.
 */
function sameIdSet(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  const seen = new Set(a);
  return b.every((id) => seen.has(id));
}

const statusOptions: RecordStatus[] = ["DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"];

/** Section id the workshop media batch publishes under, so the page-level tray can aggregate it. */
const MEDIA_SECTION = "workshop-media";
const MEDIA_SECTION_LABEL = "Workshop media";

export default function WorkshopsPage() {
  return (
    <UploadsProvider>
      {/* Next 16: `useEditDeepLink` reads useSearchParams, which must sit inside a Suspense boundary. */}
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
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const allowManage = canManageWorkshops(user);
  const allowAssign = isAdmin(user);
  // Status policy (mirrors the backend): professor+ may pick any status (default APPROVED on
  // create); everyone below sees a locked Pending chip and the server forces/keeps the status.
  const canSetStatus = hasRank(user, "PROFESSOR");
  const [data, setData] = useState<PageResult<Workshop> | null>(null);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Workshop | null>(null);
  // Both link pickers are themed MultiSelectDropdowns, which are React state rather than form
  // controls, so the selections live here and are read straight out of state at submit time. They
  // are re-seeded in `resetForm` — the one place a different workshop is ever loaded into the form.
  const [artisanIds, setArtisanIds] = useState<string[]>([]);
  const [craftIds, setCraftIds] = useState<string[]>([]);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  // Unsaved-changes guard: dirty is set by any form input / media change; confirmAction holds the
  // navigation the user asked for while the dialog decides its fate.
  const router = useRouter();
  const [dirty, setDirty] = useState(false);
  /*
    React state rather than an uncontrolled form control, for the same reason the two link pickers
    are: `resetForm` is the ONE place a different workshop is loaded into the form, and an
    uncontrolled `<select>` keeps whatever the previous record left in it. Editing an "Other"
    workshop straight after a design-prototype one would silently re-file it as design-prototype.
  */
  const [workshopType, setWorkshopType] = useState<WorkshopType>("OTHER");
  const [confirmAction, setConfirmAction] = useState<(() => void) | null>(null);
  const [saving, setSaving] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const afterSaveRef = useRef<(() => void) | null>(null);
  const skipFirstDebounce = useRef(true);
  // Assignment manager (admin only).
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
        // 100 is the server's cap (`pageSize: int = Query(20, ge=1, le=100)`); asking for 200 was a
        // 422, which left `researchers` empty and the dialog permanently showing "No users to assign".
        researchers.length ? Promise.resolve({ items: researchers }) : listResource<User>("/users", { pageSize: 100 })
      ]);
      if (!researchers.length) setResearchers((users as { items: User[] }).items ?? []);
      // GRANTED only. The endpoint returns every row on the workshop — pending requests, denials and
      // revocations too — so taking every userId pre-ticked people who had been refused or removed,
      // and saving the dialog (a whole-set PUT) silently granted them access again.
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
      setData(await listResource<Workshop>("/workshops", { search: applied || undefined, page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load workshops");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, applied]);

  // The linked-artisan / craft pickers only need loading once.
  useEffect(() => {
    (async () => {
      try {
        const [artisanResult, craftResult] = await Promise.all([
          listResource<Artisan>("/artisans", { pageSize: 100 }),
          listResource<Craft>("/crafts", { pageSize: 100 })
        ]);
        setArtisans(artisanResult.items);
        setCrafts(craftResult.items);
      } catch {
        // The pickers degrade to empty lists; the workshop list still loads.
      }
    })();
  }, []);

  // Live search: debounce typing by 350ms; Enter applies immediately via onSubmit.
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

  // Warn on hard navigation (close tab / reload) while the workshop form has unsaved edits.
  useEffect(() => {
    if (!dirty) return;
    function onBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = "";
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [dirty]);

  // Soft navigation via the round back control in the page header — the page's only back control —
  // parks through the same mechanism, so Discard performs the navigation that was asked for.
  useLeaveGuard(dirty, () => guard(() => router.back()));

  /** Run `action` now, or park it behind the unsaved-changes dialog when the form is dirty. */
  function guard(action: () => void) {
    if (dirty) setConfirmAction(() => action);
    else action();
  }

  function resetForm(next: Workshop | null) {
    setEditing(next);
    setWorkshopType(next?.workshopType ?? "OTHER");
    setArtisanIds(linkedArtisanIds(next));
    setCraftIds(linkedCraftIds(next));
    setMediaFiles([]);
    setDirty(false);
  }

  // `/workshops?edit=<id>` loads that workshop into the form below; `/workshops?new=1` opens a blank
  // one. Both are how the View Data browser, a search result, the dashboard tiles and Recent
  // submissions reach this page — before this they all linked to the bare `/workshops`, so
  // "Update workshop" landed on the create form with none of the record's fields in it.
  //
  // The fetched record goes through the SAME `resetForm` a row click uses, which is what re-seeds the
  // two link pickers: `artisanIds` and `craftIds` are React state rather than form controls, so a
  // deep link that only set `editing` would have shown the workshop's title and place with its
  // artisan and craft rosters silently empty — and saving would then have cleared them.
  const { loading: deepLinkLoading } = useEditDeepLink<Workshop>({
    endpoint: "/workshops",
    basePath: "/workshops",
    targetRef: formRef,
    // Through `guard`, exactly as the row Edit button below goes through it — see the same note on
    // /crafts for why seeding directly would silently discard work typed while the fetch was in
    // flight.
    onEdit: (record) => guard(() => resetForm(record)),
    onNew: () => guard(() => resetForm(null)),
    onError: setError,
    allowed: allowManage,
    errorMessage: "Unable to load that workshop"
  });

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls event.currentTarget after the first await — capture it before any async work.
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
        // Below professor the backend forces PENDING on create and drops status changes on update.
        status: canSetStatus ? requiredText(form, "status") : "PENDING",
        location
      };
      /*
       * THE TWO ROSTERS ARE SENT ONLY WHEN THEY CHANGED, and only on an edit. This mirrors Android's
       * WorkshopForm, which diffs them the same way, and it is a permission fix rather than a saving
       * of bytes.
       *
       * `PATCH /workshops/{id}` re-checks each roster it is SENT: when the caller is neither an
       * admin nor the workshop's creator and lacks EDIT-level access to it,
       * `assert_can_contribute_relation(..., populated=link_count > 0, ...)` refuses any send at all
       * against an already-populated roster. Sending both unconditionally therefore meant a
       * professor who opened someone else's workshop, corrected a typo in its PLACE and pressed
       * Update was answered "Only the original contributor or an admin can change populated
       * relation: artisanIds" — a refusal about two pickers they had not touched, on a save the
       * server would otherwise have accepted. Omitting an unchanged roster leaves `artisanIds` unset
       * in the payload, which is exactly what the WorkshopUpdate schema's `list[str] | None = None`
       * means: do not touch this relation.
       *
       * On CREATE both are always sent — there is no stored roster to compare against, and the
       * create path has no such check.
       */
      if (!editing || !sameIdSet(artisanIds, linkedArtisanIds(editing))) payload.artisanIds = artisanIds;
      if (!editing || !sameIdSet(craftIds, linkedCraftIds(editing))) payload.craftIds = craftIds;
      // Offline this queues to the outbox with its media rather than failing at the Save button.
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
        // OutboxBanner at the top of the page names the entry and says where it lives.
        setSaving(false);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
      if (mediaFiles.length) {
        // `outcomes` AND NOT `uploaded`: one entry per file we handed over, at its position, with the
        // `File` attached. `uploaded` is COMPACTED, so its positions are not this page's — see
        // `BatchResult.outcomes` for the two ship-blockers that came out of lining those up.
        const { outcomes } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "workshop",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.title}`,
          location,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        // The uploaded files surface twice: as chips under this section and in the page-level tray.
        addCompleted(
          MEDIA_SECTION,
          MEDIA_SECTION_LABEL,
          outcomes.flatMap((outcome) => (outcome.media ? [outcome.media] : []))
        );
        const stranded = outcomes.filter((outcome) => outcome.failure !== null);
        if (stranded.length) {
          /*
            KEEP THE BYTES THAT DID NOT LAND, AND ONLY THOSE. The early return already left the whole
            batch in the capture card, so nothing was destroyed — but the card then described files
            that ARE attached to the workshop as still needing to be sent, and a second Save would
            have re-uploaded every one of them. Narrowing it to the stranded outcomes makes the card
            mean what it says. `outcome.file` and not a name lookup: two photographs off one handset
            are routinely both IMG_0001.jpg.

            THE SENTENCE DOES NOT SAY "NOTHING HAS BEEN LOST", AND IT DID. That was true of this
            instant and false of the move it recommended: the row's Edit button and the `?edit=`
            deep link's `onEdit` both go through `resetForm`, which calls `setMediaFiles([])` — so
            "re-open the workshop" is exactly the action that discards the bytes the sentence
            promised were safe. `dirty` is still true here, so the unsaved-changes dialog interposes
            and nothing vanishes without a click; being asked to confirm a Discard right after
            reading a promise is still us misleading the designer.

            AND NOT "PRESS SAVE AGAIN", which is the only remedy in reach and is wrong after a
            CREATE: `editing` is still null on this return, so a second Save POSTs a SECOND workshop.
            The crafts page carries the identical sentence for the identical reason — the two are
            kept word for word so a fix to one is visibly owed to the other.
          */
          setMediaFiles(stranded.map((outcome) => outcome.file));
          setError(
            `${stranded.length} of ${mediaFiles.length} file(s) failed to upload: ${stranded
              .map((outcome) => outcome.file.name)
              .join(", ")}. ` +
              "The workshop was saved and the rest are attached. The files that did not go up are still in the " +
              "capture card above, but only in this browser: leaving this form or opening another workshop discards " +
              "them. Re-open the workshop to attach them again — anything captured here and saved nowhere else has " +
              "to be taken again."
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
        "This permanently deletes the workshop. This action cannot be undone.",
        "Researcher assignments to it go too. Records documented during the workshop are kept."
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
  const craftOptions = crafts.map((craft) => ({
    value: craft.id,
    label: craft.place ? `${craft.name} · ${craft.place}` : craft.name
  }));

  // The API already returns workshops createdAt-descending; re-sorting keeps the guarantee local
  // so the list stays newest-first even if a caller ever changes the server ordering.
  const rows = data ? [...data.items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? "")) : [];

  return (
    <>
      <PageHeader
        title="Workshops"
        description="Create field workshop records, link artisans and store date, place, notes and GPS context."
        icon={<MapPinned className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {deepLinkLoading ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-muted">
          Loading the workshop you asked to edit...
        </div>
      ) : null}

      {/* WHY THIS SITS ON THE WORKSHOPS PAGE and not in Settings. What it fixes is a workshop's own
          contents — "this workshop looks empty" — so the person who notices the symptom is already here,
          looking at the workshop. Admin-only because it writes to hundreds of rows at once; the endpoints
          are gated the same way, so the card is not the security boundary. */}
      {allowAssign ? (
        <div className="mb-5">
          <WorkshopMappingPanel />
        </div>
      ) : null}

      {allowManage ? (
      <form
        ref={formRef}
        key={editing?.id ?? "new"}
        onSubmit={submit}
        onInput={() => setDirty(true)}
        onKeyDown={handleFormEnter}
        // scroll-mt-28 clears the island nav when `?edit=` scrolls this form into view, and it is
        // load-bearing here in a way it is not on /crafts: an admin has the workshop-mapping panel
        // sitting above this form, so there is real content to scroll past.
        className="panel mb-5 grid scroll-mt-28 gap-4 p-4"
      >
        {/* WHICH workshop is in the form — see the same chip on /crafts for why the button label is
            not enough on its own when the researcher arrived here from another page entirely. */}
        {editing ? (
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">
              Editing: {editing.title}
            </span>
          </div>
        ) : null}
        {/*
          WHICH KIND OF WORKSHOP, FIRST — above the title, because it changes what the record is
          FOR rather than describing it.

          A Design & Prototype Development Workshop and an ordinary documentation visit were both
          `Workshop` rows with nothing to tell them apart, so the design-workshop page's picker had
          to offer every craft-documentation visit ever recorded and the right row was hard to find.
          Marking one here is what puts it in that list.
        */}
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
            A design &amp; prototype workshop can be chosen on the Design workshops page to fill in a
            22-stage record&apos;s cover details. Anything else stays out of that list.
          </p>
        </Field>

        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <Field label="Workshop title" required>
            <TextInput name="title" required defaultValue={editing?.title ?? ""} />
          </Field>
          <Field label="Place" required>
            <TextInput name="place" required defaultValue={editing?.place ?? ""} />
          </Field>
          <div className="md:col-span-2">
            <DateRangeField start={editing?.startDate ?? editing?.date} end={editing?.endDate ?? editing?.date} />
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
          <Field label="Description">
            <TextArea name="description" defaultValue={editing?.description ?? ""} />
          </Field>
          <MultiNoteField defaultValue={editing?.notes ?? ""} />
          {/* Both link pickers use the same themed multi-select as every other one in the app; the
              selections are React state, not FormData (see the note on `artisanIds` above).

              Both pass `searchable` rather than leaving it to the option count, because both lists
              are records: the artisans interviewed and the crafts recorded. The count rule would
              give this one form a filter box on the artisan picker and none on the craft picker for
              most of this repository's life (16 against 9, either side of a threshold of 8), which
              is one form asking the same question two ways. */}
          <Field label="Linked artisans">
            <MultiSelectDropdown
              values={artisanIds}
              onChange={(next) => {
                setArtisanIds(next);
                setDirty(true);
              }}
              options={artisanOptions}
              searchable
              placeholder="Link the artisans who took part"
              emptyLabel="No artisans recorded yet"
              confirmLabel="Link artisans"
            />
          </Field>
          <Field label="Crafts covered">
            {crafts.length === 0 ? (
              <p className="rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm text-ink-muted">
                No crafts available yet. Create a craft first.
              </p>
            ) : (
              <MultiSelectDropdown
                values={craftIds}
                onChange={(next) => {
                  setCraftIds(next);
                  setDirty(true);
                }}
                options={craftOptions}
                searchable
                placeholder="Pick the crafts this workshop covered"
                emptyLabel="No crafts available yet"
                confirmLabel="Add crafts"
              />
            )}
          </Field>
        </div>
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={(files) => {
            setMediaFiles(files);
            setDirty(true);
          }}
          title="Workshop media"
          description="Attach workshop images, videos, audio notes, attendance references, and documents."
        />
        <UploadProgress progress={uploadProgress} sectionId={MEDIA_SECTION} label={MEDIA_SECTION_LABEL} />
        {/* Editing an existing workshop: everything already attached to it, with per-file delete. */}
        {editing ? <ExistingMedia linkedRecordType="workshop" linkedRecordId={editing.id} title="Previously uploaded workshop media" /> : null}
        {/*
          One form serves create AND edit here (the `key` above remounts it), so the stored location
          has to be handed over on the edit pass. Without it the card reads `initial === undefined`,
          treats an edit as a new record, and auto-captures — writing wherever the researcher happens
          to be sitting over the coordinates of a workshop that was documented somewhere else.
        */}
        <LocationFields
          initial={editing ? ((editing as Workshop & { location?: LocationInitialValues | null }).location ?? null) : undefined}
          onDirty={() => setDirty(true)}
        />
        <div className="flex gap-2">
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : editing ? "Update workshop" : "Create workshop"}
          </button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => guard(() => resetForm(null))}>
              Cancel edit
            </button>
          ) : null}
        </div>
      </form>
      ) : (
        <div className="panel mb-5 p-4 text-sm text-ink-muted">
          Browse workshops below. Ask the master admin for workshop creation access to add or edit workshops.
        </div>
      )}
      {/* The workshop's own code, drawn live for the workshop in the form. A workshop is edited
          INLINE on this list rather than at a /[id]/edit route, so this is the record's detail view
          and this is where its code belongs. */}
      {editing ? <RecordCodeCard recordType="workshop" id={editing.id} title={editing.title} className="mb-5" /> : null}
      {/* Same provenance block the artisan/product/tool edit surfaces carry, for the workshop being
          edited. empty:hidden — FieldProvenance renders nothing without provenance access. */}
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
                  <tr key={workshop.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">{workshop.title}</div>
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
                            Edit
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
                        {adminMode ? (
                          <button className={rowAction("danger")} onClick={() => remove(workshop.id)}>
                            Delete
                          </button>
                        ) : null}
                      </RowActions>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>

      {/*
        THE ASSIGN DIALOG GOES THROUGH `FieldDialog` LIKE EVERY OTHER MODAL IN THE APP. It used to be
        a `fixed inset-0 z-50 bg-black/40` div with a `stopPropagation` guard and nothing else: no
        `role="dialog"`, no focus trap, no Escape, no focus restore, no scroll lock — so an admin
        opening it from the keyboard was left on the Assign button behind the dimmer with the table
        still being read out underneath, and the page scrolled under the overlay.

        `dismissOnBackdrop={false}` because the panel below holds work in progress: the ticks are
        state, not a saved answer, and a stray click on the dim area silently threw away however many
        researchers had been picked while showing the original assignment untouched on reopening.
        `busy` suspends Escape and the X while the PUT is in flight for the same reason.
      */}
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
            {/* Deliberately NOT disabled while `busy`: `busy` suspends the ambiguous dismissals
                (Escape, the X, the backdrop), and Cancel is the one explicit way out that must stay
                available — as it was before this dialog had any of the others. */}
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
