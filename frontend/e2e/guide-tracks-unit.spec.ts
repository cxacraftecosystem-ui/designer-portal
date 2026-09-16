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

/**
 * THREE WALKTHROUGHS OVER ONE RENDERER, AND THE ROLE PICKS WHICH ONES A READER GETS.
 *
 * `/guide` taught one deck — a designer's fortnight — to every signed-in account, including two
 * audiences whose own screens are not in it: the three directorate posts, and the Inspector /
 * Reviewer tier. `components/guide/tracks.ts` now registers three decks, chooses a default from the
 * reader's role (`guideTrackFor`) and, since 2026-09-16, SCOPES which decks that reader may see at
 * all (`guideTracksFor`) — everybody except an ADMIN and a MASTER ADMIN gets exactly one.
 *
 * ⚠ THIS FILE NEVER RENDERS THE PAGE, which is a limit on what it can be asked to prove and was the
 * reason a shipped defect went uncaught. It imports modules and reads two components as TEXT; there
 * is no `page.goto` anywhere in it, so the duplicate-sibling-key bug that stacked a purple hero band
 * per deck visited could not have failed here and its return could not fail here either. The browser
 * half is `e2e/guide-tracks-page.spec.ts`, added with the fix. Assertions about DATA belong in this
 * file; assertions about what is on the SCREEN belong in that one, and neither substitutes.
 *
 * ── WHAT THIS FILE PINS, AND WHY EACH IS THE FAILURE THAT ACTUALLY HAPPENS ──────────────────────
 *
 *  1. NO DECK IS EMPTY. `GuideJourney` indexes `steps[0].id` unguarded, exactly as it indexed
 *     `GUIDE_STEPS[0]` before the decks existed — there is no rendering for a walkthrough with no
 *     steps in it. One array that must never be empty became three, and nothing else counts them.
 *  2. EVERY ANCHOR IS UNIQUE ACROSS ALL THREE DECKS, not merely inside one. The rail scrolls to
 *     `#${id}` and the URL hash survives a reload; on top of that `guideTrackForAnchor` resolves a
 *     DECK from an id, so a duplicate would make one link mean two pages and the resolver would
 *     silently pick whichever deck is registered first.
 *  3. EVERY `href` RESOLVES TO A ROUTE THAT REALLY EXISTS. A guide whose links 404 is worse than no
 *     guide: the reader concludes the feature is gone. Same assertion the designer's own spec makes,
 *     extended to the two new decks and to every deck's exit tiles.
 *  4. THE SELECTION IS THE OWNER'S RULE, TIER BY TIER. An inspector gets the inspection deck; the
 *     three ministry posts get the directorate deck; everybody else — the master admin and an admin
 *     included — gets the designer's. `guideTrackFor` reuses `canReadWorkshopOversight` to mean
 *     "holds a ministry post", which is only safe while `OFFICER_ROLES` is exactly those three, so
 *     the tiers are named here rather than derived: the day that set moves, this goes red instead of
 *     silently defaulting somebody into the wrong deck.
 *  5. NO DECK STATES A COUNT IT DID NOT DERIVE. The page header said the literal "Ten steps" while
 *     the array held sixteen, and then nineteen. Three decks is three chances to do it again.
 *  6. NO CARD PROMISES A LIVE RE-READ OF A REFERENCED RECORD. The designer's spec makes this
 *     assertion over its own arc; the directorate deck describes the same 22-stage form and inherits
 *     the same rule. Choosing a record COPIES its values onto the stage and the report prints the
 *     copy — softening that is a document already in an officer's hands changing under him.
 *  7. THE STALE INSPECTOR SENTENCE CANNOT COME BACK. "Nothing an inspector does can change a
 *     workshop" was true when the surface shipped, stopped being true when the feedback routes
 *     landed, and survived in the designer's card for as long as nothing read it. It is a literal
 *     tripwire because the correction is narrower than the original — content, not the workshop —
 *     and a future edit reaching for the shorter sentence would be reintroducing the defect, not
 *     making a style choice.
 *  8. THE REGISTRY HOLDS EVERY DECK THE MODULE DECLARES. A deck added to the module and not to
 *     `GUIDE_TRACKS` is a deck `guideTracksFor` can never return and `guideTrackForAnchor` can never
 *     resolve — invisible to every reader including the admins, and to the anchor map with it.
 *     (This assertion used to be justified as "the switcher reaches all three for everybody". That
 *     justification died with the scoping on 2026-09-16; the assertion is worth strictly more now,
 *     because `GUIDE_TRACKS` is no longer merely the switcher's list — it is the whole map.)
 *  9. THE ROLE→DECK MAPPING IS TOTAL IN BOTH DIRECTIONS. Every one of the eleven tiers gets at least
 *     one deck, and every deck is owned by at least one tier. A tier with no deck would open `/guide`
 *     on a crash (`GuideJourney` indexes `steps[0]` unguarded); a deck no tier owns would be prose
 *     nobody is ever shown, which is the failure the SCOPING introduces the possibility of — before
 *     it, the switcher made every deck reachable by everybody and the question could not arise.
 * 10. THE CLOSING BAND CANNOT BE EMPTIED BY ITS OWN FILTER, UNDER EITHER GATE. `GuideOutro` drops
 *     exit tiles the reader cannot open — `canAccessRoute`, and after it the admin-view gate
 *     `AppShell` applies second — and a deck whose every exit is gated for its own audience would
 *     render a heading over nothing. The second gate is asserted with the toggle both ways, because
 *     an admin with admin view OFF is the reader it subtracts from and is the one the first version
 *     of this filter still offered `/users`.
 */

