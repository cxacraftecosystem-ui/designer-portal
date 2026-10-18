/**
 * The one-tap "everything" shortcut beside a stage list's bulk picker (B3).
 *
 * OFFERED ONLY FOR THE WORKSHOP'S OWN RECORDS. Those lists are a small closed set — the participants
 * in the room, the products this workshop made — so "all of them" is a real answer. The artisan
 * register is the cluster's and is served a page at a time; "add all" there would put strangers on
 * the roster, so it has no entry here.
 *
 * Android's twin is `dwBulkAllLabel` in `DwBulkPick.kt`; `DwBulkPickTest` reads this table and holds
 * the two to the same words.
 */
export const BULK_ALL_LABEL: Readonly<Record<string, string>> = {
  DwParticipant: "All participants",
  DwFinalProduct: "All final products",
  DwSketch: "All sketches",
  DwPrototype: "All prototypes"
};

/** The shortcut's label for a reference model, or null where none is offered. */
export function bulkAllLabel(refModel: string | null | undefined): string | null {
  if (!refModel) return null;
  return Object.prototype.hasOwnProperty.call(BULK_ALL_LABEL, refModel) ? BULK_ALL_LABEL[refModel] : null;
}

/** The records an "All" press adds: every option not already on the list, in the list's order. */
export function remainingOptions<T extends { id: string }>(options: readonly T[], alreadyChosen: readonly string[]): T[] {
  const chosen = new Set(alreadyChosen);
  const seen = new Set<string>();
  return options.filter((option) => {
    if (!option.id || chosen.has(option.id) || seen.has(option.id)) return false;
    seen.add(option.id);
    return true;
  });
}

/**
 * The line after an "All" press on a list the server cut short, or null.
 *
 * Null when the list was whole, and null when the stage cap refused some of the rows (`added` short
 * of `asked`): the form's refusal sentence carries the count then, and two counts would disagree.
 * Android's `dwBulkAddedSentence` says the same words.
 */
export function cutShortNote(truncated: boolean, asked: number, added: number, triggerLabel: string): string | null {
  if (!truncated || added <= 0 || added < asked) return null;
  return `Added the first ${added}. Use ${triggerLabel.toLowerCase()} for the rest.`;
}

/** Said when an "All" press finds nothing left to add. */
export const BULK_ALL_NOTHING_LEFT = "Everything is already on this list.";
