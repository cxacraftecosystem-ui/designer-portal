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
 *   6. **The gate is handed the last SUCCESSFUL sign-in's password, never the live box**, and shows
 *      its own box — emptied — the moment the server refuses what it was handed. The live box held a
 *      refused password through a following Google sign-in, and the gate hid itself behind it.
 *   7. **One latch, in `AuthProvider`.** `/login` and `AppShell` each kept their own, so a person
 *      who chose a password at the door was asked again on the dashboard.
 *   8. **The temporary password cannot be "replaced" with itself**, and every box stops at the
 *      server's one ceiling.
 *   9. **What a change does to the other sessions is said in one sentence on both clients** — and
 *      the gate adopts the fresh session the change answers with (in its `X-Session-Token` header)
 *      before anything re-reads the account.
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

import { ApiError } from "@/lib/api";
import {
  PASSWORD_CHANGE_PROMPT,
  PASSWORD_CHANGE_SESSIONS,
  currentPasswordRefused,
  newPasswordProblem
} from "@/lib/passwordChange";
import {
  MAX_PASSWORD_LENGTH,
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
/*
  THE FORM MOVED OUT OF `/login` ON 2026-08-31, and four assertions below moved with it.

  `FirstPasswordGate` was declared inside `app/login/page.tsx` while the door was the only place the
  flag was read. It is not any more: an administrator who resets a password through
  `PATCH /api/users/{id}` sets `mustChangePassword` on a session that is already open, and a person
  who never revisits /login never meets the door — so `AppShell` gates the whole protected tree on
  the same flag and renders the same form. Two hosts, one component, one password vocabulary.

  The assertions that follow the FORM now read this file; the ones about WHERE THE GATE IS REACHED
  FROM stay on `LOGIN`, because that is still this spec's subject. The protected host has its own:
  `e2e/protected-password-gate-unit.spec.ts`.
*/
const GATE = read("components", "FirstPasswordGate.tsx");
const SET_PASSWORD = read("app", "set-password", "page.tsx");
const SIGN_IN = read("lib", "signIn.ts");
const AUTH = read("components", "AuthProvider.tsx");
const APP_SHELL = read("components", "AppShell.tsx");

/** `/login`'s password submit, from its declaration to the page's JSX. */
const SUBMIT = (() => {
  const from = LOGIN.indexOf("async function submit(");
  return from < 0 ? "" : LOGIN.slice(from, LOGIN.indexOf("async function startOidc(", from));
})();
/** The Google Identity Services callback, from its opening line to the GIS button render. */
const GIS_CALLBACK = (() => {
  const from = LOGIN.indexOf("callback: async (response) => {");
  return from < 0 ? "" : LOGIN.slice(from, LOGIN.indexOf("renderGoogleButton();", from));
})();
/** The gate's own submit handler. */
const GATE_SUBMIT = (() => {
  const from = GATE.indexOf("async function submit(");
  return from < 0 ? "" : GATE.slice(from, GATE.indexOf("return (", from));
})();
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
  const gate = passwordRuleLine(PASSWORD_CHANGE_SESSIONS);
  const redeem = passwordRuleLine("This link works once.");
  expect(gate.startsWith(passwordRuleLine())).toBe(true);
  expect(redeem.startsWith(passwordRuleLine())).toBe(true);
  expect(passwordRuleLine()).toContain(String(MIN_PASSWORD_LENGTH));
  // The gate involves no link, so it must not mention one. And it no longer promises that other
  // devices stay signed in: since 2026-10-09 the server binds every session to the password it was
  // opened with, so the change ends every session but the fresh one it answers with.
  expect(gate.toLowerCase()).not.toContain("link");
  expect(gate).toMatch(/signed out everywhere else/);
  expect(GATE).toContain("passwordRuleLine(PASSWORD_CHANGE_SESSIONS)");
});

test("the gate adopts the session its change answers with before the host re-reads the account", () => {
  // `onDone` is the hosts' `markPasswordChanged`, whose re-read of `/me` must go out with the FRESH
  // token: the change retired the one this form was sent with. An older server answers with none
  // and retired none — nothing is adopted and `onDone` runs exactly as before.
  const succeeded = GATE_SUBMIT.slice(GATE_SUBMIT.indexOf("const fresh = await changeOwnPassword(current, next);"));
  expect(succeeded, "the success path was located").toContain("if (fresh) setToken(fresh);");
  expect(succeeded.indexOf("if (fresh) setToken(fresh);"), "adopted before the host is told").toBeLessThan(
    succeeded.indexOf("onDone();")
  );
  expect(GATE).toMatch(/import \{ setToken \} from "@\/lib\/api";/);
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
  // Regression 1. Three call sites — the password submit, the Google callback and the Microsoft/Yahoo
  // completion — and the effect is the belt rather than the brace.
  const guards = LOGIN.match(/if \(mustChangePassword\(account\)\) return;/g) ?? [];
  expect(guards.length, "the password path, the Google path and the Microsoft/Yahoo path").toBe(3);
});

