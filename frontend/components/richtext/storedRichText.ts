/**
 * Rich text in a column that is, and stays, `String?`.
 *
 * THE CONSTRAINT, WHICH IS NOT NEGOTIABLE HERE. `Artisan.notes`, `ProductDocumentation.remarks`,
 * `ToolDocumentation.remarks` and their siblings are `String?` in `prisma/schema.prisma` and
 * `str | None` in `app/schemas/records.py`. There is no migration in this change and there must not
 * be one: eleven raw Prisma `contains` clauses read these columns for free-text search, `cell()` in
 * `record_fields.py` puts them straight into four CSV/XLSX surfaces, and the review edit registry
 * renders them as plain multiline text on two platforms. Every one of those readers treats the value
 * as prose.
 *
 * THE TRAP THIS FILE EXISTS TO AVOID. `rich_text.from_json` (backend), `fromStored` (here) and
 * `RichText.fromStored` (Android) all read a `str` as PLAIN PROSE — none of them attempts a
 * `json.loads` first, and the comment on each says why: a string IS the pre-promotion value and
 * re-reading it as JSON would blank prose written before the field was promoted. So a document
 * stringified into one of these columns comes back out as literal `{"blocks":[{"kind":…` in a CSV,
 * in a report, in the review panel and in the Android form. Silent, not a crash — the repository's
 * favourite kind of defect.
 *
 * THE RULE, THEREFORE: **a document is only ever stringified when it is not expressible as plain
 * text.** {@link encodeStoredRichText} flattens an unformatted document with `toPlain` and writes
 * the prose; it writes JSON only once the researcher has actually applied a mark, a heading, a list,
 * a quote, an alignment, a table or an inline photograph. The consequences of that split are worth
 * being explicit about, because somebody will "simplify" it into an unconditional `JSON.stringify`:
 *
 *   - The overwhelming majority of records are typed and dictated plainly. Those columns keep
 *     EXACTLY the bytes they keep today, so search, exports, the review panel and the Android forms
 *     see no change at all and need no coordinated release.
 *   - A record where somebody bolded a word stores a document, and every reader that has not yet
 *     learnt to flatten will show braces for that one record. That is the known, bounded cost of
 *     shipping the editor before the read-side flattening lands everywhere; making it unconditional
 *     would move the cost from "records that were formatted" to "every record".
 *   - {@link decodeStoredRichText} is the counterpart and MUST be used on the way in, or the editor
 *     itself becomes one of those braces-showing readers the first time a formatted record is
 *     re-opened.
 *
 * `toPlain` is the same flattener the report builder and the search index use (`to_plain` in
 * `rich_text.py`, `toPlain` in `lib/richText.ts`), list markers included — so "unformatted" here
 * means the round trip is lossless, not merely close.
 */

import {
  fromStored,
  isEmptyDoc,
  toPlain,
  toStored,
  type RichBlock,
  type StoredRichDoc
} from "@/lib/richText";

/**
 * How the blocks of an UNFORMATTED document are joined when it is written back as prose.
 *
 * "line" matches `toPlain` exactly and is right for a narrative column. "paragraph" joins with a
 * blank line and exists for one reason: `Artisan.notes` and the process form's step notes have a
 * settled `"\n\n"`-separated contract — `MultiNoteField` here and `MultiNoteInput` in Android's
 * `MainActivity.kt` both SPLIT on blank lines to rebuild the note rows. Writing single newlines into
 * one of those columns would silently collapse four notes into one the next time it is opened in a
 * multi-note control. Reading is symmetric either way, because `fromPlainText` drops blank lines.
 */
export type PlainBlockJoin = "line" | "paragraph";

/**
 * Whether every block in this document survives a trip through plain text unchanged.
 *
 * Deliberately strict, and deliberately a whitelist of "nothing interesting is set" rather than a
 * blacklist of known-lossy features: a new block kind or a new mark added to `lib/richText.ts` must
 * default to "store as JSON", never to "quietly drop it". The failure of the other polarity is
 * invisible — a researcher's table would flatten to pipe-separated lines on save and they would not
 * find out until the report was printed.
 */
function isPlainProse(blocks: readonly RichBlock[]): boolean {
  return blocks.every(
    (block) =>
      block.kind === "PARAGRAPH" &&
      block.level === 0 &&
      block.align === "LEFT" &&
      !block.media &&
      !block.rows.length &&
      block.spans.every((span) => !span.marks.length)
  );
}

/**
 * What the editor should be handed, from what the API returned.
 *
 * A column holds one of three things and all three genuinely occur: `null`, prose (everything
 * written before this change, and everything written since that nobody formatted), or the JSON
 * encoding of a document. Only the third needs unpicking, and it is recognised by SHAPE rather than
 * by a marker byte — a `{"blocks":[…]}` object is not a thing a researcher types into a notes box,
 * whereas a sentinel prefix would be one more thing every other reader would have to know about.
 *
 * A string that parses as JSON but is NOT a block document — `"42"`, `"[1,2,3]"`, a pasted config
 * snippet — falls through to prose, which is what it is.
 */
export function decodeStoredRichText(raw: string | null | undefined): unknown {
  if (raw === null || raw === undefined) return null;
  const trimmed = raw.trim();
  if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return raw;
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (parsed && typeof parsed === "object" && Array.isArray((parsed as { blocks?: unknown }).blocks)) {
      return parsed;
    }
  } catch {
    /* Prose that merely begins with a brace. Falls through and is read as what it is. */
  }
  return raw;
}

