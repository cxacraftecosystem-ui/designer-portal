import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  AWAITING_SANCTION_BADGE_CLASS,
  AWAITING_SANCTION_BADGE_HREF,
  MINISTRY_APPROVAL_GAP,
  MINISTRY_DESK,
  awaitingSanctionSentence,
  ministryDeskFor
} from "@/components/dashboard/ministryDesk";
import { DIRECTORATE_STEPS } from "@/components/guide/directorateSteps";
import {
  MINISTRY_DESK_ROLES,
  ROLES_BY_RANK,
  canAccessRoute,
  canSeeMinistryDesk,
  isAdmin,
  isMasterAdmin,
  roleRank
} from "@/lib/permissions";
import { canRecordSanctionOrders } from "@/lib/sanctionOrders";
import type { User, UserRole } from "@/lib/types";

/**
 * THE MINISTRY DESK — the dashboard card that gathers the three directorate posts' screens.
 *
 * ── WHY THIS FILE ENUMERATES NOTHING BY HAND ────────────────────────────────────────────────────
 *
 * It sweeps `ROLES_BY_RANK`, which is DERIVED from `ROLE_RANK` — itself a registered mirror held to
 * `deps.py` by `backend/tests/test_role_ladder_parity.py` and by `docs/tools/check-docs.mjs`. So a
 * twelfth tier reaches every assertion below on the day it lands, with nothing here to remember,
 * and this file adds no new hand-kept copy of the ladder to a repository that has spent a lot of
 * time removing them. The one literal is the card's audience itself, which is the subject.
 *
 * ── WHAT IS PINNED, AND WHY EACH IS THE FAILURE THAT ACTUALLY HAPPENS ───────────────────────────
 *
 *  1. THE AUDIENCE IS THE FOUR TIERS AND NOTHING ELSE. Every rank-shaped instinct gets this set
 *     wrong in a different direction: `isAdmin` admits one of the four and refuses three, and no
 *     rank floor produces it at all, because the set has a hole at ADMIN (50) with MASTER_ADMIN (60)
 *     above it. It is asserted as a set rather than as a floor for exactly that reason.
 *  2. THE CARD CANNOT OFFER A ROW THE ROUTE WOULD REFUSE. This is the assertion that matters most,
 *     and it is checked against `canAccessRoute` — the real guard table — for every tier and every
 *     row rather than against a copy of the predicates. A launcher that draws a tile onto a lock
 *     panel teaches the reader the product is broken; a launcher that hides a tile the route would
 *     serve hides a feature. Both directions fail here.
 *  3. THE ROWS ARE THE DIRECTORATE WALKTHROUGH'S SCREENS, IN ITS ORDER. A launcher and a lesson that
 *     disagree about one job teach two jobs, and a reader has no way to tell which is the product.
 *     This is also the cheapest possible guard against the defect this repository keeps paying for:
 *     a screen added to one surface and forgotten on the other.
 *  4. NOBODY IN THE AUDIENCE GETS AN EMPTY CARD. The component renders null on an empty desk rather
 *     than a panel headed "Your ministry desk" with nothing under it — but "renders null" and
 *     "nobody ever hits it" are different states, and the second is the one worth asserting.
 *  5. THE LABELS ARE THE NAV'S. Four of these five destinations have no Android counterpart to be
 *     parity with, so the web nav is the only registry there is, and a sixth spelling invented on a
 *     card is a name nobody's grep finds.
 *
 * ── AND, SINCE 0.0.12, THREE MORE ──────────────────────────────────────────────────────────────
 *
 *  6. THE CARD IS A MINISTRY SURFACE AND SAYS SO IN COLOUR. /dashboard is not a ministry route and
 *     must never become one, so `AppShell`'s stamp cannot reach this card and it carries its own
 *     `data-surface="ministry"`. What is pinned here is the SURFACE-ACCENT rule: orange on grounds,
 *     borders and chips, purple on everything that is an action or app-wide chrome — and a `dark:`
 *     pair on every ministry text site, without which `text-ministry-700` on a dark card is 2.44:1.
 *     Pinned forward as well as backward: no `.field-button` or `.field-input` this card may grow
 *     later may wear the ramp, which is OQ-2 and the one rule this card is placed to break.
 *  7. THE BADGE IS ONE PILL DRAWN BY TWO FILES. The count rides on "Sanction orders" in the nav and
 *     on the same destination's row here, and on a ministry officer's dashboard both are on screen
 *     at once. The class string, the sentence and the destination are one module's, and the number
 *     is one store's, so the two cannot differ in pixels, wording or arithmetic. Both renderers gate
 *     the fetch on their own copy of "is the badged surface drawn", so BOTH of those are held to
 *     `canRecordSanctionOrders` — the card's row by identity, the nav's entry as text, because
 *     `NAV_ITEMS` is module-private. A surface that asked the count without the permission would be
 *     403'd on every page load, and logged as an authorisation failure by an innocent account.
 *  8. THE DESK DOES NOT IMPLY AN ACT THAT DOES NOT EXIST. Two guards: every row resolves to a real
 *     page.tsx, and the missing approvals hop is stated on screen for exactly as long as the
 *     approvals router is missing — the day it lands, the notice's own test demands its removal.
 */

