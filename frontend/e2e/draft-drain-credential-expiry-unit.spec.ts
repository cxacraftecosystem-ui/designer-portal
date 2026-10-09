import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { syncOutcome } from "@/components/designworkshop/DraftSyncBanner";
import { syncPassLanded, syncPassNote } from "@/components/sketches/syncNote";
import { ApiError, PASSWORD_CHANGE_REQUIRED_HEADER } from "@/lib/api";
import { stageRefusalIsPassLevel } from "@/lib/designWorkshopStore";
import { FAILURE_TRIAGE, isCredentialExpiry } from "@/lib/failureTriage";
import { MediaBatchError } from "@/lib/media";

/**
 * AN EXPIRED SIGN-IN KEEPS A DESIGNER'S WORK QUEUED. IT IS NEVER A REFUSAL OF THE WORK.
 *
 * ── THE DEFECT ──────────────────────────────────────────────────────────────────────────────────
 *
 * The design-workshop draft drain decided "is this about the stage, or about the pass?" with
 * `stageRefusalIsPassLevel`, and a PLAIN 401 — the token is finished, which a fortnight in the field
 * outlasts and a second tab signing out causes too — answered "about the stage". So the stage arm
 * wrote "The repository refused stage …: Could not validate credentials … it will keep being refused
 * until the answer that caused it is corrected" onto every dirty stage, `permanent: true`, and
 * `blocksRetry` held each one shut after the designer had signed back in. A 401 thrown by any other
 * arm reached the pass-level catch and was written onto the WHOLE workshop the same way, and a 401 on
 * the photograph leg onto the photograph. Only the password gate's 401 had been taught otherwise.
 *
 * The records outbox (`lib/offline.ts`) has stopped on a 401 without marking anything since long
 * before, and `lib/failureTriage.ts` states the row both drains are meant to obey: credential-expired
 * → "stop the pass, mark nothing, and ask for a sign-in". This file holds the draft drain to it.
 *
 * ── WHAT IS ASSERTED, AND HOW ───────────────────────────────────────────────────────────────────
 *
 *   1. the classifier, called for real, bare and wrapped;
 *   2. the drain ON ITS SOURCE — the pass needs IndexedDB, which this process does not have, so the
 *      order of the questions in its catches is what is pinned (the same choice
 *      `gated-session-watchers-unit.spec.ts` makes for the gated 401);
 *   3. what the banner and the sketches screen SAY about such a pass, called for real.
 *
 * ⚠ LINE-ENDING AGNOSTIC: the source checks use `indexOf` on single lines only.
 */

const DRAFTS = readFileSync(join(__dirname, "..", "lib", "designWorkshopStore.ts"), "utf8");

/** The 401 an expired token gets: no header, so it is NOT the password gate. */
const expired = () => new ApiError(401, "Could not validate credentials", null);

/** The password gate's 401, for the cases where the two must agree. */
const gated = () =>
  new ApiError(401, "Choose a new password to continue.", null, new Headers({ [PASSWORD_CHANGE_REQUIRED_HEADER]: "1" }));

/** A one-file batch that failed, as the photograph leg receives it. */
function asBatchFailure(error: unknown, name = "loom.jpg"): MediaBatchError {
  return new MediaBatchError(`All 1 media file(s) failed to upload (${name}).`, [
    { name, error: error instanceof Error ? error.message : String(error), cause: error }
  ]);
}

