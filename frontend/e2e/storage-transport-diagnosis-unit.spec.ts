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

/**
 * "Object storage upload failed: network error" was a guess printed as a fact, and this spec is what
 * stops it coming back.
 *
 * THE INCIDENT. The media bucket's CORS rule was declared in `infra/terraform/main.tf` and never
 * applied, so `OPTIONS <bucket>/<key>` with `Access-Control-Request-Method: PUT` answered 403 and
 * every web upload died in `xhr.onerror`. A designer read "Upload failed - Object storage upload
 * failed: network error" on every tile and "Check your internet connection and try again - the record
 * was saved, so re-open it and re-attach the media" under the batch. The connection was fine: the
 * presign leg of the very same upload had answered seconds earlier over the same link. So the app
 * sent people out of a workshop looking for signal they already had, told them to do the identical
 * thing again, and named nothing that was actually wrong.
 *
 * WHAT IS PINNED HERE, IN THE ORDER IT MATTERS.
 *
 *   1. THE WORK SURVIVES. `StorageTransportError` must go on classifying as `unreachable` - the kind
 *      whose drain action is `stop-and-keep`. Every keep-the-bytes path in the client reads that one
 *      verdict, so a "sharper" classification would have turned a bucket misconfiguration into
 *      photographs being deleted. This is the first test in the file because it is the one whose
 *      failure is unrecoverable; the wording ones merely cost an afternoon.
 *   2. NO ARM LIES. Every sentence names a cause, and the one arm that cannot tell the two causes
 *      apart names BOTH plus the observation that separates them.
 *   3. THE MEASUREMENT IS A TABLE, NOT A HUNCH. `measureStorageReach` is driven against a stubbed
 *      `fetch` through all four of its outcomes, including the two that must NOT read as a refusal.
 *
 * These assertions need no browser and no bucket: they are about which sentence a person is shown
 * and which verdict decides whether their captures are kept, and both are decidable in Node.
 */

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

/* ----------------------------------------------------------------------------
 * 1. The work survives. Nothing below this line matters if this fails.
 * -------------------------------------------------------------------------- */

test("a transport failure is still `unreachable`, so the drains keep the designer's bytes", () => {
  for (const reach of REACHES) {
    const verdict = triageFailure(transport({ reach }));
    // `status` is null and stays null: no server decided anything about this object.
    expect(verdict.kind, reach).toBe("unreachable");
    expect(verdict.drain, reach).toBe(FAILURE_TRIAGE.unreachable.drain);
    expect(verdict.retry, reach).toBe(FAILURE_TRIAGE.unreachable.retry);
    // `components/designworkshop/FieldInput.tsx` answers a true here by writing the captured
    // photographs into the local draft store. A false there is not "report it honestly" - it is the
    // photograph being gone.
    expect(isTransient(transport({ reach })), reach).toBe(true);
  }
});

test("it is a StorageError, which is how it keeps that verdict rather than falling to a default", () => {
  // A subclass, deliberately: `kindForStorage` reads `status` off a `StorageError`, and a brand-new
  // error type would have reached `unreachable` by the classifier's default instead of by declaration.
  expect(transport() instanceof Error).toBe(true);
  expect(transport().status).toBe(null);
  expect(transport().name).toBe("StorageTransportError");
});

test("the measured error is visible through a MediaBatchError, and hides nothing behind it", () => {
  // `adviceForALostBatch` finds the transport error with `underlyingError`, which recurses to the
  // DEEPEST cause. So the sharpened error must not chain the unmeasured one underneath it: that would
  // hand every reader downstream the verdict the probe was spent to replace.
  const measured = transport({ reach: "site-refused" });
  expect((measured as Error & { cause?: unknown }).cause).toBe(undefined);
  const batch = new MediaBatchError("all 1 file(s) failed", [
    { name: "loom.jpg", error: measured.message, cause: measured }
  ]);
  expect(underlyingError(batch)).toBe(measured);
  expect(triageFailure(batch).kind).toBe("unreachable");
});

/* ----------------------------------------------------------------------------
 * 2. No arm lies
 * -------------------------------------------------------------------------- */

test("no arm says \"network error\", and every arm names the bucket and the site", () => {
  for (const reach of REACHES) {
    const sentence = storageTransportSentence({ ...FACTS, reach });
    expect(sentence, reach).not.toContain("network error");
    expect(sentence, reach).toContain(FACTS.bucketOrigin!);
  }
  // The two arms that are ABOUT the rule between two origins must name both ends of it - those exact
  // strings are what somebody has to put into the bucket's AllowedOrigins.
  for (const reach of ["unmeasured", "site-refused", "reads-allowed"] as StorageReach[]) {
    expect(storageTransportSentence({ ...FACTS, reach }), reach).toContain(FACTS.pageOrigin!);
  }
});

