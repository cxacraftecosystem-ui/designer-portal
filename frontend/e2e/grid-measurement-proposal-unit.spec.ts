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

/**
 * A VISION MODEL'S NUMBER MUST NOT REACH A FORM FIELD UNTIL A PERSON ACCEPTS IT — and this file is
 * the point of the change it guards.
 *
 * ── THE DEFECT ─────────────────────────────────────────────────────────────────────────────────
 *
 * `GridMeasurement.tsx` called `onLengthBreadth(...)` / `onHeight(...)` from inside the
 * `analyzeMeasurementImage` success block. So Gemini's estimate of an object photographed on a sheet
 * of graph paper landed in `ProductForm`'s and `ToolForm`'s `lengthInches` / `breadthInches` /
 * `heightInches` state with nobody's consent — and `records.merge_field_provenance` then stamped that
 * field with the `{by, byName, at}` of whoever pressed Save, because the dimension columns are not in
 * its `PROVENANCE_SKIP_FIELDS`. The stored row therefore asserted that a NAMED HUMAN had measured the
 * object. That number is printed as a documented dimension by `services/record_fields.py`, is read by
 * somebody costing a production run, and is carried into the .docx a Development Commissioner's
 * office receives. `backend/app/services/measurement_provenance.py` names these two callbacks in its
 * list of "the four places a model's number currently lands in form state with nobody's consent".
 *
 * ── WHY THIS IS A NODE SPEC AND NOT A BROWSER ONE ──────────────────────────────────────────────
 *
 * There is no React renderer in this repository's devDependencies — Playwright is the whole of it —
 * so a judgement written inside JSX is only ever exercised by somebody looking at a screen, and this
 * is the judgement least suited to that: the broken state looks EXACTLY like the working one (a
 * number in a box) until a ministry reads the document. `components/media/gridProposal.ts` therefore
 * holds the whole decision as a pure state machine that RETURNS the write rather than performing one,
 * and the tests below drive it. The same split, for the same reason, as `components/ui/selectFilter.ts`
 * and `components/data/cappedList.ts`.
 *
 * The half that cannot be driven — that the component has no second door into form state — is a
 * source read, exactly as `capped-lists-unit.spec.ts` and `derived-fields-unit.spec.ts` read theirs.
 *
 * WHAT THESE DO NOT PROVE: that a browser paints the accept button. This used to name a second gap —
 * *"and that the marker reaches the save body. The second is still open by design — `ProductForm` /
 * `ToolForm` must collect `measurementMethods`"* — and that gap closed on 2026-08-27. Both forms
 * collect it through `components/forms/measurementMethods.ts`, whose own rules (a marker only for a
 * box still holding the number the route proposed) are driven from
 * `e2e/record-photo-measure-unit.spec.ts`. What is still true either way: a save with no marker is
 * recorded as `UNRECORDED`, which is honest and is never the false human claim.
 */

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

/** A response shaped exactly as `MeasurementProvenance.payload()` builds it, around a real reading. */
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

/** Drive the machine through a list of events, collecting every write it produced. */
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
  // Capture, then the model answers. This is the entire sequence the old code auto-filled from.
  const offered = run([
    { type: "CAPTURE", group: "lengthBreadth" },
    { type: "ANALYSIS", group: "lengthBreadth", response: answered({ lengthInches: 3.5, breadthInches: 2 }) }
  ]);

  // THE ASSERTION THE WHOLE CHANGE EXISTS FOR: the model has answered, the figure is on screen, and
  // nothing has been written anywhere.
  expect(offered.writes).toEqual([]);
  const proposal = offered.state.proposals.lengthBreadth;
  expect(proposal, "the reading must be held as an offer, not dropped").toBeTruthy();
  expect(proposal?.readings.map((reading) => reading.label)).toEqual(['L 3.50"', 'B 2.00"']);
  expect(offered.state.status.lengthBreadth).toBe('Read L 3.50" · B 2.00" — check it against the object');

  // Only the button writes, and it writes exactly what it printed.
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
  // Echoed back VERBATIM as it arrived — the server writes it beside `{by, byName, at}` so the row
  // reads "a vision model estimated this, and this person accepted it", rather than as a measurement
  // that person took.
  expect(writes[0].marker).toEqual({
    method: "VISION_MODEL",
    provider: "gemini",
    modelId: "gemini-2.5-flash-lite",
    selfReportedConfidence: 0.8
  });
  expect(writes[0].readings[0].label).toBe("4.00 in");
});

