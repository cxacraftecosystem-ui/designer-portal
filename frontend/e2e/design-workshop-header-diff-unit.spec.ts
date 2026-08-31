import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  changedKeys,
  EDITABLE_KEYS,
  EDITABLE_KEY_SET,
  storedText,
  type EditableKey
} from "@/components/designworkshop/headerDiff";
import type { DwSummary } from "@/lib/designWorkshops";

/** CRLF-normalised, so a marker below cannot depend on which checkout this ran on. */
const read = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

/**
 * The SHIPPED CODE, with the prose taken out — the same helper `dropdown-sweep-unit.spec.ts` uses.
 *
 * Every `not.toContain` below is a claim about what the form DOES, and this file's subject is a
 * change whose argument has to name the thing it removed: the comment beside the blank-title guard
 * says in as many words that it used to call `setCustomValidity` on a `namedItem("title")` that no
 * longer exists. A raw read cannot tell a shipped call from a sentence explaining why that call is
 * wrong here, so it would fail on the explanation and pass on the deletion.
 */
function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*/g, "$1");
}

/**
 * A SAVE THAT TOUCHED NOTHING MUST WRITE NOTHING — requirement 27's one silent failure.
 *
 * `PATCH /design-workshops/{id}` reads its body with `model_dump(exclude_unset=True)`, and its
 * handler turns a blank string into NULL rather than dropping it. Those two facts together make the
 * edit form's body the DIFFERENCE between the screen and the seed, not the screen:
 *
 *   absent  → the stored value stands
 *   null/"" → the column is CLEARED
 *
 * An uncontrolled form reads `""` out of every box nobody typed in. So a body built from the form
 * rather than from the diff blanks a workshop's craft, cluster, state, district, dates, notes and
 * its link to a Workshop record in one press — answered **200**, with the word "Saved" on screen and
 * nothing anywhere to read afterwards. Five of those columns are what the design-workshops list
 * filters and searches on, so the workshop does not merely lose its cover values: it falls out of
 * every list that would have found it again.
 *
 * WHY A NODE SPEC AND NOT A SIGNED-IN ONE. The defect is a decision about which keys go on the wire,
 * and it is invisible in a browser: the screen after a wrong save looks exactly like the screen after
 * a right one, and the columns it emptied are ones an eye does not miss until a fortnight later. The
 * function that decides it is pure on purpose. `components/designworkshop/headerDiff.ts` is where it
 * lives so that this file can call it — the form itself is `"use client"` and pulls in React,
 * `next/navigation`, `lucide-react` and an IndexedDB store, none of which a Node spec can import.
 *
 * WHAT THIS FILE CANNOT PROVE, and it belongs in `design-workshop-server-refusal.spec.ts` and in
 * `backend/tests/test_design_workshops.py` rather than here: that the browser's `FormData` really
 * carries the ten names this list uses, and that the server really leaves an absent key alone. Both
 * are pinned there. What is pinned HERE is the one link between them — that an untouched box
 * produces no key at all.
 */

/**
 * A workshop with every editable column filled in, INCLUDING the three that no stage-1 save can
 * reach (`notes`, `templateId`, `workshopId`). They are the ones with no second writer anywhere in
 * the product, so a diff that wrongly clears one of them has destroyed the only copy.
 */
const STORED: DwSummary = {
  id: "dw_stored",
  title: "Bagru hand-block printing, monsoon sitting",
  templateId: "DCH_STANDARD",
  status: "IN_PROGRESS",
  workshopCode: "DPW-2026-0141",
  scheme: "Design & Technology Upgradation",
  craftName: "Hand-block printing",
  clusterName: "Bagru",
  state: "Rajasthan",
  district: "Jaipur",
  venue: "Chhipa Mohalla community hall",
  startDate: "2026-07-20",
  endDate: "2026-08-02",
  designerName: "A. Deshpande",
  implementingAgency: "NIFT Jodhpur",
  sponsor: "Office of the Development Commissioner (Handicrafts)",
  notes: "Bring the indigo vat readings from the April sitting.",
  workshopId: "wk_monsoon",
  createdById: "usr_admin",
  createdAt: "2026-07-01T09:00:00+00:00",
  updatedAt: "2026-07-19T11:20:00+00:00",
  deletedAt: null,
  dictationConsent: "NOT_RECORDED",
  dictationConsentAt: null,
  dictationConsentById: null
} as DwSummary;

/** The boxes as the form reads them when NOBODY has typed: seeded, then trimmed. */
function untouched(stored: DwSummary): Record<EditableKey, string> {
  return Object.fromEntries(EDITABLE_KEYS.map((key) => [key, storedText(stored[key])])) as Record<
    EditableKey,
    string
  >;
}

test("THE ONE THAT MATTERS: a form nobody typed in sends no keys at all", () => {
  expect(changedKeys(untouched(STORED), STORED)).toEqual([]);
});

