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
 * only when a caller passes `allowedTypes`, and FOUR call sites do: the stage form's media field
 * through its own `ALLOWED_TYPES` map (`components/designworkshop/FieldInput.tsx`, the
 * `ALLOWED_TYPES` constant — named rather than cited by line, because the line this said moved
 * inside the very pass that wrote it — `["IMAGE"]`
 * for IMAGE and IMAGE_LIST, `["AUDIO"]`, `["VIDEO"]`, and deliberately nothing at all for FILE); the
 * questionnaire's audio field (`app/(protected)/questionnaire/page.tsx:999`, `["AUDIO"]`); the
 * designer profile's photograph and signature slots
 * (`components/designers/DesignerProfileForm.tsx`, the photo and signature cards, `["IMAGE"]`); and
 * that same form's `cvMediaId` card, which passes `["PDF", "DOCUMENT", "IMAGE"]` plus an explicit
 * narrow `accept`. For the three wildcard-led lists the rule above
 * is the whole story — `inferMediaType` and the bucket must agree exactly. The CV slot is the shape
 * this paragraph did not anticipate, and the third one below is about that.
 *
 * `documentAccept` IS THE `FILE` FIELD'S ATTACHMENT LIST AND NOT A `DOCUMENT` CLASSIFIER. A FILE
 * field passes no `allowedTypes` at all, so its chooser is these four joined AND `addFiles` filters
 * nothing — every token in this list is admitted whatever `inferMediaType` says about it. That is
 * why `.pdf` belongs here even though `inferMediaType` answers `"PDF"` for `application/pdf`: this
 * is the list a FILE field reaches, `ACCEPT_BY_TYPE.PDF` is the separate narrow slot for a card that
 * takes PDFs and nothing else, and deleting `.pdf` from here in the name of the rule would silently
 * stop every FILE field offering the scanned consent form `ALLOWED_TYPES`' own comment says must stay
 * pickable.
 *
 * SO `ACCEPT_BY_TYPE.DOCUMENT` WAS REACHED FROM A NARROWED CARD, WHICH IS THE ONE CASE THESE
 * BUCKETS WERE NEVER DESIGNED FOR. The CV slot has to name `DOCUMENT` to be allowed a `.docx` at
 * all, because that is what `inferMediaType` answers for one — and naming it HANDED that card the
 * FILE field's ENTIRE attachment list, spreadsheets, `.json` and the two 3D formats included, which
 * `addFiles` then ADMITS rather than drops, since those infer as DOCUMENT too. So the failure is the
 * mirror image of the one the rule above is about: nothing is lost silently, the wrong thing is
 * uploaded and kept in a column called CV. The bucket cannot be trimmed to fix it (previous
 * paragraph), so a card in that position states its own chooser through the `accept` prop and leaves
 * the buckets alone. The CV slot is the caller that needs it; `accept`'s own note carries the whole
 * argument.
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
  sanitise the bytes anywhere.

  THE PRESIGN ROUTE DOES VALIDATE THE TYPE NOW, and this paragraph used to say the opposite
  (2026-09-03). It said "`POST /media/presign` has no allowlist — it signs whatever `mimeType` it is
  handed" and cited two of that file's lines by number; that was the client-side record of a real
  hole, the hole was closed, and the sentence outlived it. `backend/app/api/routes/media.py` now
  gates every presign against an allow-list of media families with a deny-list in front of it — read
  the banner headed "WHAT THE UPLOAD DOORS WILL SIGN", and cite it by that heading rather than by
  line number, which is how this comment came to be wrong in the first place. `text/html`, its
  sandboxed spelling and the XHTML/JavaScript ones are refused outright, so the stored object can no
  longer be a page a browser executes.

  `image/svg+xml` IS DELIBERATELY STILL ACCEPTED THERE, FOR THIS COMMENT'S OWN REASON. The registry
  asks for the file by name — `sketch.lineArtFile`'s help text is "An SVG or vector export, if one
  was produced" — and the banner says so, citing this file: refusing the type would 422 a declared
  field's only answer from every device, and Android's `saveOrQueue` does not queue a 4xx, so the
  refusal would lose the file rather than retry it. The signed `Content-Type` therefore still rides
  out as `image/svg+xml`, the object still comes back down labelled that way, and
  `components/media/MediaLightbox.tsx` still gives it an "Open" control that is a TOP-LEVEL
  NAVIGATION to that URL (`<a href={item.url} target="_blank" rel="noreferrer">`). Rendered through
  `<img src>` the markup is inert; opened in a tab it is a document that executes.

  WHY THE TOKEN STILL STANDS, measured rather than waved through. `public_url_for_key`
  (`backend/app/services/s3.py`) serves media from the configured S3/CDN base, which is a DIFFERENT
  ORIGIN from the app — so what executes cannot read this app's storage or cookies — and the leading
  `image/*` above already made an SVG selectable on every desktop platform that maps the extension,
  so this token widens an existing path rather than opening one. What it deliberately does is
  guarantee reachability on the platforms that could not select one before, so it is a widening and
  is recorded as one.

  AND THE CDN RULE IS NOW THE ONLY REMAINING CONTROL ON THIS TYPE, not a belt over anything: serve
  `image/svg+xml` with `Content-Disposition: attachment`, or with a `Content-Security-Policy:
  sandbox` response header. Either kills the executing-tab case and leaves `<img src>` rendering
  untouched. It read as an optional extra while the deny-list was still to come; the deny-list came,
  and this is the one scriptable type it admits on purpose — so the rule on the distribution is what
  is left rather than a second line of defence. It is one rule on the distribution and not a code
  change, which is why it is named here rather than attempted from a chooser list.
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
/*
  `.odt` ADDED 2026-08-25, one token, for the reason the paragraph above gives for the 3D pair: a box
  was declared and drawn that could not be answered through the chooser.

  Two uploads asked for on that date name a word-processor document as a first-class answer — the
  designer's CV on the profile page, and `surveySummary.surveyDocument` ("the written-up survey")
  — and both help sentences say "PDF, .docx or .odt". OpenDocument text is the direct sibling of the
  `.doc`/`.docx` pair already here, it is what LibreOffice writes by default, and LibreOffice is what
  a great deal of this fieldwork's paperwork is written in. Without the token a designer holding
  `survey.odt` could see the box, read that their format was accepted, open the chooser and find the
  file greyed out.

  NOTHING ELSE IS NEEDED FOR IT. There is no server-side extension or mime allow-list — `media.py`
  stores what the browser reported — `inferMediaType` calls it a DOCUMENT (no `image/`, `video/`,
  `audio/` or `application/pdf` arm matches), `_KIND_WORD` already names DOCUMENT "Document", and the
  handset's `galleryMimeFor` answers the match-anything wildcard for every non-AV field, so Android
  could always attach one. This list was the divergent half, exactly as it was for `.glb`/`.gltf`.

  `.ods`/`.odp` are deliberately NOT here, on the same principle as the wider 3D formats: neither has
  been asked for, and a spreadsheet or a slide deck is not what either of these two boxes is for.
*/
const documentAccept = ".pdf,.txt,.csv,.doc,.docx,.odt,.xls,.xlsx,.json,.glb,.gltf";

