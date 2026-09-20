import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE MEGA CARDS — four collapsible, colour-coded sections, and the five ways colour coding goes
 * wrong in a codebase whose first non-negotiable is "purple-700 is the only action colour".
 *
 * ── WHAT WAS ASKED FOR, VERBATIM, BECAUSE THE SHAPE FOLLOWS FROM IT ────────────────────────────
 *
 * 2026-09-20: *"Each of the megacard is supposed to be a larger card that would stay minimized
 * unless it is clicked upon, colour code so that it is easier for people to understand and navigate,
 * there should be two cards in a row for a megacard only on the larger screens, and 1 card on mobile
 * screens, implement this for both web and android, use the mango colour from this place for the
 * ministry one … My questionnaires, and the card size for this one currently is also different, fix
 * that."*
 *
 * ── WHY THIS IS A SOURCE-READING SPEC AND NOT A BROWSER ONE ────────────────────────────────────
 *
 * There is no React renderer in `devDependencies`; every unit spec in this directory reads source
 * text and asserts over it, and `dashboard-tile-parity-unit.spec.ts` is the neighbouring worked
 * example. The browser half of this change is covered by `feature-entry-points.spec.ts`, which now
 * opens a mega card before looking inside it and asserts the collapsed default on the way past.
 *
 * ⚠ EVERY ABSENCE BELOW IS CHECKED AGAINST COMMENT-STRIPPED SOURCE. These files argue their own
 * rules in prose, so they NAME the classes they forbid — `bg-mango-100`, `field-button`,
 * `overflow-hidden` all appear in block headers. A bare `toContain` over the raw file finds the
 * explanation and passes while the code does the opposite, which is the exact failure the
 * questionnaire specs hit three times in one afternoon before `stripComments` was written.
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8");

/**
 * Source with `//`, block comments and JSX `{/* … *␠/}` removed.
 *
 * Block comments are stripped FIRST: a `//` inside a block comment is not a line comment, and
 * stripping line comments first would leave a dangling `*␠/` that swallows the next real line.
 */
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

