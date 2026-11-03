export function infoToggleLabel(fieldLabel: string): string {
  return `About ${fieldLabel}`;
}

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

export function hasDetails(details: string | undefined | null): details is string {
  return typeof details === "string" && details.trim().length > 0;
}
