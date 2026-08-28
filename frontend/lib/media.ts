import { API_BASE, ApiError, apiFetch, getToken, listResource, type ApiFetchOptions } from "@/lib/api";
// "Is this the network?" is asked in ONE place for the whole web client, and this module is not
// exempt from it just because its second leg talks to a bucket instead of to the API. `StorageError`
// is DECLARED there rather than here so the classification can read its `status`: an S3 403 or 413
// used to reach the outbox drain as an unrecognised error, default to "the connection is at fault",
// and be re-thrown for ever under "Still no connection" — the whole jam, through the storage leg.
import { LocalRefusalError, StorageError, triageFailure } from "@/lib/failureTriage";
import type { MediaFile, MediaType } from "@/lib/types";

/**
 * The web media pipeline. Mirrors the tactics the Android app uses (see docs/MEDIA_PIPELINE.md):
 *
 * - **Eager pre-upload** — bytes start moving to object storage the moment a file is attached, so the
 *   slow transfer overlaps the minutes the researcher spends filling the form and saving is instant.
 * - **Multipart above a size threshold** — big videos go up in parts, each retried independently,
 *   with the whole upload aborted (never left half-written) on failure.
 * - **Safe-request retry** — presign / multipart-setup / complete are re-issued on 502/503/504 and on
 *   anything that never reached a server at all; record-creating calls are never auto-retried. The
 *   classification is `lib/failureTriage.ts`'s, not this module's; the NARROWING (no 429, no 500 in a
 *   tight four-attempt loop) is this module's and is argued at {@link isRetriableApiFailure}.
 * - **Idempotent completion** — `/media/complete` de-duplicates on `objectKey`, so a retried finish
 *   returns the row the first attempt created instead of duplicating it.
 * - **Orphan cleanup** — every staged object is journalled; discarding a file, closing the page, or
 *   simply never saving the record all end with the object being deleted rather than lingering in S3.
 * - **One naming scheme** — every upload is renamed here, by the one implementation below, so no two
 *   capture screens can drift into naming the same kind of file differently.
 */

export function inferMediaType(file: File): MediaType {
  if (file.type.startsWith("image/")) return "IMAGE";
  if (file.type.startsWith("video/")) return "VIDEO";
  if (file.type.startsWith("audio/")) return "AUDIO";
  if (file.type === "application/pdf") return "PDF";
  return "DOCUMENT";
}

// ---------------------------------------------------------------------------
// Nomenclature — the only place a stored file gets its name
// ---------------------------------------------------------------------------

/**
 * Every file this app uploads is named
 *
 *     {RecordType}-{RecordName}-{Descriptor}-{ddMMyyyyHHmm}.{ext}
 *
 *     Artisan-Giriraj-Prasad-Chhipa-Interview-Section-D-Answer-3-010720261824.wav
 *     Product-Bagru-Block-Print-Photo-2-200620261153.jpg
 *     Process-Dabu-Printing-Step-2-Dyeing-Video-1-210620261430.mp4
 *
 * because the researcher reads that name with the folder gone — out of an extracted zip, in a
 * downloads list, on a laptop that never saw this app. `D_SEC_GIRIRAJ_001046_010720261824.wav`
 * identified a file to the screen that wrote it and to nobody else: it says nothing about which of
 * several Girirajs spoke, or which part of the interview this is. So the name itself has to answer
 * what kind of record the file hangs off, which record, what the file is, and when it was taken.
 *
 * This is the browser half of backend/app/services/media_naming.py, which rebuilds the same name at
 * read time from the row for everything already uploaded. The two must agree — the character rule,
 * the word vocabulary and the byte budget below are that module's, deliberately, not a second
 * opinion — so move them together when either side moves.
 */

/** The two path separators plus the punctuation Windows reserves. */
const UNSAFE_NAME_CHARS = new Set('<>:"/\\|?*'.split(""));
/**
 * Cc control characters; Cf invisible format characters (which include the bidi overrides that can
 * render a filename back-to-front); Cs lone surrogates, which no encoder will take.
 */
const UNSAFE_CATEGORIES = /[\p{Cc}\p{Cf}\p{Cs}]/u;
/**
 * ...except the zero-width joiner and non-joiner, which are Cf but LOAD-BEARING in Devanagari and
 * other Indic scripts, where they select conjunct and half forms. Dropping them misspells names.
 * Spelled by code point because a character that is invisible in source is a character an editor,
 * a formatter or a careless copy-paste will silently eat.
 */
const KEEP_FORMAT = new Set([String.fromCharCode(0x200c), String.fromCharCode(0x200d)]);
/**
 * A letter, a digit or a combining mark — anything else is a word boundary. Marks are kept
 * deliberately: they are not alphanumeric, but they are half of the syllable they sit on, and a
 * splitter that treated them as separators would shatter every Indic word into loose consonants.
 */
const NAME_WORD_CHAR = /[\p{L}\p{N}\p{M}]/u;
/** Windows refuses these device names in any case, with or without an extension. */
const RESERVED_NAMES = new Set([
  "CON",
  "PRN",
  "AUX",
  "NUL",
  ...Array.from({ length: 9 }, (_, i) => `COM${i + 1}`),
  ...Array.from({ length: 9 }, (_, i) => `LPT${i + 1}`)
]);

/**
 * The whole leaf, in characters and in BYTES — a filesystem counts bytes, and one Devanagari
 * character costs three of them, so a name that passes a length check in the browser can still be
 * refused on write. 200 leaves room under the 255-byte limit for the `-2` a duplicate name picks up
 * on the way out of an export.
 */
const MAX_NAME_CHARS = 150;
const MAX_NAME_BYTES = 200;
/** Enough of a record name to identify it; the rest of the budget belongs to the descriptor. */
const MAX_RECORD_NAME_CHARS = 60;
/** A step name is free text a researcher typed and runs to a sentence more often than not. */
const MAX_STEP_NAME_CHARS = 32;

const nameEncoder = new TextEncoder();

function byteLength(value: string): number {
  return nameEncoder.encode(value).length;
}

/** Drop only the characters a filesystem or a zip genuinely cannot carry, in any script. */
export function safeNameChars(value: string | null | undefined): string {
  let out = "";
  for (const ch of value ?? "") {
    if (UNSAFE_NAME_CHARS.has(ch)) continue;
    if (UNSAFE_CATEGORIES.test(ch) && !KEEP_FORMAT.has(ch)) continue;
    out += ch;
  }
  return out;
}

/** Trim to both limits at once, never mid-character. */
function clipName(value: string, maxChars: number, maxBytes: number): string {
  // Code points, not UTF-16 units: slicing by index can cut a surrogate pair in half.
  const chars = Array.from(value).slice(0, maxChars);
  while (chars.length > 0 && byteLength(chars.join("")) > maxBytes) chars.pop();
  return chars.join("");
}

/**
 * {@link clipName}, but cutting back to a whole word when it has to cut at all.
 *
 * This trims already-hyphenated text, so a blind slice leaves "Cane-Bamboo-an" — a fragment that
 * reads as a word the record does not contain. Dropping the partial word says less and says nothing
 * false. A single very long word has no boundary to retreat to, and there the blind cut stands.
 */
function clipWords(value: string, maxChars: number, maxBytes: number): string {
  const clipped = clipName(value, maxChars, maxBytes);
  if (clipped === value) return value;
  const cut = clipped.lastIndexOf("-");
  return (cut > 0 ? clipped.slice(0, cut) : clipped).replace(/^-+|-+$/g, "");
}

function trimNameEnds(value: string): string {
  return value.replace(/^[-. ]+/, "").replace(/[-. ]+$/, "");
}

/**
 * Words joined by hyphens, with every script intact: "Cane, Bamboo and Block Printing" reads
 * "Cane-Bamboo-and-Block-Printing", and "गिरीराज प्रसाद छीपा" keeps its characters and simply gains
 * hyphens. Idempotent, so a descriptor that is already hyphenated passes through unchanged.
 */
export function hyphenateName(value: string | null | undefined): string {
  let out = "";
  for (const ch of safeNameChars(value)) {
    out += NAME_WORD_CHAR.test(ch) || KEEP_FORMAT.has(ch) ? ch : "-";
  }
  return out.replace(/-{2,}/g, "-").replace(/^-+|-+$/g, "");
}

/**
 * The capture moment as `ddMMyyyyHHmm` — 010720261728 is 1 July 2026, 17:28.
 *
 * Local time, because the researcher matches these names against their own field notes and their own
 * clock, not against UTC.
 *
 * Twelve digits, never fourteen. Seconds would make every name a different length depending on which
 * screen wrote it, and the backend now cuts them off the older uploads that carry them so that one
 * precision reads across the whole repository. Two files of one minute are separated instead by the
 * `-2` the export appends — see `unique_name` in backend/app/services/media_naming.py.
 */
