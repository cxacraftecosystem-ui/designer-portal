/**
 * THE MINISTRY DASHBOARD — the pure rules it renders, and the five source properties that keep a
 * self-refreshing register from lying.
 *
 * ── WHY THIS IS A SOURCE-READING UNIT SPEC ──────────────────────────────────────────────────────
 *
 * There is no React renderer in this repository's devDependencies, so the page cannot be mounted;
 * `discarded-work-unit.spec.ts`, `web-surface-gaps-unit.spec.ts` and `dashboard-tile-parity-unit`
 * all read source for the same reason. Everything in `lib/ministryDashboard.ts` that DECIDES
 * anything is a function, and is called directly below. What cannot be a function — "the poll bumps
 * a token rather than fetching", "the table is not a live region" — is asserted as source text,
 * which is the only form those properties have.
 *
 * ── THE FIVE THINGS THAT WOULD BREAK SILENTLY ───────────────────────────────────────────────────
 *
 * Each of the source assertions below is a defect this repository has already paid for once, on
 * another screen:
 *
 *   1. A poll that FETCHES rather than bumping a token gives two write paths into one list state,
 *      and the generation counter then guards only one of them (`MediaJobsPanel`'s note).
 *   2. A failed tick that EMPTIES the rows reports a full national programme as an empty one
 *      (`DeletedWorkshopsCard`: *"A card that emptied itself on a dropped connection would report a
 *      full trash as an empty one."*).
 *   3. A timer-issued request that REDIRECTS ON 401 hard-navigates a reading officer to /login,
 *      losing their scroll and their filters, because of something they did not do (`lib/media.ts`).
 *   4. A self-refreshing table inside a LIVE REGION interrupts a screen-reader user every thirty
 *      seconds forever (`EntityForm`'s three-condition test, which this screen fails all three of).
 *   5. A progress bar animated by framer-motion animates for exactly the readers who asked it not
 *      to, because the two global reduced-motion rules reach CSS and cannot reach inline styles.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DEFAULT_REGISTER_KIND,
  DESIGN_STANDINGS,
  OTHER_STANDINGS,
  REGISTER_KINDS,
  hasProgressFigure,
  progressSentence,
  standingMembersSentence,
  standingsFor,
  unclassifiedSentence,
  type RegisterProgress
} from "@/lib/ministryDashboard";

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const PAGE = read("app/(protected)/ministry-dashboard/page.tsx");
const LIB = read("lib/ministryDashboard.ts");

/**
 * The page WITHOUT its comments, and every assertion about CODE reads this one.
 *
 * ⚠ NOT TIDINESS — IT IS THE ONLY WAY THESE ASSERTIONS CAN MEAN ANYTHING, and this file learned
 * it three times in one sitting. The page explains at length why the table is not a live region, why
 * the bar is not a progressbar and why it is not animated by framer-motion, and a good explanation of
 * a prohibition NAMES the thing prohibited. Read against the raw file, "the cell carries no
 * progressbar role" fails on the paragraph forbidding it: the assertion defeats itself.
 *
 * `ministry-surface-unit.spec.ts` strips comments before reading the ministry CSS block for exactly
 * this reason, and says so: *"that block states its own prohibitions in prose … so a substring
 * check over the raw text fails on the very sentence that forbids what it is hunting for."*
 *
 * Quotes are respected, so a prohibition written inside a STRING — which would be real code —
 * still counts.
 */
function stripComments(source: string): string {
  let out = "";
  let index = 0;
  let stringChar: string | null = null;

  while (index < source.length) {
    const char = source[index];
    const next = source[index + 1];

    if (stringChar) {
      out += char;
      if (char === "\\") {
        out += next ?? "";
        index += 2;
        continue;
      }
      if (char === stringChar) stringChar = null;
      index += 1;
      continue;
    }
    if (char === '"' || char === "'" || char === "`") {
      stringChar = char;
      out += char;
      index += 1;
      continue;
    }
    if (char === "/" && next === "/") {
      while (index < source.length && source[index] !== "\n") index += 1;
      continue;
    }
    if (char === "/" && next === "*") {
      index += 2;
      while (index < source.length && !(source[index] === "*" && source[index + 1] === "/")) index += 1;
      index += 2;
      continue;
    }
    out += char;
    index += 1;
  }
  return out;
}

const PAGE_CODE = stripComments(PAGE);

