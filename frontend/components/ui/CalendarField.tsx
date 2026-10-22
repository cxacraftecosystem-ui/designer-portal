"use client";

/**
 * `CalendarField` — the ONE date control: a labelled field you can type into (dd/mm/yyyy) or open as a
 * calendar, with min/max and a Clear button. Android's twin is `CalendarField` (`ui/CalendarField.kt`).
 *
 * It is `DateField` (components/forms/DateTimeField.tsx) with the parts every call site kept
 * re-adding around it: a visible label wired to the box, an optional hint, and Clear. The date rules
 * are DateField's and are not restated here — above all, the value is a LOCAL `yyyy-mm-dd` built by
 * `toIsoDate`/`fromIsoDate`, never `new Date("2026-07-20")` (UTC midnight, the previous day west of
 * Greenwich; field-repo-frontend §12.7), and a typed date outside min/max is held and explained, not
 * silently reverted.
 *
 * Never `<input type="date">`: a native date input formats itself from the browser's locale, so the
 * same build reads 03/04 as two different months on two laptops.
 *
 * Controlled (`value` + `onChange`) or uncontrolled (`defaultValue`, and `name` for FormData — the
 * hidden input carries the `yyyy-mm-dd`). The label is a `<span>` tied by id, not a wrapping
 * `<label>`: a wrapping label folds the calendar button's name into the input's (§12.3).
 */

import { useId, useState } from "react";
import { X } from "lucide-react";

import { DateField } from "@/components/forms/DateTimeField";
import { RequiredMark } from "@/components/ui/RequiredMark";

export type CalendarFieldProps = {
  /** The visible label. Omit it only where the caller draws its own `<label htmlFor={id}>` or passes `ariaLabel`. */
  label?: string;
  /** The accessible name when there is no visible label (the shared questionnaire screens). */
  ariaLabel?: string;
  value?: string;
  defaultValue?: string;
  onChange?: (iso: string) => void;
  /** `yyyy-mm-dd`, local. */
  min?: string;
  max?: string;
  minLabel?: string;
  maxLabel?: string;
  hint?: string;
  name?: string;
  required?: boolean;
  disabled?: boolean;
  /** Offer Clear while a date is set. Default true; a required field still may be cleared and refused on save. */
  clearable?: boolean;
  invalid?: boolean;
  id?: string;
  className?: string;
};

export function CalendarField({
  label,
  ariaLabel,
  value,
  defaultValue,
  onChange,
  min,
  max,
  minLabel,
  maxLabel,
  hint,
  name,
  required,
  disabled,
  clearable = true,
  invalid,
  id,
  className = ""
}: CalendarFieldProps) {
  const generated = useId();
  const fieldId = id ?? `calendar-${generated}`;
  const hintId = `${fieldId}-hint`;
  const controlled = value !== undefined;
  const [inner, setInner] = useState(defaultValue ?? "");
  const iso = controlled ? value : inner;

  function set(next: string) {
    if (!controlled) setInner(next);
    onChange?.(next);
  }

  return (
    <div className={`grid min-w-0 gap-1.5 ${className}`}>
      {label ? (
        <label htmlFor={fieldId} className="field-label">
          {label}
          {/* The same asterisk `Field` draws, so a required date reads like every other required box. */}
          <RequiredMark when={required} />
        </label>
      ) : null}
      <div className="flex min-w-0 items-start gap-2">
        <div className="min-w-0 flex-1">
          <DateField
            id={fieldId}
            name={name}
            value={iso}
            onChange={set}
            min={min}
            max={max}
            minLabel={minLabel}
            maxLabel={maxLabel}
            required={required}
            disabled={disabled}
            invalid={invalid}
            ariaLabel={label ? undefined : ariaLabel}
            describedBy={hint ? hintId : undefined}
          />
        </div>
        {clearable && iso && !disabled ? (
          <button
            type="button"
            className="field-button-secondary min-h-11 shrink-0 px-3 sm:min-h-10"
            onClick={() => set("")}
            aria-label={`Clear ${label ?? ariaLabel ?? "date"}`}
          >
            <X className="h-4 w-4" aria-hidden />
            <span className="hidden sm:inline">Clear</span>
          </button>
        ) : null}
      </div>
      {hint ? (
        <p id={hintId} className="text-xs leading-5 text-ink-500">
          {hint}
        </p>
      ) : null}
    </div>
  );
}
