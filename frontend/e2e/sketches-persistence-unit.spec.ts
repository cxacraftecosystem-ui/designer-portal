import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { syncPassLanded, syncPassNote } from "@/components/sketches/syncNote";

/**
 * THE THREE HOPS ON "SKETCHES AND PROTOTYPES" WHERE WORK COULD STOP AND NOBODY WOULD KNOW.
 *
 * The page captures four kinds of file and three kinds of judgement. Everything it captures is meant
 * to end up in Postgres and in S3, and an audit of the surface found three places where it did not:
 *
 *   1. THE UPLOAD TAB STAGED AND NEVER SENT. `UploadTabHost.attach` wrote the blob and the reference
 *      into IndexedDB and stopped, under a notice reading "It uploads itself when this device next
 *      has a connection." Nothing on the page called `syncDesignWorkshopDrafts`; the draft banner in
 *      the protected layout drains on mount and on the `online` event, and `online` NEVER FIRES for a
 *      tab that was never offline. A designer who traced a sketch on an office laptop and closed the
 *      tab had a file that existed nowhere but that browser.
 *   2. A RATING HAD NO DURABLE HOME AT ALL. `ratingsApi` refused the offline outbox — correctly, at
 *      the time: the drain requires a top-level `id` in the replay's answer and `POST /design-ratings`
 *      answers `{rating, replayed}`, so every SUCCESSFUL replay would have been marked a permanent
 *      captive-portal failure. The cost was that a score, an assessment and a suggested change typed
 *      with no signal were refused out loud and gone when the tab closed — the only value on the whole
 *      surface with no persistence path.
 *   3. THE ONE FIELD THAT PRINTS AS PICTURES COULD NOT BE FILLED. `prototype.turntablePhotos` is the
 *      only attachment on this surface the report draws (`report_builder._images` filters on IMAGE
 *      and IMAGE_LIST); the panel read its label, counted its frames, spent two paragraphs on why it
 *      mattered, and offered no picker.
 *
 * WHAT IS ASSERTED HERE AND WHAT IS NOT. The sentence a designer is shown about a finished sync pass
 * is pure, so it is asserted directly. The hops themselves need IndexedDB, a presign, a bucket and a
 * Postgres row, so they are pinned ON THE SOURCE — the same weaker-and-better-than-nothing choice
 * `outbox-drain-triage-unit.spec.ts` makes next door for its Web Lock and its 401, and for the same
 * reason: the alternative is a full stack, and `backend/tests/test_report_sketch_prototype_mapping.py`
 * already covers the far end of the same journey.
 */

const HOST_SOURCE = readFileSync(join(__dirname, "..", "components", "sketches", "UploadTabHost.tsx"), "utf8");
const RATINGS_SOURCE = readFileSync(join(__dirname, "..", "components", "sketches", "ratingsApi.ts"), "utf8");
const CARD_SOURCE = readFileSync(join(__dirname, "..", "components", "sketches", "ReviewCard.tsx"), "utf8");
const OFFLINE_SOURCE = readFileSync(join(__dirname, "..", "lib", "offline.ts"), "utf8");
const PANEL_SOURCE = readFileSync(
  join(__dirname, "..", "components", "sketches", "upload", "UploadTabPanel.tsx"), "utf8"
);
const MODEL_SOURCE = readFileSync(
  join(__dirname, "..", "components", "sketches", "upload", "PrototypeModelField.tsx"), "utf8"
);

/** A pass that carried everything. Each test below changes only the field it is about. */
const CLEAN = {
  workshopsCreated: 0,
  stagesSent: 1,
  mediaUploaded: 1,
  failed: 0,
  pending: 0,
  stoppedOffline: false
};

/* ────────────────────────────────────────────────────────────────────────────
 * 1. What a finished pass is allowed to claim
 * ──────────────────────────────────────────────────────────────────────────── */

test("only a pass that carried everything may say the repository has it", () => {
  expect(syncPassNote(CLEAN, "this file is")).toBe("Saved, and sent to the repository.");
  expect(syncPassLanded(CLEAN)).toBe(true);
});

test("a pass another tab is already running is not a pass that sent anything", () => {
  // `declinedResult()` — `failed: 0`, `stoppedOffline: false`, an honest `pending`. Read as "did it
  // work", this is indistinguishable from success, and announcing "sent to the repository" for it
  // tells a designer their file is in the ministry's database when it is in IndexedDB.
  const declined = { ...CLEAN, stagesSent: 0, mediaUploaded: 0, pending: 3 };
  expect(syncPassLanded(declined)).toBe(false);
  const note = syncPassNote(declined, "this file is");
  expect(note).not.toContain("sent to the repository");
  expect(note, "the designer needs to know something else is carrying it").toContain(
    "Another sync is already running"
  );
});

