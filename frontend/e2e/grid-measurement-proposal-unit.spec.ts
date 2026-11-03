import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  EMPTY_GRID_STATE,
  GRID_ANALYZING_STATUS,
  GRID_DISCARDED_STATUS,
  GRID_FAILED_STATUS,
  GRID_GROUP_HINTS,
  GRID_SECTION_HINT,
  GRID_UNREADABLE_STATUS,
  formatGridInches,
  gridFailureStatus,
  gridReduce,
  type GridEvent,
  type GridState,
  type GridWrite
} from "@/components/media/gridProposal";
import { ApiError } from "@/lib/api";
import { LocalRefusalError } from "@/lib/failureTriage";
import type { MeasurementAnalysisResponse } from "@/lib/media";

const ROOT = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(ROOT, ...parts), "utf8");
const ANDROID_MAIN = join(
  ROOT,
  "..",
  "android",
  "app",
  "src",
  "main",
  "java",
  "com",
  "designprototype",
  "workshop",
  "MainActivity.kt"
);

function answered(analysis: MeasurementAnalysisResponse["analysis"]): MeasurementAnalysisResponse {
  return {
    available: true,
    status: "COMPLETED",
    analysis,
    method: "VISION_MODEL",
    provider: "gemini",
    modelId: "gemini-2.5-flash-lite",
    selfReportedConfidence: 0.8,
    confidenceIsCalibrated: false,
    requiresAcceptance: true,
    methodMarker: {
      method: "VISION_MODEL",
      provider: "gemini",
      modelId: "gemini-2.5-flash-lite",
      selfReportedConfidence: 0.8
    }
  };
}

function run(events: GridEvent[], from: GridState = EMPTY_GRID_STATE) {
  let state = from;
  const writes: GridWrite[] = [];
  for (const event of events) {
    const step = gridReduce(state, event);
    state = step.state;
    if (step.write) writes.push(step.write);
  }
  return { state, writes };
}

test("a reading does not enter form state until it is accepted", () => {
  const offered = run([
    { type: "CAPTURE", group: "lengthBreadth" },
    { type: "ANALYSIS", group: "lengthBreadth", response: answered({ lengthInches: 3.5, breadthInches: 2 }) }
  ]);

  expect(offered.writes).toEqual([]);
  const proposal = offered.state.proposals.lengthBreadth;
  expect(proposal, "the reading must be held as an offer, not dropped").toBeTruthy();
  expect(proposal?.readings.map((reading) => reading.label)).toEqual(['L 3.50"', 'B 2.00"']);
  expect(offered.state.status.lengthBreadth).toBe('Read L 3.50" · B 2.00" — check it against the object');

  const accepted = run([{ type: "ACCEPT", group: "lengthBreadth" }], offered.state);
  expect(accepted.writes).toHaveLength(1);
  expect(accepted.writes[0].readings.map((reading) => [reading.dimension, reading.value])).toEqual([
    ["length", "3.50"],
    ["breadth", "2.00"]
  ]);
  expect(accepted.state.status.lengthBreadth).toBe('Filled L 3.50" · B 2.00" — still editable');
});

test("the accepted value carries the method that produced it, so the human stamp stops lying", () => {
  const { writes } = run([
    { type: "CAPTURE", group: "height" },
    { type: "ANALYSIS", group: "height", response: answered({ valueInches: 4 }) },
    { type: "ACCEPT", group: "height" }
  ]);
  expect(writes[0].marker).toEqual({
    method: "VISION_MODEL",
    provider: "gemini",
    modelId: "gemini-2.5-flash-lite",
    selfReportedConfidence: 0.8
  });
  expect(writes[0].readings[0].label).toBe("4.00 in");
});

test("an absent marker stays absent — it is never defaulted to TYPED", () => {
  const legacy: MeasurementAnalysisResponse = { available: true, status: "COMPLETED", analysis: { valueInches: 4 } };
  const { writes } = run([
    { type: "ANALYSIS", group: "height", response: legacy },
    { type: "ACCEPT", group: "height" }
  ]);
  expect(writes[0].marker).toBeNull();
});

test("every way an offer can end without acceptance ends with nothing written", () => {
  const offered = run([
    { type: "CAPTURE", group: "lengthBreadth" },
    { type: "ANALYSIS", group: "lengthBreadth", response: answered({ lengthInches: 3.5, breadthInches: 2 }) }
  ]).state;

  const discarded = run([{ type: "DISCARD", group: "lengthBreadth" }], offered);
  expect(discarded.writes).toEqual([]);
  expect(discarded.state.proposals.lengthBreadth).toBeUndefined();
  expect(discarded.state.status.lengthBreadth).toBe(GRID_DISCARDED_STATUS);

  const unchecked = run([{ type: "DISCARD", group: "lengthBreadth", clearStatus: true }], offered);
  expect(unchecked.writes).toEqual([]);
  expect(unchecked.state.status.lengthBreadth).toBeUndefined();

  const recaptured = run([{ type: "CAPTURE", group: "lengthBreadth" }], offered);
  expect(recaptured.writes).toEqual([]);
  expect(recaptured.state.proposals.lengthBreadth).toBeUndefined();
  expect(recaptured.state.status.lengthBreadth).toBe(GRID_ANALYZING_STATUS);

  const failed = run([{ type: "FAILURE", group: "lengthBreadth", error: new Error("boom") }], offered);
  expect(failed.writes).toEqual([]);
  expect(failed.state.proposals.lengthBreadth).toBeUndefined();
});

