import { ApiError } from "@/lib/api";
import { CONFIRM_PROVIDER_HEADER, TWO_FACTOR_REQUIRED_HEADER } from "@/lib/signIn";
import type { User } from "@/lib/types";

export function hasOwnPassword(user: Pick<User, "passwordSetAt" | "hasPassword"> | null | undefined): boolean {
  if (!user) return false;
  if (typeof user.hasPassword === "boolean") return user.hasPassword;
  return user.passwordSetAt != null;
}

export function canSetFirstPassword(user: Pick<User, "passwordSetAt" | "hasPassword"> | null | undefined): boolean {
  return !!user && !hasOwnPassword(user);
}

export const FIRST_PASSWORD_NOTE =
  "After this you can sign in with your email address and this password, or the way you sign in now.";

export type ConfirmProvider = "GOOGLE" | "MICROSOFT" | "YAHOO";

export function providerConfirmationRequired(error: unknown): ConfirmProvider | null {
  if (!(error instanceof ApiError) || error.status !== 400) return null;
  const named = error.headers?.get(CONFIRM_PROVIDER_HEADER)?.trim().toUpperCase();
  return named === "GOOGLE" || named === "MICROSOFT" || named === "YAHOO" ? named : null;
}

export function confirmProviderFor(user: { authProvider?: string } | null | undefined): ConfirmProvider {
  const named = (user?.authProvider ?? "").toUpperCase();
  return named === "MICROSOFT" || named === "YAHOO" ? named : "GOOGLE";
}

export function confirmPrompt(provider: ConfirmProvider): string {
  const label = provider === "GOOGLE" ? "Google" : provider === "MICROSOFT" ? "Microsoft" : "Yahoo";
  return `To add a password, first confirm it's you by signing in with ${label} again.`;
}

export const CONFIRMED_NOTE = "Confirmed. Set your password within the next few minutes.";

export function twoFactorCodeRequired(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400 && error.headers?.get(TWO_FACTOR_REQUIRED_HEADER) === "1";
}

export function firstPasswordNeedsCode(user: Pick<User, "twoFactorEnabled"> | null | undefined): boolean {
  return Boolean(user?.twoFactorEnabled);
}

export const PASSWORD_CHANGE_PROMPT = "Choose a new password to continue.";

export const PASSWORD_CHANGE_SESSIONS = "You stay signed in here, and are signed out everywhere else.";

export function newPasswordProblem({
  current,
  next,
  confirm
}: {
  current: string;
  next: string;
  confirm: string;
}): string | null {
  if (next !== confirm) return "The two passwords do not match.";
  if (next === current) return "Your new password must be different from your current one.";
  return null;
}

export function currentPasswordRefused(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400;
}
