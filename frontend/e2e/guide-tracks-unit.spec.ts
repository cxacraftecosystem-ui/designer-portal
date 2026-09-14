import { existsSync } from "node:fs";
import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { DIRECTORATE_STEPS } from "@/components/guide/directorateSteps";
import { INSPECTOR_STEPS } from "@/components/guide/inspectorSteps";
import { GUIDE_STEPS } from "@/components/guide/steps";
import {
  DESIGNER_TRACK,
  DIRECTORATE_TRACK,
  GUIDE_TRACKS,
  INSPECTOR_TRACK,
  guideTrackFor,
  guideTrackForAnchor
} from "@/components/guide/tracks";
import { canAccessRoute } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * THREE WALKTHROUGHS OVER ONE RENDERER, AND THE ROLE PICKS WHICH ONE OPENS.
 *
 * `/guide` taught one deck — a designer's fortnight — to every signed-in account, including two
 * audiences whose own screens are not in it: the three directorate posts, and the Inspector /
 * Reviewer tier. `components/guide/tracks.ts` now registers three decks and chooses a default from
 * the reader's role, while the switcher on the page reaches all three for everybody.
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
 *  8. THE SWITCHER REACHES EVERY REGISTERED DECK. If a deck is added to the module and not to
 *     `GUIDE_TRACKS`, the role could open it and no reader could ever leave it or reach it — the
 *     "ungated page" argument the whole design rests on would be quietly false.
 */

const ROOT = join(__dirname, "..");
const PROTECTED = join(ROOT, "app", "(protected)");
const NAV = join(ROOT, "components", "DynamicIslandNav.tsx");

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

test("the switcher reaches every deck this module declares", () => {
  // Written as a set rather than a length, so adding a fourth deck and forgetting to register it
  // fails by NAME instead of by an off-by-one somebody has to go and diff.
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

    ⚠ THE DESIGNER'S DECK IS DELIBERATELY NOT IN THIS ASSERTION, and saying so is the point rather
    than an exemption taken quietly. Its default audience is EVERYBODY — it is the fallback arm of
    `guideTrackFor` — including CROWDSOURCE_VOLUNTEER (10), who is below the Field Contributor floor
    that `/review` admits. So its "Review" tile has offered a padlock to the bottom tier for as long
    as the outro has existed. That predates these decks, it is a one-line fix in `GuideOutro` (filter
    `track.next` on `canAccessRoute`), and it was left alone rather than folded into this change
    silently: narrowing a surface nobody asked to narrow, inside a change about three other things,
    is how a review loses track of what it approved. Asserting it here and then exempting the one
    deck it fails on would be worse — it would report green over a known gap.
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
