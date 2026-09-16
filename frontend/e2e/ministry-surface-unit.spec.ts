import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ROUTE_GUARDS, canRunDesignWorkshops, ministrySurface } from "@/lib/permissions";

/**
 * THE MINISTRY SURFACE — four screens painted as the ministry's own, and the three ways that goes
 * wrong.
 *
 * ── THE MECHANISM, IN ONE PARAGRAPH, SO THIS FILE READS WITHOUT A TOUR ──────────────────────────
 *
 * `ROUTE_GUARDS` rows carry an optional `ministry` flag. `ministrySurface(pathname)` resolves a path
 * to the row that GUARDS it (longest match, `routeGuardFor`) and reads the flag. `AppShell` asks that
 * once per navigation and stamps `data-surface="ministry"` on <main>. One scoped block at the end of
 * `app/globals.css` hangs off the attribute and repaints three recipe classes — the page header's
 * icon chip, a rule down the left edge of every panel, the eyebrow. Nothing else joins the two.
 *
 * ── THE THREE FAILURES THIS PINS, AND WHY EACH IS THE ONE THAT ACTUALLY HAPPENS ─────────────────
 *
 *  1. THE FLAG QUIETLY UNDER-REPORTS. The tempting way to write `ministrySurface` is to compare
 *     `guard.can` against the ministry predicates — and /sanction-orders' `can` is an INLINE ARROW
 *     written out on the row (a named reference would be an import cycle through
 *     `lib/sanctionOrders.ts`, and its comment says so), so identity comparison finds three of four
 *     and the sanction register stops being a ministry surface with nothing on screen to say so.
 *     Asserted as an exact set of paths, in both directions.
 *
 *  2. SOMEBODY FLAGS /design-workshops. It is the fifth row of the ministry DESK card, so "wherever
 *     the desk points" reads like the definition of the surface — and it is gated on
 *     `canRunDesignWorkshops`, i.e. it is the DESIGNERS' daily workspace. Flagging it turns every
 *     designer's main screen orange. The check is against the PREDICATE rather than the path, so it
 *     also catches a future ministry-looking route that happens to be gated the same way.
 *
 *  3. THE SCOPED BLOCK REACHES A UTILITY CLASS. `[data-surface="ministry"] .text-purple-700 { … }`
 *     is the tidy-looking way to "finish the job" and it destroys information: `StatusBadge` paints
 *     NEEDS_REVISION and IN_PROGRESS `border-purple-300 bg-purple-50 text-purple-700`, deliberately
 *     sharing one treatment, and the SAME workshop row is drawn on /design-workshops, on
 *     /design-workshop-inspections and on /officers/monitored — so one workshop would read purple on
 *     a designer's screen and orange on an officer's. Read off the stylesheet as text, which is the
 *     idiom `ministry-desk-unit.spec.ts` already uses on `DynamicIslandNav.tsx`: there is no CSS
 *     parser in devDependencies and a selector is a string, so a string is what is checked.
 *
 * ── WHY THE COUNT OF ROWS IS IN HERE AT ALL ─────────────────────────────────────────────────────
 *
 * `ROUTE_GUARDS.length` is asserted because the four flagged rows are stated as a literal below, and
 * a literal set over a table that grew is a set that stopped being exhaustive without failing. It is
 * meant to be updated by hand, deliberately: adding a guarded route is a decision, and being made to
 * type the new total is the cheapest possible moment to ask "is this one a ministry surface?". The
 * number is TWENTY-FOUR — read that test, which explains why every grep for it answers twenty-one.
 */

const ROOT = join(__dirname, "..");
const GLOBALS = join(ROOT, "app", "globals.css");
const APP_SHELL = join(ROOT, "components", "AppShell.tsx");
const PAGE_HEADER = join(ROOT, "components", "PageHeader.tsx");
const TAILWIND = join(ROOT, "tailwind.config.ts");

