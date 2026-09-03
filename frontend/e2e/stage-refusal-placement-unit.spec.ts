import { expect, test } from "@playwright/test";

import { ROW_REFUSAL_KEY, rowRefusal, withoutRowRefusal } from "@/components/designworkshop/EntityForm";
import {
  countRefusedAnswers,
  placeStageErrors,
  refusedAnswersToShow,
  strandedRefusals,
  type DwRowKey
} from "@/lib/designWorkshopStore";

/**
 * What a save's REFUSALS become: which box each one marks, which ones no box can mark, and how many
 * answers a designer is told were refused.
 *
 * WHY THESE TWO ARE PINNED TOGETHER. `save_stage` answers 200 and keys its per-field errors by an
 * entry's INDEX IN THE ARRAY THAT WAS SENT, with a map of FIELD to message under each scope. Two
 * separate things then read that map — the stage form, which has to turn an index into a row on
 * screen, and the sync pass, which has to turn it into a number in a sentence — and both were wrong
 * in the same direction: they under-reported. The form dropped what it could not place, and the pass
 * counted the scopes instead of the answers inside them. A designer was shown fewer refusals than the
 * repository had made, which is the one direction this must never be wrong in.
 *
 * BOTH WERE UNREACHABLE BY A TEST UNTIL THEY WERE EXTRACTED. The placement lived inline in the stage
 * page's save handler and the count inline in `runSync`'s loop, so checking either meant a browser, a
 * server and a refused save arranged on purpose. That is why a silent drop survived in the decode
 * beside a comment describing the very index contract it was getting wrong.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * placeStageErrors — the index contract, decoded
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The `rowKeys` a stage with one singleton and three `tool` rows produces.
 *
 * `null` for the singleton, because a singleton files its errors under the bare entity key and has no
 * row to name — and its slot still occupies a position, which is exactly why the indices below are not
 * the row numbers.
 */
const ROW_KEYS: DwRowKey[] = [
  null,
  { entityKey: "tool", rowIndex: 0 },
  { entityKey: "tool", rowIndex: 1 },
  { entityKey: "tool", rowIndex: 2 }
];

test("a refusal against a sent position is re-addressed to the row on screen", () => {
  // The ordinary case, and the reason the whole mechanism exists: the server said "index 3", the form
  // has to mark ROW 2, because the singleton ahead of the rows takes position 0.
  const { decoded, unplaced } = placeStageErrors({ "tool[3]": { name: "This field is required" } }, ROW_KEYS);

  expect(decoded).toEqual({ "tool[2]": { name: "This field is required" } });
  expect(unplaced).toEqual([]);
});

test("a bare scope is left alone — the singleton and the custom container have no row", () => {
  const { decoded, unplaced } = placeStageErrors(
    { traditionalProcess: { stepCount: "Must be a whole number" }, _custom: { dyesrc: "Too long" } },
    ROW_KEYS
  );

  expect(decoded).toEqual({
    traditionalProcess: { stepCount: "Must be a whole number" },
    _custom: { dyesrc: "Too long" }
  });
  expect(unplaced).toEqual([]);
});

test("an index past the end of what was sent is REPORTED, not dropped", () => {
  /*
    THE DEFECT THIS FILE WAS WRITTEN FOR. The old decode fell back to the server's own key, so this
    produced `decoded["tool[9]"]` — indistinguishable in shape from a placed refusal. `collectionErrors`
    matched it as a row index, `CollectionTable` looks its errors up BY ROW and never renders row 9, and
    the refusal existed on the server and nowhere else: the field was not saved and nothing said so.
  */
  const { decoded, unplaced } = placeStageErrors({ "tool[9]": { name: "This field is required" } }, ROW_KEYS);

  expect(decoded).toEqual({});
  // The scope AND the field AND what the server said — the message is the only address left once the
  // row cannot be named, so it is printed verbatim rather than paraphrased.
  expect(unplaced).toEqual(["tool[9].name: This field is required"]);
});

