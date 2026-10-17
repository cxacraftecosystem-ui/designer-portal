import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { newAccountBody, rowOffers } from "@/app/(protected)/users/accountAdmin";
import { ApiError } from "@/lib/api";
import { firstPasswordNeedsCode, twoFactorCodeRequired } from "@/lib/passwordChange";
import { provisionableRoles } from "@/lib/permissions";
import { EXISTING_ACCOUNT_HEADER, existingAccountId, retryAfterWords } from "@/lib/signIn";
import type { User } from "@/lib/types";

/**
 * GETTING BACK IN — "Forgot your password?", "Sign out of every device", and an address let in with a
 * password from the access list.
 *
 *   1. the sign-in card links to a PUBLIC /forgot-password page, outside the protected tree;
 *   2. that page prints the server's one sentence verbatim and has no branch about the account;
 *   3. a 429 says when to try again, from `Retry-After`;
 *   4. "Sign out of every device" is offered on exactly the accounts a provisioner manages, confirmed
 *      first, and posts to the route the server publishes;
 *   5. the access list's add form asks how the person will sign in, and sends the password untrimmed.
 *
 * Source reads where the rule is a component — there is no React renderer in devDependencies.
 * ⚠ LINE-ENDING AGNOSTIC: `\s` and `[\s\S]` throughout.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const LOGIN = read("app", "login", "page.tsx");
const FORGOT = read("app", "forgot-password", "page.tsx");
const SIGN_IN = read("lib", "signIn.ts");
const USERS = read("app", "(protected)", "users", "page.tsx");
const ACCESS = read("app", "(protected)", "admin", "access", "page.tsx");
const ACCESS_LIB = read("lib", "accessRoster.ts");

test("the sign-in card links to a public forgot-password page", () => {
  expect(LOGIN).toMatch(/<Link href="\/forgot-password"[^>]*>\s*Forgot your password\?\s*<\/Link>/);
  // Public: it lives outside app/(protected), like /set-password, so a locked-out reader reaches it.
  expect(() => read("app", "(protected)", "forgot-password", "page.tsx")).toThrow();
  expect(FORGOT.startsWith('"use client";')).toBe(true);
});

test("the page prints the server's one answer and nothing about the account", () => {
  expect(SIGN_IN).toMatch(/"\/auth\/forgot-password",\s*\{ method: "POST", body: JSON\.stringify\(\{ email \}\) \},\s*\{ redirectOn401: false \}/);
  expect(FORGOT).toContain("setSent(answer?.message ||");
  // No sentence that could tell a stranger whether an address has an account.
  expect(FORGOT).not.toMatch(/not found|no account|could not find|doesn't exist|does not exist/i);
  expect(FORGOT).toContain("Email me a link");
  expect(FORGOT).toContain("you will still need your authenticator app");
  expect(FORGOT).toContain("No email? Ask an administrator");
  // FormData first, before any await.
  const submit = FORGOT.slice(FORGOT.indexOf("async function submit("));
  expect(submit.indexOf("new FormData(event.currentTarget)")).toBeLessThan(submit.indexOf("await requestForgotPasswordLink("));
});

test("a 429 says when to try again", () => {
  expect(retryAfterWords("30")).toBe("Try again in a minute.");
  expect(retryAfterWords("600")).toBe("Try again in 10 minutes.");
  expect(retryAfterWords(null)).toBeNull();
  expect(retryAfterWords("soon")).toBeNull();
  expect(FORGOT).toContain("serverAskedForTime(err)");
});

const viewer = { id: "a", role: "ADMIN", email: "a@x.in", name: "A" } as User;
const target = (extra: Partial<User> = {}) =>
  ({ id: "b", role: "RESEARCHER", email: "b@x.in", name: "B", ...extra }) as User;

test("sign out of every device is offered on a managed account, and only by a provisioner", () => {
  expect(rowOffers(viewer, target(), { provision: true, admin: true }).signOutEverywhere).toBe(true);
  expect(rowOffers(viewer, target(), { provision: false, admin: false }).signOutEverywhere).toBe(false);
  // Never on the reader's own row, nor a peer's.
  expect(rowOffers(viewer, { ...viewer }, { provision: true, admin: true }).signOutEverywhere).toBe(false);
  expect(rowOffers(viewer, target({ role: "ADMIN" }), { provision: true, admin: true }).signOutEverywhere).toBe(false);
  // The server's derived `hasPassword` decides the password-link offer where it is sent.
  expect(rowOffers(viewer, target({ hasPassword: true, passwordSetAt: null }), { provision: true, admin: true }).passwordLink).toBe(true);
  expect(rowOffers(viewer, target({ hasPassword: false, passwordSetAt: "2026-01-01" }), { provision: true, admin: true }).passwordLink).toBe(false);
});

test("sign out of every device is confirmed first and posts to the published route", () => {
  expect(SIGN_IN).toContain("`/users/${userId}/sign-out-everywhere`, { method: \"POST\" }");
  const handler = USERS.slice(USERS.indexOf("async function endEverySession("), USERS.indexOf("async function remove("));
  expect(handler.indexOf("await confirm(")).toBeGreaterThan(-1);
  expect(handler.indexOf("await confirm(")).toBeLessThan(handler.indexOf("await signOutEverywhere(user.id)"));
  expect(USERS).toMatch(/offers\.signOutEverywhere \? \([\s\S]*?Sign out of every device/);
});

test("the access list asks how they will sign in, and sends a password untrimmed", () => {
  expect(ACCESS).toContain("How will they sign in?");
  expect(ACCESS).toContain("With Google, Microsoft or Yahoo");
  expect(ACCESS).toContain("With a password");
  expect(ACCESS).toContain("Ask them to choose a new one at first sign-in");
  expect(ACCESS).toContain('const password = withPassword ? String(form.get("password") ?? "") : "";');
  expect(ACCESS).not.toMatch(/form\.get\("password"\)[^;\n]*\.trim\(\)/);
  expect(ACCESS).toContain("...(withPassword ? { password, mustChangePassword: mustChange } : {})");
  // Only on the add form: a correction cannot create an account.
  expect(ACCESS).toContain("{editing ? null : <SignInMethodField />}");
  expect(ACCESS_LIB).toMatch(/password\?: string;\s*mustChangePassword\?: boolean;/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * "Add a person" — one step, three ways to sign in
 * ──────────────────────────────────────────────────────────────────────────── */

