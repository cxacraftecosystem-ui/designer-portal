import { expect, test } from "@playwright/test";

import {
  DESIGN_WORKSHOP_CREATE_REFUSAL,
  DESIGN_WORKSHOP_CREATOR_ROLES,
  DESIGN_WORKSHOP_ROLES,
  canCreateDesignWorkshops,
  canRunDesignWorkshops
} from "@/lib/permissions";
import {
  DwCreateNotPermittedError,
  adoptedIntoWorkshop,
  createLocalDraft,
  createMustBeDeclined,
  emptyStage,
  localDraftNeedsAWorkshop,
  mayMintLocalWorkshop,
  setDraftSessionUser,
  type DwDraft,
  type DwDraftStage
} from "@/lib/designWorkshopStore";
import type { User, UserRole } from "@/lib/types";

/**
 * A DESIGNER MAY NOT START A DESIGN WORKSHOP — AND MUST FIND THAT OUT BEFORE THE FIELDWORK, NOT
 * AFTER IT.
 *
 * THE RULE. "designers cannot create workshops (only admins/master admins can) — designers create
 * records under existing workshops." A workshop is not a record; it is the container a fortnight of
 * records lives in and the unit the ministry indexes and funds, so opening one is an administrative
 * act belonging to whoever holds the sanction order.
 *
 * WHY THE OFFLINE HALF IS THE HALF WORTH TESTING. `POST /design-workshops` is the gate that is
 * load-bearing, and it is asserted on the server (`backend/tests/test_design_workshop_gate.py`).
 * But this app is built so that the first act of a fortnight in the field does not need a
 * connection: `createLocalDraft` mints a workshop in IndexedDB and the designer walks into it. A
 * permission enforced ONLY by the server therefore reads, from a courtyard, as no permission at
 * all — the designer starts a workshop on Monday, fills twenty-two stages of interviews and
 * photographs into it over two days, reaches signal on Wednesday and learns then that the record
 * can never be accepted. No message shown on Wednesday undoes that. So the refusal happens at the
 * FIRST act, with nothing typed and nothing lost, and these tests are what keep it there.
 *
 * THE OTHER HALF OF THE FILE IS THE HALF THAT MUST NOT HAVE MOVED. A narrowing applied one function
 * too widely takes a designer's stage edits with it, which would cost far more than this rule is
 * worth. `a designer keeps every capability except starting one` is that assertion.
 *
 * AND THE DRAFTS THAT WERE ALREADY ON THE DEVICE when this shipped are adopted rather than
 * stranded: `adoptedIntoWorkshop` is the transform that re-points one at a workshop an admin has
 * since created, and its two `null`s are the whole of its correctness.
 *
 * Run: `npx playwright test e2e/design-workshop-create-gate-unit.spec.ts --reporter=line`
 */

const ALL_ROLES: UserRole[] = [
  "MASTER_ADMIN",
  "ADMIN",
  "PROFESSOR",
  "DESIGNER",
  "RESEARCHER",
  "FIELD_CONTRIBUTOR",
  "CROWDSOURCE_VOLUNTEER"
];

function user(role: UserRole): User {
  return { id: "u1", email: "someone@example.org", name: "Someone", role };
}

function draft(over: Partial<DwDraft> = {}): DwDraft {
  return {
    localId: "dwlocal-abc",
    remoteId: null,
    header: { title: "Ikat, Barpali" },
    headerDirtyAt: 1_760_000_000_000,
    headerDirtyKeys: ["title"],
    createSentAt: 1_760_000_000_500,
    stages: {},
    lastSyncedAt: null,
    failure: null,
    updatedAt: 1,
    ...over
  } as unknown as DwDraft;
}

function stage(over: Partial<DwDraftStage> = {}): DwDraftStage {
  return { ...emptyStage("WORKSHOP_SETUP"), ...over };
}

/* ── Who may start one ─────────────────────────────────────────────────────────────────────── */

