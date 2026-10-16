"use client";

/**
 * "Change password" — replacing your own password whenever you like, not only when made to.
 *
 * ── WHY IT EXISTS ──────────────────────────────────────────────────────────────────────────────
 *
 * `POST /auth/change-password` had exactly one caller on the web, the first-password gate, so once
 * past it an account had no way to rotate a password it suspected somebody else knew except to ask
 * an administrator for a link. The accounts that sign in with a password are the ones this matters
 * for — the ones an administrator created and typed a password for, which by construction two people
 * knew — so the change form is drawn for an account with a password of its own (`hasOwnPassword`).
 *
 * ── AND "SET A PASSWORD" FOR AN ACCOUNT THAT HAS NONE ───────────────────────────────────────────
 *
 * An account that signs in with Google, Microsoft or Yahoo has no current password to type, so the
 * same card asks only for the new one (`setFirstPassword`) — the same route, which accepts that only
 * from a session opened in the last few minutes. An older session is answered 400 with
 * `X-Sign-In-Again` (`signInAgainRequired`), and the card then offers to sign out so the person can
 * sign straight back in and finish. Either way the account keeps the way it signs in now.
 *
 * ── THE SAME RULES AS THE GATE, FROM THE SAME PLACE ────────────────────────────────────────────
 *
 * The mismatch check, the same-password refusal and the reading of a refused current password come
 * from `lib/passwordChange.ts`, and the length floor and ceiling from `lib/signIn.ts`; the gate
 * imports the same ones. Two forms posting to one route must refuse the same things in the same words.
 *
 * ── A DATA-SCREEN CARD, NOT AN AUTH SURFACE ────────────────────────────────────────────────────
 *
 * It sits among Appearance and Accessibility, so it uses the recipes they use — `.panel`, the 8x8
 * icon chip, `.field-label`, `.field-input`, `.field-button` — and not the 52px auth stack the gate
 * draws. The frontend reference (§11.2) is explicit that a data screen is not standardised onto the
 * auth `Button`.
 */

import { useEffect, useRef, useState } from "react";
import { Eye, EyeOff, KeyRound } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { setToken } from "@/lib/api";
import { isUnreachable } from "@/lib/failureTriage";
import {
  FIRST_PASSWORD_NOTE,
  PASSWORD_CHANGE_SESSIONS,
  canSetFirstPassword,
  currentPasswordRefused,
  firstPasswordNeedsCode,
  newPasswordProblem,
  signInAgainRequired,
  twoFactorCodeRequired
} from "@/lib/passwordChange";
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  changeOwnPassword,
  passwordRuleLine,
  setFirstPassword
} from "@/lib/signIn";

/** The receipt, worded by what the server did — see `submit`. */
const CHANGED_HERE_ONLY = "Password changed. You are still signed in here; everywhere else, sign in with the new one.";
const CHANGED = "Password changed. Use the new one the next time you sign in.";
/** The receipt after a first password was set. */
const PASSWORD_ADDED = "Password set. You can now sign in with your email address and this password too.";

