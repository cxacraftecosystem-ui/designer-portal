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
 * which of the three sentences is right. That is a pure function, so it is tested as one. The
 * threading (store → page → grid → control) is covered by the type-checker, which is the correct
 * tool for "is this prop connected".
 *
 * ── THE CASE THE WHOLE MODEL TURNS ON IS THE THIRD ONE ───────────────────────────────────────────
 *
 * A stamp naming ONLY a record is a value that was copied and never touched — the record is the
 * author. A stamp naming ONLY a person is a value somebody typed. A stamp naming BOTH is a value
 * that arrived from the record and was then changed here, and that is precisely the moment
 * provenance moves from the record's author to the editor. If a reader cannot tell the second from
 * the third at a glance, the distinction may as well not be stored — so it is asserted explicitly,
 * and asserted as a DIFFERENCE rather than as two strings that happen to be right today.
 */

test.describe("the attribution line under a stage field", () => {
  test("a value nobody has stamped says nothing at all", () => {
    // An unstamped field is the ordinary case on a stage nobody has pushed yet. A line reading
    // "unknown" under forty boxes would be noise on every new workshop.
    expect(fieldProvenanceLine(undefined)).toBe("");
    expect(fieldProvenanceLine(null)).toBe("");
    expect(fieldProvenanceLine({})).toBe("");
  });

  test("a value copied from a record and never touched is attributed to the record", () => {
    const line = fieldProvenanceLine({ source: "reference", refModel: "Artisan", refId: "art_1" });
    expect(line).toBe("From the artisan record");
    // The record is named in the words a designer uses for it, never as a Prisma model name.
    expect(line).not.toContain("ProductDocumentation");
  });

  test("a value somebody typed is attributed to them, with the day", () => {
    expect(fieldProvenanceLine({ by: "usr_1", byName: "Sita Devi", at: "2026-08-14T09:00:00Z" }))
      .toMatch(/^Sita Devi, \d+ \w+$/);
  });

  test("a value that was copied and THEN edited says both, and is not the same as either", () => {
    const copied = fieldProvenanceLine({ source: "reference", refModel: "Artisan" });
    const typed = fieldProvenanceLine({ by: "usr_1", byName: "Sita Devi", at: "2026-08-14T09:00:00Z" });
    const edited = fieldProvenanceLine({
      source: "reference",
      refModel: "Artisan",
      by: "usr_1",
      byName: "Sita Devi",
      at: "2026-08-14T09:00:00Z"
    });

    expect(edited).toContain("Sita Devi");
    expect(edited).toContain("edited here");
    // The assertions that matter: it is DISTINGUISHABLE from both of the other two. Two of these
    // rendering the same sentence is the failure, and it would not be caught by checking any one of
    // them in isolation.
    expect(edited).not.toBe(copied);
    expect(edited).not.toBe(typed);
  });

  test("a deleted account is named as a person, never as a cuid", () => {
    // The stamp keeps its `by` id when the account goes, because "attributed to somebody no longer
    // on record" is more useful than dropping the attribution. Printing the id would tell a designer
    // nothing and read as a bug.
    const line = fieldProvenanceLine({ by: "cmf3k2xyz0000abcd", byName: null, at: "2026-08-14T09:00:00Z" });
    expect(line).toContain("former colleague");
    expect(line).not.toContain("cmf3k2xyz0000abcd");
  });

  test("a stamp with no time prints the author alone rather than inventing one", () => {
    expect(fieldProvenanceLine({ by: "usr_1", byName: "Sita Devi" })).toBe("Sita Devi");
  });

  test("an unparseable timestamp degrades to the author rather than to Invalid Date", () => {
    // The API is entitled to send a shape this build does not expect; `new Date("soon")` is NaN and
    // `toLocaleDateString` on it renders the words "Invalid Date" straight onto the form.
    expect(fieldProvenanceLine({ by: "usr_1", byName: "Sita Devi", at: "soon" })).toBe("Sita Devi");
  });
});