test("an admin and the master admin may start a design workshop", () => {
  expect(canCreateDesignWorkshops(user("ADMIN"))).toBe(true);
  expect(canCreateDesignWorkshops(user("MASTER_ADMIN"))).toBe(true);
});

test("A DESIGNER MAY NOT — this is the rule, and it is the assertion that would fail if it were reverted", () => {
  // DESIGNER is inside `DESIGN_WORKSHOP_ROLES`, so every OTHER gate on this surface admits it. This
  // is the one that does not. If it ever starts returning true, the requirement has been silently
  // undone while every other design-workshop test still passes.
  expect(canCreateDesignWorkshops(user("DESIGNER"))).toBe(false);
});

test("nobody else may either, signed out included", () => {
  for (const role of ALL_ROLES) {
    if (role === "ADMIN" || role === "MASTER_ADMIN") continue;
    expect(canCreateDesignWorkshops(user(role)), `${role} may not create a workshop`).toBe(false);
  }
  expect(canCreateDesignWorkshops(null)).toBe(false);
  expect(canCreateDesignWorkshops(undefined)).toBe(false);
});

test("the create set is a STRICT subset of the run set", () => {
  // Related by containment, and the containment is the design: crossing them would let somebody
  // create a workshop they cannot then open, and equality would mean the create gate had been
  // widened back to the designer set.
  for (const role of DESIGN_WORKSHOP_CREATOR_ROLES) expect(DESIGN_WORKSHOP_ROLES).toContain(role);
  expect(DESIGN_WORKSHOP_ROLES).toContain("DESIGNER");
  expect(DESIGN_WORKSHOP_CREATOR_ROLES).not.toContain("DESIGNER");
});

test("A DESIGNER KEEPS EVERY CAPABILITY EXCEPT STARTING ONE", () => {
  // The guard against a narrowing applied one function too widely. `canRunDesignWorkshops` gates
  // the whole /design-workshops route tree, the stage forms, the capture aids and the report; a
  // designer who lost it would lose their fortnight of fieldwork to enforce a rule about a button.
  expect(canRunDesignWorkshops(user("DESIGNER"))).toBe(true);
  expect(canRunDesignWorkshops(user("ADMIN"))).toBe(true);
  expect(canRunDesignWorkshops(user("MASTER_ADMIN"))).toBe(true);
  // And the older rule this must not have disturbed: the run set is a SET, not a rank floor, so a
  // professor outranks a designer and is still outside it.
  expect(canRunDesignWorkshops(user("PROFESSOR"))).toBe(false);
});

test("the refusal names who can create one and what to do instead", () => {
  // Refusal copy is the first thing to rot when a message is reworded in a hurry, and this one is
  // read by somebody standing in a courtyard with participants in front of them. "You do not have
  // permission" would tell them to stop working; the truth is that everything they came to do still
  // works the moment an admin has opened the workshop, and the sentence has to say so.
  const copy = DESIGN_WORKSHOP_CREATE_REFUSAL.toLowerCase();
  expect(copy).toContain("admin");
  expect(copy).toContain("ask an admin");
  expect(copy).toContain("22 stages");
  expect(copy).toContain("report");
});

/* ── The offline path: the courtyard, not the server ───────────────────────────────────────── */

test("a session nobody has described yet is NOT refused", () => {
  // Three states, not two. Refusing while `GET /me` is still in flight would block an ADMIN from
  // starting a workshop for a rule that does not apply to them — a false refusal, which is how
  // people are taught to ignore real ones. Nothing gets through the window: the create form is not
  // rendered until `useAuth` has a user, and the server is the gate that is load-bearing.
  expect(mayMintLocalWorkshop({ known: false, role: null })).toBe(true);
  expect(mayMintLocalWorkshop({ known: false, role: "DESIGNER" })).toBe(true);
});

test("a SIGNED-OUT browser may not mint a workshop, and that is why the tri-state is worth having", () => {
  // `AuthProvider.logout` deliberately does not clear the draft store — the previous designer's
  // fortnight has to survive the handover on a shared field laptop — so without this a signed-out
  // browser could write a workshop into IndexedDB that no account owns and no pass can ever send.
  expect(mayMintLocalWorkshop({ known: true, role: null })).toBe(false);
});

