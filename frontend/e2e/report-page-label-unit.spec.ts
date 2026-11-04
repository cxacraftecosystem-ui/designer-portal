import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const SHEET = "components/designworkshop/report/ReportSheet.tsx";

test("the preview's running foot prints the total, the way all four file renderers do", () => {
  const sheet = read(SHEET);
  expect(sheet).toContain("Page {sheet.pageNumber}");
  expect(sheet).toContain('<span className="rp-of"> of {sheets.length}</span>');
});

test("the sheet's accessible name carries the same two numbers the foot prints", () => {
  expect(read(SHEET)).toContain("`Page ${sheet.pageNumber} of ${sheets.length}`");
});

test("the total is qualified on screen as what it now is: an estimate, not the file's own count", () => {
  const sheet = read(SHEET);
  expect(sheet).toContain("Page counts are close estimates");
  expect(sheet).toContain("Word and the .pdf may break lines differently");
  expect(sheet).not.toContain("is a floor, not the");
});

test("the total does not survive into a printed file, because nothing there can qualify it", () => {
  const sheet = read(SHEET);
  expect(sheet).toMatch(/\[data-rp-noprint\][^}]*display: none/);
  expect(sheet).toMatch(/@media print[\s\S]*\.rp-of \{ display: none !important; \}/);
  expect(sheet).toContain('<span className="rp-of">');
});
