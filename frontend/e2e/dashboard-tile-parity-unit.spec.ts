/**
 * THE DASHBOARD TILE FOR A ROLE-GATED DESTINATION, AND THE PER-ROLE AGREEMENT BETWEEN THAT TILE AND
 * THE MENU ROW BESIDE IT.
 *
 * ── THE DEFECT THIS FILE EXISTS FOR ─────────────────────────────────────────────────────────────
 *
 * `/sketches-and-prototypes` and `/design-review` shipped complete: a page each, a `NAV_ITEMS`
 * entry each, a `ROUTE_GUARDS` row each, and a twin row each in `docs/PERMISSIONS.md` §5. Every
 * register a reviewer would think to check had them. The owner still reported the feature as "still
 * not there", because the one register nobody enumerates is the dashboard `tiles` array — and the
 * dashboard is the screen this app opens on and the whole of what the Android dashboard is. A
 * destination that exists only in the nav sheet is a destination you have to already know about: the
 * sheet is behind a tap and lists every qualifying route in one column, so it answers "where is the
 * thing I am looking for" and never "what is this product for".
 *
 * ── WHY THIS IS A SOURCE-READING UNIT SPEC AND NOT A ROW IN `feature-entry-points.spec.ts` ───────
 *
 * That spec is the obvious place and it is the wrong one, five times over. It signs in with
 * `E2E_EMAIL`/`E2E_PASSWORD` (`e2e/README.md` documents an ADMIN) and its `DESTINATIONS` list is
 * prefaced "Both are open to any signed-in user, so both must appear for every account that can sign
 * in" — a precondition a gated row silently deletes while leaving the sentence in place. Concretely:
 *
 *   1. It would go GREEN on a refusal. Both of these pages render `PageHeader` ABOVE their lock
 *      panel, so `getByRole("heading", { name: /Design review/i })` matches the "Designer access
 *      required" screen. The spec would report a working entry point while the account was refused.
 *   2. It would go RED naming the wrong defect. For an account outside the predicate the tile is
 *      not rendered at all, so the failure reads "the tile is missing from the dashboard" — exactly
 *      the regression the file exists to catch — when the truth is "this account is not entitled".
 *   3. Its colour would depend on the operator's environment rather than on the code: green as an
 *      admin, red as a professor, same commit.
 *   4. Green would prove nothing about the RULE. An ADMIN passes `canRunDesignWorkshops` and
 *      `canCreateRecords` both, so the spec would stay green with the tile gated on the wrong
 *      predicate — which is the mistake `DesignWorkshopCardTest` on the Android side was written to
 *      catch, and the mistake the "Design workshop" tile in this very array has already shipped.
 *   5. Anything `adminSurface`-shaped would fail even for the documented account, because an admin's
 *      admin view defaults to OFF and the spec never touches the toggle.
 *
 * So `feature-entry-points.spec.ts` keeps exactly the two ungated destinations it has, and the
 * role-gated pair is pinned here instead — no browser, no server, no credentials, every role
 * covered. This is the web-side twin of Android's `DesignWorkshopCardTest`, whose assertion is "the
 * card and the menu row read the same predicate for every role" and which had no counterpart on this
 * surface. That absence is precisely why the gap could ship.
 *
 * ── WHY THE TILE ARRAY IS READ AS TEXT ──────────────────────────────────────────────────────────
 *
 * There is no React renderer in this repository's devDependencies (`discarded-work-unit.spec.ts`
 * and `web-surface-gaps-unit.spec.ts` both say so and both read source for the same reason), so the
 * component cannot be mounted and `tiles` is a local inside it either way. Reading the array's own
 * source is also the stronger check here: what must not happen is somebody EDITING this array, and a
 * string equality on the predicate expression catches `visible: creator` — which renders a tile that
 * leads only to a refusal — where a behavioural test through an admin fixture would not.
 *
 * Comments are stripped before anything is parsed. The array carries a sixty-line comment naming
 * both of these paths in prose, and an assertion that a path is "in the tiles array" must not be
 * satisfiable by a sentence about it.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { NAV_ITEMS } from "@/components/DynamicIslandNav";
import { canAccessRoute, canRunDesignWorkshops, routeGuardFor } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

const ROOT = join(__dirname, "..");
const DASHBOARD = "app/(protected)/dashboard/page.tsx";
const CARD = "components/DashboardCard.tsx";

const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

/** The role is the whole fixture: every predicate this file exercises reads nothing else. */
const user = (role: UserRole): User => ({ id: "u1", email: "a@b.c", name: "A", role } as User);

