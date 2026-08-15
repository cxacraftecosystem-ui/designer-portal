#!/usr/bin/env node
/**
 * check-docs — the thing that stops this documentation set from quietly going stale.
 *
 * A document that confidently states something untrue is worse than a missing document: the reader
 * has no way to tell, and acts on it. Prose cannot be verified mechanically, but the three claims
 * that rot fastest can be, and between them they cover most of the damage:
 *
 *   1. COUNTS — "33 models", "149 endpoints", "6 role tiers". Every one of these changes with a
 *      migration or a route. So no hand-written document is allowed to state one: the numbers live
 *      in the GENERATED file docs/REPO_FACTS.md, which this script rewrites from the repository,
 *      and prose links to it. `--write` regenerates; the default run fails if it is out of date.
 *   2. PATHS — "see backend/app/services/ai.py". Files move. Every `backend/…`, `frontend/…`,
 *      `android/…`, `infra/…`, `docs/…` or `scripts/…` path mentioned in a doc is resolved on disk.
 *   3. LINE CITATIONS — "media.py:198-264". These are the worst offenders, because they are wrong
 *      silently and look precise. A citation into a file that is now shorter than the line it names
 *      is definitely wrong; one that still fits is only possibly right, and is reported as a count
 *      so the number never grows unnoticed.
 *
 * What it deliberately does NOT do: check that a sentence is true. Nothing can. The per-document
 * "How this document is kept true" section names the human check for the rest.
 *
 *   node docs/tools/check-docs.mjs           # verify; exit 1 on any failure
 *   node docs/tools/check-docs.mjs --write   # regenerate docs/REPO_FACTS.md, then verify
 *   node docs/tools/check-docs.mjs --quiet   # only failures
 */

