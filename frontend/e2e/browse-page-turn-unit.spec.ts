import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function codeOf(source: string): string {
  const blanked = (match: string) => match.replace(/[^\n]/g, " ");
  return source.replace(/\/\*[\s\S]*?\*\//g, blanked).replace(/\/\/[^\n]*/g, blanked);
}

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the panel been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

const PAGE = ["app", "(protected)", "data", "page.tsx"] as const;

test("the open record is held, not looked up in whichever page is loaded", () => {
  const code = codeOf(read(...PAGE));

  expect(code).toContain("const [selected, setSelected] = useState<BrowseRow | null>(null)");
  expect(code).toContain('const recordId = selected?.id ?? ""');

  expect(code, "the open record must not be recovered from the loaded page").not.toContain("rows?.find(");
  expect(code, "recordId is derived from `selected`; a setter for it would be a second source of truth").not.toContain(
    "setRecordId("
  );
});

test("a page load does not empty the open record's media", () => {
  const code = codeOf(read(...PAGE));
  const loader = between(code, "setBrowse(null)", "[typeKey, page]");

  expect(loader, "clearing the record's media in the LIST loader closes the record on a page turn").not.toContain(
    "setRecordMedia("
  );
  expect(loader).toContain("setBrowse(null)");
});

test("changing the record TYPE still goes back to page 1 and closes the record", () => {
  const code = codeOf(read(...PAGE));
  const reset = between(code, "setPage(1)", "[typeKey]");

  expect(reset).toContain("setSelected(null)");
});

test("the pagination the reset effect was added for is still wired", () => {
  const source = read(...PAGE);
  const code = codeOf(source);

  expect(code).toContain(".load(page)");
  expect(code).toContain("[typeKey, page]");
  expect(code).toContain("onPage={setPage}");
  expect(code).toContain("<Pagination");
  expect(code).toContain('reach="pager"');
});