test("posting the form as-is — every box read straight out of the DOM — is what would clear the columns", () => {
  // The shape the diff exists to refuse: an uncontrolled form where the six nullable columns came
  // back empty. Every one of these keys would go on the wire as `null`.
  const asIfBlank = { ...untouched(STORED), craftName: "", clusterName: "", state: "", district: "", notes: "" };
  expect(changedKeys(asIfBlank, STORED)).toEqual([
    "craftName",
    "clusterName",
    "state",
    "district",
    "notes"
  ]);
});

test("one box typed into sends one key, and the other nine stay absent", () => {
  const onScreen = { ...untouched(STORED), clusterName: "Sanganer" };
  expect(changedKeys(onScreen, STORED)).toEqual(["clusterName"]);
});

test("a box typed into and typed back is absent again — an idle edit writes nothing", () => {
  // Six of these columns are ALSO written by stage 1, so a no-op write is a second writer taking a
  // turn for no reason. Nothing on screen would show it and the row's `updatedAt` would move.
  const onScreen = { ...untouched(STORED), craftName: STORED.craftName ?? "" };
  expect(changedKeys(onScreen, STORED)).toEqual([]);
});

test("EMPTYING a box that held something IS a change — clearing has to stay reachable", () => {
  // The mirror of the test above, and the reason the diff cannot simply drop blanks: `notes` has no
  // stage-1 twin, so this form is the only thing in the whole product that can clear it.
  expect(changedKeys({ ...untouched(STORED), notes: "" }, STORED)).toEqual(["notes"]);
  expect(changedKeys({ ...untouched(STORED), workshopId: "" }, STORED)).toEqual(["workshopId"]);
});

test("a column stored as NULL and a box left empty are the SAME fact, not a diff", () => {
  // Otherwise every save on a young workshop would send `null` over NULL for every box it has never
  // filled in — six no-op writes against columns stage 1 also owns, on every press.
  const young = { ...STORED, craftName: null, clusterName: null, state: null, district: null,
    startDate: null, endDate: null, notes: null, workshopId: null } as DwSummary;
  expect(changedKeys(untouched(young), young)).toEqual([]);
});

test("whitespace either side of a stored value is not a change — the server strips before it stores", () => {
  // `_header_patch_data` strips and the form trims, so "Ikat" and "Ikat  " are one value. Comparing
  // them raw would send `craftName` on every save of every row an older create wrote unstripped.
  const padded = { ...STORED, craftName: "  Hand-block printing  ", notes: "\nBring the indigo vat readings from the April sitting.\n" } as DwSummary;
  expect(changedKeys(untouched(padded), padded)).toEqual([]);
  expect(storedText("  Ikat  ")).toBe("Ikat");
});

test("a value the server did not send, or sent as something other than a string, reads as empty rather than as a diff", () => {
  // The seed is a parsed HTTP response. A key dropped from `workshop_summary`, or a date answered as
  // a number, must leave the box comparing EQUAL to an empty one — never produce a clear for a
  // column nobody touched.
  const odd = { ...STORED, craftName: undefined, startDate: 20260720 } as unknown as DwSummary;
  const onScreen = { ...untouched(STORED), craftName: "", startDate: "" };
  expect(changedKeys(onScreen, odd)).toEqual([]);
});

test("`status` is not one of the keys this form can send, and neither is any refused column", () => {
  /*
    The route accepts `status`; the form must never carry it. The record page's SubmissionCard owns
    it — Mark complete / Submit / Reopen, each with its own confirmation and its own readiness count
    — and a second writer would let somebody archive a workshop while renaming it.

    The rest are `_NEVER_PATCHABLE`: the six cover columns stage 1 owns, the provenance stamps, the
    consent record and the two designer keys. `DesignWorkshopPatch` refuses a body that merely
    CARRIES one, by name and with the whole request unwritten — so a key added to this list without
    a matching change on the server turns every save into a 422.
  */
  for (const refused of [
    "status",
    "venue",
    "scheme",
    "designerName",
    "implementingAgency",
    "sponsor",
    "workshopCode",
    "designerUserId",
    "designerUserIds",
    "createdById",
    "schemaVersion",
    "dictationConsent"
  ]) {
    expect(EDITABLE_KEY_SET.has(refused)).toBe(false);
  }
  expect([...EDITABLE_KEYS]).toEqual([
    "title",
    "templateId",
    "craftName",
    "clusterName",
    "state",
    "district",
    "startDate",
    "endDate",
    "workshopId",
    "notes"
  ]);
});

