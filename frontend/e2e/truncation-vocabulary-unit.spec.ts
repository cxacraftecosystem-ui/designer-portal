import { expect, test } from "@playwright/test";

import {
  cappedListNotice,
  cutOf,
  CUT_NOTICE_LIVE_REGION,
  searchCutNotice,
  type CutReach
} from "@/components/data/cappedList";

/**
 * THREE FACTS ABOUT ONE LIST THAT MUST NEVER SHARE A SENTENCE.
 *
 * The moment a control's search box reaches the SERVER instead of sifting the array it was handed
 * (DROPDOWN_DESIGN §2.8), one page of a list stops being one fact and becomes three:
 *
 *   1. there are 400 and you are seeing 80
 *   2. nothing matched what you typed
 *   3. there are none at all
 *
 * A reader cannot tell them apart from the rows — all three draw an incomplete or empty list — so
 * the only thing that distinguishes them is the sentence underneath, and every pairwise collapse
 * has already shipped in this repository. (1) into (2) is a filter box over a server-truncated page
 * answering "No matches" about records that exist: sixteen controls, and the 353 eligible accounts
 * the design-workshop viewer picker hid until 2026-08-13. (2) into (3) is `"No crafts available."`
 * (`MainActivity.kt:10125`) — a claim about the repository made from a read that had a term in it,
 * or that failed. (3) into (2) is the mirror and sends an admin hunting for a record nobody created.
 *
 * WHY A NODE SPEC. `components/data/cappedList.ts` is deliberately pure, for the reason its own
 * header gives: one of its states cannot be produced by any live database, so a decision buried in
 * JSX is only ever exercised by somebody looking at a screen. This file calls it. What it cannot
 * prove is that a browser paints the sentence — that belongs in a signed-in spec against a real
 * database, and cannot be written honestly against a fixture, because the whole defect is a table
 * growing past a number and a fixture is a list somebody chose the length of.
 *
 * Companion to `capped-lists-unit.spec.ts`, which pins the vocabulary as it was before the
 * `"search"` arm existed. Every assertion there must still pass: this pass adds capability and
 * moves nothing.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The new arm — and the guard that decides who may pass it
 * ──────────────────────────────────────────────────────────────────────────── */

test("a cut list with a server-backed box points at the box and says the server does the searching", () => {
  const sentence = cappedListNotice(cutOf(80, 196, "design workshops"), "search");

  // BOTH NUMBERS, as every arm prints both. "Showing the first 80" alone leaves the reader guessing
  // whether that is most of the corpus or a fifth of it, and the difference is whether they go
  // looking elsewhere or conclude the workshop was never recorded. 196 is the live Workshop count.
  expect(sentence).toContain("80");
  expect(sentence).toContain("196");
  expect(sentence).toContain("design workshops");

  // A cap notice with no next action tells the reader they have a problem and not what to do about
  // it. This arm exists to name the control that reaches the rest.
  expect(sentence).toContain("type in the box above");

  // THE LOAD-BEARING CLAUSE. "Type in the box above" alone is something every locally-filtering
  // picker in this app could have said truthfully, and sixteen of them effectively did — so it is
  // not the half that tells this box apart from those. A reader trained by those controls to
  // distrust a search box has to be told, in the sentence, that this one goes to the server.
  expect(sentence).toContain("searched on the server");

  // §2.8's wording, byte for byte, because three other parcels and their specs are written against
  // the design document and a paraphrase here fails them for no reason a reader could see.
  expect(sentence).toBe(
    "Showing 80 of 196 design workshops — type in the box above to reach the rest, which are searched on the server."
  );
});

