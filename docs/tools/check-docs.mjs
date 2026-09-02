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
 *      silently and look precise. Two tests, and the second is the one that earns its keep:
 *        * BOUNDS. A citation into a file now shorter than the line it names is definitely wrong.
 *          This is the cheap test and it almost never fires: a file that grows swallows the drift.
 *        * DRIFT. Where the sentence names a symbol in backticks beside the pin, that symbol has to
 *          be within ten lines of the cited span. Five citations had rotted onto unrelated code
 *          while passing the bounds test — one of them 382 lines from the function it named — and
 *          this file said of itself that they "are wrong silently and look precise" while checking
 *          the one property that is almost never violated. See the long note above CITE_RE for the
 *          three deliberate abstentions that keep it from crying wolf, and `selfTestDrift` for the
 *          cases that hold it to its word on every run.
 *      Citations are also reported as a per-document count, so the number never grows unnoticed.
 *   4. DEPLOY TARGETS AND THE INDEX — two claims that are not prose at all. The host a handset build
 *      compiles in is a string literal in `android/app/build.gradle.kts`, and the set of documents
 *      in `docs/` is a directory listing; both are mechanically comparable with the document that
 *      claims to describe them, and both had drifted. See `checkAndroidApiHost` (which deliberately
 *      does NOT pick a side in an unresolved question, only refuses to let it go unrecorded) and
 *      `checkIndexListsEveryDoc` (the failure mode README.md names and could not test on itself).
 *      `checkVercelIds` is the third of that kind: a Vercel project id is written down in three
 *      tracked files and is the DEPLOY TARGET, so they are tied to each other rather than trusted
 *      to be threaded by hand — which is exactly what failed on 2026-08-22, three files out of four.
 *
 *   5. IDENTITY INHERITED FROM THE FIELD REPOSITORY, this project's sibling. This portal was split
 *      from another product and kept its identity in about a dozen places; three had been corrected
 *      one at a time, and two of those three were ship-blockers in a single week — including a docs
 *      table naming the other product's live box beside the matching SSH key, which is the pairing
 *      that authenticates and SUCCEEDS. So the facts are established ONCE, in docs/CI.md §0's
 *      two-column register, and `checkSiblingIdentity` sweeps every tracked file for the other
 *      product's values: each one must say, within a few lines, whose it is. Fixing them one at a
 *      time is how the twelfth survives. "ONCE" is itself checked — ENVIRONMENT.md §4 carries a
 *      second copy of the same table, and `checkSecondRegister` holds the two to each other cell by
 *      cell, because they had already drifted apart on the row nobody can answer.
 *   6. COMMENTS THAT ASSERT A STATE THE CODE HAS LEFT BEHIND. A docstring ending "delete this
 *      paragraph when X lands, not before" is an instruction the next reader obeys — after X has
 *      landed and the paragraph became false; one such docstring declared a shipped feature
 *      unreachable in the file that implements it. `checkRottableClaims` flags the shapes that rot
 *      that way when no date sits beside them, WARNING ONLY, against a written-out baseline of the
 *      instances already here. Its escape is the fix: put a date on the claim.
 *
 * What it deliberately does NOT do: check that a sentence is true. Nothing can. The per-document
 * "How this document is kept true" section names the human check for the rest. Nor does it pick a
 * side in the one question the repository cannot answer about itself — which CloudFront
 * distribution is this portal's; §8 and §10 both hold that open in BOTH directions instead.
 *
 *   node docs/tools/check-docs.mjs                  # verify; exit 1 on any failure
 *   node docs/tools/check-docs.mjs --write          # regenerate docs/REPO_FACTS.md, then verify
 *   node docs/tools/check-docs.mjs --quiet          # drop the "ok" notes only; known, rot, warn and FAIL all still print
 *   node docs/tools/check-docs.mjs --rot-baseline   # print a replacement ROT_BASELINE block
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
const ROT_BASELINE_WRITE = process.argv.includes("--rot-baseline");

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
// Rot findings are their OWN bucket, never a failure and never mixed in with the warnings above.
// The warnings above mean "a real problem in a file this run's owner cannot edit"; a rot finding
// means "this sentence cannot be re-checked by anybody". Folding the second into the first would
// bury the first, and the first is the one that has produced ship-blockers.
const rots = [];
// Sibling-identity mentions that are already written down in SIBLING_ALLOWLIST (§10). Printed in
// full on every run, never fatal. Their own bucket for the same reason `rots` has one: "known and
// listed" and "in a file another workstream owns" are different states, and folding either into
// the warnings would make the list of things nobody has looked at indistinguishable from the list
// of things somebody decided about.
const knowns = [];
const ownerOf = (msg) => {
  const m = msg.match(/(?:^|\/)([A-Z_]+\.md)/);
  return m && OWNED_ELSEWHERE.has(m[1]) ? "elsewhere" : "here";
};
const fail = (m) => (ownerOf(m) === "here" ? failures : warnings).push(m);
// There is deliberately no per-path severity helper here any more. §10's sweep used to route any
// finding outside `docs/*.md` to the warnings, which meant a reintroduced deploy target in a .kt,
// a .tf or a .env.example produced a GREEN run with one line in the log — and those are precisely
// the files that point a deploy at a machine. §10 now fails on anything not in its written-out
// allowlist, whatever the file's extension; see the SEVERITY paragraph there. Removed 2026-08-22.
const rot = (m) => rots.push(m);
const known = (m) => knowns.push(m);
const note = (m) => notes.push(m);
// Normalise line endings on the way in. Half these files are CRLF and half are LF, and every regex
// below that anchors on "\n" silently matched NOTHING in the CRLF half — which is how four documents'
// mermaid blocks went unchecked while the run stayed green. A check that quietly inspects less than
// it claims to is worse than no check.
// Memoised. The two sweeps at the bottom of this file each walk every tracked file, and without a
// cache the whole tree is read twice per run — the doubling is most of the wall clock.
//
// ONE FILE IS WRITTEN AND THEN READ BACK: docs/REPO_FACTS.md, under `--write`. That is safe only
// because `checkFacts` runs FIRST and drops its own cache entry on the way out — an invariant
// rather than an accident, so it is enforced there instead of assumed here. Do not move
// `checkFacts` down the run order.
//
// NOT FOR SECRETS. Anything read through here is retained for the whole process, so the designrepo
// Terraform state — which holds a live AWS secret access key beside the two outputs §10 consults —
// is deliberately read with a bare `readFileSync` instead. See `checkSiblingIdentity`.
const READ_CACHE = new Map();
const read = (p) => {
  if (!READ_CACHE.has(p)) READ_CACHE.set(p, readFileSync(p, "utf8").replace(/\r\n/g, "\n"));
  return READ_CACHE.get(p);
};

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
curl -s https://d3ekigkotd1xa2.cloudfront.net/openapi.json \\
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

**REGENERATE THIS ON A CLEAN TREE.** Only the *file* columns come from the index; every *line* count
is read off disk, so \`--write\` on a dirty working copy writes uncommitted lines into the Tracked
column and quietly destroys the one property that column is for. It has already happened: the Android
row sat at 150 tracked files against 152 in the tree from the commit that added the divergence view
until 2026-08-19, because nobody re-ran \`--write\` after \`git add\`. **Two file columns that disagree
are the tell** — tracked and tree must be equal at a clean HEAD, so any gap between them means this
file was generated mid-change and its line counts belong to somebody's working copy.
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
    // The one file this script writes and later checks read back. Drop the pre-write copy so a
    // `--write` run measures the file it just produced rather than the one it replaced.
    READ_CACHE.delete(FACTS_FILE);
    note(`wrote ${FACTS_FILE.replace(REPO + "\\", "").replace(REPO + "/", "")}`);
    return;
  }
  if (!existsSync(FACTS_FILE)) {
    fail("docs/REPO_FACTS.md is missing — run `node docs/tools/check-docs.mjs --write`");
    return;
  }
  const current = read(FACTS_FILE).trim();
  if (current !== rendered.trim()) {
    // NAME THE ROWS THAT DRIFTED, because "out of date" on its own is an instruction to run
    // `--write` and commit whatever comes out, and this file has already been regenerated on a
    // DIRTY tree once — which bakes working-copy line counts into the column whose entire promise
    // is that it is reproducible from a clone. A reader who can see that the only drifting row is
    // `android/app/src/main/java` knows the tracked file count moved and can check whether their
    // own uncommitted work explains it. A reader who sees one sentence cannot.
    //
    // Line-by-line rather than a real diff: the file is a fixed set of tables in a fixed order, so
    // positional comparison names the right row, and pulling in a diff library for a 140-line
    // generated file would be the more fragile choice.
    fail(
      "docs/REPO_FACTS.md is out of date — run `node docs/tools/check-docs.mjs --write`" +
        " ON A CLEAN TREE (line counts are read from disk, not from the index)\n" +
        factsDrift(current.split("\n"), rendered.trim().split("\n")).join("\n"),
    );
  }
}

/** Does a size-table row disagree with itself about how many FILES there are?
 *
 *  The size table's rows are `| `path` | tracked files | tracked lines | tree files | tree lines |`.
 *  Tracked and tree file counts CANNOT differ at a clean HEAD, so a row where they do is drift a
 *  clone reproduces — somebody generated the file while a source file was still untracked. Every
 *  other kind of difference in this file (line counts, test counts, endpoint totals) can be
 *  manufactured by uncommitted work sitting in the tree, so it tells the reader far less.
 *
 *  Read off the printed row rather than recomputed, because this runs on the version ON DISK as
 *  well as on the freshly rendered one, and the whole question is what the stale file claims. */
function fileCountsDisagree(line) {
  if (typeof line !== "string") return false;
  const cells = line.split("|").map((c) => c.trim());
  // ["", "`path`", tracked files, tracked lines, tree files, tree lines, ""]
  if (cells.length !== 7) return false;
  const n = (c) => (/^[\d,]+$/.test(c) ? Number(c.replace(/,/g, "")) : null);
  const trackedFiles = n(cells[2]);
  const treeFiles = n(cells[4]);
  return trackedFiles !== null && treeFiles !== null && trackedFiles !== treeFiles;
}

/** The drift listing, as a pure function so `--self-test` below can hold the ORDERING to account.
 *
 *  THE ORDER IS THE POINT, AND THE CAP IS NOT ENOUGH ON ITS OWN. The first version of this listing
 *  capped at twelve rows in file order and reproduced, one level down, the exact defect it was
 *  written to close: `android/app/src/main/java` — the only drifting row a clean checkout also shows
 *  — sat twelfth and last, one more in-flight edit away from vanishing behind a counter that reports
 *  a NUMBER rather than a row. So the rows a clone would reproduce are emitted FIRST (see
 *  `fileCountsDisagree`), and the cap is raised well past what five concurrent agents produce. The
 *  boundary can no longer swallow the row that matters, because that row is never near the boundary.
 *
 *  WHAT THE TRAILING COUNTER DOES AND DOES NOT DO. It says how many differing lines were not
 *  printed, and nothing about which — it is a "there is more here" signal, not a summary, and it can
 *  never be relied on to disclose a row. That is why ordering rather than counting is the guarantee.
 *  Within each of the two groups the order is positional, so a reader can still find a row by
 *  scrolling REPO_FACTS.md. */
function factsDrift(was, now, cap = 120) {
  const differing = [];
  for (let i = 0; i < Math.max(was.length, now.length); i += 1) {
    if (was[i] !== now[i]) differing.push(i);
  }
  const reproducible = (i) => fileCountsDisagree(was[i]) || fileCountsDisagree(now[i]);
  differing.sort((a, b) => Number(reproducible(b)) - Number(reproducible(a)) || a - b);
  const drift = [];
  let hidden = 0;
  for (const i of differing) {
    if (drift.length >= cap) { hidden += 1; continue; }
    drift.push(`  line ${i + 1}: have ${was[i] ?? "(nothing)"}`, `           want ${now[i] ?? "(nothing)"}`);
  }
  if (hidden) drift.push(`  …and ${hidden} more differing line(s) — not named, and not summarised`);
  return drift;
}

/** Does the ordering above actually bite? Same reasoning as `selfTestDrift`: the guarantee this
 *  listing makes is about which row survives a cap, and a guarantee nobody tests is how the twelve-
 *  line window shipped in the first place. */
function selfTestFactsDrift() {
  const cases = [];
  const ok = (what, cond) => { cases.push(what); if (!cond) fail(`check-docs self-test: ${what}`); };

  const row = (path, tf, tl, ef, el) => `| \`${path}\` | ${tf} | ${tl} | ${ef} | ${el} |`;
  const clean = row("android/app/src/main/java", 150, "130,629", 152, "131,446");
  const fixed = row("android/app/src/main/java", 151, "131,331", 151, "131,331");

  ok("a row whose tracked and tree file counts differ is what a clone reproduces", fileCountsDisagree(clean));
  ok("...and one where they agree is not", !fileCountsDisagree(fixed));
  ok("a line-count-only change is not counted as reproducible",
    !fileCountsDisagree(row("backend/app", 162, "85,384", 162, "85,975")));
  ok("a non-table line is not misread as a row", !fileCountsDisagree("have **251 operations** — 116 GET"));

  // THE ACTUAL DEFECT: the row that matters sits last, behind more in-flight noise than the cap.
  const noise = (n) => Array.from({ length: n }, (_, i) => `| \`lib/${i}\` | 9 | ${i} | 9 | ${i} |`);
  const wasLines = [...noise(40), clean];
  const nowLines = [...noise(40).map((l) => l.replace(/\| (\d+) \|$/, "| 999 |")), fixed];
  const listed = factsDrift(wasLines, nowLines, 4).join("\n");
  ok("the clean-tree row is printed even when the cap is smaller than the noise above it",
    listed.includes("android/app/src/main/java"));
  ok("...and it is printed FIRST, so no cap can reach it", listed.startsWith(`  line 41: have ${clean}`));
  ok("the counter says how many were withheld", listed.includes("more differing line(s)"));
  ok("nothing is hidden when the cap is not reached", !factsDrift(wasLines, nowLines).join("\n").includes("more differing"));

  note(`${cases.length} self-test cases for the REPO_FACTS drift listing`);
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

/**
 * 2b. Every client route guard is listed in PERMISSIONS.md §5, and §5 invents none.
 *
 * WHY THIS IS MECHANICAL NOW. The maintenance table used to say "`ROUTE_GUARDS` is a single literal
 * array; diff it against the table" — a human instruction, and by the time an audit counted them the
 * table held 7 of the 14 rules. The omissions were not random: `/design-workshops`, `/questionnaires`
 * and `/designers/profile` gate on a SET of roles rather than a rank threshold, so they are precisely
 * the rules a reader cannot re-derive from §2's ladder — and §5 closes with "anything unlisted is
 * open to any signed-in user", which made the omission an affirmatively false statement about the
 * product's primary surface rather than merely an incomplete one.
 *
 * Both directions are failures. A missing row is the defect above. A row for a route that no longer
 * has a guard is the same defect with the sign flipped: a reader who trusts it believes a page is
 * gated when the URL is open, and the whole point of §5 is that a hidden nav entry is not a guard.
 *
 * Only the ROUTE LIST is checked. The gate names in the middle column are prose about a predicate's
 * meaning and cannot be diffed; the maintenance table says so.
 */
function checkRouteGuardTable() {
  const src = read(join(REPO, "frontend", "lib", "permissions.ts"));
  const block = src.match(/export const ROUTE_GUARDS: RouteGuard\[\] = \[([\s\S]*?)\n\];/);
  if (!block) return fail("frontend/lib/permissions.ts: ROUTE_GUARDS not found");
  const declared = [...block[1].matchAll(/path:\s*"([^"]+)"/g)].map((m) => m[1]);
  if (!declared.length) return fail("frontend/lib/permissions.ts: ROUTE_GUARDS declares no paths");

  const doc = read(join(DOCS, "PERMISSIONS.md"));
  // §5's table only — a route named in the prose of §4 must not be able to satisfy this check, or
  // the failure it exists to catch (a rule nobody tabulated) passes the moment anyone mentions the
  // path in a sentence.
  const section = doc.slice(doc.indexOf("## 5. Route guards on the web client"), doc.indexOf("## 6."));
  if (!section) return fail("docs/PERMISSIONS.md: section 5 not found");
  const rows = section
    .split("\n")
    .filter((line) => line.startsWith("| `/"))
    .join("\n");
  // `:` is in the character class because ROUTE_GUARDS can now name a VARIABLE segment —
  // `/design-workshops/:id/provenance` — and without it this regex captured `/design-workshops/`
  // and stopped, so the row was in the table, the path was not "tabulated", and the check failed
  // against a document that was actually correct. A checker that cannot express the shape it is
  // checking reports the author as wrong.
  const tabulated = new Set([...rows.matchAll(/`(\/[\w/:-]+)`/g)].map((m) => m[1]));

  for (const path of declared) {
    if (!tabulated.has(path)) {
      fail(`docs/PERMISSIONS.md §5: ROUTE_GUARDS declares ${path} and the table does not list it`);
    }
  }
  for (const path of tabulated) {
    // `/questionnaire` (singular) appears in the plural row's own warning label; it is deliberately
    // NOT a guard, and saying so is the point of that row.
    if (path === "/questionnaire") continue;
    if (!declared.includes(path)) {
      fail(`docs/PERMISSIONS.md §5: the table lists ${path}, which has no ROUTE_GUARDS rule`);
    }
  }
  note(`${declared.length} client route guards listed in PERMISSIONS.md §5`);
}

/** 3. Every repository path mentioned in a doc exists. */
// `()` and `[]` ARE PATH CHARACTERS IN THIS REPOSITORY, and leaving them out was not harmless. The
// App Router spells a route group `app/(protected)/` and a dynamic segment `[id]`/`[stageKey]`, so a
// class of `[\w./-]` cannot match ANY of the pages under `frontend/app/(protected)/` — the
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

/**
 * Paths a clone is EXPECTED not to contain, because the developer creates them.
 *
 * ── WHY THIS EXISTS, AND WHY THE CHECK COULD NEVER PASS IN CI WITHOUT IT ────────────────────────
 *
 * `checkPaths` asserts that every repository path a document names exists on disk. That is the
 * right question on a working machine and the WRONG one on a fresh checkout: `backend/.env`,
 * `frontend/.env.local`, `android/local.properties` and `backend/.venv/` are gitignored by design —
 * the environment documents exist precisely to tell somebody to create them — so they are present
 * for every developer and absent for every runner.
 *
 * The result was a checker that passed locally and could not pass in CI. It reported 35 failures on
 * the first run of `.github/workflows/checks.yml`, every one of them a document correctly naming a
 * file the reader is being told to make. A gate that fails for a reason the author cannot reproduce
 * is a gate that gets switched off, so this is the fix rather than an exemption list of individual
 * documents.
 *
 * MATCHED BY PREFIX, so `backend/.venv/Lib/site-packages/prisma/` is covered by `backend/.venv/`
 * without enumerating what a virtualenv happens to contain this week.
 *
 * A path listed here is still checked in one direction: if it EXISTS it is fine, and if it does not
 * that is expected. What this cannot catch is a typo inside one of these prefixes, which is the
 * price of the rule and is cheaper than the alternative.
 */