export function ChangePasswordCard() {
  const { user, refreshMe, logout } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  /** The code from the authenticator app — asked for only when a first password is set on an
   *  account with two-step verification on, so a session alone cannot add a password. */
  const [code, setCode] = useState("");
  const [show, setShow] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** The receipt after a change that worked, or null. */
  const [changed, setChanged] = useState<string | null>(null);
  /** Refusals of the current password, counted so that EVERY one puts the caret back in its box. */
  const [currentRefusals, setCurrentRefusals] = useState(0);
  /** The server asked for a fresh sign-in before a first password may be set. */
  const [needsSignIn, setNeedsSignIn] = useState(false);
  const currentBox = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (currentRefusals > 0) currentBox.current?.focus();
  }, [currentRefusals]);

  if (!user) return null;
  // No password of its own: the same card asks only for the new one. Decided after every hook.
  const settingFirst = canSetFirstPassword(user);
  const askCode = settingFirst && firstPasswordNeedsCode(user);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setChanged(null);
    setNeedsSignIn(false);
    const problem = settingFirst
      ? next !== confirm
        ? newPasswordProblem({ current: "", next, confirm })
        : null
      : newPasswordProblem({ current, next, confirm });
    if (problem) {
      setError(problem);
      return;
    }
    if (askCode && !code.trim()) {
      setError("Enter the code from your authenticator app to set a password.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const fresh = settingFirst ? await setFirstPassword(next, askCode ? code : undefined) : await changeOwnPassword(current, next);
      // Emptied, all three: a form still holding both passwords after it worked is a form somebody
      // walks away from with the secret sitting in it.
      setCurrent("");
      setNext("");
      setConfirm("");
      setCode("");
      setShow(false);
      if (fresh) {
        // THE TOKEN THIS TAB HOLDS WAS JUST RETIRED with every other session of the account — the
        // server binds a session to the password it was opened with. The fresh one came back in the
        // answer's `X-Session-Token` header; adopted before `/me` is re-read, or the re-read is
        // refused and this tab is signed out by its own change.
        setToken(fresh);
        void refreshMe();
        setChanged(settingFirst ? PASSWORD_ADDED : CHANGED_HERE_ONLY);
      } else if (settingFirst) {
        void refreshMe();
        setChanged(PASSWORD_ADDED);
      } else {
        // No session came back, so nothing here changes hands. An older server retired none; a
        // header the browser was not allowed to read means this tab's next request is refused and
        // the person signs in again — with the new password, which is what the receipt tells them.
        setChanged(CHANGED);
      }
    } catch (err) {
      if (settingFirst && signInAgainRequired(err)) {
        setNeedsSignIn(true);
      } else if (settingFirst && twoFactorCodeRequired(err)) {
        // The code was missing or wrong. Only the code is emptied: the passwords were not refused.
        setCode("");
      } else if (!settingFirst && currentPasswordRefused(err)) {
        // Emptied because what was in it is wrong. The new password and its repeat are kept: nothing
        // was said against them, and retyping a long one twice is how a typo gets saved.
        setCurrent("");
        setCurrentRefusals((count) => count + 1);
      }
      setError(
        isUnreachable(err)
          ? "Couldn't connect. Check your connection and try again."
          : err instanceof Error
            ? err.message
            : "Could not change the password."
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel p-5">
      {/* `PersonalSettingsCards.CardHeading`'s anatomy, copied because that component is private to
          its file — the same treatment `GrievanceRedressalCard` gives it. */}
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <KeyRound className="h-4 w-4" aria-hidden />
        </span>
        <h2 className="font-display font-bold text-ink-900">{settingFirst ? "Set a password" : "Change password"}</h2>
      </div>
      <p className="mt-1.5 text-xs leading-5 text-ink-500">
        {settingFirst
          ? "You sign in with Google, Microsoft or Yahoo. Add a password to sign in with your email address as well."
          : "The password you sign in with. You need the current one to choose another."}
      </p>

      <form onSubmit={submit} className="mt-3 grid max-w-md gap-3">
        {/* The account this password belongs to, for a password manager: without a username in the
            form it saves the new password against nothing, or against the wrong entry. Hidden from
            everybody else, assistive technology included. */}
        <input type="text" name="username" autoComplete="username" value={user.email} readOnly hidden />

        {/* BOTH REGIONS ARE MOUNTED FROM FIRST PAINT, with the class swapped to `sr-only` while empty —
            the stage page's pattern, argued above its own save regions. A region created together with
            its sentence announces nothing, so the receipt used to arrive in silence: the boxes emptied
            and the button went back to "Change password", and a screen-reader user could not tell
            success from a silent failure. Never `hidden`, which takes a region out of the tree. */}
        <div
          role="alert"
          aria-live="assertive"
          className={error ? "rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600" : "sr-only"}
        >
          {error ?? ""}
        </div>
        {needsSignIn ? (
          <div>
            <button type="button" className="field-button-secondary" onClick={() => void logout()}>
              Sign out and sign in again
            </button>
          </div>
        ) : null}
        <p
          role="status"
          aria-live="polite"
          className={changed ? "rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700" : "sr-only"}
        >
          {changed ?? ""}
        </p>

        {settingFirst ? null : (
        <div>
          <label htmlFor="settings-current-password" className="field-label">
            Current password
          </label>
          <input
            id="settings-current-password"
            ref={currentBox}
            type={show ? "text" : "password"}
            autoComplete="current-password"
            required
            maxLength={MAX_PASSWORD_LENGTH}
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
            className="field-input mt-1.5"
          />
        </div>
        )}

        <div>
          <label htmlFor="settings-new-password" className="field-label">
            New password
          </label>
          <div className="relative mt-1.5">
            <input
              id="settings-new-password"
              type={show ? "text" : "password"}
              autoComplete="new-password"
              required
              minLength={MIN_PASSWORD_LENGTH}
              maxLength={MAX_PASSWORD_LENGTH}
              value={next}
              onChange={(event) => setNext(event.target.value)}
              className="field-input pr-11"
            />
            {/* ONE TOGGLE FOR EVERY BOX, the gate's and `/set-password`'s ruling: two independent eyes
                would let the pair be revealed separately, the one arrangement in which "they do not
                match" is still a mystery. */}
            <button
              type="button"
              aria-label={show ? "Hide passwords" : "Show passwords"}
              aria-pressed={show}
              onClick={() => setShow((value) => !value)}
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-ink-300 hover:text-ink-500"
            >
              {show ? <EyeOff size={20} aria-hidden /> : <Eye size={20} aria-hidden />}
            </button>
          </div>
        </div>

        <div>
          <label htmlFor="settings-confirm-password" className="field-label">
            Repeat password
          </label>
          <input
            id="settings-confirm-password"
            type={show ? "text" : "password"}
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            maxLength={MAX_PASSWORD_LENGTH}
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            className="field-input mt-1.5"
          />
        </div>

        {askCode ? (
          <div>
            <label htmlFor="settings-two-factor-code" className="field-label">
              Code from your authenticator app
            </label>
            <input
              id="settings-two-factor-code"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              required
              maxLength={64}
              value={code}
              onChange={(event) => setCode(event.target.value)}
              className="field-input mt-1.5"
            />
            <p className="mt-1 text-xs leading-5 text-ink-500">
              Two-step verification is on for your account. A recovery code works here too.
            </p>
          </div>
        ) : null}

        {/* The gate's sentence, from the same function and the same constant: a change ends every
            session of the account but the one it hands back (`PASSWORD_CHANGE_SESSIONS`). */}
        <p className="text-xs leading-5 text-ink-500">
          {passwordRuleLine(settingFirst ? FIRST_PASSWORD_NOTE : PASSWORD_CHANGE_SESSIONS)}
        </p>

        <div>
          <button type="submit" className="field-button" disabled={saving}>
            {saving ? "Saving…" : settingFirst ? "Set password" : "Change password"}
          </button>
        </div>
      </form>
    </section>
  );
}
