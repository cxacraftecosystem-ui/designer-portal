import { ApiError, apiFetch, apiFetchWithHeaders } from "@/lib/api";
import { SESSION_ROTATED_HEADER } from "@/lib/sessionCookie";
import type { User, UserRole } from "@/lib/types";

export const SIGN_IN_HINT_HEADER = "x-sign-in-hint";

export type SignInHint = "AMBIGUOUS_IDENTIFIER" | "PASSWORD_NOT_SET" | null;

export function signInHintOf(header: string | null | undefined): SignInHint {
  switch ((header ?? "").trim().toUpperCase()) {
    case "AMBIGUOUS_IDENTIFIER":
      return "AMBIGUOUS_IDENTIFIER";
    case "PASSWORD_NOT_SET":
      return "PASSWORD_NOT_SET";
    default:
      return null;
  }
}

export function signInHintHeading(hint: SignInHint): string | null {
  switch (hint) {
    case "AMBIGUOUS_IDENTIFIER":
      return "That number matches more than one account";
    case "PASSWORD_NOT_SET":
      return "This account has no password yet";
    default:
      return null;
  }
}

export type IssuedPasswordLink = {
  id: string;
  link: string | null;
  expiresAt: string;
  purpose: "INVITE" | "RESET";
  deliveredBy: "COPY_LINK" | "EMAIL" | string;
};

export type PasswordLinkDelivery = "COPY_LINK" | "EMAIL";

export async function issuePasswordLink(
  userId: string,
  delivery: PasswordLinkDelivery = "COPY_LINK"
): Promise<IssuedPasswordLink> {
  return apiFetch<IssuedPasswordLink>("/auth/password-links", {
    method: "POST",
    body: JSON.stringify(delivery === "EMAIL" ? { userId, delivery } : { userId })
  });
}

export async function revokePasswordLink(linkId: string): Promise<void> {
  await apiFetch(`/auth/password-links/${linkId}/revoke`, { method: "POST" });
}

export type PasswordLinkCheck = {
  valid: boolean;
  reason: string | null;
  purpose: "INVITE" | "RESET" | null;
};

export async function checkPasswordLink(token: string): Promise<PasswordLinkCheck> {
  try {
    return await apiFetch<PasswordLinkCheck>(
      "/auth/set-password/check",
      { method: "POST", body: JSON.stringify({ token }) },
      { redirectOn401: false }
    );
  } catch (error) {
    if (!(error instanceof ApiError) || (error.status !== 404 && error.status !== 405)) throw error;
    return apiFetch<PasswordLinkCheck>(
      `/auth/set-password?token=${encodeURIComponent(token)}`,
      {},
      { redirectOn401: false }
    );
  }
}

export function takeLinkTokenFromAddress(href: string): { token: string; address: string | null } {
  const url = new URL(href);
  const fragment = new URLSearchParams(url.hash.slice(1));
  const inFragment = fragment.has("token");
  const inQuery = url.searchParams.has("token");
  if (!inFragment && !inQuery) return { token: "", address: null };
  const token = fragment.get("token") || url.searchParams.get("token") || "";
  url.searchParams.delete("token");
  let hash = url.hash;
  if (inFragment) {
    fragment.delete("token");
    const rest = fragment.toString();
    hash = rest ? `#${rest}` : "";
  }
  return { token, address: `${url.pathname}${url.search}${hash}` };
}

export async function setPasswordWithLink(token: string, password: string): Promise<void> {
  await apiFetch(
    "/auth/set-password",
    { method: "POST", body: JSON.stringify({ token, password }) },
    { redirectOn401: false }
  );
}

export const SESSION_TOKEN_HEADER = "x-session-token";

function sessionRotated(headers: Headers): boolean {
  return (headers.get(SESSION_ROTATED_HEADER) ?? "").trim() === "1";
}

export async function changeOwnPassword(currentPassword: string, newPassword: string): Promise<boolean> {
  const { headers } = await apiFetchWithHeaders<unknown>("/auth/change-password", {
    method: "POST",
    body: JSON.stringify({ currentPassword, newPassword })
  });
  return sessionRotated(headers);
}

