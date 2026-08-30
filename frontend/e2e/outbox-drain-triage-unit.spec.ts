import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { outboxOutcome } from "@/components/OutboxBanner";
import { ApiError } from "@/lib/api";
import { MediaBatchError, MULTIPART_THRESHOLD, sweepStagedObjects, uploadMediaBatch } from "@/lib/media";
import {
  acknowledgeOutboxTrouble,
  getOutboxHealth,
  isCredentialExpiry,
  isSchemaRefusal,
  isTransient,
  isUnreachable,
  outboxIsAnswering,
  outstandingFiles,
  refusedFileNames,
  splitUnsendableFiles,
  syncOutbox,
  underlyingError,
  underlyingIsTransient
} from "@/lib/offline";

/**
 * THE FOUR WAYS THE RECORDS OUTBOX STOPPED DRAINING, PINNED AT THE DECISION.
 *
 * `lib/offline.ts` is the queue a researcher relies on after a fortnight with no signal, and every
 * case below is one in which it went on holding work while telling them something untrue about why.
 * None of them needed an unusual server; two needed no server at all.
 *
 *   1. A MEDIA FAILURE JAMMED THE WHOLE QUEUE FOR EVER. `uploadMediaBatch` escalates a batch in
 *      which nothing landed as a `MediaBatchError`, which is not an `ApiError` — so `isTransient`
 *      said "try again", the pass broke as if the device were offline, `markFailure` was never
 *      reached, and every entry behind it never drained. The banner said "Still no connection" and
 *      offered no Discard, because Discard is drawn from a failure record that was never written.
 *      A 0-byte file reaches it with no server involved.
 *   2. A 401 MID-DRAIN LEFT ONLY A BUTTON THAT DELETES THE WORK. Every entry the pass reached was
 *      marked permanently refused, and nothing in this repository could write an outbox failure
 *      back to null. It also NAVIGATED: `apiFetch` sends the browser to /login on a 401, and a pass
 *      runs on an `online` event with nobody having asked, so an expiring token threw the researcher
 *      off whatever they were editing. The create leg opted out first; the media leg could not,
 *      because `uploadMediaBatch` had no such argument to be given. Opting the ORPHAN SWEEPER out
 *      of the same redirect then MOVED the risk instead of removing it: the navigation used to tear
 *      the tab down after one failed DELETE, which is why `deleteStagedObject` could treat every
 *      `ApiError` as the server's final word on the object. Without it, a dead token would have
 *      swept the entire staged-object journal away and stranded the objects in the bucket.
 *   3. AN UNREADABLE OUTBOX RENDERED AS AN EMPTY ONE — an affirmative all-clear on the one surface
 *      that answers "may I hand this laptop on".
 *   4. TWO TABS FILED THE SAME GOVERNMENT RECORD TWICE, because the only replay guard was a
 *      module-level promise and a module is per-tab.
 *
 * AND THE TWO THE FIRST FOUR CREATED, because a control that clears a refusal changes what every
 * "this entry is finished" test means:
 *
 *   5. "TRY AGAIN" DELETED THE PHOTOGRAPHS THE SERVER HAD TURNED DOWN. A batch in which one file of
 *      four was refused RETURNS (only a batch that landed nothing throws), and the whole batch was
 *      recorded as uploaded. Frozen that merely stranded the entry; once the failure could be
 *      cleared, the next pass skipped the batch, found nothing outstanding, deleted the entry with
 *      the refused file inside it and reported it SENT.
 *   6. A DEPLOY WINDOW BECAME A PERMANENT REFUSAL. The media leg re-threw only what `isUnreachable`
 *      recognises, so a 503 or a 429 on a photograph was marked permanent — while the identical
 *      status on the record request, in the same pass, was "try again later".
 *
 * WHAT IS ASSERTED HERE AND WHAT IS NOT. The decisions are pure — which error stops a pass, which
 * files a refusal names, what a completed pass should SAY — so they are asserted directly, with no
 * browser, no store and no server anywhere near them, in the style of `draft-store-drift-unit.spec.ts`
 * next door. The two guards that are only observable against a real IndexedDB (the cross-tab Web
 * Lock, and the read-then-write that stops a loser tab resurrecting the winner's deleted entry) are
 * pinned by reading the source, exactly as `public-page-401-unit.spec.ts` pins the /me probe's
 * `redirectOn401: false` — a weaker assertion, and better than the nothing that was there before.
 */

