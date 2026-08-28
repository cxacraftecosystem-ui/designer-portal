/**
 * THE SKETCHES & PROTOTYPES PARITY MATRIX, HELD AGAINST THE TREE IT DESCRIBES.
 *
 * ── WHAT THIS PINS, AND WHY A DOCUMENT NEEDED PINNING AT ALL ────────────────────────────────────
 *
 * `docs/SKETCHES-PROTOTYPES-PARITY.md` answers one question — what can a designer do with a sketch or
 * a prototype on each client — and answers it with citations rather than with prose. A register of
 * that shape has exactly one failure mode, and this repository has shipped it repeatedly: the code
 * moves, the register does not, and the register is then WORSE than nothing, because every reader
 * after that point is reasoning from it.
 *
 * The precedent is written out in §16 of `.claude/skills/field-repo-frontend/SKILL.md`, which is the
 * same comparison kept by hand. It listed eleven dashboard tiles when twenty existed, so the honest
 * reading of a missing tile was "this tile is not expected" — and a page whose tile was never added
 * reads to its owner as a page that was never built. The same section stated in as many words that
 * "there is no ratings code anywhere under `android/app/src/main`" for a release after that had
 * stopped being true. Both were correct when written. Nothing was testing either.
 *
 * ── WHY THE TEST LIVES HERE, IN `frontend/e2e`, AND NOT BESIDE EITHER CLIENT ────────────────────
 *
 * The claim under test SPANS two clients, so the runner has to be able to read both trees, and only
 * one of this repository's three suites does that cheaply:
 *
 *   * A JVM test under `android/app/src/test` runs from the Gradle module and would have to reach
 *     back out of it to read TypeScript. Worse, it is the expensive half: an Android change costs a
 *     tagged release and a fleet that may be offline for a fortnight, and this file will need editing
 *     every time a row moves. A web change ships in one push.
 *   * `backend/tests` can read both trees — `test_report_parity.py` does — but the suite needs a
 *     Postgres on 127.0.0.1, so the person most likely to break this matrix (somebody renaming a
 *     Kotlin symbol) is exactly the person who cannot run it.
 *   * A `*-unit.spec.ts` here runs under `npm run test:unit` with NO browser, NO server, NO database
 *     and NO credentials, and `e2e/text-format-parity-unit.spec.ts`, `e2e/cost-integrity-port-unit.spec.ts`
 *     and `e2e/role-ladder-parity-unit.spec.ts` are all cross-client parity specs that already work
 *     this way. `e2e/qr-surfaces-unit.spec.ts` is the closest relative of all: it reads source files
 *     because "there is no runtime handle on 'how many QR surfaces does this app have'", and there is
 *     no runtime handle on "does the handset have a corner guess" either.
 *
 * So: pure Node, no `page`, no fixture, no sign-in.
 *
 * ── COMMENTS ARE STRIPPED BEFORE ANY SYMBOL IS LOOKED FOR, AND THAT IS THE WHOLE RIGOUR ─────────
 *
 * Every file this matrix cites is heavily commented, and several of them name each other's symbols in
 * prose — `SketchesAndPrototypesScreen.kt` quotes `PrototypeModelField.tsx` by name, and
 * `DwSketchRectifyGuess.kt` discusses the browser's `guessSheetCorners`. An assertion that a symbol
 * "is in" a file must not be satisfiable by a sentence about it, which is the rule
 * `e2e/dashboard-tile-parity-unit.spec.ts` states for the tiles array and follows with a hand-rolled
 * scanner. This file follows it with the same kind of scanner, for TypeScript, Kotlin and Python.
 *
 * ── WHAT THIS FILE DELIBERATELY DOES NOT ASSERT ────────────────────────────────────────────────
 *
 * IT NEVER ASSERTS AN ABSENCE. A row reading "WEB ONLY" is a claim that the handset lacks something,
 * and no grep can prove that: a port arriving under a different name would sail past any probe, and a
 * probe written against a name nobody has chosen yet goes red for whoever chooses a different one.
 * That is not a hypothetical. The corner-guess row was WEB ONLY when the matrix was drafted on
 * 2026-08-27 and BOTH by the time it was finished, because another workstream landed
 * `DwSketchRectifyGuess.kt` and wired it up in between — under `dwGuessSheetCorners`, not under the
 * browser's `guessSheetCorners`, so a probe on the browser's spelling would have stayed green through
 * the whole change and a probe on the Kotlin spelling would have been red until the moment it landed.
 *
 * What is asserted instead is the SHAPE OF THE CLAIM: a non-BOTH row must carry an ISO date and a
 * runnable command in the cell that says a client lacks something, so that the claim can be settled by
 * one person in one command. That is this repository's own rule for a claim about the state of the
 * world, and `docs/tools/check-docs.mjs` enforces the same rule on comments.
 *
 * The one absence it does assert is not about parity at all: the handset's chooser must not grow into
 * an editor. That is a standing instruction with a written argument behind it, and it is checked by
 * name — see the last test.
 */

