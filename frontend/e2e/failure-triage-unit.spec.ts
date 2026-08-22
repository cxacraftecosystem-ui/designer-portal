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

/**
 * "IS THIS THE NETWORK?" — THE WHOLE MATRIX, PINNED.
 *
 * ── WHAT THIS FILE IS FOR, AND WHY IT IS NOT ANOTHER REGRESSION TEST ────────────────────────────
 *
 * The 2026-08 audit closed a media failure that jammed the outbox for ever, a 401 that left only a
 * button which deletes the work, and an unreadable queue that rendered as an empty one. Every one of
 * them was the same shape: TWO functions in this repository answering "is this the network" and
 * disagreeing. `isTransient` did not follow `cause` and `isUnreachable`, eight lines below it, did.
 * `isSchemaRefusal` required an `ApiError`, so a wrapped one slipped past. `lib/designWorkshopStore.ts`
 * held another inline, written out twice. Android holds one more, deliberately — five in all.
 *
 * Each instance was fixed where it was found, which is why this file does not test the instances. It
 * tests the CONTRACT: one classification, one unwrap, and a table of what every failure IS and what
 * each surface does about it. If somebody adds another answer, the row it disagrees with fails here
 * rather than in a village a fortnight from now.
 *
 * The assertions are on classifiers and error types, not on a browser. That is not a shortcut — this
 * is precisely where the decisions are made, and they are decidable without one.
 */

const FRONTEND = join(__dirname, "..");

/** Every TypeScript source of the web client. Specs are excluded: they are allowed to name statuses. */
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

/** A 422 body shaped exactly as `APIModel`'s `extra="forbid"` produces one. */
const EXTRA_FORBIDDEN = {
  detail: [{ type: "extra_forbidden", loc: ["body", "merge"], msg: "Extra inputs are not permitted" }]
};

/** The same status, wrapped the way `uploadMediaBatch` escalates a batch that landed nothing. */
function asBatchFailure(error: unknown, name = "loom.jpg"): MediaBatchError {
  return new MediaBatchError(`All 1 media file(s) failed to upload (${name}).`, [
    { name, error: error instanceof Error ? error.message : String(error), cause: error }
  ]);
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The matrix — every failure a request can produce, bare and wrapped
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Each row is one failure and the kind it must sort to. The WRAPPED column is not decoration: the
 * two drains upload ONE file per `uploadMediaBatch` call, so for them every media failure arrives
 * inside a `MediaBatchError` and "the whole batch failed" is precisely "the server refused this
 * photograph". A classifier that answers differently to the two columns is the defect, restated.
 */
const MATRIX: Array<{ what: string; error: () => unknown; kind: FailureKind }> = [
  // Nothing reached a server.
  { what: "a dropped connection", error: () => new TypeError("Failed to fetch"), kind: "unreachable" },
  { what: "an aborted request", error: () => Object.assign(new Error("The operation was aborted."), { name: "AbortError" }), kind: "unreachable" },
  { what: "a thrown non-error", error: () => "not an error at all", kind: "unreachable" },
  { what: "a bare Error with nothing on it", error: () => new Error("something went wrong"), kind: "unreachable" },
  { what: "408, a proxy saying the request never completed", error: () => new ApiError(408, "timeout", null), kind: "unreachable" },

  // The server answered, and the answer means "not now".
  { what: "500, the server reached and broken", error: () => new ApiError(500, "boom", null), kind: "transient" },
  { what: "502 behind a load balancer", error: () => new ApiError(502, "bad gateway", null), kind: "transient" },
  { what: "503 in a deploy window", error: () => new ApiError(503, "unavailable", null), kind: "transient" },
  { what: "504 from a slow upstream", error: () => new ApiError(504, "gateway timeout", null), kind: "transient" },
  { what: "429, the server asking for time", error: () => new ApiError(429, "slow down", null), kind: "transient" },

  // The credential, not the item.
  { what: "401 on an expired token", error: () => new ApiError(401, "Could not validate credentials", null), kind: "credential-expired" },

  // The two builds disagree about the shape.
  { what: "422 naming a key the server does not know", error: () => new ApiError(422, "merge: Extra inputs are not permitted", EXTRA_FORBIDDEN), kind: "schema-drift" },

  // The server answered and refused.
  { what: "400 on a malformed request", error: () => new ApiError(400, "bad request", null), kind: "refused" },
  { what: "403 on a permission", error: () => new ApiError(403, "Not permitted", null), kind: "refused" },
  { what: "404 on a record that is gone", error: () => new ApiError(404, "Record not found", null), kind: "refused" },
  { what: "409 on a clash", error: () => new ApiError(409, "That Aadhaar is already registered", null), kind: "refused" },
  { what: "413 on a file over the limit", error: () => new ApiError(413, "Payload too large", null), kind: "refused" },
  { what: "415 on a container the server will not take", error: () => new ApiError(415, "Unsupported media type", null), kind: "refused" },
  { what: "422 on a field a validator rejected", error: () => new ApiError(422, "amount: not a valid number", { detail: [{ type: "decimal_parsing", loc: ["body", "amount"], msg: "…" }] }), kind: "refused" },

  // This device refused, with no server involved at all.
  { what: "a 0-byte file", error: () => new LocalRefusalError('"capture-0001.jpg" is empty (0 bytes) — there is nothing to upload.'), kind: "permanent" },

  // OBJECT STORAGE, which is a second server with its own vocabulary and used to have no row at all.
  // `lib/media.ts` PUTs straight to S3/MinIO, so none of these is ever an `ApiError`; before they
  // were declared here every one of them fell to the default and the drain re-threw an S3 403 for
  // ever under "Still no connection".
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
    // A wrapper carries the file's name and a friendlier sentence. It must carry no opinion.
    expect(triageFailure(asBatchFailure(row.error())).kind, `${row.what}, wrapped`).toBe(row.kind);
  }
});

