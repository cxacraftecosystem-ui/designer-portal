/**
 * THE FIRST-LOGIN PASSWORD GATE, HELD ABOVE THE APP AND NOT ONLY AT THE DOOR.
 *
 * ── THE GAP THIS PINS SHUT ──────────────────────────────────────────────────────────────────────
 *
 * `FirstPasswordGate` landed on 2026-08-31 and fired in exactly one place: `app/login/page.tsx`. So
 * the obligation was enforced against people ARRIVING and against nobody already inside. An
 * administrator who reset somebody's password through `PATCH /api/users/{id}` set
 * `mustChangePassword` on an account whose browser tab was open, and that tab went on working —
 * indefinitely, because nothing in the protected tree ever read the flag and a session that never
 * revisits /login never meets the door.
 *
 * The server REPORTS and deliberately never refuses (see `mustChangePassword` in `lib/signIn.ts`:
 * `POST /auth/change-password` needs a bearer token, so a 403 at the door would be a demand the
 * account could never satisfy). The client is therefore the whole of the enforcement, and half of
 * the client was not enforcing.
 *
 * ── WHY THESE ARE THE THINGS PINNED ─────────────────────────────────────────────────────────────
 *
 * Every regression below renders perfectly, which is why none of them is caught by looking:
 *
 *   1. **A gate that renders under the chrome is not a gate.** The island offers twenty
 *      destinations, an admin-view toggle and a sign-out; a lock drawn inside `<main>` leaves all of
 *      them live. The gate is an early return ABOVE `DynamicIslandNav`, above `ROUTE_GUARDS` and
 *      above admin view, so the order of those returns is the feature.
 *   2. **A lock must not decide before the answer is known** (§7.10). The flag rides on the same
 *      `/me` payload as the account, so "known" is exactly `!loading` — and `AppShell` already holds
 *      that frame. Move the branch above it and a flagged account sees the app for one commit, or an
 *      unflagged one sees the lock.
 *   3. **One password vocabulary, not two.** `/login` and the protected tree render the SAME
 *      component. A second form beside a second "do not match" sentence is how the two screens come
 *      to enforce different rules on the day the server's change.
 *   4. **The screen that clears the block must stay reachable.** `/set-password` is the only route a
 *      person who does NOT know their temporary password can use, and it lives outside
 *      `app/(protected)/` — under it, this gate would have locked the one door that opens it.
 *   5. **A completed change must close the gate even if the `/me` proving it never lands**, or a
 *      dropped connection re-locks somebody the second after they complied.
 *
 * Everything here is a source assertion or a pure function, for this repository's usual reason:
 * there is no React renderer in devDependencies, so a judgement inside JSX is only ever exercised by
 * somebody looking at a screen.
 *
 * ⚠ EVERY SOURCE ASSERTION IS LINE-ENDING AGNOSTIC. `\s` and `[\s\S]` throughout; never `\n`.
 */

import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { mustChangePassword } from "@/lib/signIn";
import type { User } from "@/lib/types";

const at = (...parts: string[]) => join(__dirname, "..", ...parts);
const read = (...parts: string[]) => readFileSync(at(...parts), "utf8");

const APP_SHELL = read("components", "AppShell.tsx");
const GATE = read("components", "FirstPasswordGate.tsx");
const LOGIN = read("app", "login", "page.tsx");
const LAYOUT = read("app", "(protected)", "layout.tsx");
const SET_PASSWORD = read("app", "set-password", "page.tsx");

/** Where the gate's own branch begins in `AppShell`. Every ordering test below is measured off it. */
const gateAt = APP_SHELL.indexOf("if (!passwordSet && mustChangePassword(user))");

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The gate exists in the protected tree at all
 * ──────────────────────────────────────────────────────────────────────────── */

test("the protected chrome reads the flag", () => {
  // The gap in one line: before this, `mustChangePassword` had four callers and all four were in
  // `app/login/page.tsx`.
  expect(APP_SHELL).toMatch(/import \{ mustChangePassword \} from "@\/lib\/signIn"/);
  expect(gateAt, "the gate branch was located").toBeGreaterThan(-1);
});

test("it reads the flag through the shared function and never off the field", () => {
  // `mustChangePassword` exists to give the ABSENT field its third answer: a deployment older than
  // the column is neither "must" nor "need not". `user.mustChangePassword` in a condition here would
  // be `undefined` — falsy today, and one `!== false` away from holding every account on such a
  // deployment at a screen whose only working control signs them out.
  expect(APP_SHELL).not.toMatch(/user\.mustChangePassword/);
});