import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

import { expect, test } from "@playwright/test";

/** The repository root. This spec reads three trees, so nothing here is relative to `frontend/`. */
const ROOT = resolve(__dirname, "..", "..");
const DOC = "docs/SKETCHES-PROTOTYPES-PARITY.md";

const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const matrix = read(DOC);

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * Reading the document
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

/**
 * The five verdicts, spelled exactly as the document's own legend spells them.
 *
 * WRITTEN OUT HERE RATHER THAN PARSED OUT OF THE LEGEND, so that the vocabulary is fixed by code and
 * not by the file being checked. Reading it from the document would make the check a tautology: a row
 * inventing "PARTIAL" would pass the moment somebody added a legend row for it, which is exactly the
 * quiet widening this file exists to refuse.
 */
const VERDICTS = [
  "BOTH",
  "WEB ONLY (deliberate)",
  "WEB ONLY (gap)",
  "ANDROID ONLY (deliberate)",
  "ANDROID ONLY (gap)"
] as const;

type Verdict = (typeof VERDICTS)[number];

/**
 * A citation, as the document writes them: `` `path/to/file.ext#SymbolName` ``.
 *
 * PATH AND SYMBOL, NEVER A LINE NUMBER. `docs/REPORT-DATA-WIRING.md` had its `file:line` pins removed
 * when they rotted onto unrelated code, and that episode is what produced the citation-drift check in
 * `docs/tools/check-docs.mjs`. A symbol name survives an edit above it; a line number does not.
 *
 * The path class admits `(` `)` `[` `]` because the App Router spells a route group `(protected)` and
 * a dynamic segment `[stageKey]`, and the largest area of the frontend is unreachable without them —
 * the same correction `check-docs.mjs` records having had to make to its own path regex.
 */
const PIN_RE = /^([\w.\-/()[\]]+\.(?:ts|tsx|kt|py|json))#([A-Za-z_][A-Za-z0-9_]*)$/;

type Pin = { path: string; symbol: string; raw: string };