const OFFLINE_SOURCE = readFileSync(join(__dirname, "..", "lib", "offline.ts"), "utf8");
const BANNER_SOURCE = readFileSync(join(__dirname, "..", "components", "OutboxBanner.tsx"), "utf8");

/** A completed pass that did nothing at all, so each test can name only the field it is about. */
const NOTHING_HAPPENED = {
  synced: 0,
  failed: 0,
  remaining: 0,
  stoppedOffline: false,
  declined: false,
  credentialExpired: false,
  storeUnreadable: false
};

/* ────────────────────────────────────────────────────────────────────────────
 * 1. A refused photograph is not a lost connection
 * ──────────────────────────────────────────────────────────────────────────── */

test("the jam's mechanism has been closed at the root, and the media leg still catches", () => {
  /*
    WHAT THIS TEST USED TO ASSERT, WHAT A REWRITE TRIED TO MAKE IT ASSERT, AND WHY IT ASSERTS BOTH.

    It read: "`isTransient` is shared with five other call sites and must go on answering 'yes,
    worth retrying' for anything it does not recognise, so the media leg cannot be made safe by
    changing it — it has to triage locally."

    A first attempt at closing the class overturned that pin, on the argument that "none of the other
    call sites is ever handed a `MediaBatchError`". THAT ARGUMENT IS FALSE, and it was checked rather
    than believed. `isTransient` has four call sites outside this spec:
    `app/(protected)/design-workshops/page.tsx:461` (a `createDesignWorkshop` catch),
    `lib/placeSearch.ts:188` (an `apiFetch` catch), `lib/offline.ts`'s `saveOrQueue` (an `apiFetch`
    catch, additionally guarded by `!(error instanceof ApiError)`) — and
    `components/designworkshop/FieldInput.tsx:1874`, which is the catch of an `await
    uploadMediaBatch(...)` and is therefore handed a `MediaBatchError` AND NOTHING ELSE.

    What FieldInput does with a true is `stageOffline(chosen)` — the only thing that keeps the
    captured bytes, since the drain effect below it has already taken those files out of the capture
    card. A false falls to `setProblem(err.message)` and the photograph is gone. So the "obvious"
    unwrap would have turned a 413 on one identity photograph into a silent deletion of it, under the
    batch wrapper's "Check your internet connection and try again" — the sentence this repository's
    headers repeatedly forbid when the server answered.

    THE PIN THEREFORE STANDS, and the fix is a second reading rather than a changed one.
    `isTransient` goes on answering for the value AS THROWN, which is what a caller that discards work
    on a false needs. `underlyingIsTransient` opens the wrapper, and it is what both drains call —
    where a false MARKS the item and nothing is thrown away. Two readings of one table
    (`lib/failureTriage.ts`), pinned against both columns of the matrix in
    `e2e/failure-triage-unit.spec.ts`, which is the property the old pair of hand-written
    implementations could not have had.

    The media leg still triages locally, and must: it has to tell an expired credential from a lost
    signal from a busy server, which is three sentences, not one boolean.
  */
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
  /*
    THE GUARD ON THE PAIR ITSELF. Two functions answering "is this the network" is the defect class
    this whole wave exists to close, and this file now has two on purpose. What makes that a split
    rather than a relapse is that they are the same table asked of a different depth: for anything
    that is not a wrapper they must be indistinguishable, and if somebody re-implements one of them
    by hand this is where it shows up.
  */
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
  // The question the media leg asks instead: did anything reach the server. It follows `cause`, so
  // the 415 inside answers it — and a false here is what lets the pass record the file and carry on
  // to the next entry instead of breaking as "offline".
  expect(isUnreachable(refusedByServer), "the server answered — this must not stop the pass").toBe(false);
  expect(refusedFileNames(refusedByServer, []), "and the entry's sentence names the file").toEqual([
    { name: "loom.mp4" }
  ]);
});

