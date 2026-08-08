/**
 * `lib/photoIntake.ts` — the proposal ranking, tested by VALUE.
 *
 * This is a unit test wearing a Playwright spec's clothes. It opens no page and needs no server: the
 * module under test is pure by design (dates and strings in, ranked proposals out) and Playwright is
 * simply the only test runner this frontend has — `e2e/zzzz-bd-probe.spec.ts` established that a
 * spec can import `@/lib/*` directly and run in Node. Keeping it here rather than inventing a second
 * runner means one command runs it.
 *
 * WHAT IS PINNED, AND WHY BY VALUE RATHER THAN BY PROPERTY. Every assertion below fixes an exact
 * order or an exact refusal, not an invariant like "a nearer anchor sorts first". A property test
 * would pass for three mutually incompatible rankings, and the ranking is the product here: the
 * point of the feature is that a designer can trust the first row without reading the other two.
 * The Kotlin port, when it is written, has to reproduce these orders exactly, which is the same
 * contract `test_market_analysis.py` holds `marketAnalysis.ts` to.
 *
 * The five cases the brief names are all here — inside a window, between two anchors, before them
 * all, no date at all, and a day boundary — plus the timezone pair, which is the one that would
 * otherwise put every evening photograph on the wrong day.
 */

import { test, expect } from "@playwright/test";

import {
  DEFAULT_TIMEZONE,
  OUTSIDE_GRACE_DAYS,
  buildAnchors,
  intakePhotos,
  intakeSummary,
  parseExifStamp,
  photoTargets,
  rankProposals,
  resolveStamp,
  type WorkshopAnchor
} from "@/lib/photoIntake";
import type { DwRegistry, DwStageData } from "@/lib/designWorkshops";

/* ────────────────────────────────────────────────────────────────────────────
 * Fixtures — a workshop that ran 12–26 Feb 2026
 * ──────────────────────────────────────────────────────────────────────────── */

function anchor(over: Partial<WorkshopAnchor> & Pick<WorkshopAnchor, "start" | "end">): WorkshopAnchor {
  return {
    stageKey: "STAGE",
    stageNumber: 1,
    stageTitle: "Stage",
    entityKey: "entity",
    entityTitle: "Entity",
    rowKey: null,
    rowLabel: null,
    fieldLabel: "Date",
    kind: over.start === over.end ? "DAY" : "SPAN",
    ...over
  };
}

/** Stage 1's window: the whole workshop, and therefore the widest and weakest anchor there is. */
const WORKSHOP_WINDOW = anchor({
  stageKey: "WORKSHOP_IDENTIFICATION",
  stageNumber: 1,
  stageTitle: "Workshop Identification",
  entityKey: "workshop",
  entityTitle: "Workshop",
  fieldLabel: "Start date – End date",
  kind: "SPAN",
  start: "2026-02-12",
  end: "2026-02-26"
});

/** Stage 13's per-day prototype logs — the narrow, specific anchors. */
const LOG_14 = anchor({
  stageKey: "PROTOTYPE_DEVELOPMENT",
  stageNumber: 13,
  stageTitle: "Prototype Development",
  entityKey: "prototypeStageLog",
  entityTitle: "Stage logs",
  rowKey: "row-14",
  rowLabel: "Warping the loom",
  fieldLabel: "Date",
  start: "2026-02-14",
  end: "2026-02-14"
});

const LOG_18 = anchor({
  stageKey: "PROTOTYPE_DEVELOPMENT",
  stageNumber: 13,
  stageTitle: "Prototype Development",
  entityKey: "prototypeStageLog",
  entityTitle: "Stage logs",
  rowKey: "row-18",
  rowLabel: "Dyeing the weft",
  fieldLabel: "Date",
  start: "2026-02-18",
  end: "2026-02-18"
});

