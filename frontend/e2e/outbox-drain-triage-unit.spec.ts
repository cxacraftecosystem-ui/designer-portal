import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { outboxOutcome } from "@/components/OutboxBanner";
import { ApiError } from "@/lib/api";
import { MediaBatchError, MULTIPART_THRESHOLD, sweepStagedObjects, uploadMediaBatch } from "@/lib/media";
import {
  acknowledgeOutboxTrouble,
  entryAlreadyCreated,
  getOutboxHealth,
  isCredentialExpiry,
  isSchemaRefusal,
  isTransient,
  isUnreachable,
  outboxEntriesForAnotherAccount,
  outboxEntryIsForAnotherAccount,
  outboxIsAnswering,
  outboxOtherAccountLine,
  outboxSessionUser,
  outstandingFiles,
  refusedFileNames,
  setOutboxSessionUser,
  splitUnsendableFiles,
  syncOutbox,
  underlyingError,
  underlyingIsTransient,
  type OutboxEntry
} from "@/lib/offline";

import { installSessionCookie, removeSessionCookie } from "./support/fakeSessionCookie";

const OFFLINE_SOURCE = readFileSync(join(__dirname, "..", "lib", "offline.ts"), "utf8");
const BANNER_SOURCE = readFileSync(join(__dirname, "..", "components", "OutboxBanner.tsx"), "utf8");

const NOTHING_HAPPENED = {
  synced: 0,
  failed: 0,
  remaining: 0,
  stoppedOffline: false,
  declined: false,
  credentialExpired: false,
  storeUnreadable: false
};

test("the jam's mechanism has been closed at the root, and the media leg still catches", () => {
  const refusedByServer = new MediaBatchError("All 1 media file(s) failed to upload (Unsupported media type).", [
    { name: "loom.mp4", error: "Unsupported media type", cause: new ApiError(415, "Unsupported media type", null) }
  ]);
  expect(
    isTransient(refusedByServer),
    "the as-thrown reading keeps its answer: FieldInput's catch reads a false as 'discard the capture'"
  ).toBe(true);
  expect(
    underlyingIsTransient(refusedByServer),
    "and the drains' reading is classified by the 415 it wraps — this is the jam, closed"
  ).toBe(false);
  expect(isTransient(new TypeError("Failed to fetch")), "and an unrecognised failure is still 'try again'").toBe(true);
  expect(underlyingIsTransient(new TypeError("Failed to fetch")), "under both readings").toBe(true);
});

test("the two readings of 'worth retrying' can differ ONLY about a wrapper", () => {
  const bare: unknown[] = [
    new ApiError(500, "boom", null),
    new ApiError(503, "unavailable", null),
    new ApiError(429, "slow down", null),
    new ApiError(408, "timeout", null),
    new ApiError(401, "Could not validate credentials", null),
    new ApiError(403, "Not permitted", null),
    new ApiError(413, "Payload too large", null),
    new ApiError(415, "Unsupported media type", null),
    new TypeError("Failed to fetch"),
    new Error("something went wrong"),
    "not an error at all"
  ];
  for (const error of bare) {
    expect(isTransient(error), `${String(error)}: nothing to unwrap, so the two must agree`).toBe(
      underlyingIsTransient(error)
    );
  }
});

test("a batch the server refused is recorded, not retried for ever", () => {
  const refusedByServer = new MediaBatchError("All 1 media file(s) failed to upload (Unsupported media type).", [
    { name: "loom.mp4", error: "Unsupported media type", cause: new ApiError(415, "Unsupported media type", null) }
  ]);
  expect(isUnreachable(refusedByServer), "the server answered — this must not stop the pass").toBe(false);
  expect(refusedFileNames(refusedByServer, []), "and the entry's sentence names the file").toEqual([
    { name: "loom.mp4" }
  ]);
});

test("a deploy window and a rate limit are retried on the media leg, exactly as on the create leg", () => {
  for (const status of [500, 502, 503, 429, 408]) {
    const wrapped = new MediaBatchError(`All 1 media file(s) failed to upload (${status}).`, [
      { name: "loom.jpg", error: `${status}`, cause: new ApiError(status, "…", null) }
    ]);
    expect(
      isCredentialExpiry(wrapped) || isUnreachable(wrapped) || underlyingIsTransient(wrapped),
      `a ${status} on a photograph must stop the pass and keep it queued, not refuse the file`
    ).toBe(true);
  }
  for (const status of [400, 403, 413, 415, 422]) {
    const wrapped = new MediaBatchError(`All 1 media file(s) failed to upload (${status}).`, [
      { name: "loom.jpg", error: `${status}`, cause: new ApiError(status, "…", null) }
    ]);
    expect(
      isCredentialExpiry(wrapped) || isUnreachable(wrapped) || underlyingIsTransient(wrapped),
      `a ${status} is the server refusing this file — waiting will not change it`
    ).toBe(false);
  }
});

