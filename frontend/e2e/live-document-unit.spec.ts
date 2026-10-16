import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { buildLiveDocument, stageMediaIds } from "@/components/designworkshop/report/liveDocument";
import { sliceStageBlocks, spliceStageBlocks, stableBlocks } from "@/components/designworkshop/report/stageSlice";
import type { OfflineReportInput } from "@/lib/offlineReport/assemble";
import type { Registry } from "@/lib/offlineReport/builder";
import type { DraftLike } from "@/lib/offlineReport/generate";

/**
 * THE LIVE STAGE PREVIEW, BUILT FROM THE PARITY FIXTURE.
 *
 * `buildLiveDocument` lays a stage form's unsaved values over the draft and builds with the same
 * builder `offline-report-parity-unit.spec.ts` holds to the server. This pins the three things the
 * stage screen relies on: what is typed appears in the document, the stage's blocks splice back into
 * a saved document without moving anything else, and a rebuild is cheap enough to run between
 * keystrokes.
 *
 * PURE NODE. Run: `npx playwright test e2e/live-document-unit.spec.ts --reporter=line`
 */

const ROOT = join(__dirname, "..", "..");
const fixture = JSON.parse(readFileSync(join(ROOT, "shared", "report-parity", "workshop.json"), "utf8")) as OfflineReportInput;
const registry = JSON.parse(
  readFileSync(join(ROOT, "android", "app", "src", "main", "assets", "design-workshop-schema.json"), "utf8")
) as Registry;

/** The fixture in the draft's shape: one singleton bucket per stage, as `draftReportInput` reads it. */
const draft: DraftLike = {
  localId: "dwlocal-fixture",
  remoteId: fixture.workshop.id,
  header: {
    title: fixture.workshop.title,
    templateId: fixture.workshop.templateId,
    workshopCode: fixture.workshop.workshopCode,
    craftName: fixture.workshop.craftName,
    clusterName: fixture.workshop.clusterName,
    state: fixture.workshop.state,
    designerName: fixture.workshop.designerName
  },
  stages: Object.fromEntries(
    Object.entries(fixture.stages).map(([key, stage]) => [
      key,
      { singletons: { all: stage.singleton }, collections: stage.collections, ...(stage.custom ? { custom: stage.custom } : {}) }
    ])
  ),
  customDefinition: fixture.customDefinition
};

const STAGE = "INTRODUCTORY_ADMIN_DOCUMENTATION";
const TITLE = registry.stages.find((s) => s.key === STAGE)!.title;

function text(blocks: unknown[]): string {
  return JSON.stringify(blocks);
}

function liveWith(sentence: string) {
  const stage = fixture.stages[STAGE];
  return {
    singleton: {
      ...stage.singleton,
      briefIntroduction: { blocks: [{ kind: "PARAGRAPH", spans: [{ text: sentence }] }] }
    },
    collections: stage.collections
  };
}

test("what is typed on the form is in the document before anything is saved", () => {
  const built = buildLiveDocument(registry, draft, STAGE, liveWith("TYPED-JUST-NOW on the stage form"), fixture.sources);
  const slice = sliceStageBlocks(built.document.blocks as never[], TITLE);
  expect(slice).not.toBeNull();
  expect(text(slice!)).toContain("TYPED-JUST-NOW");
});

test("the live stage splices into the saved document and nothing else moves", () => {
  const saved = buildLiveDocument(registry, draft, STAGE, liveWith("the saved paragraph"), fixture.sources).document.blocks as never[];
  const live = buildLiveDocument(registry, draft, STAGE, liveWith("the edited paragraph"), fixture.sources).document.blocks as never[];
  const spliced = spliceStageBlocks(saved, live, TITLE)!;
  expect(text(spliced)).toContain("the edited paragraph");
  expect(text(spliced)).not.toContain("the saved paragraph");
  expect(spliced.length).toBe(saved.length);
  // Every block outside the stage keeps its identity, so it is neither re-rendered nor re-measured.
  const stable = stableBlocks(saved, spliced);
  const changed = stable.filter((block, index) => block !== saved[index]).length;
  expect(changed).toBe(1);
});

test("a rebuild is cheap enough to run between keystrokes", () => {
  buildLiveDocument(registry, draft, STAGE, liveWith("warm"), fixture.sources);
  const started = performance.now();
  const runs = 20;
  for (let i = 0; i < runs; i += 1) buildLiveDocument(registry, draft, STAGE, liveWith(`run ${i}`), fixture.sources);
  const each = (performance.now() - started) / runs;
  // Well inside the 250 ms debounce on a laptop; generous so a slow CI runner does not flake.
  expect(each).toBeLessThan(150);
});

test("the photographs on the form are found for the dimension probe", () => {
  const stage = registry.stages.find((s) => s.key === "WORKSHOP_SETUP")!;
  const ids = stageMediaIds(stage as never, { singleton: fixture.stages.WORKSHOP_SETUP.singleton, collections: {} });
  expect(ids).toContain(String(fixture.stages.WORKSHOP_SETUP.singleton.coverPhoto));
});