/** Stage 19's closing day. */
const CLOSING = anchor({
  stageKey: "INSPECTION_CLOSING",
  stageNumber: 19,
  stageTitle: "Inspection & Closing",
  entityKey: "closing",
  entityTitle: "Closing",
  fieldLabel: "Closing date",
  start: "2026-02-26",
  end: "2026-02-26"
});

const ANCHORS = [WORKSHOP_WINDOW, LOG_14, LOG_18, CLOSING];

const photo = (fileName: string, takenAt: string | null, takenAtOffset?: string | null) => ({
  fileName,
  takenAt,
  takenAtOffset
});

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Inside a stage window
 * ──────────────────────────────────────────────────────────────────────────── */

test("a photograph on a logged day proposes that log ABOVE the workshop window", () => {
  const [row] = intakePhotos([photo("DSC_0041.JPG", "2026:02:14 10:22:33")], ANCHORS);

  expect(row.refusal).toBeNull();
  expect(row.stamp?.date).toBe("2026-02-14");

  // Narrower evidence wins. Both anchors genuinely contain the 14th; only one of them is a reason.
  expect(row.proposals[0].anchor.stageNumber).toBe(13);
  expect(row.proposals[0].anchor.rowKey).toBe("row-14");
  expect(row.proposals[0].basis).toBe("ON_DAY");
  expect(row.proposals[0].daysAway).toBe(0);

  expect(row.proposals[1].anchor.stageNumber).toBe(1);
  expect(row.proposals[1].basis).toBe("IN_SPAN");
});

test("the evidence names the reading AND the row it matched, so a wrong proposal is obvious", () => {
  const [row] = intakePhotos([photo("DSC_0041.JPG", "2026:02:14 10:22:33")], ANCHORS);

  expect(row.proposals[0].evidence).toBe(
    "Taken 14 Feb 2026, 10:22 — stage 13's Stage logs row “Warping the loom”, date 14 Feb 2026."
  );
  expect(row.proposals[1].evidence).toBe(
    "Taken 14 Feb 2026, 10:22 — inside stage 1's Workshop, start date – end date 12 Feb 2026 – 26 Feb 2026."
  );
});

