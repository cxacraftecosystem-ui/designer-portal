/**
 * TENTATIVE-FIRST — the one partition, the one predicate, and the rule about where they may be used.
 *
 * ── WHAT WAS ASKED FOR ──────────────────────────────────────────────────────────────────────────
 *
 * The owner, 2026-08-30: designers "would be able to upload sketches and mark them as tentative to
 * bring them to the top of the list, these are not finalised, the ones for which it is not checked
 * would be considered as normal as they are treated right now". The registry half of that landed as
 * `sketch.isTentative` — a BOOL, tier B, `report_role=HIDDEN` — and the long argument for the SHAPE
 * (a flag on the row, never a second gallery) is beside the field in
 * `backend/app/services/stage_definitions.py`.
 *
 * ── WHY THIS IS A PARTITION AND NOT A SORT, A SORT KEY, OR A WRITE ──────────────────────────────
 *
 * Nothing in this repository can pin a row to the top of a list, and three things that look as
 * though they could, cannot:
 *
 *   * `DwStageEntry.ordinal` is the ONLY ordering input there is, and it is derived from the ARRAY
 *     ORDER at send time (`buildStageEntries` in `lib/designWorkshopStore.ts`, `save_stage` on the
 *     server, `entry_rows` reading back `order={"ordinal": "asc"}`). Moving a row up by writing its
 *     ordinal would therefore fight the drag-reorder for the same number, and — the half that
 *     actually loses work — UNTICKING the box could no longer restore the row's place, because the
 *     place it came from was overwritten the moment it was ticked.
 *   * `sketch.rankFixedBy` / `rankFixedAt` is a fact about the ARRANGEMENT — who settled this order
 *     and when — not about one row. See `components/sketches/reviewRanking.ts`.
 *   * `reviewRanking.reconcileOrder` deliberately APPENDS ids it does not know at the end, and its
 *     own comment argues against slotting a row into the middle of an order somebody fixed.
 *
 * So tentative-first is a STABLE PARTITION applied on top of `ordinal`, at the moment a list is
 * drawn: the tentative rows first in their own `ordinal` order, then the rest in theirs. A designer's
 * arrangement inside each group survives untouched, and unticking the box returns a row to exactly
 * the position it would have had — because the stored order never changed.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE RULE FOR WHERE IT MAY BE APPLIED: A SURFACE THAT *READS* A LIST, NEVER ONE THAT *WRITES* IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * This is the whole judgement of the feature and it is mechanical rather than aesthetic. On two of
 * this app's sketch surfaces the array on screen IS the array that gets persisted:
 *
 *   * `components/designworkshop/EntityForm.tsx` — the stage form's collection rows. The digit
 *     printed on each row is `index + 1`, and that index is the `ordinal` `save_stage` stores, the
 *     provenance page prints as "row 3" and the report prints the rows in. `useDragReorder` moves
 *     rows by index within that same array.
 *   * `components/sketches/ReviewPanel.tsx` — the design-review list. Its `order` state is both what
 *     is drawn AND what `arrangeRows` writes back into the draft on the next save.
 *
 * On those two, a display partition is a mutation of `ordinal` wearing a different coat: whatever is
 * shown is what the next drag measures against and what the next save stores. It would also move a
 * row out from under the designer at the moment they tick the box — on the stage form the checkbox
 * is INSIDE the row's own expanded panel — which is the "the list re-sorted itself under me" failure
 * `ReviewPanel`'s own header spends four paragraphs refusing. Those surfaces therefore show the
 * stored order and say which rows are tentative with a WORD instead; each carries the reason at the
 * point of the decision.
 *
 * Where a list is only READ — the Sketches & Prototypes upload chooser, on both clients — the
 * partition applies, because nothing there writes an order back.
 *
 * ── THE FLAG DOES NOT REACH THE REPORT, AND THAT IS THE REGISTRY'S DECISION, NOT AN OMISSION ─────
 *
 * `report_role=HIDDEN`. A ministry receives the document, and "Tentative: Yes" in it is a designer's
 * private hedging put into the record — where it also goes stale the moment the sketch is finalised
 * and nobody thinks to untick it. Reordering the report's FIGURES by a flag the report does not print
 * would be worse than printing it: the reader would see an order they cannot account for. The report
 * is built server-side from `ordinal` (`assemble_workshop_data`) and both on-device writers read the
 * same rows, so all four renderers of that document agree, as they must.
 *
 * ── PARITY ──────────────────────────────────────────────────────────────────────────────────────
 *
 * The handset's half is `dwTentativeFirst` / `dwIsTentativeRow` in
 * `android/.../ui/designworkshop/DwSketchChooserRows.kt`, pinned by `DwSketchTentativeTest`. Same
 * partition, same predicate, same two surfaces excluded for the same reason.
 */

