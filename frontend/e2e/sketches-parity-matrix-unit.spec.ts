import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

import { expect, test } from "@playwright/test";

const ROOT = resolve(__dirname, "..", "..");
const DOC = "docs/SKETCHES-PROTOTYPES-PARITY.md";

const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

const matrix = read(DOC);

const VERDICTS = [
  "BOTH",
  "WEB ONLY (deliberate)",
  "WEB ONLY (gap)",
  "ANDROID ONLY (deliberate)",
  "ANDROID ONLY (gap)"
] as const;

type Verdict = (typeof VERDICTS)[number];

const PIN_RE = /^([\w.\-/()[\]]+\.(?:ts|tsx|kt|py|json))#([A-Za-z_][A-Za-z0-9_]*)$/;

type Pin = { path: string; symbol: string; raw: string };

function codeSpans(markdown: string): string[] {
  return [...markdown.matchAll(/`([^`\n]+)`/g)].map((m) => m[1]);
}

const pinsIn = (fragment: string): Pin[] =>
  codeSpans(fragment)
    .map((raw) => {
      const m = raw.match(PIN_RE);
      return m ? { path: m[1], symbol: m[2], raw } : null;
    })
    .filter((pin): pin is Pin => pin !== null);

const PINS = pinsIn(matrix);

type Row = { capability: string; web: string; android: string; verdict: string; line: number };

function matrixRows(markdown: string): Row[] {
  const rows: Row[] = [];
  markdown.split("\n").forEach((line, index) => {
    const trimmed = line.trim();
    if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) return;
    const cells = trimmed.slice(1, -1).split("|").map((cell) => cell.trim());
    if (cells.length !== 4) return;
    if (cells.every((cell) => /^:?-{2,}:?$/.test(cell))) return;
    if (cells[3] === "Verdict") return;
    const verdict = cells[3].replace(/\*/g, "").trim();
    rows.push({ capability: cells[0], web: cells[1], android: cells[2], verdict, line: index + 1 });
  });
  return rows;
}

const ROWS = matrixRows(matrix);

function commentFreeSource(relative: string, source: string): string {
  const extension = relative.slice(relative.lastIndexOf(".") + 1).toLowerCase();
  if (extension === "json") return source;

  const python = extension === "py";
  const lineComment = python ? "#" : "//";
  let i = 0;
  let out = "";

  const startsWith = (token: string) => source.startsWith(token, i);

  while (i < source.length) {
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

test("the matrix parses, and neither client is missing from it", () => {
  expect(existsSync(join(ROOT, DOC)), `${DOC} is gone`).toBe(true);
  expect(ROWS.length, "no matrix rows parsed out of the document").toBeGreaterThan(12);
  expect(PINS.length, "no `path#Symbol` citations parsed out of the document").toBeGreaterThan(30);

  const web = PINS.filter((pin) => pin.path.startsWith("frontend/"));
  const android = PINS.filter((pin) => pin.path.startsWith("android/"));
  expect(web.length, "the matrix cites no web symbols at all").toBeGreaterThan(10);
  expect(android.length, "the matrix cites no Android symbols at all").toBeGreaterThan(10);

  const files = new Set(PINS.map((pin) => pin.path));
  expect(files.size, "the matrix leans on too few files to be a comparison").toBeGreaterThan(12);
});

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

test("every row carries a verdict from the fixed vocabulary", () => {
  const wrong = ROWS.filter((row) => !VERDICTS.includes(row.verdict as Verdict)).map(
    (row) => `line ${row.line}: "${row.verdict}" — not one of ${VERDICTS.join(" / ")}`
  );
  expect(wrong.join("\n"), "a matrix row invented a verdict").toBe("");

  for (const verdict of VERDICTS) {
    expect(matrix.includes(`**${verdict}**`), `the legend no longer defines ${verdict}`).toBe(true);
  }
});

test("every row cites at least one symbol", () => {
  const bare = ROWS.filter((row) => pinsIn(row.web).length + pinsIn(row.android).length === 0).map(
    (row) => `line ${row.line}: ${row.capability}`
  );
  expect(bare.join("\n"), "a matrix row asserts a comparison and cites nothing").toBe("");
});

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

test("the handset's Upload tab writes through the stage's own store and mints no second one", () => {
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

  for (const forbidden of ["SharedPreferences", "Room.databaseBuilder", "/sketches", "/prototypes"]) {
    expect(
      upload.includes(forbidden),
      `the Upload tab reaches for ${forbidden}. A sketch is a row of the stage's own collection; a ` +
        "second store is the one thing this feature may not have."
    ).toBe(false);
  }

  const code = codeOf(CHOOSER);
  expect(code.includes("SKETCH_DEVELOPMENT"), "the chooser no longer knows stage 11").toBe(true);
  expect(code.includes("PROTOTYPE_DEVELOPMENT"), "the chooser no longer knows stage 13").toBe(true);
});

test("the matrix is indexed, and says how it is kept true", () => {
  expect(read("docs/README.md").includes("SKETCHES-PROTOTYPES-PARITY.md")).toBe(true);
  expect(matrix.includes("## How this document is kept true")).toBe(true);
});
