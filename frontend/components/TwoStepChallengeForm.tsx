"use client";

/**
 * The second step of a sign-in, drawn on the /login card in place of the sign-in controls.
 *
 * Reached after ANY first factor — the password, Google, Microsoft or Yahoo — when the account has
 * two-step verification on: `POST /auth/login` answered a challenge token instead of a session, and
 * this form trades it and a code for the session at `POST /auth/two-factor/verify`. Nobody is signed in
 * until that succeeds, so "Back to sign in" simply forgets the challenge.
 *
 * SIX BOXES FOR THE CODE, A SEPARATE BOX FOR A RECOVERY CODE (owner's rule, 2026-10-17). The
 * authenticator code goes in `OtpCodeInput` — six boxes, digits only, submitted the moment the sixth
 * lands — and "Use a recovery code instead" swaps in a plain text box for an `xxxx-xxxx` code
 * (`TwoStepCodeEntry`). The server still tells the two apart by shape.
 *
 * Every refusal is the server's sentence, in the card: a wrong code keeps the form, and a finished
 * challenge (five wrong codes, or five minutes) says to sign in again and offers the way back.
 */

import { useEffect, useRef, useState } from "react";
import { ShieldCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { TwoStepCodeEntry, type TwoStepCodeEntryHandle, type TwoStepCodeKind } from "@/components/TwoStepCodeEntry";
import { isCompleteCode } from "@/components/ui/otpCode";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api";
import { isUnreachable } from "@/lib/failureTriage";
import { CHALLENGE_ENDED_DETAIL } from "@/lib/twoFactor";
import type { User } from "@/lib/types";

export function TwoStepChallengeForm({
  challengeToken,
  onSignedIn,
  onCancel
}: {
  challengeToken: string;
  /** The account, signed in. The caller settles whatever else sign-in owes (consent, a password). */
  onSignedIn: (account: User) => void | Promise<void>;
  /** Back to the sign-in controls; the challenge is dropped. */
  onCancel: () => void;
}) {
  const { verifyTwoFactor } = useAuth();
  const [kind, setKind] = useState<TwoStepCodeKind>("totp");
  const useRecovery = kind === "recovery";
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errorKey, setErrorKey] = useState(0);
  /** The challenge is finished; only signing in again leads anywhere. */
  const [dead, setDead] = useState(false);
  const box = useRef<TwoStepCodeEntryHandle | null>(null);

  // On arrival: the six boxes are where the next keystroke belongs.
  useEffect(() => {
    box.current?.focus();
  }, []);

  async function submit(entered: string) {
    const typed = entered.trim();
    if (!typed || busy || dead) return;
    if (!useRecovery && !isCompleteCode(typed)) return;
    setBusy(true);
    setError(null);
    try {
      const account = await verifyTwoFactor(challengeToken, typed);
      await onSignedIn(account);
    } catch (err) {
      setCode("");
      setErrorKey((key) => key + 1);
      if (err instanceof ApiError && err.status === 401 && err.message === CHALLENGE_ENDED_DETAIL) setDead(true);
      setError(
        isUnreachable(err)
          ? "Couldn't connect. Check your connection and try again."
          : err instanceof Error
            ? err.message
            : "That code didn't work. Try again."
      );
      box.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-4">
      <div className="flex items-start gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-purple-950 text-purple-100">
          <ShieldCheck className="h-5 w-5" aria-hidden />
        </span>
        <div className="min-w-0">
          <h2 className="font-display text-lg font-bold text-ink-900">Two-step verification</h2>
          <p className="mt-0.5 text-sm leading-6 text-ink-500">
            {useRecovery
              ? "Enter one of the recovery codes you saved. Each code works once."
              : "Enter the 6-digit code from your authenticator app."}
          </p>
        </div>
      </div>

      {/* Mounted from first paint and emptied rather than removed, so the sentence is announced. */}
      <div
        role="alert"
        className={error ? "rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600" : "sr-only"}
      >
        {error ?? ""}
      </div>

      {dead ? (
        <Button type="button" size="auth" onClick={onCancel} className="w-full font-display text-base font-bold">
          Sign in again
        </Button>
      ) : (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void submit(code);
          }}
          className="grid gap-3"
        >
          <TwoStepCodeEntry
            ref={box}
            idPrefix="two-step"
            kind={kind}
            onKindChange={(next) => {
              setKind(next);
              setError(null);
            }}
            value={code}
            onChange={setCode}
            onComplete={(full) => void submit(full)}
            allowRecovery
            totpLabel="6-digit code"
            error={Boolean(error)}
            errorKey={errorKey}
            disabled={busy}
          />
          <Button
            type="submit"
            size="auth"
            disabled={busy || (useRecovery ? !code.trim() : !isCompleteCode(code))}
            className="w-full font-display text-base font-bold"
          >
            {busy ? "Checking…" : "Verify"}
          </Button>
        </form>
      )}

      <div className="flex flex-wrap items-center justify-end gap-2 text-sm">
        <button type="button" onClick={onCancel} className="text-ink-500 hover:text-ink-900">
          Back to sign in
        </button>
      </div>
    </div>
  );
}
