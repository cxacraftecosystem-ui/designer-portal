"use client";

import { createContext, useContext } from "react";

/**
 * The id of the visible label a themed dropdown is sitting under, published by the wrapper that
 * drew it and read by the control itself.
 *
 * ── WHY A CONTEXT AND NOT A PROP, WHICH IS THE OBVIOUS SHAPE ─────────────────────────────────────
 *
 * `FormControls.Field` is a `<label>`, and a `<label>` cannot name a themed dropdown. The dropdown's
 * trigger is a `<button>`, and HTML-AAM computes a button's accessible name from its own CONTENTS —
 * the label association a `<label>` establishes is simply not part of that algorithm. So every
 * `Field`-wrapped dropdown in this app announced its VALUE as its name: "Bamboo, combobox" where
 * "Craft" belonged, "Male, combobox" for Gender, "Select, combobox" for anything not yet answered.
 * A reader who tabbed back to check an answer heard the answer twice and the question never.
 *
 * That is forty-four call sites — measured, not estimated: every `Select`, `Dropdown`,
 * `MultiSelectDropdown` and `ComboBox` nested inside a `Field` across twenty files. The per-site fix
 * is to pass `ariaLabel` at each one, and the reason that fix was not taken is that it has to be
 * remembered forty-four times and then once more for every dropdown added afterwards — the same
 * shape as the register that was written down twice and went stale (see §16 of the frontend skill).
 * A wrapper that knows the label text is a better place to say it than forty-four call sites that
 * have to be told to.
 *
 * So `Field` publishes the id of the `<span>` it is already rendering, and `SearchableSelect` reads
 * it. Nothing in between has to forward anything: `Field` → `Select` → `Dropdown` →
 * `SearchableSelect` is four components and three of them would otherwise need a prop each, for a
 * value none of them has an opinion about.
 *
 * ── IT IS COMBINED WITH THE TRIGGER'S OWN TEXT, NOT SUBSTITUTED FOR IT ───────────────────────────
 *
 * `aria-labelledby` REPLACES name-from-content, so pointing it at the label alone would announce
 * "Craft, combobox" and drop the value — trading a control that says only its answer for one that
 * says only its question. The trigger therefore names the label AND itself:
 * `aria-labelledby="<label id> <trigger id>"`, which the accname algorithm concatenates in the order
 * written, giving "Craft Bamboo". Self-reference in `aria-labelledby` is legal and is the standard
 * way to compose a name out of a wrapper's text plus the element's own.
 *
 * ── AND IT NEVER OVERRULES AN EXPLICIT `ariaLabel` ───────────────────────────────────────────────
 *
 * A call site that passed `ariaLabel` said something deliberate — the multi-select folds its
 * selection summary into that string — so an ambient label from a wrapper must not win over it. The
 * control uses the context only when it has no name of its own.
 */
const FieldLabelContext = createContext<string | undefined>(undefined);

export const FieldLabelProvider = FieldLabelContext.Provider;

/** The enclosing labelled slot's label id, or `undefined` when there is no such wrapper. */
export function useFieldLabelId(): string | undefined {
  return useContext(FieldLabelContext);
}
