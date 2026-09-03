import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  capListGrowth,
  referenceMultiNotice,
  referenceMultiOptions
} from "@/components/designworkshop/FieldInput";
import type { DwField, DwReferenceOption, DwReferencePayload } from "@/lib/designWorkshops";

/**
 * THE WEB'S RECORD-BACKED MULTI_ENUM, AND THE ONE THING IT MUST NEVER BE: A CLOSED LIST WITH NO
 * MEMBERS AND NOTHING SAID.
 *
 * ── THE DEFECT ────────────────────────────────────────────────────────────────────────────────
 * Android has rendered a MULTI_ENUM that names a `refModel` as a multi-select over RECORDS since
 * before this browser could — `FieldRenderer.kt`'s MULTI_ENUM arm branches on `field.refModel` and
 * hands it to `DwReferenceMultiSelectField`. The web routed every MULTI_ENUM to `MultiEnumField`,
 * which read `field.options` and had no `refModel` branch at all. So the moment anybody wrote
 * `ref_model=…` beside a registry MULTI_ENUM's `enum=`, the browser would have drawn a dropdown
 * with ZERO options — and `FieldInput`'s own REF arm spells out the consequence in as many words:
 * a closed list with no members cannot be answered at all, and on a BASIC/required field that
 * makes the stage permanently unsubmittable.
 *
 * THAT FIELD EXISTS. `designBrief.targetCategories` on stage 10 is MULTI_ENUM, BASIC and required
 * — one of five MULTI_ENUM fields in the registry, and the only one that is both. (The brief this
 * work was asked for named `prototype.materials` and `finalProduct.materials` instead; those are
 * BASIC and required and they are TAGS, which a `ref_model` cannot reach without changing the field
 * type. The last test in this file is what keeps that reading honest rather than remembered.)
 *
 * ── PART PURE CALL, PART SOURCE READ, AND THE SPLIT IS THE HOUSE ONE ──────────────────────────
 * `dropdown-sweep-unit.spec.ts` and `capped-lists-unit.spec.ts` make the same division for the same
 * reason: this repository has no React renderer in its devDependencies, Playwright is the whole of
 * it, so a judgement that stays inside a component body can only ever be asserted as a SUBSTRING —
 * which pins the spelling of a sentence and not the condition that decides whether a designer is
 * ever shown it. What decides anything here was therefore lifted into three pure functions and is
 * CALLED below. What is genuinely structural — which prop a call site passes, which control is
 * mounted above which — is read out of the source.
 *
 * ── WHAT THIS DOES NOT PROVE, SAID SO NOBODY READS IT AS MORE THAN IT IS ──────────────────────
 * That a browser paints or announces any of it; and, more importantly, that the SERVER would keep
 * what this control collects. It would not, as of 2026-08-27 — the last test in this file is what
 * keeps that sentence re-checkable, and it is written to FAIL the day the server half lands, so
 * that the paragraph warning against the declaration is corrected in the same change.
 */

/**
 * Read a repository file with its line endings normalised.
 *
 * `core.autocrlf` is on for the Windows machines this is developed on, so a source file can be CRLF
 * in the working tree and LF in the repository and on CI. Every multi-line marker below would then
 * match in one place and not the other — a spec that passes or fails on a checkout setting is worse
 * than no spec, because the failure looks like the code.
 */
const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");
const readRepo = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const FIELD_INPUT = read("components", "designworkshop", "FieldInput.tsx");

/**
 * The source with its comments removed, for the assertions that ban a construct.
 *
 * The obvious version of those is wrong in a way that punishes the house style: a file that argues
 * at length about why it does NOT send a `limit` contains the word in its argument, so a plain
 * `toContain` fails on the very prose that proves the decision was deliberate.
 * `dropdown-sweep-unit.spec.ts` needed the same helper for the same reason.
 */
function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*/g, "$1");
}

/** The text between two markers, so an assertion cannot drift into a neighbouring call. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

/** The record arm's own source. A function, not a constant: an assertion belongs inside a test. */
const recordArm = () => between(FIELD_INPUT, "function ReferenceMultiSelect({", "function MultiEnumField({");

