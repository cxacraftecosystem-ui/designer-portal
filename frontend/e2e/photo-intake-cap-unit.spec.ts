import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { DW_DEFAULT_MAX_ITEMS, type DwRegistry } from "@/lib/designWorkshops";
import { appendMediaRef, effectiveMaxItems, mediaRefRoom, photoTargets } from "@/lib/photoIntake";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const PHOTOS_PAGE = () => read("app", "(protected)", "design-workshops", "[id]", "photos", "page.tsx");
const UPLOAD_HOST = () => read("components", "sketches", "UploadTabHost.tsx");

function between(source: string, from: string, to: string): string {
  const start = source.indexOf(from);
  expect(start, `${from} not found — has the file been restructured?`).toBeGreaterThan(-1);
  const end = source.indexOf(to, start);
  expect(end, `${to} not found after ${from}`).toBeGreaterThan(-1);
  return source.slice(start, end);
}

const refs = (n: number) => Array.from({ length: n }, (_, index) => `dwlocal:ref-${index}`);

test("an undeclared ceiling is the server's default, not the absence of one", () => {
  expect(DW_DEFAULT_MAX_ITEMS).toBe(200);
  expect(effectiveMaxItems(undefined)).toBe(DW_DEFAULT_MAX_ITEMS);
  expect(effectiveMaxItems(0)).toBe(DW_DEFAULT_MAX_ITEMS);
  expect(effectiveMaxItems(-3)).toBe(DW_DEFAULT_MAX_ITEMS);
  expect(effectiveMaxItems(20)).toBe(20);
});

test("a declared ceiling stops the file after it, and says so by refusing to grow", () => {
  const full = refs(20);
  expect(mediaRefRoom(full, true, 20)).toBe(0);
  expect(appendMediaRef(full, "dwlocal:one-more", true, 20)).toEqual(full);

  const nearly = refs(19);
  expect(mediaRefRoom(nearly, true, 20)).toBe(1);
  expect(appendMediaRef(nearly, "dwlocal:one-more", true, 20)).toEqual([...nearly, "dwlocal:one-more"]);
});

test("an undeclared gallery is capped at two hundred rather than left open", () => {
  const full = refs(DW_DEFAULT_MAX_ITEMS);
  expect(mediaRefRoom(full, true, undefined)).toBe(0);
  expect(appendMediaRef(full, "dwlocal:two-hundred-and-one", true, undefined)).toEqual(full);
  expect(appendMediaRef(full, "dwlocal:two-hundred-and-one", true, undefined)).toHaveLength(200);
});

test("a reference the field already holds is a no-op, not a refusal, even at the ceiling", () => {
  const full = refs(20);
  expect(mediaRefRoom(full, true, 20)).toBe(0);
  expect(appendMediaRef(full, full[0], true, 20)).toEqual(full);
});

test("a single-valued field replaces and therefore always has room", () => {
  expect(mediaRefRoom("dwlocal:already-there", false, undefined)).toBe(1);
  expect(appendMediaRef("dwlocal:already-there", "dwlocal:new", false, undefined)).toBe("dwlocal:new");
});

test("junk in a stored list is not counted as a photograph", () => {
  const value = ["dwlocal:a", 7, null, "dwlocal:b"] as unknown as string[];
  expect(mediaRefRoom(value, true, 3)).toBe(1);
  expect(appendMediaRef(value, "dwlocal:c", true, 3)).toEqual(["dwlocal:a", "dwlocal:b", "dwlocal:c"]);
});

const REGISTRY: DwRegistry = {
  version: "test",
  stages: [
    {
      key: "DESIGN_BRIEF",
      number: 2,
      title: "Design brief",
      entities: [
        {
          key: "brief",
          title: "Brief",
          cardinality: "SINGLETON",
          fields: [
            { key: "notes", label: "Notes", type: "TEXT", tier: "BASIC", required: false },
            {
              key: "motifPhotos",
              label: "Motif photographs",
              type: "IMAGE_LIST",
              tier: "BASIC",
              required: false,
              maxItems: 20
            },
            { key: "logPhotos", label: "Photographs", type: "IMAGE_LIST", tier: "BASIC", required: false },
            { key: "coverPhoto", label: "Cover photograph", type: "IMAGE", tier: "STANDARD", required: false }
          ]
        }
      ]
    }
  ]
} as unknown as DwRegistry;

test("a target carries the ceiling its field declared, and the silence where it declared none", () => {
  const targets = photoTargets(REGISTRY, "DESIGN_BRIEF", "brief");
  expect(targets.map((target) => target.fieldKey)).toEqual(["motifPhotos", "logPhotos", "coverPhoto"]);
  expect(targets[0].maxItems).toBe(20);
  expect(targets[1].maxItems).toBeUndefined();
  expect(targets[2].maxItems).toBeUndefined();
  expect(mediaRefRoom(refs(200), targets[1].multiple, targets[1].maxItems)).toBe(0);
});

test("the bulk import asks for room before it copies a byte, and counts only what landed", () => {
  const source = PHOTOS_PAGE();
  const confirm = between(source, "async function confirm()", "const summary = useMemo(() => intakeSummary(");

  const askedFirst = confirm.indexOf("mediaRefRoom(");
  const copied = confirm.indexOf("await copyToDevice()");
  expect(askedFirst).toBeGreaterThan(-1);
  expect(copied).toBeGreaterThan(askedFirst);

  expect(confirm.match(/destination\.maxItems/g)?.length).toBeGreaterThanOrEqual(4);

  expect(confirm).toContain(
    "full.push({ file: line.file.name, label: destination.label, declaredCap: destination.maxItems })"
  );
  expect(confirm).toContain("headed for ${because}: ");
  expect(confirm).toMatch(/:\s*"is full"/);

  expect(confirm).toContain("declared && declared > 0");
  expect(confirm).toContain("holds at most ${declared}");
  expect(confirm).not.toContain("DW_DEFAULT_MAX_ITEMS");
  expect(confirm).not.toContain("at most 200");

  expect(confirm).toContain("${touched.size} stage");
  expect(confirm).not.toContain("${pending.size} stage");
});

test("the upload tab counts frames the field took, not frames it was handed", () => {
  const source = UPLOAD_HOST();
  const attach = between(source, "const attach = useCallback(", "const sketchEntity =");

  expect(attach).toContain("what: (landedCount: number) => string");
  expect(attach).toContain("${what(took)} attached to");
  expect(attach).not.toContain("${what} attached to");

  expect(attach).toContain('took === 1 ? "this file is" : "these files are"');
  expect(attach).not.toContain('files.length === 1 ? "this file is"');

  expect(attach).toContain("if (!mediaRefRoom(row[target.fieldKey], target.multiple, target.maxItems))");
  expect(attach).toContain("turnedAway.push(file.name)");

  expect(attach).toContain("if (took === 0)");

  expect(attach).toContain("return turnedAway.length === 0;");
});

test("every destination the upload tab writes to carries its field's declared ceiling", () => {
  const source = UPLOAD_HOST();
  expect(source).toContain("maxItems: lineArt.maxItems");
  expect(source).toContain("maxItems: sketchImage.maxItems");
  expect(source).toContain("maxItems: model.maxItems");
  expect(source).toContain("maxItems: turntable.maxItems");
});