import type { DwEntity, DwEntryData, DwField, DwValue } from "@/lib/designWorkshops";

/**
 * The registry key the flag is stored under, spelled once for both clients to agree with.
 *
 * NOT INFERRED FROM A PATTERN. Unlike the five guessed roles in
 * `components/designworkshop/stageFieldRoles.ts`, this is an exact key on an exact entity, and a
 * guess here would put a chip on some other entity's boolean the day one is added.
 */
export const TENTATIVE_FIELD_KEY = "isTentative";

/**
 * The registry's own field, or null where this entity declares none.
 *
 * WHY A RENDERER SHOULD READ THIS RATHER THAN HARDCODE THE WORD "Tentative". The label belongs to
 * `stage_definitions.py`, both clients draw it, and a word written into a component is the copy that
 * goes stale when the registry is edited — §16 of the frontend contract, and the reason the chooser
 * reads its media-field labels off the schema rather than naming them.
 *
 * A NULL IS AN ORDINARY STATE AND NOT AN ERROR: an older build talking to a registry without the
 * field, or any of the other collection entities, simply draws no chip.
 */
export function tentativeField(entity: DwEntity | null | undefined): DwField | null {
  if (!entity) return null;
  return (
    entity.fields.find(
      (field) => field.key === TENTATIVE_FIELD_KEY && field.type === "BOOL" && !field.deprecated
    ) ?? null
  );
}

/**
 * Is this row marked tentative?
 *
 * `=== true` AND NOTHING LOOSER, deliberately. `BoolField` (`FieldInput.tsx`) draws "Yes" as pressed
 * only for a real `true` and shows "Not answered" for everything else, and `coerce_value` stores a
 * real boolean, so this is exactly the set of values a designer can see ticked. A predicate that also
 * accepted `"yes"` or `1` would put the chip on a row whose own checkbox reads "Not answered" — a
 * screen disagreeing with itself about one field, which is worse than the row it would have caught.
 * (The handset's `dwIsTentativeRow` goes through `DwValues.bool`, which DOES read those tokens,
 * because that helper is the one its own BOOL control reads. The two agree on every value either
 * client or the server can produce; they differ only on a hand-written string neither writes.)
 *
 * NULL AND UNDEFINED ARE "NOT TENTATIVE" AND NOT "UNKNOWN". The registry left the field optional on
 * purpose (it is a working state, not an answer the stage demands), so an unanswered box means the
 * sketch is treated exactly as it was before this feature existed — which is the owner's own second
 * clause, in one line.
 */
export function isTentativeRow(row: DwEntryData | null | undefined): boolean {
  if (!row) return false;
  const value: DwValue | undefined = row[TENTATIVE_FIELD_KEY];
  return value === true;
}

/**
 * THE PARTITION. Tentative items first in their existing order, then the rest in theirs.
 *
 * ONE IMPLEMENTATION, GENERIC IN WHAT IT IS PARTITIONING, and that is why it takes a predicate
 * rather than `DwRow[]`: callers do not all hold rows. The upload chooser partitions `{ row, index }`
 * pairs because it must keep each row's ORIGINAL stage position — `rowLabel` falls back to
 * "Untitled 3" and the handset's picker prints "Row 3 of 8", and both of those numbers are the
 * position a designer can see on the stage form. Renumbering them to the display position would make
 * the two clients' pickers name the same row differently and send somebody looking for a third
 * sketch that is really the fifth.
 *
 * STABLE, AND THAT IS THE ENTIRE BEHAVIOUR. `Array.prototype.sort` is stable in every runtime this
 * app supports, so a comparator would have worked; two buckets in one pass say what is happening
 * without a reader having to know that. The predicate is called EXACTLY ONCE PER ITEM, so a caller
 * whose predicate is expensive — or, worse, whose answer could change between two calls — cannot get
 * a torn ordering out of this.
 *
 * A SECOND PRIVATE COPY OF THIS IS REFUSED BY NAME, the same way `useDragReorder` and
 * `readableError` are: three renderers over one list order that disagreed about which sketches come
 * first is the defect this exists to prevent, and it is invisible until two people compare screens.
 */
export function tentativeFirst<T>(items: readonly T[], isTentative: (item: T) => boolean): T[] {
  const first: T[] = [];
  const rest: T[] = [];
  for (const item of items) {
    if (isTentative(item)) first.push(item);
    else rest.push(item);
  }
  return [...first, ...rest];
}
