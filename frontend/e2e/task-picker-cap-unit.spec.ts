import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { flagCutNotice } from "@/components/data/cappedList";

/**
 * THE ASSIGNMENT PICKERS SERVED A SLICE OF THE ROSTER AND CALLED IT THE ROSTER.
 *
 * Audit 2026-08-15 (MAJOR, backend, frontend half). `GET /tasks/options` fills every picker in the
 * assignment builder from one call, and reads at most 500 users, 200 workshops and 500 artisans.
 * Against this repository's own measured population — 3632 accounts and 731 artisans
 * (docs/OPEN_FINDINGS.md, 2026-08-13) — two of those cuts are live today, and the workshop table
 * stood at 196 against its cap of 200 on 2026-08-15. The route has since been given `search` and the
 * three `*Truncated` flags; the CLIENT half was still missing, and that is what this spec pins:
 *
 *   1. the page must SEND `search`, or the flags describe a cut nothing can reach past;
 *   2. all three pickers must SAY they were cut, or "not in this list" and "no such colleague"
 *      go on rendering identically — the class of defect the design-workshop viewer picker already
 *      cost this repository once (353 invisible accounts, closed 2026-08-13);
 *   3. the sentence must change once a term HAS been typed, because telling somebody to search when
 *      they already have is how a picker teaches a user that searching does not work.
 *
 * WHY PART PURE CALL AND PART SOURCE READ, the same split `capped-lists-unit.spec.ts` makes and for
 * its reason: the wording is a pure function and is tested by calling it; the request parameters and
 * the render sites live inside React components, and this repository has no React renderer in its
 * devDependencies — Playwright is the whole of it — so those are read out of the source. Every
 * assertion below fails against the tree as it was before this change.
 *
 * WHAT THESE DO NOT PROVE: that a browser paints the sentence against a real 3632-row user table.
 * That belongs in a signed-in spec, and cannot be written honestly against a stub — the defect IS
 * that a table grew past a number, and a fixture is a list somebody chose the length of.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

/* ────────────────────────────────────────────────────────────────────────────
 * The sentence
 * ──────────────────────────────────────────────────────────────────────────── */

test("a list the server did not report as cut says nothing at all", () => {
  // The common case, and the reason `""` rather than a component-level `&&`: a standing note about
  // caps under every complete picker is the padding this UI has twice been asked to lose.
  expect(flagCutNotice(false, "people", "")).toBe("");
  expect(flagCutNotice(false, "people", "kamla")).toBe("");
});

test("a flag the deployment does not send yet reads as nothing to say, never as a cut", () => {
  /*
    `apiFetch` casts the body, it does not validate it, so a browser held open across a rollback —
    or pointed at an older API — receives no flag at all. Claiming a cut nobody can act on is as bad
    as hiding one, and this is the same guard, for the same reason, as `cutOf`'s `Number.isFinite`.
  */
  expect(flagCutNotice(undefined, "people", "")).toBe("");
});

test("a cut with nothing typed points at the search box and disowns the picker's own filter", () => {
  const sentence = flagCutNotice(true, "people", "");

  expect(sentence).toContain("more people than this list can hold");
  // The instruction has to be the box that reaches the SERVER. The MultiSelectDropdown's own filter
  // runs over the array it was handed, so "type into the picker" would be the same lie one layer
  // down — precisely the mistake the viewer picker's notice was fixed for on 2026-08-13.
  expect(sentence).toContain("search for a name above");
  expect(sentence).toContain("only filters what is already listed");
});

test("a cut with a term typed asks for a narrower term, and never asks the reader to search again", () => {
  const sentence = flagCutNotice(true, "people", "  sharma  ");

  // Trimmed, because the term is quoted back at the reader and quoting their whitespace reads as a
  // bug in the box.
  expect(sentence).toContain("“sharma”");
  expect(sentence).toContain("narrow the search above");
  expect(sentence).not.toContain("search for a name above");
});

