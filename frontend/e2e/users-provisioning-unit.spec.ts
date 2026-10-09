import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  ACCOUNT_STATUS_LABEL,
  DEFAULT_CREATE_ROLE,
  NO_PREFILL,
  STANDING_LABEL,
  accountStatus,
  createFormPrefill,
  createFormRole,
  createdPanelLinkNote,
  createdPanelOffersLink,
  createsPeerAccount,
  newAccountBody,
  passwordAccountHref,
  passwordFrom,
  passwordHasEdgeSpace,
  provisionerTierPhrase,
  rowOffers,
  rowStanding
} from "@/app/(protected)/users/accountAdmin";
import { decideAccessRequest, promotionHeldSentence } from "@/lib/accessRoster";
import {
  ACCOUNT_PROVISIONER_ROLES,
  ROLES_BY_RANK,
  ROLE_RANK,
  assignableRoles,
  canProvisionAccounts,
  isAdmin,
  provisionableRoles,
  roleLabel
} from "@/lib/permissions";
import {
  MAX_PASSWORD_LENGTH,
  createPasswordAccount,
  requirePasswordChange,
  setTemporaryPassword
} from "@/lib/signIn";
import type { User, UserRole } from "@/lib/types";

/**
 * ACCOUNT PROVISIONING ON THE WEB — the owner's ruling of 2026-10-09.
 *
 * MINISTRY_ADMIN, ADMIN and MASTER_ADMIN create password accounts, require a new password at the
 * first or the next sign-in, set temporary passwords, issue password links and correct names and
 * addresses — on accounts they manage. Deleting accounts and granting capability flags stay with the
 * admins, and `isAdmin` stays exactly {MASTER_ADMIN, ADMIN}.
 *
 * Three halves, because the page is three kinds of thing:
 *   1. the PREDICATES in lib/permissions.ts, called directly;
 *   2. the per-row JUDGEMENTS in app/(protected)/users/accountAdmin.ts, called directly — there is no
 *      React renderer in devDependencies, which is why they live outside the JSX at all;
 *   3. what only the SOURCE can show (an `autoComplete`, a `maxLength`, a password read without
 *      trimming), read as text with comments stripped and line-ending-agnostic patterns.
 * And the wire shapes of the three new `lib/signIn.ts` helpers, through the real `apiFetch` over a
 * stubbed `fetch`.
 *
 * FOUR TIERS ARE NAMED HERE AND NO MORE, ON PURPOSE. `backend/tests/test_role_ladder_parity.py` sweeps
 * the client trees for files naming five or more and demands each be registered as a mirror of the
 * ladder; this file asserts predicates rather than mirroring anything, so every other tier it needs
 * is DERIVED from `ROLES_BY_RANK` by rank. The per-tier table lives in
 * `e2e/directorate-tiers-unit.spec.ts`, which is a registered mirror.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
/** Block comments (JSX ones included) and whole-line `//` comments: prose may say anything. */
const withoutComments = (source: string) => source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");

const PAGE = withoutComments(read("app", "(protected)", "users", "page.tsx"));
const DIALOGS = withoutComments(read("app", "(protected)", "users", "AccountDialogs.tsx"));
const ACCESS_PAGE = withoutComments(read("app", "(protected)", "admin", "access", "page.tsx"));

let next = 0;
const account = (role: UserRole, extra: Partial<User> = {}): User =>
  ({
    id: `u-${role}-${next++}`,
    email: `${role.toLowerCase()}@example.org`,
    name: roleLabel(role),
    role,
    authProvider: "LOCAL",
    passwordSetAt: "2026-09-01T00:00:00.000Z",
    firstLoginAt: "2026-09-02T00:00:00.000Z",
    mustChangePassword: false,
    ...extra
  }) as User;

/** Every tier strictly below the lowest provisioner, derived — this is where AD, RD and Professor sit. */
const BELOW_PROVISIONERS = ROLES_BY_RANK.filter((role) => ROLE_RANK[role] < ROLE_RANK.MINISTRY_ADMIN);
/** The highest tier below Professor — a target an admin's capability boxes mean something for. */
const BELOW_PROFESSOR = ROLES_BY_RANK.find((role) => ROLE_RANK[role] < ROLE_RANK.PROFESSOR) as UserRole;

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The predicates
 * ──────────────────────────────────────────────────────────────────────────── */

