"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
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

/**
 * WHAT EACH CHOOSER OFFERS, AND THE RULE THAT KEEPS THE FOUR LISTS HONEST.
 *
 * THE FOUR LISTS ARE THE CHOOSER'S BUCKETS, NOT A CLASSIFICATION OF FILE FORMATS, and the rule is
 * about the first thing rather than the second: a token must sit in a bucket whose `allowedTypes`
 * will ADMIT the file it matches. `addFiles` puts every incoming file through {@link inferMediaType}
 * against the caller's `allowedTypes`, so an extension advertised to a NARROWED field that will then
 * reject it is offered by the operating system's chooser and dropped on the floor with nothing said
 * — which reads to a designer as the app losing their file rather than refusing it.
 *
 * WHICH BUCKETS ARE ACTUALLY NARROWED IS THE PART THAT MAKES THE RULE USABLE. `pickAccept` narrows
 * only when a caller passes `allowedTypes`, and today three callers do: `["IMAGE"]`, `["AUDIO"]`,
 * `["VIDEO"]` (see `ALLOWED_TYPES` in `components/designworkshop/FieldInput.tsx`, the questionnaire's
 * audio field, and `DesignerProfileForm`). So the three wildcard-led lists are the ones the rule
 * bites on, and there `inferMediaType` and the bucket must agree exactly.
 *
 * `documentAccept` IS THE `FILE` FIELD'S ATTACHMENT LIST AND NOT A `DOCUMENT` CLASSIFIER. A FILE
 * field passes no `allowedTypes` at all, so its chooser is these four joined AND `addFiles` filters
 * nothing — every token in this list is admitted whatever `inferMediaType` says about it. That is
 * why `.pdf` belongs here even though `inferMediaType` answers `"PDF"` for `application/pdf`: this
 * is the list a FILE field reaches, `ACCEPT_BY_TYPE.PDF` is a separate narrow slot with no caller,
 * and deleting `.pdf` from here in the name of the rule would silently stop every FILE field
 * offering the scanned consent form `ALLOWED_TYPES`' own comment says must stay pickable.
 *
 * `.webm` IS GENUINELY AMBIGUOUS BY EXTENSION and sits in both `audioAccept` and `videoAccept`,
 * which is the one place the strict form of the rule cannot hold: the container carries either, the
 * chooser only ever sees the extension, and dropping it from one list would hide half of a real
 * format from the field that wants it. The residual cost is the small one it trades for — a
 * `video/webm` picked into an AUDIO-only field is still dropped silently.
 *
 * A token missing from ALL FOUR is a box that cannot be answered at all, since a FILE field's
 * chooser is the join of the four (see `pickAccept`).
 */

