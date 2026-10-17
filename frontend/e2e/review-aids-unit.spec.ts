import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import type { DwEntity, DwField, DwStage, DwStageData } from "@/lib/designWorkshops";
import {
  changesSummary,
  comparableRounds,
  fileLabel,
  freshnessLabel,
  clearReviewTrays,
  loadTray,
  MAX_TRAY_NOTES,
  noteAuthor,
  noteTarget,
  readinessChips,
  readinessSentence,
  registerRowHref,
  reviseBody,
  roundChanges,
  sendBackLabel,
  stageDot,
  trayKey,
  type DwReviewNote
} from "@/lib/reviewAids";

/**
 * THE REVIEW AIDS (2026-10-17) — the pure half: "Changed since round N", note labels, the tray's
 * buttons, queue readiness, where a register row opens — and the wire paths held to the server.
 */

const ROOT = join(__dirname, "..", "..");
const read = (path: string) => readFileSync(join(ROOT, path), "utf8");

function field(key: string, label = key): DwField {
  return { key, label, type: "TEXT", tier: "BASIC", required: false } as DwField;
}

function entity(key: string, cardinality: "SINGLETON" | "COLLECTION", fields: DwField[]): DwEntity {
  return { key, name: key, cardinality, title: key, description: "", parent: "", labelField: "name", fields } as DwEntity;
}

const STAGES: DwStage[] = [
  {
    number: 1,
    key: "SETUP",
    title: "Workshop setup",
    purpose: "",
    notes: "",
    optionalStage: false,
    entities: [entity("setup", "SINGLETON", [field("venue", "Venue"), field("dates", "Dates")])]
  },
  {
    number: 2,
    key: "COSTS",
    title: "Costing",
    purpose: "",
    notes: "",
    optionalStage: false,
    entities: [entity("line", "COLLECTION", [field("name", "Item"), field("cost", "Cost")])]
  }
] as unknown as DwStage[];

function stageData(partial: Partial<DwStageData>): DwStageData {
  return { singleton: {}, collections: {}, ...partial } as DwStageData;
}

test.describe("C13 — what changed since a round", () => {
  const before = {
    SETUP: stageData({ singleton: { venue: "Bagru", dates: "1-5 Oct" } }),
    COSTS: stageData({
      collections: {
        line: [
          { _entryId: "a", name: "Dye", cost: "420" },
          { _entryId: "b", name: "Cloth", cost: "300" }
        ]
      }
    })
  };
  const now = {
    SETUP: stageData({ singleton: { venue: "Sanganer", dates: "1-5 Oct" } }),
    COSTS: stageData({
      collections: {
        line: [
          { _entryId: "a", name: "Dye", cost: "455" },
          { _entryId: "c", name: "Thread", cost: "80" }
        ]
      }
    })
  };

  test("a changed field, a changed row, an added row and a removed row are each found", () => {
    const changes = roundChanges(STAGES, now, before);
    expect(Object.keys(changes.SETUP.entities.setup.fields)).toEqual(["venue"]);
    expect(changes.SETUP.entities.setup.fields.venue).toEqual({ before: "Bagru", after: "Sanganer" });
    expect(changes.COSTS.entities.line.changedRows.a).toEqual({ cost: { before: "420", after: "455" } });
    expect(changes.COSTS.entities.line.addedRows).toEqual(["c"]);
    expect(changes.COSTS.entities.line.removedRows.map((row) => row.title)).toEqual(["Cloth"]);
    expect(changes.SETUP.count).toBe(1);
    expect(changes.COSTS.count).toBe(3);
    expect(changesSummary(changes, 1)).toBe("4 changes in 2 stages since round 1.");
  });

  test("an empty answer and a missing one are the same, and nothing moved says so", () => {
    const changes = roundChanges(
      STAGES,
      { SETUP: stageData({ singleton: { venue: "" } }) },
      { SETUP: stageData({ singleton: {} }) }
    );
    expect(changes.SETUP.count).toBe(0);
    expect(changesSummary(changes, 2)).toBe("Nothing changed since round 2.");
  });

  test("only rounds older than the current one can be compared, newest first", () => {
    expect(
      comparableRounds(
        [
          { round: 1, takenAt: null },
          { round: 2, takenAt: null },
          { round: 3, takenAt: null }
        ],
        3
      )
    ).toEqual([2, 1]);
    expect(comparableRounds(undefined, 2)).toEqual([]);
  });
});

