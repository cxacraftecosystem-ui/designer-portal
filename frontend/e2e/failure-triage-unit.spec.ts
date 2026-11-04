import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError, ApiUnconfiguredError } from "@/lib/api";
import {
  ANDROID_DIVERGENCE,
  FAILURE_TRIAGE,
  LocalRefusalError,
  StorageError,
  isCredentialExpiry,
  isSchemaRefusal,
  isTransient,
  isUnreachable,
  schemaRefusalError,
  serverAskedForTime,
  triageAsThrown,
  triageFailure,
  underlyingError,
  underlyingIsTransient,
  type FailureKind
} from "@/lib/failureTriage";
import { MediaBatchError } from "@/lib/media";
import * as offline from "@/lib/offline";

const FRONTEND = join(__dirname, "..");

function sourceFiles(): string[] {
  const found: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === "node_modules" || entry.name === ".next") continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (/\.tsx?$/.test(entry.name) && !entry.name.endsWith(".spec.ts")) found.push(full);
    }
  };
  for (const top of ["lib", "app", "components"]) walk(join(FRONTEND, top));
  return found;
}

const EXTRA_FORBIDDEN = {
  detail: [{ type: "extra_forbidden", loc: ["body", "merge"], msg: "Extra inputs are not permitted" }]
};

function asBatchFailure(error: unknown, name = "loom.jpg"): MediaBatchError {
  return new MediaBatchError(`All 1 media file(s) failed to upload (${name}).`, [
    { name, error: error instanceof Error ? error.message : String(error), cause: error }
  ]);
}

const MATRIX: Array<{ what: string; error: () => unknown; kind: FailureKind }> = [
  { what: "a dropped connection", error: () => new TypeError("Failed to fetch"), kind: "unreachable" },
  { what: "an aborted request", error: () => Object.assign(new Error("The operation was aborted."), { name: "AbortError" }), kind: "unreachable" },
  { what: "a thrown non-error", error: () => "not an error at all", kind: "unreachable" },
  { what: "a bare Error with nothing on it", error: () => new Error("something went wrong"), kind: "unreachable" },
  { what: "408, a proxy saying the request never completed", error: () => new ApiError(408, "timeout", null), kind: "unreachable" },

  { what: "500, the server reached and broken", error: () => new ApiError(500, "boom", null), kind: "transient" },
  { what: "502 behind a load balancer", error: () => new ApiError(502, "bad gateway", null), kind: "transient" },
  { what: "503 in a deploy window", error: () => new ApiError(503, "unavailable", null), kind: "transient" },
  { what: "504 from a slow upstream", error: () => new ApiError(504, "gateway timeout", null), kind: "transient" },
  { what: "429, the server asking for time", error: () => new ApiError(429, "slow down", null), kind: "transient" },

  { what: "401 on an expired token", error: () => new ApiError(401, "Could not validate credentials", null), kind: "credential-expired" },

  { what: "422 naming a key the server does not know", error: () => new ApiError(422, "merge: Extra inputs are not permitted", EXTRA_FORBIDDEN), kind: "schema-drift" },

  { what: "400 on a malformed request", error: () => new ApiError(400, "bad request", null), kind: "refused" },
  { what: "403 on a permission", error: () => new ApiError(403, "Not permitted", null), kind: "refused" },
  { what: "404 on a record that is gone", error: () => new ApiError(404, "Record not found", null), kind: "refused" },
  { what: "409 on a clash", error: () => new ApiError(409, "That Aadhaar is already registered", null), kind: "refused" },
  { what: "413 on a file over the limit", error: () => new ApiError(413, "Payload too large", null), kind: "refused" },
  { what: "415 on a container the server will not take", error: () => new ApiError(415, "Unsupported media type", null), kind: "refused" },
  { what: "422 on a field a validator rejected", error: () => new ApiError(422, "amount: not a valid number", { detail: [{ type: "decimal_parsing", loc: ["body", "amount"], msg: "…" }] }), kind: "refused" },

  { what: "a 0-byte file", error: () => new LocalRefusalError('"capture-0001.jpg" is empty (0 bytes) — there is nothing to upload.'), kind: "permanent" },

  { what: "a bucket refusing the signature (S3 403)", error: () => new StorageError("Object storage upload failed: HTTP 403", 403), kind: "refused" },
  { what: "an object over the bucket's size limit (S3 413)", error: () => new StorageError("Object storage upload failed: HTTP 413", 413), kind: "refused" },
  { what: "object storage in a deploy window (S3 503)", error: () => new StorageError("Object storage upload failed: HTTP 503", 503), kind: "transient" },
  { what: "object storage asking for time (S3 429)", error: () => new StorageError("Object storage upload failed: HTTP 429", 429), kind: "transient" },
  { what: "a transfer that stalled with no answer", error: () => new StorageError("Object storage upload stalled — no data moved for 90s"), kind: "unreachable" }
];

