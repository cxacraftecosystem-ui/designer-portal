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
 *
 * Further down: the password rules spelled once (`MIN_PASSWORD_LENGTH`, `MAX_PASSWORD_LENGTH`), and
 * an account provisioner's calls behind /users — create a password account, require a new password,
 * set a temporary one, correct a name or an address.
 */

import { ApiError, apiFetch, apiFetchWithHeaders } from "@/lib/api";
import type { User, UserRole } from "@/lib/types";

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

/**
 * Mint a link for an account the caller provisions — an account provisioner, on an account it
 * manages, exactly as for `PATCH /users/{id}`. The SERVER picks the purpose: an INVITE (72 hours)
 * for an account with no password, or with one nobody has signed in with since it was created (an
 * account made after sign-ins began being recorded, late August 2026), and a RESET (2 hours)
 * otherwise — so a link for an account made this morning is not dead by lunchtime, while an
 * established account that merely predates the record gets the short one. A Google-only account (no
 * password) is a 422 — giving it a password is a decision, taken through "Set temporary password".
 */
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
 * ── THE TOKEN GOES IN THE BODY, NOT THE ADDRESS (2026-10-09) ──────────────────────────────────
 *
 * `POST /auth/set-password/check` with `{"token": …}` answers exactly what
 * `GET /auth/set-password?token=…` answers. The GET put the link's whole authority on the request
 * line, and a request line is what nginx, a CDN and every other access log in front of the API write
 * down; a body is not. The GET is asked ONLY when the POST is answered 404 or 405 — an API older than
 * the POST, which has no such route — and never after any other failure: a 5xx, a refusal or an
 * unreachable server says nothing about which door exists, and asking again the old way would put the
 * token on a request line for nothing. The page reads whatever this throws as "not examined", never
 * as "dead". Delete the fallback once every API this web build can be pointed at serves the POST.
 *
 * `redirectOn401: false` because this route is PUBLIC and the person holding the link is by
 * definition not signed in — the default 401 redirect would bounce them to /login, which is exactly
 * the page they cannot use.
 */
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

/**
 * The token a password link carries, and the address to show once it is taken out.
 *
 * ── FRAGMENT FIRST, QUERY SECOND ──────────────────────────────────────────────────────────────
 *
 * A fragment (`/set-password#token=…`) is never sent to any server — not in the request for the page,
 * not in a `Referer` — so it is where the token belongs, and it is read first. The query
 * (`/set-password?token=…`) is read second because it is what every link issued so far carries:
 * `credential_links.link_for` on the server writes it, and goes on writing it until the handset
 * builds that read only the query have left the field (docs/OPEN_FINDINGS.md). Both are read with
 * `URLSearchParams`, as `useSearchParams` read the query before.
 *
 * `address` is the same path with `token` removed from the query and the fragment, everything else
 * kept, for `history.replaceState`: the token then leaves the address bar and the tab's history entry,
 * so it is not in a copied address, a bookmark, a screenshot or what Back returns to. Null when there
 * was no token in either place, so nothing is rewritten for nothing. PURE — it reads the string it is
 * given and touches no `window` — so `set-password-link-token-unit.spec.ts` can drive it in Node.
 */
