/**
 * THE ROWS OF THE ASSISTANT DIRECTOR AND REGIONAL DIRECTOR PICKERS on Workshop oversight.
 *
 * A module of its own, beside the page, for one reason: a Next.js page file may export nothing but
 * the page, and the decisions below are worth a unit spec. There is no React renderer in this
 * project's devDependencies, so a rule written inside JSX is only ever exercised by somebody looking
 * at a screen.
 *
 * ── WHAT THE OWNER'S RULING OF 2026-10-09 CHANGED HERE ──────────────────────────────────────────
 *
 * The officer directory used to hold the three directorate tiers, with a Ministry Admin listed and
 * fitting neither slot, and every row that could not fill a slot was greyed out with ONE stock
 * sentence about Ministry Admins supervising the scheme — including an Assistant Director offered in
 * the Regional Director slot. Since ruling D3 a Ministry Admin, an admin and the master admin may be
 * named in either post, so the directory lists them with both capacities, and the reason a row is
 * greyed out is worked out per row from the capacities the SERVER sent.
 *
 * AND THE READER IS NEVER OFFERED. Nobody appoints themselves; the server answers it with a 409 and
 * its directory leaves the appointer out. This module drops them as well, so a server older than
 * the rule cannot put the one refused choice at the top of the list — except as the CURRENT holder,
 * who is always drawn, because a trigger with no matching row reads as though nobody held the post.
 * That row is DISABLED, with {@link OWN_POST_HINT}: nobody takes themselves off a post either
 * (`selfReleaseRefusal`), and the page switches the whole slot off while the reader holds it.
 */

import type { SelectOption } from "@/components/ui/selectFilter";
import { roleLabel } from "@/lib/permissions";
import {
  CAPACITY_LABELS,
  type DwOfficer,
  type DwOversightAssignment,
  type DwOversightCapacity
} from "./oversight";

/** Name a person without ever leaking an id: their name, else their email, else a neutral word. */
function personLabel(person: { name?: string | null; email?: string | null }): string {
  return person.name?.trim() || person.email?.trim() || "Unknown user";
}

/** Said on the reader's own row when they hold the post: theirs to keep until somebody else acts. */
export const OWN_POST_HINT = "you hold this post — another administrator has to take you off it";

/**
 * Why this officer cannot fill this slot, in words — or null when they can.
 *
 * READ OFF THE ROW'S OWN `capacities`, which is the server's answer, never off the role: who may hold
 * which post is the server's rule, and a client that re-derived it would grey out a row the write
 * accepts the day the rule moves.
 */
export function officerSlotRefusal(
  officer: Pick<DwOfficer, "capacities">,
  capacity: DwOversightCapacity
): string | null {
  if (officer.capacities.includes(capacity)) return null;
  if (officer.capacities.length === 0) return "cannot be named in either post";
  const posts = officer.capacities.map((held) => `the ${CAPACITY_LABELS[held]} post`);
  return `may hold ${posts.join(" or ")}, not this one`;
}

/**
 * The rows for one slot: who may hold it, then who may not and why, then whoever holds it now.
 *
 * **THE INELIGIBLE ARE DRAWN AND EXPLAINED RATHER THAN OMITTED**: this directory answers "who may be
 * named", and one that silently left people out would be concluded broken. `disabled` carries it,
 * and the row's `hint` says why — which the picker also searches, so typing a post's name finds the
 * explanation rather than nothing.
 *
 * **THE HOLDER IS ALWAYS OFFERED**, even when the current search does not reach them, their account
 * has since been barred off the directory, or they are the reader: the control's whole job is to say
 * who holds the post, and "Nobody is assigned" in the same control is how it is taken off them.
 *
 * `remembered` is every officer an earlier search has shown — a label cache, never a second source of
 * eligibility: the only row it can add is the holder's.
 */
export function officerOptionsForPost({
  officers,
  capacity,
  holder,
  remembered,
  readerId
}: {
  officers: readonly DwOfficer[] | null | undefined;
  capacity: DwOversightCapacity;
  holder?: DwOversightAssignment | null;
  remembered?: ReadonlyMap<string, DwOfficer>;
  readerId?: string | null;
}): SelectOption[] {
  const rows: SelectOption[] = [];
  const offered = new Set<string>();
  const add = (officer: DwOfficer, refusal: string | null) => {
    if (offered.has(officer.id)) return;
    offered.add(officer.id);
    const who = `${officer.email} · ${roleLabel(officer.role)}`;
    rows.push({
      value: officer.id,
      label: personLabel(officer),
      hint: refusal ? `${who} — ${refusal}` : who,
      disabled: Boolean(refusal),
      ...(refusal ? { group: "Cannot hold this post" } : {})
    });
  };

  const candidates = (officers ?? []).filter((officer) => officer.id !== readerId);
  for (const officer of candidates) {
    if (officerSlotRefusal(officer, capacity) === null) add(officer, null);
  }
  for (const officer of candidates) {
    const refusal = officerSlotRefusal(officer, capacity);
    if (refusal !== null) add(officer, refusal);
  }
  if (holder && !offered.has(holder.userId)) {
    const current = remembered?.get(holder.userId) ?? {
      id: holder.userId,
      name: holder.name,
      email: holder.email,
      role: holder.role,
      capacities: [capacity]
    };
    if (readerId && holder.userId === readerId) {
      // THE READER'S OWN POST: drawn so the control says who holds it, never choosable away, and not
      // under "Cannot hold this post" — they hold it. No group, so it reads with the eligible rows.
      offered.add(current.id);
      rows.push({
        value: current.id,
        label: personLabel(current),
        hint: `${current.email} · ${roleLabel(current.role)} — ${OWN_POST_HINT}`,
        disabled: true
      });
    } else {
      add(current, null);
    }
  }
  return rows;
}

/**
 * How many people the directory offers this reader for either post — the count an empty-directory
 * sentence has to be asked of. Not the length of the server's answer, which may still carry the
 * reader on a server older than the rule that leaves them out.
 */
export function officersOfferedTo(
  officers: readonly DwOfficer[] | null | undefined,
  readerId?: string | null
): number {
  return (officers ?? []).filter((officer) => officer.id !== readerId).length;
}