test("every failure sorts to exactly one row of the table, bare", () => {
  for (const row of MATRIX) {
    expect(triageFailure(row.error()).kind, row.what).toBe(row.kind);
  }
});

test("and to the SAME row when it arrives wrapped, which is how both drains meet it", () => {
  for (const row of MATRIX) {
    expect(triageFailure(asBatchFailure(row.error())).kind, `${row.what}, wrapped`).toBe(row.kind);
  }
});

test("a wrapper around a wrapper is still classified by what is at the bottom", () => {
  const twice = new Error("the upload gave up", { cause: asBatchFailure(new ApiError(415, "Unsupported media type", null)) });
  expect(triageFailure(twice).kind).toBe("refused");
  expect(triageFailure(twice).status).toBe(415);
});

test("a cause that points at its own error terminates rather than exhausting the stack", () => {
  const loop = new Error("round and round") as Error & { cause?: unknown };
  loop.cause = loop;
  expect(triageFailure(loop).kind).toBe("unreachable");
  expect(underlyingError(loop)).toBe(loop);
});

test("the unwrap stops at the server's answer and returns everything else unchanged", () => {
  const answered = new ApiError(415, "Unsupported media type", null);
  expect(underlyingError(asBatchFailure(answered))).toBe(answered);
  expect(underlyingError(answered), "an unwrapped error is returned unchanged").toBe(answered);
  expect(underlyingError("not an error at all")).toBe("not an error at all");
});

test("408 is the connection failing AND worth retrying, which one axis cannot say", () => {
  const timeout = new ApiError(408, "timeout", null);
  expect(isUnreachable(timeout), "nothing was decided, so a screen may say offline").toBe(true);
  expect(isTransient(timeout), "and the work is kept and retried").toBe(true);
});

test("a 5xx is worth retrying and is NOT the connection, which is the other half of it", () => {
  const broken = new ApiError(500, "ReportMeta has no __dict__", null);
  expect(isTransient(broken)).toBe(true);
  expect(isUnreachable(broken), "the server answered — do not blame their signal").toBe(false);
});

test("429 is the server asking for time; 503 is the server being broken; both are retried, one is named", () => {
  expect(serverAskedForTime(new ApiError(429, "slow down", null))).toBe(true);
  expect(serverAskedForTime(new ApiError(408, "timeout", null))).toBe(true);
  expect(serverAskedForTime(new ApiError(503, "unavailable", null)), "a deploy window is not a rate limit").toBe(false);
  expect(serverAskedForTime(new TypeError("Failed to fetch")), "and nothing answered at all is neither").toBe(false);
  expect(serverAskedForTime(asBatchFailure(new ApiError(429, "slow down", null)))).toBe(true);
});

test("each predicate is exactly its row of the table and nothing else", () => {
  for (const row of MATRIX) {
    for (const shape of [row.error(), asBatchFailure(row.error())]) {
      const opened = triageFailure(shape);
      const asThrown = triageAsThrown(shape);
      expect(isUnreachable(shape), `${row.what}: isUnreachable`).toBe(opened.kind === "unreachable");
      expect(isCredentialExpiry(shape), `${row.what}: isCredentialExpiry`).toBe(opened.kind === "credential-expired");
      expect(isSchemaRefusal(shape), `${row.what}: isSchemaRefusal`).toBe(opened.kind === "schema-drift");
      expect(underlyingIsTransient(shape), `${row.what}: underlyingIsTransient`).toBe(
        opened.retry === "connection" || opened.retry === "later"
      );
      expect(isTransient(shape), `${row.what}: isTransient`).toBe(
        asThrown.retry === "connection" || asThrown.retry === "later"
      );
    }
  }
});

