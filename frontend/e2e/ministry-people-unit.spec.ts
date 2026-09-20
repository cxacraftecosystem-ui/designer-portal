import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE MINISTRY'S THREE PEOPLE REGISTERS — designers, the directorate, and the inspectors.
 *
 * ── WHAT WAS ASKED FOR ─────────────────────────────────────────────────────────────────────────
 *
 * Owner, 2026-09-20: *"the dashboard carries no information about designers, ad, rd, inspectors,
 * make it extremely more capable and powerful, there is no specific card for designers where they
 * can do their stuff."* That was literally true of the schema: `/ministry-dashboard` was one screen
 * over two registers of WORKSHOPS, and the only thing it knew about a person was
 * `DesignWorkshop.designerName` — a free-typed, unindexed stage-1 promotion with no account behind
 * it, where two spellings are two designers and one shared spelling is one.
 *
 * ── THE FAILURE THIS FILE EXISTS FOR, AND IT ALREADY HAPPENED ONCE ─────────────────────────────
 *
 * The server sends `unpostedAccountsNote`. The first version of the client type called it
 * `unpostedNote` — and NOTHING caught it: the key is optional on the wire, `tsc` was happy, eslint
 * was happy, the page compiled, and the sentence simply never appeared. A disclosure that silently
 * stops being printed is worse than one that was never written, because the screen still looks
 * complete.
 *
 * So §1 below reads the PYTHON and holds the TypeScript to it. It is the same cross-language idiom
 * `dashboard-tile-parity-unit.spec.ts` uses on Kotlin, for the same reason: two registers of one
 * fact drift, and the drift is invisible from either side.
 *
 * ⚠ Absences are checked against comment-stripped source — these files argue their own rules and
 * therefore name what they forbid.
 */

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