const CREATED_BY_THE_DEVELOPER = [
  ["backend/.env", "gitignored — ENVIRONMENT.md tells the reader to create it from .env.example"],
  ["backend/.venv", "gitignored — the virtualenv a developer builds, named in setup instructions"],
  ["frontend/.env.local", "gitignored — DEPLOYMENT_VERCEL.md and ENVIRONMENT.md tell the reader to create it"],
  ["frontend/node_modules", "gitignored — produced by npm install"],
  // `next dev` and `next build` write here, and TESTING-E2E-LOCAL.md cites the dev server's own
  // log to explain a measurement. Absent from a fresh checkout, which is exactly how this check
  // caught it: the doc passed locally only because the author had a dev server running.
  ["frontend/.next", "gitignored — Next's build and dev output, including the dev server log TESTING-E2E-LOCAL.md quotes"],
  ["android/local.properties", "gitignored — written by Android Studio on first open"],
  ["android/app/libs/", "gitignored — the sherpa-onnx AAR the CI workflow fetches at build time"],
  // Both deploy keys are named in CI.md on purpose, and the PAIRING is the point: one opens this
  // portal's API box and one opens the field repository's, and that document's job is to stop a
  // reader reaching for the wrong one. Exempting only whichever is current would make the warning
  // half of the pair the thing that turns the docs run red.
  ["infra/terraform/designrepo-deploy.pem", "a private key, never committed — CI.md names it as the file an operator holds for THIS portal's box"],
  ["infra/terraform/fieldrepo-deploy.pem", "a private key, never committed — CI.md names it as the one that must NOT be pasted here"],
  ["infra/terraform/terraform.tfstate", "gitignored — local Terraform state, including the per-workspace copies under terraform.tfstate.d/"],
  ["infra/terraform/.terraform", "gitignored — Terraform's working directory; `.terraform/environment` is the machine-local workspace marker"],
  ["frontend/.vercel", "gitignored — written by `vercel link`/`vercel pull`; it is what says which Vercel project a checkout deploys to"],
];

function expectedAbsent(p) {
  return CREATED_BY_THE_DEVELOPER.some(([prefix]) => p === prefix || p.startsWith(prefix));
}

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
      if (!resolveRepoPath(p) && !expectedAbsent(p)) fail(`${rel}: path does not exist — ${p}`);
    }
  }
  note(`${checked} repository paths resolved`);
}

/** 4. Line citations (`file.py:120` / `file.py:120-140`): inside the file, AND still on the code the
 *  sentence around them describes.
 *
 *  ── WHY THE BOUNDS TEST ALONE WAS THE WORST KIND OF GREEN ──────────────────────────────────────
 *
 *  This check used to assert one thing: that the cited line is not past the end of the file. Every
 *  citation in the repository passed. Five of them had drifted onto code with nothing to do with the
 *  sentence that cited them — `REFERENCE_MODELS` cited at `design_workshops.py:277`, which is a blank
 *  line above `_joined`, while the table itself had moved to 335; `CustomSectionsIn` cited 28 lines
 *  above the class; `publishAppUpdate` cited 382 lines above the function. A citation drifts by
 *  exactly the amount of code inserted above it, which is a number that only ever grows, and a long
 *  file swallows the whole drift without ever getting shorter. So the one condition the check tested
 *  was the one condition that is almost never violated, and this file's own header calls line
 *  citations "the worst offenders, because they are wrong silently and look precise".
 *
 *  ── WHAT THE DRIFT TEST DOES, AND WHY IT IS DELIBERATELY WEAK ──────────────────────────────────
 *
 *  Nothing here can read prose. What it can do is notice that documentation almost always writes the
 *  citation next to the NAME of the thing being cited — "`REFERENCE_MODELS` (`design_workshops.py:277`)"
 *  — and that name is a string that either is or is not near line 277 of that file. So: take the
 *  backticked identifiers on the SAME line of the document, and require that at least one of them
 *  occurs within CITATION_DRIFT_WINDOW lines of the cited span.
 *
 *  Three deliberate abstentions, each of which exists to stop a false FAIL. A checker that cries wolf
 *  gets ignored wholesale, and this one is being repaired precisely because it was not believed:
 *
 *    * NO IDENTIFIER ON THE LINE → say nothing. Half the citations in this repository are bare, and
 *      guessing at an anchor from the paragraph is how you invent a failure.
 *    * THE IDENTIFIER APPEARS NOWHERE IN THE FILE → say nothing. The sentence is naming something
 *      that lives somewhere else — a caller, a sibling module, a wire key — and its absence is not
 *      evidence about the line number.
 *    * ANY ONE anchor near the span passes the citation. Not all of them: a sentence naming four
 *      symbols is describing a relationship between files, and only one end of it is the citation.
 *
 *  The failure is therefore always actionable and never a matter of opinion: it names the identifier,
 *  the line cited, and the line that identifier is actually on. Fix whichever is wrong — and if the
 *  citation is right and the anchor is genuinely 60 lines up (the middle of a long function), quote
 *  something that IS on the cited lines. That is a better citation anyway. */
// Same segment rule as PATH_RE, for the same reason — a citation can sit inside a markdown link.
const CITE_RE = new RegExp(
  String.raw`(${PATH_SEGMENT}(?:\/${PATH_SEGMENT})*\.(?:py|ts|tsx|kt|kts|mjs|sh|yaml|yml|prisma)):(\d+)(?:-(\d+))?`,
  "g",
);

// Basenames too common to resolve from a bare filename. The App Router gives EVERY route under
// `frontend/app/(protected)/` the SAME basename — dozens of them, and the number moves with every
// page added, which is why it is not written here — so "find the first path ending in page.tsx"
// answers with an
// arbitrary one — which is how this checker came to report citations as "past end of file (29
// lines)" against a file the citation had never named. A false FAIL is worse than a missed one: it
// teaches the next reader that the checker is noise.
const AMBIGUOUS_BASENAMES = new Set([
  "page.tsx", "layout.tsx", "route.ts", "loading.tsx", "error.tsx", "not-found.tsx",
  "__init__.py", "index.ts", "index.tsx",
]);

// How far from the cited span the named symbol may sit. Ten lines is a docstring or a decorator
// stack — the distance between "the line I meant" and "the line I counted" — and it is deliberately
// not larger: the drifts this catches are 28, 40, 58 and 382 lines, and widening the window to
// forgive a citation into the middle of a long function would forgive those too.
const CITATION_DRIFT_WINDOW = 10;

// Words that occur in every file and therefore locate nothing. An anchor has to be able to be WRONG
// to be worth testing; `data` matches something within ten lines of anywhere.
const WEAK_ANCHORS = new Set([
  "data", "true", "false", "null", "none", "type", "name", "list", "dict", "self",
  "value", "text", "file", "line", "keys", "item", "items", "return", "async", "await",
]);
const ANCHOR_RE = /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*(?:\(\))?$/;
const ANCHOR_IS_A_FILENAME = /\.(?:py|ts|tsx|kt|kts|mjs|js|sh|md|json|yaml|yml|prisma)$/;

/** Backticked identifiers on one line of a document, expanded into the strings worth grepping for.
 *
 *  `StageEntryIn.data` is the case that shaped this. As written it appears nowhere in the Python —
 *  the class is `StageEntryIn` and the field is declared as `data:` — so testing the dotted string
 *  alone reported a correct citation as drift. The rule is therefore: the whole dotted name, plus its
 *  last component, and when the last component is a word too common to locate anything (`.data`,
 *  `.name`), its FIRST component instead, which is the type. */
function citationAnchors(line) {
  const out = new Set();
  const usable = (t) => t.length >= 4 && !WEAK_ANCHORS.has(t.toLowerCase());
  for (const m of line.matchAll(/`([^`\n]+)`/g)) {
    const raw = m[1].replace(/\(\)$/, "");
    if (!ANCHOR_RE.test(raw)) continue;      // paths, citations, expressions, prose — not a symbol
    if (ANCHOR_IS_A_FILENAME.test(raw)) continue; // `identity_ocr.py` names the file, not a symbol in it
    if (usable(raw)) out.add(raw);
    if (raw.includes(".")) {
      const parts = raw.split(".");
      const last = parts[parts.length - 1];
      if (usable(last)) out.add(last);
      else if (usable(parts[0])) out.add(parts[0]);
    }
  }
  return [...out];
}

// Documents whose line numbers are pinned to a tree that no longer exists, ON PURPOSE. Drift is not a
// defect in these; correcting them would destroy the record. The bounds check still applies.
//
// AUDIT-2026-08-15.md is a dated register — "the working tree was audited, not HEAD", 126 defects
// "each anchored to a line of real code" as that tree stood on the morning of 2026-08-15. Most of
// those defects have since been fixed, in waves, and every fix moved the lines below it; how many of
// its citations have come loose is therefore a number that grows all day, and quoting one here would
// itself be stale by evening (this comment used to, and was). Re-pinning them would be a lie about
// when the audit ran;
// the entries name their symbols as well as their lines, and that is what a reader follows.
//
// ADDING A NAME HERE IS A DECISION SOMEBODY MAKES ON PURPOSE, with the reason written next to it. It
// is not the place to put a document whose citations merely rotted — that is the failure, and the
// fix for it is to name the symbol instead of the line.
const DRIFT_EXEMPT = new Map([
  ["AUDIT-2026-08-15.md", "a dated snapshot of the pre-fix tree; its line numbers are the record"],
]);

// A document that EXHIBITS a rotted citation — "`StageScreen.kt:1320-1325` had rotted onto unrelated
// code" — is writing the citation as a specimen, not as a pointer, and is the one place where "this
// number does not point at what the sentence says" is the intended reading. Mark that line with an
// HTML comment (invisible when rendered) and the drift test steps over it:
//
//     (Both were cited by line number — `StageScreen.kt:1320-1325` — and both had rotted. <!-- rotted -->)
//
// ONLY for a pin the prose is holding up as broken. Using it to quiet a citation somebody still means
// as a pointer converts a loud failure into a silent lie, which is the exact defect this check exists
// to end — and the count below keeps every use of it visible in the run's output.
const ROTTED_SPECIMEN = /<!--\s*rotted\b/;

/** The whole drift decision, as a pure function so that `--self-test` below can hold it to account.
 *
 *  Returns `null` when the file contains none of the anchors — abstention 2, "the sentence is naming
 *  something that lives elsewhere" — and otherwise the closest occurrence found, with `near` saying
 *  whether it is close enough to the cited span to count as the thing cited.
 *
 *  ANY ONE anchor near the span settles it, so the search stops at the first near hit; otherwise it
 *  keeps the occurrence closest to the start line, because that is the number a reader needs in the
 *  failure message to repair the citation. */
function citationDrift(src, start, end, anchors) {
  let best = null;
  for (const anchor of anchors) {
    for (let j = 0; j < src.length; j += 1) {
      if (!src[j].includes(anchor)) continue;
      const at = j + 1;
      if (at >= start - CITATION_DRIFT_WINDOW && at <= end + CITATION_DRIFT_WINDOW) {
        return { anchor, at, near: true };
      }
      if (best === null || Math.abs(at - start) < Math.abs(best.at - start)) best = { anchor, at, near: false };
    }
  }
  return best;
}

/** Does the drift test actually bite? Run on every invocation, because a check nobody tests is how
 *  this file got into trouble in the first place.
 *
 *  The case that matters is the first one: it is `REFERENCE_MODELS` as `docs/REPORT-DATA-WIRING.md`
 *  cited it — a citation 58 lines above the symbol, in a file long enough that the number is still
 *  inside it. That is exactly the shape the OLD check called green, and the assertion below is
 *  written to say so out loud: the bounds test passes AND the drift test fails on the same input. If
 *  somebody widens the window, weakens the anchors or "simplifies" the search, one of these stops
 *  holding and the docs run goes red with a line number in this file. */
function selfTestDrift() {
  const cases = [];
  const ok = (what, cond) => { cases.push(what); if (!cond) fail(`check-docs self-test: ${what}`); };

  // A 400-line file whose symbol sits at line 335, cited at 277 — REPORT-DATA-WIRING's own defect.
  const long = Array.from({ length: 400 }, (_, i) =>
    i + 1 === 335 ? "REFERENCE_MODELS: dict[str, ReferenceModel] = {" : "    # filler");
  const rotted = citationDrift(long, 277, 277, ["REFERENCE_MODELS"]);
  ok("a citation 58 lines above its symbol is inside the file (what the old check tested)", 277 <= long.length);
  ok("...and is reported as drift (what the old check missed)", rotted && !rotted.near && rotted.at === 335);

  // Same file, cited correctly, and cited at the far edge of the tolerance.
  ok("a citation ON the symbol passes", citationDrift(long, 335, 335, ["REFERENCE_MODELS"]).near);
  ok("a citation 10 lines above the symbol still passes", citationDrift(long, 325, 325, ["REFERENCE_MODELS"]).near);
  ok("a citation 11 lines above the symbol does not", !citationDrift(long, 324, 324, ["REFERENCE_MODELS"]).near);

  // Abstention 2: the sentence names something that lives in another file.
  ok("a symbol absent from the file is not evidence", citationDrift(long, 12, 12, ["SomethingElse"]) === null);
  // Any one anchor near the span settles it — a sentence spanning two files cites only one of them.
  ok("one near anchor rescues a far one", citationDrift(long, 330, 330, ["Absent", "REFERENCE_MODELS"]).near);

  // Anchor extraction, where the false positives would come from.
  ok("a bare identifier is an anchor", citationAnchors("`REFERENCE_MODELS` (`x.py:1`)").includes("REFERENCE_MODELS"));
  ok("`StageEntryIn.data` yields the type, since `.data` locates nothing",
    citationAnchors("`StageEntryIn.data` (`x.py:1`)").includes("StageEntryIn"));
  ok("`prototype.productRef` yields the field", citationAnchors("`prototype.productRef`").includes("productRef"));
  ok("a filename is not a symbol", citationAnchors("`identity_ocr.py`").length === 0);
  ok("a citation is not a symbol", citationAnchors("`report_builder.py:105`").length === 0);
  ok("an expression is not a symbol", citationAnchors("`Field(default=True)`").length === 0);
  ok("a word too common to locate anything is not an anchor", citationAnchors("`data` `name` `self`").length === 0);
  ok("a line naming nothing abstains", citationAnchors("**Where.** `frontend/lib/store.ts:974`").length === 0);

  // The specimen marker, which is the only sanctioned way to write a pin that is meant to be wrong.
  ok("<!-- rotted --> is recognised", ROTTED_SPECIMEN.test("`StageScreen.kt:1320-1325` <!-- rotted -->"));
  ok("an unmarked line is not", !ROTTED_SPECIMEN.test("`StageScreen.kt:1320-1325` had rotted"));

  note(`${cases.length} self-test cases for the citation-drift check`);
}

function checkCitations() {
  let checked = 0;
  let anchored = 0;
  let specimens = 0;
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
      const src = read(full).split("\n");
      const lines = src.length;
      const cite = `${file}:${startS}${endS ? "-" + endS : ""}`;
      if (end > lines) fail(`${rel}: citation ${cite} is past end of file (${lines} lines)`);
      seen.set(rel, (seen.get(rel) || 0) + 1);

      // ── and has it drifted? See the long note above CITE_RE for why this is the check that matters
      if (end > lines || DRIFT_EXEMPT.has(rel.split("/").pop())) continue;
      // The document line the citation sits on, which is where its anchors are.
      const lineStart = text.lastIndexOf("\n", m.index) + 1;
      const lineEnd = text.indexOf("\n", m.index);
      const docLineNo = text.slice(0, lineStart).split("\n").length;
      const docLine = text.slice(lineStart, lineEnd === -1 ? undefined : lineEnd);
      if (ROTTED_SPECIMEN.test(docLine)) { specimens += 1; continue; }
      const anchors = citationAnchors(docLine);
      if (!anchors.length) continue;                    // abstention 1: nothing named to look for
      const verdict = citationDrift(src, Number(startS), end, anchors);
      if (verdict === null) continue;                   // abstention 2, or the citation still points at it
      anchored += 1;
      if (verdict.near) continue;
      fail(
        `${rel}:${docLineNo}: citation ${cite} has drifted — the line names \`${verdict.anchor}\`, ` +
        `which is at ${file.split("/").pop()}:${verdict.at}. Re-pin it, or name the symbol instead of the line.`,
      );
    }
  }
  note(`${checked} line citations inside their file`);
  note(`${anchored} of them also checked against a symbol named on the same line (the rest name none)`);
  if (specimens) note(`${specimens} citation(s) marked <!-- rotted --> — quoted AS rot, drift not tested`);
  for (const [doc, n] of [...seen].sort((a, b) => b[1] - a[1])) {
    if (n >= 10) note(`  ${doc} pins ${n} line numbers — these rot silently; prefer symbol names`);
  }
}

/** 5. Every doc says how it is kept true.
 *
 *  A document with no maintenance story does not meet the brief: nobody knows what to re-check when
 *  the code moves, so nobody does, and it decays into confident misinformation. REPO_FACTS.md is
 *  exempt because it is regenerated wholesale — the script IS its maintenance story, and it is the
 *  ONLY name in the set.
 *
 *  THERE IS NO SECOND SET FOR "SOMEBODY ELSE'S DOCUMENT", and this comment used to say there was.
 *  It described "the five in PENDING", a set that does not exist in this file and by then held six
 *  names anyway — a stale comment, naming a phantom, carrying a wrong count, in the one file whose
 *  entire job is stopping documents from doing exactly that. The demotion it was describing happens
 *  once and centrally now, in OWNED_ELSEWHERE at the top of this file: a finding in a document
 *  another workstream owns becomes a warning rather than a failure no matter WHICH check produced
 *  it, so a missing maintenance section in one of those documents already reports as a warning and
 *  needs nothing here. No count of them is written down — the run prints it, and a number in a
 *  comment is a number nobody re-derives.
 *
 *  ADDING A NAME TO MAINTENANCE_EXEMPT IS NOT HOW YOU QUIET ONE OF THOSE. Exemption means "the
 *  section would be meaningless here", which is true of exactly one generated file. A document that
 *  merely lacks the section has a gap, and a visible gap is worth more than a green run; hiding it
 *  here would also hide it from the document's owner, who is the only person who can close it. */
const MAINTENANCE_EXEMPT = new Set(["REPO_FACTS.md"]);