test("a batch that never reached anything still stops the pass and changes nothing", () => {
  const connectionDropped = new MediaBatchError("All 2 media file(s) failed to upload (network error).", [
    { name: "a.jpg", error: "Failed to fetch", cause: new TypeError("Failed to fetch") },
    { name: "b.jpg", error: "Failed to fetch", cause: new TypeError("Failed to fetch") }
  ]);
  expect(isUnreachable(connectionDropped)).toBe(true);
});

test("a 0-byte file is refused before it is sent, because afterwards it cannot be classified", () => {
  const empty = new File([], "capture-0001.jpg", { type: "image/jpeg" });
  const real = new File(["photograph bytes"], "loom.jpg", { type: "image/jpeg" });

  expect(isUnreachable(new Error('"capture-0001.jpg" is empty (0 bytes) — there is nothing to upload.'))).toBe(true);

  const split = splitUnsendableFiles([real, empty]);
  expect(split.sendable.map((file) => file.name), "the real photograph still goes").toEqual(["loom.jpg"]);
  expect(split.empty.map((file) => file.name), "and the empty one is named, not sent").toEqual(["capture-0001.jpg"]);
});

test("a batch the server only PARTLY refused keeps the files it refused", () => {
  const a = new File(["a"], "IMG_0001.jpg");
  const b = new File(["b"], "IMG_0001.jpg");
  const c = new File(["c"], "IMG_0002.jpg");
  const d = new File(["d"], "IMG_0003.jpg");
  const landed = { id: "m1" } as never;

  const outstanding = outstandingFiles([a, b, c, d], [a, b, c, d], [null, landed, null, landed]);
  expect(outstanding).toEqual([a, c]);
  expect(outstanding.length, "and the batch is NOT finished, so its index stays out of uploadedBatches").toBeGreaterThan(
    0
  );

  expect(outstandingFiles([a, b], [a, b], [landed, landed])).toEqual([]);

  const empty = new File([], "capture-0001.jpg");
  expect(outstandingFiles([a, empty], [a], [landed])).toEqual([empty]);
});

test("a batch refused without per-file detail names every file that was attempted", () => {
  const files = [new File(["a"], "a.jpg"), new File(["b"], "b.jpg")];
  expect(refusedFileNames(new Error("the uploader gave up"), files)).toEqual([{ name: "a.jpg" }, { name: "b.jpg" }]);
});

test("a dialect mismatch met while uploading is still read as a dialect mismatch", () => {
  const inner = new ApiError(422, "merge: Extra inputs are not permitted", {
    detail: [{ type: "extra_forbidden", loc: ["body", "merge"], msg: "Extra inputs are not permitted" }]
  });
  const wrapped = new MediaBatchError("All 1 media file(s) failed to upload (…).", [
    { name: "a.jpg", error: "merge: Extra inputs are not permitted", cause: inner }
  ]);

  expect(isSchemaRefusal(wrapped), "the wrapper carries no opinion; the 422 inside it decides").toBe(true);
  expect(isSchemaRefusal(underlyingError(wrapped)), "and unwrapping by hand first changes nothing").toBe(true);
  expect(underlyingError(inner), "an unwrapped error is returned unchanged").toBe(inner);
  expect(underlyingError("not an error at all")).toBe("not an error at all");
});

test("an expired sign-in is recognised however it arrives", () => {
  expect(isCredentialExpiry(new ApiError(401, "Could not validate credentials", null))).toBe(true);
  expect(
    isCredentialExpiry(
      new MediaBatchError("All 1 media file(s) failed to upload (Could not validate credentials).", [
        { name: "a.jpg", error: "Could not validate credentials", cause: new ApiError(401, "…", null) }
      ])
    ),
    "the media leg's 401 is wrapped, and marking those photographs refused is the defect"
  ).toBe(true);
});

