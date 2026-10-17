"use client";

/**
 * `OtpCodeInput` — the ONE six-digit code entry in the app. Six separate boxes, digits only.
 *
 * Every surface that asks for a 6-digit code draws this and nothing else: the two-step verification
 * setup, the sign-in's second step, turning it off, making new recovery codes, setting a first password
 * on an account that has it on, and the Aadhaar OTP. Android's twin is `OtpCodeField`
 * (`ui/OtpCodeField.kt`), same rules, same words. A recovery code is NOT a six-digit code and never
 * goes in here — it has its own text box, reached by "Use a recovery code instead".
 *
 * THE RULES (owner's, 2026-10-17), each one here on purpose:
 *   · exactly six boxes; ASCII digits only — anything else is refused and the box does not change
 *     (`otpCode.typeInto`); `inputMode="numeric"`, `pattern="[0-9]*"` so phones open the number pad;
 *   · `autoComplete="one-time-code"` on the FIRST box, where a browser or an SMS autofill delivers all
 *     six at once — a string of six digits landing in any box fills them all, as does a paste;
 *   · typing advances, Backspace on an empty box steps back and empties that one, arrows move;
 *   · `onComplete` fires once, the moment the sixth digit lands — from the change handler, never from
 *     an effect, so a re-render cannot submit a code twice;
 *   · `error` paints every box red (the static signal) and `errorKey` — bump it on each refusal —
 *     shakes the row once; under EITHER reduced-motion switch (`useAppReducedMotion`) there is no
 *     shake and the red is the whole message;
 *   · each box is named "Digit n of 6"; the group carries the visible label.
 *   · Plus Jakarta Sans (`font-display`, the owner's pick for the code), tabular figures, large.
 *
 * Controlled: `value` is the digits typed so far (contiguous, at most six).
 */

import { forwardRef, useEffect, useId, useImperativeHandle, useRef } from "react";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import {
  OTP_LENGTH,
  backspaceAt,
  deleteAt,
  digitLabel,
  isCompleteCode,
  moveFocus,
  normaliseCode,
  otpShake,
  pasteInto,
  reachableBox,
  typeInto
} from "@/components/ui/otpCode";

export type OtpCodeInputHandle = {
  /** Put the caret in the first empty box. */
  focus: () => void;
};

export type OtpCodeInputProps = {
  value: string;
  onChange: (code: string) => void;
  /** Called with the six digits the moment the code is whole. */
  onComplete?: (code: string) => void;
  /** The visible label above the boxes. */
  label: string;
  /** A line under the boxes (hint or refusal). Linked to every box with aria-describedby. */
  hint?: string;
  /** Paint the boxes as refused. */
  error?: boolean;
  /** Bump on every refusal to shake the row again (ignored under reduced motion). */
  errorKey?: number;
  disabled?: boolean;
  autoFocus?: boolean;
  /** For a `<form>` that reads FormData: a hidden input under this name carries the code. */
  name?: string;
  id?: string;
  className?: string;
};

