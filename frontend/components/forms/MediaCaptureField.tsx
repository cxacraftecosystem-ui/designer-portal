"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { AlertTriangle, Camera, FolderOpen, Mic, Square, Video } from "lucide-react";

import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { RecordingStrip } from "@/components/media/Waveform";
import {
  findQualityIssues,
  isMeasurableImage,
  measureImageFile,
  type AttachedImage,
  type ImageMeasurement,
  type QualityFinding
} from "@/lib/imageQuality";
import {
  audioExtensionForMimeType,
  computeChecksum,
  inferMediaType,
  pickAudioRecorderMimeType,
  SPEECH_AUDIO_CONSTRAINTS,
  type StageEntry
} from "@/lib/media";
import { useEagerStaging } from "@/lib/uploads";
import type { MediaType } from "@/lib/types";

const imageAccept = "image/*,.jpg,.jpeg,.png,.gif,.webp,.heic,.heif,.tif,.tiff,.bmp,.avif";
const audioAccept = "audio/*,.mp3,.wav,.m4a,.aac,.ogg,.oga,.opus,.webm,.flac,.amr";
const videoAccept = "video/*,.mp4,.mov,.m4v,.webm,.mkv,.avi,.3gp";
const documentAccept = ".pdf,.txt,.csv,.doc,.docx,.xls,.xlsx,.json";

const ACCEPT_BY_TYPE: Record<MediaType, string> = {
  IMAGE: imageAccept,
  VIDEO: videoAccept,
  AUDIO: audioAccept,
  PDF: ".pdf",
  DOCUMENT: documentAccept,
  OTHER: ""
};

function mergeFiles(existing: File[], incoming: File[]) {
  const merged = [...existing];
  incoming.forEach((file) => {
    if (!merged.some((item) => item.name === file.name && item.size === file.size && item.lastModified === file.lastModified)) {
      merged.push(file);
    }
  });
  return merged;
}

/**
 * The same triple `mergeFiles` de-duplicates on, so a file keeps one identity across re-renders.
 *
 * Not the `File` object itself: a few call sites rename a File before saving (`new File([file], …)`),
 * which keeps the bytes and drops the object identity — the same reason `signatureOf` exists in
 * lib/media.ts.
 */
function fileKey(file: File): string {
  return `${file.name}:${file.size}:${file.lastModified}`;
}

/** Per-file transfer wording, mirroring the Android capture screen's attachment rows. */
function stageStatusLabel(entry: StageEntry | null): string | null {
  if (!entry) return null;
  if (entry.status === "ready") return "Uploaded ✓";
  if (entry.status === "error") return entry.error ? `Upload failed — ${entry.error}` : "Upload failed";
  const percent = entry.total > 0 ? Math.floor((entry.loaded * 100) / entry.total) : 0;
  return `Uploading… ${percent}%`;
}

/**
 * Attach-media card, mirroring the Android `MediaCaptureSection`: the same option buttons in the
 * same order — Pick files, Take photo, Record video, Record audio — plus a live waveform while
 * audio records and a tap-to-preview tile grid with per-file remove.
 *
 * Attaching a file starts its upload IMMEDIATELY (Android's eager pre-upload), so the transfer
 * overlaps the time spent filling the form and saving only has to link the finished object. The
 * selected `File[]` still flows to the caller unchanged — `uploadMediaBatch` recognises the files
 * that are already in object storage, so no call site has to know this happened.
 */
