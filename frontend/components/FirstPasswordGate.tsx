"use client";

/**
 * THE FIRST-LOGIN PASSWORD FORM, WITH TWO HOSTS AND ONE VOCABULARY.
 *
 * ── WHY IT IS A COMPONENT AND NO LONGER A FUNCTION INSIDE `/login` ──────────────────────────────
 *
 * It was declared in `app/login/page.tsx` when it landed on 2026-08-31, and that was right while the
 * door was the only place the flag was read. It is not any more. The obligation belongs to the
 * ACCOUNT, not to the act of signing in: an administrator who resets somebody's password through
 * `PATCH /api/users/{id}` sets `mustChangePassword` on a session that is already open, and a person
 * who never revisits /login never meets the door. So `AppShell` reads the flag too, above the whole
 * protected tree.
 *
 * TWO HOSTS, ONE FORM, AND THAT IS THE POINT OF THE MOVE. A second copy in the protected tree would
 * be a second length floor, a second "the two passwords do not match", and a second sentence under
 * the boxes — three things to keep in step with `/set-password` and with the handset instead of one.
 * `frontend/e2e/protected-password-gate-unit.spec.ts` refuses the copy by name.
 *
 * ── IT IS AN AUTH SURFACE, SO IT USES THE AUTH CONTROLS ─────────────────────────────────────────
 *
 * `components/ui/button` had exactly one consumer (`/login`) and §11.2 of the frontend reference
 * says not to standardise a DATA screen onto it. This is not a data screen: it is the same 52px
 * stack of boxes as the sign-in card and `/set-password`, and it draws them with the same control so
 * the three cannot come to look like three different products. Nothing else here reaches for it.
 */

import { useEffect, useRef, useState } from "react";
import { Eye, EyeOff, Lock } from "lucide-react";

import { Button } from "@/components/ui/button";
import { setToken } from "@/lib/api";
import { isUnreachable } from "@/lib/failureTriage";
import {
  PASSWORD_CHANGE_PROMPT,
  PASSWORD_CHANGE_SESSIONS,
  currentPasswordRefused,
  newPasswordProblem
} from "@/lib/passwordChange";
import { MAX_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH, changeOwnPassword, passwordRuleLine } from "@/lib/signIn";

