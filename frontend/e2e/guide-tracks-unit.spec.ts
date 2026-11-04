import { existsSync } from "node:fs";
import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { adminChromeRouteFor, adminChromeVisible } from "@/components/AdminViewProvider";
import { DIRECTORATE_STEPS } from "@/components/guide/directorateSteps";
import { INSPECTOR_STEPS } from "@/components/guide/inspectorSteps";
import { GUIDE_STEPS } from "@/components/guide/steps";
import {
  DESIGNER_TRACK,
  DIRECTORATE_TRACK,
  GUIDE_TRACKS,
  INSPECTOR_TRACK,
  guideTrackFor,
  guideTrackForAnchor,
  guideTracksFor,
  type GuideTrack
} from "@/components/guide/tracks";
import { canAccessRoute } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

const ROOT = join(__dirname, "..");
const PROTECTED = join(ROOT, "app", "(protected)");
const NAV = join(ROOT, "components", "DynamicIslandNav.tsx");
const OUTRO = join(ROOT, "components", "guide", "GuideOutro.tsx");

const user = (role: UserRole): User => ({ id: `u-${role}`, email: "a@b.c", name: role, role }) as User;

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

const MINISTRY_POSTS: UserRole[] = ["MINISTRY_ADMIN", "REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR"];

test("every registered deck has steps, because the journey indexes the first one unguarded", () => {
  expect(GUIDE_TRACKS.length).toBeGreaterThan(0);
  for (const track of GUIDE_TRACKS) {
    expect(track.steps.length, `${track.id} has no steps`).toBeGreaterThan(0);
  }
});

test("the registry holds every deck this module declares", () => {
  const registered = GUIDE_TRACKS.map((track) => track.id).sort();
  expect(registered).toEqual([DESIGNER_TRACK, DIRECTORATE_TRACK, INSPECTOR_TRACK].map((t) => t.id).sort());
  expect(new Set(GUIDE_TRACKS.map((track) => track.id)).size).toBe(GUIDE_TRACKS.length);
});

test("every anchor is unique across all three decks, not merely within one", () => {
  const ids = GUIDE_TRACKS.flatMap((track) => track.steps.map((step) => step.id));
  expect(new Set(ids).size, `duplicate step ids: ${ids.filter((id, i) => ids.indexOf(id) !== i)}`).toBe(ids.length);
});