test("the unmeasured arm names BOTH causes and the observation that separates them", () => {
  // The honest either/or, and the sentence the old "network error" should always have been. It is
  // reached when no probe could run at all, so it may not pick a side.
  const sentence = storageTransportSentence({ ...FACTS, reach: "unmeasured" });
  expect(sentence).toContain("not one byte was sent");
  expect(sentence).toContain("either no network on this device");
  expect(sentence).toContain("refusing uploads from https://designer-repository.vercel.app");
  // The one check a designer can actually make: the presign leg of this upload answered moments ago,
  // so a page that is still working is proof the link is up and the fault is at the bucket.
  expect(sentence).toContain("If the rest of the app is still loading, it is the bucket");
});

test("a refusal is named as a setting, and does not offer a retry that cannot work", () => {
  const sentence = storageTransportSentence({ ...FACTS, reach: "site-refused" });
  expect(sentence).toContain("CORS rule does not cover this site");
  expect(sentence).toContain("rather than your connection");

  const advice = adviceForTransportFailure(transport({ reach: "site-refused" }));
  // THE CLAUSE THIS WHOLE CHANGE EXISTS FOR. "Check your internet connection" and "re-open it and
  // re-attach the media" are both instructions that cannot work while a bucket is refusing the site.
  expect(advice).not.toContain("Check your internet connection");
  expect(advice).not.toContain("re-attach the media.");
  expect(advice).toContain("still on this device");
  expect(advice).toContain("Keep them");
});

test("the refusal arm claims a setting, and stops short of promising the upload cannot work", () => {
  /*
    THE SECOND VERSION OF THE FIRST MISTAKE, AND WHY IT IS PINNED SEPARATELY.

    This arm first shipped ending "It is a setting on the bucket, not your connection: retrying
    cannot clear it", with an advice clause promising "re-attaching them now will fail in exactly the
    same way". Both are guarantees about a PUT, and `measureStorageReach` sends two GETs. An S3 CORS
    rule matches on the METHOD as well as the origin, so `AllowedMethods: ["PUT"]` — a rule under
    which this site's uploads work perfectly — refuses the read probe and lands here. Measured in
    Chromium against a server carrying exactly that rule: the probe answered `site-refused` while a
    real cross-origin PUT from the same page returned 200.

    On such a bucket the first ordinary blip that killed a PUT before a byte moved — a dead spot, a
    reset connection, the field case this product is built for — was reported as a permanent
    misconfiguration and the designer told to stop trying. That is "network error" again with the
    blame moved: a certainty printed from a measurement that does not carry it.

    This is the ONE clause on the screen whose over-claim costs an upload rather than an attempt,
    because it is the only one that asks somebody to STOP. So the words that assert certainty are
    asserted absent here, by exact string, and the words that carry the actionable half are asserted
    present. Widening either sentence back means measuring a PUT first.
  */
  const sentence = storageTransportSentence({ ...FACTS, reach: "site-refused" });
  const advice = adviceForTransportFailure(transport({ reach: "site-refused" }));
  for (const claim of ["retrying cannot clear it", "will fail in exactly the same way", "cannot be uploaded"]) {
    expect(sentence, claim).not.toContain(claim);
    expect(advice, claim).not.toContain(claim);
  }
  // What the probe DID establish, which is the whole value of the arm: the host answered this device
  // and would not answer this page, so a person should be looking at the bucket and not at the signal.
  expect(sentence).toContain("is reachable from this device but refused to answer");
  expect(sentence).toContain("unlikely to help");
  expect(advice).toContain("may well fail the same way");
  // And it still never sends anybody hunting for signal - that is the defect the arm exists for.
  expect(sentence).not.toContain("connection and try again");
});

test("bytes on the wire exonerate the bucket, with no probe spent to prove it", () => {
  // A refused preflight fails before the body is sent, so `xhr.upload.onprogress` never fires. One
  // boolean, zero requests, and it decides the sentence on its own - the reach is not even consulted.
  for (const reach of REACHES) {
    const sentence = storageTransportSentence({ ...FACTS, reach, bytesMoved: true, sentBytes: 1_048_576 });
    expect(sentence, reach).toContain("1.0 MB of 4.0 MB");
    expect(sentence, reach).toContain("this is the connection and not a setting");
    expect(sentence, reach).not.toContain("CORS");
  }
  expect(adviceForTransportFailure(transport({ bytesMoved: true, sentBytes: 1 }))).toContain(
    "once the signal is steady"
  );
});

test("a genuinely unreachable host is still told it is the connection", () => {
  // The fix must not overcorrect: when the probe says the host cannot be reached at all, "check your
  // internet connection" is the true sentence and must survive.
  expect(storageTransportSentence({ ...FACTS, reach: "no-route" })).toContain("cannot reach");
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
  // MinIO behind a proxy, a relative URL, a test double: `new URL` throws and both origins are null.
  const sentence = storageTransportSentence({ ...FACTS, bucketOrigin: null, pageOrigin: null });
  expect(sentence).toContain("the storage bucket");
  expect(sentence).toContain("this site");
  expect(sentence).not.toContain("null");
});

/* ----------------------------------------------------------------------------
 * 3. The measurement is a table
 * -------------------------------------------------------------------------- */

type Attempt = { url: string; mode?: string; signal?: AbortSignal | null };