export const CONFIRM_PROVIDER_HEADER = "x-confirm-provider";

export const TWO_FACTOR_REQUIRED_HEADER = "x-two-factor-required";

export type ProviderProof =
  | { googleIdToken: string }
  | {
      oidcProvider: "MICROSOFT" | "YAHOO";
      oidcCode: string;
      oidcCodeVerifier: string;
      oidcRedirectUri: string;
      oidcNonce: string;
    };

export async function setFirstPassword(
  newPassword: string,
  proof: ProviderProof,
  twoFactorCode?: string
): Promise<boolean> {
  const code = twoFactorCode?.trim();
  const { headers } = await apiFetchWithHeaders<unknown>("/auth/change-password", {
    method: "POST",
    body: JSON.stringify(code ? { newPassword, ...proof, twoFactorCode: code } : { newPassword, ...proof })
  });
  return sessionRotated(headers);
}

export function retryAfterWords(header: string | null | undefined): string | null {
  const seconds = Number(header);
  if (!header || !Number.isFinite(seconds) || seconds <= 0) return null;
  const minutes = Math.ceil(seconds / 60);
  return minutes <= 1 ? "Try again in a minute." : `Try again in ${minutes} minutes.`;
}

export async function signOutEverywhere(userId: string): Promise<User> {
  return apiFetch<User>(`/users/${userId}/sign-out-everywhere`, { method: "POST" });
}

export type ForgotPasswordAnswer = { message: string };

export async function requestForgotPasswordLink(email: string): Promise<ForgotPasswordAnswer> {
  return apiFetch<ForgotPasswordAnswer>(
    "/auth/forgot-password",
    { method: "POST", body: JSON.stringify({ email }) },
    { redirectOn401: false }
  );
}

export type PasswordMode = "SET" | "GENERATE" | "NONE";

export type NewPasswordAccount = {
  name: string;
  email: string;
  role: UserRole;
  passwordMode: PasswordMode;
  password?: string;
  mustChangePassword?: boolean;
  sendPasswordLink?: boolean;
  canManageQuestionnaire?: boolean;
  canDownloadDataset?: boolean;
};

export type CreatedAccountLink = {
  id: string;
  expiresAt: string;
  purpose: "INVITE" | "RESET";
  deliveredBy: PasswordLinkDelivery;
};

export type CreatedAccount = User & {
  temporaryPassword?: string | null;
  passwordLink?: CreatedAccountLink | null;
  passwordLinkError?: string | null;
};

export async function createPasswordAccount(account: NewPasswordAccount): Promise<CreatedAccount> {
  return apiFetch<CreatedAccount>("/users", { method: "POST", body: JSON.stringify(account) });
}

export const EXISTING_ACCOUNT_HEADER = "x-existing-account-id";

export function existingAccountId(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const id = error.headers?.get(EXISTING_ACCOUNT_HEADER)?.trim();
  return id ? id : null;
}

export async function requirePasswordChange(userId: string): Promise<User> {
  return apiFetch<User>(`/users/${userId}`, {
    method: "PATCH",
    body: JSON.stringify({ mustChangePassword: true })
  });
}

export async function setTemporaryPassword(
  userId: string,
  password: string,
  mustChangePassword: boolean
): Promise<User> {
  return apiFetch<User>(`/users/${userId}`, {
    method: "PATCH",
    body: JSON.stringify({ password, mustChangePassword })
  });
}

export async function correctAccountDetails(
  userId: string,
  changes: { name?: string; email?: string }
): Promise<User> {
  return apiFetch<User>(`/users/${userId}`, { method: "PATCH", body: JSON.stringify(changes) });
}

export const MIN_PASSWORD_LENGTH = 8;

export const MAX_PASSWORD_LENGTH = 200;

export function passwordRuleLine(suffix?: string): string {
  return `At least ${MIN_PASSWORD_LENGTH} characters.${suffix ? ` ${suffix}` : ""}`;
}

export function mustChangePassword(user: User | null | undefined): boolean {
  return user?.mustChangePassword ?? false;
}
