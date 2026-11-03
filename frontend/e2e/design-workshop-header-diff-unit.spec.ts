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

const read = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*/g, "$1");
}

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
  const onScreen = { ...untouched(STORED), craftName: STORED.craftName ?? "" };
  expect(changedKeys(onScreen, STORED)).toEqual([]);
});

test("EMPTYING a box that held something IS a change — clearing has to stay reachable", () => {
  expect(changedKeys({ ...untouched(STORED), notes: "" }, STORED)).toEqual(["notes"]);
  expect(changedKeys({ ...untouched(STORED), workshopId: "" }, STORED)).toEqual(["workshopId"]);
});

test("a column stored as NULL and a box left empty are the SAME fact, not a diff", () => {
  const young = { ...STORED, craftName: null, clusterName: null, state: null, district: null,
    startDate: null, endDate: null, notes: null, workshopId: null } as DwSummary;
  expect(changedKeys(untouched(young), young)).toEqual([]);
});

test("whitespace either side of a stored value is not a change — the server strips before it stores", () => {
  const padded = { ...STORED, craftName: "  Hand-block printing  ", notes: "\nBring the indigo vat readings from the April sitting.\n" } as DwSummary;
  expect(changedKeys(untouched(padded), padded)).toEqual([]);
  expect(storedText("  Ikat  ")).toBe("Ikat");
});

test("a value the server did not send, or sent as something other than a string, reads as empty rather than as a diff", () => {
  const odd = { ...STORED, craftName: undefined, startDate: 20260720 } as unknown as DwSummary;
  const onScreen = { ...untouched(STORED), craftName: "", startDate: "" };
  expect(changedKeys(onScreen, odd)).toEqual([]);
});

test("`status` is not one of the keys this form can send, and neither is any refused column", () => {
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
  expect(changedKeys({ ...untouched(STORED), startDate: "2026-07-20", endDate: "2026-08-02" }, STORED)).toEqual([]);
  expect(changedKeys({ ...untouched(STORED), startDate: "20/07/2026" }, STORED)).toEqual(["startDate"]);
});

const HEADER_FORM = ["components", "designworkshop", "DesignWorkshopHeaderForm.tsx"];

test("the title survives becoming a dropdown: untouched sends nothing, a rename sends one key", () => {
  expect(changedKeys(untouched(STORED), STORED)).toEqual([]);
  expect(changedKeys({ ...untouched(STORED), title: STORED.title }, STORED)).toEqual([]);
  expect(changedKeys({ ...untouched(STORED), title: `  ${STORED.title}  `.trim() }, STORED)).toEqual([]);
  expect(changedKeys({ ...untouched(STORED), title: "Bagru hand-block printing, winter sitting" }, STORED)).toEqual([
    "title"
  ]);
});

test("a blank title has no route to the wire, and the refusal that cannot fire is still there", () => {
  const source = read(...HEADER_FORM);
  const code = withoutComments(source);

  const titleField = code.slice(code.indexOf('label="Workshop title"'), code.indexOf('label="Report template"'));
  expect(titleField.length, "the title control has moved — this slice is no longer measuring it").toBeGreaterThan(0);
  expect(titleField, "a row carrying `value: \"\"` on a NOT NULL column").not.toContain("noneLabel");
  expect(titleField).toContain("createAction={{ label: workshopNameCreateLabel, onCreate: commitTitle }}");

  expect(code).toContain("if (!onScreen.title) {");
  expect(code).toContain("Every workshop has a title");
  expect(code, "constraint validation on a control that cannot carry it").not.toContain("setCustomValidity");
  expect(code, "a `title` box that no longer exists").not.toContain('namedItem("title")');
});

test("the title is read from state, not from the FormData the dropdown does not write", () => {
  const source = withoutComments(read(...HEADER_FORM));

  expect(source).toContain("title: title.trim(),");
  expect(source, "the title read back out of the DOM").not.toContain('title: box("title")');
  expect(source).toContain('const [title, setTitle] = useState(initial.title ?? "");');

  expect(source).toContain("function commitTitle(next: string) {\n    noteEdit();");
});
