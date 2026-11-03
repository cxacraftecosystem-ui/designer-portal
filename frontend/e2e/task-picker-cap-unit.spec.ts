import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { flagCutNotice } from "@/components/data/cappedList";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

test("a list the server did not report as cut says nothing at all", () => {
  expect(flagCutNotice(false, "people", "")).toBe("");
  expect(flagCutNotice(false, "people", "kamla")).toBe("");
});

test("a flag the deployment does not send yet reads as nothing to say, never as a cut", () => {
  expect(flagCutNotice(undefined, "people", "")).toBe("");
});

test("a cut with nothing typed points at the search box and disowns the picker's own filter", () => {
  const sentence = flagCutNotice(true, "people", "");

  expect(sentence).toContain("more people than this list can hold");
  expect(sentence).toContain("search for a name above");
  expect(sentence).toContain("only filters what is already listed");
});

test("a cut with a term typed asks for a narrower term, and never asks the reader to search again", () => {
  const sentence = flagCutNotice(true, "people", "  sharma  ");

  expect(sentence).toContain("“sharma”");
  expect(sentence).toContain("narrow the search above");
  expect(sentence).not.toContain("search for a name above");
});

test("the noun is the caller's, so three pickers do not all report 'records'", () => {
  expect(flagCutNotice(true, "artisans", "")).toContain("more artisans");
  expect(flagCutNotice(true, "workshops", "")).toContain("more workshops");
});

test("the options request carries the search term, and re-runs when it settles", () => {
  const source = read("app", "(protected)", "settings", "tasks", "page.tsx");

  expect(source).toContain("buildQuery({ workshopId, search: appliedSearch || undefined })");
  expect(source, "an abandoned prefix must not become a request").toContain(
    "[permitted, workshopId, appliedSearch]"
  );
  expect(source).toContain("setAppliedSearch(pickerSearch.trim()), 350");
});

test("the search box is on screen and is announced", () => {
  const source = read("app", "(protected)", "settings", "tasks", "page.tsx");

  expect(source).toContain('aria-label="Search the assignment pickers"');
  expect(source).toContain("Find a person, workshop or artisan");
});

test("the workshop scope survives a search that no longer returns it", () => {
  const source = read("app", "(protected)", "settings", "tasks", "page.tsx");

  expect(source).toContain("pinnedWorkshop");
  expect(source).toContain("[pinnedWorkshop, ...listed]");
  expect(source, "and it must be cleared with the scope, or it accumulates").toContain(
    "setPinnedWorkshop(null)"
  );
});

test("all three capped pickers report their cut, each beside its own control", () => {
  const page = read("app", "(protected)", "settings", "tasks", "page.tsx");
  const builder = read("components", "tasks", "AssignmentBuilder.tsx");

  expect(page).toContain('flagCutNotice(options?.workshopsTruncated, "workshops", appliedSearch)');
  expect(builder).toContain('flagCutNotice(options?.assigneesTruncated, "people", pickerSearch)');
  expect(builder).toContain('flagCutNotice(options?.artisansTruncated, "artisans", pickerSearch)');

  expect(builder).toContain("<CappedListNotice");
  expect(page).toContain("<CappedListNotice");
});

test("the builder is told the term that was SENT, not the one being typed", () => {
  const page = read("app", "(protected)", "settings", "tasks", "page.tsx");

  expect(page).toContain("pickerSearch={appliedSearch}");
});

test("the wire type keeps the three flags optional", () => {
  const source = read("components", "tasks", "types.ts");

  expect(source).toContain("assigneesTruncated?: boolean;");
  expect(source).toContain("workshopsTruncated?: boolean;");
  expect(source).toContain("artisansTruncated?: boolean;");
});