/**
 * The four, written out rather than derived.
 *
 * ⚠ NOT `ROUTE_GUARDS.filter((g) => g.ministry)`, which would make the first test a restatement of
 * the thing it is testing. Four paths, typed on purpose, so a fifth ministry surface has to be typed
 * here too and is therefore a decision somebody made rather than one that happened.
 */
const MINISTRY_PATHS = ["/annual-plan", "/officers", "/officers/monitored", "/sanction-orders"];

/**
 * The one scoped block, lifted out of globals.css — WITH ITS COMMENTS STRIPPED.
 *
 * Stripping is not tidiness. That block's header states its own prohibitions in prose, naming
 * `.text-purple-700`, `.field-button` and `--purple-700` as the things it must never contain, so a
 * substring check over the raw text fails on the very sentence that forbids what it is hunting for.
 * Comments out, declarations in, and the tests below read only what a browser would.
 */
function ministryCss(): string {
  // Strip FIRST, slice second. Slicing first would start the text in the middle of the block's own
  // header comment, with no `/*` in front of it to recognise — so the prose would survive as far as
  // the first `*/` and every prohibition it names would read as a declaration.
  const bare = readFileSync(GLOBALS, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
  const from = bare.indexOf('[data-surface="ministry"]');
  expect(from, "the ministry block has left globals.css").toBeGreaterThan(-1);
  return bare.slice(from);
}

/** Every selector in the scoped block that targets `[data-surface="ministry"]`, as written. */
function ministrySelectors(): string[] {
  return ministryCss()
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.includes('[data-surface="ministry"]'));
}

test("ministrySurface agrees with the ministry rows, and with nothing else", () => {
  const flagged = ROUTE_GUARDS.filter((guard) => guard.ministry).map((guard) => guard.path);
  expect([...flagged].sort()).toEqual([...MINISTRY_PATHS].sort());

  /*
    BOTH DIRECTIONS, over the whole table. A row that gained the flag by accident fails on the
    second loop; a row that lost it fails on the first. Asked of `ministrySurface` rather than of
    the flag, because the helper is what `AppShell` calls and the flag is only its input — a helper
    that returned `false` for everything would still pass a test that read rows.
  */
  for (const guard of ROUTE_GUARDS) {
    expect(
      ministrySurface(guard.path),
      `${guard.path}: the flag and ministrySurface() disagree`
    ).toBe(guard.ministry === true);
  }
});

test("the surface is inherited by a route's subtree and does not leak sideways", () => {
  // The whole reason `ministrySurface` asks `routeGuardFor` instead of matching paths itself: one
  // officer's workshop is /officers/monitored/<id>, and it is the same surface as its index.
  expect(ministrySurface("/officers/monitored/ckq12345")).toBe(true);
  expect(ministrySurface("/annual-plan")).toBe(true);

  // Longest match decides, so a NARROWER row nested under a ministry one could opt its own subtree
  // out by simply not carrying the flag — the same override /officers/monitored already performs on
  // /officers for a disjoint audience. Nothing does that today; the mechanism is what is pinned.
  expect(ministrySurface("/officers")).toBe(true);

  // Segment-wise matching, not string prefix: the guard table's own rule, inherited for free.
  expect(ministrySurface("/officers-elsewhere")).toBe(false);
  expect(ministrySurface("/annual-planning")).toBe(false);

  // Unguarded routes, and the dashboard in particular: the ministry desk card lives there and
  // carries `data-surface="ministry"` on its own root precisely because this answers false.
  expect(ministrySurface("/dashboard")).toBe(false);
  expect(ministrySurface("/guide")).toBe(false);
  expect(ministrySurface("/")).toBe(false);
});

test("no ministry row is gated on canRunDesignWorkshops", () => {
  /*
    /design-workshops IS A MINISTRY-DESK DESTINATION AND IS NOT A MINISTRY SURFACE, and those are
    different claims about the same route. Checked by predicate rather than by path so a future
    route gated the same way — the designers' workspace under another name — is caught too.
  */
  for (const guard of ROUTE_GUARDS) {
    if (!guard.ministry) continue;
    expect(
      guard.can,
      `${guard.path}: flagged ministry but gated on canRunDesignWorkshops — that is the designers' workspace`
    ).not.toBe(canRunDesignWorkshops);
  }
  expect(ministrySurface("/design-workshops")).toBe(false);
  expect(ministrySurface("/design-workshops/ckq12345")).toBe(false);
});