const ACCEPT_BY_TYPE: Record<MediaType, string> = {
  IMAGE: imageAccept,
  VIDEO: videoAccept,
  AUDIO: audioAccept,
  PDF: ".pdf",
  DOCUMENT: documentAccept,
  OTHER: ""
};

/**
 * What a narrowed card takes, in the words a designer would use, for the refusal sentence.
 *
 * The plural noun rather than the enum: "this card takes images only" is a sentence, "allowedTypes:
 * IMAGE" is a log line. Keyed on the whole of `MediaType` so a new member cannot be added to the wire
 * type and leave this map answering `undefined` inside a sentence somebody reads.
 */
const KIND_WORD: Record<MediaType, string> = {
  IMAGE: "images",
  VIDEO: "video",
  AUDIO: "audio",
  PDF: "PDF files",
  DOCUMENT: "documents",
  OTHER: "other files"
};

/** "a", "a and b", "a, b and c" — an Oxford-comma-free list, as the rest of this app's copy writes one. */
function listWords(words: string[]): string {
  if (words.length <= 1) return words[0] ?? "";
  return `${words.slice(0, -1).join(", ")} and ${words[words.length - 1]}`;
}

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
  accept,
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
   * THE CHOOSER'S LIST, STATED BY THE CALLER, FOR A CARD THE BUCKETS ABOVE ARE WIDER THAN.
   *
   * It REPLACES the joined `ACCEPT_BY_TYPE` list rather than adding to it. A card whose narrowing
   * the four buckets already describe correctly should omit it, and then nothing about the chooser
   * changes.
   *
   * ── THE DEFECT IT EXISTS FOR ────────────────────────────────────────────────────────────────
   * `allowedTypes` does two jobs at once — it narrows the chooser AND it is the list `addFiles`
   * admits against — and on a card that takes "a PDF, a word-processor document, or a photograph of
   * a printed one" the two pull apart. `inferMediaType` answers `"DOCUMENT"` for a `.docx`, so such
   * a card MUST name `DOCUMENT` to be allowed one at all; naming it hands the chooser
   * `documentAccept`, which is every FILE field's whole attachment list — `.txt`, `.csv`, `.xls`,
   * `.xlsx`, `.json`, `.glb`, `.gltf` — and `addFiles` admits every one of those too, because they
   * infer as DOCUMENT as well. The designer profile's CV slot
   * (`components/designers/DesignerProfileForm.tsx`, the `cvMediaId` capture card — named by its
   * slot and not by a line, because the reference here read `:824` when it was written and the call
   * site had already moved before the end of the same session) is exactly that card, and what it bought
   * was a spreadsheet or a 3D model kept in a column called CV: a format neither help sentence names
   * (`components/designers/profileCopy.ts`, `backend/app/services/stage_definitions.py`) and the
   * handset's own picker cannot even select (`DesignerProfileScreen.kt`'s mime array).
   *
   * ── WHY AN OVERRIDE HERE AND NOT A NARROWER BUCKET ──────────────────────────────────────────
   * Because `documentAccept` is the FILE field's chooser and not a DOCUMENT classifier — the rule at
   * the top of this file. Trimming it there would take those formats away from every FILE field in
   * the registry to fix one card, the 3D model slot they were added for included.
   *
   * ── WHAT IT DOES NOT DO ─────────────────────────────────────────────────────────────────────
   * It does not filter anything. `accept` is a hint the file dialog honours and a DROP ignores
   * completely, so `allowedTypes` remains the only gate: a caller that narrows here still admits
   * whatever its `allowedTypes` admit, and `addFiles` still names what it turned away. Narrowing
   * this without narrowing that buys a better dialog, not a stricter card.
   */
  accept?: string;
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
  /**
   * Files a pick or a drop handed over that this card did NOT attach, and why — see `addFiles`.
   *
   * Two reasons, kept apart because they need different things from the reader: a file this field
   * cannot store at all, and a file that is already in the strip. One list rather than two states so
   * the de-duplication and the pruning are each written once. `key` is `fileKey`'s
   * `name:size:lastModified` — the same identity `mergeFiles` refuses a second copy on, which is what
   * lets an entry be matched against a file attached later.
   */
  const [unattached, setUnattached] = useState<Array<{ key: string; name: string; reason: "type" | "duplicate" }>>([]);
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

  /**
   * The two refusal sentences, derived from the list so a later pick cannot silently rewrite them.
   *
   * The wrong-type one names the kinds this card DOES take, because "not attached" on its own invites
   * the reading that the app is broken; naming the narrowing makes it a refusal a person can act on.
   * `allowedTypes` is what does the filtering, so a card with no narrowing can never reach this
   * sentence — the conditional is for the type checker, and the wording degrades to the plain fact
   * rather than inventing a list it did not read.
   */
  const wrongTypeNames = unattached.filter((entry) => entry.reason === "type").map((entry) => entry.name);
  const duplicateNames = unattached.filter((entry) => entry.reason === "duplicate").map((entry) => entry.name);
  const acceptedKinds = allowedTypes?.length ? listWords(allowedTypes.map((type) => KIND_WORD[type])) : null;
  const wrongTypeNotice = wrongTypeNames.length
    ? `${wrongTypeNames.length} file${wrongTypeNames.length === 1 ? " was" : "s were"} not attached` +
      `${acceptedKinds ? `, because this card takes ${acceptedKinds} only` : ""}: ${wrongTypeNames.join(", ")}.`
    : null;
  const duplicateNotice = duplicateNames.length
    ? `${duplicateNames.length} file${duplicateNames.length === 1 ? " was" : "s were"} already attached, so ` +
      `${duplicateNames.length === 1 ? "it was" : "they were"} not added twice: ${duplicateNames.join(", ")}.`
    : null;

  const imageAllowed = !allowedTypes || allowedTypes.includes("IMAGE");
  const videoAllowed = !allowedTypes || allowedTypes.includes("VIDEO");
  const audioAllowed = !allowedTypes || allowedTypes.includes("AUDIO");

  // "Pick files" accepts everything the field allows; addFiles still filters against allowedTypes.
  // A caller-supplied `accept` REPLACES the join rather than widening it — see the prop's own note
  // for the card that needs it, and for why the buckets themselves cannot be narrowed instead.
  const pickAccept =
    accept ??
    (
      allowedTypes
        ? allowedTypes.map((type) => ACCEPT_BY_TYPE[type])
        : [imageAccept, videoAccept, audioAccept, allowDocuments ? documentAccept : null]
    )
      .filter(Boolean)
      .join(",");

  function addFiles(fileList: FileList | null) {
    if (!fileList) return;
    const picked = Array.from(fileList);
    // Only the NEW files are filtered against allowedTypes — files already added stay untouched.
    const incoming = picked.filter((file) => !allowedTypes || allowedTypes.includes(inferMediaType(file)));
    /*
      WHAT THIS CARD JUST THREW AWAY, NAMED — rule 10, and the one gap this file's own header
      admitted to and left open ("the residual cost … a `video/webm` picked into an AUDIO-only field
      is still dropped silently").

      THE CHOOSER IS NOT THE ONLY DOOR, which is what makes the residual cost bigger than it looks.
      `accept` narrows the file dialog on the platforms that honour it and narrows nothing at all on
      a DROP: drag a folder of a shoot onto an IMAGE-only field and the two clips in it were picked
      up, filtered out here, and never mentioned — which reads as the app losing files rather than
      refusing them, the exact failure the placement rule above exists to prevent. The refusal is
      unavoidable (a field that admits IMAGE cannot store a video), so what was missing was the
      sentence, not the acceptance.

      AND THE ALREADY-ATTACHED ONES TOO. `mergeFiles` de-duplicates on `name:size:lastModified`, so
      re-picking a file that is already in the strip is correctly a no-op — but a designer who picks
      four files and watches one tile appear has no way to tell that from three files having failed.

      IT ACCUMULATES, AND ONE THING ONLY TAKES AN ENTRY OFF IT: the same file being attached for
      real. A later pick landing cleanly does not make the earlier loss untrue, and the sentence
      going away on the next successful act is precisely how the defects this session fixed all
      worked. So the pruning is exact — discard a tile, pick that file again, and the "already
      attached" line about it goes, because it is now false. A wrong-type entry can never be pruned
      this way and should not be: this card will refuse that file for as long as it is narrowed.
      De-duplicated per reason and per file, so picking one rejected folder twice is one fact.
    */
    const wrongType = picked.filter((file) => !incoming.includes(file));
    const alreadyHere = incoming.filter((file) => files.some((held) => fileKey(held) === fileKey(file)));
    const attaching = incoming.filter((file) => !alreadyHere.includes(file));
    if (wrongType.length || alreadyHere.length || attaching.length) {
      setUnattached((current) => {
        const attached = new Set(attaching.map(fileKey));
        const next = current.filter((entry) => !attached.has(entry.key));
        const known = new Set(next.map((entry) => `${entry.reason}:${entry.key}`));
        for (const [reason, list] of [
          ["type", wrongType],
          ["duplicate", alreadyHere]
        ] as const) {
          for (const file of list) {
            const key = fileKey(file);
            if (known.has(`${reason}:${key}`)) continue;
            known.add(`${reason}:${key}`);
            next.push({ key, name: file.name, reason });
          }
        }
        return next;
      });
    }
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
      {/*
        WHAT WAS PICKED AND NOT ATTACHED, DIRECTLY UNDER THE DOOR IT CAME IN BY. A refusal rendered
        somewhere else on a long form is a refusal the reader is not looking at.

        BOTH REGIONS ARE MOUNTED FROM FIRST PAINT: assistive technology announces mutations only
        inside a region that already existed when the page settled, so a `role` put on a paragraph
        that appears with its own first sentence is heard by nobody. `alert` for the files this card
        cannot store (they are lost unless the designer does something with them) and `status` for the
        duplicates (nothing is lost and nothing is needed).

        `sr-only` AND NOT `hidden`, AS A CLASS SWAP ON ONE ELEMENT — the idiom `SubmissionCard` and
        `CustomSectionsEditor` use, and its header spells out the trap: `display: none` takes the
        region out of the accessibility tree, so `empty:hidden` or a conditional mount would undo the
        whole point of mounting early. Empty, each element is absolutely positioned and 1×1, so it
        costs this `grid gap-3` card no row of dead space.

        The triangle is decoration and the sentence carries the whole message, so the refusal survives
        greyscale, colour-blindness and forced colours — amber-100 over amber-800 because those are
        the two rungs of this palette's amber that pair. It stays in the tree while the region is
        silent, which costs nothing: it is `aria-hidden` and 1×1-clipped with everything else.
      */}
      <div
        role="alert"
        aria-live="assertive"
        className={
          wrongTypeNotice ? "flex items-start gap-2 rounded-md bg-amber-100 p-3 text-amber-800" : "sr-only"
        }
      >
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
        <p className="min-w-0 flex-1 text-xs leading-5">{wrongTypeNotice}</p>
      </div>
      <p
        role="status"
        aria-live="polite"
        className={duplicateNotice ? "text-xs leading-5 text-ink-500" : "sr-only"}
      >
        {duplicateNotice}
      </p>
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
