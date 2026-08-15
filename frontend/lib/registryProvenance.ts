/**
 * WHICH FIELD LIST IS THE DESIGNER LOOKING AT — and when the form has to say so.
 *
 * ── THE DEFECT THIS FILE CLOSES ─────────────────────────────────────────────────────────────────
 * Audit 2026-08-15 (LOW, frontend). `RegistrySource` has declared three states since it was written
 * — `"network" | "memory" | "cache"` — and the stage form drew its "this is the field list saved in
 * this browser" banner for exactly one of them, `"cache"`. `"cache"` is only reachable when the
 * network genuinely FAILED, which is the rarest of the three ways a browser gets behind.
 *
 * The store half has since been fixed and now produces `"memory"`: `fetchStageRegistry` returns the
 * module-level cache without touching the network on every call after the first in a tab's life
 * (`lib/designWorkshops.ts`), nothing in this frontend ever passes `{ refresh: true }` to it, and
 * `adoptStageRegistry` seeds that same module cache from the IndexedDB copy on `loadRegistry`'s
 * catch path. So a tab open since before a deploy, and a tab whose first read fell back to disk and
 * then got its connection back, both serve a registry of arbitrary age — and both used to report
 * `"network"`. Producing the state was half the fix; **the banner is the half a designer can see**,
 * and without this module `"memory"` is a value that travels the whole way to the render and is
 * dropped by an `=== "cache"`.
 *
 * What it costs when the sentence is missing: a field added to stage 4 last week is simply absent
 * from the form, and "this stage does not ask for that" is indistinguishable on screen from "this
 * browser has not been told about it". It is also the enabling condition for the unknown-singleton
 * data loss (`DwDraftStage.unknownSingleton`), where an answer whose key this browser's registry
 * does not declare is dropped on the next ordinary save.
 *
 * ── WHY A PURE MODULE AND NOT A TERNARY IN THE PAGE ─────────────────────────────────────────────
 * The same argument `components/data/cappedList.ts` and `lib/designWorkshopViewers` make, and this
 * file deliberately copies their shape. Two of the three states cannot be produced deliberately in a
 * browser — `"memory"` needs a tab that has outlived a deploy and `"cache"` needs the network to
 * fail between two reads — so a decision written inline in JSX is only ever exercised by somebody
 * who happens to be looking at the screen at the moment it occurs. Here it is exercised by a spec.
 *
 * ── WHY NO AGE IN THE SENTENCE ──────────────────────────────────────────────────────────────────
 * `loadRegistry` declares `storedAt?: number` on its return and NEITHER of its two return
 * statements sets it (`lib/designWorkshopStore.ts`), so there is no age to print — a second field
 * declared and never produced, of exactly the kind this finding is about. Promising "saved 6 days
 * ago" from a field that is always `undefined` would print "saved NaN days ago" or, worse, silently
 * omit the clause and leave the reader with a sentence that reads as though the copy were fresh.
 * The wording below is therefore age-free and honest at any age. If the store starts producing
 * `storedAt`, add the clause HERE, once, for both states.
 */

import type { RegistrySource } from "@/lib/designWorkshopStore";

/**
 * The sentence to draw above a stage form, or `""` when there is nothing to say.
 *
 * Three states, and only one of them is silent:
 *
 * - `"network"` — this render's registry came off the wire. Nothing to explain; saying "this is
 *   current" on every stage open is a standing notice these screens have twice been asked for less
 *   of, and a banner that is always there is a banner nobody reads on the day it matters.
 * - `"memory"` — served from the tab's module cache with no request made. It was fetched at some
 *   point in this tab's life, which may be before the last deploy, or may itself have come off disk
 *   via `adoptStageRegistry`. The honest statement is that it has not been re-checked, plus the one
 *   action that fixes it: a reload, which empties the module cache by definition.
 * - `"cache"` — the network was asked and could not answer, so this came out of IndexedDB. Older
 *   than the above and for a reason the designer can see for themselves, so the sentence names the
 *   cause. This is the wording that was already on screen and it is kept verbatim: it has been read
 *   by designers in the field, and rewording a sentence that works is a cost with no benefit.
 *
 * `null` is the fourth caller state — the registry has not resolved yet — and is silent for the
 * same reason `"network"` is: a form that has not drawn cannot be misread.
 */
export function registryProvenanceNotice(source: RegistrySource | null): string {
  if (source === "memory") {
    return (
      "This form is drawn from a field list this tab loaded earlier and has not re-checked since. If the app has been " +
      "deployed while this tab was open, a field added since will not appear here — reload the page to fetch the " +
      "current one."
    );
  }
  if (source === "cache") {
    return (
      "This form is drawn from the field list saved in this browser, because the server could not be reached. It is " +
      "whatever was current the last time this laptop had a connection; a field added since will not appear until it does."
    );
  }
  return "";
}
