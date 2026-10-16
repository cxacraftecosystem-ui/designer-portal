"use client";

/**
 * The code half of every two-step verification form: the six boxes (`OtpCodeInput`), and — where a
 * recovery code is also accepted — a SEPARATE text box for one, reached by "Use a recovery code
 * instead". Used by the sign-in's second step, turning two-step verification off, and setting a first
 * password on an account that has it on. Android's twin is `TwoStepCodeEntry` in
 * `ui/TwoStepVerification.kt`.
 *
 * Two boxes, not one box that takes either: a six-digit code is digits only and goes in six boxes
 * (owner's rule), and a recovery code is `xxxx-xxxx` letters and digits — no single control can be
 * honest about both. Switching kind empties the box: half a code of one kind is not half of the other.
 */

import { forwardRef, useEffect, useImperativeHandle, useRef } from "react";

import { OtpCodeInput, type OtpCodeInputHandle } from "@/components/ui/OtpCodeInput";

export type TwoStepCodeKind = "totp" | "recovery";

export type TwoStepCodeEntryHandle = { focus: () => void };

export const TwoStepCodeEntry = forwardRef<
  TwoStepCodeEntryHandle,
  {
    kind: TwoStepCodeKind;
    onKindChange: (kind: TwoStepCodeKind) => void;
    value: string;
    onChange: (value: string) => void;
    /** The six digits are in: submit without a button press. Not called for recovery codes. */
    onComplete?: (code: string) => void;
    /** Offer "Use a recovery code instead". */
    allowRecovery: boolean;
    totpLabel?: string;
    totpHint?: string;
    error?: boolean;
    errorKey?: number;
    disabled?: boolean;
    autoFocus?: boolean;
    idPrefix: string;
  }
>(function TwoStepCodeEntry(
  {
    kind,
    onKindChange,
    value,
    onChange,
    onComplete,
    allowRecovery,
    totpLabel = "6-digit code from your authenticator app",
    totpHint,
    error = false,
    errorKey = 0,
    disabled = false,
    autoFocus = false,
    idPrefix
  },
  ref
) {
  const otp = useRef<OtpCodeInputHandle | null>(null);
  const recovery = useRef<HTMLInputElement | null>(null);
  const showRecovery = allowRecovery && kind === "recovery";

  // Switching kind puts the caret in the box that just appeared — the next keystroke belongs there.
  const switched = useRef(false);
  useEffect(() => {
    if (!switched.current) {
      switched.current = true;
      return;
    }
    if (showRecovery) recovery.current?.focus();
    else otp.current?.focus();
  }, [showRecovery]);

  useImperativeHandle(ref, () => ({
    focus: () => (showRecovery ? recovery.current?.focus() : otp.current?.focus())
  }));

  return (
    <div className="grid gap-2">
      {showRecovery ? (
        <div className="grid gap-1.5">
          <label htmlFor={`${idPrefix}-recovery`} className="field-label">
            Recovery code
          </label>
          <input
            id={`${idPrefix}-recovery`}
            ref={recovery}
            type="text"
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            maxLength={20}
            placeholder="xxxx-xxxx"
            disabled={disabled}
            aria-invalid={error || undefined}
            aria-describedby={`${idPrefix}-recovery-hint`}
            value={value}
            onChange={(event) => onChange(event.target.value)}
            className={`field-input max-w-64 font-display text-lg tracking-[0.15em] ${error ? "border-error-600" : ""}`}
          />
          <p id={`${idPrefix}-recovery-hint`} className="text-xs leading-5 text-ink-500">
            One of the recovery codes you saved when you turned this on. Each one works once.
          </p>
        </div>
      ) : (
        <OtpCodeInput
          ref={otp}
          id={`${idPrefix}-otp`}
          label={totpLabel}
          hint={totpHint}
          value={value}
          onChange={onChange}
          onComplete={onComplete}
          error={error}
          errorKey={errorKey}
          disabled={disabled}
          autoFocus={autoFocus}
        />
      )}
      {allowRecovery ? (
        <button
          type="button"
          disabled={disabled}
          onClick={() => {
            onChange("");
            onKindChange(showRecovery ? "totp" : "recovery");
          }}
          className="inline-flex min-h-11 w-fit items-center text-sm font-medium text-purple-700 hover:underline disabled:opacity-60 dark:text-purple-300 sm:min-h-9"
        >
          {showRecovery ? "Use your authenticator app instead" : "Use a recovery code instead"}
        </button>
      ) : null}
    </div>
  );
});