test("a photograph inside the window but on no logged day still proposes the window", () => {
  // The 21st: inside 12–26 Feb, but there is no log row for it.
  const [row] = intakePhotos([photo("DSC_0090.JPG", "2026:02:21 15:05:00")], ANCHORS);

  expect(row.refusal).toBeNull();
  expect(row.proposals[0].anchor.stageNumber).toBe(1);
  expect(row.proposals[0].basis).toBe("IN_SPAN");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. Between two anchors
 * ──────────────────────────────────────────────────────────────────────────── */

test("a photograph between two logged days offers the nearer one first, and says it covers nothing", () => {
  // The 15th: one day after LOG_14, three days before LOG_18. Both are outside; the window contains it.
  const [row] = intakePhotos([photo("DSC_0055.JPG", "2026:02:15 09:00:00")], ANCHORS);

  expect(row.refusal).toBeNull();
  // The containing window outranks both non-containing logs — containment is the first sort key,
  // and a claim that is actually true beats a near miss.
  expect(row.proposals[0].anchor.stageNumber).toBe(1);
  expect(row.proposals[0].basis).toBe("IN_SPAN");

  // Then the nearer log, then the farther one — the ambiguity stays visible rather than being hidden
  // behind a single confident answer.
  expect(row.proposals[1].anchor.rowKey).toBe("row-14");
  expect(row.proposals[1].daysAway).toBe(1);
  expect(row.proposals[1].basis).toBe("NEAREST");
  expect(row.proposals[1].evidence).toContain("nothing recorded covers that date");
  expect(row.proposals[1].evidence).toContain("1 day after");

  expect(row.proposals[2].anchor.rowKey).toBe("row-18");
  expect(row.proposals[2].daysAway).toBe(3);
});

test("between two logged days with no covering window, the nearer log leads", () => {
  const [row] = intakePhotos([photo("DSC_0055.JPG", "2026:02:15 09:00:00")], [LOG_14, LOG_18]);

  expect(row.proposals[0].anchor.rowKey).toBe("row-14");
  expect(row.proposals[0].daysAway).toBe(1);
  expect(row.proposals[1].anchor.rowKey).toBe("row-18");
  expect(row.proposals[1].daysAway).toBe(3);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. Before every anchor
 * ──────────────────────────────────────────────────────────────────────────── */

test("a photograph just before the workshop is proposed, and the evidence says nothing covers it", () => {
  // 11 Feb — one day before the window opens, inside the grace. Travelling in the night before is
  // a real workshop photograph and must not be refused.
  const [row] = intakePhotos([photo("DSC_0001.JPG", "2026:02:11 18:30:00")], ANCHORS);

  expect(row.refusal).toBeNull();
  expect(row.proposals[0].anchor.stageNumber).toBe(1);
  expect(row.proposals[0].basis).toBe("NEAREST");
  expect(row.proposals[0].daysAway).toBe(1);
  expect(row.proposals[0].evidence).toContain("1 day before");
});

test("a photograph far before every anchor is REFUSED rather than guessed", () => {
  // 1 Jan — 42 days before the window. Far likelier a camera clock that was never set than an
  // undated workshop day, and a confident stage from a wrong clock is the failure to avoid.
  const [row] = intakePhotos([photo("IMG_0002.JPG", "2026:01:01 12:00:00")], ANCHORS);

  expect(row.proposals).toEqual([]);
  expect(row.refusal).toContain("42 days before the first date recorded");
  expect(row.refusal).toContain("12 Feb 2026");
  expect(row.refusal).toContain("choose the stage yourself");
  // The reading is still on screen — that is what diagnoses the camera.
  expect(row.stamp?.date).toBe("2026-01-01");
});

test("the grace boundary is exact: OUTSIDE_GRACE_DAYS proposes, one more refuses", () => {
  const inside = intakePhotos([photo("a.jpg", "2026:02:10 12:00:00")], ANCHORS)[0];
  const outside = intakePhotos([photo("b.jpg", "2026:02:09 12:00:00")], ANCHORS)[0];

  expect(OUTSIDE_GRACE_DAYS).toBe(2);
  expect(inside.proposals[0]?.daysAway).toBe(2);
  expect(outside.proposals).toEqual([]);
  expect(outside.refusal).toContain("3 days before");
});

test("a photograph after the last recorded date refuses the same way", () => {
  const [row] = intakePhotos([photo("DSC_0300.JPG", "2026:03:20 09:00:00")], ANCHORS);

  expect(row.proposals).toEqual([]);
  expect(row.refusal).toContain("after the last date recorded");
  expect(row.refusal).toContain("26 Feb 2026");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. No date at all — never dropped, never guessed
 * ──────────────────────────────────────────────────────────────────────────── */

test("a photograph with no EXIF date is offered for manual assignment, not dropped or guessed", () => {
  const rows = intakePhotos([photo("Screenshot 2026-02-14.png", null)], ANCHORS);

  expect(rows).toHaveLength(1);
  expect(rows[0].fileName).toBe("Screenshot 2026-02-14.png");
  expect(rows[0].stamp).toBeNull();
  expect(rows[0].proposals).toEqual([]);
  expect(rows[0].refusal).toContain("No capture date in this file");
  // The filename says "2026-02-14" and the module must NOT read it. A name is not evidence.
  expect(rows[0].refusal).toContain("choose the stage yourself");
});

test("the unset-clock sentinel and a corrupt date are refusals, not dates", () => {
  // A camera with a flat clock battery writes exactly this, and it is not a date.
  expect(parseExifStamp("0000:00:00 00:00:00")).toBeNull();
  // 30 February cannot be rolled forward into March — that would file the photograph on a real day
  // the camera never saw.
  expect(parseExifStamp("2026:02:30 10:00:00")).toBeNull();
  expect(parseExifStamp("")).toBeNull();
  expect(parseExifStamp(null)).toBeNull();
  expect(parseExifStamp("2026:02:14 10:22:33")).toEqual({ y: 2026, mo: 2, d: 14, hh: 10, mm: 22, ss: 33 });
  // 29 Feb 2024 is real; 29 Feb 2026 is not.
  expect(parseExifStamp("2024:02:29 10:00:00")).not.toBeNull();
  expect(parseExifStamp("2026:02:29 10:00:00")).toBeNull();

  const [row] = intakePhotos([photo("IMG_9999.JPG", "0000:00:00 00:00:00")], ANCHORS);
  expect(row.stamp).toBeNull();
  expect(row.proposals).toEqual([]);
  expect(row.refusal).toContain("No capture date");
});

test("nothing is dropped: every file picked comes back as a row, in the order picked", () => {
  const rows = intakePhotos(
    [
      photo("c.jpg", "2026:02:18 08:00:00"),
      photo("a.png", null),
      photo("b.jpg", "2026:01:01 12:00:00"),
      photo("d.jpg", "2026:02:14 10:00:00")
    ],
    ANCHORS
  );

  expect(rows.map((row) => row.fileName)).toEqual(["c.jpg", "a.png", "b.jpg", "d.jpg"]);
  expect(intakeSummary(rows)).toEqual({ total: 4, proposed: 2, manual: 2 });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. Day boundaries — the case the whole timezone argument is about
 * ──────────────────────────────────────────────────────────────────────────── */

test("23:59 and 00:01 land on their own calendar days, whatever the machine's zone", () => {
  const late = intakePhotos([photo("late.jpg", "2026:02:14 23:59:00")], ANCHORS)[0];
  const early = intakePhotos([photo("early.jpg", "2026:02:15 00:01:00")], ANCHORS)[0];

  // The late one is ON the 14th's log; the early one is not, and falls back to the window.
  expect(late.stamp?.date).toBe("2026-02-14");
  expect(late.proposals[0].anchor.rowKey).toBe("row-14");
  expect(late.proposals[0].basis).toBe("ON_DAY");

  expect(early.stamp?.date).toBe("2026-02-15");
  expect(early.proposals[0].anchor.stageNumber).toBe(1);
  expect(early.proposals[0].basis).toBe("IN_SPAN");
});

test("a naive evening clock is NOT shifted — the wall clock is taken as workshop-local", () => {
  // The off-by-5.5-hours bug in its most common form: 19:40 read as UTC and rendered in
  // Asia/Kolkata would become 01:10 on the 15th and file this photograph on the wrong day.
  const stamp = resolveStamp(photo("evening.jpg", "2026:02:14 19:40:12"), DEFAULT_TIMEZONE);

  expect(stamp?.date).toBe("2026-02-14");
  expect(stamp?.minutes).toBe(19 * 60 + 40);
  expect(stamp?.shiftedFrom).toBeNull();
});

test("a camera left on UTC that DECLARES its offset is shifted into workshop-local time", () => {
  // Same wall clock, but the camera says it was +00:00. The real instant is 2026-02-15 01:10 IST,
  // so this belongs on the 15th — and the evidence has to say the clock was moved.
  const stamp = resolveStamp(photo("utc.jpg", "2026:02:14 19:40:12", "+00:00"), DEFAULT_TIMEZONE);

  expect(stamp?.date).toBe("2026-02-15");
  expect(stamp?.minutes).toBe(60 + 10);
  expect(stamp?.shiftedFrom).toBe("+00:00");

  const [row] = intakePhotos([photo("utc.jpg", "2026:02:14 19:40:12", "+00:00")], ANCHORS);
  expect(row.proposals[0].evidence).toContain("The camera recorded 2026-02-14 19:40 at +00:00");
});

test("a camera already on the workshop's offset is left alone", () => {
  const stamp = resolveStamp(photo("ist.jpg", "2026:02:14 19:40:12", "+05:30"), DEFAULT_TIMEZONE);

  expect(stamp?.date).toBe("2026-02-14");
  expect(stamp?.shiftedFrom).toBeNull();
});

test("an explicit timezone other than the default is honoured", () => {
  // Same file, a workshop recorded in London: 19:40+00:00 is 19:40 there, so it stays on the 14th.
  const stamp = resolveStamp(photo("utc.jpg", "2026:02:14 19:40:12", "+00:00"), "Europe/London");

  expect(stamp?.date).toBe("2026-02-14");
  expect(stamp?.shiftedFrom).toBeNull();
});

/* ────────────────────────────────────────────────────────────────────────────
 * Anchors are read from the registry, not from a list of stage numbers
 * ──────────────────────────────────────────────────────────────────────────── */

const REGISTRY: DwRegistry = {
  version: "test",
  enums: {},
  stages: [
    {
      number: 1,
      key: "WORKSHOP_IDENTIFICATION",
      title: "Workshop Identification",
      purpose: "",
      notes: "",
      optionalStage: false,
      entities: [
        {
          key: "workshop",
          name: "DwWorkshop",
          cardinality: "SINGLETON",
          title: "Workshop",
          description: "",
          parent: "",
          labelField: "",
          fields: [
            { key: "startDate", label: "Start date", type: "DATE", tier: "BASIC", required: true },
            { key: "endDate", label: "End date", type: "DATE", tier: "BASIC", required: true },
            { key: "coverPhoto", label: "Cover photograph", type: "IMAGE", tier: "STANDARD", required: false }
          ]
        }
      ]
    },
    {
      number: 13,
      key: "PROTOTYPE_DEVELOPMENT",
      title: "Prototype Development",
      purpose: "",
      notes: "",
      optionalStage: false,
      entities: [
        {
          key: "prototypeStageLog",
          name: "DwPrototypeStageLog",
          cardinality: "COLLECTION",
          title: "Stage logs",
          description: "",
          parent: "",
          labelField: "activity",
          fields: [
            { key: "logDate", label: "Date", type: "DATE", tier: "BASIC", required: true },
            { key: "activity", label: "Activity", type: "TEXT", tier: "BASIC", required: true },
            { key: "logPhotos", label: "Photographs", type: "IMAGE_LIST", tier: "BASIC", required: false }
          ]
        }
      ]
    }
  ]
};

const STAGE_DATA: Record<string, DwStageData> = {
  WORKSHOP_IDENTIFICATION: {
    singleton: { startDate: "2026-02-12", endDate: "2026-02-26" },
    collections: {}
  },
  PROTOTYPE_DEVELOPMENT: {
    singleton: {},
    collections: {
      prototypeStageLog: [
        // Carries BOTH keys, so the preference between them is actually exercised: a row that has
        // been round-tripped through the server has an `_entryId` as well, and picking the wrong one
        // is invisible until a replayed save re-creates the row under a fresh id.
        { _clientKey: "row-a", _entryId: "server-a", logDate: "2026-02-14", activity: "Warping the loom" },
        { _entryId: "row-b", logDate: "2026-02-18", activity: "Dyeing the weft" },
        { _clientKey: "row-c", logDate: "", activity: "Undated note" }
      ]
    }
  }
};

test("buildAnchors reads the registry: a window from the paired dates, a day from each dated row", () => {
  const built = buildAnchors(REGISTRY, STAGE_DATA);

  // startDate + endDate collapse into ONE span, not two loose days.
  const spans = built.filter((item) => item.kind === "SPAN");
  expect(spans).toHaveLength(1);
  expect(spans[0]).toMatchObject({
    stageNumber: 1,
    entityKey: "workshop",
    start: "2026-02-12",
    end: "2026-02-26",
    fieldLabel: "Start date – End date",
    rowKey: null
  });

  const days = built.filter((item) => item.kind === "DAY");
  expect(days.map((item) => item.start)).toEqual(["2026-02-14", "2026-02-18"]);
  // `_clientKey` is preferred over `_entryId` — it is the key that survives a replayed save.
  expect(days[0].rowKey).toBe("row-a");
  expect(days[0].rowLabel).toBe("Warping the loom");
  expect(days[1].rowKey).toBe("row-b");
  // The undated row contributes nothing rather than an anchor on some default date.
  expect(built).toHaveLength(3);
});

test("a stage the workshop has no data for contributes no anchors", () => {
  expect(buildAnchors(REGISTRY, {})).toEqual([]);
  expect(intakePhotos([photo("x.jpg", "2026:02:14 10:00:00")], [])[0].refusal).toContain(
    "no dates recorded yet"
  );
});

test("the end-to-end path from registry to proposal picks the log row over the window", () => {
  const built = buildAnchors(REGISTRY, STAGE_DATA);
  const [row] = intakePhotos([photo("DSC_0041.JPG", "2026:02:14 10:22:33")], built);

  expect(row.proposals[0].anchor.stageKey).toBe("PROTOTYPE_DEVELOPMENT");
  expect(row.proposals[0].anchor.entityKey).toBe("prototypeStageLog");
  expect(row.proposals[0].anchor.rowKey).toBe("row-a");
});

test("photoTargets lists only the image fields of the entity a proposal points at", () => {
  expect(photoTargets(REGISTRY, "PROTOTYPE_DEVELOPMENT", "prototypeStageLog")).toEqual([
    { fieldKey: "logPhotos", fieldLabel: "Photographs", multiple: true }
  ]);
  expect(photoTargets(REGISTRY, "WORKSHOP_IDENTIFICATION", "workshop")).toEqual([
    { fieldKey: "coverPhoto", fieldLabel: "Cover photograph", multiple: false }
  ]);
  expect(photoTargets(REGISTRY, "NOPE", "nope")).toEqual([]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Ordering is total and stable
 * ──────────────────────────────────────────────────────────────────────────── */

test("two anchors that match equally well keep a fixed order however the input is shuffled", () => {
  const stamp = resolveStamp(photo("x.jpg", "2026:02:14 10:00:00"), DEFAULT_TIMEZONE)!;
  const twin = anchor({ ...LOG_14, rowKey: "row-14b", rowLabel: "Second entry", start: "2026-02-14", end: "2026-02-14" });

  const forwards = rankProposals(stamp, [LOG_14, twin, WORKSHOP_WINDOW]).map((item) => item.anchor.rowKey);
  const backwards = rankProposals(stamp, [WORKSHOP_WINDOW, twin, LOG_14]).map((item) => item.anchor.rowKey);

  expect(forwards).toEqual(backwards);
  expect(forwards.slice(0, 2)).toEqual(["row-14", "row-14b"]);
});

test("no more than MAX_PROPOSALS come back for one photograph", () => {
  const many = Array.from({ length: 9 }, (_, index) =>
    anchor({ ...LOG_14, rowKey: `r${index}`, start: "2026-02-14", end: "2026-02-14" })
  );
  const stamp = resolveStamp(photo("x.jpg", "2026:02:14 10:00:00"), DEFAULT_TIMEZONE)!;

  expect(rankProposals(stamp, many)).toHaveLength(3);
});

test("a window typed backwards is still read as a window, not thrown away", () => {
  // Nothing about fieldwork may be blocked by a typo in a date box.
  const reversed: Record<string, DwStageData> = {
    ...STAGE_DATA,
    WORKSHOP_IDENTIFICATION: { singleton: { startDate: "2026-02-26", endDate: "2026-02-12" }, collections: {} }
  };
  const span = buildAnchors(REGISTRY, reversed).find((item) => item.kind === "SPAN");

  expect(span?.start).toBe("2026-02-12");
  expect(span?.end).toBe("2026-02-26");
});
