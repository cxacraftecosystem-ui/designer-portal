"use client";

import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import { AudioLines, Images, Loader2, QrCode, Upload } from "lucide-react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import {
  DesignWorkshopSelect,
  useDesignWorkshopSelection
} from "@/components/forms/DesignWorkshopSelect";
import { LIST_PAGE_CEILING, listCut, type ListCut } from "@/components/data/cappedList";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { Markdown } from "@/components/Markdown";
import { MediaJobsPanel } from "@/components/media/MediaJobsPanel";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { RecordCodeCard } from "@/components/RecordCode";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { ComboBox, Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { apiFetch, listResource } from "@/lib/api";
import { bytes, formatDateTime } from "@/lib/format";
import { locationFromForm, textValue } from "@/lib/forms";
import { inferMediaType, transcribeMediaNow, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { isAdmin } from "@/lib/permissions";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import { WORKSHOP_OPTION_PAGE_SIZE, fieldWorkshopOptions } from "@/lib/workshopOptions";
import type {
  Artisan,
  Craft,
  MediaFile,
  MediaType,
  PageResult,
  ProductDocumentation,
  QuestionnaireInterview,
  ToolDocumentation,
  Workshop
} from "@/lib/types";

// ---------------------------------------------------------------------------
// Android parity: mediaLinkModes — the EXACT list + labels of record types a
// miscellaneous-media upload can be linked to (MainActivity `mediaLinkModes`).
// ---------------------------------------------------------------------------

const LINK_TYPES: Array<{ value: string; label: string }> = [
  { value: "artisan", label: "Artisan" },
  { value: "workshop", label: "Workshop" },
  { value: "craft", label: "Craft" },
  { value: "tool", label: "Tool" },
  { value: "product", label: "Product" },
  { value: "process", label: "Process" },
  { value: "questionnaire", label: "Questionnaire" },
  { value: "media", label: "Miscellaneous Media" }
];

const LINK_TYPE_LABEL = new Map(LINK_TYPES.map((t) => [t.value, t.label]));

type ProcessListItem = {
  id: string;
  name: string;
  product?: { productName?: string | null } | null;
  createdAt?: string;
};

function sortRecent<T extends { createdAt?: string }>(items: T[]) {
  // Most recent first, even if a backend list ever changes its default ordering.
  return [...items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
}

/**
 * Android `loadViewEntries` parity: label each entry with its human name/title — AND say how much of
 * the type this page of them is.
 *
 * WHY THE RETURN TYPE GREW. This builds the second dropdown of the upload form, the one that decides
 * WHICH RECORD a file is attached to, and it did so from a single `pageSize: 100` request per type
 * with `total` discarded in all eight branches. 100 is the ceiling `normalize_pagination` clamps to,
 * the lists are ordered newest-first, and the tables behind them are far past it — 2530 media files,
 * 878 products, 749 artisans, 196 workshops, 178 crafts, 177 tools, 177 processes, counted against
 * this repository's Postgres on 2026-08-15. The ComboBox this feeds filters the array it is handed
 * (`components/ui/SearchableSelect`), so its "type to search" affordance searched the newest hundred
 * and nothing else, and the placeholder said "Select an entry" — never "showing 100 of 749".
 *
 * That makes this a WRITE defect rather than a read one. A designer uploading photographs of an
 * artisan entered months ago types the name, gets nothing, and the upload button only requires
 * `linkedType` — so the batch lands attached to the type and no record. It does not appear in that
 * artisan's "Previously uploaded media", does not travel with the record, and has to be repaired
 * later through the relink route.
 *
 * The cut is REPORTED rather than removed, and the difference matters: removing it needs the
 * server's `search=` (which all eight routes accept) threaded out of `SearchableSelect`'s own filter
 * box, and that is a shared primitive this change does not own. A stated cut is the difference
 * between a list that is short and a list that lies; it is not the whole fix, and the follow-up is
 * recorded with the audit.
 */
/**
 * `DropdownOption` and not `{ value, label }`, because one of the eight branches now builds its rows
 * in a shared module that gives them a `hint` and a `group` as well — see the workshop branch. The
 * other seven pass the same two keys they always did; the type simply stops throwing the extra ones
 * away at the door.
 */
type EntryOptions = { options: DropdownOption[]; cut: ListCut | null };

async function loadEntryOptions(type: string): Promise<EntryOptions> {
  const params = { pageSize: LIST_PAGE_CEILING };
  switch (type) {
    case "artisan": {
      const page = await listResource<Artisan>("/artisans", params);
      return {
        options: sortRecent(page.items).map((x) => ({ value: x.id, label: `${x.name} · ${x.place}` })),
        cut: listCut(page, "artisans")
      };
    }
    case "workshop": {
      /*
        THE ONE BRANCH THAT NO LONGER BUILDS ITS OWN ROWS, and the reason is that this dropdown is
        not the only workshop picker a designer meets. It shipped `title` alone, sorted by
        `createdAt`, while the record forms drew `title · date` sorted by occurrence and the funnel
        drew a third shape — so the same workshop read three ways on three screens, and the one
        sorted by creation put a workshop entered last week from a backlog import above the one that
        actually ran yesterday. `fieldWorkshopOptions` is that decision made once: the title in
        `label`, the place and the day in `hint` (searched as well as drawn), the occurrence sort,
        and "Ended" on a workshop whose window has closed — which on THIS screen is worth having,
        because attaching a photograph to a workshop that ended eight months ago is ordinary and
        attaching it to the wrong one of two similarly named visits is not.

        `group: true` for that marking; `offPage: "refuse"` because this is an upload form with no
        stored value to recover — nothing is being edited, so there is no row that is already true
        of a record.

        Its own page size, and not the shared `params`: one number governs the fetch and the render
        for a workshop picker, so the cut this reports is the cut the panel actually draws. The other
        seven branches keep `LIST_PAGE_CEILING` — they are other tables, with their own pass ahead of
        them, and quietly narrowing them here would move seven cuts nobody had asked about.
      */
      const page = await listResource<Workshop>("/workshops", { pageSize: WORKSHOP_OPTION_PAGE_SIZE });
      return {
        options: fieldWorkshopOptions(
          { kind: "ok", rows: page.items, total: page.total },
          { group: true, offPage: { mode: "refuse" } }
        ).options,
        cut: listCut(page, "workshops")
      };
    }
    case "craft": {
      const page = await listResource<Craft>("/crafts", params);
      return {
        options: sortRecent(page.items).map((x) => ({ value: x.id, label: x.place ? `${x.name} · ${x.place}` : x.name })),
        cut: listCut(page, "crafts")
      };
    }
    case "tool": {
      const page = await listResource<ToolDocumentation>("/tools", params);
      return {
        options: sortRecent(page.items).map((x) => ({ value: x.id, label: `${x.toolkitName} · ${x.artisanName}` })),
        cut: listCut(page, "tools")
      };
    }
    case "product": {
      const page = await listResource<ProductDocumentation>("/products", params);
      return {
        options: sortRecent(page.items).map((x) => ({ value: x.id, label: `${x.productName} · ${x.artisanName}` })),
        cut: listCut(page, "products")
      };
    }
    case "process": {
      const page = await listResource<ProcessListItem>("/processes", params);
      return {
        options: sortRecent(page.items).map((x) => ({
          value: x.id,
          label: x.product?.productName ? `${x.name} · ${x.product.productName}` : x.name
        })),
        cut: listCut(page, "processes")
      };
    }
    case "questionnaire": {
      const page = await listResource<QuestionnaireInterview>("/questionnaire/interviews", params);
      return {
        options: sortRecent(page.items).map((x) => ({ value: x.id, label: x.title?.trim() || "Untitled interview" })),
        cut: listCut(page, "interviews")
      };
    }
    case "media": {
      const page = await listResource<MediaFile>("/media", params);
      return {
        options: sortRecent(page.items).map((x) => {
          const tag = x.linkedRecordType?.trim()
            ? x.linkedRecordType.charAt(0).toUpperCase() + x.linkedRecordType.slice(1)
            : null;
          const name = x.originalFilename?.trim() || "Media";
          return { value: x.id, label: [name, x.mediaType, tag].filter(Boolean).join(" · ") };
        }),
        cut: listCut(page, "media files")
      };
    }
    default:
      return { options: [], cut: null };
  }
}

// ---------------------------------------------------------------------------
// Android parity: WorkshopRepository.mediaFilename — the uploaded object name is
// `PREFIX_NamePart_TYPECODE_index_ddMMyyyyHHmmss.ext` built from the linked
// record type and the "Media title / object name" (falling back to caption,
// then the original filename).
// ---------------------------------------------------------------------------

function safeToken(value: string) {
  const cleaned = value.trim().replace(/\s+/g, "").replace(/[^A-Za-z0-9]/g, "").slice(0, 60);
  return cleaned || "Record";
}

function typeCode(mediaType: MediaType) {
  if (mediaType === "IMAGE") return "IMG";
  if (mediaType === "AUDIO") return "AUD";
  if (mediaType === "VIDEO") return "VID";
  return "DOC";
}

function buildObjectName(
  recordType: string,
  title: string | null,
  caption: string | null,
  mediaType: MediaType,
  index: number,
  originalName: string
) {
  const dot = originalName.lastIndexOf(".");
  const extension = dot > 0 ? originalName.slice(dot + 1) : null;
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  const timestamp = `${pad(now.getDate())}${pad(now.getMonth() + 1)}${now.getFullYear()}${pad(now.getHours())}${pad(
    now.getMinutes()
  )}${pad(now.getSeconds())}`;
  const prefix = safeToken(recordType || "MEDIA").toUpperCase();
  const nameSource = title?.trim() || caption?.trim() || (dot > 0 ? originalName.slice(0, dot) : originalName);
  const base = [prefix, safeToken(nameSource), typeCode(mediaType), String(index), timestamp].join("_");
  return extension ? `${base}.${extension}` : base;
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

/** Section id this page's batch publishes under, so the page-level tray can aggregate it. */
const MEDIA_SECTION = "misc-media";
const MEDIA_SECTION_LABEL = "Miscellaneous Media";

export default function MediaPage() {
  return (
    <UploadsProvider>
      <MediaPageBody />
      <UploadTray />
    </UploadsProvider>
  );
}

function MediaPageBody() {
  const confirm = useConfirm();
  const { adminMode } = useAdminView();
  const { user } = useAuth();
  const { addCompleted } = useUploads();
  const [data, setData] = useState<PageResult<MediaFile> | null>(null);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");
  const [transcribingId, setTranscribingId] = useState<string | null>(null);
  /**
   * Which file has its code open, or null.
   *
   * ONE AT A TIME, and by id rather than a flag per row: a page of twenty codes is twenty QR symbols
   * drawn at once for a screen where at most one is being scanned or printed, and the row somebody
   * opened is the file they are working on.
   */
  const [codeFor, setCodeFor] = useState<string | null>(null);

  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  /*
    ── THIS FORM CALLS `formElement.reset()`, AND THAT IS WHY THESE TWO EXIST ──────────────────────

    `reset()` rewrites the DOM nodes and tells React NOTHING. A box whose value React is holding —
    which is every dictated box, because dictation writes from outside the keyboard — is therefore
    re-painted with the PREVIOUS upload's text on the next render, and the researcher's second
    photograph arrives carrying the first one's caption. `DictatedTextInput`'s header states the
    rule; this is the form it was written about.

    TWO MECHANISMS BECAUSE THE TWO COMPONENTS HAVE TWO CONTRACTS, not because one was overlooked.
    `DictatedTextInput` is controlled by its caller, so the title lives here and is cleared in the
    same block as `reset()`. `DictatedTextArea` owns its value and re-seeds on remount, so the
    caption is keyed on a nonce that the same block bumps — which is exactly the
    `key={editing?.id ?? "new"}` shape that component's own doc calls the right one for it.
  */
  const [mediaTitle, setMediaTitle] = useState("");
  const [resetNonce, setResetNonce] = useState(0);
  const [linkedType, setLinkedType] = useState("");
  /*
    THE DESIGN & PROTOTYPE WORKSHOP this loose upload is filed under — "Miscellaneous Media" is one
    of the seven record types the owner named on 2026-08-28.

    IT IS NOT THE SAME QUESTION AS "Linked record type" ABOVE, and the two must not be folded: that
    pair says which RECORD a file is a picture OF, and is what `linkedRecordType` carries; this says
    which workshop a designer FILED it under. A file may legitimately have one, both or neither, and
    `records.media_relation_data` on the server carries the argument for why the column is not
    derived from the tag.

    `null` and not `undefined`: this form only ever uploads, so there is no stored value to protect
    and the picker may always prefill.
  */
  const designWorkshop = useDesignWorkshopSelection(null);
  const [linkedEntryId, setLinkedEntryId] = useState("");
  const [entryOptions, setEntryOptions] = useState<DropdownOption[]>([]);
  /** How much of the chosen record type the entry dropdown holds — see `loadEntryOptions`. */
  const [entryCut, setEntryCut] = useState<ListCut | null>(null);
  const [loadingEntries, setLoadingEntries] = useState(false);
  /**
   * THE READ FOR THIS TYPE DID NOT ANSWER — which is not the same fact as "there are none of them".
   *
   * The catch below already raised the banner at the top of the page, and this control went on
   * reading "No entries for this type" underneath it: a claim about the repository produced by a
   * request that never arrived, and on the workshop branch a claim about which workshops exist.
   * Two sentences on one screen, one of them false, and the false one is the one at the control —
   * which is where a reader looks and which is why the house rule puts the sentence on the control
   * it is about. A banner three inches up does not repair a picker that is still lying.
   *
   * Cleared when the type changes and when an answer lands, so it only ever describes the read this
   * dropdown is currently showing the result of.
   */
  const [entriesFailed, setEntriesFailed] = useState(false);

  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState<BatchProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);

  /**
   * Which fetch is the current one — the counter /artisans, /questionnaires and /design-workshops
   * already carry, now also on /products, /tools and /processes.
   *
   * The 300 ms debounce below is NOT this guard: `clearTimeout` cancels loads that have not fired
   * yet, and does nothing at all to one already in flight. Type a term, wait past the debounce so
   * request A goes out, then press Next — request B goes out beside it, and on a slow link A can
   * answer last.
   *
   * That matters more than a stale table because `<Pagination>` is rendered with `page={data.page}`
   * — the ANSWERED page — while the reload effect depends on `page`, the REQUESTED one. With
   * `data.page === 1` and `page === 2`, Next calls `setPage(data.page + 1)` = `setPage(2)`, React
   * bails on the identical scalar, no dependency changes, no request is issued, and the button is
   * simply dead until Previous is pressed. Audit 2026-08-15 filed that against this file, /products,
   * /tools and /processes.
   *
   * Counted rather than aborted because `listResource` takes no `AbortSignal`; ignoring the late
   * answer is the part that matters. `useRef` is stable across renders, so `load`'s empty dependency
   * array stays correct — do not add the ref to it. Any new `setData`/`setError` inside `load` needs
   * the same guard in front of it.
   */
  const currentLoad = useRef(0);

  const load = useCallback(
    async (pageToLoad: number, term: string) => {
      const generation = (currentLoad.current += 1);
      try {
        const result = await listResource<MediaFile>("/media", {
          page: pageToLoad,
          pageSize: 20,
          search: term || undefined
        });
        if (generation !== currentLoad.current) return;
        setData(result);
        setError(null);
      } catch (err) {
        if (generation !== currentLoad.current) return;
        setError(err instanceof Error ? err.message : "Unable to load media");
      }
    },
    []
  );

  // Live search: debounce keystrokes, reload most-recent-first from page 1.
  useEffect(() => {
    const timer = setTimeout(() => load(page, search), 300);
    return () => clearTimeout(timer);
  }, [page, search, load]);

  // Android parity: when the linked record type changes, load that type's entries
  // for the optional second dropdown.
  useEffect(() => {
    setLinkedEntryId("");
    setEntryOptions([]);
    setEntryCut(null);
    setEntriesFailed(false);
    if (!linkedType) return;
    let cancelled = false;
    setLoadingEntries(true);
    loadEntryOptions(linkedType)
      .then((loaded) => {
        if (cancelled) return;
        setEntryOptions(loaded.options);
        setEntryCut(loaded.cut);
      })
      .catch((err) => {
        if (cancelled) return;
        // Both, and neither instead of the other: the banner says the page hit a problem, and the
        // flag stops the picker below claiming the record type is empty on the strength of it.
        setEntriesFailed(true);
        setError(err instanceof Error ? err.message : `Unable to load ${LINK_TYPE_LABEL.get(linkedType) ?? linkedType} entries`);
      })
      .finally(() => {
        if (!cancelled) setLoadingEntries(false);
      });
    return () => {
      cancelled = true;
    };
  }, [linkedType]);

  /**
   * Android parity: every uploaded object is renamed to `PREFIX_Name_TYPE_index_timestamp.ext`.
   * Renaming the File itself (rather than keeping a parallel name) lets this page share the one
   * resilient upload path in lib/media — per-byte progress, ETA, and a fresh presign per retry.
   */
  function renameForUpload(form: FormData) {
    const title = textValue(form, "mediaTitle");
    const caption = textValue(form, "caption");
    return selectedFiles.map((file, index) => {
      const objectName = buildObjectName(linkedType, title, caption, inferMediaType(file), index + 1, file.name);
      return new File([file], objectName, { type: file.type, lastModified: file.lastModified });
    });
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (selectedFiles.length === 0) {
      setError("Choose or record at least one file first");
      return;
    }
    if (!linkedType) {
      setError("Choose the type of record this media belongs to");
      return;
    }
    setUploading(true);
    setError(null);
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    try {
      const { uploaded, failed } = await uploadMediaBatch({
        files: renameForUpload(form),
        linkedRecordType: linkedType || null,
        linkedRecordId: linkedEntryId || null,
        designWorkshopId: designWorkshop.workshopId || null,
        caption: textValue(form, "caption") ?? undefined,
        location: locationFromForm(form),
        onProgress: setProgress
      });
      setProgress(null);
      // The uploaded files surface twice: as chips under this form and in the page-level tray.
      addCompleted(MEDIA_SECTION, MEDIA_SECTION_LABEL, uploaded);
      // Anything that got through is already in the repository, so refresh the table either way and
      // keep the form (and its selection) intact when part of the batch still needs another attempt.
      load(page, search);
      if (failed.length) {
        // THE CAUSE, NOT ONLY THE CASUALTIES. This branch named the files and stopped, so a designer
        // whose uploads were being refused by the bucket's CORS rule was told which photographs had
        // not landed and nothing whatever about why — which reads as "try again", and trying again
        // was the one thing that could not work. `BatchFailure.error` is the transport layer's own
        // sentence (see `storageTransportSentence` in lib/media.ts); it already distinguishes a lost
        // connection from a bucket refusing this site, so the only thing needed here is to show it.
        // The first failure's reason is enough: a batch either met one refusal or lost the link.
        const reason = failed[0]?.error ? `${failed[0].error} ` : "";
        setError(
          `${failed.length} of ${selectedFiles.length} file(s) failed to upload: ${failed.map((item) => item.name).join(", ")}. ` +
            reason +
            "The rest were saved — remove the ones that landed and upload again."
        );
        return;
      }
      formElement.reset();
      // The two dictated boxes, which `reset()` alone cannot clear. See the note beside their state.
      setMediaTitle("");
      setResetNonce((nonce) => nonce + 1);
      setSelectedFiles([]);
      setLinkedType("");
      setLinkedEntryId("");
    } catch (err) {
      // NOTHING LANDED, AND NOTHING IS DISCARDED EITHER. `selectedFiles` and the form are left exactly
      // as they were — no `formElement.reset()`, no `setSelectedFiles([])` on this path — so the files
      // are still attached and one more press of Upload retries them. That matters most for the case
      // this message now names: a bucket refusing this site fails every file with the record it links
      // to already saved, and clearing the picker here would have destroyed the only handle a designer
      // has on the captures. `MediaBatchError`'s message already carries the reason and the advice
      // clause chosen for that reason, so surfacing it whole is right.
      setError(err instanceof Error ? err.message : "Unable to upload media");
    } finally {
      setUploading(false);
      setProgress(null);
    }
  }

  async function remove(id: string) {
    const ok = await confirm({
      ...deleteConfirm(
        "Remove this media file?",
        // Android's "Permanently delete recording?" says the same thing: the file leaves storage, so
        // there is nothing left to re-link afterwards.
        "This deletes the file from storage and its record from the database. It cannot be undone, and the file can no longer be re-linked.",
        "Any transcript generated from it is deleted with it."
      ),
      confirmLabel: "Remove file"
    });
    if (!ok) return;
    try {
      await apiFetch(`/media/${id}`, { method: "DELETE" });
      await load(page, search);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to remove media file");
    }
  }

  /**
   * Android parity (MainActivity `MediaWithTranscript` -> `TranscribeNowButton`): run this audio
   * file's transcription on the spot, applying the settings-page mode and bypassing both the queue
   * and the off-peak window. The row is patched from the response so the Transcript column updates
   * without a reload.
   *
   * `POST /media/{id}/transcribe-now` is `require_admin` and refuses anything whose `mediaType` is
   * not AUDIO, so the control below is offered on exactly that pair and nothing else.
   */
  async function transcribeNow(item: MediaFile) {
    setTranscribingId(item.id);
    setError(null);
    try {
      const updated = await transcribeMediaNow(item.id);
      setData((current) =>
        current ? { ...current, items: current.items.map((row) => (row.id === updated.id ? updated : row)) } : current
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to transcribe this file right now");
    } finally {
      setTranscribingId(null);
    }
  }

  const canTranscribe = isAdmin(user);

  const uploadLabel = uploading
    ? "Uploading batch..."
    : !linkedType
      ? "Choose a record type"
      : `Upload ${selectedFiles.length || ""} media file${selectedFiles.length === 1 ? "" : "s"}`;

  return (
    <>
      <PageHeader
        title="Miscellaneous Media"
        description="Upload media — images, videos, audio and files go to the same repository backend. Audio is queued for transcription after upload."
        icon={<Images className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <form onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
        <MediaCaptureField
          files={selectedFiles}
          onFilesChange={setSelectedFiles}
          title="Capture media"
          description="Images, videos, audio and files upload to the same repository backend. Audio is queued for transcription after upload."
        />
        <div className="grid gap-3 md:grid-cols-2">
          {/* DICTATED, and Android has been since the `TextInput` default flipped: this is the
              name of the object in the photograph — "Bagru indigo block, 4 inch" — spoken by
              somebody holding both the phone and the object. `MainActivity.kt`'s
              `TextInput("Media title / object name", mediaTitle)` takes no `dictate = false`, so it
              carries a microphone; the web drew a bare box. */}
          <DictatedTextInput
            name="mediaTitle"
            label="Media title / object name"
            value={mediaTitle}
            onChange={setMediaTitle}
            placeholder="Names the uploaded object (optional)"
            explainWhenUnavailable={false}
          />
          {/*
            TWO THINGS WERE WRONG HERE AND ONLY ONE OF THEM WAS VISIBLE.

            THE ASTERISK WAS TYPED INTO THE LABEL STRING — `label="Linked record type *"` — so it was
            the one required mark in the web client that no shared primitive could reach. When the
            ten `{required ? " *" : ""}` sites became `<RequiredMark />` and turned red on
            2026-08-30, this one would have stayed grey, on a screen beside them, looking exactly
            like a field whose mark had been forgotten. `required` on the wrapper is the same fact
            said in a way a component can act on.

            AND THE WRAPPER WAS `Field`, WHICH IS A `<label>`, AROUND A THEMED DROPDOWN. §12.3 of the
            frontend reference forbids exactly this: a `<label>` forwards a stray click to the first
            labelable control inside it, which for a themed dropdown is the trigger button — so a
            click meant for an option also fires the toggle. `FieldBlock` is the `<div>` twin that
            exists for this case, and it names the control through `role="group"` plus the label
            context instead of through label-for.
          */}
          <FieldBlock label="Linked record type" required>
            <Dropdown
              value={linkedType}
              onChange={setLinkedType}
              options={LINK_TYPES}
              placeholder="Choose the type of record"
              ariaLabel="Linked record type"
            />
          </FieldBlock>
        </div>
        {linkedType ? (
          <Field label="Linked entry (optional)">
            <ComboBox
              options={entryOptions}
              value={linkedEntryId}
              onChange={setLinkedEntryId}
              placeholder={
                loadingEntries
                  ? "Loading…"
                  : entriesFailed
                    ? // WHICH KIND OF EMPTY THIS IS. "No entries for this type" is a claim about the
                      // repository and its next move is to go and create the record; this one is a
                      // claim about a request and its next move is to try again. Collapsing them is
                      // how a timeout came to tell somebody a workshop they had just been added to
                      // did not exist — and a file uploaded attached to nothing has to be repaired
                      // through the relink route afterwards.
                      "This list could not be loaded — nothing you have chosen is lost"
                    : entryOptions.length === 0
                      ? "No entries for this type"
                      : entryCut
                        // Naming the number in the placeholder is not decoration: it is the first
                        // thing read by somebody about to type a name into this box, and it is what
                        // stops an empty result being taken as proof the record does not exist.
                        ? `Select one of these ${entryOptions.length}`
                        : "Select an entry"
              }
              name="linkedRecordId"
            />
            <CappedListNotice cuts={[entryCut]} />
          </Field>
        ) : null}
        {/*
          Under the two link controls and above the caption — see the hook for why it is a separate
          question from "Linked record type".
        */}
        <DesignWorkshopSelect state={designWorkshop} saving={uploading} />
        {/*
          THE LAST BARE PROSE BOX ON A WEB RECORD PAGE, dictated 2026-08-28.

          A caption is one sentence describing a photograph, written by somebody standing in front of
          the thing photographed — the case dictation exists for. Android's own media form has had a
          microphone here since the record-form sweep, so this was also the last place the two clients
          disagreed about this control.

          `DictatedTextArea` and not `DictatedTextInput`: a caption runs to a sentence or two, and it
          is the multi-line box on the handset as well. Uncontrolled, exactly like the `<TextArea>` it
          replaces, so `FormData` reads it unchanged.
        */}
        <DictatedTextArea key={resetNonce} name="caption" label="Caption" explainWhenUnavailable={false} />
        {/* Once for the form — two microphones, one grey paragraph where a browser has no
            recogniser. Both boxes above pass `explainWhenUnavailable={false}`. */}
        <DictationUnavailableNotice />
        <LocationFields />
        <UploadProgress progress={progress} sectionId={MEDIA_SECTION} label={MEDIA_SECTION_LABEL} />
        <div>
          <button className="field-button" disabled={uploading || selectedFiles.length === 0 || !linkedType}>
            <Upload className="h-4 w-4" aria-hidden />
            {uploadLabel}
          </button>
        </div>
      </form>

      {/* Directly under the upload form, because it is the form's own promise being answered: the
          card above says "Audio is queued for transcription after upload", and until this panel
          existed nothing on any client said what became of that queue. Below the 20-row media table
          it would need scrolling past to find a failure — which is how they stayed invisible. */}
      <MediaJobsPanel />

      <section className="panel overflow-hidden">
        <div className="border-b border-line-200 p-4">
          <SearchInput
            value={search}
            onChange={(value) => {
              setSearch(value);
              setPage(1);
            }}
            placeholder="Search media by filename, caption, or MIME type"
          />
        </div>
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No media uploaded" body={search ? "Nothing matches this search." : undefined} />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1200px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  {["Preview", "File", "Type", "Size", "Linked record", "Transcript", "Status", "Uploaded", "Actions"].map(
                    (heading) => (
                      <th
                        key={heading}
                        className={`resize-x overflow-hidden px-4 py-3 ${heading === "Actions" ? "text-right" : ""}`}
                        style={{ minWidth: heading === "Preview" ? 160 : 96 }}
                      >
                        {heading}
                      </th>
                    )
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((item) => (
                  <Fragment key={item.id}>
                    <tr>
                      <td className="px-4 py-3">
                        {item.url ? (
                          <div className="w-36">
                            <MediaPreviewTile
                              item={{
                                key: item.id,
                                id: item.id,
                                name: item.originalFilename,
                                mediaType: item.mediaType,
                                mimeType: item.mimeType,
                                sizeBytes: item.sizeBytes,
                                url: item.url,
                                caption: item.caption
                              }}
                              onOpen={() =>
                                setActivePreview({
                                  key: item.id,
                                  id: item.id,
                                  name: item.originalFilename,
                                  mediaType: item.mediaType,
                                  mimeType: item.mimeType,
                                  sizeBytes: item.sizeBytes,
                                  url: item.url,
                                  caption: item.caption,
                                  transcriptStatus: item.transcriptStatus,
                                  transcriptText: item.transcriptText,
                                  transcriptError: item.transcriptError
                                })
                              }
                            />
                          </div>
                        ) : (
                          <span className="text-ink-500">No URL</span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <div className="font-medium text-ink-900">{item.originalFilename}</div>
                        {item.caption ? <div className="max-w-xs truncate text-xs text-ink-500">{item.caption}</div> : null}
                      </td>
                      <td className="px-4 py-3 text-ink-700">{item.mediaType}</td>
                      <td className="px-4 py-3 text-ink-700">{bytes(item.sizeBytes)}</td>
                      <td className="px-4 py-3 text-ink-700">
                        {item.linkedRecordType ? LINK_TYPE_LABEL.get(item.linkedRecordType) ?? item.linkedRecordType : "-"}
                      </td>
                      <td className="px-4 py-3 text-ink-700">
                        {item.transcriptText ? (
                          <details>
                            <summary className="cursor-pointer font-semibold text-field-700">View transcript</summary>
                            <div className="mt-2 max-h-64 min-w-64 overflow-auto rounded-md bg-field-100 p-3">
                              <Markdown text={item.transcriptText} />
                            </div>
                          </details>
                        ) : (
                          <>
                            <div>{item.transcriptStatus ?? "-"}</div>
                            {/* The status alone was the whole of what this cell said, so a transcript
                                that failed read as one word with no cause and no recourse. The reason
                                is stored on the row; print it. */}
                            {item.transcriptError ? (
                              <div className="mt-1 max-w-xs text-xs text-red-700">{item.transcriptError}</div>
                            ) : null}
                          </>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <StatusBadge status={item.status} />
                      </td>
                      <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                      <td className="px-4 py-3 text-right">
                        {/* THREE different gates, on purpose. Delete keeps the page's existing
                            admin-VIEW gate; re-transcribing mirrors its route (`require_admin` +
                            AUDIO) and is not ANDed with the toggle — it is a repair an admin needs
                            whether or not admin chrome is switched on, and it destroys nothing. The
                            code is gated on nothing at all: it shows an opaque reference to a row this
                            person is already reading, and a designer who can see the file but not the
                            tag that opens it is the exact gap the tag exists to close. The "Admin only"
                            line this cell used to fall back to is gone with it — the cell is never
                            empty now. */}
                        <RowActions>
                          <button
                            className={rowAction("neutral", codeFor === item.id ? "bg-surface-50" : undefined)}
                            onClick={() => setCodeFor(codeFor === item.id ? null : item.id)}
                            aria-expanded={codeFor === item.id}
                          >
                            <QrCode className="h-3.5 w-3.5" aria-hidden />
                            {codeFor === item.id ? "Hide code" : "Code"}
                          </button>
                          {canTranscribe && item.mediaType === "AUDIO" ? (
                            <button
                              className={rowAction("edit")}
                              onClick={() => transcribeNow(item)}
                              disabled={transcribingId === item.id}
                              data-testid="media-transcribe-now"
                            >
                              {transcribingId === item.id ? (
                                <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
                              ) : (
                                <AudioLines className="h-3.5 w-3.5" aria-hidden />
                              )}
                              {transcribingId === item.id
                                ? "Transcribing…"
                                : item.transcriptText
                                  ? "Re-transcribe now"
                                  : "Transcribe now"}
                            </button>
                          ) : null}
                          {adminMode ? (
                            <button className={rowAction("danger")} onClick={() => remove(item.id)}>
                              Delete
                            </button>
                          ) : null}
                        </RowActions>
                      </td>
                    </tr>
                    {codeFor === item.id ? (
                      /* An expanded row and not a route, because a media file has no per-record page
                         on the web — `lib/workshopCodeLookup.ts` says so in as many words and lands a
                         scanned M code on the stored object or, when the caller is not entitled to the
                         bytes, on this list. This row is the closest thing a designer opens for ONE
                         file, so it is where the code for one file belongs. Android shows the same
                         card on its own media detail (`RecordCodeSection(..., MEDIA, ...)` in
                         MainActivity); until now the web showed none, and two clients disagreeing
                         about which records have a code is a defect in itself.

                         The title is the caption or, failing that, the filename — the SAME line
                         `workshopCodeLookup` puts on a resolved media hit, so the card a designer
                         prints and the row a scan reports name the file the same way. */
                      <tr className="bg-surface-50">
                        <td className="px-4 py-3" colSpan={9}>
                          <RecordCodeCard
                            recordType="media"
                            id={item.id}
                            title={item.caption?.trim() || item.originalFilename}
                          />
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </>
  );
}