test("a deploy window and a rate limit are retried on the media leg, exactly as on the create leg", () => {
  // THE GAP THIS SPEC LEFT OPEN. It pinned 415 (refuse) and TypeError (retry) and nothing between,
  // so a media catch that re-threw only what `isUnreachable` recognises looked fully covered — while
  // a 503 and a 429 came out of the photograph upload PERMANENTLY refused and parked behind a manual
  // button, and the identical status on the record request was "try again later" in the same pass.
  for (const status of [500, 502, 503, 429, 408]) {
    const wrapped = new MediaBatchError(`All 1 media file(s) failed to upload (${status}).`, [
      { name: "loom.jpg", error: `${status}`, cause: new ApiError(status, "…", null) }
    ]);
    expect(
      // The media leg's line verbatim — see `lib/offline.ts`. `underlyingIsTransient`, because every
      // failure that reaches it is a `MediaBatchError` and what it wraps is the whole question.
      isCredentialExpiry(wrapped) || isUnreachable(wrapped) || underlyingIsTransient(wrapped),
      `a ${status} on a photograph must stop the pass and keep it queued, not refuse the file`
    ).toBe(true);
  }
  // And the widening is exactly that set — a refusal of the file itself is still a refusal.
  for (const status of [400, 403, 413, 415, 422]) {
    const wrapped = new MediaBatchError(`All 1 media file(s) failed to upload (${status}).`, [
      { name: "loom.jpg", error: `${status}`, cause: new ApiError(status, "…", null) }
    ]);
    expect(
      // The media leg's line verbatim — see `lib/offline.ts`. `underlyingIsTransient`, because every
      // failure that reaches it is a `MediaBatchError` and what it wraps is the whole question.
      isCredentialExpiry(wrapped) || isUnreachable(wrapped) || underlyingIsTransient(wrapped),
      `a ${status} is the server refusing this file — waiting will not change it`
    ).toBe(false);
  }
});

test("a batch that never reached anything still stops the pass and changes nothing", () => {
  // The other direction, and the one a one-sided fix would break: a connection that genuinely
  // dropped must keep everything queued rather than marking a fortnight of records refused.
  const connectionDropped = new MediaBatchError("All 2 media file(s) failed to upload (network error).", [
    { name: "a.jpg", error: "Failed to fetch", cause: new TypeError("Failed to fetch") },
    { name: "b.jpg", error: "Failed to fetch", cause: new TypeError("Failed to fetch") }
  ]);
  expect(isUnreachable(connectionDropped)).toBe(true);
});

test("a 0-byte file is refused before it is sent, because afterwards it cannot be classified", () => {
  const empty = new File([], "capture-0001.jpg", { type: "image/jpeg" });
  const real = new File(["photograph bytes"], "loom.jpg", { type: "image/jpeg" });

  // WHY THE SPLIT EXISTS, asserted rather than asserted about: `lib/media.ts` refuses an empty file
  // with a bare `Error`, and a bare `Error` is indistinguishable from lost signal to every test
  // downstream. That is the whole jam, reachable with no server at all.
  expect(isUnreachable(new Error('"capture-0001.jpg" is empty (0 bytes) — there is nothing to upload.'))).toBe(true);

  const split = splitUnsendableFiles([real, empty]);
  expect(split.sendable.map((file) => file.name), "the real photograph still goes").toEqual(["loom.jpg"]);
  expect(split.empty.map((file) => file.name), "and the empty one is named, not sent").toEqual(["capture-0001.jpg"]);
});

test("a batch the server only PARTLY refused keeps the files it refused", () => {
  /*
    THE SILENT DELETION "TRY AGAIN" WOULD OTHERWISE HAVE CAUSED.

    `uploadMediaBatch` throws only when nothing landed, so a batch of four in which the server
    refused one RETURNS. Recording that batch in `uploadedBatches` meant the next pass skipped it
    whole: nothing outstanding, entry deleted with the refused photograph inside it, "1 saved entry
    sent · The outbox is empty." Frozen, that was survivable — the only exit was a button labelled
    Discard. With a Try again drawn first and styled as the benign one, it destroyed bytes the file
    header says cannot be recreated "because the artisan has gone home".
  */
  const a = new File(["a"], "IMG_0001.jpg");
  const b = new File(["b"], "IMG_0001.jpg"); // Same name, different photograph. This is ordinary.
  const c = new File(["c"], "IMG_0002.jpg");
  const d = new File(["d"], "IMG_0003.jpg");
  const landed = { id: "m1" } as never;

  // b and d landed; a and c were refused. Positions, not names — a name match would misfile a and b.
  const outstanding = outstandingFiles([a, b, c, d], [a, b, c, d], [null, landed, null, landed]);
  expect(outstanding).toEqual([a, c]);
  expect(outstanding.length, "and the batch is NOT finished, so its index stays out of uploadedBatches").toBeGreaterThan(
    0
  );

  // A batch that fully landed is finished, which is the case that must go on being recorded — a
  // replay of it would upload every file a second time against the record that already has them.
  expect(outstandingFiles([a, b], [a, b], [landed, landed])).toEqual([]);

  // The unsendable ones were never offered, so they are still owed. This is what keeps a 0-byte file
  // naming itself on every pass instead of letting the entry reach the discard branch.
  const empty = new File([], "capture-0001.jpg");
  expect(outstandingFiles([a, empty], [a], [landed])).toEqual([empty]);
});

