import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { syncPassLanded, syncPassNote } from "@/components/sketches/syncNote";

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

const CLEAN = {
  workshopsCreated: 0,
  stagesSent: 1,
  mediaUploaded: 1,
  failed: 0,
  pending: 0,
  stoppedOffline: false
};

test("only a pass that carried everything may say the repository has it", () => {
  expect(syncPassNote(CLEAN, "this file is")).toBe("Saved, and uploaded.");
  expect(syncPassLanded(CLEAN)).toBe(true);
});

test("a pass another tab is already running is not a pass that sent anything", () => {
  const declined = { ...CLEAN, stagesSent: 0, mediaUploaded: 0, pending: 3 };
  expect(syncPassLanded(declined)).toBe(false);
  const note = syncPassNote(declined, "this file is");
  expect(note).not.toContain("Saved, and uploaded");
  expect(note, "the designer needs to know something else is carrying it").toContain(
    "Another sync is already running"
  );
});

test("an offline pass and a refused pass each say which they were", () => {
  const offline = { ...CLEAN, stagesSent: 0, mediaUploaded: 0, stoppedOffline: true, pending: 1 };
  expect(syncPassNote(offline, "this file is")).toContain("when you're back online");
  expect(syncPassLanded(offline)).toBe(false);

  const refused = { ...CLEAN, failed: 1, pending: 1 };
  expect(syncPassNote(refused, "this file is"), "a refusal is not a signal problem").toContain(
    "couldn't be uploaded"
  );
  expect(syncPassLanded(refused)).toBe(false);
});

test("a pass that ran and left work behind does not report an all-clear either", () => {
  const partial = { ...CLEAN, pending: 2 };
  expect(syncPassLanded(partial)).toBe(false);
  expect(syncPassNote(partial, "this file is")).toContain("still waiting to upload");
});

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
  expect(body, "and it says so").toContain("hasn't been uploaded yet");
  expect(CARD_SOURCE).toContain("saved.queued");
  expect(CARD_SOURCE).toContain("bg-amber-100");
});

test("a queued rating is handed to the drain, not left for an `online` event this tab will never get", () => {
  const fn = RATINGS_SOURCE.slice(RATINGS_SOURCE.indexOf("export async function submitOrQueueDesignRating("));
  expect(RATINGS_SOURCE, "the shared pass, not a private replay").toContain(
    'import { queueOffline, syncOutbox } from "@/lib/offline";'
  );
  expect(fn, "the queue asks the pass that exists to carry it").toContain("await syncOutbox()");
  expect(fn).toContain('typeof navigator !== "undefined" && navigator.onLine === false');
  expect(fn, "the pass's own report decides what the card says").toContain(
    "pass.synced > 0 && pass.remaining === 0"
  );
  expect(CARD_SOURCE, "the drained case says the repository has it").toContain("outcome.sent");
  expect(CARD_SOURCE, "and the still-queued case no longer waits on an event").toContain(
    "has now been uploaded"
  );
});

test("no panel claims a file was added when the host has just said it was not", () => {
  expect(PANEL_SOURCE, "the contract is named and argued in one place").toContain(
    "export type AttachAnswer = void | boolean | Promise<void | boolean>;"
  );
  expect(HOST_SOURCE).toContain(
    "files: File[], what: (landedCount: number) => string): Promise<boolean>"
  );
  const refusals = HOST_SOURCE.match(/refuse\("(?:sketch|prototype)"\);\s+return false;/g);
  expect(refusals, "all four handlers answer, not just the two that were reported").toHaveLength(4);
  expect(MODEL_SOURCE, "the model file").toContain("const handed = await onAttachModel(chosen);");
  expect(MODEL_SOURCE, "the turntable frames").toContain("const handed = await onAttachTurntable(frames);");
  expect(MODEL_SOURCE.match(/if \(handed === false\) return;/g)).toHaveLength(2);
});

test("the turntable can be filled from the tab that advises filling it", () => {
  expect(PANEL_SOURCE, "the callback the panel offers").toContain(
    "onAttachTurntable?: (files: File[]) => AttachAnswer;"
  );
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
