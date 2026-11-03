import { test, expect } from "@playwright/test";

import {
  canonicalText,
  comparisonText,
  divergedFields,
  divergenceTally,
  hasMoved,
  RECORD_DELETED_TEXT,
  type DwCanonicalComparison,
  type DwProvenanceReport
} from "../lib/designWorkshopProvenance";
import { canAccessRoute } from "../lib/permissions";
import type { User } from "../lib/types";

const user = (role: string): User => ({ id: "u1", email: "a@b.c", name: "A", role } as User);

const report = (entries: DwProvenanceReport["entries"]): DwProvenanceReport => ({ entries });

test.describe("the divergence arithmetic", () => {
  test("a workshop whose copied values still match reports nothing to explain", () => {
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
    const tally = divergenceTally(
      report([
        {
          entryId: "e1",
          stageKey: "S",
          entityKey: "participant",
          canonical: {
            name: { stored: "Latha", canonical: null, diverged: false, recordDeleted: true }
          }
        }
      ])
    );
    expect(tally).toEqual({ entries: 1, fields: 1 });
  });

  test("a cleared column is a divergence, and is not reported as a deleted record", () => {
    const cleared: DwCanonicalComparison = {
      stored: "98765",
      canonical: null,
      diverged: true,
      recordDeleted: false
    };
    expect(hasMoved(cleared)).toBe(true);
    expect(canonicalText(cleared)).toBe("—");
    expect(canonicalText({ stored: "Latha", canonical: null, recordDeleted: true })).toBe(
      RECORD_DELETED_TEXT
    );
  });

  test("an empty value renders as a dash rather than as the word null", () => {
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
    expect(canAccessRoute(user("DESIGNER"), path)).toBe(false);
    expect(canAccessRoute(user("DESIGNER"), "/design-workshops/dw_123")).toBe(true);
    expect(canAccessRoute(user("DESIGNER"), "/design-workshops/dw_123/stages/WORKSHOP_SETUP")).toBe(true);
  });

  test("an admin and the master admin may open it", () => {
    expect(canAccessRoute(user("ADMIN"), path)).toBe(true);
    expect(canAccessRoute(user("MASTER_ADMIN"), path)).toBe(true);
  });

  test("the rule matches whatever the workshop id happens to be", () => {
    for (const id of ["dw_1", "cmf3k2xyz0000abcd", "a-b-c"]) {
      expect(canAccessRoute(user("DESIGNER"), `/design-workshops/${id}/provenance`)).toBe(false);
    }
  });

  test("a professor is refused too, which is the line isAdmin draws everywhere else", () => {
    expect(canAccessRoute(user("PROFESSOR"), path)).toBe(false);
  });
});
