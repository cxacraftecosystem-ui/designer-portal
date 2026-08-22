#!/usr/bin/env node
/**
 * One-shot setup so the release pipeline publishes the web app without anyone's laptop.
 *
 * Run it once, with a Vercel token:
 *
 *     VERCEL_TOKEN=xxxxxxxx node scripts/vercel-ci-setup.mjs          # interactive: it asks first
 *     VERCEL_TOKEN=xxxxxxxx node scripts/vercel-ci-setup.mjs --yes    # non-interactive: flag required
 *
 * Create the token at https://vercel.com/account/tokens → Create Token, scoped to the TEAM that
 * owns the Vercel project `frontend/.vercel/project.json` names (not "Personal Account", or every
 * call below 403s). This script never prints it and never writes it to disk.
 *
 * ─── WHICH REPOSITORY IT WRITES TO, AND WHY THAT IS NOT A LITERAL ANY MORE ──────────────────────
 * This file used to carry `const REPO = "cxacraftecosystem-ui/documentation-portal"`, hard-coded,
 * while `git remote get-url origin` in this checkout is `cxacraftecosystem-ui/designer-portal`.
 * Those are two different repositories with divergent histories, so running it sealed THIS portal's
 * live Vercel token, org id and project id into ANOTHER repository's Actions secrets — where they
 * are readable by every workflow that repository runs — and left this one with no token at all,
 * which is the very failure the script exists to fix. A wrong deploy target that AUTHENTICATES is
 * the dangerous kind: nothing 403s, nothing looks wrong, and the run goes green.
 *
 * So the slug is now DERIVED, from `GITHUB_REPOSITORY` if the process has one and otherwise from
 * the `origin` remote of the checkout the script is sitting in, and the script exits non-zero
 * rather than guessing. Everything it is about to touch — owner/name, Vercel project name, project
 * and org id — is printed before the first API call, and a run that cannot ask a human for
 * confirmation has to be told `--yes` in as many words.
 *
 * What it does, and why each step is needed:
 *
 *  1. Sets the project's **Root Directory to `frontend`**. This is the actual cause of the
 *     "No Next.js version detected" build failure: with it unset, Vercel builds the repository
 *     root, whose package.json has no `next` in it. It is also what `vercel build` in
 *     .github/workflows/deploy-frontend.yml reads, so the same setting fixes both paths at once.
 *     It cannot be set from vercel.json — it is a project setting, so it has to be this or the
 *     dashboard.
 *
 *  2. **Turns off Git-triggered deployments.** Two publishers for one site is the bug: Vercel's
 *     own Git integration starts building the moment `main` moves, which is *before* the backend
 *     has deployed and migrated, so the live site spends that window calling endpoints that answer
 *     404. GitHub Actions becomes the single publisher and the backend→frontend order holds.
 *     (`vercel.json`'s `ignoreCommand` already does this belt-and-braces for Git builds; this makes
 *     it explicit at the project level. CLI deploys — which is what Actions does — are unaffected.)
 *
 *  3. Writes **VERCEL_TOKEN / VERCEL_ORG_ID / VERCEL_PROJECT_ID** into the repository's Actions
 *     secrets, encrypted with the repo's public key (libsodium sealed box), so stage 2 of the
 *     pipeline stops skipping and actually publishes.
 *
 * For step 3 it needs a GitHub token with `repo` scope. It reuses the one in your git credential
 * helper automatically — the same credential `git push` uses — or you can pass GITHUB_TOKEN.
 */

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { createInterface } from "node:readline/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import sodium from "libsodium-wrappers";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "..");
const CONFIRMED = process.argv.includes("--yes") || process.argv.includes("-y");

/**
 * `owner/name` out of anything git or GitHub Actions is likely to hand us — an HTTPS clone URL, an
 * `ssh://` or `git@host:` remote, or the bare `owner/name` that `GITHUB_REPOSITORY` holds. Returns
 * null rather than a half-parse, because a half-parse here is a secret written somewhere else.
 *
 * `.` is in the character class because real repository names contain dots (`foo.github.io`), and
 * that let a RELATIVE PATH through: `../sibling-repo` matched and yielded the slug
 * `../sibling-repo`, which this script then interpolates into
 * `https://api.github.com/repos/<slug>/actions/secrets/...`, where WHATWG normalisation eats the
 * `repos/` segment and leaves `https://api.github.com/sibling-repo/...`. No secret actually left
 * the machine — the public-key GET 404s and throws long before any PUT — but a `..` reaching a
 * value that names where a token gets written is precisely the input this function exists to
 * refuse, and "it happens to fail later" is not the contract stated above. So a segment that is
 * exactly `.` or `..` is rejected here, at the parse, where the refusal is the documented one.
 */
function repoSlug(value) {
  const match = String(value || "")
    .trim()
    .match(/^(?:https?:\/\/[^/]+\/|ssh:\/\/(?:[^@/]+@)?[^/]+\/|[^@/]+@[^:]+:)?([\w.-]+)\/([\w.-]+?)(?:\.git)?\/?$/);
  if (!match) return null;
  if (match[1] === "." || match[1] === ".." || match[2] === "." || match[2] === "..") return null;
  return `${match[1]}/${match[2]}`;
}

function resolveRepo() {
  const fromEnv = repoSlug(process.env.GITHUB_REPOSITORY);
  if (fromEnv) return { slug: fromEnv, source: "GITHUB_REPOSITORY" };
  let origin = "";
  try {
    origin = execFileSync("git", ["remote", "get-url", "origin"], { cwd: REPO_ROOT, encoding: "utf8" }).trim();
  } catch {
    origin = "";
  }
  const fromGit = repoSlug(origin);
  if (fromGit) return { slug: fromGit, source: `git remote origin (${origin})` };
  return null;
}