/** 5b. The three exemption lists still name documents that exist, and the maintenance exemption is
 *  still a GENERATED file.
 *
 *  Every failure this catches is silent, which is why it is worth three lines of comparison. A name
 *  in OWNED_ELSEWHERE that no longer matches a file stops fencing anything: rename or delete one of
 *  those documents and its successor's findings go from warnings to failures with nothing anywhere
 *  saying why the fence moved. DRIFT_EXEMPT is worse — the register it protects is a dated snapshot
 *  whose line numbers ARE the record, so a name that has stopped matching hands the next reader a
 *  page of drift failures and an invitation to "fix" them by re-pinning, destroying exactly what the
 *  exemption existed to preserve. And MAINTENANCE_EXEMPT is defensible for one reason only, that the
 *  file it names is machine-written and the script is its maintenance story; a hand-written document
 *  in that set is not exempt, it is hidden.
 *
 *  THIS EXISTS BECAUSE THE COMMENT ABOVE MAINTENANCE_EXEMPT WENT STALE AND NOBODY NOTICED. It
 *  described a set named `PENDING`, which this file does not define, and gave a count of five for a
 *  fence that held six — in the file whose entire job is stopping documents from making confident
 *  false statements about the code. A comment cannot be tested and does not get re-read. An
 *  assertion that runs on every invocation cannot be not-read, so the rules the comments state are
 *  written here as well, where breaking one turns the docs run red with a line number. */
function selfTestExemptions() {
  const present = new Set(docFiles().map((d) => d.split(/[\\/]/).pop()));
  const named = [...OWNED_ELSEWHERE, ...DRIFT_EXEMPT.keys(), ...MAINTENANCE_EXEMPT];
  const missing = named.filter((base) => !present.has(base));
  for (const base of missing) {
    fail(`check-docs: an exemption list names ${base}, which is not a document in docs/ — the fence is protecting nothing`);
  }
  // Not "size <= 1": the point is that the ONE name is the generated file, so that adding a second
  // is a decision somebody has to come here and defend rather than a quiet way to silence a gap.
  if (MAINTENANCE_EXEMPT.size !== 1 || !MAINTENANCE_EXEMPT.has("REPO_FACTS.md")) {
    fail(
      "check-docs: MAINTENANCE_EXEMPT is for generated files only. A hand-written document that lacks " +
      "a maintenance section has a gap worth reporting — exempting it hides the gap from its owner.",
    );
  }
  if (!read(FACTS_FILE).includes("GENERATED FILE")) {
    fail("check-docs: REPO_FACTS.md is exempt from the maintenance-section rule for being generated, and it no longer says it is");
  }
  // Say what was actually true, not what was hoped for: a green-sounding note printed above its own
  // FAIL is the tone that teaches a reader to skim the notes, and skimmed output is where the stale
  // comment this check exists for survived a day.
  note(
    missing.length
      ? `${named.length} exemption entries checked — ${missing.length} name no document (see FAIL below)`
      : `${named.length} exemption entries, each naming a document that exists`,
  );
}

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

/** 8. The Android default API host is tied to the infrastructure table that claims to describe it.
 *
 *  `android/app/build.gradle.kts` compiles a default `apiBaseUrl` into every handset build that has
 *  no `local.properties` override — which is every CI build and every APK anyone sideloads. It is a
 *  deploy target written as a string literal, and nothing anywhere checked it against the document
 *  that tells an operator which distribution belongs to this portal.
 *
 *  THEY AGREE NOW, AND THE CHECK IS WHAT MADE THE DISAGREEMENT COSTLY ENOUGH TO SETTLE. For a
 *  fortnight ENVIRONMENT.md's rule 4 table named `d3ekigkotd1xa2.cloudfront.net` (origin id
 *  `designrepo-ec2-origin`) as this portal's CloudFront while the gradle default, the committed web
 *  production value and a dozen documents named `d2b34i3e92al6i.cloudfront.net` — the FIELD
 *  REPOSITORY's distribution, as it turned out. Both clients were pointed at another product's API.
 *  Resolved 2026-08-23 by measurement (`/api/design-workshops` is 404 on one and 401 on the other);
 *  the evidence is under ENVIRONMENT.md's CloudFront row and in docs/CI.md §0.
 *
 *  The old note here said the question could not be settled from a checkout, because no CloudFront
 *  resource exists in `infra/terraform/` — true, and not the same as unanswerable. Running the
 *  pipeline and watching which distribution's behaviour changed answered it from a checkout.
 *
 *  THIS STILL DOES NOT PICK A SIDE, and must not start. It asserts the two things that can be
 *  checked, in both directions:
 *
 *    * the literal in the gradle file is the literal ENVIRONMENT.md documents for `apiBaseUrl`, so
 *      changing the handset's default without the document is a red run rather than a silent drift;
 *    * while the gradle host and the infrastructure table's CloudFront row DISAGREE, the document
 *      must carry the dated open question naming both hosts — and once they AGREE, that block must
 *      be gone. An unresolved question that outlives its resolution is the next reader's wild goose
 *      chase, and an unrecorded contradiction is how this one survived four documents.
 */
const API_HOST_QUESTION = "UNRESOLVED — WHICH CLOUDFRONT DISTRIBUTION IS THIS PORTAL'S?";

function hostOf(url) {
  return String(url).replace(/^[a-zA-Z][\w+.-]*:\/\//, "").replace(/[/?].*$/, "");
}

function checkAndroidApiHost() {
  const gradlePath = join(REPO, "android", "app", "build.gradle.kts");
  const gradle = read(gradlePath);
  const declared = gradle.match(/"apiBaseUrl",\s*\n?\s*"([^"]+)"/);
  if (!declared) {
    fail("android/app/build.gradle.kts: no `apiBaseUrl` default found — this check can no longer say what the handset ships with");
    return;
  }
  const gradleUrl = declared[1];
  const gradleHost = hostOf(gradleUrl);

  const env = read(join(DOCS, "ENVIRONMENT.md"));
  if (!env.includes(gradleUrl)) {
    fail(
      `ENVIRONMENT.md does not document the handset's compiled-in default \`${gradleUrl}\` ` +
      "(android/app/build.gradle.kts). One of the two moved without the other.",
    );
  }

  // The CloudFront row of the "THIS PORTAL HAS ITS OWN INFRASTRUCTURE" table.
  // The table is indented inside a numbered list item, so the row does not start at column 0.
  const row = env.match(/^[ \t]*\|\s*CloudFront\s*\|\s*`([^`]+)`/m);
  if (!row) {
    fail("ENVIRONMENT.md: no `| CloudFront | ... |` row in the infrastructure table — the gradle default has nothing to be checked against");
    return;
  }
  const tableHost = hostOf(row[1]);
  const asks = env.includes(API_HOST_QUESTION);

  if (tableHost !== gradleHost && !asks) {
    fail(
      `ENVIRONMENT.md says this portal's CloudFront is ${tableHost} while android/app/build.gradle.kts ships ` +
      `${gradleHost}, and no open question records it. Add the "${API_HOST_QUESTION}" block, or make the two agree.`,
    );
  }
  if (tableHost === gradleHost && asks) {
    fail(
      `ENVIRONMENT.md still carries "${API_HOST_QUESTION}" although the table and the gradle default now both say ` +
      `${gradleHost}. Answer it in the document and delete the block.`,
    );
  }
  // The note says what is actually true in all three states, including the two that FAIL above. A
  // reassuring note printed over its own failure is how a reader learns to skim this output.
  note(
    tableHost === gradleHost
      ? asks
        ? `the handset default and ENVIRONMENT.md's infrastructure table agree on ${gradleHost}, and the open question about it is stale (see FAIL below)`
        : `the handset default and ENVIRONMENT.md's infrastructure table agree on ${gradleHost}`
      : asks
        ? `the handset ships ${gradleHost}, the infrastructure table says ${tableHost} — recorded as an open question, not settled`
        : `the handset ships ${gradleHost}, the infrastructure table says ${tableHost}, and nothing records the contradiction (see FAIL below)`,
  );
}

/** 8b. The Vercel project and team ids agree across every tracked file that writes one down.
 *
 *  WHY THIS EXISTS. On 2026-08-22 a wave went through this repository specifically to stop the
 *  FIELD REPOSITORY's Vercel project id being handed to a reader as this portal's deploy secret.
 *  It corrected docs/CI.md §2, the banner above it and `.github/workflows/deploy-frontend.yml`'s
 *  header — and left ENVIRONMENT.md's Actions-secrets table, the one document a reader is
 *  likeliest to open to look up a variable's value, still naming `prj_EzXN8hhGKpMciFBrZRdxpcgUUzN0`
 *  and calling it "an identifier, not a credential", i.e. harmless. Three of four is the normal
 *  outcome of threading a value through prose by hand, which is why it is checked here instead.
 *
 *  A project id is not a credential and it is not cosmetic either: it is the DEPLOY TARGET. The
 *  wrong one does not fail the run — it publishes this portal's build over another live product.
 *
 *  Ground truth is docs/CI.md §2, the row that tells a human what to paste. Both other files must
 *  state the same id. `frontend/.vercel/project.json` is the real article, but it is gitignored and
 *  absent from a fresh clone, so it is consulted when present and reported as unavailable when not
 *  — it can only ever add confidence, never be the thing this check depends on.
 *
 *  The second half is the more useful one: the field repository's id is deliberately QUOTED in all
 *  three files, as the value never to use. So a bare `prj_` that is not the canonical one is only
 *  acceptable where the surrounding lines say whose it is. The window is ±2 lines because in the
 *  workflow header the id and the words "field-repository" are one line apart. */
const VERCEL_ID_FILES = [
  ["docs/CI.md", join(DOCS, "CI.md")],
  ["docs/ENVIRONMENT.md", join(DOCS, "ENVIRONMENT.md")],
  [".github/workflows/deploy-frontend.yml", join(REPO, ".github", "workflows", "deploy-frontend.yml")],
];

/** The pair docs/CI.md §2 tells a human to paste, read off its own table rows. Memoised, because
 *  §8b checks the other two files against it and §10 checks the whole tree for ids that are neither
 *  these nor a value the register already accounts for. */
let CANONICAL_VERCEL = null;
function canonicalVercelIds() {
  if (CANONICAL_VERCEL) return CANONICAL_VERCEL;
  // The canonical pair: the first id quoted on CI.md's own `| \`VERCEL_PROJECT_ID\` |` table row.
  const ci = read(join(DOCS, "CI.md"));
  const ids = {};
  const missing = [];
  for (const [name, prefix] of [["VERCEL_PROJECT_ID", "prj_"], ["VERCEL_ORG_ID", "team_"]]) {
    const row = ci.match(new RegExp(`^\\|\\s*\`${name}\`[^\\n]*$`, "m"));
    const id = row && row[0].match(new RegExp(`${prefix}[A-Za-z0-9]+`));
    if (id) ids[name] = id[0];
    else missing.push([name, prefix]);
  }
  CANONICAL_VERCEL = { ids, missing };
  return CANONICAL_VERCEL;
}

function checkVercelIds() {
  const { ids: canonical, missing } = canonicalVercelIds();
  for (const [name, prefix] of missing) {
    fail(`docs/CI.md §2 has no \`${name}\` row naming a \`${prefix}\` id — the value the other files are checked against no longer exists`);
  }
  if (missing.length) return;

  for (const [rel, abs] of VERCEL_ID_FILES) {
    if (rel === "docs/CI.md") continue;
    const text = read(abs);
    for (const [name, prefix] of [["VERCEL_PROJECT_ID", "prj_"], ["VERCEL_ORG_ID", "team_"]]) {
      // The line where this file states the variable's value: a markdown table row or a header
      // comment line, both of which put the name and the id together. FIRST such line wins,
      // because every one of these files states the value once at the top and then discusses the
      // wrong one below it — the workflow's own guard prints both names on adjacent lines. A file
      // that loses its stating line falls through to the discussion and fails, which is right: it
      // no longer tells a reader what to paste.
      const stated = text
        .split("\n")
        .map((line) => (line.includes(name) ? line.match(new RegExp(`${prefix}[A-Za-z0-9]+`)) : null))
        .find(Boolean);
      if (!stated) {
        fail(`${rel} names ${name} but never gives a \`${prefix}\` id for it — docs/CI.md §2 says it is ${canonical[name]}`);
      } else if (stated[0] !== canonical[name]) {
        fail(
          `${rel} gives ${name} as ${stated[0]} while docs/CI.md §2 gives ${canonical[name]}. ` +
          "A project id is the deploy target: the wrong one publishes this build over another product's live site.",
        );
      }
    }
  }

  // The second half of this check — "any other id must say whose it is" — MOVED to
  // `checkSiblingIdentity` (§10) on 2026-08-22 and is not duplicated here. It runs there as a SHAPE
  // rule (`VERCEL_ID_SHAPE`) over every tracked file, rather than against a list of known literals
  // over these three — so an id that is in NO register, a typo or a leftover from an old link, is
  // caught wherever it is written. What stays here is the part that is specific to Vercel: the
  // three files that TELL AN OPERATOR WHAT TO PASTE must agree with each other, which is a
  // different claim from "this id is labelled".

  // The article itself, when this checkout has one. `vercel link` writes it and `.gitignore`
  // keeps it out, so its absence is the normal state in CI and is not a finding.
  const linkFile = join(REPO, "frontend", ".vercel", "project.json");
  let confirmed = "not in this checkout (`.vercel/` is gitignored)";
  if (existsSync(linkFile)) {
    try {
      const link = JSON.parse(read(linkFile));
      if (link.projectId !== canonical.VERCEL_PROJECT_ID) {
        fail(
          `frontend/.vercel/project.json links this checkout to ${link.projectId} while docs/CI.md §2 says the deploy target is ` +
          `${canonical.VERCEL_PROJECT_ID}. One of the two is describing a different product.`,
        );
      }
      confirmed = `confirmed by frontend/.vercel/project.json (${link.projectName ?? "no projectName"})`;
    } catch {
      fail("frontend/.vercel/project.json exists but is not readable JSON — delete it and re-run `vercel link`");
    }
  }
  note(
    `Vercel ids agree across ${VERCEL_ID_FILES.length} tracked files (${canonical.VERCEL_PROJECT_ID}, ${canonical.VERCEL_ORG_ID}); ${confirmed}`,
  );
}


/** 8c. WHICH POSTGRESQL PROVIDER HOSTS PRODUCTION — established once, from the only file that knows.
 *
 *  WHY THIS EXISTS. A provider migration landed before 2026-08-22 and left roughly THIRTY tracked
 *  files saying something untrue: docs/ENVIRONMENT.md, ARCHITECTURE.md, SCALABILITY.md, SECURITY.md,
 *  KUBERNETES.md, CI.md, DOCKER.md, DEPLOYMENT_VERCEL.md, README.md, the k8s base and both overlays,
 *  the Terraform, and a whole nightly workflow written for the old provider's pause behaviour. None
 *  of it failed anything. It rotted for months because the fact "where the database is" had no owner
 *  and no assertion — it was retyped into every document that needed to mention a database at all.
 *
 *  THE RULE THIS ENFORCES, and it is the one that stops a repeat: the APPLICATION REQUIRES
 *  PostgreSQL, and that requirement names no vendor. WHERE IT RUNS is a deployment fact, stated in
 *  ONE place — docs/ENVIRONMENT.md's "The database" section — beside the file that is its authority.
 *  Everything else either says "PostgreSQL" or says, with a date, that it is describing the past.
 *
 *  THE EVIDENCE, AND THE RULE ABOUT READING IT. The authority is `backend/.env.production`, which
 *  holds live credentials and is gitignored. So this check reads it for the PRESENCE OF A HOST
 *  SUBSTRING AND NOTHING ELSE: `providerFromEnvFile` returns provider NAMES off its own hardcoded
 *  table, never a line, never a URL, never a match from the file. No value read out of that file
 *  reaches a variable that any message interpolates. It is deliberately NOT routed through `read()`,
 *  whose cache lives for the whole run and is shared with every other check — a secret this one has
 *  to touch should not become a secret all the others are holding.
 *
 *  ABSENCE IS NOT A FAILURE. The file is gitignored, so it is missing from CI and from every fresh
 *  clone. Missing evidence reports what it cannot check and moves on; it never guesses, and it never
 *  falls back to `backend/.env`, because that file is a DEVELOPER'S local configuration and on a
 *  working machine it can name a local container and a cloud host at the same time. Only
 *  `.env.production` answers the question this check asks.
 *
 *  THE SECOND HALF is the part that would have caught the original rot: every tracked file that
 *  names a provider which is NOT the current one must say, within the lines around it, that it is
 *  talking about the past. `HISTORY_LABEL` is what counts as saying so. A mention that says nothing
 *  is either a leftover from the migration or a fresh copy-paste, and in a diff those look the same.
 *  Filenames are exempt: `keep-supabase-active.yml` is a real path and §2 checks it as one. */
const KNOWN_PROVIDERS = [
  // [name as written in prose, host substring that proves it, sweepable?]
  //
  // `sweepable` is the third column and the one that took a measurement to get right. A name has TWO
  // jobs here and they need different things of it. Identifying the provider FROM ITS HOST SUBSTRING
  // (job one) works for any name and is the assertion that actually matters. SWEEPING THE TREE FOR
  // THE NAME (job two) only works if the name is not also an ordinary word in this codebase.
  //
  // MEASURED — re-measured 2026-08-23 the same way, by flipping `Render` and `Timescale` to `true`
  // and re-running: **302 extra findings** (300 files naming `render`, 2 naming `timescale`), 681
  // individual mentions, and ZERO of them about a database. The `render` population is React
  // rendering plus the `Render:` prefix on a great many Playwright test titles; the `timescale` one
  // is `frontend/lib/media.ts` and an Android device-tier probe test. THAT 302 IS THE ONLY MEASURED
  // NUMBER IN THIS COMMENT and it is the same number quoted in `selfTestDatabaseProvider` — an
  // earlier draft carried 309 in one place and 302 in two, which is exactly the kind of unmeasured
  // figure this repository's first house rule is about. A check with that false-positive rate is not
  // a strict check, it is a check somebody deletes, and it would have buried the real Supabase
  // findings this sweep actually produced.
  //
  // So a name is swept only where the word means nothing else here. The rest are still IDENTIFIED by
  // host; they are simply not grep-able by name. If a future migration lands on one of them, the
  // honest move is to leave `sweepable` false and rely on the ENVIRONMENT.md sentence — not to flip
  // it and drown the run. Re-measure the same way before changing any of these.
  //
  // `sweepable` ALSO governs the "stated once" count at the foot of this check: that count is about
  // the CURRENT provider (Supabase, since 2026-09-02), and it can only be enforced for a name the
  // tree can be grepped for. Both names that have hosted this deployment stay sweepable: Neon so the
  // former-provider sweep catches an unlabelled leftover of the 2026-09-02 move, Supabase so the
  // "stated once" half can be counted rather than claimed. Neither word means anything else in this
  // tree — "neon" the CSS colour keyword does not appear, and the ARM-SIMD homonym is excused by
  // path in PROVIDER_HOMONYM_PREFIXES, not by unsweeping the name.
  ["Neon", "neon.tech", true],            // hosted this deployment 2026-08-22 → 2026-09-02
  ["Supabase", "supabase.co", true],      // CURRENT since 2026-09-02; also the pre-2026-08-22 era
  ["Amazon RDS", "rds.amazonaws.com", true],
  ["Cloud SQL", "cloudsql", false],       // "cloud" plus a product word, both common in prose here
  ["Azure", "postgres.database.azure.com", false],
  ["Render", "render.com", false],        // React renders; every spec file says it
  ["Railway", "railway.app", false],
  ["DigitalOcean", "ondigitalocean.com", true],
  ["Timescale", "tsdb.cloud.timescale.com", false], // "timescale" appears in the media/transcript code
];