const ROOT = join(__dirname, "..");
const NAV = join(ROOT, "components", "DynamicIslandNav.tsx");
const CARD = join(ROOT, "components", "dashboard", "MinistryDeskCard.tsx");
const PROTECTED = join(ROOT, "app", "(protected)");

/**
 * The router that would make a workshop approvable, and does not exist.
 *
 * Reached from a frontend spec because the fact being checked is a fact about the PRODUCT rather
 * than about either half of it — the idiom `role-ladder-parity-unit.spec.ts` and three other specs
 * in this folder already use to read `backend/`.
 */
const APPROVALS_ROUTER = join(ROOT, "..", "backend", "app", "api", "routes", "design_workshop_approvals.py");

/**
 * The file with its comments taken out — WHAT IS RENDERED, not what is explained.
 *
 * ⚠ EVERY POSITIVE SOURCE ASSERTION BELOW READS THIS AND NOT THE RAW TEXT, and the reason is a
 * failure this file actually had: these components are documented at length in this repository's
 * house style, so `MinistryDeskCard.tsx` quotes `data-surface="ministry"` and names
 * `MINISTRY_APPROVAL_GAP` in prose several lines before it uses either. A `toContain` over the raw
 * file therefore passed with the attribute deleted from the element — verified by deleting it. A
 * test that a comment can satisfy is a test of the comment.
 *
 * Negative assertions are left on the raw text on purpose: "this class must not appear on the card"
 * is stricter, not weaker, when a comment counts.
 */