const ROLES: UserRole[] = [
  "MASTER_ADMIN",
  "ADMIN",
  "PROFESSOR",
  "INSPECTOR",
  "DESIGNER",
  "RESEARCHER",
  "FIELD_CONTRIBUTOR",
  "CROWDSOURCE_VOLUNTEER"
];

/**
 * WHO THE FEATURE IS FOR, WRITTEN OUT ROLE BY ROLE RATHER THAN DERIVED FROM THE PREDICATE.
 *
 * Deriving it would make the table a tautology: `canRunDesignWorkshops` would be asserted equal to
 * itself and a widening of the set would sail through. This literal is the independent statement, and
 * PROFESSOR is the row that carries it — a professor outranks a designer everywhere else in this
 * app and is outside this set, because the set is a SET and not a rank threshold.
 */
const OFFERED: Record<UserRole, boolean> = {
  MASTER_ADMIN: true,
  ADMIN: true,
  DESIGNER: true,
  // FALSE, and it is the same answer PROFESSOR gets one line down for the same reason: the tile
  // leads to running a workshop, and `canRunDesignWorkshops` is a SET that INSPECTOR is not in.
  // Outranking a designer buys review authority over their records, not the ability to run one.
  INSPECTOR: false,
  PROFESSOR: false,
  RESEARCHER: false,
  FIELD_CONTRIBUTOR: false,
  CROWDSOURCE_VOLUNTEER: false
};

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * Reading the `tiles` array
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

type ParsedTile = {
  index: number;
  /** Raw source of each property, comments removed — `"Design review"`, `Star`, `creator`. */
  props: Record<string, string>;
  label: string;
  newHref: string;
  newLabel: string | null;
  /** The `visible:` EXPRESSION as written, or null when the tile carries no `visible` key at all. */
  visible: string | null;
};

const quoted = (raw: string | undefined): string | null =>
  raw && raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') ? raw.slice(1, -1) : null;

/**
 * Scan the tile array literal from its opening bracket to the bracket that closes it, dropping
 * comments and keeping string contents. A hand-rolled scanner rather than a regex because the array
 * nests objects and holds a ternary, and because the comment stripping has to respect quotes: the
 * one thing this file must never do is let a path mentioned in prose count as a tile.
 */
function tileArrayBody(source: string): string {
  const DECLARATION = "const tiles: Tile[] = [";
  const declaration = source.indexOf(DECLARATION);
  expect(declaration, "the dashboard no longer declares `const tiles: Tile[] = [`").toBeGreaterThan(-1);

  // Past the WHOLE declaration, not to the next `[` — which is the one in `Tile[]`, whose closing
  // bracket is one character later and made every assertion in this file report an empty array.
  let i = declaration + DECLARATION.length;
  let depth = 1;
  let out = "";
  let stringChar: string | null = null;

  while (i < source.length) {
    const c = source[i];
    const next = source[i + 1];

    if (stringChar) {
      out += c;
      if (c === "\\") {
        out += next ?? "";
        i += 2;
        continue;
      }
      if (c === stringChar) stringChar = null;
      i += 1;
      continue;
    }
    if (c === '"' || c === "'" || c === "`") {
      stringChar = c;
      out += c;
      i += 1;
      continue;
    }
    if (c === "/" && next === "/") {
      while (i < source.length && source[i] !== "\n") i += 1;
      continue;
    }
    if (c === "/" && next === "*") {
      i += 2;
      while (i < source.length && !(source[i] === "*" && source[i + 1] === "/")) i += 1;
      i += 2;
      continue;
    }
    if (c === "[" || c === "{" || c === "(") depth += 1;
    if (c === "]" || c === "}" || c === ")") {
      depth -= 1;
      if (depth === 0) return out;
    }
    out += c;
    i += 1;
  }
  throw new Error("the `tiles` array literal is not closed");
}

