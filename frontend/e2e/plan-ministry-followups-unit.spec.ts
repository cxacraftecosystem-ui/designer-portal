/**
 * The plan/ministry stream's client rules (C1, C9, C15) — the pure halves called directly, and the
 * properties that have no other form asserted as source text (no React renderer in devDependencies).
 *
 *   - C9: `copyMarksBody`, `copiesAfterPush` (the marks leave the draft once a push is accepted, so
 *     one press of "Keep as is" confirms on the server), and the completeness-hiding rule.
 *   - C1: the planned-workshop picker's single-match auto-pick and its refusal rendering; the
 *     "stage 1 was not filled" notice.
 *   - C15: the export list polls only while a job is in flight.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { autoPickedRow, plannedRowSummary } from "@/app/(protected)/sanction-orders/PlannedWorkshopPicker";
import { DW_COPIED_SOURCE, countedSingleton, pendingCopyKeys, splitKeptCopies } from "@/lib/designWorkshops";
import { copiesAfterPush, copyMarksBody, emptyStage, type DwDraftStage } from "@/lib/designWorkshopStore";
import { exportsInFlight } from "@/lib/ministryDashboard";
import { COVER_PINNED_KEYS, coverRows } from "@/lib/offlineReport/builder";
import type { PlannedWorkshopCandidate } from "@/lib/sanctionOrders";

const ROOT = join(__dirname, "..");
const read = (path: string) => readFileSync(join(ROOT, path), "utf8");

function stageWith(copiedFrom: Record<string, string>): DwDraftStage {
  return { ...emptyStage("CLUSTER_CRAFT_BACKGROUND"), copiedFrom };
}

test.describe("C9 copy marks", () => {
  test("copyMarksBody sends only a non-empty map", () => {
    expect(copyMarksBody(undefined)).toEqual({});
    expect(copyMarksBody(stageWith({}))).toEqual({});
    expect(copyMarksBody(stageWith({ history: "w0" }))).toEqual({ copiedFrom: { history: "w0" } });
  });

  test("an accepted push moves the marks into a copied stamp", () => {
    const stage = stageWith({ history: "w0", craftIntroduction: "w1" });
    const settled = copiesAfterPush(stage, { history: "w0", craftIntroduction: "w1" });
    expect(settled.copiedFrom).toEqual({});
    expect(settled.provenance?.singleton?.history?.source).toBe(DW_COPIED_SOURCE);
    expect(settled.provenance?.singleton?.history?.copiedFrom?.workshopId).toBe("w0");
    // The next push carries no marks, so a second device cannot re-mark kept text.
    expect(copyMarksBody({ ...stage, ...settled })).toEqual({});
  });

  test("a box re-copied from another workshop during the round trip keeps its newer mark", () => {
    const settled = copiesAfterPush(stageWith({ history: "w9" }), { history: "w0" });
    expect(settled).toEqual({});
  });

  test("an existing server stamp is kept as it is", () => {
    const stage: DwDraftStage = {
      ...stageWith({ history: "w0" }),
      provenance: { singleton: { history: { source: DW_COPIED_SOURCE, by: "u1", at: "2026-10-17" } } }
    };
    const settled = copiesAfterPush(stage, { history: "w0" });
    expect(settled.provenance?.singleton?.history).toEqual({ source: DW_COPIED_SOURCE, by: "u1", at: "2026-10-17" });
  });

  test("copy, sync, then one press of Keep as is confirms on the server and the box counts", () => {
    // Copied on this device…
    let stage = stageWith({ history: "w0" });
    const singleton = { history: "Our history", craftIntroduction: "Typed" };
    expect([...pendingCopyKeys(stage.copiedFrom!, stage.provenance?.singleton, new Set())]).toEqual(["history"]);
    // …synced…
    stage = { ...stage, ...copiesAfterPush(stage, copyMarksBody(stage).copiedFrom) };
    const hidden = pendingCopyKeys(stage.copiedFrom ?? {}, stage.provenance?.singleton, new Set());
    expect([...hidden]).toEqual(["history"]);
    expect(countedSingleton(singleton, hidden)).toEqual({ craftIntroduction: "Typed" });
    // …and the press goes to the server, not merely to the local mark.
    expect(splitKeptCopies(["history"], stage.copiedFrom ?? {})).toEqual({ local: [], remote: ["history"] });
    // The server's answer (source: designer) makes it count.
    const confirmed = { history: { source: "designer" } };
    const after = pendingCopyKeys(stage.copiedFrom ?? {}, confirmed, new Set());
    expect(after.size).toBe(0);
    expect(countedSingleton(singleton, after)).toEqual(singleton);
  });

  test("an unsent copy is kept locally; an edited box is not pending", () => {
    expect(splitKeptCopies(["history"], { history: "w0" })).toEqual({ local: ["history"], remote: [] });
    const stamps = { history: { source: DW_COPIED_SOURCE } };
    expect(pendingCopyKeys({}, stamps, new Set(["history"])).size).toBe(0);
  });

  test("both acknowledgement sites settle the marks", () => {
    const store = read("lib/designWorkshopStore.ts");
    expect(store.match(/\.\.\.copiesAfterPush\(/g)?.length).toBe(2);
    const page = read("app/(protected)/design-workshops/[id]/stages/[stageKey]/page.tsx");
    expect(page).toContain("sentCopiedFrom: copyMarksBody(draftStage).copiedFrom");
    expect(page).toContain("setCopiedFrom(next.copiedFrom ?? {})");
  });

  test("the reference block names both matches the server makes", () => {
    const panel = read("components/designworkshop/CopyFromEarlierPanel.tsx");
    expect(panel).toContain("Other designers, same cluster or craft");
    expect(panel).not.toContain("Other designers in this cluster");
  });
});

function candidate(overrides: Partial<PlannedWorkshopCandidate>): PlannedWorkshopCandidate {
  return {
    id: "p1",
    planYear: 2099,
    planYearLabel: "2099-00",
    workshopNo: "DPW/2099/001",
    plannedTitle: null,
    workshopKindLabel: null,
    craftName: "Ajrakh",
    clusterName: null,
    state: "Gujarat",
    district: "Kachchh",
    plannedStartDate: "2099-01-05T00:00:00Z",
    plannedEndDate: "2099-01-19T00:00:00Z",
    standing: "PLANNED",
    pickable: true,
    reason: null,
    ...overrides
  } as PlannedWorkshopCandidate;
}

test.describe("C1 planned-workshop picker", () => {
  test("one pickable exact match is picked; anything else is left to the officer", () => {
    const one = candidate({});
    expect(autoPickedRow([one])).toBe(one);
    expect(autoPickedRow([])).toBeNull();
    expect(autoPickedRow([candidate({ pickable: false, reason: "Already opened." })])).toBeNull();
    expect(autoPickedRow([one, candidate({ id: "p2", pickable: false, reason: "Withdrawn." })])).toBeNull();
  });

  test("a row that cannot be picked shows its reason instead of a button", () => {
    const source = read("app/(protected)/sanction-orders/PlannedWorkshopPicker.tsx");
    expect(source).toMatch(/row\.pickable \? \([\s\S]*Use this workshop[\s\S]*\) : \([\s\S]*\{row\.reason\}/);
    expect(plannedRowSummary(candidate({}))).toBe("Ajrakh · Kachchh, Gujarat · 2099-01-05 to 2099-01-19");
  });

  test("a stage 1 the order could not fill is said on the register", () => {
    const page = read("app/(protected)/sanction-orders/page.tsx");
    expect(page).toContain("created.reportCopySeeded === false");
    expect(page).toContain("SANCTION_SEED_FAILED_NOTE");
  });
});

test.describe("C1 the sanctioned amount on the cover (device builder)", () => {
  test("a pinned row keeps its place inside the ten-row cap, in declared order", () => {
    expect([...COVER_PINNED_KEYS]).toEqual(["sanctionAmount"]);
    const keyed: Array<[string, string, string]> = [
      ...Array.from({ length: 12 }, (_, i) => [`k${i}`, `L${i}`, "v"] as [string, string, string]),
      ["sanctionAmount", "Amt", "1"]
    ];
    const { printed, overflow } = coverRows(keyed);
    expect(printed.map(([label]) => label)).toEqual([...Array.from({ length: 9 }, (_, i) => `L${i}`), "Amt"]);
    expect(overflow.map(([label]) => label)).toEqual(["L9", "L10", "L11"]);
  });
});

test.describe("C15 export polling", () => {
  test("polls only while a job is waiting or running", () => {
    const job = (status: string) => ({ status }) as Parameters<typeof exportsInFlight>[0][number];
    expect(exportsInFlight([job("READY"), job("FAILED")])).toBe(false);
    expect(exportsInFlight([job("READY"), job("QUEUED")])).toBe(true);
    expect(exportsInFlight([job("RUNNING")])).toBe(true);
    const insights = read("app/(protected)/ministry-dashboard/MinistryInsights.tsx");
    expect(insights).toContain("if (!items || !exportsInFlight(items)) return;");
    expect(insights).toContain("window.clearTimeout(timer)");
  });
});
