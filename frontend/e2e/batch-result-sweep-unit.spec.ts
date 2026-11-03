import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { MediaBatchError, type BatchResult } from "@/lib/media";

const FRONTEND = join(__dirname, "..");

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
  shape: "destructure" | "identifier" | "then" | "escapes" | "unreadable";
  names: string[] | null;
  detail: string;
};

function destructuredNames(pattern: string): string[] {
  return [...pattern.matchAll(/([A-Za-z_$][\w$]*)\s*(?::|=|,|\})/g)].map((match) => match[1]);
}

function readsThrough(text: string, identifier: string): string[] {
  const escaped = identifier.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const names = [...text.matchAll(new RegExp(`\\b${escaped}\\.([A-Za-z_$][\\w$]*)`, "g"))].map((m) => m[1]);
  for (const match of text.matchAll(new RegExp(`(\\{[^{}]*\\})\\s*=\\s*${escaped}\\b`, "g"))) {
    names.push(...destructuredNames(match[1]));
  }
  return names;
}

function callSites(): CallSite[] {
  const sites: CallSite[] = [];
  for (const file of sourceFiles()) {
    const name = relative(FRONTEND, file).replace(/\\/g, "/");
    if (name === "lib/media.ts") continue;
    const raw = readFileSync(file, "utf8");
    if (!/\buploadMediaBatch\s*\(/.test(raw)) continue;
    const text = withoutComments(raw);

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
      if (/(?:^|[;{}(,]|=>|\breturn)$/.test(before.trimEnd())) {
        sites.push({ where, shape: "escapes", names: [], detail: before.trimEnd().slice(-24) });
        continue;
      }
      sites.push({ where, shape: "unreadable", names: null, detail: before.trimEnd().slice(-48) });
    }
  }
  return sites;
}

test("`outcomes` is one entry per input file, at its position, carrying the file", () => {
  const a = new File(["a"], "IMG_0001.jpg");
  const b = new File(["b"], "IMG_0001.jpg");
  const c = new File(["c"], "IMG_0002.jpg");
  const landed = { id: "m2" } as never;

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
  expect(result.uploaded).toHaveLength(1);
  expect(result.outcomes[1].media, "and the honest reading needs no counter at all").toBe(landed);
});

test("a batch that landed nothing throws, and carries every per-file cause out with it", () => {
  const error = new MediaBatchError("All 2 media file(s) failed to upload (Unsupported media type).", [
    { name: "a.jpg", error: "Unsupported media type", cause: new ApiError(415, "Unsupported media type", null) },
    { name: "b.jpg", error: "Payload too large", cause: new ApiError(413, "Payload too large", null) }
  ]);
  expect(error.failures).toHaveLength(2);
  expect((error as Error & { cause?: unknown }).cause).toBeInstanceOf(ApiError);
});

test("no caller reads `uploaded` without also reading what did NOT upload", () => {
  const offenders = callSites().filter((site) => {
    if (!site.names) return false;
    if (!site.names.includes("uploaded")) return false;
    return !site.names.includes("failed") && !site.names.includes("outcomes");
  });
  expect(
    offenders.map((site) => `${site.where}  reads ${site.names?.join(", ")}`),
    "uploadMediaBatch RESOLVES on a partly-failed batch: take `failed` (or `outcomes`) and say what did not make it"
  ).toEqual([]);
});

test("the sweep sees every call shape, including the two that used to walk past it", () => {
  const sites = callSites();
  expect(sites.length, "uploadMediaBatch has callers and this file found them").toBeGreaterThanOrEqual(10);
  expect(
    sites.filter((site) => site.shape === "unreadable").map((site) => `${site.where}  ${site.detail}`),
    "this sweep does not understand these call shapes — extend `callSites` rather than leaving them unchecked"
  ).toEqual([]);
  for (const site of sites) {
    if (site.shape === "identifier" || site.shape === "destructure") {
      expect(site.names?.length, `${site.where} binds the result but nothing was read off it`).toBeGreaterThan(0);
    }
  }
});

test("the two spellings that used to walk past this sweep are caught", () => {
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
  const offenders: string[] = [];
  for (const file of sourceFiles()) {
    const name = relative(FRONTEND, file).replace(/\\/g, "/");
    if (name === "lib/media.ts") continue;
    readFileSync(file, "utf8")
      .split("\n")
      .forEach((line, index) => {
        const code = line.trimStart();
        if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return;
        if (/\buploaded\.(forEach|map)\(\s*\(\s*\w+\s*,\s*\w+/.test(line)) offenders.push(`${name}:${index + 1}`);
        if (/\buploaded\[(?!\s*\d)/.test(line)) offenders.push(`${name}:${index + 1}`);
      });
  }
  expect(
    offenders,
    "`uploaded` is compacted — its positions are not the caller's. Read `outcomes`, which carries the File."
  ).toEqual([]);
});
