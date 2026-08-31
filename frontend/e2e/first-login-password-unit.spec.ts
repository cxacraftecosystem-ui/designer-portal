/**
 * THE FIRST-LOGIN PASSWORD, ON BOTH CLIENTS, AND THE SECOND REFUSAL HEADER BESIDE IT.
 *
 * ── WHAT LANDED, AND WHAT IT REPLACED ───────────────────────────────────────────────────────────
 *
 * Owner: *"they would be able to set the password on their first login, and confirm it"*. Until
 * 2026-08-31 that requirement was NOT MET on either client, and the interesting part is that nothing
 * was broken: `POST /api/users` created accounts with `mustChangePassword`, `serialize_user` carried
 * the flag on all four doors, `POST /auth/change-password` was the route it named — and no screen
 * anywhere read it. An account holding a password an administrator had typed, which is a shared
 * secret by construction, signed in and worked normally for ever.
 *
 * ── WHY THESE ARE THE THINGS PINNED ─────────────────────────────────────────────────────────────
 *
 * Every regression below renders perfectly, which is why none of them is caught by looking:
 *
 *   1. **The gate must not be skippable by the redirect.** `login()` calls `setUser`, React flushes
 *      it across the `await`, and the redirect effect runs BEFORE any state a handler sets after it.
 *      The two sign-in paths therefore check the ACCOUNT they were handed rather than waiting for the
 *      effect — miss one and the person lands on /dashboard a frame before the gate can draw.
 *   2. **One password vocabulary, not two.** The floor lived inside `/set-password` while that was
 *      the only screen asking anybody for a password. A second copy beside a second sentence is how
 *      two screens come to state different rules on the day the server's changes.
 *   3. **The gate REPLACES the sign-in controls.** The person already holds a token; a live "Sign In"
 *      underneath offers a second sign-in they cannot usefully make.
 *   4. **Consent first, then the password.** Both can be true of one account, and the consent panel
 *      is a CORRECTION owed to somebody who has just ticked a box — a request stacked on top of an
 *      unread correction is how the correction goes unread.
 *   5. **The two clients say the same two headings.** A designer refused on the phone opens the
 *      website next, and a different explanation there is how somebody concludes one is broken.
 *
 * Everything here is a source assertion or a pure function, for this repository's usual reason:
 * there is no React renderer in devDependencies, so a judgement inside JSX is only ever exercised by
 * somebody looking at a screen.
 *
 * ⚠ EVERY SOURCE ASSERTION IS LINE-ENDING AGNOSTIC. The tree is checked out CRLF on Windows and LF
 * in CI, and five specs in this folder already fail locally because they anchor on a literal
 * newline. `\s` and `[\s\S]` throughout; never `\n`.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  MIN_PASSWORD_LENGTH,
  mustChangePassword,
  passwordRuleLine,
  signInHintHeading,
  signInHintOf
} from "@/lib/signIn";
import type { User } from "@/lib/types";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const readAndroid = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", "..", "android", "app", "src", "main", "java", "com", "designprototype", "workshop", ...parts), "utf8");

const LOGIN = read("app", "login", "page.tsx");
const SET_PASSWORD = read("app", "set-password", "page.tsx");
const SIGN_IN = read("lib", "signIn.ts");
const KT_COPY = readAndroid("ui", "PasswordSetupCopy.kt");
const KT_HINT = readAndroid("ui", "AccessRefusalCopy.kt");
const KT_MAIN = readAndroid("MainActivity.kt");
const KT_REPO = readAndroid("data", "WorkshopRepository.kt");

const account = (mustChange?: boolean): User =>
  ({ id: "u1", email: "d@example.org", name: "A Designer", role: "DESIGNER", mustChangePassword: mustChange }) as User;

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Reading the flag
 * ──────────────────────────────────────────────────────────────────────────── */

test("an account whose password an administrator typed is gated", () => {
  expect(mustChangePassword(account(true))).toBe(true);
});

test("an account that chose its own password is not", () => {
  expect(mustChangePassword(account(false))).toBe(false);
});

