import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  MEDIA_PICKER_PAGE_SIZE,
  acceptRepositoryPicks,
  capCeilingClause,
  mediaPickerNoun,
  mediaPickerNotice,
  mediaPickerOptions,
  mediaPickerTypeFilter,
  repositoryEntitlementNotice,
  repositoryRefusalSentence
} from "@/components/media/mediaPicker";
import { RENDER_CAP } from "@/components/ui/selectFilter";
import type { MediaFile, MediaType } from "@/lib/types";

const ROW = (over: Partial<MediaFile> = {}): MediaFile => ({
  id: "med_1",
  originalFilename: "loom.jpg",
  mediaType: "IMAGE",
  mimeType: "image/jpeg",
  sizeBytes: 12345,
  objectKey: "media/loom.jpg",
  url: "https://example.invalid/loom.jpg",
  status: "APPROVED",
  createdAt: "2026-08-20T09:30:00.000Z",
  ...over
});

test("the picker asks for RENDER_CAP rows, not the 100 GET /media will serve", () => {
  expect(MEDIA_PICKER_PAGE_SIZE).toBe(RENDER_CAP);
  expect(MEDIA_PICKER_PAGE_SIZE).toBeLessThan(100);
});

test("the narrowing is derived from the capture card's own allowedTypes", () => {
  expect(mediaPickerTypeFilter(["IMAGE"])).toBe("IMAGE");
  expect(mediaPickerTypeFilter(["AUDIO"])).toBe("AUDIO");
  expect(mediaPickerTypeFilter(["VIDEO"])).toBe("VIDEO");
});

test("no list, and more than one kind, both mean ask for everything", () => {
  expect(mediaPickerTypeFilter(undefined)).toBeNull();
  expect(mediaPickerTypeFilter([])).toBeNull();
  expect(mediaPickerTypeFilter(["IMAGE", "VIDEO"] as MediaType[])).toBeNull();
});

test("the noun follows the narrowing, in both numbers", () => {
  expect(mediaPickerNoun("IMAGE")).toEqual({ singular: "photograph", plural: "photographs" });
  expect(mediaPickerNoun("AUDIO")).toEqual({ singular: "audio recording", plural: "audio recordings" });
  expect(mediaPickerNoun("OTHER")).toEqual({ singular: "file", plural: "files" });
  expect(mediaPickerNoun(null)).toEqual({ singular: "file", plural: "files" });
});

test("a row already attached is listed and disabled, never hidden", () => {
  const options = mediaPickerOptions({
    rows: [ROW({ id: "a" }), ROW({ id: "b", originalFilename: "warp.jpg" })],
    attachedIds: ["a"]
  });
  expect(options.map((option) => option.value)).toEqual(["a", "b"]);
  expect(options[0].disabled).toBe(true);
  expect(options[0].hint).toContain("already attached");
  expect(options[1].disabled).toBeFalsy();
});

test("the label is the caption where there is one, and the filename otherwise", () => {
  const options = mediaPickerOptions({
    rows: [ROW({ id: "a", caption: "Pit loom, Bagru" }), ROW({ id: "b", caption: "   " })],
    attachedIds: []
  });
  expect(options[0].label).toBe("Pit loom, Bagru");
  expect(options[1].label).toBe("loom.jpg");
});

test("a row whose url was withheld says so, and is still pickable", () => {
  const options = mediaPickerOptions({ rows: [ROW({ id: "a", url: null })], attachedIds: [] });
  expect(options[0].hint).toContain("stored, but this account may not open the file itself");
  expect(options[0].disabled).toBeFalsy();
});

const NOTICE = (over: Partial<Parameters<typeof mediaPickerNotice>[0]> = {}) =>
  mediaPickerNotice({
    loading: false,
    problem: null,
    query: "",
    shown: 80,
    total: 80,
    typeFilter: "IMAGE",
    ...over
  });

test("a truncated list names both figures and the box that reaches the rest", () => {
  const notice = NOTICE({ shown: 80, total: 214 });
  expect(notice).toContain("80");
  expect(notice).toContain("214");
  expect(notice).toContain("search box above");
  expect(notice).toContain("every photograph you may read");
  expect(notice).not.toContain("Keep typing");
});

test("a complete list says so rather than borrowing the truncation sentence", () => {
  expect(NOTICE({ shown: 12, total: 12 })).toContain("All 12 photographs you may read are listed.");
  expect(NOTICE({ shown: 1, total: 1 })).toContain("The one photograph you may read is listed.");
});

test("a search in flight does not read as an empty repository", () => {
  expect(NOTICE({ loading: true, shown: 0, total: 0 })).toBe(
    "Searching the repository for photographs…"
  );
});

test("a search in flight over the PREVIOUS answer says which question the rows answer", () => {
  const notice = NOTICE({ loading: true, shown: 12, total: 12, query: "warp" });
  expect(notice).toContain("Searching the repository…");
  expect(notice).toContain("the 12 photographs listed below are the answer to the previous search");
  expect(notice).not.toContain("All 12");
});

test("nothing matched and nothing exists are two different sentences", () => {
  const matched = NOTICE({ query: "warp", shown: 0, total: 0 });
  const exists = NOTICE({ query: "", shown: 0, total: 0 });
  expect(matched).toContain("warp");
  expect(matched).toContain("This search covers every");
  expect(exists).toBe("The repository holds no photograph you may read yet.");
  expect(exists).not.toContain("matches");
});

test("a failed search is reported instead of an empty list", () => {
  expect(NOTICE({ problem: "The repository could not be searched: offline", shown: 0, total: 0 })).toBe(
    "The repository could not be searched: offline"
  );
});

test("entitlement is silent when every row carries its url", () => {
  expect(repositoryEntitlementNotice([ROW({ id: "a" }), ROW({ id: "b" })])).toBeNull();
  expect(repositoryEntitlementNotice([])).toBeNull();
});