test("the arm is opt-in: every caller that passes no reach renders exactly what it rendered before", () => {
  const cut = cutOf(100, 749, "artisans");

  // 19 `listCut` call sites and six `<CappedListNotice>` sites pass no `reach`. The default is the
  // arm they have always had — one page, a box that filters it locally, no second page — and if
  // adding `"search"` had moved it, every record picker in the web forms would have started telling
  // its reader to type into a box that cannot reach past the cut. That is the defect being closed,
  // reintroduced by the fix.
  expect(cappedListNotice(cut)).toBe(cappedListNotice(cut, "none"));
  expect(cappedListNotice(cut)).toContain("typing here searches only the 100 shown");
  expect(cappedListNotice(cut)).not.toContain("server");

  // And the pager arm is untouched — View Data's browse panel is still the one site that says it.
  expect(cappedListNotice(cutOf(100, 2530, "media"), "pager")).toContain("use the pager");
  expect(cappedListNotice(null, "search")).toBe("");
});

test("the three reaches are three different sentences, no two of them interchangeable", () => {
  // One list, one cut, three controls: nothing on screen / a pager / a server-backed box. A reader
  // who is told to page when there is no pager, or to type when typing cannot reach, is being sent
  // to a control that does not do what the sentence says — which is worse than an admitted limit,
  // because they conclude the screen is broken rather than that the list is capped.
  const cut = cutOf(80, 400, "designers");
  const said = (["none", "pager", "search"] as CutReach[]).map((reach) => cappedListNotice(cut, reach));
  expect(new Set(said).size, "two reaches wording one cut the same way is a control named wrongly").toBe(3);
  for (const sentence of said) {
    expect(sentence, "rule (iii): the number is stated on screen").toContain("400");
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * The three sentences
 * ──────────────────────────────────────────────────────────────────────────── */

test("a cut, a term that matched nothing, and an empty list are three different sentences", () => {
  const facts = { noun: "designers", term: "", emptyLabel: "No designers have been recorded yet." };

  const capped = searchCutNotice({ ...facts, loaded: 80, total: 400 });
  const noMatch = searchCutNotice({ ...facts, loaded: 0, total: 0, term: "ravi" });
  const none = searchCutNotice({ ...facts, loaded: 0, total: 0 });

  expect(new Set([capped, noMatch, none]).size, "collapsing any two of these is the defect").toBe(3);

  // 1. There are 400 and you are seeing 80.
  expect(capped).toContain("80");
  expect(capped).toContain("400");

  // 2. Nothing matched what you typed — and the term is quoted back, because a debounced box can
  //    answer a query the reader has already typed past, and seeing which term the answer is about
  //    is how they tell a stale panel from a real absence.
  expect(noMatch).toContain("ravi");
  expect(noMatch, "the claim is only honest because the term went to the server").toContain("on the server");
  expect(noMatch, "a next action, or the reader is stuck in a list they cannot leave").toContain("Clear the box");
  expect(noMatch, "this is not a claim about the repository").not.toContain("recorded yet");

  // 3. There are none at all — the caller's sentence, printed verbatim and composed with nothing.
  expect(none).toBe("No designers have been recorded yet.");
});

test("a cut the reader has already searched into does not tell them to search", () => {
  const sentence = searchCutNotice({ noun: "workshops", loaded: 80, total: 260, term: "  bagru  " });

  // The cut is now a cut OF THE MATCHES, and telling somebody to search when they already have is
  // how a picker teaches a reader that searching does not work — `flagCutNotice` makes the identical
  // split for the routes that report a boolean, and these two must stay one voice.
  expect(sentence).toContain("80");
  expect(sentence).toContain("260");
  expect(sentence, "the term is quoted back, trimmed").toContain("“bagru”");
  expect(sentence).toContain("narrow the search above");
  expect(sentence).not.toContain("type in the box above");
});

test("nothing loaded while the server says rows match is never reported as no matches", () => {
  // THE STATE NO LIVE DATABASE PRODUCES, tested because it is the one where silence — or the wrong
  // sentence — does the most damage. `total > 0` is the server saying these records DO match; "No
  // designers match “ravi”" over it is flatly false, and it is the false half of the collapse this
  // whole module exists to prevent.
  const sentence = searchCutNotice({ noun: "designers", loaded: 0, total: 431, term: "ravi" });
  expect(sentence).toContain("431");
  expect(sentence).toContain("this is not an empty repository");
  expect(sentence, "no control on screen reaches those rows, so none is named").not.toContain("Clear the box");
  expect(sentence).not.toContain("No designers match");
  // One wording for one fact: `cappedListNotice`'s first arm owns it and is reached from here.
  expect(sentence).toBe(cappedListNotice(cutOf(0, 431, "designers")));
});

test("a whole list says nothing, with or without a term", () => {
  // Silence is the common answer and the correct one. A standing note about searching under every
  // complete list is what makes the sentence mean nothing on the day it does appear — and this
  // screen's owner has twice asked for less text, not more.
  expect(searchCutNotice({ noun: "crafts", loaded: 12, total: 12, term: "" })).toBe("");
  expect(searchCutNotice({ noun: "crafts", loaded: 3, total: 3, term: "block" })).toBe("");
  // A list exactly as long as the page it was fetched into is not assumed to be cut.
  expect(searchCutNotice({ noun: "crafts", loaded: 80, total: 80, term: "" })).toBe("");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The defaults, which are what make this additive
 * ──────────────────────────────────────────────────────────────────────────── */

test("the optional arguments default to what every caller already assumed", () => {
  // `emptyLabel` defaults to silence: before this function existed, a caller with nothing loaded and
  // nothing typed got `cappedListNotice(null)`, which is "", and drew its own `EmptyState` or its
  // picker's `emptyLabel` above. Passing nothing must keep doing exactly that.
  expect(searchCutNotice({ noun: "designers", loaded: 0, total: 0, term: "" })).toBe("");

  // `pending` defaults to "the answer in hand is the answer", which is what every caller of this
  // module has always been.
  expect(searchCutNotice({ noun: "designers", loaded: 80, total: 400, term: "" })).toBe(
    searchCutNotice({ noun: "designers", loaded: 80, total: 400, term: "", pending: false })
  );
});

test("an empty list mid-flight is not an empty list", () => {
  // The window is a second and a half wide on the connections this app is built for, and inside it
  // a debounced box holds zero rows for a term the server has not answered yet. Printing "No
  // designers match “ravi”" there is a claim made from a read that has not finished — the same
  // most-repeated bug class arriving through a door that only a server-backed box has.
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
  // `apiFetch` is a plain cast, not a schema parse, so a deployment predating the field puts
  // `undefined` here at runtime. `searchCutNotice` goes through `cutOf` for exactly this guard.
  const total = undefined as unknown as number;
  expect(searchCutNotice({ noun: "designers", loaded: 20, total, term: "" })).toBe("");
  expect(searchCutNotice({ noun: "designers", loaded: 20, total, term: "ravi" })).toBe("");
  // With nothing loaded the term still gets its honest answer — zero rows came back for it.
  expect(searchCutNotice({ noun: "designers", loaded: 0, total, term: "ravi" })).toContain("No designers match");
});

/* ────────────────────────────────────────────────────────────────────────────
 * Announced, not merely drawn
 * ──────────────────────────────────────────────────────────────────────────── */

test("the notice is announced through a region that exists before it has anything to say", () => {
  // Assistive technology announces mutations inside a region that ALREADY EXISTED when the page
  // settled. `CappedListNotice` returns `null` when there is nothing to say — correctly, an empty
  // box under every complete picker is padding this UI has twice been asked to lose — so it can
  // never be the region itself, and the wrapper has to be the caller's. `EntityForm`'s cap notice
  // shipped the other way round and a designer using a screen reader heard nothing at all.
  expect(CUT_NOTICE_LIVE_REGION.role).toBe("status");
  // Polite, never assertive: nothing is broken and nothing has been lost. These sentences say what
  // is not on screen, and interrupting somebody mid-word over a list that stopped at eighty is not
  // warranted — the same judgement `Toast` makes and `DraftSyncBanner` deliberately does not.
  expect(CUT_NOTICE_LIVE_REGION["aria-live"]).toBe("polite");
});