test("a wrapper around a wrapper is still classified by what is at the bottom", () => {
  // Not hypothetical in shape: `MediaBatchError` sets `cause` from the first per-file failure, and a
  // per-file failure's own `cause` is whatever `linkOrUpload` re-threw.
  const twice = new Error("the upload gave up", { cause: asBatchFailure(new ApiError(415, "Unsupported media type", null)) });
  expect(triageFailure(twice).kind).toBe("refused");
  expect(triageFailure(twice).status).toBe(415);
});

test("a cause that points at its own error terminates rather than exhausting the stack", () => {
  // `cause` is an ordinary property and nothing stops it forming a cycle.
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

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The two axes, which are not one axis
 * ──────────────────────────────────────────────────────────────────────────── */

test("408 is the connection failing AND worth retrying, which one axis cannot say", () => {
  const timeout = new ApiError(408, "timeout", null);
  expect(isUnreachable(timeout), "nothing was decided, so a screen may say offline").toBe(true);
  expect(isTransient(timeout), "and the work is kept and retried").toBe(true);
});

test("a 5xx is worth retrying and is NOT the connection, which is the other half of it", () => {
  const broken = new ApiError(500, "ReportMeta has no __dict__", null);
  // The real one: a saved page size 500'd and was reported as an offline problem, which sends a
  // designer out of the building to look for signal they already have.
  expect(isTransient(broken)).toBe(true);
  expect(isUnreachable(broken), "the server answered — do not blame their signal").toBe(false);
});

test("429 is the server asking for time; 503 is the server being broken; both are retried, one is named", () => {
  expect(serverAskedForTime(new ApiError(429, "slow down", null))).toBe(true);
  expect(serverAskedForTime(new ApiError(408, "timeout", null))).toBe(true);
  expect(serverAskedForTime(new ApiError(503, "unavailable", null)), "a deploy window is not a rate limit").toBe(false);
  expect(serverAskedForTime(new TypeError("Failed to fetch")), "and nothing answered at all is neither").toBe(false);
  // Wrapped, because `mediaRefusal` in the draft store is handed a one-file batch's escalation.
  expect(serverAskedForTime(asBatchFailure(new ApiError(429, "slow down", null)))).toBe(true);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The named readings agree with the table, always
 * ──────────────────────────────────────────────────────────────────────────── */

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
      // `isTransient` reads the SAME table at the SAME depth as `triageAsThrown` — see the pair test
      // below for why that depth is different, and for the guard on the pair itself.
      expect(isTransient(shape), `${row.what}: isTransient`).toBe(
        asThrown.retry === "connection" || asThrown.retry === "later"
      );
    }
  }
});