const ROOT = join(__dirname, "..");
const PROTECTED = join(ROOT, "app", "(protected)");
const NAV = join(ROOT, "components", "DynamicIslandNav.tsx");
const OUTRO = join(ROOT, "components", "guide", "GuideOutro.tsx");

const user = (role: UserRole): User => ({ id: `u-${role}`, email: "a@b.c", name: role, role }) as User;

/**
 * Every tier the server knows, highest first.
 *
 * A HAND-KEPT TUPLE THAT LOOKS TYPED AND IS NOT — `UserRole[]` is an ARRAY, so a short one
 * type-checks — which is why `backend/tests/test_role_ladder_parity.py` registers it as a mirror and
 * diffs it against `deps.ROLE_RANK`. A tier added to the ladder and not to this list would make the
 * selection test below pass while asking nothing at all about the new tier, which is exactly the
 * shape of green that wastes a quarter.
 */
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

/** The tiers whose default deck is the directorate's — `OFFICER_ROLES`, named rather than imported. */
const MINISTRY_POSTS: UserRole[] = ["MINISTRY_ADMIN", "REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR"];

test("every registered deck has steps, because the journey indexes the first one unguarded", () => {
  expect(GUIDE_TRACKS.length).toBeGreaterThan(0);
  for (const track of GUIDE_TRACKS) {
    expect(track.steps.length, `${track.id} has no steps`).toBeGreaterThan(0);
  }
});

