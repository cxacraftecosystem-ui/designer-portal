/**
 * The sentence a `*MediaNote` field holds, composed on the client — the pure half of
 * {@link StageMediaNoteField}.
 *
 * ── WHY THIS FILE EXISTS AT ALL ─────────────────────────────────────────────────────────────────
 *
 * The owner asked for "Media on the artisan record" to be a multi-select. That box is
 * `participant.recordMediaNote`, declared `TEXT` with `max_length=200`
 * (`backend/app/services/stage_definitions.py:598`), and what lands in it is not a name, an id or an
 * enum: it is a SENTENCE a server function composes by counting the files attached to the linked
 * record — `_media_note` in `backend/app/services/design_workshops.py:494`, called through
 * `REFERENCE_MODELS["Artisan"].data` and copied onto the stage entry at SAVE time. Four fields share
 * that function (`participant.recordMediaNote`, `workshopSetup.craftMediaNote`,
 * `tool.recordMediaNote`, `existingProduct.recordMediaNote`); the fifth,
 * `traditionalProcess.recordMediaNote`, is filled by a DIFFERENT function with a DIFFERENT grammar
 * and is refused this control in writing — see `recordMediaNoteRole` in `stageFieldRoles.ts`.
 *
 * So a multi-select over that field cannot store "the thing that was picked" the way
 * `StageWorkshopField` stores a `Workshop.title`. There is no lossless one-to-one between a picked
 * media row and any part of the stored string — the string is a COUNT. A control that writes into it
 * has to write a sentence, which means this file: the same grammar, on the client, so that what the
 * control composes and what hydration wrote are the same kind of sentence rather than two dialects.
 *
 * ── THE ONE RULE THIS FILE OBEYS, AND IT IS THE WHOLE DESIGN ────────────────────────────────────
 *
 * **THE RECORD'S OWN COUNT IS NEVER REWRITTEN INTO A SMALLER ONE.** Re-running `_media_note`'s
 * grammar over a designer's SUBSET is the obvious move and it is the one thing that must not happen:
 * "Attached to the artisan record: 1 photograph." against a record that holds four is a false claim,
 * printed in a document that goes to a Development Commissioner's office, and the field exists
 * precisely so a reader knows what to ask for. Under-counting it is worse than not narrowing at all.
 *
 * What a narrowing designer is actually doing is POINTING — "of the fourteen files on this artisan's
 * record, these three are the ones the reader should ask for" — so that is what the sentence says.
 * {@link composePointerNote} keeps the record's own count intact as the first sentence and appends a
 * second one. Both sentences are true, the claim about the record is byte-identical to the one
 * hydration wrote, and nothing has to be migrated for it to be legal: the field is TEXT either way.
 *
 * ── AND THE SELECTION ITSELF CANNOT BE STORED, WHICH IS WHY IT IS SPELLED OUT IN THE SENTENCE ───
 *
 * There is nowhere to put the picked ids. `DwStageEntry.data` is validated against the registry, so
 * an extra key lands in `droppedKeys` and is gone; the registry declares no id list here and cannot
 * grow one cheaply (see the media-note report of 2026-08-24 for the live-JSON value migration a kind
 * change costs). `ExistingMedia`'s own header makes the same point about the record side: "no record
 * type has a column for 'which of my files are the chosen ones'". So the SENTENCE is the only memory
 * of the pick, and {@link stripPointerNote} is what lets a later visit read it back, edit it and
 * replace it rather than accumulate clauses. What cannot be recovered on a later visit is WHICH files
 * were ticked — only how many of each kind — and `StageMediaNoteField` says so on screen rather than
 * letting a designer discover an empty chooser over a narrowed sentence.
 *
 * ── BYTE-IDENTICAL, AND THAT IS TESTED RATHER THAN ASSERTED ─────────────────────────────────────
 *
 * {@link composeRecordCount} is a port of `_media_note` and is pinned against the exact string the
 * backend's own test pins (`backend/tests/test_reference_carry.py:1930` —
 * "Attached to the artisan record: 1 photograph, 1 audio note.") in
 * `e2e/stage-media-note-unit.spec.ts`. The identity matters for one concrete reason: the control
 * offers "use the count of the files listed here", and an offer that writes a differently-spelled
 * version of the value already in the box would turn a designer pressing "put it back" into a
 * designer silently authoring a new value.
 *
 * PURE, AND SEPARATE FROM THE COMPONENT FOR THE REASON `components/ui/selectFilter.ts` IS. There is
 * no React renderer in this repository's devDependencies — Playwright is the whole of it — so a
 * judgement made inside JSX is only ever exercised by somebody looking at a screen. Everything here
 * is called directly by `e2e/stage-media-note-unit.spec.ts`.
 */