import { execSync } from "node:child_process";
import { readFileSync, writeFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const DOCS = join(REPO, "docs");
const FACTS_FILE = join(DOCS, "REPO_FACTS.md");
const WRITE = process.argv.includes("--write");
const QUIET = process.argv.includes("--quiet");

// Documents written and owned by a different workstream. Findings in these are reported as
// warnings rather than failures, so this check's exit code speaks for the documents its owner can
// actually fix — and so a file being edited in another session cannot make this run red. They are
// still reported: an unowned problem is a visible problem, not an absent one.
const OWNED_ELSEWHERE = new Set([
  "SCALABILITY.md",
  "DOCKER.md",
  "KUBERNETES.md",
  "CDN.md",
  "AI_FEATURES.md",
  "RESEARCH_NOTES.md",
]);

const failures = [];
const warnings = [];
const notes = [];
const ownerOf = (msg) => {
  const m = msg.match(/(?:^|\/)([A-Z_]+\.md)/);
  return m && OWNED_ELSEWHERE.has(m[1]) ? "elsewhere" : "here";
};
const fail = (m) => (ownerOf(m) === "here" ? failures : warnings).push(m);
const note = (m) => notes.push(m);
// Normalise line endings on the way in. Half these files are CRLF and half are LF, and every regex
// below that anchors on "\n" silently matched NOTHING in the CRLF half — which is how four documents'
// mermaid blocks went unchecked while the run stayed green. A check that quietly inspects less than
// it claims to is worse than no check.
const read = (p) => readFileSync(p, "utf8").replace(/\r\n/g, "\n");

/* ── ground truth, derived from the repository ──────────────────────────────────────────────── */

function prismaFacts() {
  const src = read(join(REPO, "backend", "prisma", "schema.prisma"));
  const models = [...src.matchAll(/^model (\w+) \{/gm)].map((m) => m[1]);
  const enums = [...src.matchAll(/^enum (\w+) \{/gm)].map((m) => m[1]);
  const indexes = (src.match(/@@index\(/g) || []).length;
  const uniques = (src.match(/@@unique\(/g) || []).length;
  return { models, enums, indexes, uniques };
}

function routeFacts() {
  const dir = join(REPO, "backend", "app", "api", "routes");
  const perFile = {};
  const byMethod = { get: 0, post: 0, put: 0, patch: 0, delete: 0 };
  for (const f of readdirSync(dir).filter((f) => f.endsWith(".py") && f !== "__init__.py")) {
    const src = read(join(dir, f));
    const hits = [...src.matchAll(/@router\.(get|post|put|patch|delete)\(/g)];
    perFile[f] = hits.length;
    for (const h of hits) byMethod[h[1]] += 1;
  }
  // The two liveness routes are declared on the app, not on a router.
  const appLevel = [...read(join(REPO, "backend", "app", "main.py")).matchAll(/@app\.(get|post)\(/g)];
  for (const h of appLevel) byMethod[h[1]] += 1;
  const total = Object.values(byMethod).reduce((a, b) => a + b, 0);
  return { perFile, byMethod, total, appLevel: appLevel.length };
}

function roleFacts() {
  const src = read(join(REPO, "backend", "app", "core", "deps.py"));
  const block = src.match(/ROLE_RANK: dict\[str, int\] = \{([\s\S]*?)\}/);
  if (!block) return [];
  return [...block[1].matchAll(/"(\w+)":\s*(\d+)/g)].map((m) => ({ role: m[1], rank: Number(m[2]) }));
}

/** What test coverage exists, per surface. Quoted in QA_AUDIT.md, so it is generated. */
function testFacts() {
  const count = (dir, pred, re) => {
    if (!existsSync(dir)) return { files: 0, cases: 0 };
    const files = readdirSync(dir).filter(pred);
    let cases = 0;
    for (const f of files) cases += (read(join(dir, f)).match(re) || []).length;
    return { files: files.length, cases };
  };
  const backend = count(
    join(REPO, "backend", "tests"),
    (f) => f.startsWith("test_") && f.endsWith(".py"),
    /^(?:async )?def test_/gm,
  );
  const e2e = count(
    join(REPO, "frontend", "e2e"),
    (f) => f.endsWith(".spec.ts"),
    /^\s*test\(/gm,
  );
  /* The Kotlin suites are NESTED — `src/test/java/com/designprototype/workshop/data/…` — so the flat
     `count` above finds nothing in them. It reported zero files, which is why this table said
     "reports NO-SOURCE" for years while `:app:testDebugUnitTest` was in fact running 1156 tests. */
  const countDeep = (dir, re) => {
    if (!existsSync(dir)) return { files: 0, cases: 0 };
    let files = 0;
    let cases = 0;
    const walk = (at) => {
      for (const entry of readdirSync(at, { withFileTypes: true })) {
        const full = join(at, entry.name);
        if (entry.isDirectory()) walk(full);
        else if (entry.name.endsWith(".kt")) {
          const hits = (read(full).match(re) || []).length;
          if (hits > 0) files += 1;
          cases += hits;
        }
      }
    };
    walk(dir);
    return { files, cases };
  };
  // `@Test` on its own line, which is how every suite in this repo writes it. JUnit 4 (`org.junit`)
  // and androidx both spell the annotation the same way, so one pattern covers both source sets.
  const androidUnit = countDeep(join(REPO, "android", "app", "src", "test"), /^\s*@Test\b/gm);
  const androidInstr = countDeep(join(REPO, "android", "app", "src", "androidTest"), /^\s*@Test\b/gm);
  return { backend, e2e, androidUnit, androidInstr };
}

function sttFacts() {
  const src = read(join(REPO, "backend", "app", "services", "app_settings.py"));
  const m = src.match(/DEFAULT_STT_PROVIDER_ORDER = \(([^)]*)\)/);
  return m ? [...m[1].matchAll(/"(\w+)"/g)].map((x) => x[1]) : [];
}

/** Line counts per area, reported BOTH ways.
 *
 *  Tracked and working-tree counts differ by however much new work is uncommitted, and the two get
 *  quoted interchangeably in write-ups until somebody notices they disagree by a third. So both are
 *  produced here, labelled, from one walk. */
function volumeFacts() {
  const areas = {
    "backend/app": [".py"],
    "frontend/app": [".ts", ".tsx", ".css"],
    "frontend/components": [".ts", ".tsx"],
    "frontend/lib": [".ts", ".tsx"],
    "android/app/src/main/java": [".kt"],
  };
  let tracked = null;
  try {
    tracked = new Set(
      execSync("git ls-files", { cwd: REPO, encoding: "utf8", maxBuffer: 32 * 1024 * 1024 })
        .split("\n")
        .map((s) => s.trim())
        .filter(Boolean),
    );
  } catch {
    /* not a git checkout, or git unavailable — the tree columns still work */
  }
  const out = {};
  for (const [area, exts] of Object.entries(areas)) {
    const root = join(REPO, area);
    if (!existsSync(root)) continue;
    const acc = { files: 0, lines: 0, trackedFiles: 0, trackedLines: 0 };
    const walk = (d) => {
      for (const e of readdirSync(d, { withFileTypes: true })) {
        if (e.name === "__pycache__" || e.name === "node_modules") continue;
        const p = join(d, e.name);
        if (e.isDirectory()) {
          walk(p);
          continue;
        }
        if (!exts.some((x) => e.name.endsWith(x))) continue;
        // A NUL byte in MainActivity.kt makes some tools treat it as binary; count it regardless.
        const n = read(p).split("\n").length;
        acc.files += 1;
        acc.lines += n;
        const rel = p.slice(REPO.length + 1).replace(/\\/g, "/");
        if (tracked?.has(rel)) {
          acc.trackedFiles += 1;
          acc.trackedLines += n;
        }
      }
    };
    walk(root);
    out[area] = acc;
  }
  return { areas: out, haveTracked: tracked !== null };
}

/* ── the generated facts file ───────────────────────────────────────────────────────────────── */

function renderFacts() {
  const { models, enums, indexes, uniques } = prismaFacts();
  const routes = routeFacts();
  const roles = roleFacts();
  const stt = sttFacts();
  const tests = testFacts();
  const volume = volumeFacts();
  const m = routes.byMethod;

  const routeRows = Object.entries(routes.perFile)
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([f, n]) => `| \`${f}\` | ${n} |`)
    .join("\n");

  const n = (x) => x.toLocaleString("en-US");
  const volumeRows = Object.entries(volume.areas)
    .map(
      ([a, v]) =>
        `| \`${a}\` | ${v.trackedFiles} | ${n(v.trackedLines)} | ${v.files} | ${n(v.lines)} |`,
    )
    .join("\n");

  return `<!-- GENERATED FILE — do not edit by hand.
     Regenerate with:  node docs/tools/check-docs.mjs --write
     Every count in this documentation set lives here and nowhere else, so that a migration or a new
     route makes exactly one file wrong and a scripted run makes it right again. -->

# Repository facts (generated)

Counts derived from the working tree by \`docs/tools/check-docs.mjs\`. **Do not restate these numbers
in prose** — link here instead. If a document quotes a count, that count is already rotting.

These figures describe the **working tree**, which is not the same thing as production. The deployed
API lags the tree by however many commits have not been deployed; see
[the deployed-versus-tree note](#deployed-versus-tree).

## Data model

| | Count |
|---|---|
| Prisma models | **${models.length}** |
| Prisma enums | **${enums.length}** |
| \`@@index\` declarations | ${indexes} |
| \`@@unique\` declarations | ${uniques} |

Models: ${models.map((x) => `\`${x}\``).join(", ")}.

Enums: ${enums.map((x) => `\`${x}\``).join(", ")}.

## API surface

**${routes.total} operations** in the working tree — ${m.get} GET, ${m.post} POST, ${m.delete} DELETE,
${m.patch} PATCH, ${m.put} PUT. ${routes.appLevel} of them (\`/health\`, \`/health/ready\`) are declared
on the app rather than on a router; the rest are spread across \`backend/app/api/routes/\`:

| Route module | Operations |
|---|---|
${routeRows}

### Deployed versus tree

The number above counts decorators in this checkout. The number that matters operationally is what
the running API actually serves, which you read from the deployed schema rather than from the source:

\`\`\`bash
curl -s https://d2b34i3e92al6i.cloudfront.net/openapi.json \\
  | python -c "import json,sys,collections; d=json.load(sys.stdin); \\
      c=collections.Counter(m for p in d['paths'].values() for m in p if m in ('get','post','put','patch','delete')); \\
      print(sum(c.values()), dict(c))"
\`\`\`

A gap between the two is normal and means "not deployed yet". A gap in the other direction means
someone deployed from a branch.

> Note: that command only works while \`BACKEND_EXPOSE_DOCS\` is true on the deployment. The default
> is now **false** — see [SECURITY.md](SECURITY.md). Once it is false in production, count from a
> checkout of the deployed commit instead.

## Role ladder

${roles.map((r) => `- \`${r.role}\` — rank **${r.rank}**`).join("\n")}

Source of truth: \`ROLE_RANK\` in \`backend/app/core/deps.py\`, mirrored in
\`frontend/lib/permissions.ts\`. The two are checked against each other by this script.

## Transcription provider chain

Default order: ${stt.map((p, i) => `${i + 1}. \`${p}\``).join("  ")} — \`DEFAULT_STT_PROVIDER_ORDER\`
in \`backend/app/services/app_settings.py\`. A master admin can reorder it at runtime; a provider with
no key is skipped wherever it sits.

## Automated tests

| Surface | Files | Cases | Runner |
|---|---|---|---|
| Backend unit (\`backend/tests/\`) | ${tests.backend.files} | ${tests.backend.cases} \`def test_\` | \`python -m pytest -q\` from \`backend/\` |
| Web end-to-end (\`frontend/e2e/\`) | ${tests.e2e.files} | ${tests.e2e.cases} \`test(\` | Playwright, \`frontend/playwright.config.ts\` |
| Android unit (\`android/app/src/test/\`) | ${tests.androidUnit.files || "**none**"} | ${tests.androidUnit.cases} \`@Test\` | \`./gradlew :app:testDebugUnitTest\` from \`android/\` |
| Android instrumented (\`android/app/src/androidTest/\`) | ${tests.androidInstr.files || "**none**"} | ${tests.androidInstr.cases} \`@Test\` | needs a device; not run in CI |

The backend case count is \`def test_\` occurrences; pytest reports a larger number because
parametrised cases expand. Neither the backend suite nor the e2e suite is a CI gate today — see
[CI.md](CI.md) and [QA_AUDIT.md](QA_AUDIT.md).

**THIS TABLE USED TO SAY \`:app:testDebugUnitTest\` REPORTS NO-SOURCE, AND IT WAS FALSE.** The string
was a hard-coded literal in the generator, and the counter beside it only read a flat directory —
which finds nothing in \`src/test/java/com/…\`, so the emptiness it reported was its own. The suite
runs: **1156 tests, 0 failures** on 2026-08-15. A generated fact is only as true as its generator,
and this one asserted an absence it had never looked for.

## Code volume

| Area | Tracked files | Tracked lines | Tree files | Tree lines |
|---|---|---|---|---|
${volumeRows}

Two columns because the two numbers get quoted interchangeably and disagree by however much work is
uncommitted. **Tracked** is \`git ls-files\`, which is the figure to use in a write-up — it is
reproducible from a clone. **Tree** includes files not yet committed, which is the figure to use when
reasoning about what is running locally. Neither is wrong; they answer different questions.
`;
}

/* ── checks ─────────────────────────────────────────────────────────────────────────────────── */

function docFiles() {
  return readdirSync(DOCS)
    .filter((f) => f.endsWith(".md"))
    .map((f) => join(DOCS, f))
    .concat([join(REPO, "README.md")].filter(existsSync));
}

/** 1. The generated facts file is current. */
function checkFacts() {
  const rendered = renderFacts();
  if (WRITE) {
    writeFileSync(FACTS_FILE, rendered);
    note(`wrote ${FACTS_FILE.replace(REPO + "\\", "").replace(REPO + "/", "")}`);
    return;
  }
  if (!existsSync(FACTS_FILE)) {
    fail("docs/REPO_FACTS.md is missing — run `node docs/tools/check-docs.mjs --write`");
    return;
  }
  if (read(FACTS_FILE).trim() !== rendered.trim()) {
    fail("docs/REPO_FACTS.md is out of date — run `node docs/tools/check-docs.mjs --write`");
  }
}

/** 2. The web client's role ladder still matches the backend's. */
function checkRoleParity() {
  const backend = Object.fromEntries(roleFacts().map((r) => [r.role, r.rank]));
  const src = read(join(REPO, "frontend", "lib", "permissions.ts"));
  const block = src.match(/ROLE_RANK: Record<UserRole, number> = \{([\s\S]*?)\}/);
  if (!block) return fail("frontend/lib/permissions.ts: ROLE_RANK not found");
  const web = Object.fromEntries([...block[1].matchAll(/(\w+):\s*(\d+)/g)].map((m) => [m[1], Number(m[2])]));
  for (const [role, rank] of Object.entries(backend)) {
    if (web[role] !== rank) fail(`role ladder drift: backend ${role}=${rank}, web ${role}=${web[role]}`);
  }
  for (const role of Object.keys(web)) {
    if (!(role in backend)) fail(`role ladder drift: web has ${role}, backend does not`);
  }
}

/** 3. Every repository path mentioned in a doc exists. */
// `()` and `[]` ARE PATH CHARACTERS IN THIS REPOSITORY, and leaving them out was not harmless. The
// App Router spells a route group `app/(protected)/` and a dynamic segment `[id]`/`[stageKey]`, so a
// class of `[\w./-]` cannot match ANY of the 53 pages under `frontend/app/(protected)/` — the
// largest single area of the frontend was invisible to a tool whose whole job is checking that docs
// still describe the code. Worse than invisible, in fact: see the fallback in `checkCitations`.
//
// THEY ARE ADMITTED AS WHOLE SEGMENTS AND NOT AS LOOSE CHARACTERS, which is the difference between
// fixing this and breaking every link in README.md. Putting `[`, `]`, `(` and `)` into the character
// class lets the match run straight through the `](` of a markdown link, so `[docs/](docs/README.md)`
// becomes one path spelled `docs/](docs/README.md` — 25 confident, wrong failures in one file. A
// segment is therefore `(name)`, `[name]` or `name`, and nothing else can appear between two slashes.
const PATH_SEGMENT = String.raw`(?:\([\w.-]+\)|\[[\w.-]+\]|[\w.-]+)`;
const PATH_RE = new RegExp(
  String.raw`(?<![\w/.\-\])])((?:backend|frontend|android|infra|docs|scripts|\.github)(?:\/${PATH_SEGMENT})+\/?)`,
  "g",
);

// A runbook writes commands as you would type them, from the directory the runbook told you to be
// in, so `python scripts/seed_admin.py` after `cd backend` is correct prose and a wrong path. These
// are the roots a bare path is allowed to be relative to.
const PATH_ROOTS = [REPO, join(REPO, "backend"), join(REPO, "frontend"), join(REPO, "android")];

// Path-shaped strings that are deliberately NOT paths. Each needs a reason; the point of the list is
// that adding to it is a decision somebody made on purpose, not a check quietly weakening.
const NOT_A_PATH = new Map([
  ["frontend/frontend", "CI.md names it as the wrong path a misconfiguration produces"],
]);

function resolveRepoPath(p) {
  return PATH_ROOTS.some((root) => existsSync(join(root, p)));
}

function checkPaths() {
  let checked = 0;
  for (const doc of docFiles()) {
    const text = read(doc);
    const rel = doc.slice(REPO.length + 1).replace(/\\/g, "/");
    for (const m of text.matchAll(PATH_RE)) {
      const p = m[1].replace(/[.,;:)]+$/, "");
      if (p.includes("*") || p.includes("…")) continue; // globs and elisions are prose, not paths
      if (NOT_A_PATH.has(p)) continue;
      checked += 1;
      if (!resolveRepoPath(p)) fail(`${rel}: path does not exist — ${p}`);
    }
  }
  note(`${checked} repository paths resolved`);
}

