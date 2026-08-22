import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { LocalRefusalError, triageFailure } from "@/lib/failureTriage";
import { MediaBatchError, uploadMediaBatch } from "@/lib/media";
import { isUnreachable } from "@/lib/offline";

/**
 * A photograph the server REFUSES must not be reported as a lost connection.
 *
 * THE DEFECT THIS PINS. The design-workshop sync uploads one file per `uploadMediaBatch` call, and
 * that helper escalates a batch in which nothing landed — so for this caller "the whole batch
 * failed" is precisely "the server refused this photograph". The escalation was a bare `new Error`
 * carrying only a sentence, and every test of "did the server answer" is `error instanceof
 * ApiError`. So `isUnreachable` answered true, the pass stopped as `stoppedOffline`, the refusal was
 * never recorded against the file, the stage was never unblocked, and the next connection met the
 * same refusal — for ever. The designer was told to go and find a better signal about a video the
 * server was never going to accept.
 *
 * The branch that was supposed to record the refusal could not run at all: it sat AFTER a call that
 * always threw first. That is the shape worth remembering — a documented recovery path made
 * unreachable by the helper above it.
 *
 * These assertions are on the classifier and the error type, not on a browser: they are what decides
 * which way the sync goes, and they are decidable without one.
 */

test("a wrapped ApiError is classified by what it wraps, not by the wrapper", () => {
  // The server answered and refused: NOT unreachable, so the pass records it and carries on.
  expect(isUnreachable(new MediaBatchError("all 1 file(s) failed", [
    { name: "loom.mp4", error: "Unsupported media type", cause: new ApiError(415, "Unsupported media type", null) }
  ]))).toBe(false);

  // 408 is the one status on the offline side — the request never completed.
  expect(isUnreachable(new MediaBatchError("all 1 file(s) failed", [
    { name: "loom.jpg", error: "timeout", cause: new ApiError(408, "timeout", null) }
  ]))).toBe(true);

  // A dropped connection throws a TypeError out of fetch, which has no cause and stays "offline".
  expect(isUnreachable(new MediaBatchError("all 1 file(s) failed", [
    { name: "loom.jpg", error: "Failed to fetch", cause: new TypeError("Failed to fetch") }
  ]))).toBe(true);
});

test("the unwrapping does not disturb the answers that were already right", () => {
  expect(isUnreachable(new ApiError(500, "boom", null))).toBe(false);
  expect(isUnreachable(new ApiError(408, "timeout", null))).toBe(true);
  expect(isUnreachable(new TypeError("Failed to fetch"))).toBe(true);
  expect(isUnreachable("not an error at all")).toBe(true);
});