test("a batch refused without per-file detail names every file that was attempted", () => {
  const files = [new File(["a"], "a.jpg"), new File(["b"], "b.jpg")];
  expect(refusedFileNames(new Error("the uploader gave up"), files)).toEqual([{ name: "a.jpg" }, { name: "b.jpg" }]);
});

test("a dialect mismatch met while uploading is still read as a dialect mismatch", () => {
  // The 422 arrives WRAPPED here. `isSchemaRefusal` used to ask `instanceof ApiError` and answer no,
  // so the entry got "you got this wrong, correct it" — about an answer nobody typed, which no edit
  // can ever clear — instead of "the two builds are out of step, it will send itself". It follows
  // `cause` now, like every other classifier, because they all read one verdict.
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

/* ────────────────────────────────────────────────────────────────────────────
 * 2. A 401 is the credential, not the entry
 * ──────────────────────────────────────────────────────────────────────────── */

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
  // 403 is a different sentence with a different remedy, and a 401 must not swallow it.
  expect(isCredentialExpiry(new ApiError(403, "Not permitted", null))).toBe(false);
  expect(isCredentialExpiry(new ApiError(422, "…", null))).toBe(false);
  expect(isCredentialExpiry(new TypeError("Failed to fetch"))).toBe(false);
  // And 401 stays out of BOTH readings of "worth retrying": a 401 there still means "signed out",
  // not "your signal dropped" — widening it would start banking signed-out saves in the queue.
  expect(isTransient(new ApiError(401, "Could not validate credentials", null))).toBe(false);
  expect(underlyingIsTransient(new ApiError(401, "Could not validate credentials", null))).toBe(false);
});

test("the outbox's own create never navigates the researcher away", async () => {
  // `apiFetch` redirects to /login on a 401 by default, which is right for a screen somebody is
  // looking at and wrong for a background pass: a token that expired while the tab sat open would
  // throw the researcher off whatever they were doing, mid-edit, with the outbox never getting to
  // say what happened. Pinned on the source because the alternative is a real 401 against a real
  // API — which `outbox-schema-skew-drain.spec.ts` does, with a stack running.
  //
  // THE ANCHOR IS THE `apiFetch` CALL AND NOT THE NAME OF WHAT IT IS ASSIGNED TO. It used to be
  // `"const saved = await apiFetch<"`, and the day the drain learned to read a saved row out of a
  // NESTED key (see `savedIdIn`, which is what lets a design rating be queued at all) that binding
  // was renamed to `answer` — `indexOf` returned -1, `slice(-1)` handed this assertion the file's
  // last character, and a test about a 401 failed for a reason that has nothing to do with 401s.
  // `await apiFetch<ReplayAnswer>(` names the call itself, which is the thing being pinned.
  const create = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("await apiFetch<ReplayAnswer>("));
  expect(create, "the create leg's apiFetch call was renamed or removed").not.toHaveLength(1);
  expect(create.slice(0, 1200), "the drain must not navigate on a 401").toContain("redirectOn401: false");
});

/**
 * A window just real enough for `apiFetch`: the token store it reads and clears, and the location it
 * inspects and navigates. A second copy of the helper in `public-page-401-unit.spec.ts` rather than
 * an import, because that spec belongs to the /me probe and a shared fixture between two specs that
 * pin different subjects is a coupling neither asked for; `protocol: "http:"` is load-bearing in
 * both for the same reason — `assertApiConfigured()` refuses a LOOPBACK api base from an https page,
 * and the base falls back to http://localhost:8000 in a test process.
 */