export function mediaTimestamp(at: Date = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${pad(at.getDate())}${pad(at.getMonth() + 1)}${at.getFullYear()}${pad(at.getHours())}${pad(at.getMinutes())}`;
}

/** What each kind of file is called in a name. Plain words, never the app's IMG/VID/AUD codes. */
const KIND_WORD: Record<MediaType, string> = {
  IMAGE: "Photo",
  VIDEO: "Video",
  AUDIO: "Audio-Note",
  PDF: "Document",
  DOCUMENT: "Document",
  OTHER: "File"
};

export function mediaKindWord(subject: File | MediaType): string {
  return KIND_WORD[typeof subject === "string" ? subject : inferMediaType(subject)] ?? "File";
}

/** 1-based, whole, and never zero — the index is half of what keeps two names apart. */
function positiveIndex(index: number | null | undefined): number {
  const value = Math.trunc(index ?? 0);
  return value > 0 ? value : 1;
}

/**
 * `Photo-2`, `Video-1`, `Audio-Note-3`, `Document-1` — what a file is when it is simply one of a
 * record's captures. The index is its place among that record's media of every kind, so it must
 * continue past the files already attached rather than restart at 1 on a second save; that
 * continuation, with the minute stamp, is what keeps two names apart in the first place, before the
 * export's `-2` has to.
 */
export function describeMedia(subject: File | MediaType, index: number): string {
  return `${mediaKindWord(subject)}-${positiveIndex(index)}`;
}

/**
 * `Interview-Section-K-Answer-1` for a clip answering one question, `Interview-Section-D` for a
 * recording covering a whole section — which is not an answer to any single question, so it claims
 * no answer number. A clip that names no section at all falls back to what it plainly is.
 */
export function describeInterviewAudio({
  sectionCode,
  answerNumber,
  subject,
  index
}: {
  sectionCode: string | null | undefined;
  answerNumber?: number | null;
  subject: File | MediaType;
  index: number;
}): string {
  const section = hyphenateName(sectionCode).toUpperCase();
  if (!section) return `Interview-${describeMedia(subject, index)}`;
  if (answerNumber && answerNumber > 0) return `Interview-Section-${section}-Answer-${Math.trunc(answerNumber)}`;
  return `Interview-Section-${section}`;
}

/**
 * `Grid-Measurement-Length-Breadth` / `Grid-Measurement-Height`. A grid photo whose axis the screen
 * did not record gets the bare `Grid-Measurement` rather than a guess that would be wrong half the
 * time — a name that says less is still useful, a name that says the wrong thing is not.
 */
export function describeMeasurementGrid(axis: "lengthBreadth" | "height" | null | undefined): string {
  if (axis === "lengthBreadth") return "Grid-Measurement-Length-Breadth";
  if (axis === "height") return "Grid-Measurement-Height";
  return "Grid-Measurement";
}

/**
 * `Step-2-Dyeing-Video-1` — the step's number and the name the researcher gave it, then what the
 * file is. The step name is the one piece of free text in a descriptor, so it is clipped to a whole
 * word: a step called "mixing lime and gum arabic overnight" contributes as much as it can.
 */
export function describeProcessStep({
  stepNumber,
  stepName,
  subject,
  index
}: {
  stepNumber?: number | null;
  stepName?: string | null;
  subject: File | MediaType;
  index: number;
}): string {
  const head = stepNumber && stepNumber > 0 ? `Step-${Math.trunc(stepNumber)}` : "Step";
  const name = clipWords(hyphenateName(stepName), MAX_STEP_NAME_CHARS, MAX_STEP_NAME_CHARS * 3);
  return [head, name, describeMedia(subject, index)].filter(Boolean).join("-");
}

/** `Pre-Process-Video-1` — a capture of what happens before the documented process begins. */
export function describePreProcess(subject: File | MediaType, index: number): string {
  return `Pre-Process-${describeMedia(subject, index)}`;
}

/** What a file is named after: the record it hangs off, what it is, and when it was captured. */
export type MediaNameSpec = {
  /** The word that opens the name: Artisan, Product, Tool, Process, Workshop, Craft. */
  recordType: string;
  /** That record's own name, as stored — the artisan, the product, the process. */
  recordName: string;
  /** What this file IS, from the vocabulary above. */
  descriptor: string;
  /** Capture time; defaults to now, which is when a browser upload is being named. */
  at?: Date;
};

function extensionOf(filename: string): string {
  const dot = filename.lastIndexOf(".");
  return dot > 0 ? safeNameChars(filename.slice(dot)) : "";
}

/**
 * The name for one file, or "" when the pieces yield nothing nameable.
 *
 * The budget is spent on the record name and nothing else. The descriptor and the timestamp are
 * precisely what tells one artisan's forty clips apart, so they are never trimmed; the record name
 * absorbs the whole shortfall, and if the tail alone has eaten the budget the head goes entirely —
 * leaving a name that says less about which record this belongs to but still says exactly which
 * file it is.
 */
export function mediaFileName(file: File, spec: MediaNameSpec): string {
  const extension = extensionOf(file.name);
  const tail = [hyphenateName(spec.descriptor), mediaTimestamp(spec.at)].filter(Boolean).join("-");
  const budget = MAX_NAME_BYTES - byteLength(`-${tail}${extension}`);

  let head = "";
  if (budget > 0) {
    const name = clipWords(hyphenateName(spec.recordName), MAX_RECORD_NAME_CHARS, budget);
    // Re-clip the pair: a long type word plus a short name can still overrun.
    head = clipWords([hyphenateName(spec.recordType), name].filter(Boolean).join("-"), MAX_NAME_CHARS, budget);
  }

  let stem = trimNameEnds(
    [head, tail]
      .filter(Boolean)
      .join("-")
      .replace(/-{2,}/g, "-")
  );
  if (!stem) return "";
  // CON, PRN, LPT1 ... are refused by Windows with or without an extension.
  if (RESERVED_NAMES.has(stem.split(".")[0].toUpperCase())) stem = `${stem}_`;
  return trimNameEnds(clipName(stem, MAX_NAME_CHARS, MAX_NAME_BYTES - byteLength(extension))) + extension;
}

/**
 * The same file under its archive name, ready to hand to any upload path in this module.
 *
 * Renaming the File itself (rather than carrying a parallel name) is what lets every capture screen
 * share the one resilient upload path — and it is safe for the eager pre-upload, whose fallback
 * match is on size + last-modified + MIME, all of which a rename preserves (see `signatureOf`).
 */
export function renameMediaFile(file: File, spec: MediaNameSpec): File {
  const name = mediaFileName(file, spec);
  // Nothing nameable — keep what the device called it rather than upload a file with no name at all.
  if (!name) return file;
  return new File([file], name, { type: file.type, lastModified: file.lastModified });
}

const UPLOAD_MAX_ATTEMPTS = 3;

/** Gateway codes that mean "the origin was too slow", not "the request was bad" (Android parity). */
const RETRIABLE_GATEWAY_CODES = new Set([502, 503, 504]);
const SAFE_RETRY_ATTEMPTS = 4;

/**
 * Files at/under this size go up as one PUT; larger ones switch to an S3 multipart upload that the
 * server stitches back into a single object. 64 MiB matches Android's `MULTIPART_THRESHOLD`.
 */
export const MULTIPART_THRESHOLD = 64 * 1024 * 1024;
const PART_CONCURRENCY = 3;
const PART_MAX_ATTEMPTS = 3;

/**
 * How many files of one batch transfer at once. Three saturates a rural link far better than the
 * strict sequence this used to run, while staying low enough that no single upload is starved into
 * the stall watchdog below (which is exactly what a high fan-out does on a bad connection).
 */
export const UPLOAD_CONCURRENCY = 3;

/**
 * Above this, the SHA-256 content hash is skipped: `crypto.subtle.digest` needs the whole file in
 * memory at once, and a 300 MB video would cost more than the corruption check is worth.
 */
const CHECKSUM_MAX_BYTES = 32 * 1024 * 1024;

/**
 * The transfer is killed only when it STOPS MOVING, never on a flat deadline. A fixed five-minute
 * timeout dooms a large video on a slow link and then burns every retry doing it again; a stall
 * watchdog lets a slow-but-alive upload run for as long as it needs and still kills a dead socket
 * quickly. Once the last byte is handed to the socket we are waiting on S3 to finalise the object,
 * which produces no progress events at all — hence the longer second window.
 */
const STALL_TIMEOUT_MS = 60_000;
const FINALIZE_TIMEOUT_MS = 5 * 60_000;

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// Ordered by preference. Safari/iOS cannot record WebM at all (it produces audio/mp4), so hardcoding
// "audio/webm" there yields a file whose extension and MIME lie about its contents — which breaks
// both playback and server-side transcription. Ask MediaRecorder what it actually supports instead.
const AUDIO_RECORDER_MIME_TYPES = [
  "audio/webm;codecs=opus",
  "audio/webm",
  "audio/mp4;codecs=mp4a.40.2",
  "audio/mp4",
  "audio/ogg;codecs=opus",
  "audio/ogg"
];

/** The best container this browser can record, or null to let MediaRecorder pick its own default. */
export function pickAudioRecorderMimeType(): string | null {
  if (typeof MediaRecorder === "undefined" || typeof MediaRecorder.isTypeSupported !== "function") return null;
  return AUDIO_RECORDER_MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type)) ?? null;
}

/**
 * Capture constraints for a spoken-word recording — the web's half of the noise handling Android
 * already does in `createAudioRecorder`, which asks the platform for the `VOICE_RECOGNITION` source
 * to get voice pre-processing tuned for clean speech.
 *
 * Both web recorders used to pass a bare `{ audio: true }`, which accepts whatever the browser
 * happens to default to — and those defaults vary by browser and by device. So one client was
 * suppressing noise and the other was recording raw. What these fields capture is interviews in
 * working workshops, over looms, hammering and open courtyards, and the audio is fed to
 * speech-to-text: background noise is transcription accuracy, not a cosmetic concern.
 *
 * Every flag is named explicitly rather than left to a default, because absent is not the same as
 * off and because naming them says what the recording is for. `autoGainControl` earns its place
 * here in particular — an artisan working at a bench sits further from the microphone than a caller
 * does, and without it their voice arrives quiet underneath the workshop.
 *
 * Plain (non-`exact`) constraints on purpose: a browser that cannot honour one ignores it and still
 * returns a stream, whereas `exact` would reject the request outright and leave the researcher
 * unable to record at all — far worse than a noisier file.
 *
 * Exported as a shared constant because two screens record audio (the media capture field and the
 * questionnaire) and a second copy is how the two drift apart.
 */
export const SPEECH_AUDIO_CONSTRAINTS: MediaTrackConstraints = {
  noiseSuppression: true,
  echoCancellation: true,
  autoGainControl: true,
  channelCount: 1,
  sampleRate: 44_100
};

/** File extension for a recorded blob's REAL mime type (recorder.mimeType), never a guess. */
export function audioExtensionForMimeType(mimeType: string | null | undefined): string {
  const type = (mimeType ?? "").toLowerCase();
  if (type.includes("webm")) return "webm";
  if (type.includes("mp4") || type.includes("m4a") || type.includes("aac")) return "m4a";
  if (type.includes("ogg") || type.includes("opus")) return "ogg";
  if (type.includes("wav")) return "wav";
  if (type.includes("mpeg") || type.includes("mp3")) return "mp3";
  return "webm";
}

// ---------------------------------------------------------------------------
// Transport primitives
// ---------------------------------------------------------------------------

/** Raised when S3 answered a part PUT without an ETag the browser is allowed to read. */
class EtagUnavailableError extends Error {}

/**
 * May {@link apiRetry} send this SAFE request again, right now, inside the same call?
 *
 * ── DELIBERATELY NARROWER THAN "IS IT WORTH RETRYING", AND THE DIFFERENCE IS THE POINT ──────────
 *
 * This is a TIGHT loop — four attempts, 800ms apart, while a researcher watches a progress bar — and
 * not the outbox, which retries on the next connection an hour later. The two want different sets,
 * and this function used to express that by holding its own private copy of the network rules
 * (`Set([502, 503, 504])` plus `error instanceof TypeError`). That copy was the sixth answer to "is
 * this the network" in a repository whose whole defect class is answers that disagree, and it was the
 * one no sweep could see, because `Set.has(...)` does not look like `status === 408`.
 *
 * So the CLASSIFICATION comes from `lib/failureTriage.ts` now and only the NARROWING lives here:
 *
 *   • nothing answered at all — a `fetch` TypeError, DNS, a dropped connection, a 408 from a proxy
 *     saying the request never completed — is repeated. Nothing was decided, and every call this
 *     wraps (presign, multipart create/abort, `/media/complete`) is safe to repeat by construction.
 *   • of the answers, only the GATEWAY codes are repeated: 502/503/504 mean the origin was too slow
 *     or is being deployed, which is what an 800ms wait is for. Android's OkHttp interceptor uses the
 *     same three, which is where they came from.
 *   • A 429 IS NOT REPEATED HERE, and that is the one place this must differ from the drains. The
 *     server has explicitly asked for time; four requests in three seconds is the opposite of giving
 *     it any, and on a metered rural connection it is somebody's data being spent to be refused
 *     again. The outbox's `underlyingIsTransient` keeps the work and comes back later, which is the
 *     right answer at that timescale and the wrong one at this.
 *   • A 500 IS NOT REPEATED HERE either: it is a fault in the request's own handling rather than a
 *     gateway being slow, so an immediate repeat reproduces it. The batch result still carries it
 *     out to the drain, which keeps the file and tries again on the next connection.
 */
function isRetriableApiFailure(error: unknown): boolean {
  const { kind, status } = triageFailure(error);
  if (kind === "unreachable") return true;
  return status !== null && RETRIABLE_GATEWAY_CODES.has(status);
}

/**
 * `apiFetch` with the retry policy Android's OkHttp interceptor applies — but only ever used for
 * calls that are SAFE to repeat: presigning, multipart setup/abort, and `/media/complete` (which the
 * API de-duplicates on `objectKey`). Record-creating calls stay single-shot.
 *
 * `options` is `apiFetch`'s own third argument, carried through unchanged so a caller that must not
 * navigate on a 401 can say so — see {@link ObjectUploadOptions.redirectOn401}. It is deliberately
 * placed BEFORE `attempts`, which no call site has ever passed, so the flag is the argument in the
 * position a reader looks at.
 */
async function apiRetry<T>(
  path: string,
  init: RequestInit,
  options: ApiFetchOptions = {},
  attempts = SAFE_RETRY_ATTEMPTS
): Promise<T> {
  let lastError: unknown;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await apiFetch<T>(path, init, options);
    } catch (error) {
      lastError = error;
      if (attempt >= attempts || !isRetriableApiFailure(error)) throw error;
      // Linear-ish backoff with a hard cap, so a struggling origin gets air without long stalls.
      await delay(Math.min(4_000, 600 * attempt));
    }
  }
  throw lastError;
}

/**
 * PUT a blob to object storage with real byte-level progress, via XHR (fetch cannot report upload
 * progress). Returns the ETag when the response exposes one — S3 multipart completion needs it.
 */
function putBlob({
  url,
  headers,
  body,
  onProgress,
  signal
}: {
  url: string;
  headers?: Record<string, string>;
  body: Blob;
  onProgress?: (loaded: number, total: number) => void;
  signal?: AbortSignal;
}): Promise<{ etag: string | null }> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new StorageError("Upload cancelled"));
      return;
    }
    const total = body.size;
    const xhr = new XMLHttpRequest();
    let stalled = false;
    let watchdog: number | null = null;
    const arm = (ms: number) => {
      if (watchdog !== null) window.clearTimeout(watchdog);
      watchdog = window.setTimeout(() => {
        stalled = true;
        xhr.abort();
      }, ms);
    };
    const abortFromSignal = () => xhr.abort();
    const settle = (finish: () => void) => {
      if (watchdog !== null) window.clearTimeout(watchdog);
      watchdog = null;
      signal?.removeEventListener("abort", abortFromSignal);
      finish();
    };

    xhr.open("PUT", url, true);
    Object.entries(headers ?? {}).forEach(([key, value]) => xhr.setRequestHeader(key, value));
    // No flat deadline — see STALL_TIMEOUT_MS.
    xhr.timeout = 0;
    xhr.upload.onprogress = (event) => {
      arm(STALL_TIMEOUT_MS);
      if (event.lengthComputable) onProgress?.(event.loaded, event.total);
    };
    xhr.upload.onload = () => arm(FINALIZE_TIMEOUT_MS);
    xhr.onload = () =>
      settle(() => {
        if (xhr.status >= 200 && xhr.status < 300) {
          onProgress?.(total, total);
          resolve({ etag: (xhr.getResponseHeader("ETag") ?? "").replace(/"/g, "") || null });
        } else {
          reject(new StorageError(`Object storage upload failed: HTTP ${xhr.status}`, xhr.status));
        }
      });
    xhr.onerror = () => settle(() => reject(new StorageError("Object storage upload failed: network error")));
    xhr.onabort = () =>
      settle(() =>
        reject(
          new StorageError(
            stalled
              ? `Object storage upload stalled — no data moved for ${Math.round(STALL_TIMEOUT_MS / 1000)}s`
              : "Upload cancelled"
          )
        )
      );

    signal?.addEventListener("abort", abortFromSignal, { once: true });
    arm(STALL_TIMEOUT_MS);
    xhr.send(body);
  });
}

/**
 * Run `worker` over `items` with at most `limit` in flight, preserving each item's index. Stops
 * handing out new work after the first rejection and rethrows it once the in-flight ones have
 * settled — a worker that swallows its own errors therefore never stops the pool.
 */
async function runPool<T>(items: T[], limit: number, worker: (item: T, index: number) => Promise<void>): Promise<void> {
  let cursor = 0;
  let firstError: unknown = null;
  const runner = async () => {
    while (cursor < items.length && firstError === null) {
      const index = cursor;
      cursor += 1;
      try {
        await worker(items[index], index);
      } catch (error) {
        if (firstError === null) firstError = error;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, Math.min(limit, items.length)) }, runner));
  if (firstError !== null) throw firstError;
}

/**
 * SHA-256 of the file, as `sha256:<hex>`, stored on the media row so a later integrity sweep can
 * prove the bytes in S3 are the bytes that left the device. Null when the file is too big to hash in
 * one allocation, or when WebCrypto is unavailable (an insecure origin) — the upload still proceeds.
 *
 * EXPORTED so the selection-time duplicate check (lib/imageQuality.ts) hashes a newly-chosen file
 * with exactly this implementation. The check compares that value against the `checksum` already
 * stored on the media rows this workshop has attached, so the comparison is only meaningful if both
 * sides were produced the same way — a second SHA-256 helper with, say, a different prefix or hex
 * casing would silently never match anything and duplicate detection would simply never fire.
 */
export async function computeChecksum(file: File): Promise<string | null> {
  if (file.size === 0 || file.size > CHECKSUM_MAX_BYTES) return null;
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) return null;
  try {
    const digest = await subtle.digest("SHA-256", await file.arrayBuffer());
    return `sha256:${Array.from(new Uint8Array(digest))
      .map((byte) => byte.toString(16).padStart(2, "0"))
      .join("")}`;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Object upload (presign -> PUT -> [multipart complete])
// ---------------------------------------------------------------------------

/** A file already pushed to object storage but not yet attached to a saved record. */
export type StagedObject = {
  objectKey: string;
  bucket: string;
  publicUrl: string | null;
  mediaType: MediaType;
  mimeType: string;
  sizeBytes: number;
  checksum: string | null;
};

type UploadTarget = { objectKey: string; bucket: string; publicUrl: string | null };

type PresignResponse = {
  uploadUrl: string;
  objectKey: string;
  bucket: string;
  headers: Record<string, string>;
  publicUrl?: string;
};

type MultipartCreateResponse = {
  objectKey: string;
  uploadId: string;
  bucket: string;
  partSize: number;
  partCount: number;
  publicUrl?: string;
};

type MultipartPartsResponse = { urls: Record<string, string> };
type MultipartCompleteResponse = { objectKey: string; bucket: string; publicUrl?: string };

type ObjectUploadOptions = {
  file: File;
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  onProgress?: (loaded: number, total: number) => void;
  /** Called with every key we presign, so a half-written object can be cleaned up later. */
  onObjectKey?: (objectKey: string) => void;
  signal?: AbortSignal;
  /**
   * Let a 401 met on the way send the browser to /login. Defaults to true, which is right for a
   * capture screen somebody is looking at: their session is over and a sign-in form is what they
   * need next.
   *
   * PASS FALSE FROM ANYTHING RUNNING IN THE BACKGROUND. `lib/offline.ts` replays its outbox on an
   * `online` event and on mount, with nobody having asked for anything — and a replay that resumes
   * an entry whose record ALREADY EXISTS sends its very first request from the media leg. Its
   * create leg has always opted out (`redirectOn401: false`); until this option existed the media
   * leg could not, so a seven-day token expiring in exactly that window hard-navigated a researcher
   * off whatever they were editing and took the unsaved screen with it. The token is still cleared
   * by `apiFetch` either way — that is not this flag — and `AppShell` does the soft redirect for a
   * protected route once `AuthProvider` notices.
   */
  redirectOn401?: boolean;
};

/**
 * Set to false the first time S3/MinIO answers a part PUT without a readable ETag: without
 * `ExposeHeaders: ["ETag"]` in the bucket CORS rule no browser can ever complete a multipart upload,
 * so the whole session falls back to single PUTs instead of failing every large file.
 */
let multipartAvailable = true;

/**
 * Push a file to object storage and stop there — no MediaFile row, no record link.
 *
 * Everything else in this module ends by calling `/media/complete`, which is right for field media:
 * a photo that is not attached to a record is an orphan. An Android APK is the one thing that is
 * legitimately just an object: the release is recorded against `/app/release` by object key, and a
 * MediaFile row for it would put a 25 MB binary into the researcher's media lists.
 */
export function uploadStorageObject(options: ObjectUploadOptions): Promise<StagedObject> {
  return uploadObject(options);
}

async function uploadObject(options: ObjectUploadOptions): Promise<StagedObject> {
  const { file } = options;
  /*
    A `LocalRefusalError` AND NOT A BARE `Error`, WHICH IS THE WHOLE POINT OF THE TYPE.

    Presign rejects a zero-byte object (`sizeBytes > 0`), so this refusal is correct and is made here
    to save a round trip. It used to be a bare `new Error`, and a bare `Error` is indistinguishable
    from a `TypeError` out of `fetch` to every classifier downstream — whose default is, correctly,
    "the connection is at fault". So an empty file came out of the outbox drain classified as OFFLINE:
    the batch escalation stopped the whole pass, nothing was recorded, no Discard was offered, and the
    next connection met the identical empty file. A queue jammed for ever by a file no server saw.

    `lib/offline.ts` also separates empty files out BEFORE calling here (`splitUnsendableFiles`), and
    should go on doing so — not sending is better than sending and being refused. But that only
    protects the callers that remember it. The type protects the ones that do not: see
    `lib/failureTriage.ts`, where it is the one thing that resolves to kind `permanent`.
  */
  if (file.size === 0) throw new LocalRefusalError(`"${file.name}" is empty (0 bytes) — there is nothing to upload.`);
  const mediaType = inferMediaType(file);
  const mimeType = file.type || "application/octet-stream";
  // Hashed alongside the transfer, not before it, so it never delays the bytes leaving the device.
  const checksum = computeChecksum(file);
  const target =
    file.size > MULTIPART_THRESHOLD && multipartAvailable
      ? await uploadInParts(options, mediaType, mimeType)
      : await uploadWhole(options, mediaType, mimeType);
  return { ...target, mediaType, mimeType, sizeBytes: file.size, checksum: await checksum };
}

async function uploadWhole(options: ObjectUploadOptions, mediaType: MediaType, mimeType: string): Promise<UploadTarget> {
  const { file, linkedRecordType, linkedRecordId, onProgress, onObjectKey, signal, redirectOn401 } = options;
  let lastError: unknown;
  let previousKey: string | null = null;
  for (let attempt = 1; attempt <= UPLOAD_MAX_ATTEMPTS; attempt += 1) {
    // A fresh presign per attempt keeps the 15-minute signature window from expiring under retries.
    const presign = await apiRetry<PresignResponse>(
      "/media/presign",
      {
        method: "POST",
        body: JSON.stringify({
          filename: file.name,
          mimeType,
          mediaType,
          sizeBytes: file.size,
          linkedRecordType,
          linkedRecordId
        })
      },
      { redirectOn401 }
    );
    // The previous attempt's key is now abandoned: stop keeping it alive so the sweeper reclaims it.
    if (previousKey) liveObjectKeys.delete(previousKey);
    previousKey = presign.objectKey;
    onObjectKey?.(presign.objectKey);
    try {
      await putBlob({ url: presign.uploadUrl, headers: presign.headers, body: file, onProgress, signal });
      return { objectKey: presign.objectKey, bucket: presign.bucket, publicUrl: presign.publicUrl ?? null };
    } catch (error) {
      if (signal?.aborted) throw error;
      lastError = error;
      onProgress?.(0, file.size);
      if (attempt < UPLOAD_MAX_ATTEMPTS) await delay(800 * attempt);
    }
  }
  throw lastError instanceof Error ? lastError : new StorageError(`Object storage upload failed for ${file.name}`);
}

/**
 * S3 multipart upload for a large file: chunk -> upload parts (in parallel, each retried on its own)
 * -> S3 stitches them into one object. The win over a single PUT is that a blip costs one 16 MiB
 * part instead of restarting a 400 MB video.
 */
async function uploadInParts(options: ObjectUploadOptions, mediaType: MediaType, mimeType: string): Promise<UploadTarget> {
  const { file, linkedRecordType, linkedRecordId, onProgress, onObjectKey, signal, redirectOn401 } = options;
  const create = await apiRetry<MultipartCreateResponse>(
    "/media/multipart/create",
    {
      method: "POST",
      body: JSON.stringify({
        filename: file.name,
        mimeType,
        mediaType,
        sizeBytes: file.size,
        linkedRecordType,
        linkedRecordId
      })
    },
    { redirectOn401 }
  );
  onObjectKey?.(create.objectKey);

  const partNumbers = Array.from({ length: create.partCount }, (_, index) => index + 1);
  const loaded = new Array<number>(create.partCount).fill(0);
  const etags = new Array<string>(create.partCount);
  const report = () => onProgress?.(loaded.reduce((sum, value) => sum + value, 0), file.size);

  const presignParts = (numbers: number[]) =>
    apiRetry<MultipartPartsResponse>(
      "/media/multipart/presign-parts",
      {
        method: "POST",
        body: JSON.stringify({ objectKey: create.objectKey, uploadId: create.uploadId, partNumbers: numbers })
      },
      { redirectOn401 }
    );

  const urls = (await presignParts(partNumbers)).urls;

  async function sendPart(partNumber: number): Promise<string | null> {
    let url = urls[String(partNumber)];
    if (!url) throw new StorageError(`Missing presigned URL for part ${partNumber}`);
    const start = (partNumber - 1) * create.partSize;
    let lastError: unknown;
    for (let attempt = 1; attempt <= PART_MAX_ATTEMPTS; attempt += 1) {
      try {
        // Content-Type is deliberately unset: the part presign does not sign it, so sending one
        // would not match the signature. `File.slice` yields a type-less Blob, so XHR sends none.
        const { etag } = await putBlob({
          url,
          body: file.slice(start, Math.min(file.size, start + create.partSize)),
          onProgress: (sent) => {
            loaded[partNumber - 1] = sent;
            report();
          },
          signal
        });
        return etag;
      } catch (error) {
        if (signal?.aborted) throw error;
        lastError = error;
        loaded[partNumber - 1] = 0;
        report();
        // A 403 on a part means the hour-long part signature expired mid-transfer — re-sign just
        // that part rather than failing (and restarting) a multi-hundred-megabyte upload.
        if (error instanceof StorageError && error.status === 403) {
          const refreshed = await presignParts([partNumber]).catch(() => null);
          const fresh = refreshed?.urls?.[String(partNumber)];
          if (fresh) url = fresh;
        }
        if (attempt < PART_MAX_ATTEMPTS) await delay(800 * attempt);
      }
    }
    throw lastError instanceof Error ? lastError : new StorageError(`Part ${partNumber} failed to upload`);
  }

  try {
    // Probe part 1 on its own. S3 identifies each part by the ETag it returns, which the browser can
    // only read when the bucket's CORS rule lists ETag under ExposeHeaders — if it does not, NO
    // multipart upload can ever be completed from a browser, so find out after 16 MiB, not 400 MB.
    const firstEtag = await sendPart(1);
    if (!firstEtag) throw new EtagUnavailableError();
    etags[0] = firstEtag;
    await runPool(partNumbers.slice(1), PART_CONCURRENCY, async (partNumber) => {
      const etag = await sendPart(partNumber);
      if (!etag) throw new EtagUnavailableError();
      etags[partNumber - 1] = etag;
    });
    const done = await apiRetry<MultipartCompleteResponse>(
      "/media/multipart/complete",
      {
        method: "POST",
        body: JSON.stringify({
          objectKey: create.objectKey,
          uploadId: create.uploadId,
          parts: etags.map((etag, index) => ({ partNumber: index + 1, etag }))
        })
      },
      { redirectOn401 }
    );
    return { objectKey: done.objectKey, bucket: done.bucket, publicUrl: done.publicUrl ?? null };
  } catch (error) {
    // Never leave half-written parts behind: they are billable, invisible, and never expire on their
    // own unless the bucket has a lifecycle rule for them.
    await apiFetch(
      "/media/multipart/abort",
      {
        method: "POST",
        body: JSON.stringify({ objectKey: create.objectKey, uploadId: create.uploadId })
      },
      { redirectOn401 }
    ).catch(() => undefined);
    forgetStagedObject(create.objectKey);
    if (error instanceof EtagUnavailableError) {
      multipartAvailable = false;
      loaded.fill(0);
      report();
      return uploadWhole(options, mediaType, mimeType);
    }
    throw error;
  }
}

// ---------------------------------------------------------------------------
// Staged-object journal — nothing we upload is ever allowed to become a silent orphan
// ---------------------------------------------------------------------------

const STAGED_JOURNAL_KEY = "field_repo_staged_objects";
/** A journalled key untouched for this long is assumed abandoned (closed tab, crash, navigation). */
const STAGED_STALE_MS = 5 * 60 * 1000;
const STAGED_HEARTBEAT_MS = 60 * 1000;
const STAGED_SWEEP_INTERVAL_MS = 5 * 60 * 1000;
const STAGED_SWEEP_BATCH = 20;

/** Keys this tab is actively using — heart-beaten, and never swept out from under a live form. */
const liveObjectKeys = new Set<string>();
let heartbeatTimer: number | null = null;
let sweepTimer: number | null = null;
let sweptThisSession = false;

function readJournal(): Record<string, number> {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(STAGED_JOURNAL_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : null;
    return parsed && typeof parsed === "object" ? (parsed as Record<string, number>) : {};
  } catch {
    return {};
  }
}

function writeJournal(entries: Record<string, number>) {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(STAGED_JOURNAL_KEY, JSON.stringify(entries));
  } catch {
    // Private mode / quota: the journal is a safety net, never a hard dependency.
  }
}

function rememberStagedObject(objectKey: string) {
  liveObjectKeys.add(objectKey);
  const journal = readJournal();
  journal[objectKey] = Date.now();
  writeJournal(journal);
  startHeartbeat();
}

function forgetStagedObject(objectKey: string) {
  liveObjectKeys.delete(objectKey);
  const journal = readJournal();
  if (objectKey in journal) {
    delete journal[objectKey];
    writeJournal(journal);
  }
}

/**
 * Keep every key this tab still owns looking fresh. A tab that closes (or a form the user navigated
 * away from) stops refreshing, and its keys age past STAGED_STALE_MS into the sweeper's sights —
 * which is what makes it safe to delete stale objects without ever hitting a form still in use.
 */
function startHeartbeat() {
  if (heartbeatTimer !== null || typeof window === "undefined") return;
  heartbeatTimer = window.setInterval(() => {
    if (liveObjectKeys.size === 0) {
      if (heartbeatTimer !== null) window.clearInterval(heartbeatTimer);
      heartbeatTimer = null;
      return;
    }
    const journal = readJournal();
    const now = Date.now();
    liveObjectKeys.forEach((key) => {
      journal[key] = now;
    });
    writeJournal(journal);
  }, STAGED_HEARTBEAT_MS);
}

/**
 * Bin one staged object.
 *
 * `redirectOn401: false` FOR THE SAME REASON THE OUTBOX DRAIN PASSES IT, and this one needs no
 * outbox at all: {@link scheduleStagedObjectSweep} calls this from a five-minute `setInterval`, so a
 * researcher with a form open and a token that has just expired was hard-navigated to /login BY A
 * TIMER — losing the screen to an orphan cleanup nobody asked for. The other callers (the ✕ on a
 * staged file, a released owner, a failed eager attempt) are bookkeeping behind a local action, and
 * none of them is the thing the person is waiting on either. The token is still cleared by
 * `apiFetch`, so a genuinely dead session is still noticed — by `AuthProvider`, which is where a
 * decision about the session belongs.
 */
async function deleteStagedObject(objectKey: string) {
  liveObjectKeys.delete(objectKey);
  try {
    await apiFetch<void>(
      `/media/object?objectKey=${encodeURIComponent(objectKey)}`,
      { method: "DELETE" },
      { redirectOn401: false }
    );
    forgetStagedObject(objectKey);
  } catch (error) {
    // ONLY A REFUSAL FORGETS THE OBJECT — 403 / 404 / 409 are terminal answers ("not yours" / "gone"
    // / "now attached to a record"), and there is nothing left to reclaim. Every other verdict keeps
    // the journal entry for the next sweep.
    //
    // A 401 IS AN `ApiError` AND IS EMPHATICALLY NOT ONE OF THEM, and that only became load-bearing
    // when this call stopped navigating. Before, the first 401 replaced the document and the tab
    // took the journal with it — at most one key lost. Now the sweep keeps going, and since
    // `apiFetch` clears the stored token on that same 401, every following DELETE is unauthenticated
    // and 401s too: forgetting here would empty the journal a batch at a time and strand every
    // staged object in the bucket with nothing left to reclaim it by — the one outcome this journal
    // exists to prevent. The object is still there and still ours; only the credential died. A 5xx
    // says the same thing about the server rather than the object, so it keeps the entry as well.
    //
    // WRITTEN AS `kind === "refused"` RATHER THAN `status !== 401 && status < 500`, which is what it
    // said and which was a seventh private copy of the network rules. The rewrite also fixes two
    // statuses the hand-rolled version got wrong in the dangerous direction: a 408 (the request never
    // completed — the server was never asked about this object) and a 429 (the server asking for
    // time) both used to FORGET the key and strand the object. See `lib/failureTriage.ts`.
    if (triageFailure(error).kind === "refused") {
      forgetStagedObject(objectKey);
    }
  }
}

/** Delete a staged object during page unload, where a normal fetch would be cancelled mid-flight. */
function beaconDeleteStagedObject(objectKey: string) {
  const token = getToken();
  if (!token) return;
  liveObjectKeys.delete(objectKey);
  try {
    void fetch(`${API_BASE}/api/media/object?objectKey=${encodeURIComponent(objectKey)}`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${token}` },
      keepalive: true
    }).catch(() => undefined);
  } catch {
    // Best effort only — the journal sweep is the durable backstop.
  }
}

