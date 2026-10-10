/**
 * THE JUDGEMENTS BEHIND /users, KEPT OUT OF THE JSX SO A TEST CAN CALL THEM.
 *
 * There is no React renderer in this repository's devDependencies, so a decision written inline in
 * a table cell is only ever exercised by somebody looking at the screen — the same reason
 * `components/ui/selectFilter.ts` and `components/data/cappedList.ts` exist. Each function here
 * answers one question the page used to answer inline: what one row may offer, why a row offers
 * nothing, what an account's sign-in status is, and what a create sends.
 * `e2e/users-provisioning-unit.spec.ts` calls them.
 *
 * WHO MAY DO WHAT is not decided here. `lib/permissions.ts` owns the predicates and the server owns
 * the rule; this module only combines them per row, so the page cannot offer a control the API
 * refuses for a reason the predicates already know.
 */

import {
  ACCOUNT_PROVISIONER_ROLES,
  ROLES_BY_RANK,
  canManageUser,
  hasRank,
  isMasterAdmin,
  roleLabel,
  roleRank
} from "@/lib/permissions";
import type { NewPasswordAccount } from "@/lib/signIn";
import type { User, UserRole } from "@/lib/types";

/**
 * The tiers that provision accounts, lowest first, joined for a sentence — "Ministry Admin, Admin
 * and Master Admin". DERIVED from `ACCOUNT_PROVISIONER_ROLES`, for the reason the ladder sentence on
 * the page is derived from `ROLES_BY_RANK`: a hand-written list of who holds an access-control power
 * is a fact with no owner, and it is wrong the day the set moves.
 */
export function provisionerTierPhrase(): string {
  const labels = [...ROLES_BY_RANK]
    .reverse()
    .filter((role) => ACCOUNT_PROVISIONER_ROLES.includes(role))
    .map(roleLabel);
  if (labels.length <= 1) return labels.join("");
  return `${labels.slice(0, -1).join(", ")} and ${labels[labels.length - 1]}`;
}

/* ────────────────────────────────────────────────────────────────────────────
 * One row
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Where a row stands relative to the person reading the table.
 *
 * MANAGED is {@link canManageUser}: strictly below the reader, or — for a master admin — anybody
 * but another master admin. The other three are the reasons a row carries no controls, and they are
 * three because they are three different facts: the reader's own account (changed through
 * change-password and Settings, never here), an account above them, and an account at their own
 * tier. This said "Peer tier" for all three until 2026-10-09, so a ministry admin read "Peer tier"
 * beside every Admin and beside their own row — the permission screen stating the wrong relationship.
 */
export type RowStanding = "SELF" | "ABOVE" | "PEER" | "MANAGED";

export function rowStanding(viewer: User | null | undefined, target: User): RowStanding {
  // FIRST, because `canManageUser` answers TRUE for a master admin's own row (its self-exception
  // mirrors the server's), and nothing on this page is for one's own account.
  if (viewer && target.id === viewer.id) return "SELF";
  if (canManageUser(viewer, target)) return "MANAGED";
  return roleRank(target) > roleRank(viewer) ? "ABOVE" : "PEER";
}

/** The words a row with no controls says instead. */
export const STANDING_LABEL: Record<Exclude<RowStanding, "MANAGED">, string> = {
  SELF: "You",
  ABOVE: "Above your role",
  PEER: "Same role as yours"
};

/**
 * What the page may do with this table at all: `provision` is `canProvisionAccounts` and `admin` is
 * `isAdmin`, each already ANDed with admin view by the page (an ADMIN with admin view off is shown
 * neither; a ministry admin has no toggle and is shown `provision` always).
 */
export type TablePowers = { provision: boolean; admin: boolean };

export type RowOffers = {
  standing: RowStanding;
  /** The role picker — every tier from Professor, on an account it manages. */
  changeRole: boolean;
  /** Correct the name or the sign-in address. */
  editDetails: boolean;
  passwordLink: boolean;
  requirePasswordChange: boolean;
  temporaryPassword: boolean;
  /** Delete — an admin's alone. */
  remove: boolean;
  /** The capability checkboxes — an admin's alone, and only below Professor, where they mean something. */
  grants: boolean;
};

/**
 * Does this account have a password? `passwordSetAt` and NOT `authProvider`: Google sign-in on a
 * password account used to flip it to GOOGLE while it kept its hash, and the "Password link" keyed
 * on the provider then vanished from exactly the account that needed it. Every account holding a
 * hash was backfilled with a `passwordSetAt` when the column landed, so the stamp is the fact.
 */
export function hasPassword(user: User): boolean {
  return user.passwordSetAt != null;
}