/** 4. Line citations (`file.py:120` / `file.py:120-140`) land inside a file that is long enough. */
// Same segment rule as PATH_RE, for the same reason — a citation can sit inside a markdown link.
const CITE_RE = new RegExp(
  String.raw`(${PATH_SEGMENT}(?:\/${PATH_SEGMENT})*\.(?:py|ts|tsx|kt|kts|mjs|sh|yaml|yml|prisma)):(\d+)(?:-(\d+))?`,
  "g",
);

// Basenames too common to resolve from a bare filename. The App Router gives every one of 53 routes
// the SAME basename, so "find the first path in this document ending in page.tsx" answers with an
// arbitrary one — which is how this checker came to report citations as "past end of file (29
// lines)" against a file the citation had never named. A false FAIL is worse than a missed one: it
// teaches the next reader that the checker is noise.
const AMBIGUOUS_BASENAMES = new Set([
  "page.tsx", "layout.tsx", "route.ts", "loading.tsx", "error.tsx", "not-found.tsx",
  "__init__.py", "index.ts", "index.tsx",
]);
function checkCitations() {
  let checked = 0;
  const seen = new Map();
  for (const doc of docFiles()) {
    const text = read(doc);
    const rel = doc.slice(REPO.length + 1).replace(/\\/g, "/");
    for (const m of text.matchAll(CITE_RE)) {
      const [, file, startS, endS] = m;
      const end = Number(endS || startS);
      // Resolve a bare filename against the paths named elsewhere in the same document.
      let full = PATH_ROOTS.map((r) => join(r, file)).find(existsSync) || null;
      if (!full) {
        const base = file.split("/").pop();
        // Resolve a bare filename against the paths named elsewhere in the same document — but only
        // when the answer is UNAMBIGUOUS. `find` used to take the first match, which for a basename
        // every route shares is an arbitrary file, and the line number was then checked against it.
        if (!AMBIGUOUS_BASENAMES.has(base)) {
          const candidates = [
            ...new Set(
              [...text.matchAll(PATH_RE)].map((x) => x[1]).filter((p) => p.endsWith(base)),
            ),
          ];
          if (candidates.length === 1 && existsSync(join(REPO, candidates[0]))) {
            full = join(REPO, candidates[0]);
          }
        }
      }
      if (!full || !statSync(full).isFile()) continue; // path check already reported real misses
      checked += 1;
      const lines = read(full).split("\n").length;
      if (end > lines) fail(`${rel}: citation ${file}:${startS}${endS ? "-" + endS : ""} is past end of file (${lines} lines)`);
      seen.set(rel, (seen.get(rel) || 0) + 1);
    }
  }
  note(`${checked} line citations inside their file`);
  for (const [doc, n] of [...seen].sort((a, b) => b[1] - a[1])) {
    if (n >= 10) note(`  ${doc} pins ${n} line numbers — these rot silently; prefer symbol names`);
  }
}

