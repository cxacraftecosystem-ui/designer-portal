import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { MediaBatchError, type BatchResult } from "@/lib/media";

/**
 * A RESOLVED `uploadMediaBatch` IS NOT A SUCCESSFUL ONE, AND NO CALLER MAY READ IT AS ONE.
 *
 * ── THE CLASS ───────────────────────────────────────────────────────────────────────────────────
 *
 * `uploadMediaBatch` throws only when NOTHING landed. A batch of four photographs in which the
 * server refused one — 413 on the one over the size limit, 415 on the one the phone wrote as heic —
 * RESOLVES, with the refusal sitting in `failed`. So
 *
 *     const { uploaded } = await uploadMediaBatch({ … });
 *
 * reads exactly like success, compiles, and silently drops a photograph the artisan went home before
 * we could retake. Thirteen call sites across twelve files reach this helper (measured by the sweep
 * below, which is why it also asserts that it found them). The ones that did not guard it produced
 * two ship-blockers; those instances are fixed — this sweep is what stops the next one.
 *
 * The second half of the same class is INDEX ARITHMETIC on top of the resolved value. `uploaded` is
 * COMPACTED: it is the by-index array with its nulls filtered out, so its ORDER is the caller's and
 * its POSITIONS are not. A caller that walked it with a counter into its own `File[]` recorded every
 * surviving file against the wrong one — `FieldInput` did, and its reader is `IdentityCardReader`,
 * which prints a file's name beside the identity number it OCR'd out of the bytes. The misfile
 * showed a designer one photograph's name over another photograph's identity number, on identity
 * data, defeating the one cross-check that surface relies on.
 *
 * `BatchResult.outcomes` is the answer to both: one entry per input file, at its position, WITH THE
 * FILE ATTACHED, so there is no second array to line up and nothing to compact. The assertions below
 * pin that shape; the sweep after them pins that callers read something honest.
 */

const FRONTEND = join(__dirname, "..");

/** Every TypeScript source of the web client — the population a caller could be hiding in. */
function sourceFiles(): string[] {
  const found: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === "node_modules" || entry.name === ".next") continue;
      const full = join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (/\.tsx?$/.test(entry.name) && !entry.name.endsWith(".spec.ts")) found.push(full);
    }
  };
  for (const top of ["lib", "app", "components"]) walk(join(FRONTEND, top));
  return found;
}

/**
 * The source with every comment blanked to spaces, so offsets and line numbers still line up.
 *
 * WHY A SCANNER AND NOT A LINE FILTER. The first version of this sweep skipped comment LINES and read
 * a four-line window, which meant it could only see a binding written in the same statement as the
 * call. `const res = await uploadMediaBatch({…}); const { uploaded } = res;` — the most ordinary
 * spelling of the defect this file exists to catch — went straight through it, and so did
 * `uploadMediaBatch({…}).then(({ uploaded }) => …)`. Both were measured against a probe module, not
 * argued about. Following a binding needs the whole file, and reading the whole file needs the
 * comments gone: this repository's comments are long, contain equals signs, and name the helper.
 *
 * Strings and template literals are tracked so a `//` inside one cannot start a comment; regex
 * literals are recognised by the standard "what may precede a division" heuristic. Any mistake here
 * REMOVES code, which could hide a call site — so the caller cross-checks the count against a raw
 * scan and fails if they disagree, rather than trusting this.
 */
function withoutComments(source: string): string {
  const out = source.split("");
  const blank = (from: number, to: number) => {
    for (let at = from; at < to; at += 1) if (out[at] !== "\n") out[at] = " ";
  };
  let i = 0;
  let previous = "";
  while (i < source.length) {
    const here = source[i];
    const two = source.slice(i, i + 2);
    if (two === "//") {
      const end = source.indexOf("\n", i);
      blank(i, end === -1 ? source.length : end);
      i = end === -1 ? source.length : end;
      continue;
    }
    if (two === "/*") {
      const end = source.indexOf("*/", i + 2);
      blank(i, end === -1 ? source.length : end + 2);
      i = end === -1 ? source.length : end + 2;
      continue;
    }
    if (here === "'" || here === '"' || here === "`") {
      i += 1;
      while (i < source.length) {
        if (source[i] === "\\") { i += 2; continue; }
        if (source[i] === here) { i += 1; break; }
        i += 1;
      }
      previous = here;
      continue;
    }
    // A `/` is a regex only where a value may not appear — otherwise it is division.
    if (here === "/" && (previous === "" || "(,=:[!&|?{};+-*%<>~^".includes(previous))) {
      i += 1;
      while (i < source.length && source[i] !== "\n") {
        if (source[i] === "\\") { i += 2; continue; }
        if (source[i] === "[") { while (i < source.length && source[i] !== "]") i += 1; }
        if (source[i] === "/") { i += 1; break; }
        i += 1;
      }
      previous = "/";
      continue;
    }
    if (!/\s/.test(here)) previous = here;
    i += 1;
  }
  return out.join("");
}