test("the three provisioner tiers provision, and the ministry admin is still not an admin", () => {
  expect([...ACCOUNT_PROVISIONER_ROLES].sort()).toEqual(["ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"]);
  for (const role of ACCOUNT_PROVISIONER_ROLES) {
    expect(canProvisionAccounts(account(role)), role).toBe(true);
  }
  // THE CELL TO READ TWICE. Provisioning is a set BESIDE `isAdmin`, not a widening of it.
  expect(isAdmin(account("MINISTRY_ADMIN"))).toBe(false);
  expect(canProvisionAccounts(null)).toBe(false);
  expect(canProvisionAccounts(undefined)).toBe(false);
});

test("nobody below the ministry admin provisions — the directorate posts and Professor included", () => {
  expect(BELOW_PROVISIONERS.length).toBeGreaterThanOrEqual(8);
  for (const role of BELOW_PROVISIONERS) {
    expect(canProvisionAccounts(account(role)), role).toBe(false);
    expect(provisionableRoles(account(role)), role).toEqual([]);
  }
});

test("the creation ceiling is inclusive of one's own tier, exactly as assert_role rules", () => {
  for (const role of ACCOUNT_PROVISIONER_ROLES) {
    const actor = account(role);
    const offered = provisionableRoles(actor);
    // At or below one's own rank, and one's own tier included.
    expect(offered, role).toContain(role);
    for (const option of offered) expect(ROLE_RANK[option], `${role} offers ${option}`).toBeLessThanOrEqual(ROLE_RANK[role]);
    // Never more than the promotion picker would let the same account hand out a moment later.
    for (const option of offered) expect(assignableRoles(actor), role).toContain(option);
    // Highest first: the picker's order is the ladder's.
    expect(offered).toEqual(ROLES_BY_RANK.filter((option) => offered.includes(option)));
  }
  expect(provisionableRoles(account("MINISTRY_ADMIN"))).not.toContain("ADMIN");
  expect(provisionableRoles(account("ADMIN"))).not.toContain("MASTER_ADMIN");
  expect(provisionableRoles(account("MASTER_ADMIN"))).toContain("MASTER_ADMIN");
});

