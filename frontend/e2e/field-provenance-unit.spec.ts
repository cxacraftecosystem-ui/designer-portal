import { test, expect } from "@playwright/test";

import { fieldProvenanceLine } from "../components/designworkshop/FieldProvenance";

test.describe("the attribution line under a stage field", () => {
  test("a value nobody has stamped says nothing at all", () => {
    expect(fieldProvenanceLine(undefined)).toBe("");
    expect(fieldProvenanceLine(null)).toBe("");
    expect(fieldProvenanceLine({})).toBe("");
    expect(fieldProvenanceLine({ source: "designer", at: "2026-08-14T09:00:00Z" })).toBe("");
  });

  test("a hydrated value names the RECORD, and its person is that record's author", () => {
    const line = fieldProvenanceLine({
      source: "reference",
      refModel: "Artisan",
      refId: "art_1",
      by: "usr_9",
      byName: "Sita Devi"
    });
    expect(line).toBe("From the artisan record, by Sita Devi");
    expect(line).not.toContain("edited");
  });

  test("a hydrated value whose record author is gone still names the record", () => {
    const line = fieldProvenanceLine({ source: "reference", refModel: "ToolDocumentation", by: "usr_x" });
    expect(line).toBe("From the tool record");
    expect(line).not.toContain("usr_x");
  });

  test("a hydrated value with no author at all names the record", () => {
    expect(fieldProvenanceLine({ source: "reference", refModel: "Craft" })).toBe("From the craft record");
  });

  test("a value somebody typed here is attributed to them, with the day", () => {
    const line = fieldProvenanceLine({
      source: "designer",
      by: "usr_1",
      byName: "Ravi Kumar",
      at: "2026-08-14T09:00:00Z"
    });
    expect(line).toMatch(/^Ravi Kumar, 14 \w+$/);
    expect(line).not.toMatch(/^Ravi Kumar, \w+ 14$/);
  });

  test("typing over a hydrated field reads as the designer's, with no trace of the record", () => {
    const line = fieldProvenanceLine({
      source: "designer",
      by: "usr_1",
      byName: "Ravi Kumar",
      at: "2026-08-14T09:00:00Z"
    });
    expect(line).not.toContain("record");
    expect(line).toContain("Ravi Kumar");
  });

  test("a deleted editor is named as a person, never as a cuid", () => {
    const line = fieldProvenanceLine({
      source: "designer",
      by: "cmf3k2xyz0000abcd",
      byName: null,
      at: "2026-08-14T09:00:00Z"
    });
    expect(line).toContain("former colleague");
    expect(line).not.toContain("cmf3k2xyz0000abcd");
  });

  test("a stamp with no time prints the author alone rather than inventing one", () => {
    expect(fieldProvenanceLine({ source: "designer", by: "usr_1", byName: "Ravi Kumar" })).toBe("Ravi Kumar");
  });

  test("an unparseable timestamp degrades to the author rather than to Invalid Date", () => {
    expect(fieldProvenanceLine({ source: "designer", by: "usr_1", byName: "Ravi Kumar", at: "soon" }))
      .toBe("Ravi Kumar");
  });
});