export function rowOffers(viewer: User | null | undefined, target: User, powers: TablePowers): RowOffers {
  const standing = rowStanding(viewer, target);
  const managed = standing === "MANAGED";
  const provision = managed && powers.provision;
  return {
    standing,
    changeRole: managed,
    editDetails: provision,
    passwordLink: provision && hasPassword(target),
    // Only where there is a password to replace (otherwise the server answers 422) and the flag is
    // not already standing — raising it again would sign the person out a second time for nothing.
    requirePasswordChange: provision && hasPassword(target) && !target.mustChangePassword,
    // Offered on an account with no password too: the dialog says, in words, that it gives a
    // Google-only account a password, which is the decision the old "no link for Google" rule was
    // protecting — taken here explicitly rather than refused.
    temporaryPassword: provision,
    remove: managed && powers.admin,
    grants: managed && powers.admin && !hasRank(target, "PROFESSOR")
  };
}

/**
 * May the "Account created" panel offer a password link for the account just made?
 *
 * EXACTLY THE ROW'S ANSWER, because it is the same account and the same server rule: the link route
 * runs `assert_can_manage_target`, which refuses an account AT the creator's own tier, while creating
 * one there is allowed (`assert_role` is inclusive, D1). So an admin who makes an admin, a ministry
 * admin who makes a ministry admin and a master admin who makes a master admin are handed an account
 * they cannot issue a link for — and the panel used to offer the button anyway, to a 403 every time.
 */
export function createdPanelOffersLink(viewer: User | null | undefined, account: User, powers: TablePowers): boolean {
  return rowOffers(viewer, account, powers).passwordLink;
}

/**
 * Why the panel offers no link, in the creator's terms — or null where it offers one, or where there
 * is nothing worth saying. A peer is the case that matters: the creator has just made an account they
 * will never manage, so the password they typed is the only credential they can hand over.
 */
export function createdPanelLinkNote(
  viewer: User | null | undefined,
  account: User,
  powers: TablePowers
): string | null {
  if (createdPanelOffersLink(viewer, account, powers)) return null;
  if (rowStanding(viewer, account) !== "PEER") return null;
  if (isMasterAdmin(viewer) && isMasterAdmin(account)) {
    return "No master admin can issue a password link for another master admin, or change its password — hand over the password you typed.";
  }
  return "This account has the same role as yours, so only a higher role can issue its password link or change its password. Hand over the password you typed.";
}

/**
 * Is `role` the reader's own tier — an account they may create and will not be able to manage once it
 * exists? The create form says so while that tier is chosen, BEFORE the account is made.
 */