test("the two readings of 'worth retrying' are one table asked at two depths", () => {
  for (const row of MATRIX) {
    const bare = row.error();
    expect(isTransient(bare), `${row.what}: nothing to unwrap, so the two must agree`).toBe(
      underlyingIsTransient(bare)
    );
    expect(triageAsThrown(bare).kind, `${row.what}: and so must the verdicts they read`).toBe(triageFailure(bare).kind);
  }
  const refused = asBatchFailure(new ApiError(415, "Unsupported media type", null));
  expect(isTransient(refused), "as thrown, an unreadable wrapper is 'keep the work'").toBe(true);
  expect(underlyingIsTransient(refused), "opened, it is the 415 the server sent").toBe(false);
  const dropped = asBatchFailure(new TypeError("Failed to fetch"));
  expect(isTransient(dropped), "and where the wrapper hides no answer they agree again").toBe(
    underlyingIsTransient(dropped)
  );
});

test("FieldInput's media catch is still the conservative caller this pair exists for", () => {
  const source = readFileSync(join(FRONTEND, "components", "designworkshop", "FieldInput.tsx"), "utf8");
  expect(
    source.includes("if (isTransient(err))"),
    "FieldInput's media catch changed shape — read this test's comment before changing the predicate pair"
  ).toBe(true);
  expect(
    source.includes("underlyingIsTransient"),
    "FieldInput now unwraps: make sure its catch keeps the bytes on a refusal first, then release the pair"
  ).toBe(false);
});

test("the table has a row for every kind, and every row is reachable", () => {
  const reached = new Set(MATRIX.map((row) => row.kind));
  for (const kind of Object.keys(FAILURE_TRIAGE) as FailureKind[]) {
    expect(FAILURE_TRIAGE[kind].kind, "each row names itself, so a copy-paste cannot mislabel one").toBe(kind);
    expect(FAILURE_TRIAGE[kind].meaning.length, `${kind} says what it IS`).toBeGreaterThan(40);
    expect(reached.has(kind), `${kind} has no failure in the matrix that reaches it`).toBe(true);
  }
});

test("nothing a drain marks against an item is also something it retries by itself", () => {
  for (const row of Object.values(FAILURE_TRIAGE)) {
    const marksTheItem = row.drain === "record-against-the-item" || row.drain === "record-but-retry-next-run";
    const worthRetryingNow = row.retry === "connection" || row.retry === "later";
    if (worthRetryingNow) {
      expect(marksTheItem, `${row.kind}: a drain that retries an item must not also mark it refused`).toBe(false);
    }
    if (marksTheItem) {
      expect(worthRetryingNow, `${row.kind}: a drain that marks an item must not also retry it`).toBe(false);
    }
  }
});

test("only a failure nothing answered may put the word offline on a screen", () => {
  for (const row of Object.values(FAILURE_TRIAGE)) {
    if (row.screen === "say-offline") expect(row.kind, "say-offline belongs to one kind").toBe("unreachable");
    if (row.kind === "unreachable") expect(row.screen).toBe("say-offline");
  }
});

test("a refusal wrapped in a MediaBatchError is a refusal to EVERY classifier, not just to one", () => {
  const refused = asBatchFailure(new ApiError(415, "Unsupported media type", null), "loom.mp4");
  expect(isUnreachable(refused)).toBe(false);
  expect(underlyingIsTransient(refused), "this answered TRUE before the unwrap was made shared").toBe(false);
  expect(isCredentialExpiry(refused)).toBe(false);
  expect(isSchemaRefusal(refused)).toBe(false);
  expect(isTransient(refused), "except the conservative reading, deliberately — see the pair test").toBe(true);
});

test("a dialect mismatch met while uploading a photograph is read as a dialect mismatch", () => {
  const inner = new ApiError(422, "merge: Extra inputs are not permitted", EXTRA_FORBIDDEN);
  const wrapped = asBatchFailure(inner, "a.jpg");
  expect(isSchemaRefusal(wrapped)).toBe(true);
  expect(schemaRefusalError(wrapped), "and the caller gets the sentence naming the key").toBe(inner);
  expect(schemaRefusalError(new ApiError(422, "amount: not a valid number", null)), "a field a validator rejected is not drift").toBeNull();
  expect(schemaRefusalError(new TypeError("Failed to fetch"))).toBeNull();
});