test("an offline pass and a refused pass each say which they were", () => {
  const offline = { ...CLEAN, stagesSent: 0, mediaUploaded: 0, stoppedOffline: true, pending: 1 };
  expect(syncPassNote(offline, "this file is")).toContain("no connection");
  expect(syncPassLanded(offline)).toBe(false);

  const refused = { ...CLEAN, failed: 1, pending: 1 };
  expect(syncPassNote(refused, "this file is"), "a refusal is not a signal problem").toContain(
    "the repository refused something"
  );
  expect(syncPassLanded(refused)).toBe(false);
});

test("a pass that ran and left work behind does not report an all-clear either", () => {
  const partial = { ...CLEAN, pending: 2 };
  expect(syncPassLanded(partial)).toBe(false);
  expect(syncPassNote(partial, "this file is")).toContain("still has work outstanding");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The upload tab actually sends
 * ──────────────────────────────────────────────────────────────────────────── */

test("a staged file is followed by a sync pass, in that order", () => {
  const attach = HOST_SOURCE.slice(HOST_SOURCE.indexOf("const attach = useCallback("));
  const body = attach.slice(0, attach.indexOf("[reload, workshopId]"));
  const staged = body.indexOf("stageLocalMedia(");
  const written = body.indexOf("await putDraftStage(");
  const sent = body.indexOf("await syncDesignWorkshopDrafts()");
  expect(staged, "the bytes still go into the local draft first — that is what makes them durable")
    .toBeGreaterThan(-1);
  expect(written).toBeGreaterThan(staged);
  expect(sent, "the file is written to this device and never asked to move").toBeGreaterThan(written);
});

test("the device write and the sending are separate blocks with separate sentences", () => {
  // The bug this rule comes from is `ReviewPanel.persist`'s: one `try` around both, so a throw from
  // the sync pass printed "this could not be saved on this device" over a write that had landed.
  const attach = HOST_SOURCE.slice(HOST_SOURCE.indexOf("const attach = useCallback("));
  const body = attach.slice(0, attach.indexOf("[reload, workshopId]"));
  const sent = body.indexOf("await syncDesignWorkshopDrafts()");
  const deviceRefusal = body.indexOf("Nothing could be written to this device's storage");
  expect(deviceRefusal, "the storage refusal belongs to the write, not to the send")
    .toBeLessThan(sent);
  expect(body.slice(sent), "a failed send must not claim the file was lost").toContain(
    "is saved on this device"
  );
});

test("the tab is released before the transfer, not held for the length of it", () => {
  const attach = HOST_SOURCE.slice(HOST_SOURCE.indexOf("const attach = useCallback("));
  const body = attach.slice(0, attach.indexOf("[reload, workshopId]"));
  expect(body.indexOf("setBusy(false)"), "a 200 MB model would freeze both halves of the panel")
    .toBeLessThan(body.indexOf("await syncDesignWorkshopDrafts()"));
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. A rating survives a courtyard with no signal
 * ──────────────────────────────────────────────────────────────────────────── */

test("the outbox can be told where a saved row lives, and does not guess when it is not there", () => {
  expect(OFFLINE_SOURCE, "the field the drain reads").toContain("savedIdIn?: string;");
  const helper = OFFLINE_SOURCE.slice(OFFLINE_SOURCE.indexOf("function savedRow("));
  const body = helper.slice(0, helper.indexOf("\n}"));
  expect(body, "absent means the top level, so every existing entry replays as it always has")
    .toContain("if (!savedIdIn) return answer;");
  expect(body, "a named key that is not there is NOT the whole answer: that is how a captive portal's own 200 page becomes a save")
    .toContain("return nested && typeof nested === \"object\" ? (nested as ReplayAnswer) : undefined;");
});

test("a rating that never reached the server is queued, and one the server refused is not", () => {
  const fn = RATINGS_SOURCE.slice(RATINGS_SOURCE.indexOf("export async function submitOrQueueDesignRating("));
  expect(fn, "the nested key this route answers with").toContain("savedIdIn: RATING_SAVED_IN");
  expect(RATINGS_SOURCE).toContain('const RATING_SAVED_IN = "rating"');
  expect(fn, "only a request the server never saw may be replayed").toContain(
    "if (isTransient(error) && !(error instanceof ApiError)) return queue();"
  );
  expect(fn, "a 403 on your own record must not be replayed for ever").toContain("throw error;");
  // The courtyard moment, which is the whole reason `ratedAt` exists: a queued delivery is ordered by
  // the DEVICE clock, because it can arrive days after the judgement was made.
  expect(fn).toContain("ratedAt: new Date().toISOString()");
  expect(fn, "the network is still tried first, always").toContain("await submitDesignRating(body)");
});

test("a queued rating is never drawn as a rating the repository holds", () => {
  const submit = CARD_SOURCE.slice(CARD_SOURCE.indexOf("async function submit("));
  const body = submit.slice(0, submit.indexOf("\n  }\n"));
  const queued = body.indexOf("if (outcome.queued)");
  const rated = body.indexOf("onRated(outcome.saved.rating)");
  expect(queued).toBeGreaterThan(-1);
  expect(rated, "the ranking is the repository's; a card must not add a score it is still holding")
    .toBeGreaterThan(queued);
  expect(body, "and it says so").toContain("has NOT reached the repository yet");
  // The queued box is not the green one. A colour is not the signal — see the comment beside it —
  // but spending the card's only affirmative styling on work that has not moved is the false
  // all-clear this repository keeps having to un-ship.
  expect(CARD_SOURCE).toContain("saved.queued");
  expect(CARD_SOURCE).toContain("bg-amber-100");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The field that prints as pictures is writable, and its label points at its input
 * ──────────────────────────────────────────────────────────────────────────── */

test("a queued rating is handed to the drain, not left for an `online` event this tab will never get", () => {
  /*
    THE DEFECT. `submitOrQueueDesignRating` stopped at the IndexedDB write while the card promised "It
    sends itself when this device next has a connection". Nothing on this page drains the outbox:
    `OutboxBanner` drains on MOUNT and on the `online` EVENT, and `online` never fires for a tab that
    was never offline. So a rating queued because of a transient with `navigator.onLine === true` — a
    dropped handshake, a DNS blip, a VPN flap — sat in IndexedDB until somebody reloaded the page.
    Never data loss (the banner lists it), which is exactly why nobody would have noticed. The same
    defect was fixed in `UploadTabHost.attach` in the same wave and was not fixed here.
  */
  const fn = RATINGS_SOURCE.slice(RATINGS_SOURCE.indexOf("export async function submitOrQueueDesignRating("));
  expect(RATINGS_SOURCE, "the shared pass, not a private replay").toContain(
    'import { queueOffline, syncOutbox } from "@/lib/offline";'
  );
  expect(fn, "the queue asks the pass that exists to carry it").toContain("await syncOutbox()");
  // NOT WHEN THE BROWSER ALREADY KNOWS IT IS OFFLINE — the same shortcut the direct path takes, and
  // the same one the banner's drain-on-mount takes. A request and a 30s timeout to learn what
  // `navigator.onLine` already said is a cost paid on a metered rural connection for nothing.
  expect(fn).toContain('typeof navigator !== "undefined" && navigator.onLine === false');
  // BOTH conditions, or "sent" is a claim the pass did not make: `synced > 0` alone could be another
  // entry moving while this one failed, and `remaining === 0` alone is also true of a declined pass
  // over a store that could not be read.
  expect(fn, "the pass's own report decides what the card says").toContain(
    "pass.synced > 0 && pass.remaining === 0"
  );
  // And the sentence the designer reads changes with it, in both directions.
  expect(CARD_SOURCE, "the drained case says the repository has it").toContain("outcome.sent");
  expect(CARD_SOURCE, "and the still-queued case no longer waits on an event").toContain(
    "This browser has already "
  );
});

test("no panel claims a file was added when the host has just said it was not", () => {
  /*
    THE DEFECT, IN THREE PLACES. Every panel in `components/sketches/upload/` called its attach
    callback and then set a green "… was added to …" on the very next line, unconditionally.
    `UploadTabHost` can refuse SYNCHRONOUSLY (`refuse("prototype")` — no row chosen, or a stage whose
    repository copy could not be read) and can fail its IndexedDB write a moment later. Either way the
    host's red sentence rendered directly above the panel's green tick, one of them a lie, on the
    surface whose only job is telling a designer whether their file is safe.

    `false` AND NOT A THROW: the host has already said what went wrong, in words next to the picker the
    designer used. A throw would make each panel invent a second sentence about a failure it cannot
    describe — the scar `ReviewPanel.persist` carries.
  */
  expect(PANEL_SOURCE, "the contract is named and argued in one place").toContain(
    "export type AttachAnswer = void | boolean | Promise<void | boolean>;"
  );
  // The host answers: `attach` resolves true once the file is on this device (phase two owns its own
  // sentence), and every synchronous refusal resolves false.
  //
  // `what` TAKES THE LANDED COUNT RATHER THAN BEING A FIXED STRING, since 2026-08-26. The field's
  // `maxItems` ceiling is enforced on this path now (`mediaRefRoom` before `appendMediaRef`), so a
  // turn of turntable frames can partly fit — and a sentence built before the write would say
  // "12 frames added" over the nine that landed. That is the same claim-without-checking defect this
  // very test exists to stop, one layer down, which is why the parameter had to grow a shape rather
  // than the caller a second sentence. What is pinned here is unchanged: the ANSWER is still
  // `Promise<boolean>`, and it is the answer the panels read.
  expect(HOST_SOURCE).toContain(
    "files: File[], what: (landedCount: number) => string): Promise<boolean>"
  );
  const refusals = HOST_SOURCE.match(/refuse\("(?:sketch|prototype)"\);\s+return false;/g);
  expect(refusals, "all four handlers answer, not just the two that were reported").toHaveLength(4);
  // And the panels ask before they claim. `!== false` and not truthiness: a host with nothing to
  // report returns `undefined`, which is every record form that mounts these panels, unchanged.
  expect(MODEL_SOURCE, "the model file").toContain("const handed = await onAttachModel(chosen);");
  expect(MODEL_SOURCE, "the turntable frames").toContain("const handed = await onAttachTurntable(frames);");
  expect(MODEL_SOURCE.match(/if \(handed === false\) return;/g)).toHaveLength(2);
});

test("the turntable can be filled from the tab that advises filling it", () => {
  // `AttachAnswer` AND NOT `void`, WHICH IS THE POINT OF THE RETURN TYPE. The callback used to return
  // nothing, so the panel could not learn that a file it had handed over went nowhere — and it printed
  // its green "N photographs were added to …" unconditionally, next to the host's own red "This file
  // has not been attached: …". `AttachAnswer` is `void | boolean | Promise<void | boolean>`, so an
  // explicit `false` suppresses the claim and a host with nothing to report is unchanged.
  expect(PANEL_SOURCE, "the callback the panel offers").toContain(
    "onAttachTurntable?: (files: File[]) => AttachAnswer;"
  );
  // THIS ASSERTION USED TO READ `toContain('id={`${fieldId}-turntable`}')` AND IT WENT RED THE MOMENT
  // THE PICKER IMPROVED. The turntable input is now a `DropCard`, and each card derives its own ids
  // from its own `useId` — which is better, because the label and the input can no longer disagree
  // about a hand-built id. But the old line pinned the hand-built id itself, so a change that removed
  // a whole class of bug read as a regression.
  //
  // The lesson is about what a source-read test should hold onto. Pinning an id string pins the
  // implementation; pinning the CONTROL and its behaviour pins the thing the feature promises. What
  // matters here is that the turntable is offered by a card that takes a whole turn at once, and that
  // the frames go to the callback the panel declares.
  expect(MODEL_SOURCE, "the turntable is offered by a drop card").toContain("<DropCard");
  expect(MODEL_SOURCE, "and it is the turntable's card, named from the field").toContain(
    "label={`Add photographs to “${turntableLabel}”`}"
  );
  expect(MODEL_SOURCE, "a turn is many frames chosen at once").toContain("multiple");
  expect(MODEL_SOURCE, "and the whole turn reaches the panel's callback").toContain(
    "onFiles={(files) => chooseFrames(files)}"
  );
  expect(HOST_SOURCE, "wired to the registry field, not to a name this file invented").toContain(
    "fieldKey: PROTOTYPE_TURNTABLE"
  );
  expect(HOST_SOURCE, "IMAGE_LIST appends; a single IMAGE would have to replace").toContain(
    'multiple: turntable.type === "IMAGE_LIST"'
  );
});

test("no control on the prototype panel carries a hardcoded DOM id", () => {
  // `fieldId` exists because this panel is not a singleton: one per prototype row, plus a dialog copy
  // over the top, is ordinary. A hardcoded id makes the second panel's label point at the first
  // panel's input — and the label that was hardcoded pointed at NO element at all, which costs the
  // input its accessible name and stops the label being a click target for it.
  //
  // THE COMMENTS ARE STRIPPED FIRST, AND THAT IS NOT A CONVENIENCE. The fix for the hardcoded id
  // carries a block comment QUOTING the id it replaced — which is exactly the house rule about
  // recording what went wrong — so a search over the raw file finds the defect in the sentence
  // explaining it. What is being pinned is the markup, so the markup is what is searched.
  const markup = MODEL_SOURCE.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
  expect(markup).not.toContain('id="prototype-model-file"');
  for (const match of markup.matchAll(/id=(?:\{`)?([^`{}"\s>]+)/g)) {
    const value = match[1];
    if (value.startsWith("$") || value.includes("fieldId")) continue;
    expect(value, `a literal DOM id (${value}) cannot survive two copies of this panel`).not.toMatch(
      /^"?[a-z][a-z0-9-]*"?$/
    );
  }
});