test("the noun is the caller's, so three pickers do not all report 'records'", () => {
  expect(flagCutNotice(true, "artisans", "")).toContain("more artisans");
  expect(flagCutNotice(true, "workshops", "")).toContain("more workshops");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The request, and the three render sites
 * ──────────────────────────────────────────────────────────────────────────── */

test("the options request carries the search term, and re-runs when it settles", () => {
  const source = read("app", "(protected)", "settings", "tasks", "page.tsx");

  // Folded into the request, not applied to the answer. Searching after the take searches the first
  // 500 names of the alphabet and stops at exactly the ceiling the parameter exists to get past —
  // the same argument `design_workshop_viewers.eligible_viewers` makes about its own.
  expect(source).toContain("buildQuery({ workshopId, search: appliedSearch || undefined })");
  expect(source, "an abandoned prefix must not become a request").toContain(
    "[permitted, workshopId, appliedSearch]"
  );
  // 350ms is what /artisans already debounces its live search at; a second number here would make
  // two screens in one app feel differently responsive for no reason.
  expect(source).toContain("setAppliedSearch(pickerSearch.trim()), 350");
});

test("the search box is on screen and is announced", () => {
  const source = read("app", "(protected)", "settings", "tasks", "page.tsx");

  expect(source).toContain('aria-label="Search the assignment pickers"');
  expect(source).toContain("Find a person, workshop or artisan");
});

test("the workshop scope survives a search that no longer returns it", () => {
  const source = read("app", "(protected)", "settings", "tasks", "page.tsx");

  /*
    The search narrows the workshop list too, and the scope is chosen FROM that list. Without the
    pinned row, typing an artisan's name after picking a workshop dropped the workshop out of
    `options.workshops`, the Dropdown matched no option and fell back to its placeholder ("All
    workshops") while the rollup and the batch list stayed narrowed to it. A control that says "All
    workshops" over a filtered board is worse than the cut the box was added to fix.
  */
  expect(source).toContain("pinnedWorkshop");
  expect(source).toContain("[pinnedWorkshop, ...listed]");
  expect(source, "and it must be cleared with the scope, or it accumulates").toContain(
    "setPinnedWorkshop(null)"
  );
});

test("all three capped pickers report their cut, each beside its own control", () => {
  const page = read("app", "(protected)", "settings", "tasks", "page.tsx");
  const builder = read("components", "tasks", "AssignmentBuilder.tsx");

  // The workshop dropdown lives on the page; the assignee and artisan pickers live in the builder.
  // A notice has to sit AT the control it is about — the reader who cannot find their colleague is
  // looking at the picker, not at a paragraph above the fold.
  expect(page).toContain('flagCutNotice(options?.workshopsTruncated, "workshops", appliedSearch)');
  expect(builder).toContain('flagCutNotice(options?.assigneesTruncated, "people", pickerSearch)');
  expect(builder).toContain('flagCutNotice(options?.artisansTruncated, "artisans", pickerSearch)');

  // Drawn through the one element every other truncation line in the app uses, so these do not
  // become a second kind of thing with their own classes.
  expect(builder).toContain("<CappedListNotice");
  expect(page).toContain("<CappedListNotice");
});

test("the builder is told the term that was SENT, not the one being typed", () => {
  const page = read("app", "(protected)", "settings", "tasks", "page.tsx");

  // The builder's notices describe the lists it is HOLDING, which came back for the settled term.
  // Quoting a half-typed prefix would name a search nobody ran.
  expect(page).toContain("pickerSearch={appliedSearch}");
});

test("the wire type keeps the three flags optional", () => {
  const source = read("components", "tasks", "types.ts");

  // Required fields would satisfy the compiler and still hand the runtime `undefined` from any
  // deployment that predates them.
  expect(source).toContain("assigneesTruncated?: boolean;");
  expect(source).toContain("workshopsTruncated?: boolean;");
  expect(source).toContain("artisansTruncated?: boolean;");
});