/** Every inline code span in the document, in order. */
function codeSpans(markdown: string): string[] {
  return [...markdown.matchAll(/`([^`\n]+)`/g)].map((m) => m[1]);
}

/** Every citation in a fragment of the document — the whole file, or one table cell. */
const pinsIn = (fragment: string): Pin[] =>
  codeSpans(fragment)
    .map((raw) => {
      const m = raw.match(PIN_RE);
      return m ? { path: m[1], symbol: m[2], raw } : null;
    })
    .filter((pin): pin is Pin => pin !== null);

const PINS = pinsIn(matrix);

/**
 * A row of one of the four matrices: four cells, the last of which is a verdict.
 *
 * FOUR CELLS IS THE DISCRIMINATOR, and it is enough. The document holds other tables — the verdict
 * legend, the adjacent-features table, the maintenance table — and none of them has four columns, so
 * none of them is mistaken for a matrix row. A future table that DID have four would have to end its
 * last column in one of the five verdicts to be picked up, which is the definition of a matrix row.
 */
type Row = { capability: string; web: string; android: string; verdict: string; line: number };

function matrixRows(markdown: string): Row[] {
  const rows: Row[] = [];
  markdown.split("\n").forEach((line, index) => {
    const trimmed = line.trim();
    if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) return;
    const cells = trimmed.slice(1, -1).split("|").map((cell) => cell.trim());
    if (cells.length !== 4) return;
    // Neither the separator row (`|---|---|---|---|`) nor the header above it is data. The header is
    // recognised by its last cell rather than by position, because a table may be moved and a
    // position-based skip would then silently drop a real row or admit a heading as one.
    if (cells.every((cell) => /^:?-{2,}:?$/.test(cell))) return;
    if (cells[3] === "Verdict") return;
    const verdict = cells[3].replace(/\*/g, "").trim();
    rows.push({ capability: cells[0], web: cells[1], android: cells[2], verdict, line: index + 1 });
  });
  return rows;
}

const ROWS = matrixRows(matrix);

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * Reading the source it cites
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

/**
 * A file with its comments removed and its string contents kept.
 *
 * A HAND-ROLLED SCANNER RATHER THAN A REGEX, for the reason `dashboard-tile-parity-unit.spec.ts`
 * gives about the same problem: the stripping has to respect quotes, or a `//` inside a URL literal
 * eats the rest of a line of real code, and a `/*` inside a string swallows the file.
 *
 * WHAT COUNTS AS A COMMENT IS PER LANGUAGE, and Python is the one that differs. Its block comment is
 * a triple-quoted string used as a statement, so triple-quoted spans are DROPPED there and kept
 * everywhere else. The cost is understood and accepted: a Python symbol that exists only inside a
 * triple-quoted value would be reported missing. No pin on this matrix is of that shape — the two
 * Python pins are registry field keys, which are ordinary double-quoted arguments.
 *
 * Kotlin's raw strings are triple-quoted too, and are KEPT, because in Kotlin they are values rather
 * than comments. Handled explicitly rather than left to chance: a raw string containing a lone `"`
 * would otherwise unbalance the scanner for the rest of the file.
 */
function commentFreeSource(relative: string, source: string): string {
  const extension = relative.slice(relative.lastIndexOf(".") + 1).toLowerCase();
  if (extension === "json") return source;

  const python = extension === "py";
  const lineComment = python ? "#" : "//";
  let i = 0;
  let out = "";

  const startsWith = (token: string) => source.startsWith(token, i);

  while (i < source.length) {
    // Triple-quoted spans: dropped in Python (a docstring), kept in Kotlin and TypeScript (a value).
    if (startsWith('"""') || startsWith("'''")) {
      const fence = source.slice(i, i + 3);
      const end = source.indexOf(fence, i + 3);
      const stop = end === -1 ? source.length : end + 3;
      if (!python) out += source.slice(i, stop);
      else out += "\n".repeat((source.slice(i, stop).match(/\n/g) ?? []).length);
      i = stop;
      continue;
    }
    const c = source[i];
    if (c === '"' || c === "'" || (!python && c === "`")) {
      // A single-line string. Its contents are code — a registry key, a stage key, a sentence the
      // matrix pins — so they are kept.
      out += c;
      i += 1;
      while (i < source.length) {
        if (source[i] === "\\") {
          out += source.slice(i, i + 2);
          i += 2;
          continue;
        }
        out += source[i];
        i += 1;
        if (source[i - 1] === c) break;
        // An unterminated literal cannot run past its own line in any of these languages.
        if (source[i - 1] === "\n") break;
      }
      continue;
    }
    if (startsWith(lineComment)) {
      while (i < source.length && source[i] !== "\n") i += 1;
      continue;
    }
    if (!python && startsWith("/*")) {
      const end = source.indexOf("*/", i + 2);
      const stop = end === -1 ? source.length : end + 2;
      // Newlines are preserved so that nothing downstream has to care that a comment was here.
      out += "\n".repeat((source.slice(i, stop).match(/\n/g) ?? []).length);
      i = stop;
      continue;
    }
    out += c;
    i += 1;
  }
  return out;
}

const sourceCache = new Map<string, string>();

function codeOf(relative: string): string {
  const cached = sourceCache.get(relative);
  if (cached !== undefined) return cached;
  const stripped = commentFreeSource(relative, read(relative));
  sourceCache.set(relative, stripped);
  return stripped;
}

const declares = (code: string, symbol: string) =>
  new RegExp(String.raw`(?<![\w$])${symbol}(?![\w$])`).test(code);

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * The tests
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

/**
 * THE ANTI-VACUITY FLOOR, WHICH EVERY REGISTER-CHECKING SPEC NEEDS AND WHICH THIS ONE NEEDS TWICE.
 *
 * A document that failed to parse, or one somebody emptied while "rewriting" it, would make every
 * assertion below pass over an empty list — the one way a parity check can lie. `text-format-parity-unit.spec.ts`
 * opens with the same floor over its shared vector table for the same reason.
 *
 * The floors are deliberately well under what the file holds rather than equal to it: this is a
 * tripwire against COLLAPSE, not a count of the matrix. A count would be a second copy of the
 * document's own size, which is the thing `docs/README.md` forbids writing down anywhere.
 */
test("the matrix parses, and neither client is missing from it", () => {
  expect(existsSync(join(ROOT, DOC)), `${DOC} is gone`).toBe(true);
  expect(ROWS.length, "no matrix rows parsed out of the document").toBeGreaterThan(12);
  expect(PINS.length, "no `path#Symbol` citations parsed out of the document").toBeGreaterThan(30);

  const web = PINS.filter((pin) => pin.path.startsWith("frontend/"));
  const android = PINS.filter((pin) => pin.path.startsWith("android/"));
  expect(web.length, "the matrix cites no web symbols at all").toBeGreaterThan(10);
  expect(android.length, "the matrix cites no Android symbols at all").toBeGreaterThan(10);

  // One matrix that cited one file per side would satisfy the floors above and describe nothing.
  const files = new Set(PINS.map((pin) => pin.path));
  expect(files.size, "the matrix leans on too few files to be a comparison").toBeGreaterThan(12);
});

/**
 * THE REGRESSION THIS FILE EXISTS FOR: a symbol the matrix names, gone from the tree.
 *
 * Reported all at once rather than failing on the first, because a divergence is usually a whole FILE
 * being renamed and seeing one row of nine tells a reader far less than seeing the nine.
 */
test("every symbol the matrix cites is declared, in code, in the file it cites", () => {
  const missing: string[] = [];
  for (const pin of PINS) {
    if (!existsSync(join(ROOT, pin.path))) {
      missing.push(`${pin.raw}\n  the file does not exist`);
      continue;
    }
    if (!declares(codeOf(pin.path), pin.symbol)) {
      missing.push(
        `${pin.raw}\n  \`${pin.symbol}\` is not in ${pin.path} outside its comments.\n` +
          "  Either the symbol was renamed — update the pin — or the capability moved, in which case\n" +
          "  the ROW is what needs rewriting, and its verdict with it."
      );
    }
  }
  expect(missing.join("\n\n"), `${DOC} cites symbols that are not in the tree`).toBe("");
});

/** Every row says one of the five things, and the legend defines all five. */
test("every row carries a verdict from the fixed vocabulary", () => {
  const wrong = ROWS.filter((row) => !VERDICTS.includes(row.verdict as Verdict)).map(
    (row) => `line ${row.line}: "${row.verdict}" — not one of ${VERDICTS.join(" / ")}`
  );
  expect(wrong.join("\n"), "a matrix row invented a verdict").toBe("");

  for (const verdict of VERDICTS) {
    expect(matrix.includes(`**${verdict}**`), `the legend no longer defines ${verdict}`).toBe(true);
  }
});

/**
 * A ROW WITH NO CITATION IS PROSE WEARING A TABLE, and prose is what this page replaced.
 *
 * The rule is at least one pin per row rather than one per CELL, deliberately: the cell that says a
 * client lacks something has nothing to cite, and demanding a pin there would push somebody into
 * citing a file that does not implement the thing just to satisfy a checker.
 */
test("every row cites at least one symbol", () => {
  const bare = ROWS.filter((row) => pinsIn(row.web).length + pinsIn(row.android).length === 0).map(
    (row) => `line ${row.line}: ${row.capability}`
  );
  expect(bare.join("\n"), "a matrix row asserts a comparison and cites nothing").toBe("");
});

/**
 * AN ABSENCE THAT NOBODY CAN RE-CHECK IS THE CLAIM THIS WHOLE PAGE EXISTS BECAUSE OF.
 *
 * "There is no ratings code anywhere under `android/app/src/main`" was true, then was not, and stayed
 * in the register for a release either way — because there was no date on it and no command under it.
 * `docs/tools/check-docs.mjs` reports exactly this shape in comments and asks for the dated form:
 * "true as of «2026-08-22»; check `«grep»`". A matrix cell claiming a client lacks a capability is
 * that shape, so it is held to that form here.
 *
 * NOT the claim itself — see this file's header for why no absence is ever asserted. The form.
 */
test("a row that says a client lacks something says when, and how to check", () => {
  const ISO_DATE = /\b20\d\d-\d\d-\d\d\b/;
  const RUNNABLE = /^(?:grep|rg|git|node|npx|npm|\.\/gradlew|python)\b/;
  const problems: string[] = [];

  for (const row of ROWS) {
    if (row.verdict === "BOTH") continue;
    const absent = row.verdict.startsWith("WEB ONLY") ? row.android : row.web;
    const side = row.verdict.startsWith("WEB ONLY") ? "Android" : "web";
    if (!ISO_DATE.test(absent)) {
      problems.push(
        `line ${row.line} (${row.capability}): the ${side} cell claims an absence with no date on it.`
      );
    }
    if (!codeSpans(absent).some((span) => RUNNABLE.test(span.trim()))) {
      problems.push(
        `line ${row.line} (${row.capability}): the ${side} cell claims an absence with no command that settles it.`
      );
    }
  }
  expect(problems.join("\n"), "an absence in the matrix cannot be re-checked by anybody").toBe("");
});

/**
 * THE STANDING INSTRUCTION, AS AN ASSERTION: THE CHOOSER STAYS A CHOOSER.
 *
 * `SketchesAndPrototypesScreen.kt`'s own header argues it, and the argument is about the archive
 * rather than about layout: "A screen here that let a designer add a sketch would be one feature with
 * two stores, and the one it wrote to would be the one the report did not read."
 *
 * THE PROBE IS BY NAME AND EVERY NAME IS THE ONE THE HANDSET ALREADY USES for that job elsewhere, so
 * this is not a guess about a future spelling — it is the list of doors that exist. A capture card, the
 * straightening panel, the file the panel writes a plate into, and the draft store every stage write
 * goes through. Somebody adding an editor here reaches for one of them, because they are what the
 * stage screen is built out of.
 *
 * It is paired with the positive half. A screen that refuses to write and also stopped navigating
 * would pass an absence check while being useless, so the two stage keys it hands over are asserted
 * in the same test.
 */
test("the handset's Upload tab writes through the stage's own store and mints no second one", () => {
  /*
    ══════════════════════════════════════════════════════════════════════════════════════════════
    THIS TEST USED TO ASSERT THE OPPOSITE, AND IT WAS RIGHT UNTIL 2026-08-28
    ══════════════════════════════════════════════════════════════════════════════════════════════

    It read `SketchesAndPrototypesScreen.kt` and failed if that file so much as NAMED
    `DwMediaCaptureCard`, `DwSketchRectifyPanel`, `newCaptureFile` or `WorkshopDraftStore`, under the
    message "the chooser has grown an editor". The owner then asked for exactly that capability —
    *"Provide an option to add a Sketch or Prototype directly to the selected workshop from this
    screen"* — and the handset grew the web's Upload/Review tabs.

    **THE OLD ASSERTION WOULD HAVE PASSED ANYWAY, AND THAT IS WHY IT IS REWRITTEN RATHER THAN
    DELETED.** The capture card lives in `DwSketchChooserUpload.kt`, a file this test never opened,
    so a green run would have been reporting on a screen that no longer exists in that shape. A
    check that passes because the code moved is worse than no check: it is a claim, still being made,
    about something nobody is looking at.

    ══════════════════════════════════════════════════════════════════════════════════════════════
    WHAT THE ORIGINAL RULE ACTUALLY FORBADE, WHICH IS STILL FORBIDDEN
    ══════════════════════════════════════════════════════════════════════════════════════════════

    A SECOND STORE. `SketchesAndPrototypesScreen.kt`'s own words: "one feature with two stores, and
    the one it wrote to would be the one the report did not read." The web found the resolution first
    and this page records it — `UploadTabHost` "picks an existing row and writes through the same
    draft store the stage form uses, so the web is not the thing this comment forbids."

    So the property is no longer "does not write". It is **writes through the ONE path**, and that is
    what is asserted here: `WorkshopDraftStore.updateStage` for the row, `WorkshopDraftStore.importMedia`
    for the bytes, `WorkshopSyncEngine.pushStage` for the hop to the repository — the same three the
    stage screen uses — and nothing that looks like a private collection or a bespoke endpoint.
  */
  const CHOOSER =
    "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/SketchesAndPrototypesScreen.kt";
  const UPLOAD =
    "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchChooserUpload.kt";
  expect(existsSync(join(ROOT, CHOOSER)), "the chooser screen is gone").toBe(true);
  expect(
    existsSync(join(ROOT, UPLOAD)),
    "the Upload tab's file is gone — if it was renamed, rename it here too, or this test is " +
      "asserting about a file that does not exist and will pass for ever"
  ).toBe(true);

  const upload = codeOf(UPLOAD);

  // THE ONE PATH, named. Each of these is what the stage screen itself calls.
  for (const [symbol, what] of [
    ["WorkshopDraftStore.updateStage", "the row write every stage form goes through"],
    ["WorkshopDraftStore.importMedia", "the copy into the workshop's own media directory"],
    ["WorkshopSyncEngine.pushStage", "the one place a stage becomes a payload"]
  ] as const) {
    expect(
      upload.includes(symbol),
      `the Upload tab no longer calls ${symbol} — ${what}. If the write moved, it must have moved ` +
        "to the stage's own path and not to a second one; check before changing this line."
    ).toBe(true);
  }

  // AND NO SECOND STORE. A private table, a bespoke endpoint or a parallel collection is the thing
  // the original rule forbade, and it is still forbidden.
  for (const forbidden of ["SharedPreferences", "Room.databaseBuilder", "/sketches", "/prototypes"]) {
    expect(
      upload.includes(forbidden),
      `the Upload tab reaches for ${forbidden}. A sketch is a row of the stage's own collection; a ` +
        "second store is the one thing this feature may not have."
    ).toBe(false);
  }

  // And the chooser still names both stages, which is what decides where a row is filed.
  const code = codeOf(CHOOSER);
  expect(code.includes("SKETCH_DEVELOPMENT"), "the chooser no longer knows stage 11").toBe(true);
  expect(code.includes("PROTOTYPE_DEVELOPMENT"), "the chooser no longer knows stage 13").toBe(true);
});

/**
 * THE DOCS GATE'S OWN RULE, ASSERTED HERE TOO because its failure is silent from this side.
 *
 * `docs/tools/check-docs.mjs` requires every file in `docs/` to be listed in `docs/README.md` and to
 * carry a maintenance section — "the one failure mode an index cannot check by reading itself". That
 * check runs in a different command from this one, and a matrix that has fallen out of the index is a
 * matrix nobody will find, however green this file is.
 */
test("the matrix is indexed, and says how it is kept true", () => {
  expect(read("docs/README.md").includes("SKETCHES-PROTOTYPES-PARITY.md")).toBe(true);
  expect(matrix.includes("## How this document is kept true")).toBe(true);
});
