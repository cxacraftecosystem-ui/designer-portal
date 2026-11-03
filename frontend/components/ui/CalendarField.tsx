"use client";

import { useId, useState } from "react";
import { X } from "lucide-react";

import { DateField } from "@/components/forms/DateTimeField";
import { RequiredMark } from "@/components/ui/RequiredMark";

export type CalendarFieldProps = {
  label?: string;
  ariaLabel?: string;
  value?: string;
  defaultValue?: string;
  onChange?: (iso: string) => void;
  min?: string;
  max?: string;
  minLabel?: string;
  maxLabel?: string;
  hint?: string;
  name?: string;
  required?: boolean;
  disabled?: boolean;
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
