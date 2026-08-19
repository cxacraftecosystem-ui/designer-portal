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

selfTestDrift();
selfTestFactsDrift();
selfTestExemptions();
checkFacts();
checkRoleParity();
checkRouteGuardTable();
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
