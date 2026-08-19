"use client";

/**
 * The `Workshop` record a design workshop is linked to, made readable from inside a stage form.
 *
 * ── WHY THIS IS NOT A PROP ────────────────────────────────────────────────────────────────────
 * The only consumer is `StageReferenceSelect`, five components deep: the stage page renders
 * `EntityForm` / `CollectionTable`, which render `FieldInput`, whose REF branch renders the picker.
 * All four already take a `workshopId` — the DESIGN workshop's id, which is what every request on
 * this screen is addressed by — so threading a second, differently-meaning id of the same name
 * through the same four signatures is how two ids get swapped in a call site six months from now.
 * A named context says which one it is at the point of use.
 *
 * ── WHY THE PICKER NEEDS IT AT ALL ────────────────────────────────────────────────────────────
 * Five of the registry's REF fields are WORKSHOP-scoped — `existingProduct.artisanRef` and
 * `.productRef`, `prototype.productRef`, `processStep.processRef`, `traditionalProcess.processRef`
 * — and the server narrows each of them with `spec.workshop_where(record.workshopId)`, i.e. on the
 * linked Workshop. So a record created from one of those pickers and filed against a DIFFERENT
 * sitting is a record that picker can never show again, including to the `describeCreated` round
 * trip that has to read it back a second later. Seeding the workshop is what makes an inline create
 * land inside the list it was created from.
 *
 * ── ABSENT IS A REAL ANSWER, NOT A MISSING VALUE ──────────────────────────────────────────────
 * A design workshop that is not linked to a Workshop record has no answer here, and the references
 * endpoint already reports that case honestly as `scopedToWorkshop: false` — it widens to the whole
 * table rather than serving nothing. GUESSING A WORKSHOP THERE WOULD FILE THE RECORD AGAINST A
 * SITTING IT WAS NOT DOCUMENTED AT, which is worse than the unlinked list the designer is already
 * being warned about by `ScopeNotice`. So null means null, and the seed simply omits the key.
 */

import { createContext, useContext, useMemo } from "react";

const LinkedWorkshopContext = createContext<string | null>(null);

export function LinkedWorkshopProvider({
  workshopId,
  children
}: {
  /** The linked `Workshop` id, or null/undefined when this design workshop has none. */
  workshopId: string | null | undefined;
  children: React.ReactNode;
}) {
  // Memoised on the value itself: the stage page re-renders on every keystroke of a 496-field form,
  // and a context whose value identity changed each time would re-render every consumer under it.
  const value = useMemo(() => workshopId || null, [workshopId]);
  return <LinkedWorkshopContext.Provider value={value}>{children}</LinkedWorkshopContext.Provider>;
}

/**
 * The linked `Workshop` id, or null.
 *
 * Null both when the design workshop has no linked workshop AND when there is no provider at all —
 * a picker rendered outside a stage page, which is the safe direction: the seed omits the key and
 * the record is filed exactly as it was before this existed.
 */
export function useLinkedWorkshopId(): string | null {
  return useContext(LinkedWorkshopContext);
}