export function MediaCaptureField({
  files,
  onFilesChange,
  title = "Attach media",
  description = "Photos, video, audio and files link to this record automatically. Audio is queued for transcription after upload.",
  allowDocuments = true,
  allowedTypes,
  attachedImages
}: {
  files: File[];
  onFilesChange: (files: File[]) => void;
  title?: string;
  description?: string;
  allowDocuments?: boolean;
  allowedTypes?: MediaType[];
  /**
   * Photographs already attached to this record, for duplicate detection.
   *
   * Optional, and the check degrades quietly without it: with no list, a newly chosen photograph is
   * still compared against the others chosen alongside it, which is where the same-shot-twice mistake
   * usually happens. Pass a MEMOISED array — this is an effect dependency, and a fresh array every
   * render would re-run the measurement on every keystroke in a sibling field.
   */
  attachedImages?: AttachedImage[];
}) {
  const [recording, setRecording] = useState(false);
  // The live stream is state, not just a ref, because <Waveform> needs to re-render on it.
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [elapsedMs, setElapsedMs] = useState(0);
  const [dragging, setDragging] = useState(false);
  const [previewItems, setPreviewItems] = useState<PreviewMedia[]>([]);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const elapsedTimerRef = useRef<number | null>(null);
  // Latest files, so async callbacks (recorder.onstop) never append to a stale snapshot.
  const filesRef = useRef(files);
  useEffect(() => {
    filesRef.current = files;
  }, [files]);

  // Eager pre-upload: every attached file starts streaming to object storage right away.
  const staging = useEagerStaging(files, title);

  /**
   * On-device quality findings per attached photograph, and the ones the designer has waved away.
   *
   * `measuredRef` is a cache, not state: measuring is the expensive part and a file's pixels never
   * change, so re-rendering the card (which happens on every progress tick of every upload) must not
   * re-decode a 12 MP JPEG.
   */
  const [findings, setFindings] = useState<Array<{ key: string; name: string; items: QualityFinding[] }>>([]);
  const [dismissed, setDismissed] = useState<string[]>([]);
  const measuredRef = useRef(new Map<string, { measurement: ImageMeasurement; checksum: string | null }>());
  // Serialised, so a parent that rebuilds the array every render does not re-run the effect.
  const attachedKey = (attachedImages ?? []).map((item) => `${item.label}~${item.checksum ?? ""}`).join("|");

  /**
   * Measure every newly attached photograph and work out what is worth saying about it.
   *
   * THE POINT OF DOING THIS HERE is that this is the moment of SELECTION — the designer is still
   * standing in front of the object. Everything downstream of this component (the upload queue, the
   * save, the report) happens somewhere else, hours later, where "that photograph is blurred" is
   * true and useless.
   *
   * NOTHING HERE CAN STOP AN UPLOAD, and the ordering makes that structural rather than a promise:
   * `useEagerStaging` above has already started streaming every one of these files to object storage
   * before this effect runs. A finding is a sentence added to the screen afterwards. There is no code
   * path from a finding to a cancel, a delete or a replace, and there must never be one.
   *
   * Files are measured ONE AT A TIME and published as each finishes, so the first photograph's
   * warning appears while the tenth is still decoding, and two 48 MB bitmaps never coexist.
   */
  useEffect(() => {
    let cancelled = false;
    const images = files.filter(isMeasurableImage);
    if (!images.length) {
      setFindings([]);
      return;
    }

    /** Compare each photograph against the ones chosen BEFORE it, plus whatever is already attached. */
    const publish = () => {
      const priorInThisBatch: AttachedImage[] = [];
      const next: Array<{ key: string; name: string; items: QualityFinding[] }> = [];
      for (const file of images) {
        const key = fileKey(file);
        const entry = measuredRef.current.get(key);
        if (!entry) continue;
        const items = findQualityIssues({
          measurement: entry.measurement,
          checksum: entry.checksum,
          attached: [...(attachedImages ?? []), ...priorInThisBatch]
        });
        priorInThisBatch.push({
          label: file.name,
          checksum: entry.checksum,
          perceptualHash: entry.measurement.perceptualHash
        });
        if (items.length) next.push({ key, name: file.name, items });
      }
      setFindings(next);
    };

    (async () => {
      for (const file of images) {
        const key = fileKey(file);
        if (measuredRef.current.has(key)) continue;
        const measurement = await measureImageFile(file);
        if (cancelled) return;
        if (!measurement) continue;
        // The same SHA-256 the upload computes, so an exact-duplicate match against an already
        // attached row is a comparison of like with like. Null above the hashing ceiling, which the
        // finding logic reads as "unknown" and never as "unique".
        const checksum = await computeChecksum(file);
        if (cancelled) return;
        measuredRef.current.set(key, { measurement, checksum });
        publish();
      }
      if (!cancelled) publish();
    })();

    return () => {
      cancelled = true;
    };
    // `attachedKey` stands in for `attachedImages` so an unmemoised array cannot loop this effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [files, attachedKey]);

  const visibleFindings = useMemo(
    () => findings.filter((entry) => !dismissed.includes(entry.key)),
    [findings, dismissed]
  );

  const imageAllowed = !allowedTypes || allowedTypes.includes("IMAGE");
  const videoAllowed = !allowedTypes || allowedTypes.includes("VIDEO");
  const audioAllowed = !allowedTypes || allowedTypes.includes("AUDIO");

  // "Pick files" accepts everything the field allows; addFiles still filters against allowedTypes.
  const pickAccept = (
    allowedTypes
      ? allowedTypes.map((type) => ACCEPT_BY_TYPE[type])
      : [imageAccept, videoAccept, audioAccept, allowDocuments ? documentAccept : null]
  )
    .filter(Boolean)
    .join(",");

  function addFiles(fileList: FileList | null) {
    if (!fileList) return;
    // Only the NEW files are filtered against allowedTypes — files already added stay untouched.
    const incoming = Array.from(fileList).filter((file) => !allowedTypes || allowedTypes.includes(inferMediaType(file)));
    if (!incoming.length) return;
    onFilesChange(mergeFiles(files, incoming));
  }

  function stopElapsedTimer() {
    if (elapsedTimerRef.current !== null) {
      window.clearInterval(elapsedTimerRef.current);
      elapsedTimerRef.current = null;
    }
  }

  async function startAudioRecording() {
    const liveStream = await navigator.mediaDevices.getUserMedia({ audio: SPEECH_AUDIO_CONSTRAINTS });
    streamRef.current = liveStream;
    chunksRef.current = [];
    // Ask the browser what it can actually record: Safari/iOS produces audio/mp4, so a hardcoded
    // "audio/webm" name and type would lie about the bytes and break playback and transcription.
    const preferredType = pickAudioRecorderMimeType();
    const recorder = new MediaRecorder(liveStream, preferredType ? { mimeType: preferredType } : undefined);
    recorderRef.current = recorder;
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) chunksRef.current.push(event.data);
    };
    recorder.onstop = () => {
      const mimeType = recorder.mimeType || preferredType || "audio/webm";
      const blob = new Blob(chunksRef.current, { type: mimeType });
      const file = new File([blob], `field-recording-${Date.now()}.${audioExtensionForMimeType(mimeType)}`, { type: mimeType });
      onFilesChange([...filesRef.current, file]);
      liveStream.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    };
    const startedAt = Date.now();
    setElapsedMs(0);
    // Only the clock needs a timer now — the bars run on <Waveform>'s own requestAnimationFrame loop,
    // which owns (and tears down) the AudioContext and analyser.
    elapsedTimerRef.current = window.setInterval(() => setElapsedMs(Date.now() - startedAt), 250);
    recorder.start();
    setStream(liveStream);
    setRecording(true);
  }

  function stopAudioRecording() {
    recorderRef.current?.stop();
    stopElapsedTimer();
    setRecording(false);
    setStream(null);
    setElapsedMs(0);
  }

  useEffect(() => {
    return () => {
      stopElapsedTimer();
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, []);

  useEffect(() => {
    const items = files.map((file, index) => ({
      key: `${file.name}-${file.size}-${file.lastModified}-${index}`,
      name: file.name,
      mediaType: inferMediaType(file),
      mimeType: file.type || "unknown MIME",
      sizeBytes: file.size,
      url: URL.createObjectURL(file)
    }));
    setPreviewItems(items);
    return () => {
      items.forEach((item) => {
        if (item.url) URL.revokeObjectURL(item.url);
      });
    };
  }, [files]);

  return (
    <section className="grid gap-3 rounded-lg border border-line-200 bg-card p-4 shadow-sm">
      <div>
        <h3 className="font-display font-bold text-lg text-ink-900">{title}</h3>
        <p className="mt-1 text-sm text-ink-500">{description}</p>
      </div>
      <div
        className={`grid gap-3 rounded-lg border-2 border-dashed p-3 transition ${
          dragging ? "border-purple-600 bg-purple-50" : "border-line-200 bg-surface-50"
        }`}
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          addFiles(event.dataTransfer.files);
        }}
      >
        {/* Android order: Pick files · Take photo · Record video · Record audio. */}
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <label className="file-trigger">
            <FolderOpen className="h-4 w-4" aria-hidden />
            Pick files
            <input className="hidden" type="file" accept={pickAccept || undefined} multiple onChange={(event) => addFiles(event.target.files)} />
          </label>
          {imageAllowed ? (
            <label className="file-trigger">
              <Camera className="h-4 w-4" aria-hidden />
              Take photo
              <input className="hidden" type="file" accept={imageAccept} capture="environment" multiple onChange={(event) => addFiles(event.target.files)} />
            </label>
          ) : null}
          {videoAllowed ? (
            <label className="file-trigger">
              <Video className="h-4 w-4" aria-hidden />
              Record video
              <input className="hidden" type="file" accept={videoAccept} capture="environment" multiple onChange={(event) => addFiles(event.target.files)} />
            </label>
          ) : null}
          {audioAllowed ? (
            !recording ? (
              <button type="button" className="file-trigger" onClick={startAudioRecording}>
                <Mic className="h-4 w-4" aria-hidden />
                Record audio ●
              </button>
            ) : (
              <button type="button" className="file-trigger" onClick={stopAudioRecording}>
                <Square className="h-4 w-4" aria-hidden />
                Stop audio
              </button>
            )
          ) : null}
        </div>
        {recording ? <RecordingStrip stream={stream} elapsedMs={elapsedMs} /> : null}
        <p className="text-xs text-ink-500">
          Drag and drop files here, or use the buttons above. Uploading starts the moment a file is attached — saving
          then only links it — and captured files go up unchanged so embedded EXIF metadata is retained.
        </p>
      </div>
      {previewItems.length ? (
        <div className="grid gap-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm font-semibold text-ink-700">
              {previewItems.length} file{previewItems.length === 1 ? "" : "s"} attached
            </p>
            {/* Android parity wording: "All uploaded ✓ — ready to save". */}
            <p className={`text-xs ${staging.failed ? "text-error-600" : "text-ink-500"}`}>
              {staging.allReady
                ? "All uploaded ✓ — ready to save"
                : staging.failed
                  ? `${staging.failed} upload${staging.failed === 1 ? "" : "s"} failed — retry below, or just save to try again`
                  : `Uploading… ${Math.round(staging.fraction * 100)}% (${staging.ready}/${staging.total} files done)`}
            </p>
          </div>
          {!staging.allReady && !staging.failed ? (
            <div className="h-1.5 overflow-hidden rounded-full bg-line-200" aria-hidden>
              <div
                className="h-full rounded-full bg-purple-700 transition-all"
                style={{ width: `${Math.round(staging.fraction * 100)}%` }}
              />
            </div>
          ) : null}
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {previewItems.map((item, index) => {
              const entry = staging.entries[index] ?? null;
              return (
                <MediaPreviewTile
                  key={item.key}
                  item={item}
                  onOpen={() => setActivePreview(item)}
                  onRemove={() => onFilesChange(files.filter((_, itemIndex) => itemIndex !== index))}
                  removeLabel="Discard"
                  progress={entry && entry.total > 0 ? entry.loaded / entry.total : null}
                  failed={entry?.status === "error"}
                  statusLabel={stageStatusLabel(entry)}
                  onRetry={entry ? () => staging.retry(entry.file) : undefined}
                />
              );
            })}
          </div>
        </div>
      ) : null}
      {/*
        ADVICE, NEVER A BLOCK. Every one of these files is already uploading, and nothing in this
        block can stop it, undo it or swap the file out — there is only a Dismiss, which hides the
        sentence and leaves the photograph exactly where the designer put it. A designer may have
        deliberately photographed something soft, or be holding the only shot of an object that is
        about to be sold, and refusing that would be a far worse failure than any problem named here.

        The wording carries the MEASUREMENT and not just the verdict, because "Blur score 42, sharp
        photographs here score 300+" is something a person can overrule with confidence, and "Image
        may be blurred" is indistinguishable from the app being wrong.
      */}
      {visibleFindings.length ? (
        <div className="grid gap-2">
          {visibleFindings.map((entry) => (
            <div
              key={entry.key}
              data-testid="image-quality-warning"
              className="grid gap-2 rounded-md bg-amber-100 p-3 text-amber-800"
            >
              <div className="flex items-start gap-2">
                {/* The icon is decoration; "Check this photograph" is the part that carries the
                    meaning, so the warning survives greyscale, colour-blindness and forced colours. */}
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-semibold">Check this photograph — {entry.name}</p>
                  <ul className="mt-1 grid gap-1">
                    {entry.items.map((finding) => (
                      <li key={finding.flag} className="text-xs leading-5">
                        {finding.message}
                      </li>
                    ))}
                  </ul>
                </div>
                <button
                  type="button"
                  className="shrink-0 text-xs font-medium underline"
                  onClick={() => setDismissed((current) => [...current, entry.key])}
                >
                  Dismiss
                </button>
              </div>
              <p className="text-xs">This file is uploading anyway. Nothing here stops it being saved.</p>
            </div>
          ))}
        </div>
      ) : null}
      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </section>
  );
}
