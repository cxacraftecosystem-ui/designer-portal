export const MEDIA_NOTE_GRID_PURPOSE = "MEASUREMENT_GRID";

const MEDIA_NOTE_WORDS: readonly { tokens: readonly string[]; one: string; many: string }[] = [
  { tokens: ["IMAGE"], one: "photograph", many: "photographs" },
  { tokens: ["VIDEO"], one: "video", many: "videos" },
  { tokens: ["AUDIO"], one: "audio note", many: "audio notes" },
  { tokens: ["PDF", "DOCUMENT"], one: "document", many: "documents" },
  { tokens: ["OTHER"], one: "file", many: "files" }
];

const KNOWN_TOKENS = new Set(MEDIA_NOTE_WORDS.flatMap((entry) => entry.tokens));

export type MediaNoteRow = {
  mediaType?: string | null;
  originalFilename?: string | null;
  extraMetadata?: unknown;
};

export function isMeasurementGridRow(row: MediaNoteRow): boolean {
  const meta = row.extraMetadata;
  if (!meta || typeof meta !== "object" || Array.isArray(meta)) return false;
  const purpose = (meta as Record<string, unknown>).purpose;
  return typeof purpose === "string" && purpose === MEDIA_NOTE_GRID_PURPOSE;
}

export function countableMediaRows<T extends MediaNoteRow>(rows: readonly T[]): T[] {
  return rows.filter((row) => !isMeasurementGridRow(row));
}

export function mediaNoteBreakdown(rows: readonly MediaNoteRow[]): string {
  const counts = new Map<string, number>();
  for (const row of countableMediaRows(rows)) {
    const token = typeof row.mediaType === "string" ? row.mediaType : "";
    counts.set(token, (counts.get(token) ?? 0) + 1);
  }
  const parts: string[] = [];
  for (const entry of MEDIA_NOTE_WORDS) {
    let total = entry.tokens.reduce((sum, token) => sum + (counts.get(token) ?? 0), 0);
    if (entry.tokens.includes("OTHER")) {
      for (const [token, n] of counts) if (!KNOWN_TOKENS.has(token)) total += n;
    }
    if (total) parts.push(`${total} ${total === 1 ? entry.one : entry.many}`);
  }
  return parts.join(", ");
}

export function makingSequenceCount(rows: readonly MediaNoteRow[], numberedPrefix: string): number {
  if (!numberedPrefix) return 0;
  return countableMediaRows(rows).filter(
    (row) => typeof row.originalFilename === "string" && row.originalFilename.startsWith(numberedPrefix)
  ).length;
}

export function composeRecordCount({
  subject,
  rows,
  numberedPrefix = ""
}: {
  subject: string;
  rows: readonly MediaNoteRow[];
  numberedPrefix?: string;
}): string | null {
  const breakdown = mediaNoteBreakdown(rows);
  if (!breakdown) return null;
  let note = `Attached to the ${subject} record: ${breakdown}`;
  const numbered = makingSequenceCount(rows, numberedPrefix);
  if (numbered) {
    note += `, of which ${numbered} ${numbered === 1 ? "documents" : "document"} the making in order`;
  }
  return `${note}.`;
}

export const POINTER_CLAUSE_OPENER = "See in particular: ";

const POINTER_CLAUSE = /\s*See in particular:[^.]*\.\s*$/i;

const STANDALONE_POINTER = /\s*Of the media attached to the [^.]*record, see:[^.]*\.\s*$/i;

export function stripPointerNote(value: string): string {
  return value.replace(POINTER_CLAUSE, "").replace(STANDALONE_POINTER, "").trim();
}

export function hasPointerNote(value: string): boolean {
  return POINTER_CLAUSE.test(value) || STANDALONE_POINTER.test(value);
}

export function composePointerNote({
  subject,
  base,
  rows
}: {
  subject: string;
  base: string;
  rows: readonly MediaNoteRow[];
}): string | null {
  const breakdown = mediaNoteBreakdown(rows);
  if (!breakdown) return null;
  const kept = stripPointerNote(base);
  if (!kept) return `Of the media attached to the ${subject} record, see: ${breakdown}.`;
  const joined = /[.!?]$/.test(kept) ? kept : `${kept}.`;
  return `${joined} ${POINTER_CLAUSE_OPENER}${breakdown}.`;
}

export function mediaNoteOverrun(value: string, maxLength?: number): number {
  if (!maxLength) return 0;
  return Math.max(0, value.length - maxLength);
}