test("the guard table still has twenty-four rows", () => {
  /*
    TWENTY-FOUR, and updating this literal is supposed to be a deliberate act rather than a
    formality. It is what keeps MINISTRY_PATHS above honest: a literal set over a table that grew
    silently stops being exhaustive, and being made to type the new total is the cheapest possible
    moment to ask "is this new route a ministry surface?".

    ⚠ TWENTY-FOUR AND NOT TWENTY-ONE, AND THE DIFFERENCE IS A GREP THAT CANNOT SEE THREE ROWS.
    Grepping this table for lines beginning `path:` answers twenty-one, and that number has been
    carried into planning documents as a verified fact. It is wrong by construction: the last three
    rows are written on ONE LINE EACH — `{ path: "/artisans/new", ...RECORD_CREATOR_GUARD }` and its
    two siblings — so `path:` is not the first thing after the indent and the pattern skips them.
    The length of the array is the only honest count, which is why this asserts the array and never
    a grep. Do not "correct" it back down.
  */
  expect(ROUTE_GUARDS.length).toBe(24);
});

test("the scoped block never reaches a utility class", () => {
  /*
    THE ASSERTION THAT PROTECTS INFORMATION RATHER THAN APPEARANCE. See failure 3 in the header:
    a descendant rule on `.text-purple-700` or `.bg-purple-50` repaints every StatusBadge on the
    page, and the same workshop then reads purple on a designer's screen and orange on an officer's.
  */
  for (const selector of ministrySelectors()) {
    for (const utility of [".text-purple-", ".bg-purple-", ".border-purple-", ".ring-purple-"]) {
      expect(
        selector,
        `the ministry block targets ${utility}* — that repaints StatusBadge across the app`
      ).not.toContain(utility);
    }
  }
});

test("the ministry accent is spent on no action control", () => {
  /*
    OQ-2, ENFORCED RATHER THAN REMEMBERED. Purple-700 is the only action colour in this product and
    a ministry page is not an exception: `ministry-700` computes to #923e0d against `amber-800`
    #92400e — ΔE 0.004, the same colour — and amber is drawn on all four of these screens, so an
    orange button would sit beside an indistinguishable "Withdrawn" pill. `.field-button` also
    carries `hover:shadow-cta`, which is a literal purple glow at hue 305 and cannot be scoped.

    Stated as forbidden SELECTORS rather than as forbidden properties, because the block is scoped
    by selector: if none of these classes is ever the subject, no button, input, upload trigger or
    focus ring on a ministry page can be anything but purple.
  */
  const block = ministryCss();
  for (const control of [
    ".field-button",
    ".field-button-secondary",
    ".field-input",
    ".file-trigger",
    ":focus-visible"
  ]) {
    expect(block, `the ministry block restyles ${control} — the accent is surface-only`).not.toContain(
      control
    );
  }

  /*
    And it re-points no custom property. `--purple-700` is read by the global focus outline, the
    audio range's outline and `.fr-flash-row` — three declarations, all of them action-coloured.
    `--bg-0` and `--card` are worse: `THEME_COLOR` in lib/preferences.ts drives the mobile address
    bar and is rewritten on a PREFERENCE change and never on a navigation, so an orange canvas would
    leave the address bar lavender with no code path in the product that could ever correct it.
  */
  for (const token of ["--purple-700", "--bg-0:", "--card:"]) {
    expect(block, `the ministry block re-points ${token}`).not.toContain(`${token} `);
  }
});

