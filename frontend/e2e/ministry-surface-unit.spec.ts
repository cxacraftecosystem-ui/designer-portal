import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ROUTE_GUARDS, canRunDesignWorkshops, ministrySurface } from "@/lib/permissions";

const ROOT = join(__dirname, "..");
const GLOBALS = join(ROOT, "app", "globals.css");
const APP_SHELL = join(ROOT, "components", "AppShell.tsx");
const PAGE_HEADER = join(ROOT, "components", "PageHeader.tsx");
const TAILWIND = join(ROOT, "tailwind.config.ts");

const MINISTRY_PATHS = [
  "/annual-plan",
  "/design-workshop-approvals",
  "/ministry-dashboard",
  "/ministry-exports",
  "/officers",
  "/officers/monitored",
  "/sanction-orders"
];

function ministryCss(): string {
  const bare = readFileSync(GLOBALS, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
  const from = bare.indexOf('[data-surface="ministry"]');
  expect(from, "the ministry block has left globals.css").toBeGreaterThan(-1);
  return bare.slice(from);
}

function ministrySelectors(): string[] {
  return ministryCss()
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.includes('[data-surface="ministry"]'));
}

test("ministrySurface agrees with the ministry rows, and with nothing else", () => {
  const flagged = ROUTE_GUARDS.filter((guard) => guard.ministry).map((guard) => guard.path);
  expect([...flagged].sort()).toEqual([...MINISTRY_PATHS].sort());

  for (const guard of ROUTE_GUARDS) {
    expect(
      ministrySurface(guard.path),
      `${guard.path}: the flag and ministrySurface() disagree`
    ).toBe(guard.ministry === true);
  }
});

test("the surface is inherited by a route's subtree and does not leak sideways", () => {
  expect(ministrySurface("/officers/monitored/ckq12345")).toBe(true);
  expect(ministrySurface("/annual-plan")).toBe(true);

  expect(ministrySurface("/officers")).toBe(true);

  expect(ministrySurface("/officers-elsewhere")).toBe(false);
  expect(ministrySurface("/annual-planning")).toBe(false);

  expect(ministrySurface("/dashboard")).toBe(false);
  expect(ministrySurface("/guide")).toBe(false);
  expect(ministrySurface("/")).toBe(false);
});

test("no ministry row is gated on canRunDesignWorkshops", () => {
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

test("the guard table still has twenty-six rows", () => {
  expect(ROUTE_GUARDS.length).toBe(27);
});

test("the scoped block never reaches a utility class", () => {
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
  const block = ministryCss();

  for (const control of [".field-button", ".field-button-secondary", ".field-input", ".file-trigger"]) {
    expect(block, `the ministry block no longer repaints ${control} — the owner asked for it`).toContain(
      control
    );
  }

  expect(block, "the ministry primary does not spend the hue-45 glow").toContain("shadow-cta-ministry");
  expect(
    block.replace(/shadow-cta-ministry/g, ""),
    "the ministry block still spends `shadow-cta`, which is a literal purple glow at hue 305"
  ).not.toContain("shadow-cta");

  const primary = ruleFor(block, ".field-button");
  expect(primary, "the ministry primary sets no hover ground, so hover is the resting colour").toContain(
    "hover:bg-ministry-"
  );
  expect(primary, "the ministry primary sets no disabled ground — a disabled button stays orange").toContain(
    "disabled:bg-line-200"
  );
  expect(primary, "the ministry primary drops the disabled ink").toContain("disabled:text-ink-500");
  expect(primary, "the ministry primary keeps a glow while disabled").toContain("disabled:shadow-none");

  expect(
    ruleFor(block, ".field-button-secondary"),
    "the ministry secondary takes ministry INK, which is amber-800 to ΔE 0.004 — the status-pill collision"
  ).not.toContain("text-ministry-");

  expect(block, "the ministry block re-points the global focus outline").not.toContain(":focus-visible");

  for (const token of ["--purple-700:", "--bg-0:", "--card:"]) {
    expect(block, `the ministry block re-points ${token}`).not.toContain(token);
  }
});

function ruleFor(block: string, recipe: string): string {
  const marker = `.${recipe.replace(/^\./, "")}[data-surface="ministry"] {`;
  const from = block.indexOf(marker);
  expect(from, `the ministry block carries no self-matching rule for ${recipe}`).toBeGreaterThan(-1);
  const to = block.indexOf("}", from);
  expect(to, `${recipe}'s rule in the ministry block is not closed`).toBeGreaterThan(from);
  return block.slice(from + marker.length, to);
}

test("every ministry rule carries its dark pair", () => {
  const rules = ministryCss()
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.startsWith("@apply"));
  expect(rules.length, "the ministry block declares no rules at all").toBeGreaterThan(0);
  for (const rule of rules) {
    if (rule.includes("text-white")) continue;
    expect(rule, `no dark: pair on "${rule}" — ministry ink on a dark card is 2.44:1`).toContain(
      "dark:"
    );
  }
});

test("the block matches both the descendant and the self case", () => {
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
  expect(shell).toContain("const ministry = !blocked && ministrySurface(pathname);");
});

test("a dialog raised from a ministry page carries the surface with it", () => {
  const dialog = readFileSync(join(ROOT, "components", "dialogs", "FieldDialog.tsx"), "utf8");
  expect(dialog).toContain('data-surface={ministrySurface(pathname) ? "ministry" : undefined}');
  expect(dialog, "the dialog reads the route it was raised from").toContain(
    'import { usePathname } from "next/navigation";'
  );
});

test("PageHeader carries the hook the block hangs off", () => {
  expect(readFileSync(PAGE_HEADER, "utf8")).toContain('className="field-header-chip mt-1 grid');
  expect(ministryCss()).toContain(".field-header-chip");
});

test("the ministry ramp is eleven literal rungs at one hue", () => {
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
