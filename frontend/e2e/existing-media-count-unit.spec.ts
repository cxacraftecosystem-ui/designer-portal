import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const SOURCE = () =>
  readFileSync(join(__dirname, "..", "components", "media", "ExistingMedia.tsx"), "utf8");

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

test("the number of attached files comes from the server, not from the page that was fetched", () => {
  const source = SOURCE();
  const sentence = source
    .split(/\r?\n/)
    .find((line) => line.includes("already attached. Audio transcripts"));
  expect(sentence, "the rendered sentence not found — has the panel been reworded?").toBeTruthy();

  expect(sentence ?? "", "print the count the server reported").toContain("{total} file");
  expect(sentence ?? "", "items.length is a page size, not a number of attachments").not.toContain(
    "{items.length} file"
  );

  expect(source, "PageResult.total is what makes the sentence true").toContain("serverTotal = result.total");
});

test("the older attachments beyond the first page can still be reached", () => {
  const source = SOURCE();

  const request = between(source, 'listResource<MediaFile>("/media"', "});");
  expect(request, "a request that cannot name a page can only ever show the first one").toContain("page,");
  expect(request, "100 is the endpoint's maximum, declared once").toContain("pageSize: PAGE_SIZE");

  expect(source, "say that older files exist").toContain("older file");
  expect(source, "and offer a way to reach them").toContain("setPagesLoaded((current) => current + 1)");
});

test("removing a file moves the count with it", () => {
  const remove = between(SOURCE(), "async function removeMedia", "} catch");
  expect(remove).toContain("setTotal((current) => Math.max(0, current - 1));");
});