test("the offer is spent when it is taken, so the button cannot write twice", () => {
  const { writes, state } = run([
    { type: "ANALYSIS", group: "height", response: answered({ valueInches: 4 }) },
    { type: "ACCEPT", group: "height" },
    { type: "ACCEPT", group: "height" }
  ]);
  expect(writes).toHaveLength(1);
  expect(state.proposals.height).toBeUndefined();
});

test("an accept with nothing on offer writes nothing at all", () => {
  const { writes, state } = run([{ type: "ACCEPT", group: "lengthBreadth" }]);
  expect(writes).toEqual([]);
  expect(state).toEqual(EMPTY_GRID_STATE);
});

test("half a reading is still an offer, and no reading at all is not an error", () => {
  const half = run([
    { type: "ANALYSIS", group: "lengthBreadth", response: answered({ lengthInches: 3.5, breadthInches: null }) },
    { type: "ACCEPT", group: "lengthBreadth" }
  ]);
  expect(half.writes[0].readings.map((reading) => reading.dimension)).toEqual(["length"]);

  const none = run([{ type: "ANALYSIS", group: "lengthBreadth", response: answered({}) }]);
  expect(none.writes).toEqual([]);
  expect(none.state.proposals.lengthBreadth).toBeUndefined();
  expect(none.state.status.lengthBreadth).toBe(GRID_UNREADABLE_STATUS);
});

test("an old server's 200 with available:false keeps the sentence that names the missing key", () => {
  const message = "Grid measurement is unavailable because no Gemini API key is configured.";
  const { state, writes } = run([
    { type: "ANALYSIS", group: "height", response: { available: false, status: "UNAVAILABLE", analysis: null, message } }
  ]);
  expect(writes).toEqual([]);
  expect(state.status.height).toBe(message);
});

test("the value written matches the two decimals the button prints and the box accepts", () => {
  expect(formatGridInches(3.4967)).toBe("3.50");
  expect(formatGridInches("2")).toBe("2.00");

  expect(formatGridInches(0.004)).toBeNull();
  expect(formatGridInches(0)).toBeNull();
  expect(formatGridInches(-1)).toBeNull();
  expect(formatGridInches(null)).toBeNull();
  expect(formatGridInches("about four inches")).toBeNull();
});

test("a failed read says WHICH failure it was, because they need different things done about them", () => {
  const answered = (status: number, detail: string) => new ApiError(status, detail, { detail });

  expect(gridFailureStatus(new TypeError("Failed to fetch"))).toContain("You are offline");

  const unconfigured =
    "Grid measurement is unavailable because no Gemini API key is configured. Measure the object and " +
    "type the value in, or ask whoever administers the server to add GEMINI_API_KEY in the Settings hub.";
  expect(gridFailureStatus(answered(503, unconfigured))).toBe(unconfigured);
  expect(gridFailureStatus(answered(413, "The image is larger than the 8 MB limit."))).toContain("8 MB");

  expect(gridFailureStatus(new LocalRefusalError("That file is empty."))).toContain("empty");
  expect(gridFailureStatus(new LocalRefusalError("That file is empty."))).not.toContain("You are offline");

  const bodyless = new ApiError(503, "The server refused the request (HTTP 503).", null);
  expect(gridFailureStatus(bodyless)).not.toContain("HTTP 503");
  expect(gridFailureStatus(bodyless)).not.toBe(GRID_FAILED_STATUS);
  expect(gridFailureStatus(bodyless)).toContain("an administrator can turn it on");

  const said = [
    gridFailureStatus(new TypeError("Failed to fetch")),
    gridFailureStatus(answered(503, unconfigured)),
    gridFailureStatus(answered(413, "The image is larger than the 8 MB limit.")),
    gridFailureStatus(new LocalRefusalError("That file is empty.")),
    gridFailureStatus(bodyless)
  ];
  expect(new Set(said).size).toBe(5);
});

test("the copy says the control needs a connection, in Android's words", () => {
  const android = readFileSync(ANDROID_MAIN, "utf8");
  for (const clause of [
    "Needs a connection",
    "you confirm before anything is filled"
  ]) {
    expect(android, `${clause} — has the handset's grid hint been reworded?`).toContain(clause);
    expect(GRID_SECTION_HINT).toContain(clause);
  }

  expect(GRID_SECTION_HINT).not.toContain("auto-fill");

  for (const hint of Object.values(GRID_GROUP_HINTS)) {
    expect(android, `${hint} — the two clients' grid hints have drifted`).toContain(hint);
    expect(hint).toContain("offers");
  }
});

test("the component has exactly one door into form state, and it is the accept button", () => {
  const source = read("components", "media", "GridMeasurement.tsx")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "");

  expect(source.match(/onLengthBreadth\(/g) ?? []).toHaveLength(1);
  expect(source.match(/onHeight\(/g) ?? []).toHaveLength(1);

  const from = source.indexOf("function accept(");
  const to = source.indexOf("function renderGroup(");
  expect(from, "accept() not found — has the component been restructured?").toBeGreaterThan(-1);
  expect(to).toBeGreaterThan(from);
  const acceptBody = source.slice(from, to);
  expect(acceptBody).toContain("onLengthBreadth(");
  expect(acceptBody).toContain("onHeight(");

  const pickFrom = source.indexOf("async function pick(");
  const pickTo = source.indexOf("function accept(");
  const pickBody = source.slice(pickFrom, pickTo);
  expect(pickBody).not.toContain("onLengthBreadth(");
  expect(pickBody).not.toContain("onHeight(");
});