test("a cause that points at its own error terminates", () => {
  // `cause` is an ordinary property and nothing stops it forming a cycle. The classifier is bounded,
  // so this answers rather than exhausting the stack — the default, which is "offline".
  const loop = new Error("round and round") as Error & { cause?: unknown };
  loop.cause = loop;
  expect(isUnreachable(loop)).toBe(true);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The SENTENCE, which stayed hand-rolled for a wave after the classification was shared
 * ──────────────────────────────────────────────────────────────────────────── */

test("a batch nothing survived does not blame the connection when the connection was not at fault", async () => {
  /*
    THE STRING THE UNIFICATION LEFT BEHIND. `cause` is carried, `triageFailure` reads it, and every
    drain acts on the verdict — and the advice clause of `MediaBatchError`'s own message went on
    being the constant "Check your internet connection and try again — the record was saved, so
    re-open it and re-attach the media", whatever had actually happened. A 413, a 415, a 422 naming a
    key the two builds disagree about: the server answered all three, and each of them sent a
    researcher out of the building to look for signal they already had. `adviceForALostBatch` in
    `lib/media.ts` picks the clause off the verdict's `screen` instead.

    THIS DRIVES THE REAL FUNCTION AND NOT A HAND-BUILT ERROR, which is why it uses the one refusal
    that needs no server: a 0-byte file — a capture the camera app never finished writing, a card
    pulled mid-write — is refused by THIS DEVICE inside `uploadObject`, before a request is made. It
    sorts to `permanent`, whose screen action is `say-unsendable`, and "check your internet" is
    exactly as wrong for it as for a 415. Every other arm needs a server to answer, so the arms are
    walked directly in `failure-triage-unit.spec.ts`; what is pinned here is that the clause is
    chosen at all.
  */
  const empty = new File([], "capture-0001.jpg", { type: "image/jpeg" });
  const raised = await uploadMediaBatch({ files: [empty], linkedRecordType: "craft", linkedRecordId: "c1" }).then(
    () => null,
    (error: unknown) => error
  );

  expect(raised, "a batch in which nothing landed escalates").toBeInstanceOf(MediaBatchError);
  const message = (raised as MediaBatchError).message;
  // The reason clause is unchanged — callers that strip the advice and keep the reason are unaffected.
  expect(message, "the per-file reason still leads").toContain("0 bytes");
  expect(message, "no server was involved, so nothing here is about a connection").not.toContain(
    "Check your internet connection"
  );
  expect(message, "and it says what it actually is").toContain("Nothing was sent");
  // The cause is intact, so the classifier downstream still reaches the same verdict this sentence did.
  expect((raised as Error & { cause?: unknown }).cause).toBeInstanceOf(LocalRefusalError);
  expect(isUnreachable(raised), "a file this device refused is not a lost connection").toBe(false);
});

/**
 * The other five arms of the same clause, driven through the real `uploadMediaBatch`.
 *
 * WHY THIS NEEDED A STUB AND WHY THE STUB IS THIS SMALL. The 0-byte arm above needs no server —
 * `uploadObject` refuses the file before presigning — and the first version of this file stopped
 * there, leaving five of six arms of a new user-facing branch unpinned: nothing would have failed if
 * a later edit collapsed them back to one string. Every remaining arm needs a status to have been
 * ANSWERED, and the whole of what answers is `globalThis.fetch`: the batch's first request is
 * `POST /media/presign` through `apiFetch`, so one stub that returns a status is the entire harness.
 * The bytes never reach `putBlob` (that is XHR, and this never gets that far).
 *
 * `redirectOn401: false` IS NOT DECORATION. `apiFetch` navigates to /login on a 401 by default; in
 * Node `typeof window === "undefined"` makes that a no-op today, so this is a guard against the day
 * this file runs anywhere else, and it is what the outbox drain passes for the real reason.
 *
 * 503 IS THE SLOW ONE, deliberately kept: it is in `RETRIABLE_GATEWAY_CODES`, so `apiRetry` spends
 * all four attempts (0.6s + 1.2s + 1.8s of backoff) before the batch gives up. That is the narrowing
 * `failure-triage-unit.spec.ts` records as the last hand-rolled status table, and a test that stubbed
 * the delay away would stop being a test of what a designer waits through.
 */
const ANSWERED_ARMS = [
  {
    what: "an expired token",
    status: 401,
    body: { detail: "Not authenticated" },
    says: "Your sign-in has expired",
    // A 401 is never a refusal of the WORK — the twenty other fields were fine and one sign-in fixes it.
    andNot: "refused the media"
  },
  {
    what: "a rate limit, which is the server asking for time in words",
    status: 429,
    body: { detail: "Too many requests" },
    says: "could not take the media just now",
    andNot: "sign in again"
  },
  {
    what: "a 503, the shape `ApiUnconfiguredError` also wears",
    status: 503,
    body: { detail: "Service unavailable" },
    says: "could not take the media just now",
    andNot: "out of step"
  },
  {
    what: "a media type the server will not take",
    status: 415,
    body: { detail: "Unsupported media type" },
    says: "answered and refused the media",
    andNot: "wait a minute"
  },
  {
    what: "a 422 naming a key the two builds disagree about",
    status: 422,
    body: { detail: [{ type: "extra_forbidden", loc: ["body", "merge"], msg: "Extra inputs are not permitted" }] },
    says: "out of step",
    andNot: "re-attach the media"
  }
] as const;

for (const arm of ANSWERED_ARMS) {
  test(`a batch lost to ${arm.what} says so, and does not send anyone looking for signal`, async () => {
    const realFetch = globalThis.fetch;
    globalThis.fetch = (async () =>
      new Response(JSON.stringify(arm.body), {
        status: arm.status,
        headers: { "content-type": "application/json" }
      })) as typeof globalThis.fetch;
    try {
      const photograph = new File([new Uint8Array(64)], "loom.jpg", { type: "image/jpeg" });
      const raised = await uploadMediaBatch({
        files: [photograph],
        linkedRecordType: "craft",
        linkedRecordId: "c1",
        redirectOn401: false
      }).then(
        () => null,
        (error: unknown) => error
      );

      expect(raised, "nothing landed, so the batch escalates").toBeInstanceOf(MediaBatchError);
      const message = (raised as MediaBatchError).message;
      expect(message, "the reason clause still leads with the server's own sentence").toContain(
        "media file(s) failed to upload"
      );
      expect(message, `a ${arm.status} gets its own advice`).toContain(arm.says);
      expect(message, "and not the sentence for a different verdict").not.toContain(arm.andNot);
      expect(message, "a server that answered is never a connection to go and look for").not.toContain(
        "Check your internet connection"
      );
      // The classifier and the sentence must agree, or one of them is lying to a different surface.
      expect(triageFailure(raised).status, "the status survives the wrapper").toBe(arm.status);
    } finally {
      globalThis.fetch = realFetch;
    }
  });
}

test("the record having been saved is stated for every arm except the one where it is not the point", async () => {
  /*
    ONE ASSERTION THE TABLE ABOVE CANNOT MAKE PER-ARM, because it is about the shape all six share.
    `uploadMediaBatch` runs AFTER the record is written — that is the whole reason a lost batch is not
    a lost form — and five of the six sentences end by saying so. The `say-out-of-step` arm says "The
    record was saved." and stops: re-opening it to re-attach changes nothing while the two builds
    disagree, so sending someone back to the form would be the same wasted trip in a different
    direction.
  */
  const realFetch = globalThis.fetch;
  globalThis.fetch = (async () =>
    new Response(JSON.stringify({ detail: "Unsupported media type" }), {
      status: 415,
      headers: { "content-type": "application/json" }
    })) as typeof globalThis.fetch;
  try {
    const raised = await uploadMediaBatch({
      files: [new File([new Uint8Array(8)], "loom.jpg", { type: "image/jpeg" })],
      linkedRecordType: "craft",
      linkedRecordId: "c1",
      redirectOn401: false
    }).then(
      () => null,
      (error: unknown) => error
    );
    expect((raised as MediaBatchError).message).toContain("The record was saved");
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("MediaBatchError keeps every failure, not only the first", () => {
  const error = new MediaBatchError("all 2 file(s) failed", [
    { name: "a.jpg", error: "Unsupported media type", cause: new ApiError(415, "Unsupported media type", null) },
    { name: "b.jpg", error: "Payload too large", cause: new ApiError(413, "Payload too large", null) }
  ]);
  expect(error.failures).toHaveLength(2);
  expect(error.failures[1].name).toBe("b.jpg");
  // `cause` is the first, which is the whole of it for the one-file batch this exists to serve.
  expect((error as Error & { cause?: unknown }).cause).toBeInstanceOf(ApiError);
  expect(error).toBeInstanceOf(Error);
});