test("the registry holds every deck this module declares", () => {
  // Written as a set rather than a length, so adding a fourth deck and forgetting to register it
  // fails by NAME instead of by an off-by-one somebody has to go and diff.
  //
  // WHAT THIS GUARDS CHANGED ON 2026-09-16 WITHOUT THE ASSERTION MOVING. It used to mean "the
  // switcher reaches it"; now `guideTracksFor` returns a slice of this array and
  // `guideTrackForAnchor` searches it, so an unregistered deck is one no account can be scoped to
  // and no anchor can resolve — reachable by nobody at all rather than merely absent from a row of
  // buttons.
  const registered = GUIDE_TRACKS.map((track) => track.id).sort();
  expect(registered).toEqual([DESIGNER_TRACK, DIRECTORATE_TRACK, INSPECTOR_TRACK].map((t) => t.id).sort());
  // And each deck is the object it claims to be, not a copy: the page compares by `id` and the
  // switcher renders `GUIDE_TRACKS`, so two objects with one id would make the tick land nowhere.
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
  /*
    AN EXIT TILE MAY NOT LAND ITS READER ON A LOCK PANEL, and that is a stricter rule than the one a
    step CARD is under. A card may name a screen the reader cannot open — that is how the ungated
    guide teaches a capability somebody has not earned yet, and every such card says so in its own
    `watch`. The closing band is different: it is the "you are done, here is where to go" band, and a
    padlock there is a promise broken rather than a description given.

    ⚠ THE DESIGNER'S DECK IS STILL NOT IN THIS ASSERTION, AND THE REASON IS NOW THE OPPOSITE OF WHAT
    IT WAS. The exemption used to record a GAP: its default audience is everybody — it is the
    fallback arm of `guideTrackFor` — including CROWDSOURCE_VOLUNTEER (10), who is below the Field
    Contributor floor `/review` admits, so its "Review" tile offered the bottom tier a padlock, and
    the one-line fix (filter `track.next` on `canAccessRoute` in `GuideOutro`) was named here and
    deliberately not taken inside a change about three other things.

    IT WAS TAKEN ON 2026-09-16, when the deck scoping turned that gap into a trap — the designer's
    deck is now the only deck those tiers will ever be shown. So this assertion is exempting the
    designer's deck from a DATA rule that the RENDERER now enforces for every deck: `track.next` is
    the deck's full list and `GuideOutro` draws the reachable subset of it. The two new decks stay
    asserted here because their data is narrow enough to be right at the source — every tile is
    reachable by every tier that opens the deck, so the filter never has anything to remove — and a
    deck whose data is right is a deck whose closing band does not silently shrink. The filter is
    pinned separately, two tests below.
  */
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
  /*
    THE ONE ANSWER A READER OF `guideTrackFor` WILL EXPECT TO BE DIFFERENT. The master admin is in
    the ministry CARD's audience (`canSeeMinistryDesk`) and is deliberately not in the directorate
    deck's default, because the two answer different questions: the card asks "who has no hub", and
    the default asks "whose job is this". A master admin does every job here, so the deck that opens
    for them is the one that describes what the product IS.

    Asserted separately from the sweep above so the failure names the decision rather than a tier in
    a loop.
  */
  expect(guideTrackFor(user("MASTER_ADMIN")).id).toBe(DESIGNER_TRACK.id);
  expect(guideTrackFor(user("ADMIN")).id).toBe(DESIGNER_TRACK.id);
});