/** The block of the `RegisterPeoplePage` type, where every envelope key has to be declared. */
const PEOPLE_PAGE_TYPE = (() => {
  const match = /export type RegisterPeoplePage = PageResult<RegisterPerson> & \{([\s\S]*?)\n\};/.exec(LIB_CODE);
  expect(match, "`RegisterPeoplePage` is gone or was renamed").not.toBeNull();
  return match![1];
})();

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 1. THE WIRE IS ONE REGISTER, READ FROM THE PYTHON
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("the envelope the server sends is the envelope the client declares", () => {
  test("every key the gate test pins is declared on `RegisterPeoplePage`", () => {
    /*
      `test_the_envelope_keys_are_pinned_so_a_people_list_cannot_lose_its_disclosures` asserts the
      exact set `_people_envelope` writes. Reading ITS literal rather than the route's source means
      this test is measuring the same thing the backend gate measures, so the two cannot pass while
      disagreeing — and a key added on the server without a client reader fails HERE, in the gate
      that runs on a frontend change.
    */
    const block = /_keys_written_to\(route\._people_envelope, "payload"\) == \{([\s\S]*?)\n    \}/.exec(GATE);
    expect(block, "the backend gate no longer pins the envelope; this test cannot do its job").not.toBeNull();
    const keys = [...block![1].matchAll(/"(\w+)"/g)].map((match) => match[1]).sort();
    expect(keys.length, "the pinned set came back empty — the parse is wrong, not the code").toBeGreaterThan(5);

    for (const key of keys) {
      expect(PEOPLE_PAGE_TYPE, `the server sends \`${key}\` and no client type declares it`).toContain(`${key}`);
    }
  });

  test("the per-route extras are declared too", () => {
    // These are written onto `payload` and onto `row` in the three route bodies rather than in the
    // shared envelope, so the sweep above cannot see them.
    for (const key of [
      "feedbackRead",
      "feedbackNote",
      "feedbackFiledTotal",
      "unpostedAccountsTruncated",
      // Added 2026-09-20 with the roster-gap caveat — the answer to "35 on the roster page, 9
      // here, why?". A key the server sends and no client type declares is the exact failure this
      // whole section exists for, and it has already happened once on this payload.
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
    // And the client builds its URL from the same three tokens, so a rename is one edit and not two.
    expect(LIB_CODE).toContain('export type PeopleKind = "designers" | "officers" | "inspectors"');
    expect(LIB_CODE).toContain("`/ministry-dashboard/${kind}${registerQuery(params)}`");
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 2. NULL IS NOT ZERO — THE RULE THIS WHOLE SCREEN IS BUILT ON
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("an unmeasured figure is never printed as a zero", () => {
  test("every figure that can be unmeasured is nullable in the client type", () => {
    for (const field of ["percent", "stagesComplete", "stagesTotal", "requiredTotal", "requiredFilled"]) {
      expect(
        LIB_CODE,
        `\`${field}\` is not nullable — a designer whose workshops could not be scored would read as 0`
      ).toMatch(new RegExp(`${field}: number \\| null`));
    }
    // The counters are the other way round on purpose: a person is in this register because a
    // relation put them there, so a workshop count of zero is measured by construction.
    expect(LIB_CODE).toMatch(/workshops: number;/);
  });

  test("the progress sentence has a branch for each of the server's three reasons", () => {
    for (const reason of ["noWorkshops", "capped", "unreadable"]) {
      expect(LIB_CODE, `\`${reason}\` has no sentence, so it would fall through to a default`).toContain(
        `case "${reason}":`
      );
    }
    // And it never composes a percentage out of a null.
    expect(LIB_CODE).toContain('if (typeof person.percent === "number")');
  });

  test("an unread feedback column is a dash and a word", () => {
    expect(PAGE_CODE).toContain('typeof person.feedbackFiled === "number"');
    expect(PAGE_CODE).toContain("— not read");
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 3. EVERY DISCLOSURE THE SERVER COMPOSED IS PRINTED, AND THE PAGE COMPOSES NONE
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("the page prints the server's sentences", () => {
  test("each list prints its own scope label", () => {
    /*
      This router shipped ONE caption over two differently-scoped counts once, and told an Assistant
      Director "the workshops you were named on" above a national figure. Each list now carries its
      own sentence and the page prints it verbatim.
    */
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
    // Absence reading as non-existence is this repository's most repeated defect class.
    expect(PAGE_CODE).toContain("data.scan?.truncated");
    expect(PAGE_CODE).toContain("data.unpostedAccountsTruncated");
  });

  test("a failed read is a card that says why, not a card that is not there", () => {
    expect(PAGE_CODE).toContain('role="alert"');
    expect(PAGE_CODE).toContain("which is NOT the same as there being nobody in it");
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 4. NO CLIENT-SIDE NARROWING
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("the server decides what is in the list and in what order", () => {
  test("nothing filters, sorts or slices the rows", () => {
    /*
      A client that narrowed a people list would print a total that disagreed with the rows under it,
      and the ORDER is load-bearing: the server's is workshops descending then name, with an id
      tiebreak so a self-refreshing paged register never serves a row twice or never.
    */
    /*
      SLICED BETWEEN TWO NAMED FUNCTIONS RATHER THAN MATCHED WITH A LINE-ENDING ANCHOR.

      The first version of this ended its pattern with a newline followed by a closing brace, and it
      matched NOTHING: this file is written with CRLF endings, so that anchor never sees the carriage
      return in front of it. The failure read "PeoplePanel is gone or was renamed", which sends the
      next reader to check the wrong thing entirely. Anchor on source text, never on a line ending.
    */
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

// ══════════════════════════════════════════════════════════════════════════════════════════════
// 5. THE CARDS THEMSELVES
// ══════════════════════════════════════════════════════════════════════════════════════════════

test.describe("the three people cards", () => {
  test("they wear `mango` and never the ministry ACTION ramp", () => {
    /*
      `ministry` (hue 45) paints the buttons on this very page through the `[data-surface="ministry"]`
      block — it is this surface's action colour. `mango` (hue 71) is the navigation mark the owner
      asked for. A card chip in the action ramp would make a heading look like a control.
    */
    expect(PAGE_CODE).toContain('tone="mango"');
    expect(PAGE_CODE).not.toContain('tone="ministry"');
  });

  test("they are shut on a first visit and only an open one fetches", () => {
    expect(PAGE_CODE).toContain('useMegaCards("ministry-dashboard")');
    expect(PAGE_CODE).toContain("if (!allowed || !openPeople) return;");
  });

  test("the read rides the one token every other read on this screen rides", () => {
    /*
      `loadToken` is what the poll, the filters, the pager and the manual Refresh all bump. A second
      independent timer would be a second answer to "as of when" on a screen whose whole claim is
      that it says so.
    */
    expect(PAGE_CODE).toMatch(/\[allowed, loadToken, openPeople\]/);
  });

  test("a late response cannot overwrite newer state", () => {
    // `apiFetch` takes no AbortSignal; the cancelled flag is this repository's convention and
    // `list-fetch-generation-unit.spec.ts` pins it for the workshop read.
    expect(PAGE_CODE).toContain("let cancelled = false;");
    expect(PAGE_CODE).toContain("if (cancelled) return;");
  });

  test("the rows are not announced on a timer", () => {
    // They are replaced every thirty seconds; a container that acquired a live role would interrupt
    // a screen-reader user twice a minute.
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