test("a signed-in designer may not mint one; an admin may", () => {
  expect(mayMintLocalWorkshop({ known: true, role: "DESIGNER" })).toBe(false);
  expect(mayMintLocalWorkshop({ known: true, role: "PROFESSOR" })).toBe(false);
  expect(mayMintLocalWorkshop({ known: true, role: "ADMIN" })).toBe(true);
  expect(mayMintLocalWorkshop({ known: true, role: "MASTER_ADMIN" })).toBe(true);
});

test("createLocalDraft REFUSES a designer, before it touches storage at all", async () => {
  /*
    THE WHOLE POINT OF THE OFFLINE HALF, asserted end to end on the one function that mints a
    workshop on this device.

    "Before it touches storage" is not incidental — it is why this assertion can run here at all.
    There is no IndexedDB in this process, so a guard placed after the first `transact` would fail
    with a storage error rather than a refusal, and a designer in the field would get a workshop
    written to disk and a confusing message about the browser store.
  */
  setDraftSessionUser("designer-1", "DESIGNER");
  await expect(createLocalDraft({ title: "Ikat, Barpali" })).rejects.toThrow(DwCreateNotPermittedError);
  await expect(createLocalDraft({ title: "Ikat, Barpali" })).rejects.toThrow(/ask an admin/i);
});

test("the refusal a designer reads offline is the SAME sentence the server sends", () => {
  // Four surfaces say this: the server's 403, the list page's panel, this store, and the adoption
  // dialog. A refusal that names a different next move depending on where you met it is not a rule,
  // it is three rumours.
  expect(new DwCreateNotPermittedError().message).toBe(DESIGN_WORKSHOP_CREATE_REFUSAL);
});

/* ── What the sync pass does with a draft it may not create ────────────────────────────────── */

test("the sync pass declines to create when this session may not", () => {
  expect(createMustBeDeclined({ alreadyOnServer: false, sessionMayCreate: false })).toBe(true);
});

test("A DRAFT WHOSE CREATE ALREADY LANDED IS NOT REFUSED — this is the bug this function exists for", () => {
  /*
    THE DEFECT, WHICH THIS CHANGE SHIPPED AND THEN CAUGHT. Written first as "refuse whenever this
    session may not create", the guard also refused the draft that `resolveInterruptedCreate` had
    just FOUND on the server: a workshop opened under the old rule, whose create landed and whose
    answer this device never saw. Nothing needs creating for it — the pass only writes the id back —
    so refusing stranded an EXISTING workshop behind a permanent failure and told the designer to go
    and ask an admin for a workshop they already had, with a fortnight of stages sitting behind it.

    The two facts are independent and only one ordering of them is right, which is exactly why the
    decision is a named function with both of them in its signature rather than two `&&`s inline.
  */
  expect(createMustBeDeclined({ alreadyOnServer: true, sessionMayCreate: false })).toBe(false);
});

test("an account that may create is never declined, landed or not", () => {
  expect(createMustBeDeclined({ alreadyOnServer: false, sessionMayCreate: true })).toBe(false);
  expect(createMustBeDeclined({ alreadyOnServer: true, sessionMayCreate: true })).toBe(false);
});

/* ── The drafts that were already on the device ────────────────────────────────────────────── */

test("a draft with no server record is the one that needs a workshop", () => {
  expect(localDraftNeedsAWorkshop(draft())).toBe(true);
  expect(localDraftNeedsAWorkshop(draft({ remoteId: "cly7realserverid" }))).toBe(false);
});

