import { test, expect } from "@playwright/test";

import {
  comparisonText,
  divergedFields,
  divergenceTally,
  type DwProvenanceReport
} from "../lib/designWorkshopProvenance";
import { canAccessRoute } from "../lib/permissions";
import type { User } from "../lib/types";

/**
 * **THE ADMIN AUTHORSHIP AND DIVERGENCE VIEW** — its arithmetic, and the gate in front of it.
 *
 * ── WHAT IT IS FOR ───────────────────────────────────────────────────────────────────────────────
 *
 * A workshop keeps what the designer saw on the day; the shared record goes on changing. Only the
 * `reference` stamp plus a live read of the record can say "this workshop says Barpali and the
 * artisan record now says Bargarh", because once hydrated the value is an ordinary string —
 * a hydrated village and a typed village are the same bytes, deliberately.
 *
 * ── DIVERGENCE IS NOT AN ERROR, AND THE TESTS ARE WRITTEN THAT WAY ON PURPOSE ────────────────────
 *
 * Nothing here asserts that divergence is bad, counts "problems", or expects a warning. An artisan
 * who moved village after the workshop makes every row naming them diverge and every one of those
 * rows is correct. The page exists so an admin can EXPLAIN why a submitted report and the live
 * directory disagree, and the tests exist so the arithmetic behind that explanation is right.
 */

const user = (role: string): User => ({ id: "u1", email: "a@b.c", name: "A", role } as User);

const report = (entries: DwProvenanceReport["entries"]): DwProvenanceReport => ({ entries });

test.describe("the divergence arithmetic", () => {
  test("a workshop whose copied values still match reports nothing to explain", () => {
    // The COMMON CASE, and the one the page must answer in a line rather than with an empty table
    // that reads like a failed load.
    const tally = divergenceTally(
      report([
        {
          entryId: "e1",
          stageKey: "S",
          entityKey: "participant",
          canonical: { name: { stored: "Latha", canonical: "Latha", diverged: false } }
        }
      ])
    );
    expect(tally).toEqual({ entries: 0, fields: 0 });
  });

  test("fields and records are counted separately, because they answer different questions", () => {
    // "Three fields across one participant" and "three fields across three participants" are very
    // different findings for an admin, and a single total cannot tell them apart.
    const tally = divergenceTally(
      report([
        {
          entryId: "e1",
          stageKey: "S",
          entityKey: "participant",
          canonical: {
            village: { stored: "Barpali", canonical: "Bargarh", diverged: true },
            phone: { stored: "98765", canonical: "99999", diverged: true },
            name: { stored: "Latha", canonical: "Latha", diverged: false }
          }
        },
        {
          entryId: "e2",
          stageKey: "S",
          entityKey: "participant",
          canonical: { name: { stored: "Bhikari", canonical: "Bhikari", diverged: false } }
        }
      ])
    );
    expect(tally).toEqual({ entries: 1, fields: 2 });
  });

  test("only the diverged fields are listed, never the whole comparison", () => {
    const fields = divergedFields({
      entryId: "e1",
      stageKey: "S",
      entityKey: "participant",
      canonical: {
        village: { stored: "Barpali", canonical: "Bargarh", diverged: true },
        name: { stored: "Latha", canonical: "Latha", diverged: false }
      }
    });
    expect(fields.map(([key]) => key)).toEqual(["village"]);
  });

  test("a deleted canonical record is a divergence and is not dropped", () => {
    // THE CASE REFERENCE HYDRATION EXISTS FOR. The workshop now holds the only copy of what the
    // designer saw, which is the most interesting row on this page — omitting it would make "the
    // record is gone" look identical to "the field was never copied".
    const tally = divergenceTally(
      report([
        {
          entryId: "e1",
          stageKey: "S",
          entityKey: "participant",
          canonical: { name: { stored: "Latha", canonical: null, diverged: true } }
        }
      ])
    );
    expect(tally).toEqual({ entries: 1, fields: 1 });
  });

  test("an empty value renders as a dash rather than as the word null", () => {
    // A blank cell means "this record says nothing here", which is a real answer and one of the
    // more interesting divergences: a workshop holding a phone number the record has since cleared.
    expect(comparisonText(null)).toBe("—");
    expect(comparisonText(undefined)).toBe("—");
    expect(comparisonText("")).toBe("—");
    expect(comparisonText(0)).toBe("0");
    expect(comparisonText(false)).toBe("false");
    expect(comparisonText("Barpali")).toBe("Barpali");
  });
});

test.describe("who may open it", () => {
  const path = "/design-workshops/dw_123/provenance";

  test("a designer is refused, and still keeps the workshop itself", () => {
    // The point of the pair. This view crosses OUT of the workshop into the shared record tables and
    // reports one account's data beside another's; the workshop, its stages and the per-field stamps
    // under each box are unaffected. A gate that took the workshop with it would be far worse than
    // the view is worth.
    expect(canAccessRoute(user("DESIGNER"), path)).toBe(false);
    expect(canAccessRoute(user("DESIGNER"), "/design-workshops/dw_123")).toBe(true);
    expect(canAccessRoute(user("DESIGNER"), "/design-workshops/dw_123/stages/WORKSHOP_SETUP")).toBe(true);
  });

  test("an admin and the master admin may open it", () => {
    expect(canAccessRoute(user("ADMIN"), path)).toBe(true);
    expect(canAccessRoute(user("MASTER_ADMIN"), path)).toBe(true);
  });

  test("the rule matches whatever the workshop id happens to be", () => {
    // It is declared with a `:id` segment, which `routeMatches` did not understand until this route
    // needed it. A rule that silently matched nothing would read as correct in review and admit
    // every designer — which is the whole hole it exists to close.
    for (const id of ["dw_1", "cmf3k2xyz0000abcd", "a-b-c"]) {
      expect(canAccessRoute(user("DESIGNER"), `/design-workshops/${id}/provenance`)).toBe(false);
    }
  });

  test("a professor is refused too, which is the line isAdmin draws everywhere else", () => {
    expect(canAccessRoute(user("PROFESSOR"), path)).toBe(false);
  });
});