/**
 * Delete staged objects nobody claimed: uploads whose `/media/complete` never ran because the tab
 * was closed, the browser crashed, or the record was simply abandoned. Safe by construction — a key
 * still being used by THIS tab is in `liveObjectKeys`, and one used by another tab is still being
 * heart-beaten, so only genuinely stale keys are touched.
 *
 * Returns how many keys were actually ATTEMPTED, which is not always how many were stale: the loop
 * stops the moment the credential dies, and a key it never reached is still in the journal for the
 * next pass. Nothing branches on the number today — it is a signal for the console and for tests.
 */
export async function sweepStagedObjects(): Promise<number> {
  if (typeof window === "undefined" || !getToken()) return 0;
  const journal = readJournal();
  const now = Date.now();
  const stale = Object.keys(journal)
    .filter((key) => !liveObjectKeys.has(key) && now - (journal[key] ?? 0) > STAGED_STALE_MS)
    .slice(0, STAGED_SWEEP_BATCH);
  let attempted = 0;
  for (const key of stale) {
    await deleteStagedObject(key);
    attempted += 1;
    // The token check above is a snapshot, and `apiFetch` clears the stored token the moment one of
    // these DELETEs comes back 401. Carrying on would fire the rest of the batch with no
    // Authorization header at all — nineteen guaranteed failures, and nineteen more chances for a
    // future edit to decide they were terminal. Stop; the journal is durable and the next sweep,
    // under a live session, still has every key.
    if (!getToken()) break;
  }
  return attempted;
}

