/**
 * WHICH KEYS A HEADER EDIT PUTS ON THE WIRE — the pure half of `DesignWorkshopHeaderForm`.
 *
 * A PLAIN `.ts` MODULE BESIDE THE COMPONENT, and the split is the whole point of this file existing
 * rather than these thirty lines staying where they were written.
 *
 * `PATCH /design-workshops/{id}` reads its body with `model_dump(exclude_unset=True)`, so the body
 * is not "the form" — it is the DIFFERENCE between the form and what the form was seeded with:
 *
 *   * a key that is **absent** leaves the stored value alone;
 *   * a key sent as **`null`** — or `""`, or whitespace — CLEARS the column to NULL.
 *
 * An uncontrolled form reads `""` out of every box nobody touched, so a body built from the form
 * rather than from the diff blanks the workshop's craft, cluster, place, dates, notes and link in
 * one press, under a 200, with nothing on any screen to read. The component's own header calls that
 * "the single most likely defect in this change" and names {@link changedKeys} as the whole of the
 * defence.
 *
 * A DEFENCE NOTHING EXERCISES IS A DEFENCE THAT HAS NOT BEEN CHECKED SINCE IT WAS TYPED. The
 * function was already written to be pure — "so it can be reasoned about without a browser" — but it
 * lived unexported inside a `"use client"` component that imports React, `next/navigation`,
 * `lucide-react` and an IndexedDB store, which is not a module a Node spec can pull in. Twenty
 * `design-workshop-*-unit.spec.ts` files run in this repository's CI unit gate
 * (`npm run test:unit`) and the edit surface had none, for that mechanical reason and no other.
 * Moving the pure half here is what lets `e2e/design-workshop-header-diff-unit.spec.ts` call it —
 * the same shape `components/data/cappedList.ts` and `lib/designWorkshopCreate.ts` already take, and
 * the same reason.
 *
 * NOTHING HERE KNOWS ABOUT A FORM. It takes the values as strings and the record as it came off the
 * wire; the component owns reading the DOM, and the switch that turns a returned key into `null` or
 * a string stays there, next to the `DwUpdateBody` type that refuses `null` for the two NOT NULL
 * columns.
 */

import type { DwSummary } from "@/lib/designWorkshops";

/**
 * The ten columns this form sends, and the only ten.
 *
 * `PATCH` accepts an eleventh — `status` — and this form deliberately never sends it. The record
 * page's `SubmissionCard` owns it: it has its own confirmation naming how many required fields are
 * outstanding, its own count of stages this device has not sent, and its own online-only failure
 * sentence. A second writer in a details form would let a designer archive a workshop while
 * renaming it, and the two controls would then disagree about which of them the designer meant.
 *
 * Every OTHER key `workshop_summary` serialises is refused by the route BY NAME — the six cover
 * columns stage 1 owns, the provenance stamps, the consent record, the two designer keys — so this
 * list is not a preference, it is the wire contract. Rendering a control for one of them and 422-ing
 * on save is a worse experience than not offering it, which is why the six that a reader actually
 * needs are rendered READ-ONLY on the form instead, each saying where the value really lives.
 *
 * ORDER IS THE ORDER THE BOXES ARE READ IN, and it is not load-bearing: {@link changedKeys} filters
 * this list, so the body's key order follows it, and JSON object order reaches no decision on the
 * server. Keep it matching the form only so the two read against each other by eye.
 */
export const EDITABLE_KEYS = [
  "title",
  "templateId",
  "craftName",
  "clusterName",
  "state",
  "district",
  "startDate",
  "endDate",
  "workshopId",
  "notes"
] as const;

export type EditableKey = (typeof EDITABLE_KEYS)[number];

/** Membership test for {@link EDITABLE_KEYS} — used to decide whether a 422 names a box on screen. */
export const EDITABLE_KEY_SET: ReadonlySet<string> = new Set<string>(EDITABLE_KEYS);

/**
 * The stored value as the wire spells it — a trimmed string, with "nothing stored" and "stored as
 * blank" collapsed onto `""`.
 *
 * COLLAPSING THEM IS THE POINT. Every one of these columns is nullable and the server writes NULL
 * for a blank, so `null` and `""` are the same fact on the way back; keeping them apart here would
 * make an empty box "differ" from a NULL column and send `null` over a NULL, on every save, for
 * every box a workshop has never filled in. Six of these columns are also stage 1's, and a no-op
 * write is a second writer taking a turn for no reason.
 *
 * NON-STRINGS COLLAPSE TO `""` RATHER THAN THROWING because the seed is a parsed HTTP response and
 * not a value this module constructed: a server that starts answering `startDate` as a number, or a
 * key that goes missing from `workshop_summary`, must read as "nothing stored" and leave the box
 * comparing equal to an empty one — never as a diff that sends a clear for a column nobody touched.
 */
export function storedText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

/**
 * WHAT THE SERVER IS ACTUALLY BEING ASKED TO CHANGE — the whole of the unset-vs-null contract, in
 * one function.
 *
 * `onScreen` holds the trimmed value of every control; `stored` is the record the form was seeded
 * from. A key is returned when the two differ and is omitted when they do not, so:
 *
 *   * a box nobody touched is ABSENT and the stored value stands;
 *   * a box that held "Ikat" and now holds nothing is `""` here and goes on the wire as `null`,
 *     which is the only way this product can clear a column at all;
 *   * a box typed into and then typed back is absent again, so an idle edit writes nothing.
 *
 * A DIFF AND NOT A DIRTY-FLAG, deliberately. The obvious alternative is to record which controls the
 * user touched and send those. It has a silent failure this one cannot have: a themed dropdown, a
 * date range and a multi-select are all `<button>`s that fire no native input event (SKILL §12.1),
 * so one missed hand-written `markDirty` means a change the designer made, watched go onto the
 * screen, and never sent — answered 200. Comparing values needs no control to remember to speak up.
 */
export function changedKeys(onScreen: Record<EditableKey, string>, stored: DwSummary): EditableKey[] {
  return EDITABLE_KEYS.filter((key) => onScreen[key] !== storedText(stored[key]));
}
