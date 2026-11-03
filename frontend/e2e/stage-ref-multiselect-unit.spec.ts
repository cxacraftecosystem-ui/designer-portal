import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  capListGrowth,
  referenceMultiNotice,
  referenceMultiOptions
} from "@/components/designworkshop/FieldInput";
import type { DwField, DwReferenceOption, DwReferencePayload } from "@/lib/designWorkshops";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");
const readRepo = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const FIELD_INPUT = read("components", "designworkshop", "FieldInput.tsx");

function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/(^|[^:])\/\/.*/g, "$1");
}

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

const recordArm = () => between(FIELD_INPUT, "function ReferenceMultiSelect({", "function MultiEnumField({");

const ROSTER: DwField = {
  key: "targetCategories",
  label: "Target product categories",
  type: "MULTI_ENUM",
  tier: "BASIC",
  required: true,
  refModel: "Artisan",
  refScope: "WORKSHOP"
};

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
    expect(line.length, `${state}: too short to be an explanation`).toBeGreaterThan(24);
  }
});

test("a list that could not be loaded does not read as a repository with no records", () => {
  const line = notice({ payload: null, problem: "The list could not be loaded." });
  expect(line).toContain("The list could not be loaded.");
  expect(line).toContain("still listed and can be removed");
  expect(line).not.toContain("has been documented yet");
});

test("an unanswered cascade names the box to answer first, and never blames the repository", () => {
  const line = notice({ field: CASCADED, awaitingCascade: true, parentLabel: "Artisan", payload: null });
  expect(line).toContain("Artisan");
  expect(line).toContain("first");
  expect(line).not.toContain("has been documented yet");

  expect(notice({ field: CASCADED, awaitingCascade: true, parentLabel: "", payload: null })).toContain(
    "the field above"
  );
});

test("an empty repository says what has to exist first, and names the field that cannot be answered", () => {
  const line = notice({ payload: answer({ scopedToWorkshop: false }) });
  expect(line).toContain("Target product categories");
  expect(line).toContain("then link it here");
  expect(line).toContain("Create the record elsewhere first");
  expect(line).not.toContain("Create one here");
});

test("the linked-workshop sentence is the server's own, said once and never doubled", () => {
  const line = notice({ payload: answer({ scopedToWorkshop: true }) });
  expect(line).toContain("linked workshop yet");
  expect(line).not.toContain("then link it here");
});

test("a typed term replaces the scope's empty-list claim instead of stacking on it", () => {
  const line = notice({ payload: answer({ scopedToWorkshop: true }), query: "kamla" });
  expect(line).not.toContain("Nothing is documented under this design workshop");
  expect(line).toContain("kamla");
  expect(line).toContain("in this design workshop's linked workshop");
});

test("an unlinked workshop keeps its widened-net sentence even under a search", () => {
  const line = notice({ payload: answer({ scopedToWorkshop: false }), query: "kamla" });
  expect(line).toContain("Not linked to a workshop yet");
  expect(line).toContain("Nothing matches");
});

test("a search still in flight says so rather than reporting no match", () => {
  const line = notice({ payload: answer(), query: "kam", loading: true });
  expect(line).toContain("Searching");
  expect(line).not.toContain("Nothing matches");
});

test("a truncated answer keeps the server's own truncation sentence", () => {
  const line = notice({ payload: answer({ truncated: true, options: [option("a1", "Kamla Devi")] }) });
  expect(line).toContain("First 50 matches");
});

test("a whole, ordinary list says nothing at all", () => {
  expect(notice({ payload: answer({ options: [option("a1", "Kamla Devi")] }) })).toBe("");
});

const KAMLA = "cmsik2jg8000eh8xc1lcy661a";
const RAM = "cmsjb6qaq01ar4otfh1p0hm1a";

test("an id already on the field is drawn even when the answer no longer contains it", () => {
  const rows = referenceMultiOptions({
    payload: answer({ options: [option(KAMLA, "Kamla Devi", "Bagru")] }),
    values: [KAMLA, RAM]
  });
  expect(rows.map((row) => row.value)).toEqual([KAMLA, RAM]);
  expect(rows[1].label).toBe("Linked record cmsjb6qa");
  expect(rows[1].hint).toContain("already on this field");
});