/** Split a comment-free fragment on the separators that sit at nesting depth zero. */
function splitTop(body: string, separator: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let stringChar: string | null = null;
  let current = "";

  for (let i = 0; i < body.length; i += 1) {
    const c = body[i];
    if (stringChar) {
      current += c;
      if (c === "\\") {
        current += body[i + 1] ?? "";
        i += 1;
      } else if (c === stringChar) {
        stringChar = null;
      }
      continue;
    }
    if (c === '"' || c === "'" || c === "`") {
      stringChar = c;
      current += c;
      continue;
    }
    if (c === "{" || c === "[" || c === "(") depth += 1;
    if (c === "}" || c === "]" || c === ")") depth -= 1;
    if (c === separator && depth === 0) {
      parts.push(current);
      current = "";
      continue;
    }
    current += c;
  }
  parts.push(current);
  return parts.map((part) => part.trim()).filter((part) => part.length > 0);
}

function parseTiles(source: string): ParsedTile[] {
  return splitTop(tileArrayBody(source), ",")
    .map((entry, index) => {
      const inner = entry.replace(/^\{/, "").replace(/\}$/, "");
      const props: Record<string, string> = {};
      for (const field of splitTop(inner, ",")) {
        const colon = field.indexOf(":");
        if (colon < 0) continue;
        props[field.slice(0, colon).trim()] = field.slice(colon + 1).trim();
      }
      return {
        index,
        props,
        label: quoted(props.label) ?? "",
        newHref: quoted(props.newHref) ?? props.newHref ?? "",
        newLabel: quoted(props.newLabel),
        visible: props.visible ?? null
      };
    })
    .filter((tile) => tile.label.length > 0);
}

const TILES = parseTiles(read(DASHBOARD));

/** Where a tile leads, as a pathname — the "Design workshop" tile carries `?new=1` on its href. */
const tilePath = (tile: ParsedTile) => tile.newHref.split("?")[0];

/**
 * The two halves of one feature: a designer uploads a sketch or a prototype on the first and the
 * wider pool ranks it on the second. Both are top-level routes precisely because neither has a
 * workshop id in hand — each page's first question is which workshop — so neither is covered by the
 * `/design-workshops` prefix and neither can be reached from inside a workshop it does not know yet.
 */