test("nothing else is mistaken for an expired sign-in", () => {
  expect(isCredentialExpiry(new ApiError(403, "Not permitted", null))).toBe(false);
  expect(isCredentialExpiry(new ApiError(422, "…", null))).toBe(false);
  expect(isCredentialExpiry(new TypeError("Failed to fetch"))).toBe(false);
  expect(isTransient(new ApiError(401, "Could not validate credentials", null))).toBe(false);
  expect(underlyingIsTransient(new ApiError(401, "Could not validate credentials", null))).toBe(false);
});

test("the outbox's own create never navigates the researcher away", async () => {
  const create = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("await apiFetch<ReplayAnswer>("));
  expect(create, "the create leg's apiFetch call was renamed or removed").not.toHaveLength(1);
  expect(create.slice(0, 1200), "the drain must not navigate on a 401").toContain("redirectOn401: false");
});

function installFakeWindow(pathname: string, storedToken: string | null): { replace: string[] } {
  const replace: string[] = [];
  const store = new Map<string, string>();
  installSessionCookie(storedToken);
  (globalThis as Record<string, unknown>).window = {
    localStorage: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key)
    },
    location: {
      pathname,
      protocol: "http:",
      assign: (url: string) => void replace.push(url),
      replace: (url: string) => void replace.push(url)
    }
  };
  return { replace };
}

function install401() {
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ detail: "Could not validate credentials" }), {
      status: 401,
      headers: { "content-type": "application/json" }
    });
}

test.afterEach(() => {
  delete (globalThis as Record<string, unknown>).window;
  removeSessionCookie();
});

const onePhotograph = () => [new File([new Uint8Array([1, 2, 3])], "loom.jpg", { type: "image/jpeg" })];

test("the media leg does not navigate either, which was the half that could not be reached", async () => {
  const recorded = installFakeWindow("/products", "stale-token-from-six-weeks-ago");
  install401();

  await expect(
    uploadMediaBatch({ files: onePhotograph(), linkedRecordType: "artisan", linkedRecordId: "a1", redirectOn401: false })
  ).rejects.toBeInstanceOf(MediaBatchError);

  expect(recorded.replace, "a background pass must leave the researcher where they are").toEqual([]);
});

test("and a capture screen somebody is watching still lands on /login", async () => {
  const recorded = installFakeWindow("/products", "stale-token-from-six-weeks-ago");
  install401();

  await expect(
    uploadMediaBatch({ files: onePhotograph(), linkedRecordType: "artisan", linkedRecordId: "a1" })
  ).rejects.toBeInstanceOf(MediaBatchError);

  expect(recorded.replace, "the redirect is not gone, only opted out of").toEqual(["/session-ended?next=%2Fproducts"]);
});

test("the orphan sweeper cannot eject a researcher either — it is a five-minute timer", async () => {
  const recorded = installFakeWindow("/products", "stale-token-from-six-weeks-ago");
  install401();
  (
    (globalThis as Record<string, unknown>).window as { localStorage: { setItem(k: string, v: string): void } }
  ).localStorage.setItem("field_repo_staged_objects", JSON.stringify({ "staged/abandoned.jpg": Date.now() - 3_600_000 }));

  expect(await sweepStagedObjects(), "the stale key was reached, so the 401 really happened").toBe(1);
  expect(recorded.replace, "a timer must not take the screen away").toEqual([]);
  const journalAfter = JSON.parse(
    ((globalThis as Record<string, unknown>).window as { localStorage: { getItem(k: string): string | null } })
      .localStorage.getItem("field_repo_staged_objects") ?? "{}"
  ) as Record<string, number>;
  expect(Object.keys(journalAfter), "a dead credential must not cost us the object").toEqual([
    "staged/abandoned.jpg"
  ]);
});

test("the multipart leg is threaded too, and an over-threshold video is the realistic drain case", async () => {
  const recorded = installFakeWindow("/products", "stale-token-from-six-weeks-ago");
  const paths: string[] = [];
  (globalThis as Record<string, unknown>).fetch = async (input: unknown) => {
    paths.push(String(input));
    return new Response(JSON.stringify({ detail: "Could not validate credentials" }), {
      status: 401,
      headers: { "content-type": "application/json" }
    });
  };
  const video = new File([new Uint8Array([1, 2, 3])], "demonstration.mp4", { type: "video/mp4" });
  Object.defineProperty(video, "size", { value: MULTIPART_THRESHOLD + 1 });

  await expect(
    uploadMediaBatch({ files: [video], linkedRecordType: "artisan", linkedRecordId: "a1", redirectOn401: false })
  ).rejects.toBeInstanceOf(MediaBatchError);

  expect(paths.join(" "), "the large file really did take the multipart branch").toContain("/media/multipart/create");
  expect(recorded.replace, "the multipart branch must not navigate either").toEqual([]);
});