test("a 0-byte file has a type now, and it is not 'the connection is at fault'", () => {
  const empty = new LocalRefusalError('"capture-0001.jpg" is empty (0 bytes) — there is nothing to upload.');
  expect(triageFailure(empty).kind).toBe("permanent");
  expect(isUnreachable(empty), "no connection can change it").toBe(false);
  expect(isTransient(empty), "and retrying it for ever is the jam").toBe(false);
  expect(triageFailure(asBatchFailure(empty)).kind, "wrapped, which is how a drain meets it").toBe("permanent");

  expect(isUnreachable(new Error('"capture-0001.jpg" is empty (0 bytes) — there is nothing to upload.'))).toBe(true);
});

test("a site published with no API address keeps its queued work and still tells the person", () => {
  const unconfigured = new ApiUnconfiguredError();
  expect(triageFailure(unconfigured).kind).toBe("transient");
  expect(isTransient(unconfigured), "the work is kept").toBe(true);
  expect(isUnreachable(unconfigured), "but it is not the visitor's signal, and must not be reported as one").toBe(false);
});

test("the classifiers `lib/offline.ts` exports ARE these, not copies of them", () => {
  expect(offline.isTransient).toBe(isTransient);
  expect(offline.underlyingIsTransient).toBe(underlyingIsTransient);
  expect(offline.isUnreachable).toBe(isUnreachable);
  expect(offline.isSchemaRefusal).toBe(isSchemaRefusal);
  expect(offline.isCredentialExpiry).toBe(isCredentialExpiry);
  expect(offline.underlyingError).toBe(underlyingError);

  const importers = sourceFiles().filter((file) => {
    const name = relative(FRONTEND, file).replace(/\\/g, "/");
    if (name === "lib/offline.ts" || name === "lib/failureTriage.ts") return false;
    const source = readFileSync(file, "utf8");
    return /import\s*\{[^}]*\b(?:isTransient|underlyingIsTransient|isUnreachable|isSchemaRefusal|isCredentialExpiry|underlyingError|schemaRefusalError|serverAskedForTime)\b[^}]*\}\s*from\s*"@\/lib\/(?:offline|failureTriage)"/.test(
      source
    );
  });
  expect(importers.length, "the classifiers are imported across the client, which is why they are shared").toBeGreaterThanOrEqual(10);
});

const MAY_TOUCH_A_CAUSE: Record<string, string> = {
  "lib/failureTriage.ts": "the one unwrap — `underlyingError`, bounded, and every classifier reads its result",
  "lib/media.ts": "SETS it: `new MediaBatchError(…, { cause: failures[0]?.cause })` on the batch escalation"
};

test("`cause` is touched in two places in the whole web client, and read in one", () => {
  const readers = new Map<string, string[]>();
  for (const file of sourceFiles()) {
    const name = relative(FRONTEND, file).replace(/\\/g, "/");
    readFileSync(file, "utf8")
      .split("\n")
      .forEach((line, index) => {
        const code = line.trimStart();
        if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return;
        if (!/\.cause\b/.test(line) && !/\{[^{}]*\bcause\b[^{}]*\}\s*=/.test(line)) return;
        readers.set(name, [...(readers.get(name) ?? []), `${name}:${index + 1}`]);
      });
  }
  const unexpected = [...readers.keys()].filter((name) => !(name in MAY_TOUCH_A_CAUSE)).sort();
  expect(
    unexpected,
    "only lib/failureTriage.ts may unwrap a cause — add a reasoned entry to MAY_TOUCH_A_CAUSE if a file must set one"
  ).toEqual([]);
  expect([...readers.keys()], "the unwrap itself is still here, so this sweep is looking at real code").toContain(
    "lib/failureTriage.ts"
  );
  const readsACause = (line: string) => /\.cause\b/.test(line) || /\{[^{}]*\bcause\b[^{}]*\}\s*=/.test(line);
  expect(readsACause("const c = e.cause;"), "a plain property read").toBe(true);
  expect(readsACause("const { cause } = err as { cause?: unknown };"), "a destructure").toBe(true);
  expect(readsACause("if (failure.cause instanceof ApiError) {"), "and the original three still match").toBe(true);
  expect(readsACause("super(message, { cause: failures[0]?.cause });"), "the setter reads one too — hence the list").toBe(true);
  expect(readsACause("throw new MediaBatchError(message, failures);"), "and constructing one without a cause is not a read").toBe(false);
});

