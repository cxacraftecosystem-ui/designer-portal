/**
 * THE SECOND REFUSAL HEADER, AND THE PASSWORD-LINK ENDPOINTS BEHIND IT.
 *
 * `lib/accessRoster.ts` classifies a refusal that is about ADMISSION — where an address stands with
 * the platform allow-list. This one classifies a refusal that is about the IDENTIFIER: what was
 * typed names two accounts, or the account it names has never had a password. They are two
 * different questions with two different remedies, which is why the server answers them on two
 * different headers (`app/api/routes/auth.py`) rather than crowding one switch that would then mean
 * two kinds of thing.
 *
 * ── NEVER FROM THE MESSAGE TEXT ───────────────────────────────────────────────────────────────
 *
 * Same rule as `accessRefusalKind`, for the same reason: the sentences are English written for the
 * person reading them and they will be reworded. A client that matched on prose would silently stop
 * distinguishing the cases the first time somebody fixed a comma, and the screen would go on looking
 * correct.
 *
 * ── AN ABSENT HEADER IS "UNCLASSIFIED", NEVER "NOT REFUSED" ───────────────────────────────────
 *
 * A proxy that strips unknown headers, a deployment that predates this, and a cross-origin response
 * whose server forgot `expose_headers` all produce the same absence. The caller falls back to the
 * server's own sentence and neutral chrome — the only safe direction to be wrong in on the front
 * door.
 */

import { apiFetch } from "@/lib/api";
import type { User } from "@/lib/types";

/** Spelled once; see `auth.SIGN_IN_HINT_HEADER`. Lower-case because `Headers.get` is case-insensitive. */
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

/**
 * The heading drawn AROUND the server's sentence — never instead of it.
 *
 * Terse, per the owner's instruction of 2026-08-30: the server's `detail` already says what to do,
 * so this is a heading and nothing more. `accessRefusalChrome`'s three-line advice paragraphs are
 * the older shape and are not copied here.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * Password links
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One issued link.
 *
 * `deliveredBy` is `"COPY_LINK"` today, which means the server sent nothing and the administrator
 * hands the link over themselves — the owner's decision of 2026-08-30, and the reason no mail
 * dependency was added. **Branch on this field, never assume it**: the transport sits behind an
 * interface on the server precisely so that adding SES later is a config change, and a screen that
 * hard-codes "copy this" would go on saying so after the mail started going out.
 *
 * There is no `token` field beside `link`, deliberately: a credential appearing twice in one answer
 * is a credential in two places to keep out of logs.
 */
export type IssuedPasswordLink = {
  id: string;
  link: string;
  expiresAt: string;
  purpose: "INVITE" | "RESET";
  deliveredBy: string;
};

export async function issuePasswordLink(userId: string): Promise<IssuedPasswordLink> {
  return apiFetch<IssuedPasswordLink>("/auth/password-links", {
    method: "POST",
    body: JSON.stringify({ userId })
  });
}

export async function revokePasswordLink(linkId: string): Promise<void> {
  await apiFetch(`/auth/password-links/${linkId}/revoke`, { method: "POST" });
}

/** Why a link was refused, in a word the set-password screen branches on. */
export type PasswordLinkCheck = {
  valid: boolean;
  reason: string | null;
  purpose: "INVITE" | "RESET" | null;
};

/**
 * Is this link still good?
 *
 * `redirectOn401: false` because this route is PUBLIC and the person holding the link is by
 * definition not signed in — the default 401 redirect would bounce them to /login, which is exactly
 * the page they cannot use.
 */
export async function checkPasswordLink(token: string): Promise<PasswordLinkCheck> {
  return apiFetch<PasswordLinkCheck>(
    `/auth/set-password?token=${encodeURIComponent(token)}`,
    {},
    { redirectOn401: false }
  );
}

export async function setPasswordWithLink(token: string, password: string): Promise<void> {
  await apiFetch(
    "/auth/set-password",
    { method: "POST", body: JSON.stringify({ token, password }) },
    { redirectOn401: false }
  );
}

/** The signed-in account replacing its own password — the route `mustChangePassword` sends you to. */
export async function changeOwnPassword(currentPassword: string, newPassword: string): Promise<void> {
  await apiFetch("/auth/change-password", {
    method: "POST",
    body: JSON.stringify({ currentPassword, newPassword })
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * The password rules, spelled once
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The shortest password this product will store.
 *
 * ONE VOCABULARY, NOT TWO. `credential_links.MIN_PASSWORD_LENGTH`, `SetPasswordRequest.password`,
 * `ChangePasswordRequest.newPassword`, `LoginRequest.password` and `UserCreate.password` all carry
 * the same floor on the server, deliberately, so that a password which can be SET can always be
 * used to sign in. This constant lived inside `app/set-password/page.tsx` while that screen was the
 * only one asking for a password; the first-login gate on `/login` asks for one too, and a second
 * copy of the number beside a second sentence is how the two screens come to disagree about what
 * they are enforcing. It is mirrored rather than fetched for the reason `DICTATE_MAX_BYTES` is: it
 * is a deployment constant with no endpoint that reports it, and the server refuses either way.
 */
export const MIN_PASSWORD_LENGTH = 8;

/**
 * The one line printed under a pair of password boxes.
 *
 * A FUNCTION AND NOT A CONSTANT, because the two screens have a genuinely different second clause —
 * a link works once, a sign-in gate does not involve a link at all — and a shared string with the
 * link sentence in it would have `/login` telling somebody about a link they are not holding. The
 * FIRST clause is what must not diverge, and it is the only part this owns.
 */
export function passwordRuleLine(suffix?: string): string {
  return `At least ${MIN_PASSWORD_LENGTH} characters.${suffix ? ` ${suffix}` : ""}`;
}

/**
 * Must this account choose its own password before it is let into the product?
 *
 * ── WHY THE CLIENT ENFORCES WHAT THE SERVER ONLY REPORTS ──────────────────────────────────────
 *
 * `POST /auth/login` mints a token for an account carrying this flag, and that is not an oversight:
 * the only route that can change a password (`POST /auth/change-password`) needs a bearer token, so
 * refusing the sign-in would leave the account permanently unable to comply. It is the identical
 * decision the usage-consent gate took, argued in `auth.serialize_user` and in this column's own
 * comment in `schema.prisma`, and the blocking half belongs to the clients in exactly the same way.
 *
 * ── AN ABSENT FIELD IS "NO GATE", NEVER "GATE OPEN" AND NEVER "GATE SHUT" ─────────────────────
 *
 * `mustChangePassword` is optional on `User` because a deployment older than the column sends
 * nothing. `?? false` is the only safe reading: a handset or a browser talking to such a server must
 * not invent a demand nobody made, and must not claim the person has already chosen.
 */
export function mustChangePassword(user: User | null | undefined): boolean {
  return user?.mustChangePassword ?? false;
}
