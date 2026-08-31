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

import { useState } from "react";
import { Eye, EyeOff, Lock } from "lucide-react";

import { Button } from "@/components/ui/button";
import { MIN_PASSWORD_LENGTH, changeOwnPassword, passwordRuleLine } from "@/lib/signIn";

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
 * ── IT REPORTS, WE REFUSE — THE CONSENT GATE'S OWN SHAPE ────────────────────────────────────────
 *
 * The server deliberately does not 403 the sign-in, for the reason the column's comment gives: the
 * only route that can change a password needs a bearer token, so refusing here would leave the
 * account permanently unable to comply. So this is the same arrangement as `StandingRefusal` in
 * `app/login/page.tsx` and as Android's `UsageConsentGateScreen`: the server states the fact, the
 * client is the gate.
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
 * to: the flag means "the password you hold was typed for you", not "anybody at this keyboard may
 * replace it". On the ordinary path the person typed that password into the sign-in card ten seconds
 * ago, so asking for it again would be asking somebody to re-type a secret that screen is already
 * holding. Where the host is NOT holding one the box appears, because the alternative is a gate
 * whose only button cannot succeed. Three hosts are in that position and all three are ordinary: a
 * Google sign-in, a session that was already open when /login loaded, and the protected tree — which
 * by construction never saw a password, and is the case an administrator's reset actually produces.
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
  /** The password typed at the door this visit, or "" where the host never saw one. */
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
  // Asked only where the host has nothing to offer — always so in the protected tree. Computed from
  // the PROP and not from `current`, which the person is about to type into: reading the state would
  // make the box vanish under the caret on the first keystroke.
  const askCurrent = currentPassword.length === 0;

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (next !== confirm) {
      setError("The two passwords do not match.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await changeOwnPassword(current, next);
      onDone();
    } catch (err) {
      // The server's own sentence wins: it is the only text that knows whether the current password
      // was wrong, whether the account has no password to change at all, or whether the new one was
      // refused — three different next moves behind one status code family.
      setError(err instanceof Error ? err.message : "Could not set the password.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-3">
      {/* `role="status"` and not `alert`: nothing has gone wrong and nothing is being refused — the
          person is signed in and one form away from the app. Same reading as `StandingRefusal`. */}
      <div role="status" className="rounded-md border border-line-200 bg-surface-50 p-3 text-sm leading-6 text-ink-700">
        {/* TERSE. The whole explanation is that somebody else chose the password they just used. */}
        An administrator set your password. Choose your own to continue.
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
              type={show ? "text" : "password"}
              autoComplete="current-password"
              required
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
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            className="field-input h-[52px] pl-10"
          />
        </div>
      </div>

      {/* The same first clause as `/set-password`, from the same function — see `passwordRuleLine`.
          The second clause differs because nobody here is holding a link, and because this route
          deliberately does NOT revoke sessions the way a link redemption does. */}
      <p className="text-xs leading-5 text-ink-500">{passwordRuleLine("Other devices stay signed in.")}</p>

      <Button type="submit" size="auth" disabled={saving} className="mt-1 w-full font-display text-base font-bold">
        {saving ? "Saving…" : "Set password and continue"}
      </Button>
      <button type="button" className="field-button-secondary w-full" onClick={onSignOut}>
        Sign out instead
      </button>
    </form>
  );
}
