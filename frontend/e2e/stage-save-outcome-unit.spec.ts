import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  localWriteDecision,
  stageRefusalResult,
  stageRefusalWroteCount
} from "@/lib/stageSaveOutcome";

/**
 * THE TWO OUTCOMES THE STAGE FORM USED TO READ AS "NOTHING HAPPENED".
 *
 * Audit 2026-08-15 filed three findings that are one mistake seen from three sides: an outcome the
 * caller had not modelled was treated as an absence.
 *
 *   - MAJOR "the stage form banks a FAILED local write as saved" — `putDraftStage` answers `null`
 *     when the IndexedDB transaction ABORTED, and the autosave advanced its baseline, disarmed its
 *     retry and put out the amber chip anyway. Because the baseline had moved, the effect's
 *     `sameSnapshot` short-circuit then refused to write those values ever again: the loss was
 *     permanent for the session, and `save()` — which rebuilds its payload from the draft read back
 *     OFF DISK — uploaded the stale copy under "Stage saved".
 *   - MAJOR "the strict pass discards the per-field errors the 422 carries" and MAJOR "the strict
 *     pass names no field" — the same missing branch, twice. `describeApiDetail` flattens `detail`
 *     to `detail.message`, so the per-field map reached `ApiError.payload` and nothing read it.
 *
 * THESE ARE UNIT TESTS BECAUSE THE DEFECTS SURVIVED FOR WANT OF ONE. Reproducing either in a browser
 * needs a signed-in session, a live API and a deliberately-broken origin quota — which is why a
 * comment asserting "`describeApiDetail` has already turned it into a sentence" sat above a catch
 * that could not possibly have been doing that, for as long as it did. The decisions now live in
 * `lib/stageSaveOutcome.ts` and are checkable in four milliseconds.
 *
 * Run: `npx playwright test e2e/stage-save-outcome-unit.spec.ts --reporter=line`
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The local write — a null is a refusal, not a save
 * ──────────────────────────────────────────────────────────────────────────── */

/** What a landed write looks like: `mutate` hands back the draft it wrote. */
const LANDED = { localId: "dwlocal-1", stages: {} };

test("a landed local write banks the snapshot and puts out every outstanding-work signal", () => {
  expect(localWriteDecision(LANDED)).toEqual({ bank: true, retry: false, pending: false, failed: false });
});

test("a REFUSED local write banks nothing — the baseline must stay stale so the values are written again", () => {
  // This is the assertion the whole finding turns on. `bank: false` is what keeps the autosave
  // effect's `sameSnapshot` comparison unequal on the next render; banking here is what made the
  // loss permanent rather than merely delayed.
  expect(localWriteDecision(null).bank).toBe(false);
});

test("a refused local write keeps the retry armed, keeps the amber chip lit, and says so out loud", () => {
  expect(localWriteDecision(null)).toEqual({ bank: false, retry: true, pending: true, failed: true });
});

test("undefined is treated exactly as null — an absent answer is not a written one", () => {
  // `putDraftStage` is typed to return a draft or null, but it is `mutate`'s value passed straight
  // through and a future arm returning nothing must not read as success.
  expect(localWriteDecision(undefined)).toEqual(localWriteDecision(null));
});

/* ────────────────────────────────────────────────────────────────────────────
 * The 422 — a full save result, not a sentence
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The body `PUT /design-workshops/{id}/stages/{key}` sends for a refused `submit=true`.
 *
 * Shaped from the route itself (`detail={**result, "message": _submit_refusal_message(...)}`), so it
 * carries the counts of what WAS committed before the refusal was raised — which is the half of this
 * finding that is not about messages. The scope keys are indices into the entries ARRAY THAT WAS
 * SENT, which is why `placeStageErrors` needs the caller's `rowKeys` to re-address them.
 */
const SUBMIT_REFUSAL = {
  detail: {
    stageKey: "CLUSTER_CRAFT_BACKGROUND",
    saved: 2,
    created: 1,
    updated: 1,
    removed: 3,
    errors: {
      clusterBackground: { giDetails: "This field is required." },
      "tool[1]": { name: "This field is required." }
    },
    droppedKeys: [],
    droppedCustomKeys: [],
    completeness: null,
    customSchemaVersion: "v7",
    message: "Some required fields are missing"
  }
};

test("a submit 422 yields the whole result, so the per-field map reaches the boxes", () => {
  const result = stageRefusalResult(new ApiError(422, "Some required fields are missing", SUBMIT_REFUSAL));
  expect(result).not.toBeNull();
  expect(Object.keys(result!.errors)).toEqual(["clusterBackground", "tool[1]"]);
  expect(result!.errors["tool[1]"].name).toBe("This field is required.");
});

test("and it yields what the refused save nevertheless WROTE, which the old catch never looked at", () => {
  // The refusal is raised AFTER `save_stage` has committed: rows were created, rows were updated and
  // the sweep's soft-deletes landed. Reporting it as a bare failure told a designer that a deletion
  // they had just made had not happened.
  const result = stageRefusalResult(new ApiError(422, "Some required fields are missing", SUBMIT_REFUSAL));
  expect(stageRefusalWroteCount(result)).toBe(5);
  expect(result!.customSchemaVersion).toBe("v7");
});

test("a 422 whose detail is a bare STRING is refused — that is the schema-skew shape, not a save result", () => {
  // Pydantic's `extra="forbid"` refusal on this very route answers 422 with a string detail. Reading
  // it as a result would mark boxes from a response that never examined an answer.
  expect(stageRefusalResult(new ApiError(422, "…", { detail: "Extra inputs are not permitted" }))).toBeNull();
});

test("a FastAPI validation 422 — detail as a LIST — is refused, because an array is an object", () => {
  const body = { detail: [{ loc: ["body", "entries"], msg: "field required", type: "value_error.missing" }] };
  expect(stageRefusalResult(new ApiError(422, "field required", body))).toBeNull();
});

test("a 422 with a detail object but no error map is refused rather than half-read", () => {
  expect(stageRefusalResult(new ApiError(422, "…", { detail: { message: "no" } }))).toBeNull();
});

test("only 422 is accepted — a 500's envelope carries no examined answers", () => {
  expect(stageRefusalResult(new ApiError(500, "…", SUBMIT_REFUSAL))).toBeNull();
  expect(stageRefusalResult(new ApiError(404, "Record not found", SUBMIT_REFUSAL))).toBeNull();
});

test("a plain Error, a null body and a string body all degrade to null instead of throwing", () => {
  // This function runs inside a catch. Throwing a second error out of the handler for the first one
  // would replace a refusal the designer can act on with a blank screen.
  expect(stageRefusalResult(new Error("boom"))).toBeNull();
  expect(stageRefusalResult(new ApiError(422, "…", null))).toBeNull();
  expect(stageRefusalResult(new ApiError(422, "…", "Unprocessable Entity"))).toBeNull();
  expect(stageRefusalResult(undefined)).toBeNull();
});

test("a refusal that committed nothing reports zero, so the sentence does not claim a write nobody made", () => {
  const clean = { detail: { ...SUBMIT_REFUSAL.detail, created: 0, updated: 0, removed: 0 } };
  const result = stageRefusalResult(new ApiError(422, "…", clean));
  expect(stageRefusalWroteCount(result)).toBe(0);
  expect(stageRefusalWroteCount(null)).toBe(0);
});
