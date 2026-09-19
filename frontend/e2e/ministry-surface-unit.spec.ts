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
 * `app/globals.css` hangs off the attribute and repaints two recipe classes — the page header's
 * icon chip and the eyebrow. Nothing else joins the two.
 *
 * It repainted a third until 2026-09-17: `.panel` carried a 2px accent stroke down its left edge —
 * the "ministry spine" — on all 22 panels of the four routes plus the desk card that opts in on
 * /dashboard. It was removed at the product owner's direction; the tombstone left in its place in
 * `globals.css` carries the argument, including why nothing is lost by its absence. Do not read the
 * count above as a budget: the block may carry one recipe class or five, and the tests below are
 * written to hold either way.
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
 * The five, written out rather than derived.
 *
 * ⚠ NOT `ROUTE_GUARDS.filter((g) => g.ministry)`, which would make the first test a restatement of
 * the thing it is testing. Five paths, typed on purpose, so a sixth ministry surface has to be typed
 * here too and is therefore a decision somebody made rather than one that happened.
 *
 * `/ministry-dashboard` joined on 2026-09-20 — the ministry's whole-estate register. It is TOP-LEVEL
 * and that is load-bearing rather than a filing preference: `routeMatches` compares whole segments,
 * so nesting it at `/officers/dashboard` would inherit `canAssignWorkshopOversight`, a SET that
 * refuses a REGIONAL DIRECTOR and an ASSISTANT DIRECTOR — two thirds of the page's own audience.
 * Its guard row carries the argument in full.
 */
const MINISTRY_PATHS = [
  "/annual-plan",
  "/ministry-dashboard",
  "/officers",
  "/officers/monitored",
  "/sanction-orders"
];

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