test("every ministry rule carries its dark pair", () => {
  /*
    THE RAMP IS LITERAL AND DOES NOT INVERT, exactly as purple does not. `text-ministry-700` on a
    dark `bg-card` measures 2.44:1, under the 4.5:1 floor `e2e/a11y-barriers.spec.ts` enforces; the
    `dark:ministry-300` pair is 10.06:1. That is not a regression — `text-purple-700` is already
    2.32:1 in the same position — but it multiplies across every new site, so it is checked at the
    only place all of them are written down. `bg-ministry-100` needs it just as much in the other
    direction: a near-white peach chip painted onto a dark card is the `SearchableSelect` failure
    its own comment already names for purple-50.
  */
  const rules = ministryCss()
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.startsWith("@apply"));
  expect(rules.length, "the ministry block declares no rules at all").toBeGreaterThan(0);
  for (const rule of rules) {
    expect(rule, `no dark: pair on "${rule}" — ministry ink on a dark card is 2.44:1`).toContain(
      "dark:"
    );
  }
});

test("the block matches both the descendant and the self case", () => {
  /*
    A component OUTSIDE the four routes opts in by putting `data-surface="ministry"` on its own root
    — the ministry desk card on /dashboard does exactly that, and its root IS the `.panel`. A
    descendant combinator alone would not match an element that carries the attribute itself, and
    the symptom is the worst kind: no error, no warning, a rule that simply never applies.
  */
  const panelSelectors = ministrySelectors().filter((line) => line.includes(".panel"));
  expect(panelSelectors).toContain('[data-surface="ministry"] .panel,');
  expect(panelSelectors).toContain('.panel[data-surface="ministry"] {');
});

test("AppShell stamps the attribute, and only on a page it is serving", () => {
  const shell = readFileSync(APP_SHELL, "utf8");
  expect(shell).toContain('data-surface={ministry ? "ministry" : undefined}');
  /*
    `!blocked` is the load-bearing half. What <main> holds when a guard refuses is `RouteLocked` —
    a padlock shown to somebody who is NOT a ministry account — and painting the ministry accent
    onto it puts the mark in front of exactly the person it is not for, which is the same argument
    the three self-refusal panels inside /officers carry for staying purple.
  */
  expect(shell).toContain("const ministry = !blocked && ministrySurface(pathname);");
});

test("PageHeader carries the hook the block hangs off", () => {
  /*
    `field-header-chip` has no rule of its own anywhere — it is an inert class name whose whole
    purpose is to be a stable selector. Which means nothing on any screen goes wrong if it is
    deleted, and the ministry header silently stops being a ministry header. Hence this line.
  */
  expect(readFileSync(PAGE_HEADER, "utf8")).toContain('className="field-header-chip mt-1 grid');
  expect(ministryCss()).toContain(".field-header-chip");
});

test("the ministry ramp is eleven literal rungs at one hue", () => {
  /*
    LITERAL `oklch()`, NEVER `rgb(var(--…))`. A themed neutral inverts under `data-theme="dark"`;
    a brand ramp must not, because every `dark:` pair in the block above is written on the
    assumption that `ministry-300` means the same colour in both themes. And eleven rungs, because
    the swap table other slices work from pairs each ministry rung with its purple twin 1:1 — a
    missing rung is a `text-ministry-500` that silently compiles to nothing.

    The hue is asserted as ONE value across the ramp rather than as 45 specifically: what matters
    is that a rung was not re-derived at a different hue, which is how a ladder stops being a ladder.
  */
  const config = readFileSync(TAILWIND, "utf8");
  const ramp = config.slice(config.indexOf("const ministry = {"));
  const hues = new Set<string>();
  for (const rung of [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950]) {
    const match = ramp.match(new RegExp(`\\n  ${rung}: "oklch\\(([\\d.]+) ([\\d.]+) ([\\d.]+) /`));
    expect(match, `ministry-${rung} is missing or is not a literal oklch()`).not.toBeNull();
    hues.add(match![3]);
  }
  expect([...hues], "the ministry rungs are not all at one hue").toHaveLength(1);
  expect(ramp).not.toContain("rgb(var(");
});