test("adopting withdraws every stage's claim to have READ the server", () => {
  /*
    THE DEFECT THIS PREVENTS, IN FULL. `putDraftStage` stamps `serverLoadedAt` for free on any stage
    written while `remoteId` is null, on the true premise that a workshop the server has never heard
    of has nothing up there to overwrite. Adoption expires that premise harder than a create does:
    the target workshop was opened by an admin and `POST /design-workshops` has already seeded
    `workshopSetup` and `workshopPlan` singletons into it. Left standing, the stamp makes the first
    PUT omit `merge`, and `save_stage` replaces each singleton's `data` wholesale — destroying the
    seeded designer block in place, under a 200.
  */
  const moved = adoptedIntoWorkshop(
    draft({ stages: { WORKSHOP_SETUP: stage({ serverLoadedAt: 1_760_000_000_000 }) } }),
    "cly7realserverid"
  );
  expect(moved.stages.WORKSHOP_SETUP.serverLoadedAt).toBeNull();
});

test("ADOPTING CANNOT SWEEP ROWS OUT OF THE WORKSHOP IT MOVES INTO", () => {
  /*
    THE DANGEROUS HALF. `removedFrom` arms `replaceCollections` on the sync PUT and is the ONLY way
    a deletion reaches the server. On a never-synced draft every one of those deletions was of a row
    that has only ever existed on this device. Carried into an adoption they would arm a sweep
    against a workshop this browser has never read, and `save_stage` would delete rows belonging to
    whoever has been working in it. This assertion is the difference between adopting a workshop and
    emptying one.
  */
  const moved = adoptedIntoWorkshop(
    draft({ stages: { WORKSHOP_SETUP: stage({ removedFrom: ["participants"] }) } }),
    "cly7realserverid"
  );
  expect(moved.stages.WORKSHOP_SETUP.removedFrom).toEqual([]);
});

test("adopting points the draft at the workshop and clears what is no longer outstanding", () => {
  const moved = adoptedIntoWorkshop(draft({ failure: { message: "x", permanent: true, at: 1, attempts: 1 } as never }), "cly7realserverid");
  expect(moved.remoteId).toBe("cly7realserverid");
  // Nothing is outstanding from a create that will never be attempted; a stamp left behind would
  // send the next pass looking on the server for the answer to a request nobody sent.
  expect(moved.createSentAt).toBeNull();
  // Re-POINTED, not reconciled: this browser still has not read a row of the workshop it now names,
  // and `neverReconciled` reads exactly this pair to decide whether a 404 means "lost access" or
  // "never opened".
  expect(moved.lastSyncedAt).toBeNull();
  // The refusal that sent the designer to this control has been acted on.
  expect(moved.failure).toBeNull();
});

test("adopting KEEPS what the designer typed, and keeps it owed", () => {
  // The local header holds what was typed in the room — the title, the craft, the cluster, the
  // dates. Clearing `headerDirtyAt` would silently drop all of it in favour of whatever the admin
  // typed into the create form, so it stays owed and the ordinary PATCH arm sends it.
  const typed = draft({
    stages: { WORKSHOP_SETUP: stage({ singletons: { workshopSetup: { venue: "Weavers' hall" } } as never }) }
  });
  const moved = adoptedIntoWorkshop(typed, "cly7realserverid");
  expect(moved.headerDirtyAt).toBe(typed.headerDirtyAt);
  expect(moved.headerDirtyKeys).toEqual(["title"]);
  expect(moved.header.title).toBe("Ikat, Barpali");
  expect(moved.stages.WORKSHOP_SETUP.singletons).toEqual({ workshopSetup: { venue: "Weavers' hall" } });
});

test("adopting never mutates the draft it was handed", () => {
  // The store's writes are `mutate` transforms run inside one IndexedDB transaction; a transform
  // that edited its input in place would leave the in-memory cache holding half-applied state if the
  // transaction then aborted.
  const before = draft({ stages: { WORKSHOP_SETUP: stage({ serverLoadedAt: 5, removedFrom: ["participants"] }) } });
  adoptedIntoWorkshop(before, "cly7realserverid");
  expect(before.remoteId).toBeNull();
  expect(before.stages.WORKSHOP_SETUP.serverLoadedAt).toBe(5);
  expect(before.stages.WORKSHOP_SETUP.removedFrom).toEqual(["participants"]);
});