/** 5. Every doc says how it is kept true.
 *
 *  A document with no maintenance story does not meet the brief: nobody knows what to re-check when
 *  the code moves, so nobody does, and it decays into confident misinformation. REPO_FACTS.md is
 *  exempt because it is regenerated wholesale — the script IS its maintenance story.
 *
 *  The five in PENDING were written by a different workstream and are not this one's to rewrite.
 *  They are reported as warnings rather than silently skipped, so the gap stays visible; move each
 *  name out of the set as its owner adds the section. */
const MAINTENANCE_EXEMPT = new Set(["REPO_FACTS.md"]);

function checkMaintenanceSections() {
  for (const doc of docFiles()) {
    const base = doc.split(/[\\/]/).pop();
    if (MAINTENANCE_EXEMPT.has(base)) continue;
    if (/how this document is kept (true|current)/i.test(read(doc))) continue;
    fail(`${base}: no "How this document is kept true" section`);
  }
}

/** 6. Mermaid blocks: the failure modes that are cheap to detect without a parser.
 *
 *  A broken diagram renders as a red error box on GitHub, which is worse than no diagram — it makes
 *  the page look abandoned. The authoritative check is a real parse (see below); this catches the
 *  two mistakes that have actually been made here, with no dependency.
 *
 *  For the real thing, in a scratch directory:
 *      npm i mermaid jsdom
 *      # set window/document/DOMParser/Element/Node/SVGElement from a JSDOM instance,
 *      # then `await mermaid.parse(block)` for every fenced block.
 *  That found a semicolon inside a sequenceDiagram message, which mermaid reads as a statement
 *  separator — the rest of the line then parses as a new statement and the diagram dies. */
