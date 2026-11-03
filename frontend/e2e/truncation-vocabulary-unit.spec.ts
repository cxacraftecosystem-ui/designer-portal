import { expect, test } from "@playwright/test";

import {
  cappedListNotice,
  cutOf,
  CUT_NOTICE_LIVE_REGION,
  searchCutNotice,
  type CutReach
} from "@/components/data/cappedList";

test("a cut list with a server-backed box points at the box and says the server does the searching", () => {
  const sentence = cappedListNotice(cutOf(80, 196, "design workshops"), "search");

  expect(sentence).toContain("80");
  expect(sentence).toContain("196");
  expect(sentence).toContain("design workshops");

  expect(sentence).toContain("type in the box above");

  expect(sentence).toContain("the search covers all 196");

  expect(sentence).toBe(
    "Showing 80 of 196 design workshops — type in the box above to reach the rest; the search covers all 196."
  );
});

test("the arm is opt-in: every caller that passes no reach renders exactly what it rendered before", () => {
  const cut = cutOf(100, 749, "artisans");

  expect(cappedListNotice(cut)).toBe(cappedListNotice(cut, "none"));
  expect(cappedListNotice(cut)).toContain("typing here searches only the 100 shown");
  expect(cappedListNotice(cut)).not.toContain("server");

  expect(cappedListNotice(cutOf(100, 2530, "media"), "pager")).toContain("use the pager");
  expect(cappedListNotice(null, "search")).toBe("");
});

test("the three reaches are three different sentences, no two of them interchangeable", () => {
  const cut = cutOf(80, 400, "designers");
  const said = (["none", "pager", "search"] as CutReach[]).map((reach) => cappedListNotice(cut, reach));
  expect(new Set(said).size, "two reaches wording one cut the same way is a control named wrongly").toBe(3);
  for (const sentence of said) {
    expect(sentence, "rule (iii): the number is stated on screen").toContain("400");
  }
});

test("a cut, a term that matched nothing, and an empty list are three different sentences", () => {
  const facts = { noun: "designers", term: "", emptyLabel: "No designers have been recorded yet." };

  const capped = searchCutNotice({ ...facts, loaded: 80, total: 400 });
  const noMatch = searchCutNotice({ ...facts, loaded: 0, total: 0, term: "ravi" });
  const none = searchCutNotice({ ...facts, loaded: 0, total: 0 });

  expect(new Set([capped, noMatch, none]).size, "collapsing any two of these is the defect").toBe(3);

  expect(capped).toContain("80");
  expect(capped).toContain("400");

  expect(noMatch).toContain("ravi");
  expect(noMatch, "the claim is only honest because the term went to the server").toContain("anywhere in the full list");
  expect(noMatch, "a next action, or the reader is stuck in a list they cannot leave").toContain("Clear the box");
  expect(noMatch, "this is not a claim about the repository").not.toContain("recorded yet");

  expect(none).toBe("No designers have been recorded yet.");
});

test("a cut the reader has already searched into does not tell them to search", () => {
  const sentence = searchCutNotice({ noun: "workshops", loaded: 80, total: 260, term: "  bagru  " });

  expect(sentence).toContain("80");
  expect(sentence).toContain("260");
  expect(sentence, "the term is quoted back, trimmed").toContain("“bagru”");
  expect(sentence).toContain("narrow the search above");
  expect(sentence).not.toContain("type in the box above");
});

test("nothing loaded while the server says rows match is never reported as no matches", () => {
  const sentence = searchCutNotice({ noun: "designers", loaded: 0, total: 431, term: "ravi" });
  expect(sentence).toContain("431");
  expect(sentence).toContain("but they do exist");
  expect(sentence, "no control on screen reaches those rows, so none is named").not.toContain("Clear the box");
  expect(sentence).not.toContain("No designers match");
  expect(sentence).toBe(cappedListNotice(cutOf(0, 431, "designers")));
});

test("a whole list says nothing, with or without a term", () => {
  expect(searchCutNotice({ noun: "crafts", loaded: 12, total: 12, term: "" })).toBe("");
  expect(searchCutNotice({ noun: "crafts", loaded: 3, total: 3, term: "block" })).toBe("");
  expect(searchCutNotice({ noun: "crafts", loaded: 80, total: 80, term: "" })).toBe("");
});

test("the optional arguments default to what every caller already assumed", () => {
  expect(searchCutNotice({ noun: "designers", loaded: 0, total: 0, term: "" })).toBe("");

  expect(searchCutNotice({ noun: "designers", loaded: 80, total: 400, term: "" })).toBe(
    searchCutNotice({ noun: "designers", loaded: 80, total: 400, term: "", pending: false })
  );
});

test("an empty list mid-flight is not an empty list", () => {
  expect(searchCutNotice({ noun: "designers", loaded: 0, total: 0, term: "ravi", pending: true })).toBe("");
  expect(
    searchCutNotice({
      noun: "designers",
      loaded: 0,
      total: 0,
      term: "",
      pending: true,
      emptyLabel: "No designers have been recorded yet."
    }),
    "and the repository claim is the worse of the two to make early"
  ).toBe("");
});

test("a total the wire did not carry says nothing rather than claiming a cut of NaN", () => {
  const total = undefined as unknown as number;
  expect(searchCutNotice({ noun: "designers", loaded: 20, total, term: "" })).toBe("");
  expect(searchCutNotice({ noun: "designers", loaded: 20, total, term: "ravi" })).toBe("");
  expect(searchCutNotice({ noun: "designers", loaded: 0, total, term: "ravi" })).toContain("No designers match");
});

test("the notice is announced through a region that exists before it has anything to say", () => {
  expect(CUT_NOTICE_LIVE_REGION.role).toBe("status");
  expect(CUT_NOTICE_LIVE_REGION["aria-live"]).toBe("polite");
});
