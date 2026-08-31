import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DW_LOCAL_DRAFT_LINK_PROMPT,
  DW_LOCAL_DRAFT_UNLINKED,
  DW_LOCAL_START_ACTION,
  DW_LOCAL_START_NOTE,
  DwCreateNotPermittedError,
  classifyDraftStart,
  createLocalDraft,
  createMustBeDeclined,
  setDraftSessionUser
} from "@/lib/designWorkshopStore";

/**
 * A DESIGNER WITH NO SIGNAL MAY START A WORKSHOP ON THIS DEVICE — AND IT MAY NEVER BECOME ONE.
 *
 * ── THE CLAUSE THIS PINS, VERBATIM ───────────────────────────────────────────────────────────────
 *
 * *"the designers do not get to create a designer workshop, they simply get to participate in
 * one... **Instead if they are offline, let them create one for the time being, and when the
 * internet comes back up, let them link it to one of the workshops that they have access to.**"*
 *
 * ── WHY THIS FILE EXISTS BESIDE `design-workshop-create-gate-unit.spec.ts` RATHER THAN INSIDE IT ──
 *
 * That file pins the rule this one carves an exception out of, and every assertion in it still
 * holds unchanged — `mayMintLocalWorkshop({ known: true, role: "DESIGNER" })` is still `false`, and
 * `createLocalDraft` still refuses a designer on a reachable device. Keeping the two apart is what
 * makes that visible: an edit that widens the create rule breaks THAT file, and an edit that
 * withdraws the offline arm breaks THIS one. Folded together, a future reader would have to work
 * out which of the two rules a failure was about.
 *
 * ── THE ASSERTION THAT MATTERS MOST IS THE ONE ABOUT THE SYNC PASS ───────────────────────────────
 *
 * The draft a designer mints here is byte-identical to an admin's. What separates them is what the
 * drain does, and the drain decides on the ROLE AT DRAIN TIME through `createMustBeDeclined` — so a
 * designer's draft takes the ADOPT path and can never be posted. `a designer's draft is never
 * posted, whoever minted it` is that assertion, and it is the one that keeps this feature from
 * becoming a designer-created workshop by another name.
 *
 * Run: `npx playwright test e2e/design-workshop-offline-start-unit.spec.ts --reporter=line`
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

/** The handset's twin of every string and every rule asserted below. */
const KOTLIN = "../android/app/src/main/java/com/designprototype/workshop/data/DwWorkshopCreation.kt";

/* ── The tri-state ─────────────────────────────────────────────────────────────────────────── */

test("an account that may create is answered CREATE, connection or not", () => {
  // ASKED FIRST, AND THAT ORDER IS LOAD-BEARING. An admin's draft is destined to become a workshop
  // on the next pass whether it was started in an office or a courtyard, and nothing below may
  // narrow that — an admin routed to "link-later" would be told to go and ask themselves for a
  // workshop they are entitled to open.
  expect(classifyDraftStart({ mayCreate: true, mayRunWorkshops: true, reachable: true })).toBe("create");
  expect(classifyDraftStart({ mayCreate: true, mayRunWorkshops: true, reachable: false })).toBe("create");
});

test("a designer WITH a connection is refused — the create rule is untouched online", () => {
  /*
    THE HALF THAT MUST NOT MOVE. With signal there are two better answers than an unlinked draft:
    open a workshop the designer already holds, or ask an admin for one — and
    `DESIGN_WORKSHOP_CREATE_REFUSAL` names both, with `ContinueOnAllocatedWorkshop` beneath it as the
    control for the first. An unlinked draft minted with a connection would be a second, worse copy
    of a workshop that could have been the real one from the first keystroke.
  */
  expect(classifyDraftStart({ mayCreate: false, mayRunWorkshops: true, reachable: true })).toBe("refused");
});

test("a designer with NO connection is answered LINK-LATER — the owner's clause", () => {
  expect(classifyDraftStart({ mayCreate: false, mayRunWorkshops: true, reachable: false })).toBe("link-later");
});

test("an account that may not run a workshop at all is refused, reachable or not", () => {
  /*
    THE ARM IS FOR THE PEOPLE WHO RUN WORKSHOPS AND NOBODY ELSE. `canRunDesignWorkshops` is a SET
    ({DESIGNER, ADMIN, MASTER_ADMIN}), so a PROFESSOR outranks a designer and is still outside it —
    and a workshop minted by an account that can never open one is a record with no future at all,
    since adoption needs a workshop the account may be named on.
  */
  expect(classifyDraftStart({ mayCreate: false, mayRunWorkshops: false, reachable: false })).toBe("refused");
  expect(classifyDraftStart({ mayCreate: false, mayRunWorkshops: false, reachable: true })).toBe("refused");
});

/* ── The gate on the one function that writes ──────────────────────────────────────────────── */

test("createLocalDraft still refuses a designer on a device that can reach the repository", async () => {
  // The existing gate spec asserts this too, and it is repeated here on purpose: this file adds an
  // arm to that function, and the assertion that the arm did not swallow the rule belongs beside
  // the arm. Node has no `navigator.onLine`, so `deviceLooksOffline()` is false here — which is the
  // reachable case.
  setDraftSessionUser("designer-1", "DESIGNER");
  await expect(createLocalDraft({ title: "Ikat, Barpali" })).rejects.toThrow(DwCreateNotPermittedError);
});