/** The two files whose ENTIRE PURPOSE is the old provider, exempt from the name sweep by path.
 *
 *  They are not documents that happen to mention a provider — they are the workaround that exists
 *  only because of one provider's pause behaviour, and every line of the script is about that
 *  provider's pooler. Labelling each mention individually would mean interleaving a date into
 *  working code to satisfy a grep. Their DORMANCY is stated once, at the top of each file, with the
 *  date it should be reviewed; that is the honest place for it. If they are ever deleted, delete
 *  these two lines with them. */
const PROVIDER_WORKAROUND_FILES = new Set([
  ".github/workflows/keep-supabase-active.yml",
  "scripts/keep-supabase-active.mjs",
]);

/** The vendored tracer engine, exempt from the provider-name sweep because "NEON" there is the ARM
 *  SIMD instruction set and not the company hosting the database.
 *
 *  `android/core-imaging/.../Accel.kt:31` names its native backend `"neon-arm64"`, which is what
 *  every imaging library calls that code path; the four `core-*` modules are full of it. Two reasons
 *  this is a path exemption rather than a reworded line. First, these modules are vendored VERBATIM
 *  from Offline-Tracer and hashed file by file in `android/UPSTREAM-MANIFEST-KOTLIN.txt`, so editing
 *  them to satisfy a grep is not available: the next re-vendor undoes it and the manifest stops
 *  matching upstream, which is the one property the manifest exists to have. Second, the sweep's
 *  purpose is to stop a SECOND COPY of "which company hosts production" drifting when the deployment
 *  moves — and a SIMD register file cannot go stale that way, so there is nothing here for the rule
 *  to protect.
 *
 *  A PREFIX AND NOT A FILE LIST, deliberately: the exemption is a property of the vendored tree, and
 *  a list would go stale the first time upstream adds a file. If the engine is ever un-vendored and
 *  becomes ours to edit, delete this block with it. Added 2026-08-28, when vendoring first tripped
 *  the sweep. */
const PROVIDER_HOMONYM_PREFIXES = [
  "android/core-imaging/",
  "android/core-vector/",
  "android/core-pipeline/",
  "android/core-export/",
];

const isProviderHomonymPath = (rel) => PROVIDER_HOMONYM_PREFIXES.some((p) => rel.startsWith(p));

/** The words that say, in the lines around a provider name, that the sentence is about the past.
 *  A bare date is deliberately NOT enough — dates decorate live facts all over this repository — and
 *  neither is "was", which appears in every third sentence of this prose. */
const HISTORY_LABEL = new RegExp(
  [
    String.raw`\bhistoric(?:al|ally)?\b`,
    String.raw`\bdormant\b`,
    String.raw`\bdormancy\b`,
    String.raw`\bpreviously\b`,
    String.raw`\bformerly\b`,
    String.raw`\bno longer\b`,
    String.raw`\bused to\b`,
    String.raw`\blegacy\b`,
    String.raw`\bleft that provider\b`,
    String.raw`\bmoved off\b`,
    String.raw`\buntil 2\d{3}-\d{2}-\d{2}\b`,
    String.raw`\bhosted this deployment until\b`,
    String.raw`\bran on until\b`,
    String.raw`\bmeasured on\b`,
    String.raw`\bnot yet re-established\b`,
    String.raw`\bis not on that provider\b`,
    String.raw`\bwas never independently verified\b`,
  ].join("|"),
  "i",
);

/** Runs a wrapped block of prose back into one line, so a label can be matched across the break.
 *
 *  THIS EXISTS BECAUSE OF A MEASURED BUG, TWICE OVER. The sweep first tested HISTORY_LABEL against
 *  the raw window, and markdown wraps at ~100 columns — so "production left that\nprovider on
 *  2026-08-22" carries a newline through the middle of its own label and every multi-word entry in
 *  HISTORY_LABEL silently failed. Collapsing whitespace fixed the plain-prose case and left two
 *  more: a wrapped label inside a `>` blockquote flattens to "left that > provider", and one inside
 *  a `#` comment block to "until # 2026-08-22". The continuation MARKER is part of the wrap, so it
 *  has to come off with the newline.
 *
 *  Both variants were found the same way, by reading the findings the sweep produced during the
 *  2026-08-22 wave and checking each against the file: a dated note in `docs/KUBERNETES.md`'s
 *  blockquote and one in `.github/workflows/deploy-backend.yml`'s comment block were reported as rot
 *  while both said exactly what the check asks for. A check that reports correct work as rot is a
 *  check somebody deletes, so this is a load-bearing part of the sweep rather than a tidying
 *  convenience. Only LEADING markers are stripped, and only where a line begins with one, so a `#`
 *  or a `*` inside a sentence is left alone — the self-test pins that. */