/**
 * What goes in the hidden input the form submits — see the file header for the whole argument.
 *
 * Returns `""` and never `null` because this feeds an `<input value=…>`; the forms' own
 * `textValue`/`requiredText` helpers turn an empty string into the null the API expects, exactly as
 * they do for an empty `<textarea>` today.
 */
export function encodeStoredRichText(stored: StoredRichDoc | null, join: PlainBlockJoin = "line"): string {
  if (!stored) return "";
  const doc = fromStored(stored);
  if (isEmptyDoc(doc)) return "";
  if (!isPlainProse(doc.blocks)) return JSON.stringify(toStored(doc));
  const plain = toPlain(doc);
  return join === "paragraph" ? plain.split("\n").join("\n\n") : plain;
}

/**
 * Append a machine-written paragraph to a value that may be prose or may be a document.
 *
 * THIS IS NOT DECORATION — IT IS THE BUG THAT WOULD OTHERWISE SHIP. `appendRemarksWithExif` in
 * `lib/media.ts` joins the EXIF summary onto the end of `Artisan.notes`, `ProductDocumentation.
 * remarks` and `ToolDocumentation.remarks` at submit time. Concatenating `"\n\nPhoto 1 taken at…"`
 * onto a JSON string produces a value that is neither valid JSON nor readable prose: the editor
 * would fail to parse it and show the researcher raw braces followed by their EXIF note, and every
 * downstream reader would show the same. So the three forms that do this call THIS function instead,
 * which appends INTO the document when there is one.
 *
 * The prose branch is byte-for-byte what `appendRemarksWithExif` does, blank-line join included, so
 * an unformatted record is unchanged.
 */
export function appendStoredParagraph(
  stored: string | null | undefined,
  addition: string,
  join: PlainBlockJoin = "line"
): string | null {
  const base = stored?.trim() ?? "";
  if (!addition) return base || null;
  const decoded = decodeStoredRichText(base || null);
  if (decoded === null || typeof decoded === "string") {
    return [decoded ?? "", addition].filter(Boolean).join("\n\n") || null;
  }
  const doc = fromStored(decoded);
  const appended = fromStored({
    blocks: [
      ...toStored(doc).blocks,
      // One paragraph per line of the addition, matching `fromPlainText`. The EXIF summary is
      // multi-line, and a single span containing "\n" is the one thing `fromStored` has to repair
      // (it replaces the newline with a space) — which would run two photographs' notes together.
      ...addition
        .split("\n")
        .filter((line) => line.trim())
        .map((line) => ({ kind: "PARAGRAPH" as const, spans: [{ text: line.trim() }] }))
    ]
  });
  return encodeStoredRichText(toStored(appended), join) || null;
}

/**
 * A stored rich-text column as the words in it — prose returned untouched, a document flattened.
 *
 * ── THE READ BOUNDARY, AND WHY IT NOW LIVES IN THE SHARED MODULE ──────────────────────────────
 * {@link encodeStoredRichText} writes JSON into a `String?` column the moment somebody applies a
 * mark, and the header of this file sets out the bounded cost that buys: a reader that has not
 * learnt to flatten shows `{"blocks":[{"kind":…` for that one record. Every surface that mounts
 * `RichTextField` decodes for itself; a surface that RENDERS one of these columns without an editor
 * has to flatten, and there was exactly one of those until 2026-08-31: the designer profile's own
 * private address reader, which set the condition for its own deletion in its header —
 * *"When the shared module grows one, delete this and import that: two spellings of a read boundary
 * is how the two come to disagree about what a document is."*
 *
 * **That condition was met and that module is gone.** The designer profile now imports this
 * function, and the two bodies were character-for-character identical when they were merged, so
 * nothing about what an address renders as has changed. The sentence is quoted rather than dropped
 * because it is the ARGUMENT for this module existing, and whoever meets a third such surface should
 * meet it too. (Its path is deliberately not written out: `docs/tools/check-docs.mjs` opens every
 * backticked path it finds, so naming a deleted file here would fail the docs gate.)
 *
 * The questionnaire is the second, which is what makes this the shared one. `QuestionnaireResponse.
 * answerText` became a rich-text column when the interview form's answer boxes did, and the
 * consolidated interview page renders those answers as prose for a reader who is quoting them into a
 * ministry report. Braces there are not a cosmetic defect: they are the artisan's answer, unreadable,
 * on the surface the report is assembled from.
 *
 * Returns `""` for null, undefined and an empty column, so callers keep their own blank wording — the
 * consolidated page already draws an em dash for an unanswered question and must go on drawing it.
 *
 * The flattening is `toPlain`, the same flattener the report builder and the search index use, list
 * markers included, so what this prints is what the .docx would print rather than an approximation.
 */
export function plainFromStoredRichText(raw: string | null | undefined): string {
  if (raw === null || raw === undefined) return "";
  const decoded = decodeStoredRichText(raw);
  // Prose, and the string identity is the point: a value that merely begins with a brace but is not
  // a block document has already fallen through inside `decodeStoredRichText`, which is what it is —
  // somebody's typing.
  if (decoded === null) return "";
  if (typeof decoded === "string") return decoded;
  return toPlain(fromStored(decoded));
}
