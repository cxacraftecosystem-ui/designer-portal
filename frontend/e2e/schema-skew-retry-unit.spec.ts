import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { APP_RUN_ID, blocksRetry, isSchemaRefusal } from "@/lib/offline";

const EXTRA_FORBIDDEN = {
  detail: [
    {
      type: "string_too_short",
      loc: ["body", "password"],
      msg: "String should have at least 8 characters",
      input: "x",
      ctx: { min_length: 8 }
    },
    { type: "extra_forbidden", loc: ["body", "merge"], msg: "Extra inputs are not permitted", input: true }
  ]
};

const FIELD_INVALID = {
  detail: [{ type: "string_too_long", loc: ["body", "title"], msg: "String should have at most 200 characters" }]
};

const PREVIOUS_RUN = "run-that-recorded-the-refusal";

test("a refusal the designer can act on still binds, exactly as before", () => {
  expect(blocksRetry({ permanent: true }), "no skew recorded — only a person can clear it").toBe(true);
  expect(blocksRetry({ permanent: true, skewRun: null }), "an explicit null means the same thing").toBe(true);
});

test("a hold-up that is not a refusal never blocks", () => {
  expect(blocksRetry({ permanent: false, skewRun: null })).toBe(false);
  expect(blocksRetry(null), "nothing has gone wrong at all").toBe(false);
  expect(blocksRetry(undefined)).toBe(false);
});

test("a schema refusal is not re-sent by the run that recorded it", () => {
  expect(blocksRetry({ permanent: true, skewRun: APP_RUN_ID })).toBe(true);
});

test("…and IS re-sent by the next one, which is the whole fix", () => {
  expect(blocksRetry({ permanent: true, skewRun: PREVIOUS_RUN })).toBe(false);
});

test("the app run is one value for the life of the page", () => {
  expect(APP_RUN_ID).toBe(APP_RUN_ID);
  expect(APP_RUN_ID.length, "and it is a real value even outside a secure context").toBeGreaterThan(0);
  expect(APP_RUN_ID).not.toBe(PREVIOUS_RUN);
});

test("the pass marks a skew refusal, and only a skew refusal, as waiting for an update", () => {
  const skew = new ApiError(422, "Extra inputs are not permitted", EXTRA_FORBIDDEN);
  const rejected = new ApiError(422, "String should have at most 200 characters", FIELD_INVALID);

  expect(blocksRetry({ permanent: true, skewRun: isSchemaRefusal(skew) ? PREVIOUS_RUN : null })).toBe(false);
  expect(blocksRetry({ permanent: true, skewRun: isSchemaRefusal(rejected) ? PREVIOUS_RUN : null })).toBe(true);
});

test("a body carrying BOTH an unknown key and a bad field is re-attempted", () => {
  expect(isSchemaRefusal(new ApiError(422, "…", EXTRA_FORBIDDEN))).toBe(true);
});

test("the two questions stay separate: what to say, and whether to try again", () => {
  const serverFault = new ApiError(500, "Extra inputs are not permitted", EXTRA_FORBIDDEN);
  expect(isSchemaRefusal(serverFault)).toBe(false);
  expect(blocksRetry({ permanent: true, skewRun: isSchemaRefusal(serverFault) ? PREVIOUS_RUN : null })).toBe(true);
});