/** The index just past the `)` that closes the `(` at `open`. */
function closingParen(text: string, open: number): number {
  let depth = 0;
  for (let at = open; at < text.length; at += 1) {
    if (text[at] === "(") depth += 1;
    else if (text[at] === ")") {
      depth -= 1;
      if (depth === 0) return at + 1;
    }
  }
  return text.length;
}

type CallSite = {
  where: string;
  /** How the result is consumed. `unreadable` fails the sweep — it is never skipped. */
  shape: "destructure" | "identifier" | "then" | "escapes" | "unreadable";
  /** Every property of the result this call site observably reads, or null when that cannot be read. */
  names: string[] | null;
  detail: string;
};

/** The property names inside a `{ … }` binding pattern, ignoring renames and defaults. */
function destructuredNames(pattern: string): string[] {
  return [...pattern.matchAll(/([A-Za-z_$][\w$]*)\s*(?::|=|,|\})/g)].map((match) => match[1]);
}

/**
 * Everything read off an identifier the result was bound to, anywhere in the same file.
 *
 * FILE-WIDE AND NOT FUNCTION-WIDE, which is imprecise in one direction and is stated rather than
 * hidden: a second variable of the same name elsewhere in the file could contribute a `failed` that
 * exonerates a caller that never reads one. Scoping it properly needs a parser. The imprecision only
 * bites where a file binds two `uploadMediaBatch` results to the same identifier and guards one of
 * them, which no file here does — and the alternative, matching nothing at all, is what the previous
 * version did.
 */
function readsThrough(text: string, identifier: string): string[] {
  const escaped = identifier.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const names = [...text.matchAll(new RegExp(`\\b${escaped}\\.([A-Za-z_$][\\w$]*)`, "g"))].map((m) => m[1]);
  for (const match of text.matchAll(new RegExp(`(\\{[^{}]*\\})\\s*=\\s*${escaped}\\b`, "g"))) {
    names.push(...destructuredNames(match[1]));
  }
  return names;
}

/**
 * Every `uploadMediaBatch` call in the web client, and what its caller observably reads off the result.
 *
 * FOUR SHAPES ARE UNDERSTOOD and everything else FAILS rather than passing quietly:
 *
 *   `const { … } = await uploadMediaBatch(…)`   the names are right there.
 *   `x = await uploadMediaBatch(…)`             followed to every `x.name` and `{ … } = x` in the file.
 *   `uploadMediaBatch(…).then(({ … }) => …)`    the names are in the callback's parameter.
 *   a bare statement, a `return`, an argument     the result escapes this function unread; there is no
 *                                               `uploaded` here to misread, so there is no defect here.
 *
 * A sweep that silently passes on a shape it does not understand is worth nothing — that is exactly
 * how the previous version of this function let the two commonest spellings through — so the fifth
 * bucket is `unreadable` and it is asserted empty.
 */