test("a failed or absent answer still draws every stored id", () => {
  const rows = referenceMultiOptions({ payload: null, values: [KAMLA, RAM] });
  expect(rows).toHaveLength(2);
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

test("growth past the ceiling is trimmed, and what did not fit is named", () => {
  const outcome = capListGrowth(["a1", "a2"], ["a1", "a2", "a3"], 2);
  expect(outcome.kept).toEqual(["a1", "a2"]);
  expect(outcome.refused).toEqual(["a3"]);
});

test("what is already held survives first, whatever order the panel hands back", () => {
  const outcome = capListGrowth(["a2"], ["a3", "a2", "a4"], 2);
  expect(outcome.kept).toEqual(["a3", "a2"]);
  expect(outcome.refused).toEqual(["a4"]);
});

test("a shrink under a lowered ceiling passes through untouched", () => {
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
  expect(capListGrowth([], next, 200).kept).toBe(next);
});

test("MULTI_ENUM branches on refModel, and the record arm is what it reaches", () => {
  const arm = between(FIELD_INPUT, "function MultiEnumField({", "const ALLOWED_TYPES:");
  expect(withoutComments(arm)).toContain("field.refModel ? (");
  expect(withoutComments(arm)).toContain("<ReferenceMultiSelect");
});

test("the record arm turns the picker's own filter off and says where the rest are", () => {
  const arm = recordArm();
  expect(arm).toContain("searchable={false}");
  expect(arm).toContain("capHint={");
  expect(arm).toContain("Search above to find the rest");
});

test("the search box is the server's, and it sits above the picker", () => {
  const arm = recordArm();
  const beforePicker = between(arm, "return (", "<MultiSelectDropdown");
  expect(beforePicker).toContain("<SearchInput");
  expect(beforePicker).toContain("onChange={setQuery}");
  expect(arm).toContain("useReferenceSearch({");
});

test("typing in that box cannot arm a record form's unsaved-changes prompt", () => {
  expect(recordArm()).toContain("onInput={(event) => event.stopPropagation()}");
});

test("the panel's empty line and the line under the box are one authored sentence", () => {
  expect(recordArm()).toContain("emptyLabel={notice ||");
});

test("no limit is sent, so the borrowed truncation sentence stays true", () => {
  const fetcher = between(FIELD_INPUT, "listStageReferences(workshopId, {", "})");
  expect(withoutComments(fetcher)).not.toContain("limit");
});

test("the enum arm keeps the option count as its judge", () => {
  const enumArm = between(FIELD_INPUT, '<MultiSelectDropdown\n          values={values}', 'confirmLabel="Confirm"');
  expect(enumArm).not.toContain("searchable");
  expect(enumArm).not.toContain("capHint");
});

test("the dispatch hands the record arm the three things it cannot derive", () => {
  const dispatch = between(FIELD_INPUT, 'case "MULTI_ENUM":', 'case "TAGS":');
  expect(dispatch).toContain("entity={entity}");
  expect(dispatch).toContain("row={row}");
  expect(dispatch).toContain("workshopId={workshopId}");
});

test("the backend has learnt a record-backed MULTI_ENUM, and still keeps what the old box wrote", () => {
  const coerce = readRepo("backend", "app", "services", "stage_schema.py");
  expect(
    coerce,
    "coerce_value no longer exempts a record-backed MULTI_ENUM from the enum allow-list — every id would be refused on save"
  ).toContain("if t is FieldType.MULTI_ENUM and not spec.ref_model:");
  expect(coerce, "the enum allow-list has gone entirely, not just been narrowed").toContain(
    "allowed = ENUMS.get(spec.enum, {})"
  );

  const report = readRepo("backend", "app", "services", "report_builder.py");
  expect(
    report,
    "ReportBuilder._value no longer resolves a record-backed MULTI_ENUM's tokens — the report is printing cuids"
  ).toContain("if spec.type is FieldType.MULTI_ENUM and spec.ref_model:");

  expect(report, "a promoted field's free text is no longer printed verbatim by the report").toContain(
    "elif not _looks_like_an_id(text):"
  );
});

test("the registry declares two record-backed MULTI_ENUM fields, and neither can brick a stage", () => {
  const registry = readRepo("backend", "app", "services", "stage_definitions.py");

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

  const brickable = multiEnums.filter((row) => row.field.tier === "BASIC" && row.field.required);
  expect(brickable.map((row) => `${row.stage.number}:${row.entity.key}.${row.field.key}`)).toEqual([
    "10:designBrief.targetCategories"
  ]);
});

test("free text stored before the promotion is drawn as the words it is, not as a broken link", () => {
  const rows = referenceMultiOptions({
    payload: answer({ options: [option("cmsik2jg8000eh8xc1lcy661a", "Pit loom", "Bargarh")] }),
    values: ["cmsik2jg8000eh8xc1lcy661a", "pit loom", "SK-01", "cmsjb6qaq01ar4otfh1p0hm1a"]
  });

  expect(rows.map((row) => row.label)).toEqual([
    "Pit loom",
    "pit loom",
    "SK-01",
    "Linked record cmsjb6qa"
  ]);

  expect(rows[1].hint).toBe("typed on this field, not a linked record");
  expect(rows[3].hint).toContain("not in the current list");
});

test("every held value survives the round trip, whichever shape it is", () => {
  const values = ["pit loom", "cmsik2jg8000eh8xc1lcy661a", "बुनाई"];
  const rows = referenceMultiOptions({ payload: answer(), values });
  expect(rows.map((row) => row.value)).toEqual(values);
});