test("a scope naming a different entity than the entry at that position is REPORTED, not misplaced", () => {
  // Presence is not enough. Position 2 exists and holds a `tool`, so trusting presence alone would draw
  // a `rawMaterial` refusal onto a tool row — a red mark on an answer nobody objected to, which sends a
  // designer to correct something that is fine. Admitting it cannot be placed is the lesser failure.
  const { decoded, unplaced } = placeStageErrors({ "rawMaterial[2]": { source: "Unknown supplier" } }, ROW_KEYS);

  expect(decoded).toEqual({});
  expect(unplaced).toEqual(["rawMaterial[2].source: Unknown supplier"]);
});

test("a bracketed scope resolving to a singleton's slot is REPORTED, not drawn on row 0", () => {
  // `rowKeys[0]` is `null` — a real position, occupied by an entry that has no row. `!origin` catches it
  // for the same reason the entity check catches the case above: there is no row to mark.
  const { decoded, unplaced } = placeStageErrors({ "tool[0]": { name: "This field is required" } }, ROW_KEYS);

  expect(decoded).toEqual({});
  expect(unplaced).toEqual(["tool[0].name: This field is required"]);
});

test("every field of an unplaceable scope is reported, not just the first", () => {
  // One line per ANSWER, because that is what a designer is counting and what the count below sums.
  const { unplaced } = placeStageErrors(
    { "tool[9]": { name: "This field is required", cost: "Must be a number", source: "Too long" } },
    ROW_KEYS
  );

  expect(unplaced).toHaveLength(3);
  expect(unplaced).toEqual([
    "tool[9].name: This field is required",
    "tool[9].cost: Must be a number",
    "tool[9].source: Too long"
  ]);
});

test("a scope refused with no field map at all is still reported", () => {
  // There is no box to mark and no message to print, and the server still refused something. Silence
  // here would be a refusal that no surface anywhere admits to. The handset says this sentence too.
  const { decoded, unplaced } = placeStageErrors({ "tool[1]": {} }, ROW_KEYS);

  expect(decoded).toEqual({});
  expect(unplaced).toEqual(["tool[1]: refused, with no reason given"]);
});

test("placed and unplaceable refusals in one response are both accounted for", () => {
  // The mixed case is the realistic one, and the property is that nothing is lost: every scope in the
  // response comes out either marked on a box or listed in the banner.
  const { decoded, unplaced } = placeStageErrors(
    {
      "tool[1]": { name: "This field is required" },
      "tool[9]": { cost: "Must be a number" },
      traditionalProcess: { stepCount: "Must be a whole number" }
    },
    ROW_KEYS
  );

  expect(decoded).toEqual({
    "tool[0]": { name: "This field is required" },
    traditionalProcess: { stepCount: "Must be a whole number" }
  });
  expect(unplaced).toEqual(["tool[9].cost: Must be a number"]);
  // Stated as a property rather than left implicit in the two assertions above: no scope may vanish.
  expect(Object.keys(decoded).length + unplaced.length).toBe(3);
});

