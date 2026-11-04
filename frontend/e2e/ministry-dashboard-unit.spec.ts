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

test.describe("the type switch", () => {
  test("opens on design & prototype workshops", () => {
    expect(DEFAULT_REGISTER_KIND).toBe("design");
    expect(REGISTER_KINDS[0].value).toBe("design");
    expect(REGISTER_KINDS.map((option) => option.value)).toEqual(["design", "other"]);
  });

  test("the first position is spelled the way the rest of the product spells it", () => {
    expect(REGISTER_KINDS[0].label).toBe("Design & prototype workshops");
  });

  test("each position says what it reads, because the two tables are two different things", () => {
    for (const option of REGISTER_KINDS) {
      expect(option.note.length, `${option.value} has no note`).toBeGreaterThan(20);
    }
  });
});

test.describe("the standing switch", () => {
  test("empty means everything, by absence, in both vocabularies", () => {
    expect(DESIGN_STANDINGS[0].value).toBe("");
    expect(OTHER_STANDINGS[0].value).toBe("");
    expect(DESIGN_STANDINGS[0].label).toBe("Everything");
    expect(OTHER_STANDINGS[0].label).toBe("Everything");
  });

  test("the ministry's three words are only on the register that has a lifecycle", () => {
    expect(DESIGN_STANDINGS.map((option) => option.value)).toEqual([
      "",
      "ongoing",
      "completed",
      "registered"
    ]);
    for (const option of OTHER_STANDINGS.slice(1)) {
      expect(["ongoing", "completed", "registered"]).not.toContain(option.value);
      expect(option.value).toMatch(/^[A-Z_]+$/);
    }
  });

  test("the switch shown is the one belonging to the register on screen", () => {
    expect(standingsFor("design")).toBe(DESIGN_STANDINGS);
    expect(standingsFor("other")).toBe(OTHER_STANDINGS);
  });

  test("the members of a chosen group are the SERVER's grouping, not a copy of it", () => {
    const meta = { standingGroups: { ongoing: ["IN_PROGRESS", "NEEDS_REVISION", "PRE_SUBMISSION"] } };
    expect(standingMembersSentence(meta, "ongoing")).toBe(
      "Covers In progress, Needs revision and Pre-submission."
    );
    expect(standingMembersSentence(meta, "ongoing")).toContain("Pre-submission");

    expect(standingMembersSentence(meta, ""), "Everything has nothing to disambiguate").toBeNull();
    expect(standingMembersSentence(null, "ongoing")).toBeNull();
    expect(standingMembersSentence({ standingGroups: {} }, "ongoing")).toBeNull();
    expect(standingMembersSentence({ standingGroups: { solo: ["DRAFT"] } }, "solo")).toBe("Covers Draft.");
  });
});

test.describe("the progress sentence", () => {
  test("leads with the two numbers and not with the percentage", () => {
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
    const capped = progressSentence(scored({ percent: null, stagesComplete: null, unscoredReason: "capped" }));
    const unreadable = progressSentence(
      scored({ percent: null, stagesComplete: null, unscoredReason: "unreadable" })
    );
    expect(capped).not.toBe(unreadable);
    expect(capped).toContain("Not scored on this page");
    expect(unreadable).toContain("couldn't be loaded");
    expect(unreadable).toContain("hasn't been scored");
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
    expect(unclassifiedSentence(0)).toBeNull();
    expect(unclassifiedSentence(-1)).toBeNull();
    expect(unclassifiedSentence(1)).toContain("1 workshop has an ungrouped status");
    expect(unclassifiedSentence(3)).toContain("3 workshops have ungrouped statuses");
    expect(unclassifiedSentence(3)).toContain("Everything");
  });
});

test.describe("the register re-reads itself without lying", () => {
  test("the poll bumps a token and never fetches", () => {
    expect(PAGE).toContain("window.setInterval(tick, REFRESH_MS)");
    expect(PAGE).toContain("setLoadToken((token) => token + 1)");
    const tickBody = PAGE.slice(PAGE.indexOf("const tick = () => {"), PAGE.indexOf("const timer = window.setInterval"));
    expect(tickBody, "the poll issues its own request instead of bumping the token").not.toContain("listRegister");
  });

  test("the timer yields to a read already in the air", () => {
    expect(PAGE_CODE).toContain("if (inFlight.current) return;");
    expect(PAGE_CODE).toContain("inFlight.current = true;");
    expect(PAGE_CODE).toContain(".finally(() => {");
    const load = PAGE_CODE.slice(PAGE_CODE.indexOf("inFlight.current = true;"));
    expect(load.slice(0, 400)).toContain("inFlight.current = false;");
  });

  test("the pager cannot be left pointing past the end of a register that shrank", () => {
    expect(PAGE_CODE).toContain("if (data.pages > 0 && page > data.pages) setPage(1);");
  });

  test("a failed FIRST read is not a spinner", () => {
    expect(PAGE_CODE).toContain("data === null && error ?");
  });

  test("a beneficiary count that was not read is not zero", () => {
    expect(PAGE_CODE).toContain("row.beneficiaries === null ?");
  });

  test("a hidden tab does not poll, and coming back reads once", () => {
    expect(PAGE).toContain('if (document.visibilityState === "hidden") return;');
    expect(PAGE).toContain('document.addEventListener("visibilitychange", onVisible)');
    expect(PAGE).toContain('document.removeEventListener("visibilitychange", onVisible)');
    expect(PAGE).toContain("window.clearInterval(timer)");
  });

  test("every write is behind a generation guard, in the catch as well as the then", () => {
    expect(PAGE).toContain("const generation = (currentLoad.current += 1);");
    const guards = PAGE.match(/if \(generation !== currentLoad\.current\) return;/g) ?? [];
    expect(guards.length, "guard every setData and setError, not just the first").toBeGreaterThanOrEqual(2);
    expect(PAGE, "hold the result so it can be discarded").not.toContain("setData(await");
  });

  test("a failed read never empties the register", () => {
    const catchBody = PAGE.slice(PAGE.indexOf(".catch((err) => {"), PAGE.indexOf("return () => {\n      cancelled = true;"));
    expect(catchBody).toContain("setError(describeFailure(err))");
    expect(catchBody, "a failed tick clears the rows").not.toContain("setData(null)");
    expect(catchBody, "a failed tick empties the rows").not.toContain("setData({");
  });

  test("a timer's request never hard-navigates the reader to /login", () => {
    const calls = LIB.match(/redirectOn401: false/g) ?? [];
    expect(calls.length, "a read on this screen can be issued by the timer and must opt out").toBeGreaterThanOrEqual(4);
  });
});

