import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { applyAutoNumbers, dateOfDay, formatAutoNumber, nextAutoNumber } from "@/lib/autoNumber";
import { formFields, type DwEntity, type DwField, type DwRow } from "@/lib/designWorkshops";
import { formatExifTime, positionFromExif } from "@/lib/photoPosition";
import { lastStageName, logTodayRow } from "@/lib/prototypeLog";
import { fillValues, stillApplies, suggestedRows, usableCheck, type DwAssistSuggestion } from "@/lib/stageAssist";
import { attendedDays, isLegacyAttendance, isWoman, rosterValues, withAttendance, workshopDays } from "@/lib/stageComputed";

/**
 * The stage capture work of 2026-10-17 (A14, A15, A16, A21, B10, B11, C6, C7, C8, C10, C12), held
 * to the server's behaviour: the numbering port against `stage_schema.next_auto_number`, the counts
 * against `stage_computed`, the suggestion application against `stage_assist`'s payload, and the
 * wiring by reading the stage page's source.
 */

const field = (over: Partial<DwField>): DwField =>
  ({ key: "k", label: "K", type: "TEXT", tier: "BASIC", required: false, ...over }) as DwField;

const sketchNo = field({ key: "sketchNo", autoNumber: "S" });
const versionNo = field({ key: "versionNo", type: "INT", autoNumber: "#", autoNumberWithin: "prototypeRef" });

test.describe("C6 numbering", () => {
  test("a code follows the highest one used, two digits at least", () => {
    expect(formatAutoNumber("S", 3)).toBe("S-03");
    expect(nextAutoNumber(sketchNo, [])).toBe("S-01");
    expect(nextAutoNumber(sketchNo, [{ sketchNo: "S-01" }, { sketchNo: "s-07" }])).toBe("S-08");
  });

  test("a code typed another way never collides with the next", () => {
    expect(nextAutoNumber(sketchNo, [{ sketchNo: "Sk1" }, { sketchNo: "Runner" }])).toBe("S-03");
  });

  test("iterations number within their prototype", () => {
    const rows = [
      { prototypeRef: "p1", versionNo: 1 },
      { prototypeRef: "p1", versionNo: 2 },
      { prototypeRef: "p2", versionNo: 1 }
    ];
    expect(nextAutoNumber(versionNo, rows, "p1")).toBe(3);
    expect(nextAutoNumber(versionNo, rows, "p3")).toBe(1);
  });

  test("only new rows are numbered, in order, and existing numbers are never touched", () => {
    const entity = { key: "sketch", fields: [sketchNo] } as unknown as DwEntity;
    const old: DwRow[] = [{ _clientKey: "a", sketchNo: "S-01" }, { _clientKey: "b", sketchNo: "" }];
    const next = applyAutoNumbers(entity, old, [...old, { _clientKey: "c" }, { _clientKey: "d" }]);
    expect(next.map((r) => r.sketchNo)).toEqual(["S-01", "", "S-03", "S-04"]);
  });

  test("an iteration takes its number when its prototype is chosen", () => {
    const entity = { key: "prototypeIteration", fields: [versionNo] } as unknown as DwEntity;
    const before: DwRow[] = [{ _clientKey: "x", prototypeRef: "p1", versionNo: 1 }, { _clientKey: "y" }];
    const after = applyAutoNumbers(entity, before, [before[0], { _clientKey: "y", prototypeRef: "p1" }]);
    expect(after[1].versionNo).toBe(2);
  });

  test("a schedule day is dated from the start date", () => {
    expect(dateOfDay("2026-10-05", 3)).toBe("2026-10-07");
    expect(dateOfDay("2026-12-31", 2)).toBe("2027-01-01");
    expect(dateOfDay(null, 2)).toBeNull();
    const entity = {
      key: "workshopScheduleDay",
      fields: [field({ key: "dayNumber", type: "INT", autoNumber: "#" }), field({ key: "date", type: "DATE", autoDateFromDay: "dayNumber" })]
    } as unknown as DwEntity;
    const [row] = applyAutoNumbers(entity, [], [{ _clientKey: "n" }], { startDate: "2026-10-05" });
    expect(row).toMatchObject({ dayNumber: 1, date: "2026-10-05" });
  });
});

test.describe("C7 attendance and C8 counts", () => {
  const people: DwRow[] = [
    { name: "Asha", gender: "Female" },
    { name: "Ravi", gender: "Male", absentDays: ["2"] },
    { name: "" }
  ];

  test("an untouched roster reads all present; a blank row is nobody", () => {
    expect(rosterValues(people, [1, 2, 3])).toEqual({ ROSTER_COUNT: 2, ROSTER_WOMEN: 1, ROSTER_COMPLETED: 1 });
    expect(attendedDays(people[1], [1, 2, 3])).toBe(2);
    expect(rosterValues([], [1])).toEqual({ ROSTER_COUNT: null, ROSTER_WOMEN: null, ROSTER_COMPLETED: null });
    expect(isWoman("महिला")).toBe(true);
  });

  test("a typed legacy days-attended stands until the row is ticked", () => {
    const legacy: DwRow = { name: "Asha", attendedDays: 3 };
    expect(isLegacyAttendance(legacy)).toBe(true);
    expect(attendedDays(legacy, [1, 2, 3, 4, 5])).toBeNull();
    expect(rosterValues([legacy, { name: "Ravi" }], [1, 2, 3]).ROSTER_COMPLETED).toBeNull();
    expect(attendedDays({ ...legacy, absentDays: [] }, [1, 2, 3, 4, 5])).toBe(5);
    expect(isLegacyAttendance({ name: "Ravi", attendedDays: "" })).toBe(false);
  });

  test("ticking writes the absent day list, sorted", () => {
    expect(withAttendance({ name: "A" }, 3, false).absentDays).toEqual(["3"]);
    expect(withAttendance({ name: "A", absentDays: ["3", "1"] }, 2, false).absentDays).toEqual(["1", "2", "3"]);
    expect(withAttendance({ name: "A", absentDays: ["3"] }, 3, true).absentDays).toEqual([]);
  });

  test("workshop days come from the schedule, else the server's", () => {
    expect(workshopDays([{ dayNumber: 2 }, { dayNumber: 1 }], [1, 2, 3, 4])).toEqual([1, 2]);
    expect(workshopDays([], [1, 2, 3])).toEqual([1, 2, 3]);
  });

  test("the attendance list is drawn by its card, never as a box", () => {
    const entity = {
      key: "participant",
      fields: [field({ key: "name" }), field({ key: "absentDays", type: "TAGS", widget: "ATTENDANCE" })]
    } as unknown as DwEntity;
    expect(formFields(entity).map((f) => f.key)).toEqual(["name"]);
  });
});