const scored = (over: Partial<RegisterProgress> = {}): RegisterProgress => ({
  percent: 50,
  stagesTotal: 22,
  stagesComplete: 11,
  requiredTotal: 100,
  requiredFilled: 50,
  definitionRead: true,
  ...over
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The switch the owner asked to default one way
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the type switch", () => {
  test("opens on design & prototype workshops", () => {
    // THE OWNER'S INSTRUCTION, PINNED. "By default, the toggle for the type of workshop over there
    // should be design and prototype workshops." It is also the product's own order — the whole app
    // exists to run them, and the legacy table is the craft-documentation visit that predates them.
    expect(DEFAULT_REGISTER_KIND).toBe("design");
    expect(REGISTER_KINDS[0].value).toBe("design");
    expect(REGISTER_KINDS.map((option) => option.value)).toEqual(["design", "other"]);
  });

  test("the first position is spelled the way the rest of the product spells it", () => {
    // This destination already answers to a name in the nav and on the ministry desk card. A fourth
    // spelling invented on a new screen is a name nobody's grep finds and nobody's colleague
    // recognises — `ministryDesk.ts` carries that rule and this is it being followed.
    expect(REGISTER_KINDS[0].label).toBe("Design & prototype workshops");
  });

  test("each position says what it reads, because the two tables are two different things", () => {
    for (const option of REGISTER_KINDS) {
      expect(option.note.length, `${option.value} has no note`).toBeGreaterThan(20);
    }
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * Two registers, two vocabularies
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the standing switch", () => {
  test("empty means everything, by absence, in both vocabularies", () => {
    /*
      `buildQuery` drops "" exactly as it drops null, so the "Everything" position is UNSENDABLE and
      therefore means everything by absence — which is what the server reads it as. That is why the
      value is the empty string rather than a word like "all": one state, one spelling, on both sides
      of the wire. A word here would give one state two spellings and the server would have to know
      about the second.
    */
    expect(DESIGN_STANDINGS[0].value).toBe("");
    expect(OTHER_STANDINGS[0].value).toBe("");
    expect(DESIGN_STANDINGS[0].label).toBe("Everything");
    expect(OTHER_STANDINGS[0].label).toBe("Everything");
  });

  test("the ministry's three words are only on the register that has a lifecycle", () => {
    /*
      A `DesignWorkshop` has `DesignWorkshopStatus`, moved by a transition graph — a LIFECYCLE. A
      legacy `Workshop` has `RecordStatus`, which says how far a recorded visit has got through
      MODERATION. Mapping "ongoing" onto a review state would be a second meaning for one word,
      invented on this screen, for rows every other surface in the product describes differently.
    */
    expect(DESIGN_STANDINGS.map((option) => option.value)).toEqual([
      "",
      "ongoing",
      "completed",
      "registered"
    ]);
    for (const option of OTHER_STANDINGS.slice(1)) {
      expect(["ongoing", "completed", "registered"]).not.toContain(option.value);
      // The other register's values are the enum's own tokens, so what the screen asks for is what
      // the rows are actually filed under.
      expect(option.value).toMatch(/^[A-Z_]+$/);
    }
  });

  test("the switch shown is the one belonging to the register on screen", () => {
    expect(standingsFor("design")).toBe(DESIGN_STANDINGS);
    expect(standingsFor("other")).toBe(OTHER_STANDINGS);
  });

  test("the members of a chosen group are the SERVER's grouping, not a copy of it", () => {
    /*
      The sentence under the switch says which standings the word covered. It is built from the
      `standingGroups` map the list response carries, so a group re-cut on the server reaches the
      screen without a deploy — and a reader is never left guessing what "Ongoing" included, which is
      the rule the server's own grouping comment states and this is the surface keeping it.
    */
    const meta = { standingGroups: { ongoing: ["IN_PROGRESS", "NEEDS_REVISION", "PRE_SUBMISSION"] } };
    expect(standingMembersSentence(meta, "ongoing")).toBe(
      "Covers In progress, Needs revision and Pre-submission."
    );
    // "Pre-submission" and not "Pre submission": the curated spelling is `StatusBadge`'s and the
    // filter control's, and this file records the one time those two disagreed.
    expect(standingMembersSentence(meta, "ongoing")).toContain("Pre-submission");

    expect(standingMembersSentence(meta, ""), "Everything has nothing to disambiguate").toBeNull();
    expect(standingMembersSentence(null, "ongoing")).toBeNull();
    expect(standingMembersSentence({ standingGroups: {} }, "ongoing")).toBeNull();
    expect(standingMembersSentence({ standingGroups: { solo: ["DRAFT"] } }, "solo")).toBe("Covers Draft.");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * Progress: never a zero it did not measure
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the progress sentence", () => {
  test("leads with the two numbers and not with the percentage", () => {
    /*
      `lib/submissionReadiness.ts` is explicit: *"'82%' is the figure this screen must NOT lead with:
      it is the same number whether the remaining 18% is one date field or a stage nobody has
      opened … Counting the stages and then counting the fields inside them gives the two numbers
      that actually decide whether the afternoon is enough."* At ministry scale that misreading
      decides which designers get chased, so the order is pinned rather than left to taste.
    */
    const sentence = progressSentence(scored());
    expect(sentence.indexOf("stages complete")).toBeLessThan(sentence.indexOf("%"));
    expect(sentence).toBe("11 of 22 stages complete, 50 required fields outstanding — 50%.");
  });

  test("counts one outstanding field in the singular", () => {
    expect(progressSentence(scored({ requiredTotal: 100, requiredFilled: 99, percent: 99 }))).toBe(
      "11 of 22 stages complete, 1 required field outstanding — 99%."
    );
  });

  test("a finished workshop says so rather than saying zero outstanding", () => {
    expect(
      progressSentence(scored({ requiredFilled: 100, percent: 100, stagesComplete: 22 }))
    ).toBe("22 of 22 stages complete, no required fields outstanding — 100%.");
  });

  test("a workshop that was not scored says WHICH absence it is, and never 0%", () => {
    /*
      THE SINGLE MOST DAMAGING FALSE STATEMENT THIS SCREEN COULD MAKE is 0% against a workshop it
      failed to read. `overallPercent` in `lib/designWorkshops.ts` returns 0 for an empty completeness
      map — correct for the header it serves, and exactly wrong here — which is why the server rolls
      up to `null` and why these two sentences are different sentences. They also suggest different
      next moves: one is answered by narrowing the list, the other is not answerable by the reader at
      all.
    */
    const capped = progressSentence(scored({ percent: null, stagesComplete: null, unscoredReason: "capped" }));
    const unreadable = progressSentence(
      scored({ percent: null, stagesComplete: null, unscoredReason: "unreadable" })
    );
    expect(capped).not.toBe(unreadable);
    expect(capped).toContain("Not scored on this page");
    expect(unreadable).toContain("could not be read");
    expect(unreadable).toContain("not zero progress");
    for (const sentence of [capped, unreadable, progressSentence(null)]) {
      expect(sentence).not.toMatch(/\b0%/);
    }
  });

  test("only a real figure draws a bar", () => {
    expect(hasProgressFigure(scored())).toBe(true);
    expect(hasProgressFigure(scored({ percent: null }))).toBe(false);
    expect(hasProgressFigure(null)).toBe(false);
    expect(hasProgressFigure(undefined)).toBe(false);
  });

  test("a summary whose buckets do not add up says so, and is silent when they do", () => {
    // `unclassified` is normally zero. It is not zero when the server stores a status this build has
    // never heard of, and a register whose groups add up to less than its own total — with nothing on
    // screen to say so — is the summary contradicting its own rows.
    expect(unclassifiedSentence(0)).toBeNull();
    expect(unclassifiedSentence(-1)).toBeNull();
    expect(unclassifiedSentence(1)).toContain("1 workshop is in a standing");
    expect(unclassifiedSentence(3)).toContain("3 workshops are in a standing");
    expect(unclassifiedSentence(3)).toContain("Everything");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The five source properties
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the register re-reads itself without lying", () => {
  test("the poll bumps a token and never fetches", () => {
    // Two independent write paths into one list state is precisely the overlap a generation counter
    // survives only half of. `MediaJobsPanel` is the precedent: every trigger — poll, filters,
    // pagination, the manual refresh — enters one request path.
    expect(PAGE).toContain("window.setInterval(tick, REFRESH_MS)");
    expect(PAGE).toContain("setLoadToken((token) => token + 1)");
    const tickBody = PAGE.slice(PAGE.indexOf("const tick = () => {"), PAGE.indexOf("const timer = window.setInterval"));
    expect(tickBody, "the poll issues its own request instead of bumping the token").not.toContain("listRegister");
  });

  test("the timer yields to a read already in the air", () => {
    /*
      ⚠ THE GENERATION COUNTER IS NOT ENOUGH ON THIS SCREEN, WHICH IS THE ONE PLACE IN THE APP
      WHERE THAT IS TRUE. On the four sibling list pages every load is started by a PERSON, so
      superseding an older one is exactly right. Here a load is also started by a CLOCK, and the two
      interact badly: a read slower than the interval is discarded by the guard, the next tick starts
      another, and the register never renders at all — "Loading…" for ever, with a request stacking up
      every thirty seconds on the one screen whose whole purpose is to be current. Exactly the
      connection this deployment is sized against is the one that triggers it.

      The guard is in the TICK and not in the load, so a person's action still supersedes a slow poll
      — pressing the other register must not wait for it.
    */
    expect(PAGE_CODE).toContain("if (inFlight.current) return;");
    expect(PAGE_CODE).toContain("inFlight.current = true;");
    // Released for EVERY outcome, before the generation guard returns early — a flag cleared only on
    // the path that renders is a poll that stops for ever the first time somebody clicks mid-read.
    expect(PAGE_CODE).toContain(".finally(() => {");
    const load = PAGE_CODE.slice(PAGE_CODE.indexOf("inFlight.current = true;"));
    expect(load.slice(0, 400)).toContain("inFlight.current = false;");
  });

  test("the pager cannot be left pointing past the end of a register that shrank", () => {
    // Every filter resets to page one, so the ordinary route is covered. What is not is the register
    // shrinking UNDER the reader between two ticks: `Pagination` would print "Page 3 of 1" over an
    // empty table and the empty state beside it would call a full national register "genuinely
    // empty" — this repository's most repeated bug class, arriving from the clock and not a click.
    expect(PAGE_CODE).toContain("if (data.pages > 0 && page > data.pages) setPage(1);");
  });

  test("a failed FIRST read is not a spinner", () => {
    // `items === null` means "still asking" everywhere in this app, and the catch deliberately does
    // not touch the rows — so on the FIRST failure, with no rows to keep, the panel sat on "Loading…"
    // for ever under a red banner. Three states collapsed into the wrong one, and a spinner tells the
    // reader to wait for something that will never arrive.
    expect(PAGE_CODE).toContain("data === null && error ?");
  });

  test("a beneficiary count that was not read is not zero", () => {
    // The server sends `null` for "the roster could not be read" and a number otherwise — including
    // an honest 0 on the day a workshop opens. Collapsing them would print a confident wrong number
    // in a column a ministry uses to count people.
    expect(PAGE_CODE).toContain("row.beneficiaries === null ?");
  });

  test("a hidden tab does not poll, and coming back reads once", () => {
    // Either a hidden tab polls a ministry endpoint forever — the precise cost the standing NO-TIMER
    // ruling objects to — or the officer who returns after an hour waits up to thirty seconds to find
    // out they are reading an hour-old register. Both halves are needed.
    expect(PAGE).toContain('if (document.visibilityState === "hidden") return;');
    expect(PAGE).toContain('document.addEventListener("visibilitychange", onVisible)');
    expect(PAGE).toContain('document.removeEventListener("visibilitychange", onVisible)');
    expect(PAGE).toContain("window.clearInterval(timer)");
  });

  test("every write is behind a generation guard, in the catch as well as the then", () => {
    /*
      A stale FAILURE matters as much as a stale success: it paints an error over rows that loaded
      fine. `list-fetch-generation-unit.spec.ts` pins the same shape on five other list pages, and its
      documented failure is a poll that started before the reader's toggle landing after it — which
      here would be the type switch appearing to snap back to the other register.
    */
    expect(PAGE).toContain("const generation = (currentLoad.current += 1);");
    const guards = PAGE.match(/if \(generation !== currentLoad\.current\) return;/g) ?? [];
    expect(guards.length, "guard every setData and setError, not just the first").toBeGreaterThanOrEqual(2);
    expect(PAGE, "hold the result so it can be discarded").not.toContain("setData(await");
  });

  test("a failed read never empties the register", () => {
    // An empty table under an error banner reads as "there are no workshops", which is the one thing
    // this screen must never say by accident. The catch sets an error and touches nothing else.
    const catchBody = PAGE.slice(PAGE.indexOf(".catch((err) => {"), PAGE.indexOf("return () => {\n      cancelled = true;"));
    expect(catchBody).toContain("setError(describeFailure(err))");
    expect(catchBody, "a failed tick clears the rows").not.toContain("setData(null)");
    expect(catchBody, "a failed tick empties the rows").not.toContain("setData({");
  });

  test("a timer's request never hard-navigates the reader to /login", () => {
    // `lib/media.ts` argues it for the staged-object sweep: a navigation BY A TIMER loses the screen
    // the officer was working on. `apiFetch` still clears the token, so a genuinely dead session is
    // still noticed by AuthProvider — which is where a decision about the session belongs.
    const calls = LIB.match(/redirectOn401: false/g) ?? [];
    expect(calls.length, "a read on this screen can be issued by the timer and must opt out").toBeGreaterThanOrEqual(4);
  });
});

test.describe("the accessibility contract a moving table owes", () => {
  test("the table is explicitly not a live region", () => {
    /*
      `role="status"` implies `aria-atomic`, so every tick would re-read the WHOLE region. `EntityForm`
      gives the three conditions under which a moving number may sit in one — it changes only when
      the reader deliberately acted, it appears near a threshold rather than from first paint, and
      what is announced is the consequence of the act just performed — and this screen fails all
      three. The remedy that note states verbatim is the one taken: `aria-live="off"` with the
      sentence said some other way.
    */
    expect(PAGE).toContain('aria-live="off"');
    const table = PAGE.slice(PAGE.indexOf('<div className="overflow-x-auto"'));
    expect(table).not.toContain('role="status"');
    expect(table).not.toContain('aria-live="polite"');
  });

  test("the progress bar is decoration and the sentence is the announcement, exactly once", () => {
    /*
      ⚠ THIS TEST PINNED THE OPPOSITE UNTIL 2026-09-20, AND THE CODE IT PINNED WAS WRONG. It required
      `role="progressbar"` with `aria-valuetext={sentence}` — while the same sentence was also printed
      as text beneath. A screen reader therefore read every row's progress TWICE, and the progressbar
      had no accessible name at all: `GalleryProgress`'s precedent is ONE bar on a page that a heading
      names, and this is one per row in a table with nothing to name it.

      Dropping the role fixes both and loses nothing, because the sentence is complete on its own.
      That is rule 5 of the frontend contract working as intended — the WORD carries the signal and
      the bar only agrees with it — rather than an accessibility feature being removed.
    */
    const cell = PAGE_CODE.slice(PAGE_CODE.indexOf("function ProgressCell("));
    expect(cell, "the bar is announced as well as the sentence, so every row is read twice").not.toContain(
      'role="progressbar"'
    );
    expect(cell, "the bar is not hidden from assistive technology").toContain("aria-hidden");
    // The sentence is still DRAWN, which is the half that must never be dropped: a figure that
    // exists only inside an ARIA attribute is a figure most readers never get.
    expect(cell).toContain("{sentence}</span>");
    expect(cell).toContain("progressSentence(progress)");
  });

  test("a manual refresh is announced and the timer's readout is not", () => {
    /*
      The two halves of `EntityForm`'s three-condition test, on one screen. A refresh the READER
      asked for passes all three — they acted, it is not announced from first paint, and what is
      announced is the consequence of that act — so it gets a live region. The "Read at …" line moves
      on a TIMER and fails all three, so it stays plain text. Without the first half the button is
      inaudible and cannot be told from a dead control; with the second half the reader would be
      interrupted every thirty seconds forever.
    */
    expect(PAGE_CODE).toContain("manualRefresh.current = true;");
    expect(PAGE_CODE).toContain('role="status"');
    // MOUNTED FROM FIRST PAINT AND EMPTY — `cappedList`'s rule: assistive tech only announces
    // mutations inside a region that already existed.
    expect(PAGE_CODE).toContain("{refreshNote}");
    // Both outcomes speak. A button silent on failure is worse than one silent always, because the
    // silence reads as success.
    expect(PAGE_CODE).toContain("Register re-read.");
    expect(PAGE_CODE).toContain("could not be re-read");
  });

  test("the load-error banner interrupts, and the self-refreshing table does not", () => {
    // The banner is the sentence that stops an empty table reading as an empty register, and it was
    // the silent one while the download banner beside it carried a role.
    expect(PAGE_CODE).toContain('<div role="alert"');
    expect(PAGE_CODE).toContain('aria-live="off"');
  });

  test("the bar moves by CSS, with the property named", () => {
    // The two global reduced-motion rules reach CSS and cannot reach framer's inline styles, and
    // there is no `MotionConfig reducedMotion="user"` anywhere in this app. `transition-all` would
    // also animate the colour.
    expect(PAGE).toContain("transition-[width]");
    expect(PAGE).not.toContain("transition-all");
    const cell = PAGE.slice(PAGE.indexOf("function ProgressCell("));
    expect(cell, "the progress bar is animated by framer-motion").not.toContain("motion.");
  });

  test("the selected position of each switch is not carried by colour alone", () => {
    // Rule 5 of the frontend contract governs any signal carried by one channel: a tick and
    // `aria-pressed` survive colour-blindness, a greyscale printout and forced-colours mode.
    // `GuideTrackSwitch` is the precedent, including why this is not a tablist.
    expect(PAGE).toContain("aria-pressed={selected}");
    expect(PAGE).toContain("<Check");
    expect(PAGE, "a tablist owes roving focus and an aria-controls this does not have").not.toContain(
      'role="tablist"'
    );
    expect(PAGE).toContain('role="group"');
  });
});

test.describe("the register narrows on the server and nowhere else", () => {
  test("no row list is filtered or sorted in the browser", () => {
    /*
      `/sanction-orders`' rule: *"NO `.filter()` AND NO `.sort()` OVER `data.items`, ANYWHERE ON THIS
      PAGE. Every narrowing is a query parameter. A list filtered in the browser is the right SIZE and
      has silently dropped whatever it excluded."* On a MINISTRY register that reports a national
      programme as the size of one page.
    */
    expect(PAGE).not.toMatch(/data\.items\s*\.\s*(filter|sort)/);
    expect(PAGE).not.toMatch(/rows\s*\.\s*(filter|sort)\(/);
  });

  test("the bucket counts come from the server and not from the page of rows", () => {
    // A bucket count taken from twenty loaded rows is a property of the page, not of the platform,
    // and on a ministry screen it is read as the size of the programme.
    expect(PAGE).toContain("fetchRegisterSummary()");
    expect(PAGE).toContain("summary.designWorkshops.ongoing");
    expect(PAGE).toContain("summary.designWorkshops.completed");
    expect(PAGE).toContain("summary.designWorkshops.registered");
  });

  test("the table is rendered from the register the ROWS came from, not from the switch", () => {
    /*
      `kind` changes the instant the officer presses the other button; the rows arrive one round trip
      later. Rendering the table from `kind` would, for every frame in between, cast the OTHER
      register's rows to this one's shape and read columns that are not on them — a table of empty
      cells under the right headings, which is indistinguishable from a register that genuinely holds
      nothing. That is this repository's most repeated bug class arriving through a type assertion,
      and a type assertion is exactly the construct a compiler will not catch it in.
    */
    expect(PAGE).toContain("{dataKind === \"design\" ? (");
    expect(PAGE).toContain("setDataKind(requested)");
    // Captured at request time so the answer can be paired with the question that asked it.
    expect(PAGE).toContain("const requested = kind;");
    const table = PAGE.slice(PAGE.indexOf("<DesignTable rows="));
    expect(table.slice(0, 400), "the table still branches on the live switch").not.toContain(
      "kind === \"design\" ?"
    );
  });

  test("switching register clears the standing rather than carrying a word the other list never heard of", () => {
    // The server ignores an unrecognised narrowing rather than refusing it, so a carried value would
    // silently show "Everything" under a button reading "Ongoing".
    const choose = PAGE.slice(PAGE.indexOf("const chooseKind = useCallback"), PAGE.indexOf("const chooseStanding"));
    expect(choose).toContain('setStanding("")');
    expect(choose).toContain("setPage(1)");
  });
});

test.describe("the downloads say what they hold", () => {
  test("the register download takes the filters and the beneficiary list does not", () => {
    // Silently handing over a different population than the one on screen would be worse than
    // handing over the whole one and saying which it is — so the page says which.
    expect(LIB).toContain("downloadRegister");
    expect(LIB).toContain('downloadFile("/export/artisans.csv", "artisans.csv")');
    // ONE LINE, WHATEVER THE FORMATTER DID TO IT. This sentence is JSX text and is wrapped wherever
    // it fits, so a raw substring check would be asserting the formatter's line breaks rather than
    // the copy.
    expect(PAGE.replace(/\s+/g, " ")).toContain("not only the artisans of the workshops listed here");
  });

  test("the page states what the workshop file does NOT contain, beside the button", () => {
    // SAY IT ON SCREEN, NEVER 403 A BUTTON — `can_export_design_workshop_data`'s standing rule. The
    // question a reader has about a file they are about to send somewhere is what is in it.
    const flat = PAGE.replace(/\s+/g, " ");
    expect(flat).toContain("and no stage content: no answers, no photographs");
    expect(flat).toContain("masked to its last four characters");
  });
});