const HAND_ROLLED_NETWORK_TESTS = [

  "lib/dataExplorer/engine.ts",

  "lib/media.ts",

  "lib/questionnaire/queue.ts"
];

test("no NEW surface invents another answer to 'is this the network'", () => {
  const offenders = new Set<string>();
  for (const file of sourceFiles()) {
    const name = relative(FRONTEND, file).replace(/\\/g, "/");
    if (name === "lib/failureTriage.ts") continue;
    for (const line of readFileSync(file, "utf8").split("\n")) {
      const code = line.trimStart();
      if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) continue;
      const comparesAStatus = /(?<!verdict\.)\bstatus\s*(?:===|!==|==|>=|<=|>|<)\s*(?:408|429|5\d\d)\b/i;
      const declaresAStatusSet =
        /\b[A-Za-z_$][\w$]*(?:CODES?|STATUS(?:ES)?)\b[^=\n]*=\s*(?:new\s+Set\s*\(\s*)?\[[^\]]*\b(?:408|429|5\d\d)\b/i;
      if (comparesAStatus.test(line) || declaresAStatusSet.test(line)) offenders.add(name);
    }
  }
  const unexpected = [...offenders].filter((name) => !HAND_ROLLED_NETWORK_TESTS.includes(name)).sort();
  expect(
    unexpected,
    "these decide for themselves what 408/429/5xx mean — call lib/failureTriage.ts instead, or add a " +
      "reasoned entry to HAND_ROLLED_NETWORK_TESTS above saying why this one is different"
  ).toEqual([]);
  const comparesAStatus = /(?<!verdict\.)\bstatus\s*(?:===|!==|==|>=|<=|>|<)\s*(?:408|429|5\d\d)\b/i;
  const declaresAStatusSet =
    /\b[A-Za-z_$][\w$]*(?:CODES?|STATUS(?:ES)?)\b[^=\n]*=\s*(?:new\s+Set\s*\(\s*)?\[[^\]]*\b(?:408|429|5\d\d)\b/i;
  for (const spelling of [
    "if (err.status === 408) return NO_CONNECTION;",
    "if (status === 503 || status === 500) return true;",
    "if (error.status !== 401 && error.status < 500) forget();",
    "if (err instanceof ApiError && err.status >= 500) {",
    "if (e.status == 429) wait();"
  ]) {
    expect(comparesAStatus.test(spelling), `a status rule spelled "${spelling}" must be visible`).toBe(true);
  }
  for (const spelling of [
    "const RETRIABLE_GATEWAY_CODES = new Set([502, 503, 504]);",
    "const RETRY_STATUSES = [408, 429];"
  ]) {
    expect(declaresAStatusSet.test(spelling), `a status TABLE spelled "${spelling}" must be visible`).toBe(true);
  }
  expect(comparesAStatus.test("if (verdict.status === 429) return true;"), "reading the shared verdict is fine").toBe(
    false
  );
  expect(comparesAStatus.test("if (xhr.status >= 200 && xhr.status < 300) {"), "2xx is not a network rule").toBe(false);
});

test("the web does NOT fold 401 into isTransient, and the divergence is recorded as intentional", () => {
  const expired = new ApiError(401, "Could not validate credentials", null);
  expect(isTransient(expired), "the web keeps 401 out of isTransient").toBe(false);
  expect(isCredentialExpiry(expired), "and asks the question separately").toBe(true);
  expect(isUnreachable(expired), "a 401 is an answer, so it is not offline either").toBe(false);
  expect(isCredentialExpiry(asBatchFailure(expired))).toBe(true);

  expect(ANDROID_DIVERGENCE.status).toBe(401);
  expect(ANDROID_DIVERGENCE.web).toBe("credential-expired");
  expect(FAILURE_TRIAGE["credential-expired"].drain).toBe("stop-and-ask-for-sign-in");
});

test("403 is not swallowed by 401 — a different sentence with a different remedy", () => {
  expect(isCredentialExpiry(new ApiError(403, "Not permitted", null))).toBe(false);
  expect(triageFailure(new ApiError(403, "Not permitted", null)).kind).toBe("refused");
});
