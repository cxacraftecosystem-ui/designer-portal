import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { isSchemaRefusal, isTransient, isUnreachable } from "@/lib/offline";

const EXTRA_FORBIDDEN = {
  detail: [
    {
      type: "extra_forbidden",
      loc: ["body", "entries", 0, "totallyUnknownKey"],
      msg: "Extra inputs are not permitted",
      input: true
    }
  ]
};

const FIELD_INVALID = {
  detail: [
    { type: "string_too_long", loc: ["body", "title"], msg: "String should have at most 200 characters" }
  ]
};

test("an unknown key is a schema refusal, not the designer's fault", () => {
  expect(isSchemaRefusal(new ApiError(422, "Extra inputs are not permitted", EXTRA_FORBIDDEN))).toBe(true);
});

test("a field the validator rejected is NOT a schema refusal", () => {
  expect(isSchemaRefusal(new ApiError(422, "String should have at most 200 characters", FIELD_INVALID))).toBe(false);
});

test("only 422 qualifies, and only with a well-formed detail array", () => {
  expect(isSchemaRefusal(new ApiError(500, "Extra inputs are not permitted", EXTRA_FORBIDDEN))).toBe(false);
  expect(isSchemaRefusal(new ApiError(422, "boom", null))).toBe(false);
  expect(isSchemaRefusal(new ApiError(422, "boom", { detail: "not an array" }))).toBe(false);
  expect(isSchemaRefusal(new ApiError(422, "boom", { detail: [null] }))).toBe(false);
  expect(isSchemaRefusal(new ApiError(422, "boom", {}))).toBe(false);
});

test("things that are not ApiErrors are never schema refusals", () => {
  expect(isSchemaRefusal(new TypeError("Failed to fetch"))).toBe(false);
  expect(isSchemaRefusal("nonsense")).toBe(false);
  expect(isSchemaRefusal(null)).toBe(false);
});

test("the three classifiers stay independent", () => {
  const schema = new ApiError(422, "Extra inputs are not permitted", EXTRA_FORBIDDEN);
  expect(isSchemaRefusal(schema)).toBe(true);
  expect(isUnreachable(schema)).toBe(false);
  expect(isTransient(schema)).toBe(false);
});
