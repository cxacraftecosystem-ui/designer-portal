import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").split("\r\n").join("\n");

const BROWSE = ["components", "dataExplorer", "BrowseByType.tsx"] as const;
const EXPLORER = ["components", "dataExplorer", "DataExplorer.tsx"] as const;

test("a row opens its record page, so a page turn has no open record to lose", () => {
  const code = read(...BROWSE);
  expect(code).toContain("onClick={(event) => openRow(event, view)}");
  expect(code, "no record picker that only searches the loaded page").not.toContain("<ComboBox");
});

test("the type, page and search live in the URL, so Back and a shared link land on the same page", () => {
  const code = read(...EXPLORER);
  expect(code).toContain("navigate({ browse: next.type, browsePage: next.page, browseQ: next.q })");
  expect(code).toContain("type={url.browse}");
  expect(code).toContain("page={url.browsePage}");
});

test("changing the record TYPE goes back to page 1 and clears the search", () => {
  expect(read(...BROWSE)).toContain('onChange={(value) => onChange({ type: value, page: 1, q: "" })}');
});

test("a page turn keeps the type and the search", () => {
  const code = read(...BROWSE);
  expect(code).toContain("onChange({ type, page: current.page + 1, q })");
  expect(code).toContain("onChange({ type, page: current.page - 1, q })");
});

test("the search reaches every row on the server, not the page in the browser", () => {
  const code = read(...BROWSE);
  expect(code).toContain("fetchBrowse({ type: chosen.value, page, q, workshopIds: narrowed.workshopIds ? undefined : scopeIds, sort: sortText, extra: narrowed })");
  expect(code).toContain("return chosen.legacy(page, q, scopeIds);");
});