function installFakeWindow(pathname: string, storedToken: string | null): { replace: string[] } {
  const replace: string[] = [];
  const store = new Map<string, string>();
  if (storedToken) store.set("field_repo_token", storedToken);
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

/** What the server says to a token it no longer honours. */
function install401() {
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ detail: "Could not validate credentials" }), {
      status: 401,
      headers: { "content-type": "application/json" }
    });
}

test.afterEach(() => {
  delete (globalThis as Record<string, unknown>).window;
});

/** One tiny attachment — enough to reach the presign, which is the request under test. */
const onePhotograph = () => [new File([new Uint8Array([1, 2, 3])], "loom.jpg", { type: "image/jpeg" })];

test("the media leg does not navigate either, which was the half that could not be reached", async () => {
  // THE OTHER HALF OF THE 401 FIX, AND IT NEEDED A CHANGE IN `lib/media.ts`. The create leg has
  // opted out since the fix above, but a pass RESUMING an entry whose record already exists skips
  // the create entirely and sends its first request from `uploadMediaBatch` — which had no way to
  // pass the flag down to its own `apiFetch`. A seven-day token dying in that window threw the
  // researcher onto /login mid-edit, losing whatever was on screen. Driven for real rather than
  // grepped: `apiFetch`'s 401 branch is a plain function over `window` and `fetch`, both of which
  // stand up in Node, exactly as `public-page-401-unit.spec.ts` drives the same branch.
  const recorded = installFakeWindow("/products", "stale-token-from-six-weeks-ago");
  install401();

  await expect(
    uploadMediaBatch({ files: onePhotograph(), linkedRecordType: "artisan", linkedRecordId: "a1", redirectOn401: false })
  ).rejects.toBeInstanceOf(MediaBatchError);

  expect(recorded.replace, "a background pass must leave the researcher where they are").toEqual([]);
});

test("and a capture screen somebody is watching still lands on /login", async () => {
  // The default is not weakened, only made overridable: on a screen a person is looking at, an
  // expired session and a sign-in form are the same thing. A test that only pinned the opt-out would
  // pass just as happily against a module that had stopped redirecting altogether.
  const recorded = installFakeWindow("/products", "stale-token-from-six-weeks-ago");
  install401();

  await expect(
    uploadMediaBatch({ files: onePhotograph(), linkedRecordType: "artisan", linkedRecordId: "a1" })
  ).rejects.toBeInstanceOf(MediaBatchError);

  expect(recorded.replace, "the redirect is not gone, only opted out of").toEqual(["/login"]);
});

test("the orphan sweeper cannot eject a researcher either — it is a five-minute timer", async () => {
  // FOUND WHILE THREADING THE FLAG, AND IT NEEDS NO OUTBOX. `scheduleStagedObjectSweep` runs
  // `sweepStagedObjects` on a `setInterval`, and its DELETE went out with `apiFetch`'s default — so
  // a researcher with a form open and a token that expired five minutes ago was navigated to /login
  // by a background cleanup of objects nobody was waiting on. Same defect as the media leg's, from a
  // caller that is even further from anything a person did.
  const recorded = installFakeWindow("/products", "stale-token-from-six-weeks-ago");
  install401();
  // One journalled key, aged past STAGED_STALE_MS (five minutes) so the sweep actually picks it up.
  (
    (globalThis as Record<string, unknown>).window as { localStorage: { setItem(k: string, v: string): void } }
  ).localStorage.setItem("field_repo_staged_objects", JSON.stringify({ "staged/abandoned.jpg": Date.now() - 3_600_000 }));

  expect(await sweepStagedObjects(), "the stale key was reached, so the 401 really happened").toBe(1);
  expect(recorded.replace, "a timer must not take the screen away").toEqual([]);
  // AND THE KEY IS STILL THERE. Not navigating is only half of it: `deleteStagedObject` treats an
  // `ApiError` as the server's final word and drops the journal entry, and a 401 is an `ApiError`.
  // With the redirect gone there is nothing to stop the sweep grinding through all twenty keys of a
  // batch — every one of them unauthenticated after the first, every one of them 401 — and emptying
  // the journal, which would strand the objects in the bucket for good. A 401 says the credential
  // died, not the object.
  const journalAfter = JSON.parse(
    ((globalThis as Record<string, unknown>).window as { localStorage: { getItem(k: string): string | null } })
      .localStorage.getItem("field_repo_staged_objects") ?? "{}"
  ) as Record<string, number>;
  expect(Object.keys(journalAfter), "a dead credential must not cost us the object").toEqual([
    "staged/abandoned.jpg"
  ]);
});

