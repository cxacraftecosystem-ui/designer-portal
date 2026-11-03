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
const GATE = read("components", "FirstPasswordGate.tsx");
const SET_PASSWORD = read("app", "set-password", "page.tsx");
const SIGN_IN = read("lib", "signIn.ts");
const AUTH = read("components", "AuthProvider.tsx");
const APP_SHELL = read("components", "AppShell.tsx");

const SUBMIT = (() => {
  const from = LOGIN.indexOf("async function submit(");
  return from < 0 ? "" : LOGIN.slice(from, LOGIN.indexOf("async function startOidc(", from));
})();
const GIS_CALLBACK = (() => {
  const from = LOGIN.indexOf("async (credential: string) => {");
  return from < 0 ? "" : LOGIN.slice(from, LOGIN.indexOf("const onGoogleLoadError", from));
})();
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

test("an account whose password an administrator typed is gated", () => {
  expect(mustChangePassword(account(true))).toBe(true);
});

test("an account that chose its own password is not", () => {
  expect(mustChangePassword(account(false))).toBe(false);
});

test("a server older than the column blocks nobody", () => {
  expect(mustChangePassword(account(undefined))).toBe(false);
  expect(mustChangePassword(null)).toBe(false);
  expect(mustChangePassword(undefined)).toBe(false);
});

test("the length floor is declared once and is the server's", () => {
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
  expect(gate.toLowerCase()).not.toContain("link");
  expect(gate).toMatch(/signed out everywhere else/);
  expect(GATE).toContain("passwordRuleLine(PASSWORD_CHANGE_SESSIONS)");
});

test("the gate waits for the change (and its renewed cookie) before the host re-reads the account", () => {
  const succeeded = GATE_SUBMIT.slice(GATE_SUBMIT.indexOf("await changeOwnPassword(current, next);"));
  expect(succeeded, "the success path was located").toContain("onDone();");
  expect(GATE, "no script touches the session token").not.toMatch(/setToken|getToken|localStorage/);
});

test("the Android handset carries the same floor and the same first clause", () => {
  expect(KT_COPY).toContain("const val MIN_PASSWORD_LENGTH = 8");
  expect(KT_COPY).toContain('"At least $MIN_PASSWORD_LENGTH characters."');
});

test("both sign-in paths check the account they were handed, not the effect", () => {
  const guards = LOGIN.match(/if \(mustChangePassword\(account\)\) return;/g) ?? [];
  expect(guards.length, "the password, Google and Microsoft/Yahoo paths, and the second step").toBe(4);
});

test("the sign-in card offers only the ways in that exist", () => {
  expect(LOGIN).toContain("oidcProviders.map((provider) =>");
  expect(LOGIN).toContain("Continue with {provider.label}");
  expect(LOGIN).not.toContain("Coming soon");
  expect(LOGIN).not.toMatch(/sign-in is coming soon/);
  expect(LOGIN).not.toContain("to enable Google sign-in");
  expect(LOGIN).toMatch(/\{googleClientId \|\| oidcProviders\.length > 0 \? \([\s\S]{0,400}?>OR<[\s\S]*?<GoogleSignInButton[\s\S]*?\) : null\}/);
  expect(SUBMIT, "the submit slice ends before the JSX").not.toContain("<form");
});

test("the redirect effect refuses to navigate while either gate stands", () => {
  expect(LOGIN).toMatch(
    /if \(user && !signingIn\.current && !held && !passwordGate && !challenge\) router\.replace\("\/dashboard"\)/
  );
});

test("the gate is derived from the live account, so an already-open session meets it too", () => {
  expect(LOGIN).toMatch(/const passwordGate = mustChangePassword\(user\) \? user : null;/);
});