function callSites(): CallSite[] {
  const sites: CallSite[] = [];
  for (const file of sourceFiles()) {
    const name = relative(FRONTEND, file).replace(/\\/g, "/");
    if (name === "lib/media.ts") continue; // Where it is declared, not called.
    const raw = readFileSync(file, "utf8");
    if (!/\buploadMediaBatch\s*\(/.test(raw)) continue;
    const text = withoutComments(raw);

    // THE CROSS-CHECK ON THE STRIPPER. If blanking the comments lost a call, every conclusion below
    // is drawn from a file this test cannot actually read — so say so instead of reporting zero.
    const rawCalls = raw
      .split("\n")
      .filter((line) => {
        const code = line.trimStart();
        return !code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*");
      })
      .join("\n")
      .match(/\buploadMediaBatch\s*\(/g);
    const strippedCalls = text.match(/\buploadMediaBatch\s*\(/g);
    if ((rawCalls?.length ?? 0) !== (strippedCalls?.length ?? 0)) {
      sites.push({
        where: name,
        shape: "unreadable",
        names: null,
        detail: `comment stripper disagrees with a raw scan (${rawCalls?.length ?? 0} vs ${strippedCalls?.length ?? 0})`
      });
      continue;
    }

    for (const match of text.matchAll(/\buploadMediaBatch\s*\(/g)) {
      const start = match.index!;
      const where = `${name}:${text.slice(0, start).split("\n").length}`;
      const before = text.slice(0, start).replace(/\s*(?:await\s*)?$/, "");
      const after = text.slice(closingParen(text, start + match[0].length - 1));

      const chained = /^\s*\.then\s*\(\s*(?:async\s*)?\(?\s*(\{[^{}]*\}|[A-Za-z_$][\w$]*)/.exec(after);
      if (chained) {
        const bound = chained[1];
        const body = after.slice(0, closingParen(after, after.indexOf("(", after.indexOf(".then"))));
        sites.push({
          where,
          shape: "then",
          names: bound.startsWith("{") ? destructuredNames(bound) : readsThrough(body, bound),
          detail: `.then(${bound}`
        });
        continue;
      }
      const destructured = /(\{[^{}]*\})\s*=$/.exec(before);
      if (destructured) {
        sites.push({ where, shape: "destructure", names: destructuredNames(destructured[1]), detail: destructured[1] });
        continue;
      }
      const identifier = /(?:^|[^\w$.])([A-Za-z_$][\w$]*)\s*=$/.exec(before);
      if (identifier) {
        sites.push({ where, shape: "identifier", names: readsThrough(text, identifier[1]), detail: identifier[1] });
        continue;
      }
      // Nothing is bound: a bare statement, a `return`, or an argument to something else. The result
      // leaves this function unread, so no caller here can mistake a partly-failed batch for a whole one.
      if (/(?:^|[;{}(,]|=>|\breturn)$/.test(before.trimEnd())) {
        sites.push({ where, shape: "escapes", names: [], detail: before.trimEnd().slice(-24) });
        continue;
      }
      sites.push({ where, shape: "unreadable", names: null, detail: before.trimEnd().slice(-48) });
    }
  }
  return sites;
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The shape a caller cannot mis-read
 * ──────────────────────────────────────────────────────────────────────────── */

test("`outcomes` is one entry per input file, at its position, carrying the file", () => {
  // Two photographs off one handset with the SAME NAME, which is ordinary, plus one more. If any
  // part of this pipeline matched on `file.name` it would misfile a and b and look like it worked.
  const a = new File(["a"], "IMG_0001.jpg");
  const b = new File(["b"], "IMG_0001.jpg");
  const c = new File(["c"], "IMG_0002.jpg");
  const landed = { id: "m2" } as never;

  // The value `uploadMediaBatch` returns for "a and c refused, b landed" — built by hand because the
  // shape is what is under test, not the transport.
  const result: BatchResult = {
    outcomes: [
      { file: a, media: null, failure: { name: "IMG_0001.jpg", error: "Payload too large", cause: new ApiError(413, "…", null) } },
      { file: b, media: landed, failure: null },
      { file: c, media: null, failure: { name: "IMG_0002.jpg", error: "Unsupported media type", cause: new ApiError(415, "…", null) } }
    ],
    uploaded: [landed],
    failed: [
      { name: "IMG_0001.jpg", error: "Payload too large", cause: new ApiError(413, "…", null) },
      { name: "IMG_0002.jpg", error: "Unsupported media type", cause: new ApiError(415, "…", null) }
    ],
    uploadedByIndex: [null, landed, null]
  };

  expect(result.outcomes.map((outcome) => outcome.file), "position for position with the input").toEqual([a, b, c]);
  for (const outcome of result.outcomes) {
    expect(
      (outcome.media === null) !== (outcome.failure === null),
      "exactly one of media/failure is set — a file that neither landed nor failed is a lost file"
    ).toBe(true);
  }
  // THE ZIP THAT USED TO BE POSSIBLE. `uploaded` has one entry and the input has three, so walking
  // it with a counter into `[a, b, c]` reads `a` for a result that belongs to `b`.
  expect(result.uploaded).toHaveLength(1);
  expect(result.outcomes[1].media, "and the honest reading needs no counter at all").toBe(landed);
});

test("a batch that landed nothing throws, and carries every per-file cause out with it", () => {
  const error = new MediaBatchError("All 2 media file(s) failed to upload (Unsupported media type).", [
    { name: "a.jpg", error: "Unsupported media type", cause: new ApiError(415, "Unsupported media type", null) },
    { name: "b.jpg", error: "Payload too large", cause: new ApiError(413, "Payload too large", null) }
  ]);
  expect(error.failures).toHaveLength(2);
  // `cause` is the FIRST, which is the whole of it for the one-file batch both drains send.
  expect((error as Error & { cause?: unknown }).cause).toBeInstanceOf(ApiError);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The sweep — the actual deliverable
 * ──────────────────────────────────────────────────────────────────────────── */

test("no caller reads `uploaded` without also reading what did NOT upload", () => {
  /*
    THE ONE RULE. A caller may take the whole result (and then read it however it likes), or it may
    destructure — but a destructure that names `uploaded` and not `failed` or `outcomes` is a caller
    that has decided a partly-failed batch is a success. That is the ship-blocker, twice.

    A caller that takes ONLY `failed` is fine: it is not claiming anything about what landed.
  */
  const offenders = callSites().filter((site) => {
    if (!site.names) return false; // Reported by the test below, which is where an unreadable shape fails.
    if (!site.names.includes("uploaded")) return false;
    return !site.names.includes("failed") && !site.names.includes("outcomes");
  });
  expect(
    offenders.map((site) => `${site.where}  reads ${site.names?.join(", ")}`),
    "uploadMediaBatch RESOLVES on a partly-failed batch: take `failed` (or `outcomes`) and say what did not make it"
  ).toEqual([]);
});

test("the sweep sees every call shape, including the two that used to walk past it", () => {
  /*
    A SWEEP THAT MATCHES NOTHING PASSES, and a sweep that matches less than its own docstring claims
    is worse: the docstring is what the next author trusts. This one asserts three things about
    itself — that it found the callers, that it understood every one of them, and that it catches the
    two spellings the first version silently allowed, which were measured against a probe module
    rather than reasoned about:

        const res = await uploadMediaBatch({…});  const { uploaded } = res;   // passed clean
        uploadMediaBatch({…}).then(({ uploaded }) => uploaded.length);        // passed clean

    Both are exercised below against synthetic sources, because the tree is (correctly) free of them
    and a guard nobody has ever seen fail is not a guard.
  */
  const sites = callSites();
  expect(sites.length, "uploadMediaBatch has callers and this file found them").toBeGreaterThanOrEqual(10);
  expect(
    sites.filter((site) => site.shape === "unreadable").map((site) => `${site.where}  ${site.detail}`),
    "this sweep does not understand these call shapes — extend `callSites` rather than leaving them unchecked"
  ).toEqual([]);
  // Every recognised site read SOMETHING or deliberately escaped; a recognised site with no names and
  // a shape that binds the result would mean the follower silently found nothing.
  for (const site of sites) {
    if (site.shape === "identifier" || site.shape === "destructure") {
      expect(site.names?.length, `${site.where} binds the result but nothing was read off it`).toBeGreaterThan(0);
    }
  }
});

test("the two spellings that used to walk past this sweep are caught", () => {
  // The offender rule, applied to the shapes rather than to the tree — `callSites` reads real files,
  // so the shapes are exercised through the same helpers it composes.
  const viaVariable = `
    async function save() {
      const res = await uploadMediaBatch({ files });
      const { uploaded } = res;
      return uploaded.length;
    }
  `;
  const viaThen = `
    function save() {
      return uploadMediaBatch({ files }).then(({ uploaded }) => uploaded.length);
    }
  `;
  expect(readsThrough(viaVariable, "res"), "a binding is followed to the destructure that reads it").toEqual(
    expect.arrayContaining(["uploaded"])
  );
  expect(readsThrough(viaVariable, "res")).not.toEqual(expect.arrayContaining(["failed"]));
  expect(
    destructuredNames(/\.then\s*\(\s*\(?\s*(\{[^{}]*\})/.exec(viaThen)![1]),
    "and a `.then` callback's parameter is read the same way"
  ).toEqual(["uploaded"]);
});

test("no caller zips the COMPACTED array against its own files", () => {
  /*
    THE SECOND HALF OF THE CLASS. `uploaded.forEach((media, index) => …)` and `uploaded[index]` are
    the two spellings of the defect that misfiled identity photographs. Reading `uploaded[0]` is
    something else and is allowed: a one-file batch has one landed file and no positions to confuse.
  */
  const offenders: string[] = [];
  for (const file of sourceFiles()) {
    const name = relative(FRONTEND, file).replace(/\\/g, "/");
    if (name === "lib/media.ts") continue;
    readFileSync(file, "utf8")
      .split("\n")
      .forEach((line, index) => {
        const code = line.trimStart();
        if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return;
        // A second parameter to forEach/map over `uploaded` is a position, and its positions are lies.
        if (/\buploaded\.(forEach|map)\(\s*\(\s*\w+\s*,\s*\w+/.test(line)) offenders.push(`${name}:${index + 1}`);
        // `uploaded[<not a literal>]` is an index from somewhere else.
        if (/\buploaded\[(?!\s*\d)/.test(line)) offenders.push(`${name}:${index + 1}`);
      });
  }
  expect(
    offenders,
    "`uploaded` is compacted — its positions are not the caller's. Read `outcomes`, which carries the File."
  ).toEqual([]);
});