/** Run the orphan sweep shortly after the first media surface mounts, then periodically. */
export function scheduleStagedObjectSweep() {
  if (typeof window === "undefined") return;
  if (!sweptThisSession) {
    sweptThisSession = true;
    // Let the page finish its own data fetches first; abandoned objects are in no hurry.
    window.setTimeout(() => void sweepStagedObjects(), 4_000);
  }
  if (sweepTimer === null) {
    sweepTimer = window.setInterval(() => void sweepStagedObjects(), STAGED_SWEEP_INTERVAL_MS);
  }
}

// ---------------------------------------------------------------------------
// Eager pre-upload store
// ---------------------------------------------------------------------------

export type StageStatus = "uploading" | "ready" | "error";

/** The read-only view of one eagerly-uploading attachment, as rendered by the capture UI. */
export type StageEntry = {
  file: File;
  status: StageStatus;
  loaded: number;
  total: number;
  error: string | null;
  objectKey: string | null;
  staged: StagedObject | null;
};

/**
 * Identity is the primary key, but a few call sites rename a File before saving
 * (`new File([file], niceName, …)`), which keeps the bytes and drops the object identity. Size +
 * last-modified + MIME survives that rename and is what the fallback match uses.
 */
function signatureOf(file: File): string {
  return `${file.size}:${file.lastModified}:${file.type}`;
}

