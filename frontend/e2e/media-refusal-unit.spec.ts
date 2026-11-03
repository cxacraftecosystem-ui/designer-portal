import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { LocalRefusalError, triageFailure } from "@/lib/failureTriage";
import { MediaBatchError, uploadMediaBatch } from "@/lib/media";
import { isUnreachable } from "@/lib/offline";

test("a wrapped ApiError is classified by what it wraps, not by the wrapper", () => {
  expect(isUnreachable(new MediaBatchError("all 1 file(s) failed", [
    { name: "loom.mp4", error: "Unsupported media type", cause: new ApiError(415, "Unsupported media type", null) }
  ]))).toBe(false);

  expect(isUnreachable(new MediaBatchError("all 1 file(s) failed", [
    { name: "loom.jpg", error: "timeout", cause: new ApiError(408, "timeout", null) }
  ]))).toBe(true);

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
  const loop = new Error("round and round") as Error & { cause?: unknown };
  loop.cause = loop;
  expect(isUnreachable(loop)).toBe(true);
});

test("a batch nothing survived does not blame the connection when the connection was not at fault", async () => {
  const empty = new File([], "capture-0001.jpg", { type: "image/jpeg" });
  const raised = await uploadMediaBatch({ files: [empty], linkedRecordType: "craft", linkedRecordId: "c1" }).then(
    () => null,
    (error: unknown) => error
  );

  expect(raised, "a batch in which nothing landed escalates").toBeInstanceOf(MediaBatchError);
  const message = (raised as MediaBatchError).message;
  expect(message, "the per-file reason still leads").toContain("0 bytes");
  expect(message, "no server was involved, so nothing here is about a connection").not.toContain(
    "Check your internet connection"
  );
  expect(message, "and it says what it actually is").toContain("Nothing was sent");
  expect((raised as Error & { cause?: unknown }).cause).toBeInstanceOf(LocalRefusalError);
  expect(isUnreachable(raised), "a file this device refused is not a lost connection").toBe(false);
});

const ANSWERED_ARMS = [
  {
    what: "an expired token",
    status: 401,
    body: { detail: "Not authenticated" },
    says: "Your sign-in has expired",
    andNot: "wasn't accepted"
  },
  {
    what: "a rate limit, which is the server asking for time in words",
    status: 429,
    body: { detail: "Too many requests" },
    says: "couldn't be saved just now",
    andNot: "sign in again"
  },
  {
    what: "a 503, the shape `ApiUnconfiguredError` also wears",
    status: 503,
    body: { detail: "Service unavailable" },
    says: "couldn't be saved just now",
    andNot: "Reload the page to update the app"
  },
  {
    what: "a media type the server will not take",
    status: 415,
    body: { detail: "Unsupported media type" },
    says: "wasn't accepted, for the reason given",
    andNot: "wait a minute"
  },
  {
    what: "a 422 naming a key the two builds disagree about",
    status: 422,
    body: { detail: [{ type: "extra_forbidden", loc: ["body", "merge"], msg: "Extra inputs are not permitted" }] },
    says: "Reload the page to update the app",
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
        "The media file failed to upload"
      );
      expect(message, `a ${arm.status} gets its own advice`).toContain(arm.says);
      expect(message, "and not the sentence for a different verdict").not.toContain(arm.andNot);
      expect(message, "a server that answered is never a connection to go and look for").not.toContain(
        "Check your internet connection"
      );
      expect(triageFailure(raised).status, "the status survives the wrapper").toBe(arm.status);
    } finally {
      globalThis.fetch = realFetch;
    }
  });
}

test("the record having been saved is stated for every arm except the one where it is not the point", async () => {
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
  expect((error as Error & { cause?: unknown }).cause).toBeInstanceOf(ApiError);
  expect(error).toBeInstanceOf(Error);
});