test.describe("A19 / B2 / C16 — notes", () => {
  const note = (overrides: Partial<DwReviewNote>): DwReviewNote =>
    ({
      id: "n",
      designWorkshopId: "w",
      round: 1,
      stageKey: null,
      fieldKey: null,
      note: "x",
      sentBack: false,
      actorId: "u",
      actorName: "R. Sen",
      recordedAt: null,
      createdAt: null,
      ...overrides
    }) as DwReviewNote;

  test("freshness is said in words, and silence when the server did not say", () => {
    expect(freshnessLabel({ editedSince: true })).toBe("Edited since");
    expect(freshnessLabel({ editedSince: false })).toBe("Untouched");
    expect(freshnessLabel({})).toBeNull();
  });

  test("a note names its stage and field by title, never by key", () => {
    expect(noteTarget({ stageKey: "SETUP", fieldKey: "venue" }, STAGES)).toBe("1. Workshop setup · Venue");
    expect(noteTarget({ stageKey: "COSTS", fieldKey: null }, STAGES)).toBe("2. Costing");
    expect(noteTarget({ stageKey: null, fieldKey: null }, STAGES)).toBe("The report as a whole");
    expect(noteTarget({ stageKey: "GONE", fieldKey: null }, STAGES)).toBe("GONE");
  });

  test("who wrote it, with the post a director filed it from", () => {
    expect(noteAuthor(note({ actorPost: "ASSISTANT_DIRECTOR" }))).toBe("R. Sen · Assistant Director");
    expect(noteAuthor(note({ byApprovingAuthority: true }))).toBe("R. Sen · approving authority");
    expect(noteAuthor(note({ actorName: " " }))).toBe("An officer no longer named");
  });

  test("the tray's buttons count what they send", () => {
    expect(fileLabel(1)).toBe("File 1 note");
    expect(fileLabel(3)).toBe("File 3 notes");
    expect(sendBackLabel(0)).toBe("Send back");
    expect(sendBackLabel(3)).toBe("Send back with 3 notes");
  });

  test("the approver's send-back carries the tray, or the single sentence as before", () => {
    const drafts = [{ id: "1", note: " Fix venue ", stageKey: "SETUP", fieldKey: "venue" }];
    expect(reviseBody(drafts, "", "SETUP")).toEqual({
      note: null,
      notes: [{ note: "Fix venue", stageKey: "SETUP", fieldKey: "venue" }]
    });
    expect(reviseBody([], "Redo stage 1", "SETUP")).toEqual({ note: "Redo stage 1", stageKey: "SETUP" });
  });

  test("the tray survives a blocked or absent storage", () => {
    expect(loadTray("u", "w", "approve")).toEqual([]);
    expect(() => clearReviewTrays()).not.toThrow();
  });

  test("one account keeps a separate tray per screen", () => {
    expect(trayKey("u", "w", "inspect")).not.toBe(trayKey("u", "w", "approve"));
    expect(trayKey("u", "w", "approve")).toBe("dw_review_tray:approve:u:w");
    for (const page of [
      ["frontend/app/(protected)/design-workshop-approvals/[id]/page.tsx", '"approve"'],
      ["frontend/app/(protected)/design-workshop-inspections/[id]/page.tsx", '"inspect"'],
      ["frontend/app/(protected)/officers/monitored/[id]/page.tsx", '"supervise"']
    ]) {
      expect(read(page[0])).toContain(`useReviewTray(user?.id, id, ${page[1]})`);
    }
  });

  test("signing out forgets every drafted tray", () => {
    const store = new Map<string, string>([
      ["dw_review_tray:inspect:u:w", "[]"],
      ["dw_review_tray:approve:u:x", "[]"],
      ["field_repo_preferences", "{}"]
    ]);
    const fake = {
      get length() {
        return store.size;
      },
      key: (index: number) => [...store.keys()][index] ?? null,
      removeItem: (key: string) => void store.delete(key)
    };
    const g = globalThis as unknown as { window?: unknown };
    const had = "window" in g;
    const previous = g.window;
    g.window = { localStorage: fake };
    try {
      clearReviewTrays();
    } finally {
      if (had) g.window = previous;
      else delete g.window;
    }
    expect([...store.keys()]).toEqual(["field_repo_preferences"]);
    expect(read("frontend/components/AuthProvider.tsx")).toContain("clearReviewTrays();");
  });

  test("approving with drafted notes says they are discarded", () => {
    const page = read("frontend/app/(protected)/design-workshop-approvals/[id]/page.tsx");
    expect(page).toContain(`{plural(drafts.length, "drafted note")} won&apos;t be sent and will be discarded.`);
    expect(page).toContain("Draft a note");
  });

  test("the tray's cap is the server's batch cap", () => {
    const py = read("backend/app/schemas/design_workshop_review_loop.py");
    const match = py.match(/^MAX_BATCH_NOTES\s*=\s*(\d+)/m);
    expect(match).toBeTruthy();
    expect(MAX_TRAY_NOTES).toBe(Number(match![1]));
  });
});