test("the drain is what passes the flag — media.ts accepting it is not the same as offline.ts using it", () => {
  const leg = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("result = await uploadMediaBatch({"));
  expect(leg.slice(0, 1600), "the resumed entry's first request is this one").toContain("redirectOn401: false");
});

test("a pass on a device with no readable store says so, and does not report an empty queue", async () => {
  const result = await syncOutbox();

  expect(result.remaining, "the count from an unreadable store is 0 and always was").toBe(0);
  expect(result.storeUnreadable, "and it now travels with the flag that disqualifies it").toBe(true);
  expect(outboxIsAnswering(getOutboxHealth()), "the health record is what the banner draws its panel from").toBe(
    false
  );
  expect(getOutboxHealth().readFailedAt, "a readonly failure is a READ failure").not.toBeNull();
  expect(getOutboxHealth().writeFailedAt, "and not a write failure — a full disk reads perfectly well").toBeNull();

  expect(outboxOutcome(result).kind).toBe("unreadable");
  expect(outboxOutcome(result).title).not.toBe("Nothing to send");

  acknowledgeOutboxTrouble();
  expect(outboxIsAnswering(getOutboxHealth())).toBe(true);
});

test("the pass is held under a cross-tab lease, and declines rather than queueing", () => {
  expect(OFFLINE_SOURCE).toContain('const SYNC_LOCK = "field-repo-outbox-sync"');
  expect(OFFLINE_SOURCE).toContain("locks.request(SYNC_LOCK, { ifAvailable: true }");
  expect(OFFLINE_SOURCE, "no Web Locks means the behaviour every field laptop already runs").toContain(
    "if (!locks?.request) return runSync();"
  );
});

test("a declined pass says a tab is busy, not that there is no connection", () => {
  const declined = { ...NOTHING_HAPPENED, declined: true, remaining: 3 };
  const outcome = outboxOutcome(declined);
  expect(outcome.kind).toBe("busy");
  expect(outcome.title, "'Still no connection' sends a researcher out to find signal they have").not.toBe(
    "Still no connection"
  );
  expect(outcome.title, "'Nothing to send' about a queue that is mid-flight").not.toBe("Nothing to send");
  expect(outcome.description, "and the count is still honest").toContain("3");
});

test("a stored createdId counts as created, so a queued create is not replayed over its own record", () => {
  expect(entryAlreadyCreated({ method: "POST", created: true, createdId: null })).toBe(true);
  expect(entryAlreadyCreated({ method: "POST", createdId: "art_9" })).toBe(true);
  expect(entryAlreadyCreated({ method: "POST" })).toBe(false);
  expect(entryAlreadyCreated({ method: "POST", createdId: "" })).toBe(false);
  expect(entryAlreadyCreated({ method: "POST", createdId: null })).toBe(false);

  expect(entryAlreadyCreated({ method: "PATCH", createdId: "art_9" })).toBe(false);
  expect(entryAlreadyCreated({ method: "PATCH", created: true })).toBe(true);
});

test("the drain consults the guard before the create branch, and writes the repair back", () => {
  const loop = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("const progress: OutboxEntry = { ...entry };"));
  const beforeCreate = loop.slice(0, loop.indexOf("if (!progress.created) {"));
  expect(beforeCreate, "the guard must run BEFORE the create branch, or it guards nothing").toContain(
    "if (!progress.created && entryAlreadyCreated(progress)) {"
  );
  expect(beforeCreate, "the repair is persisted").toContain("progress.created = true;");
  expect(beforeCreate, "and a vanished entry stops the pass working on it").toContain(
    "if (!(await persistProgress(progress))) continue;"
  );
});

test("the guard is still an exported, pure predicate", () => {
  const guard = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("export function entryAlreadyCreated("));
  expect(guard, "the predicate is still exported and pure").toContain("entry.method !== \"POST\"");
});

test("a mid-pass write goes through the store, not around it", () => {
  expect(OFFLINE_SOURCE, "the failure mark must not be a blind put of the loop's stale copy").not.toContain(
    "store.put({ ...entry, attempts: entry.attempts + 1"
  );
  expect(OFFLINE_SOURCE).not.toContain('tx("readwrite", (store) => store.put(entry))');
  expect(OFFLINE_SOURCE, "no row, no write").toContain("if (!row) return;");
});

