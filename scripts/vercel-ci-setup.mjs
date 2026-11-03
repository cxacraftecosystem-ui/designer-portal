#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { createInterface } from "node:readline/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import sodium from "libsodium-wrappers";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "..");
const CONFIRMED = process.argv.includes("--yes") || process.argv.includes("-y");

function repoSlug(value) {
  const match = String(value || "")
    .trim()
    .match(/^(?:https?:\/\/[^/]+\/|ssh:\/\/(?:[^@/]+@)?[^/]+\/|[^@/]+@[^:]+:)?([\w.-]+)\/([\w.-]+?)(?:\.git)?\/?$/);
  if (!match) return null;
  if (match[1] === "." || match[1] === ".." || match[2] === "." || match[2] === "..") return null;
  return `${match[1]}/${match[2]}`;
}

function withoutCredentials(remote) {
  return String(remote || "").replace(/^([a-z][a-z\d+.-]*:\/\/)?[^/]*@/i, "$1");
}

function resolveVercelLink() {
  const file = path.join(REPO_ROOT, "frontend", ".vercel", "project.json");
  let fromFile = null;
  if (existsSync(file)) {
    let link;
    try {
      link = JSON.parse(readFileSync(file, "utf8"));
    } catch {
      return { error: "frontend/.vercel/project.json exists but is not readable JSON. Delete it, or re-run `vercel link`." };
    }
    if (link.projectId && link.orgId) fromFile = { projectId: link.projectId, orgId: link.orgId };
  }

  const envOrg = (process.env.VERCEL_ORG_ID || "").trim();
  const envProject = (process.env.VERCEL_PROJECT_ID || "").trim();
  if (Boolean(envOrg) !== Boolean(envProject)) {
    const [have, missing] = envOrg ? ["VERCEL_ORG_ID", "VERCEL_PROJECT_ID"] : ["VERCEL_PROJECT_ID", "VERCEL_ORG_ID"];
    return { error: `${have} is set but ${missing} is not. Set both or neither.` };
  }
  const fromEnv = envOrg ? { projectId: envProject, orgId: envOrg } : null;

  if (fromFile && fromEnv) {
    if (fromFile.projectId !== fromEnv.projectId || fromFile.orgId !== fromEnv.orgId) {
      return {
        error:
          `frontend/.vercel/project.json names project ${fromFile.projectId} in ${fromFile.orgId}, while ` +
          `VERCEL_PROJECT_ID/VERCEL_ORG_ID name ${fromEnv.projectId} in ${fromEnv.orgId}. One of the two ` +
          "describes a different project. Refusing to guess which.",
      };
    }
    return { ...fromFile, source: "frontend/.vercel/project.json, and VERCEL_ORG_ID/VERCEL_PROJECT_ID agree" };
  }
  if (fromFile) return { ...fromFile, source: "frontend/.vercel/project.json" };
  if (fromEnv) return { ...fromEnv, source: "VERCEL_ORG_ID/VERCEL_PROJECT_ID environment variables" };
  return { error: "No Vercel project to link: frontend/.vercel/project.json does not exist and VERCEL_ORG_ID/VERCEL_PROJECT_ID are not set." };
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
  if (fromGit) return { slug: fromGit, source: `git remote origin (${withoutCredentials(origin)})` };
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

const linked = resolveVercelLink();
if (linked.error) {
  console.error(`${linked.error}\n`);
  console.error("  Link this checkout first, either way:");
  console.error("    cd frontend && vercel link --yes --project designer-repository");
  console.error("    or set VERCEL_ORG_ID and VERCEL_PROJECT_ID (docs/CI.md §2 gives both values)\n");
  process.exit(1);
}
const { projectId, orgId } = linked;

const target = await vercel("GET", `/v9/projects/${projectId}`);
const projectName = target.name || "<no name returned>";
if (projectName === "field-repository") {
  console.error(`${projectId} is the Vercel project 'field-repository', a different product in the same team.`);
  console.error("This repository publishes to 'designer-repository'. Nothing has been changed; see docs/CI.md §2.\n");
  process.exit(1);
}

console.log("About to change:");
console.log(`  GitHub repository   ${REPO}   [from ${resolved.source}]`);
console.log(`  Vercel project      ${projectName}  (${projectId} in ${orgId})   [from ${linked.source}]`);
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
