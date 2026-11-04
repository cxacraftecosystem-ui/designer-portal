#!/usr/bin/env node

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
const rots = [];
const knowns = [];
const ownerOf = (msg) => {
  const m = msg.match(/(?:^|\/)([A-Z_]+\.md)/);
  return m && OWNED_ELSEWHERE.has(m[1]) ? "elsewhere" : "here";
};
const fail = (m) => (ownerOf(m) === "here" ? failures : warnings).push(m);
const rot = (m) => rots.push(m);
const known = (m) => knowns.push(m);
const note = (m) => notes.push(m);
const READ_CACHE = new Map();
const read = (p) => {
  if (!READ_CACHE.has(p)) READ_CACHE.set(p, readFileSync(p, "utf8").replace(/\r\n/g, "\n"));
  return READ_CACHE.get(p);
};

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
  const androidUnit = countDeep(join(REPO, "android", "app", "src", "test"), /^\s*@Test\b/gm);
  const androidInstr = countDeep(join(REPO, "android", "app", "src", "androidTest"), /^\s*@Test\b/gm);
  return { backend, e2e, androidUnit, androidInstr };
}

function sttFacts() {
  const src = read(join(REPO, "backend", "app", "services", "app_settings.py"));
  const m = src.match(/DEFAULT_STT_PROVIDER_ORDER = \(([^)]*)\)/);
  return m ? [...m[1].matchAll(/"(\w+)"/g)].map((x) => x[1]) : [];
}

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

  const ciGateSentence = `**The backend suite gates the deploy, the web suite half-gates it, and
neither gates a merge.** \`Backend tests\` — the job that runs the whole pytest suite — and \`Web
typecheck, lint and unit specs\`, which runs \`npm run test:unit\`, are two of the three names in the
\`GATING_JOBS\` list that \`deploy-backend.yml\` and \`deploy-frontend.yml\` each poll for at the SHA
being shipped, and neither workflow hands over to its \`deploy\` job until all three have concluded
green. The web one is only **half** a gate because \`test:unit\` is every \`*-unit.spec.ts\` bar two
excluded by name for wanting a dev server: the specs that drive a real screen are gated by nothing.
Merging is a separate question with a separate answer — required status checks live in branch
protection, which no file in a checkout can prove — so a red Checks still merges to \`main\`. See
[CI.md](CI.md) §1.1 and §5, and [QA_AUDIT.md](QA_AUDIT.md).`;

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
| Backend unit (\`backend/tests/\`) | ${tests.backend.files} | ${tests.backend.cases} \`def test_\` | \`python -m pytest -rf --durations=15\` from \`backend/\` |
| Web end-to-end (\`frontend/e2e/\`) | ${tests.e2e.files} | ${tests.e2e.cases} \`test(\` | Playwright, \`frontend/playwright.config.ts\` |
| Android unit (\`android/app/src/test/\`) | ${tests.androidUnit.files || "**none**"} | ${tests.androidUnit.cases} \`@Test\` | \`./gradlew :app:testDebugUnitTest\` from \`android/\` |
| Android instrumented (\`android/app/src/androidTest/\`) | ${tests.androidInstr.files || "**none**"} | ${tests.androidInstr.cases} \`@Test\` | needs a device; on demand only (\`.github/workflows/android-emulator.yml\`), never a gate |

The backend case count is \`def test_\` occurrences; pytest reports a larger number because
parametrised cases expand.
${ciGateSentence}

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

function docFiles() {
  return readdirSync(DOCS)
    .filter((f) => f.endsWith(".md"))
    .map((f) => join(DOCS, f))
    .concat([join(REPO, "README.md")].filter(existsSync));
}

function checkFacts() {
  const rendered = renderFacts();
  if (WRITE) {
    writeFileSync(FACTS_FILE, rendered);
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
    fail(
      "docs/REPO_FACTS.md is out of date — run `node docs/tools/check-docs.mjs --write`" +
        " ON A CLEAN TREE (line counts are read from disk, not from the index)\n" +
        factsDrift(current.split("\n"), rendered.trim().split("\n")).join("\n"),
    );
  }
}

function fileCountsDisagree(line) {
  if (typeof line !== "string") return false;
  const cells = line.split("|").map((c) => c.trim());
  if (cells.length !== 7) return false;
  const n = (c) => (/^[\d,]+$/.test(c) ? Number(c.replace(/,/g, "")) : null);
  const trackedFiles = n(cells[2]);
  const treeFiles = n(cells[4]);
  return trackedFiles !== null && treeFiles !== null && trackedFiles !== treeFiles;
}

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

function checkRouteGuardTable() {
  const src = read(join(REPO, "frontend", "lib", "permissions.ts"));
  const block = src.match(/export const ROUTE_GUARDS: RouteGuard\[\] = \[([\s\S]*?)\n\];/);
  if (!block) return fail("frontend/lib/permissions.ts: ROUTE_GUARDS not found");
  const declared = [...block[1].matchAll(/path:\s*"([^"]+)"/g)].map((m) => m[1]);
  if (!declared.length) return fail("frontend/lib/permissions.ts: ROUTE_GUARDS declares no paths");

  const doc = read(join(DOCS, "PERMISSIONS.md"));
  const section = doc.slice(doc.indexOf("## 5. Route guards on the web client"), doc.indexOf("## 6."));
  if (!section) return fail("docs/PERMISSIONS.md: section 5 not found");
  const rows = section
    .split("\n")
    .filter((line) => line.startsWith("| `/"))
    .join("\n");
  const tabulated = new Set([...rows.matchAll(/`(\/[\w/:-]+)`/g)].map((m) => m[1]));

  for (const path of declared) {
    if (!tabulated.has(path)) {
      fail(`docs/PERMISSIONS.md §5: ROUTE_GUARDS declares ${path} and the table does not list it`);
    }
  }
  for (const path of tabulated) {
    if (path === "/questionnaire") continue;
    if (!declared.includes(path)) {
      fail(`docs/PERMISSIONS.md §5: the table lists ${path}, which has no ROUTE_GUARDS rule`);
    }
  }
  note(`${declared.length} client route guards listed in PERMISSIONS.md §5`);
}