test("only a batch with nothing outstanding is recorded as uploaded, and the narrowing is durable", () => {
  const loop = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("mediaFailed.push(...result.failed);"));
  const decision = loop.slice(0, loop.indexOf("progress.uploadedBatches ="));
  expect(decision, "the index goes in only when the whole batch is done").toContain("if (outstanding.length) {");
  expect(decision, "and the narrowing is what decides it").toContain("outstandingFiles(batch.files, sendable");
  const persist = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("async function persistProgress("));
  expect(persist.slice(0, 400), "the remaining files are progress, and progress is persisted").toContain(
    "media: entry.media"
  );
});

test("a write this device refused tells the listeners, because nothing else will", () => {
  const note = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("function noteStoreFailure("));
  const body = note.slice(0, note.indexOf("\n}"));
  expect(body, "a failed write must publish, or the panel it marks is never drawn").toContain("publish();");
});

test("what was sent is the answer to the button", () => {
  expect(outboxOutcome({ ...NOTHING_HAPPENED, synced: 1 })).toMatchObject({ kind: "sent", tone: "success" });
  expect(outboxOutcome({ ...NOTHING_HAPPENED, synced: 2, remaining: 4 }).description).toContain("4 still waiting");
});

test("an expired sign-in is named, and named as recoverable", () => {
  const outcome = outboxOutcome({ ...NOTHING_HAPPENED, credentialExpired: true, remaining: 6 });
  expect(outcome.kind).toBe("expired");
  expect(outcome.description).toContain("nothing has been thrown away");
});

test("an expiry that arrives PART WAY THROUGH is still named", () => {
  const outcome = outboxOutcome({ ...NOTHING_HAPPENED, synced: 2, credentialExpired: true, remaining: 6 });
  expect(outcome.title, "what was sent is still the answer to the button").toContain("2 saved entries sent");
  expect(outcome.description, "and the one thing a person can fix in ten seconds is in it").toContain("sign-in expired");
  expect(outcome.description).toContain("6");
  expect(outcome.tone, "not 'success' — there is something to do").toBe("error");
});

test("refusals are reported as refusals, not as an empty queue", () => {
  const outcome = outboxOutcome({ ...NOTHING_HAPPENED, failed: 2, remaining: 2 });
  expect(outcome.kind).toBe("refused");
  expect(outcome.title).toContain("2");
});

test("a genuine lack of signal still says exactly what it always said", () => {
  expect(outboxOutcome({ ...NOTHING_HAPPENED, stoppedOffline: true })).toMatchObject({
    kind: "offline",
    title: "Still no connection"
  });
});

test("the 'saved on this device' confirmation counts saves, not rows", () => {
  expect(BANNER_SOURCE, "the toast fires on saves this tab banked").toContain(
    "useSyncExternalStore(subscribeOutbox, getOutboxQueuedHere, getServerOutboxQueuedHere)"
  );
  expect(BANNER_SOURCE, "and no longer on a row count that a read can move").not.toContain("[entries.length, toast]");
  const queue = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("export async function queueOffline("));
  const body = queue.slice(0, queue.indexOf("\n}"));
  expect(body, "counted only once the device has actually accepted the write").toContain("queuedHere += 1;");
  expect(body.indexOf("queuedHere += 1;"), "after the add, never before").toBeGreaterThan(body.indexOf("store.add("));
});

test("and an empty queue is allowed to be an empty queue", () => {
  expect(outboxOutcome(NOTHING_HAPPENED)).toMatchObject({ kind: "idle", title: "Nothing to send", tone: "info" });
});

function owned(ownerUserId: string | null | undefined, failure: string | null = null): OutboxEntry {
  return {
    label: "Artisan · Giriraj Prasad",
    createdAt: 0,
    endpoint: "/artisans",
    method: "POST",
    body: "{}",
    media: [],
    attempts: 0,
    failure,
    ownerUserId
  };
}

test("a null owner is not another account, so it is held for a claim rather than counted against one", () => {
  expect(outboxEntryIsForAnotherAccount(null, "designer-a")).toBe(false);
  expect(outboxEntryIsForAnotherAccount(undefined, "designer-a")).toBe(false);
});

test("a null session passes too, because nobody being signed in is not somebody else", () => {
  expect(outboxEntryIsForAnotherAccount("designer-a", null)).toBe(false);
});

test("one designer's entry is not another's to send", () => {
  expect(outboxEntryIsForAnotherAccount("designer-a", "designer-a")).toBe(false);
  expect(outboxEntryIsForAnotherAccount("designer-a", "designer-b")).toBe(true);
});

