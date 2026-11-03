import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { syncOutcome } from "@/components/designworkshop/DraftSyncBanner";
import { syncPassLanded, syncPassNote } from "@/components/sketches/syncNote";
import { ApiError, PASSWORD_CHANGE_REQUIRED_HEADER } from "@/lib/api";
import { stageRefusalIsPassLevel } from "@/lib/designWorkshopStore";
import { FAILURE_TRIAGE, isCredentialExpiry } from "@/lib/failureTriage";
import { MediaBatchError } from "@/lib/media";

const DRAFTS = readFileSync(join(__dirname, "..", "lib", "designWorkshopStore.ts"), "utf8");

const expired = () => new ApiError(401, "Could not validate credentials", null);

const gated = () =>
  new ApiError(401, "Choose a new password to continue.", null, new Headers({ [PASSWORD_CHANGE_REQUIRED_HEADER]: "1" }));

function asBatchFailure(error: unknown, name = "loom.jpg"): MediaBatchError {
  return new MediaBatchError(`All 1 media file(s) failed to upload (${name}).`, [
    { name, error: error instanceof Error ? error.message : String(error), cause: error }
  ]);
}

function runBody(): string {
  const at = DRAFTS.indexOf("async function runSync(): Promise<DwSyncResult> {");
  expect(at, "runSync was located").toBeGreaterThan(-1);
  return DRAFTS.slice(at);
}

test("the triage row the drain obeys is the one that keeps the work", () => {
  expect(FAILURE_TRIAGE["credential-expired"].drain).toBe("stop-and-ask-for-sign-in");
  expect(isCredentialExpiry(expired())).toBe(true);
});

test("a plain 401 on a stage is handed to the pass, never recorded against the stage", () => {
  expect(stageRefusalIsPassLevel(expired())).toBe(true);
  expect(stageRefusalIsPassLevel(asBatchFailure(expired()))).toBe(true);
  expect(stageRefusalIsPassLevel(gated())).toBe(true);
});

test("the refusals that are about one stage stay on that stage", () => {
  expect(stageRefusalIsPassLevel(new ApiError(403, "You are this workshop's inspector, so you can read it but not change it.", null))).toBe(false);
  expect(stageRefusalIsPassLevel(new ApiError(422, "amount: not a valid number", null))).toBe(false);
  expect(stageRefusalIsPassLevel(new ApiError(500, "lone surrogate", null))).toBe(false);
});

test("the pass-level catch stops on an expired sign-in before anything is written", () => {
  const run = runBody();
  const caught = run.slice(run.indexOf("lastSyncedAt: Date.now(), failure: null }));"));
  const gate = caught.indexOf("if (isPasswordChangeRefusal(error)) {");
  const expiry = caught.indexOf("if (isCredentialExpiry(error)) {");
  expect(gate, "the gate's stop was located").toBeGreaterThan(-1);
  expect(expiry, "the expiry's stop was located").toBeGreaterThan(-1);
  expect(gate).toBeLessThan(expiry);
  expect(expiry).toBeLessThan(caught.indexOf("await mutate(draft.localId"));
  expect(caught.slice(expiry, expiry + 200)).toContain("result.credentialExpired = true;");
  expect(caught.slice(expiry, expiry + 200)).toContain("break;");
});

test("the photograph leg hands an expired sign-in on instead of recording it against the file", () => {
  const run = runBody();
  const rethrow = run.indexOf("if (isUnreachable(error) || isCredentialExpiry(error)) throw error;");
  expect(rethrow, "the rethrow was located").toBeGreaterThan(-1);
  expect(rethrow).toBeLessThan(run.indexOf("await noteMediaFailure(media.id, error);"));
});

test("the stage arm asks the pass-level question before it records anything", () => {
  const run = runBody();
  const ask = run.indexOf("if (!answered || stageRefusalIsPassLevel(error)) throw error;");
  expect(ask, "the stage arm's rethrow was located").toBeGreaterThan(-1);
  expect(ask).toBeLessThan(run.indexOf("await noteStageFailure(", ask));
});

const idle = { workshopsCreated: 0, stagesSent: 0, mediaUploaded: 0, failed: 0, pending: 2, stoppedOffline: false };

test("the draft banner says the sign-in expired — not no connection, not nothing to send", () => {
  const stopped = syncOutcome({ ...idle, credentialExpired: true });
  expect(stopped.kind).toBe("expired");
  expect(stopped.title).toBe("Your sign-in has expired");
  expect(stopped.tone).toBe("error");
  expect(syncOutcome({ ...idle, failed: 1, credentialExpired: true }).kind).toBe("refused");
  const partly = syncOutcome({ ...idle, stagesSent: 2, credentialExpired: true });
  expect(partly.kind).toBe("sent");
  expect(partly.tone).toBe("error");
});

test("the sketches screen says which session stop it was, and never claims the work landed", () => {
  const expiredPass = { ...idle, pending: 1, credentialExpired: true };
  const gatedPass = { ...idle, pending: 1, passwordChangeRequired: true };
  expect(syncPassNote(expiredPass, "this file is")).toBe(
    "Saved on this device. Your sign-in has expired, so it will upload once you sign in again."
  );
  expect(syncPassNote(gatedPass, "this file is")).toBe(
    "Saved on this device. It will upload once your new password is set."
  );
  expect(syncPassNote(expiredPass, "this file is")).not.toContain("Another sync is already running");
  expect(syncPassNote(gatedPass, "this file is")).not.toContain("Another sync is already running");
  expect(syncPassLanded({ ...idle, pending: 0, credentialExpired: true })).toBe(false);
  expect(syncPassLanded({ ...idle, pending: 0, passwordChangeRequired: true })).toBe(false);
});