test("an absent marker stays absent — it is never defaulted to TYPED", () => {
  // An older server that has not deployed `measurement_provenance` sends no marker. A save carrying
  // none is recorded as UNRECORDED, which is honest and distinguishable. Reading the absence as
  // "typed" would be the original defect with a new spelling: it would assert a human measured a
  // number a machine guessed, for exactly the rows where the assertion is false.
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

  // The designer refuses the reading.
  const discarded = run([{ type: "DISCARD", group: "lengthBreadth" }], offered);
  expect(discarded.writes).toEqual([]);
  expect(discarded.state.proposals.lengthBreadth).toBeUndefined();
  expect(discarded.state.status.lengthBreadth).toBe(GRID_DISCARDED_STATUS);

  // Unchecking the dimension takes the status line with it — the row must come back blank rather
  // than carrying the last thing said about a photograph the form no longer has.
  const unchecked = run([{ type: "DISCARD", group: "lengthBreadth", clearStatus: true }], offered);
  expect(unchecked.writes).toEqual([]);
  expect(unchecked.state.status.lengthBreadth).toBeUndefined();

  // A re-capture retracts the previous offer BEFORE it asks for a new one. Leaving it standing would
  // put an accept button under a figure belonging to a photograph that has just been replaced.
  const recaptured = run([{ type: "CAPTURE", group: "lengthBreadth" }], offered);
  expect(recaptured.writes).toEqual([]);
  expect(recaptured.state.proposals.lengthBreadth).toBeUndefined();
  expect(recaptured.state.status.lengthBreadth).toBe(GRID_ANALYZING_STATUS);

  // And a failure cannot leave a stale offer behind either.
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
  // A double press, a stale render, a keyboard activation after the card has gone. It must not
  // resurrect a figure from anywhere.
  const { writes, state } = run([{ type: "ACCEPT", group: "lengthBreadth" }]);
  expect(writes).toEqual([]);
  expect(state).toEqual(EMPTY_GRID_STATE);
});

test("half a reading is still an offer, and no reading at all is not an error", () => {
  // One photograph, two dimensions: a top-down shot the model read a length from and no breadth is an
  // ordinary outcome and must be acceptable for the half it did read.
  const half = run([
    { type: "ANALYSIS", group: "lengthBreadth", response: answered({ lengthInches: 3.5, breadthInches: null }) },
    { type: "ACCEPT", group: "lengthBreadth" }
  ]);
  expect(half.writes[0].readings.map((reading) => reading.dimension)).toEqual(["length"]);

  // Nothing readable: the provider was reached and looked, so this is not worded as a failure.
  const none = run([{ type: "ANALYSIS", group: "lengthBreadth", response: answered({}) }]);
  expect(none.writes).toEqual([]);
  expect(none.state.proposals.lengthBreadth).toBeUndefined();
  expect(none.state.status.lengthBreadth).toBe(GRID_UNREADABLE_STATUS);
});

test("an old server's 200 with available:false keeps the sentence that names the missing key", () => {
  // `media.py` raises 503 for this now, so it is unreachable against a current deployment — but a web
  // build outlives a backend deploy, and collapsing it into "couldn't read a value" is precisely the
  // confusion the 503 was introduced to end: a researcher re-photographs the object in better light
  // for ever while the real answer is that nobody has set GEMINI_API_KEY.
  const message = "Grid measurement is unavailable because no Gemini API key is configured.";
  const { state, writes } = run([
    { type: "ANALYSIS", group: "height", response: { available: false, status: "UNAVAILABLE", analysis: null, message } }
  ]);
  expect(writes).toEqual([]);
  expect(state.status.height).toBe(message);
});

test("the value written matches the two decimals the button prints and the box accepts", () => {
  // `ProductForm`/`ToolForm` declare every dimension as `type="number" step="0.01"`, and the forms
  // carry no `noValidate` — so a value with more decimals is a `stepMismatch` and the browser refuses
  // the submit with a bubble on a field the designer never touched. `String(value)` used to write
  // exactly that.
  expect(formatGridInches(3.4967)).toBe("3.50");
  expect(formatGridInches("2")).toBe("2.00");

  // Not a dimension of anything — and `0.00` in a documented measurement is a confident answer about
  // nothing, which is worse than no answer.
  expect(formatGridInches(0.004)).toBeNull();
  expect(formatGridInches(0)).toBeNull();
  expect(formatGridInches(-1)).toBeNull();
  expect(formatGridInches(null)).toBeNull();
  expect(formatGridInches("about four inches")).toBeNull();
});

