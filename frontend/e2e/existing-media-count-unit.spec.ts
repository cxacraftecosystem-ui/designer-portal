import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE COUNT THAT WAS REALLY A PAGE SIZE.
 *
 * "Previously uploaded media" — the panel on every record edit page (ArtisanForm, ProductForm,
 * ToolForm, /workshops, /crafts) — asked `GET /media` for one page of 100, discarded
 * `PageResult.total`, and then rendered `{items.length} file{…} already attached`. 100 is the
 * endpoint's CEILING (`pageSize: int = Query(20, ge=1, le=100)`), so a record carrying a bulk import
 * printed its own request's page size as a fact: "100 files already attached", whether there were
 * 100 or 340. `/media` orders `createdAt desc`, so the ones that fell off the end were the OLDEST,
 * and this panel is the only per-record screen from which a file can be opened or removed. Audit
 * 2026-08-15 (MINOR, frontend).
 *
 * WHY THIS IS A SOURCE READ. `ExistingMedia` is a React component, and this repository has no React
 * renderer in its devDependencies — Playwright is the whole of it — so mounting it is not available.
 * `questionnaire-workshop-filter-unit.spec.ts` and `derived-fields-unit.spec.ts` read their subjects
 * the same way and for the same reason. What this cannot prove is that the browser PAINTS the number;
 * what it does prove is that the number the sentence is built from is the server's and not the
 * page's.
 *
 * Every assertion below fails against the file as it was.
 */

const SOURCE = () =>
  readFileSync(join(__dirname, "..", "components", "media", "ExistingMedia.tsx"), "utf8");

/** The text between two markers, so an assertion cannot drift into a neighbouring block. */
function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

test("the number of attached files comes from the server, not from the page that was fetched", () => {
  const source = SOURCE();
  /*
    The ONE rendered line, picked by the words that only the rendered line has. Not a `between()`
    range: the file's own KDoc and the `total` state's comment both quote the old sentence verbatim
    (that is what a comment naming its defect looks like), so any range assertion anchored on
    "already attached" matches the prose describing the bug and passes against the bug itself.
  */
  const sentence = source
    .split(/\r?\n/)
    .find((line) => line.includes("already attached. Audio transcripts"));
  expect(sentence, "the rendered sentence not found — has the panel been reworded?").toBeTruthy();

  // The whole finding. `items.length` in this sentence IS the defect: it can never exceed the page
  // size, so above one page it stops being a count and becomes a description of the request.
  expect(sentence ?? "", "print the count the server reported").toContain("{total} file");
  expect(sentence ?? "", "items.length is a page size, not a number of attachments").not.toContain(
    "{items.length} file"
  );

  // And the count has to actually be read off the envelope, or `total` is just a differently named
  // `items.length`.
  expect(source, "PageResult.total is what makes the sentence true").toContain("serverTotal = result.total");
});

test("the older attachments beyond the first page can still be reached", () => {
  const source = SOURCE();

  // A record's oldest files were unreachable from the one screen that manages them — not merely
  // uncounted. The request must be able to name a page at all...
  const request = between(source, 'listResource<MediaFile>("/media"', "});");
  expect(request, "a request that cannot name a page can only ever show the first one").toContain("page,");
  expect(request, "100 is the endpoint's maximum, declared once").toContain("pageSize: PAGE_SIZE");

  // ...and the panel must tell the designer that the tail exists rather than drawing 100 tiles and
  // implying that is all of them.
  expect(source, "say that older files exist").toContain("older file");
  expect(source, "and offer a way to reach them").toContain("setPagesLoaded((current) => current + 1)");
});

test("removing a file moves the count with it", () => {
  // `total` is the server's number, so a local delete has to adjust it — otherwise the fix would
  // trade "100 files" over 340 for "3 files" over two tiles.
  const remove = between(SOURCE(), "async function removeMedia", "} catch");
  expect(remove).toContain("setTotal((current) => Math.max(0, current - 1));");
});
