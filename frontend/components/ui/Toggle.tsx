"use client";

import { useId, useState } from "react";

import { switchAria, toggleOutcome } from "@/components/ui/toggleKeys";

export const SWITCH_TRACK_ON = "border-purple-700 bg-purple-700 dark:border-purple-400 dark:bg-purple-400";
export const SWITCH_TRACK_OFF = "border-ink-500 bg-field-200";
export const SWITCH_THUMB_ON = "bg-white dark:bg-purple-950";
export const SWITCH_THUMB_OFF = "bg-ink-500";

export type ToggleProps = {
  checked?: boolean;
  defaultChecked?: boolean;
  onChange?: (next: boolean) => void;
  label: string;
  description?: React.ReactNode;
  status?: React.ReactNode;
  disabled?: boolean;
  busy?: boolean;
  busyLabel?: string;
  name?: string;
  id?: string;
  className?: string;
  testId?: string;
};

export function Toggle({
  checked: checkedProp,
  defaultChecked = false,
  onChange,
  label,
  description,
  status,
  disabled = false,
  busy = false,
  busyLabel,
  name,
  id,
  className = "",
  testId
}: ToggleProps) {
  const [inner, setInner] = useState(defaultChecked);
  const checked = checkedProp ?? inner;
  const generated = useId();
  const switchId = id ?? `toggle-${generated}`;
  const labelId = `${switchId}-label`;
  const descriptionId = `${switchId}-description`;
  const statusId = `${switchId}-status`;
  const inert = disabled || busy;

  function flip() {
    const next = toggleOutcome(checked, { disabled, busy });
    if (next === null) return;
    if (checkedProp === undefined) setInner(next);
    onChange?.(next);
  }

  const describedBy = [description ? descriptionId : null, status || (busy && busyLabel) ? statusId : null]
    .filter(Boolean)
    .join(" ");

  return (
    <div
      className={`flex min-h-11 items-center justify-between gap-4 rounded-md py-2 ${inert ? "" : "cursor-pointer"} ${className}`}
      onClick={(event) => {
        if ((event.target as HTMLElement).closest("button[role=switch]")) return;
        flip();
      }}
    >
      <div className="min-w-0">
        <span id={labelId} className={`block text-sm font-medium ${disabled ? "text-ink-500" : "text-ink-900"}`}>
          {label}
        </span>
        {description ? (
          <div id={descriptionId} className="mt-0.5 text-xs leading-5 text-ink-500">
            {description}
          </div>
        ) : null}
        {status || (busy && busyLabel) ? (
          <span id={statusId} className="mt-1 block text-xs font-medium leading-5 text-ink-700" aria-live="polite">
            {busy && busyLabel ? busyLabel : status}
          </span>
        ) : null}
      </div>
      <button
        type="button"
        {...switchAria(checked, busy)}
        id={switchId}
        data-testid={testId}
        aria-labelledby={labelId}
        aria-describedby={describedBy || undefined}
        disabled={disabled}
        onClick={(event) => {
          event.preventDefault();
          flip();
        }}
        className={`relative inline-flex h-7 w-12 shrink-0 items-center rounded-full border transition-colors duration-200 ease-out disabled:cursor-not-allowed disabled:opacity-60 ${
          checked ? SWITCH_TRACK_ON : SWITCH_TRACK_OFF
        } ${busy ? "opacity-70" : ""}`}
      >
        <span
          aria-hidden
          className={`inline-block h-5 w-5 rounded-full shadow-sm transition-transform duration-200 ease-out ${
            checked ? `translate-x-[22px] ${SWITCH_THUMB_ON}` : `translate-x-[3px] ${SWITCH_THUMB_OFF}`
          }`}
        />
      </button>
      {name && checked ? <input type="hidden" name={name} value="on" /> : null}
    </div>
  );
}

export function ToggleSwitch({
  checked,
  label,
  onChange,
  disabled,
  showLabel = false,
  className = ""
}: {
  checked: boolean;
  label: string;
  onChange: (next: boolean) => void;
  disabled?: boolean;
  showLabel?: boolean;
  className?: string;
}) {
  return (
    <button
      type="button"
      {...switchAria(checked, false)}
      aria-label={showLabel ? undefined : label}
      disabled={disabled}
      onClick={() => {
        const next = toggleOutcome(checked, { disabled });
        if (next !== null) onChange(next);
      }}
      className={`group inline-flex min-h-11 shrink-0 items-center gap-2 px-0.5 disabled:cursor-not-allowed disabled:opacity-60 ${className}`}
    >
      {showLabel ? <span className="text-xs font-medium text-ink-700">{label}</span> : null}
      <span
        aria-hidden
        className={`relative inline-flex h-7 w-12 items-center rounded-full border transition-colors duration-200 ease-out ${
          checked ? SWITCH_TRACK_ON : SWITCH_TRACK_OFF
        }`}
      >
        <span
          className={`inline-block h-5 w-5 rounded-full shadow-sm transition-transform duration-200 ease-out ${
            checked ? `translate-x-[22px] ${SWITCH_THUMB_ON}` : `translate-x-[3px] ${SWITCH_THUMB_OFF}`
          }`}
        />
      </span>
    </button>
  );
}