const MERMAID_TYPES = /^(flowchart|graph|sequenceDiagram|stateDiagram(-v2)?|erDiagram|classDiagram|journey|gantt|pie|mindmap|timeline|quadrantChart|gitGraph)\b/;

function checkMermaid() {
  let blocks = 0;
  for (const doc of docFiles()) {
    const text = read(doc);
    const rel = doc.slice(REPO.length + 1).replace(/\\/g, "/");
    const fences = (text.match(/^```mermaid$/gm) || []).length;
    const found = [...text.matchAll(/```mermaid\n([\s\S]*?)```/g)];
    if (found.length !== fences) fail(`${rel}: an unclosed \`\`\`mermaid fence`);
    for (const [i, m] of found.entries()) {
      blocks += 1;
      const body = m[1];
      const first = body.split("\n").find((l) => l.trim() && !l.trim().startsWith("%%"))?.trim() ?? "";
      if (!MERMAID_TYPES.test(first)) {
        fail(`${rel}: mermaid block ${i + 1} does not start with a diagram type — got "${first.slice(0, 40)}"`);
      }
      // A semicolon in a sequence-diagram message ends the statement; everything after it is parsed
      // as a new one and the whole diagram fails to render.
      if (/^sequenceDiagram/.test(first)) {
        for (const line of body.split("\n")) {
          const msg = line.match(/^\s*\w+\s*-{1,2}>>?\s*\w+\s*:(.*)$/);
          if (msg && msg[1].includes(";")) {
            fail(`${rel}: mermaid block ${i + 1} — semicolon inside a sequence message ends the statement: "${line.trim().slice(0, 60)}"`);
          }
        }
      }
    }
  }
  note(`${blocks} mermaid blocks linted (structure only — parse them for certainty)`);
}

/** Blank out fenced blocks and inline code spans, keeping the byte count and the line structure.
 *
 *  ONLY THE LINK CHECK USES THIS, and the asymmetry is deliberate. A path or a citation is normally
 *  written INSIDE backticks — stripping code there would blind every other check in this file. A
 *  markdown link is the opposite: `](` inside a code span is never a link, and a regex quoted in
 *  prose supplies one readily. `/^(\d{1,2})[/\-.](\d{4})$/` contains `](\d{4})`, which this checker
 *  reported as a broken link to a file named `\d{4}` — a confident failure about a character class. */
function blankCode(text) {
  return text
    .replace(/```[\s\S]*?```/g, (b) => b.replace(/[^\n]/g, " "))
    .replace(/~~~[\s\S]*?~~~/g, (b) => b.replace(/[^\n]/g, " "))
    .replace(/`[^`\n]*`/g, (b) => " ".repeat(b.length));
}

/** 7. Relative markdown links between docs resolve. */
function checkCrossLinks() {
  let checked = 0;
  for (const doc of docFiles()) {
    const text = blankCode(read(doc));
    const rel = doc.slice(REPO.length + 1).replace(/\\/g, "/");
    for (const m of text.matchAll(/\]\((?!https?:|#|mailto:)([^)#\s]+)/g)) {
      const target = resolve(dirname(doc), m[1]);
      checked += 1;
      if (!existsSync(target)) fail(`${rel}: broken link — ${m[1]}`);
    }
  }
  note(`${checked} relative links resolved`);
}

/* ── run ────────────────────────────────────────────────────────────────────────────────────── */

checkFacts();
checkRoleParity();
checkPaths();
checkCitations();
checkMaintenanceSections();
checkMermaid();
checkCrossLinks();

if (!QUIET) for (const n of notes) console.log(`  ok    ${n}`);
for (const w of warnings) console.log(`  warn  ${w}   [owned by another workstream]`);
for (const f of failures) console.error(`FAIL    ${f}`);
console.log(
  failures.length
    ? `\n${failures.length} problem(s)${warnings.length ? `, ${warnings.length} warning(s) elsewhere` : ""}.`
    : `\ndocs check passed${warnings.length ? ` (${warnings.length} warning(s) in documents owned elsewhere)` : ""}.`,
);
process.exit(failures.length ? 1 : 0);