class StageRecord {
  readonly file: File;
  readonly signature: string;
  readonly owners = new Set<string>();
  readonly controller = new AbortController();
  readonly promise: Promise<StagedObject>;
  status: StageStatus = "uploading";
  loaded = 0;
  error: string | null = null;
  objectKey: string | null = null;
  staged: StagedObject | null = null;

  constructor(file: File) {
    this.file = file;
    this.signature = signatureOf(file);
    this.promise = runStage(this);
    // Nobody may be awaiting yet (the record is saved minutes later) — keep Node/browsers from
    // reporting an unhandled rejection. The original promise still rejects for real awaiters.
    void this.promise.catch(() => undefined);
  }

  get total(): number {
    return this.file.size;
  }
}

const stagedByFile = new Map<File, StageRecord>();
const stagingListeners = new Set<() => void>();
const EMPTY_STAGING: ReadonlyMap<File, StageEntry> = new Map();
let stagingSnapshot: ReadonlyMap<File, StageEntry> = EMPTY_STAGING;

function notifyStaging() {
  const next = new Map<File, StageEntry>();
  stagedByFile.forEach((record, file) => {
    next.set(file, {
      file,
      status: record.status,
      loaded: record.loaded,
      total: record.total,
      error: record.error,
      objectKey: record.objectKey,
      staged: record.staged
    });
  });
  stagingSnapshot = next;
  stagingListeners.forEach((listener) => listener());
}

export function subscribeStaging(listener: () => void): () => void {
  stagingListeners.add(listener);
  return () => {
    stagingListeners.delete(listener);
  };
}

export function getStagingSnapshot(): ReadonlyMap<File, StageEntry> {
  return stagingSnapshot;
}

export function getServerStagingSnapshot(): ReadonlyMap<File, StageEntry> {
  return EMPTY_STAGING;
}

/** Whole-percent throttling, so a 400 MB video does not re-render the tile grid thousands of times. */
function setStageProgress(record: StageRecord, loaded: number) {
  const before = record.total > 0 ? Math.floor((record.loaded * 100) / record.total) : 0;
  const after = record.total > 0 ? Math.floor((loaded * 100) / record.total) : 0;
  record.loaded = loaded;
  if (after !== before) notifyStaging();
}

async function runStage(record: StageRecord): Promise<StagedObject> {
  try {
    const staged = await uploadObject({
      file: record.file,
      signal: record.controller.signal,
      onObjectKey: (objectKey) => {
        record.objectKey = objectKey;
        rememberStagedObject(objectKey);
      },
      onProgress: (loaded) => setStageProgress(record, loaded)
    });
    record.staged = staged;
    record.status = "ready";
    record.loaded = staged.sizeBytes;
    record.error = null;
    notifyStaging();
    return staged;
  } catch (error) {
    record.status = "error";
    record.error = error instanceof Error ? error.message : "Upload failed";
    // Let the sweeper reclaim whatever the failed attempt left behind.
    if (record.objectKey) liveObjectKeys.delete(record.objectKey);
    notifyStaging();
    throw error;
  }
}

let lifecycleInstalled = false;

function installLifecycleHooks() {
  if (lifecycleInstalled || typeof window === "undefined") return;
  lifecycleInstalled = true;
  window.addEventListener("beforeunload", (event) => {
    const busy = Array.from(stagedByFile.values()).some((record) => record.status === "uploading");
    if (!busy) return;
    event.preventDefault();
    // Still required by Chrome/Safari to actually show the "leave site?" prompt.
    event.returnValue = "";
  });
  window.addEventListener("pagehide", (event) => {
    // A bfcache freeze may be restored with the form (and its attachments) intact — deleting the
    // staged objects there would silently break a page the user is about to come back to.
    if (event.persisted) return;
    stagedByFile.forEach((record) => {
      record.controller.abort();
      const objectKey = record.staged?.objectKey ?? record.objectKey;
      if (objectKey) beaconDeleteStagedObject(objectKey);
    });
  });
}

/**
 * Start streaming every newly attached file to object storage right now. Idempotent per File, so a
 * component may call it on every render pass; `ownerId` ties the files to the surface that attached
 * them so they can be cleaned up when it goes away.
 */
export function stageFiles(files: File[], ownerId: string) {
  if (typeof window === "undefined" || !getToken()) return;
  cancelStagedOwnerRelease(ownerId);
  installLifecycleHooks();
  scheduleStagedObjectSweep();
  let changed = false;
  files.forEach((file) => {
    // Presign rejects a zero-byte file (sizeBytes > 0); let the save path report that clearly.
    if (file.size === 0) return;
    let record = stagedByFile.get(file);
    if (!record) {
      record = new StageRecord(file);
      stagedByFile.set(file, record);
      changed = true;
    }
    if (!record.owners.has(ownerId)) record.owners.add(ownerId);
  });
  if (changed) notifyStaging();
}

/** Abort a still-running eager upload and bin whatever it already wrote (the user pressed ✕). */
export function discardStagedFile(file: File) {
  const record = stagedByFile.get(file);
  if (!record) return;
  stagedByFile.delete(file);
  notifyStaging();
  record.controller.abort();
  discardRecordObject(record);
}

/** Re-run a failed eager upload from scratch (the per-file "Retry" affordance). */
export function retryStagedFile(file: File) {
  const previous = stagedByFile.get(file);
  if (!previous) return;
  previous.controller.abort();
  discardRecordObject(previous);
  const record = new StageRecord(file);
  previous.owners.forEach((owner) => record.owners.add(owner));
  stagedByFile.set(file, record);
  notifyStaging();
}

const RELEASE_GRACE_MS = 2_000;
const releaseTimers = new Map<string, number>();

function cancelStagedOwnerRelease(ownerId: string) {
  const timer = releaseTimers.get(ownerId);
  if (timer === undefined) return;
  window.clearTimeout(timer);
  releaseTimers.delete(ownerId);
}

/**
 * The surface that attached these files is gone (unmounted, or the user navigated away in the SPA).
 * Anything nobody else owns is aborted and deleted — after a short grace period, because React's
 * StrictMode tears every effect down and immediately re-runs it on mount in development, and that
 * must not throw away a perfectly good in-flight upload.
 */
export function releaseStagedOwner(ownerId: string) {
  if (typeof window === "undefined") return;
  cancelStagedOwnerRelease(ownerId);
  releaseTimers.set(
    ownerId,
    window.setTimeout(() => {
      releaseTimers.delete(ownerId);
      let changed = false;
      stagedByFile.forEach((record, file) => {
        if (!record.owners.delete(ownerId)) return;
        if (record.owners.size > 0) return;
        stagedByFile.delete(file);
        record.controller.abort();
        discardRecordObject(record);
        changed = true;
      });
      if (changed) notifyStaging();
    }, RELEASE_GRACE_MS)
  );
}

function discardRecordObject(record: StageRecord) {
  void record.promise
    .then((staged) => deleteStagedObject(staged.objectKey))
    .catch(() => {
      if (record.objectKey) void deleteStagedObject(record.objectKey);
    });
}

/**
 * Claim the staged uploads for these files, synchronously and before any await, so a form that
 * unmounts the instant it saves can never delete an object the save is about to link.
 */
function takeStagedFor(files: File[]): Array<StageRecord | null> {
  const taken = new Array<StageRecord | null>(files.length).fill(null);
  const unmatched: number[] = [];
  files.forEach((file, index) => {
    const record = stagedByFile.get(file);
    if (record) {
      stagedByFile.delete(file);
      taken[index] = record;
    } else {
      unmatched.push(index);
    }
  });
  if (unmatched.length && stagedByFile.size) {
    const bySignature = new Map<string, StageRecord[]>();
    stagedByFile.forEach((record) => {
      const list = bySignature.get(record.signature);
      if (list) list.push(record);
      else bySignature.set(record.signature, [record]);
    });
    unmatched.forEach((index) => {
      const signature = signatureOf(files[index]);
      const candidates = bySignature.get(signature);
      // Only an UNAMBIGUOUS signature match is honoured: attaching the wrong photo to a record is
      // far worse than re-uploading a file whose eager copy we could not confidently identify.
      if (!candidates || candidates.length !== 1) return;
      const record = candidates[0];
      stagedByFile.delete(record.file);
      bySignature.delete(signature);
      taken[index] = record;
    });
  }
  if (taken.some(Boolean)) notifyStaging();
  return taken;
}

/** Await a claimed eager upload, forwarding its live byte progress to the batch's progress bar. */
async function awaitStaged(record: StageRecord, onProgress?: (loaded: number, total: number) => void) {
  if (!onProgress) return record.promise;
  onProgress(record.loaded, record.total);
  const unsubscribe = subscribeStaging(() => onProgress(record.loaded, record.total));
  try {
    return await record.promise;
  } finally {
    unsubscribe();
  }
}

// ---------------------------------------------------------------------------
// Save-time: link the staged object, or upload it now
// ---------------------------------------------------------------------------

type CompleteParams = {
  caption?: string;
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  /**
   * THE DESIGN & PROTOTYPE WORKSHOP a MISCELLANEOUS upload is filed under, from that form's dropdown.
   *
   * NOT DERIVED FROM `linkedRecordType`, deliberately: a stage photograph already carries the tag
   * `"designWorkshop"` and must keep carrying only the tag. `records.media_relation_data` on the
   * server holds the argument — the orphan-recovery machinery is split precisely on whether a link
   * has a typed foreign key, and deriving this would put every stage photograph in scope of both
   * halves at once. Every caller but the Miscellaneous Media page leaves it undefined.
   */
  designWorkshopId?: string | null;
  location?: unknown;
  extraMetadata?: Record<string, unknown>;
  recordedAt?: string;
  recordedTimezone?: string;
  processingRequests: string[];
};

function resolveProcessing(file: File, transcribeAudio: boolean, requested?: string[]): string[] {
  const queued = new Set(requested ?? []);
  if (inferMediaType(file) === "AUDIO" && transcribeAudio) queued.add("TRANSCRIPTION");
  return Array.from(queued);
}

async function completeUpload(
  file: File,
  staged: StagedObject,
  params: CompleteParams,
  redirectOn401?: boolean
): Promise<MediaFile> {
  // Safe to retry: the API de-duplicates on objectKey and returns the row the first call created.
  return apiRetry<MediaFile>(
    "/media/complete",
    {
      method: "POST",
      body: JSON.stringify({
        originalFilename: file.name,
        mediaType: staged.mediaType,
        mimeType: staged.mimeType,
        sizeBytes: staged.sizeBytes,
        objectKey: staged.objectKey,
        bucket: staged.bucket,
        url: staged.publicUrl,
        checksum: staged.checksum,
        caption: params.caption,
        linkedRecordType: params.linkedRecordType,
        linkedRecordId: params.linkedRecordId,
        designWorkshopId: params.designWorkshopId,
        location: params.location,
        extraMetadata: params.extraMetadata,
        recordedAt: params.recordedAt,
        recordedTimezone: params.recordedTimezone,
        processingRequests: params.processingRequests
      })
    },
    { redirectOn401 }
  );
}

/**
 * The one path every upload takes at save time: prefer the eagerly pre-uploaded object (awaiting it
 * if it is still in flight), fall back to a fresh upload when there is none or it failed, then link
 * the object to the record.
 */
async function linkOrUpload({
  file,
  record,
  params,
  onProgress,
  redirectOn401
}: {
  file: File;
  record: StageRecord | null;
  params: CompleteParams;
  onProgress?: (loaded: number, total: number) => void;
  /** See {@link ObjectUploadOptions.redirectOn401} — false for a background pass. */
  redirectOn401?: boolean;
}): Promise<MediaFile> {
  let staged: StagedObject | null = null;
  if (record) {
    if (record.status === "error") {
      // The eager attempt gave up; bin what it left behind and upload from scratch below.
      discardRecordObject(record);
    } else {
      try {
        staged = await awaitStaged(record, onProgress);
      } catch {
        discardRecordObject(record);
      }
    }
  }
  if (!staged) {
    staged = await uploadObject({
      file,
      linkedRecordType: params.linkedRecordType,
      linkedRecordId: params.linkedRecordId,
      onProgress,
      onObjectKey: rememberStagedObject,
      redirectOn401
    });
  }
  const media = await completeUpload(file, staged, params, redirectOn401);
  // Linked: it is a real MediaFile now, not an orphan candidate.
  forgetStagedObject(staged.objectKey);
  return media;
}