test("a completed change closes the gate through the ONE latch, shared with the protected tree", () => {
  expect(LOGIN, "no page-local latch is left").not.toMatch(/setPasswordSet|const \[passwordSet,/);
  const onDone = /onDone=\{\(\) => \{[\s\S]*?\}\}/.exec(LOGIN)?.[0] ?? "";
  expect(onDone, "the gate's completion was located").toContain("markPasswordChanged()");
  expect(onDone.indexOf("markPasswordChanged()"), "cleared BEFORE the navigation, so the dashboard reads it").toBeLessThan(
    onDone.indexOf('router.replace("/dashboard")')
  );
  const latch = AUTH.slice(AUTH.indexOf("const markPasswordChanged = useCallback("));
  const body = latch.slice(0, latch.indexOf("}, [refreshMe]);"));
  expect(body).toMatch(/setUser\(\(current\) => \(current \? \{ \.\.\.current, mustChangePassword: false \} : current\)\)/);
  expect(body).toContain("setSessionOwesPasswordChange(false)");
});

test("a re-read sent before the change cannot put the flag back; one sent after it can", () => {
  expect(AUTH).toMatch(/const probe = \+\+probes\.current;/);
  expect(AUTH).toMatch(/changedAtProbe\.current = probes\.current;/);
  expect(AUTH).toMatch(/adopt\(probe <= changedAtProbe\.current \? \{ \.\.\.me, mustChangePassword: false \} : me\);/);
});

test("the two gates are one chain, consent first", () => {
  expect(LOGIN).toMatch(
    /\{held \? \([\s\S]{0,200}?<StandingRefusal[\s\S]{0,200}?\) : passwordGate \? \([\s\S]{0,200}?<FirstPasswordGate/
  );
});

test("the sign-in controls are replaced, not left live underneath", () => {
  const chain = /\{held \? \([\s\S]*?\) : passwordGate \? \([\s\S]*?\) : \(/.exec(LOGIN)?.[0] ?? "";
  expect(chain, "the chain was located").toContain("<FirstPasswordGate");
  expect(chain, "and no sign-in form is inside either gate branch").not.toContain("<form onSubmit={submit}");
});

test("the gate has a way out that is not a way in", () => {
  expect(GATE).toContain("Sign out instead");
  const escape = /onSignOut=\{\(\) => \{[\s\S]*?\}\}/.exec(LOGIN)?.[0] ?? "";
  expect(escape, "the escape was located").toContain("logout()");
  expect(escape, "and it does not navigate into the app").not.toContain("/dashboard");
});

test("the gate is handed the password of the last SUCCESSFUL sign-in, never the live box", () => {
  expect(LOGIN, "the live box is never handed over").not.toContain("currentPassword={password}");
  expect(LOGIN, "the door password is").toContain("currentPassword={doorPassword}");
  expect(LOGIN).toMatch(/const \[doorPassword, setDoorPassword\] = useState\(""\);/);
});

test("the door password is written at five moments and no others", () => {
  expect(SUBMIT, "the submit was located").toContain("await login(email, password)");
  expect(SUBMIT.indexOf("setDoorPassword(password);"), "set before the request").toBeGreaterThan(-1);
  expect(SUBMIT.indexOf("setDoorPassword(password);")).toBeLessThan(SUBMIT.indexOf("await login(email, password)"));
  const failed = /catch \(err\) \{[\s\S]*?\}/.exec(SUBMIT)?.[0] ?? "";
  expect(failed, "and emptied when the attempt is refused").toContain('setDoorPassword("")');
  expect(GIS_CALLBACK, "the GIS callback was located").toContain("loginWithGoogle(");
  expect(GIS_CALLBACK.indexOf('setDoorPassword("")'), "emptied first").toBeGreaterThan(-1);
  expect(GIS_CALLBACK.indexOf('setDoorPassword("")')).toBeLessThan(GIS_CALLBACK.indexOf("if (gateNow.current.blocked)"));
  const escape = /onSignOut=\{\(\) => \{[\s\S]*?\}\}/.exec(LOGIN)?.[0] ?? "";
  const onDone = /onDone=\{\(\) => \{[\s\S]*?\}\}/.exec(LOGIN)?.[0] ?? "";
  expect(escape).toContain('setDoorPassword("")');
  expect(onDone).toContain('setDoorPassword("")');
  const oidc = LOGIN.slice(LOGIN.indexOf("const callback = oidcCallback.current;"));
  expect(oidc.indexOf('setDoorPassword("")'), "emptied before the sign-in").toBeGreaterThan(-1);
  expect(oidc.indexOf('setDoorPassword("")')).toBeLessThan(oidc.indexOf("loginWithOidc("));
  const cancelSecondStep = /onCancel=\{\(\) => \{[\s\S]*?\}\}/.exec(LOGIN)?.[0] ?? "";
  expect(cancelSecondStep).toContain('setDoorPassword("")');
  expect((LOGIN.match(/setDoorPassword\(/g) ?? []).length).toBe(7);
});

test("the box is asked for when the host has none, and again after any refusal", () => {
  expect(GATE).toMatch(/const askCurrent = currentPassword\.length === 0 \|\| currentRefusals > 0;/);
});

test("a refused current password reveals the box, empties it and puts the caret in it", () => {
  expect(currentPasswordRefused(new ApiError(400, "Current password is incorrect", { detail: "Current password is incorrect" }))).toBe(
    true
  );
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
  expect(GATE).toContain('id="gate-confirm-password"');
  expect(newPasswordProblem({ current: "temporary-1", next: "my-own-pass", confirm: "my-own-pazz" })).toMatch(/do not match/);
  expect(GATE_SUBMIT.indexOf("newPasswordProblem("), "checked in the submit").toBeGreaterThan(-1);
  expect(GATE_SUBMIT.indexOf("newPasswordProblem("), "before anything is sent").toBeLessThan(
    GATE_SUBMIT.indexOf("changeOwnPassword(")
  );
});

test("the gate refuses the very password it exists to retire, before sending it", () => {
  expect(newPasswordProblem({ current: "temporary-1", next: "temporary-1", confirm: "temporary-1" })).toMatch(
    /different from your current one/
  );
  expect(newPasswordProblem({ current: "temporary-1", next: "my-own-pass", confirm: "my-own-pass" })).toBeNull();
  expect(newPasswordProblem({ current: "temporary-1", next: "temporary-1 ", confirm: "temporary-1 " })).toBeNull();
});

test("every box carries the one ceiling and says what it is for", () => {
  expect(MAX_PASSWORD_LENGTH).toBe(200);
  expect((GATE.match(/maxLength=\{MAX_PASSWORD_LENGTH\}/g) ?? []).length, "current, new and repeat").toBe(3);
  expect((GATE.match(/autoComplete="current-password"/g) ?? []).length).toBe(1);
  expect((GATE.match(/autoComplete="new-password"/g) ?? []).length).toBe(2);
});

test("the gate's sentence is neutral, and is the server's own", () => {
  expect(PASSWORD_CHANGE_PROMPT).toBe("Choose a new password to continue.");
  expect(PASSWORD_CHANGE_PROMPT.toLowerCase()).not.toContain("administrator");
  expect(GATE, "the status box draws it").toMatch(/role="status"[\s\S]{0,600}?\{PASSWORD_CHANGE_PROMPT\}/);
});

test("the handset gates on the same boolean, read the same way", () => {
  expect(KT_COPY).toContain("fun mustChangePasswordBlocks(user: UserDto?): Boolean = user?.mustChangePassword == true");
});

test("the handset's gate is a when arm between sign-in and the dashboard, not a dialog", () => {
  const routing = /usageConsentBlocks\(user\) -> UsageConsentGateScreen\([\s\S]*?else -> (?:CompositionLocalProvider\([\s\S]*?\) \{ )?HomeScreen\(/.exec(KT_MAIN)?.[0] ?? "";
  expect(routing, "the routing block was located").toContain("mustChangePasswordBlocks(user) -> PasswordGateScreen(");
  expect(
    routing.indexOf("usageConsentBlocks(user)"),
    "consent is asked first"
  ).toBeLessThan(routing.indexOf("mustChangePasswordBlocks(user)"));
});

test("the gate's heading, its sentence and its two refusals are the handset's, word for word", () => {
  const kotlinString = (name: string) => new RegExp(`const val ${name} = "([^"]+)"`).exec(KT_COPY)?.[1];
  const heading = kotlinString("PASSWORD_GATE_HEADING");
  expect(heading, "the handset's heading was located").toBeTruthy();
  expect(APP_SHELL).toContain(`>${heading}</h1>`);
  expect(kotlinString("PASSWORD_GATE_SENTENCE")).toBe(PASSWORD_CHANGE_PROMPT);
  const mismatch = newPasswordProblem({ current: "a-current-one", next: "first-try-1", confirm: "first-try-2" });
  const same = newPasswordProblem({ current: "a-current-one", next: "a-current-one", confirm: "a-current-one" });
  expect(KT_COPY).toContain(`"${mismatch}"`);
  expect(KT_COPY).toContain(`"${same}"`);
});

test("what a change does to the other sessions is said in the handset's words too", () => {
  const kotlinString = (name: string) => new RegExp(`const val ${name} = "([^"]+)"`).exec(KT_COPY)?.[1];
  expect(kotlinString("PASSWORD_CHANGE_SESSIONS"), "the handset's constant was located").toBeTruthy();
  expect(kotlinString("PASSWORD_CHANGE_SESSIONS")).toBe(PASSWORD_CHANGE_SESSIONS);
});

test("the handset forgets the door password on every exit", () => {
  const clears = KT_MAIN.match(/doorPassword = ""/g) ?? [];
  expect(clears.length, "failed sign-in, Google path, gate satisfied, and both sign-outs").toBeGreaterThanOrEqual(5);
});

test("the hint is classified from the header and never from the body", () => {
  expect(SIGN_IN).toContain('export const SIGN_IN_HINT_HEADER = "x-sign-in-hint"');
  expect(KT_REPO, "the handset records the same rule beside its own classifier").toContain(
    "THE REFUSAL BODY CARRIES EXACTLY ONE KEY"
  );
  expect(LOGIN, "the web reads it off the response headers").toContain("err.headers?.get(SIGN_IN_HINT_HEADER)");
  expect(KT_MAIN, "the handset reads it off the response headers").toContain("failure.signInHint()");
});

test("an unrecognised or absent hint draws no panel at all", () => {
  expect(signInHintOf(null)).toBeNull();
  expect(signInHintOf("SOMETHING_NEW")).toBeNull();
  expect(signInHintHeading(null)).toBeNull();
});

test("both clients say the same two headings, word for word", () => {
  for (const hint of ["AMBIGUOUS_IDENTIFIER", "PASSWORD_NOT_SET"] as const) {
    const heading = signInHintHeading(hint);
    expect(heading, hint).toBeTruthy();
    expect(KT_HINT, `${hint} matches the handset`).toContain(`"${heading}"`);
  }
});

test("the handset classifies both hints and nothing else", () => {
  expect(KT_MAIN).toContain("signInHint = failure.signInHint()");
  expect(KT_HINT).toMatch(/SignInHint\.AMBIGUOUS_IDENTIFIER ->[\s\S]{0,120}SignInHint\.PASSWORD_NOT_SET ->[\s\S]{0,120}SignInHint\.NONE -> null/);
});
