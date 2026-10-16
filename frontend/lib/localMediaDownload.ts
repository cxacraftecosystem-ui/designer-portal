/**
 * SAVING A FILE THE PERSON IS STILL UPLOADING — from the copy this browser already holds.
 *
 * Every surface that shows a file on its way to the server (the capture tiles, the questionnaire's
 * recorded clips, a stage's on-this-device files, the offline outbox, a dictation being sent) offers
 * a Download button that reads THESE bytes, not the server's. So a designer can keep their own
 * recording the moment it exists, whatever the connection is doing, and the upload is never asked
 * to wait: nothing here touches a transfer, a queue entry or a staged object.
 *
 * `downloadLocalMedia` is the name the documentation portal's twin of this module uses as well, so
 * the two can be lifted into shared code without a rename. The pure halves —
 * {@link extensionForMime} and {@link localMediaFileName} — are exported for
 * `e2e/local-media-download-unit.spec.ts`; there is no React renderer in devDependencies, so the
 * naming rules are tested here rather than through a button.
 */

import { saveBlobToDisk } from "@/lib/fileDownload";

/**
 * The extension a saved file should carry for a mime type, or null when there is no sensible one.
 *
 * Parameters are dropped first: MediaRecorder reports `audio/webm;codecs=opus`, and the codec is not
 * part of the container's name. Matched exactly, never by substring, so `application/vnd.foo-pdf`
 * does not become a `.pdf`.
 */
const EXTENSION_BY_MIME: Record<string, string> = {
  "audio/webm": "webm",
  "audio/ogg": "ogg",
  "audio/mp4": "m4a",
  "audio/x-m4a": "m4a",
  "audio/aac": "aac",
  "audio/mpeg": "mp3",
  "audio/mp3": "mp3",
  "audio/wav": "wav",
  "audio/x-wav": "wav",
  "audio/wave": "wav",
  "audio/flac": "flac",
  "audio/3gpp": "3gp",
  "audio/amr": "amr",
  "video/webm": "webm",
  "video/mp4": "mp4",
  "video/quicktime": "mov",
  "video/3gpp": "3gp",
  "video/x-matroska": "mkv",
  "image/jpeg": "jpg",
  "image/jpg": "jpg",
  "image/png": "png",
  "image/webp": "webp",
  "image/gif": "gif",
  "image/heic": "heic",
  "image/heif": "heif",
  "image/svg+xml": "svg",
  "image/tiff": "tiff",
  "application/pdf": "pdf",
  "application/msword": "doc",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
  "application/vnd.ms-excel": "xls",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "xlsx",
  "application/vnd.ms-powerpoint": "ppt",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation": "pptx",
  "application/zip": "zip",
  "text/plain": "txt",
  "text/csv": "csv",
  "application/json": "json",
  "model/gltf-binary": "glb"
};

export function baseMimeType(mimeType: string | null | undefined): string {
  return (mimeType ?? "").split(";")[0].trim().toLowerCase();
}

export function extensionForMime(mimeType: string | null | undefined): string | null {
  const base = baseMimeType(mimeType);
  if (!base) return null;
  return EXTENSION_BY_MIME[base] ?? null;
}

/** What an unnamed file is called, by kind: a recording is a voice note, anything else by its type. */
function stemForMime(mimeType: string | null | undefined): string {
  const base = baseMimeType(mimeType);
  if (base.startsWith("audio/")) return "voice-note";
  if (base.startsWith("video/")) return "video";
  if (base.startsWith("image/")) return "photo";
  return "file";
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

/** `2026-10-16-143005`, in the device's own clock — the moment a person would say they recorded it. */
export function fileDateStamp(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}-${pad(date.getHours())}${pad(
    date.getMinutes()
  )}${pad(date.getSeconds())}`;
}

/** Characters no common file system accepts in a name, and control characters. */
const UNSAFE_NAME = /[\\/:*?"<>|\u0000-\u001f]+/g;

/** Names a browser or a recorder gives a file nobody named. Treated as absent. */
const PLACEHOLDER_NAMES = new Set(["blob", "untitled"]);

/**
 * The name a downloaded local file is saved under.
 *
 * - The file's own name when it has one, made safe for a file system, with the mime type's
 *   extension added only when the name carries none.
 * - Otherwise `voice-note-<date>.<ext>` for a recording (`video-`, `photo-` or `file-` for the
 *   rest), dated from `capturedAt` when the caller knows it and from now when it does not.
 * - `.bin` only when neither the name nor the mime type says anything about the bytes.
 */
export function localMediaFileName({
  name,
  mimeType,
  capturedAt,
  now = new Date()
}: {
  name?: string | null;
  mimeType?: string | null;
  capturedAt?: number | Date | null;
  now?: Date;
}): string {
  const extension = extensionForMime(mimeType);
  const cleaned = (name ?? "").replace(UNSAFE_NAME, "-").replace(/\s+/g, " ").trim().replace(/^\.+/, "");
  const stem = cleaned.replace(/\.[A-Za-z0-9]{1,8}$/, "");
  if (cleaned && !PLACEHOLDER_NAMES.has(stem.toLowerCase())) {
    const hasExtension = /\.[A-Za-z0-9]{1,8}$/.test(cleaned);
    if (hasExtension || !extension) return cleaned;
    return `${cleaned}.${extension}`;
  }
  const when = capturedAt instanceof Date ? capturedAt : typeof capturedAt === "number" ? new Date(capturedAt) : now;
  const stamp = fileDateStamp(Number.isFinite(when.getTime()) ? when : now);
  return `${stemForMime(mimeType)}-${stamp}.${extension ?? "bin"}`;
}

/**
 * Save a file this browser holds — an in-memory `File`/`Blob`, or one read back out of IndexedDB —
 * to the person's device. Returns the name it was saved under.
 *
 * The object URL is built from the blob and revoked on the next task by `saveBlobToDisk` (never in
 * the click's own tick, which Safari answers by saving nothing). Reading a Blob does not consume it,
 * so the upload that holds the same object carries on untouched.
 */
export function downloadLocalMedia(
  source: Blob,
  options: { name?: string | null; mimeType?: string | null; capturedAt?: number | Date | null } = {}
): string {
  const fileName = localMediaFileName({
    name: options.name ?? (source instanceof File ? source.name : null),
    mimeType: options.mimeType || source.type,
    capturedAt: options.capturedAt ?? (source instanceof File && source.lastModified ? source.lastModified : null)
  });
  saveBlobToDisk(source, fileName);
  return fileName;
}

/**
 * Save a file that has finished uploading, from the URL the server handed back for it.
 *
 * Fetched into a blob first so the browser honours the filename (a cross-origin `download`
 * attribute is ignored); when the store will not answer a `fetch` — a bucket without CORS for this
 * origin — the URL is opened instead, which still lets the person save it.
 */
export async function downloadMediaUrl(url: string, name?: string | null, mimeType?: string | null): Promise<void> {
  const fileName = localMediaFileName({ name, mimeType });
  try {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const blob = await response.blob();
    saveBlobToDisk(blob, fileName);
  } catch {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = fileName;
    anchor.target = "_blank";
    anchor.rel = "noreferrer";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  }
}