test("a server older than the column blocks nobody", () => {
  // THE LOAD-BEARING READING. `mustChangePassword` is optional on `User` because a deployment that
  // predates the column sends nothing, and that absence is neither "must" nor "need not". A `!==
  // false` written by somebody tidying up would hold every account on such a deployment at a screen
  // whose only working control signs them out.
  expect(mustChangePassword(account(undefined))).toBe(false);
  expect(mustChangePassword(null)).toBe(false);
  expect(mustChangePassword(undefined)).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. One password vocabulary
 * ──────────────────────────────────────────────────────────────────────────── */

test("the length floor is declared once and is the server's", () => {
  // `credential_links.MIN_PASSWORD_LENGTH`, `SetPasswordRequest.password`,
  // `ChangePasswordRequest.newPassword`, `LoginRequest.password` and `UserCreate.password` all carry
  // 8, so a password that can be SET can always be used to sign in.
  expect(MIN_PASSWORD_LENGTH).toBe(8);
  expect(SIGN_IN).toContain("export const MIN_PASSWORD_LENGTH = 8");
});

test("the set-password screen no longer declares its own copy of it", () => {
  expect(SET_PASSWORD, "the local constant is gone").not.toMatch(/const\s+MIN_PASSWORD_LENGTH\s*=/);
  expect(SET_PASSWORD, "and it imports the shared one").toMatch(/MIN_PASSWORD_LENGTH[\s\S]{0,120}from "@\/lib\/signIn"/);
});

test("both screens print the same first clause and a different second", () => {
  const gate = passwordRuleLine("Other devices stay signed in.");
  const redeem = passwordRuleLine("This link works once.");
  expect(gate.startsWith(passwordRuleLine())).toBe(true);
  expect(redeem.startsWith(passwordRuleLine())).toBe(true);
  expect(passwordRuleLine()).toContain(String(MIN_PASSWORD_LENGTH));
  // The gate involves no link, so it must not mention one — and it must not promise session
  // revocation either, because `POST /auth/change-password` deliberately does not revoke.
  expect(gate.toLowerCase()).not.toContain("link");
});

test("the Android handset carries the same floor and the same first clause", () => {
  expect(KT_COPY).toContain("const val MIN_PASSWORD_LENGTH = 8");
  // Built the same way from the same number, so the two sentences cannot drift apart while the
  // constant agrees.
  expect(KT_COPY).toContain('"At least $MIN_PASSWORD_LENGTH characters."');
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The gate cannot be walked past
 * ──────────────────────────────────────────────────────────────────────────── */

test("both sign-in paths check the account they were handed, not the effect", () => {
  // Regression 1. Two call sites — the password submit and the Google callback — and the effect is
  // the belt rather than the brace.
  const guards = LOGIN.match(/if \(mustChangePassword\(account\)\) return;/g) ?? [];
  expect(guards.length, "the password path and the Google path").toBe(2);
});

test("the redirect effect refuses to navigate while either gate stands", () => {
  expect(LOGIN).toMatch(
    /if \(user && !signingIn\.current && !held && !passwordGate\) router\.replace\("\/dashboard"\)/
  );
});

test("the gate is derived from the live account, so an already-open session meets it too", () => {
  // Not a copy of the flag taken at sign-in: a session that was open when this page loaded carries
  // the same obligation, and a stored copy is a copy that goes stale after the change lands.
  expect(LOGIN).toMatch(/const passwordGate = !passwordSet && mustChangePassword\(user\) \? user : null;/);
});

test("a completed change closes the gate even if the /me that would prove it never lands", () => {
  // The latch. `changeOwnPassword` has succeeded server-side by this point; a failed best-effort
  // re-read must not ask somebody a second time for a password they have just set.
  expect(LOGIN).toContain("setPasswordSet(true)");
  expect(LOGIN).toMatch(/refreshMe\(\)\.catch\(\(\) => undefined\)/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. What the gate replaces, and in what order
 * ──────────────────────────────────────────────────────────────────────────── */

test("the two gates are one chain, consent first", () => {
  // Regression 4. A chain and not two independent branches, because both can be true of one account
  // — an admin-created account whose owner withdrew consent in Settings.
  expect(LOGIN).toMatch(
    /\{held \? \([\s\S]{0,200}?<StandingRefusal[\s\S]{0,200}?\) : passwordGate \? \([\s\S]{0,200}?<FirstPasswordGate/
  );
});

test("the sign-in controls are replaced, not left live underneath", () => {
  // Regression 3. The form lives in the final `: (` branch, so reaching it means neither gate stands.
  const chain = /\{held \? \([\s\S]*?\) : passwordGate \? \([\s\S]*?\) : \(/.exec(LOGIN)?.[0] ?? "";
  expect(chain, "the chain was located").toContain("<FirstPasswordGate");
  expect(chain, "and no sign-in form is inside either gate branch").not.toContain("<form onSubmit={submit}");
});

test("the gate has a way out that is not a way in", () => {
  // `UsageConsentGateScreen`'s own escape, for its reason: a person who cannot complete this must not
  // be held on one screen whose controls all do nothing. It signs out — it does not continue.
  expect(LOGIN).toContain("Sign out instead");
  const escape = /onSignOut=\{\(\) => \{[\s\S]*?\}\}/.exec(LOGIN)?.[0] ?? "";
  expect(escape, "the escape was located").toContain("logout()");
  expect(escape, "and it does not navigate into the app").not.toContain("/dashboard");
});

test("the current password is carried from the door and asked for only when absent", () => {
  // `POST /auth/change-password` requires it even for an account carrying the flag. On the ordinary
  // path the person typed it ten seconds ago; re-asking would be asking for a secret the page holds.
  expect(LOGIN).toContain("currentPassword={password}");
  expect(LOGIN).toMatch(/const askCurrent = currentPassword\.length === 0;/);
});

test("the confirmation is a real second box, checked before anything is sent", () => {
  // The owner asked for "set the password on their first login, and confirm it". The server takes
  // one `newPassword` and cannot see the second box, so a mismatch it could never detect would
  // otherwise be filed as the person's choice.
  expect(LOGIN).toContain('id="gate-confirm-password"');
  expect(LOGIN).toMatch(/if \(next !== confirm\) \{[\s\S]{0,200}?do not match/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The Android half of the same gate
 * ──────────────────────────────────────────────────────────────────────────── */

test("the handset gates on the same boolean, read the same way", () => {
  expect(KT_COPY).toContain("fun mustChangePasswordBlocks(user: UserDto?): Boolean = user?.mustChangePassword == true");
});

test("the handset's gate is a when arm between sign-in and the dashboard, not a dialog", () => {
  // A dialog is dismissible and "set a password if you feel like it" is not the requirement. It sits
  // AFTER the consent arm for the same reason the web chains its two in that order.
  const routing = /usageConsentBlocks\(user\) -> UsageConsentGateScreen\([\s\S]*?else -> HomeScreen\(/.exec(KT_MAIN)?.[0] ?? "";
  expect(routing, "the routing block was located").toContain("mustChangePasswordBlocks(user) -> PasswordGateScreen(");
  expect(
    routing.indexOf("usageConsentBlocks(user)"),
    "consent is asked first"
  ).toBeLessThan(routing.indexOf("mustChangePasswordBlocks(user)"));
});

test("the handset forgets the door password on every exit", () => {
  // It is process memory on a handset that is passed around a workshop. Written on a sign-in
  // attempt, read once by the gate, blanked on the gate being satisfied and on every sign-out —
  // the discipline `consentDoor.reset()` already gets, and for the same reason.
  const clears = KT_MAIN.match(/doorPassword = ""/g) ?? [];
  expect(clears.length, "failed sign-in, Google path, gate satisfied, and both sign-outs").toBeGreaterThanOrEqual(5);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. The identifier hint, and the rule about where it may be read from
 * ──────────────────────────────────────────────────────────────────────────── */

test("the hint is classified from the header and never from the body", () => {
  // `tests/test_platform_access_gate.py` asserts the refusal body holds nothing but `detail`, and
  // `auth.py` records that a second field there "would be the first crack in a rule the whole
  // feature's privacy argument rests on". Both clients read the header instead.
  expect(SIGN_IN).toContain('export const SIGN_IN_HINT_HEADER = "x-sign-in-hint"');
  expect(KT_REPO, "the handset records the same rule beside its own classifier").toContain(
    "THE REFUSAL BODY CARRIES EXACTLY ONE KEY"
  );
  expect(LOGIN, "the web reads it off the response headers").toContain("err.headers?.get(SIGN_IN_HINT_HEADER)");
  expect(KT_MAIN, "the handset reads it off the response headers").toContain("failure.signInHint()");
});

test("an unrecognised or absent hint draws no panel at all", () => {
  // A proxy that strips unknown headers, or a deployment older than the client, produces the same
  // absence as an ordinary wrong password. Guessing is the only way to produce a WRONG heading.
  expect(signInHintOf(null)).toBeNull();
  expect(signInHintOf("SOMETHING_NEW")).toBeNull();
  expect(signInHintHeading(null)).toBeNull();
});

test("both clients say the same two headings, word for word", () => {
  // Regression 5. A designer refused on the phone opens the website next.
  for (const hint of ["AMBIGUOUS_IDENTIFIER", "PASSWORD_NOT_SET"] as const) {
    const heading = signInHintHeading(hint);
    expect(heading, hint).toBeTruthy();
    expect(KT_HINT, `${hint} matches the handset`).toContain(`"${heading}"`);
  }
});

test("the handset classifies both hints and nothing else", () => {
  expect(KT_MAIN).toContain("signInHint = failure.signInHint()");
  // Two arms plus the fallback; a third value would be a client inventing a state the server has no
  // word for.
  expect(KT_HINT).toMatch(/SignInHint\.AMBIGUOUS_IDENTIFIER ->[\s\S]{0,120}SignInHint\.PASSWORD_NOT_SET ->[\s\S]{0,120}SignInHint\.NONE -> null/);
});
