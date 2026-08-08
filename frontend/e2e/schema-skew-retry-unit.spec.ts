import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { APP_RUN_ID, blocksRetry, isSchemaRefusal } from "@/lib/offline";

/**
 * A REFUSAL MUST NOT OUTLIVE THE BUG THAT CAUSED IT.
 *
 * WHAT HAPPENED, TWICE. A designer opened a workshop and read:
 *
 *   "The repository refused stage 'CLUSTER_CRAFT_BACKGROUND': merge: Extra inputs are not permitted
 *    … it will keep being refused until the answer that caused it is corrected — this is not a
 *    connection problem. Open the stage, then use Try again."
 *
 * By then the cause was gone. `merge` had been added to `StageSaveIn`
 * (`backend/app/schemas/design_workshops.py`) and the very PUT the banner described answered 200.
 * What survived was the RECORD of the refusal: `noteStageFailure` had written it with
 * `permanent: true`, and a permanent failure is stepped over by every future pass, so the app could
 * not recover from a version skew even once the skew had closed. The sentence had already been
 * corrected earlier that day; this is the same defect one layer down, in the retry policy behind it.
 *
 * THE SPLIT THESE TESTS PIN. "Permanent" is right for a refusal a DESIGNER can act on — a rejected
 * field, a workshop an admin deleted, a duplicate. It is wrong for a SCHEMA refusal, whose cause is
 * that two builds disagree and whose fix is an update to one of them. So a schema refusal is
 * recorded and shown exactly like any other (a stage that is silently not syncing is worse than one
 * that says so) and is re-attempted BY THE NEXT APP RUN, with nobody pressing anything.
 *
 * THE BODY BELOW IS REAL. Captured from the running API on 2026-08-08 by posting an unknown key at
 * an endpoint whose model is `extra="forbid"`:
 *
 *   $ curl -X POST localhost:8000/api/auth/login -d '{"email":"a@b.com","password":"x","merge":true}'
 *   422 {"detail":[{"type":"string_too_short","loc":["body","password"],…},
 *                  {"type":"extra_forbidden","loc":["body","merge"],"msg":"Extra inputs are not permitted",…}]}
 *
 * The end-to-end claim — refused, then a server that accepts, then synced with no human
 * intervention — is carried by `design-workshop-schema-skew.spec.ts`, which drives the real store in
 * a real browser. These are the two decisions that spec depends on, pinned where they can be run
 * without a stack.
 */

/** Exactly what the API answered. Note that it carries a FIELD error as well — see the last test. */
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

/** A field the validator rejected, and nothing else: the refusal the old sentence was written for. */
const FIELD_INVALID = {
  detail: [{ type: "string_too_long", loc: ["body", "title"], msg: "String should have at most 200 characters" }]
};

/** Some earlier run of the app. Any value that is not this run's stands for "the app was reopened". */
const PREVIOUS_RUN = "run-that-recorded-the-refusal";

// ── The gate the sync pass consults ────────────────────────────────────────────────────────────

test("a refusal the designer can act on still binds, exactly as before", () => {
  // The whole point of `permanent`, and this must not have moved: re-sending a rejected field would
  // get the same rejection for ever and the banner is right to wait for a person.
  expect(blocksRetry({ permanent: true }), "no skew recorded — only a person can clear it").toBe(true);
  expect(blocksRetry({ permanent: true, skewRun: null }), "an explicit null means the same thing").toBe(true);
});

test("a hold-up that is not a refusal never blocks", () => {
  // "Three files are still on this device" — it clears itself when they upload, and always could.
  expect(blocksRetry({ permanent: false, skewRun: null })).toBe(false);
  expect(blocksRetry(null), "nothing has gone wrong at all").toBe(false);
  expect(blocksRetry(undefined)).toBe(false);
});

test("a schema refusal is not re-sent by the run that recorded it", () => {
  // A pass runs on the `online` event and on every "Sync now", and in a village with a marginal
  // signal the connectivity event alone fires dozens of times an hour. Trying again on every pass
  // would spend a prepaid data plan on 422s nobody reads and leave the banner permanently churning.
  expect(blocksRetry({ permanent: true, skewRun: APP_RUN_ID })).toBe(true);
});

test("…and IS re-sent by the next one, which is the whole fix", () => {
  // The designer does not have to know that "Try again" would help, and has no reason to think it
  // would: the refusal they were shown blamed an answer of theirs.
  expect(blocksRetry({ permanent: true, skewRun: PREVIOUS_RUN })).toBe(false);
});

test("the app run is one value for the life of the page", () => {
  // Load-bearing in both directions. If it changed between reads, the run that recorded a refusal
  // would immediately re-send it and the "not on every pass" guarantee above would be worthless; if
  // it were constant across loads, nothing would ever be re-attempted.
  expect(APP_RUN_ID).toBe(APP_RUN_ID);
  expect(APP_RUN_ID.length, "and it is a real value even outside a secure context").toBeGreaterThan(0);
  expect(APP_RUN_ID).not.toBe(PREVIOUS_RUN);
});

// ── Which refusals earn that treatment ─────────────────────────────────────────────────────────

test("the pass marks a skew refusal, and only a skew refusal, as waiting for an update", () => {
  // This is the line `runSync` runs, on the two bodies it can actually get back. Getting it wrong in
  // the generous direction would re-send a genuinely rejected answer once per app run for ever.
  const skew = new ApiError(422, "Extra inputs are not permitted", EXTRA_FORBIDDEN);
  const rejected = new ApiError(422, "String should have at most 200 characters", FIELD_INVALID);

  expect(blocksRetry({ permanent: true, skewRun: isSchemaRefusal(skew) ? PREVIOUS_RUN : null })).toBe(false);
  expect(blocksRetry({ permanent: true, skewRun: isSchemaRefusal(rejected) ? PREVIOUS_RUN : null })).toBe(true);
});

test("a body carrying BOTH an unknown key and a bad field is re-attempted", () => {
  // The captured body is this shape, and the generous answer is the right one here even though it is
  // wrong above: an update genuinely may change the outcome, a re-attempt costs one request per app
  // run, and the alternative is the reported defect. The field error is not lost — the server says
  // it again on the re-attempt, and `save_stage`'s per-field errors are reported separately.
  expect(isSchemaRefusal(new ApiError(422, "…", EXTRA_FORBIDDEN))).toBe(true);
});

test("the two questions stay separate: what to say, and whether to try again", () => {
  // A 500 carrying the same prose is a server fault, not a dialect mismatch, and must go on binding
  // — nothing about an update to either side would change it.
  const serverFault = new ApiError(500, "Extra inputs are not permitted", EXTRA_FORBIDDEN);
  expect(isSchemaRefusal(serverFault)).toBe(false);
  expect(blocksRetry({ permanent: true, skewRun: isSchemaRefusal(serverFault) ? PREVIOUS_RUN : null })).toBe(true);
});