test("the multipart leg is threaded too, and an over-threshold video is the realistic drain case", async () => {
  // The three tests above all send a 3-byte photo, so all three go through `uploadWhole` and none of
  // them touches `uploadInParts` — whose `/media/multipart/create` is the FIRST request a queued
  // video makes, and therefore the one that would have navigated.
  //
  // THE SIZE IS WRITTEN AS `MULTIPART_THRESHOLD + 1` AND MUST STAY THAT WAY. What this test needs is
  // "one byte over whichever threshold the module currently declares", and the threshold moved on
  // 2026-08-30 (64 MiB → 16 MiB, so that a file needing longer than the 900-second whole-PUT
  // signature is never sent down a path that cannot resume — see the constant's note). A literal
  // here would have gone on passing while testing the wrong branch.
  //
  // `size` is stubbed rather than allocated: the 401 lands on the create call, long before anything
  // slices the blob. Note that at 16 MiB + 1 the file is now UNDER `CHECKSUM_MAX_BYTES`, so
  // `computeChecksum` no longer skips it on size — it is simply never awaited, because
  // `uploadInParts` throws first, and it is safe regardless (the real blob is three bytes, and the
  // function returns null rather than throwing when `crypto.subtle` is absent).
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
  // The two behavioural tests above prove `uploadMediaBatch` HONOURS the flag; nothing in them
  // reaches the drain, which is the caller that has to send it. Pinned on the source for the same
  // reason as the create leg: the alternative is a real 401 against a real API mid-replay.
  const leg = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("result = await uploadMediaBatch({"));
  expect(leg.slice(0, 1600), "the resumed entry's first request is this one").toContain("redirectOn401: false");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. A store that cannot be read is not an empty store
 * ──────────────────────────────────────────────────────────────────────────── */

test("a pass on a device with no readable store says so, and does not report an empty queue", async () => {
  // There is no IndexedDB in this process, which is exactly the shape of the failure being pinned:
  // the read fails, the cache is emptied, and `remaining` comes back 0. The old result carried
  // nothing to distinguish that from a queue that really is empty, so the click was answered
  // "Nothing to send" — an affirmative all-clear from a device that cannot see its own outbox.
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

  // The acknowledgement repairs nothing and clears both marks, so the panel can be dismissed by a
  // researcher whose disk was freed an hour ago.
  acknowledgeOutboxTrouble();
  expect(outboxIsAnswering(getOutboxHealth())).toBe(true);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. One device drains once, across every tab
 * ──────────────────────────────────────────────────────────────────────────── */

test("the pass is held under a cross-tab lease, and declines rather than queueing", () => {
  // Only observable with two real tabs and a real store, so it is pinned here at the three
  // decisions that make it correct: one named lock for the origin, `ifAvailable` so a second tab
  // declines instead of running the identical pass a moment later, and a fall-through where the API
  // is absent (it needs a secure context, and a researcher on an http:// LAN address must not lose
  // the ability to sync at all to gain protection against a race).
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

test("a mid-pass write goes through the store, not around it", () => {
  // The resurrection: `put` on a deleted key does not fail in IndexedDB, IT RE-CREATES THE ROW. So
  // the loser of a two-tab race put back the entry the winner had just filed and deleted, using an
  // object it had read before the network round trip. Read-then-write in ONE transaction is what
  // closes it on browsers with no Web Locks; these pin that the blind writes are gone.
  expect(OFFLINE_SOURCE, "the failure mark must not be a blind put of the loop's stale copy").not.toContain(
    "store.put({ ...entry, attempts: entry.attempts + 1"
  );
  expect(OFFLINE_SOURCE).not.toContain('tx("readwrite", (store) => store.put(entry))');
  expect(OFFLINE_SOURCE, "no row, no write").toContain("if (!row) return; // Another tab finished");
});

test("only a batch with nothing outstanding is recorded as uploaded, and the narrowing is durable", () => {
  // {@link outstandingFiles} is asserted directly above; these pin that the loop actually acts on it
  // — a narrowing computed and then discarded would re-send every file in the batch on the next
  // pass, duplicating the ones that landed.
  const loop = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("mediaFailed.push(...result.failed);"));
  const decision = loop.slice(0, loop.indexOf("progress.uploadedBatches ="));
  expect(decision, "the index goes in only when the whole batch is done").toContain("if (outstanding.length) {");
  expect(decision, "and the narrowing is what decides it").toContain("outstandingFiles(batch.files, sendable");
  // Written back to IndexedDB, not just to the loop's copy: a pass that dies here must come back to
  // the files that are still owed rather than to the batch as it was queued.
  const persist = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("async function persistProgress("));
  expect(persist.slice(0, 400), "the remaining files are progress, and progress is persisted").toContain(
    "media: entry.media"
  );
});

test("a write this device refused tells the listeners, because nothing else will", () => {
  /*
    THREE OF FOUR, WHICH IS THIS REPOSITORY'S CLASSIC SILENT MISS. The health mark exists, the red
    panel exists, and the panel's write sentence — "a queued record may not have been saved here" —
    exists; what was missing was the notification. `queueOffline` rejects at its `tx` and never
    reaches the `refreshOutbox` on the next line, so the mark sat on the record unrendered while the
    banner went on showing the last good list. The sibling reaches the same conclusion from the other
    end: see the `else publish()` on a failed `putDraftStage` write in `lib/designWorkshopStore.ts`.
  */
  const note = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("function noteStoreFailure("));
  const body = note.slice(0, note.indexOf("\n}"));
  expect(body, "a failed write must publish, or the panel it marks is never drawn").toContain("publish();");
});

/* ────────────────────────────────────────────────────────────────────────────
 * What the banner says — the whole point of the triage above
 * ──────────────────────────────────────────────────────────────────────────── */

test("what was sent is the answer to the button", () => {
  expect(outboxOutcome({ ...NOTHING_HAPPENED, synced: 1 })).toMatchObject({ kind: "sent", tone: "success" });
  expect(outboxOutcome({ ...NOTHING_HAPPENED, synced: 2, remaining: 4 }).description).toContain("4 still waiting");
});

test("an expired sign-in is named, and named as recoverable", () => {
  const outcome = outboxOutcome({ ...NOTHING_HAPPENED, credentialExpired: true, remaining: 6 });
  expect(outcome.kind).toBe("expired");
  // The sentence a researcher reads before deciding whether to press Discard. Six records and their
  // photographs turned on this being true, and the banner used to say the server had refused them.
  expect(outcome.description).toContain("nothing has been thrown away");
});

test("an expiry that arrives PART WAY THROUGH is still named", () => {
  // The combination the two flags were only ever exercised in isolation, which is why it was never
  // considered. `runSync` reaches an expiry mid-queue BY CONSTRUCTION — it sends until a 401 and
  // then breaks, keeping what it had already sent — so this is the ordinary shape of an expiry on a
  // queue of any size, not a corner. Ordered on `synced` alone it read "2 saved entries sent · 6
  // still waiting", and the reason those six will go on waiting was never said.
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
  // The one answer that was already right. A fix that renamed it would leave a researcher who has
  // learned this sentence in a courtyard wondering what changed.
  expect(outboxOutcome({ ...NOTHING_HAPPENED, stoppedOffline: true })).toMatchObject({
    kind: "offline",
    title: "Still no connection"
  });
});

test("the 'saved on this device' confirmation counts saves, not rows", () => {
  /*
    ROWS GO UP FOR TWO REASONS AND ONLY ONE OF THEM IS AN EVENT. Entries survive a browser restart,
    so the load after a fresh mount is a fortnight of records arriving at once — that is how a
    researcher was told her week-old queue had "just been saved on this device with no connection".
    The baseline that fixed it could only be armed by a read that SUCCEEDED, so when the store was
    unreadable at mount, the first real save afterwards armed the baseline silently and said nothing:
    the one message that tells somebody with no signal that their work went somewhere, swallowed
    exactly when the device had already proved it could fail. A counter that only a banked write
    moves has neither half.
  */
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
