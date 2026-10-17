"use client";

/**
 * `Toggle` — the ONE on/off control for a SETTING. Android's twin is `AppToggle` (`ui/AppToggle.kt`).
 *
 * A setting is something that stays switched: two-step verification, reduced motion, "must change
 * password at next sign-in", a capability flag. It is never drawn as the word "On"/"Off" beside a
 * button, and never as a checkbox — a checkbox is for choosing ROWS (select these three records),
 * which is a different act with a different control.
 *
 * What it is: a real `<button role="switch" aria-checked>` inside a full-width row whose label and
 * description are part of the click target, so the whole row toggles (≥44px tall on a phone). Space
 * and Enter both flip it — natively, because it is a real button (`toggleKeys.ts` says why there is
 * no keydown handler; `e2e/toggle-semantics-unit.spec.ts` pins the state rules). The
 * global `:focus-visible` outline draws the keyboard mark. The thumb slides with a CSS transition,
 * which both reduced-motion blocks in globals.css already zero — no JS branch needed.
 *
 * `busy` is the in-between state a switch must be able to show honestly: the two-step verification
 * switch reads "Setting up…" while the server has not yet confirmed a code, and is NOT checked. A busy
 * toggle is `aria-busy`, still announces its real `checked`, and refuses clicks.
 *
 * Controlled (`checked` + `onChange`) or uncontrolled (`defaultChecked`). For a `<form>` read through
 * FormData pass `name`: while ON a hidden input carries "on" and while OFF there is none — exactly
 * what a checkbox submits, so every `form.get(name) === "on"` reader keeps working when a setting
 * checkbox becomes this switch.
 */

import { useId, useState } from "react";

import { switchAria, toggleOutcome } from "@/components/ui/toggleKeys";

/**
 * THE TRACK AND THUMB COLOURS, measured (WCAG 1.4.11 asks 3:1 for the parts that say the state):
 *
 * - Off: an ink-500 edge on a surface-200 track — 6.3:1 against the card in light, 6.4:1 in dark —
 *   with an ink-500 thumb (5.1:1 on the track). The old line-200 edge and white thumb were 1.2:1 on
 *   a white card, so an Off switch was barely there at all.
 * - On: purple-700 with a white thumb in light (7.6:1 on the card). In dark purple-700 is 2.3:1 on
 *   the card, so the track moves up the ramp to purple-400 (7.2:1) with a purple-950 thumb (6.7:1) —
 *   the same pair Android's dark scheme uses for `primary` / `onPrimary`.
 *
 * Exported so `toggle-semantics-unit.spec.ts` measures the very classes drawn here.
 */
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
  /** A short state line drawn under the description — "On since 17 Oct 2026 · 8 recovery codes left". */
  status?: React.ReactNode;
  disabled?: boolean;
  /** Mid-change: shown with `busyLabel`, clicks refused, `checked` still the truth. */
  busy?: boolean;
  busyLabel?: string;
  name?: string;
  id?: string;
  className?: string;
  /** `data-testid` for the switch element itself. */
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
        // The row toggles too; a click ON the switch reaches it directly and must not flip twice.
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

/**
 * The switch alone, for a row that already lays out its own label (the Settings cards, the
 * questionnaire capture controls). Same element, same semantics — `role="switch"` + `aria-checked`,
 * named by `label` — and the same 44px phone target, drawn with padding around the 28px track. New
 * code uses `Toggle` above, whose whole row is the target.
 */
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
  /**
   * Draw `label` beside the track, inside the switch, for chrome too tight for a `Toggle` row — the
   * island's "Admin view" pill. The switch is still the one control and still says on or off by its
   * position and `aria-checked`, never by an "ON"/"OFF" word.
   */
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