const VERCEL_TOKEN = process.env.VERCEL_TOKEN;
if (!VERCEL_TOKEN) {
  console.error("VERCEL_TOKEN is not set.\n");
  console.error("  1. https://vercel.com/account/tokens -> Create Token (scope: the team, not Personal)");
  console.error("  2. VERCEL_TOKEN=<the token> node scripts/vercel-ci-setup.mjs\n");
  process.exit(1);
}

const resolved = resolveRepo();
if (!resolved) {
  console.error("Cannot tell which GitHub repository these secrets belong in.\n");
  console.error("  Neither GITHUB_REPOSITORY nor `git remote get-url origin` resolved to owner/name.");
  console.error("  Refusing to guess: guessing writes a live Vercel token into somebody else's repository.");
  console.error("  Set GITHUB_REPOSITORY=owner/name, or run this from a checkout that has an origin remote.\n");
  process.exit(1);
}
const REPO = resolved.slug;

// projectId/orgId are identifiers, not credentials — they live in the linked project file.
const link = JSON.parse(readFileSync(path.join(REPO_ROOT, "frontend", ".vercel", "project.json"), "utf8"));
const { projectId, orgId } = link;

// Printed BEFORE the first API call, because every one of these is a thing that can be wrong in a
// way that still succeeds. The token itself is never printed, here or anywhere below.
console.log("About to change:");
console.log(`  GitHub repository   ${REPO}   [from ${resolved.source}]`);
console.log(`  Vercel project      ${link.projectName}  (${projectId} in ${orgId})`);
console.log("        1. Root Directory -> frontend, framework -> nextjs");
console.log("        2. Git-triggered deployments -> disabled");
console.log(`        3. VERCEL_TOKEN / VERCEL_ORG_ID / VERCEL_PROJECT_ID -> Actions secrets of ${REPO}`);
console.log("");

if (!CONFIRMED) {
  if (!process.stdin.isTTY) {
    console.error("Refusing to run unattended without confirmation. Re-run with --yes once the two");
    console.error("names above are the ones you meant.\n");
    process.exit(1);
  }
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const answer = (await rl.question("Write the Vercel credentials into that repository? [y/N] ")).trim().toLowerCase();
  rl.close();
  if (answer !== "y" && answer !== "yes") {
    console.error("Aborted; nothing was changed.");
    process.exit(1);
  }
}

// --- GitHub token: whatever `git push` already uses -----------------------------------------
function githubToken() {
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN;
  const out = execFileSync("git", ["credential", "fill"], {
    input: "protocol=https\nhost=github.com\n\n",
    encoding: "utf8",
    cwd: REPO_ROOT
  });
  const line = out.split("\n").find((l) => l.startsWith("password="));
  if (!line) throw new Error("No GitHub credential found. Set GITHUB_TOKEN and re-run.");
  return line.slice("password=".length).trim();
}

async function vercel(method, url, body) {
  const response = await fetch(`https://api.vercel.com${url}${url.includes("?") ? "&" : "?"}teamId=${orgId}`, {
    method,
    headers: { Authorization: `Bearer ${VERCEL_TOKEN}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Vercel ${method} ${url} -> ${response.status} ${text.slice(0, 400)}`);
  return text ? JSON.parse(text) : {};
}

// --- 1 + 2: project settings ------------------------------------------------------------------
console.log("\n[1/3] Root Directory -> frontend, framework -> nextjs");
const project = await vercel("PATCH", `/v9/projects/${projectId}`, {
  rootDirectory: "frontend",
  framework: "nextjs"
});
console.log(`      rootDirectory is now: ${JSON.stringify(project.rootDirectory)}`);

console.log("[2/3] Git-triggered deployments -> disabled (GitHub Actions becomes the only publisher)");
await vercel("PATCH", `/v9/projects/${projectId}`, {
  gitProviderOptions: { createDeployments: "disabled" }
});
console.log("      done");

// --- 3: GitHub Actions secrets ----------------------------------------------------------------
console.log("[3/3] Writing Actions secrets");
await sodium.ready;
const gh = githubToken();

async function github(method, url, body) {
  const response = await fetch(`https://api.github.com${url}`, {
    method,
    headers: {
      Authorization: `Bearer ${gh}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "Content-Type": "application/json"
    },
    body: body ? JSON.stringify(body) : undefined
  });
  if (!response.ok && response.status !== 204) {
    throw new Error(`GitHub ${method} ${url} -> ${response.status} ${(await response.text()).slice(0, 300)}`);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : {};
}

const key = await github("GET", `/repos/${REPO}/actions/secrets/public-key`);
const publicKey = sodium.from_base64(key.key, sodium.base64_variants.ORIGINAL);

for (const [name, value] of [
  ["VERCEL_TOKEN", VERCEL_TOKEN],
  ["VERCEL_ORG_ID", orgId],
  ["VERCEL_PROJECT_ID", projectId]
]) {
  const sealed = sodium.crypto_box_seal(sodium.from_string(value), publicKey);
  await github("PUT", `/repos/${REPO}/actions/secrets/${name}`, {
    encrypted_value: sodium.to_base64(sealed, sodium.base64_variants.ORIGINAL),
    key_id: key.key_id
  });
  console.log(`      ${name} set`);
}

console.log("\nDone. Push anything to main (or re-run the backend workflow) and all three stages");
console.log("should go green with the web app actually published.");