const withoutComments = (source: string) => source.replace(/\/\*[\s\S]*?\*\//g, "");

const user = (role: UserRole): User => ({ id: `u-${role}`, email: "a@b.c", name: role, role }) as User;

/**
 * The card's audience, written out once.
 *
 * ⚠ NOT DERIVED FROM `MINISTRY_DESK_ROLES`, which would make this test a restatement of the thing it
 * is testing. Four tokens, typed on purpose, so a widening of that set has to be typed here too and
 * is therefore a decision somebody made rather than one that happened.
 */
const AUDIENCE: UserRole[] = ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN", "MASTER_ADMIN"];

test("the desk is offered to exactly four tiers, and the literal says the same", () => {
  const offered = ROLES_BY_RANK.filter((role) => canSeeMinistryDesk(user(role)));
  expect([...offered].sort()).toEqual([...AUDIENCE].sort());
  expect([...MINISTRY_DESK_ROLES].sort()).toEqual([...AUDIENCE].sort());
});

test("a signed-out caller is not in the audience", () => {
  expect(canSeeMinistryDesk(null)).toBe(false);
  expect(canSeeMinistryDesk(undefined)).toBe(false);
});

test("the audience is not isAdmin, and it is not a rank floor either", () => {
  /*
    STATED AS TWO FAILED SHAPES RATHER THAN AS THE SET AGAIN, because the set is already asserted
    above and what a reader needs from this file is why it could not have been written more simply.

    `isAdmin` is set membership on the two admin tiers. It admits the master admin and refuses the
    three ministry posts — the audience this card exists for — so it is wrong in the loudest possible
    direction: the card would be invisible to everybody it was built for.

    A rank floor is wrong the other way. The tightest floor admitting ASSISTANT_DIRECTOR (42) also
    admits ADMIN (50), who is deliberately out: an admin already has the settings hub and every one
    of these destinations in the nav. There is no floor that skips 50 and keeps 60.
  */
  for (const role of ROLES_BY_RANK) {
    const u = user(role);
    if (isAdmin(u) && !isMasterAdmin(u)) {
      expect(canSeeMinistryDesk(u), `${role}: an admin is out by name`).toBe(false);
    }
  }
  // The hole in the ladder, asserted as a hole: a tier is in, the tier above it is out, and the tier
  // above THAT is in. No threshold can produce that, which is the whole argument for the set.
  const inAt48 = canSeeMinistryDesk(user("MINISTRY_ADMIN"));
  const outAt50 = canSeeMinistryDesk(user("ADMIN"));
  const inAt60 = canSeeMinistryDesk(user("MASTER_ADMIN"));
  expect([inAt48, outAt50, inAt60]).toEqual([true, false, true]);
  expect(roleRank(user("MINISTRY_ADMIN"))).toBeLessThan(roleRank(user("ADMIN")));
  expect(roleRank(user("ADMIN"))).toBeLessThan(roleRank(user("MASTER_ADMIN")));
});

test("a row is offered exactly when the route guard would admit it — every tier, every row", () => {
  /*
    THE ASSERTION THIS FILE EXISTS FOR. `canAccessRoute` reads `ROUTE_GUARDS`, which is the real
    table `AppShell` enforces above every page — not a copy of the predicates, and not the predicates
    the card happens to import. If somebody widens a row's `can` to "make the card look complete",
    or narrows a guard without touching the card, this goes red and names the tier and the route.

    It sweeps the whole ladder, not only the audience, because `ministryDeskFor` is a pure function
    and a row that would be wrongly offered to a designer is a row that is wrong.
  */
  for (const role of ROLES_BY_RANK) {
    const u = user(role);
    const open = new Set(ministryDeskFor(u).map((destination) => destination.href));
    for (const destination of MINISTRY_DESK) {
      expect(
        open.has(destination.href),
        `${role} · ${destination.href}: the card and ROUTE_GUARDS disagree`
      ).toBe(canAccessRoute(u, destination.href));
    }
  }
});

test("nobody in the audience is shown an empty desk", () => {
  /*
    The component returns null on an empty desk rather than drawing a headed panel with nothing in
    it. That branch is correct and it is also unreachable today, which are two different facts: the
    floor below is what makes "unreachable" a checked claim instead of a comment. Every tier in the
    audience clears the sanction register (Assistant Director and above) and the workshop set, so two
    is the minimum.
  */
  for (const role of AUDIENCE) {
    expect(ministryDeskFor(user(role)).length, `${role} would see an empty ministry desk`).toBeGreaterThanOrEqual(2);
  }
});

test("a master admin is offered the desk and not the officer's own read surface", () => {
  /*
    THE ROW THAT PROVES THE PER-ROW GATING IS REAL RATHER THAN DECORATIVE. `assert_oversight_surface`
    answers an admin and the master admin a 403 BY NAME — an admin scoped to their own oversight rows
    sees an empty page and reads it as a broken deployment — so the one destination on this card that
    a master admin cannot open is absent from their card and present on an Assistant Director's.

    If this ever flips, either the server's rule moved or somebody "completed" the card by widening a
    row, and the two need telling apart.
  */
  const masterHrefs = ministryDeskFor(user("MASTER_ADMIN")).map((destination) => destination.href);
  expect(masterHrefs).not.toContain("/officers/monitored");
  expect(ministryDeskFor(user("ASSISTANT_DIRECTOR")).map((d) => d.href)).toContain("/officers/monitored");
});

test("the desk's screens are the directorate walkthrough's screens, in its order", () => {
  expect(MINISTRY_DESK.map((destination) => destination.href)).toEqual(
    DIRECTORATE_STEPS.map((step) => step.href)
  );
  // And by name too, so a href kept while a label drifted still fails.
  expect(MINISTRY_DESK.map((destination) => destination.label)).toEqual(
    DIRECTORATE_STEPS.map((step) => step.label)
  );
});

test("the desk's labels are the nav's labels, character for character", () => {
  const nav = readFileSync(NAV, "utf8");
  for (const destination of MINISTRY_DESK) {
    expect(nav, `NAV_ITEMS has no entry labelled "${destination.label}"`).toContain(
      `label: "${destination.label}"`
    );
  }
});

test("the desk holds no duplicate destination", () => {
  const hrefs = MINISTRY_DESK.map((destination) => destination.href);
  expect(new Set(hrefs).size).toBe(hrefs.length);
});

test("every row on the desk opens a route that exists", () => {
  /*
    A LAUNCHER MAY NOT POINT AT A SCREEN THAT WAS NOT BUILT. Resolved against the app directory
    rather than by fetching, so it needs no server — the idiom `guide-walkthrough-unit.spec.ts` uses
    on its own hrefs. `canAccessRoute` above cannot catch this: a path with no `ROUTE_GUARDS` row is
    an UNGATED path, so a tile invented for a future screen would be reported as open to everybody
    and would land its reader on a 404. That is the shape of mistake this release was asked to
    prevent on this card specifically, and it costs one existsSync.
  */
  for (const destination of MINISTRY_DESK) {
    const dir = join(PROTECTED, destination.href.split("?")[0].replace(/^\//, ""));
    expect(existsSync(join(dir, "page.tsx")), `${destination.label} → ${destination.href}`).toBe(true);
  }
});

test("the missing approvals hop is stated on the card, and stops being stated the day it lands", () => {
  /*
    THE NOTICE IS TIED TO THE THING IT IS ABOUT, so it cannot outlive it. The ministry's own terminus
    — read the report back and sign it off — has no router: `design_workshop_approvals.py` does not
    exist, `DECISION_EDGES` names its three verbs against a router "this workstream does not build",
    and `PATCH /design-workshops/{id}` refuses all three edges so no header edit can manufacture an
    approval either. The five rows are drawn "in the order a workshop reaches them" and an ordered
    sequence reads as a complete one, so the card says the sequence stops.

    When the approvals workstream lands, this test goes red and the sentence must come out in the
    same commit. A "not built yet" notice that outlives the thing not being built teaches the reader
    that the product's own copy cannot be trusted, which is more expensive than never having said it.
  */
  const rendered = withoutComments(readFileSync(CARD, "utf8"));
  if (existsSync(APPROVALS_ROUTER)) {
    expect(
      rendered,
      "the approvals router exists now — delete MINISTRY_APPROVAL_GAP and the paragraph that renders it"
    ).not.toContain("MINISTRY_APPROVAL_GAP");
    return;
  }
  // The INTERPOLATION, not the identifier: an import left behind after the paragraph was deleted
  // satisfies a bare `toContain` and says nothing on screen. Verified by deleting the paragraph.
  expect(rendered, "the card must say on screen that the sequence stops short").toContain(
    "{MINISTRY_APPROVAL_GAP}"
  );

  // The SHAPE of the sentence, not its wording: it must name the act that is missing, name what an
  // officer can still do instead, and promise nothing. Who may approve at all is an open product
  // question, so copy naming a tier or a release would be this card inventing the answer.
  expect(MINISTRY_APPROVAL_GAP).toMatch(/approve/i);
  expect(MINISTRY_APPROVAL_GAP).toMatch(/send(ing)? a report back/i);
  expect(MINISTRY_APPROVAL_GAP, "no promises: no date, no version, no tier").not.toMatch(
    /soon|shortly|next release|coming|0\.0\.\d|will be able/i
  );
});

test("the badged destination is a real desk row, and its predicate is the endpoint's own mirror", () => {
  /*
    The count is only ever spent when its row is on screen, which is how both renderers avoid asking
    an endpoint the caller would be refused. That argument only holds if the row's predicate IS the
    mirror of `require_sanction_recorder` — so it is asserted by IDENTITY rather than by agreeing
    today: a row switched to a predicate that merely happens to admit the same tiers would put a
    request behind a 403 for somebody, and the 403 is logged against an account doing nothing wrong.
  */
  const badged = MINISTRY_DESK.find((destination) => destination.href === AWAITING_SANCTION_BADGE_HREF);
  expect(badged, `no desk row answers to ${AWAITING_SANCTION_BADGE_HREF}`).toBeTruthy();
  expect(badged!.can).toBe(canRecordSanctionOrders);
});

test("the nav entry the badge also sits on is gated by that same mirror", () => {
  /*
    THE OTHER HALF OF THE ASSERTION ABOVE, AND UNTIL NOW IT WAS PINNED BY NOTHING.

    The count is drawn on two surfaces and each computes its own `enabled`. The card's is this
    module's table, whose predicate is asserted by IDENTITY one test up. The nav's is
    `visibleItems.some((item) => item.href === AWAITING_SANCTION_BADGE_HREF)`, which folds in
    whatever `can` that NAV_ITEMS row happens to carry — and `NAV_ITEMS` is module-private, so it
    cannot be imported and compared. Read as text instead, which is what the label test above
    already does to the same file and for the same reason.

    What it protects: widen that one row to a neighbouring predicate and the nav spends a request on
    `/sanction-orders/awaiting-count` on every page load by an account `require_sanction_recorder`
    answers 403 — logged, on the server, as an authorisation failure by somebody doing nothing
    wrong. The desk card would stay green throughout, because its row is a different literal in a
    different file. `gate:` is checked beside `can:` because that field is this nav's own record of
    which server dependency the entry mirrors, and the two drifting apart is the same defect said
    twice.

    Sliced from the constant to the NEXT `href:`, so the assertion cannot be satisfied by a `can`
    belonging to the entry after it.
  */
  const nav = withoutComments(readFileSync(NAV, "utf8"));
  const after = nav.split(`href: "${AWAITING_SANCTION_BADGE_HREF}"`)[1];
  expect(after, `NAV_ITEMS has no entry for ${AWAITING_SANCTION_BADGE_HREF}`).toBeTruthy();
  const entry = after.split("href:")[0];
  expect(entry, "the badged nav entry must be gated by the endpoint's own mirror").toContain(
    "can: canRecordSanctionOrders"
  );
  expect(entry, "…and must still say which server dependency that mirrors").toContain(
    'gate: "require_sanction_recorder"'
  );
});

test("the count's sentence says what it counts, in both plurals", () => {
  /*
    NOT A BARE DIGIT — the rule both badges beside it state. The plural rule is a function rather
    than JSX for exactly this: a screen only exercises the singular arm on the day the database holds
    precisely one stalled order.
  */
  expect(awaitingSanctionSentence(1)).toBe("1 sanction order is awaiting its designer's details");
  expect(awaitingSanctionSentence(4)).toBe("4 sanction orders are awaiting their designers' details");
  for (const count of [1, 2, 17]) {
    expect(awaitingSanctionSentence(count)).toContain(String(count));
    expect(awaitingSanctionSentence(count), "the sentence names what is waiting").toMatch(/awaiting/i);
  }
});

test("one pill, two renderers: the class string is shared, and it is not a ministry rung", () => {
  /*
    The nav's other two badges are one component each, rendered twice inside one file, so a literal
    class string cannot drift for them. This one is drawn by two components in two files and both are
    on a ministry officer's dashboard at once, so the pixels live in `ministryDesk.ts` and both read
    them from there.

    AND IT IS AMBER, DELIBERATELY, THOUGH IT RIDES ON A MINISTRY DESTINATION. `ministry-700` and
    `amber-800` are ΔE 0.0043 apart in OKLab — the same colour — and the nav's sheet is one flat list
    of every destination, so on a master admin's sheet the access queue's amber "waiting to be
    approved to sign in" pill sits in the same column as this one. An orange pill here would be
    indistinguishable from it while meaning something entirely different. The register's own
    readiness pill is this exact pair, so the badge is that pill added up, in that pill's colour.
  */
  const nav = withoutComments(readFileSync(NAV, "utf8"));
  const card = withoutComments(readFileSync(CARD, "utf8"));
  expect(nav).toContain("AWAITING_SANCTION_BADGE_CLASS");
  expect(card).toContain("AWAITING_SANCTION_BADGE_CLASS");
  expect(AWAITING_SANCTION_BADGE_CLASS).toContain("bg-amber-100");
  expect(AWAITING_SANCTION_BADGE_CLASS).toContain("text-amber-800");
  expect(AWAITING_SANCTION_BADGE_CLASS, "the badge is not painted from the ministry ramp").not.toContain(
    "ministry-"
  );
});

test("the badge reaches the sheet as well as the hover menu, and neither renderer fetches unconditionally", () => {
  /*
    THE SHEET IS THE KEYBOARD AND TOUCH ROUTE TO EVERY DESTINATION — the desktop dropdowns are
    pointer-only by design — so a notification drawn only in the dropdown does not reach an officer
    on a tablet. Held to the badge beside it rather than to the number 2, so a third menu would have
    to grow both badges or neither.

    `enabled` is the other half: a hook called with a literal `true` would ask
    `/sanction-orders/awaiting-count` on every page load by every account in the product, and the
    endpoint is `require_sanction_recorder`.
  */
  const nav = withoutComments(readFileSync(NAV, "utf8"));
  const card = withoutComments(readFileSync(CARD, "utf8"));
  const drawn = (source: string, tag: string) => source.split(`<${tag}`).length - 1;
  expect(drawn(nav, "AwaitingSanctionBadge")).toBe(drawn(nav, "PendingAccessBadge"));
  expect(drawn(nav, "AwaitingSanctionBadge")).toBeGreaterThanOrEqual(2);
  for (const [name, source] of [["the nav", nav], ["the desk card", card]] as const) {
    expect(source, `${name} draws the badge`).toContain("useAwaitingSanctionCount");
    expect(source, `${name} must gate the fetch on what it renders`).not.toMatch(
      /useAwaitingSanctionCount\(\s*true\s*\)/
    );
  }
});

test("the card carries its own ministry stamp, on the panel element itself", () => {
  /*
    /dashboard is not a ministry route and must never become one — a designer's dashboard is the same
    screen — so `AppShell`'s stamp cannot reach this card and it opts in on its own root. That only
    works because every rule in the scoped block is written twice, as a descendant AND as a
    self-match: the attribute is on the `.panel` element, so `[data-surface="ministry"] .panel` alone
    would have matched nothing here and failed with no error anywhere.
  */
  const opening = withoutComments(readFileSync(CARD, "utf8")).match(/<section\b[^>]*>/)?.[0] ?? "";
  expect(opening, "the card has a <section> root").toContain("<section");
  expect(opening, "the stamp belongs on the panel element itself, not on a wrapper").toContain(
    'className="panel mb-6 p-4 sm:p-5"'
  );
  expect(opening, "…and the attribute belongs on that same element").toContain('data-surface="ministry"');

  const globals = readFileSync(join(ROOT, "app", "globals.css"), "utf8");
  expect(globals, "the scoped block must keep its self-matching selector or this card loses its rule").toContain(
    '.panel[data-surface="ministry"]'
  );
});

test("the accent is a surface accent: the tiles turn, the focus ring does not", () => {
  /*
    SURFACE ACCENT ONLY. Orange is the icon chips, the hover border and the walkthrough link; purple
    stays on everything that is an action or app-wide chrome. The focus ring is the case worth
    pinning: these tiles are <Link>s, i.e. <a>s, and globals.css already draws every focused anchor a
    purple `outline` that no scope re-points — an orange `ring` would be drawn immediately inside it
    and the tile would wear two accent colours on one keyboard focus.
  */
  const raw = readFileSync(CARD, "utf8");
  const rendered = withoutComments(raw);
  expect(raw, "the tile chip is a ministry ground now").not.toContain("bg-purple-50");
  expect(raw, "the walkthrough link is ministry ink now").not.toContain("text-purple-700");
  expect(raw, "the hover border is ministry now").not.toContain("hover:border-purple-300");
  expect(rendered, "the focus ring stays purple, deliberately").toContain("focus-visible:ring-purple-700");
  expect(rendered).toContain("bg-ministry-50");
  expect(rendered).toContain("hover:border-ministry-300");

  /*
    OQ-2 ITSELF, STATED FORWARD RATHER THAN BACKWARD. Everything above pins what this card looks
    like today; this pins the one rule it could break tomorrow. There is no button and no input on
    this card, so the swap had nothing to argue about — but a card whose whole subject is "your
    screens" is exactly where a "Record an order" control gets added later, and painting it from
    this ramp is the mistake OQ-2 exists to refuse: `ministry-700` is ΔE 0.004 from the `amber-800`
    of the "Withdrawn" and "Awaiting designer details" pills — the second of which this very card
    draws — so an orange action control is indistinguishable from a status it is not. `.field-button`
    also carries `hover:shadow-cta`, a literal purple glow at hue 305, so it would throw a purple
    halo while doing it. One regex, both recipe classes, either order.
  */
  expect(rendered, "OQ-2: no action control on this card may wear the ministry ramp").not.toMatch(
    /(?:field-button|field-input)[^"]*\bministry-|\bministry-[^"]*(?:field-button|field-input)/
  );
});

test("every ministry ink and ground on the card carries its dark pair", () => {
  /*
    THE RAMP IS LITERAL AND DOES NOT INVERT, exactly as purple does not. `text-ministry-700` on
    `bg-card` in dark is 2.44:1 and `bg-ministry-50` is a near-white peach in BOTH themes, so a chip
    written without a dark half paints a bright patch on a dark card with near-black ink on it —
    which is the `SearchableSelect` failure its own comment already records for purple.

    Only INK and GROUNDS are held to this. `hover:border-ministry-300` is exempt on purpose: a border
    is decoration rather than text, it reads against both grounds, and S0's own swap table specifies
    it without a pair.
  */
  const painted = withoutComments(readFileSync(CARD, "utf8"))
    .split("\n")
    .filter((line) => line.includes("className"))
    .filter((line) =>
      (line.match(/[A-Za-z0-9:/[\]._-]+/g) ?? []).some(
        (token) => !token.startsWith("dark:") && /^(?:[a-z-]+:)*(?:text|bg)-ministry-/.test(token)
      )
    );
  expect(painted.length, "the card paints with the ministry ramp at all").toBeGreaterThanOrEqual(2);
  for (const line of painted) {
    expect(line.trim(), "ministry ink or ground with no dark: pair").toMatch(/dark:(?:text|bg)-ministry-/);
  }
});
