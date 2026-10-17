/**
 * The pure half of `Toggle` (components/ui/Toggle.tsx), pinned by `e2e/toggle-semantics-unit.spec.ts`
 * without a renderer.
 *
 * KEYBOARD: the switch is a real `<button>`, so Space and Enter already click it in every browser and
 * through every screen reader's own activation — which is why the component adds NO keydown handler. A
 * hand-written one is how a switch flips twice (keydown Enter, then the native click) or not at all
 * (a screen reader's synthetic click carries no key). `TOGGLE_KEYS` records the contract that native
 * behaviour gives, for the skill and the test, not for a listener.
 */

export const TOGGLE_KEYS = [" ", "Enter"] as const;

/** The attributes a switch must carry for a screen reader, given its state. */
export function switchAria(
  checked: boolean,
  busy: boolean
): { role: "switch"; "aria-checked": boolean; "aria-busy"?: true } {
  return busy ? { role: "switch", "aria-checked": checked, "aria-busy": true } : { role: "switch", "aria-checked": checked };
}

/** What a press does: the flipped value, or null when the switch is disabled or mid-change. */
export function toggleOutcome(checked: boolean, state: { disabled?: boolean; busy?: boolean }): boolean | null {
  if (state.disabled || state.busy) return null;
  return !checked;
}
