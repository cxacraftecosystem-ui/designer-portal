import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const REPO = join(ROOT, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8");
const readRepo = (relative: string) => readFileSync(join(REPO, relative), "utf8");

function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}

const LIB = read("lib/ministryDashboard.ts");
const LIB_CODE = stripComments(LIB);
const PAGE = read("app/(protected)/ministry-dashboard/page.tsx");
const PAGE_CODE = stripComments(PAGE);
const ROUTES = readRepo("backend/app/api/routes/ministry_dashboard.py");
const GATE = readRepo("backend/tests/test_ministry_dashboard_gate.py");

const PEOPLE_PAGE_TYPE = (() => {
  const match = /export type RegisterPeoplePage = PageResult<RegisterPerson> & \{([\s\S]*?)\n\};/.exec(LIB_CODE);
  expect(match, "`RegisterPeoplePage` is gone or was renamed").not.toBeNull();
  return match![1];
})();

test.describe("the envelope the server sends is the envelope the client declares", () => {
  test("every key the gate test pins is declared on `RegisterPeoplePage`", () => {
    const block = /_keys_written_to\(route\._people_envelope, "payload"\) == \{([\s\S]*?)\n    \}/.exec(GATE);
    expect(block, "the backend gate no longer pins the envelope; this test cannot do its job").not.toBeNull();
    const keys = [...block![1].matchAll(/"(\w+)"/g)].map((match) => match[1]).sort();
    expect(keys.length, "the pinned set came back empty — the parse is wrong, not the code").toBeGreaterThan(5);

    for (const key of keys) {
      expect(PEOPLE_PAGE_TYPE, `the server sends \`${key}\` and no client type declares it`).toContain(`${key}`);
    }
  });

  test("the per-route extras are declared too", () => {
    for (const key of [
      "feedbackRead",
      "feedbackNote",
      "feedbackFiledTotal",
      "unpostedAccountsTruncated",
      "rosterRepresentation",
      "rosterRepresentationNote"
    ]) {
      expect(ROUTES, `the route no longer sends \`${key}\`; update this list deliberately`).toContain(`"${key}"`);
      expect(PEOPLE_PAGE_TYPE, `the server sends \`${key}\` and the client type omits it`).toContain(key);
    }
    for (const key of ["workshopsCreated", "workshopsNamedOn", "byCapacity", "unknownCapacity", "feedbackFiled", "sendBacks"]) {
      expect(ROUTES).toContain(`"${key}"`);
      expect(LIB_CODE, `\`${key}\` is on a person row and the client type omits it`).toContain(key);
    }
  });

  test("the three paths the client asks for are the three the router declares", () => {
    const declared = [...ROUTES.matchAll(/@router\.get\("\/(designers|officers|inspectors)"\)/g)].map(
      (match) => match[1]
    );
    expect(declared.sort()).toEqual(["designers", "inspectors", "officers"]);
    expect(LIB_CODE).toContain('export type PeopleKind = "designers" | "officers" | "inspectors"');
    expect(LIB_CODE).toContain("`/ministry-dashboard/${kind}${registerQuery(params)}`");
  });
});

test.describe("an unmeasured figure is never printed as a zero", () => {
  test("every figure that can be unmeasured is nullable in the client type", () => {
    for (const field of ["percent", "stagesComplete", "stagesTotal", "requiredTotal", "requiredFilled"]) {
      expect(
        LIB_CODE,
        `\`${field}\` is not nullable — a designer whose workshops could not be scored would read as 0`
      ).toMatch(new RegExp(`${field}: number \\| null`));
    }
    expect(LIB_CODE).toMatch(/workshops: number;/);
  });

  test("the progress sentence has a branch for each of the server's three reasons", () => {
    for (const reason of ["noWorkshops", "capped", "unreadable"]) {
      expect(LIB_CODE, `\`${reason}\` has no sentence, so it would fall through to a default`).toContain(
        `case "${reason}":`
      );
    }
    expect(LIB_CODE).toContain('if (typeof person.percent === "number")');
  });

  test("an unread feedback column is a dash and a word", () => {
    expect(PAGE_CODE).toContain('typeof person.feedbackFiled === "number"');
    expect(PAGE_CODE).toContain("— not read");
  });
});