test.describe("suggestions", () => {
  const fill: DwAssistSuggestion = {
    id: "reportSettings:template",
    kind: "FILL",
    entityKey: "reportSettings",
    rowKey: null,
    title: "Use the workshop's template",
    detail: "",
    values: { templateId: "PHOTO_CATALOGUE" }
  };

  test("a FILL lands only on blank boxes and goes away once filled", () => {
    expect(fillValues(fill, {})).toEqual({ templateId: "PHOTO_CATALOGUE" });
    expect(fillValues(fill, { templateId: "DCH_STANDARD" })).toEqual({});
    expect(stillApplies(fill, {}, {})).toBe(true);
    expect(stillApplies(fill, { templateId: "DCH_STANDARD" }, {})).toBe(false);
  });

  test("A16's Use ₹X replaces a contradicting subtotal, and is gone once it matches", () => {
    const use: DwAssistSuggestion = { ...fill, entityKey: "prototype", rowKey: "p1", values: { materialCost: "150.50" }, replaces: true };
    const rows = { prototype: [{ _entryId: "p1", materialCost: "100.00" }] };
    expect(stillApplies(use, {}, rows)).toBe(true);
    expect(stillApplies(use, {}, { prototype: [{ _entryId: "p1", materialCost: "150.50" }] })).toBe(false);
    expect(usableCheck({ key: "materialCost", computed: 150.5, verdict: "MISMATCH" })).toBe(true);
    expect(usableCheck({ key: "totalCost", computed: 150.5, verdict: "MISMATCH" })).toBe(false);
    expect(usableCheck({ key: "labourCost", computed: 10, verdict: "AGREES" })).toBe(false);
  });

  test("ADD_ROWS rows each get their own client key", () => {
    const rows = suggestedRows({ ...fill, kind: "ADD_ROWS", rows: [{ dayNumber: 2 }, { dayNumber: 3 }] });
    expect(rows).toHaveLength(2);
    expect(rows[0]._clientKey).not.toBe(rows[1]._clientKey);
    expect(rows[1].dayNumber).toBe(3);
  });
});

test.describe("A15 log today and A21 photo position", () => {
  test("the last stage name is the latest log's for that prototype", () => {
    const proto: DwRow = { _entryId: "p1" };
    const logs: DwRow[] = [
      { prototypeRef: "p1", logDate: "2026-10-10", stageName: "Weaving" },
      { prototypeRef: "p1", logDate: "2026-10-12", stageName: "Dyeing" },
      { prototypeRef: "p2", logDate: "2026-10-13", stageName: "Cutting" }
    ];
    expect(lastStageName(proto, logs)).toBe("Dyeing");
    expect(logTodayRow(proto, "Dyeing", "2026-10-17")).toMatchObject({ prototypeRef: "p1", logDate: "2026-10-17", stageName: "Dyeing" });
  });

  test("a photo with GPS gives its place and time; one without gives nothing", () => {
    expect(positionFromExif({ latitude: 20.5, longitude: 85.8, DateTimeOriginal: "2026:10:12 10:42:05" })).toEqual({
      lat: 20.5,
      lon: 85.8,
      takenAt: "12 Oct 2026, 10:42"
    });
    expect(positionFromExif({ Make: "Phone" })).toBeNull();
    expect(positionFromExif({ latitude: 0, longitude: 0 })).toBeNull();
    expect(formatExifTime("nonsense")).toBeNull();
  });
});

test.describe("wiring", () => {
  const page = readFileSync(
    join(__dirname, "..", "app", "(protected)", "design-workshops", "[id]", "stages", "[stageKey]", "page.tsx"),
    "utf8"
  );

  test("the stage page mounts the capture extras, numbers rows and keeps counts computed", () => {
    expect(page).toContain("<StageCaptureExtras");
    expect(page).toContain("applyAutoNumbers(");
    expect(page).toContain("useComputedCounts(");
    expect(page).toContain("useStageAssist(");
    // C12: an empty venue is asked for after a stage 1 save.
    expect(page).toContain("setVenuePrompt(stageKey === SETUP_STAGE && !singleton.venueLocation)");
  });

  test("a computed count is drawn read-only, not as a box", () => {
    const input = readFileSync(join(__dirname, "..", "components", "designworkshop", "FieldInput.tsx"), "utf8");
    expect(input).toContain("if (field.computedKind) return unlabelled(<StageComputedCount");
  });

  test("the geo box offers a photo's position", () => {
    const geo = readFileSync(join(__dirname, "..", "components", "designworkshop", "StageGeoField.tsx"), "utf8");
    expect(geo).toContain("From a photo");
  });
});
