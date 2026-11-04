import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { serverReadPredatesPush } from "@/lib/designWorkshopStore";

const T = 1_760_000_000_000;

test("a push that completed after the read left makes the read stale", () => {
  expect(serverReadPredatesPush({ lastPushedAt: T + 40 }, T)).toBe(true);
  expect(serverReadPredatesPush({ lastPushedAt: T }, T)).toBe(true);
});

test("a push that completed before the read left is already in the answer", () => {
  expect(serverReadPredatesPush({ lastPushedAt: T - 1 }, T)).toBe(false);
  expect(serverReadPredatesPush({ lastPushedAt: null }, T)).toBe(false);
  expect(serverReadPredatesPush(undefined, T)).toBe(false);
  expect(serverReadPredatesPush({ lastPushedAt: T + 40 }, undefined)).toBe(false);
});

test("the stage page stamps the early read, hands the stamp to the store and reads again on refusal", () => {
  const page = readFileSync(
    join(__dirname, "..", "app", "(protected)", "design-workshops", "[id]", "stages", "[stageKey]", "page.tsx"),
    "utf8"
  );
  const sent = page.indexOf("const earlySentAt = Date.now();");
  const early = page.indexOf("settled(getDesignWorkshopStage(id, stageKey))");
  expect(sent).toBeGreaterThan(-1);
  expect(early).toBeGreaterThan(sent);
  expect(page).toContain("adoptServerStage(draft.localId, spec, stageData, { readSentAt, onStale: () => (stale = true) })");
  expect(page).toMatch(/if \(stale\) \{[\s\S]{0,200}getDesignWorkshopStage\(draft\.remoteId, stageKey\)/);
});