/**
 * The `extraMetadata.purpose` marker a measurement-grid frame carries.
 *
 * DECLARED HERE AND PINNED IN THE SPEC RATHER THAN IMPORTED, and the asymmetry is deliberate: the
 * canonical spelling is `MEASUREMENT_GRID_PURPOSE` in `components/media/GridMeasurement.tsx`, but
 * that module is a React component with a camera, a vision request and a file picker in it, and this
 * file is the pure half a unit spec imports directly. `e2e/stage-media-note-unit.spec.ts` asserts the
 * two are equal, which is the same arrangement — one enforced spelling, no cross-layer import — that
 * `inline-record-host-unit.spec.ts` already uses for that constant.
 *
 * A THREE-SURFACE CONTRACT, NOW FOUR. The web writes it (`ProductForm`, `ToolForm`), Android writes
 * the identical string, the server sorts on it and `_media_note` SUBTRACTS it. A sheet of ruled paper
 * photographed to fill a dimension box is not footage of the subject, so it is not counted — and it
 * must not be OFFERED either, or the chooser would list a frame the sentence deliberately excludes
 * and the two would disagree by one on exactly the records that were measured most carefully.
 */
export const MEDIA_NOTE_GRID_PURPOSE = "MEASUREMENT_GRID";

/**
 * What one attached file is counted as, in the order the sentence names them.
 *
 * A LINE-FOR-LINE PORT OF `_MEDIA_NOTE_WORDS`, including the two decisions that look like untidiness
 * and are not. PDF and DOCUMENT collapse onto one word because the difference is a mime type and not
 * a fact a reader of the printed report can act on — "2 documents" is what they would ask the
 * researcher for either way. OTHER keeps a word of its own ("file") rather than being folded into
 * documents, because it is the bucket `media.py` puts anything it could not classify in, and calling
 * an unclassified upload a document is a claim about it.
 *
 * THE ORDER IS THE SENTENCE'S ORDER and must not be sorted, alphabetised or driven off the data:
 * `_media_note` walks this tuple, so a different order here is a different string for the same files,
 * which is the one thing {@link composeRecordCount} may not produce.
 */
const MEDIA_NOTE_WORDS: readonly { tokens: readonly string[]; one: string; many: string }[] = [
  { tokens: ["IMAGE"], one: "photograph", many: "photographs" },
  { tokens: ["VIDEO"], one: "video", many: "videos" },
  { tokens: ["AUDIO"], one: "audio note", many: "audio notes" },
  { tokens: ["PDF", "DOCUMENT"], one: "document", many: "documents" },
  { tokens: ["OTHER"], one: "file", many: "files" }
];

/** Every token the table above names, so an unknown one can be counted rather than lost. */
const KNOWN_TOKENS = new Set(MEDIA_NOTE_WORDS.flatMap((entry) => entry.tokens));

/**
 * The parts of one media row this grammar reads, and nothing else.
 *
 * A STRUCTURAL SHAPE RATHER THAN `MediaFile`, so the spec can hand it three-key literals and so this
 * file compiles with no knowledge of the API type. `extraMetadata` had to be DECLARED on `MediaFile`
 * in `lib/types.ts` for this to work at all: an optional property TypeScript cannot see is a property
 * this filter would silently never read, because a `MediaFile` lacking the key is still assignable
 * here — and the symptom of that is a count one too high on exactly the records somebody measured.
 */
export type MediaNoteRow = {
  mediaType?: string | null;
  originalFilename?: string | null;
  extraMetadata?: unknown;
};

/** Is this row a photograph of ruled paper taken to fill a dimension box? */
export function isMeasurementGridRow(row: MediaNoteRow): boolean {
  const meta = row.extraMetadata;
  if (!meta || typeof meta !== "object" || Array.isArray(meta)) return false;
  const purpose = (meta as Record<string, unknown>).purpose;
  return typeof purpose === "string" && purpose === MEDIA_NOTE_GRID_PURPOSE;
}