test("the two readings of 'worth retrying' are one table asked at two depths", () => {
  /*
    THE PAIR, AND THE GUARD ON IT. Two functions answering "is this the network" is the defect class
    this module closes, and it now ships two on purpose. What makes that a split rather than a relapse
    is that neither has an implementation of its own: both read {@link FAILURE_TRIAGE}, and they can
    disagree ONLY about whether a `cause` was followed first.

    WHY THE PAIR EXISTS, measured rather than asserted. `components/designworkshop/FieldInput.tsx:1874`
    is `if (isTransient(err))` in the catch of an `await uploadMediaBatch(...)`, so it is handed a
    `MediaBatchError` and nothing else — and a TRUE there runs `stageOffline(chosen)`, the only thing
    that keeps the captured bytes once the drain effect below has taken those files out of the capture
    card. A false falls through to `setProblem(err.message)` and the photograph is gone, under the
    batch wrapper's own "Check your internet connection and try again". An unwrapping `isTransient`
    would therefore have turned a 413 on an identity photograph into a silent deletion of it.

    So the drains — where a false MARKS an item and nothing is discarded — call
    `underlyingIsTransient`, and the interactive callers keep the conservative reading. When
    FieldInput's catch grows the drain's three-way shape (keep the bytes for unreachable and
    transient; for a refusal keep the bytes ANYWAY and show the server's sentence) the two collapse
    back into one, and this test is where that shows up.
  */
  for (const row of MATRIX) {
    const bare = row.error();
    expect(isTransient(bare), `${row.what}: nothing to unwrap, so the two must agree`).toBe(
      underlyingIsTransient(bare)
    );
    expect(triageAsThrown(bare).kind, `${row.what}: and so must the verdicts they read`).toBe(triageFailure(bare).kind);
  }
  // Wrapped, they part company exactly once: on a failure the wrapper hides an ANSWER for.
  const refused = asBatchFailure(new ApiError(415, "Unsupported media type", null));
  expect(isTransient(refused), "as thrown, an unreadable wrapper is 'keep the work'").toBe(true);
  expect(underlyingIsTransient(refused), "opened, it is the 415 the server sent").toBe(false);
  const dropped = asBatchFailure(new TypeError("Failed to fetch"));
  expect(isTransient(dropped), "and where the wrapper hides no answer they agree again").toBe(
    underlyingIsTransient(dropped)
  );
});

