export const BULK_ALL_LABEL: Readonly<Record<string, string>> = {
  DwParticipant: "All participants",
  DwFinalProduct: "All final products",
  DwSketch: "All sketches",
  DwPrototype: "All prototypes"
};

export function bulkAllLabel(refModel: string | null | undefined): string | null {
  if (!refModel) return null;
  return Object.prototype.hasOwnProperty.call(BULK_ALL_LABEL, refModel) ? BULK_ALL_LABEL[refModel] : null;
}

export function remainingOptions<T extends { id: string }>(options: readonly T[], alreadyChosen: readonly string[]): T[] {
  const chosen = new Set(alreadyChosen);
  const seen = new Set<string>();
  return options.filter((option) => {
    if (!option.id || chosen.has(option.id) || seen.has(option.id)) return false;
    seen.add(option.id);
    return true;
  });
}

export function cutShortNote(truncated: boolean, asked: number, added: number, triggerLabel: string): string | null {
  if (!truncated || added <= 0 || added < asked) return null;
  return `Added the first ${added}. Use ${triggerLabel.toLowerCase()} for the rest.`;
}

export const BULK_ALL_NOTHING_LEFT = "Everything is already on this list.";