test("withheld urls are counted and explained without discouraging the pick", () => {
  const one = repositoryEntitlementNotice([ROW({ id: "a", url: null }), ROW({ id: "b" })]);
  expect(one).toContain("1 of the 2 listed is stored");
  expect(one).toContain("may not open the file itself");
  expect(one).toContain("can still be attached");
  expect(one).toContain("checked each time it is opened");

  const many = repositoryEntitlementNotice([ROW({ id: "a", url: null }), ROW({ id: "b", url: undefined })]);
  expect(many).toContain("2 of the 2 listed are stored");
  expect(many).toContain("may not open the files themselves");
});

test("a declared ceiling is named and reconciled", () => {
  expect(capCeilingClause({ label: "Traditional motif photographs", declaredCap: 20, accounted: 20 })).toBe(
    "Traditional motif photographs holds at most 20 files, and 20 are accounted for"
  );
  expect(capCeilingClause({ label: "Sanction order", declaredCap: 1, accounted: 1 })).toBe(
    "Sanction order holds at most 1 file, and 1 is accounted for"
  );
});

test("an UNDECLARED ceiling prints no number at all", () => {
  const clause = capCeilingClause({ label: "Process photographs", declaredCap: null, accounted: 200 });
  expect(clause).toBe("Process photographs is full");
  expect(clause).not.toMatch(/\d/);
});

test("the ceiling takes what fits and hands back the rest", () => {
  const picks = ["a", "b", "c", "d", "e"];
  expect(acceptRepositoryPicks({ picked: picks, room: 5 })).toEqual({ attach: picks, refused: [] });
  expect(acceptRepositoryPicks({ picked: picks, room: 2 })).toEqual({
    attach: ["a", "b"],
    refused: ["c", "d", "e"]
  });
  expect(acceptRepositoryPicks({ picked: picks, room: 0 })).toEqual({ attach: [], refused: picks });
});

test("a value already over its ceiling refuses everything rather than slicing negative", () => {
  expect(acceptRepositoryPicks({ picked: ["a", "b"], room: -3 })).toEqual({
    attach: [],
    refused: ["a", "b"]
  });
});

test("the refusal names the files and tells the reader where they still are", () => {
  const sentence = repositoryRefusalSentence({
    label: "Traditional motif photographs",
    declaredCap: 20,
    accounted: 20,
    refusedNames: ["warp.jpg", "weft.jpg"]
  });
  expect(sentence).toContain("warp.jpg, weft.jpg");
  expect(sentence).toContain("holds at most 20 files");
  expect(sentence).toContain("press Attach again");
  expect(sentence).toContain("still ticked in the repository list");
  expect(sentence).not.toContain("above");
  expect(sentence).not.toContain("pick them again");
});

test("an undeclared ceiling still refuses out loud, and still names the files", () => {
  const sentence = repositoryRefusalSentence({
    label: "Process photographs",
    declaredCap: null,
    accounted: 200,
    refusedNames: ["dye.jpg"]
  });
  expect(sentence).toContain("Process photographs is full");
  expect(sentence).toContain("dye.jpg");
  expect(sentence).not.toMatch(/\b200\b/);
  expect(repositoryRefusalSentence({ label: "x", declaredCap: 20, accounted: 1, refusedNames: [] })).toBeNull();
});

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

test("the picker's request uses the shared page size and the server's own search", () => {
  const source = read("components", "media", "MediaRepositoryPicker.tsx");
  const request = between(source, 'listResource<MediaFile>("/media"', "})");
  expect(request).toContain("search: query.trim() || null");
  expect(request).toContain("pageSize: MEDIA_PICKER_PAGE_SIZE");
  expect(request).not.toMatch(/pageSize:\s*\d/);
});

test("both panels turn their own filter box off and name the box that reaches the rest", () => {
  const markup = between(read("components", "media", "MediaRepositoryPicker.tsx"), "if (disabled) return null;", "\n}");
  expect(markup.match(/searchable=\{false\}/g)?.length).toBe(2);
  expect(markup.match(/capHint="Use the search box above/g)?.length).toBe(2);
});

test("FieldInput mounts the picker with the enforced ceiling and the printable one kept apart", () => {
  const source = read("components", "designworkshop", "FieldInput.tsx");
  const mount = between(source, "<MediaRepositoryPicker", "/>");
  expect(mount).toContain("room={room}");
  expect(mount).toContain("declaredCap={declaredCap}");
  expect(mount).toContain("typeFilter={mediaPickerTypeFilter(ALLOWED_TYPES[field.type])}");
  expect(source).toContain("allowedTypes={ALLOWED_TYPES[field.type]}");
});

test("a stored id is never cleared, and a withheld url is never drawn as a broken frame", () => {
  const source = read("components", "designworkshop", "FieldInput.tsx");
  expect(source).toContain("{file && !file.url ? (");
  expect(source).toContain("You don&apos;t have access to this file");
  const commit = between(source, "function attachExisting", "\n  }");
  expect(commit).not.toContain(".url");
});

test("the two media refusals share the ceiling clause and only the ceiling clause", () => {
  const source = read("components", "designworkshop", "FieldInput.tsx");
  const notice = between(source, "const refusalNotice = (() => {", "})();");
  expect(notice).toContain("capCeilingClause({ label: field.label, declaredCap: printableCeiling, accounted })");
  expect(notice).toContain("const printableCeiling = multiple ? declaredMaxItems(field) : null;");
  expect(source).toContain("const declaredCap = multiple ? declaredMaxItems(field) : null;");
  expect(notice).not.toContain("holds at most");
  expect(notice).not.toContain("is full`");
  expect(notice).toContain("this list stays until you do");
});
