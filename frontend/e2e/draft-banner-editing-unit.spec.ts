import { expect, test } from "@playwright/test";

import { bannerRowWanted, editingWorkshopId, outstanding } from "@/components/designworkshop/DraftSyncBanner";

/**
 * THE DRAFT BANNER STAYS OFF THE STAGE THAT IS BEING TYPED INTO.
 *
 * The banner is mounted above every protected page. It used to appear 800 ms after the first
 * keystroke on a stage — the autosave marks the stage unsent — inserting a 128 px panel above the
 * caret, which the browser answered by scrolling the page 144 px (desktop) / 286 px (phone) under
 * the designer. The open stage's own form already says "Saved on this device only" in place, so its
 * workshop's row is left off the banner while it carries nothing but unsent stage edits. Anything
 * that needs action still shows.
 *
 * PURE NODE. Run: `npx playwright test e2e/draft-banner-editing-unit.spec.ts --reporter=line`
 */

type State = ReturnType<typeof outstanding>;

function state(overrides: Partial<State> = {}): State {
  return { stages: 1, refusals: [], held: [], folds: [], neverSent: false, headerOnly: false, ...overrides } as State;
}

const draft = { localId: "dwlocal-1", remoteId: "cmremote1" };

test("the route names the workshop whose stage form is open, by either id", () => {
  expect(editingWorkshopId("/design-workshops/cmremote1/stages/DESIGN_BRIEF")).toBe("cmremote1");
  expect(editingWorkshopId("/design-workshops/dwlocal-1/stages/DESIGN_BRIEF")).toBe("dwlocal-1");
  expect(editingWorkshopId("/design-workshops/cmremote1")).toBeNull();
  expect(editingWorkshopId("/design-workshops/cmremote1/report")).toBeNull();
  expect(editingWorkshopId(null)).toBeNull();
});

test("unsent stage edits of the open workshop are left to the stage form", () => {
  expect(bannerRowWanted(draft, state(), "cmremote1")).toBe(false);
  expect(bannerRowWanted(draft, state(), "dwlocal-1")).toBe(false);
});

test("every other workshop, and every other screen, still shows them", () => {
  expect(bannerRowWanted(draft, state(), "cmother")).toBe(true);
  expect(bannerRowWanted(draft, state(), null)).toBe(true);
});

test("anything that needs action shows even for the open workshop", () => {
  expect(bannerRowWanted(draft, state({ refusals: ["x"] } as Partial<State>), "cmremote1")).toBe(true);
  expect(bannerRowWanted(draft, state({ held: ["x"] } as Partial<State>), "cmremote1")).toBe(true);
  expect(bannerRowWanted(draft, state({ neverSent: true }), "cmremote1")).toBe(true);
  expect(bannerRowWanted(draft, state({ headerOnly: true }), "cmremote1")).toBe(true);
});

test("a draft with nothing outstanding is never shown", () => {
  expect(bannerRowWanted(draft, state({ stages: 0 }), null)).toBe(false);
});