test("every step of every deck links to a route that exists", () => {
  for (const track of GUIDE_TRACKS) {
    for (const step of track.steps) {
      const path = step.href.split("?")[0].replace(/^\//, "");
      expect(existsSync(join(PROTECTED, path, "page.tsx")), `${track.id}/${step.id} → ${step.href}`).toBe(true);
    }
  }
});

test("every exit tile of every deck links to a route that exists", () => {
  for (const track of GUIDE_TRACKS) {
    for (const exit of track.next) {
      const path = exit.href.replace(/^\//, "");
      expect(existsSync(join(PROTECTED, path, "page.tsx")), `${track.id} → ${exit.href}`).toBe(true);
    }
  }
});

test("the two new decks' exits are reachable by the tiers those decks open for", () => {
  for (const [track, roles] of [
    [DIRECTORATE_TRACK, MINISTRY_POSTS],
    [INSPECTOR_TRACK, ["INSPECTOR"] as UserRole[]]
  ] as const) {
    for (const role of roles) {
      for (const exit of track.next) {
        expect(
          canAccessRoute(user(role), exit.href),
          `${track.id}: a ${role} is refused ${exit.href}, which the closing band offers them`
        ).toBe(true);
      }
    }
  }
});

test("the role picks the deck that opens, tier by tier", () => {
  for (const role of ALL_ROLES) {
    const expected =
      role === "INSPECTOR"
        ? INSPECTOR_TRACK
        : MINISTRY_POSTS.includes(role)
          ? DIRECTORATE_TRACK
          : DESIGNER_TRACK;
    expect(guideTrackFor(user(role)).id, role).toBe(expected.id);
  }
});

test("an admin and the master admin open on the designer's deck, and that is the ruling", () => {
  expect(guideTrackFor(user("MASTER_ADMIN")).id).toBe(DESIGNER_TRACK.id);
  expect(guideTrackFor(user("ADMIN")).id).toBe(DESIGNER_TRACK.id);
});

test("a signed-out or unknown caller gets the designer's deck rather than nothing", () => {
  expect(guideTrackFor(null).id).toBe(DESIGNER_TRACK.id);
  expect(guideTrackFor(undefined).id).toBe(DESIGNER_TRACK.id);
});

const DECKS_BY_TIER: Record<UserRole, string[]> = {
  MASTER_ADMIN: [DESIGNER_TRACK.id, DIRECTORATE_TRACK.id, INSPECTOR_TRACK.id],
  ADMIN: [DESIGNER_TRACK.id, DIRECTORATE_TRACK.id, INSPECTOR_TRACK.id],
  MINISTRY_ADMIN: [DIRECTORATE_TRACK.id],
  REGIONAL_DIRECTOR: [DIRECTORATE_TRACK.id],
  ASSISTANT_DIRECTOR: [DIRECTORATE_TRACK.id],
  PROFESSOR: [DESIGNER_TRACK.id],
  INSPECTOR: [INSPECTOR_TRACK.id],
  DESIGNER: [DESIGNER_TRACK.id],
  RESEARCHER: [DESIGNER_TRACK.id],
  FIELD_CONTRIBUTOR: [DESIGNER_TRACK.id],
  CROWDSOURCE_VOLUNTEER: [DESIGNER_TRACK.id]
};

test("every tier may read exactly the decks the ruling gives it", () => {
  for (const role of ALL_ROLES) {
    expect(
      guideTracksFor(user(role)).map((track) => track.id),
      `${role} must be scoped to ${DECKS_BY_TIER[role].join(", ")}`
    ).toEqual(DECKS_BY_TIER[role]);
  }
  expect(Object.keys(DECKS_BY_TIER).sort(), "ALL_ROLES and DECKS_BY_TIER must be the same ladder").toEqual(
    [...ALL_ROLES].sort()
  );
});

test("the mapping is total: no tier without a deck, no deck without a tier", () => {
  const owned = new Set<string>();
  for (const role of ALL_ROLES) {
    const decks = guideTracksFor(user(role));
    expect(decks.length, `${role} is scoped to no deck at all`).toBeGreaterThan(0);
    for (const deck of decks) owned.add(deck.id);
  }
  for (const track of GUIDE_TRACKS) {
    expect(owned.has(track.id), `${track.id} is a deck no tier is ever shown`).toBe(true);
  }
});

test("the deck that opens is always one the account is allowed to read", () => {
  for (const role of [...ALL_ROLES, null]) {
    const account = role ? user(role) : null;
    const opens = guideTrackFor(account);
    expect(
      guideTracksFor(account).some((deck) => deck.id === opens.id),
      `${role ?? "a signed-out caller"} opens on ${opens.id}, which is not in their own list`
    ).toBe(true);
  }
});

function exitsDrawn(account: User, track: GuideTrack, adminMode: boolean) {
  return track.next.filter(
    (exit) =>
      canAccessRoute(account, exit.href) &&
      (!adminChromeRouteFor(exit.href) || adminChromeVisible(account, adminMode))
  );
}

test("the closing band cannot be emptied by its own filter, for any deck or any tier", () => {
  for (const role of ALL_ROLES) {
    const account = user(role);
    for (const adminMode of [false, true]) {
      for (const track of guideTracksFor(account)) {
        expect(
          exitsDrawn(account, track, adminMode).length,
          `a ${role} reading ${track.id} with admin view ${adminMode ? "on" : "off"} would be shown ` +
            '"Where to go next" with nothing under it'
        ).toBeGreaterThan(0);
      }
    }
  }
});

test("the Manage users tile is not offered to an admin browsing with admin view off", () => {
  const tile = DIRECTORATE_TRACK.next.find((exit) => exit.href === "/users");
  expect(tile, "the directorate deck's /users tile is what this pins").toBeTruthy();
  expect(adminChromeRouteFor("/users"), "/users must still be admin chrome for this to mean anything").toBeTruthy();

  for (const role of ["ADMIN", "MASTER_ADMIN"] as UserRole[]) {
    const account = user(role);
    expect(canAccessRoute(account, "/users"), `${role} passes ROUTE_GUARDS for /users`).toBe(true);
    expect(
      exitsDrawn(account, DIRECTORATE_TRACK, false),
      `a ${role} with admin view off is offered /users, which AppShell answers with AdminViewLocked`
    ).not.toContain(tile);
    expect(
      exitsDrawn(account, DIRECTORATE_TRACK, true),
      `a ${role} with admin view on can open /users and must still be offered it`
    ).toContain(tile);
  }

  for (const role of MINISTRY_POSTS) {
    const account = user(role);
    for (const adminMode of [false, true]) {
      expect(
        exitsDrawn(account, DIRECTORATE_TRACK, adminMode),
        `a ${role} has no admin-view toggle, so /users cannot depend on it`
      ).toContain(tile);
    }
  }
});

test("the closing band really does filter, and the filter is read off the component", () => {
  const outro = readFileSync(OUTRO, "utf8");
  expect(outro, "GuideOutro must filter its exits on the route table").toContain("canAccessRoute");
  expect(outro, "and it must filter `track.next`, not something else").toMatch(
    /track\.next\.filter\(\s*\(entry\)\s*=>\s*canAccessRoute\(user, entry\.href\)/
  );
  expect(outro, "GuideOutro must also apply AppShell's admin-view gate").toMatch(
    /!adminChromeRouteFor\(entry\.href\)\s*\|\|\s*adminChromeVisible\(user, adminMode\)/
  );
});

test("an anchor resolves to the deck that owns it, and to nothing when nobody owns it", () => {
  for (const track of GUIDE_TRACKS) {
    for (const step of track.steps) {
      expect(guideTrackForAnchor(step.id)?.id, `${step.id} belongs to ${track.id}`).toBe(track.id);
    }
  }
  expect(guideTrackForAnchor("a-step-that-was-renamed")).toBeNull();
  expect(guideTrackForAnchor("")).toBeNull();
  expect(guideTrackForAnchor(null)).toBeNull();
});

test("no deck states a step count it did not derive from its own array", () => {
  for (const track of GUIDE_TRACKS) {
    expect(
      track.description,
      `${track.id}'s header sentence must carry ${track.steps.length}, its own length`
    ).toContain(String(track.steps.length));
    const facts = track.facts.map((fact) => fact.text).join(" ");
    expect(facts, `${track.id}'s hero facts must carry ${track.steps.length}`).toContain(
      String(track.steps.length)
    );
  }
});

test("no card in any deck promises that a stage re-reads the record it was filled from", () => {
  for (const track of GUIDE_TRACKS) {
    for (const step of track.steps) {
      const prose = [step.summary, step.why, ...step.fields, ...step.watch].join(" ");
      expect(prose, `${track.id}/${step.id} must not promise a live re-read`).not.toMatch(
        /re-?reads? the (live )?record|shows the linked record|reads the linked record|stays in sync with the record/i
      );
    }
  }
});

test("the directorate deck states the copy rule, because it teaches the same stage form", () => {
  const stages = DIRECTORATE_STEPS.find((step) => step.id === "ministry-workshop");
  expect(stages, "the authoring card is where the rule belongs").toBeTruthy();
  const prose = [stages!.summary, stages!.why, ...stages!.fields, ...stages!.watch].join(" ");
  expect(prose).toMatch(/COPIES ITS VALUES/i);
  expect(prose).toMatch(/report prints that copy/i);
});

test("STANDING TRIPWIRE: the directorate deck never promises every tier a notice above the stages", () => {
  const everything = DIRECTORATE_STEPS.flatMap((step) => [step.summary, step.why, ...step.fields, ...step.watch]).join(" ");
  const notices = everything.match(/[^.]*\babove the stages\b[^.]*\./gi) ?? [];
  expect(notices.length, "the sentence is still on the card").toBeGreaterThan(0);
  for (const sentence of notices) {
    expect(sentence, "a notice above the stages is promised only to the tier that sees it").toMatch(/Ministry Admin/);
  }
  expect(everything).not.toMatch(/the page says so above the stages/i);
  expect(everything).toMatch(/A workshop you supervise or inspect is read-only to you here/);
  expect(everything).not.toMatch(/told by the save itself/i);
});

test("STANDING TRIPWIRE: nothing claims an inspector cannot change a workshop, full stop", () => {
  const everything = GUIDE_TRACKS.flatMap((track) =>
    track.steps.flatMap((step) => [step.summary, step.why, ...step.fields, ...step.watch])
  ).join(" ");
  expect(everything).not.toMatch(/nothing an inspector does can change a workshop/i);
  expect(everything).toMatch(/never a workshop's contents|changes a workshop's CONTENT/i);
});

test("the labels that claim to be nav entries really are nav entries, character for character", () => {
  const nav = readFileSync(NAV, "utf8");
  const navBacked = [
    ...DIRECTORATE_STEPS.map((step) => step.label),
    "Workshops to inspect",
    "Review"
  ];
  for (const label of navBacked) {
    expect(nav, `NAV_ITEMS has no entry labelled "${label}"`).toContain(`label: "${label}"`);
  }
});

test("the designer's deck is the one that existed, unchanged in shape", () => {
  expect(DESIGNER_TRACK.steps).toBe(GUIDE_STEPS);
  expect(DIRECTORATE_TRACK.steps).toBe(DIRECTORATE_STEPS);
  expect(INSPECTOR_TRACK.steps).toBe(INSPECTOR_STEPS);
});