/*
  `.svg` IS AN IMAGE HERE BECAUSE `inferMediaType` SAYS SO: `image/svg+xml` starts with `image/`.
  It was already reachable through the leading `image/*` on every platform that maps the extension
  to that type, and naming it is for the platforms that do not — a chooser holding no mapping for
  `.svg` matches neither the wildcard nor any extension token, and the file cannot be selected at
  all. The registry asks for exactly this file: `sketch.lineArtFile`'s help text is "An SVG or
  vector export, if one was produced". `lib/imageQuality`'s `isMeasurableImage` already excludes
  `image/svg+xml` from the blur/duplicate measurement, which is the other half of treating a vector
  as an image without pretending it is a photograph.

  AND THE PART THAT IS NOT A CONVENIENCE: AN SVG IS A SCRIPTABLE DOCUMENT, not an inert raster. It
  can carry `<script>`, an `onload` handler and external references, and this pipeline does not
  sanitise the bytes anywhere. `POST /media/presign` has no allowlist — it signs whatever
  `mimeType` it is handed (`backend/app/api/routes/media.py:188`) and puts it straight on the stored
  object (`"headers": {"Content-Type": payload.mimeType}`, same file, line 199) — so the object comes
  back down labelled `image/svg+xml`, and `components/media/MediaLightbox.tsx` gives it an "Open"
  control that is a TOP-LEVEL NAVIGATION to that URL (lines 345 and 378,
  `<a href={item.url} target="_blank" rel="noreferrer">`). Rendered through `<img src>` the markup is
  inert; opened in a tab it is a document that executes.

  WHY THE TOKEN STILL STANDS, measured rather than waved through. `public_url_for_key`
  (`backend/app/services/s3.py:160`) serves media from the configured S3/CDN base, which is a
  DIFFERENT ORIGIN from the app — so what executes cannot read this app's storage or cookies — and
  the leading `image/*` above already made an SVG selectable on every desktop platform that maps the
  extension, so this token widens an existing path rather than opening one. What it deliberately
  does is guarantee reachability on the platforms that could not select one before, so it is a
  widening and is recorded as one.

  THE CHEAP BELT, IF THE OWNER WANTS IT, is on the bucket/CDN rather than here: serve
  `image/svg+xml` with `Content-Disposition: attachment`, or with a `Content-Security-Policy:
  sandbox` response header. Either kills the executing-tab case and leaves `<img src>` rendering
  untouched. It is one rule on the distribution, not a code change, which is why it is named here
  rather than attempted from a chooser list.
*/
const imageAccept = "image/*,.jpg,.jpeg,.png,.gif,.webp,.heic,.heif,.tif,.tiff,.bmp,.avif,.svg";
const audioAccept = "audio/*,.mp3,.wav,.m4a,.aac,.ogg,.oga,.opus,.webm,.flac,.amr";
const videoAccept = "video/*,.mp4,.mov,.m4v,.webm,.mkv,.avi,.3gp";
/*
  `.glb` AND `.gltf` CLOSE THE ONE SLOT THAT WAS GENUINELY UNREACHABLE. The registry declares
  `prototype.modelFile` as a FILE field labelled "3D model", and a FILE field's chooser is these
  four lists joined — none of which matched a 3D model, and the three wildcards cannot reach one
  either: a browser with no mapping for `.glb` reports an empty type, which is the case
  `lib/media`'s `file.type || "application/octet-stream"` was written for. The box was declared,
  drawn, and unanswerable through the chooser.

  THEY SIT IN THE DOCUMENT LIST RATHER THAN ONE OF THEIR OWN because that list IS the FILE field's
  attachment list (see the rule above), which is the only field type asking for a 3D model, and
  because a file the browser cannot type is in any case what `inferMediaType` calls a DOCUMENT —
  nothing matches its `image/` `video/` `audio/` `application/pdf` arms. There is no 3D member of
  `MediaType` on either side of the wire to promote them to (`lib/types.ts`, `prisma/schema.prisma`),
  and inventing one would be a migration and a registry change for a label.

  WIDER 3D FORMATS ARE DELIBERATELY NOT HERE. `.stl`, `.obj`, `.fbx` and `.usdz` are all plausible
  in a prototyping room and none of them was asked for; glTF is the pair that the web and AR
  pipelines both write, so it makes the slot usable without guessing at a list nobody has been
  consulted about. Each of the others is one token here and no other change anywhere.

  THE HANDSET NEEDS NOTHING FOR EITHER: `DwMediaCapture.kt`'s `galleryMimeFor` answers the
  match-anything wildcard for every field that is not IMAGE/VIDEO/AUDIO, so Android could always
  attach both. This list was the divergent half.
*/
const documentAccept = ".pdf,.txt,.csv,.doc,.docx,.xls,.xlsx,.json,.glb,.gltf";

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
  attachedImages,
  stagingOwnerId,
  "aria-describedby": ariaDescribedBy
}: {
  files: File[];
  onFilesChange: (files: File[]) => void;
  title?: string;
  description?: string;
  allowDocuments?: boolean;
  allowedTypes?: MediaType[];
  /**
   * A stable name for the SURFACE these files belong to, when that surface outlives this card.
   *
   * Forwarded verbatim to {@link useEagerStaging}, whose header carries the whole argument. In one
   * sentence: the eager-upload store deletes an owner's unclaimed objects two seconds after that
   * owner goes away, and a design-workshop collection row unmounts its whole panel when it is
   * collapsed — so with the default per-mount owner, collapsing a row DELETED the photograph that
   * was already in object storage. Callers whose `files` array lives above this component pass a
   * key naming that place; everyone else omits it and behaves exactly as before.
   */
  stagingOwnerId?: string;
  /**
   * Photographs already attached to this record, for duplicate detection.
   *
   * Optional, and the check degrades quietly without it: with no list, a newly chosen photograph is
   * still compared against the others chosen alongside it, which is where the same-shot-twice mistake
   * usually happens. Pass a MEMOISED array — this is an effect dependency, and a fresh array every
   * render would re-run the measurement on every keystroke in a sibling field.
   */
  attachedImages?: AttachedImage[];
  /**
   * The refusal — or the hint — that belongs to this card, bound rather than merely painted.
   *
   * ── THE DEFECT THIS EXISTS FOR ──────────────────────────────────────────────────────────────
   * `ProcessForm` refuses a save when "Pre-processes available" is ticked with nothing attached.
   * It painted a red paragraph WITH AN ID beside this card and there was nothing to bind the id to,
   * because this component accepted no description of any kind. Its own comment said so. A
   * `role="alert"` was the whole of what a screen reader ever received: heard once, when the
   * paragraph appeared, and unreachable afterwards — so a researcher who tabbed back to the card to
   * fix it arrived at a control that said nothing about why they were there.
   *
   * ── WHY IT LANDS ON A `group` AND NOT ON A CONTROL ──────────────────────────────────────────
   * There is no single control to hang it on. This card is a heading, a paragraph, four capture
   * triggers and a tile grid; the refusal is about the CARD ("attach the pre-process media"), not
   * about any one button in it. So the section is given `role="group"`, named by its own heading
   * and described by its own paragraph plus whatever the caller passes — which is what a group is
   * for, and which means the sentence is announced on entering the card rather than only once as it
   * appears. The card's own description is included so that adding a refusal does not silence it.
   *
   * ── WHAT WAS NOT MEASURED, SAID PLAINLY ─────────────────────────────────────────────────────
   * `role="group"` and the two attributes are UNCONDITIONAL, so all twelve call sites changed, not
   * only the one that needed a description — and `ProcessForm` draws one of these cards per step.
   * The trade was reasoned, not heard: naming the group is what makes it describable at all, and
   * `role="group"` rather than a named `<section>` is deliberate, because a named `<section>` is a
   * landmark `region` and twelve landmarks for twelve capture cards would be the louder mistake.
   * What has NOT been put in front of a screen reader is the repeated boilerplate description on a
   * form with several steps; no browser pass was possible here (the suite points at a running dev
   * server and API, and neither is up on this machine). IF IT PROVES LOUD, the narrow fix is to
   * bind only the caller's id — `aria-describedby={ariaDescribedBy}` — and leave this card's own
   * paragraph as plain content it already is, which keeps the refusal bound and costs nothing else.
   * Do not answer it by dropping the group: that puts the refusal back on the floor.
   */
  "aria-describedby"?: string;
}) {
  /*
    `useId` and not a literal: a process form draws one of these cards per step plus the
    pre-process one, so a fixed id would name every card on the screen at once.
  */
  const cardId = useId();
  const headingId = `${cardId}-title`;
  const descriptionId = `${cardId}-description`;
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
  const staging = useEagerStaging(files, title, stagingOwnerId);

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
    /*
      A NAMED, DESCRIBED `group` — see the `aria-describedby` prop for the whole argument. A bare
      `<section>` with no accessible name is a generic container, so `aria-describedby` on it would
      have been dropped by the accessibility tree; naming it with its own heading is what turns it
      into something a description can belong to.
    */
    <section
      role="group"
      aria-labelledby={headingId}
      aria-describedby={ariaDescribedBy ? `${descriptionId} ${ariaDescribedBy}` : descriptionId}
      className="grid gap-3 rounded-lg border border-line-200 bg-card p-4 shadow-sm"
    >
      <div>
        <h3 id={headingId} className="font-display font-bold text-lg text-ink-900">
          {title}
        </h3>
        <p id={descriptionId} className="mt-1 text-sm text-ink-500">
          {description}
        </p>
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