test("the sign-in card offers only the ways in that exist", () => {
  // Every button on the card is a working way in: Microsoft and Yahoo are drawn from
  // `oidcProviders`, i.e. only where this site is configured for them, and Google only where its
  // client id is set. Nothing says "coming soon", and an unconfigured site shows no setup
  // instructions in place of a button.
  expect(LOGIN).toContain("oidcProviders.map((provider) =>");
  expect(LOGIN).toContain("Continue with {provider.label}");
  expect(LOGIN).not.toContain("Coming soon");
  expect(LOGIN).not.toMatch(/sign-in is coming soon/);
  expect(LOGIN).not.toContain("to enable Google sign-in");
  expect(LOGIN).toMatch(/\{googleClientId \|\| oidcProviders\.length > 0 \? \([\s\S]{0,400}?>OR<[\s\S]*?Continue with Google[\s\S]*?\) : null\}/);
  expect(SUBMIT, "the submit slice ends before the JSX").not.toContain("<form");
});

test("the redirect effect refuses to navigate while either gate stands", () => {
  expect(LOGIN).toMatch(
    /if \(user && !signingIn\.current && !held && !passwordGate\) router\.replace\("\/dashboard"\)/
  );
});

test("the gate is derived from the live account, so an already-open session meets it too", () => {
  // Not a copy of the flag taken at sign-in: a session that was open when this page loaded carries
  // the same obligation, and a stored copy is a copy that goes stale after the change lands. And no
  // page-local latch beside it any more — see the next test for where the latch went.
  expect(LOGIN).toMatch(/const passwordGate = mustChangePassword\(user\) \? user : null;/);
});

