import { expect, test } from "@playwright/test";

import { FAILURE_TRIAGE, isTransient, triageFailure, underlyingError } from "@/lib/failureTriage";
import {
  adviceForTransportFailure,
  measureStorageReach,
  MediaBatchError,
  StorageTransportError,
  storageTransportSentence,
  type StorageReach,
  type StorageTransportFacts
} from "@/lib/media";

const FACTS: StorageTransportFacts = {
  bytesMoved: false,
  sentBytes: 0,
  totalBytes: 4_200_000,
  bucketOrigin: "https://s3.dualstack.ap-south-1.amazonaws.com",
  pageOrigin: "https://designer-repository.vercel.app",
  reach: "unmeasured"
};

const REACHES: StorageReach[] = ["unmeasured", "no-route", "site-refused", "reads-allowed"];

function transport(overrides: Partial<StorageTransportFacts> = {}): StorageTransportError {
  return new StorageTransportError({ ...FACTS, ...overrides }, "https://s3.example/media/x/y.jpg");
}

test("a transport failure is still `unreachable`, so the drains keep the designer's bytes", () => {
  for (const reach of REACHES) {
    const verdict = triageFailure(transport({ reach }));
    expect(verdict.kind, reach).toBe("unreachable");
    expect(verdict.drain, reach).toBe(FAILURE_TRIAGE.unreachable.drain);
    expect(verdict.retry, reach).toBe(FAILURE_TRIAGE.unreachable.retry);
    expect(isTransient(transport({ reach })), reach).toBe(true);
  }
});

test("it is a StorageError, which is how it keeps that verdict rather than falling to a default", () => {
  expect(transport() instanceof Error).toBe(true);
  expect(transport().status).toBe(null);
  expect(transport().name).toBe("StorageTransportError");
});

test("the measured error is visible through a MediaBatchError, and hides nothing behind it", () => {
  const measured = transport({ reach: "site-refused" });
  expect((measured as Error & { cause?: unknown }).cause).toBe(undefined);
  const batch = new MediaBatchError("all 1 file(s) failed", [
    { name: "loom.jpg", error: measured.message, cause: measured }
  ]);
  expect(underlyingError(batch)).toBe(measured);
  expect(triageFailure(batch).kind).toBe("unreachable");
});

test("no arm says \"network error\" or speaks of buckets, and every arm says the upload didn't finish", () => {
  for (const reach of REACHES) {
    const sentence = storageTransportSentence({ ...FACTS, reach });
    expect(sentence, reach).not.toContain("network error");
    expect(sentence, reach).toContain("The upload didn't finish");
    expect(sentence, reach).not.toContain(FACTS.bucketOrigin!);
    expect(sentence, reach).not.toContain(FACTS.pageOrigin!);
    expect(sentence.toLowerCase(), reach).not.toContain("bucket");
    expect(sentence, reach).not.toContain("CORS");
  }
});

test("the unmeasured arm names BOTH causes and the observation that separates them", () => {
  const sentence = storageTransportSentence({ ...FACTS, reach: "unmeasured" });
  expect(sentence).toContain("nothing was sent");
  expect(sentence).toContain("Either this device has no connection");
  expect(sentence).toContain("or the problem is on our side");
  expect(sentence).toContain("If the rest of the app is still working, it is on our side");
});

test("a refusal is named as a setting, and does not offer a retry that cannot work", () => {
  const sentence = storageTransportSentence({ ...FACTS, reach: "site-refused" });
  expect(sentence).toContain("a problem on our side");
  expect(sentence).toContain("rather than your connection");

  const advice = adviceForTransportFailure(transport({ reach: "site-refused" }));
  expect(advice).not.toContain("Check your internet connection");
  expect(advice).not.toContain("re-attach the media.");
  expect(advice).toContain("still on this device");
  expect(advice).toContain("Keep them");
});

test("the refusal arm claims a setting, and stops short of promising the upload cannot work", () => {
  const sentence = storageTransportSentence({ ...FACTS, reach: "site-refused" });
  const advice = adviceForTransportFailure(transport({ reach: "site-refused" }));
  for (const claim of ["retrying cannot clear it", "will fail in exactly the same way", "cannot be uploaded"]) {
    expect(sentence, claim).not.toContain(claim);
    expect(advice, claim).not.toContain(claim);
  }
  expect(sentence).toContain("problem on our side rather than your connection");
  expect(sentence).toContain("unlikely to help");
  expect(advice).toContain("may well fail the same way");
  expect(sentence).not.toContain("connection and try again");
});