/** The four tones the dashboard spends, in `TILE_GROUPS` order. */
const GROUP_TONES = ["purple", "archive", "errand", "steward"] as const;
/** Every ramp `MegaCard` knows about. `mango` is the ministry mega card's, on other screens. */
const ALL_TONES = [...GROUP_TONES, "mango"] as const;

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 1. THE RAMPS EXIST, AND THEY ARE RAMPS RATHER THAN FOUR LOOSE SWATCHES
// ══════════════════════════════════════════════════════════════════════════════════════════════

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

      // Literal OKLCH with the alpha placeholder, exactly as `purple`, `gold` and `ministry` are
      // written, so every alpha utility (`bg-archive-950/40`) still works.
      expect(body).toContain("/ <alpha-value>)");
      expect(body, "a themed ramp must not resolve through a custom property — it would invert").not.toContain("rgb(var(");
    });
  }

  test("the mango is the one the owner named, and it did not replace the ministry ramp", () => {
    // hsl(39 80% 40%) — the reference site's DARK primary — is #B87E14 = oklch(0.637 0.129 75.1).
    // `mango-500` is the rung that lands on it.
    expect(TAILWIND).toContain('500: "oklch(0.648 0.131 71 / <alpha-value>)"');

    // And the eleven-rung hue-45 `ministry` ramp is untouched. Every `dark:` pair, the
    // `cta-ministry` shadow and three test files are written against it; mango's bright rungs
    // cannot carry white text (#FFA600 on white is 1.96:1), so it extends rather than replaces.
    expect(TAILWIND).toContain('700: "oklch(0.47 0.127 45 / <alpha-value>)"');
    expect(TAILWIND).toContain("cta-ministry");
  });

  test("no ramp is named after a stock Tailwind scale, because those DEEP-MERGE", () => {
    // `amber` in this very file is the standing proof: only 100/500/800 are brand, and `amber-50`
    // silently resolves to a stock value that does not pair with them.
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

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 2. THE TONE IS A NAVIGATION MARK AND NEVER AN ACTION COLOUR
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("colour coding stays inside its boundary", () => {
  test("the tone maps are written out in full and never interpolated", () => {
    /*
      Tailwind's scanner reads SOURCE TEXT. `bg-${tone}-100` produces no CSS at all, and the chip
      renders transparent with no error in the console, in the build, or in a typecheck. This is the
      single most common way a themed component ships broken, so the map is explicit — and this test
      is what stops the next reader "simplifying" it back.
    */
    expect(MEGA_CODE).not.toMatch(/bg-\$\{/);
    expect(MEGA_CODE).not.toMatch(/text-\$\{/);
    expect(MEGA_CODE).not.toMatch(/border-\$\{/);
    for (const tone of ALL_TONES) {
      expect(MEGA_CODE, `\`${tone}\` has no entry in the chip map`).toContain(`${tone}:`);
    }
  });

  test("every pale tone chip carries its `dark:` pair", () => {
    /*
      The ramps are literal OKLCH and do NOT invert. `text-archive-700` on a dark `bg-card` is far
      under the 4.5:1 floor; the `-300` rung on a `-950/40` wash is the paired value. Purple is the
      exception and is allowed to be: it is a FILLED 800 chip with white ink, which has already
      fixed both halves of its own contrast — the same exemption `globals.css` grants the one
      ministry rule that sets `text-white`.
    */
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
    /*
      Non-negotiable 1 is still in force. The tone buys an `aria-hidden` chip and a hover border —
      exactly what `MinistryDeskCard` spends the `ministry` ramp on — and nothing else. A tone on
      `.field-button` would be a second action colour on a data screen; a tone on a ring would be
      drawn immediately INSIDE the global purple `:focus-visible` outline and the control would wear
      two accent colours on one keyboard focus.
    */
    for (const tone of ALL_TONES) {
      if (tone === "purple") continue; // purple IS the action colour; the rule is about the others.
      expect(MEGA_CODE).not.toContain(`ring-${tone}`);
      expect(MEGA_CODE).not.toContain(`outline-${tone}`);
      expect(MEGA_CODE).not.toMatch(new RegExp(`field-button[^"]*${tone}`));
      // A ground on the card itself would read as a page canvas rather than as a mark.
      expect(MEGA_CODE).not.toMatch(new RegExp(`className=\\{?"[^"]*\\bbg-${tone}-(50|100)\\b[^"]*panel`));
    }
  });

  test("the left-edge accent rule is not reinstated, because globals.css is a tombstone for it", () => {
    // The "ministry spine" was removed at the owner's direction on 2026-09-17 and globals.css
    // forbids bringing it back by name. A group tone on a `border-l-2` is the same idea wearing a
    // different word.
    for (const tone of ALL_TONES) {
      expect(MEGA_CODE).not.toContain(`border-l-2 border-${tone}`);
      expect(MEGA_CODE).not.toContain(`border-l-4 border-${tone}`);
    }
  });

  test("the colour is never the only channel", () => {
    // Title, note and a count — three non-colour channels on a shut card. A reader who cannot
    // separate teal from indigo loses nothing at all.
    expect(MEGA_CODE).toContain("{title}");
    expect(MEGA_CODE).toContain("{note}");
    expect(MEGA_CODE).toContain("{count}");
    expect(MEGA_CODE).toContain("{countLabel}");
    // And the chip is decorative, so it is hidden rather than announced twice.
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

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 3. THE DISCLOSURE ITSELF
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("collapsed by default, and correctly announced", () => {
  test("the toggle is a real button carrying `aria-expanded`", () => {
    expect(MEGA_CODE).toContain('type="button"');
    expect(MEGA_CODE).toContain("aria-expanded={expanded}");
  });

  test("`aria-controls` is present only while the panel is mounted", () => {
    /*
      The panel is UNMOUNTED when collapsed (it is inside `AnimatePresence`), and an `aria-controls`
      naming an element that does not exist is worse than none: a screen reader announces a control
      that leads nowhere. `Accordion`, the app's other disclosure, drops the attribute entirely for
      the same reason; this keeps the relationship wired for the half of the time it is true.
    */
    expect(MEGA_CODE).toContain("aria-controls={expanded ? panelId : undefined}");
  });

  test("the hook starts with everything shut and only then reads storage", () => {
    // The owner's ruling is the FIRST render, and a `useState` initializer that read storage would
    // make the client's first render disagree with the server's.
    expect(HOOK_CODE).toMatch(/useState<ReadonlySet<string>>\(\(\) => new Set\(\)\)/);
    expect(HOOK_CODE).toContain("useEffect");
  });

  test("every storage touch is wrapped, because the accessor itself can throw", () => {
    /*
      Not merely "can be empty". In a private window, with site data blocked, or inside a
      thumbnailer, touching `window.localStorage` throws on ACCESS. An unguarded read there takes the
      whole dashboard down to remember which heading somebody opened.
    */
    const reads = [...HOOK_CODE.matchAll(/window\.localStorage/g)];
    expect(reads.length, "the hook stopped using localStorage; update this test deliberately").toBeGreaterThan(0);
    expect((HOOK_CODE.match(/try \{/g) ?? []).length).toBeGreaterThanOrEqual(2);
    expect((HOOK_CODE.match(/\} catch/g) ?? []).length).toBeGreaterThanOrEqual(2);
  });

  test("a failed write cannot clobber the reader's real choices", () => {
    // Without the `hydrated` guard the persisting effect writes the EMPTY first-render set over
    // whatever was stored, before the hydrating effect has run — the "my settings reset themselves"
    // bug, arriving through the code that exists to prevent it.
    expect(HOOK_CODE).toContain("hydrated");
    expect(HOOK_CODE).toMatch(/if \(hydrated\.current\) write\(/);
  });

  test("the card does not clip its own focus ring, and the panel clips itself", () => {
    /*
      The global ring is an `outline` at `outline-offset: 2px`, drawn OUTSIDE the border box. The
      toggle is full-bleed, so clipping the card erases the ring on three sides and the primary
      control of every mega card looks unfocused. Six components in this tree carry this same note.
    */
    const root = /<section\s+aria-labelledby=\{headingId\}[\s\S]*?className=\{`panel([^`]*)`\}/.exec(MEGA_CODE);
    expect(root, "the MegaCard root is no longer a `panel` section").not.toBeNull();
    expect(root![1], "the card clips its own toggle's focus ring").not.toContain("overflow-hidden");
    expect(MEGA_CODE, "the height-animated panel must clip itself").toContain('className="overflow-hidden rounded-b-lg"');
  });

  test("reduced motion is honoured on the JS path, which CSS cannot reach", () => {
    // The two global blocks in globals.css cannot touch framer-motion's inline styles, and this app
    // sets no `MotionConfig reducedMotion="user"`.
    expect(MEGA_CODE).toContain("useAppReducedMotion()");
    expect(MEGA_CODE).toContain("reduce ? { duration: 0 }");
  });

  test("the mega card is a panel and not glass, because glass may not nest", () => {
    // Every `DashboardCard` is a `GlassSurface`. An ancestor with its own backdrop-filter becomes
    // the backdrop root and the inner surface refracts a flat wash instead of the page.
    expect(MEGA_CODE).not.toContain("GlassSurface");
    expect(MEGA_CODE).not.toContain("backdrop-blur");
    expect(MEGA_CODE).toContain("panel");
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 4. THE GEOMETRY THE OWNER ASKED FOR
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("two in a row on a large screen, one on a phone", () => {
  test("the rail is two columns from `lg` and one below it", () => {
    expect(DASHBOARD_CODE).toContain('className="relative grid items-start gap-6 lg:grid-cols-2"');
  });

  test("`items-start` is present, or a shut card stretches to its open neighbour", () => {
    // `grid` stretches every cell by default. Without this a collapsed card renders as a tall empty
    // box beside an expanded one, which reads as a section that failed to load rather than a closed
    // one. `MegaCard`'s own `h-fit` is the other half; both are needed, because the card cannot see
    // the rail and the rail cannot see the card.
    expect(DASHBOARD_CODE).toMatch(/grid items-start gap-6 lg:grid-cols-2/);
    expect(MEGA_CODE).toMatch(/panel h-fit/);
  });

  test("the tiles inside a card are one per row on a phone and two once it is wide", () => {
    expect(DASHBOARD_CODE).toContain('className="grid grid-cols-1 gap-3 sm:grid-cols-2"');
    // The old full-width geometry packed three tiles into what is now half a page.
    expect(DASHBOARD_CODE).not.toContain("grid grid-cols-2 gap-3 md:grid-cols-3");
  });

  test("an empty group still renders nothing at all", () => {
    // A researcher has no Admin tiles and a volunteer has no Records tiles. A collapsed empty mega
    // card is strictly worse than the heading-over-nothing this rule already forbade.
    expect(DASHBOARD_CODE).toContain("if (members.length + siblings === 0) return null;");
  });

  test("a shut card counts the sibling destination it hides", () => {
    // The number under a shut card's title is a promise about what opening it reveals. "My
    // questionnaires" is in the designers card and is not a tile, so `members.length` alone would be
    // short by one.
    expect(DASHBOARD_CODE).toContain('const siblings = group.id === "designers" ? 1 : 0;');
    expect(DASHBOARD_CODE).toContain("count={members.length + siblings}");
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 5. THE GROUPING DID NOT DISTURB WHAT THE THREE PARSERS READ
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("the tile register is untouched", () => {
  test("the default-ALLOW filter is still the first step, character for character", () => {
    /*
      `dashboard-tile-parity-unit.spec.ts` asserts this literal including the parameter name, and it
      matters more than it looks: the filter is default-ALLOW, so a tile whose `visible` key was
      deleted is shown to every signed-in account. Grouping happens AFTER it, so a tile this account
      may not have cannot reappear inside a mega card.
    */
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
    // `DashboardTileParityTest.kt` asserts `WEB_ONLY == emptyList()` in both directions and the
    // handset has no `EntryMode` for this destination, so a tile here goes red on `main` rather than
    // on the change that added it.
    const array = /const tiles: Tile\[\] = \[([\s\S]*?)\n  \];/.exec(DASHBOARD);
    expect(array).not.toBeNull();
    expect(array![1]).not.toContain("My questionnaires");
    expect(array![1]).not.toContain("/questionnaires");
  });

  test("the annual-plan absence this file is also watched for is preserved", () => {
    // `backend/tests/test_annual_plan_web_surface.py` asserts this identifier does not appear in
    // this file AT ALL — it never strips comments, so writing it in a new comment fails exactly as a
    // call site would.
    expect(DASHBOARD).not.toContain("canManageAnnualPlan");
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 6. THE CARD SIZE THE OWNER ASKED TO HAVE FIXED
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("My questionnaires is a card the size of a tile", () => {
  test("it is a grid child rather than a sibling of the grid", () => {
    // It shipped as a full-width `<Link>` beside the grid: 100% of the section against a tile's
    // 50%, and seven other differences on top. The owner: "the card size for this one currently is
    // also different, fix that."
    const grid = /<div className="grid grid-cols-1 gap-3 sm:grid-cols-2">([\s\S]*?)\n                  <\/div>/.exec(
      DASHBOARD_CODE
    );
    expect(grid, "the tile grid is no longer where this spec expects it").not.toBeNull();
    expect(grid![1]).toContain("<EntryPointCard");
  });

  test("its shell is `DashboardCard`'s, restated exactly", () => {
    /*
      Not "similar to". The shell string is compared against the real one, so a change to the tile's
      padding, radius, ground or shadow that is not mirrored here fails — which is the cheap half of
      the trade this component's header describes (the alternative was a new optional prop in front
      of the three parsers that read `DashboardCard.tsx`).
    */
    const shell = /className="(flex flex-col gap-2 rounded-lg[^"]*)"/.exec(TILE);
    expect(shell, "DashboardCard's shell class moved; EntryPointCard must follow").not.toBeNull();
    expect(ENTRY_CODE).toContain(shell![1]);

    // The chip, the type scale and the button rail too — the eight differences, closed.
    expect(ENTRY_CODE).toContain('className="grid h-10 w-10 place-items-center rounded-md bg-purple-800"');
    expect(ENTRY_CODE).toContain('className="font-display text-base font-bold leading-snug text-ink-900"');
    expect(ENTRY_CODE).toContain('className="mt-auto flex flex-col gap-1.5 pt-1"');
    expect(ENTRY_CODE).toContain('className="field-button h-9 min-h-0 px-3 text-xs"');
    expect(ENTRY_CODE, "the tiles are glass; an opaque ground beside them reads as a different kind of thing").toContain(
      "GlassSurface"
    );
  });

  test('"Open" takes the arrow and never the plus', () => {
    // Android draws the same distinction (`primaryIcon`), and a plus on a button that only
    // navigates is a lie. This card only ever navigates.
    expect(ENTRY_CODE).toContain("<ArrowRight");
    expect(ENTRY_CODE).not.toContain("<Plus");
  });

  test("the sentence that tells a designer the two instruments differ is still printed", () => {
    /*
      Load-bearing copy rather than decoration: it is the only place in the product that says the
      designer's own .xlsx form and the shared artisan questionnaire are different things.
      `questionnaires/page.tsx` and `guide/steps.ts` both depend on that distinction being stated.
    */
    expect(DASHBOARD_CODE).toContain(
      "Build your own interview form from the .xlsx pro-forma, and record answers against it — separate from the shared artisan questionnaire on Take interview."
    );
  });

  test("it still answers to the navigation's name for it", () => {
    expect(DASHBOARD_CODE).toContain('label="My questionnaires"');
  });
});