test("an empty error map is not a refusal", () => {
  for (const errors of [{}, null, undefined]) {
    const { decoded, unplaced } = placeStageErrors(errors, ROW_KEYS);
    expect(decoded).toEqual({});
    expect(unplaced).toEqual([]);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * countRefusedAnswers — the number in the sentence
 * ──────────────────────────────────────────────────────────────────────────── */

test("the count sums the field maps, because a designer counts ANSWERS", () => {
  /*
    `Object.keys(errors).length` — what this replaced — counted the SCOPES. Three refused answers in one
    row reported "The server refused 1 answer in this stage", and the designer who followed that sentence
    into the stage found three marked boxes. The number disagreed with the screen it was sending them to.
  */
  expect(countRefusedAnswers({ "tool[0]": { name: "required", cost: "not a number", source: "too long" } })).toBe(3);
});

test("the count agrees with the handset on the same response", () => {
  // Android: `result.errors.values.sumOf { (entry as? JsonObject)?.size ?: 1 }`. One save produced two
  // different counts on the two surfaces, and a designer working across a laptop and a phone had no way
  // to tell which was lying. This is that expression, in TypeScript.
  const errors = {
    traditionalProcess: { stepCount: "Must be a whole number" },
    "tool[1]": { name: "required", cost: "not a number" },
    _custom: { dyesrc: "too long" }
  };

  expect(countRefusedAnswers(errors)).toBe(4);
  // And the old implementation's answer, stated so the difference is on the record rather than implied.
  expect(Object.keys(errors).length).toBe(3);
});

test("a scope with an empty field map counts as one, so a refusal can never sum to zero", () => {
  /*
    THE GUARD ON THE FIX'S OWN FOOTGUN. A plain sum would give 0 here — and `rejected` is read as a
    boolean: `failure: rejected ? … : null` and `if (rejected) result.failed += 1; else
    result.stagesSent += 1`. Zero would record a save the server partly refused as a clean one, which is
    the same silence in a new place. `|| 1` is the handset's `?: 1`.
  */
  expect(countRefusedAnswers({ "tool[0]": {} })).toBe(1);
  expect(countRefusedAnswers({ "tool[0]": {}, "tool[1]": { name: "required" } })).toBe(2);
});

test("no refusals is zero, so a clean save stays clean", () => {
  // The falsy end of the same boolean read: this must not be able to invent a failure.
  expect(countRefusedAnswers({})).toBe(0);
  expect(countRefusedAnswers(null)).toBe(0);
  expect(countRefusedAnswers(undefined)).toBe(0);
});

/* ────────────────────────────────────────────────────────────────────────────
 * strandedRefusals — placement against the rows that are on screen NOW
 *
 * The reachable half. `placeStageErrors` answers "could this be placed against what I SENT", once, at
 * save time. The decoded errors then sit in state until the next save while the rows underneath them
 * are edited freely — so the question has to be asked again at render, against the current rows, and
 * the answer can change without another save happening at all.
 * ──────────────────────────────────────────────────────────────────────────── */

const ENTITIES = [
  { key: "traditionalProcess", cardinality: "SINGLETON" },
  { key: "tool", cardinality: "COLLECTION" },
  { key: "rawMaterial", cardinality: "COLLECTION" }
];

/** Three tool rows and one raw material on screen. */
const ROWS = { tool: [{}, {}, {}], rawMaterial: [{}] };

test("a refusal on a row that is on screen is left to the box", () => {
  expect(strandedRefusals({ "tool[2]": { name: "required" } }, ENTITIES, ROWS)).toEqual([]);
  expect(strandedRefusals({ traditionalProcess: { stepCount: "bad" } }, ENTITIES, ROWS)).toEqual([]);
  expect(strandedRefusals({ _custom: { dyesrc: "too long" } }, ENTITIES, ROWS)).toEqual([]);
});

test("a refusal whose row was DELETED under it is reported instead of vanishing", () => {
  /*
    THE REACHABLE PATH, and the one no amount of care in the decode can catch. The save placed this
    correctly on row 2 of three; the designer then deleted a row. `CollectionTable` looks its errors up
    BY ROW, so with two rows left the entry under index 2 is never read again — the mark disappears from
    the screen while the repository goes on holding the refusal and the old value under that field.
  */
  const afterDelete = { tool: [{}, {}], rawMaterial: [{}] };
  expect(strandedRefusals({ "tool[2]": { name: "This field is required" } }, ENTITIES, afterDelete)).toEqual([
    "tool[2].name: This field is required"
  ]);
});

test("emptying a collection strands every refusal that was marked in it", () => {
  const emptied = { tool: [], rawMaterial: [{}] };
  expect(
    strandedRefusals({ "tool[0]": { name: "required", cost: "not a money" } }, ENTITIES, emptied)
  ).toEqual(["tool[0].name: required", "tool[0].cost: not a money"]);
});

test("a scope naming an entity this stage does not declare is reported", () => {
  // A registry that has moved on under a browser holding a stale error map, and the bare-key form of
  // the same thing. Neither has a box, and neither may be silently dropped.
  expect(strandedRefusals({ "sketch[0]": { title: "required" } }, ENTITIES, ROWS)).toEqual([
    "sketch[0].title: required"
  ]);
  expect(strandedRefusals({ somethingElse: { title: "required" } }, ENTITIES, ROWS)).toEqual([
    "somethingElse.title: required"
  ]);
});

test("a bare key naming a COLLECTION is reported — a table draws no entity-level error", () => {
  // `errors[entity.key]` is only ever read for a singleton's `EntityForm`; `CollectionTable` is given
  // `errorsByIndex` and has nowhere to put a scope-level message.
  expect(strandedRefusals({ tool: { name: "required" } }, ENTITIES, ROWS)).toEqual(["tool.name: required"]);
});

test("marked and stranded are complements, so no refusal falls between them", () => {
  /*
    The property that makes the pair trustworthy rather than two lists that happen to be maintained. For
    each scope: it is either drawn on a box (and absent here) or reported here — never both, never
    neither. Checked over a mixed map against a shrunken row set, which is the state that produced the
    silent drop.
  */
  const errors = {
    traditionalProcess: { stepCount: "bad" },
    "tool[0]": { name: "required" },
    "tool[5]": { cost: "not a money" },
    "rawMaterial[0]": { source: "required" },
    "sketch[1]": { title: "required" }
  };
  const rows = { tool: [{}, {}], rawMaterial: [{}] };
  const strandedLines = strandedRefusals(errors, ENTITIES, rows);

  // Two scopes cannot be drawn: `tool[5]` (only two rows) and `sketch[1]` (not in this stage).
  expect(strandedLines).toEqual(["tool[5].cost: not a money", "sketch[1].title: required"]);
  // And the total is conserved: every answer in the map is either marked or listed.
  const drawnAnswers = countRefusedAnswers(errors) - strandedLines.length;
  expect(drawnAnswers).toBe(3);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The shape the server actually sends
 * ──────────────────────────────────────────────────────────────────────────── */

test("a response captured from the running API decodes as expected", () => {
  /*
    NOT AN INVENTED FIXTURE. Both maps below are the verbatim `errors` of real 200 responses from
    `PUT /api/design-workshops/{id}/stages/TRADITIONAL_PROCESS_BASELINE` against live Postgres, taken
    while this change was being made. The first came from a payload of one singleton followed by two
    `tool` rows — note that the indices are 1 and 2, NOT 0 and 1, because the singleton occupies
    position 0 in the array that was sent. That off-by-the-singleton is the entire reason `rowKeys`
    exists, and pinning it against a captured response is what stops the decode being "corrected" to
    use the index as a row number.
  */
  const twoRowsOneFieldEach = {
    "tool[1]": { cost: "Cost is not a valid money" },
    "tool[2]": { cost: "Cost is not a valid money" }
  };
  const { decoded, unplaced } = placeStageErrors(twoRowsOneFieldEach, [
    null,
    { entityKey: "tool", rowIndex: 0 },
    { entityKey: "tool", rowIndex: 1 }
  ]);
  expect(decoded).toEqual({
    "tool[0]": { cost: "Cost is not a valid money" },
    "tool[1]": { cost: "Cost is not a valid money" }
  });
  expect(unplaced).toEqual([]);
  expect(countRefusedAnswers(twoRowsOneFieldEach)).toBe(2);

  /*
    The second response is one row refusing TWO fields, which is the case the count was wrong for. The
    old `Object.keys(errors).length` answered 1 while the form marked two boxes; the handset answered 2.
  */
  const oneRowTwoFields = {
    "tool[0]": {
      toolFamily: "Tool family: 'NOT_A_REAL_FAMILY' is not a valid option",
      cost: "Cost is not a valid money"
    }
  };
  expect(countRefusedAnswers(oneRowTwoFields)).toBe(2);
  expect(Object.keys(oneRowTwoFields).length).toBe(1);
});

/* ────────────────────────────────────────────────────────────────────────────
 * refusedAnswersToShow — choosing between the server's count and this build's
 * ──────────────────────────────────────────────────────────────────────────── */

test("the server's count is used, so a laptop and a handset print one number", () => {
  /*
    `refusedAnswers` exists because `errors` is two levels deep and carries no total, so each client
    computed whichever reading its author saw first — the web counted scopes, Android counted fields,
    and one response body produced two sentences both using the word "answer". The server settles it.
  */
  const errors = { "tool[0]": { name: "required", cost: "not a number" } };
  expect(refusedAnswersToShow(2, errors)).toBe(2);
});

test("a scope refused with an empty field map is never reported as a clean save", () => {
  /*
    THE CASE THAT MAKES THIS A FUNCTION RATHER THAN A FIELD READ. `refused_answer_count` sums
    `len(fields)`, so `{"costing": {}}` totals 0 on the server — a non-empty `errors` alongside
    "nothing was refused". `countRefusedAnswers`' `|| 1` contributes one instead, deliberately, and
    that floor has to survive the switch to the server's number. Under-reporting a refusal is the one
    direction this file's header says must never be wrong.
  */
  const emptyMap = { costing: {} };
  expect(countRefusedAnswers(emptyMap)).toBe(1);
  expect(refusedAnswersToShow(0, emptyMap)).toBe(1);
});

test("a genuinely clean save still reports zero", () => {
  // The floor must not invent a refusal: with no errors at all, the server's 0 stands.
  expect(refusedAnswersToShow(0, {})).toBe(0);
  expect(refusedAnswersToShow(0, null)).toBe(0);
});

test("a deployment older than the field falls back to counting locally", () => {
  // `refusedAnswers` is optional precisely because a client can be newer than the server it talks to.
  expect(refusedAnswersToShow(undefined, { "tool[0]": { name: "required", cost: "bad" } })).toBe(2);
  expect(refusedAnswersToShow(undefined, {})).toBe(0);
});

test("the server's count wins where counting locally would return a character count", () => {
  /*
    `countRefusedAnswers` walks the map with `Object.keys`, and `Object.keys("required")` returns the
    string's INDICES — so a scope whose value arrives as a bare string would put 8 into a sentence
    about one refused answer. `refused_answer_count`'s non-mapping guard returns 1 for exactly this,
    and preferring the server is what keeps that guard effective on the web.
  */
  const stringScope = { costing: "required" } as unknown as Record<string, Record<string, string>>;
  expect(countRefusedAnswers(stringScope)).toBe(8);
  expect(refusedAnswersToShow(1, stringScope)).toBe(1);
});

/* ────────────────────────────────────────────────────────────────────────────
 * rowRefusal — the refusal that names no field, and the box-marking pass cannot place
 *
 * `save_stage` files a version conflict under the reserved key `_row` INSIDE the scope's ordinary
 * error bucket, deliberately: `errors` is a surface both clients already draw and already count, so
 * the refusal reaches a fielded 0.0.7 rather than needing a new response key nothing renders. What it
 * does NOT reach is a box — every other entry in that map names a real field, and both grids draw one
 * by looking its key up in the registry. So the count said "1 answer was refused", the banner said
 * the marked boxes were below, and there was nothing marked anywhere on the stage.
 * ──────────────────────────────────────────────────────────────────────────── */

/** The server's sentence, verbatim — `design_workshops.STAGE_ROW_CONFLICT_MESSAGE`. */
const ROW_CONFLICT = "Someone else saved this row first — reopen the stage to see the latest before saving again.";

test("the reserved key is the wire constant, spelled the same on all three clients", () => {
  // Kept in step by hand with `STAGE_ROW_CONFLICT_KEY` and Android's `DW_ROW_REFUSAL_KEY`. A rename on
  // one side alone is a refusal that arrives and is drawn nowhere, which is the whole defect here.
  expect(ROW_REFUSAL_KEY).toBe("_row");
});

test("a row-level refusal is read out of the row's own error map", () => {
  expect(rowRefusal({ [ROW_REFUSAL_KEY]: ROW_CONFLICT })).toBe(ROW_CONFLICT);
  // Beside real field messages, which is the ordinary shape: `setdefault` on the server means a row
  // can be refused for BOTH reasons at once — a typo to fix and an edit somebody else made.
  expect(rowRefusal({ name: "This field is required", [ROW_REFUSAL_KEY]: ROW_CONFLICT })).toBe(ROW_CONFLICT);
});

test("no row-level refusal is null, so a field-only refusal draws no card band", () => {
  expect(rowRefusal(undefined)).toBeNull();
  expect(rowRefusal({})).toBeNull();
  expect(rowRefusal({ name: "This field is required" })).toBeNull();
  // An empty string is not a sentence. Drawing an empty red band would be a mark with nothing in it.
  expect(rowRefusal({ [ROW_REFUSAL_KEY]: "" })).toBeNull();
});

test("the sentence is passed through verbatim and never rewritten", () => {
  /*
    THE WORDS ARE THE SERVER'S. It is one line, it already states what happened and what to do next,
    and both clients show it — so a client that prefixed or paraphrased it would put a second voice on
    one refusal and the two apps would describe one conflict differently. Pinned as an identity so a
    "friendlier" wrapper has to delete this assertion to land.
  */
  expect(rowRefusal({ [ROW_REFUSAL_KEY]: ROW_CONFLICT })).toBe(ROW_CONFLICT);
});

test("stripping the row refusal leaves the field messages exactly as they were", () => {
  /*
    `CustomSectionsForm` is the one caller: the server files every custom answer under the single
    `_custom` scope and that one bucket is handed to an `EntityForm` per section, so a row refusal
    passed down would be printed once per section — one stored row's conflict read as three.
  */
  const errors = { dyesrc: "Too long", [ROW_REFUSAL_KEY]: ROW_CONFLICT };
  expect(withoutRowRefusal(errors)).toEqual({ dyesrc: "Too long" });
  // The input is not mutated: the same map is still the one the hoisted line reads its sentence from.
  expect(errors[ROW_REFUSAL_KEY]).toBe(ROW_CONFLICT);
  // A map with nothing to strip is returned as-is, so the common case allocates nothing.
  const untouched = { dyesrc: "Too long" };
  expect(withoutRowRefusal(untouched)).toBe(untouched);
  expect(withoutRowRefusal(undefined)).toBeUndefined();
});

test("a row refusal is counted as an answer, and now has somewhere to be drawn", () => {
  /*
    THE TWO HALVES THAT HAD TO MEET. `countRefusedAnswers` has always counted `_row` — it walks the
    field map and cannot tell a reserved key from a real one — and `strandedRefusals` has always
    treated a scope whose row is on screen as DRAWN, so the banner did not list it either. Both are
    correct; what was missing was the drawing. The pin is that all three still agree.
  */
  const errors = { "tool[1]": { [ROW_REFUSAL_KEY]: ROW_CONFLICT } };
  expect(countRefusedAnswers(errors)).toBe(1);
  expect(strandedRefusals(errors, ENTITIES, ROWS)).toEqual([]);
  expect(rowRefusal(errors["tool[1]"])).toBe(ROW_CONFLICT);
});

test("a row refusal against a row that is NOT on screen is still stranded, not swallowed", () => {
  // The complement holds for the reserved key exactly as it does for a field: delete the row and the
  // card band goes with it, so the banner has to pick the refusal up.
  const errors = { "tool[7]": { [ROW_REFUSAL_KEY]: ROW_CONFLICT } };
  expect(strandedRefusals(errors, ENTITIES, ROWS)).toEqual([`tool[7]._row: ${ROW_CONFLICT}`]);
});