/**
 * THE FIRST-LOGIN PASSWORD, ASKED BETWEEN THE PERSON AND THE PRODUCT.
 *
 * This heading read "BETWEEN SIGN-IN AND THE DASHBOARD" while /login was the only host, which named
 * the moment rather than the rule and is exactly how the gap below the header came about.
 *
 * ── THE REQUIREMENT, AND WHAT WAS ACTUALLY MISSING ──────────────────────────────────────────────
 *
 * Owner: *"they would be able to set the password on their first login, and confirm it"*. Every
 * piece of the mechanism existed and nothing joined them up: `POST /api/users` creates an account
 * with `mustChangePassword` set, `serialize_user` puts the flag on every sign-in answer and every
 * `/me`, and `POST /auth/change-password` is the route it names — and NO SCREEN ON EITHER CLIENT
 * READ THE FLAG. An account created with a password an administrator typed signed in, worked
 * normally, and nobody was ever asked to replace a secret that by construction two people know.
 *
 * ── THE SERVER REFUSES TOO, BUT NOT AT THE DOOR ─────────────────────────────────────────────────
 *
 * The sign-in itself still succeeds, for the reason the column's comment gives: the only route that
 * can change a password needs a bearer token, so refusing the sign-in would leave the account
 * permanently unable to comply. What the server refuses since the owner's ruling is everything AFTER
 * it — every authenticated route outside a short allow-list answers 401 with
 * `X-Password-Change-Required` until this form has been satisfied — so a typed temporary password
 * cannot be used to work around the screen. This form is still where the person meets the demand;
 * the server is what makes skipping it pointless. `apiFetch` keeps the session on that refusal and
 * has `AuthProvider` re-read the account, which is how a tab that was already open comes to show
 * this form in place of whatever it was doing.
 *
 * ── WHY IT REPLACES ITS HOST RATHER THAN OPENING A DIALOG ───────────────────────────────────────
 *
 * A dialog is dismissible, and "you may set a password if you feel like it" is not the requirement.
 * The person holds a token by this point, so leaving the sign-in controls live underneath would also
 * offer them a second sign-in they neither need nor can usefully make — the argument
 * `StandingRefusal` already makes one branch up on that screen. The protected host takes the same
 * ruling one step further, because there the thing left live underneath would be the whole app: it
 * returns this form INSTEAD of the navigation island and the page, above `ROUTE_GUARDS` and above
 * admin view. Android's `PasswordGateScreen` is a `when` arm replacing `HomeScreen` for that reason.
 *
 * ── THE CURRENT PASSWORD IS USUALLY NOT ASKED FOR, AND SOMETIMES MUST BE ────────────────────────
 *
 * `POST /auth/change-password` requires it even for an account carrying this flag, and it is right
 * to: the flag means "the password you hold must be replaced", not "anybody at this keyboard may
 * replace it". On the ordinary path the person typed that password into the sign-in card ten seconds
 * ago, so asking for it again would be asking somebody to re-type a secret that screen is already
 * holding. Where the host is NOT holding one the box appears, because the alternative is a gate
 * whose only button cannot succeed. Three hosts are in that position and all three are ordinary: a
 * Google sign-in, a session that was already open when /login loaded, and the protected tree — which
 * by construction never saw a password, and is the case an administrator's reset actually produces.
 *
 * WHAT /login HANDS OVER IS THE PASSWORD OF THE LAST SIGN-IN THAT SUCCEEDED, never the live box:
 * after a failed attempt and a "Continue with Google" the box still held the wrong password, and the
 * gate hid itself behind it and sent it for ever. And the box appears the moment the server refuses
 * the one it was handed — cleared, because what was in it was wrong — so no host can leave somebody
 * at a form whose only button cannot succeed.
 *
 * ── AND IT HAS A WAY OUT, WHICH IS NOT A HEDGE ──────────────────────────────────────────────────
 *
 * "Sign out instead" is the escape `UsageConsentGateScreen` carries, for its reason: a person who
 * cannot complete this — they do not know the temporary password, the server is unreachable — must
 * not be held on one screen whose controls all do nothing. Signing out returns them to a door they
 * can use with a different account. It does not let anybody INTO the product.
 *
 * It matters MORE on the protected host than at the door, and this is the case to keep in mind when
 * touching either half: an administrator who resets a password without telling anybody leaves a
 * person who cannot fill this form in at all, because they do not know the current password. Their
 * route is the link the administrator issues, redeemed at `/set-password` — which is public and
 * lives OUTSIDE `app/(protected)/` precisely so that no gate in that tree can stand in front of it.
 */