const PATH_SEGMENT = String.raw`(?:\([\w.-]+\)|\[[\w.-]+\]|[\w.-]+)`;
const PATH_RE = new RegExp(
  String.raw`(?<![\w/.\-\])])((?:backend|frontend|android|infra|docs|scripts|\.github)(?:\/${PATH_SEGMENT})+\/?)`,
  "g",
);

const PATH_ROOTS = [REPO, join(REPO, "backend"), join(REPO, "frontend"), join(REPO, "android")];

const NOT_A_PATH = new Map([
  ["frontend/frontend", "CI.md names it as the wrong path a misconfiguration produces"],
]);

const CREATED_BY_THE_DEVELOPER = [
  ["backend/.env", "gitignored — ENVIRONMENT.md tells the reader to create it from .env.example"],
  ["backend/.venv", "gitignored — the virtualenv a developer builds, named in setup instructions"],
  ["frontend/.env.local", "gitignored — DEPLOYMENT_VERCEL.md and ENVIRONMENT.md tell the reader to create it"],
  ["frontend/node_modules", "gitignored — produced by npm install"],
  ["frontend/.next", "gitignored — Next's build and dev output, including the dev server log TESTING-E2E-LOCAL.md quotes"],
  ["android/local.properties", "gitignored — written by Android Studio on first open"],
  ["android/app/libs/", "gitignored — the sherpa-onnx AAR the Android workflows and codeql.yml fetch at build time"],
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
      if (p.includes("*") || p.includes("…")) continue;
      if (NOT_A_PATH.has(p)) continue;
      checked += 1;
      if (!resolveRepoPath(p) && !expectedAbsent(p)) fail(`${rel}: path does not exist — ${p}`);
    }
  }
  note(`${checked} repository paths resolved`);
}

const CITE_RE = new RegExp(
  String.raw`(${PATH_SEGMENT}(?:\/${PATH_SEGMENT})*\.(?:py|ts|tsx|kt|kts|mjs|sh|yaml|yml|prisma)):(\d+)(?:-(\d+))?`,
  "g",
);

const AMBIGUOUS_BASENAMES = new Set([
  "page.tsx", "layout.tsx", "route.ts", "loading.tsx", "error.tsx", "not-found.tsx",
  "__init__.py", "index.ts", "index.tsx",
]);