test("bytes on the wire exonerate the bucket, with no probe spent to prove it", () => {
  for (const reach of REACHES) {
    const sentence = storageTransportSentence({ ...FACTS, reach, bytesMoved: true, sentBytes: 1_048_576 });
    expect(sentence, reach).toContain("1.0 MB of 4.0 MB");
    expect(sentence, reach).toContain("the connection dropped");
    expect(sentence, reach).not.toContain("CORS");
  }
  expect(adviceForTransportFailure(transport({ bytesMoved: true, sentBytes: 1 }))).toContain(
    "once the signal is steady"
  );
});

test("a genuinely unreachable host is still told it is the connection", () => {
  expect(storageTransportSentence({ ...FACTS, reach: "no-route" })).toContain("couldn't connect");
  expect(adviceForTransportFailure(transport({ reach: "no-route" }))).toContain(
    "Check your internet connection"
  );
});

test("every arm produces a distinct sentence, so none of them is decoration", () => {
  const sentences = new Set(REACHES.map((reach) => storageTransportSentence({ ...FACTS, reach })));
  expect(sentences.size).toBe(REACHES.length);
  const clauses = new Set(REACHES.map((reach) => adviceForTransportFailure(transport({ reach }))));
  expect(clauses.size).toBe(REACHES.length);
});

test("a message with no origins to name degrades to prose rather than to `null`", () => {
  const sentence = storageTransportSentence({ ...FACTS, bucketOrigin: null, pageOrigin: null });
  expect(sentence).toContain("The upload didn't finish");
  expect(sentence).not.toContain("null");
});

type Attempt = { url: string; mode?: string; signal?: AbortSignal | null };

async function measureWith(
  answer: (attempt: Attempt) => Promise<Response>,
  timeoutMs?: number
): Promise<{ reach: StorageReach; attempts: Attempt[] }> {
  const attempts: Attempt[] = [];
  const real = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const attempt = { url: String(input), mode: init?.mode, signal: init?.signal };
    attempts.push(attempt);
    return answer(attempt);
  }) as typeof fetch;
  try {
    return { reach: await measureStorageReach("https://bucket.example/media/u/1/x.jpg", timeoutMs), attempts };
  } finally {
    globalThis.fetch = real;
  }
}

const opaque = () => Promise.resolve(new Response(null, { status: 200 }));
const blocked = () => Promise.reject(new TypeError("Failed to fetch"));
const never = (attempt: Attempt) =>
  new Promise<Response>((_resolve, reject) => {
    attempt.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
  });

test("nothing came back at all: the connection, and nothing is blamed on the bucket", async () => {
  const { reach, attempts } = await measureWith(blocked);
  expect(reach).toBe("no-route");
  expect(attempts).toHaveLength(1);
  expect(attempts[0].mode).toBe("no-cors");
});

test("the host answers but will not answer THIS site: the bucket's rule, named", async () => {
  const { reach, attempts } = await measureWith((attempt) => (attempt.mode === "no-cors" ? opaque() : blocked()));
  expect(reach).toBe("site-refused");
  expect(attempts).toHaveLength(2);
  expect(attempts.every((attempt) => attempt.url === "https://bucket.example/media/u/1/x.jpg")).toBe(true);
});

test("a 403 the page is ALLOWED to read is not a refusal of the site", async () => {
  const { reach } = await measureWith(() => Promise.resolve(new Response("<Error/>", { status: 403 })));
  expect(reach).toBe("reads-allowed");
});

test("reads allowed rules out both headline causes without pretending to have found a third", async () => {
  const { reach } = await measureWith(opaque);
  expect(reach).toBe("reads-allowed");
  const sentence = storageTransportSentence({ ...FACTS, reach });
  expect(sentence).toContain("interrupted as it started");
  expect(adviceForTransportFailure(transport({ reach }))).toContain("If it fails the same way again, contact support");
});

test("a probe that throws leaves the message at the honest either/or", async () => {
  const real = globalThis.fetch;
  globalThis.fetch = (() => {
    throw new Error("stubbed fetch exploded synchronously");
  }) as unknown as typeof fetch;
  try {
    await expect(measureStorageReach("https://bucket.example/x")).rejects.toThrow();
  } finally {
    globalThis.fetch = real;
  }
});

test("a probe that never answers is capped, and reports nothing rather than guessing", async () => {
  const { reach, attempts } = await measureWith((attempt) => (attempt.mode === "no-cors" ? opaque() : never(attempt)), 25);
  expect(reach).toBe("unmeasured");
  expect(attempts).toHaveLength(2);
  expect(storageTransportSentence({ ...FACTS, reach })).toContain("Either this device has no connection");
});

test("a first probe that never answers is capped too, and is not reported as no route", async () => {
  const { reach, attempts } = await measureWith(never, 25);
  expect(reach).toBe("unmeasured");
  expect(attempts).toHaveLength(1);
});
