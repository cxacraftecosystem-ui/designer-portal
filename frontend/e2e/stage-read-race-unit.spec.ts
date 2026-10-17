import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { serverReadPredatesPush } from "@/lib/designWorkshopStore";

/**
 * The stage page asks for the server's copy of a stage beside the registry and the draft, and adopts
 * it later. If this browser PUSHES the stage in between, the answer describes the server before that
 * push, and adopting it would lay the older copy over what was just sent. `adoptServerStage` refuses
 * such an answer and the page reads again.
 */

const T = 1_760_000_000_000;

test("a push that completed after the read left makes the read stale", () => {
  // Interleaving: read sent at T, push completes at T + 40, adopt runs at T + 90.
  expect(serverReadPredatesPush({ lastPushedAt: T + 40 }, T)).toBe(true);
  // A tie is not evidence of order, so it is refused too: one more read, never lost data.
  expect(serverReadPredatesPush({ lastPushedAt: T }, T)).toBe(true);
});

test("a push that completed before the read left is already in the answer", () => {
  expect(serverReadPredatesPush({ lastPushedAt: T - 1 }, T)).toBe(false);
  expect(serverReadPredatesPush({ lastPushedAt: null }, T)).toBe(false);
  expect(serverReadPredatesPush(undefined, T)).toBe(false);
  // A caller that does not say when its read left keeps the old behaviour exactly.
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