export function createsPeerAccount(viewer: User | null | undefined, role: UserRole | ""): boolean {
  return Boolean(viewer) && role !== "" && roleRank(role) === roleRank(viewer);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Sign-in status
 * ──────────────────────────────────────────────────────────────────────────── */

export type AccountStatusKind =
  | "MUST_CHOOSE_NEW_PASSWORD"
  | "GOOGLE_ONLY"
  | "NO_PASSWORD"
  | "NEVER_SIGNED_IN"
  | "ACTIVE";

/**
 * NEUTRAL ABOUT WHO CAUSED THE CHANGE, for the reason the gate's own sentence is
 * (`lib/passwordChange.PASSWORD_CHANGE_PROMPT`): the flag stands both on a password a provisioner
 * typed and on one its owner chose that an administrator has since required a change of. This read
 * "Must set own password" until 2026-10-09, which claimed the first cause for both.
 */
export const ACCOUNT_STATUS_LABEL: Record<AccountStatusKind, string> = {
  MUST_CHOOSE_NEW_PASSWORD: "Must choose a new password",
  GOOGLE_ONLY: "Google sign-in only",
  NO_PASSWORD: "No password set",
  NEVER_SIGNED_IN: "Never signed in",
  ACTIVE: "Active"
};

/**
 * What an administrator needs to know about how this account signs in.
 *
 * In ORDER, and the order is the point. A standing `mustChangePassword` comes first whatever else
 * is true, because it is the one status an administrator may have to act on: the account's next
 * sign-in leads only to choosing a new password — either because a provisioner typed the present
 * one (a temporary password somebody else knows) or because a change was required of the owner's
 * own. An account with no password next: it cannot be "never signed in" with a password it does not
 * have. Then "no recorded sign-in", then "Active".
 *
 * `firstLoginAt` is stamped by either credential, but only since the column was added (August
 * 2026), so an account last used before then also reads "Never signed in" — the page says so
 * under the table rather than leaving the label to overstate.
 */
export function accountStatus(user: User): AccountStatusKind {
  if (user.mustChangePassword) return "MUST_CHOOSE_NEW_PASSWORD";
  if (!hasPassword(user)) return user.authProvider === "GOOGLE" ? "GOOGLE_ONLY" : "NO_PASSWORD";
  if (!user.firstLoginAt) return "NEVER_SIGNED_IN";
  return "ACTIVE";
}

/* ────────────────────────────────────────────────────────────────────────────
 * Creating an account
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The password box's value EXACTLY as typed. `lib/forms.requiredText` trims, and a password stored
 * trimmed while its owner pastes the original is refused at sign-in for ever — so the create form
 * and the temporary-password dialog read it through this and nothing else.
 */
export function passwordFrom(form: FormData, key = "password"): string {
  const value = form.get(key);
  return typeof value === "string" ? value : "";
}

/** Begins or ends with whitespace — kept as typed, so the person must type it too, and is told so. */
export function passwordHasEdgeSpace(password: string): boolean {
  return password.length > 0 && password !== password.trim();
}

export type NewAccountForm = {
  name: string;
  email: string;
  role: UserRole;
  password: string;
  mustChangePassword: boolean;
  canManageQuestionnaire: boolean;
  canDownloadDataset: boolean;
};

/**
 * What `POST /api/users` is sent. The capability grants travel only for an ADMIN: a provisioner who
 * is not one is refused with a 403 for any grant set true, so the boxes are not drawn for them and
 * nothing they did not choose is sent on their behalf.
 */
export function newAccountBody(form: NewAccountForm, powers: Pick<TablePowers, "admin">): NewPasswordAccount {
  const body: NewPasswordAccount = {
    name: form.name,
    email: form.email,
    role: form.role,
    password: form.password,
    mustChangePassword: form.mustChangePassword
  };
  if (powers.admin) {
    body.canManageQuestionnaire = form.canManageQuestionnaire;
    body.canDownloadDataset = form.canDownloadDataset;
  }
  return body;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Arriving from "Who may sign in"
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The query parameters /users reads to pre-fill its create form, spelled once for both ends.
 * `/admin/access` builds the link with {@link passwordAccountHref}; this page reads it with
 * {@link createFormPrefill}. The address and the tier are the point; the name rides along because
 * the admin typed it on the allow-list a moment ago.
 */
export const PREFILL_EMAIL_PARAM = "email";
export const PREFILL_ROLE_PARAM = "role";
export const PREFILL_NAME_PARAM = "name";

/**
 * A link to /users with the create form filled in — for an address admitted on "Who may sign in"
 * whose owner will sign in with a password, which admission alone never gives them: only Google
 * sign-in creates an account by itself.
 */
export function passwordAccountHref(email: string, role?: UserRole | null, name?: string | null): string {
  const params = new URLSearchParams();
  params.set(PREFILL_EMAIL_PARAM, email);
  if (role) params.set(PREFILL_ROLE_PARAM, role);
  if (name) params.set(PREFILL_NAME_PARAM, name);
  return `/users?${params.toString()}`;
}

export type CreateFormPrefill = { email: string; name: string; role: UserRole | null };

export const NO_PREFILL: CreateFormPrefill = { email: "", name: "", role: null };

/**
 * What the create form starts with. A role the reader may not create at is DROPPED rather than
 * shown — the picker cannot display a value it does not offer, and a form that silently created at
 * a different tier from the one the link named would be worse than one that asks again.
 */
export function createFormPrefill(
  params: Pick<URLSearchParams, "get">,
  offered: readonly UserRole[]
): CreateFormPrefill {
  const email = (params.get(PREFILL_EMAIL_PARAM) ?? "").trim();
  const name = (params.get(PREFILL_NAME_PARAM) ?? "").trim();
  const asked = params.get(PREFILL_ROLE_PARAM);
  const role = offered.find((option) => option === asked) ?? null;
  return { email, name, role };
}

/** Did the address bar carry anything for the create form? Then it is stripped once read. */
export function hasPrefill(prefill: CreateFormPrefill): boolean {
  return Boolean(prefill.email || prefill.name || prefill.role);
}

/** The tier the create form opens on when nothing asked for another. */
export const DEFAULT_CREATE_ROLE: UserRole = "RESEARCHER";

/**
 * The tier the create form OPENS on — or "" for none, which the picker then requires to be chosen.
 *
 * A LINK THAT NAMED AN ADDRESS AND NO TIER OPENS ON NO TIER. "Who may sign in" sends exactly that for
 * an address admitted at the platform default, where Google sign-in would have started the person at
 * the lowest rung; opening on the ordinary default handed them Researcher — two rungs up, able to
 * create records — to anybody who trusted the link and pressed "Create account", and the allow-list
 * row moved with it. The same holds for a tier the link named that this reader may not create at,
 * which {@link createFormPrefill} drops: the form asks again rather than choosing a different tier
 * for them. Only a form opened with nothing pre-filled takes {@link DEFAULT_CREATE_ROLE}.
 */
export function createFormRole(prefill: CreateFormPrefill, offered: readonly UserRole[]): UserRole | "" {
  if (prefill.role) return prefill.role;
  if (prefill.email) return "";
  return offered.includes(DEFAULT_CREATE_ROLE) ? DEFAULT_CREATE_ROLE : "";
}