/* ────────────────────────────────────────────────────────────────────────────
 * Fixtures — the declaration this branch exists to make safe
 * ──────────────────────────────────────────────────────────────────────────── */

/** `designBrief.targetCategories`, as it would read the day somebody adds a ref model to it. */
const ROSTER: DwField = {
  key: "targetCategories",
  label: "Target product categories",
  type: "MULTI_ENUM",
  tier: "BASIC",
  required: true,
  refModel: "Artisan",
  refScope: "WORKSHOP"
};

/** The same, cascaded off a sibling — the shape stage 6's product picker already has on REF. */
const CASCADED: DwField = {
  ...ROSTER,
  key: "products",
  label: "Products",
  refScope: "ALL",
  refFilterBy: "artisanRef"
};

function option(id: string, label: string, sublabel = ""): DwReferenceOption {
  return { id, label, sublabel, data: {} };
}

function answer(over: Partial<DwReferencePayload> = {}): DwReferencePayload {
  return {
    model: "Artisan",
    scope: "WORKSHOP",
    scopedToWorkshop: true,
    filtered: false,
    truncated: false,
    options: [],
    ...over
  };
}

function notice(over: Partial<Parameters<typeof referenceMultiNotice>[0]> = {}): string {
  return referenceMultiNotice({
    field: ROSTER,
    parentLabel: "",
    awaitingCascade: false,
    payload: answer(),
    problem: null,
    loading: false,
    query: "",
    ...over
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. An empty or erroring list is never an unanswerable required field in silence
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * EVERY WAY THIS LIST CAN COME BACK WITH NOTHING, and there must not be a silent one among them.
 *
 * The panel's own empty line is the same "No options" whichever of these produced it, so without a
 * sentence beside it a required field that cannot be answered is indistinguishable from one the
 * designer simply has not opened yet. That is rule 10 of the frontend contract, on the one control
 * where the price of getting it wrong is a stage nobody can ever complete.
 */
const EMPTY_STATES: { state: string; over: Partial<Parameters<typeof referenceMultiNotice>[0]> }[] = [
  { state: "the request failed", over: { payload: null, problem: "The list could not be loaded." } },
  {
    state: "the cascade above is unanswered",
    over: { field: CASCADED, awaitingCascade: true, parentLabel: "Artisan", payload: null }
  },
  {
    state: "the cascade is answered and that record has nothing under it",
    over: {
      field: CASCADED,
      parentLabel: "Artisan",
      payload: answer({ scope: "ALL", scopedToWorkshop: false, filtered: true })
    }
  },
  {
    state: "the design workshop is not linked, and the whole repository is empty",
    over: { payload: answer({ scopedToWorkshop: false }) }
  },
  {
    state: "the design workshop is linked and its workshop record holds nothing",
    over: { payload: answer({ scopedToWorkshop: true }) }
  },
  { state: "a search matched nothing", over: { payload: answer(), query: "kamla" } },
  { state: "the first answer has not arrived", over: { payload: null, loading: true } }
];

test("no empty or erroring reference list is left unexplained", () => {
  for (const { state, over } of EMPTY_STATES) {
    const line = notice(over);
    expect(line, `${state}: an empty closed list with nothing said`).not.toBe("");
    // A sentence, not a word — every branch has to carry a reason a designer can act on.
    expect(line.length, `${state}: too short to be an explanation`).toBeGreaterThan(24);
  }
});

test("a list that could not be loaded does not read as a repository with no records", () => {
  const line = notice({ payload: null, problem: "The list could not be loaded." });
  expect(line).toContain("The list could not be loaded.");
  // The whole point of the distinction: nothing may be ADDED, and what is already stored is not
  // being reported as missing — it is still listed and still removable.
  expect(line).toContain("still listed and can be removed");
  expect(line).not.toContain("has been documented yet");
});

test("an unanswered cascade names the box to answer first, and never blames the repository", () => {
  const line = notice({ field: CASCADED, awaitingCascade: true, parentLabel: "Artisan", payload: null });
  expect(line).toContain("Artisan");
  expect(line).toContain("first");
  expect(line).not.toContain("has been documented yet");

  // With no parent label to hand — a descriptor naming a `refFilterBy` this entity does not carry —
  // it still points somewhere rather than falling back to silence.
  expect(notice({ field: CASCADED, awaitingCascade: true, parentLabel: "", payload: null })).toContain(
    "the field above"
  );
});

test("an empty repository says what has to exist first, and names the field that cannot be answered", () => {
  const line = notice({ payload: answer({ scopedToWorkshop: false }) });
  expect(line).toContain("Target product categories");
  expect(line).toContain("has to exist in the repository first");
  // And it does not offer a create this control does not have — see the four things
  // `ReferenceMultiSelect` deliberately does not do, in its own header.
  expect(line).toContain("it does not create them");
});

test("the linked-workshop sentence is the server's own, said once and never doubled", () => {
  const line = notice({ payload: answer({ scopedToWorkshop: true }) });
  // `scopeNoticeLines` owns this sentence; the arm imports it rather than writing a second copy.
  expect(line).toContain("linked workshop yet");
  // Two explanations of one empty list would be the form arguing with itself.
  expect(line).not.toContain("has to exist in the repository first");
});

test("a typed term replaces the scope's empty-list claim instead of stacking on it", () => {
  const line = notice({ payload: answer({ scopedToWorkshop: true }), query: "kamla" });
  // The list is empty because of the QUERY, so the claim that the workshop holds nothing is not one
  // this answer supports.
  expect(line).not.toContain("Nothing is documented under this design workshop");
  expect(line).toContain("kamla");
  // But the narrowing still has to be said, or "Nothing matches" reads as "this person has no
  // record" about somebody documented one workshop away.
  expect(line).toContain("narrowed to this design workshop");
  expect(line).toContain("will not appear");
});

test("an unlinked workshop keeps its widened-net sentence even under a search", () => {
  // That line is about how wide the net IS, which a query does not make false — unlike the one the
  // test above replaces. Both halves of one judgement, an assertion each.
  const line = notice({ payload: answer({ scopedToWorkshop: false }), query: "kamla" });
  expect(line).toContain("not linked to a workshop record");
  expect(line).toContain("Nothing matches");
});

test("a search still in flight says so rather than reporting no match", () => {
  const line = notice({ payload: answer(), query: "kam", loading: true });
  expect(line).toContain("Searching");
  expect(line).not.toContain("Nothing matches");
});

test("a truncated answer keeps the server's own truncation sentence", () => {
  const line = notice({ payload: answer({ truncated: true, options: [option("a1", "Kamla Devi")] }) });
  expect(line).toContain("Only the first 50 matches are listed");
});

test("a whole, ordinary list says nothing at all", () => {
  // Silence is the common and correct answer; a standing note on every visit is padding.
  expect(notice({ payload: answer({ options: [option("a1", "Kamla Devi")] }) })).toBe("");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The control cannot under-report its own value
 * ──────────────────────────────────────────────────────────────────────────── */

/*
  REAL CUIDS RATHER THAN "a1"/"a9" IN THESE TWO, AND THE SHAPE IS NOW LOAD-BEARING.

  Every other test in this file cares only that a held value gets a row and survives the round trip,
  so a two-character stand-in is fine there and is left alone. These two assert the WORDING of the
  row, and since the 2026-09-03 promotion the wording is chosen by `heldRow` from the token's shape:
  a record id is drawn as a link, and anything a person could have typed is drawn as itself. "a9" is
  not a shape Prisma produces, and pinning the link wording to it would have pinned the branch to a
  value the application cannot contain.
*/
const KAMLA = "cmsik2jg8000eh8xc1lcy661a";
const RAM = "cmsjb6qaq01ar4otfh1p0hm1a";

test("an id already on the field is drawn even when the answer no longer contains it", () => {
  const rows = referenceMultiOptions({
    payload: answer({ options: [option(KAMLA, "Kamla Devi", "Bagru")] }),
    values: [KAMLA, RAM]
  });
  // Nine stored, eight offered, "8 selected" on the trigger, and the next tick writes the eight
  // back: that is the defect this second half exists to close.
  expect(rows.map((row) => row.value)).toEqual([KAMLA, RAM]);
  expect(rows[1].label).toBe("Linked record cmsjb6qa");
  expect(rows[1].hint).toContain("already on this field");
});

test("a failed or absent answer still draws every stored id", () => {
  const rows = referenceMultiOptions({ payload: null, values: [KAMLA, RAM] });
  expect(rows).toHaveLength(2);
  // Which is what makes the error notice's promise true: what is already chosen can still be
  // removed, because it is still on the list to untick.
  expect(rows.every((row) => row.label.startsWith("Linked record "))).toBe(true);
});

test("a row this mount has already been shown keeps its NAME after the query moves on", () => {
  const seen = new Map<string, DwReferenceOption>([["a9", option("a9", "Ram Kumar", "Sanganer")]]);
  const rows = referenceMultiOptions({
    payload: answer({ options: [option("a1", "Kamla Devi")] }),
    values: ["a1", "a9"],
    seen
  });
  expect(rows[1].label).toBe("Ram Kumar");
  expect(rows[1].hint).toBe("Sanganer");
});

test("the server's order is never re-sorted, and a held row is appended last", () => {
  const rows = referenceMultiOptions({
    payload: answer({ options: [option("z1", "Zara"), option("a1", "Kamla Devi")] }),
    values: ["a1", "a9"]
  });
  // `name` then `id` is the server's total order and the reason two identical requests agree about
  // which rows fell inside the page. Re-sorting here would move rows under the designer's eye.
  expect(rows.map((row) => row.value)).toEqual(["z1", "a1", "a9"]);
});

test("an id that is both stored and offered is drawn once, with the server's own label", () => {
  const rows = referenceMultiOptions({
    payload: answer({ options: [option("a1", "Kamla Devi", "Bagru")] }),
    values: ["a1"]
  });
  expect(rows).toHaveLength(1);
  expect(rows[0].label).toBe("Kamla Devi");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The ceiling — shared by both arms, and it caps growth only
 * ──────────────────────────────────────────────────────────────────────────── */

test("growth past the ceiling is trimmed, and what did not fit is named", () => {
  const outcome = capListGrowth(["a1", "a2"], ["a1", "a2", "a3"], 2);
  expect(outcome.kept).toEqual(["a1", "a2"]);
  expect(outcome.refused).toEqual(["a3"]);
});

test("what is already held survives first, whatever order the panel hands back", () => {
  const outcome = capListGrowth(["a2"], ["a3", "a2", "a4"], 2);
  // `a2` is held, so it is kept wherever it sits; the ceiling is then filled from the front.
  expect(outcome.kept).toEqual(["a3", "a2"]);
  expect(outcome.refused).toEqual(["a4"]);
});

test("a shrink under a lowered ceiling passes through untouched", () => {
  // The 2026-08-26 defect, and the reason this is not a bare `next.length > cap` test: a cap is not
  // part of `registry_version()`, so a field can be holding five on the day its ceiling becomes
  // three. Unticking one used to delete a second value the designer never touched, reported as
  // "Not added" about something that had in fact just been removed.
  const outcome = capListGrowth(["a1", "a2", "a3", "a4", "a5"], ["a1", "a2", "a3", "a4"], 3);
  expect(outcome.kept).toEqual(["a1", "a2", "a3", "a4"]);
  expect(outcome.refused).toEqual([]);
});

test("a same-size swap under a lowered ceiling passes through untouched", () => {
  const outcome = capListGrowth(["a1", "a2", "a3"], ["a1", "a2", "a9"], 2);
  expect(outcome.kept).toEqual(["a1", "a2", "a9"]);
  expect(outcome.refused).toEqual([]);
});

test("a selection inside the ceiling is handed on as the identical array", () => {
  const next = ["a1", "a2"];
  // Identity, not equality: the enum arm has always passed `next` straight through, and a copy
  // would make an untrimmed commit look like a different value to anything comparing references.
  expect(capListGrowth([], next, 200).kept).toBe(next);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The call site — §11.5's contract for a server-truncated list
 * ──────────────────────────────────────────────────────────────────────────── */

test("MULTI_ENUM branches on refModel, and the record arm is what it reaches", () => {
  const arm = between(FIELD_INPUT, "function MultiEnumField({", "\n/* ─");
  expect(withoutComments(arm)).toContain("field.refModel ? (");
  expect(withoutComments(arm)).toContain("<ReferenceMultiSelect");
});

test("the record arm turns the picker's own filter off and says where the rest are", () => {
  const arm = recordArm();
  // Both halves, never one: `searchable={false}` does not switch the RENDER CAP off, so without a
  // `capHint` the footer's default last clause tells a designer to type into a filter box this
  // control deliberately does not have.
  expect(arm).toContain("searchable={false}");
  expect(arm).toContain("capHint={");
  expect(arm).toContain("Use the search box above to reach the rest");
});

test("the search box is the server's, and it sits above the picker", () => {
  const arm = recordArm();
  const beforePicker = between(arm, "return (", "<MultiSelectDropdown");
  expect(beforePicker).toContain("<SearchInput");
  expect(beforePicker).toContain("onChange={setQuery}");
  // Wired to the fetch, not to a client-side filter over whatever already arrived.
  expect(arm).toContain("useReferenceSearch({");
});

test("typing in that box cannot arm a record form's unsaved-changes prompt", () => {
  // A stage field can be rendered inside a mirrored record's own form, which marks itself dirty on
  // any native input event — and this is a real text input. `WorkshopSelect` carries the same
  // firewall for the same reason.
  expect(recordArm()).toContain("onInput={(event) => event.stopPropagation()}");
});

test("the panel's empty line and the line under the box are one authored sentence", () => {
  expect(recordArm()).toContain("emptyLabel={notice ||");
});

test("no limit is sent, so the borrowed truncation sentence stays true", () => {
  // `scopeNoticeLines` names the server's own default page in words ("the first 50"), so asking for
  // a different number would print that sentence over a list of another length.
  const fetcher = between(FIELD_INPUT, "listStageReferences(workshopId, {", "})");
  expect(withoutComments(fetcher)).not.toContain("limit");
});

test("the enum arm keeps the option count as its judge", () => {
  const enumArm = between(FIELD_INPUT, '<MultiSelectDropdown\n          values={values}', 'confirmLabel="Confirm"');
  // `field.options` is an authored vocabulary rather than a list of records, so forcing `searchable`
  // either way here would be wrong in one direction on every one of the 22 stages.
  expect(enumArm).not.toContain("searchable");
  expect(enumArm).not.toContain("capHint");
});

test("the dispatch hands the record arm the three things it cannot derive", () => {
  const dispatch = between(FIELD_INPUT, 'case "MULTI_ENUM":', 'case "TAGS":');
  expect(dispatch).toContain("entity={entity}");
  expect(dispatch).toContain("row={row}");
  expect(dispatch).toContain("workshopId={workshopId}");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The half a browser cannot hold
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE SERVER HALF LANDED ON 2026-09-03, AND THIS IS THE TEST THAT WAS WRITTEN TO FAIL WHEN IT DID.
 *
 * What stood here asserted the opposite — that `coerce_value` still ran every MULTI_ENUM token past
 * `ENUMS.get(spec.enum, {})` and that `format_value` still printed `enum_label(...)` — precisely so
 * that repairing the server would break this file and force `MultiEnumField`'s warning paragraph to
 * be corrected in the same change rather than left standing over a fixed thing. It did, and it was.
 * The assertions are now the mirror image, so the same file goes on being the place the claim lives.
 *
 * THREE CLAIMS, AND THE THIRD IS THE ONE WITH A DESIGNER ON THE END OF IT. The first two are that
 * the two repairs exist. The third is that the OLD SHAPE IS STILL ACCEPTED: `processStep.toolsUsed`
 * and `prototype.toolsUsed` were free-text TAGS until this promotion, so every 0.0.7 handset in the
 * field still draws a tag box for them and submits `["pit loom", …]`. If coercion ever starts
 * checking those tokens against anything, that handset's save is refused — and `saveOrQueue` does
 * not retry a 4xx, so the stage is lost rather than degraded.
 */
test("the backend has learnt a record-backed MULTI_ENUM, and still keeps what the old box wrote", () => {
  const coerce = readRepo("backend", "app", "services", "stage_schema.py");
  expect(
    coerce,
    "coerce_value no longer exempts a record-backed MULTI_ENUM from the enum allow-list — every id would be refused on save"
  ).toContain("if t is FieldType.MULTI_ENUM and not spec.ref_model:");
  // The allow-list still exists, for the five MULTI_ENUM fields that DO name a vocabulary. An
  // exemption that had quietly become universal would pass the assertion above and check nothing.
  expect(coerce, "the enum allow-list has gone entirely, not just been narrowed").toContain(
    "allowed = ENUMS.get(spec.enum, {})"
  );

  const report = readRepo("backend", "app", "services", "report_builder.py");
  expect(
    report,
    "ReportBuilder._value no longer resolves a record-backed MULTI_ENUM's tokens — the report is printing cuids"
  ).toContain("if spec.type is FieldType.MULTI_ENUM and spec.ref_model:");

  // NOTHING WAS MIGRATED, AND NOTHING SHOULD BE. Both shapes live in one stored array — ids from the
  // new picker, prose from the old box and from every handset still running the old registry — so
  // the promotion owes a reader a statement that the free text is still printed. That statement is
  // the `elif not _looks_like_an_id(text)` arm, and this is what keeps it from being deleted as
  // dead-looking code by somebody who assumes a MULTI_ENUM only ever holds ids.
  expect(report, "a promoted field's free text is no longer printed verbatim by the report").toContain(
    "elif not _looks_like_an_id(text):"
  );
});

/**
 * AND THE REGISTRY ITSELF — WHICH IS NOW READ FROM TWO PLACES, BECAUSE THEY MOVE AT DIFFERENT TIMES.
 *
 * This used to read the bundled Android asset alone and assert that NO MULTI_ENUM named a ref model.
 * Two of them now do, and the asset cannot be the judge of that on its own: it is a by-value dump of
 * `registry_to_dict()` produced by an operator command (`StageSchema.kt`'s header carries it), so it
 * lags the registry by however long it takes somebody to re-dump it. Asserting the new state against
 * the asset would fail until that happened; asserting the OLD state against it would pin the
 * staleness and fail the moment it was fixed. So the two claims are split by source:
 *
 *  · WHAT THE REGISTRY DECLARES is read from `stage_definitions.py`, which is the source of truth and
 *    moves in the same commit as the promotion.
 *  · WHAT SURVIVES A DUMP is read from the asset, and only for the claim that is true either side of
 *    a regeneration: that the walk finds MULTI_ENUM fields at all (otherwise it proves nothing), and
 *    that exactly one of them is BASIC and required — the field this whole branch was written for.
 *    Both promoted fields are STANDARD and optional, deliberately, so that pin does not move.
 */
test("the registry declares two record-backed MULTI_ENUM fields, and neither can brick a stage", () => {
  const registry = readRepo("backend", "app", "services", "stage_definitions.py");

  // Read as source rather than counted, because what matters is that BOTH declarations carry the
  // ref model — a promotion applied to one of the two would leave a designer typing tool names on
  // stage 5 and picking records on stage 13 for the same tools.
  const promoted = [...registry.matchAll(/f\(\s*"toolsUsed",\s*"Tools used",\s*(\w+),/g)].map((m) => m[1]);
  expect(promoted, "stage_definitions no longer declares exactly two toolsUsed fields").toHaveLength(2);
  expect(promoted, "a toolsUsed field is still TAGS — the promotion was applied to only one of them").toEqual([
    "MENUM",
    "MENUM"
  ]);
  expect(
    registry.match(/ref_model="ToolDocumentation"/g)?.length,
    "the two promoted boxes plus tool.toolRef are the three ToolDocumentation pickers"
  ).toBe(3);

  const schema = JSON.parse(
    readRepo("android", "app", "src", "main", "assets", "design-workshop-schema.json")
  ) as {
    stages: { number: number; entities: { key: string; fields: DwField[] }[] }[];
  };

  const multiEnums = schema.stages.flatMap((stage) =>
    stage.entities.flatMap((entity) =>
      entity.fields.filter((field) => field.type === "MULTI_ENUM").map((field) => ({ stage, entity, field }))
    )
  );

  expect(multiEnums.length, "no MULTI_ENUM in the shipped schema — this walk is proving nothing").toBeGreaterThan(0);

  // The field the whole branch is written for, named rather than described, so that a registry
  // change which removes it also fails here and the header stops naming a field nobody has. It is
  // also the assertion that would catch a future promotion of a BASIC required TAGS box into a
  // closed list, which is the one shape of this change that can leave a stage unsubmittable.
  const brickable = multiEnums.filter((row) => row.field.tier === "BASIC" && row.field.required);
  expect(brickable.map((row) => `${row.stage.number}:${row.entity.key}.${row.field.key}`)).toEqual([
    "10:designBrief.targetCategories"
  ]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. A promoted field's array holds two shapes, and the picker draws both
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE REGRESSION THIS PROMOTION COULD HAVE SHIPPED, ASSERTED SO IT CANNOT COME BACK.
 *
 * `processStep.toolsUsed` was a TAGS box for the whole life of the app before 2026-09-03, so every
 * value stored under it today is a tool NAME a designer typed, and a 0.0.7 handset is still writing
 * more of them. `referenceMultiOptions` gives every held id a row so the control cannot silently
 * drop what it cannot see — and that row used to be `orphanRow` unconditionally, which would have
 * drawn "pit loom" as `Linked record pit loom` under a hint saying the repository did not return it.
 *
 * Two false statements in one row: it is not a linked record, and nothing failed to return it. The
 * designer's own word for their own fieldwork, replaced on screen by a claim about a link nobody
 * made — and the stage-13 half of the same field would have done it to the same words.
 */
test("free text stored before the promotion is drawn as the words it is, not as a broken link", () => {
  const rows = referenceMultiOptions({
    payload: answer({ options: [option("cmsik2jg8000eh8xc1lcy661a", "Pit loom", "Bargarh")] }),
    values: ["cmsik2jg8000eh8xc1lcy661a", "pit loom", "SK-01", "cmsjb6qaq01ar4otfh1p0hm1a"]
  });

  expect(rows.map((row) => row.label)).toEqual([
    // Offered by the server, and named by it.
    "Pit loom",
    // Typed. Both are kept verbatim: a space fails the id shape, and so do capitals and a dash.
    "pit loom",
    "SK-01",
    // A real id the current search answer does not contain — still a link, still removable.
    "Linked record cmsjb6qa"
  ]);

  // The hint is what separates the two kinds for a designer reading the list, and it is the only
  // thing on screen that can: both rows are just text in a dropdown.
  expect(rows[1].hint).toBe("typed on this field, not a linked record");
  expect(rows[3].hint).toContain("not in the list the repository returned");
});

test("every held value survives the round trip, whichever shape it is", () => {
  // The defect `referenceMultiOptions` exists to prevent, restated for a mixed array: a control that
  // draws three of four stored entries hands back three, and the fourth is gone with nothing said.
  const values = ["pit loom", "cmsik2jg8000eh8xc1lcy661a", "बुनाई"];
  const rows = referenceMultiOptions({ payload: answer(), values });
  expect(rows.map((row) => row.value)).toEqual(values);
});