export async function uploadMediaFile({
  file,
  linkedRecordType,
  linkedRecordId,
  designWorkshopId,
  caption,
  location,
  extraMetadata,
  recordedAt,
  recordedTimezone,
  transcribeAudio = true,
  processingRequests,
  onProgress
}: {
  file: File;
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  /** See `CompleteParams.designWorkshopId`. */
  designWorkshopId?: string | null;
  caption?: string;
  location?: unknown;
  extraMetadata?: Record<string, unknown>;
  recordedAt?: string;
  recordedTimezone?: string;
  transcribeAudio?: boolean;
  processingRequests?: string[];
  onProgress?: (loaded: number, total: number) => void;
}) {
  const [record] = takeStagedFor([file]);
  return linkOrUpload({
    file,
    record,
    onProgress,
    params: {
      caption,
      linkedRecordType,
      linkedRecordId,
      designWorkshopId,
      location,
      extraMetadata,
      recordedAt,
      recordedTimezone,
      processingRequests: resolveProcessing(file, transcribeAudio, processingRequests)
    }
  });
}

export type BatchProgress = {
  fileIndex: number;
  fileCount: number;
  uploadedBytes: number;
  totalBytes: number;
  fraction: number;
  etaSeconds: number | null;
  currentFileName: string;
};

/** One file that did not land, with the error that stopped it kept intact for classification. */
export type BatchFailure = {
  name: string;
  error: string;
  /** The ORIGINAL thrown value — an `ApiError` when the server answered. See {@link MediaBatchError}. */
  cause?: unknown;
};

/**
 * ONE INPUT FILE'S FATE, WITH THE FILE ITSELF ATTACHED.
 *
 * The shape a caller cannot mis-zip, because there is nothing to zip: the `File`, what it became and
 * why it did not are one object, at the position the caller passed the file in. Everything else on
 * {@link BatchResult} is a view of this array, kept because a dozen call sites read them.
 */
export type BatchOutcome = {
  /** The `File` the caller passed at this position. Identity, not name — see {@link BatchResult.outcomes}. */
  file: File;
  /** What it became, or null when it did not land. */
  media: MediaFile | null;
  /** Why it did not land, or null when it did. Exactly one of these two is non-null. */
  failure: BatchFailure | null;
};

export type BatchResult = {
  /**
   * ONE ENTRY PER INPUT FILE, AT ITS POSITION, CARRYING THE FILE. The authoritative result; the three
   * fields below are derived from it and exist because a dozen call sites already read them.
   *
   * ── WHY THE AUTHORITATIVE SHAPE IS THIS ONE ──────────────────────────────────────────────
   * This function RESOLVES on a partly-failed batch and throws only when nothing landed, so
   * `const { uploaded } = await uploadMediaBatch(...)` reads as success and is not. Two ship-blockers
   * came out of that, and both were index arithmetic on top of it: `uploaded` is COMPACTED (the
   * by-index array with its nulls filtered out) so its ORDER is the caller's and its POSITIONS are
   * not, and a caller that walked it with a counter into its own `File[]` recorded every surviving
   * file against the wrong one. `FieldInput` did exactly that, and its reader is `IdentityCardReader`,
   * which prints a file's name beside the identity number it OCR'd out of the bytes — so the misfile
   * showed a designer one photograph's name over another photograph's identity number, defeating the
   * one cross-check that surface relies on. On identity data.
   *
   * `uploadedByIndex` was added to close that instance. This closes the CLASS: with the file inside
   * the record there is no second array to line up, so the arithmetic that produced both defects has
   * nowhere to happen. `e2e/batch-result-sweep-unit.spec.ts` is the other half — it fails if any
   * caller destructures `uploaded` without also taking `failed` or `outcomes`.
   *
   * NEVER MATCHED BACK BY `file.name`. Two photographs off one handset are routinely both
   * `IMG_0001.jpg`; a name match would misfile them and look like it had worked.
   */
  outcomes: BatchOutcome[];
  uploaded: MediaFile[];
  failed: BatchFailure[];
  /**
   * The same results, but at the POSITION of the file that produced each one — `null` where that
   * file did not land.
   *
   * ── THE DEFECT THIS EXISTS FOR, AND WHY `uploaded` COULD NOT ANSWER IT ────────────────────────
   * `uploaded` is COMPACTED: the by-index array is built at `files.length` and then filtered of its
   * nulls on the way out. Its ORDER is the caller's; its POSITIONS are not, and the two were
   * described by one sentence above for long enough that a caller zipped them together.
   * `FieldInput`'s media field did exactly that — `uploaded.forEach((media, index) => originals[
   * media.id] = chosen[index])` — so in any batch where an earlier file failed and a later one
   * succeeded, every surviving file was recorded against the wrong `File`. The reader that consumes
   * that map is `IdentityCardReader`, which OCRs the original bytes and prints the file's name
   * beside the digits it read: the mismatch shows a designer one photograph's name over another
   * photograph's identity number, which defeats the one cross-check its own header relies on.
   *
   * ── ADDITIVE, NOT A REPLACEMENT ───────────────────────────────────────────────────────────────
   * Every existing caller reads `uploaded` and `failed` and is unaffected. A caller that needs to
   * know WHICH file produced a result reads this instead of counting, and can never fall back to
   * matching on `file.name` — two photographs off one handset are routinely both `IMG_0001.jpg`,
   * and a name match would misfile them with no failure anywhere to notice it by.
   */
  uploadedByIndex: Array<MediaFile | null>;
};

/**
 * Raised when a batch landed NOTHING — carrying the per-file causes, not just a sentence.
 *
 * The caller that needs this is the design-workshop sync, which uploads one file per call: for it
 * "the whole batch failed" and "the server refused this photograph" are the same event, and the two
 * demand opposite responses. A refusal must be recorded against the file and the pass must carry on;
 * a dropped connection must stop the pass and change nothing. `isUnreachable` decides that, and it
 * decides on the ERROR'S TYPE — so an escalation that flattened the cause to a string forced every
 * refusal down the "you appear to be offline" path and retried it for ever.
 */
export class MediaBatchError extends Error {
  readonly failures: BatchFailure[];

  constructor(message: string, failures: BatchFailure[]) {
    // `cause` is the FIRST underlying error, which is the whole of it for a one-file batch and the
    // representative one otherwise — the batch either met one refusal or lost the connection.
    super(message, { cause: failures[0]?.cause });
    this.name = "MediaBatchError";
    this.failures = failures;
  }
}

/**
 * The second half of {@link MediaBatchError}'s sentence — what to DO, chosen by what happened.
 *
 * ── THE ONE STRING THAT STAYED HAND-ROLLED AFTER THE RULE WAS SHARED ────────────────────────────
 *
 * This clause was a constant: "Check your internet connection and try again — the record was saved,
 * so re-open it and re-attach the media", appended to every total failure whatever caused it. The
 * classification underneath had already been unified — `cause` is carried, `triageFailure` reads it,
 * every drain acts on the verdict — and the SENTENCE went on saying the one thing the verdict most
 * often contradicts. A 413 on an over-size video, a 415 on a heic the server will not take, a 422
 * naming a key the two builds disagree about: the server answered all three, and each of them told
 * the researcher to go and look for signal they already had. `e2e/draft-store-drift-unit.spec.ts`
 * pins the worst of them, where the advice arrived stacked with two others that contradicted it.
 *
 * IT BRANCHES ON `screen`, WHICH IS THE FIELD FOR EXACTLY THIS. `ScreenAction` in
 * `lib/failureTriage.ts` is the triage's own answer to "what must a screen somebody is looking at
 * say", so this function is a lookup and not a seventh opinion — nothing here decides anything
 * about a status, and the `default` arm exists only so a kind added there cannot silently lose its
 * sentence.
 *
 * THE PREFIX IS UNCHANGED and so is the shape: a reason clause, then an advice clause. Callers that
 * strip the advice and keep the reason — `batchCause` on the questionnaire page is the one that does,
 * because an interview has no edit screen to "re-open" — go on working unaltered, and callers that
 * surface `.message` whole get a sentence that is true of what happened instead of one that is true
 * a third of the time.
 */
function adviceForALostBatch(cause: unknown): string {
  const { screen } = triageFailure(cause);
  switch (screen) {
    case "say-signed-out":
      return "Your sign-in has expired, so nothing could be sent — sign in again. The record was saved, so re-open it and re-attach the media.";
    case "say-try-later":
      return "The repository was reached but could not take the media just now, so this is not your connection — wait a minute. The record was saved, so re-open it and re-attach the media.";
    case "say-out-of-step":
      return "This app and the repository are out of step about the shape of the request — nothing you did is wrong and nothing here will change it until one of them is updated. The record was saved.";
    case "say-refused":
      return "The repository answered and refused the media, so this is not your connection — the reason above is what has to change. The record was saved, so re-open it and re-attach the media once it has.";
    case "say-unsendable":
      return "Nothing was sent: the reason above is about the file itself, so no connection and no retry can clear it. The record was saved, so re-open it and attach a good copy.";
    case "say-offline":
    default:
      return "Check your internet connection and try again — the record was saved, so re-open it and re-attach the media.";
  }
}

/**
 * Upload many files resiliently: files that were eagerly pre-uploaded only need linking (so saving is
 * near-instant), the rest transfer now — up to {@link UPLOAD_CONCURRENCY} at a time. Each file
 * retries independently, a failure of one does not abort the rest, and per-byte progress + ETA is
 * reported across the whole batch.
 *
 * IT RESOLVES ON A PARTLY-FAILED BATCH and throws only when NOTHING landed, so a resolved promise is
 * not a success and `const { uploaded } = await uploadMediaBatch(...)` is a bug in the reading of it.
 * Take {@link BatchResult.outcomes} — one entry per input file, at its position, with the `File`
 * attached — or at minimum take `failed` alongside `uploaded` and say what did not make it.
 * `e2e/batch-result-sweep-unit.spec.ts` fails the build if a call site does neither.
 *
 * `uploaded` KEEPS THE CALLER'S ORDER AND NOT THE CALLER'S POSITIONS, and the difference is a bug
 * this sentence used to hide: it is the by-index array with its nulls removed, so `uploaded[2]` is
 * the third file that LANDED, not the third file that was passed in. See {@link BatchResult.outcomes}
 * for what that zip cost, on identity data, twice.
 *
 * `redirectOn401` IS HOW A BACKGROUND PASS STOPS BEING A NAVIGATION. Every request this makes went
 * out with `apiFetch`'s default, which sends the browser to /login on an expired token — right for a
 * researcher watching a save, and wrong for the outbox drain in `lib/offline.ts`, which runs on an
 * `online` event with nobody having asked. A resumed entry whose record already exists sends its
 * FIRST request from here, so a seven-day token dying in that window threw the researcher off
 * whatever they were editing. See {@link ObjectUploadOptions.redirectOn401}, which carries it the
 * rest of the way.
 */
