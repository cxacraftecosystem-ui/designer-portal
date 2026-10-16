"use client";

/**
 * The bare switch the Settings cards lay out beside their own label. It is `ToggleSwitch` from
 * `components/ui/Toggle.tsx` — a real `role="switch"` with `aria-checked` (this file drew an
 * `aria-pressed` toggle button until 2026-10-17, which a screen reader announces as a pressed BUTTON,
 * not as a setting that is on). Kept under its old name so the four callers did not have to move;
 * new code uses `Toggle` from `components/ui/Toggle`, whose whole row is the target.
 */
export { ToggleSwitch as Toggle } from "@/components/ui/Toggle";
