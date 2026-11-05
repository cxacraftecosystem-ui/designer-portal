"use client";

import { Fragment, useCallback, useEffect, useId, useRef, useState } from "react";
import { AudioLines, Images, Loader2, QrCode, Upload } from "lucide-react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { OpenableCard, OpenableRow, OpenableRowLink } from "@/components/data/OpenableRow";
import {
  HELD_POST_PENDING_FILES,
  HeldPostNotice,
  MEDIA_HELD_LABEL,
  mediaListNotice,
  mediaUploadHold,
  mediaWorkshopIds,
  useHeldPostRefusals,
  writesHeld,
  type HeldPostRefusal
} from "@/components/designworkshop/HeldPostNotice";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { DesignWorkshopSelect, useDesignWorkshopSelection } from "@/components/forms/DesignWorkshopSelect";
import { LIST_PAGE_CEILING, listCut, type ListCut } from "@/components/data/cappedList";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { useListMemory } from "@/components/hooks/useListMemory";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { Markdown } from "@/components/Markdown";
import { TranscriptPreview } from "@/components/transcript/TranscriptPreview";
import { MediaJobsPanel } from "@/components/media/MediaJobsPanel";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { RecordCodeCard } from "@/components/RecordCode";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { chipToggler, FilterBar } from "@/components/questionnaire/FilterBar";
import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { useUrlFilters } from "@/components/questionnaire/useUrlFilters";
import { filtersAsParams, type Filters } from "@/lib/questionnaire/filters";
import { StatusBadge } from "@/components/StatusBadge";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { ComboBox, Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { apiFetch, listResource } from "@/lib/api";
import { listAllPages } from "@/lib/listAllPages";
import { bytes, countNoun, formatDateTime } from "@/lib/format";
import { locationFromForm, textValue } from "@/lib/forms";
import { inferMediaType, transcribeMediaNow, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { isContentAdmin } from "@/lib/permissions";
import { RECORD_VIEW_HREF } from "@/lib/recordView";
import { toolRowLabel } from "@/lib/toolArtisans";
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
  designWorkshopId?: string | null;
  createdAt?: string;
};

function sortRecent<T extends { createdAt?: string }>(items: T[]) {
  return [...items].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
}

type EntryOptions = {
  options: DropdownOption[];
  cut: ListCut | null;
  filings?: ReadonlyMap<string, readonly string[]>;
};

function filedUnder(rows: ReadonlyArray<{ id: string; designWorkshopId?: string | null }>): Map<string, string[]> {
  return new Map(rows.map((row) => [row.id, row.designWorkshopId?.trim() ? [row.designWorkshopId.trim()] : []]));
}

async function loadEntryOptions(type: string): Promise<EntryOptions> {
  const params = { pageSize: LIST_PAGE_CEILING };
  switch (type) {
    case "artisan": {
      const page = await listAllPages<Artisan>("/artisans", params);
      return {
        options: sortRecent(page.items).map((x) => ({ value: x.id, label: `${x.name} · ${x.place}` })),
        cut: listCut(page, "artisans"),
        filings: filedUnder(page.items)
      };
    }
    case "workshop": {
      const page = await listAllPages<Workshop>("/workshops", { pageSize: WORKSHOP_OPTION_PAGE_SIZE });
      return {
        options: fieldWorkshopOptions(
          { kind: "ok", rows: page.items, total: page.total },
          { group: true, offPage: { mode: "refuse" } }
        ).options,
        cut: listCut(page, "workshops")
      };
    }
    case "craft": {
      return { options: [], cut: null };
    }
    case "tool": {
      const page = await listAllPages<ToolDocumentation>("/tools", params);
      return {
        options: sortRecent(page.items).map((x) => ({ value: x.id, label: toolRowLabel(x) })),
        cut: listCut(page, "tools"),
        filings: filedUnder(page.items)
      };
    }
    case "product": {
      const page = await listAllPages<ProductDocumentation>("/products", params);
      return {
        options: sortRecent(page.items).map((x) => ({ value: x.id, label: `${x.productName} · ${x.artisanName}` })),
        cut: listCut(page, "products"),
        filings: filedUnder(page.items)
      };
    }
    case "process": {
      const page = await listAllPages<ProcessListItem>("/processes", params);
      return {
        options: sortRecent(page.items).map((x) => ({
          value: x.id,
          label: x.product?.productName ? `${x.name} · ${x.product.productName}` : x.name
        })),
        cut: listCut(page, "processes"),
        filings: filedUnder(page.items)
      };
    }
    case "questionnaire": {
      const page = await listAllPages<QuestionnaireInterview>("/questionnaire/interviews", params);
      return {
        options: sortRecent(page.items).map((x) => ({ value: x.id, label: x.title?.trim() || "Untitled interview" })),
        cut: listCut(page, "interviews"),
        filings: filedUnder(page.items)
      };
    }
    case "media": {
      const page = await listAllPages<MediaFile>("/media", params);
      return {
        options: sortRecent(page.items).map((x) => {
          const tag = x.linkedRecordType?.trim()
            ? x.linkedRecordType.charAt(0).toUpperCase() + x.linkedRecordType.slice(1)
            : null;
          const name = x.originalFilename?.trim() || "Media";
          return { value: x.id, label: [name, x.mediaType, tag].filter(Boolean).join(" · ") };
        }),
        cut: listCut(page, "media files"),
        filings: new Map(page.items.map((x) => [x.id, mediaWorkshopIds(x)]))
      };
    }
    default:
      return { options: [], cut: null };
  }
}

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
  const { adminMode, canDeleteRecords } = useAdminView();
  const { user } = useAuth();
  const { addCompleted } = useUploads();
  const [data, setData] = useState<PageResult<MediaFile> | null>(null);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");
  const [transcribingId, setTranscribingId] = useState<string | null>(null);
  const [codeFor, setCodeFor] = useState<string | null>(null);

  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [mediaTitle, setMediaTitle] = useState("");
  const [resetNonce, setResetNonce] = useState(0);
  const [linkedType, setLinkedType] = useState("");
  const designWorkshop = useDesignWorkshopSelection(null);
  const [linkedEntryId, setLinkedEntryId] = useState("");
  const [entryOptions, setEntryOptions] = useState<DropdownOption[]>([]);
  const [entryCut, setEntryCut] = useState<ListCut | null>(null);
  const [entryFilings, setEntryFilings] = useState<ReadonlyMap<string, readonly string[]> | null>(null);
  const [loadingEntries, setLoadingEntries] = useState(false);
  const [entriesFailed, setEntriesFailed] = useState(false);

  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState<BatchProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);

  const currentLoad = useRef(0);

  const { filters: takeFilters, setFilters: setTakeFilters, ready: filtersReady } = useUrlFilters();
  const load = useCallback(
    async (pageToLoad: number, term: string, shared: Filters = {}) => {
      const generation = (currentLoad.current += 1);
      try {
        const result = await listResource<MediaFile>("/media", {
          ...filtersAsParams(shared),
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

  const memory = useListMemory("media", { query: search, page }, data !== null);
  const [placeRestored, setPlaceRestored] = useState(false);
  useEffect(() => {
    const remembered = memory.recall();
    if (remembered) {
      setSearch(remembered.query ?? "");
      setPage(remembered.page ?? 1);
    }
    setPlaceRestored(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!placeRestored || !filtersReady) return;
    const timer = setTimeout(() => load(page, search, takeFilters), 300);
    return () => clearTimeout(timer);
  }, [page, search, load, placeRestored, filtersReady, takeFilters]);

  useEffect(() => {
    setLinkedEntryId("");
    setEntryOptions([]);
    setEntryCut(null);
    setEntryFilings(null);
    setEntriesFailed(false);
    if (!linkedType) return;
    let cancelled = false;
    setLoadingEntries(true);
    loadEntryOptions(linkedType)
      .then((loaded) => {
        if (cancelled) return;
        setEntryOptions(loaded.options);
        setEntryCut(loaded.cut);
        setEntryFilings(loaded.filings ?? null);
      })
      .catch((err) => {
        if (cancelled) return;
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

  const canTranscribe = isContentAdmin(user);

  const writable = (item: MediaFile) => adminMode || (canTranscribe && (item.mediaType === "AUDIO" || item.mediaType === "VIDEO"));
  const uploadChosen = designWorkshop.workshopId ? [designWorkshop.workshopId] : [];
  const uploadLinked = linkedType
    ? [
        ...mediaWorkshopIds({ linkedRecordType: linkedType, linkedRecordId: linkedEntryId || null }),
        ...((linkedEntryId && entryFilings?.get(linkedEntryId)) || [])
      ]
    : [];
  const heldFor = useHeldPostRefusals([
    ...(data?.items ?? []).filter(writable).flatMap(mediaWorkshopIds),
    ...uploadChosen,
    ...uploadLinked
  ]);
  const holdOf = (item: MediaFile): HeldPostRefusal => (writable(item) ? heldFor(mediaWorkshopIds(item)) : null);
  const heldNoticeId = useId();
  const uploadHold = mediaUploadHold(heldFor(uploadChosen), heldFor(uploadLinked));
  const uploadNoticeId = useId();

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
    if (writesHeld(uploadHold)) return;
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
      addCompleted(MEDIA_SECTION, MEDIA_SECTION_LABEL, uploaded);
      load(page, search, takeFilters);
      if (failed.length) {
        const reason = failed[0]?.error ? `${failed[0].error} ` : "";
        setError(
          `${failed.length} of ${countNoun(selectedFiles.length, "file")} failed to upload: ${failed.map((item) => item.name).join(", ")}. ` +
            reason +
            "The rest were saved. Remove those, then upload the others again."
        );
        return;
      }
      formElement.reset();
      setMediaTitle("");
      setResetNonce((nonce) => nonce + 1);
      setSelectedFiles([]);
      setLinkedType("");
      setLinkedEntryId("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to upload media");
    } finally {
      setUploading(false);
      setProgress(null);
    }
  }

  async function remove(item: MediaFile) {
    if (writesHeld(holdOf(item))) return;
    const ok = await confirm({
      ...deleteConfirm(
        "Remove this media file?",
        "The file and its transcript are deleted.",
        "This can't be undone."
      ),
      confirmLabel: "Remove file"
    });
    if (!ok) return;
    try {
      await apiFetch(`/media/${item.id}`, { method: "DELETE" });
      await load(page, search, takeFilters);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to remove media file");
    }
  }

  async function transcribeNow(item: MediaFile) {
    if (writesHeld(holdOf(item))) return;
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

  const uploadLabel = uploading
    ? "Uploading batch..."
    : !linkedType
      ? "Choose a record type"
      : `Upload ${selectedFiles.length || ""} media file${selectedFiles.length === 1 ? "" : "s"}`;

  const mediaPreview = (item: MediaFile) =>
    item.url ? (
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
    );

  const mediaActions = (item: MediaFile, hold: HeldPostRefusal) => (
    <RowActions>
      <button
        className={rowAction("neutral", codeFor === item.id ? "bg-surface-50" : undefined)}
        onClick={() => setCodeFor(codeFor === item.id ? null : item.id)}
        aria-expanded={codeFor === item.id}
      >
        <QrCode className="h-3.5 w-3.5" aria-hidden />
        {codeFor === item.id ? "Hide code" : "Code"}
      </button>
      {canTranscribe && (item.mediaType === "AUDIO" || item.mediaType === "VIDEO") ? (
        <button
          className={rowAction("edit")}
          onClick={() => transcribeNow(item)}
          disabled={transcribingId === item.id || writesHeld(hold)}
          aria-describedby={typeof hold === "string" ? heldNoticeId : undefined}
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
      {canDeleteRecords ? (
        <button
          data-danger className={rowAction("danger")}
          onClick={() => remove(item)}
          disabled={writesHeld(hold)}
          aria-describedby={typeof hold === "string" ? heldNoticeId : undefined}
        >
          Delete
        </button>
      ) : null}
      {typeof hold === "string" ? (
        <span className="self-center rounded-md bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800">
          {MEDIA_HELD_LABEL}
        </span>
      ) : null}
    </RowActions>
  );

  return (
    <>
      <PageHeader
        title="Miscellaneous Media"
        description="Images, video, audio and files."
        icon={<Images className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <form onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
        <MediaCaptureField
          files={selectedFiles}
          onFilesChange={setSelectedFiles}
          title="Capture media"
          description="Images, videos, audio and files."
        />
        <div className="grid gap-3 md:grid-cols-2">
          <DictatedTextInput
            name="mediaTitle"
            label="Media title / object name"
            value={mediaTitle}
            onChange={setMediaTitle}
            placeholder="Names the uploaded object (optional)"
            explainWhenUnavailable={false}
          />
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
        {linkedType === "craft" ? (
          <FieldBlock label="Linked entry (optional)">
            <CraftSelect name="linkedRecordId" ariaLabel="Linked craft" value={linkedEntryId} onChange={(id) => setLinkedEntryId(id)} />
          </FieldBlock>
        ) : linkedType ? (
          <Field label="Linked entry (optional)">
            <ComboBox
              options={entryOptions}
              value={linkedEntryId}
              onChange={setLinkedEntryId}
              placeholder={
                loadingEntries
                  ? "Loading…"
                  : entriesFailed
                    ?
                      "Couldn't load this list"
                    : entryOptions.length === 0
                      ? "No entries for this type"
                      : entryCut
                        ? `Select one of these ${entryOptions.length}`
                        : "Select an entry"
              }
              name="linkedRecordId"
            />
            <CappedListNotice cuts={[entryCut]} />
          </Field>
        ) : null}
        <DesignWorkshopSelect state={designWorkshop} saving={uploading} />
        <DictatedTextArea key={resetNonce} name="caption" label="Caption" explainWhenUnavailable={false} />
        <DictationUnavailableNotice />
        <LocationFields />
        <UploadProgress progress={progress} sectionId={MEDIA_SECTION} label={MEDIA_SECTION_LABEL} files={selectedFiles} />
        <HeldPostNotice refusal={uploadHold} id={uploadNoticeId} className="" sayPending />
        <div>
          <button
            className="field-button"
            disabled={uploading || selectedFiles.length === 0 || !linkedType || writesHeld(uploadHold)}
            aria-describedby={typeof uploadHold === "string" ? uploadNoticeId : undefined}
          >
            <Upload className="h-4 w-4" aria-hidden />
            {uploadLabel}
          </button>
        </div>
      </form>

      <MediaJobsPanel />

      <section className="panel overflow-hidden">
        <div className="border-b border-line-200 p-4">
          <SearchInput
            value={search}
            onChange={(value) => {
              setSearch(value);
              setPage(1);
            }}
            placeholder="Search media by filename, caption, or file type"
          />
          <div className="mt-3">
            <FilterBar
              filters={takeFilters}
              onChange={(next) => {
                setTakeFilters(next);
                setPage(1);
              }}
              placeholder=""
              search={false}
              hide={["q", "dateFrom", "dateTo"]}
            />
          </div>
          <HeldPostNotice
            refusal={mediaListNotice((data?.items ?? []).map(holdOf), "deleted or re-transcribed")}
            id={heldNoticeId}
            className="mt-3"
            sayPending
            pendingSentence={HELD_POST_PENDING_FILES}
          />
        </div>
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No media uploaded" body={search ? "Nothing matches this search." : undefined} />
          </div>
        ) : (
          <>
          <ul className="divide-y divide-line-200 md:hidden">
            {data.items.map((item) => {
              const hold = holdOf(item);
              return (
                <li key={item.id}>
                  <OpenableCard href={RECORD_VIEW_HREF.media(item.id)} className="px-4 py-3">
                  <div className="flex items-start gap-3">
                    <div className="shrink-0">{mediaPreview(item)}</div>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <p className="min-w-0 break-words font-medium text-ink-900">
                          <OpenableRowLink href={RECORD_VIEW_HREF.media(item.id)} className="break-all">
                            {item.originalFilename}
                          </OpenableRowLink>
                        </p>
                        <StatusBadge status={item.status} />
                      </div>
                      {item.caption ? <p className="truncate text-xs text-ink-500">{item.caption}</p> : null}
                      <RecordingChips chips={item.questionnaireChips} onChip={chipToggler(takeFilters, setTakeFilters)} filters={takeFilters} className="mt-1" />
                      <p className="mt-1 text-xs leading-5 text-ink-500">
                        {[
                          item.mediaType,
                          bytes(item.sizeBytes),
                          item.linkedRecordType ? LINK_TYPE_LABEL.get(item.linkedRecordType) ?? item.linkedRecordType : null,
                          formatDateTime(item.createdAt)
                        ]
                          .filter(Boolean)
                          .join(" · ")}
                      </p>
                      <TranscriptPreview preview={item.transcriptPreview} text={item.transcriptText} lines={2} className="mt-1" />
                      {item.transcriptText ? (
                        <details data-row-ignore="" className="mt-1 text-sm">
                          <summary className="cursor-pointer font-semibold text-field-700 dark:text-purple-300 pointer-coarse:py-2">
                            View transcript
                          </summary>
                          <div className="mt-2 max-h-64 overflow-auto rounded-md bg-field-100 p-3">
                            <Markdown text={item.transcriptText} />
                          </div>
                        </details>
                      ) : item.transcriptError ? (
                        <p className="mt-1 text-xs text-red-700 dark:text-red-400">{item.transcriptError}</p>
                      ) : null}
                    </div>
                  </div>
                  <div className="mt-3 [&>div]:justify-start">{mediaActions(item, hold)}</div>
                  </OpenableCard>
                  {codeFor === item.id ? (
                    <div className="mx-4 mb-3 rounded-md bg-surface-50 p-3">
                      <RecordCodeCard recordType="media" id={item.id} title={item.caption?.trim() || item.originalFilename} />
                    </div>
                  ) : null}
                </li>
              );
            })}
          </ul>
          <div className="hidden overflow-x-auto md:block">
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
                {data.items.map((item) => {
                  const hold = holdOf(item);
                  return (
                    <Fragment key={item.id}>
                      <OpenableRow href={RECORD_VIEW_HREF.media(item.id)}>
                        <td className="px-4 py-3">
                          {mediaPreview(item)}
                        </td>
                        <td className="px-4 py-3">
                          <OpenableRowLink href={RECORD_VIEW_HREF.media(item.id)} className="break-all">
                            {item.originalFilename}
                          </OpenableRowLink>
                          {item.caption ? <div className="max-w-xs truncate text-xs text-ink-500">{item.caption}</div> : null}
                          <RecordingChips chips={item.questionnaireChips} onChip={chipToggler(takeFilters, setTakeFilters)} filters={takeFilters} className="mt-1 max-w-md" />
                        </td>
                        <td className="px-4 py-3 text-ink-700">{item.mediaType}</td>
                        <td className="px-4 py-3 text-ink-700">{bytes(item.sizeBytes)}</td>
                        <td className="px-4 py-3 text-ink-700">
                          {item.linkedRecordType ? LINK_TYPE_LABEL.get(item.linkedRecordType) ?? item.linkedRecordType : "-"}
                        </td>
                        <td className="px-4 py-3 text-ink-700">
                          <TranscriptPreview preview={item.transcriptPreview} text={item.transcriptText} lines={2} className="mt-1" />
                          {item.transcriptText ? (
                            <details data-row-ignore="">
                              <summary className="cursor-pointer font-semibold text-field-700 dark:text-purple-300">View transcript</summary>
                              <div className="mt-2 max-h-64 min-w-64 overflow-auto rounded-md bg-field-100 p-3">
                                <Markdown text={item.transcriptText} />
                              </div>
                            </details>
                          ) : (
                            <>
                              <div>{item.transcriptStatus ?? "-"}</div>
                              {item.transcriptError ? (
                                <div className="mt-1 max-w-xs text-xs text-red-700 dark:text-red-400">{item.transcriptError}</div>
                              ) : null}
                            </>
                          )}
                        </td>
                        <td className="px-4 py-3">
                          <StatusBadge status={item.status} />
                        </td>
                        <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                        <td className="px-4 py-3 text-right">
                          {mediaActions(item, hold)}
                        </td>
                      </OpenableRow>
                      {codeFor === item.id ? (
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
                  );
                })}
              </tbody>
            </table>
          </div>
          </>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </>
  );
}