/**
 * Drive `measureStorageReach` against a stubbed `fetch`, recording what it asked for.
 *
 * THE STUB CARRIES `signal` THROUGH, and that is not decoration: the cap is implemented by aborting
 * the in-flight request, so a stub that ignored the signal would hang for ever on the very tests that
 * exist to prove nothing hangs. A double that cannot fail the way the real thing fails is a double
 * that pins nothing — see {@link never}.
 */
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

/** An opaque response is what a `no-cors` fetch resolves with; it carries no status a page may read. */
const opaque = () => Promise.resolve(new Response(null, { status: 200 }));
/** What a CORS-checked fetch does when the response carries no `Access-Control-Allow-Origin`. */
const blocked = () => Promise.reject(new TypeError("Failed to fetch"));
/**
 * A host that takes the connection and never answers — a black-holed route, a captive portal. It
 * settles only when the caller aborts, exactly as a real `fetch` does, which is what makes the cap
 * observable at all.
 */
const never = (attempt: Attempt) =>
  new Promise<Response>((_resolve, reject) => {
    attempt.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
  });

test("nothing came back at all: the connection, and nothing is blamed on the bucket", async () => {
  // `mode: "no-cors"` is not subject to CORS, so it rejects ONLY when the network genuinely failed.
  const { reach, attempts } = await measureWith(blocked);
  expect(reach).toBe("no-route");
  // One request, not two: there is nothing left to ask once the host is unreachable.
  expect(attempts).toHaveLength(1);
  expect(attempts[0].mode).toBe("no-cors");
});

test("the host answers but will not answer THIS site: the bucket's rule, named", async () => {
  const { reach, attempts } = await measureWith((attempt) => (attempt.mode === "no-cors" ? opaque() : blocked()));
  expect(reach).toBe("site-refused");
  expect(attempts).toHaveLength(2);
  // BOTH are GETs on purpose. S3 attaches the CORS response headers only when the method matches an
  // `AllowedMethods` entry, so a HEAD probe would read a rule listing GET-and-not-HEAD as a refusal.
  expect(attempts.every((attempt) => attempt.url === "https://bucket.example/media/u/1/x.jpg")).toBe(true);
});

test("a 403 the page is ALLOWED to read is not a refusal of the site", async () => {
  // The probe URL is unsigned, so S3 answers it 403 or 404. What is being measured is whether the
  // browser may READ the answer, not what the answer says - a CORS-checked fetch resolves either way.
  const { reach } = await measureWith(() => Promise.resolve(new Response("<Error/>", { status: 403 })));
  expect(reach).toBe("reads-allowed");
});

test("reads allowed rules out both headline causes without pretending to have found a third", async () => {
  const { reach } = await measureWith(opaque);
  expect(reach).toBe("reads-allowed");
  // A rule that allows GET and not PUT answers both probes. That case gets its own sentence rather
  // than being folded into the either/or, because two of the three candidates HAVE been ruled out.
  const sentence = storageTransportSentence({ ...FACTS, reach });
  expect(sentence).toContain("neither a lost connection nor a missing CORS rule");
  expect(adviceForTransportFailure(transport({ reach }))).toContain("the bucket's upload rule");
});

test("a probe that throws leaves the message at the honest either/or", async () => {
  const real = globalThis.fetch;
  globalThis.fetch = (() => {
    throw new Error("stubbed fetch exploded synchronously");
  }) as unknown as typeof fetch;
  try {
    // The caller wraps this in `.catch(() => "unmeasured")`; what is pinned here is that a broken
    // probe can only ever cost the diagnosis, never turn a failed upload into an unhandled rejection.
    await expect(measureStorageReach("https://bucket.example/x")).rejects.toThrow();
  } finally {
    globalThis.fetch = real;
  }
});

test("a probe that never answers is capped, and reports nothing rather than guessing", async () => {
  /*
    THE HANG THIS SECTION WOULD OTHERWISE HAVE INTRODUCED. `fetch` has no default timeout, and the
    failure this probe runs after is the one most likely to make it hang - a black-holed route or a
    captive portal takes the connection and never answers. The diagnosis is awaited before the
    upload's error reaches the screen, so an unbounded probe turns a clear, immediate failure into a
    long silence: exactly the trade the stall watchdog in `putBlob` exists to refuse, reintroduced by
    the code meant to explain it.

    AND THE CAP MUST NOT PRODUCE A VERDICT. A capped second probe is indistinguishable from a refused
    one, so reading a timeout as "site-refused" would put a new confident lie where the old one was.
  */
  const { reach, attempts } = await measureWith((attempt) => (attempt.mode === "no-cors" ? opaque() : never(attempt)), 25);
  expect(reach).toBe("unmeasured");
  expect(attempts).toHaveLength(2);
  // Falls back to the either/or, which is true of a probe that measured nothing.
  expect(storageTransportSentence({ ...FACTS, reach })).toContain("either no network on this device");
});

test("a first probe that never answers is capped too, and is not reported as no route", async () => {
  // A capped fetch also reports "did not reach", so the abort has to be checked before that is read.
  // Getting this order wrong blames a connection the probe never actually measured.
  const { reach, attempts } = await measureWith(never, 25);
  expect(reach).toBe("unmeasured");
  expect(attempts).toHaveLength(1);
});