const PINNED = ["/sketches-and-prototypes", "/design-review"] as const;

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The tile exists at all
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the dashboard offers both halves of the sketches feature", () => {
  test("the parser found the array it was aimed at", () => {
    // Without this the whole file is vacuously green the day the array is renamed, extracted into a
    // hook, or moved to a module — every `find` below would return undefined and every assertion
    // would be reported as "the tile is missing" when the tile is fine and the parser is not.
    expect(TILES.length).toBeGreaterThan(15);
    expect(TILES.map((tile) => tile.label)).toContain("Miscellaneous Media");
    // And the comments really were stripped: the array's prose names both pinned paths, and the
    // scanner has to have removed every sentence containing them.
    expect(tileArrayBody(read(DASHBOARD))).not.toContain("nav sheet");
  });

  for (const href of PINNED) {
    test(`a tile leads to ${href}`, () => {
      // THE ASSERTION THE OWNER'S REPORT IS ABOUT. Deleting the tile — or losing it to an unrelated
      // edit of this array, which is how `Map` and `Consolidated questionnaire` went missing before
      // it — turns this red and names the path that vanished.
      const tile = TILES.find((candidate) => candidate.newHref === href);
      expect(tile, `no dashboard tile leads to ${href}; the page is reachable only from the nav sheet`).toBeTruthy();
      // Exactly the guard table's path, with no query of its own. `?new=1` would be the create
      // convention borrowed from `/design-workshops`, and neither of these pages has anything to
      // create: both open a chooser. A query would also read as though it were part of the guarded
      // string, which it is not — `routeGuardFor` sees a pathname.
      expect(tile!.newHref).toBe(href);
      expect(tile!.props.updateHref, `${href} has one destination, so a second "Update" button would be a lie`).toBeUndefined();
    });
  }

  test("the tiles lead the grid, directly behind the workshop they belong to", () => {
    /*
      PINNED BY POSITION, and deliberately tighter than "somewhere in the grid".

      The defect being fixed is discoverability, and a tile nineteenth in a grid of twenty is not
      discoverable — it is below the fold on a phone, under six kinds of reference data a designer
      did not open the app for. Order is also the one property of this array that no type, lint or
      build has an opinion about, and a reorder is invisible in review.

      The three that lead are the design-workshop block: the workshop itself, then the two faces of
      it reached without a workshop in hand. `Artisan` is asserted as the fourth so that the block
      cannot be padded from below without a reader deciding, here, that the new tile belongs inside
      it. If a future tile genuinely does, this is the line to change, with the reason written down.
    */
    expect(TILES.slice(0, 4).map((tile) => tile.label)).toEqual([
      "Design workshop",
      "Sketches & prototypes",
      "Design review",
      "Artisan"
    ]);
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The tile and the menu row say the same thing
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("each tile agrees with its nav entry", () => {
  for (const href of PINNED) {
    test(`the tile and the nav row for ${href} carry one label and one predicate`, () => {
      const tile = TILES.find((candidate) => candidate.newHref === href)!;
      const entry = NAV_ITEMS.find((item) => item.href === href);
      expect(entry, `the nav entry for ${href} is gone; this spec pins the tile TO it`).toBeTruthy();

      // ONE SPELLING, CHARACTER FOR CHARACTER. This destination already answers to three strings in
      // the tree — the nav label, the page title "Sketches and Prototypes", and the guard panel's
      // "Designer access required" — and a fourth invented on the tile would be found by nobody's
      // grep and would read as a different feature to the user who saw both.
      expect(tile.label).toBe(entry!.label);

      // The predicate as WRITTEN, not merely as behaved. `visible: creator` would keep every
      // assertion in the role table below honest for an admin fixture and still put a tile in front
      // of a researcher whose only destination is "Designer access required" — the mistake the
      // "Design workshop" tile in this same array has already shipped once.
      expect(tile.visible, `the ${href} tile has no \`visible\` predicate at all`).toBe("canRunDesignWorkshops(user)");
      // ...and the filter that consumes it is default-ALLOW, which is why the line above asserts
      // presence and not just spelling: `tile.visible !== false` shows a tile whose key was deleted
      // to every signed-in account.
      expect(read(DASHBOARD)).toContain(".filter((tile) => tile.visible !== false)");
      expect(entry!.can).toBe(canRunDesignWorkshops);

      // NOT admin chrome. Wrapping either of these in `adminSurface` would hide, from an admin who
      // has admin view switched OFF, a page that admin may still open — a link removed from a live
      // route, which is the defect the `/review` nav entry records having already caused.
      expect(tile.visible).not.toContain("adminSurface");
      expect(entry!.adminSurface).toBeFalsy();
    });

    test(`for every role, the ${href} tile, the menu row and the URL agree`, () => {
      /*
        THE TABLE. Three independent decisions about one destination — whether the dashboard DRAWS
        it, whether the menu DRAWS it, and whether the URL OPENS — asserted equal for all seven
        roles and equal to a literal written by hand at the top of this file.

        The third column is not redundant with the other two. A hidden entry point is a decision
        about drawing and a guard is a decision about reaching; this family has shipped the first
        without the second three times, and both of these paths are the second and third of those
        three. Asserting all three together is what makes a tile added behind a predicate with no
        `ROUTE_GUARDS` row fail HERE rather than in production.
      */
      const tile = TILES.find((candidate) => candidate.newHref === href)!;
      const entry = NAV_ITEMS.find((item) => item.href === href)!;
      expect(tile.visible).toBe("canRunDesignWorkshops(user)");

      const table = ROLES.map((role) => {
        const account = user(role);
        return {
          role,
          // Resolved from the expression the tile actually carries, asserted verbatim above.
          tile: canRunDesignWorkshops(account),
          nav: entry.can(account),
          url: canAccessRoute(account, href)
        };
      });

      expect(table).toEqual(
        ROLES.map((role) => ({ role, tile: OFFERED[role], nav: OFFERED[role], url: OFFERED[role] }))
      );

      // A signed-out reader is nobody's role, and `canAccessRoute` is the only one of the three that
      // is ever asked about them — the dashboard and the sheet are behind the shell.
      expect(canAccessRoute(null, href)).toBe(false);
      // And the refusal is this path's OWN row, not a prefix that happens to reach it: a row deleted
      // in the belief that something broader covers the URL fails here.
      expect(routeGuardFor(href)?.path).toBe(href);
    });
  }

  test('"Open" still means an arrow, in the component that reads the word', () => {
    // The tiles say "Open" because arriving at either page creates nothing. That word is not
    // decoration: `DashboardCard` chooses `ArrowRight` over `Plus` off this exact string, so a
    // rename of the branch turns two honest buttons into two lies about what they do. And a plus
    // would be wrong twice over — bringing a workshop into existence is `canCreateDesignWorkshops`,
    // a strict subset that refuses a DESIGNER, i.e. most of the accounts these tiles are for.
    for (const href of PINNED) {
      expect(TILES.find((tile) => tile.newHref === href)!.newLabel).toBe("Open");
    }
    expect(read(CARD)).toContain('newLabel === "Open" || newLabel === "Manage" ? (');
    expect(read(CARD)).toContain("<ArrowRight");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The general rule, over the family this keeps breaking in
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the design-workshop family is enumerated in both registers", () => {
  /**
   * Every menu destination behind `canRunDesignWorkshops`, and whether it has a tile.
   *
   * A CLOSED LIST, so that a sixth member cannot arrive without somebody editing this file. That is
   * the whole mechanism: the two pages this spec pins were each added to the menu and the guard table
   * and to nothing else, and no build, type check, lint or docs check had an opinion. Growing the
   * family now forces the author to state, here, which register the new page belongs in.
   *
   * `/questionnaires` is the one member with no tile, and that is a decision rather than the same
   * oversight: "My questionnaires" is the authoring tool for a designer's own .xlsx-derived
   * instrument, which is why the menu files it under Record with the four record types rather than
   * beside the workshop. If the owner wants it on the grid, move it across and change this list.
   * What the list forbids is a new member arriving with neither.
   */
  const FAMILY: Record<string, boolean> = {
    "/design-workshops": true,
    "/sketches-and-prototypes": true,
    "/design-review": true,
    "/designers/profile": true,
    "/questionnaires": false
  };

  test("the family is exactly the five destinations this file knows about", () => {
    const hrefs = NAV_ITEMS.filter((item) => item.can === canRunDesignWorkshops).map((item) => item.href);
    expect([...hrefs].sort()).toEqual(Object.keys(FAMILY).sort());
  });

  test("each family member that should have a tile has one, and the tile is gated the same way", () => {
    // Matched on the PATHNAME because the "Design workshop" tile leads to `/design-workshops?new=1`
    // — the create convention for a list page, which neither of the two pinned above shares.
    const paths = new Set(TILES.map(tilePath));
    const wrong: string[] = [];

    for (const [href, expected] of Object.entries(FAMILY)) {
      const present = paths.has(href);
      if (present !== expected) {
        wrong.push(
          present
            ? `${href} has a dashboard tile and this file says it should not — decide, and say why here`
            : `${href} has no dashboard tile, so it can only be found by somebody who already knows it exists`
        );
        continue;
      }
      if (!present) continue;
      for (const tile of TILES.filter((candidate) => tilePath(candidate) === href)) {
        // The tile and the menu row must never disagree about what an account may do. String
        // equality on the expression, for the reason stated above: a behaviourally-equivalent
        // predicate today is a divergence the first time either one moves.
        if (tile.visible !== "canRunDesignWorkshops(user)") {
          wrong.push(`the ${href} tile is gated on \`${tile.visible}\`, not on the nav entry's predicate`);
        }
      }
    }

    expect(wrong).toEqual([]);
  });
});
