"use client";

import { useEffect, useRef, useState } from "react";
import { Eye, EyeOff, KeyRound } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { GoogleSignInButton } from "@/components/GoogleSignInButton";
import { TwoStepCodeEntry, type TwoStepCodeKind } from "@/components/TwoStepCodeEntry";
import { isCompleteCode } from "@/components/ui/otpCode";
import { isUnreachable } from "@/lib/failureTriage";
import { googleClientId } from "@/lib/googleIdentity";
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

const CHANGED_HERE_ONLY = "Password changed. You are still signed in here; everywhere else, sign in with the new one.";
const CHANGED = "Password changed. Use the new one the next time you sign in.";
const PASSWORD_ADDED = "Password set. You can now sign in with your email address and this password too.";

export function ChangePasswordCard() {
  const { user, refreshMe } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [code, setCode] = useState("");
  const [codeKind, setCodeKind] = useState<TwoStepCodeKind>("totp");
  const [codeErrorKey, setCodeErrorKey] = useState(0);
  const [show, setShow] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [changed, setChanged] = useState<string | null>(null);
  const [currentRefusals, setCurrentRefusals] = useState(0);
  const [proof, setProof] = useState<ProviderProof | null>(null);
  const [askedProvider, setAskedProvider] = useState<ConfirmProvider | null>(null);
  const currentBox = useRef<HTMLInputElement | null>(null);
  const section = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (currentRefusals > 0) currentBox.current?.focus();
  }, [currentRefusals]);

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
      setCurrent("");
      setNext("");
      setConfirm("");
      setCode("");
      setShow(false);
      setProof(null);
      if (fresh) {
        void refreshMe();
        setChanged(settingFirst ? PASSWORD_ADDED : CHANGED_HERE_ONLY);
      } else if (settingFirst) {
        void refreshMe();
        setChanged(PASSWORD_ADDED);
      } else {
        setChanged(CHANGED);
      }
    } catch (err) {
      const confirmAgain = settingFirst ? providerConfirmationRequired(err) : null;
      if (confirmAgain) {
        setProof(null);
        setAskedProvider(confirmAgain);
      } else if (settingFirst && twoFactorCodeRequired(err)) {
        setCode("");
        setCodeErrorKey((key) => key + 1);
      } else if (!settingFirst && currentPasswordRefused(err)) {
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
        <input type="text" name="username" autoComplete="username" value={user.email} readOnly hidden />

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

function ConfirmItsYou({
  provider,
  onProof,
  onError
}: {
  provider: ConfirmProvider;
  onProof: (proof: ProviderProof) => void;
  onError: (message: string) => void;
}) {
  const [leaving, setLeaving] = useState(false);
  const onProofRef = useRef(onProof);
  const onErrorRef = useRef(onError);
  useEffect(() => {
    onProofRef.current = onProof;
    onErrorRef.current = onError;
  });
  const clientId = provider === "GOOGLE" ? googleClientId() : null;
  const oidc = provider === "GOOGLE" ? null : (configuredOidcProviders().find((item) => item.id === provider) ?? null);

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
      {clientId ? (
        <GoogleSignInButton
          clientId={clientId}
          onCredential={(credential) => onProofRef.current({ googleIdToken: credential })}
          onLoadError={() => onErrorRef.current("Couldn't reach Google. Check your connection and try again.")}
        />
      ) : null}
      {oidc ? (
        <div>
          <button type="button" className="field-button-secondary w-full" disabled={leaving} onClick={() => void leaveForProvider()}>
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