test("the sentence naming who provisions is derived from the set, lowest tier first", () => {
  const labels = [...ROLES_BY_RANK].reverse().filter((role) => ACCOUNT_PROVISIONER_ROLES.includes(role)).map(roleLabel);
  const phrase = provisionerTierPhrase();
  for (const label of labels) expect(phrase).toContain(label);
  expect(phrase.indexOf(roleLabel("MINISTRY_ADMIN"))).toBe(0);
  expect(phrase).toMatch(/ and [^,]+$/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. One row
 * ──────────────────────────────────────────────────────────────────────────── */

const MINISTRY_POWERS = { provision: true, admin: false };
const ADMIN_POWERS = { provision: true, admin: true };
const PROMOTER_POWERS = { provision: false, admin: false };

test("a ministry admin gets the password actions on an account below it, and no Delete and no grant", () => {
  const ministry = account("MINISTRY_ADMIN");
  const offers = rowOffers(ministry, account(BELOW_PROFESSOR), MINISTRY_POWERS);
  expect(offers.standing).toBe("MANAGED");
  expect(offers.changeRole).toBe(true);
  expect(offers.editDetails).toBe(true);
  expect(offers.passwordLink).toBe(true);
  expect(offers.requirePasswordChange).toBe(true);
  expect(offers.temporaryPassword).toBe(true);
  expect(offers.remove, "deleting stays an admin's").toBe(false);
  expect(offers.grants, "granting a capability stays an admin's").toBe(false);
});

test("an admin adds Delete, and the capability boxes only below Professor", () => {
  const admin = account("ADMIN");
  const below = rowOffers(admin, account(BELOW_PROFESSOR), ADMIN_POWERS);
  expect(below.remove).toBe(true);
  expect(below.grants).toBe(true);
  const professor = rowOffers(admin, account("PROFESSOR"), ADMIN_POWERS);
  expect(professor.remove).toBe(true);
  expect(professor.grants, "Professor and above hold every capability already").toBe(false);
});

test("the tiers that only promote get the role picker and nothing else", () => {
  const offers = rowOffers(account("PROFESSOR"), account(BELOW_PROFESSOR), PROMOTER_POWERS);
  expect(offers.changeRole).toBe(true);
  for (const key of ["editDetails", "passwordLink", "requirePasswordChange", "temporaryPassword", "remove", "grants"] as const) {
    expect(offers[key], key).toBe(false);
  }
});

test("the password link follows HAVING a password, never authProvider", () => {
  const ministry = account("MINISTRY_ADMIN");
  // A password account once signed into with Google: the old rule hid the link from exactly this row.
  const flipped = account(BELOW_PROFESSOR, { authProvider: "GOOGLE" });
  expect(rowOffers(ministry, flipped, MINISTRY_POWERS).passwordLink).toBe(true);
  // A Google-only account: no link (the server answers 422), no forced change (nothing to replace) —
  // and still a temporary password, because giving it one is a decision the dialog names.
  const googleOnly = account(BELOW_PROFESSOR, { authProvider: "GOOGLE", passwordSetAt: null });
  const offers = rowOffers(ministry, googleOnly, MINISTRY_POWERS);
  expect(offers.passwordLink).toBe(false);
  expect(offers.requirePasswordChange).toBe(false);
  expect(offers.temporaryPassword).toBe(true);
});

test("a change already required is not offered again", () => {
  const offers = rowOffers(account("ADMIN"), account(BELOW_PROFESSOR, { mustChangePassword: true }), ADMIN_POWERS);
  expect(offers.requirePasswordChange).toBe(false);
  expect(offers.temporaryPassword).toBe(true);
});

test("admin view off takes every provisioning and admin control away", () => {
  const offers = rowOffers(account("ADMIN"), account(BELOW_PROFESSOR), PROMOTER_POWERS);
  expect(offers.changeRole).toBe(true);
  expect(offers.temporaryPassword || offers.passwordLink || offers.remove || offers.grants).toBe(false);
});

test("a row with no controls says why: You, Above your tier, Peer tier", () => {
  expect(STANDING_LABEL).toEqual({ SELF: "You", ABOVE: "Above your tier", PEER: "Peer tier" });

  const ministry = account("MINISTRY_ADMIN");
  expect(rowStanding(ministry, ministry)).toBe("SELF");
  expect(rowStanding(ministry, account("ADMIN"))).toBe("ABOVE");
  expect(rowStanding(ministry, account("MINISTRY_ADMIN"))).toBe("PEER");

  // `canManageUser` answers TRUE for a master admin's own row; the page still offers it nothing.
  const master = account("MASTER_ADMIN");
  expect(rowStanding(master, master)).toBe("SELF");
  const self = rowOffers(master, master, ADMIN_POWERS);
  expect(self.temporaryPassword || self.requirePasswordChange || self.remove || self.changeRole).toBe(false);
  // Master admins are peers, and neither manages the other.
  const other = rowOffers(master, account("MASTER_ADMIN"), ADMIN_POWERS);
  expect(other.standing).toBe("PEER");
  expect(other.temporaryPassword || other.remove || other.changeRole).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The status column
 * ──────────────────────────────────────────────────────────────────────────── */

test("the status says a new password is owed, before anything else, without saying who caused it", () => {
  // NEUTRAL: the flag stands on a password a provisioner typed AND on one its owner chose that an
  // administrator has required a change of. "Must set own password" claimed the first for both.
  expect(ACCOUNT_STATUS_LABEL.MUST_CHOOSE_NEW_PASSWORD).toBe("Must choose a new password");
  expect(ACCOUNT_STATUS_LABEL.NEVER_SIGNED_IN).toBe("Never signed in");
  expect(ACCOUNT_STATUS_LABEL.ACTIVE).toBe("Active");
  expect(ACCOUNT_STATUS_LABEL.GOOGLE_ONLY).toBe("Google sign-in only");

  const role = BELOW_PROFESSOR;
  expect(accountStatus(account(role, { mustChangePassword: true, firstLoginAt: null }))).toBe("MUST_CHOOSE_NEW_PASSWORD");
  // First even after a sign-in: the flag standing is the fact an administrator acts on.
  expect(accountStatus(account(role, { mustChangePassword: true }))).toBe("MUST_CHOOSE_NEW_PASSWORD");
  expect(accountStatus(account(role, { firstLoginAt: null }))).toBe("NEVER_SIGNED_IN");
  expect(accountStatus(account(role))).toBe("ACTIVE");
  expect(accountStatus(account(role, { authProvider: "GOOGLE", passwordSetAt: null }))).toBe("GOOGLE_ONLY");
  expect(accountStatus(account(role, { passwordSetAt: null }))).toBe("NO_PASSWORD");
  // A password account somebody signed into with Google is still a password account.
  expect(accountStatus(account(role, { authProvider: "GOOGLE" }))).toBe("ACTIVE");
});

test("the footnote under the table names both causes, and never claims somebody else typed the password", () => {
  // It said "a password somebody else typed still opens the account" of every flagged row — false of
  // an owner's own password after "Require a new password", and an invitation to a needless
  // temporary password, which WOULD make a shared secret.
  const at = PAGE.indexOf("{ACCOUNT_STATUS_LABEL.MUST_CHOOSE_NEW_PASSWORD}");
  expect(at, "the footnote was located").toBeGreaterThan(-1);
  const footnote = PAGE.slice(at, PAGE.indexOf("</p>", at));
  expect(footnote).toMatch(/because\s+a\s+provisioner\s+typed\s+it/);
  expect(footnote).toMatch(/a\s+change\s+was\s+required\s+of\s+the\s+owner/);
  expect(PAGE).not.toContain("somebody else typed still opens the account");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. Creating an account
 * ──────────────────────────────────────────────────────────────────────────── */

test("the password is read exactly as typed, and an edge space is noticed rather than removed", () => {
  const form = new FormData();
  form.set("password", "  hand over this  ");
  expect(passwordFrom(form)).toBe("  hand over this  ");
  expect(passwordFrom(new FormData())).toBe("");
  expect(passwordHasEdgeSpace(" leading")).toBe(true);
  expect(passwordHasEdgeSpace("trailing ")).toBe(true);
  expect(passwordHasEdgeSpace("in the middle")).toBe(false);
  expect(passwordHasEdgeSpace("")).toBe(false);
});

test("a non-admin provisioner's create carries no grant at all; an admin's carries both", () => {
  const form = {
    name: "A Designer",
    email: "a.designer@example.org",
    role: BELOW_PROFESSOR,
    password: " exactly as typed ",
    mustChangePassword: false,
    canManageQuestionnaire: true,
    canDownloadDataset: true
  };
  const ministry = newAccountBody(form, { admin: false });
  expect(ministry).toEqual({
    name: "A Designer",
    email: "a.designer@example.org",
    role: BELOW_PROFESSOR,
    password: " exactly as typed ",
    mustChangePassword: false
  });
  expect("canManageQuestionnaire" in ministry, "not even `false`: the server refuses a ministry admin any grant").toBe(false);
  const admin = newAccountBody(form, { admin: true });
  expect(admin.canManageQuestionnaire).toBe(true);
  expect(admin.canDownloadDataset).toBe(true);
});

test("the create form: raw password, browser hints, one ceiling, and the first-sign-in box ticked", () => {
  // Never through `requiredText`, which trims.
  expect(PAGE).not.toMatch(/requiredText\(\s*form\s*,\s*["']password["']\s*\)/);
  expect(PAGE).toContain("password: passwordFrom(form)");
  // `off` on both identity boxes, `new-password` on the secret — or a browser fills in the
  // administrator's own sign-in and the new account's temporary password is theirs.
  expect(PAGE).toMatch(/name="name"[^>]*autoComplete="off"/);
  expect(PAGE).toMatch(/name="email"[^>]*autoComplete="off"/);
  expect(PAGE).toMatch(/name="password"[\s\S]{0,200}maxLength=\{MAX_PASSWORD_LENGTH\}[\s\S]{0,80}autoComplete="new-password"/);
  expect(MAX_PASSWORD_LENGTH).toBe(200);
  // Ticked by default, sent either way, and warned about when unticked.
  expect(PAGE).toMatch(/\[requireChange, setRequireChange\] = useState\(true\)/);
  expect(PAGE).toContain("Require a new password at first sign-in");
  expect(PAGE).toContain("mustChangePassword: requireChange");
  expect(PAGE).toMatch(/<RequireChangeNote required=\{requireChange\} when="first" \/>/);
  expect(DIALOGS).toContain("Not required: the password you type stays theirs");
  // The picker offers what the server accepts, and the form is the provisioners'.
  expect(PAGE).toContain("const offeredRoles = provisionableRoles(currentUser)");
  expect(PAGE).toMatch(/offeredRoles\.map\(/);
  expect(PAGE).toContain("const provision = canProvisionAccounts(currentUser) && chrome;");
  expect(PAGE).toContain("const admin = isAdmin(currentUser) && chrome;");
  expect(PAGE).toMatch(/\{provision \? \(\s*<form/);
  // The grant boxes are an admin's alone.
  expect(PAGE).toMatch(/\{admin \? \(\s*<div[\s\S]{0,400}name="canManageQuestionnaire"/);
  // One request at a time.
  expect(PAGE).toMatch(/if \(creating\) return;/);
});

test("the success panel says what to hand over and never holds the password", () => {
  expect(PAGE).toContain("Hand them two things, and not in the same message");
  // The state the panel draws from has no password in it, and the form is remounted empty.
  expect(PAGE).toContain("useState<{ account: User; mustChangePassword: boolean; signInAt: string } | null>");
  expect(PAGE).not.toMatch(/setCreated\(\{[^}]*password:/);
  expect(PAGE).toMatch(/setFormGeneration\(\(value\) => value \+ 1\)/);
  expect(PAGE).toMatch(/<form\s+key=\{formGeneration\}/);
});

test("the success panel offers a password link only where the row would — never to a peer", () => {
  // CREATING at one's own tier is allowed (`assert_role` is inclusive); ISSUING A LINK for that
  // account is not (`assert_can_manage_target` refuses an account at the issuer's tier, and master
  // admins are peers). The panel used to offer the button to every create, and a peer's was a 403
  // every time.
  const admin = account("ADMIN");
  const ministry = account("MINISTRY_ADMIN");
  const master = account("MASTER_ADMIN");
  expect(createdPanelOffersLink(admin, account("ADMIN"), ADMIN_POWERS), "admin → admin").toBe(false);
  expect(createdPanelOffersLink(ministry, account("MINISTRY_ADMIN"), MINISTRY_POWERS), "ministry → ministry").toBe(false);
  expect(createdPanelOffersLink(master, account("MASTER_ADMIN"), ADMIN_POWERS), "master → master").toBe(false);
  expect(createdPanelOffersLink(admin, account(BELOW_PROFESSOR), ADMIN_POWERS), "admin → below").toBe(true);
  expect(createdPanelOffersLink(ministry, account(BELOW_PROFESSOR), MINISTRY_POWERS), "ministry → below").toBe(true);

  // And the panel says why, in the creator's own terms — never silence where a button used to be.
  expect(createdPanelLinkNote(admin, account("ADMIN"), ADMIN_POWERS)).toMatch(/your own tier, so only a higher tier/);
  expect(createdPanelLinkNote(ministry, account("MINISTRY_ADMIN"), MINISTRY_POWERS)).toMatch(/your own tier/);
  expect(createdPanelLinkNote(master, account("MASTER_ADMIN"), ADMIN_POWERS)).toMatch(/No master admin can issue/);
  expect(createdPanelLinkNote(admin, account(BELOW_PROFESSOR), ADMIN_POWERS), "a link is offered: nothing to explain").toBeNull();

  // The button and its sentence sit behind the helper, on the page.
  expect(PAGE).toContain(
    "const createdLinkOffered = created ? createdPanelOffersLink(currentUser, created.account, { provision, admin }) : false;"
  );
  expect(PAGE).toMatch(/\{createdLinkOffered \? \(\s*<button[\s\S]{0,300}?Issue a password link/);
  expect(PAGE).toMatch(/\{createdLinkOffered \? " Rather not pass a password on\?/);
  expect(PAGE).toContain("{createdLinkNote ? <p");
  expect(PAGE.match(/Issue a password link/g) ?? [], "one button, behind the helper").toHaveLength(1);
});

test("choosing one's own tier says, before the account exists, that it cannot be managed afterwards", () => {
  expect(createsPeerAccount(account("ADMIN"), "ADMIN")).toBe(true);
  expect(createsPeerAccount(account("MASTER_ADMIN"), "MASTER_ADMIN")).toBe(true);
  expect(createsPeerAccount(account("ADMIN"), BELOW_PROFESSOR)).toBe(false);
  expect(createsPeerAccount(account("ADMIN"), ""), "no tier chosen yet").toBe(false);
  expect(createsPeerAccount(null, "ADMIN")).toBe(false);
  expect(PAGE).toMatch(/\{createsPeerAccount\(currentUser, role\) \? \(/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The rows on the page
 * ──────────────────────────────────────────────────────────────────────────── */

test("every row control is decided by rowOffers, and the old fallthroughs are gone", () => {
  expect(PAGE).toContain("const offers = rowOffers(currentUser, user, { provision, admin });");
  expect(PAGE).not.toMatch(/authProvider\s*!==?\s*["']GOOGLE["']/);
  expect(PAGE).toMatch(/offers\.passwordLink \? \(/);
  expect(PAGE).toMatch(/offers\.requirePasswordChange \? \(/);
  expect(PAGE).toMatch(/offers\.temporaryPassword \? \(/);
  expect(PAGE).toMatch(/offers\.remove \? \(/);
  expect(PAGE).toMatch(/editable=\{offers\.grants\}/);
  expect(PAGE).toContain("STANDING_LABEL[offers.standing]");
  expect(PAGE).not.toContain(">Peer tier<");
  expect(PAGE).not.toContain(">Protected<");
  expect(PAGE).toContain("Set temporary password");
  expect(PAGE).toContain("onClick={() => setRequiring({ open: true, user })}");
  expect(PAGE).toContain("<RequirePasswordChangeDialog");
  expect(DIALOGS).toContain('title="Require a new password at next sign-in?"');
});

test("both password actions say the person is signed out everywhere, before it happens", () => {
  expect(DIALOGS).toMatch(/\{who\} is signed out everywhere now/);
  expect(DIALOGS).toMatch(/Saving signs \{who\} out everywhere/);
  // The dialog's own box: ticked by default, sent in both directions.
  expect(DIALOGS).toMatch(/\[requireChange, setRequireChange\] = useState\(true\)/);
  expect(DIALOGS).toContain("setTemporaryPassword(target.id, password, requireChange)");
  expect(DIALOGS).toContain("Require a new password at next sign-in");
  expect(DIALOGS).toMatch(/autoComplete="new-password"/);
  expect(DIALOGS).toMatch(/maxLength=\{MAX_PASSWORD_LENGTH\}/);
  expect(DIALOGS).toContain("passwordFrom(new FormData(event.currentTarget))");
});

test("requiring a new password says what a Google sign-in and an old phone will meet, and offers the link", () => {
  /*
    Two things on the other end of this decision that nothing on /users shows the reader. The screen
    the person is held at asks for their PRESENT password, which somebody who only ever signs in with
    Google may never have known — their way through is a password link, offered from the same dialog
    (and ticked already for an account the server still records as a Google sign-in). And handsets on
    0.0.15 or older word that screen "An administrator set your password", which is false here.
  */
  const dialog = DIALOGS.slice(DIALOGS.indexOf("export function RequirePasswordChangeDialog("));
  const body = dialog.slice(0, dialog.indexOf("export function AccountDetailsDialog("));
  expect(body, "the dialog was located").toContain("requirePasswordChange(target.id)");
  expect(body).toContain("Somebody who signs in with Google is asked for it too");
  expect(body).toContain("they need a password link");
  expect(body).toContain("Also issue a password link");
  expect(body).toContain('const withLink = withLinkChoice ?? target?.authProvider === "GOOGLE";');
  expect(body).toContain("onSaved(updated, asked)");
  expect(body).toContain("0.0.15 or older still say “An administrator set your password”");
  // The page issues the link AFTER the flag is raised, through the same path as the row's button.
  expect(PAGE).toMatch(/onSaved=\{\(updated, withLink\) => \{[\s\S]{0,600}?if \(withLink\) void issueLink\(updated\);/);
});

test("the status column replaced the raw provider column", () => {
  expect(PAGE).toContain("<ResizableTh>Sign-in</ResizableTh>");
  expect(PAGE).not.toContain("<ResizableTh>Provider</ResizableTh>");
  expect(PAGE).toContain("<AccountStatusCell user={user} />");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. Arriving from "Who may sign in"
 * ──────────────────────────────────────────────────────────────────────────── */

test("the pre-filled link round-trips, and a tier the reader cannot create at is dropped", () => {
  const href = passwordAccountHref("ad.design@example.gov.in", "MINISTRY_ADMIN", "A Director");
  expect(href.startsWith("/users?")).toBe(true);
  const params = new URLSearchParams(href.slice("/users?".length));
  expect(createFormPrefill(params, provisionableRoles(account("ADMIN")))).toEqual({
    email: "ad.design@example.gov.in",
    name: "A Director",
    role: "MINISTRY_ADMIN"
  });
  // A ministry admin may create a ministry admin; nobody is handed a tier above their own.
  const above = new URLSearchParams(passwordAccountHref("x@example.org", "ADMIN").slice("/users?".length));
  expect(createFormPrefill(above, provisionableRoles(account("MINISTRY_ADMIN"))).role).toBeNull();
  // No role in the link is no role in the form.
  const bare = new URLSearchParams(passwordAccountHref("y@example.org", null).slice("/users?".length));
  expect(createFormPrefill(bare, provisionableRoles(account("ADMIN")))).toEqual({ email: "y@example.org", name: "", role: null });
});

test("a link that named an address and no tier opens the picker on NO tier, never the ordinary default", () => {
  /*
    "Who may sign in" sends a bare link for an address admitted at the platform default — where Google
    sign-in would have started the person at the lowest rung. The picker used to open on the form's
    ordinary default, so an admin who trusted the link and pressed "Create account" made somebody two
    rungs up, and the allow-list row moved with it. Now the tier is a choice made on /users.
  */
  const offered = provisionableRoles(account("ADMIN"));
  const bare = createFormPrefill(
    new URLSearchParams(passwordAccountHref("y@example.org", null).slice("/users?".length)),
    offered
  );
  expect(createFormRole(bare, offered), "a bare prefill preselects nothing").toBe("");
  // A tier the reader may not create at is dropped — and the form asks, rather than choosing another.
  const dropped = createFormPrefill(
    new URLSearchParams(passwordAccountHref("z@example.org", "ADMIN").slice("/users?".length)),
    provisionableRoles(account("MINISTRY_ADMIN"))
  );
  expect(createFormRole(dropped, provisionableRoles(account("MINISTRY_ADMIN")))).toBe("");
  // A tier the link carried is the tier the form opens on; no link at all keeps the ordinary default.
  const tiered = createFormPrefill(
    new URLSearchParams(passwordAccountHref("w@example.org", "MINISTRY_ADMIN").slice("/users?".length)),
    offered
  );
  expect(createFormRole(tiered, offered)).toBe("MINISTRY_ADMIN");
  expect(createFormRole(NO_PREFILL, offered)).toBe(DEFAULT_CREATE_ROLE);
  expect(offered).toContain(DEFAULT_CREATE_ROLE);

  // The picker is held in state, seeded by that rule, and required — never a literal default.
  expect(PAGE).toContain("useState<UserRole | \"\">(() => createFormRole(prefill, offeredRoles))");
  expect(PAGE).toMatch(/<Select\s+name="role"\s+value=\{role\}[\s\S]{0,160}?required/);
  expect(PAGE).not.toMatch(/defaultValue=\{prefill\.role \?\?/);
  expect(PAGE).toContain("if (!role) {");
});

test("/users reads the link once and strips it; /admin/access says Google and offers the link", () => {
  expect(PAGE).toMatch(/useState<CreateFormPrefill>\(\(\) =>\s*createFormPrefill\(/);
  expect(PAGE).toContain('window.history.replaceState(null, "", "/users")');
  expect(PAGE).toMatch(/<Suspense[\s\S]{0,200}<UsersScreen \/>/);
  // Only Google sign-in makes an account by itself; the notice and the form copy now say so.
  expect(ACCESS_PAGE).toContain("may sign in with Google");
  expect(ACCESS_PAGE).toContain("the first time they sign in with Google");
  expect(ACCESS_PAGE).not.toContain("and the account is created the first time they sign in.");
  expect(ACCESS_PAGE).toContain("passwordAccountHref(created.email, created.admitRole, created.fullName)");
  expect(ACCESS_PAGE).toMatch(/<Link href="\/users"/);
  // "…and tier already filled in" only where a tier was chosen — the link cannot carry the default.
  expect(ACCESS_PAGE).toContain("admitRole: created.admitRole");
  expect(ACCESS_PAGE).toMatch(
    /\{passwordAccount\.admitRole\s*\?\s*", with this address and tier already filled in\."\s*:\s*", with this address filled in — choose their tier there/
  );
  expect(ACCESS_PAGE.match(/and tier already filled in/g) ?? [], "and never unconditionally").toHaveLength(1);
});

test("an approval that left the account's tier waiting says so in the server's words, on both approving paths", () => {
  /*
    Approving promotes an account that already exists — except one still holding a temporary password
    somebody typed for it (2026-10-09): the address is approved, the tier waits, and the 200 carries
    `accountPromotionHeld`, the sentence that names the two ways on. This screen's own receipt ("may
    sign in from their next attempt") would read as "done", so the server's sentence replaces it,
    drawn amber. Restoring is an approval at the row's own tier and can meet the same thing.
  */
  const sentence =
    "lata@example.org is approved, but the account keeps its Researcher tier for now: it still has to replace a password somebody typed for it.";
  expect(promotionHeldSentence({ accountPromotionHeld: sentence })).toBe(sentence);
  expect(promotionHeldSentence({ accountPromotionHeld: `  ${sentence}  ` })).toBe(sentence);
  expect(promotionHeldSentence({ accountPromotionHeld: null }), "every other decision").toBeNull();
  expect(promotionHeldSentence({}), "a server older than the rule").toBeNull();
  expect(promotionHeldSentence({ accountPromotionHeld: "   " })).toBeNull();
  expect(promotionHeldSentence(null)).toBeNull();

  expect((ACCESS_PAGE.match(/const held = promotionHeldSentence\(updated\);/g) ?? []).length, "approve and restore").toBe(2);
  expect(ACCESS_PAGE).toContain("setNotice(held ?? `${updated.email} may sign in from their next attempt.`);");
  expect(ACCESS_PAGE).toContain("setNotice(held ?? `${updated.email} can sign in again.`);");
  expect((ACCESS_PAGE.match(/setHeldPromotion\(held\);/g) ?? []).length).toBe(2);
  // Amber only while the notice is still that very sentence — a later notice replaces it, nothing to clear.
  expect(ACCESS_PAGE).toContain("heldPromotion !== null && heldPromotion === notice");
  // And the confirmation no longer promises a lift the server may hold back.
  expect(ACCESS_PAGE).toContain("unless it still has to replace a temporary password somebody typed for it");

  // The key is the server's, read where it is written.
  expect(readFileSync(join(__dirname, "..", "..", "backend", "app", "api", "routes", "access.py"), "utf8")).toContain(
    '"accountPromotionHeld": promotion_held'
  );
});

test("the decision's answer reaches the screen with the held sentence, through the real apiFetch", async () => {
  const sentence = "a@example.org is approved, but the account keeps its Researcher tier for now.";
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ id: "r1", email: "a@example.org", status: "ACTIVE", accountPromotionHeld: sentence }), {
      status: 200,
      headers: { "content-type": "application/json" }
    });
  const answer = await decideAccessRequest("r1", { decision: "APPROVE", role: null });
  expect(promotionHeldSentence(answer)).toBe(sentence);
  expect(answer.email).toBe("a@example.org");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 7. The wire, through the real apiFetch
 * ──────────────────────────────────────────────────────────────────────────── */

type Sent = { url: string; method: string; body: unknown };

const realFetch = globalThis.fetch;
let sent: Sent[] = [];

test.beforeEach(() => {
  sent = [];
  (globalThis as Record<string, unknown>).fetch = async (url: string, init: RequestInit = {}) => {
    sent.push({ url: String(url), method: String(init.method ?? "GET"), body: init.body ? JSON.parse(String(init.body)) : null });
    return new Response(JSON.stringify({ id: "u-1", email: "a@example.org", name: "A", role: "PROFESSOR" }), {
      status: 200,
      headers: { "content-type": "application/json" }
    });
  };
});

test.afterEach(() => {
  globalThis.fetch = realFetch;
});

test("requirePasswordChange raises the flag and touches nothing else", async () => {
  await requirePasswordChange("u-1");
  expect(sent).toHaveLength(1);
  expect(sent[0].method).toBe("PATCH");
  expect(sent[0].url).toMatch(/\/api\/users\/u-1$/);
  expect(sent[0].body).toEqual({ mustChangePassword: true });
});

test("setTemporaryPassword sends the password untrimmed and the flag in BOTH directions", async () => {
  await setTemporaryPassword("u-1", " spaced out ", false);
  expect(sent[0].method).toBe("PATCH");
  expect(sent[0].url).toMatch(/\/api\/users\/u-1$/);
  // `false` must travel: the server's default for a password set for somebody else is `true`.
  expect(sent[0].body).toEqual({ password: " spaced out ", mustChangePassword: false });
  await setTemporaryPassword("u-1", "another one", true);
  expect(sent[1].body).toEqual({ password: "another one", mustChangePassword: true });
});

test("createPasswordAccount posts exactly the body it is given", async () => {
  const body = newAccountBody(
    {
      name: "N",
      email: "n@example.org",
      role: BELOW_PROFESSOR,
      password: "eight chars ",
      mustChangePassword: true,
      canManageQuestionnaire: false,
      canDownloadDataset: false
    },
    { admin: false }
  );
  await createPasswordAccount(body);
  expect(sent[0].method).toBe("POST");
  expect(sent[0].url).toMatch(/\/api\/users$/);
  expect(sent[0].body).toEqual(body);
});
