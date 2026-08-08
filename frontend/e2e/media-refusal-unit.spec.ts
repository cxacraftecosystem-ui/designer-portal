import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { MediaBatchError } from "@/lib/media";
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
