/**
 * The pure half of the "i" beside a field label: the extra guidance a field carries in `details`,
 * collapsed until asked for.
 *
 * Owner ruling, 2026-10-17 (audit DW-14): a field's `help` is one short line under the box, and
 * anything else that is useful goes behind this disclosure. Android draws the same control
 * (`DwFieldDetails` in `FieldRenderer.kt`) with the same name and state words.
 *
 * Split from the component because this repository has no React renderer in its devDependencies:
 * `e2e/stage-help-dictate-unit.spec.ts` calls these directly.
 */

/** What the toggle is called. One phrase on both clients, so a screen reader hears the same thing. */
export function infoToggleLabel(fieldLabel: string): string {
  return `About ${fieldLabel}`;
}

/**
 * The toggle's ARIA, open or closed.
 *
 * `aria-controls` is set ONLY while the panel is mounted — the panel is removed when collapsed, and
 * pointing at a missing id is worse than not pointing (the same rule the guide cards follow).
 */
export function infoToggleAria(
  fieldLabel: string,
  expanded: boolean,
  panelId: string
): { "aria-label": string; "aria-expanded": boolean; "aria-controls"?: string } {
  return {
    "aria-label": infoToggleLabel(fieldLabel),
    "aria-expanded": expanded,
    ...(expanded ? { "aria-controls": panelId } : {})
  };
}

/** Whether a field has anything to disclose. Whitespace is nothing. */
export function hasDetails(details: string | undefined | null): details is string {
  return typeof details === "string" && details.trim().length > 0;
}
