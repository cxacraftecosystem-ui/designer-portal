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

const user = (role: UserRole): User => ({ id: "u1", email: "a@b.c", name: "A", role } as User);

const ROLES: UserRole[] = [
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

const OFFERED: Record<UserRole, boolean> = {
  MASTER_ADMIN: true,
  ADMIN: true,
  DESIGNER: true,
  INSPECTOR: false,
  MINISTRY_ADMIN: true,
  REGIONAL_DIRECTOR: true,
  ASSISTANT_DIRECTOR: true,
  PROFESSOR: false,
  RESEARCHER: false,
  FIELD_CONTRIBUTOR: false,
  CROWDSOURCE_VOLUNTEER: false
};

type ParsedTile = {
  index: number;
  props: Record<string, string>;
  label: string;
  newHref: string;
  newLabel: string | null;
  visible: string | null;
};

const quoted = (raw: string | undefined): string | null =>
  raw && raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') ? raw.slice(1, -1) : null;

function tileArrayBody(source: string): string {
  const DECLARATION = "const tiles: Tile[] = [";
  const declaration = source.indexOf(DECLARATION);
  expect(declaration, "the dashboard no longer declares `const tiles: Tile[] = [`").toBeGreaterThan(-1);

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

const tilePath = (tile: ParsedTile) => tile.newHref.split("?")[0];

const PINNED = ["/sketches-and-prototypes", "/design-review"] as const;

test.describe("the dashboard offers both halves of the sketches feature", () => {
  test("the parser found the array it was aimed at", () => {
    expect(TILES.length).toBeGreaterThan(15);
    expect(TILES.map((tile) => tile.label)).toContain("Miscellaneous Media");
    expect(tileArrayBody(read(DASHBOARD))).not.toContain("nav sheet");
  });

  for (const href of PINNED) {
    test(`a tile leads to ${href}`, () => {
      const tile = TILES.find((candidate) => candidate.newHref === href);
      expect(tile, `no dashboard tile leads to ${href}; the page is reachable only from the nav sheet`).toBeTruthy();
      expect(tile!.newHref).toBe(href);
      expect(tile!.props.updateHref, `${href} has one destination, so a second "Update" button would be a lie`).toBeUndefined();
    });
  }

  test("the tiles lead the grid, directly behind the workshop they belong to", () => {
    expect(TILES.slice(0, 4).map((tile) => tile.label)).toEqual([
      "Design workshop",
      "Sketches & prototypes",
      "Design review",
      "Artisan"
    ]);
  });
});

test.describe("each tile agrees with its nav entry", () => {
  for (const href of PINNED) {
    test(`the tile and the nav row for ${href} carry one label and one predicate`, () => {
      const tile = TILES.find((candidate) => candidate.newHref === href)!;
      const entry = NAV_ITEMS.find((item) => item.href === href);
      expect(entry, `the nav entry for ${href} is gone; this spec pins the tile TO it`).toBeTruthy();

      expect(tile.label).toBe(entry!.label);

      expect(tile.visible, `the ${href} tile has no \`visible\` predicate at all`).toBe("canRunDesignWorkshops(user)");
      expect(read(DASHBOARD)).toContain(".filter((tile) => tile.visible !== false)");
      expect(entry!.can).toBe(canRunDesignWorkshops);

      expect(tile.visible).not.toContain("adminSurface");
      expect(entry!.adminSurface).toBeFalsy();
    });

    test(`for every role, the ${href} tile, the menu row and the URL agree`, () => {
      const tile = TILES.find((candidate) => candidate.newHref === href)!;
      const entry = NAV_ITEMS.find((item) => item.href === href)!;
      expect(tile.visible).toBe("canRunDesignWorkshops(user)");

      const table = ROLES.map((role) => {
        const account = user(role);
        return {
          role,
          tile: canRunDesignWorkshops(account),
          nav: entry.can(account),
          url: canAccessRoute(account, href)
        };
      });

      expect(table).toEqual(
        ROLES.map((role) => ({ role, tile: OFFERED[role], nav: OFFERED[role], url: OFFERED[role] }))
      );

      expect(canAccessRoute(null, href)).toBe(false);
      expect(routeGuardFor(href)?.path).toBe(href);
    });
  }

  test('"Open" still means an arrow, in the component that reads the word', () => {
    for (const href of PINNED) {
      expect(TILES.find((tile) => tile.newHref === href)!.newLabel).toBe("Open");
    }
    expect(read(CARD)).toContain('newLabel === "Open" || newLabel === "Manage" ? (');
    expect(read(CARD)).toContain("<ArrowRight");
  });
});

test.describe("the design-workshop family is enumerated in both registers", () => {
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
        if (tile.visible !== "canRunDesignWorkshops(user)") {
          wrong.push(`the ${href} tile is gated on \`${tile.visible}\`, not on the nav entry's predicate`);
        }
      }
    }

    expect(wrong).toEqual([]);
  });
});