test("FieldInput's media catch is still the conservative caller this pair exists for", () => {
  /*
    A CROSS-FILE COUPLING, PINNED WHERE IT CAN BE SEEN. `components/designworkshop/FieldInput.tsx` is
    not this group's file and was not edited; the reason `isTransient` does not unwrap is entirely
    about what that one catch does with a false. If somebody swaps it for `underlyingIsTransient`
    without first changing the catch to stage the bytes on a refusal too, a server-refused capture
    starts disappearing — silently, with no test to say so. This is that test.

    TO RELEASE IT: give that catch the drain's three-way shape (stage the bytes for `isUnreachable ||
    isTransient`, and for a refusal stage them ANYWAY and show the server's sentence), then switch it
    to `underlyingIsTransient`, delete this test, and collapse the pair in `lib/failureTriage.ts`.
  */
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
  /*
    THE CONTRADICTION THAT PRODUCED THE JAM: an item recorded as permanently refused AND re-sent on
    every connection. Stated as two one-way implications rather than an equivalence, because
    `credential-expired` keeps everything and still does not retry now — what unblocks it is a person
    signing in, and spending a request per queued item to be told 401 again is exactly the prepaid
    data bill `blocksRetry` exists to avoid.
  */
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

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The cases that used to slip past
 * ──────────────────────────────────────────────────────────────────────────── */

test("a refusal wrapped in a MediaBatchError is a refusal to EVERY classifier, not just to one", () => {
  /*
    THE DISAGREEMENT, IN ONE ASSERTION. `isUnreachable` followed `cause` and `isTransient` did not,
    so the same 415 on the same video was "the server answered and refused it" to the media leg and
    "worth trying again" to the pass-level catch, eight lines apart. Every outbox defect the audit
    closed lived in that gap.
  */
  const refused = asBatchFailure(new ApiError(415, "Unsupported media type", null), "loom.mp4");
  expect(isUnreachable(refused)).toBe(false);
  expect(underlyingIsTransient(refused), "this answered TRUE before the unwrap was made shared").toBe(false);
  expect(isCredentialExpiry(refused)).toBe(false);
  expect(isSchemaRefusal(refused)).toBe(false);
  // `isTransient` is the ONE classifier that still answers about the wrapper, and the test above says
  // at length why. Named here so this test cannot be read as "every classifier unwraps".
  expect(isTransient(refused), "except the conservative reading, deliberately — see the pair test").toBe(true);
});

test("a dialect mismatch met while uploading a photograph is read as a dialect mismatch", () => {
  const inner = new ApiError(422, "merge: Extra inputs are not permitted", EXTRA_FORBIDDEN);
  const wrapped = asBatchFailure(inner, "a.jpg");
  // Wrapped, this used to answer false and the entry got "you got this wrong, correct it" — about an
  // answer nobody typed, which no edit can ever clear.
  expect(isSchemaRefusal(wrapped)).toBe(true);
  expect(schemaRefusalError(wrapped), "and the caller gets the sentence naming the key").toBe(inner);
  expect(schemaRefusalError(new ApiError(422, "amount: not a valid number", null)), "a field a validator rejected is not drift").toBeNull();
  expect(schemaRefusalError(new TypeError("Failed to fetch"))).toBeNull();
});

test("a 0-byte file has a type now, and it is not 'the connection is at fault'", () => {
  /*
    THE JAM THAT NEEDED NO SERVER. `lib/media.ts` refuses an empty file before it presigns anything,
    and it used to do so with a bare `new Error` — indistinguishable from a `TypeError` out of
    `fetch`, whose correct reading is "offline". So the batch escalation stopped the whole outbox
    pass, nothing was recorded, no Discard was offered (Discard is drawn from a failure record), and
    every future connection met the identical empty file. A capture the camera app never finished
    writing is not exotic on this hardware.
  */
  const empty = new LocalRefusalError('"capture-0001.jpg" is empty (0 bytes) — there is nothing to upload.');
  expect(triageFailure(empty).kind).toBe("permanent");
  expect(isUnreachable(empty), "no connection can change it").toBe(false);
  expect(isTransient(empty), "and retrying it for ever is the jam").toBe(false);
  expect(triageFailure(asBatchFailure(empty)).kind, "wrapped, which is how a drain meets it").toBe("permanent");

  // A bare `Error` still defaults to offline, and must: that is the right reading of everything
  // unrecognised. The fix was to stop this particular failure being unrecognised, not to change the
  // default — which would start marking dropped connections as refusals, the same bug reversed.
  expect(isUnreachable(new Error('"capture-0001.jpg" is empty (0 bytes) — there is nothing to upload.'))).toBe(true);
});

test("a site published with no API address keeps its queued work and still tells the person", () => {
  // `ApiUnconfiguredError` is thrown with no request made, and `lib/api.ts` chose 503 for it
  // deliberately: it is the code the rest of the app already reads as "the service is not answering
  // right now", so queued work stays queued and drains once an administrator redeploys.
  const unconfigured = new ApiUnconfiguredError();
  expect(triageFailure(unconfigured).kind).toBe("transient");
  expect(isTransient(unconfigured), "the work is kept").toBe(true);
  expect(isUnreachable(unconfigured), "but it is not the visitor's signal, and must not be reported as one").toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. One implementation, consulted by everything
 * ──────────────────────────────────────────────────────────────────────────── */

test("the classifiers `lib/offline.ts` exports ARE these, not copies of them", () => {
  /*
    HOW MANY SCREENS DEPEND ON THIS IS MEASURED HERE AND STATED NOWHERE ELSE. Three drafts of the
    comments around this module each wrote the number down — "ten surfaces", "Ten screens", "Eleven
    files" — inside one change, and two of the three were wrong. A count in prose is a fact with no
    test behind it, which is the same defect as a triage table in a docstring.
  */
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
  // A FLOOR, and the point of it is that the re-export is load-bearing rather than decorative: if a
  // refactor left one importer, "the import path did not move, on purpose" would stop being a reason.
  expect(importers.length, "the classifiers are imported across the client, which is why they are shared").toBeGreaterThanOrEqual(10);
});

/**
 * The two files allowed to touch a `cause` at all, and what each is allowed to do with it.
 *
 * An ALLOWLIST rather than an exact-equality on one path, because the sweep below now matches every
 * spelling of a read instead of three hand-picked ones — and one of those spellings is the SETTER in
 * `lib/media.ts`, which copies the first per-file failure onto the batch escalation. That is the
 * other half of the contract and is why a wrapper carries a classifiable error at all.
 */
const MAY_TOUCH_A_CAUSE: Record<string, string> = {
  "lib/failureTriage.ts": "the one unwrap — `underlyingError`, bounded, and every classifier reads its result",
  "lib/media.ts": "SETS it: `new MediaBatchError(…, { cause: failures[0]?.cause })` on the batch escalation"
};

test("`cause` is touched in two places in the whole web client, and read in one", () => {
  /*
    THE PROPERTY THE TRIAGE MODULE EXISTS FOR, swept rather than asserted about. Two functions that
    both walk `cause` are two chances to walk it differently, and this repository had exactly that:
    `isUnreachable` unwrapped to depth 8 and `isTransient`, eight lines above it, did not unwrap at
    all. Every outbox defect the audit closed lived in that gap.

    THE PATTERN IS DELIBERATELY WIDER THAN THE SENTENCE ABOVE IT USED TO BE. The first version matched
    `).cause`, `error.cause` and `failure.cause` — three spellings — while claiming to cover the whole
    client. `const c = e.cause;` and `const { cause } = err as { cause?: unknown };` both walked past
    it, measured against a probe module. A guard weaker than its own docstring is how the previous
    generation of these tables got trusted while being wrong, so this one matches ANY property read
    named `cause` and any destructure of one.
  */
  const readers = new Map<string, string[]>();
  for (const file of sourceFiles()) {
    const name = relative(FRONTEND, file).replace(/\\/g, "/");
    readFileSync(file, "utf8")
      .split("\n")
      .forEach((line, index) => {
        const code = line.trimStart();
        if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return;
        // `.cause` anywhere on the right of an expression READS one; `{ … cause … } =` destructures
        // one. `cause:` as an object key CONSTRUCTS one and is not a read, so it is not matched.
        if (!/\.cause\b/.test(line) && !/\{[^{}]*\bcause\b[^{}]*\}\s*=/.test(line)) return;
        readers.set(name, [...(readers.get(name) ?? []), `${name}:${index + 1}`]);
      });
  }
  const unexpected = [...readers.keys()].filter((name) => !(name in MAY_TOUCH_A_CAUSE)).sort();
  expect(
    unexpected,
    "only lib/failureTriage.ts may unwrap a cause — add a reasoned entry to MAY_TOUCH_A_CAUSE if a file must set one"
  ).toEqual([]);
  // And the sweep is not vacuous: the one file that MUST appear does.
  expect([...readers.keys()], "the unwrap itself is still here, so this sweep is looking at real code").toContain(
    "lib/failureTriage.ts"
  );
  // The spellings that used to walk past, asserted directly so a typo in the pattern cannot lose them.
  const readsACause = (line: string) => /\.cause\b/.test(line) || /\{[^{}]*\bcause\b[^{}]*\}\s*=/.test(line);
  expect(readsACause("const c = e.cause;"), "a plain property read").toBe(true);
  expect(readsACause("const { cause } = err as { cause?: unknown };"), "a destructure").toBe(true);
  expect(readsACause("if (failure.cause instanceof ApiError) {"), "and the original three still match").toBe(true);
  expect(readsACause("super(message, { cause: failures[0]?.cause });"), "the setter reads one too — hence the list").toBe(true);
  expect(readsACause("throw new MediaBatchError(message, failures);"), "and constructing one without a cause is not a read").toBe(false);
});

/**
 * Surfaces that still hard-code what a 408, a 429 or a 5xx MEANS, instead of asking the triage.
 *
 * A MAXIMUM, NOT AN EXACT LIST. Every entry is a candidate for the same treatment, recorded so that a
 * NEW one fails this test rather than joining them quietly. Deleting an entry because a surface
 * started consulting the triage is expected and must not fail; adding one is the class reopening.
 * The list started at five, because none of those files belonged to the wave that wrote the sweep.
 * FOUR have since been deleted from it by the waves that owned them: `lib/aiLayers.ts` and
 * `lib/aiVerbs.ts` each held a copy of the 408 rule and now ask `isUnreachable`;
 * `components/settings/ProviderOrderPanel.tsx` branched on `>= 500` and `=== 0` and now reads
 * `triageFailure`'s kind — which is what gave its 408 and its 429 the right sentence at last; and the
 * design-workshop stage page, which owned itself and moved in the same hour, now takes its 429 and
 * both of its `>= 500` branches from the triage — the first inline comment below is the record of
 * that one, and this paragraph used to claim it was still here.
 *
 * THE ONE THAT REMAINS IS NOT A LEFTOVER OF THE SAME KIND. `lib/media.ts` is a deliberate NARROWING
 * of a verdict it already takes from the triage, argued in the comment beside it.
 */
const HAND_ROLLED_NETWORK_TESTS = [
  // The stage page is GONE from this list, and the empty line is the record of it: its 429 branch is
  // `serverAskedForTime` and both of its `>= 500` branches are `triageFailure(err).kind ===
  // "transient"`. It kept its two extra SENTENCES — a 429 is a notice about a banked save, a 5xx is an
  // error naming the stage — which is what this list is for: a surface may say different things about
  // two verdicts, it may not decide the verdicts itself.

  // WHAT THE REMAINING ENTRY HOLDS is a table and not a comparison:
  // `RETRIABLE_GATEWAY_CODES = new Set([502, 503, 504])`, and it is the deliberate NARROWING rather
  // than a second classification: `isRetriableApiFailure` gets its verdict from `triageFailure` now
  // and then declines to repeat a 429 or a 500 inside a four-attempt loop that runs while somebody
  // watches a progress bar. The argument is written out above that function. Listed rather than
  // fixed because the set IS the difference, and deleting it would put a rate-limited upload into a
  // tight retry loop on a metered rural connection.
  "lib/media.ts"
];

test("no NEW surface invents another answer to 'is this the network'", () => {
  /*
    THE PATTERN IS WIDER THAN IT WAS, FOR THE REASON THE PREVIOUS ONE FAILED. It matched exactly three
    spellings — `=== 408`, `=== 429`, `>= 500` — while its own docstring claimed every surface that
    hard-codes what those statuses mean. `status === 503 || status === 500` walked past it, measured
    against a probe module, and so did every `Set.has(status)`: `lib/media.ts` held a whole sixth
    answer to this question in a `new Set([502, 503, 504])` and nothing recorded it for a wave whose
    thesis was that the class was closed.

    So: any comparison of a `status` against a network status (408, 429, any 5xx), with any operator,
    plus any collection of them declared under a `…CODES` / `…STATUS` name.
  */
  const offenders = new Set<string>();
  for (const file of sourceFiles()) {
    const name = relative(FRONTEND, file).replace(/\\/g, "/");
    if (name === "lib/failureTriage.ts") continue;
    for (const line of readFileSync(file, "utf8").split("\n")) {
      const code = line.trimStart();
      if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) continue;
      // `verdict.status` IS the shared classifier's answer being read back — that is the remedy, not
      // the defect. What this looks for is a surface deciding from a raw `error.status` of its own.
      const comparesAStatus = /(?<!verdict\.)\bstatus\s*(?:===|!==|==|>=|<=|>|<)\s*(?:408|429|5\d\d)\b/i;
      // `const RETRIABLE_GATEWAY_CODES = new Set([502, 503, 504])` — a table of statuses is a rule
      // about them, and `Set.has()` is precisely the spelling no status-comparison regex can see.
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
  // NOT VACUOUS, and not coupled to the tree either — the list above is a MAXIMUM, so asserting it
  // exactly would fail the day somebody fixes one of them. The spellings are asserted instead, which
  // is the thing a typo in the regex would silently lose. Every one of these walked past the previous
  // pattern; they are here so they cannot start walking past this one.
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
  // And the two readings that are the remedy rather than the defect stay invisible to it.
  expect(comparesAStatus.test("if (verdict.status === 429) return true;"), "reading the shared verdict is fine").toBe(
    false
  );
  expect(comparesAStatus.test("if (xhr.status >= 200 && xhr.status < 300) {"), "2xx is not a network rule").toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. Android differs on 401, deliberately
 * ──────────────────────────────────────────────────────────────────────────── */

test("the web does NOT fold 401 into isTransient, and the divergence is recorded as intentional", () => {
  /*
    ANDROID IS DIFFERENT AND STAYS DIFFERENT. `WorkshopRepository.isTransient` answers 401 with
    `true` and `WorkshopSync.isConnectionFailure` puts 401 on the connection's side of its line too.
    Its readers are the two drains, which all want the same thing from a 401, so folding it in there
    is the shortest true statement. The web's `isTransient` is also read by `saveOrQueue` and by two
    interactive surfaces, where a 401 means "you are signed out": widening it would start banking
    signed-out saves into the outbox to be refused later.

    The OUTCOME is the same on both platforms — stop, mark nothing, ask for a sign-in — which is the
    property that actually has to hold, and it is what makes this a divergence rather than a bug.
    Recorded here so the next person comparing the two finds the argument, not a discrepancy.
  */
  const expired = new ApiError(401, "Could not validate credentials", null);
  expect(isTransient(expired), "the web keeps 401 out of isTransient").toBe(false);
  expect(isCredentialExpiry(expired), "and asks the question separately").toBe(true);
  expect(isUnreachable(expired), "a 401 is an answer, so it is not offline either").toBe(false);
  // Wrapped, which is how the media leg meets it — the case that used to mark photographs refused.
  expect(isCredentialExpiry(asBatchFailure(expired))).toBe(true);

  expect(ANDROID_DIVERGENCE.status).toBe(401);
  expect(ANDROID_DIVERGENCE.web).toBe("credential-expired");
  expect(FAILURE_TRIAGE["credential-expired"].drain).toBe("stop-and-ask-for-sign-in");
});

test("403 is not swallowed by 401 — a different sentence with a different remedy", () => {
  expect(isCredentialExpiry(new ApiError(403, "Not permitted", null))).toBe(false);
  expect(triageFailure(new ApiError(403, "Not permitted", null)).kind).toBe("refused");
});
