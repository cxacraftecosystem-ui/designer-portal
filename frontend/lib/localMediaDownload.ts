import { saveBlobToDisk } from "@/lib/fileDownload";
import { recordingFilename } from "@/lib/naming";

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

function whatForMime(mimeType: string | null | undefined): string {
  const base = baseMimeType(mimeType);
  if (base.startsWith("audio/")) return "Recording";
  if (base.startsWith("video/")) return "Video";
  if (base.startsWith("image/")) return "Photo";
  return "File";
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

export function fileDateStamp(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}-${pad(date.getHours())}${pad(
    date.getMinutes()
  )}${pad(date.getSeconds())}`;
}

const UNSAFE_NAME = /[\\/:*?"<>|\u0000-\u001f]+/g;

const PLACEHOLDER_NAMES = new Set(["blob", "untitled"]);

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
  return recordingFilename(null, extension ?? "bin", {
    capturedAt: Number.isFinite(when.getTime()) ? when : now,
    what: whatForMime(mimeType)
  });
}

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
