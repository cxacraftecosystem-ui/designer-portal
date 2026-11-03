import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  localWriteDecision,
  stageRefusalResult,
  stageRefusalWroteCount
} from "@/lib/stageSaveOutcome";

const LANDED = { localId: "dwlocal-1", stages: {} };

test("a landed local write banks the snapshot and puts out every outstanding-work signal", () => {
  expect(localWriteDecision(LANDED)).toEqual({ bank: true, retry: false, pending: false, failed: false });
});

test("a REFUSED local write banks nothing — the baseline must stay stale so the values are written again", () => {
  expect(localWriteDecision(null).bank).toBe(false);
});

test("a refused local write keeps the retry armed, keeps the amber chip lit, and says so out loud", () => {
  expect(localWriteDecision(null)).toEqual({ bank: false, retry: true, pending: true, failed: true });
});

test("undefined is treated exactly as null — an absent answer is not a written one", () => {
  expect(localWriteDecision(undefined)).toEqual(localWriteDecision(null));
});

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
  const result = stageRefusalResult(new ApiError(422, "Some required fields are missing", SUBMIT_REFUSAL));
  expect(stageRefusalWroteCount(result)).toBe(5);
  expect(result!.customSchemaVersion).toBe("v7");
});

test("a 422 whose detail is a bare STRING is refused — that is the schema-skew shape, not a save result", () => {
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