export function FirstPasswordGate({
  currentPassword,
  onDone,
  onSignOut
}: {
  /**
   * The password of the last sign-in that SUCCEEDED on this visit, or "" where the host has none —
   * the Google path, a session that was already open, and the protected tree.
   */
  currentPassword: string;
  onDone: () => void;
  onSignOut: () => void;
}) {
  const [current, setCurrent] = useState(currentPassword);
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [show, setShow] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /**
   * How many times the server has refused the current password this form sent. Past zero the box is
   * drawn whatever the host handed over: what it handed over was wrong, and a hidden box holding a
   * wrong password is a form whose only button can never succeed. A count rather than a flag so that
   * every refusal, not only the first, puts the caret back in the box.
   */
  const [currentRefusals, setCurrentRefusals] = useState(0);
  const currentBox = useRef<HTMLInputElement | null>(null);
  // Asked where the host has nothing to offer — always so in the protected tree — and after any
  // refusal. Computed from the PROP and the refusal count and not from `current`, which the person is
  // about to type into: reading the state would make the box vanish under the caret on the first
  // keystroke.
  const askCurrent = currentPassword.length === 0 || currentRefusals > 0;

  useEffect(() => {
    if (currentRefusals > 0) currentBox.current?.focus();
  }, [currentRefusals]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // The same checks the Settings card makes, from the same function — see `lib/passwordChange.ts`.
    const problem = newPasswordProblem({ current, next, confirm });
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const fresh = await changeOwnPassword(current, next);
      // THE SESSION THIS FORM WAS SENT WITH IS RETIRED BY ITS OWN SUCCESS — the server binds a session
      // to the password it was opened with, which is what ends a session somebody else opened with
      // the temporary password. The fresh one arrives in the answer's `X-Session-Token` header and is
      // adopted BEFORE `onDone`, whose re-read of `/me` must go out with it or be refused. Null keeps
      // today's behaviour: an older server retired nothing, and a header the browser could not read
      // costs the person one sign-in with the password they have just chosen.
      if (fresh) setToken(fresh);
      onDone();
    } catch (err) {
      if (currentPasswordRefused(err)) {
        // Revealed AND emptied: the box was holding the password the server just refused.
        setCurrent("");
        setCurrentRefusals((count) => count + 1);
      }
      // The server's own sentence wins wherever it answered: it is the only text that knows whether
      // the current password was wrong, whether the account has no password to change at all, or
      // whether the new one was refused. Where it did not answer, "Failed to fetch" is the browser
      // talking — and the offline notice stays off this screen, so this is the only place it is said.
      setError(
        isUnreachable(err)
          ? "Could not reach the server. Check the connection and try again."
          : err instanceof Error
            ? err.message
            : "Could not set the password."
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-3">
      {/* `role="status"` and not `alert`: nothing has gone wrong and nothing is being refused — the
          person is signed in and one form away from the app. Same reading as `StandingRefusal`. */}
      <div role="status" className="rounded-md border border-line-200 bg-surface-50 p-3 text-sm leading-6 text-ink-700">
        {/* TERSE, AND IT NO LONGER SAYS WHO CAUSED IT: "An administrator set your password" was false
            the moment an administrator could require a change of a password its owner chose. Word for
            word the sentence the server refuses a gated request with. */}
        {PASSWORD_CHANGE_PROMPT}
      </div>
      {error ? (
        <div role="alert" className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
          {error}
        </div>
      ) : null}

      {askCurrent ? (
        <div className="grid gap-2">
          <label htmlFor="gate-current-password" className="text-sm font-medium text-ink-900">
            Current password
          </label>
          <div className="relative">
            <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
            <input
              id="gate-current-password"
              ref={currentBox}
              type={show ? "text" : "password"}
              autoComplete="current-password"
              required
              maxLength={MAX_PASSWORD_LENGTH}
              value={current}
              onChange={(event) => setCurrent(event.target.value)}
              className="field-input h-[52px] pl-10"
            />
          </div>
        </div>
      ) : null}

      <div className="grid gap-2">
        <label htmlFor="gate-new-password" className="text-sm font-medium text-ink-900">
          New password
        </label>
        <div className="relative">
          <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
          <input
            id="gate-new-password"
            type={show ? "text" : "password"}
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            maxLength={MAX_PASSWORD_LENGTH}
            value={next}
            onChange={(event) => setNext(event.target.value)}
            className="field-input h-[52px] pl-10 pr-11"
          />
          {/* ONE TOGGLE FOR EVERY BOX ON THIS FORM, the ruling `/set-password` already made: the
              pair is typed in sequence by one person checking one password against itself, and two
              independent eyes would let them be revealed separately — the one arrangement in which
              "they do not match" is still a mystery. `aria-pressed` because it is a toggle. */}
          <button
            type="button"
            aria-label={show ? "Hide password" : "Show password"}
            aria-pressed={show}
            onClick={() => setShow((value) => !value)}
            className="absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-ink-300 hover:text-ink-500"
          >
            {show ? <EyeOff size={22} aria-hidden /> : <Eye size={22} aria-hidden />}
          </button>
        </div>
      </div>

      <div className="grid gap-2">
        <label htmlFor="gate-confirm-password" className="text-sm font-medium text-ink-900">
          Repeat password
        </label>
        <div className="relative">
          <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
          <input
            id="gate-confirm-password"
            type={show ? "text" : "password"}
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            maxLength={MAX_PASSWORD_LENGTH}
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            className="field-input h-[52px] pl-10"
          />
        </div>
      </div>

      {/* The same first clause as `/set-password`, from the same function — see `passwordRuleLine`.
          The second clause differs because nobody here is holding a link. It said "Other devices stay
          signed in" until the server began binding every session to the password it was opened with
          (2026-10-09): changing it now ends every session but the one this form is handed back. */}
      <p className="text-xs leading-5 text-ink-500">{passwordRuleLine(PASSWORD_CHANGE_SESSIONS)}</p>

      <Button type="submit" size="auth" disabled={saving} className="mt-1 w-full font-display text-base font-bold">
        {saving ? "Saving…" : "Set password and continue"}
      </Button>
      <button type="button" className="field-button-secondary w-full" onClick={onSignOut}>
        Sign out instead
      </button>
    </form>
  );
}
