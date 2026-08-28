/**
 * "Which design and prototype workshop should this form open on?" — asked once, of the server.
 *
 * ── WHY THE SERVER ANSWERS IT AND NOT EACH FORM ─────────────────────────────────────────────────
 *
 * The owner's instruction of 2026-08-28 has two halves and they are one question:
 *
 *   * *"When a designer selects Start a new workshop, provide a dropdown containing the workshops
 *     that the designer is already part of or has been given access to. By default, select the
 *     Design and Prototype Workshop that the designer was most recently given access to."*
 *   * *"Whenever a designer goes to create/record any particular record type, the most recently
 *     allocated Design and Prototype Workshop should be populated by default."*
 *
 * Seven record forms times two clients is fourteen places that would each have to decide what "most
 * recently allocated" means, and they would not agree. `forms/WorkshopSelect.tsx` already records
 * what that costs for the ORDINARY workshop default — `workshopOccurrenceDate` is re-exported into
 * three consumers rather than reimplemented, because "getting 'which workshop is most recent' wrong
 * picks the wrong default silently". This is the same rule applied one scope over, and the answer
 * lives on the server because only the server can see `DesignWorkshopViewer.createdAt`, which is
 * when a designer was actually allocated a workshop.
 *
 * `GET /design-workshops/default-for-me` is that endpoint. It reads BOTH doors into a workshop —
 * a grant, and authorship — and returns whichever is later, with `reason` naming which one.
 *
 * ── IT IS A SUGGESTION AND NEVER A SCOPE ────────────────────────────────────────────────────────
 *
 * Nothing here narrows what a designer may pick. The picker still lists everything the server
 * admits, and every write is still checked by `load_workshop_or_404` server-side. A caller that
 * treated this as "the workshop I am allowed to use" would be inventing a client-side scope the API
 * does not have — the mistake `permissions.ts` warns about for `/search`.
 *
 * ── A FAILURE IS "NO DEFAULT", NEVER AN ERROR THE FORM REPORTS ─────────────────────────────────
 *
 * A record form must open on a bad connection. If this request fails the form gets `null`, the
 * dropdown opens unselected, and nothing is said — because there is nothing for the designer to do
 * about it and a red banner over a blank workshop box would read as the form being broken. That is
 * the same call `useWorkshopSelection`'s `fetchCheck` makes ("a researcher in the field must not
 * lose work to a flaky pre-flight").
 *
 * ── AND THE ANSWER IS MEMOISED, WITH A CEILING ────────────────────────────────────────────────
 *
 * Every record form on the page asks, and a designer opening four forms in a sitting should not
 * cost four round trips. The window is deliberately the same minute `ACCESSIBLE_WORKSHOPS_TTL_MS`
 * uses in `forms/WorkshopSelect.tsx`, and for the reason stated there: long enough to collapse a
 * burst, short enough that "you were added to a workshop" reaches the next form inside the same
 * sitting rather than at the next reload. **A FAILURE IS NOT CACHED AT ALL** — the next form asks
 * again rather than inheriting a bad minute.
 */

import { apiFetch } from "@/lib/api";

/** Which door the default came through. `null` only when there is no default at all. */
export type DesignWorkshopDefaultReason = "GRANTED" | "CREATED" | null;

export type DesignWorkshopDefault = {
  /** null = this account is on no design workshop. An answer, not a failure. */
  workshopId: string | null;
  title: string | null;
  /** ISO. When access began — the grant's timestamp, or the workshop's own creation. */
  accessAt: string | null;
  reason: DesignWorkshopDefaultReason;
};

/** See the header. One minute, matching `ACCESSIBLE_WORKSHOPS_TTL_MS`. */
const DEFAULT_TTL_MS = 60_000;

let pending: Promise<DesignWorkshopDefault> | null = null;
let askedAt = 0;

/**
 * The default, or `null` if it could not be asked for.
 *
 * Resolves rather than rejects on every failure, so a caller never has to write a `catch` whose only
 * behaviour is "carry on". The distinction a caller DOES need — "asked, and you are on none" versus
 * "could not ask" — is carried in the return: an answered call gives an object whose `workshopId`
 * may be null; an unanswerable one gives `null` outright.
 */
export async function readDesignWorkshopDefault(): Promise<DesignWorkshopDefault | null> {
  const fresh = pending && Date.now() - askedAt <= DEFAULT_TTL_MS;
  if (!fresh) {
    askedAt = Date.now();
    pending = apiFetch<DesignWorkshopDefault>("/design-workshops/default-for-me");
  }
  try {
    return await pending!;
  } catch {
    // NOT CACHED. Clearing the memo is what stops one refused minute answering every form that
    // mounts inside it — the same rule `loadAccessibleWorkshops` states for its own failure path.
    pending = null;
    askedAt = 0;
    return null;
  }
}

/**
 * One sentence saying WHY a workshop was filled in, for printing under the picker.
 *
 * A dropdown that fills itself in and cannot say why reads as a bug, and the two doors need
 * different sentences: "the workshop you were most recently added to" sends a designer looking for
 * an allocation, and "the one you opened most recently" does not.
 *
 * Returns null when there is nothing to explain, so a caller can render it unconditionally.
 */
export function designWorkshopDefaultNote(value: DesignWorkshopDefault | null): string | null {
  if (!value?.workshopId || !value.reason) return null;
  const when = readableDay(value.accessAt);
  const tail = when ? ` on ${when}` : "";
  return value.reason === "GRANTED"
    ? `Filled in because it is the design workshop you were most recently added to${tail}. Change it if this record belongs somewhere else.`
    : `Filled in because it is the design workshop you most recently opened${tail}. Change it if this record belongs somewhere else.`;
}

/**
 * An ISO timestamp as a day in the reader's own locale, or null when it cannot be read.
 *
 * Echoes nothing on failure — unlike a date the designer TYPED, this one is the server's, so a value
 * that will not parse is a bug rather than something to show them.
 */
function readableDay(iso: string | null): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString();
}