test.describe("the accessibility contract a moving table owes", () => {
  test("the table is explicitly not a live region", () => {
    expect(PAGE).toContain('aria-live="off"');
    const table = PAGE.slice(PAGE.indexOf('<div className="overflow-x-auto"'));
    expect(table).not.toContain('role="status"');
    expect(table).not.toContain('aria-live="polite"');
  });

  test("the progress bar is decoration and the sentence is the announcement, exactly once", () => {
    const cell = PAGE_CODE.slice(PAGE_CODE.indexOf("function ProgressCell("));
    expect(cell, "the bar is announced as well as the sentence, so every row is read twice").not.toContain(
      'role="progressbar"'
    );
    expect(cell, "the bar is not hidden from assistive technology").toContain("aria-hidden");
    expect(cell).toContain("{sentence}</span>");
    expect(cell).toContain("progressSentence(progress)");
  });

  test("a manual refresh is announced and the timer's readout is not", () => {
    expect(PAGE_CODE).toContain("manualRefresh.current = true;");
    expect(PAGE_CODE).toContain('role="status"');
    expect(PAGE_CODE).toContain("{refreshNote}");
    expect(PAGE_CODE).toContain("Refreshed.");
    expect(PAGE_CODE).toContain("couldn't be refreshed");
  });

  test("the load-error banner interrupts, and the self-refreshing table does not", () => {
    expect(PAGE_CODE).toContain('<div role="alert"');
    expect(PAGE_CODE).toContain('aria-live="off"');
  });

  test("the bar moves by CSS, with the property named", () => {
    expect(PAGE).toContain("transition-[width]");
    expect(PAGE).not.toContain("transition-all");
    const cell = PAGE.slice(PAGE.indexOf("function ProgressCell("));
    expect(cell, "the progress bar is animated by framer-motion").not.toContain("motion.");
  });

  test("the selected position of each switch is not carried by colour alone", () => {
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
    expect(PAGE).not.toMatch(/data\.items\s*\.\s*(filter|sort)/);
    expect(PAGE).not.toMatch(/rows\s*\.\s*(filter|sort)\(/);
  });

  test("the bucket counts come from the server and not from the page of rows", () => {
    expect(PAGE).toContain("fetchRegisterSummary()");
    expect(PAGE).toContain("summary.designWorkshops.ongoing");
    expect(PAGE).toContain("summary.designWorkshops.completed");
    expect(PAGE).toContain("summary.designWorkshops.registered");
  });

  test("the table is rendered from the register the ROWS came from, not from the switch", () => {
    expect(PAGE).toContain("{dataKind === \"design\" ? (");
    expect(PAGE).toContain("setDataKind(requested)");
    expect(PAGE).toContain("const requested = kind;");
    const table = PAGE.slice(PAGE.indexOf("<DesignTable rows="));
    expect(table.slice(0, 400), "the table still branches on the live switch").not.toContain(
      "kind === \"design\" ?"
    );
  });

  test("switching register clears the standing rather than carrying a word the other list never heard of", () => {
    const choose = PAGE.slice(PAGE.indexOf("const chooseKind = useCallback"), PAGE.indexOf("const chooseStanding"));
    expect(choose).toContain('setStanding("")');
    expect(choose).toContain("setPage(1)");
  });
});

test.describe("the downloads say what they hold", () => {
  test("the register download takes the filters and the beneficiary list does not", () => {
    expect(LIB).toContain("downloadRegister");
    expect(LIB).toContain('downloadFile("/export/artisans.csv", exportFilename("Artisan", "Export", "csv"))');
    expect(PAGE.replace(/\s+/g, " ")).toContain("“Download beneficiaries” exports every artisan");
  });

  test("the page states what the workshop file does NOT contain, beside the button", () => {
    const flat = PAGE.replace(/\s+/g, " ");
    expect(flat).toContain("with no stage content");
    expect(flat).toContain("masked to the last four characters");
  });
});