function flattenProse(text) {
  return text
    .split("\n")
    .map((line) => line.replace(/^\s*(?:>\s*)*(?:#+|\/\/+|\*|-|\d+\.)?\s*/, ""))
    .join(" ")
    .replace(/\s+/g, " ");
}

/** The lines that may carry the label for a provider mention on line `ln` (1-based). TIGHT ON
 *  PURPOSE: the SAME table row, or the hit's line plus its two neighbours.
 *
 *  IT USED TO BE `labelWindow`, AND THAT WAS A REAL HOLE — the same one §10 documents finding and
 *  fixing for the sibling sweep. `labelWindow` returns ±4 lines or, for a table row, THE WHOLE
 *  TABLE, so ONE correctly dated row licensed every other unlabelled provider claim in the same
 *  table. Measured against the shipped functions: a five-row table whose third row said "Same
 *  dormancy." made a fourth row reading "Use the Supabase session pooler URL for DATABASE_URL."
 *  pass. It was not hypothetical — docs/ENVIRONMENT.md's own `DATABASE_USE_TRANSACTION_POOLER` row
 *  named a vendor pooler host with nothing dating it and was excused by the row underneath.
 *
 *  WHY ±1 RATHER THAN ±4 FOR PROSE. Markdown here wraps at ~100 columns, so a label and the mention
 *  it labels can straddle exactly one break — that is the case `flattenProse` exists for and the
 *  case this window has to keep reaching. Two lines away is a DIFFERENT SENTENCE, and a different
 *  sentence vouching for this one is the hole above in slower motion. A writer whose label ends up
 *  further away should move the label, which is better prose anyway.
 *
 *  §10's `labelWindow` is deliberately left alone: it answers a different question (which product a
 *  value belongs to, stated in a table header a dozen lines up) and has its own mitigation in
 *  `windowSaysWhose`. */
function providerLabelWindow(lines, ln) {
  const isRow = (s) => /^\s*>?\s*\|/.test(s ?? "");
  if (isRow(lines[ln - 1])) return lines[ln - 1];
  return lines.slice(Math.max(0, ln - 2), ln + 1).join("\n");
}

/** Where the ONE deployment statement lives, and the shape it must keep. Pinned to a SENTENCE
 *  rather than a table cell, because a sentence is what a reader actually believes. */
const PROVIDER_CLAIM = /\*\*Production runs on ([A-Z][A-Za-z0-9 ._-]{1,30}?)\.\*\*/;

/** Filenames that contain a provider name. A PATH IS NOT A CLAIM: `keep-supabase-active.yml` and
 *  `backend/.env.supabase.bak` are real files, §2 checks the paths that are written down, and the
 *  whole point of keeping the `.bak` one named is that a reader who finds it can tell what it is.
 *  `supabase/.temp/` joined 2026-09-02: the provider's CLI writes its machine-local state there,
 *  and the .gitignore pattern excluding it is a path too. Stripped from a line before the line is
 *  swept; the self-test pins that a claim sharing the line with a filename still reports. */
const PROVIDER_IN_A_FILENAME = /[\w./-]*(?:keep-supabase-active|\.env\.supabase|supabase\/\.temp)[\w./-]*/gi;

/** The lines in `lines` (returned 1-based) where `re` names a provider OUTSIDE the escapes every
 *  sweep in §8c shares: a filename that merely contains the name is stripped first, and a mention
 *  whose label window says "this is the past" is history doing its job, not rot. ONE helper serves
 *  the former-provider sweep and the "stated once" count so the two halves cannot drift apart —
 *  the 2026-09-02 move back onto a previously-swept provider is exactly when they would have. */
function unlabelledProviderMentions(lines, re) {
  const hits = [];
  for (let i = 0; i < lines.length; i += 1) {
    if (!re.test(lines[i])) continue;
    // Strip the filenames that legitimately contain a provider name, then re-test.
    if (!re.test(lines[i].replace(PROVIDER_IN_A_FILENAME, " "))) continue;
    if (HISTORY_LABEL.test(flattenProse(providerLabelWindow(lines, i + 1)))) continue;
    hits.push(i + 1);
  }
  return hits;
}

/** Reads `backend/.env.production` and returns ONLY the names of providers whose host substring is
 *  present — names taken from KNOWN_PROVIDERS above, never from the file. Nothing derived from the
 *  file's text is returned, stored, or logged. Returns null when there is no file to read. */
function providerFromEnvFile(abs) {
  if (!existsSync(abs)) return null;
  try {
    const hay = readFileSync(abs, "utf8").toLowerCase();
    return KNOWN_PROVIDERS.filter(([, host]) => hay.includes(host)).map(([name]) => name);
  } catch {
    return null;
  }
}

function checkDatabaseProvider() {
  const found = providerFromEnvFile(join(REPO, "backend", ".env.production"));

  const env = read(join(DOCS, "ENVIRONMENT.md"));
  const claim = env.match(PROVIDER_CLAIM);
  if (!claim) {
    fail(
      "docs/ENVIRONMENT.md has no `**Production runs on <Provider>.**` sentence. That one sentence is where this " +
      "repository states which PostgreSQL hosts production; without it every other document is free to invent an " +
      "answer, which is exactly how the last provider move went unrecorded in thirty files.",
    );
    return;
  }
  const claimed = claim[1];
  if (!KNOWN_PROVIDERS.some(([name]) => name === claimed)) {
    fail(
      `docs/ENVIRONMENT.md says production runs on "${claimed}", which is not in KNOWN_PROVIDERS in this file. Add ` +
      "it there with the host substring that proves it, or this check cannot verify the claim against " +
      "backend/.env.production and is asserting nothing at all.",
    );
    return;
  }

  if (found === null) {
    // The normal state in CI and in a fresh clone. Say what is unverified rather than implying it passed.
    note(
      `docs/ENVIRONMENT.md says production runs on ${claimed}; backend/.env.production is not in this checkout ` +
      "(gitignored), so that sentence is UNVERIFIED here rather than confirmed",
    );
  } else if (found.length === 0) {
    fail(
      `backend/.env.production names none of the ${KNOWN_PROVIDERS.length} provider hosts this check knows, while ` +
      `docs/ENVIRONMENT.md says production runs on ${claimed}. Either the deployment moved to a provider that is not ` +
      "in KNOWN_PROVIDERS — add it, with its host substring — or that sentence is now fiction. (Nothing from that " +
      "file is printed here, by design; a host substring is all the evidence this needs.)",
    );
  } else if (!found.includes(claimed)) {
    fail(
      `docs/ENVIRONMENT.md says production runs on ${claimed}, but the host in backend/.env.production is ` +
      `${found.join(" + ")}'s. The env file is the authority and the document is a report of it, so the document is ` +
      "the thing that is wrong. Fix that sentence FIRST, then sweep the mentions reported below — and do not paste " +
      "anything out of that file to do it.",
    );
  } else {
    note(
      `production database provider: ${claimed}, confirmed by the host substring in backend/.env.production` +
      (found.length > 1 ? ` (${found.join(" + ")} both present — see ENVIRONMENT.md)` : ""),
    );
  }

  // ── The sweep: a provider that is not the current one must be labelled as history. ──
  const stale = KNOWN_PROVIDERS.filter(([name, , sweepable]) => sweepable && name !== claimed);
  const seen = new Map();
  for (const rel of trackedTextFiles()) {
    // This file is exempt from its own sweep: naming every provider is what its table is FOR.
    if (rel === "docs/tools/check-docs.mjs") continue;
    if (PROVIDER_WORKAROUND_FILES.has(rel)) continue;
    // The vendored ARM-SIMD "neon" homonym — mattered nowhere while Neon was the CURRENT provider
    // (only "stated once" looked for it, and that half always had this escape), and matters here
    // from the day Neon became a FORMER one. See PROVIDER_HOMONYM_PREFIXES.
    if (isProviderHomonymPath(rel)) continue;
    const lines = read(join(REPO, rel)).split("\n");
    for (const [name] of stale) {
      const re = new RegExp(String.raw`\b${name.replace(/[.*+?^${}()|[\]\\]/g, String.raw`\$&`)}\b`, "i");
      const hits = unlabelledProviderMentions(lines, re);
      if (hits.length) {
        const key = `${rel} ${name}`;
        seen.set(key, (seen.get(key) ?? 0) + hits.length);
      }
    }
  }

  let unexplained = 0;
  for (const [key, count] of [...seen].sort()) {
    const allowed = PROVIDER_ALLOWLIST.get(key) ?? 0;
    if (count > allowed) {
      unexplained += 1;
      fail(
        `${key}: ${count} unlabelled mention(s)${allowed ? ` of which ${allowed} are allowlisted` : ""} of a provider ` +
        "that does not host production. Say PostgreSQL where it is a REQUIREMENT; where it is HISTORY say when it " +
        "stopped being true — a date plus one of the words in HISTORY_LABEL. Adding it to PROVIDER_ALLOWLIST to go " +
        "green is the one thing that list is not for.",
      );
    } else if (count) {
      known(`${key}: ${count} mention(s), allowlisted — a former provider named with nothing dating it`);
    }
  }
  if (!unexplained) {
    note(
      `no unlabelled mention of ${stale.map(([n]) => n).join(", ") || "a former provider"} outside ` +
      `PROVIDER_ALLOWLIST (${PROVIDER_ALLOWLIST.size} entr${PROVIDER_ALLOWLIST.size === 1 ? "y" : "ies"}) ` +
      `in the ${trackedTextFiles().length} files trackedTextFiles() returns; ` +
      `${KNOWN_PROVIDERS.length - stale.length - 1} other provider name(s) are identified by host but not swept ` +
      "by name — see KNOWN_PROVIDERS",
    );
  }

  // ── "Stated once" — the half the sweep above structurally cannot see. ──
  //
  // The sweep looks for FORMER providers. A SECOND live-tense statement of the CURRENT one is
  // invisible to it, and copying the current provider's name into every file that mentions a
  // database is precisely how the thirty untrue files were produced: each of them was accurate on
  // the day it was written. docs/ENVIRONMENT.md asserts "stated once" as the architecture, so this
  // counts it rather than taking its word for it. ENVIRONMENT.md itself is exempt — it is the one
  // place the name belongs — and so is this file, whose table has to spell every provider out.
  //
  // WHAT CHANGED ON 2026-09-02: production moved back onto a provider this repository has real
  // machinery named after — the keep-alive workaround, and a history of measured incidents from the
  // pre-2026-08-22 era. The original "zero mentions anywhere else" count, which held while the
  // current provider was a name nothing else used, would now fail the workaround files, every
  // correctly dated note about that era, and every path containing the name. So this half applies
  // the SAME escapes as the sweep above — workaround files, vendored homonym paths, filenames, and
  // history-labelled prose — via the shared unlabelledProviderMentions. What survives the escapes is
  // what the rule was always about: a LIVE-TENSE claim about where production runs, outside the one
  // file allowed to make it. Those fail, minus the counted CURRENT_PROVIDER_ALLOWLIST.
  //
  // ONLY POSSIBLE FOR A SWEEPABLE NAME. If the current provider's name is also an ordinary word here,
  // grepping for it measures nothing, and the honest output is to say the "once" is unenforced rather
  // than to print a green line that means "we did not look".
  const claimedRow = KNOWN_PROVIDERS.find(([name]) => name === claimed);
  if (!claimedRow?.[2]) {
    note(
      `"stated once" is NOT enforced for ${claimed}: its KNOWN_PROVIDERS row is not sweepable, so the name cannot be ` +
      "counted across the tree without false findings. ENVIRONMENT.md's claim that the provider is named in one place " +
      "is unverified here.",
    );
    return;
  }
  const claimedRe = new RegExp(String.raw`\b${claimed.replace(/[.*+?^${}()|[\]\\]/g, String.raw`\$&`)}\b`, "i");
  const elsewhere = new Map();
  for (const rel of trackedTextFiles()) {
    if (rel === "docs/tools/check-docs.mjs" || rel === "docs/ENVIRONMENT.md") continue;
    if (PROVIDER_WORKAROUND_FILES.has(rel)) continue;
    if (isProviderHomonymPath(rel)) continue;
    const n = unlabelledProviderMentions(read(join(REPO, rel)).split("\n"), claimedRe).length;
    if (n) elsewhere.set(rel, n);
  }
  let extra = 0;
  for (const [rel, n] of [...elsewhere].sort()) {
    const allowed = CURRENT_PROVIDER_ALLOWLIST.get(`${rel} ${claimed}`) ?? 0;
    if (n > allowed) {
      extra += 1;
      fail(
        `${rel}: ${n} live-tense mention(s)${allowed ? ` of which ${allowed} are allowlisted` : ""} of the CURRENT ` +
        `provider "${claimed}". Where production runs is stated in exactly one place — docs/ENVIRONMENT.md's "The ` +
        'database" section. History may name the provider WITH a label (a HISTORY_LABEL word in the same table row ' +
        "or the line's ±1 window), the keep-alive workaround machinery may name it freely, and a filename is a path, " +
        'not a claim — this line is none of those. Say what the sentence actually needs ("the provider hosting ' +
        'production today", "a managed PostgreSQL") and point at that section.',
      );
    } else if (n) {
      known(`${rel}: ${n} live-tense mention(s) of ${claimed}, allowlisted — machinery documentation, not a claim`);
    }
  }
  if (!extra) {
    note(
      `"${claimed}" appears live-tense only in docs/ENVIRONMENT.md and the ${CURRENT_PROVIDER_ALLOWLIST.size} ` +
      `allowlisted place(s), across the ${trackedTextFiles().length} files swept — every other mention is a ` +
      'filename, the keep-alive machinery, or labelled history. "Stated once" is enforced in its 2026-09-02 form.',
    );
  }
}

/** Files that still name a former provider with nothing saying it is the past.
 *
 *  READ THIS LIST; DO NOT GROW IT. Same contract as SIBLING_ALLOWLIST above: keyed FILE + PROVIDER
 *  with a count, so an edit elsewhere in the file does not churn the key, and a SECOND unlabelled
 *  mention in an already-listed file is a new finding rather than a free ride. Every entry is a real
 *  leftover of the migration; shrinking the list to nothing is the work. */
const PROVIDER_ALLOWLIST = new Map([
  // EMPTY as of 2026-09-02, and the emptying is the story: the move back onto the previously-swept
  // provider was the occasion to apply every recorded fix rather than carry it — entrypoint.sh and
  // .dockerignore now say "managed database", .gitignore dates its .bak description as history —
  // and the NEW former provider left no unlabelled mention behind (its name only ever appeared in
  // docs/ENVIRONMENT.md's authority section, which the move relabelled in the same change). An
  // entry added here is a regression to hunt down, not a workaround to keep.
]);

/** Live-tense mentions of the CURRENT provider that are machinery documentation rather than a
 *  second statement of where production runs. Same contract as PROVIDER_ALLOWLIST: keyed
 *  FILE + PROVIDER with a count, so a SECOND mention in a listed file is a new finding. Every entry
 *  must be documentation OF provider-specific machinery — never a claim about the deployment — and
 *  each dies with the machinery it documents. */
const CURRENT_PROVIDER_ALLOWLIST = new Map([
  // The root env template's keep-alive block: a TRUE description of what
  // `scripts/keep-supabase-active.mjs` does — it really does match `*.pooler.supabase.com:5432` and
  // nothing else. The same category as PROVIDER_WORKAROUND_FILES, one step removed: a template
  // documenting that script's own variables. Whoever retires the workflow deletes that block and
  // this line together.
  [".env.example Supabase", 1],
]);

/** Self-test for §8c. Every moving part of this check can be disarmed by an edit that reads as a
 *  copy-edit — a widened label word, a narrowed claim regex, a host substring that swallows another
 *  — so each one is exercised here against a case that must pass and a case that must not. */
function selfTestDatabaseProvider() {
  let n = 0;

  // The claim sentence must be found, and must yield the provider name alone.
  n += 1;
  const m = "text\n**Production runs on Neon.** *Recorded 2026-08-22.*\nmore".match(PROVIDER_CLAIM);
  if (!m || m[1] !== "Neon") {
    fail(
      "check-docs: §8c's PROVIDER_CLAIM no longer reads the provider out of its own sentence " +
      `(got ${m ? `"${m[1]}"` : "no match"}). The check would report a missing sentence on a document that has one.`,
    );
  }
  // …and must not match a sentence that merely discusses the claim without making it.
  n += 1;
  if (PROVIDER_CLAIM.test("the sentence that says **Production runs on** something")) {
    fail("check-docs: §8c's PROVIDER_CLAIM matches a sentence with no provider name in it");
  }

  // HISTORY_LABEL: what must, and must not, count as saying "this is the past".
  const labelCases = [
    [true, "**Historical, 2026-08-22.** Left from the Supabase deployment."],
    [true, "Supabase, which hosted this deployment until 2026-08-22."],
    [true, "It is dormant as of 2026-08-22."],
    [true, "production left that provider on 2026-08-22"],
    [true, "Measured on Supabase, before the move."],
    [true, "Supabase is no longer what this points at."],
    // The trap: a bare date decorates live claims all over this repository.
    [false, "On 2026-08-22 the Supabase pooler ceiling is 200 client connections."],
    [false, "Use the Supabase session pooler URL for DATABASE_URL."],
    [false, "The Supabase pooler speaks TLS, so this was thought to be safe."],
  ];
  for (const [want, line] of labelCases) {
    n += 1;
    if (HISTORY_LABEL.test(line) !== want) {
      fail(
        `check-docs: §8c's HISTORY_LABEL ${want ? "no longer accepts" : "now accepts"} "${line}". ` +
        (want
          ? "A correctly dated historical note would be reported as rot, and a check that cries wolf gets deleted."
          : "An unlabelled live claim about a former provider would pass, which is the exact rot this check exists for."),
      );
    }
  }

  // The provider table must separate the current provider from the old one BY SUBSTRING, which is
  // the whole reason this check never has to read a credential.
  n += 1;
  const hosts = new Map(KNOWN_PROVIDERS.map(([name, host]) => [name, host]));
  if (!hosts.get("Neon") || !hosts.get("Supabase") || hosts.get("Neon") === hosts.get("Supabase")) {
    fail("check-docs: §8c's KNOWN_PROVIDERS no longer tells Neon from Supabase by host substring");
  }
  // No host substring may contain another, or one deployment would report as two providers.
  for (const [aName, aHost] of KNOWN_PROVIDERS) {
    for (const [bName, bHost] of KNOWN_PROVIDERS) {
      if (aName === bName) continue;
      n += 1;
      if (aHost.includes(bHost)) {
        fail(
          `check-docs: §8c's host substring for ${bName} (${bHost}) is contained in ${aName}'s (${aHost}) — ` +
          "one deployment would be reported as two providers, and the disagreement message would be nonsense",
        );
      }
    }
  }

  // SWEEPABILITY. The measured failure of the first version of this check was sweeping for names
  // that are ordinary words, so both directions are pinned: Supabase and Neon must stay swept, and
  // the two words that produced the 302 false findings must stay unswept. That 302 is the figure in
  // KNOWN_PROVIDERS' header and the only one; if you re-measure it, change it in both places.
  const sweepable = new Map(KNOWN_PROVIDERS.map(([name, , sweep]) => [name, !!sweep]));
  const WHY_SWEPT = {
    Supabase: 'Supabase is the CURRENT provider (since 2026-09-02), and the "stated once" count at the foot of the check can only run on a sweepable name.',
    Neon: "Neon hosted this deployment from 2026-08-22 until 2026-09-02; not sweeping for it leaves the check asserting nothing about unlabelled leftovers of that move.",
  };
  for (const [name, want] of [["Supabase", true], ["Neon", true], ["Render", false], ["Timescale", false]]) {
    n += 1;
    if (!sweepable.has(name)) {
      fail(`check-docs: §8c's KNOWN_PROVIDERS no longer has a ${name} row, so its sweepability cannot be pinned`);
    } else if (sweepable.get(name) !== want) {
      fail(
        `check-docs: §8c now ${want ? "declines to sweep" : "sweeps"} for "${name}". ` +
        (want
          ? WHY_SWEPT[name]
          : `"${name}" is an ordinary word in this codebase — measured, sweeping for it and the other one adds 302 findings, none about a database.`),
      );
    }
  }
  // At least one provider must remain sweepable, or the second half of the check is dead code.
  n += 1;
  if (![...sweepable.values()].some(Boolean)) {
    fail("check-docs: §8c has no sweepable provider left — the tree sweep would silently check nothing");
  }

  // The filename escapes must exempt a PATH without excusing a claim that shares its line.
  for (const line of [
    "See .github/workflows/keep-supabase-active.yml for the cron.",
    "(`infra/terraform/fieldrepo-deploy.pem`, `backend/.env.supabase.bak`, and the rest)",
  ]) {
    n += 1;
    if (/\bSupabase\b/i.test(line.replace(PROVIDER_IN_A_FILENAME, " "))) {
      fail(`check-docs: §8c reports a filename as though it were an unlabelled provider claim — ${line}`);
    }
  }
  n += 1;
  if (!/\bSupabase\b/i.test("keep-supabase-active.yml pings Supabase so the project does not pause.".replace(PROVIDER_IN_A_FILENAME, " "))) {
    fail("check-docs: §8c's filename escape now swallows a real provider claim that shares a line with the filename");
  }

  // LABELS THAT WRAPPED. The measured bug, in all three variants it was found in: a label straddling
  // a line break is invisible to the sweep unless the continuation marker comes off with the newline.
  const wraps = [
    ["plain prose", "The 200 is a Supabase per-project figure, and production left that\nprovider on 2026-08-22."],
    ["a `>` blockquote", "> The 200 is Supabase's, and production left that\n> provider on 2026-08-22."],
    ["a `#` comment block", "# Migrations talk to the Supabase pooler. Written against that provider until\n# 2026-08-22."],
    ["a `*` list item", "* Supabase's ceiling, which is no longer\n  the one that applies."],
  ];
  for (const [what, text] of wraps) {
    n += 1;
    if (!HISTORY_LABEL.test(flattenProse(text))) {
      fail(
        `check-docs: §8c no longer recognises a history label wrapped across two lines in ${what}. ` +
        "That is the exact bug flattenProse exists for: without it, a correctly dated note reports as rot, " +
        "and a check that cries wolf gets deleted rather than fixed.",
      );
    }
  }
  // …and flattening must not invent a label where the raw text has none.
  n += 1;
  if (HISTORY_LABEL.test(flattenProse("Use the Supabase session pooler URL.\nIt is the one that works."))) {
    fail("check-docs: §8c's flattenProse now manufactures a history label out of two unlabelled lines");
  }
  // The marker strip must only take LEADING markers, or a sentence loses its own words.
  n += 1;
  if (flattenProse("a line\n# 2026-08-22 and a # inside it") !== "a line 2026-08-22 and a # inside it") {
    fail(
      "check-docs: §8c's flattenProse no longer strips only LEADING continuation markers " +
      `(got "${flattenProse("a line\n# 2026-08-22 and a # inside it")}")`,
    );
  }

  // THE SHARED MENTION-COUNTER behind both halves (the former sweep and "stated once"): a labelled
  // mention is history doing its job, an unlabelled one is rot, and a filename alone is not a
  // mention. One helper serves both so its edges are pinned once, here.
  const mentionCases = [
    [0, ["Runs on the Supabase project that", "hosted this deployment until 2026-08-22."]],
    [1, ["Use the Supabase session pooler URL for DATABASE_URL."]],
    [0, ["See .github/workflows/keep-supabase-active.yml for the cron."]],
  ];
  for (const [want, sample] of mentionCases) {
    n += 1;
    const got = unlabelledProviderMentions(sample, /\bSupabase\b/i).length;
    if (got !== want) {
      fail(
        `check-docs: §8c's unlabelledProviderMentions counts ${got} where ${want} was expected for ` +
        `${JSON.stringify(sample)} — ` +
        (want
          ? "an unlabelled live-tense mention no longer reports, which is the exact rot both halves exist for."
          : "labelled history or a bare filename now reports as rot, and a check that cries wolf gets deleted."),
      );
    }
  }

  // THE WINDOW. A row must not be excused by its NEIGHBOURS — the hole §10 found for the sibling
  // sweep and this one shipped with, because it borrowed §10's `labelWindow` (±4 lines, or the WHOLE
  // TABLE for a row). These four cases are the ones that were reported by hand against the shipped
  // functions during the 2026-08-23 repair; they are here so the next borrowing gets caught.
  const table = [
    "| Variable | Notes |",
    "|---|---|",
    "| A | Same dormancy. |",
    "| B | Use the Supabase session pooler URL for DATABASE_URL. |",
    "| C | plain |",
  ];
  n += 1;
  if (HISTORY_LABEL.test(flattenProse(providerLabelWindow(table, 4)))) {
    fail(
      "check-docs: §8c's label window excuses a table row using a DIFFERENT row's label. One dated row then " +
      "licenses every other unlabelled provider claim in the same table — the exact defect §10's windowSaysWhose " +
      "exists to stop, and the one that let docs/ENVIRONMENT.md carry an undated vendor pooler host.",
    );
  }
  n += 1;
  if (!HISTORY_LABEL.test(flattenProse(providerLabelWindow(table, 3)))) {
    fail("check-docs: §8c's label window no longer reads a table row's OWN label, so every dated row reports as rot");
  }
  // Prose: the immediately adjacent line still counts (that is the ~100-column wrap), two away does not.
  const prose = [
    "It used to match a vendor pooler host —",
    "`.pooler.supabase.com` — and rewrite the port.",
    "",
    "A separate sentence about Supabase entirely.",
  ];
  n += 1;
  if (!HISTORY_LABEL.test(flattenProse(providerLabelWindow(prose, 2)))) {
    fail("check-docs: §8c's label window no longer reaches the previous line — a label that wrapped would report as rot");
  }
  n += 1;
  if (HISTORY_LABEL.test(flattenProse(providerLabelWindow(prose, 4)))) {
    fail(
      "check-docs: §8c's label window reaches a label three lines away. That is a different sentence vouching for " +
      "this one, which is the table hole above in slower motion.",
    );
  }

  // COVERAGE. The sweep's green line says how many files it swept; extensionless files are the ones
  // that silently fell out of that count, so the basename escape is pinned here rather than trusted.
  n += 1;
  const swept = new Set(trackedTextFiles());
  for (const p of [".dockerignore", ".gitignore"]) {
    if (existsSync(join(REPO, p)) && !swept.has(p)) {
      fail(
        `check-docs: ${p} is tracked and carries prose, but trackedTextFiles() no longer returns it — so every sweep ` +
        "below reports itself as tree-wide while skipping it. See KEEP_BASENAMES.",
      );
    }
  }

  note(`${n} self-test cases for the database-provider check`);
}

/** 9. Every document in docs/ is listed in docs/README.md.
 *
 *  README.md states this rule about itself — "an unlisted document is the one failure mode this
 *  index cannot check for itself" — under a table of two documents added to close it. It was wrong
 *  by nineteen when this check was written, including the 2026-08-15 audit and OPEN_FINDINGS.md,
 *  which are the two a reader is most likely to go looking for and least likely to guess the name
 *  of. An index that says it is complete and is not is worse than one that admits a gap.
 *
 *  Cheap, exact, and it closes the failure mode the index named and could not test: the file must
 *  simply mention each basename somewhere. Not "link", deliberately — one listed document is
 *  gitignored and absent from a clone, so a link to it would fail the cross-link check instead. */
function checkIndexListsEveryDoc() {
  const index = read(join(DOCS, "README.md"));
  const missing = readdirSync(DOCS)
    .filter((f) => f.endsWith(".md") && f !== "README.md")
    .filter((f) => !index.includes(f));
  const total = readdirSync(DOCS).filter((f) => f.endsWith(".md")).length - 1;
  for (const base of missing) {
    fail(`docs/README.md does not list ${base} — an unlisted document is invisible to every reader who does not already know it exists`);
  }
  // Say what was actually true. A note reading "each named in the index" printed directly above
  // nineteen FAILs saying otherwise is the tone that teaches a reader to skim the notes.
  note(
    missing.length
      ? `${total} documents in docs/, ${missing.length} of them unlisted in docs/README.md (see FAIL below)`
      : `${total} documents, each named in docs/README.md`,
  );
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

/** Every tracked file whose bytes are prose or source, as repo-relative POSIX paths.
 *
 *  `git ls-files` rather than a directory walk: the sweeps below are about what a reader of THIS
 *  REPOSITORY can find, and an untracked scratch file is not that. It also keeps `node_modules`,
 *  build output and the sibling worktrees under `.claude/worktrees/` out without a denylist that
 *  would need maintaining. Lockfiles are excluded by name — they are megabytes of hashes that no
 *  identity fact is ever written into, and scanning them is most of this script's runtime.
 */
let TRACKED = null;
function trackedTextFiles() {
  if (TRACKED) return TRACKED;
  const KEEP = /\.(md|py|ts|tsx|js|jsx|mjs|cjs|kt|kts|yml|yaml|xml|sh|tf|tfvars|sql|toml|ini|cfg|gradle|properties|example|env|txt|json)$/i;
  // KEEP REQUIRES AN EXTENSION, AND SOME OF THE MOST PROSE-HEAVY FILES HERE HAVE NONE. Measured
  // against the regex above: `.dockerignore` false, `.gitignore` false, `Dockerfile` false — so every
  // sweep below silently skipped them while reporting itself as tree-wide. That was not theoretical:
  // `.dockerignore` carries a commented claim about which provider holds the LIVE PRODUCTION URL and
  // `.gitignore` carries one about `.env.supabase.bak`, which is exactly the rot §8c exists to catch.
  // Listed by BASENAME rather than loosened into KEEP, because "no extension" would also admit
  // binaries; anything added here has to be a text file somebody writes sentences in.
  const KEEP_BASENAMES = new Set([".dockerignore", ".gitignore", ".gitattributes", "Dockerfile"]);
  const keep = (p) => KEEP.test(p) || KEEP_BASENAMES.has(p.split("/").pop());
  const DROP = /(^|\/)(node_modules|\.claude|test-results)\//;
  let out = [];
  try {
    out = execSync("git ls-files -z", { cwd: REPO, encoding: "utf8", maxBuffer: 256e6 })
      .split("\0")
      .filter(Boolean)
      .filter((p) => keep(p) && !DROP.test(p) && !/(package|skills)-lock\.json$/.test(p))
      // THIS FILE IS EXEMPT FROM ITS OWN TWO SWEEPS, and the reason is not modesty. It is the one
      // place in the tree where "field-repository", "when X lands" and ", not before" are DATA:
      // they are the patterns being searched for, written out in the docstrings that explain them.
      // A checker that reports its own rule definitions reports five findings on its first run and
      // teaches its reader that the whole section is noise. Everything else it says about itself
      // still applies — its paths, its citations and its links are checked like any other file's.
      .filter((p) => p !== "docs/tools/check-docs.mjs");
  } catch {
    // No git in the environment (a tarball export, some CI images). The sweeps below then have
    // nothing to walk, and each says so rather than reporting a clean result it never measured.
    out = [];
  }
  TRACKED = out;
  return out;
}

/* ── 10. identity inherited from the field repository ───────────────────────────────────────── */

/** 10. Every fact that distinguishes this portal from the field repository, asserted from ONE place.
 *
 *  WHY THIS EXISTS, AND WHY IT IS ONE CHECK RATHER THAN A DOZEN. This portal was split from the
 *  field repository and kept that product's identity in about a dozen places. Three had been
 *  corrected one at a time before this check was written, and two of the three were ship-blockers
 *  in a single week: a setup script that sealed this portal's secrets into the other repository,
 *  and docs/CI.md §2 naming the other product's live box TOGETHER WITH the matching SSH key
 *  — wrong together, which is the pairing that authenticates and SUCCEEDS. Fixing them one at a
 *  time is how the twelfth survives, so the values are established once and swept for everywhere.
 *
 *  THE REGISTER IS docs/CI.md §0 — the two-column banner table headed "This portal" / "The field
 *  repository — never put these here". It was already there with five rows; this check turns it
 *  from a table a reader may consult into the thing the tree is measured against, and three rows
 *  were added with it (the Vercel production alias, the GitHub slug, and CloudFront). Ground truth
 *  for the "this portal" column is not this checker's business — the banner names the two artefacts
 *  it was read out of — but where a checkout HOLDS one of those artefacts it is consulted, because
 *  a register nothing can corroborate is a better-formatted rumour.
 *
 *  THE ASSERTION THAT CATCHES THE NEXT ONE is the second half: the field repository's values are
 *  QUOTED deliberately, all over this repository, as the values never to use. So a sibling value is
 *  acceptable exactly where the surrounding lines say whose it is. Anywhere else it is either a
 *  leftover from the split or a fresh copy-paste, and the two are indistinguishable in a diff. The
 *  window is four lines either side, widened to the whole markdown table when the hit is a table
 *  row, because a table labels its rows in a header that can sit a dozen rows above them.
 *
 *  THE REGISTER'S OWN SHAPE IS ASSERTED TOO, because the sweep reads its values OUT of that table
 *  and a copy-edit can therefore disarm it silently. Every row must still yield a backticked
 *  literal in BOTH columns unless it says in words why it cannot, and the number of sibling values
 *  the table yields is pinned at EXPECTED_SIBLING_VALUES. Replacing one value with prose used to
 *  drop it from the sweep, silence every finding that depended on it, and leave the exit code
 *  untouched, while reading in a diff as tidying.
 *
 *  THE ONE FACT THIS DELIBERATELY LEAVES OPEN. Which CloudFront distribution is this portal's
 *  cannot be settled from a checkout — see `checkAndroidApiHost` above and ENVIRONMENT.md §4. So
 *  the register's CloudFront row must say UNRESOLVED for exactly as long as that question stands,
 *  and must stop saying it the moment the question is answered; both directions fail. ENVIRONMENT.md
 *  §4 carries a SECOND copy of this register, and `checkSecondRegister` below holds the two to
 *  each other cell by cell — including that marker — because two registers that can disagree are
 *  worse than one, and they already had.
 *
 *  WHAT THE OPEN QUESTION IS NOT AN EXCUSE FOR — corrected 2026-08-22. This check first shipped with
 *  an escape that read ANY sibling value within four lines of `d2b34i3e92al6i` or `d3ekigkotd1xa2`
 *  as a restatement of that question. It was argued for the Elastic IP and applied to every value,
 *  so — measured — putting `fieldrepo-media-626159998512` back into DEPLOYMENT_VERCEL.md §0's table
 *  produced ZERO findings, because a neighbouring row of the same table named a CloudFront host,
 *  and the whole table is one label window. The escape is deleted, and its premise was wrong even
 *  for the IP it was written for: the DISTRIBUTION is open, the BOX is not. `15.207.145.174` is the
 *  field repository's, corroborated by the designrepo Terraform state and said in so many words by
 *  ENVIRONMENT.md §4, so a document pairing it with "this portal's backend" is making a false claim
 *  rather than restating a question — and is labelled the way everything else is, by saying whose
 *  box it is. Deleting the escape made five mentions visible; the two in this workstream's own
 *  DEPLOYMENT_VERCEL.md were labelled and the other three are on the allowlist below.
 *
 *  SEVERITY, AND WHY IT IS AN ALLOWLIST RATHER THAN A RULE ABOUT PATHS. An unlabelled sibling value
 *  is a FAILURE wherever it sits. Until 2026-08-22 only `docs/*.md` could fail, which meant a
 *  reintroduced deploy target in a .kt, a .tf or a .env.example produced a green run with one line
 *  in the log — and those are the files that actually point a build at a machine. What keeps
 *  today's tree green is SIBLING_ALLOWLIST: the nine mentions already here, written out one per
 *  line with whose file each is, for the same reason ROT_BASELINE is written out rather than
 *  hidden. Anything not on it is red, whatever its extension. Shrinking the list is the work.
 */
const REGISTER_ROWS = [
  "API box (Elastic IP)",
  "EC2 instance",
  "SSH key pair / file",
  "S3 media bucket",
  "Vercel project",
  "Vercel production alias",
  "GitHub repository",
  "CloudFront",
];
/** Words that say, in the lines around a sibling value, whose value it is. */
const SIBLING_LABEL = /field[- ]repository|field repo\b|fieldrepo|the other product|another product|never put these here|never appear here|must NOT be pasted/i;
/** True when the label regex matches the WHOLE literal, i.e. the value is its own label. */
const isOwnLabel = (v) => { const m = v.match(SIBLING_LABEL); return !!m && m[0].length === v.length; };

/** How many distinct field-repository literals docs/CI.md §0 is expected to yield.
 *
 *  PINNED, because the sweep reads its values out of that table, so a copy-edit to the table
 *  disarms the sweep with nothing going red. Measured 2026-08-22: replacing the S3 row's
 *  `fieldrepo-media-626159998512` with the words "the sibling bucket" took the run's note from
 *  "6 field-repository values … 37 labelled mention(s) across 16 file(s)" to "5 … 32 … 12", dropped
 *  the three findings in next.config.ts and variables.tf, and left the exit code where it was. The
 *  diff read as prose polish. A pinned count is the one thing a copy-edit cannot quietly remove. */
const EXPECTED_SIBLING_VALUES = 7;

/** Rows whose "field repository" cell legitimately holds no literal, and the words that say so.
 *  Every OTHER row must name a value in BOTH columns: a row that stops naming one stops being
 *  swept, and a fact that is not swept is not a fact this check knows about. */
const ROWS_WITHOUT_A_SIBLING_VALUE = new Map([
  ["CloudFront", /unresolved/i],
  ["GitHub repository", /not established/i],
]);

/** A two-segment `owner/repo` slug, as opposed to a filesystem path.
 *
 *  The literal filter drops anything containing `/` — the SSH row names
 *  `infra/terraform/fieldrepo-deploy.pem`, which is a path, is checked as one by §2, and is not an
 *  identity fact. But the GitHub repository IS a register row and `owner/repo` is the only way to
 *  write it, so the one row whose value must contain a slash could never be swept at all. Two
 *  segments and no more is what separates the two. */
const A_SLUG = /^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/;
const cellLiterals = (cell) =>
  [...cell.matchAll(/`([^`]+)`/g)]
    .map((x) => x[1])
    .filter((v) => v.length >= 8 && (!v.includes("/") || A_SLUG.test(v)));

/** A Vercel project or team id, by SHAPE rather than by being on a list.
 *
 *  Twenty-four base-62 characters with at least one capital and one digit in them. The class
 *  discriminates: `team_workshop` is a fixture name in `backend/tests/test_media_entitlement.py`
 *  and matches `team_[A-Za-z0-9]+`, which is why the rule this replaced could only ever be run over
 *  three hand-listed files. Sixteen characters is the floor rather than twenty-four so a truncated
 *  paste is still caught. */
const VERCEL_ID_SHAPE = /\b(?:prj|team)_(?=[A-Za-z0-9]{16,})(?=[A-Za-z0-9]*[A-Z])(?=[A-Za-z0-9]*\d)[A-Za-z0-9]+/g;

/** The field-repository values still written down somewhere with nothing saying whose they are.
 *
 *  READ THIS LIST; DO NOT GROW IT. Every entry is a real leftover from the split, kept out of the
 *  exit code only so that the check can be red for the NEXT one — an allowlist that a reader can
 *  see is the difference between "nine known problems" and "no problems". Keyed FILE + VALUE with a
 *  count rather than by line number, so an unrelated edit above the mention does not churn it, and
 *  a SECOND copy of the same value in the same file is a new finding rather than a free pass.
 *
 *  Every one of these was invisible until 2026-08-22: five behind the deleted CloudFront escape,
 *  four behind the rule that only `docs/*.md` could fail. None of the files is this workstream's.
 *
 *  SHRANK FROM NINE ENTRIES TO FOUR on 2026-08-23, which is the direction this list is supposed to
 *  move. Four were fixed with the real value rather than a label: README.md's two mentions and
 *  docs/CDN.md's worked example presented the sibling's Elastic IP as this portal's backend, and the
 *  note here said the fix had to be a label because "which distribution sits in front of it is the
 *  open question" — that question is now answered (docs/CI.md §0), so the honest fix became
 *  available. The other two, `frontend/next.config.ts` and `infra/terraform/variables.tf`, had
 *  already been corrected and their entries were stale: an allowlist nobody prunes stops describing
 *  the code and starts excusing it. */
const SIBLING_ALLOWLIST = new Map([
  // A handover note naming the other product's SSH key file.
  ["SESSION_HANDOVER.md fieldrepo-deploy", 1],
  // The handset's own source. A .kt cannot fail this run's owner's build, but it is a live client.
  ["android/app/src/main/java/com/designprototype/workshop/MainActivity.kt field-repository.vercel.app", 1],
  ["android/app/src/main/res/xml/network_security_config.xml 15.207.145.174", 1],
]);

function registerFacts() {
  const ci = read(join(DOCS, "CI.md"));
  const facts = [];
  for (const m of ci.matchAll(/^>\s*\|\s*([^|]+?)\s*\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|\s*$/gm)) {
    const [, label, ours, theirs] = m;
    if (/^-+$/.test(label)) continue;
    facts.push({
      label,
      ours: cellLiterals(ours),
      theirs: cellLiterals(theirs),
      oursCell: ours,
      theirsCell: theirs,
    });
  }
  return facts;
}

/** Does the window around a hit say whose value it is?
 *
 *  EVERY sibling value is blanked before the label is looked for, not just the one being judged.
 *  `fieldrepo-media-…` contains the word that would otherwise label it, so a value must not vouch
 *  for itself — and it must not vouch for its NEIGHBOURS either: "the bucket is fieldrepo-media-…
 *  and the box is 15.207.145.174" would otherwise have the bucket silently excusing the IP, which
 *  is exactly the pairing docs/CI.md §0 exists to stop.
 *
 *  SIBLING_LABEL IS THE ONLY THING THAT LABELS. There was briefly a second escape here — a window
 *  naming either CloudFront host counted as a restatement of the open distribution question — and
 *  because a markdown table is one window, one CloudFront row excused every other inherited value
 *  in the same table. Pinned by `selfTestSiblingSweep`. */
function windowSaysWhose(lines, ln, siblings) {
  let win = labelWindow(lines, ln);
  for (const s of siblings) win = win.split(s.v).join(" ");
  return SIBLING_LABEL.test(win);
}

/** The lines that may carry the label for a hit on line `ln` (1-based): ±4, or the whole table. */
function labelWindow(lines, ln) {
  const isRow = (s) => /^\s*>?\s*\|/.test(s ?? "");
  if (isRow(lines[ln - 1])) {
    let a = ln - 1;
    let b = ln - 1;
    while (a > 0 && isRow(lines[a - 1])) a -= 1;
    while (b < lines.length - 1 && isRow(lines[b + 1])) b += 1;
    return lines.slice(Math.max(0, a - 2), b + 1).join("\n");
  }
  return lines.slice(Math.max(0, ln - 5), ln + 4).join("\n");
}

function checkSiblingIdentity() {
  const facts = registerFacts();
  if (facts.length === 0) {
    fail("docs/CI.md §0: no `> | … | … | … |` register table found — the sibling-identity sweep has nothing to check against");
    return;
  }
  const byLabel = new Map(facts.map((f) => [f.label, f]));
  for (const want of REGISTER_ROWS) {
    const row = byLabel.get(want);
    if (!row) {
      fail(
        `docs/CI.md §0's register has no \`${want}\` row. That table is what every other file's identity is measured ` +
        "against, so a fact with no row in it is a fact that can diverge silently — which is the whole reason it exists.",
      );
      continue;
    }
    // A row that has stopped NAMING a value is a row that has stopped being checked. Pinning the
    // labels alone left the sweep disarmable by prose; see EXPECTED_SIBLING_VALUES.
    const excuse = ROWS_WITHOUT_A_SIBLING_VALUE.get(want);
    for (const [side, lits, cell] of [["This portal", row.ours, row.oursCell], ["The field repository", row.theirs, row.theirsCell]]) {
      if (lits.length || (excuse && excuse.test(cell))) continue;
      fail(
        `docs/CI.md §0's \`${want}\` row gives no backticked value in the "${side}" column — it reads "${cell.trim() || "(empty)"}". ` +
        "Every other file's identity is measured against the literals in this table, so a row that states its value in prose " +
        "is a row that is no longer checked anywhere, and the diff that did it reads as tidying.",
      );
    }
  }

  // 10a. CloudFront stays open for exactly as long as ENVIRONMENT.md's question does.
  const cf = byLabel.get("CloudFront");
  const asks = read(join(DOCS, "ENVIRONMENT.md")).includes(API_HOST_QUESTION);
  const cfOpen = cf ? /unresolved/i.test(cf.oursCell) && /unresolved/i.test(cf.theirsCell) : false;
  if (cf && asks && !cfOpen) {
    fail(
      "docs/CI.md §0's CloudFront row names a distribution while ENVIRONMENT.md still carries " +
      `"${API_HOST_QUESTION}". Nothing in a checkout settles that — answer it in ENVIRONMENT.md first, or put the row back to UNRESOLVED.`,
    );
  }
  if (cf && !asks && cfOpen) {
    fail("docs/CI.md §0's CloudFront row still says UNRESOLVED although ENVIRONMENT.md no longer asks the question. Fill the row in from the answer.");
  }

  // 10b. The register's own column, corroborated where this checkout holds the artefact it was read
  //      from. Only the two NON-SENSITIVE Terraform outputs are looked at: that state file also
  //      holds a live AWS secret access key, and a documentation checker has no business with it.
  //
  //      READ WITH A BARE `readFileSync`, NOT THROUGH `read()`. The memoising reader would keep the
  //      whole state file — secret included — in this process's memory for the rest of the run, so
  //      "only two outputs are read" would be true of the JSON access and false of the file. The
  //      parsed object goes out of scope with this block; nothing retains it.
  const witnessed = [];
  const tfstate = join(REPO, "infra", "terraform", "terraform.tfstate.d", "designrepo", "terraform.tfstate");
  if (existsSync(tfstate)) {
    try {
      const out = JSON.parse(readFileSync(tfstate, "utf8")).outputs ?? {};
      for (const [label, key] of [["API box (Elastic IP)", "api_public_ip"], ["S3 media bucket", "s3_bucket"]]) {
        const claimed = byLabel.get(label)?.ours ?? [];
        const actual = out[key]?.value;
        if (!actual || claimed.length === 0) continue;
        if (!claimed.includes(actual)) {
          fail(
            `docs/CI.md §0 gives ${label} as ${claimed.join(", ")} while the designrepo Terraform workspace's ` +
            `\`${key}\` output is ${actual}. One of the two is describing a different deployment.`,
          );
        } else witnessed.push(label);
      }
    } catch {
      note("the designrepo Terraform state is present but unreadable — the register went uncorroborated on this run");
    }
  }
  // The GitHub slug is the one register value with a `/` in it. `A_SLUG` is what keeps the literal
  // filter from dropping it — that filter exists to keep filesystem paths out — so the row is swept
  // like any other the day a sibling slug is written into it, instead of being the one fact with no
  // assertion at all, which is precisely the state every row was in before this check.
  //
  // The remote is the article, when there is one: an export or a fresh `git init` has none, and
  // that is reported rather than passed over, because "no remote" and "the right remote" are not
  // the same result and a checker that conflates them is claiming a measurement it did not take.
  const slugRow = byLabel.get("GitHub repository");
  if (slugRow) {
    const claimed = [...slugRow.oursCell.matchAll(/`([^`]+)`/g)].map((m) => m[1])[0];
    let remote = null;
    try {
      remote = execSync("git remote get-url origin", { cwd: REPO, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
    } catch { /* no remote, or no git: reported in the note below rather than guessed at */ }
    const slugOf = (u) => (u.match(/[:/]([^/:]+\/[^/]+?)(?:\.git)?$/) || [])[1];
    if (claimed && remote && slugOf(remote) !== claimed) {
      fail(
        `docs/CI.md §0 gives the GitHub repository as \`${claimed}\` while this checkout's \`origin\` is ${remote}. ` +
        "The register is what tells a reader which repository's Actions secrets the deploy targets belong to.",
      );
    } else if (claimed && remote) witnessed.push("GitHub repository");
    else if (!remote) note("no `origin` remote in this checkout — docs/CI.md §0's GitHub row went uncorroborated on this run");
  }

  const link = join(REPO, "frontend", ".vercel", "project.json");
  if (existsSync(link)) {
    try {
      const name = JSON.parse(read(link)).projectName;
      const claimed = byLabel.get("Vercel project")?.ours ?? [];
      if (name && claimed.length && !claimed.includes(name)) {
        fail(`docs/CI.md §0 gives the Vercel project as ${claimed.join(", ")} while frontend/.vercel/project.json links this checkout to \`${name}\`.`);
      } else if (name) witnessed.push("Vercel project");
    } catch {
      /* checkVercelIds already fails on unreadable JSON here; one message is enough. */
    }
  }

  // 10c. The sweep. Every sibling value, everywhere, must say whose it is.
  // A sibling value that IS the label vocabulary cannot be swept: `field-repository` is both the
  // other product's Vercel project name and the words every correct mention of it uses, so every
  // occurrence would be reported and every one of them would be a false alarm. Skipped when the
  // label regex matches the WHOLE literal — which leaves `field-repository.vercel.app` (a deploy
  // target), `fieldrepo-media-…` and `fieldrepo-deploy` swept, because there the label matches only
  // a prefix and the rest of the string is the part that does damage.
  const siblings = [];
  for (const f of facts) for (const v of f.theirs) if (!isOwnLabel(v)) siblings.push({ v, label: f.label });
  siblings.sort((a, b) => b.v.length - a.v.length); // longest first: one hit, not one per prefix
  if (siblings.length !== EXPECTED_SIBLING_VALUES) {
    fail(
      `docs/CI.md §0's register yields ${siblings.length} sweepable field-repository value(s), and EXPECTED_SIBLING_VALUES says ` +
      `${EXPECTED_SIBLING_VALUES}. Fewer means a row stopped naming its value and the sweep silently stopped looking for it; ` +
      "more means a fact was added without anybody deciding it should be swept. Either way, say so here.",
    );
  }
  // 10d's shape rule needs to know which ids are THIS portal's before it can call the rest unknown.
  // When §2 has lost a row, `checkVercelIds` has already failed for it and every canonical id here
  // would be reported as a stranger — one real failure turned into a flood, which is how a reader
  // learns to skim. So the shape rule stands down and says so, and the literal sweep carries on.
  const vercel = canonicalVercelIds();
  const canonicalIds = new Set(Object.values(vercel.ids));
  const idShapeRuns = vercel.missing.length === 0;
  if (!idShapeRuns) note("docs/CI.md §2 is missing an id row, so §10d's unknown-Vercel-id rule stood down this run");
  const allowanceLeft = new Map(SIBLING_ALLOWLIST);
  let labelled = 0;
  let carrying = 0;
  for (const rel of trackedTextFiles()) {
    let text;
    try { text = read(join(REPO, rel)); } catch { continue; }
    const anyId = VERCEL_ID_SHAPE.test(text);
    VERCEL_ID_SHAPE.lastIndex = 0;
    if (!anyId && !siblings.some((s) => text.includes(s.v))) continue;
    if (siblings.some((s) => text.includes(s.v))) carrying += 1;
    const lines = text.split("\n");

    const saysWhose = (i) => windowSaysWhose(lines, i + 1, siblings);
    lines.forEach((line, i) => {
      const taken = [];
      for (const { v, label } of siblings) {
        for (let at = line.indexOf(v); at !== -1; at = line.indexOf(v, at + 1)) {
          if (taken.some(([a, b]) => at < b && at + v.length > a)) continue;
          taken.push([at, at + v.length]);
          if (saysWhose(i)) { labelled += 1; continue; }
          const key = `${rel} ${v}`;
          const left = allowanceLeft.get(key) ?? 0;
          if (left > 0) {
            allowanceLeft.set(key, left - 1);
            known(
              `${rel}:${i + 1} writes \`${v}\` — the FIELD REPOSITORY's ${label} — with nothing beside it saying whose it is. ` +
              "On SIBLING_ALLOWLIST, so not fatal. Shrink that list; do not grow it.",
            );
          } else {
            fail(
              `${rel}:${i + 1} writes \`${v}\` — the FIELD REPOSITORY's ${label}, per the register in docs/CI.md §0 — ` +
              "and nothing within four lines says whose it is. Either it is a leftover from the split and this file " +
              "should name this portal's value instead, or it is quoted on purpose and must say so.",
            );
          }
        }
      }
      // 10d. And an id of the Vercel SHAPE that is in no register at all. The literal sweep above
      // can only find values somebody already wrote into §0; a stale id, a typo, or one copied from
      // an old dashboard link is in none of them, and it is still a deploy target. This is the half
      // of `checkVercelIds` that moved here on 2026-08-22, widened from three files to every one.
      for (const m of idShapeRuns ? line.matchAll(VERCEL_ID_SHAPE) : []) {
        const id = m[0];
        if (canonicalIds.has(id)) continue;
        if (siblings.some((s) => s.v === id)) continue; // already judged, above
        if (saysWhose(i)) continue;
        fail(
          `${rel}:${i + 1} writes \`${id}\`, a Vercel id that is neither this portal's (docs/CI.md §2) nor the field ` +
          "repository's (docs/CI.md §0). An id nobody has written down is still a deploy target: the wrong one does not " +
          "fail, it publishes this build over whatever is at the other end. Name it in §0 or say whose it is.",
        );
      }
    });
  }
  const unused = [...allowanceLeft].filter(([, n]) => n > 0);
  for (const [key, n] of unused) {
    note(`SIBLING_ALLOWLIST has ${n} unused slot(s) for \`${key}\` — the mention is gone, so delete the entry`);
  }
  note(
    `${facts.length} identity facts registered in docs/CI.md §0 ` +
    `(${witnessed.length ? `corroborated here: ${witnessed.join(", ")}` : "no corroborating artefact in this checkout"}); ` +
    `${siblings.length} field-repository values swept over ${trackedTextFiles().length} tracked files — ` +
    `${labelled} labelled mention(s) across ${carrying} file(s), ` +
    `${[...SIBLING_ALLOWLIST.values()].reduce((a, b) => a + b, 0)} allowlisted; ` +
    `CloudFront ${cfOpen ? "deliberately UNRESOLVED, see ENVIRONMENT.md §4" : "resolved"}`,
  );
}

/* ── 10e. the second register ───────────────────────────────────────────────────────────────── */

/** 10e. ENVIRONMENT.md §4's copy of the register must agree with docs/CI.md §0, cell for cell.
 *
 *  §10 above says the facts are established ONCE. They were not: ENVIRONMENT.md §4 carries a second
 *  two-column table with the same rows — API box, S3 media bucket, CloudFront, SSH key pair, Vercel
 *  — and it is, by that document's own words, "the one document a reader is likeliest to open to
 *  look up a variable's value". Nothing compared the two. MEASURED 2026-08-22: swapping that table's
 *  S3 row end for end, so it told every reader this portal's bucket was `fieldrepo-media-…`,
 *  produced no finding at all, because both values sit under a "Field repository" header and the
 *  sweep therefore counts both as labelled. The sweep cannot catch this; only a comparison can.
 *
 *  The two had ALREADY diverged on the one row this section exists to hold open: §0 says CloudFront
 *  is UNRESOLVED, and §4's table filled it in. Both must carry the marker while the question stands.
 *
 *  A ROW §4 HAS AND §0 DOES NOT is fine, and two of them are the point of §4's table (Google OAuth,
 *  and the systemd units that are deliberately IDENTICAL on both boxes) — but it has to be DECLARED
 *  here, so that a row added to one register and not the other is a decision somebody made rather
 *  than a drift nobody saw. And if §4's table is ever collapsed into a pointer at §0 — the other
 *  good answer to this — the check says so and passes, because one register cannot disagree. */
const SECOND_REGISTER_HEADER = "| | Designer portal (this repo) | Field repository |";
const SECOND_REGISTER_ROWS = new Map([
  ["API box", "API box (Elastic IP)"],
  ["S3 media bucket", "S3 media bucket"],
  ["CloudFront", "CloudFront"],
  ["SSH key pair", "SSH key pair / file"],
  ["Vercel", "Vercel production alias"],
  ["Google OAuth", null], // §0 does not carry it; nothing to compare
  ["systemd units", null], // identical on both boxes ON PURPOSE — the row exists to say so
]);

function checkSecondRegister() {
  const text = read(join(DOCS, "ENVIRONMENT.md"));
  const lines = text.split("\n");
  const at = lines.findIndex((l) => l.trim() === SECOND_REGISTER_HEADER);
  if (at === -1) {
    note("ENVIRONMENT.md carries no second register — docs/CI.md §0 is the only one, which is the simplest way for these to agree");
    return;
  }
  const byLabel = new Map(registerFacts().map((f) => [f.label, f]));
  const asks = text.includes(API_HOST_QUESTION);
  let compared = 0;
  for (let i = at + 1; i < lines.length && /^\s*\|/.test(lines[i]); i += 1) {
    const cells = lines[i].split("|").map((c) => c.trim());
    if (cells.length < 5) continue;
    const [, label, ours, theirs] = cells;
    if (!label || /^-+$/.test(label)) continue;
    if (!SECOND_REGISTER_ROWS.has(label)) {
      fail(
        `ENVIRONMENT.md §4's register has a \`${label}\` row that SECOND_REGISTER_ROWS does not know about. Either it restates a ` +
        "docs/CI.md §0 row — in which case map it here so the two are compared — or it is a fact only this table carries, " +
        "in which case say so here. Two registers that can drift apart are worse than one.",
      );
      continue;
    }
    const want = SECOND_REGISTER_ROWS.get(label);
    if (!want) continue;
    const row = byLabel.get(want);
    if (!row) continue; // §0 has lost the row; the loop above already failed for it
    if (want === "CloudFront" && asks && !/unresolved/i.test(lines[i])) {
      fail(
        "ENVIRONMENT.md §4's CloudFront row states a distribution with no UNRESOLVED marker on it, while the document itself " +
        `still carries "${API_HOST_QUESTION}" and docs/CI.md §0's row says UNRESOLVED. This is the table a reader opens to ` +
        "look up the value, so it is the one place the open question must not be quietly answered.",
      );
    }
    for (const [side, registered, cell] of [["Designer portal (this repo)", row.ours, ours], ["Field repository", row.theirs, theirs]]) {
      const here = cellLiterals(cell);
      if (!here.length || !registered.length) continue; // one side states it in prose; nothing to compare
      const disagree = here.filter((v) => !registered.includes(v));
      if (disagree.length) {
        fail(
          `ENVIRONMENT.md §4's \`${label}\` row gives ${disagree.join(", ")} in the "${side}" column, and docs/CI.md §0's ` +
          `\`${want}\` row gives ${registered.join(", ")}. Two registers, two answers: a reader takes whichever one they opened.`,
        );
      }
    }
    compared += 1;
  }
  note(`${compared} row(s) of ENVIRONMENT.md §4's register compared cell-for-cell with docs/CI.md §0`);
}

/* ── 11. comments that assert a state the code has left behind ──────────────────────────────── */

/** 11. Comment shapes that rot, reported as warnings against a baseline of the ones already here.
 *
 *  THE PATTERN THIS REPOSITORY NAMED ABOUT ITSELF. COMPUTED_FINDINGS.md §7 records a docstring on
 *  `workshop_cost_integrity` that said no designer sees a cost-integrity finding on any surface
 *  today, and ended "delete this paragraph when the ports and a panel land, not before". Both ports
 *  landed. Nobody deleted it. The sentence was then not merely stale but ARMED: the only reader who
 *  could act on the instruction was one who already knew the answer, and the claim above it is
 *  exactly what stops such a reader looking. A shipped feature was documented as unreachable for a
 *  fortnight, in the file that implements it. Roughly a dozen live instances of the same family
 *  were found across Python, Kotlin, TypeScript and Markdown in the same audit.
 *
 *  WHAT IS CHECKED, AND WHY EACH SHAPE. Prose cannot be verified, but a claim about the state of
 *  the world that carries NO DATE cannot be re-checked by anyone either, and that is mechanical:
 *
 *    * `when X lands` / `when it ships` — a promise about a future edit, paired either with a
 *      not-yet-built noun (route, endpoint, panel, port, migration…) or with an obligation in the
 *      lines around it (must, should, delete, rewrite). Without one of those two it is almost
 *      always runtime prose — "when the upload lands" — and is left alone.
 *    * `, not before` — the tail of the instruction form above, and the exact words of the one this
 *      repository named.
 *    * `today, everywhere` and `for now` — a claim scoped to a moment that does not name the moment.
 *    * `Phase 2` / `Phase 3` — a plan's vocabulary, meaningless to a reader who was not in the room
 *      and unfalsifiable once the plan is superseded.
 *    * a bare count of call sites — "all three callers", "the two call sites". The count is an
 *      invariant the comment is asserting, and the fourth caller does not come with a reminder.
 *
 *  THE ESCAPES, WHICH ARE THE POINT RATHER THAN A CONCESSION. Three, and each of them is the fix
 *  rather than a suppression:
 *
 *    1. AN ISO DATE within three lines silences every shape. That is the shape this repository's own
 *       docs recommend over an instruction — "true as of «date»; check `«grep»`" — because a dated
 *       claim tells the next reader what to re-check and when it was last true, while an undated
 *       one only tells them what to believe. So the cheapest way to clear a warning here is to
 *       improve the comment in exactly the direction the house style already asks for.
 *    2. A QUOTATION is somebody else's words. `stage_definitions.py` quotes the source Word
 *       document's margin notes — "Phase 2 work", "we may consider deleting this entire section
 *       for now" — and those are evidence, not claims: the quotation is true forever precisely
 *       because the document said it. Text inside quotes is skipped.
 *    3. A FILE WHOSE NAME CARRIES A DATE is dated by construction. `docs/AUDIT-2026-08-15.md` is a
 *       record of what was true on 15 August; asking it to date its sentences is asking it to
 *       repeat its own filename nine hundred times.
 *
 *  WARNING, NOT FAILURE, AND WHY THE BASELINE IS SPELLED OUT IN FULL. A gate that goes red on the
 *  day it lands gets disabled, and this one started with 57 hits across five languages, most of
 *  them in files this workstream does not own. So the existing ones are listed below and the check
 *  reports only what is NOT in that list. The list is written out one line per instance rather than
 *  kept in a side file on purpose: a baseline nobody reads is a suppression list, and this one is
 *  meant to be read and emptied. Regenerate it with `--rot-baseline`, which prints a replacement
 *  block to stdout — and shrinking it is the work, not regenerating it.
 *
 *  THE KEY IS SHAPE + FILE + A HASH OF THE COMMENT LINE, deliberately not a line number: comments
 *  move constantly and a baseline that churns on every unrelated edit is one nobody will trust. The
 *  consequence is that REWORDING a baselined comment re-reports it, which is correct — a reworded
 *  claim about the state of the world is a new claim, and it is exactly the moment to date it.
 */
const ROT_SHAPES = [
  ["not-before", /(?:^|[,;—-])\s*not before\b/i],
  ["today-everywhere", /\btoday,?\s+everywhere\b/i],
  ["for-now", /\bfor now\b/i],
  ["phase-n", /\bPhase\s*[23]\b/i],
  [
    "undated-call-site-count",
    /\b(?:all|the|its|only|both|these|those)\s+(?:two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\d+)\s+(?:call[- ]sites?|callers?|usages?|use[- ]sites?)\b/i,
  ],
];
const ROT_FUTURE_EVENT = /\bwhen\b[^.\n]{0,70}\b(?:lands?|ships?|goes live)\b/i;
const ROT_PENDING_WORK = /\b(?:route|endpoint|panel|port|feature|screen|surface|migration|column|flag|gate|answer|counterpart|that|it|one)\s+(?:lands?|ships?)\b/i;
const ROT_OBLIGATION = /\b(?:must|should|has to|have to|delete|remove|rewrite|revisit|re-?enable|update this|then this|becomes)\b/i;
const ISO_DATE = /\b20\d\d-\d\d-\d\d\b/;

/** The instances that were already here when this check landed. Shrink it; do not grow it. */
const ROT_BASELINE = new Set([
  "when-x-lands SESSION_HANDOVER.md 6ea56210", // when the ports land — SESSION_HANDOVER.md:482
  "not-before SESSION_HANDOVER.md 1c61ff15", // , not before — SESSION_HANDOVER.md:1108
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/MainActivity.kt 239c6854", // the three call sites — android/app/src/main/java/com/designprototype/workshop/MainActivity.kt:4207
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/MainActivity.kt 6cba3dce", // when it lands — android/app/src/main/java/com/designprototype/workshop/MainActivity.kt:14231
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/data/DwAiVerbs.kt e4600780", // When the route lands — android/app/src/main/java/com/designprototype/workshop/data/DwAiVerbs.kt:526
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/data/DwAsrModelInstall.kt c0423c23", // Its two call sites — android/app/src/main/java/com/designprototype/workshop/data/DwAsrModelInstall.kt:620
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/data/DwCustomSections.kt 465a57a0", // The two callers — android/app/src/main/java/com/designprototype/workshop/data/DwCustomSections.kt:379
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/data/DwDownload.kt 49f32f75", // THE TWO CALLERS — android/app/src/main/java/com/designprototype/workshop/data/DwDownload.kt:265
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/data/DwTier2Layer.kt 3a5c5af7", // When the route lands — android/app/src/main/java/com/designprototype/workshop/data/DwTier2Layer.kt:249
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/data/DwWorkshopCreation.kt 2135d31b", // the two callers — android/app/src/main/java/com/designprototype/workshop/data/DwWorkshopCreation.kt:104
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/data/StageSchema.kt 9b006348", // the two callers — android/app/src/main/java/com/designprototype/workshop/data/StageSchema.kt:2085
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/ui/AppNavigation.kt 19198349", // When one lands — android/app/src/main/java/com/designprototype/workshop/ui/AppNavigation.kt:396
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/ui/LocationFields.kt 18a6fb0c", // the three call sites — android/app/src/main/java/com/designprototype/workshop/ui/LocationFields.kt:793
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/ui/NumberedPointsField.kt 9fdf3210", // its two call sites — android/app/src/main/java/com/designprototype/workshop/ui/NumberedPointsField.kt:40
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwAsrModelInstallUi.kt 1a0907eb", // the two callers — android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwAsrModelInstallUi.kt:249
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwAsrModelInstallUi.kt f382a97f", // when it lands — android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwAsrModelInstallUi.kt:1080
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/WorkshopListScreen.kt 65e530ee", // when the create lands — android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/WorkshopListScreen.kt:1072
  "undated-call-site-count android/app/src/test/java/com/designprototype/workshop/report/ChartLabelSizeTest.kt 82f18988", // The two callers — android/app/src/test/java/com/designprototype/workshop/report/ChartLabelSizeTest.kt:130
  "undated-call-site-count backend/app/api/routes/design_workshops.py 39a94a67", // ITS TWO CALLERS — backend/app/api/routes/design_workshops.py:2902
  "not-before backend/app/api/routes/design_workshops.py 1e2d0f71", // , not before — backend/app/api/routes/design_workshops.py:3128
  "undated-call-site-count backend/app/api/routes/media.py f267bc6a", // THE TWO CALL SITES — backend/app/api/routes/media.py:100
  "not-before backend/app/api/routes/media.py 1560bf63", // , not before — backend/app/api/routes/media.py:105
  "undated-call-site-count backend/app/api/routes/questionnaire_forms.py 8c04658c", // the three callers — backend/app/api/routes/questionnaire_forms.py:291
  "not-before backend/app/schemas/design_workshops.py 55a6b50b", // , not before — backend/app/schemas/design_workshops.py:77
  "undated-call-site-count backend/app/services/ai_layers.py e31cb9ed", // THE TWO CALL SITES — backend/app/services/ai_layers.py:691
  "for-now backend/app/services/app_settings.py b7989001", // For now — backend/app/services/app_settings.py:3
  "undated-call-site-count backend/app/services/artisan_identity.py 63ae41be", // the two callers — backend/app/services/artisan_identity.py:165
  "undated-call-site-count backend/app/services/design_workshops.py 7ddb27dc", // all three callers — backend/app/services/design_workshops.py:2161
  "undated-call-site-count backend/app/services/design_workshops.py 761aefe4", // the five call sites — backend/app/services/design_workshops.py:3691
  "phase-n backend/app/services/stage_definitions.py 7304f9b7", // Phase 3 — backend/app/services/stage_definitions.py:24
  "for-now backend/app/services/stage_definitions.py d3c8d720", // for now — backend/app/services/stage_definitions.py:47
  "for-now backend/app/services/stage_definitions.py d4adf2c4", // for now — backend/app/services/stage_definitions.py:1620
  "phase-n backend/scripts/reconcile_interview_set_keys.py b15aa9c5", // Phase 2 — backend/scripts/reconcile_interview_set_keys.py:51
  "phase-n backend/scripts/reconcile_interview_set_keys.py 389a10f2", // Phase 3 — backend/scripts/reconcile_interview_set_keys.py:90
  "when-x-lands backend/tests/test_designer_roster.py b794c92b", // WHEN THAT LANDS — backend/tests/test_designer_roster.py:873
  "for-now backend/tests/test_stage_schema.py 9bced674", // for now — backend/tests/test_stage_schema.py:415
  "not-before docs/COMPUTED_FINDINGS.md 0254fd77", // , not before — docs/COMPUTED_FINDINGS.md:506
  "undated-call-site-count docs/SCALABILITY.md 29c4c9a2", // all 57 call sites — docs/SCALABILITY.md:637
  "when-x-lands frontend/components/BackButton.tsx 9888ff6a", // when the page knows where "back" should land — frontend/components/BackButton.tsx:10
  "for-now frontend/components/SignaturePad.tsx 099005d8", // for now — frontend/components/SignaturePad.tsx:112
  "for-now frontend/components/designworkshop/AiVerbReviewDialog.tsx 9aef7fa5", // for now — frontend/components/designworkshop/AiVerbReviewDialog.tsx:193
  "undated-call-site-count frontend/components/designworkshop/StageRecordEmbed.tsx 8ec67a5f", // its three callers — frontend/components/designworkshop/StageRecordEmbed.tsx:1041
  "undated-call-site-count frontend/components/dictation/onDeviceSpeech.ts 11511c84", // the two callers — frontend/components/dictation/onDeviceSpeech.ts:214
  "undated-call-site-count frontend/components/forms/MediaCaptureField.tsx 03d86ba2", // all twelve call sites — frontend/components/forms/MediaCaptureField.tsx:184
  "undated-call-site-count frontend/components/forms/inlineRecordHost.ts b4a0ea0c", // ITS THREE CALLERS — frontend/components/forms/inlineRecordHost.ts:274
  "when-x-lands frontend/components/settings/ProviderOrderPanel.tsx b2302a21", // When it lands — frontend/components/settings/ProviderOrderPanel.tsx:366
  "not-before frontend/e2e/guide-walkthrough-unit.spec.ts 326990ef", // , not before — frontend/e2e/guide-walkthrough-unit.spec.ts:51
  "undated-call-site-count frontend/e2e/inline-record-host-unit.spec.ts e0a75647", // the two call sites — frontend/e2e/inline-record-host-unit.spec.ts:270
  "undated-call-site-count frontend/e2e/inline-record-host-unit.spec.ts 3719c355", // ITS THREE CALLERS — frontend/e2e/inline-record-host-unit.spec.ts:541
  "when-x-lands frontend/e2e/inline-record-host-unit.spec.ts 5ec39b6e", // When it lands — frontend/e2e/inline-record-host-unit.spec.ts:543
  "undated-call-site-count frontend/e2e/process-refusal-a11y-unit.spec.ts 5fd64ec0", // these two call sites — frontend/e2e/process-refusal-a11y-unit.spec.ts:51
  "when-x-lands frontend/e2e/qr-decode-unit.spec.ts 56b5829d", // when it lands — frontend/e2e/qr-decode-unit.spec.ts:309
  "when-x-lands frontend/lib/aiLayers.ts 7169ea6f", // when the model volunteered a number, so it l — frontend/lib/aiLayers.ts:887
  "undated-call-site-count frontend/lib/aiVerbs.ts 19cfbc00", // all three call sites — frontend/lib/aiVerbs.ts:672
  "undated-call-site-count frontend/lib/aiVerbs.ts dca8ada2", // all three call sites — frontend/lib/aiVerbs.ts:716
  "undated-call-site-count frontend/lib/designWorkshopStore.ts 315664c5", // the two callers — frontend/lib/designWorkshopStore.ts:4180
  "not-before frontend/lib/media.ts 39b007b9", // , not before — frontend/lib/media.ts:705
]);

/** Spans of a line that sit inside quotation marks — quoted words are evidence, not a claim.
 *
 *  THE STRAIGHT SINGLE QUOTE IS WORD-BOUNDARY DELIMITED, and that is not a nicety. This repository's
 *  comment style is dense with possessives and contractions, and a bare `'…'` treats any two
 *  apostrophes on a line as one quotation: "Don't delete this when the panel lands, not before the
 *  port's done" opened a span at `n't` and closed it at `port'`, swallowing both shapes in the
 *  archetype this whole check is named for. The lookarounds cost a real quotation nothing —
 *  `'Phase 2 work'` still escapes, because the mark that opens one follows a space and precedes a
 *  letter, never the reverse. Pinned by four cases in ROT_SELFTEST. */
function quotedSpans(line) {
  const spans = [];
  for (const re of [/"[^"]{1,300}"/g, /“[^”]{1,300}”/g, /(?<![A-Za-z])'[^'\n]{3,300}'(?![A-Za-z])/g, /‘[^’]{1,300}’/g]) {
    for (const m of line.matchAll(re)) spans.push([m.index, m.index + m[0].length]);
  }
  return spans;
}

/** Which lines of a file are comment or docstring. Markdown is prose throughout, so all of it. */
function commentLinesOf(path, text) {
  const lines = text.split("\n");
  if (path.endsWith(".md")) return lines.map((l, i) => [i + 1, l]);
  const out = [];
  let inDoc = false;
  let docQ = null;
  let inBlock = false;
  lines.forEach((raw, i) => {
    const t = raw.trim();
    let isComment = false;
    if (/\.(ya?ml|sh|tf|tfvars|toml|ini|cfg|properties|env|example)$/i.test(path)) {
      isComment = t.startsWith("#");
    } else if (path.endsWith(".py")) {
      if (inDoc) {
        isComment = true;
        if (t.includes(docQ)) inDoc = false;
      } else {
        const m = t.match(/^[rbfu]*("""|''')/);
        if (m) {
          docQ = m[1];
          isComment = true;
          if (!t.slice(t.indexOf(docQ) + 3).includes(docQ)) inDoc = true;
        } else if (t.startsWith("#")) isComment = true;
      }
    } else {
      // C-family: Kotlin, TypeScript, JavaScript. A `*` continuation line counts, which is what
      // makes a KDoc or JSDoc block visible at all — the shapes above live in their prose.
      if (inBlock) {
        isComment = true;
        if (t.includes("*/")) inBlock = false;
      } else if (t.startsWith("//")) isComment = true;
      else if (t.startsWith("/*")) {
        isComment = true;
        if (!t.includes("*/")) inBlock = true;
      } else if (t.startsWith("*")) isComment = true;
    }
    if (isComment) out.push([i + 1, raw]);
  });
  return out;
}

/** FNV-1a, 32 bits. Only needs to be stable and short; nothing here is adversarial. */
function fnv1a(s) {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i += 1) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(16).padStart(8, "0");
}
const rotKey = (shape, path, line) => `${shape} ${path} ${fnv1a(line.trim().toLowerCase().replace(/\s+/g, " "))}`;

function scanRottableClaims() {
  const hits = [];
  const COMMENTED = /\.(md|py|ts|tsx|js|jsx|mjs|cjs|kt|kts|ya?ml|sh|tf|tfvars|toml|ini|cfg|properties|env|example)$/i;
  for (const rel of trackedTextFiles()) {
    if (!COMMENTED.test(rel) || ISO_DATE.test(rel)) continue;
    let text;
    try { text = read(join(REPO, rel)); } catch { continue; }
    const all = text.split("\n");
    for (const [ln, line] of commentLinesOf(rel, text)) {
      if (ISO_DATE.test(all.slice(Math.max(0, ln - 4), ln + 3).join("\n"))) continue;
      const spans = quotedSpans(line);
      const outside = (m) => m && !spans.some(([a, b]) => m.index >= a && m.index < b);
      const near = all.slice(Math.max(0, ln - 3), ln + 2).join("\n");
      const found = [];
      const fe = line.match(ROT_FUTURE_EVENT);
      if (outside(fe) && (ROT_PENDING_WORK.test(line) || ROT_OBLIGATION.test(near))) found.push(["when-x-lands", fe[0]]);
      for (const [name, re] of ROT_SHAPES) {
        const m = line.match(re);
        if (outside(m)) found.push([name, m[0].trim()]);
      }
      for (const [shape, phrase] of found) hits.push({ shape, rel, ln, phrase, key: rotKey(shape, rel, line) });
    }
  }
  return hits;
}

function checkRottableClaims() {
  const hits = scanRottableClaims();
  if (ROT_BASELINE_WRITE) {
    console.log("const ROT_BASELINE = new Set([");
    for (const h of hits) console.log(`  ${JSON.stringify(h.key)}, // ${h.phrase.slice(0, 44)} — ${h.rel}:${h.ln}`);
    console.log("]);");
    return;
  }
  const fresh = hits.filter((h) => !ROT_BASELINE.has(h.key));
  for (const h of fresh) {
    rot(
      `${h.rel}:${h.ln} — "${h.phrase}". A claim about the state of the world with no date beside it: nobody can re-check it, ` +
      "so it survives the thing it describes. Prefer the dated form this repository already recommends — " +
      "\"true as of «2026-08-22»; check `«grep»`\".",
    );
  }
  const stale = [...ROT_BASELINE].filter((k) => !hits.some((h) => h.key === k));
  note(
    `${hits.length} rottable comment shape(s) in tracked source and docs — ${hits.length - fresh.length} baselined, ` +
    `${fresh.length} new (warning only); ${stale.length} baseline entr${stale.length === 1 ? "y" : "ies"} no longer match, ` +
    "so the fix is landing. Regenerate the list with `--rot-baseline`.",
  );
}

/** Cases that hold §11's detector to its word, and — more importantly — to its ABSTENTIONS.
 *
 *  A rot detector earns its keep by what it declines to report. The first three drafts of this one
 *  reported 261, then 1,020, then 94 findings over the same tree, almost all of them prose about
 *  runtime — "when the upload lands", "the two callers dispose of their handle" — and a list that
 *  long is a list nobody reads. The escapes are what brought it to 57, so each of them is pinned
 *  here: break one and the run goes red at this line instead of quietly re-flooding the output.
 *
 *  The FIRES cases are written from the instance this repository named about itself
 *  (COMPUTED_FINDINGS.md §7), so the detector cannot silently stop catching the thing it was built
 *  for; the ABSTAINS cases are the real sentences that made the earlier drafts unusable. */
const ROT_SELFTEST = [
  // ── fires ──
  ["FIRES", "x.py", '# delete this paragraph when the ports and a panel land, not before.'],
  ["FIRES", "x.kt", "// When the route lands, this map is the contract it has to satisfy."],
  ["FIRES", "x.ts", "// Retained so the three call sites need no edit."],
  ["FIRES", "x.md", "There is no designer-facing surface today, everywhere it could sit is unbuilt."],
  ["FIRES", "x.py", "#: Phase 2 work — the Advanced tier is not implemented."],
  ["FIRES", "x.ts", "// Good enough for now; the real gate goes in beside the reducer."],
  // ── abstains, and each of these is why the check is usable ──
  ["ABSTAINS", "x.kt", "// A hold-up that resolves itself when an upload lands, which is normal."],
  // Pinned as FIRES because it DOES, and it should not: this is `ProviderOrderPanel.tsx`'s real
  // comment about keyboard focus, caught by "when it lands" + "goes" in the obligation window. It
  // is the detector's precision limit written down rather than hidden — the baseline absorbs it,
  // and a future tightening that abstains here should delete this case rather than be surprised.
  ["FIRES", "x.ts", "// Follow the row. When it lands on either end its arrow goes disabled."],
  ["ABSTAINS", "x.py", '# The margin note read "Phase 2 work", which is quoted, not claimed.'],
  // The same escape through STRAIGHT SINGLE QUOTES, which is the form that had to be narrowed:
  // a real quotation still silences the shape …
  ["ABSTAINS", "x.py", "# The margin note read 'Phase 2 work', which is quoted, not claimed."],
  // … while two possessives no longer do. Both of these reported nothing before 2026-08-22, and the
  // first is the archetype from COMPUTED_FINDINGS.md §7 with an apostrophe added to each half.
  ["FIRES", "x.ts", "// Don't delete this when the panel lands, not before the port's done."],
  ["FIRES", "x.ts", "// The portal's default is good enough for now, per the reader's note."],
  // A miss that survives the narrowing, pinned so it is written down rather than assumed absent:
  // the call-site shape wants its determiner NEXT TO the numeral, and "the store's three callers"
  // puts a possessive between them. A future widening that fires here should delete this case.
  ["ABSTAINS", "x.ts", "// The store's three callers each dispose of it, and that's deliberate."],
  ["ABSTAINS", "x.ts", "const threeCallers = 1; // not a comment line, so not scanned at all"],
  ["ABSTAINS", "x.md", "All three callers agree — true as of 2026-08-22; check `grep -rn hydrate`."],
];

function selfTestRot() {
  let fired = 0;
  for (const [want, name, line] of ROT_SELFTEST) {
    // The date escape reads three lines either side, so a case is fed as its own little file.
    const text = `${line}\n`;
    const hits = [];
    const all = text.split("\n");
    for (const [ln, l] of commentLinesOf(name, text)) {
      if (ISO_DATE.test(all.slice(Math.max(0, ln - 4), ln + 3).join("\n"))) continue;
      const spans = quotedSpans(l);
      const outside = (m) => m && !spans.some(([a, b]) => m.index >= a && m.index < b);
      const near = all.slice(Math.max(0, ln - 3), ln + 2).join("\n");
      const fe = l.match(ROT_FUTURE_EVENT);
      if (outside(fe) && (ROT_PENDING_WORK.test(l) || ROT_OBLIGATION.test(near))) hits.push("when-x-lands");
      for (const [shape, re] of ROT_SHAPES) if (outside(l.match(re))) hits.push(shape);
    }
    const got = hits.length ? "FIRES" : "ABSTAINS";
    if (got !== want) {
      fail(
        `check-docs: §11's rot detector ${got} on a case pinned as ${want} — "${line.trim().slice(0, 70)}". ` +
        (want === "ABSTAINS"
          ? "An escape has been lost, and the next run will bury its real findings in prose about runtime."
          : "The shape this check exists for is no longer detected."),
      );
    } else fired += 1;
  }
  note(`${fired} self-test cases for the rottable-comment shapes (${ROT_SELFTEST.filter((c) => c[0] === "ABSTAINS").length} of them abstentions)`);
}

/** Cases for §10's two judgements: which sibling values are swept, and where a label may sit. */
function selfTestSiblingSweep() {
  const cases = [
    // Swept: the part after the label prefix is the part that does the damage.
    [false, "fieldrepo-media-626159998512"],
    [false, "field-repository.vercel.app"],
    [false, "fieldrepo-deploy"],
    [false, "15.207.145.174"],
    [false, "prj_EzXN8hhGKpMciFBrZRdxpcgUUzN0"],
    // Not swept: the literal IS the words every correct mention of it uses.
    [true, "field-repository"],
    [true, "fieldrepo"],
  ];
  for (const [want, v] of cases) {
    if (isOwnLabel(v) !== want) {
      fail(
        `check-docs: §10 would ${want ? "sweep" : "skip"} \`${v}\`, and it must ${want ? "skip" : "sweep"} it. ` +
        (want
          ? "A value that is also its own label reports every correct mention of the other product."
          : "Dropping this value from the sweep is dropping the fact it identifies."),
      );
    }
  }
  // A table labels its rows from a header that can sit well above them, so the window widens to
  // the whole block. This is the case that made docs/CI.md §0's own register report itself.
  const table = ["prose", "| | This portal | The field repository |", "|---|---|---|", "| a | x | y |", "| b | p | q |", "| c | m | n |"];
  if (!SIBLING_LABEL.test(labelWindow(table, 6))) {
    fail("check-docs: §10's labelWindow no longer reaches a markdown table's header row — every register row will report itself");
  }
  if (SIBLING_LABEL.test(labelWindow(["a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "the field repository"], 1))) {
    fail("check-docs: §10's labelWindow reaches eleven lines away in prose — a label that distant is not a label the reader will see");
  }

  // THE DEFECT THIS PINS SHIPPED ONCE. A table where one row names a CloudFront host and another
  // carries an inherited bucket, with nothing anywhere saying whose the bucket is: for a fortnight
  // that counted as labelled, because the disputed hostname was a second escape and a table is one
  // window. Re-introducing `fieldrepo-media-626159998512` into DEPLOYMENT_VERCEL.md then produced
  // zero findings. Only SIBLING_LABEL labels.
  const swept = [{ v: "fieldrepo-media-626159998512", label: "S3 media bucket" }, { v: "15.207.145.174", label: "API box (Elastic IP)" }];
  const cfTable = [
    "| Piece | Where it runs | Notes |",
    "|---|---|---|",
    "| HTTPS edge | CloudFront `https://d2b34i3e92al6i.cloudfront.net` | the value the browser talks to |",
    "| Media | S3 `fieldrepo-media-626159998512` | uploads go straight there |",
  ];
  if (windowSaysWhose(cfTable, 4, swept)) {
    fail(
      "check-docs: §10 counts an inherited value as labelled because a CloudFront host sits in the same table. " +
      "That is the escape deleted on 2026-08-22 — it excused the bucket, the alias, the deploy key and the project id, " +
      "not the one Elastic IP it was argued for.",
    );
  }
  // …and the header that DOES label it still does.
  if (!windowSaysWhose([...cfTable.slice(0, 1), "| | This portal | The field repository |", ...cfTable.slice(1)], 5, swept)) {
    fail("check-docs: §10 no longer reads a 'field repository' table header as a label — every register row will report itself");
  }

  // The Vercel id SHAPE rule that replaced `checkVercelIds`'s deleted half. `team_workshop` is a
  // fixture name in backend/tests/test_media_entitlement.py and is why the old rule could only run
  // over three hand-listed files; the shape has to tell it from a real id without a list.
  const idCases = [
    [true, "prj_QQQQQQQQ9QQQQQQQQQQQQQ"],
    [true, "team_ZZZZZZZZ7ZZZZZZZZ"],
    [false, "team_workshop"],
    [false, "prj_short1A"],
    [false, "team_alllowercasenodigits"],
  ];
  for (const [want, id] of idCases) {
    VERCEL_ID_SHAPE.lastIndex = 0;
    if (VERCEL_ID_SHAPE.test(id) !== want) {
      fail(
        `check-docs: §10's Vercel id shape ${want ? "no longer matches" : "now matches"} \`${id}\`. ` +
        (want
          ? "An unregistered deploy target written anywhere in the tree will go unreported."
          : "A name that merely starts with the prefix is not an id, and reporting it is how this rule gets deleted."),
      );
    }
  }
  VERCEL_ID_SHAPE.lastIndex = 0;

  note(`${cases.length + idCases.length + 4} self-test cases for the field-repository sweep`);
}

/* ── run ────────────────────────────────────────────────────────────────────────────────────── */

selfTestDrift();
selfTestFactsDrift();
selfTestExemptions();
selfTestRot();
selfTestSiblingSweep();
selfTestDatabaseProvider();
checkFacts();
checkRoleParity();
checkRouteGuardTable();
checkPaths();
checkCitations();
checkMaintenanceSections();
checkMermaid();
checkCrossLinks();
checkAndroidApiHost();
checkVercelIds();
checkDatabaseProvider();
checkIndexListsEveryDoc();
checkSiblingIdentity();
checkSecondRegister();
checkRottableClaims();

if (!QUIET) for (const n of notes) console.log(`  ok    ${n}`);
// The allowlisted identity mentions print on every run, above the warnings and below the notes.
// A list of known problems that nobody sees is a suppression list; this one is meant to be read
// down to nothing. See SIBLING_ALLOWLIST.
for (const k of knowns) console.log(`  known ${k}`);
for (const r of rots) console.log(`  rot   ${r}`);
for (const w of warnings) console.log(`  warn  ${w}   [owned by another workstream]`);
for (const f of failures) console.error(`FAIL    ${f}`);
console.log(
  failures.length
    ? `\n${failures.length} problem(s)${warnings.length ? `, ${warnings.length} warning(s) elsewhere` : ""}.`
    : `\ndocs check passed${warnings.length ? ` (${warnings.length} warning(s) in documents owned elsewhere)` : ""}.`,
);
process.exit(failures.length ? 1 : 0);