const FORM = {
  name: "Asha",
  email: "asha@example.org",
  role: "RESEARCHER" as const,
  password: " typed ",
  mustChangePassword: true,
  canManageQuestionnaire: true,
  canDownloadDataset: false
};

test("the body carries only what each sign-in answer needs", () => {
  expect(newAccountBody({ ...FORM, passwordMode: "SET" }, { admin: false })).toEqual({
    name: "Asha",
    email: "asha@example.org",
    role: "RESEARCHER",
    passwordMode: "SET",
    password: " typed ",
    mustChangePassword: true
  });
  // GENERATE: no password and no flag — the server makes one and forces the change.
  expect(newAccountBody({ ...FORM, passwordMode: "GENERATE" }, { admin: false })).toEqual({
    name: "Asha",
    email: "asha@example.org",
    role: "RESEARCHER",
    passwordMode: "GENERATE"
  });
  // NONE: no password; the link only when ticked.
  expect(newAccountBody({ ...FORM, passwordMode: "NONE" }, { admin: false })).toEqual({
    name: "Asha",
    email: "asha@example.org",
    role: "RESEARCHER",
    passwordMode: "NONE"
  });
  expect(newAccountBody({ ...FORM, passwordMode: "NONE", sendPasswordLink: true }, { admin: false }).sendPasswordLink).toBe(true);
  // A link is never asked for beside a password.
  expect("sendPasswordLink" in newAccountBody({ ...FORM, passwordMode: "SET", sendPasswordLink: true }, { admin: false })).toBe(false);
  // Grants still ride only for an admin.
  expect(newAccountBody({ ...FORM, passwordMode: "GENERATE" }, { admin: true }).canManageQuestionnaire).toBe(true);
});

