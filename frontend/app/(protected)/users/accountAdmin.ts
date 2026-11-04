import {
  ACCOUNT_PROVISIONER_ROLES,
  ROLES_BY_RANK,
  canManageUser,
  hasRank,
  isMasterAdmin,
  roleLabel,
  roleRank
} from "@/lib/permissions";
import type { NewPasswordAccount, PasswordMode } from "@/lib/signIn";
import type { User, UserRole } from "@/lib/types";

export function provisionerTierPhrase(): string {
  const labels = [...ROLES_BY_RANK]
    .reverse()
    .filter((role) => ACCOUNT_PROVISIONER_ROLES.includes(role))
    .map(roleLabel);
  if (labels.length <= 1) return labels.join("");
  return `${labels.slice(0, -1).join(", ")} and ${labels[labels.length - 1]}`;
}

export type RowStanding = "SELF" | "ABOVE" | "PEER" | "MANAGED";

export function rowStanding(viewer: User | null | undefined, target: User): RowStanding {
  if (viewer && target.id === viewer.id) return "SELF";
  if (canManageUser(viewer, target)) return "MANAGED";
  return roleRank(target) > roleRank(viewer) ? "ABOVE" : "PEER";
}

export const STANDING_LABEL: Record<Exclude<RowStanding, "MANAGED">, string> = {
  SELF: "You",
  ABOVE: "Above your role",
  PEER: "Same role as yours"
};

export type TablePowers = { provision: boolean; admin: boolean };

export type RowOffers = {
  standing: RowStanding;
  changeRole: boolean;
  editDetails: boolean;
  passwordLink: boolean;
  requirePasswordChange: boolean;
  temporaryPassword: boolean;
  resetTwoFactor: boolean;
  signOutEverywhere: boolean;
  remove: boolean;
  grants: boolean;
};

export function hasPassword(user: User): boolean {
  if (typeof user.hasPassword === "boolean") return user.hasPassword;
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
    requirePasswordChange: provision && hasPassword(target) && !target.mustChangePassword,
    temporaryPassword: provision,
    resetTwoFactor: provision && target.twoFactorEnabled === true,
    signOutEverywhere: provision,
    remove: managed && powers.admin,
    grants: managed && powers.admin && !hasRank(target, "PROFESSOR")
  };
}

export function createdPanelOffersLink(viewer: User | null | undefined, account: User, powers: TablePowers): boolean {
  return rowOffers(viewer, account, powers).passwordLink;
}

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

export function createsPeerAccount(viewer: User | null | undefined, role: UserRole | ""): boolean {
  return Boolean(viewer) && role !== "" && roleRank(role) === roleRank(viewer);
}

export type AccountStatusKind =
  | "MUST_CHOOSE_NEW_PASSWORD"
  | "GOOGLE_ONLY"
  | "NO_PASSWORD"
  | "NEVER_SIGNED_IN"
  | "ACTIVE";

export const ACCOUNT_STATUS_LABEL: Record<AccountStatusKind, string> = {
  MUST_CHOOSE_NEW_PASSWORD: "Must choose a new password",
  GOOGLE_ONLY: "Google sign-in only",
  NO_PASSWORD: "No password set",
  NEVER_SIGNED_IN: "Never signed in",
  ACTIVE: "Active"
};

export function accountStatus(user: User): AccountStatusKind {
  if (user.mustChangePassword) return "MUST_CHOOSE_NEW_PASSWORD";
  if (!hasPassword(user)) return user.authProvider === "GOOGLE" ? "GOOGLE_ONLY" : "NO_PASSWORD";
  if (!user.firstLoginAt) return "NEVER_SIGNED_IN";
  return "ACTIVE";
}

export function lastSignIn(user: User): { label: "Last signed in" | "First signed in"; at: string } | null {
  if (user.lastLoginAt) return { label: "Last signed in", at: user.lastLoginAt };
  if (user.firstLoginAt) return { label: "First signed in", at: user.firstLoginAt };
  return null;
}

export function passwordFrom(form: FormData, key = "password"): string {
  const value = form.get(key);
  return typeof value === "string" ? value : "";
}

export function passwordHasEdgeSpace(password: string): boolean {
  return password.length > 0 && password !== password.trim();
}

export type NewAccountForm = {
  name: string;
  email: string;
  role: UserRole;
  passwordMode?: PasswordMode;
  password: string;
  mustChangePassword: boolean;
  sendPasswordLink?: boolean;
  canManageQuestionnaire: boolean;
  canDownloadDataset: boolean;
};

export function newAccountBody(form: NewAccountForm, powers: Pick<TablePowers, "admin">): NewPasswordAccount {
  const passwordMode = form.passwordMode ?? "SET";
  const body: NewPasswordAccount = {
    name: form.name,
    email: form.email,
    role: form.role,
    passwordMode
  };
  if (passwordMode === "SET") {
    body.password = form.password;
    body.mustChangePassword = form.mustChangePassword;
  } else if (passwordMode === "NONE" && form.sendPasswordLink) {
    body.sendPasswordLink = true;
  }
  if (powers.admin) {
    body.canManageQuestionnaire = form.canManageQuestionnaire;
    body.canDownloadDataset = form.canDownloadDataset;
  }
  return body;
}

export const PREFILL_EMAIL_PARAM = "email";
export const PREFILL_ROLE_PARAM = "role";
export const PREFILL_NAME_PARAM = "name";

export function passwordAccountHref(email: string, role?: UserRole | null, name?: string | null): string {
  const params = new URLSearchParams();
  params.set(PREFILL_EMAIL_PARAM, email);
  if (role) params.set(PREFILL_ROLE_PARAM, role);
  if (name) params.set(PREFILL_NAME_PARAM, name);
  return `/users?${params.toString()}`;
}

export type CreateFormPrefill = { email: string; name: string; role: UserRole | null };

export const NO_PREFILL: CreateFormPrefill = { email: "", name: "", role: null };

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

export function hasPrefill(prefill: CreateFormPrefill): boolean {
  return Boolean(prefill.email || prefill.name || prefill.role);
}

export const DEFAULT_CREATE_ROLE: UserRole = "RESEARCHER";

export function createFormRole(prefill: CreateFormPrefill, offered: readonly UserRole[]): UserRole | "" {
  if (prefill.role) return prefill.role;
  if (prefill.email) return "";
  return offered.includes(DEFAULT_CREATE_ROLE) ? DEFAULT_CREATE_ROLE : "";
}