/**
 * The rows the sentence counts: everything attached except the measurement grids.
 *
 * Exported because the COMPONENT needs the same list three times over and must not compute it a
 * second way: it is what the chooser offers, what the count is taken over, and what the "N grid
 * frames are not listed" sentence measures the difference against.
 */
export function countableMediaRows<T extends MediaNoteRow>(rows: readonly T[]): T[] {
  return rows.filter((row) => !isMeasurementGridRow(row));
}

/**
 * The breakdown clause — "4 photographs, 1 audio note" — or "" when nothing is countable.
 *
 * `_enum_token`'s blank-and-unknown behaviour is ported exactly: a row whose `mediaType` this build
 * does not recognise, or does not carry at all, is counted into the OTHER word rather than dropped.
 * The backend's own comment says why, and it is the reason this cannot be a `Record` lookup with a
 * fallthrough: "a member added to the enum must not silently stop being counted, because the symptom
 * is a sentence that says a record carries three files when it carries five, and nothing anywhere
 * would contradict it."
 */
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

/**
 * How many of these rows are frames of an ordered making sequence.
 *
 * ONLY THE TOOL RECORD PASSES A PREFIX, and that is not a simplification — `design_workshops.py`
 * calls `_media_note` with `numbered_prefix="STAGE_STEP_"` for the tool and for nothing else, so the
 * artisan, craft and product sentences never grow the making clause. `ToolForm` renames every capture
 * in its "Process stages" card to `STAGE_STEP_<n>_<name>` on BOTH its online upload loop and its
 * queued offline array, so this reads the same on a handset that has never had a signal.
 */
export function makingSequenceCount(rows: readonly MediaNoteRow[], numberedPrefix: string): number {
  if (!numberedPrefix) return 0;
  return countableMediaRows(rows).filter(
    (row) => typeof row.originalFilename === "string" && row.originalFilename.startsWith(numberedPrefix)
  ).length;
}

/**
 * The record's own count, spelled exactly as `_media_note` spells it — or null when nothing counts.
 *
 * Null and not "" for the reason the server returns `None`: the key is then ABSENT from the hydration
 * payload and the box stays blank, deliberately, rather than reading "0 files". A blank box is an
 * unanswered question; "0 files" is a claim, and it is the wrong one the moment somebody attaches a
 * photograph without re-saving the stage.
 */
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
    // "documents" for one and "document" for many reads backwards and is correct: the subject of the
    // clause is the COUNT ("1 documents the making", "9 document the making"). Ported verbatim rather
    // than repaired, because the string is compared against a value already stored on live rows.
    note += `, of which ${numbered} ${numbered === 1 ? "documents" : "document"} the making in order`;
  }
  return `${note}.`;
}

/**
 * The opening of the clause a narrowing designer adds. Exported so the spec and the component read
 * one literal rather than two.
 */
export const POINTER_CLAUSE_OPENER = "See in particular: ";

/**
 * The clause as it appears at the very end of a stored note, so it can be found and removed.
 *
 * ANCHORED AT THE END AND NON-GREEDY ABOUT PERIODS. The breakdown itself never contains a period — it
 * is counts and nouns — so `[^.]*` cannot run past the clause it belongs to, and the `$` anchor means
 * a designer who has typed the words "see in particular" into the middle of their own prose keeps
 * them. Case-insensitive because the clause may have been through a reviewer's editing pass.
 */
const POINTER_CLAUSE = /\s*See in particular:[^.]*\.\s*$/i;

/**
 * The OTHER form this file writes, as it appears when it is the whole of a stored note.
 *
 * ── THE DEFECT THIS ENDS, WHICH WAS THIS FILE'S OWN CENTRAL CLAIM BEING FALSE ON ONE BRANCH ─────
 *
 * `composePointerNote` has two forms and only the first was strippable. Over an empty base it
 * writes a complete sentence of its own — "Of the media attached to the artisan record, see: 1
 * photograph." — which contains none of the words {@link POINTER_CLAUSE} matches, so
 * `stripPointerNote` returned it untouched and the next tick composed a pointer clause ONTO it:
 *
 *     tick 1  Of the media attached to the artisan record, see: 1 photograph.
 *     tick 2  Of the media attached to the artisan record, see: 1 photograph. See in particular: 2 photographs.
 *     tick 3  Of the media attached to the artisan record, see: 1 photograph. See in particular: 3 photographs.
 *
 * The stored sentence then carried a stale first clause contradicting its own second one, for ever;
 * and because `hasPointerNote` was false after tick 1, the panel's promise that "ticking here
 * replaces that clause rather than adding to it" was neither shown nor true. Exactly the
 * accumulating value the header below says cannot happen, on the branch nothing round-tripped.
 *
 * REACHABLE, THOUGH NARROWLY: the base is empty only over a blank box on a TRUNCATED listing, which
 * needs more than `MAX_PAGES × PAGE_SIZE` files on one record. Once it happens the poisoned value
 * persists and every later visit re-appends to it, so the rarity is about how often it starts, not
 * about how long it lasts.
 *
 * ANCHORED AND PERIOD-BOUNDED for the same reasons as the clause above, and the subject is spanned
 * by `[^.]*` rather than named because this function is handed a stored string and not the role that
 * composed it. A designer who has written those exact words as their own closing sentence loses
 * them, which is the same accepted cost as the clause pattern and for the same reason: the
 * alternative is a value that grows per click.
 */