test("the role picker offers only tiers strictly below the viewer, and Admin to the master admin alone", () => {
  const as = (role: User["role"]) => ({ id: "v", role, email: "v@x.in", name: "V" }) as User;
  expect(provisionableRoles(as("ADMIN"))).not.toContain("ADMIN");
  expect(provisionableRoles(as("ADMIN"))).toContain("MINISTRY_ADMIN");
  expect(provisionableRoles(as("MASTER_ADMIN"))).toContain("ADMIN");
  expect(provisionableRoles(as("MASTER_ADMIN"))).not.toContain("MASTER_ADMIN");
  expect(provisionableRoles(as("MINISTRY_ADMIN"))).not.toContain("MINISTRY_ADMIN");
  expect(provisionableRoles(as("PROFESSOR"))).toEqual([]);
});

test("a duplicate is answered with the existing account, read off the header", () => {
  const headers = new Headers({ [EXISTING_ACCOUNT_HEADER]: "cuid123" });
  expect(existingAccountId(new ApiError(409, "asha@example.org already has an account", null, headers))).toBe("cuid123");
  expect(existingAccountId(new ApiError(409, "x", null))).toBeNull();
  expect(existingAccountId(new ApiError(403, "x", null, headers))).toBeNull();
  expect(USERS).toContain("Open their account");
  expect(USERS).toContain("const id = existingAccountId(err);");
});

test("the form asks how they will sign in, shows a generated password once, and offers the link only with mail", () => {
  for (const label of ["Set a password now", "Make a temporary password for me", "Google, Microsoft or Yahoo only"]) {
    expect(USERS).toContain(label);
  }
  expect(USERS).toContain("Add a person");
  expect(USERS).toMatch(/passwordMode === "NONE" && mailAvailable \? \([\s\S]{0,400}Email them a link to set a password too/);
  expect(USERS).toContain("They must change it when they first sign in");
  expect(USERS).toMatch(/aria-label="Temporary password"/);
});

const CARD = read("components", "settings", "ChangePasswordCard.tsx");
const ASSET_LINKS = read("public", ".well-known", "assetlinks.json");
const NEXT_CONFIG = read("next.config.ts");

test("a first password on a two-step account asks for the code and sends it", () => {
  expect(firstPasswordNeedsCode({ twoFactorEnabled: true })).toBe(true);
  expect(firstPasswordNeedsCode({ twoFactorEnabled: false })).toBe(false);
  expect(firstPasswordNeedsCode(null)).toBe(false);
  expect(SIGN_IN).toMatch(/twoFactorCode: code/);
  expect(CARD).toMatch(/Code from your authenticator app/);
  expect(CARD).toMatch(/setFirstPassword\(next, proof, askCode \? code : undefined\)/);
});

test("a refusal for the two-step code is read off its header, not its words", () => {
  const refused = new ApiError(400, "Enter the code", null, new Headers({ "x-two-factor-required": "1" }));
  expect(twoFactorCodeRequired(refused)).toBe(true);
  expect(twoFactorCodeRequired(new ApiError(400, "Enter the code", null, new Headers()))).toBe(false);
  expect(twoFactorCodeRequired(new ApiError(401, "x", null, new Headers({ "x-two-factor-required": "1" })))).toBe(false);
});

test("assetlinks.json names the app and a release certificate fingerprint, served as JSON", () => {
  const statements = JSON.parse(ASSET_LINKS) as Array<{
    relation: string[];
    target: { namespace: string; package_name: string; sha256_cert_fingerprints: string[] };
  }>;
  const app = statements.find((s) => s.target.namespace === "android_app");
  expect(app?.relation).toContain("delegate_permission/common.handle_all_urls");
  expect(app?.target.package_name).toBe("com.designprototype.workshop");
  expect(app?.target.sha256_cert_fingerprints.length).toBeGreaterThan(0);
  for (const fp of app?.target.sha256_cert_fingerprints ?? []) expect(fp).toMatch(/^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);
  expect(NEXT_CONFIG).toMatch(/source: "\/\.well-known\/assetlinks\.json"[\s\S]*?application\/json/);
});