test.describe("the page prints the server's sentences", () => {
  test("each list prints its own scope label", () => {
    expect(PAGE_CODE).toContain("{data.scopeLabel}");
  });

  test("every note the envelope can carry has a reader", () => {
    for (const note of [
      "progressNote",
      "withheldAccountsNote",
      "unpostedAccountsNote",
      "feedbackNote",
      "rosterRepresentationNote"
    ]) {
      expect(PAGE_CODE, `\`${note}\` is sent and never rendered — a disclosure nobody sees`).toContain(
        `data.${note}`
      );
    }
  });

  test("a truncated scan says so, in both of the two ways it can truncate", () => {
    expect(PAGE_CODE).toContain("data.scan?.truncated");
    expect(PAGE_CODE).toContain("data.unpostedAccountsTruncated");
  });

  test("a failed read is a card that says why, not a card that is not there", () => {
    expect(PAGE_CODE).toContain('role="alert"');
    expect(PAGE_CODE).toContain("{error} This list couldn&apos;t be loaded.");
  });
});

test.describe("the server decides what is in the list and in what order", () => {
  test("nothing filters, sorts or slices the rows", () => {
    const from = PAGE_CODE.indexOf("function PeoplePanel(");
    const to = PAGE_CODE.indexOf("function PersonRow(", from);
    expect(from, "PeoplePanel is gone or was renamed").toBeGreaterThan(-1);
    expect(to, "PersonRow is gone, so this slice has no end").toBeGreaterThan(from);
    const panel = PAGE_CODE.slice(from, to);
    for (const call of ["data.items.filter", "data.items.sort", "data.items.slice"]) {
      expect(panel, `${call} narrows a list the server already scoped`).not.toContain(call);
    }
  });

  test("the pager is the server's page, not a window over loaded rows", () => {
    expect(PAGE_CODE).toContain("page={data.page}");
    expect(PAGE_CODE).toContain("pages={data.pages}");
    expect(PAGE_CODE).toContain("total={data.total}");
  });
});

test.describe("the three people cards", () => {
  test("they wear `mango` and never the ministry ACTION ramp", () => {
    expect(PAGE_CODE).toContain('tone="mango"');
    expect(PAGE_CODE).not.toContain('tone="ministry"');
  });

  test("they are shut on a first visit and only an open one fetches", () => {
    expect(PAGE_CODE).toContain('useMegaCards("ministry-dashboard")');
    expect(PAGE_CODE).toContain("if (!allowed || !openPeople) return;");
  });

  test("the read rides the one token every other read on this screen rides", () => {
    expect(PAGE_CODE).toMatch(/\[allowed, loadToken, openPeople\]/);
  });

  test("a late response cannot overwrite newer state", () => {
    expect(PAGE_CODE).toContain("let cancelled = false;");
    expect(PAGE_CODE).toContain("if (cancelled) return;");
  });

  test("the rows are not announced on a timer", () => {
    expect((PAGE_CODE.match(/aria-live="off"/g) ?? []).length).toBeGreaterThanOrEqual(2);
  });

  test("each card carries a word and a glyph, not only a colour", () => {
    expect(LIB_CODE).toContain("export const PEOPLE_KINDS");
    const entries = [...LIB_CODE.matchAll(/id: "(designers|officers|inspectors)"/g)].map((m) => m[1]);
    expect(entries.sort()).toEqual(["designers", "inspectors", "officers"]);
    const icons = /const PEOPLE_ICONS = \{([^}]*)\}/.exec(PAGE_CODE);
    expect(icons, "the people cards lost their glyphs").not.toBeNull();
    const glyphs = [...icons![1].matchAll(/:\s*(\w+)/g)].map((m) => m[1]);
    expect(new Set(glyphs).size, "two people cards share a glyph").toBe(3);
  });
});