export async function uploadMediaBatch({
  files,
  linkedRecordType,
  linkedRecordId,
  designWorkshopId,
  caption,
  location,
  extraMetadata,
  recordedAt,
  recordedTimezone,
  transcribeAudio = true,
  processingRequests,
  onProgress,
  redirectOn401
}: {
  files: File[];
  // Nullable so the Miscellaneous Media page (where the linked entry is optional) can share this
  // path instead of keeping its own upload implementation.
  linkedRecordType?: string | null;
  linkedRecordId?: string | null;
  /** See `CompleteParams.designWorkshopId`. */
  designWorkshopId?: string | null;
  caption?: string;
  location?: unknown;
  extraMetadata?: Record<string, unknown>;
  recordedAt?: string;
  recordedTimezone?: string;
  transcribeAudio?: boolean;
  processingRequests?: string[];
  onProgress?: (progress: BatchProgress) => void;
  /**
   * Defaults to true, exactly as `apiFetch` does. Pass FALSE from anything that is not a screen
   * somebody is looking at — see the note above.
   */
  redirectOn401?: boolean;
}): Promise<BatchResult> {
  // Claimed synchronously, before the first await, so nothing can reclaim these objects mid-save.
  const records = takeStagedFor(files);
  const uploadedByIndex = new Array<MediaFile | null>(files.length).fill(null);
  // BY INDEX, NOT BY ARRIVAL. `runPool` runs UPLOAD_CONCURRENCY files at once, so a pushed array is
  // in COMPLETION order — which read like input order in every test with one slow file and was not.
  // `failed` below is rebuilt from this in the caller's own order, so `failed[0]` is the first file
  // the caller passed that did not land, rather than whichever request happened to lose first.
  const failureByIndex = new Array<BatchFailure | null>(files.length).fill(null);
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0) || 1;
  const loaded = new Array<number>(files.length).fill(0);
  const startedAt = Date.now();
  let completedFiles = 0;
  let currentFileName = files[0]?.name ?? "";

  const report = () => {
    const uploadedBytes = Math.min(
      totalBytes,
      loaded.reduce((sum, value) => sum + value, 0)
    );
    const elapsed = (Date.now() - startedAt) / 1000;
    const rate = elapsed > 0 ? uploadedBytes / elapsed : 0;
    onProgress?.({
      fileIndex: Math.min(completedFiles, Math.max(0, files.length - 1)),
      fileCount: files.length,
      uploadedBytes,
      totalBytes,
      fraction: uploadedBytes / totalBytes,
      etaSeconds: rate > 0 ? Math.max(0, Math.round((totalBytes - uploadedBytes) / rate)) : null,
      currentFileName
    });
  };

  report();
  await runPool(files, UPLOAD_CONCURRENCY, async (file, index) => {
    currentFileName = file.name;
    report();
    try {
      uploadedByIndex[index] = await linkOrUpload({
        file,
        record: records[index],
        redirectOn401,
        onProgress: (sent) => {
          loaded[index] = sent;
          report();
        },
        params: {
          caption,
          linkedRecordType,
          linkedRecordId,
          designWorkshopId,
          location,
          extraMetadata,
          recordedAt,
          recordedTimezone,
          processingRequests: resolveProcessing(file, transcribeAudio, processingRequests)
        }
      });
    } catch (error) {
      // `cause` keeps the ORIGINAL error, `ApiError` and all. Reducing it to `.message` here is what
      // made a refusal indistinguishable from a dropped connection two callers away: every test of
      // "did the server actually answer" is `error instanceof ApiError`, and a string is not.
      failureByIndex[index] = {
        name: file.name,
        error: error instanceof Error ? error.message : "Upload failed",
        cause: error
      };
    }
    // Settled either way — count its bytes so the bar still reaches the end of the batch.
    loaded[index] = file.size;
    completedFiles += 1;
    report();
  });

  /*
    THE ONE ARRAY EVERYTHING ELSE IS A VIEW OF. Built here, at `files.length`, so a position in it is
    a position in the caller's own array and the two can never be a different length. See
    {@link BatchResult.outcomes} for the two ship-blockers that came out of lining up two arrays.
  */
  const outcomes: BatchOutcome[] = files.map((file, index) => ({
    file,
    media: uploadedByIndex[index],
    failure: failureByIndex[index]
  }));
  const failed = outcomes.map((outcome) => outcome.failure).filter((failure): failure is BatchFailure => failure !== null);

  // Total failure (nothing got through) is escalated so every caller surfaces it — the record was
  // already saved before this ran, so the fix is to retry the media, not redo the whole form.
  //
  // THE THROW CARRIES `cause`, AND THAT IS NOT A DETAIL. The offline sync uploads ONE file per call,
  // so for it "every file failed" is simply "this file was refused" — and a bare `new Error` made a
  // 415 on a video the server will never accept look exactly like a lost signal to `isUnreachable`,
  // which stopped the pass as offline and came back to the same refusal for ever.
  if (files.length > 0 && failed.length === files.length) {
    throw new MediaBatchError(
      `All ${files.length} media file(s) failed to upload (${failed[0]?.error ?? "network error"}). ` +
        adviceForALostBatch(failed[0]?.cause),
      failed
    );
  }
  return {
    outcomes,
    // COMPACTED, and the three lines that say so are the whole reason `outcomes` exists: this is the
    // by-index array with its nulls removed, so its ORDER is the caller's and its POSITIONS are not.
    // Callers that only need "what landed" go on reading it; callers that need "what THIS file
    // became" read `outcomes`, where the file is attached and there is nothing to line up.
    uploaded: uploadedByIndex.filter((item): item is MediaFile => item !== null),
    failed,
    // The uncompacted array, kept for the callers that already read it by position. New readers
    // should take `outcomes` instead — it is the same information with the `File` attached, so a
    // wrong index is a type error rather than a photograph filed under another photograph's name.
    uploadedByIndex
  };
}

export async function transcribeMediaFile(file: File, mediaType = inferMediaType(file)) {
  if (mediaType !== "AUDIO") return {};
  const form = new FormData();
  form.append("file", file);
  const result = await apiFetch<{
    available: boolean;
    status: string;
    text?: string | null;
    formattedTranscript?: string | null;
    message?: string;
  }>("/media/transcribe", { method: "POST", body: form });
  return {
    transcriptText: result.formattedTranscript ?? result.text ?? null,
    transcriptStatus: result.status,
    transcriptError: result.available ? null : result.message ?? "Transcription unavailable for now"
  };
}

/**
 * HOW A DIMENSION CAME TO BE KNOWN, in the shape a client echoes back UNCHANGED when it saves the
 * value it was given.
 *
 * `backend/app/services/measurement_provenance.py` composes this (`MeasurementProvenance.marker()`)
 * and reads it back off the save body; the server then writes the method BESIDE the `{by, byName,
 * at}` stamp, so the row reads *a vision model estimated this, and this person accepted it into the
 * record at that moment* rather than as a measurement that person took.
 *
 * DELIBERATELY ONE FORMAT FOR EVERY ORIGIN — a client composes the same shape by hand for a typed
 * measurement (`{method: "TYPED"}`) or for the on-device geometry path
 * (`{method: "PHOTO_GEOMETRY", technique: "SCALE" | "RECTIFIED"}`). Keys whose answer is not a known
 * fact are OMITTED rather than sent as a placeholder: a `provider` on a hand-typed dimension is a
 * question that does not apply.
 *
 * SENDING NOTHING IS LEGAL AND MEANS `UNRECORDED`; **it must never mean TYPED.** The rows already in
 * this database include model estimates that auto-filled a form field, so defaulting an absent marker
 * to TYPED would assert a human measured a number a machine guessed, for exactly the rows where the
 * assertion is false.
 */
export type MeasurementMethodMarker = {
  method: string;
  provider?: string;
  modelId?: string;
  selfReportedConfidence?: number;
  technique?: string;
};

/**
 * What `POST /media/analyze-measurement` answers with.
 *
 * EVERY PROVENANCE KEY IS OPTIONAL, and that is not laziness. A web build outlives a backend deploy,
 * and a client that required `methodMarker` would break outright against a server that predates it —
 * the same reasoning that makes `ApiClient.kt` build its `Json` with `ignoreUnknownKeys = true` in the
 * other direction. An absent marker is read as "this server does not say", never as "typed".
 *
 * `requiresAcceptance` is `true` on every response this endpoint can produce, INCLUDING the failures:
 * it is a statement about what kind of thing this endpoint produces, not about one reading. Nothing
 * here branches on it — `components/media/gridProposal.ts` treats every reading as a proposal
 * unconditionally, which is the stronger reading of the same rule — but it is declared so a future
 * caller can see that the server has an opinion about it.
 *
 * `confidenceIsCalibrated` is hard `false` server-side and there is no code path that sets it true.
 * It exists so `selfReportedConfidence` can never be mistaken for a measured error bar — which is
 * what `lib/photoMeasure.ts`'s `uncertainty` is, and the two must not look alike on a wire that
 * carries both.
 */
export type MeasurementAnalysisResponse = {
  available: boolean;
  status: string;
  analysis?: {
    valueInches?: number | string | null;
    lengthInches?: number | string | null;
    breadthInches?: number | string | null;
    notes?: string;
  } | null;
  message?: string;
  method?: string;
  provider?: string | null;
  modelId?: string | null;
  selfReportedConfidence?: number | null;
  confidenceIsCalibrated?: boolean;
  requiresAcceptance?: boolean;
  methodMarker?: MeasurementMethodMarker | null;
};

/**
 * THIS CLIENT STOPPED WAITING. Thrown by {@link analyzeMeasurementImage} and by nothing else.
 *
 * A TYPE RATHER THAN AN ERROR NAME, and the reason is that the alternative does not work. An aborted
 * `fetch` rejects with a `DOMException` whose `name` is `"AbortError"` or `"TimeoutError"` depending
 * on the runtime and on how the signal was built — and, worse, a CALLER's abort (the designer replaced
 * the photograph, the component unmounted, the group was unchecked) produces the identical rejection.
 * Only the layer that started the clock can tell the two apart, so it says which happened instead of
 * leaving `lib/measurementFailure.ts` to guess from a string. A caller's own abort is deliberately NOT
 * this: it is not a timeout, and reporting it as one would put a sentence about a slow connection
 * under a photograph the designer themselves discarded.
 */
export class MeasurementTimeoutError extends Error {
  constructor(public readonly timeoutMs: number) {
    super(`No answer from the measurement service within ${Math.round(timeoutMs / 1000)} seconds.`);
    this.name = "MeasurementTimeoutError";
  }
}

/**
 * The wait before this client gives up on a grid reading, in milliseconds.
 *
 * ABOVE THE SERVER'S OWN BUDGET, DELIBERATELY, AND THE MARGIN IS THE POINT.
 * `_post_gemini_measurement` calls the provider with `timeout=90` (seconds) and ROTATES ONTO THE NEXT
 * KEY on a network error, so a request that is going to succeed can legitimately still be in flight
 * well past a minute. A client timeout under that would abort calls that were about to answer and
 * report them as "timed out" — the same class of lie the rest of this work exists to end, with the
 * added cost that the deployment has already paid the provider for the reading that got thrown away.
 *
 * A TIMEOUT AT ALL, RATHER THAN NONE, because a connection that opens and then stalls never rejects
 * `fetch` on its own — a captive portal, a lift, a tower handover halfway through an 8 MB upload. Until
 * this existed the status line said "Analyzing…" until the designer navigated away, which is the one
 * outcome with no sentence at all.
 *
 * True on 2026-08-27; re-check the server's half with `grep -n "timeout=90" backend/app/services/ai.py`.
 */
export const MEASUREMENT_TIMEOUT_MS = 120_000;

/**
 * Send one grid photograph to be read, and wait no longer than {@link MEASUREMENT_TIMEOUT_MS}.
 *
 * THROWS RATHER THAN RETURNING A VERDICT, and the classification lives in `lib/measurementFailure.ts`
 * — `classifyMeasurementFailure` for what this throws, `measurementBodyFailure` for the two 200 bodies
 * that are failures anyway. Kept apart so that this function is only the wire and the sentences are
 * only in one place; `components/media/gridProposal.ts` is the caller that reads both.
 *
 * @param options.signal the CALLER's cancellation — a replaced photograph, an unmounted component. It
 *   aborts the request like the timeout does and is deliberately NOT reported as a timeout.
 */