test.describe("B4 — how ready a queued report is", () => {
  const ready = { percent: 64, stagesComplete: 14, stagesTotal: 22, notesThisRound: 3, inspectors: 2, cleared: 1 };

  test("the chips say it in words", () => {
    const chips = readinessChips(ready, { lastHandedInAt: "2026-10-12T09:00:00Z", status: "PRE_SUBMISSION" }, new Date("2026-10-17T09:00:00Z"));
    expect(chips).toEqual(["3 notes this round", "1 of 2 inspectors: no corrections", "Waiting 5 days"]);
    expect(readinessChips({ ...ready, cleared: 2, notesThisRound: 0 }, { lastHandedInAt: null, status: "APPROVED" })).toEqual([
      "All inspectors: no corrections"
    ]);
    expect(readinessChips({ ...ready, inspectors: 0, cleared: 0, notesThisRound: 0 }, { lastHandedInAt: null, status: "x" })).toEqual([
      "No inspector assigned"
    ]);
  });

  test("an unscored row is never a zero", () => {
    expect(readinessSentence({ ...ready, percent: null })).toBe("Not scored");
    expect(readinessSentence(ready)).toBe("64% of required fields · 14 of 22 stages complete");
  });

  test("the stage menu's dots", () => {
    expect(stageDot(undefined)).toBe("empty");
    expect(stageDot({ requiredFilled: 0, requiredTotal: 4 })).toBe("empty");
    expect(stageDot({ requiredFilled: 2, requiredTotal: 4 })).toBe("partial");
    expect(stageDot({ requiredFilled: 4, requiredTotal: 4, isComplete: true })).toBe("complete");
  });
});

test.describe("A17 — a register row opens the read the reader may see", () => {
  test("the approving authority's read, the supervisor's read, or the record page", () => {
    expect(registerRowHref("design", "w1", { approves: true })).toBe("/design-workshop-approvals/w1");
    expect(registerRowHref("design", "w1", { approves: false })).toBe("/officers/monitored/w1");
    expect(registerRowHref("other", "k9", { approves: true })).toBe("/workshops/k9");
  });

  test("both register tables open on a click", () => {
    const page = read("frontend/app/(protected)/ministry-dashboard/page.tsx");
    expect(page.match(/<OpenableRow key=\{row\.id\} href=\{hrefFor\(row\.id\)\}>/g)?.length).toBe(2);
    expect(page).toContain("OpenableRowLink");
  });
});

test.describe("the review screens and the server agree", () => {
  test("every path the client calls exists on the server", () => {
    const inspections = read("backend/app/api/routes/design_workshop_inspections.py");
    const approvals = read("backend/app/api/routes/design_workshop_approvals.py");
    const oversight = read("backend/app/api/routes/design_workshop_oversight.py");
    for (const path of ["/{workshop_id}/feedback/batch", "/{workshop_id}/send-back/batch", "/{workshop_id}/clearance", "/{workshop_id}/rounds/{round}"]) {
      expect(inspections, path).toContain(`"${path}"`);
    }
    for (const path of ["/{workshop_id}/media", "/{workshop_id}/rounds/{round}"]) {
      expect(approvals, path).toContain(`"${path}"`);
    }
    for (const path of ["/assigned/{workshop_id}/feedback/batch", "/assigned/{workshop_id}/rounds/{round}"]) {
      expect(oversight, path).toContain(`"${path}"`);
    }
  });

  test("the three review screens share the layout, the bar and the reader", () => {
    for (const page of [
      "frontend/app/(protected)/design-workshop-approvals/[id]/page.tsx",
      "frontend/app/(protected)/design-workshop-inspections/[id]/page.tsx",
      "frontend/app/(protected)/officers/monitored/[id]/page.tsx"
    ]) {
      const text = read(page);
      for (const part of ["<ReviewLayout", "<ReviewDecisionBar", "<ReviewReadStage", "<NoteComposer", "<RoundCompareControls"]) {
        expect(text, `${page} ${part}`).toContain(part);
      }
    }
  });
});