export function takeLinkTokenFromAddress(href: string): { token: string; address: string | null } {
  const url = new URL(href);
  const fragment = new URLSearchParams(url.hash.slice(1));
  const inFragment = fragment.has("token");
  const inQuery = url.searchParams.has("token");
  if (!inFragment && !inQuery) return { token: "", address: null };
  const token = fragment.get("token") || url.searchParams.get("token") || "";
  url.searchParams.delete("token");
  // A fragment that held no token is kept exactly as it was: re-serialising `#section` would write
  // `#section=`.
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

/**
 * The header the fresh session arrives in after a password change. Spelled once; lower-case because
 * `Headers.get` is case-insensitive, and the server spells it `X-Session-Token`.
 *
 * A HEADER AND NOT A FIELD IN THE BODY, and the reason is a client already in people's hands. Android
 * builds 0.0.6–0.0.15 decode the change-password answer as `Map<String, Boolean>`, so any string beside
 * `ok` makes the decode throw — and the message they then put on screen, under "the change failed",
 * carries the token itself, although the password has changed. So the body stays exactly
 * `{"ok": true}`, as it was before sessions were bound to passwords, and nothing here reads it.
 *
 * IT MUST BE IN THE API'S CORS `expose_headers` (`backend/app/main.py`, beside
 * `X-Password-Change-Required`), or a cross-origin browser hides it: this tab then keeps the token the
 * change has just retired, its next request is one plain 401, and the person signs in again with the
 * password they have just chosen. Safe, and one sign-in dearer.
 */
export const SESSION_TOKEN_HEADER = "x-session-token";

/**
 * The signed-in account replacing its own password — the route `mustChangePassword` sends you to, and
 * the Settings card's.
 *
 * RETURNS THE SESSION TO CARRY ON WITH, or null. Since 2026-10-09 every session token carries the
 * fingerprint of the password it was opened with, and the server refuses one whose password has since
 * changed — so this change retires the very token that sent it, with every other session of the
 * account (which is the point: a session opened with a temporary password somebody else knew must not
 * outlive it). The answer carries a fresh token minted AFTER the write, in {@link SESSION_TOKEN_HEADER}
 * and never in the body. The caller adopts it with `setToken` BEFORE anything re-reads `/me`, or that
 * re-read is the first request refused and the person is signed out the moment they complied.
 *
 * NULL HAS TWO CAUSES AND ONE ANSWER. A server older than the rule sends no header and has retired
 * nothing, so "carry on with the token you have" is all a caller did before. A header the browser was
 * not allowed to read is the other, and that server HAS retired the token — the next request is a
 * plain 401 and the person signs in again with the new password. Neither is improved by guessing, and
 * a token is never dug out of the body, whatever a server puts there.
 */
export async function changeOwnPassword(currentPassword: string, newPassword: string): Promise<string | null> {
  const { headers } = await apiFetchWithHeaders<unknown>("/auth/change-password", {
    method: "POST",
    body: JSON.stringify({ currentPassword, newPassword })
  });
  const token = headers.get(SESSION_TOKEN_HEADER)?.trim();
  return token ? token : null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Provisioning — an account provisioner's half of a password account
 *
 * `canProvisionAccounts` in lib/permissions.ts decides who may call these; the server decides again,
 * per target (`assert_can_manage_target` in backend/app/services/account_provisioning.py), and its
 * sentence is what a screen shows on a 403, 409 or 422. EVERY PASSWORD BELOW IS SENT EXACTLY AS TYPED: `lib/forms.requiredText` trims, and a
 * password stored trimmed while its owner pastes the untrimmed original is refused at sign-in for
 * ever, with nothing on either screen to say why.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `POST /api/users`. `mustChangePassword` is sent always rather than left to the server's default,
 * so what was ticked on the form is what happened. The capability grants are an ADMIN's to send: a
 * provisioner who is not an admin is refused with a 403 for any of them set true, so a caller leaves
 * them out entirely rather than sending `false` it has no business deciding.
 */
export type NewPasswordAccount = {
  name: string;
  email: string;
  role: UserRole;
  password: string;
  mustChangePassword: boolean;
  canManageQuestionnaire?: boolean;
  canDownloadDataset?: boolean;
};

export async function createPasswordAccount(account: NewPasswordAccount): Promise<User> {
  return apiFetch<User>("/users", { method: "POST", body: JSON.stringify(account) });
}

/**
 * "Require a new password at next sign-in" on an account that already HAS one — its password is not
 * touched. Raising the flag signs the person out everywhere (the server stamps `sessionsValidFrom`),
 * so the screen asking for this must say so. A 422 means the account has no password to replace.
 */
export async function requirePasswordChange(userId: string): Promise<User> {
  return apiFetch<User>(`/users/${userId}`, {
    method: "PATCH",
    body: JSON.stringify({ mustChangePassword: true })
  });
}

/**
 * Set a password FOR somebody else, and say whether they must replace it at their next sign-in.
 * Sent explicitly in both directions, because the server's default for a password set for someone
 * else is `true` and a `false` that was never sent would quietly be a `true`. Either way it signs the
 * person out everywhere. Never for one's own account — that is a 403 pointing at change-password.
 */
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

/**
 * Correct the name or the sign-in address of an account the caller provisions. Send only what
 * changed. A corrected address takes the account's place on the allow-list with it; a 409 is either
 * an address another account holds or — for a provisioner who is not an admin — one an admin has
 * refused or suspended, and the server's sentence says which.
 */
export async function correctAccountDetails(
  userId: string,
  changes: { name?: string; email?: string }
): Promise<User> {
  return apiFetch<User>(`/users/${userId}`, { method: "PATCH", body: JSON.stringify(changes) });
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
 * The longest password this product will store — ONE ceiling, on every route that takes one.
 *
 * Creation and an admin's update used to accept 256 while change-password and set-password stopped
 * at 200, so a 210-character temporary password signed in and could never be typed into the box
 * that replaces it: the flag could then be cleared only through a link. Every password box on the
 * web carries this as its `maxLength`. (bcrypt reads only the first 72 bytes either way; the ceiling
 * is about agreement between routes, not about strength.)
 */
export const MAX_PASSWORD_LENGTH = 200;

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
 * ── THE SERVER HOLDS THE ACCOUNT; THE CLIENT IS WHERE IT CAN COMPLY ───────────────────────────
 *
 * `POST /auth/login` still mints a token for an account carrying this flag, and that is not an
 * oversight: the only route that can change a password (`POST /auth/change-password`) needs a bearer
 * token, so refusing the sign-in would leave the account permanently unable to comply. What that
 * token opens while the flag stands is a short allow-list — `/me`, change-password, sign-out, the
 * usage-consent pair and the release check — and every other authenticated route answers 401 with
 * `X-Password-Change-Required: 1` (a 401 and never a 403: both clients keep queued work on a 401
 * and give it up on a 403). The configured break-glass master is exempt. So this predicate no
 * longer decides WHETHER the account is held — the server does — only whether to put the one screen
 * in front of it that lets the person comply, rather than a page whose every request fails.
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