const STANDALONE_POINTER = /\s*Of the media attached to the [^.]*record, see:[^.]*\.\s*$/i;

/**
 * A stored note with any earlier pointer clause removed, trimmed.
 *
 * THIS IS WHAT MAKES THE CONTROL IDEMPOTENT, and without it the second pick would produce "…: 4
 * photographs. See in particular: 1 photograph. See in particular: 2 photographs." — a value that
 * grows a sentence per click, overruns 200 characters, and is then refused by `coerce_value` on a
 * save the designer cannot connect to anything they did.
 *
 * BOTH FORMS, because `composePointerNote` writes both — see {@link STANDALONE_POINTER} for the
 * three-tick reproduction of what stripping only one of them did. They are mutually exclusive by
 * construction (one contains "See in particular:", the other does not), so the order of the two
 * replacements decides nothing.
 */
export function stripPointerNote(value: string): string {
  return value.replace(POINTER_CLAUSE, "").replace(STANDALONE_POINTER, "").trim();
}

/** Does this stored note already carry a pointer clause? Used only to word the panel honestly. */
export function hasPointerNote(value: string): boolean {
  return POINTER_CLAUSE.test(value) || STANDALONE_POINTER.test(value);
}

/**
 * The value the control writes when a designer has narrowed the note.
 *
 * TWO FORMS, AND THE SECOND IS NOT A FALLBACK FOR TIDINESS.
 *
 *  * `base` present — the record's own claim is kept verbatim as the first sentence and the pointer
 *    is a second one. This is the normal case: the box arrived hydrated, so the true count is already
 *    in it and nothing needs to be recomputed to keep it.
 *  * `base` empty — there is no count to keep, either because the box is blank (a row whose stage has
 *    never been saved) or because the file list is TRUNCATED and a count taken over part of it would
 *    be a smaller number presented as a total. So the sentence makes no claim about the record at
 *    all: it says only what was picked. Rule 10 of this repository's frontend contract, expressed in
 *    a sentence rather than a banner — a number that cannot be trusted is not printed.
 *
 * BOTH FORMS ARE STRIPPABLE, and the second one was not for one revision — see
 * {@link STANDALONE_POINTER}, which is the pattern that recognises this branch's output. Anything
 * added here that writes a THIRD shape has to be recognised there in the same commit, or the control
 * silently stops being idempotent on exactly the path the new shape covers.
 */
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
  // A period is added only where the kept sentence has none — hydration always ends in one, a
  // designer's own prose often does not, and "…record. . See in particular" is the kind of defect
  // nobody reports and everybody notices.
  const joined = /[.!?]$/.test(kept) ? kept : `${kept}.`;
  return `${joined} ${POINTER_CLAUSE_OPENER}${breakdown}.`;
}

/**
 * How far a composed value overruns the field's declared bound — 0 when it fits.
 *
 * SAID OUT LOUD RATHER THAN TRUNCATED, and the requirement is not a preference: `coerce_value`
 * refuses an over-length string, `save_stage` then restores the refused key from `previous`, and the
 * designer is left with an error against a box whose value silently reverted. Truncating instead
 * would be worse in the way this repository names as its most repeated bug class — a sentence
 * shortened to fit is a count nobody can tell is wrong.
 *
 * `maxLength` may legitimately be absent (`field_to_dict` emits only non-default keys), and absent
 * means unbounded, not zero.
 */
export function mediaNoteOverrun(value: string, maxLength?: number): number {
  if (!maxLength) return 0;
  return Math.max(0, value.length - maxLength);
}