test("it is route-independent, so every protected page enforces it", () => {
  // The whole point of moving it above the app: a gate that consulted `pathname` would be a gate
  // with a list of routes somebody has to remember to extend.
  const branch = APP_SHELL.slice(gateAt, gateAt + 400);
  expect(branch).not.toContain("pathname");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. Above the chrome — the ordering IS the feature
 * ──────────────────────────────────────────────────────────────────────────── */

test("the gate is an early return, not a branch inside the page frame", () => {
  // Regression 1, first half. A `return` out of the component is the only shape that can be ABOVE
  // the chrome; a fourth arm added to the `blocked → settling → hidden → children` chain at the
  // bottom of this file would render the lock INSIDE `motion.main`, under a live island.
  const branch = APP_SHELL.slice(gateAt, gateAt + 700);
  expect(branch).toContain("return (");
  expect(branch).toContain("<FirstPasswordLocked");
});

test("and the surface it returns carries no app chrome at all", () => {
  // Regression 1, second half, and this is the assertion that actually bites: the island alone
  // offers twenty destinations, the admin-view toggle and sign-out, so a lock rendered anywhere that
  // still draws it is a picture of a gate. Nor may the surface fall through to `children` — that
  // would be the page it exists to withhold.
  const from = APP_SHELL.indexOf("function FirstPasswordLocked(");
  expect(from, "the surface was located").toBeGreaterThan(-1);
  const surface = APP_SHELL.slice(from, APP_SHELL.indexOf("/**", from));
  expect(surface).not.toContain("DynamicIslandNav");
  expect(surface).not.toContain("children");
  expect(surface).not.toContain("motion.main");
  expect(surface, "and it does render the shared form").toContain("<FirstPasswordGate");
});

test("above the route guards and above admin view, not beside them", () => {
  // Both of those answer "may this person open THIS route". The password gate answers "may this
  // person use the product at all", so it is the outer question and is asked first — otherwise an
  // account that fails a route guard gets the honest permission copy and full chrome while still
  // holding a password an administrator typed.
  //
  // Measured against where each decision is TAKEN, never against the name: `adminViewResolved` is
  // destructured at the top of the component and the prose above the gate discusses it, so an
  // `indexOf` on the bare identifier answers about neither.
  const guardAt = APP_SHELL.indexOf("const guard = routeGuardFor(pathname);");
  const adminAt = APP_SHELL.indexOf("const chromeSettling =");
  expect(guardAt, "the route guard decision was located").toBeGreaterThan(-1);
  expect(adminAt, "the admin-view decision was located").toBeGreaterThan(-1);
  expect(gateAt).toBeLessThan(guardAt);
  expect(gateAt).toBeLessThan(adminAt);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. Settling — a lock holds the frame until the answer is known (§7.10)
 * ──────────────────────────────────────────────────────────────────────────── */

test("nothing is decided while /me is still in flight", () => {
  // Regression 2. `AppShell` already owns the one "Opening the repository…" frame; putting the gate
  // BELOW it means neither the app nor the lock can be painted before the answer exists. There is no
  // second, later-settling source here the way `adminViewResolved` is a second source for admin
  // view: the flag arrives on the same object as the account.
  const loadingAt = APP_SHELL.indexOf("if (loading) {");
  expect(loadingAt, "the loading frame was located").toBeGreaterThan(-1);
  expect(loadingAt).toBeLessThan(gateAt);
});

test("and not for a visitor who is not signed in at all", () => {
  // `mustChangePassword(null)` is false, so this is belt and brace — but the redirect to /login must
  // still win, or somebody with a dead token meets a password form instead of a sign-in card.
  const signedOutAt = APP_SHELL.indexOf("if (!user) return null;");
  expect(signedOutAt, "the signed-out return was located").toBeGreaterThan(-1);
  expect(signedOutAt).toBeLessThan(gateAt);
  expect(mustChangePassword(null)).toBe(false);
  expect(mustChangePassword({ id: "u1", role: "DESIGNER" } as User)).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. One vocabulary: both hosts render the same form
 * ──────────────────────────────────────────────────────────────────────────── */

test("the form is a component both screens import, not a copy in each", () => {
  // Regression 3. It was declared inside `app/login/page.tsx` while that was the only host.
  expect(GATE).toMatch(/export function FirstPasswordGate\(/);
  expect(LOGIN).toMatch(/import \{ FirstPasswordGate \} from "@\/components\/FirstPasswordGate"/);
  expect(APP_SHELL).toMatch(/import \{ FirstPasswordGate \} from "@\/components\/FirstPasswordGate"/);
  expect(LOGIN, "and no local declaration is left behind").not.toMatch(/function FirstPasswordGate\(/);
});

test("the protected host mints no password vocabulary of its own", () => {
  // No second floor, no second rule line, no second call to the route, and no second form. If this
  // host ever needs one of them, `FirstPasswordGate` is the thing to change.
  //
  // Asserted against what the file IMPORTS and RENDERS rather than against the strings, because the
  // argument for the single vocabulary is written in prose a few lines above the branch and a bare
  // `toContain` would be reading that argument as the defect it warns about.
  const imports = /import \{[^}]*\} from "@\/lib\/signIn";/.exec(APP_SHELL)?.[0] ?? "";
  expect(imports, "the signIn import was located").toContain("mustChangePassword");
  expect(imports, "and nothing else from it").not.toContain("changeOwnPassword");
  expect(imports).not.toContain("MIN_PASSWORD_LENGTH");
  expect(imports).not.toContain("passwordRuleLine");
  expect(APP_SHELL, "the host renders no password boxes of its own").not.toContain('type="password"');
});

test("the protected host asks for the current password, because it never saw one", () => {
  // `POST /auth/change-password` requires it even for an account carrying the flag. /login can hand
  // over what was typed at the door seconds ago; a session that was already open cannot, which is
  // exactly Android's "a session that was already open when the app was launched" case.
  expect(APP_SHELL).toMatch(/currentPassword=""/);
  expect(GATE).toMatch(/const askCurrent = currentPassword\.length === 0;/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The escapes — and the one door this must never lock
 * ──────────────────────────────────────────────────────────────────────────── */

test("the link-redemption screen is outside the protected tree", () => {
  // Regression 4, and it is the one failure that would be permanent: somebody whose password an
  // administrator reset WITHOUT telling them cannot satisfy the form — they do not know the current
  // password — so the link is their only route. Under `app/(protected)/` this gate would render over
  // it and the account would be locked out of the product with no way back in.
  expect(existsSync(at("app", "set-password", "page.tsx")), "/set-password is public").toBe(true);
  expect(existsSync(at("app", "(protected)", "set-password")), "and is not under the gate").toBe(false);
  // It renders its own frame, so it never mounts the chrome the gate lives in. Asserted on the
  // import and not on the word: that page's header explains at length that there is no `AppShell`
  // here, and a `toContain` would fail on the explanation.
  expect(SET_PASSWORD).not.toMatch(/import \{[^}]*AppShell[^}]*\}/);
  expect(LAYOUT, "which is mounted by the protected layout and nowhere else").toContain("<AppShell>");
});

test("the gate carries a way out that is not a way in", () => {
  // `UsageConsentGateScreen`'s escape, for its reason: a person who cannot complete this must not be
  // held on one screen whose controls all do nothing. It signs out — it does not continue.
  const escape = /onSignOut=\{\(\) => \{[\s\S]*?\}\}/.exec(APP_SHELL.slice(gateAt))?.[0] ?? "";
  expect(escape, "the escape was located").toContain("logout()");
  expect(escape, "and it does not navigate into the app").not.toContain("/dashboard");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. Closing the gate
 * ──────────────────────────────────────────────────────────────────────────── */

test("a completed change closes the gate even if the /me that would prove it never lands", () => {
  // Regression 5, and the same latch `/login` carries. `changeOwnPassword` has already succeeded
  // server-side by this point; folding a failed best-effort re-read into the same outcome would
  // re-lock somebody the second after they complied and tell them the password was wrong.
  expect(APP_SHELL).toContain("setPasswordSet(true)");
  expect(APP_SHELL.slice(gateAt)).toMatch(/refreshMe\(\)\.catch\(\(\) => undefined\)/);
});

test("the latch is state above the branch, not a copy of the flag", () => {
  // A stored copy of `mustChangePassword` would go stale in the other direction: an administrator
  // who sets the flag on a session that is already open would be ignored until a reload.
  expect(APP_SHELL).toMatch(/const \[passwordSet, setPasswordSet\] = useState\(false\);/);
});