export async function analyzeMeasurementImage(
  file: File,
  dimension?: "length" | "breadth" | "height",
  options: { timeoutMs?: number; signal?: AbortSignal } = {}
) {
  const form = new FormData();
  form.append("file", file);
  const query = dimension ? `?dimension=${dimension}` : "";
  const timeoutMs = options.timeoutMs ?? MEASUREMENT_TIMEOUT_MS;

  const controller = new AbortController();
  let weStoppedWaiting = false;
  const timer = setTimeout(() => {
    weStoppedWaiting = true;
    controller.abort();
  }, timeoutMs);
  const relay = () => controller.abort();
  options.signal?.addEventListener("abort", relay);

  try {
    return await apiFetch<MeasurementAnalysisResponse>(`/media/analyze-measurement${query}`, {
      method: "POST",
      body: form,
      signal: controller.signal
    });
  } catch (error) {
    // The flag is read rather than the rejection, for the reason on {@link MeasurementTimeoutError}.
    // Anything else — including the caller's own abort — is re-thrown exactly as it arrived, so the
    // triage sees the `ApiError` or the `TypeError` the server or the transport actually produced.
    if (weStoppedWaiting) throw new MeasurementTimeoutError(timeoutMs);
    throw error;
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener("abort", relay);
  }
}

/**
 * Read ONLY the capture clock off an image, for the bulk photo intake (`lib/photoIntake.ts`).
 *
 * A separate reader from {@link extractImageExifMetadata} rather than a second caller of it, for two
 * reasons that both matter at two hundred files:
 *
 *  - **`pick` stops the parse at the two tags wanted.** The full reader asks for the whole TIFF, EXIF
 *    and GPS blocks so it can write a provenance remark; this one names its tags, so exifr reads the
 *    APP1 segment header and stops. Over a camera dump that is the difference between a spinner and
 *    a list.
 *  - **`reviveValues: false` is load-bearing here, not a preference.** Left on, exifr hands back a
 *    `Date` built by parsing the naive EXIF string in the BROWSER'S timezone — which is precisely the
 *    off-by-hours bug `photoIntake.ts` exists to avoid, arriving before that module ever sees the
 *    value. Measured, on one hand-built JPEG whose `DateTimeOriginal` reads `2026:02:14 19:40:12`:
 *    with `reviveValues: true` exifr returns `2026-02-14T14:10:12.000Z` on a machine set to IST and
 *    `2026-02-14T19:40:12.000Z` on one set to UTC — the same file, two different instants, decided by
 *    the reader's laptop. The raw string is passed through untouched and parsed in `photoIntake.ts`,
 *    where the timezone rule is written down and tested.
 *
 * NOTHING IS WRITTEN, DECODED OR RE-ENCODED. exifr reads bytes out of the file; the `File` handed in
 * is the same object that is later uploaded, unchanged. `docs/MEDIA_PIPELINE.md` §5 records why this
 * repository refuses to re-encode an image at all — the original IS the artifact — and a feature that
 * read EXIF by round-tripping a canvas would strip the very tag it came for.
 *
 * Returns nulls rather than throwing. A file with no EXIF, a PNG screenshot, a truncated download and
 * a format exifr cannot parse are all ordinary here, and all of them must reach the intake table as a
 * row a designer can assign by hand — never as a failed import.
 */
export async function readCaptureStamp(file: File): Promise<{ takenAt: string | null; takenAtOffset: string | null }> {
  const empty = { takenAt: null, takenAtOffset: null };
  if (!file.type.startsWith("image/")) return empty;
  try {
    const exifr = await import("exifr");
    const metadata = await exifr.parse(file, {
      // `tiff` must stay on: DateTimeOriginal lives in the EXIF IFD, which is reached THROUGH the
      // TIFF header. Picking the tag without it returns undefined on every file.
      tiff: true,
      exif: true,
      gps: false,
      pick: ["DateTimeOriginal", "OffsetTimeOriginal", "CreateDate"],
      reviveValues: false
    });
    if (!metadata) return empty;
    // CreateDate is the fallback the existing reader already accepts. It is the digitisation time
    // rather than the shutter time and the two differ only for scans — where DateTimeOriginal is
    // absent anyway, so taking it changes nothing for a photograph and rescues a scan.
    const takenAt = metadata.DateTimeOriginal ?? metadata.CreateDate ?? null;
    return {
      takenAt: typeof takenAt === "string" ? takenAt : null,
      takenAtOffset: typeof metadata.OffsetTimeOriginal === "string" ? metadata.OffsetTimeOriginal : null
    };
  } catch {
    return empty;
  }
}

export async function extractImageExifMetadata(file: File) {
  if (!file.type.startsWith("image/")) return null;
  try {
    const exifr = await import("exifr");
    const metadata = await exifr.parse(file, {
      tiff: true,
      exif: true,
      gps: true,
      reviveValues: false
    });
    if (!metadata) return null;
    const keys = [
      "Make",
      "Model",
      "DateTimeOriginal",
      "CreateDate",
      "ModifyDate",
      "latitude",
      "longitude",
      "GPSLatitude",
      "GPSLongitude",
      "GPSAltitude",
      "Orientation",
      "LensModel"
    ];
    return Object.fromEntries(keys.filter((key) => metadata[key] !== undefined).map((key) => [key, metadata[key]]));
  } catch {
    return null;
  }
}

export async function collectExifMetadata(files: File[]) {
  const results = await Promise.all(
    files.map(async (file) => {
      const metadata = await extractImageExifMetadata(file);
      return metadata ? { filename: file.name, metadata } : null;
    })
  );
  return results.filter(Boolean) as Array<{ filename: string; metadata: Record<string, unknown> }>;
}

export function exifMetadataToRemark(items: Array<{ filename: string; metadata: Record<string, unknown> }>) {
  if (!items.length) return "";
  return items
    .map(({ filename, metadata }) => {
      const camera = [metadata.Make, metadata.Model].filter(Boolean).join(" ");
      const captured = metadata.DateTimeOriginal ?? metadata.CreateDate ?? metadata.ModifyDate;
      const latitude = metadata.latitude ?? metadata.GPSLatitude;
      const longitude = metadata.longitude ?? metadata.GPSLongitude;
      return [
        `Image EXIF (${filename})`,
        camera ? `camera: ${camera}` : null,
        captured ? `captured: ${captured}` : null,
        latitude && longitude ? `gps: ${latitude}, ${longitude}` : null
      ]
        .filter(Boolean)
        .join("; ");
    })
    .join("\n");
}

export function appendRemarksWithExif(remarks: string | null, exifRemark: string) {
  const base = remarks?.trim() ?? "";
  if (!exifRemark) return base || null;
  return [base, exifRemark].filter(Boolean).join("\n\n");
}

// ---------------------------------------------------------------------------
// The media processing queue
//
// Audio uploads do not transcribe inline — `/media/complete` enqueues a
// MediaProcessingJob and a background worker (app/main.py -> app/worker.py)
// drains it. That works, so nobody noticed for months that the queue had no
// client at all: a job that exhausted its attempts sat in FAILED with its
// reason in a column no screen read, and there was no way to run it again from
// any client. A transcription that failed was indistinguishable from one that
// was never requested. These four calls are what make the queue visible and
// re-runnable; the panel that uses them is components/media/MediaJobsPanel.
// ---------------------------------------------------------------------------

/** MediaProcessingJobStatus in prisma/schema.prisma — the five states, verbatim. */
export type MediaProcessingJobStatus = "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED" | "CANCELLED";

/** MediaProcessingJobType in prisma/schema.prisma. */
export type MediaProcessingJobType = "TRANSCRIPTION" | "MEASUREMENT";

/**
 * One row of `GET /media/jobs`.
 *
 * The nested `mediaFile` comes back through `public_encode(jobs)` with NO viewer, so it is the
 * masked shape: **`url` and `objectKey` are absent from every job row**, for a master admin as much
 * as for anyone else. Hence `Pick<...>` rather than `MediaFile` — typing it as the full row would
 * compile and then render a dead player, which is exactly the lie lib/types warns about on
 * `MediaFile.url`. Use the media table (or `GET /media/{id}`) when the bytes are wanted.
 */
export type MediaProcessingJob = {
  id: string;
  jobType: MediaProcessingJobType | string;
  status: MediaProcessingJobStatus | string;
  priority: number;
  attempts: number;
  maxAttempts: number;
  /** WHY it failed, straight from the provider ("...(NoSuchKey)... The specified key does not exist"). */
  error?: string | null;
  result?: unknown;
  /** Set while a worker holds the job; a crashed worker's lock is recovered by the queue itself. */
  lockedAt?: string | null;
  lockedBy?: string | null;
  /** Backoff gate — a QUEUED job with a future `runAfter` is waiting out a retry delay, not stuck. */
  runAfter?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt: string;
  mediaFileId: string;
  mediaFile?: Pick<MediaFile, "id" | "originalFilename" | "mediaType" | "mimeType" | "transcriptStatus"> | null;
  requestedById?: string | null;
  requestedBy?: { id: string; name?: string | null; email?: string | null } | null;
};

/** `POST /media/jobs/process` — how much of the queue this drain actually moved. */
export type MediaQueueRunResult = { processed: number; succeeded: number; failed: number };

/**
 * `GET /media/jobs` — open to every signed-in account, but **scoped server-side**: an admin sees
 * every job, everyone else sees only the jobs they requested (`requestedById == me`). So this needs
 * no client-side permission check, and a panel that hid it from non-admins would hide a
 * contributor's own failed transcription from the only person who noticed it was missing.
 */
export function listMediaProcessingJobs(params: {
  page?: number;
  pageSize?: number;
  statusFilter?: MediaProcessingJobStatus | string | null;
}) {
  return listResource<MediaProcessingJob>("/media/jobs", {
    page: params.page,
    pageSize: params.pageSize,
    statusFilter: params.statusFilter || undefined
  });
}

/**
 * `POST /media/jobs/{id}/retry` — back to QUEUED, `runAfter` now, lock and error cleared, so the
 * next drain picks it up. **`require_admin`**: gate any control that calls this on `isAdmin(user)`,
 * not on ownership — the uploader of the failed file may not re-run it.
 */
export function retryMediaProcessingJob(jobId: string) {
  return apiFetch<MediaProcessingJob>(`/media/jobs/${jobId}/retry`, { method: "POST" });
}

/**
 * `POST /media/jobs/process` — drain up to `limit` jobs (1–25) synchronously instead of waiting for
 * the background worker's next tick. **`require_admin`.**
 *
 * TRANSCRIPTION jobs may still be skipped even when this returns: the queue only runs them inside
 * the configured off-peak window or while the server is idle, and never during a rate-limit
 * cooldown. `processed: 0` therefore means "nothing was eligible right now", not "nothing to do" —
 * say so, or an admin presses this twice and concludes the button is broken.
 */
export function runMediaProcessingQueue(limit?: number) {
  const query = limit ? `?limit=${limit}` : "";
  return apiFetch<MediaQueueRunResult>(`/media/jobs/process${query}`, { method: "POST" });
}

/**
 * `POST /media/{id}/transcribe-now` — transcribe this audio file on the spot, applying the
 * settings-page transcription mode and bypassing both the queue and the off-peak window. Returns
 * the updated MediaFile.
 *
 * **`require_admin`, and the route 400s anything that is not AUDIO**, so a caller must check both
 * before offering the control. Android has offered this since it shipped
 * (MainActivity `MediaWithTranscript` -> `TranscribeNowButton`, labelled "Transcribe now" /
 * "Re-transcribe now"); the web had no equivalent, so a researcher who could re-run a transcript on
 * a phone could not do it at a desk.
 */
export function transcribeMediaNow(mediaId: string) {
  return apiFetch<MediaFile>(`/media/${mediaId}/transcribe-now`, { method: "POST" });
}