const CITATION_DRIFT_WINDOW = 10;

const WEAK_ANCHORS = new Set([
  "data", "true", "false", "null", "none", "type", "name", "list", "dict", "self",
  "value", "text", "file", "line", "keys", "item", "items", "return", "async", "await",
]);
const ANCHOR_RE = /^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*(?:\(\))?$/;
const ANCHOR_IS_A_FILENAME = /\.(?:py|ts|tsx|kt|kts|mjs|js|sh|md|json|yaml|yml|prisma)$/;

function citationAnchors(line) {
  const out = new Set();
  const usable = (t) => t.length >= 4 && !WEAK_ANCHORS.has(t.toLowerCase());
  for (const m of line.matchAll(/`([^`\n]+)`/g)) {
    const raw = m[1].replace(/\(\)$/, "");
    if (!ANCHOR_RE.test(raw)) continue;
    if (ANCHOR_IS_A_FILENAME.test(raw)) continue;
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

const DRIFT_EXEMPT = new Map([
  ["AUDIT-2026-08-15.md", "a dated snapshot of the pre-fix tree; its line numbers are the record"],
  ["UX_AUDIT_2026-10-16.md", "a dated snapshot of the tree at c13bf5be; its line numbers are the record and each finding carries its own status"],
]);

const ROTTED_SPECIMEN = /<!--\s*rotted\b/;

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

function selfTestDrift() {
  const cases = [];
  const ok = (what, cond) => { cases.push(what); if (!cond) fail(`check-docs self-test: ${what}`); };

  const long = Array.from({ length: 400 }, (_, i) =>
    i + 1 === 335 ? "REFERENCE_MODELS: dict[str, ReferenceModel] = {" : "    # filler");
  const rotted = citationDrift(long, 277, 277, ["REFERENCE_MODELS"]);
  ok("a citation 58 lines above its symbol is inside the file (what the old check tested)", 277 <= long.length);
  ok("...and is reported as drift (what the old check missed)", rotted && !rotted.near && rotted.at === 335);

  ok("a citation ON the symbol passes", citationDrift(long, 335, 335, ["REFERENCE_MODELS"]).near);
  ok("a citation 10 lines above the symbol still passes", citationDrift(long, 325, 325, ["REFERENCE_MODELS"]).near);
  ok("a citation 11 lines above the symbol does not", !citationDrift(long, 324, 324, ["REFERENCE_MODELS"]).near);

  ok("a symbol absent from the file is not evidence", citationDrift(long, 12, 12, ["SomethingElse"]) === null);
  ok("one near anchor rescues a far one", citationDrift(long, 330, 330, ["Absent", "REFERENCE_MODELS"]).near);

  ok("a bare identifier is an anchor", citationAnchors("`REFERENCE_MODELS` (`x.py:1`)").includes("REFERENCE_MODELS"));
  ok("`StageEntryIn.data` yields the type, since `.data` locates nothing",
    citationAnchors("`StageEntryIn.data` (`x.py:1`)").includes("StageEntryIn"));
  ok("`prototype.productRef` yields the field", citationAnchors("`prototype.productRef`").includes("productRef"));
  ok("a filename is not a symbol", citationAnchors("`identity_ocr.py`").length === 0);
  ok("a citation is not a symbol", citationAnchors("`report_builder.py:105`").length === 0);
  ok("an expression is not a symbol", citationAnchors("`Field(default=True)`").length === 0);
  ok("a word too common to locate anything is not an anchor", citationAnchors("`data` `name` `self`").length === 0);
  ok("a line naming nothing abstains", citationAnchors("**Where.** `frontend/lib/store.ts:974`").length === 0);

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
      let full = PATH_ROOTS.map((r) => join(r, file)).find(existsSync) || null;
      if (!full) {
        const base = file.split("/").pop();
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
      if (!full || !statSync(full).isFile()) continue;
      checked += 1;
      const src = read(full).split("\n");
      const lines = src.length;
      const cite = `${file}:${startS}${endS ? "-" + endS : ""}`;
      if (end > lines) fail(`${rel}: citation ${cite} is past end of file (${lines} lines)`);
      seen.set(rel, (seen.get(rel) || 0) + 1);

      if (end > lines || DRIFT_EXEMPT.has(rel.split("/").pop())) continue;
      const lineStart = text.lastIndexOf("\n", m.index) + 1;
      const lineEnd = text.indexOf("\n", m.index);
      const docLineNo = text.slice(0, lineStart).split("\n").length;
      const docLine = text.slice(lineStart, lineEnd === -1 ? undefined : lineEnd);
      if (ROTTED_SPECIMEN.test(docLine)) { specimens += 1; continue; }
      const anchors = citationAnchors(docLine);
      if (!anchors.length) continue;
      const verdict = citationDrift(src, Number(startS), end, anchors);
      if (verdict === null) continue;
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

const MAINTENANCE_EXEMPT = new Set(["REPO_FACTS.md"]);

function selfTestExemptions() {
  const present = new Set(docFiles().map((d) => d.split(/[\\/]/).pop()));
  const named = [...OWNED_ELSEWHERE, ...DRIFT_EXEMPT.keys(), ...MAINTENANCE_EXEMPT];
  const missing = named.filter((base) => !present.has(base));
  for (const base of missing) {
    fail(`check-docs: an exemption list names ${base}, which is not a document in docs/ — the fence is protecting nothing`);
  }
  if (MAINTENANCE_EXEMPT.size !== 1 || !MAINTENANCE_EXEMPT.has("REPO_FACTS.md")) {
    fail(
      "check-docs: MAINTENANCE_EXEMPT is for generated files only. A hand-written document that lacks " +
      "a maintenance section has a gap worth reporting — exempting it hides the gap from its owner.",
    );
  }
  if (!read(FACTS_FILE).includes("GENERATED FILE")) {
    fail("check-docs: REPO_FACTS.md is exempt from the maintenance-section rule for being generated, and it no longer says it is");
  }
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

const VERCEL_ID_FILES = [
  ["docs/CI.md", join(DOCS, "CI.md")],
  ["docs/ENVIRONMENT.md", join(DOCS, "ENVIRONMENT.md")],
  [".github/workflows/deploy-frontend.yml", join(REPO, ".github", "workflows", "deploy-frontend.yml")],
];

let CANONICAL_VERCEL = null;
function canonicalVercelIds() {
  if (CANONICAL_VERCEL) return CANONICAL_VERCEL;
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


const KNOWN_PROVIDERS = [
  ["Neon", "neon.tech", true],
  ["Supabase", "supabase.co", true],
  ["Amazon RDS", "rds.amazonaws.com", true],
  ["Cloud SQL", "cloudsql", false],
  ["Azure", "postgres.database.azure.com", false],
  ["Render", "render.com", false],
  ["Railway", "railway.app", false],
  ["DigitalOcean", "ondigitalocean.com", true],
  ["Timescale", "tsdb.cloud.timescale.com", false],
];

const PROVIDER_WORKAROUND_FILES = new Set([
  ".github/workflows/keep-supabase-active.yml",
  "scripts/keep-supabase-active.mjs",
]);

const PROVIDER_HOMONYM_PREFIXES = [
  "android/core-imaging/",
  "android/core-vector/",
  "android/core-pipeline/",
  "android/core-export/",
];

const isProviderHomonymPath = (rel) => PROVIDER_HOMONYM_PREFIXES.some((p) => rel.startsWith(p));

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

function flattenProse(text) {
  return text
    .split("\n")
    .map((line) => line.replace(/^\s*(?:>\s*)*(?:#+|\/\/+|\*|-|\d+\.)?\s*/, ""))
    .join(" ")
    .replace(/\s+/g, " ");
}

function providerLabelWindow(lines, ln) {
  const isRow = (s) => /^\s*>?\s*\|/.test(s ?? "");
  if (isRow(lines[ln - 1])) return lines[ln - 1];
  return lines.slice(Math.max(0, ln - 2), ln + 1).join("\n");
}

const PROVIDER_CLAIM = /\*\*Production runs on ([A-Z][A-Za-z0-9 ._-]{1,30}?)\.\*\*/;

const PROVIDER_IN_A_FILENAME = /[\w./-]*(?:keep-supabase-active|\.env\.supabase|supabase\/\.temp)[\w./-]*/gi;

function unlabelledProviderMentions(lines, re) {
  const hits = [];
  for (let i = 0; i < lines.length; i += 1) {
    if (!re.test(lines[i])) continue;
    if (!re.test(lines[i].replace(PROVIDER_IN_A_FILENAME, " "))) continue;
    if (HISTORY_LABEL.test(flattenProse(providerLabelWindow(lines, i + 1)))) continue;
    hits.push(i + 1);
  }
  return hits;
}

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

  const stale = KNOWN_PROVIDERS.filter(([name, , sweepable]) => sweepable && name !== claimed);
  const seen = new Map();
  for (const rel of trackedTextFiles()) {
    if (rel === "docs/tools/check-docs.mjs") continue;
    if (PROVIDER_WORKAROUND_FILES.has(rel)) continue;
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

const PROVIDER_ALLOWLIST = new Map([
]);

const CURRENT_PROVIDER_ALLOWLIST = new Map([
  [".env.example Supabase", 1],
]);

function selfTestDatabaseProvider() {
  let n = 0;

  n += 1;
  const m = "text\n**Production runs on Neon.** *Recorded 2026-08-22.*\nmore".match(PROVIDER_CLAIM);
  if (!m || m[1] !== "Neon") {
    fail(
      "check-docs: §8c's PROVIDER_CLAIM no longer reads the provider out of its own sentence " +
      `(got ${m ? `"${m[1]}"` : "no match"}). The check would report a missing sentence on a document that has one.`,
    );
  }
  n += 1;
  if (PROVIDER_CLAIM.test("the sentence that says **Production runs on** something")) {
    fail("check-docs: §8c's PROVIDER_CLAIM matches a sentence with no provider name in it");
  }

  const labelCases = [
    [true, "**Historical, 2026-08-22.** Left from the Supabase deployment."],
    [true, "Supabase, which hosted this deployment until 2026-08-22."],
    [true, "It is dormant as of 2026-08-22."],
    [true, "production left that provider on 2026-08-22"],
    [true, "Measured on Supabase, before the move."],
    [true, "Supabase is no longer what this points at."],
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

  n += 1;
  const hosts = new Map(KNOWN_PROVIDERS.map(([name, host]) => [name, host]));
  if (!hosts.get("Neon") || !hosts.get("Supabase") || hosts.get("Neon") === hosts.get("Supabase")) {
    fail("check-docs: §8c's KNOWN_PROVIDERS no longer tells Neon from Supabase by host substring");
  }
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
  n += 1;
  if (![...sweepable.values()].some(Boolean)) {
    fail("check-docs: §8c has no sweepable provider left — the tree sweep would silently check nothing");
  }

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
  n += 1;
  if (HISTORY_LABEL.test(flattenProse("Use the Supabase session pooler URL.\nIt is the one that works."))) {
    fail("check-docs: §8c's flattenProse now manufactures a history label out of two unlabelled lines");
  }
  n += 1;
  if (flattenProse("a line\n# 2026-08-22 and a # inside it") !== "a line 2026-08-22 and a # inside it") {
    fail(
      "check-docs: §8c's flattenProse no longer strips only LEADING continuation markers " +
      `(got "${flattenProse("a line\n# 2026-08-22 and a # inside it")}")`,
    );
  }

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