export const OtpCodeInput = forwardRef<OtpCodeInputHandle, OtpCodeInputProps>(function OtpCodeInput(
  { value, onChange, onComplete, label, hint, error = false, errorKey = 0, disabled = false, autoFocus = false, name, id, className = "" },
  ref
) {
  const reduce = useAppReducedMotion();
  const generated = useId();
  const baseId = id ?? `otp-${generated}`;
  const labelId = `${baseId}-label`;
  const hintId = `${baseId}-hint`;
  const code = normaliseCode(value);
  const boxes = useRef<Array<HTMLInputElement | null>>([]);
  const row = useRef<HTMLDivElement | null>(null);
  const reduceRef = useRef(reduce);
  useEffect(() => {
    reduceRef.current = reduce;
  });

  function focusBox(index: number) {
    const box = boxes.current[index];
    if (box) {
      box.focus();
      box.select();
    }
  }

  useImperativeHandle(ref, () => ({ focus: () => focusBox(reachableBox(code, code.length)) }), [code]);

  useEffect(() => {
    if (autoFocus) focusBox(0);
    // Mount only: re-running on every render would steal the caret back to box one.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The refusal shake. The Web Animations API rather than a framer transition because the row is a
  // plain element whose transform nothing else owns; the reduced-motion answer is read through a ref
  // so a preference settling after mount cannot replay the animation (field-repo-frontend §17).
  useEffect(() => {
    if (!errorKey) return;
    const shake = otpShake(reduceRef.current);
    if (!shake || !row.current || typeof row.current.animate !== "function") return;
    row.current.animate(
      shake.offsets.map((x) => ({ transform: `translateX(${x}px)` })),
      { duration: shake.duration, easing: shake.easing }
    );
  }, [errorKey]);

  function commit(next: string, focus: number) {
    if (next !== code) {
      onChange(next);
      if (isCompleteCode(next) && !isCompleteCode(code)) onComplete?.(next);
    }
    // After React has drawn the new digits, so the selection lands on the right character.
    requestAnimationFrame(() => focusBox(focus));
  }

  function handleKeyDown(index: number, event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Backspace") {
      event.preventDefault();
      const edit = backspaceAt(code, index);
      commit(edit.value, edit.focus);
    } else if (event.key === "Delete") {
      event.preventDefault();
      const edit = deleteAt(code, index);
      commit(edit.value, edit.focus);
    } else if (event.key === "ArrowLeft" || event.key === "ArrowRight" || event.key === "Home" || event.key === "End") {
      event.preventDefault();
      focusBox(moveFocus(code, index, event.key));
    } else if (event.key.length === 1 && !/[0-9]/.test(event.key) && !event.ctrlKey && !event.metaKey && !event.altKey) {
      // A printable key that is not a digit: refused before it can reach the box.
      event.preventDefault();
    }
  }

  return (
    <div className={`grid gap-1.5 ${className}`}>
      <span id={labelId} className="field-label">
        {label}
      </span>
      <div
        ref={row}
        role="group"
        aria-labelledby={labelId}
        aria-describedby={hint ? hintId : undefined}
        className="flex items-center gap-2 sm:gap-2.5"
      >
        {Array.from({ length: OTP_LENGTH }, (_, index) => {
          const digit = code[index] ?? "";
          return (
            <input
              key={index}
              ref={(node) => {
                boxes.current[index] = node;
              }}
              id={index === 0 ? baseId : undefined}
              type="text"
              inputMode="numeric"
              pattern="[0-9]*"
              autoComplete={index === 0 ? "one-time-code" : "off"}
              autoCorrect="off"
              spellCheck={false}
              enterKeyHint={index === OTP_LENGTH - 1 ? "done" : "next"}
              disabled={disabled}
              value={digit}
              aria-label={digitLabel(index)}
              aria-invalid={error || undefined}
              aria-describedby={hint ? hintId : undefined}
              onFocus={(event) => {
                // Never past the first empty box: the code has no holes.
                const reachable = reachableBox(code, index);
                if (reachable !== index) focusBox(reachable);
                else event.currentTarget.select();
              }}
              onKeyDown={(event) => handleKeyDown(index, event)}
              onPaste={(event) => {
                event.preventDefault();
                const edit = pasteInto(code, index, event.clipboardData.getData("text"));
                if (edit.accepted) commit(edit.value, edit.focus);
              }}
              onChange={(event) => {
                // What the box now holds minus the digit it held: a typed key, or an autofill of six.
                const raw = event.target.value;
                const typed = digit && raw.startsWith(digit) && raw.length > 1 ? raw.slice(1) : raw;
                const edit = typeInto(code, index, typed);
                if (edit.accepted) commit(edit.value, edit.focus);
              }}
              className={`h-14 w-11 min-w-0 rounded-md border bg-card text-center font-display text-2xl font-semibold tabular-nums text-ink-900 outline-hidden transition-colors sm:h-16 sm:w-13 sm:text-3xl ${
                error
                  ? "border-error-600 ring-4 ring-error-600/15"
                  : "border-line-200 focus:border-purple-600 focus:ring-4 focus:ring-purple-600/15"
              } disabled:cursor-not-allowed disabled:opacity-60`}
            />
          );
        })}
      </div>
      {name ? <input type="hidden" name={name} value={code} /> : null}
      {hint ? (
        <p id={hintId} className={`text-xs leading-5 ${error ? "text-error-600" : "text-ink-500"}`}>
          {hint}
        </p>
      ) : null}
    </div>
  );
});
