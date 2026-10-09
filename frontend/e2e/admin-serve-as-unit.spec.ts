import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  officerOptionsForPost,
  officerSlotRefusal,
  officersOfferedTo
} from "@/app/(protected)/officers/pickers";
import {
  oversightEmptyState,
  oversightRefusalMeansNoPosts,
  type DwOfficer
} from "@/app/(protected)/officers/oversight";
import { ApiError } from "@/lib/api";
import {
  eligibleInspectorNotice,
  inspectionEmptyState,
  inspectionRefusalMeansNoPosts,
  inspectorAdministrationFailure
} from "@/lib/designWorkshopInspections";
import { viewerAdministrationFailure } from "@/lib/designWorkshopViewers";
import {
  ASSISTANT_DIRECTOR_HOLDER_ROLES,
  INSPECTION_HOLDER_ROLES,
  REGIONAL_DIRECTOR_HOLDER_ROLES,
  ROLES_BY_RANK,
  canAccessRoute,
  canAssignWorkshopOversight,
  canInspectDesignWorkshops,
  canReadWorkshopOversight,
  heldPostEditRefusal,
  heldPostReadPath,
  isAdmin,
  isDirectorateTier,
  isInspectorTier,
  oversightPostsUserMayHold,
  workshopPostsHeldBy
} from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * ADMINS SERVING AS DESIGNER, ASSISTANT DIRECTOR, REGIONAL DIRECTOR AND INSPECTOR — the web half of
 * the owner's ruling D3 of 2026-10-09.
 *
 * The three administering tiers — a Ministry Admin, an admin and the master admin — may be APPOINTED,
 * one workshop at a time, to any of the four posts, by somebody else. What this spec pins is the part
 * of that the browser decides:
 *
 *   1. The holder sets: who may be appointed to each post, held to the server's own sets read off
 *      disk, and the two read surfaces opening to every possible holder.
 *   2. The pickers: never the reader, every other row the server offered, and a disabled row's
 *      reason worked out per row.
 *   3. A post on a workshop makes that workshop read-only for its holder: which posts the reader
 *      holds, the sentence (the server's own `write_refusal`, word for word) and where to read it.
 *   4. An empty read surface is an ANSWER — "You do not hold any … posts" — and a refusal met by an
 *      administering tier is read as that, while a 409 from an appointment reaches the reader verbatim.
 *
 * ── WHY ONLY FOUR TIERS ARE NAMED HERE ──────────────────────────────────────────────────────────
 *
 * `backend/tests/test_role_ladder_parity.py` sweeps the client trees for files naming five or more
 * tiers and treats them as copies of the ladder. This file is not one: it asserts predicates. So the
 * two platform admin tiers are DERIVED — every rung of `ROLES_BY_RANK` that `isAdmin` admits — and
 * everybody else is the complement of what the ruling names, never a typed list.
 *
 * PURE NODE — no browser, no server, no database.
 * Run: `npx playwright test e2e/admin-serve-as-unit.spec.ts --reporter=line`
 */

const user = (role: UserRole, id = "u-reader"): User =>
  ({ id, email: `${id}@example.org`, name: id, role }) as User;

/** The two platform admin tiers, read off the ladder through `isAdmin` rather than typed. */
const PLATFORM_ADMINS = ROLES_BY_RANK.filter((role) => isAdmin(user(role)));

/** The ruling's three administering tiers: the Ministry Admin and the two platform admin tiers. */
const ADMINISTERING: UserRole[] = ["MINISTRY_ADMIN", ...PLATFORM_ADMINS];

/** Who may hold a post whose own tier is `tier`: that tier, and the three administering tiers. */
const holders = (tier: UserRole): Set<UserRole> => new Set<UserRole>([tier, ...ADMINISTERING]);

const MAY_INSPECT = holders("INSPECTOR");
const MAY_BE_ASSISTANT_DIRECTOR = holders("ASSISTANT_DIRECTOR");
const MAY_BE_REGIONAL_DIRECTOR = holders("REGIONAL_DIRECTOR");
const MAY_SUPERVISE = new Set<UserRole>([...MAY_BE_ASSISTANT_DIRECTOR, ...MAY_BE_REGIONAL_DIRECTOR]);

/** The lowest rung of the ladder: a tier the ruling appoints to nothing, chosen by rank. */
const NEVER_APPOINTED = ROLES_BY_RANK[ROLES_BY_RANK.length - 1];

/* ────────────────────────────────────────────────────────────────────────────
 * Reading the server's sets off disk
 * ──────────────────────────────────────────────────────────────────────────── */

const SERVICES = join(__dirname, "..", "..", "backend", "app", "services");
const MODULES = {
  posts: "design_workshop_posts.py",
  oversight: "design_workshop_oversight.py",
  inspectors: "design_workshop_inspectors.py"
} as const;
type ServerModule = keyof typeof MODULES;

/** Read lazily, so a renamed module fails the test that needs it rather than the whole file. */
function source(owner: ServerModule): string {
  return readFileSync(join(SERVICES, MODULES[owner]), "utf8");
}

/** The right-hand side of one module-level assignment, comment stripped — or null. */
function assignment(owner: ServerModule, name: string): string | null {
  const match = source(owner).match(new RegExp(`^${name}\\s*(?::[^=\\n]*)?=\\s*(.+)$`, "m"));
  return match ? match[1].replace(/\s+#.*$/, "").trim() : null;
}

/**
 * A frozenset expression evaluated the way Python would: literals, names (bare, or through the
 * `posts.` alias the services import `design_workshop_posts` under) and `|`. Anything else fails with
 * the text it could not read, which is the cue to widen this reader rather than to delete the test.
 */
function evaluate(expression: string, home: ServerModule, depth = 0): Set<string> {
  if (depth > 6) throw new Error(`frozenset expression nests too deeply: ${expression}`);
  const members = new Set<string>();
  for (const part of expression.split("|").map((piece) => piece.trim())) {
    const literal = part.match(/^frozenset\(\{([^}]*)\}\)$/);
    if (literal) {
      for (const token of literal[1].matchAll(/"([A-Z_]+)"/g)) members.add(token[1]);
      continue;
    }
    const named = part.match(/^(?:(posts)\.)?([A-Z][A-Z0-9_]*)$/);
    if (!named) throw new Error(`cannot read the set expression part \`${part}\``);
    const owner: ServerModule = named[1] ? "posts" : home;
    const rhs = assignment(owner, named[2]);
    if (!rhs) throw new Error(`${named[2]} is not assigned in ${MODULES[owner]}`);
    for (const member of evaluate(rhs, owner, depth + 1)) members.add(member);
  }
  return members;
}

function serverSet(owner: ServerModule, name: string): string[] {
  const rhs = assignment(owner, name);
  expect(rhs, `${name} is not declared in backend/app/services/${MODULES[owner]}`).toBeTruthy();
  return [...evaluate(rhs!, owner)].sort();
}

/**
 * The server's own 403 sentence for a post holder's write, `design_workshop_posts.write_refusal`,
 * read off disk with its labels filled in — so the web's sentence is held to the words, not to a
 * copy of them typed into this file.
 */
function serverWriteRefusal(labels: string): string {
  const body = source("posts").match(/def write_refusal\([\s\S]*?return \(([\s\S]*?)\n\s*\)/);
  expect(body, "design_workshop_posts.write_refusal could not be read").toBeTruthy();
  const pieces = [...body![1].matchAll(/\bf?"((?:[^"\\]|\\.)*)"/g)].map((piece) => piece[1]);
  const template = pieces.join("");
  expect(template, "write_refusal no longer interpolates the posts").toContain("{_labels(posts)}");
  return template.replace("{_labels(posts)}", labels);
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Who may be appointed, and the two read surfaces
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("who may be appointed to a post", () => {
  test("each holder set is its own tier plus the three administering tiers, and nothing else", () => {
    expect(PLATFORM_ADMINS).toHaveLength(2);
    expect(new Set(INSPECTION_HOLDER_ROLES)).toEqual(MAY_INSPECT);
    expect(new Set(ASSISTANT_DIRECTOR_HOLDER_ROLES)).toEqual(MAY_BE_ASSISTANT_DIRECTOR);
    expect(new Set(REGIONAL_DIRECTOR_HOLDER_ROLES)).toEqual(MAY_BE_REGIONAL_DIRECTOR);
    // The tiers who appoint are exactly the tiers who may be appointed beside each post's own tier.
    for (const role of ROLES_BY_RANK) {
      expect(canAssignWorkshopOversight(user(role)), role).toBe(ADMINISTERING.includes(role));
    }
  });

  test("the web holder sets are the server's, read off disk", () => {
    expect(INSPECTION_HOLDER_ROLES.slice().sort()).toEqual(serverSet("inspectors", "INSPECTION_HOLDER_ROLES"));
    expect(ASSISTANT_DIRECTOR_HOLDER_ROLES.slice().sort()).toEqual(
      serverSet("oversight", "ASSISTANT_DIRECTOR_HOLDER_ROLES")
    );
    expect(REGIONAL_DIRECTOR_HOLDER_ROLES.slice().sort()).toEqual(
      serverSet("oversight", "REGIONAL_DIRECTOR_HOLDER_ROLES")
    );
  });

  test("each post's slot takes its own tier and never the other post's", () => {
    // Filed in the wrong slot, the right name prints beside the wrong post on a ministry document.
    expect(MAY_BE_ASSISTANT_DIRECTOR.has("REGIONAL_DIRECTOR")).toBe(false);
    expect(MAY_BE_REGIONAL_DIRECTOR.has("ASSISTANT_DIRECTOR")).toBe(false);
    for (const role of ROLES_BY_RANK) {
      const expected = (["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"] as const).filter((post) =>
        (post === "ASSISTANT_DIRECTOR" ? MAY_BE_ASSISTANT_DIRECTOR : MAY_BE_REGIONAL_DIRECTOR).has(role)
      );
      expect(oversightPostsUserMayHold(user(role)), role).toEqual(expected);
    }
    expect(oversightPostsUserMayHold(null)).toEqual([]);
  });
});

test.describe("the two read surfaces open to every possible holder", () => {
  test("Workshops to inspect opens for whoever may be appointed to inspect — admins included", () => {
    for (const role of ROLES_BY_RANK) {
      const expected = MAY_INSPECT.has(role);
      expect(canInspectDesignWorkshops(user(role)), role).toBe(expected);
      expect(canAccessRoute(user(role), "/design-workshop-inspections"), role).toBe(expected);
      expect(canAccessRoute(user(role), "/design-workshop-inspections/w1"), role).toBe(expected);
    }
  });

  test("Workshops I monitor opens for whoever may be named in either post — admins included", () => {
    for (const role of ROLES_BY_RANK) {
      const expected = MAY_SUPERVISE.has(role);
      expect(canReadWorkshopOversight(user(role)), role).toBe(expected);
      expect(canAccessRoute(user(role), "/officers/monitored"), role).toBe(expected);
      expect(canAccessRoute(user(role), "/officers/monitored/w1"), role).toBe(expected);
    }
  });

  test("the TIER predicates stay the tiers: the walkthrough deck and the reading of a refusal", () => {
    // Opening a surface is no longer the same question as being OF the tier whose work it is, and the
    // two must not be read for each other: a platform admin may inspect and is not an inspector.
    for (const role of ROLES_BY_RANK) {
      expect(isInspectorTier(user(role)), role).toBe(role === "INSPECTOR");
      expect(isDirectorateTier(user(role)), role).toBe(
        role === "ASSISTANT_DIRECTOR" || role === "REGIONAL_DIRECTOR" || role === "MINISTRY_ADMIN"
      );
    }
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The appointment pickers
 * ──────────────────────────────────────────────────────────────────────────── */

const officer = (id: string, role: UserRole, capacities: DwOfficer["capacities"]): DwOfficer => ({
  id,
  name: id,
  email: `${id}@example.org`,
  role,
  capacities
});

test.describe("the Assistant Director and Regional Director pickers", () => {
  const reader = officer("u-reader", "MINISTRY_ADMIN", ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"]);
  const colleague = officer("u-colleague", PLATFORM_ADMINS[0], ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"]);
  const assistant = officer("u-assistant", "ASSISTANT_DIRECTOR", ["ASSISTANT_DIRECTOR"]);
  const regional = officer("u-regional", "REGIONAL_DIRECTOR", ["REGIONAL_DIRECTOR"]);
  const directory = [reader, colleague, assistant, regional];

  test("the reader is never offered, and every other account the server listed is", () => {
    const rows = officerOptionsForPost({ officers: directory, capacity: "REGIONAL_DIRECTOR", readerId: reader.id });
    const values = rows.map((row) => row.value);
    expect(values).not.toContain(reader.id);
    expect(values).toEqual(expect.arrayContaining([colleague.id, regional.id, assistant.id]));
    // An administering tier other than the reader is offered and choosable in either slot.
    expect(rows.find((row) => row.value === colleague.id)?.disabled).toBe(false);
    expect(officersOfferedTo(directory, reader.id)).toBe(3);
  });

  test("a row that cannot fill this slot is drawn, greyed out, with ITS reason", () => {
    const rows = officerOptionsForPost({ officers: directory, capacity: "REGIONAL_DIRECTOR", readerId: reader.id });
    const wrongSlot = rows.find((row) => row.value === assistant.id);
    expect(wrongSlot?.disabled).toBe(true);
    expect(wrongSlot?.hint).toMatch(/may hold the Assistant Director post, not this one/);
    // The old stock sentence about the scheme is gone: it was false of every row it was printed on.
    expect(wrongSlot?.hint).not.toMatch(/supervises the scheme/);
    expect(officerSlotRefusal({ capacities: [] }, "ASSISTANT_DIRECTOR")).toBe("cannot be named in either post");
    expect(officerSlotRefusal({ capacities: ["ASSISTANT_DIRECTOR"] }, "ASSISTANT_DIRECTOR")).toBeNull();
    // Eligible rows come first, the greyed ones after them.
    const firstDisabled = rows.findIndex((row) => row.disabled);
    expect(rows.slice(firstDisabled).every((row) => row.disabled)).toBe(true);
  });

  test("the current holder is always drawn — the reader included — so the control can say who holds it", () => {
    const holder = {
      capacity: "REGIONAL_DIRECTOR" as const,
      userId: reader.id,
      name: reader.name,
      email: reader.email,
      role: reader.role,
      assignedAt: null,
      assignedById: null
    };
    const rows = officerOptionsForPost({
      officers: [colleague],
      capacity: "REGIONAL_DIRECTOR",
      holder,
      readerId: reader.id
    });
    expect(rows.map((row) => row.value)).toContain(reader.id);
  });
});

test.describe("an appointment refused by a separation-of-duties rule", () => {
  const sentence =
    "Kamla Devi (kamla@example.org) is this workshop's Regional Director, so they cannot also inspect it. One person cannot both supervise a workshop and inspect it. Nothing was changed.";

  test("reaches the reader with the server's sentence, verbatim, on both panels", () => {
    const refused = new ApiError(409, sentence, { detail: sentence });
    expect(inspectorAdministrationFailure(refused, "fallback", true)).toContain(sentence);
    expect(viewerAdministrationFailure(refused, "fallback", true)).toContain(sentence);
  });

  test("a 403 met by somebody who may assign is the server's reason, not a lecture about administration", () => {
    const post = "You are this workshop's inspector, so you can read it but not change it.";
    const refused = new ApiError(403, post, { detail: post });
    expect(inspectorAdministrationFailure(refused, "fallback", true)).toBe(`The repository refused this. ${post}`);
    expect(inspectorAdministrationFailure(refused, "fallback", false)).toMatch(/is administration/);
    expect(viewerAdministrationFailure(refused, "fallback", true)).toBe(`The repository refused this. ${post}`);
  });

  test("the inspector picker's empty search names the holders, not only the tier", () => {
    const searched = eligibleInspectorNotice({ truncated: false, offered: 0, searched: true });
    expect(searched).toMatch(/search/);
    expect(searched).not.toMatch(/Inspector \/ Reviewer account/);
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. A post on a workshop makes it read-only for its holder
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("the posts the reader holds on one workshop", () => {
  const reader = user("MINISTRY_ADMIN");
  const row = (capacity: "ASSISTANT_DIRECTOR" | "REGIONAL_DIRECTOR", userId: string) => ({ capacity, userId });

  test("are read off the rows, for the reader and nobody else", () => {
    expect(workshopPostsHeldBy(reader, { oversight: [row("REGIONAL_DIRECTOR", reader.id)] })).toEqual([
      "REGIONAL_DIRECTOR"
    ]);
    expect(workshopPostsHeldBy(reader, { oversight: [row("ASSISTANT_DIRECTOR", "u-other")] })).toEqual([]);
    expect(workshopPostsHeldBy(reader, { inspectors: [{ userId: reader.id }] })).toEqual(["INSPECTION"]);
    // Two posts at once can only be data older than the rules; it is still read, in the server's order.
    expect(
      workshopPostsHeldBy(reader, {
        oversight: [row("ASSISTANT_DIRECTOR", reader.id)],
        inspectors: [{ userId: reader.id }]
      })
    ).toEqual(["ASSISTANT_DIRECTOR", "INSPECTION"]);
  });

  test("absent rows and an absent reader answer none, leaving the server's refusal as the last word", () => {
    expect(workshopPostsHeldBy(reader, {})).toEqual([]);
    expect(workshopPostsHeldBy(reader, { oversight: null, inspectors: null })).toEqual([]);
    expect(workshopPostsHeldBy(null, { inspectors: [{ userId: reader.id }] })).toEqual([]);
  });

  test("the notice is the server's write refusal word for word, then where to read the workshop", () => {
    expect(heldPostEditRefusal([])).toBeNull();

    const regional = heldPostEditRefusal(["REGIONAL_DIRECTOR"]);
    expect(regional).toContain(serverWriteRefusal("Regional Director"));
    expect(regional).toMatch(/Workshops I monitor/);

    const inspecting = heldPostEditRefusal(["INSPECTION"]);
    expect(inspecting).toContain(serverWriteRefusal("inspector"));
    expect(inspecting).toMatch(/Workshops to inspect/);

    // The server names posts in one spoken order whatever order they were held in.
    const both = heldPostEditRefusal(["INSPECTION", "ASSISTANT_DIRECTOR"]);
    expect(both).toContain(serverWriteRefusal("Assistant Director and inspector"));
    expect(both).toMatch(/Workshops I monitor or Workshops to inspect/);
  });

  test("the way in is the read-only page the post opens the workshop on", () => {
    expect(heldPostReadPath(["REGIONAL_DIRECTOR"], "w1")).toBe("/officers/monitored/w1");
    expect(heldPostReadPath(["INSPECTION"], "w1")).toBe("/design-workshop-inspections/w1");
    expect(heldPostReadPath(["ASSISTANT_DIRECTOR", "INSPECTION"], "w1")).toBe("/officers/monitored/w1");
    expect(heldPostReadPath([], "w1")).toBeNull();
    expect(heldPostReadPath(["INSPECTION"], "a/b")).toBe("/design-workshop-inspections/a%2Fb");
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. An empty read surface is an answer
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("holding no posts is said, not shown as an error", () => {
  const forbidden = new ApiError(403, "Refused.", { detail: "Refused." });

  test("the inspector's list: an administering tier refused holds no inspection posts", () => {
    for (const role of ADMINISTERING) {
      expect(inspectionRefusalMeansNoPosts(forbidden, user(role)), role).toBe(true);
    }
    // The tier itself refused has met a fault, and so has anybody who may not inspect at all.
    expect(inspectionRefusalMeansNoPosts(forbidden, user("INSPECTOR"))).toBe(false);
    expect(inspectionRefusalMeansNoPosts(forbidden, user(NEVER_APPOINTED))).toBe(false);
    // Only a refusal reads that way: a missing record or a broken server is not "no posts".
    expect(inspectionRefusalMeansNoPosts(new ApiError(404, "Record not found", null), user(PLATFORM_ADMINS[0]))).toBe(false);
    expect(inspectionRefusalMeansNoPosts(new ApiError(500, "Boom", null), user(PLATFORM_ADMINS[0]))).toBe(false);
    expect(inspectionRefusalMeansNoPosts(new Error("offline"), user(PLATFORM_ADMINS[0]))).toBe(false);
  });

  test("Workshops I monitor: a platform admin refused holds no posts; a directorate tier refused is a fault", () => {
    for (const role of PLATFORM_ADMINS) {
      expect(oversightRefusalMeansNoPosts(forbidden, user(role)), role).toBe(true);
    }
    for (const role of ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN"] as const) {
      expect(oversightRefusalMeansNoPosts(forbidden, user(role)), role).toBe(false);
    }
    expect(oversightRefusalMeansNoPosts(new ApiError(404, "Record not found", null), user(PLATFORM_ADMINS[0]))).toBe(false);
  });

  test("the empty states say You do not hold any … posts, naming only the posts the reader may hold", () => {
    expect(inspectionEmptyState(false).title).toBe("You do not hold any inspection posts");
    expect(inspectionEmptyState(true).title).toMatch(/search/);

    expect(oversightEmptyState(false, user(PLATFORM_ADMINS[0])).title).toBe(
      "You do not hold any Assistant Director or Regional Director posts"
    );
    expect(oversightEmptyState(false, user("ASSISTANT_DIRECTOR")).title).toBe(
      "You do not hold any Assistant Director posts"
    );
    expect(oversightEmptyState(false, user("REGIONAL_DIRECTOR")).title).toBe(
      "You do not hold any Regional Director posts"
    );
    expect(oversightEmptyState(true, user("MINISTRY_ADMIN")).title).toMatch(/search/);
    // Both bodies say who appoints and where — an empty page is a next move, not a dead end.
    for (const body of [inspectionEmptyState(false).body, oversightEmptyState(false, user("MINISTRY_ADMIN")).body]) {
      expect(body).toMatch(/Workshop oversight/);
      expect(body).toMatch(/nothing failed to load/);
    }
  });
});