test("another account's entries are counted for that account, refused or not", () => {
  const entries = [owned("designer-a"), owned("designer-a", "The server refused this."), owned("designer-b"), owned(null)];
  expect(outboxEntriesForAnotherAccount(entries, "designer-b")).toBe(2);
  expect(outboxEntriesForAnotherAccount(entries, "designer-a")).toBe(1);
  expect(outboxEntriesForAnotherAccount(entries, null)).toBe(0);
  expect(outboxEntriesForAnotherAccount([owned(null), owned(null)], "designer-b")).toBe(0);
});

test("the banner's line is one sentence, states the remedy, and counts in words", () => {
  expect(outboxOtherAccountLine(1)).toBe("1 entry was captured by another account — sign in as them to send.");
  expect(outboxOtherAccountLine(4)).toBe("4 entries were captured by another account — sign in as them to send.");
  expect(outboxOtherAccountLine(2)).not.toContain("deleted");
});

test("the session the queue answers for is told to it, and signing out is a real answer", () => {
  const before = outboxSessionUser();
  try {
    setOutboxSessionUser("designer-a");
    expect(outboxSessionUser()).toBe("designer-a");
    setOutboxSessionUser(null);
    expect(outboxSessionUser()).toBeNull();
  } finally {
    setOutboxSessionUser(before);
  }
});

test("the drain steps over another account's entry, and neither marks nor deletes it", () => {
  const loop = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("  for (const entry of entries) {"));
  const skip = "if (outboxEntryIsForAnotherAccount(entry.ownerUserId, sessionUserId)) {";
  expect(loop, "the drain asks the shared predicate rather than comparing ids itself").toContain(skip);
  expect(
    loop.indexOf(skip),
    "after the refusal test, so a refused entry is still reported as a refusal"
  ).toBeGreaterThan(loop.indexOf("if (blocksRetry("));
  expect(
    loop.slice(loop.indexOf(skip), loop.indexOf(skip) + 140),
    "counted and stepped over — no markFailure, no delete"
  ).toContain("otherAccount += 1;");
  expect(OFFLINE_SOURCE).toContain("const sessionUserId = outboxSessionUserId;");
});

test("the stamp is written by the queue and not by the six forms that reach it", () => {
  const queue = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("export async function queueOffline("));
  const head = queue.slice(0, queue.indexOf("{\n  void requestPersistence();"));
  expect(head, "a caller cannot pass one, so a caller cannot omit one").toContain('"ownerUserId"');
  expect(queue.slice(0, queue.indexOf("\n}")), "and the write takes it from the session").toContain(
    "ownerUserId: outboxSessionUserId"
  );
});

test("another account's entries leave the WAITING count, or the banner promises a send that cannot happen", () => {
  expect(BANNER_SOURCE).toContain("const entries = outboxEntriesForThisAccount(stored, signedIn);");
  expect(BANNER_SOURCE).toContain("const waiting = entries.length - rejected.length;");
  expect(BANNER_SOURCE, "held entries are a count and a choice, never a list").toContain("<OutboxAccountHold");
  expect(BANNER_SOURCE).toContain("{waiting ? (");
});

test("the cloud-off icon is gated on the same count, or the picture gives the instruction the words refuse to", () => {
  const panel = BANNER_SOURCE.slice(BANNER_SOURCE.indexOf('className="mb-4 grid gap-3 rounded-lg border border-amber-500/40'));
  const icons = panel.slice(0, panel.indexOf("<h2"));
  expect(icons, "the cloud is drawn on a branch, not unconditionally").toContain("{waiting ? (");
  expect(icons, "and the branch is the cloud").toContain("<CloudOff");
  expect(icons, "with a different glyph on the other arm rather than nothing at all").toContain("<CircleAlert");
  expect(icons, "the amber panel does not borrow the red panel's glyph").not.toContain("<TriangleAlert");
  expect(BANNER_SOURCE).toContain("const waiting = entries.length - rejected.length;");
});

test("a pass that moved nothing because everything belongs to somebody else says which", () => {
  const outcome = outboxOutcome({ ...NOTHING_HAPPENED, remaining: 3, otherAccount: 3 });
  expect(outcome.kind).toBe("idle");
  expect(outcome.description).toBe(outboxOtherAccountLine(3));
  expect(outboxOutcome(NOTHING_HAPPENED).description).toBeUndefined();
});