test("with the caller's own unreachable evidence, the same designer gets PAST the gate", async () => {
  /*
    WHAT THIS CAN AND CANNOT ASSERT, SAID PLAINLY. There is no IndexedDB in this process, so the
    write itself cannot be exercised here — what is being pinned is that the failure stops being a
    PERMISSION failure. A refusal thrown before the transaction and a storage error thrown inside it
    are the two outcomes, and this asserts the gate handed over.

    `serverUnreachable` IS EVIDENCE AND NOT A LICENCE — see `DwLocalDraftOptions.serverUnreachable`.
    The next test is the half that shows it cannot be used to widen the rule.
  */
  setDraftSessionUser("designer-1", "DESIGNER");
  const thrown = await createLocalDraft({ title: "Ikat, Barpali" }, { serverUnreachable: true }).catch(
    (err: unknown) => err
  );
  expect(thrown).toBeInstanceOf(Error);
  expect(thrown).not.toBeInstanceOf(DwCreateNotPermittedError);
});

test("`serverUnreachable` cannot let an account outside the workshop set through", async () => {
  // A researcher holding the same evidence is still refused, which is what makes the key evidence
  // about the NETWORK rather than a way round the rule.
  setDraftSessionUser("researcher-1", "RESEARCHER");
  await expect(
    createLocalDraft({ title: "Ikat, Barpali" }, { serverUnreachable: true })
  ).rejects.toThrow(DwCreateNotPermittedError);
});

test("a signed-out browser is refused even with no connection", async () => {
  // The tri-state's whole reason for existing: `AuthProvider.logout` does not clear this store, so
  // without this a signed-out laptop in a courtyard could write a workshop that no account owns and
  // no pass can ever send.
  setDraftSessionUser(null, null);
  await expect(
    createLocalDraft({ title: "Ikat, Barpali" }, { serverUnreachable: true })
  ).rejects.toThrow(DwCreateNotPermittedError);
});

/* ── And what the drain does with what was minted ──────────────────────────────────────────── */

test("a designer's draft is never posted, whoever minted it", () => {
  /*
    THE INVARIANT THIS WHOLE FEATURE HANGS ON. `runSync` asks `createMustBeDeclined` with the role
    AS OF THE DRAIN, so a draft minted under the offline arm meets exactly the same refusal every
    stranded designer draft has met since the create rule shipped: it is not posted, it is not
    retried into a wall, and the message names "Move into a workshop".

    IF THIS EVER RETURNS FALSE, the create arm collects the server's 403 — which is not transient —
    and the entry parks for ever behind a "Try again" that can only fetch the same 403.
  */
  expect(createMustBeDeclined({ alreadyOnServer: false, sessionMayCreate: false })).toBe(true);
  // And the one case that must NOT be declined, restated here because this file's arm makes drafts
  // that look like the stranded ones: a create that already LANDED needs no create.
  expect(createMustBeDeclined({ alreadyOnServer: true, sessionMayCreate: false })).toBe(false);
});

/* ── Both clients, one set of words ────────────────────────────────────────────────────────── */

test("the four link-later strings are byte-for-byte the handset's", () => {
  /*
    A designer moves between the two clients mid-workshop, and §1 of the frontend contract makes
    Android the authority on what a thing is CALLED. These four are declared in both files as
    literals, so nothing but a test can hold them together — the same treatment
    `DESIGN_WORKSHOP_CREATE_REFUSAL` gets, and for the same reason: a rule worded differently
    depending on which device is in your hand is not a rule, it is two rumours.
  */
  const kotlin = read(KOTLIN);
  for (const line of [
    DW_LOCAL_START_ACTION,
    DW_LOCAL_START_NOTE,
    DW_LOCAL_DRAFT_UNLINKED,
    DW_LOCAL_DRAFT_LINK_PROMPT
  ]) {
    expect(kotlin, `the handset must carry: ${line}`).toContain(line);
  }
});

test("the note says it is not a workshop yet, which is the fact a fortnight rests on", () => {
  /*
    NOT A STYLE ASSERTION. "Saved offline" describes every other record in this app, all of which
    send themselves; this one cannot, and a designer who reads it as the usual offline banner waits
    for a sync that is never coming. The words may change; the claim may not.
  */
  expect(DW_LOCAL_START_NOTE).toContain("Not a workshop yet");
  expect(DW_LOCAL_DRAFT_UNLINKED).toContain("Not a workshop yet");
  expect(DW_LOCAL_DRAFT_LINK_PROMPT).toContain("workshops");
});

test("the handset's tri-state is the same three lines as the browser's", () => {
  /*
    ASSERTED AS SOURCE BECAUSE THERE IS NO WAY TO RUN THE KOTLIN FROM HERE, and the failure being
    guarded against is visible in the source anyway: a second implementation, or one client quietly
    dropping the `reachable` clause and minting unlinked drafts online. `DwWorkshopCreationTest`
    holds the Kotlin behaviour; this holds the shape against drift.
  */
  const kotlin = read(KOTLIN);
  expect(kotlin).toContain("fun dwClassifyDraftStart(");
  expect(kotlin).toContain("mayCreate -> DwDraftStart.CREATE");
  expect(kotlin).toContain("mayRunWorkshops && !reachable -> DwDraftStart.LINK_LATER");
  expect(kotlin).toContain("else -> DwDraftStart.REFUSED");
});