test("a completed change closes the gate through the ONE latch, shared with the protected tree", () => {
  // `changeOwnPassword` has succeeded server-side by this point; a failed best-effort re-read must
  // not ask somebody a second time for a password they have just set. This page used to keep its own
  // latch, and the dashboard it navigated to started a FRESH one reading false over a still-stale
  // account — the person was asked twice. `markPasswordChanged` clears the flag on the account in
  // `AuthProvider`, which both hosts read.
  expect(LOGIN, "no page-local latch is left").not.toMatch(/setPasswordSet|const \[passwordSet,/);
  const onDone = /onDone=\{\(\) => \{[\s\S]*?\}\}/.exec(LOGIN)?.[0] ?? "";
  expect(onDone, "the gate's completion was located").toContain("markPasswordChanged()");
  expect(onDone.indexOf("markPasswordChanged()"), "cleared BEFORE the navigation, so the dashboard reads it").toBeLessThan(
    onDone.indexOf('router.replace("/dashboard")')
  );
  // The latch itself: the account is patched where both hosts read it, and the module flag the
  // background drains read goes down with it.
  const latch = AUTH.slice(AUTH.indexOf("const markPasswordChanged = useCallback("));
  const body = latch.slice(0, latch.indexOf("}, [refreshMe]);"));
  expect(body).toMatch(/setUser\(\(current\) => \(current \? \{ \.\.\.current, mustChangePassword: false \} : current\)\)/);
  expect(body).toContain("setSessionOwesPasswordChange(false)");
});

test("a re-read sent before the change cannot put the flag back; one sent after it can", () => {
  // The race the latch exists for, made explicit: the `/me` a refused request starts is typically
  // still in flight while the new password is being typed. Its `true` is stale; a later `/me` is the
  // server's word and an administrator who raises the flag again is obeyed.
  expect(AUTH).toMatch(/const probe = \+\+probes\.current;/);
  expect(AUTH).toMatch(/changedAtProbe\.current = probes\.current;/);
  expect(AUTH).toMatch(/adopt\(probe <= changedAtProbe\.current \? \{ \.\.\.me, mustChangePassword: false \} : me\);/);
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
  expect(GATE).toContain("Sign out instead");
  const escape = /onSignOut=\{\(\) => \{[\s\S]*?\}\}/.exec(LOGIN)?.[0] ?? "";
  expect(escape, "the escape was located").toContain("logout()");
  expect(escape, "and it does not navigate into the app").not.toContain("/dashboard");
});

test("the gate is handed the password of the last SUCCESSFUL sign-in, never the live box", () => {
  // `POST /auth/change-password` requires it even for an account carrying the flag. On the ordinary
  // path the person typed it ten seconds ago; re-asking would be asking for a secret the page holds.
  // BUT NOT THE LIVE BOX: after a refused attempt it still held the wrong password, and a following
  // "Continue with Google" met a gate that hid its own "Current password" box behind that leftover
  // and sent it on every try. Android's `doorPassword` is the model, and is pinned below.
  expect(LOGIN, "the live box is never handed over").not.toContain("currentPassword={password}");
  expect(LOGIN, "the door password is").toContain("currentPassword={doorPassword}");
  // State, not a ref: the gate reads it while rendering, and `react-hooks/refs` refuses that.
  expect(LOGIN).toMatch(/const \[doorPassword, setDoorPassword\] = useState\(""\);/);
});

test("the door password is written at five moments and no others", () => {
  // Set as an attempt goes out — BEFORE the request, so the render that draws the gate already holds
  // it — and taken back when that attempt is refused.
  expect(SUBMIT, "the submit was located").toContain("await login(email, password)");
  expect(SUBMIT.indexOf("setDoorPassword(password);"), "set before the request").toBeGreaterThan(-1);
  expect(SUBMIT.indexOf("setDoorPassword(password);")).toBeLessThan(SUBMIT.indexOf("await login(email, password)"));
  const failed = /catch \(err\) \{[\s\S]*?\}/.exec(SUBMIT)?.[0] ?? "";
  expect(failed, "and emptied when the attempt is refused").toContain('setDoorPassword("")');
  // At the TOP of the Google callback — before the consent check can return — because whatever an
  // earlier password attempt left behind is not this sign-in's password.
  expect(GIS_CALLBACK, "the GIS callback was located").toContain("loginWithGoogle(");
  expect(GIS_CALLBACK.indexOf('setDoorPassword("")'), "emptied first").toBeGreaterThan(-1);
  expect(GIS_CALLBACK.indexOf('setDoorPassword("")')).toBeLessThan(GIS_CALLBACK.indexOf("if (gateNow.current.blocked)"));
  // And on both ways out of the gate.
  const escape = /onSignOut=\{\(\) => \{[\s\S]*?\}\}/.exec(LOGIN)?.[0] ?? "";
  const onDone = /onDone=\{\(\) => \{[\s\S]*?\}\}/.exec(LOGIN)?.[0] ?? "";
  expect(escape).toContain('setDoorPassword("")');
  expect(onDone).toContain('setDoorPassword("")');
  // At the top of the Microsoft/Yahoo completion too, for the Google callback's reason.
  const oidc = LOGIN.slice(LOGIN.indexOf("const callback = oidcCallback.current;"));
  expect(oidc.indexOf('setDoorPassword("")'), "emptied before the sign-in").toBeGreaterThan(-1);
  expect(oidc.indexOf('setDoorPassword("")')).toBeLessThan(oidc.indexOf("loginWithOidc("));
  // Nothing else writes it: five empties and one set.
  expect((LOGIN.match(/setDoorPassword\(/g) ?? []).length).toBe(6);
});

test("the box is asked for when the host has none, and again after any refusal", () => {
  // A hidden box holding a wrong password is a form whose only button can never succeed.
  expect(GATE).toMatch(/const askCurrent = currentPassword\.length === 0 \|\| currentRefusals > 0;/);
});

test("a refused current password reveals the box, empties it and puts the caret in it", () => {
  // The server answers a wrong current password with a 400 — not a 401, which `apiFetch` would have
  // read as a dead session, costing the person their sign-in for one typo.
  expect(currentPasswordRefused(new ApiError(400, "Current password is incorrect", { detail: "Current password is incorrect" }))).toBe(
    true
  );
  // A length rule and the guessing budget say nothing against the current password; neither does a
  // session that is genuinely over.
  expect(currentPasswordRefused(new ApiError(422, "newPassword: too long", null))).toBe(false);
  expect(currentPasswordRefused(new ApiError(429, "Too many attempts", null))).toBe(false);
  expect(currentPasswordRefused(new ApiError(401, "This session is no longer valid. Sign in again.", null))).toBe(false);
  expect(currentPasswordRefused(new TypeError("Failed to fetch"))).toBe(false);

  const refused = /if \(currentPasswordRefused\(err\)\) \{[\s\S]*?\}/.exec(GATE_SUBMIT)?.[0] ?? "";
  expect(refused, "the gate acts on it").toContain('setCurrent("")');
  expect(refused).toContain("setCurrentRefusals(");
  expect(GATE, "and moves focus to the box it has just drawn").toMatch(
    /if \(currentRefusals > 0\) currentBox\.current\?\.focus\(\);/
  );
  expect(GATE).toContain("ref={currentBox}");
});

test("the confirmation is a real second box, checked before anything is sent", () => {
  // The owner asked for "set the password on their first login, and confirm it". The server takes
  // one `newPassword` and cannot see the second box, so a mismatch it could never detect would
  // otherwise be filed as the person's choice.
  expect(GATE).toContain('id="gate-confirm-password"');
  expect(newPasswordProblem({ current: "temporary-1", next: "my-own-pass", confirm: "my-own-pazz" })).toMatch(/do not match/);
  expect(GATE_SUBMIT.indexOf("newPasswordProblem("), "checked in the submit").toBeGreaterThan(-1);
  expect(GATE_SUBMIT.indexOf("newPasswordProblem("), "before anything is sent").toBeLessThan(
    GATE_SUBMIT.indexOf("changeOwnPassword(")
  );
});

test("the gate refuses the very password it exists to retire, before sending it", () => {
  // The gate retires a secret somebody else knows. Typing that same secret into "New password" used
  // to satisfy it: the server compared nothing, cleared the flag and kept the password.
  expect(newPasswordProblem({ current: "temporary-1", next: "temporary-1", confirm: "temporary-1" })).toMatch(
    /different from your current one/
  );
  expect(newPasswordProblem({ current: "temporary-1", next: "my-own-pass", confirm: "my-own-pass" })).toBeNull();
  // Compared exactly, the way the server compares: a trailing space is a different password.
  expect(newPasswordProblem({ current: "temporary-1", next: "temporary-1 ", confirm: "temporary-1 " })).toBeNull();
});

test("every box carries the one ceiling and says what it is for", () => {
  // One maximum, the server's, on every box: a 210-character entry the next screen refuses with a 422
  // is a password typed twice for nothing.
  expect(MAX_PASSWORD_LENGTH).toBe(200);
  expect((GATE.match(/maxLength=\{MAX_PASSWORD_LENGTH\}/g) ?? []).length, "current, new and repeat").toBe(3);
  // A password manager fills the box it is told about: the current one, then the pair it should save.
  expect((GATE.match(/autoComplete="current-password"/g) ?? []).length).toBe(1);
  expect((GATE.match(/autoComplete="new-password"/g) ?? []).length).toBe(2);
});

test("the gate's sentence is neutral, and is the server's own", () => {
  // "An administrator set your password" stopped being true the moment an administrator could require
  // a change of a password its owner chose. The server refuses a gated request with this sentence.
  expect(PASSWORD_CHANGE_PROMPT).toBe("Choose a new password to continue.");
  expect(PASSWORD_CHANGE_PROMPT.toLowerCase()).not.toContain("administrator");
  expect(GATE, "the status box draws it").toMatch(/role="status"[\s\S]{0,600}?\{PASSWORD_CHANGE_PROMPT\}/);
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

test("the gate's heading, its sentence and its two refusals are the handset's, word for word", () => {
  // A designer refused on the phone opens the website next. `AppShell` has always said its heading
  // was Android's "word for word"; this is what makes that a fact rather than a comment.
  const kotlinString = (name: string) => new RegExp(`const val ${name} = "([^"]+)"`).exec(KT_COPY)?.[1];
  const heading = kotlinString("PASSWORD_GATE_HEADING");
  expect(heading, "the handset's heading was located").toBeTruthy();
  expect(APP_SHELL).toContain(`>${heading}</h1>`);
  expect(kotlinString("PASSWORD_GATE_SENTENCE")).toBe(PASSWORD_CHANGE_PROMPT);
  // The two refusals both clients make before sending — same words, so neither reads as a new rule.
  const mismatch = newPasswordProblem({ current: "a-current-one", next: "first-try-1", confirm: "first-try-2" });
  const same = newPasswordProblem({ current: "a-current-one", next: "a-current-one", confirm: "a-current-one" });
  expect(KT_COPY).toContain(`"${mismatch}"`);
  expect(KT_COPY).toContain(`"${same}"`);
});

test("what a change does to the other sessions is said in the handset's words too", () => {
  // Both gates print it under their boxes, and since 2026-10-09 it is the opposite of what both used to
  // say ("Other devices stay signed in"): a change ends every session but the one it hands back. A
  // designer who changes the password on the phone and opens the website next must read one rule.
  const kotlinString = (name: string) => new RegExp(`const val ${name} = "([^"]+)"`).exec(KT_COPY)?.[1];
  expect(kotlinString("PASSWORD_CHANGE_SESSIONS"), "the handset's constant was located").toBeTruthy();
  expect(kotlinString("PASSWORD_CHANGE_SESSIONS")).toBe(PASSWORD_CHANGE_SESSIONS);
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
