import { test, expect } from "@playwright/test";

import { fieldProvenanceLine } from "../components/designworkshop/FieldProvenance";

/**
 * **THE ONE SENTENCE UNDER EACH BOX SAYING WHERE ITS VALUE CAME FROM.**
 *
 * ── WHY THIS EXISTS AT ALL, WHICH IS THE UNCOMFORTABLE PART ──────────────────────────────────────
 *
 * The server has carried per-field authorship for a while. `DwFieldStamp` and `DwStageProvenance`
 * were declared in `lib/designWorkshops.ts` with **zero consumers**; Android's `DwFieldStampDto` had
 * only a wire test calling it; and `GET /design-workshops/{id}/provenance` had no client at all. So
 * the requirement that provenance "stays with the original author unless a field is edited" was
 * true of the database and invisible to every person using the product — which is the same as not
 * having it, since the entire value of knowing a colleague changed the price is a person reading it
 * on the screen where the price is.
 *
 * ── WHY IT IS A UNIT SPEC AND NOT A STAGE-FORM WALKTHROUGH ───────────────────────────────────────
 *
 * Getting a real stamp onto a real box needs a workshop, a stage, an entry the server has already
 * seen, and a second account to have edited one field of it — minutes of fixture, admin
 * credentials, and rows left behind. What is actually worth pinning is the DECISION: given a stamp,
 * which of the two sentences is right. That is a pure function, so it is tested as one. The
 * threading (store → page → grid → control) is covered by the type-checker, which is the correct
 * tool for "is this prop connected".
 *
 * ── THE CASE THIS FILE EXISTS FOR, AND THE MISTAKE IT CAUGHT ─────────────────────────────────────
 *
 * The server writes exactly two sources and they are mutually exclusive. `reference` means hydration
 * wrote the value from a shared record, and its `by` is THAT RECORD's author. `designer` means a
 * person on this workshop typed or changed it. Rule 3 in `entry_provenance.py` REPLACES the whole
 * stamp on an edit, so "copied from a record and then edited here" is not a state that occurs.
 *
 * The first version of the component rendered a `reference` stamp carrying a name as
 * "Sita Devi — edited here" — a sentence about somebody who never opened the workshop, since Sita
 * Devi is whoever recorded the artisan one table away. Attributing an edit to a person who did not
 * make it is worse than saying nothing, and it is the kind of wrongness that reads perfectly.
 */

test.describe("the attribution line under a stage field", () => {
  test("a value nobody has stamped says nothing at all", () => {
    // An unstamped field is the ordinary case on every row written before this column existed. A
    // line reading "unknown" under forty boxes would be noise that trains designers to stop reading
    // the label at all — at which point it cannot do its job on the rows that DO carry an author.
    expect(fieldProvenanceLine(undefined)).toBe("");
    expect(fieldProvenanceLine(null)).toBe("");
    expect(fieldProvenanceLine({})).toBe("");
    // A designer-sourced stamp with no author is the same state and must also stay silent.
    expect(fieldProvenanceLine({ source: "designer", at: "2026-08-14T09:00:00Z" })).toBe("");
  });

  test("a hydrated value names the RECORD, and its person is that record's author", () => {
    // THE ASSERTION THIS FILE EXISTS FOR. `source: "reference"` means hydration wrote the value from
    // a shared record, and `by` is THAT RECORD's author — not anybody who touched this workshop
    // (entry_provenance.py, rule 1). An earlier version of this component rendered exactly this
    // stamp as "Sita Devi — edited here", which is a sentence about a person who never opened the
    // workshop: Sita Devi recorded the artisan, one table away. Attributing an edit to somebody who
    // did not make it is worse than showing nothing at all.
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
    // The record answers even when the account that recorded it does not, so the clause is dropped
    // rather than replaced with a cuid or with the word "unknown".
    const line = fieldProvenanceLine({ source: "reference", refModel: "ToolDocumentation", by: "usr_x" });
    expect(line).toBe("From the tool record");
    expect(line).not.toContain("usr_x");
  });

  test("a hydrated value with no author at all names the record", () => {
    expect(fieldProvenanceLine({ source: "reference", refModel: "Craft" })).toBe("From the craft record");
  });

  test("a value somebody typed here is attributed to them, with the day", () => {
    expect(
      fieldProvenanceLine({ source: "designer", by: "usr_1", byName: "Ravi Kumar", at: "2026-08-14T09:00:00Z" })
    ).toMatch(/^Ravi Kumar, \d+ \w+$/);
  });

  test("typing over a hydrated field reads as the designer's, with no trace of the record", () => {
    // Rule 3 REPLACES the whole stamp, so this is what the server actually sends once a designer
    // types over a hydrated value: a plain designer stamp with no refModel. It must not mention the
    // record, because the record is no longer the source of what is in the box.
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
    // The API is entitled to send a shape this build does not expect; `new Date("soon")` is NaN and
    // `toLocaleDateString` on it renders the words "Invalid Date" straight onto the form.
    expect(fieldProvenanceLine({ source: "designer", by: "usr_1", byName: "Ravi Kumar", at: "soon" }))
      .toBe("Ravi Kumar");
  });
});