test("a failed read says WHICH failure it was, because they need different things done about them", () => {
  /*
    THE ERRORS ARE BUILT THE WAY `apiFetch` BUILDS THEM, PAYLOAD AND ALL, and that is not decoration.
    `apiFetch` sets `message = describeApiDetail(detail, statusText || "The server refused the request
    (HTTP ${status}).")` and `payload = body`, so the BODY is the only place "the server put a sentence
    here" is visible — `statusText` is empty over HTTP/2, which every deployed request is. An earlier
    draft of this test constructed `new ApiError(503, sentence, null)`, a shape `apiFetch` cannot
    produce, and so could not have caught a classifier that quotes a fabricated message. See
    `serverSentence` in `lib/failureTriage.ts`.
  */
  const answered = (status: number, detail: string) => new ApiError(status, detail, { detail });

  // 1. Nothing reached the server. The section hint has already warned that this control needs one,
  //    so this reads as the stated limit rather than as a fault.
  expect(gridFailureStatus(new TypeError("Failed to fetch"))).toContain("No connection");

  // 2. The server answered and refused, in its own words — the sentence names GEMINI_API_KEY, and no
  //    client could have guessed it. A generic "Analysis failed" here is the defect: it sends a
  //    researcher back out to re-photograph an object over a problem only an administrator can fix.
  const unconfigured =
    "Grid measurement is unavailable because no Gemini API key is configured. Measure the object and " +
    "type the value in, or ask whoever administers the server to add GEMINI_API_KEY in the Settings hub.";
  expect(gridFailureStatus(answered(503, unconfigured))).toBe(unconfigured);
  expect(gridFailureStatus(answered(413, "The image is larger than the 8 MB limit."))).toContain("8 MB");

  // 3. THIS DEVICE refused the file, so no request was made. Not "offline" — nothing about a
  //    connection can help a capture the camera never finished writing.
  expect(gridFailureStatus(new LocalRefusalError("That file is empty."))).toContain("empty");
  expect(gridFailureStatus(new LocalRefusalError("That file is empty."))).not.toContain("No connection");

  // 4. AND A 503 WITH NO BODY BEHIND IT IS NOT THE UNCONFIGURED SENTENCE. A gateway in a deploy window
  //    answers with no `detail`, so `ApiError.message` is `apiFetch`'s own last resort — the literal
  //    "The server refused the request (HTTP 503)." Printing that would show a status code on a screen
  //    whose whole promise is that it never does, dressed as the server naming a missing key.
  const bodyless = new ApiError(503, "The server refused the request (HTTP 503).", null);
  expect(gridFailureStatus(bodyless)).not.toContain("HTTP 503");
  expect(gridFailureStatus(bodyless)).not.toBe(GRID_FAILED_STATUS);
  // It still says the true thing — nobody has switched this on here, and it is not the photograph.
  expect(gridFailureStatus(bodyless)).toContain("administers the server");

  // And they are genuinely distinguishable, which is the whole claim.
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
  // §1.3 of the frontend reference: Android owns the wording, and a researcher moves between the two
  // apps mid-workshop. Where Android has a sentence and the web has none, copying is the whole rule.
  // These clauses are asserted rather than the full string because the Kotlin literal is assembled
  // across four concatenated lines; each clause below is the load-bearing half of one of them.
  const android = readFileSync(ANDROID_MAIN, "utf8");
  for (const clause of [
    "Reading the photo needs a connection",
    "offered for you to check and accept",
    "nothing is written into a",
    "field until you press the button"
  ]) {
    expect(android, `${clause} — has the handset's grid hint been reworded?`).toContain(clause);
    expect(GRID_SECTION_HINT).toContain(clause);
  }

  // The old sentence promised a write this control no longer makes. A hint that describes a fill the
  // app does not perform reads as a broken control rather than a deliberate one.
  expect(GRID_SECTION_HINT).not.toContain("auto-fill");

  // The per-group hints moved with it, and say "offers" on both clients.
  for (const hint of Object.values(GRID_GROUP_HINTS)) {
    expect(android, `${hint} — the two clients' grid hints have drifted`).toContain(hint);
    expect(hint).toContain("offers");
  }
});

test("the component has exactly one door into form state, and it is the accept button", () => {
  // The source read, because this is the part no pure function can hold: that nothing ELSE in the
  // component calls the write callbacks. Comments are stripped first so the prose above the code —
  // which names both callbacks repeatedly, on purpose — cannot satisfy or break the count.
  const source = read("components", "media", "GridMeasurement.tsx")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "");

  expect(source.match(/onLengthBreadth\(/g) ?? []).toHaveLength(1);
  expect(source.match(/onHeight\(/g) ?? []).toHaveLength(1);

  // …and both of them are inside `accept`, which is the only function that runs from the button.
  // `renderGroup` is the next declaration, so it bounds the slice.
  const from = source.indexOf("function accept(");
  const to = source.indexOf("function renderGroup(");
  expect(from, "accept() not found — has the component been restructured?").toBeGreaterThan(-1);
  expect(to).toBeGreaterThan(from);
  const acceptBody = source.slice(from, to);
  expect(acceptBody).toContain("onLengthBreadth(");
  expect(acceptBody).toContain("onHeight(");

  // The regression witness: the analysis handler must not write. If a future edit puts a callback
  // back into the response path, the count above fails first — this names what it would mean.
  const pickFrom = source.indexOf("async function pick(");
  const pickTo = source.indexOf("function accept(");
  const pickBody = source.slice(pickFrom, pickTo);
  expect(pickBody).not.toContain("onLengthBreadth(");
  expect(pickBody).not.toContain("onHeight(");
});
