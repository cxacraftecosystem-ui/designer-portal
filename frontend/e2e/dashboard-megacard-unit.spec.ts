import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8");

function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "");
}

const DASHBOARD = read("app/(protected)/dashboard/page.tsx");
const DASHBOARD_CODE = stripComments(DASHBOARD);
const MEGA = read("components/dashboard/MegaCard.tsx");
const MEGA_CODE = stripComments(MEGA);
const ENTRY = read("components/dashboard/EntryPointCard.tsx");
const ENTRY_CODE = stripComments(ENTRY);
const TILE = read("components/DashboardCard.tsx");
const HOOK = read("components/dashboard/useMegaCards.ts");
const HOOK_CODE = stripComments(HOOK);
const TAILWIND = read("tailwind.config.ts");

const GROUP_TONES = ["purple", "archive", "errand", "steward"] as const;
const ALL_TONES = [...GROUP_TONES, "mango"] as const;

test.describe("the group tones are real ramps", () => {
  for (const tone of ["archive", "errand", "steward", "mango"] as const) {
    test(`\`${tone}\` declares eleven rungs on one hue`, () => {
      const block = new RegExp(`const ${tone} = \\{([\\s\\S]*?)\\n\\};`).exec(TAILWIND);
      expect(block, `tailwind.config.ts declares no \`const ${tone}\` ramp`).not.toBeNull();
      const body = block![1];

      const rungs = [...body.matchAll(/^\s*(\d+):/gm)].map((match) => match[1]);
      expect(
        rungs,
        `\`${tone}\` must carry purple's own eleven rungs — a chip swap has to change hue and nothing else`
      ).toEqual(["50", "100", "200", "300", "400", "500", "600", "700", "800", "900", "950"]);

      const hues = new Set([...body.matchAll(/oklch\([\d.]+ [\d.]+ (\d+)/g)].map((match) => match[1]));
      expect(hues.size, `\`${tone}\` mixes ${[...hues].join(", ")} — one hue per ramp, like every other one here`).toBe(1);

      expect(body).toContain("/ <alpha-value>)");
      expect(body, "a themed ramp must not resolve through a custom property — it would invert").not.toContain("rgb(var(");
    });
  }

  test("the mango is the one the owner named, and it did not replace the ministry ramp", () => {
    expect(TAILWIND).toContain('500: "oklch(0.648 0.131 71 / <alpha-value>)"');

    expect(TAILWIND).toContain('700: "oklch(0.47 0.127 45 / <alpha-value>)"');
    expect(TAILWIND).toContain("cta-ministry");
  });

  test("no ramp is named after a stock Tailwind scale, because those DEEP-MERGE", () => {
    const declared = [...TAILWIND.matchAll(/^const (\w+) = \{$/gm)].map((match) => match[1]);
    const stock = [
      "slate", "gray", "zinc", "neutral", "stone", "red", "orange", "yellow", "lime",
      "green", "emerald", "teal", "cyan", "sky", "blue", "indigo", "violet", "fuchsia",
      "pink", "rose"
    ];
    expect(declared.filter((name) => stock.includes(name))).toEqual([]);
  });

  test("every ramp the config declares is actually wired into `colors`", () => {
    for (const tone of ["archive", "errand", "steward", "mango"] as const) {
      expect(TAILWIND, `\`${tone}\` is declared and never registered — every class silently no-ops`).toContain(
        `        ${tone},`
      );
    }
  });
});

test.describe("colour coding stays inside its boundary", () => {
  test("the tone maps are written out in full and never interpolated", () => {
    expect(MEGA_CODE).not.toMatch(/bg-\$\{/);
    expect(MEGA_CODE).not.toMatch(/text-\$\{/);
    expect(MEGA_CODE).not.toMatch(/border-\$\{/);
    for (const tone of ALL_TONES) {
      expect(MEGA_CODE, `\`${tone}\` has no entry in the chip map`).toContain(`${tone}:`);
    }
  });

  test("every pale tone chip carries its `dark:` pair", () => {
    for (const tone of ["archive", "errand", "steward", "mango"] as const) {
      const row = new RegExp(`${tone}: "([^"]+)"`).exec(MEGA_CODE);
      expect(row, `no chip entry for \`${tone}\``).not.toBeNull();
      const classes = row![1];
      expect(classes, `\`${tone}\` chip has no dark ground`).toContain(`dark:bg-${tone}-950`);
      expect(classes, `\`${tone}\` chip ink is unreadable in dark theme`).toContain(`dark:text-${tone}-300`);
    }
    expect(MEGA_CODE).toContain('purple: "bg-purple-800 text-white"');
  });

  test("no tone ever reaches a button, an input or a focus ring", () => {
    for (const tone of ALL_TONES) {
      if (tone === "purple") continue;
      expect(MEGA_CODE).not.toContain(`ring-${tone}`);
      expect(MEGA_CODE).not.toContain(`outline-${tone}`);
      expect(MEGA_CODE).not.toMatch(new RegExp(`field-button[^"]*${tone}`));
      expect(MEGA_CODE).not.toMatch(new RegExp(`className=\\{?"[^"]*\\bbg-${tone}-(50|100)\\b[^"]*panel`));
    }
  });

  test("the left-edge accent rule is not reinstated, because globals.css is a tombstone for it", () => {
    for (const tone of ALL_TONES) {
      expect(MEGA_CODE).not.toContain(`border-l-2 border-${tone}`);
      expect(MEGA_CODE).not.toContain(`border-l-4 border-${tone}`);
    }
  });

  test("the colour is never the only channel", () => {
    expect(MEGA_CODE).toContain("{title}");
    expect(MEGA_CODE).toContain("{note}");
    expect(MEGA_CODE).toContain("{count}");
    expect(MEGA_CODE).toContain("{countLabel}");
    expect(MEGA_CODE).toMatch(/<span aria-hidden className=\{`grid h-10 w-10/);
  });

  test("each dashboard group declares a distinct tone and its own icon", () => {
    const groups = /const TILE_GROUPS = \[([\s\S]*?)\n\];/.exec(DASHBOARD_CODE);
    expect(groups).not.toBeNull();
    const body = groups![1];

    const tones = [...body.matchAll(/tone: "(\w+)" as MegaTone/g)].map((match) => match[1]);
    expect(tones, "TILE_GROUPS must declare one tone per group, in render order").toEqual([...GROUP_TONES]);
    expect(new Set(tones).size, "two groups share a tone, which is the opposite of colour coding").toBe(tones.length);

    const icons = [...body.matchAll(/icon: (\w+)/g)].map((match) => match[1]);
    expect(icons.length, "every group needs an icon — it is the channel a reader navigates by shape with").toBe(4);
    expect(new Set(icons).size, "two groups share a glyph").toBe(4);
  });
});

test.describe("collapsed by default, and correctly announced", () => {
  test("the toggle is a real button carrying `aria-expanded`", () => {
    expect(MEGA_CODE).toContain('type="button"');
    expect(MEGA_CODE).toContain("aria-expanded={expanded}");
  });

  test("`aria-controls` is present only while the panel is mounted", () => {
    expect(MEGA_CODE).toContain("aria-controls={expanded ? panelId : undefined}");
  });

  test("the hook starts with everything shut and only then reads storage", () => {
    expect(HOOK_CODE).toMatch(/useState<ReadonlySet<string>>\(\(\) => new Set\(\)\)/);
    expect(HOOK_CODE).toContain("useEffect");
  });

  test("every storage touch is wrapped, because the accessor itself can throw", () => {
    const reads = [...HOOK_CODE.matchAll(/window\.localStorage/g)];
    expect(reads.length, "the hook stopped using localStorage; update this test deliberately").toBeGreaterThan(0);
    expect((HOOK_CODE.match(/try \{/g) ?? []).length).toBeGreaterThanOrEqual(2);
    expect((HOOK_CODE.match(/\} catch/g) ?? []).length).toBeGreaterThanOrEqual(2);
  });

  test("a failed write cannot clobber the reader's real choices", () => {
    expect(HOOK_CODE).toContain("hydrated");
    expect(HOOK_CODE).toMatch(/if \(hydrated\.current\) write\(/);
  });

  test("the card does not clip its own focus ring, and the panel clips itself", () => {
    const root = /<section\s+aria-labelledby=\{headingId\}[\s\S]*?className=\{`panel([^`]*)`\}/.exec(MEGA_CODE);
    expect(root, "the MegaCard root is no longer a `panel` section").not.toBeNull();
    expect(root![1], "the card clips its own toggle's focus ring").not.toContain("overflow-hidden");
    expect(MEGA_CODE, "the height-animated panel must clip itself").toContain('className="overflow-hidden rounded-b-lg"');
  });

  test("reduced motion is honoured on the JS path, which CSS cannot reach", () => {
    expect(MEGA_CODE).toContain("useAppReducedMotion()");
    expect(MEGA_CODE).toContain("reduce ? { duration: 0 }");
  });

  test("the mega card is a panel and not glass, because glass may not nest", () => {
    expect(MEGA_CODE).not.toContain("GlassSurface");
    expect(MEGA_CODE).not.toContain("backdrop-blur");
    expect(MEGA_CODE).toContain("panel");
  });
});

test.describe("two in a row on a large screen, one on a phone", () => {
  test("the rail is two columns from `lg` and one below it", () => {
    expect(DASHBOARD_CODE).toContain('className="relative grid items-start gap-6 lg:grid-cols-2"');
  });

  test("`items-start` is present, or a shut card stretches to its open neighbour", () => {
    expect(DASHBOARD_CODE).toMatch(/grid items-start gap-6 lg:grid-cols-2/);
    expect(MEGA_CODE).toMatch(/panel h-fit/);
  });

  test("the tiles inside a card are one per row on a phone and two once it is wide", () => {
    expect(DASHBOARD_CODE).toContain('className="grid grid-cols-1 gap-3 sm:grid-cols-2"');
    expect(DASHBOARD_CODE).not.toContain("grid grid-cols-2 gap-3 md:grid-cols-3");
  });

  test("an empty group still renders nothing at all", () => {
    expect(DASHBOARD_CODE).toContain("if (members.length + siblings === 0) return null;");
  });

  test("a shut card counts the sibling destination it hides", () => {
    expect(DASHBOARD_CODE).toContain('const siblings = group.id === "designers" ? 1 : 0;');
    expect(DASHBOARD_CODE).toContain("count={members.length + siblings}");
  });
});

test.describe("the tile register is untouched", () => {
  test("the default-ALLOW filter is still the first step, character for character", () => {
    expect(DASHBOARD_CODE).toContain("const visible = tiles.filter((tile) => tile.visible !== false);");
  });

  test("the array is still one flat literal that the Kotlin and Python parsers can find", () => {
    const declaration = (DASHBOARD.match(/const tiles: Tile\[\] = \[/g) ?? []).length;
    expect(
      declaration,
      "the parsers `indexOf` this declaration in the RAW file — a second copy, even in a comment, sends the scan off the end"
    ).toBe(1);
  });

  test("`My questionnaires` is still not a tile", () => {
    const array = /const tiles: Tile\[\] = \[([\s\S]*?)\n  \];/.exec(DASHBOARD);
    expect(array).not.toBeNull();
    expect(array![1]).not.toContain("My questionnaires");
    expect(array![1]).not.toContain("/questionnaires");
  });

  test("the annual-plan absence this file is also watched for is preserved", () => {
    expect(DASHBOARD).not.toContain("canManageAnnualPlan");
  });
});

test.describe("My questionnaires is a card the size of a tile", () => {
  test("it is a grid child rather than a sibling of the grid", () => {
    const grid = /<div className="grid grid-cols-1 gap-3 sm:grid-cols-2">([\s\S]*?)\n                  <\/div>/.exec(
      DASHBOARD_CODE
    );
    expect(grid, "the tile grid is no longer where this spec expects it").not.toBeNull();
    expect(grid![1]).toContain("<EntryPointCard");
  });

  test("its shell is `DashboardCard`'s, restated exactly", () => {
    const shell = /className="(flex flex-col gap-2 rounded-lg[^"]*)"/.exec(TILE);
    expect(shell, "DashboardCard's shell class moved; EntryPointCard must follow").not.toBeNull();
    expect(ENTRY_CODE).toContain(shell![1]);

    expect(ENTRY_CODE).toContain('className="grid h-10 w-10 place-items-center rounded-md bg-purple-800"');
    expect(ENTRY_CODE).toContain('className="font-display text-base font-bold leading-snug text-ink-900"');
    expect(ENTRY_CODE).toContain('className="mt-auto flex flex-col gap-1.5 pt-1"');
    expect(ENTRY_CODE).toContain('className="field-button h-9 min-h-0 px-3 text-xs"');
    expect(ENTRY_CODE, "the tiles are glass; an opaque ground beside them reads as a different kind of thing").toContain(
      "GlassSurface"
    );
  });

  test('"Open" takes the arrow and never the plus', () => {
    expect(ENTRY_CODE).toContain("<ArrowRight");
    expect(ENTRY_CODE).not.toContain("<Plus");
  });

  test("the sentence that tells a designer the two instruments differ is still printed", () => {
    expect(DASHBOARD_CODE).toContain(
      "Build your own interview form from the .xlsx pro-forma, and record answers against it — separate from the shared artisan questionnaire on Take interview."
    );
  });

  test("it still answers to the navigation's name for it", () => {
    expect(DASHBOARD_CODE).toContain('label="My questionnaires"');
  });
});