test("the guard table still has twenty-five rows", () => {
  /*
    TWENTY-FIVE since 2026-09-20, and updating this literal is supposed to be a deliberate act
    rather than a formality. It is what keeps MINISTRY_PATHS above honest: a literal set over a table that grew
    silently stops being exhaustive, and being made to type the new total is the cheapest possible
    moment to ask "is this new route a ministry surface?".

    ⚠ IT IS FIVE MORE THAN A GREP REPORTS, AND THE DIFFERENCE IS THREE ROWS THE PATTERN CANNOT SEE.
    Grepping this table for lines beginning `path:` answers twenty-two, and the equivalent number has
    been carried into planning documents as a verified fact. It is wrong by construction: the last three
    rows are written on ONE LINE EACH — `{ path: "/artisans/new", ...RECORD_CREATOR_GUARD }` and its
    two siblings — so `path:` is not the first thing after the indent and the pattern skips them.
    The length of the array is the only honest count, which is why this asserts the array and never
    a grep. Do not "correct" it back down.
  */
  expect(ROUTE_GUARDS.length).toBe(25);
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

test("the ministry accent reaches the action controls, and stops where it was told to", () => {
  /*
    ⚠ THIS TEST USED TO ASSERT THE OPPOSITE, AND THE REVERSAL IS AN OWNER RULING OF 2026-09-20 RATHER
    THAN A CORRECTION. What it enforced was OQ-2 — "the ministry accent is spent on no action
    control" — by forbidding the strings `.field-button`, `.field-button-secondary`, `.field-input`,
    `.file-trigger` and `:focus-visible` anywhere in the block. Its reasoning, kept here in its own
    words because the day somebody proposes putting it back, this is the argument they are making:

      "Purple-700 is the only action colour in this product and a ministry page is not an exception:
       `ministry-700` computes to #923e0d against `amber-800` #92400e — ΔE 0.004, the same colour —
       and amber is drawn on all four of these screens, so an orange button would sit beside an
       indistinguishable 'Withdrawn' pill. `.field-button` also carries `hover:shadow-cta`, which is
       a literal purple glow at hue 305 and cannot be scoped."

    THE RULING IS REVERSED. THE TWO MEASUREMENTS INSIDE IT ARE NOT, and this test is now the place
    both are enforced rather than the place the question was avoided. What it checks, in order: the
    orange reaches the four controls; the glow is the hue-45 twin and never the purple one; each
    button rule restates the states the cascade would otherwise hand it; the secondary keeps ordinary
    ink so it cannot be mistaken for a status pill; and the two things the owner did NOT overrule are
    still absent.
  */
  const block = ministryCss();

  // ── 1. THE ACCENT ACTUALLY ARRIVES ──────────────────────────────────────────────────────────
  // Asserted as PRESENCE, not merely as permission. The change is worth nothing if a later edit
  // quietly drops a rule, and "the block no longer mentions .field-button" is invisible in review.
  for (const control of [".field-button", ".field-button-secondary", ".field-input", ".file-trigger"]) {
    expect(block, `the ministry block no longer repaints ${control} — the owner asked for it`).toContain(
      control
    );
  }

  /*
    ── 2. THE HOVER GLOW IS THE HUE-45 TWIN ────────────────────────────────────────────────────
    `hover:shadow-cta` is a Tailwind TOKEN compiled to a utility class, not a custom property, so no
    selector here can re-point it — an orange button spending it would throw a saturated PURPLE glow.
    `theme.extend.boxShadow["cta-ministry"]` exists for this one line. Both halves are checked: the
    twin is spent, and the original is not.
  */
  expect(block, "the ministry primary does not spend the hue-45 glow").toContain("shadow-cta-ministry");
  expect(
    block.replace(/shadow-cta-ministry/g, ""),
    "the ministry block still spends `shadow-cta`, which is a literal purple glow at hue 305"
  ).not.toContain("shadow-cta");

  /*
    ── 3. EVERY STATE THE BASE RECIPE CARRIES IS RESTATED ──────────────────────────────────────
    `.field-button:hover` and `.field-button:disabled` are (0,2,0) — identical to
    `[data-surface="ministry"] .field-button` — and this block is LAST in the file, so it wins the
    source-order tie on `background-color`. A rule that set only the resting ground would paint the
    same orange on hover (no hover response at all) and, worse, the same orange when DISABLED,
    erasing the disabled affordance on every button on the surface.
  */
  const primary = ruleFor(block, ".field-button");
  expect(primary, "the ministry primary sets no hover ground, so hover is the resting colour").toContain(
    "hover:bg-ministry-"
  );
  expect(primary, "the ministry primary sets no disabled ground — a disabled button stays orange").toContain(
    "disabled:bg-line-200"
  );
  expect(primary, "the ministry primary drops the disabled ink").toContain("disabled:text-ink-500");
  expect(primary, "the ministry primary keeps a glow while disabled").toContain("disabled:shadow-none");

  /*
    ── 4. THE SECONDARY KEEPS ORDINARY INK, WHICH IS WHERE ΔE 0.004 WOULD HAVE BITTEN ──────────
    A secondary button is a pale ground with dark ink, the same SHAPE as an `amber-100`/`amber-800`
    status pill — and `ministry-700` ink and `amber-800` ink are the same colour to 0.004 in OKLab.
    Two inches apart on /annual-plan and /sanction-orders, one a thing to press and the other a fact
    about a workshop, they would be indistinguishable. Only the border and the hover wash take the
    ramp; the label stays in the app's ordinary ink.
  */
  expect(
    ruleFor(block, ".field-button-secondary"),
    "the ministry secondary takes ministry INK, which is amber-800 to ΔE 0.004 — the status-pill collision"
  ).not.toContain("text-ministry-");

  /*
    ── 5. WHAT THE OWNER DID NOT OVERRULE ──────────────────────────────────────────────────────
    The global `:focus-visible` outline stays purple: it was not part of the instruction, and it is
    the one mark that is identical on every screen in the product. `--purple-700` is therefore never
    re-pointed either, which also protects `.audio-range:focus-visible` and `.fr-flash-row`'s "this
    row, just now" outline — both app-wide meanings rather than ministry ones.

    `--bg-0` and `--card` are likewise untouched. `THEME_COLOR` in lib/preferences.ts drives
    <meta name="theme-color"> and `applyPreferences` rewrites it on a PREFERENCE change and never on
    a navigation, so an orange canvas would leave the mobile address bar lavender with no code path
    in the product that could ever correct it.
  */
  expect(block, "the ministry block re-points the global focus outline").not.toContain(":focus-visible");

  /*
    ⚠ THE FIRST TOKEN USED TO BE CHECKED WITH A TRAILING SPACE — `not.toContain("--purple-700 ")` —
    AND THAT ASSERTION COULD NEVER FIRE. A re-pointing declaration is written `--purple-700: oklch(…)`
    and a usage is `var(--purple-700)`; neither contains the token followed by a space, so the one
    guard on the one-line way to turn every focus ring in the product orange was inert from the day it
    was written. Measured on the real file: "--purple-700: " occurs, "--purple-700 " occurs nowhere.
    The other two already ended in a colon and worked; they are spelled the same way now so the loop
    reads as one rule.
  */
  for (const token of ["--purple-700:", "--bg-0:", "--card:"]) {
    expect(block, `the ministry block re-points ${token}`).not.toContain(token);
  }
});

/**
 * The declarations of one recipe's rule inside the block — everything between its selector pair and
 * the closing brace.
 *
 * Needed because the assertions above are about ONE rule rather than about the block: "the secondary
 * carries no ministry ink" is false of the block (the upload trigger legitimately does) and true of
 * the rule, and a substring test over the whole block cannot tell those apart.
 */
function ruleFor(block: string, recipe: string): string {
  const marker = `.${recipe.replace(/^\./, "")}[data-surface="ministry"] {`;
  const from = block.indexOf(marker);
  expect(from, `the ministry block carries no self-matching rule for ${recipe}`).toBeGreaterThan(-1);
  const to = block.indexOf("}", from);
  expect(to, `${recipe}'s rule in the ministry block is not closed`).toBeGreaterThan(from);
  return block.slice(from + marker.length, to);
}

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
    /*
      ⚠ ONE EXEMPTION, ADDED WITH THE ORANGE BUTTONS ON 2026-09-20, AND IT IS A RULE RATHER THAN A
      HOLE. The obligation above is owed by ministry INK on a ground that inverts underneath it, and
      by a PALE ministry ground painted onto a card that inverts underneath IT. A rule that sets
      `text-white` on a dark ministry ground owes neither, because it has fixed BOTH halves of its own
      contrast and neither half is themed: white on `ministry-700` #923e0d measures 7.20:1 and is the
      same 7.20:1 in either theme. That is the identical position `.field-button`'s own
      `bg-purple-700 text-white` has always been in, and the alternative — bolting a meaningless
      `dark:` token on to satisfy a string check — would weaken the assertion for the ink rules it
      genuinely protects, which is the failure this file's own header warns about twice.
    */
    if (rule.includes("text-white")) continue;
    expect(rule, `no dark: pair on "${rule}" — ministry ink on a dark card is 2.44:1`).toContain(
      "dark:"
    );
  }
});

