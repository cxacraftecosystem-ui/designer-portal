"use client";

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
  focus: () => void;
};

export type OtpCodeInputProps = {
  value: string;
  onChange: (code: string) => void;
  onComplete?: (code: string) => void;
  label: string;
  hint?: string;
  error?: boolean;
  errorKey?: number;
  disabled?: boolean;
  autoFocus?: boolean;
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
