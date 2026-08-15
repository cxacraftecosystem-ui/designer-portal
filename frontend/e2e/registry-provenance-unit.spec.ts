import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { registryProvenanceNotice } from "@/lib/registryProvenance";

/**
 * THE STALE-FIELD-LIST BANNER WAS GATED ON THE ONE STALE STATE THAT ALMOST NEVER HAPPENS.
 *
 * Audit 2026-08-15 (LOW, frontend). `RegistrySource` declares `"network" | "memory" | "cache"`, and
 * the stage form drew its "drawn from the field list saved in this browser" banner for `"cache"`
 * alone. `"cache"` needs the network to have actually FAILED. The ordinary way a browser gets
 * behind is `"memory"`: `fetchStageRegistry` returns the tab's module-level cache without touching
 * the network on every call after the first, nothing in this frontend passes `{ refresh: true }`,
 * and `adoptStageRegistry` seeds that same module cache from IndexedDB on the catch path — so a tab
 * open across a deploy, and a tab that started offline and got its signal back, both drew an
 * arbitrarily old field list and said nothing at all.
 *
 * On screen the cost is that "this stage does not ask for that" and "this browser has not been told
 * about it" are indistinguishable. It is also the enabling condition for the unknown-singleton data
 * loss, where an answer whose key this browser's registry does not declare is dropped by the next
 * ordinary save.
 *
 * WHY A SPEC AND NOT A LOOK AT THE SCREEN. Two of the three states cannot be produced deliberately
 * in a browser — `"memory"` needs a tab that has outlived a deploy, `"cache"` needs a failure
 * between two reads — which is exactly the argument `components/data/cappedList.ts` and
 * `lib/designWorkshopViewers` make for keeping the decision in a pure function. Here it is
 * exercised without one.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

test("a registry fetched on this render says nothing", () => {
  // A banner that is always there is a banner nobody reads on the day it matters, and these screens
  // have twice been asked for less standing furniture.
  expect(registryProvenanceNotice("network")).toBe("");
});

test("a registry that has not resolved yet says nothing", () => {
  // A form that has not drawn cannot be misread.
  expect(registryProvenanceNotice(null)).toBe("");
});

test("a registry served from this tab's memory says so, and names the reload that fixes it", () => {
  const sentence = registryProvenanceNotice("memory");

  expect(sentence, "this is the state that used to be silent").not.toBe("");
  expect(sentence).toContain("has not re-checked");
  // The action has to be the one that actually works: a reload empties the module cache by
  // definition, whereas nothing on the stage form revalidates it.
  expect(sentence).toContain("reload the page");
  expect(sentence).toContain("a field added since will not appear");
});

test("a registry read off disk after a failed fetch keeps its original wording", () => {
  const sentence = registryProvenanceNotice("cache");

  // Verbatim from the banner that was already on screen. Designers in the field have read this
  // sentence; rewording one that works is a cost with no benefit.
  expect(sentence).toContain("the field list saved in this browser, because the server could not be reached");
  expect(sentence).toContain("the last time this laptop had a connection");
});

test("the two stale states do not print the same sentence", () => {
  // They have different causes and different remedies — one is fixed by reloading, the other by
  // getting a connection — and collapsing them would tell half the readers to do the wrong thing.
  expect(registryProvenanceNotice("memory")).not.toBe(registryProvenanceNotice("cache"));
});

test("the stage form renders the decided sentence and compares no source itself", () => {
  const source = read("app", "(protected)", "design-workshops", "[id]", "stages", "[stageKey]", "page.tsx");

  expect(source).toContain("registryProvenanceNotice(registrySource)");
  expect(source).toContain("{registryNotice ? (");
  /*
    The ban that matters. A second `registrySource === "…"` in the render is how this defect was
    written in the first place: the state existed, travelled all the way to the JSX and was dropped
    by a comparison that knew about one of its three values. The string below is searched with its
    comment lines stripped, because the file's own house style argues about the old gate IN PROSE
    and a naive `toContain` would fail on the explanation of the fix.
  */
  const code = source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^[ \t]*\/\/.*$/gm, "");
  expect(code, "the decision belongs in lib/registryProvenance").not.toContain('registrySource === "cache"');
  expect(code).not.toContain('registrySource === "memory"');
});