test("the block matches both the descendant and the self case", () => {
  /*
    A component OUTSIDE the four routes opts in by putting `data-surface="ministry"` on its own root
    — the ministry desk card on /dashboard does exactly that, and its root IS a `.panel`. A
    descendant combinator alone would not match an element that carries the attribute itself, and
    the symptom is the worst kind: no error, no warning, a rule that simply never applies.

    ASKED OF EVERY RECIPE CLASS THE BLOCK CARRIES, RATHER THAN OF ONE NAMED EXAMPLE. This test used
    to pin `.panel` by name, and when the ministry spine was removed on 2026-09-17 it failed for a
    reason that had nothing to do with the duality it exists to defend: a rule had been DELETED, not
    written wrong. A test that goes red when a rule is legitimately retired teaches the next reader
    to edit the test, which is how the real assertion gets weakened on the way past. Derived from the
    block itself it cannot be outlived — it says nothing about WHICH classes the block chooses to
    repaint, and everything about the one way each of them silently fails.
  */
  const selectors = ministrySelectors();
  const recipes = new Set<string>();
  for (const line of selectors) {
    const descendant = line.match(/^\[data-surface="ministry"\]\s+\.([\w-]+)/);
    const self = line.match(/^\.([\w-]+)\[data-surface="ministry"\]/);
    if (descendant) recipes.add(descendant[1]);
    if (self) recipes.add(self[1]);
  }

  expect(recipes.size, "the ministry block targets no recipe class at all").toBeGreaterThan(0);

  for (const recipe of recipes) {
    expect(selectors, `.${recipe} is repainted with no descendant form`).toContain(
      `[data-surface="ministry"] .${recipe},`
    );
    expect(selectors, `.${recipe} is repainted with no self-match form`).toContain(
      `.${recipe}[data-surface="ministry"] {`
    );
  }
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

test("a dialog raised from a ministry page carries the surface with it", () => {
  /*
    THE ONE PLACE THE SCOPE CANNOT REACH BY ANCESTRY, AND IT MATTERS MORE SINCE THE BUTTONS TURNED.

    `FieldDialog` `createPortal`s to `document.body`, which is OUTSIDE <main> by construction, so no
    ancestor rule can reach it. The block's header records that as DELIBERATE for dropdown panels and
    toasts: those are app chrome that happens to have been raised from a ministry page.

    A DIALOG IS NOT THAT. /annual-plan opens "Upload the annual plan" and /sanction-orders opens its
    import review, and the confirming button INSIDE each is the second half of the act whose trigger
    the reader just pressed on the page. Before the accent reached action controls this was invisible;
    after it, an unstamped overlay ships an orange trigger on the page and a purple primary in the
    dialog it opens — one action in two accent colours, which is worse than either alone.

    Derived from the pathname rather than passed in, so every existing call site is correct without
    being edited and the next one cannot forget.
  */
  const dialog = readFileSync(join(ROOT, "components", "dialogs", "FieldDialog.tsx"), "utf8");
  expect(dialog).toContain('data-surface={ministrySurface(pathname) ? "ministry" : undefined}');
  expect(dialog, "the dialog reads the route it was raised from").toContain(
    'import { usePathname } from "next/navigation";'
  );
  /*
    NO `!blocked` GUARD IS OWED HERE, unlike AppShell's stamp, and the asymmetry is worth stating: a
    dialog can only be opened by a page that is already being served, so there is no refusal panel
    for the accent to be painted in front of.
  */
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
