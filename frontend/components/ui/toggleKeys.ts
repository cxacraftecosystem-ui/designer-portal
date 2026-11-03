export const TOGGLE_KEYS = [" ", "Enter"] as const;

export function switchAria(
  checked: boolean,
  busy: boolean
): { role: "switch"; "aria-checked": boolean; "aria-busy"?: true } {
  return busy ? { role: "switch", "aria-checked": checked, "aria-busy": true } : { role: "switch", "aria-checked": checked };
}

export function toggleOutcome(checked: boolean, state: { disabled?: boolean; busy?: boolean }): boolean | null {
  if (state.disabled || state.busy) return null;
  return !checked;
}
