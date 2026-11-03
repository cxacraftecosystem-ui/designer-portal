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

const ALL_ROLES: UserRole[] = [
  "MASTER_ADMIN",
  "ADMIN",
  "MINISTRY_ADMIN",
  "REGIONAL_DIRECTOR",
  "ASSISTANT_DIRECTOR",
  "PROFESSOR",
  "INSPECTOR",
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

test("an admin and the master admin may start a design workshop", () => {
  expect(canCreateDesignWorkshops(user("ADMIN"))).toBe(true);
  expect(canCreateDesignWorkshops(user("MASTER_ADMIN"))).toBe(true);
});

test("A DESIGNER MAY NOT — this is the rule, and it is the assertion that would fail if it were reverted", () => {
  expect(canCreateDesignWorkshops(user("DESIGNER"))).toBe(false);
});

test("nobody else may either, signed out included", () => {
  for (const role of ALL_ROLES) {
    if (role === "ADMIN" || role === "MASTER_ADMIN" || role === "MINISTRY_ADMIN") continue;
    expect(canCreateDesignWorkshops(user(role)), `${role} may not create a workshop`).toBe(false);
  }
  expect(canCreateDesignWorkshops(null)).toBe(false);
  expect(canCreateDesignWorkshops(undefined)).toBe(false);
});

test("the create set is a STRICT subset of the run set", () => {
  for (const role of DESIGN_WORKSHOP_CREATOR_ROLES) expect(DESIGN_WORKSHOP_ROLES).toContain(role);
  expect(DESIGN_WORKSHOP_ROLES).toContain("DESIGNER");
  expect(DESIGN_WORKSHOP_CREATOR_ROLES).not.toContain("DESIGNER");
});

test("A DESIGNER KEEPS EVERY CAPABILITY EXCEPT STARTING ONE", () => {
  expect(canRunDesignWorkshops(user("DESIGNER"))).toBe(true);
  expect(canRunDesignWorkshops(user("ADMIN"))).toBe(true);
  expect(canRunDesignWorkshops(user("MASTER_ADMIN"))).toBe(true);
  expect(canRunDesignWorkshops(user("PROFESSOR"))).toBe(false);
});

test("the refusal names who can create one and what to do instead", () => {
  const copy = DESIGN_WORKSHOP_CREATE_REFUSAL.toLowerCase();
  expect(copy).toContain("admin");
  expect(copy).toContain("ask an admin");
  expect(copy).toContain("22 stages");
  expect(copy).toContain("report");
});

test("a session nobody has described yet is NOT refused", () => {
  expect(mayMintLocalWorkshop({ known: false, role: null })).toBe(true);
  expect(mayMintLocalWorkshop({ known: false, role: "DESIGNER" })).toBe(true);
});

test("a SIGNED-OUT browser may not mint a workshop, and that is why the tri-state is worth having", () => {
  expect(mayMintLocalWorkshop({ known: true, role: null })).toBe(false);
});

test("a signed-in designer may not mint one; an admin may", () => {
  expect(mayMintLocalWorkshop({ known: true, role: "DESIGNER" })).toBe(false);
  expect(mayMintLocalWorkshop({ known: true, role: "PROFESSOR" })).toBe(false);
  expect(mayMintLocalWorkshop({ known: true, role: "ADMIN" })).toBe(true);
  expect(mayMintLocalWorkshop({ known: true, role: "MASTER_ADMIN" })).toBe(true);
});

test("createLocalDraft REFUSES a designer, before it touches storage at all", async () => {
  setDraftSessionUser("designer-1", "DESIGNER");
  await expect(createLocalDraft({ title: "Ikat, Barpali" })).rejects.toThrow(DwCreateNotPermittedError);
  await expect(createLocalDraft({ title: "Ikat, Barpali" })).rejects.toThrow(/ask an admin/i);
});

test("the refusal a designer reads offline is the SAME sentence the server sends", () => {
  expect(new DwCreateNotPermittedError().message).toBe(DESIGN_WORKSHOP_CREATE_REFUSAL);
});

test("the sync pass declines to create when this session may not", () => {
  expect(createMustBeDeclined({ alreadyOnServer: false, sessionMayCreate: false })).toBe(true);
});

test("A DRAFT WHOSE CREATE ALREADY LANDED IS NOT REFUSED — this is the bug this function exists for", () => {
  expect(createMustBeDeclined({ alreadyOnServer: true, sessionMayCreate: false })).toBe(false);
});

test("an account that may create is never declined, landed or not", () => {
  expect(createMustBeDeclined({ alreadyOnServer: false, sessionMayCreate: true })).toBe(false);
  expect(createMustBeDeclined({ alreadyOnServer: true, sessionMayCreate: true })).toBe(false);
});

test("a draft with no server record is the one that needs a workshop", () => {
  expect(localDraftNeedsAWorkshop(draft())).toBe(true);
  expect(localDraftNeedsAWorkshop(draft({ remoteId: "cly7realserverid" }))).toBe(false);
});

test("adopting withdraws every stage's claim to have READ the server", () => {
  const moved = adoptedIntoWorkshop(
    draft({ stages: { WORKSHOP_SETUP: stage({ serverLoadedAt: 1_760_000_000_000 }) } }),
    "cly7realserverid"
  );
  expect(moved.stages.WORKSHOP_SETUP.serverLoadedAt).toBeNull();
});

test("ADOPTING CANNOT SWEEP ROWS OUT OF THE WORKSHOP IT MOVES INTO", () => {
  const moved = adoptedIntoWorkshop(
    draft({ stages: { WORKSHOP_SETUP: stage({ removedFrom: ["participants"] }) } }),
    "cly7realserverid"
  );
  expect(moved.stages.WORKSHOP_SETUP.removedFrom).toEqual([]);
});

test("adopting points the draft at the workshop and clears what is no longer outstanding", () => {
  const moved = adoptedIntoWorkshop(draft({ failure: { message: "x", permanent: true, at: 1, attempts: 1 } as never }), "cly7realserverid");
  expect(moved.remoteId).toBe("cly7realserverid");
  expect(moved.createSentAt).toBeNull();
  expect(moved.lastSyncedAt).toBeNull();
  expect(moved.failure).toBeNull();
});

test("adopting KEEPS what the designer typed, and keeps it owed", () => {
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
  const before = draft({ stages: { WORKSHOP_SETUP: stage({ serverLoadedAt: 5, removedFrom: ["participants"] }) } });
  adoptedIntoWorkshop(before, "cly7realserverid");
  expect(before.remoteId).toBeNull();
  expect(before.stages.WORKSHOP_SETUP.serverLoadedAt).toBe(5);
  expect(before.stages.WORKSHOP_SETUP.removedFrom).toEqual(["participants"]);
});