test("the dates compare in the `yyyy-mm-dd` the column serialises, so an untouched range is not sent", () => {
  /*
    `workshop_summary` answers `record.startDate.date().isoformat()` and the form's two hidden inputs
    are written by `toIsoDate`. The two spellings have to be byte-identical or every save would
    re-write both promoted date columns — and a display-formatted value ("20/07/2026") would reach
    `_parse_date`, which cannot read it, and answer 422 on a form nobody had touched the dates on.
  */
  expect(changedKeys({ ...untouched(STORED), startDate: "2026-07-20", endDate: "2026-08-02" }, STORED)).toEqual([]);
  expect(changedKeys({ ...untouched(STORED), startDate: "20/07/2026" }, STORED)).toEqual(["startDate"]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * THE TITLE BOX, WHICH BECAME A THEMED CONTROL ON 2026-08-31
 *
 * A previous lane left this box a plain `<input name="title">` deliberately and
 * wrote down why: *"converting it means a themed control inside `changedKeys`'
 * value diff on a PATCH that can clear NOT NULL columns."* The conversion has
 * now happened, so the hazard needs a test rather than a warning. Three things
 * have to hold, and each of them is a different failure:
 *
 *   1. an UNTOUCHED title carries no key — otherwise every save re-writes the
 *      column stage 1 also owns, and `updatedAt` moves for nothing;
 *   2. a CLEARED title cannot reach the wire — `title` is NOT NULL and `""` is
 *      the PATCH's spelling of CLEAR, so a blank would be a 422 at best;
 *   3. a REAL rename still goes.
 *
 * The first and third are `changedKeys`' and are exercised directly. The second
 * is a property of the CONTROL — there is no route to a blank — so it is read
 * off the source, which is the only place it can be checked without a browser.
 * ──────────────────────────────────────────────────────────────────────────── */

const HEADER_FORM = ["components", "designworkshop", "DesignWorkshopHeaderForm.tsx"];

test("the title survives becoming a dropdown: untouched sends nothing, a rename sends one key", () => {
  // The state is SEEDED from `initial.title` and compared through the same trim `storedText`
  // applies, so "the designer opened the form and pressed Save" is still an empty body.
  expect(changedKeys(untouched(STORED), STORED)).toEqual([]);
  expect(changedKeys({ ...untouched(STORED), title: STORED.title }, STORED)).toEqual([]);
  // Whitespace is not a rename either — the control commits a trimmed term and the server strips.
  expect(changedKeys({ ...untouched(STORED), title: `  ${STORED.title}  `.trim() }, STORED)).toEqual([]);
  // And a real one still goes, alone.
  expect(changedKeys({ ...untouched(STORED), title: "Bagru hand-block printing, winter sitting" }, STORED)).toEqual([
    "title"
  ]);
});

test("a blank title has no route to the wire, and the refusal that cannot fire is still there", () => {
  const source = read(...HEADER_FORM);
  const code = withoutComments(source);

  /*
    NO `noneLabel` ON THE TITLE PICKER. That prop is what draws a row carrying `value: ""` — the way
    a record is UN-FILED — and on a NOT NULL column it would be a row offering to leave a ministry
    document unnamed. The link picker below it DOES pass one (`NO_FIELD_WORKSHOP`), correctly, which
    is why this is asserted against the title control's own call rather than against the file.
  */
  const titleField = code.slice(code.indexOf('label="Workshop title"'), code.indexOf('label="Report template"'));
  expect(titleField.length, "the title control has moved — this slice is no longer measuring it").toBeGreaterThan(0);
  expect(titleField, "a row carrying `value: \"\"` on a NOT NULL column").not.toContain("noneLabel");
  // The create row is the other door in, and `SelectCreateAction` is offered only for a non-empty
  // term — `SearchableSelect`'s `createTerm` returns null for a blank query.
  expect(titleField).toContain("createAction={{ label: workshopNameCreateLabel, onCreate: commitTitle }}");

  /*
    AND THE GUARD OVER THE STATE THAT NOW HAS NO ROUTE TO IT STAYS. `min_length=1` catches `""` and
    not `"   "`, so whitespace would reach `_header_patch_data`, be stripped, and answer a 422 the
    reader has to match to a box themselves. It is cheap, and being wrong once about "that cannot
    happen" costs a NOT NULL column on a PATCH whose contract is that `""` means CLEAR.
  */
  expect(code).toContain("if (!onScreen.title) {");
  expect(code).toContain("Every workshop has a title");
  /*
    IT IS A RENDERED REFUSAL NOW AND NOT `reportValidity`: constraint validation is a property of
    form CONTROLS, and a themed dropdown is a `<button>` that submits nothing and validates nothing.
    A leftover call would be a refusal nobody ever sees, on the one box that must not be emptied.
  */
  expect(code, "constraint validation on a control that cannot carry it").not.toContain("setCustomValidity");
  expect(code, "a `title` box that no longer exists").not.toContain('namedItem("title")');
});

test("the title is read from state, not from the FormData the dropdown does not write", () => {
  const source = withoutComments(read(...HEADER_FORM));

  /*
    THE ONE LINE THE WHOLE CONVERSION TURNS ON. A themed dropdown is a `<button>`: it submits
    nothing, so `new FormData(form).get("title")` is `null` and `box("title")` is `""` — which
    `changedKeys` reads as "the designer emptied the title" on EVERY save, on a NOT NULL column.
    `templateId` beside it has been React state since this form was written, for the same reason.
  */
  expect(source).toContain("title: title.trim(),");
  expect(source, "the title read back out of the DOM").not.toContain('title: box("title")');
  expect(source).toContain('const [title, setTitle] = useState(initial.title ?? "");');

  // A themed control fires no native input event, so the form's `onInput` never sees it: the guard
  // is armed by hand, through the same function every other themed control on this form calls.
  expect(source).toContain("function commitTitle(next: string) {\n    noteEdit();");
});
