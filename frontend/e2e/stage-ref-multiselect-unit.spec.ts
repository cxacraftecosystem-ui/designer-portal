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

test("an id already on the field is drawn even when the answer no longer contains it", () => {
  const rows = referenceMultiOptions({
    payload: answer({ options: [option("a1", "Kamla Devi", "Bagru")] }),
    values: ["a1", "a9"]
  });
  // Nine stored, eight offered, "8 selected" on the trigger, and the next tick writes the eight
  // back: that is the defect this second half exists to close.
  expect(rows.map((row) => row.value)).toEqual(["a1", "a9"]);
  expect(rows[1].label).toBe("Linked record a9");
  expect(rows[1].hint).toContain("already on this field");
});

test("a failed or absent answer still draws every stored id", () => {
  const rows = referenceMultiOptions({ payload: null, values: ["a1", "a2"] });
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
 * THE SERVER STILL REFUSES A RECORD-BACKED MULTI_ENUM, AND THIS IS HOW THAT SENTENCE STAYS
 * RE-CHECKABLE.
 *
 * `MultiEnumField`'s header says, in as many words, that `ref_model` must not be declared on a
 * MULTI_ENUM until two server-side repairs land with it: `coerce_value` tests every token against
 * `ENUMS.get(spec.enum, {})` — the EMPTY map for a field with a ref model and no enum, so every
 * record id comes back as "unknown option(s) …" and the field is refused on every save — and
 * `format_value` prints `enum_label(spec.enum, token)`, which falls back to the raw token, so the
 * report would carry CUIDs where a roster of names belongs.
 *
 * A comment saying that rots the day it stops being true, and a claim nobody can re-check is what
 * this repository's docs gate exists to flag. So the claim is asserted instead: this test FAILS
 * when the server half is repaired, which is the moment somebody has to come back and correct the
 * paragraph rather than leave a warning standing over a fixed thing.
 */
test("the backend has not yet learnt a record-backed MULTI_ENUM, so the warning above the arm stands", () => {
  const coerce = readRepo("backend", "app", "services", "stage_schema.py");
  expect(
    coerce,
    "coerce_value now resolves a MULTI_ENUM's tokens differently — correct MultiEnumField's header with it"
  ).toContain("allowed = ENUMS.get(spec.enum, {})");

  const report = readRepo("backend", "app", "services", "report_builder.py");
  expect(
    report,
    "format_value now resolves a MULTI_ENUM's tokens through something other than the enum table"
  ).toContain('return ", ".join(enum_label(spec.enum, str(v)) for v in value)');
});

/**
 * AND THE REGISTRY ITSELF, READ AS THE CLIENTS RECEIVE IT.
 *
 * The shipped schema is the one artefact that answers "what does a MULTI_ENUM actually look like on
 * the wire" without anybody parsing Python: `field_to_dict` emits `refModel` for ANY field that
 * declares one, whatever its type, so a MULTI_ENUM carrying one would be visible here. Android's
 * roster picker leans on the same file for the same claim — its own comment says every `refModel`
 * in this asset is on a `REF`.
 *
 * Two assertions, and the second is what stops the first being vacuous: a walk that found no
 * MULTI_ENUM fields at all would pass while proving nothing.
 */
test("no registry MULTI_ENUM names a ref model yet, and one of them is BASIC and required", () => {
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
  expect(
    multiEnums.filter((row) => row.field.refModel).map((row) => `${row.entity.key}.${row.field.key}`),
    "a registry MULTI_ENUM now names a ref model — the server half above must have landed with it"
  ).toEqual([]);

  // The field the whole branch is written for, named rather than described, so that a registry
  // change which removes it also fails here and the header stops naming a field nobody has.
  const brickable = multiEnums.filter((row) => row.field.tier === "BASIC" && row.field.required);
  expect(brickable.map((row) => `${row.stage.number}:${row.entity.key}.${row.field.key}`)).toEqual([
    "10:designBrief.targetCategories"
  ]);
});
