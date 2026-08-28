"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Landmark } from "lucide-react";

import { CollabDialog } from "@/components/CollabDialog";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { FieldProvenance } from "@/components/FieldProvenance";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { useEditDeepLink } from "@/components/hooks/useEditDeepLink";
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
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { requiredText, textValue } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { canManageCrafts } from "@/lib/permissions";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import type { Craft, PageResult } from "@/lib/types";

/** Section id the craft media batch publishes under, so the page-level tray can aggregate it. */
const MEDIA_SECTION = "craft-media";
const MEDIA_SECTION_LABEL = "Craft media";

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
 * ── DICTATION ON THIS FORM: WHICH BOXES HAVE A MICROPHONE, AND WHY THE REST DO NOT ──────────────
 *
 * The owner's instruction (2026-08-28): "All the record pages should have dictation options
 * available, wherever applicable so as to reduce the friction as much as possible." So the default
 * flipped — a free-text box HAS a microphone unless there is a reason it must not — and the reason
 * is written down here so a later reader can tell a decision from an oversight.
 *
 * DICTATED: Craft name · Local name · Category · Place · Description.
 *
 * NOT DICTATED, and each is a rule rather than a preference:
 *
 *  - **Workshop** — a record picker behind a themed dropdown. Nothing free to speak.
 *  - **Craft media** — a file picker.
 *  - **Search crafts, categories or descriptions** — the list's own search box, and NOT part of the
 *    record form at all. It is not an answer being stored; it re-runs a query on every keystroke,
 *    and a microphone on it would fire a debounced fetch per committed phrase. If searching by voice
 *    is wanted it belongs in `SearchInput` once, for every list screen, rather than here for one.
 *
 * Category is the one judgement call on this list and it went the dictated way: there is no
 * vocabulary endpoint behind it, nothing validates it, and what researchers type into it is prose.
 * The day it becomes a dropdown it leaves this list.
 */
function CraftsPageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const allowManage = canManageCrafts(user);
  const [data, setData] = useState<PageResult<Craft> | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Craft | null>(null);
  /*
    ── THE FOUR DICTATED ONE-LINE BOXES, IN PAGE STATE ─────────────────────────────────────────────

    `DictatedTextInput` is controlled by its caller and cannot be anything else — the argument is in
    that file, and it is exactly this page's shape that proves it: `submit` finishes a CREATE with
    `resetForm(null); formElement.reset();`, and `formElement.reset()` rewrites the DOM and tells
    React nothing at all. A box that owned its own value would go on holding the craft that was just
    saved while the DOM showed empty, and the next character typed would bring the old name back.

    So `resetForm` is the ONE place these four are seeded, from the record being loaded or from
    nothing at all, and every route into the form goes through it: the row Edit button, `?edit=`,
    `?new=1`, Cancel edit, the saved branch and the queued branch.
  */
  const [name, setName] = useState("");
  const [localName, setLocalName] = useState("");
  const [category, setCategory] = useState("");
  const [place, setPlace] = useState("");
  /*
    ── AND THE FORM IS REMOUNTED ON EVERY RESET, WHICH IS A BUG FIX AND NOT A TIDY-UP ──────────────

    The `<form>` was keyed `editing?.id ?? "new"` alone, so creating a craft did not change the key
    and the form was NOT rebuilt: `formElement.reset()` was the whole of the clearing. That reaches
    uncontrolled DOM inputs and nothing else, and this form has children holding their own state —
    `DictatedTextArea` (the description) most importantly, and `TitleCasedInput`'s typed shadow. So
    after saving a craft the description box painted empty while the component still held the saved
    text, and one keystroke brought the whole previous description back into the next craft. The
    QUEUED branch was worse: it calls `resetForm(null)` and never called `formElement.reset()` at
    all, so an offline save left every box filled with the craft that had just been banked.

    Bumping a counter and putting it in the key is the same instrument `ArtisanForm` uses
    (`formKey`), for the same reason it gives: remounting is what clears the state living inside the
    field components, "which no amount of `form.reset()` can reach".
  */
  const [formKey, setFormKey] = useState(0);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [collabId, setCollabId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  // Unsaved-changes guard: dirty is set by any form input / media change; confirmAction holds the
  // navigation the user asked for while the dialog decides its fate.
  const router = useRouter();
  const [dirty, setDirty] = useState(false);
  const [confirmAction, setConfirmAction] = useState<(() => void) | null>(null);
  const [saving, setSaving] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const afterSaveRef = useRef<(() => void) | null>(null);
  const skipFirstDebounce = useRef(true);
  // The workshop this craft was documented at. One inline form edits every craft here, so `resetKey`
  // re-seeds the picker whenever a different record is loaded into it (see forms/WorkshopSelect).
  const workshop = useWorkshopSelection({
    initialWorkshopId: editing?.workshopId,
    isEdit: Boolean(editing),
    resetKey: editing?.id ?? null
  });

  async function load() {
    try {
      setData(await listResource<Craft>("/crafts", { search: applied || undefined, page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load crafts");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, applied]);

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

  // Soft navigation via the round back control in the page header — the page's only back control —
  // parks through the same mechanism, so Discard performs the navigation that was asked for.
  useLeaveGuard(dirty, () => guard(() => router.back()));

  /** Run `action` now, or park it behind the unsaved-changes dialog when the form is dirty. */
  function guard(action: () => void) {
    if (dirty) setConfirmAction(() => action);
    else action();
  }

  function resetForm(next: Craft | null) {
    setEditing(next);
    setMediaFiles([]);
    setDirty(false);
    // The four dictated boxes are page state, so they are seeded here rather than by a `defaultValue`
    // the remount below would re-read. `next` is the record being loaded into the form, or null for
    // a blank one — the same source the other boxes' `defaultValue`s use.
    setName(next?.name ?? "");
    setLocalName(next?.localName ?? "");
    setCategory(next?.category ?? "");
    setPlace(next?.place ?? "");
    // See `formKey` above: this is what actually empties the description box and every other child
    // holding its own state. It runs on all six routes into the form, including the offline one.
    setFormKey((key) => key + 1);
  }

  // `/crafts?edit=<id>` loads that craft into the form below; `/crafts?new=1` opens a blank one.
  // Both are how the View Data browser, the dashboard tiles and Recent submissions reach this page —
  // before this they all linked to the bare `/crafts`, so "Update craft" landed on the create form.
  const { loading: deepLinkLoading } = useEditDeepLink<Craft>({
    endpoint: "/crafts",
    basePath: "/crafts",
    targetRef: formRef,
    // Through `guard`, exactly as the row Edit button below goes through it: the form stays typeable
    // while the fetch is in flight, and `resetForm` remounts it (`key={editing?.id}`) and clears
    // `dirty` — so seeding directly would throw away anything typed in those seconds with nothing
    // asked and nothing said. Dirty means the unsaved-changes dialog decides, as everywhere else.
    onEdit: (record) => guard(() => resetForm(record)),
    onNew: () => guard(() => resetForm(null)),
    onError: setError,
    allowed: allowManage,
    errorMessage: "Unable to load that craft"
  });

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) {
      afterSaveRef.current = null;
      return;
    }
    setSaving(true);
    try {
      const exifItems = await collectExifMetadata(mediaFiles);
      const exifRemark = exifMetadataToRemark(exifItems);
      const description = [textValue(form, "description"), exifRemark].filter(Boolean).join("\n\n") || null;
      // extraMetadata stays programmatic (EXIF etc.) — the raw JSON textarea is gone for good.
      const payload: Record<string, unknown> = {
        name: requiredText(form, "name"),
        localName: textValue(form, "localName"),
        category: textValue(form, "category"),
        place: textValue(form, "place"),
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
        // OutboxBanner at the top of the page names the entry and says where it lives.
        resetForm(null);
        setSaving(false);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
      if (mediaFiles.length) {
        // `outcomes` AND NOT `uploaded`: one entry per file we handed over, at its position, with the
        // `File` itself attached. That is what lets the two lines below be about the same batch —
        // what landed goes to the tray, what did not stays in the capture card as the actual bytes,
        // with nothing to line up between them and no name-matching (two photographs off one handset
        // are routinely both IMG_0001.jpg).
        const { outcomes } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "craft",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.name}`,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
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
            batch sitting in the capture card, so nothing was ever destroyed here — but the card was
            then describing files that ARE attached to the craft as though they still had to be sent,
            and a second Save would have uploaded every one of them again. Narrowing it to the
            stranded outcomes makes the card mean what it says.

            `outcome.file` and not a lookup by name: two photographs off one handset are routinely
            both IMG_0001.jpg, so the only safe identity is the object itself, which is why this
            reads `outcomes` rather than pairing `failed` back against `mediaFiles`.

            THE SENTENCE DOES NOT SAY "NOTHING HAS BEEN LOST", AND IT DID. That reading was true of
            this instant and false of the next move it recommended: the row's Edit button and the
            `?edit=` deep link's `onEdit` both go through `resetForm`, which begins `setMediaFiles([])`
            — so "re-open the craft" is the one action that discards the bytes the sentence promised
            were safe. The unsaved-changes dialog does interpose (nothing clears `dirty` before this
            return), which made it a false reassurance rather than a silent deletion, but a designer
            who reads a promise and then confirms a Discard has been misled by us.

            THE TWO REMEDIES THAT WOULD MAKE IT SAFE ARE BOTH OUT OF THIS BRANCH'S REACH, so it
            states the position instead of inventing one. "Press Save again" is wrong after a CREATE:
            `editing` is still null on this return, so a second Save POSTs a SECOND craft. Pointing
            `editing` at `saved` would fix that and break something quieter — the EXIF remark for the
            WHOLE batch is already appended to the saved description, and the re-mounted form would
            append the stranded files' remark to it a second time, into a column a ministry officer
            reads. That is a rework of the remark, not a line here.
          */
          setMediaFiles(stranded.map((outcome) => outcome.file));
          setError(
            `${stranded.length} of ${mediaFiles.length} file(s) failed to upload: ${stranded
              .map((outcome) => outcome.file.name)
              .join(", ")}. ` +
              "The craft was saved and the rest are attached. The files that did not go up are still in the capture " +
              "card above, but only in this browser: leaving this form or opening another craft discards them. " +
              "Re-open the craft to attach them again — anything captured here and saved nowhere else has to be taken again."
          );
          setSaving(false);
          return;
        }
      }
      resetForm(null);
      setConfirmAction(null);
      // KEPT, AND NO LONGER THE THING THAT CLEARS THE FORM. `resetForm` above bumps `formKey`, so
      // React rebuilds this form from scratch — see the `formKey` note at the top of this component
      // for the children `.reset()` could never reach. This line still runs against the outgoing DOM
      // node (state updates flush after the handler), which is harmless, and it is what keeps the
      // browser's own idea of "the default values" in step for anything outside React's view.
      formElement.reset();
      load();
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

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this craft?",
        "This permanently deletes the craft. This action cannot be undone.",
        "Artisans, products and tools that reference it keep their craft name as text — they are not deleted and they are not left blank."
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/crafts/${id}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete craft");
    }
  }

  // Most recent first, regardless of the API's name ordering.
  const rows = data ? [...data.items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? "")) : [];

  return (
    <>
      <PageHeader title="Crafts" description="Maintain craft vocabulary used to link artisans, products and tools." icon={<Landmark className="h-5 w-5" aria-hidden />} />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {deepLinkLoading ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-muted">
          Loading the craft you asked to edit...
        </div>
      ) : null}
      {allowManage ? (
      <form
        ref={formRef}
        key={`${editing?.id ?? "new"}-${formKey}`}
        onSubmit={submit}
        onInput={() => setDirty(true)}
        onKeyDown={handleFormEnter}
        // scroll-mt-28 clears the island nav when `?edit=` scrolls this form into view (§AppShell pt-24).
        className="panel mb-5 grid scroll-mt-28 gap-3 p-4 md:grid-cols-2 lg:grid-cols-4"
      >
        {/* WHICH craft is in the form. Arriving from ?edit= drops the researcher straight into a
            populated form part-way down the page, and "Update craft" on the button is too far away
            (and too generic) to answer "update WHICH one" on its own. */}
        {editing ? (
          <div className="flex flex-wrap items-center gap-2 md:col-span-2 lg:col-span-4">
            <span className="rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">
              Editing: {editing.name}
            </span>
          </div>
        ) : null}
        {/* The workshop leads every other dropdown: it is the context the record belongs to. */}
        <WorkshopSelect state={workshop} onDirty={() => setDirty(true)} saving={saving} />
        {/* TITLE-CASED, AND SAYING SO — the one record form on the web that was not.
            `create_craft` and `update_craft` both call `clean_data(...)` with its default
            `title_case=True`, and `records.TITLE_CASE_FIELDS` contains `name` and `place`, so a
            researcher who typed "bagru block printing" here saved and the record silently became
            something else. That is precisely the defect `TitleCasedInput`'s header was written for —
            "the web showed nothing, so the value changed silently AFTER saving and the form and the
            record disagreed" — and `ArtisanForm`, `ProductForm`, `ToolForm` and `ProcessForm` all
            already use this control for exactly these columns, as does Android's `CraftForm`
            (`titleCased = true` on both boxes).

            IT IS STILL THAT COMPONENT, reached through `DictatedTextInput`'s `titleCased` prop
            rather than mounted directly: the dictation sweep of 2026-08-28 put a microphone under
            these boxes, and the one thing it was not allowed to do on the way was drop the "Will be
            saved as …" sentence this comment exists for. The prop mounts `TitleCasedInput` itself,
            not a copy of its hint.

            NOT `localName`, and never: it is Devanagari or Gujarati, where capitalising means
            nothing, and `TITLE_CASE_FIELDS` leaves it out for that reason. `category` is left alone
            too — it is not in that set, so a hint here would promise a normalisation the API does not
            perform, which is worse than no hint. Each of the two says so at its own call site. */}
        {/*
          THE ONE PLACE THIS FORM EXPLAINS A MISSING MICROPHONE — see `DictationUnavailableNotice`.
          Every dictated box below passes `explainWhenUnavailable={false}`, because on Firefox the
          same honest paragraph printed five times across one four-column row is grey text nobody
          reads. It spans the whole grid so it reads as a sentence about the form rather than as the
          first field's caption.
        */}
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
        {/* NOT title-cased, and never — see the block above: it is Devanagari or Gujarati, where
            capitalising means nothing, and `TITLE_CASE_FIELDS` leaves it out for that reason. It
            still gets a microphone: the recogniser takes whichever language it is set to, and Hindi,
            Odia and Gujarati are all in `DICTATION_LANGUAGES`. */}
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
        {/* CATEGORY IS FREE TEXT ON THIS SCREEN AND NOT A CLOSED LIST — there is no vocabulary
            endpoint behind it and nothing validates it, so it is prose a researcher types ("textile,
            hand block printed"). That is what makes it dictatable; if it ever becomes a dropdown it
            leaves this list, like every other closed vocabulary here. Also NOT title-cased: it is
            not in `TITLE_CASE_FIELDS`, so a hint would promise a normalisation the API does not
            perform. */}
        <DictatedTextInput
          name="category"
          label="Category"
          explainWhenUnavailable={false}
          value={category}
          onChange={(next) => {
            setCategory(next);
            setDirty(true);
          }}
        />
        <DictatedTextInput
          name="place"
          label="Place"
          titleCased
          explainWhenUnavailable={false}
          value={place}
          onChange={(next) => {
            setPlace(next);
            setDirty(true);
          }}
        />
        <div className="md:col-span-2 lg:col-span-4">
          {/*
            DICTATION BUT NOT RICH TEXT, and the second half of that is a decision rather than an
            omission.

            Android's `CraftForm` draws this column as `TextInput("Description", …, dictate = true,
            rich = true)` and the web drew it as a bare `<TextArea>`, so the web record form was the
            poorer of the two for the same fact — a researcher who would rather speak three sentences
            about a technique than thumb them in had no microphone here at all. `DictatedTextArea`
            closes that half and changes nothing about what is stored: plain text in, plain text out,
            exactly as Android's `recordStoredFromDoc` writes (`toPlain(doc)`, never JSON).

            THE LIST AFFORDANCE IS NOT CLOSED HERE ON PURPOSE. Pointing `RichTextField` at this box
            would make `Craft.description` a NINTH `String?` column holding `{"blocks":…}`, and
            `RecordProseText.kt`'s storage block spells out what that costs in this repository
            specifically: `record_fields.cell()`, the `/data/report` workbook, `details.txt`, both
            `/export` CSVs and the review diff all read these columns as prose, so the braces print
            verbatim into an exported cell and nothing crashes to say so. That is a storage decision
            across three languages, not an input-method fix, and it belongs with the other eight —
            `_reference_data`'s docstring names them — or nowhere.
          */}
          <DictatedTextArea
            name="description"
            label="Description"
            defaultValue={editing?.description ?? ""}
            /* The form says it once, at the top of the grid — see `DictationUnavailableNotice`. */
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
            description="Attach or capture craft reference images, audio notes, video, and documents."
          />
        </div>
        <UploadProgress
          progress={uploadProgress}
          sectionId={MEDIA_SECTION}
          label={MEDIA_SECTION_LABEL}
          className="md:col-span-2 lg:col-span-4"
        />
        {/* Editing an existing craft: everything already attached to it, with playback and per-file
            delete — the same surface the artisan/product/tool/workshop forms carry. */}
        {editing ? (
          <div className="md:col-span-2 lg:col-span-4">
            <ExistingMedia linkedRecordType="craft" linkedRecordId={editing.id} title="Previously uploaded craft media" />
          </div>
        ) : null}
        <div className="flex gap-2 md:col-span-2 lg:col-span-4">
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : editing ? "Update craft" : "Create craft"}
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
          Browse the craft vocabulary below. Ask the master admin for craft creation access to add or edit crafts.
        </div>
      )}
      {/* The craft's own code, drawn live for the craft in the form. A craft is edited INLINE on this
          list rather than at a /[id]/edit route, so this is the record's detail view and this is
          where its code belongs. */}
      {editing ? <RecordCodeCard recordType="craft" id={editing.id} title={editing.name} className="mb-5" /> : null}
      {/* Same provenance block the artisan/product/tool/workshop edit surfaces carry, for the craft
          being edited. empty:hidden — FieldProvenance renders nothing without provenance access. */}
      {editing ? (
        <div className="mb-5 empty:hidden">
          <FieldProvenance extraMetadata={editing.extraMetadata} title="Craft field contributions" />
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
          placeholder="Search crafts, categories or descriptions"
        />
      </div>
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No crafts found" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Name</ResizableTh>
                  <ResizableTh>Local name</ResizableTh>
                  <ResizableTh>Category</ResizableTh>
                  <ResizableTh>Place</ResizableTh>
                  <ResizableTh>Description</ResizableTh>
                  <ResizableTh>Created</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((craft) => (
                  <tr key={craft.id}>
                    <td className="px-4 py-3 font-medium text-ink-900">{craft.name}</td>
                    <td className="px-4 py-3 text-ink-700">{craft.localName ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{craft.category ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{craft.place ?? "-"}</td>
                    <td className="max-w-md px-4 py-3 text-ink-700">{craft.description ?? "-"}</td>
                    <td className="px-4 py-3 text-ink-700">{craft.createdAt ? formatDate(craft.createdAt) : "-"}</td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        {allowManage ? (
                          <button className={rowAction("edit")} onClick={() => guard(() => resetForm(craft))}>
                            Edit
                          </button>
                        ) : null}
                        <button className={rowAction("neutral")} onClick={() => setCollabId(craft.id)}>
                          Discuss
                        </button>
                        {adminMode ? (
                          <button className={rowAction("danger")} onClick={() => remove(craft.id)}>
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
      {/* The hand-rolled overlay this replaces was pre-FieldDialog legacy: no focus trap, no Escape,
          no focus restoration and no scroll lock. /workshops already used the shared component. */}
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
