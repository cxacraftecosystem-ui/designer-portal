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
 * with a FRESH provider sign-in for this account (2026-10-17; it used to accept any session opened in
 * the last fifteen minutes). So the card first asks the person to confirm it's you with the provider
 * the account signs in with — Google's own button, or a round trip to Microsoft or Yahoo that comes
 * back here (`CONFIRM_STATE_PREFIX`) — and only then draws the password boxes. A proof the server
 * refuses as stale or mismatched (`providerConfirmationRequired`) is dropped and asked for again.
 * Either way the account keeps the way it signs in now.
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
import { TwoStepCodeEntry, type TwoStepCodeKind } from "@/components/TwoStepCodeEntry";
import { isCompleteCode } from "@/components/ui/otpCode";
import { setToken } from "@/lib/api";
import { isUnreachable } from "@/lib/failureTriage";
import { googleClientId, loadGoogleIdentity } from "@/lib/googleIdentity";
import {
  CONFIRM_STATE_PREFIX,
  beginSignIn,
  callbackErrorMessage,
  configuredOidcProviders,
  loginBody,
  parseCallbackFragment,
  takePendingSignIn
} from "@/lib/oidcSignIn";
import {
  CONFIRMED_NOTE,
  FIRST_PASSWORD_NOTE,
  PASSWORD_CHANGE_SESSIONS,
  type ConfirmProvider,
  canSetFirstPassword,
  confirmPrompt,
  confirmProviderFor,
  currentPasswordRefused,
  firstPasswordNeedsCode,
  newPasswordProblem,
  providerConfirmationRequired,
  twoFactorCodeRequired
} from "@/lib/passwordChange";
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  type ProviderProof,
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
  const { user, refreshMe } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  /** The code from the authenticator app — asked for only when a first password is set on an
   *  account with two-step verification on, so a session alone cannot add a password. */
  const [code, setCode] = useState("");
  const [codeKind, setCodeKind] = useState<TwoStepCodeKind>("totp");
  const [codeErrorKey, setCodeErrorKey] = useState(0);
  const [show, setShow] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** The receipt after a change that worked, or null. */
  const [changed, setChanged] = useState<string | null>(null);
  /** Refusals of the current password, counted so that EVERY one puts the caret back in its box. */
  const [currentRefusals, setCurrentRefusals] = useState(0);
  /** The provider's answer to "confirm it's you", held until the first password is sent. Never stored. */
  const [proof, setProof] = useState<ProviderProof | null>(null);
  /** Which provider the server last asked to confirm with, when it named one. */
  const [askedProvider, setAskedProvider] = useState<ConfirmProvider | null>(null);
  const currentBox = useRef<HTMLInputElement | null>(null);
  const section = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (currentRefusals > 0) currentBox.current?.focus();
  }, [currentRefusals]);

  // BACK FROM MICROSOFT OR YAHOO. `/login/callback` sends a confirmation here with the answer in the
  // fragment; it is taken off the address bar before anything else, because a code is single use.
  useEffect(() => {
    const callback = parseCallbackFragment(window.location.hash);
    if (!callback || !callback.state.startsWith(CONFIRM_STATE_PREFIX)) return;
    window.history.replaceState(null, "", window.location.pathname + window.location.search);
    const pending = takePendingSignIn(callback.state);
    if (pending && callback.code) {
      setProof(loginBody(pending, callback.code));
      setChanged(CONFIRMED_NOTE);
    } else {
      setError(callbackErrorMessage(pending?.provider ?? "MICROSOFT", callback.error));
    }
    section.current?.scrollIntoView({ block: "start" });
  }, []);

  if (!user) return null;
  // No password of its own: the same card asks only for the new one. Decided after every hook.
  const settingFirst = canSetFirstPassword(user);
  const askCode = settingFirst && firstPasswordNeedsCode(user);
  const confirmWith = askedProvider ?? confirmProviderFor(user);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setChanged(null);
    if (settingFirst && !proof) {
      setError(confirmPrompt(confirmWith));
      return;
    }
    const problem = settingFirst
      ? next !== confirm
        ? newPasswordProblem({ current: "", next, confirm })
        : null
      : newPasswordProblem({ current, next, confirm });
    if (problem) {
      setError(problem);
      return;
    }
    if (askCode && (codeKind === "totp" ? !isCompleteCode(code) : !code.trim())) {
      setError("Enter the code from your authenticator app to set a password.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const fresh =
        settingFirst && proof ? await setFirstPassword(next, proof, askCode ? code : undefined) : await changeOwnPassword(current, next);
      // Emptied, all three: a form still holding both passwords after it worked is a form somebody
      // walks away from with the secret sitting in it.
      setCurrent("");
      setNext("");
      setConfirm("");
      setCode("");
      setShow(false);
      setProof(null);
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
      const confirmAgain = settingFirst ? providerConfirmationRequired(err) : null;
      if (confirmAgain) {
        // Stale, or for another mailbox: drop it and ask again. The passwords are kept.
        setProof(null);
        setAskedProvider(confirmAgain);
      } else if (settingFirst && twoFactorCodeRequired(err)) {
        // The code was missing or wrong. Only the code is emptied: the passwords were not refused.
        setCode("");
        setCodeErrorKey((key) => key + 1);
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
    <section ref={section} className="panel scroll-mt-28 p-5">
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
        {settingFirst && !proof ? (
          <ConfirmItsYou
            provider={confirmWith}
            onProof={(value) => {
              setProof(value);
              setError(null);
              setChanged(CONFIRMED_NOTE);
            }}
            onError={setError}
          />
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

        {settingFirst && !proof ? null : (
        <>
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
          <div className="grid gap-1">
            <TwoStepCodeEntry
              idPrefix="settings-two-factor"
              kind={codeKind}
              onKindChange={setCodeKind}
              value={code}
              onChange={setCode}
              allowRecovery
              totpLabel="Code from your authenticator app"
              totpHint="Two-step verification is on for your account."
              error={codeErrorKey > 0 && !code}
              errorKey={codeErrorKey}
              disabled={saving}
            />
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
        </>
        )}
      </form>
    </section>
  );
}

/**
 * "Confirm it's you" before a first password: the provider's own sign-in, run again just now.
 *
 * GOOGLE draws Google's button (the same script `/login` uses, `lib/googleIdentity.ts`) and hands the
 * ID token straight back. MICROSOFT and YAHOO leave for the provider and come back to `/settings`
 * (`beginSignIn(…, "confirm")`), where the card picks the code up. A provider this build has not
 * configured cannot be confirmed with here, so the sentence names the way that still works.
 */
function ConfirmItsYou({
  provider,
  onProof,
  onError
}: {
  provider: ConfirmProvider;
  onProof: (proof: ProviderProof) => void;
  onError: (message: string) => void;
}) {
  const host = useRef<HTMLDivElement | null>(null);
  const [leaving, setLeaving] = useState(false);
  const onProofRef = useRef(onProof);
  const onErrorRef = useRef(onError);
  useEffect(() => {
    onProofRef.current = onProof;
    onErrorRef.current = onError;
  });
  const clientId = provider === "GOOGLE" ? googleClientId() : null;
  const oidc = provider === "GOOGLE" ? null : (configuredOidcProviders().find((item) => item.id === provider) ?? null);

  useEffect(() => {
    if (!clientId) return;
    let cancelled = false;
    loadGoogleIdentity()
      .then(() => {
        if (cancelled || !host.current || !window.google) return;
        window.google.accounts.id.initialize({
          client_id: clientId,
          callback: (response) => onProofRef.current({ googleIdToken: response.credential })
        });
        window.google.accounts.id.renderButton(host.current, {
          type: "standard",
          theme: "outline",
          size: "large",
          text: "continue_with",
          shape: "rectangular"
        });
      })
      .catch(() => {
        if (!cancelled) onErrorRef.current("Couldn't reach Google. Check your connection and try again.");
      });
    return () => {
      cancelled = true;
    };
  }, [clientId]);

  async function leaveForProvider() {
    if (!oidc) return;
    setLeaving(true);
    try {
      window.location.assign(await beginSignIn(oidc, window.location.origin, null, window.sessionStorage, "confirm"));
    } catch {
      setLeaving(false);
      onError(callbackErrorMessage(oidc.id, null));
    }
  }

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
      <p className="text-sm text-ink-700">{confirmPrompt(provider)}</p>
      {clientId ? <div ref={host} className="min-h-10" /> : null}
      {oidc ? (
        <div>
          <button type="button" className="field-button-secondary" disabled={leaving} onClick={() => void leaveForProvider()}>
            {leaving ? "Opening…" : `Continue with ${oidc.label}`}
          </button>
        </div>
      ) : null}
      {!clientId && !oidc ? (
        <p className="text-xs leading-5 text-ink-500">
          To add a password, ask an administrator to send you a link to set one.
        </p>
      ) : null}
    </div>
  );
}