test("a signed-out or unknown caller gets the designer's deck rather than nothing", () => {
  expect(guideTrackFor(null).id).toBe(DESIGNER_TRACK.id);
  expect(guideTrackFor(undefined).id).toBe(DESIGNER_TRACK.id);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The scoping — `guideTracksFor`, 2026-09-16
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE RULING, WRITTEN OUT TIER BY TIER RATHER THAN DERIVED FROM THE CODE UNDER TEST.
 *
 * Owner, 2026-09-16: "except for admins and master admins, the walkthrough that the people get to
 * see should be the ones that are relevant to their roles." Computing the expectation with the same
 * predicates the implementation uses would assert only that the function calls itself; the point of
 * a table is that changing `guideTracksFor` has to be accompanied by changing a decision somebody
 * wrote down.
 *
 * `Record<UserRole, …>` AND NOT AN ARRAY OF PAIRS, deliberately: a tier added to the ladder and not
 * to this table fails to COMPILE, which is the one kind of reminder that cannot be forgotten. The
 * `ALL_ROLES` tuple above cannot do that — `UserRole[]` accepts a short list, which is why
 * `backend/tests/test_role_ladder_parity.py` has to diff it against the server's ladder by regex.
 *
 * FOUR TIERS REACH THEIR DECK BY FALLBACK RATHER THAN BECAUSE ONE WAS WRITTEN FOR THEM — PROFESSOR,
 * RESEARCHER, FIELD_CONTRIBUTOR and CROWDSOURCE_VOLUNTEER land on the designer's deck, and PROFESSOR
 * is outside `DESIGN_WORKSHOP_ROLES`, `INSPECTION_ROLES` and `OFFICER_ROLES` alike, so ten of that
 * deck's cards teach a route tree they are refused. That is a content gap and it is recorded in
 * `tracks.ts` beside the function; it is NOT a hole in the mapping, which is what this table pins.
 *
 * FOUR, AND IT SAID FIVE OVER AN ENUMERATION OF FOUR UNTIL 2026-09-16 — corrected to agree with
 * `tracks.ts`, which states the same proposition and states it as four. The number to be careful
 * with is a DIFFERENT one that reads almost the same: `guideTrackFor`'s fallback ARM is taken by
 * more tiers than these, because DESIGNER, ADMIN and MASTER_ADMIN take it too. "Lands on the
 * designer's deck" and "has no deck written for it" are two propositions with two answers, and
 * `GuideOutro.tsx` carries the first while this docstring and `tracks.ts` carry the second. Read
 * which one a sentence is making before reconciling its arithmetic with another file's.
 */
const DECKS_BY_TIER: Record<UserRole, string[]> = {
  // The owner's carve-out: an admin and the master admin do every job here and keep the switcher.
  MASTER_ADMIN: [DESIGNER_TRACK.id, DIRECTORATE_TRACK.id, INSPECTOR_TRACK.id],
  ADMIN: [DESIGNER_TRACK.id, DIRECTORATE_TRACK.id, INSPECTOR_TRACK.id],
  // `OFFICER_ROLES` — the three ministry posts, which refuses an admin by name.
  MINISTRY_ADMIN: [DIRECTORATE_TRACK.id],
  REGIONAL_DIRECTOR: [DIRECTORATE_TRACK.id],
  ASSISTANT_DIRECTOR: [DIRECTORATE_TRACK.id],
  // No deck is written for a professor; the fallback arm gives them the designer's.
  PROFESSOR: [DESIGNER_TRACK.id],
  // `INSPECTION_ROLES` — one member, sitting at 37, BETWEEN designer (35) and professor (40).
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
  // And the table and the tuple describe the same ladder, so neither can drift on its own: the
  // table cannot compile short, and this catches a tuple that has.
  expect(Object.keys(DECKS_BY_TIER).sort(), "ALL_ROLES and DECKS_BY_TIER must be the same ladder").toEqual(
    [...ALL_ROLES].sort()
  );
});

test("the mapping is total: no tier without a deck, no deck without a tier", () => {
  /*
    BOTH DIRECTIONS, AND THE SECOND ONLY BECAME ASKABLE WITH THE SCOPING. Until 2026-09-16 the
    switcher handed every deck to everybody, so "a deck nobody opens" was not a state the product
    had. Now a deck reaches a reader only through `guideTracksFor`, and one that no tier's answer
    contains is prose in the bundle that no account is ever shown — which nothing else here would
    notice, because every other assertion in this file reads `GUIDE_TRACKS` directly.

    A tier with no deck is the louder half: `GuideJourney` indexes `steps[0].id` unguarded, so an
    empty answer is not a blank page, it is a crash on `/guide` for that tier.
  */
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
  /*
    THE ONE WAY THESE TWO FUNCTIONS CAN DISAGREE, and it would not crash — it would render. The page
    clamps `track` to `guideTracksFor(user)` and falls back to `guideTrackFor(user)`; if the default
    were ever outside the scope, that clamp would fall back to the very deck it just rejected and the
    gate would be silently off for that tier. Today it cannot happen (the non-admin arm IS the
    default, and the admin arm is everything), and that is exactly the kind of "true by construction"
    a later edit breaks without noticing.
  */
  for (const role of [...ALL_ROLES, null]) {
    const account = role ? user(role) : null;
    const opens = guideTrackFor(account);
    expect(
      guideTracksFor(account).some((deck) => deck.id === opens.id),
      `${role ?? "a signed-out caller"} opens on ${opens.id}, which is not in their own list`
    ).toBe(true);
  }
});

/**
 * The tiles `GuideOutro` really draws for one reader — `AppShell`'s two gates, in `AppShell`'s order.
 *
 * SPELLED ONCE HERE AND USED BY BOTH TESTS BELOW, because the thing being asked about is the same
 * predicate from two directions: "it is never empty" and "it never contains a tile that opens onto a
 * lock panel". Written out rather than imported from the component, for this file's standing reason —
 * a helper that reused the component's own filter would assert only that the component agrees with
 * itself, which is why the test below it names one concrete pair instead of sweeping with this.
 */
function exitsDrawn(account: User, track: GuideTrack, adminMode: boolean) {
  return track.next.filter(
    (exit) =>
      canAccessRoute(account, exit.href) &&
      (!adminChromeRouteFor(exit.href) || adminChromeVisible(account, adminMode))
  );
}

test("the closing band cannot be emptied by its own filter, for any deck or any tier", () => {
  /*
    `GuideOutro` drops exit tiles the reader cannot open, so "Where to go next" is now a heading over
    a filtered list — and a deck whose every exit is gated for its own audience would render the
    heading over nothing at all, which is worse than the padlock the filter was added to remove.

    BOTH OF `AppShell`'S GATES, IN ITS ORDER, because the component now applies both and a test that
    asked only the first would be pinning the version of the filter that shipped the `/users` defect.
    The toggle is swept both ways rather than assumed off — `false` is the state that subtracts, and
    `true` is the state every tier with no toggle at all is already in.

    It holds today because `/dashboard` is in every deck's `next`, is in no `ROUTE_GUARDS` row and is
    no admin chrome. That is the fact being asserted, per deck and per tier that can reach it, rather
    than the `/dashboard` row itself — a deck could lose that tile and still be fine, and a guard or
    an `ADMIN_CHROME_ROUTES` entry could land on `/dashboard` and break every deck at once.
  */
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
  /*
    THE DEFECT THIS TEST IS NAMED AFTER, and it pins the PREMISES rather than the component: this file
    cannot render `GuideOutro`, so what stops the second gate being deleted from it is the source
    tripwire two tests below. What is asserted here is every fact that made the gate necessary — that
    this tile is on this deck, that `/users` is admin chrome, that an admin passes `ROUTE_GUARDS` for
    it anyway, and that the pair of gates therefore answers differently with the toggle on and off. If
    any one of those moves, this goes red beside the sentence explaining what the gate was for, rather
    than the gate quietly becoming dead code nobody removes.

    `canAccessRoute(admin, "/users")` is TRUE: the guard row's predicate is `canManageUsers`, a
    professor-and-above rank test, which is exactly why the one-gate filter drew the tile. `/users` is
    also an `ADMIN_CHROME_ROUTES` entry, so `AppShell` answers an ADMIN or MASTER ADMIN with the
    toggle off by rendering `AdminViewLocked` INSTEAD of the page. The directorate deck's `next`
    carries "Manage users", and the admin carve-out of 2026-09-16 is what first put an admin on that
    deck — before it, nobody in particular was ever steered onto this band.

    THE TOGGLE SUBTRACTS AND NEVER ADDS, which the second half asserts: a ministry post has no toggle
    at all, so the same tile is drawn for them whatever `adminMode` says.
  */
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
  /*
    A SOURCE TRIPWIRE, AND IT SAYS SO. This file cannot render `GuideOutro` — it has no browser — so
    the honest guard here is that the component still asks `canAccessRoute`, and the real one is
    `e2e/guide-tracks-page.spec.ts`, which loads the page and counts tiles. Same idiom as the nav
    assertion below, and the same limit: it proves the call is written, never that it is applied.

    It is worth keeping anyway. The filter is one line and reads like a tidy-up; deleting it puts a
    crowdsource volunteer back in front of a `/review` padlock from the band that exists to tell them
    where to go, and until 2026-09-16 nothing in this suite would have said a word about it.
  */
  const outro = readFileSync(OUTRO, "utf8");
  expect(outro, "GuideOutro must filter its exits on the route table").toContain("canAccessRoute");
  expect(outro, "and it must filter `track.next`, not something else").toMatch(
    /track\.next\.filter\(\s*\(entry\)\s*=>\s*canAccessRoute\(user, entry\.href\)/
  );
  // And the second gate, which `AppShell` applies after the first and this band forgot until
  // 2026-09-16. Pinned separately so a regression names which half went missing.
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
    // And the hero's count fact, which is the other place the page prints a number before the reader
    // has scrolled anywhere.
    const facts = track.facts.map((fact) => fact.text).join(" ");
    expect(facts, `${track.id}'s hero facts must carry ${track.steps.length}`).toContain(
      String(track.steps.length)
    );
  }
});

test("no card in any deck promises that a stage re-reads the record it was filled from", () => {
  /*
    Its authority is `REFERENCE_HYDRATION` in `backend/app/services/stage_schema.py`: hydration
    copies at SAVE time and the report reads the frozen copy. A sentence saying the report shows the
    linked record would teach an officer that correcting an artisan next week updates a document
    handed over last month — the one thing this application must never do.
  */
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

test("STANDING TRIPWIRE: nothing claims an inspector cannot change a workshop, full stop", () => {
  /*
    THE SENTENCE THAT ROTTED. "Nothing an inspector does can change a workshop" was true when the
    inspection surface shipped and false from the day `POST /design-workshop-inspections/{id}/feedback`
    and `.../send-back` landed — a send-back moves the report to Needs revision. It survived in
    `steps.ts` because nothing read it.

    The correction is NARROWER than the original and that is the whole repair: an inspector changes
    no CONTENT — no stage value, no photograph, no completeness figure, no record — and does change a
    report's standing. This assertion exists because the shorter sentence is the one a future editor
    will reach for, and it reads as a tidy-up rather than as a regression.
  */
  const everything = GUIDE_TRACKS.flatMap((track) =>
    track.steps.flatMap((step) => [step.summary, step.why, ...step.fields, ...step.watch])
  ).join(" ");
  expect(everything).not.toMatch(/nothing an inspector does can change a workshop/i);
  // And the true form is stated somewhere, so the tripwire cannot be satisfied by silence.
  expect(everything).toMatch(/never a workshop's contents|changes a workshop's CONTENT/i);
});

test("the labels that claim to be nav entries really are nav entries, character for character", () => {
  /*
    The naming rule: a walkthrough must call a screen exactly what the menu calls it, or the guide
    teaches a vocabulary the product does not use. Read off `NAV_ITEMS` as text, because that is the
    only registry these five have — four of them have no Android counterpart to be parity with, and
    the nav's own comment says so where it declares them.

    THE OTHER TWO INSPECTOR CARDS ARE NOT IN THIS LIST AND MUST NOT BE ADDED TO IT. "Workshop under
    inspection" and "Correction suggestions" are a page title and a section heading — real strings
    read off the components, and not nav entries, because neither is a destination in the menu.
  */
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
  // The two Android parity suites and `guide-walkthrough-unit.spec.ts` all read `GUIDE_STEPS`
  // directly. If the designer track ever stopped BEING that array — a copy, a filter, a slice — all
  // three would keep passing while the page rendered something else.
  expect(DESIGNER_TRACK.steps).toBe(GUIDE_STEPS);
  expect(DIRECTORATE_TRACK.steps).toBe(DIRECTORATE_STEPS);
  expect(INSPECTOR_TRACK.steps).toBe(INSPECTOR_STEPS);
});