function checkIndexListsEveryDoc() {
  const index = read(join(DOCS, "README.md"));
  const missing = readdirSync(DOCS)
    .filter((f) => f.endsWith(".md") && f !== "README.md")
    .filter((f) => !index.includes(f));
  const total = readdirSync(DOCS).filter((f) => f.endsWith(".md")).length - 1;
  for (const base of missing) {
    fail(`docs/README.md does not list ${base} — an unlisted document is invisible to every reader who does not already know it exists`);
  }
  note(
    missing.length
      ? `${total} documents in docs/, ${missing.length} of them unlisted in docs/README.md (see FAIL below)`
      : `${total} documents, each named in docs/README.md`,
  );
}

function blankCode(text) {
  return text
    .replace(/```[\s\S]*?```/g, (b) => b.replace(/[^\n]/g, " "))
    .replace(/~~~[\s\S]*?~~~/g, (b) => b.replace(/[^\n]/g, " "))
    .replace(/`[^`\n]*`/g, (b) => " ".repeat(b.length));
}

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

let TRACKED = null;
function trackedTextFiles() {
  if (TRACKED) return TRACKED;
  const KEEP = /\.(md|py|ts|tsx|js|jsx|mjs|cjs|kt|kts|yml|yaml|xml|sh|tf|tfvars|sql|toml|ini|cfg|gradle|properties|example|env|txt|json)$/i;
  const KEEP_BASENAMES = new Set([".dockerignore", ".gitignore", ".gitattributes", "Dockerfile"]);
  const keep = (p) => KEEP.test(p) || KEEP_BASENAMES.has(p.split("/").pop());
  const DROP = /(^|\/)(node_modules|\.claude|test-results)\//;
  let out = [];
  try {
    out = execSync("git ls-files -z", { cwd: REPO, encoding: "utf8", maxBuffer: 256e6 })
      .split("\0")
      .filter(Boolean)
      .filter((p) => keep(p) && !DROP.test(p) && !/(package|skills)-lock\.json$/.test(p))
      .filter((p) => p !== "docs/tools/check-docs.mjs")
      .filter((p) => existsSync(join(REPO, p)));
  } catch {
    out = [];
  }
  TRACKED = out;
  return out;
}

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
const SIBLING_LABEL = /field[- ]repository|field repo\b|fieldrepo|the other product|another product|never put these here|never appear here|must NOT be pasted/i;
const isOwnLabel = (v) => { const m = v.match(SIBLING_LABEL); return !!m && m[0].length === v.length; };

const EXPECTED_SIBLING_VALUES = 7;

const ROWS_WITHOUT_A_SIBLING_VALUE = new Map([
  ["CloudFront", /unresolved/i],
  ["GitHub repository", /not established/i],
]);

const A_SLUG = /^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/;
const cellLiterals = (cell) =>
  [...cell.matchAll(/`([^`]+)`/g)]
    .map((x) => x[1])
    .filter((v) => v.length >= 8 && (!v.includes("/") || A_SLUG.test(v)));

const VERCEL_ID_SHAPE = /\b(?:prj|team)_(?=[A-Za-z0-9]{16,})(?=[A-Za-z0-9]*[A-Z])(?=[A-Za-z0-9]*\d)[A-Za-z0-9]+/g;

const SIBLING_ALLOWLIST = new Map([
  ["docs/archive/SESSION_HANDOVER.md fieldrepo-deploy", 1],
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

function windowSaysWhose(lines, ln, siblings) {
  let win = labelWindow(lines, ln);
  for (const s of siblings) win = win.split(s.v).join(" ");
  return SIBLING_LABEL.test(win);
}

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
  const slugRow = byLabel.get("GitHub repository");
  if (slugRow) {
    const claimed = [...slugRow.oursCell.matchAll(/`([^`]+)`/g)].map((m) => m[1])[0];
    let remote = null;
    try {
      remote = execSync("git remote get-url origin", { cwd: REPO, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
    } catch { }
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
    }
  }

  const siblings = [];
  for (const f of facts) for (const v of f.theirs) if (!isOwnLabel(v)) siblings.push({ v, label: f.label });
  siblings.sort((a, b) => b.v.length - a.v.length);
  if (siblings.length !== EXPECTED_SIBLING_VALUES) {
    fail(
      `docs/CI.md §0's register yields ${siblings.length} sweepable field-repository value(s), and EXPECTED_SIBLING_VALUES says ` +
      `${EXPECTED_SIBLING_VALUES}. Fewer means a row stopped naming its value and the sweep silently stopped looking for it; ` +
      "more means a fact was added without anybody deciding it should be swept. Either way, say so here.",
    );
  }
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
      for (const m of idShapeRuns ? line.matchAll(VERCEL_ID_SHAPE) : []) {
        const id = m[0];
        if (canonicalIds.has(id)) continue;
        if (siblings.some((s) => s.v === id)) continue;
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

const SECOND_REGISTER_HEADER = "| | Designer portal (this repo) | Field repository |";
const SECOND_REGISTER_ROWS = new Map([
  ["API box", "API box (Elastic IP)"],
  ["S3 media bucket", "S3 media bucket"],
  ["CloudFront", "CloudFront"],
  ["SSH key pair", "SSH key pair / file"],
  ["Vercel", "Vercel production alias"],
  ["Google OAuth", null],
  ["systemd units", null],
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
    if (!row) continue;
    if (want === "CloudFront" && asks && !/unresolved/i.test(lines[i])) {
      fail(
        "ENVIRONMENT.md §4's CloudFront row states a distribution with no UNRESOLVED marker on it, while the document itself " +
        `still carries "${API_HOST_QUESTION}" and docs/CI.md §0's row says UNRESOLVED. This is the table a reader opens to ` +
        "look up the value, so it is the one place the open question must not be quietly answered.",
      );
    }
    for (const [side, registered, cell] of [["Designer portal (this repo)", row.ours, ours], ["Field repository", row.theirs, theirs]]) {
      const here = cellLiterals(cell);
      if (!here.length || !registered.length) continue;
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

const ROT_BASELINE = new Set([
  "when-x-lands docs/archive/SESSION_HANDOVER.md 6ea56210",
  "not-before docs/archive/SESSION_HANDOVER.md 1c61ff15",
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/MainActivity.kt 239c6854",
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/MainActivity.kt 6cba3dce",
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/data/DwAiVerbs.kt e4600780",
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/data/DwAsrModelInstall.kt c0423c23",
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/data/DwCustomSections.kt 465a57a0",
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/data/DwDownload.kt 49f32f75",
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/data/DwTier2Layer.kt 3a5c5af7",
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/data/DwWorkshopCreation.kt 2135d31b",
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/data/StageSchema.kt 9b006348",
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/ui/AppNavigation.kt 19198349",
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/ui/LocationFields.kt 18a6fb0c",
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/ui/NumberedPointsField.kt 9fdf3210",
  "undated-call-site-count android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwAsrModelInstallUi.kt 1a0907eb",
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwAsrModelInstallUi.kt f382a97f",
  "when-x-lands android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/WorkshopListScreen.kt 65e530ee",
  "undated-call-site-count android/app/src/test/java/com/designprototype/workshop/report/ChartLabelSizeTest.kt 82f18988",
  "undated-call-site-count backend/app/api/routes/design_workshops.py 39a94a67",
  "not-before backend/app/api/routes/design_workshops.py 1e2d0f71",
  "undated-call-site-count backend/app/api/routes/media.py f267bc6a",
  "not-before backend/app/api/routes/media.py 1560bf63",
  "undated-call-site-count backend/app/api/routes/questionnaire_forms.py 8c04658c",
  "not-before backend/app/schemas/design_workshops.py 55a6b50b",
  "undated-call-site-count backend/app/services/ai_layers.py e31cb9ed",
  "for-now backend/app/services/app_settings.py b7989001",
  "undated-call-site-count backend/app/services/artisan_identity.py 63ae41be",
  "undated-call-site-count backend/app/services/design_workshops.py 7ddb27dc",
  "undated-call-site-count backend/app/services/design_workshops.py 761aefe4",
  "phase-n backend/app/services/stage_definitions.py 7304f9b7",
  "for-now backend/app/services/stage_definitions.py d3c8d720",
  "for-now backend/app/services/stage_definitions.py d4adf2c4",
  "phase-n backend/scripts/reconcile_interview_set_keys.py b15aa9c5",
  "phase-n backend/scripts/reconcile_interview_set_keys.py 389a10f2",
  "when-x-lands backend/tests/test_designer_roster.py b794c92b",
  "for-now backend/tests/test_stage_schema.py 9bced674",
  "not-before docs/COMPUTED_FINDINGS.md 0254fd77",
  "undated-call-site-count docs/SCALABILITY.md 29c4c9a2",
  "when-x-lands frontend/components/BackButton.tsx 9888ff6a",
  "for-now frontend/components/SignaturePad.tsx 099005d8",
  "for-now frontend/components/designworkshop/AiVerbReviewDialog.tsx 9aef7fa5",
  "undated-call-site-count frontend/components/designworkshop/StageRecordEmbed.tsx 8ec67a5f",
  "undated-call-site-count frontend/components/dictation/onDeviceSpeech.ts 11511c84",
  "undated-call-site-count frontend/components/forms/MediaCaptureField.tsx 03d86ba2",
  "undated-call-site-count frontend/components/forms/inlineRecordHost.ts b4a0ea0c",
  "when-x-lands frontend/components/settings/ProviderOrderPanel.tsx b2302a21",
  "not-before frontend/e2e/guide-walkthrough-unit.spec.ts 326990ef",
  "undated-call-site-count frontend/e2e/inline-record-host-unit.spec.ts e0a75647",
  "undated-call-site-count frontend/e2e/inline-record-host-unit.spec.ts 3719c355",
  "when-x-lands frontend/e2e/inline-record-host-unit.spec.ts 5ec39b6e",
  "undated-call-site-count frontend/e2e/process-refusal-a11y-unit.spec.ts 5fd64ec0",
  "when-x-lands frontend/e2e/qr-decode-unit.spec.ts 56b5829d",
  "when-x-lands frontend/lib/aiLayers.ts 7169ea6f",
  "undated-call-site-count frontend/lib/aiVerbs.ts 19cfbc00",
  "undated-call-site-count frontend/lib/aiVerbs.ts dca8ada2",
  "undated-call-site-count frontend/lib/designWorkshopStore.ts 315664c5",
  "not-before frontend/lib/media.ts 39b007b9",
]);

function quotedSpans(line) {
  const spans = [];
  for (const re of [/"[^"]{1,300}"/g, /“[^”]{1,300}”/g, /(?<![A-Za-z])'[^'\n]{3,300}'(?![A-Za-z])/g, /‘[^’]{1,300}’/g]) {
    for (const m of line.matchAll(re)) spans.push([m.index, m.index + m[0].length]);
  }
  return spans;
}

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

const ROT_SELFTEST = [
  ["FIRES", "x.py", '# delete this paragraph when the ports and a panel land, not before.'],
  ["FIRES", "x.kt", "// When the route lands, this map is the contract it has to satisfy."],
  ["FIRES", "x.ts", "// Retained so the three call sites need no edit."],
  ["FIRES", "x.md", "There is no designer-facing surface today, everywhere it could sit is unbuilt."],
  ["FIRES", "x.py", "#: Phase 2 work — the Advanced tier is not implemented."],
  ["FIRES", "x.ts", "// Good enough for now; the real gate goes in beside the reducer."],
  ["ABSTAINS", "x.kt", "// A hold-up that resolves itself when an upload lands, which is normal."],
  ["FIRES", "x.ts", "// Follow the row. When it lands on either end its arrow goes disabled."],
  ["ABSTAINS", "x.py", '# The margin note read "Phase 2 work", which is quoted, not claimed.'],
  ["ABSTAINS", "x.py", "# The margin note read 'Phase 2 work', which is quoted, not claimed."],
  ["FIRES", "x.ts", "// Don't delete this when the panel lands, not before the port's done."],
  ["FIRES", "x.ts", "// The portal's default is good enough for now, per the reader's note."],
  ["ABSTAINS", "x.ts", "// The store's three callers each dispose of it, and that's deliberate."],
  ["ABSTAINS", "x.ts", "const threeCallers = 1; // not a comment line, so not scanned at all"],
  ["ABSTAINS", "x.md", "All three callers agree — true as of 2026-08-22; check `grep -rn hydrate`."],
];

function selfTestRot() {
  let fired = 0;
  for (const [want, name, line] of ROT_SELFTEST) {
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

function selfTestSiblingSweep() {
  const cases = [
    [false, "fieldrepo-media-626159998512"],
    [false, "field-repository.vercel.app"],
    [false, "fieldrepo-deploy"],
    [false, "15.207.145.174"],
    [false, "prj_EzXN8hhGKpMciFBrZRdxpcgUUzN0"],
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
  const table = ["prose", "| | This portal | The field repository |", "|---|---|---|", "| a | x | y |", "| b | p | q |", "| c | m | n |"];
  if (!SIBLING_LABEL.test(labelWindow(table, 6))) {
    fail("check-docs: §10's labelWindow no longer reaches a markdown table's header row — every register row will report itself");
  }
  if (SIBLING_LABEL.test(labelWindow(["a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "the field repository"], 1))) {
    fail("check-docs: §10's labelWindow reaches eleven lines away in prose — a label that distant is not a label the reader will see");
  }

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
  if (!windowSaysWhose([...cfTable.slice(0, 1), "| | This portal | The field repository |", ...cfTable.slice(1)], 5, swept)) {
    fail("check-docs: §10 no longer reads a 'field repository' table header as a label — every register row will report itself");
  }

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
