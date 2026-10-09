/**
 * CHANGING YOUR OWN PASSWORD — THE RULES BOTH FORMS ENFORCE BEFORE ANYTHING IS SENT.
 *
 * Two forms call `POST /auth/change-password`: `FirstPasswordGate`, which an account owing a new
 * password meets on `/login` and above the whole protected tree, and the "Change password" card in
 * Settings, which any account that has a password can use whenever it likes. They must refuse the
 * same things in the same words, so the refusals live here and both import them — a second copy of
 * "the two passwords do not match" is how two screens come to enforce different rules on the day
 * the server's change. The length floor and ceiling are not here: they are every password box's,
 * not only these two forms', and live beside each other in `lib/signIn.ts`.
 *
 * Pure, and free of React, so the unit specs call the rules themselves
 * (`e2e/first-login-password-unit.spec.ts`, `e2e/change-password-card-unit.spec.ts`). Android's
 * `newPasswordRefusal` (`ui/PasswordSetupCopy.kt`) says the same sentences, word for word.
 */

import { ApiError } from "@/lib/api";
import type { User } from "@/lib/types";

/**
 * Has this account a password of its own to change? The Settings card's whole gate.
 *
 * `passwordSetAt` and not `authProvider`: a password account that has also signed in with Google is
 * still a password account (the owner's ruling keeps its hash and its LOCAL provider), and the
 * column was backfilled from `createdAt` for every account that already had a hash, so its absence
 * really does mean "never had one". `!= null` on purpose — a server older than the column sends
 * nothing, and the card must not offer a change the server would refuse with "This account has no
 * password to change".
 */
export function hasOwnPassword(user: Pick<User, "passwordSetAt"> | null | undefined): boolean {
  return user?.passwordSetAt != null;
}

/**
 * What the gate says, and what the server says when it refuses a gated request: one sentence.
 *
 * NEUTRAL ON PURPOSE. It used to read "An administrator set your password. Choose your own to
 * continue." — true when an administrator had typed the password, and false the moment the flag can
 * be raised on a password its owner chose (an administrator's "require a new password at next sign-
 * in"). The demand is the same either way; who caused it is not the person's problem to be told.
 */
export const PASSWORD_CHANGE_PROMPT = "Choose a new password to continue.";

/**
 * What changing a password does to the account's other sessions, said under the boxes of both forms.
 *
 * Since 2026-10-09 the server binds every session token to the password it was opened with, so ANY
 * change ends every session of the account but the fresh one the change answers with — which the
 * form adopts (`changeOwnPassword`). That is what retires a session somebody else opened with a
 * temporary password, and it is also the honest answer to "will my tablet in the next room notice":
 * it will, at its next request, and signs in again with the new password. Both forms said "Other
 * devices stay signed in" until then, which was true only while a session outlived its password.
 */
export const PASSWORD_CHANGE_SESSIONS = "You stay signed in here, and are signed out everywhere else.";

/**
 * Why this change must not be sent, or null when it may be.
 *
 * THE SAME-PASSWORD REFUSAL IS THE ONE THAT MATTERS. The gate exists to retire a secret somebody else
 * knows — the administrator who typed it, and whatever chat it was sent over — and typing that same
 * secret into "New password" used to satisfy it: the server compared nothing, cleared the flag and
 * kept the password. The server refuses it now too (a 400, not charged to the guessing budget);
 * this is the half that says so before a request is spent on it, in both forms.
 */
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

/**
 * Did `POST /auth/change-password` refuse the CURRENT password this form sent?
 *
 * A 400 is how the server answers it — not a 401, which would have been read as a dead session and
 * cost the person their sign-in for one typo. On this route a 400 is about the current password once
 * the same-password case has been refused before sending; the only other 400 is "this account has no
 * password to change", where clearing a box costs nothing. A 422 (a length rule) and a 429 (the
 * guessing budget) say nothing against the current password, so the box is left as it was.
 */
export function currentPasswordRefused(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400;
}