/** The body of `runSync`, from its declaration to the end of the file. */
function runBody(): string {
  const at = DRAFTS.indexOf("async function runSync(): Promise<DwSyncResult> {");
  expect(at, "runSync was located").toBeGreaterThan(-1);
  return DRAFTS.slice(at);
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The classifier
 * ──────────────────────────────────────────────────────────────────────────── */

test("the triage row the drain obeys is the one that keeps the work", () => {
  // If this row ever changes, the drain's behaviour below has to be re-decided with it.
  expect(FAILURE_TRIAGE["credential-expired"].drain).toBe("stop-and-ask-for-sign-in");
  expect(isCredentialExpiry(expired())).toBe(true);
});

test("a plain 401 on a stage is handed to the pass, never recorded against the stage", () => {
  expect(stageRefusalIsPassLevel(expired())).toBe(true);
  // Wrapped, as a stage arm never throws it today and a photograph leg always does.
  expect(stageRefusalIsPassLevel(asBatchFailure(expired()))).toBe(true);
  // And the gate's 401 still goes the same way — it is inside the same row.
  expect(stageRefusalIsPassLevel(gated())).toBe(true);
});

test("the refusals that are about one stage stay on that stage", () => {
  // The whole point of the per-stage arm: one bad answer cannot strand the other twenty-one. A 403
  // is a decision about this account and this workshop, not a finished token.
  expect(stageRefusalIsPassLevel(new ApiError(403, "You are this workshop's inspector, so you can read it but not change it.", null))).toBe(false);
  expect(stageRefusalIsPassLevel(new ApiError(422, "amount: not a valid number", null))).toBe(false);
  expect(stageRefusalIsPassLevel(new ApiError(500, "lone surrogate", null))).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The drain, on its source
 * ──────────────────────────────────────────────────────────────────────────── */

test("the pass-level catch stops on an expired sign-in before anything is written", () => {
  const run = runBody();
  const caught = run.slice(run.indexOf('THE TEST IS "DID THE SERVER ANSWER", NOT `isTransient`.'));
  const gate = caught.indexOf("if (isPasswordChangeRefusal(error)) {");
  const expiry = caught.indexOf("if (isCredentialExpiry(error)) {");
  expect(gate, "the gate's stop was located").toBeGreaterThan(-1);
  expect(expiry, "the expiry's stop was located").toBeGreaterThan(-1);
  // The gate is the narrower reading and keeps its own sentence, so it is asked first.
  expect(gate).toBeLessThan(expiry);
  // Both before the arm that writes a refusal onto the whole workshop, and the expiry says so.
  expect(expiry).toBeLessThan(caught.indexOf("await mutate(draft.localId"));
  expect(caught.slice(expiry, expiry + 200)).toContain("result.credentialExpired = true;");
  expect(caught.slice(expiry, expiry + 200)).toContain("break;");
});

test("the photograph leg hands an expired sign-in on instead of recording it against the file", () => {
  const run = runBody();
  const rethrow = run.indexOf("if (isUnreachable(error) || isCredentialExpiry(error)) throw error;");
  expect(rethrow, "the rethrow was located").toBeGreaterThan(-1);
  // And it comes before the line that would mark the photograph.
  expect(rethrow).toBeLessThan(run.indexOf("await noteMediaFailure(media.id, error);"));
});

test("the stage arm asks the pass-level question before it records anything", () => {
  const run = runBody();
  const ask = run.indexOf("if (!answered || stageRefusalIsPassLevel(error)) throw error;");
  expect(ask, "the stage arm's rethrow was located").toBeGreaterThan(-1);
  expect(ask).toBeLessThan(run.indexOf("await noteStageFailure(", ask));
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. What is said about such a pass
 * ──────────────────────────────────────────────────────────────────────────── */

const idle = { workshopsCreated: 0, stagesSent: 0, mediaUploaded: 0, failed: 0, pending: 2, stoppedOffline: false };

test("the draft banner says the sign-in expired — not no connection, not nothing to send", () => {
  const stopped = syncOutcome({ ...idle, credentialExpired: true });
  expect(stopped.kind).toBe("expired");
  // The outbox banner's own words for the same fact about the same session.
  expect(stopped.title).toBe("Your sign-in has expired");
  expect(stopped.tone).toBe("error");
  // A refusal recorded earlier in the same pass is still the one thing that names an item.
  expect(syncOutcome({ ...idle, failed: 1, credentialExpired: true }).kind).toBe("refused");
  // Something sent before the token ran out is still reported as sent — in the error tone, because
  // the rest is waiting on a person.
  const partly = syncOutcome({ ...idle, stagesSent: 2, credentialExpired: true });
  expect(partly.kind).toBe("sent");
  expect(partly.tone).toBe("error");
});

test("the sketches screen says which session stop it was, and never claims the work landed", () => {
  const expiredPass = { ...idle, pending: 1, credentialExpired: true };
  const gatedPass = { ...idle, pending: 1, passwordChangeRequired: true };
  expect(syncPassNote(expiredPass, "this file is")).toBe(
    "Saved on this device. Your sign-in has expired, so it sends itself once you sign in again."
  );
  expect(syncPassNote(gatedPass, "this file is")).toBe(
    "Saved on this device. It sends itself once your new password is set."
  );
  // Neither is "another sync is already running", which is what the honest pending count used to
  // be read as.
  expect(syncPassNote(expiredPass, "this file is")).not.toContain("Another sync is already running");
  expect(syncPassNote(gatedPass, "this file is")).not.toContain("Another sync is already running");
  // And neither is a landing, even on a pass that had nothing else outstanding.
  expect(syncPassLanded({ ...idle, pending: 0, credentialExpired: true })).toBe(false);
  expect(syncPassLanded({ ...idle, pending: 0, passwordChangeRequired: true })).toBe(false);
});
