import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { isSchemaRefusal, isTransient, isUnreachable } from "@/lib/offline";

/**
 * A refusal the designer cannot act on must not be reported as their mistake.
 *
 * WHAT HAPPENED. One sentence covered every refusal the sync got back, and it asserted a cause:
 * "it will keep being refused until the answer that caused it is corrected". On 2026-08-08 a client
 * sent the then-new `merge` flag to an API that predated it; `APIModel` is `extra="forbid"`, so
 * every stage came back 422 "merge: Extra inputs are not permitted" — and the designer was told to
 * open the stage and correct an answer that had nothing wrong with it. They would find nothing,
 * press Try again, and get the same sentence, indefinitely.
 *
 * THE BODIES BELOW ARE REAL. They were captured from the running API by PUTting an unknown key at a
 * stage endpoint, not written from memory — `type: "extra_forbidden"` is pydantic's own
 * discriminator, and matching it rather than the prose is what keeps this working when the message
 * is reworded or translated.
 */

/** Exactly what the API answered on 2026-08-08 for an unknown key in a stage entry. */
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

/** A field the validator rejected — the case the original sentence was written for, and is right for. */
const FIELD_INVALID = {
  detail: [
    { type: "string_too_long", loc: ["body", "title"], msg: "String should have at most 200 characters" }
  ]
};

test("an unknown key is a schema refusal, not the designer's fault", () => {
  expect(isSchemaRefusal(new ApiError(422, "Extra inputs are not permitted", EXTRA_FORBIDDEN))).toBe(true);
});

test("a field the validator rejected is NOT a schema refusal", () => {
  // The distinction the whole fix rests on: this one IS about something a person typed, so it must
  // keep the original "open the stage and correct it" wording.
  expect(isSchemaRefusal(new ApiError(422, "String should have at most 200 characters", FIELD_INVALID))).toBe(false);
});

test("only 422 qualifies, and only with a well-formed detail array", () => {
  // A 500 carrying the same words is a server fault, not a dialect mismatch.
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
  // A schema refusal reached the server (so it is not "offline") and retrying it unchanged will
  // never succeed — but `isTransient` is deliberately generous and this test pins that they are
  // answering different questions rather than drifting into one.
  const schema = new ApiError(422, "Extra inputs are not permitted", EXTRA_FORBIDDEN);
  expect(isSchemaRefusal(schema)).toBe(true);
  expect(isUnreachable(schema)).toBe(false);
  expect(isTransient(schema)).toBe(false);
});
