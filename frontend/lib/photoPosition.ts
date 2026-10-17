/**
 * The position and time a photograph carries in its EXIF (owner, A21, 2026-10-17).
 *
 * Both apps already read this off photos and used it only in a remark; the venue box can now take
 * it in one tap. A photo with no GPS answers null — never a guessed place.
 */

import { extractImageExifMetadata } from "@/lib/media";

export type PhotoPosition = { lat: number; lon: number; takenAt: string | null };

function finite(value: unknown): number | null {
  const n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

/** "2026:10:12 10:42:05" (EXIF) or an ISO string, as "12 Oct 2026, 10:42"; null when unreadable. */
export function formatExifTime(raw: unknown): string | null {
  const match = /^(\d{4})[:-](\d{2})[:-](\d{2})[ T](\d{2}):(\d{2})/.exec(String(raw ?? ""));
  if (!match) return null;
  const [, y, m, d, hh, mm] = match;
  const month = new Date(Number(y), Number(m) - 1, 1).toLocaleString("en-IN", { month: "short" });
  return `${Number(d)} ${month} ${y}, ${hh}:${mm}`;
}

export function positionFromExif(metadata: Record<string, unknown> | null): PhotoPosition | null {
  if (!metadata) return null;
  const lat = finite(metadata.latitude);
  const lon = finite(metadata.longitude);
  if (lat === null || lon === null || Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat === 0 && lon === 0)) return null;
  return { lat, lon, takenAt: formatExifTime(metadata.DateTimeOriginal ?? metadata.CreateDate) };
}

export async function photoPosition(file: File): Promise<PhotoPosition | null> {
  return positionFromExif(await extractImageExifMetadata(file));
}
