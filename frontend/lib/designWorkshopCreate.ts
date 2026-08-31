"use client";

/**
 * STARTING A DESIGN WORKSHOP FROM THE CREATE FORM — AND THE ONE ASYMMETRY THAT KEEPS ONE SUBMIT
 * FROM BECOMING TWO GOVERNMENT RECORDS.
 *
 * The form has two ways to end well, and from the outside they look the same: the workshop is
 * created on the server, or it is kept on this device and created by the next sync pass. What
 * separates them is whether `POST /design-workshops` HAD ALREADY GONE OUT before the local draft
 * was written — and nothing afterwards can recover that fact, so it has to be recorded at the
 * moment the decision is taken. That is the whole of this file.
 *
 * ── WHY THE STAMP EXISTS ─────────────────────────────────────────────────────────────────────────
 *
 * `POST /design-workshops` carries no client key and the create route de-duplicates nothing, so
 * sending one workshop twice leaves two records under one title in a government index, one of them
 * empty for ever. The compensating machinery is all in `lib/designWorkshopStore.ts`:
 * `DwDraft.createSentAt` records that an answer is outstanding, and the sync pass reads it and asks
 * `resolveInterruptedCreate` — "is my workshop already up there?" — before it posts anything.
 *
 * The transient arm below is the SECOND writer of that POST, and it was the one that never stamped.
 * A 502, a 504 or a connection dropped mid-flight leaves a request that may well have committed,
 * and the fallback then writes a local draft for it. Unstamped, that draft says "never sent", the
 * sync pass believes it, and the workshop is filed a second time.
 *
 * ── AND THE OTHER ARM MUST NOT BE STAMPED ────────────────────────────────────────────────────────
 *
 * A blanket "always stamp" is the opposite defect and it is the worse of the two, because it fires
 * on the ordinary path rather than the rare one. Every workshop started in a courtyard with no
 * signal would carry a stamp from birth, and the stamp is what arms `resolveInterruptedCreate`,
 * whose single-candidate arm ADOPTS: an admin who already had a workshop of this exact title on the
 * server would have this draft silently pointed at it, and a fortnight of stages pushed into the
 * wrong ministry record under a 200. Android reached that conclusion first and its comment is the
 * long form of this one — `couldHaveReachedServer` in
 * `android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/WorkshopListScreen.kt`,
 * read BEFORE the POST and spent on the failure branch.
 *
 * ── WHY EVERY DEPENDENCY ARRIVES AS A PARAMETER ──────────────────────────────────────────────────
 *
 * The asymmetry above IS the correctness argument, and a tidy-up that collapsed the two arms into
 * one would read as a simplification. It is pinned by
 * `e2e/design-workshop-create-idempotence-unit.spec.ts`, which can only assert it because
 * {@link DwCreateIo} lets a test watch which arm ran with no network, no IndexedDB and no browser.
 * {@link liveCreate} is the real wiring and is the default, so the create form still calls one
 * function with one argument.
 */

import { createDesignWorkshop } from "@/lib/designWorkshops";
import { createLocalDraft, type DwDraftHeader } from "@/lib/designWorkshopStore";
// The one rule for "is this worth carrying rather than showing" lives in `lib/failureTriage.ts` and
// is imported rather than restated — `lib/offline.ts` re-exports the same function object.
import { isTransient } from "@/lib/failureTriage";

/** What the create form hands over: a partial draft header that at least carries a title. */
export type DwCreateHeader = Partial<DwDraftHeader> & { title: string };

/**
 * Everything {@link createWorkshopOrKeepItHere} touches outside itself, so a test can hand it fakes
 * and watch which arm ran and what it recorded.
 */
export type DwCreateIo = {
  /**
   * Could a request from this device reach the server AT ALL — asked BEFORE anything is sent.
   *
   * `navigator.onLine === false` is the only hard no: the browser has no network interface, so
   * nothing can have left. Anything else — including a runtime with no `navigator` at all — is a
   * maybe, and a maybe is treated as "a request may have landed".
   *
   * ASKED AFTER A FAILURE THIS WOULD BE THE WRONG QUESTION. It would then answer "is there signal
   * NOW", which is false for exactly the request whose answer was lost when the signal went — the
   * one request that has to be stamped.
   */
  couldReachServer: () => boolean;
  /** `POST /design-workshops`. */
  post: (header: DwCreateHeader) => Promise<{ id: string }>;
  /**
   * Keep the workshop on this device, and answer with the id to navigate to. `createSentAt` is
   * passed on ONE arm only — see this file's header for why the other must not carry it.
   */
  keepHere: (header: DwCreateHeader, options?: { createSentAt?: number }) => Promise<{ id: string }>;
  /** Is this failure worth carrying rather than showing? */
  transient: (error: unknown) => boolean;
  /** `Date.now`, injected so the stamp itself can be asserted. */
  now: () => number;
};

/** The real wiring: the API, the local store, the shared failure triage and the clock. */
export const liveCreate: DwCreateIo = {
  couldReachServer: () => typeof navigator === "undefined" || navigator.onLine !== false,
  post: (header) => createDesignWorkshop(header),
  keepHere: async (header, options) => {
    /*
      THE WHOLE HEADER, SPREAD — NEVER A HAND-COPIED FIELD LIST.

      This used to declare its own nine-key parameter type and re-enumerate those nine keys into
      `createLocalDraft`. `workshopId` was in neither list, so a design workshop created without a
      connection — or created online when the POST merely 500'd once, which takes the same fallback
      — silently dropped the workshop record the designer had just chosen from the picker. Nothing
      caught it: TypeScript does not apply excess-property checking to a variable, so the caller's
      extra key was legal and invisible, and every OTHER field survived, which made the loss look
      like a correctly pre-filled row. The consequence surfaces a fortnight later, on the stages
      that scope their reference pickers to the linked workshop: `refScope: "WORKSHOP"` falls back
      to the whole table and the designer picks participants out of the entire repository.

      Taking `Partial<DwDraftHeader>` means this cannot drift from the header shape again — a field
      added to `DwDraftHeader` arrives here for free. `createLocalDraft` prunes the keys that are
      present-but-undefined, which is what an unfilled box on this form produces.
    */
    const draft = await createLocalDraft(header, options);
    return { id: draft.localId };
  },
  transient: isTransient,
  now: Date.now
};

/**
 * Create the workshop on the server, or keep it here — and record which of those two happened.
 *
 * @returns the id to navigate to: the server's id when the create landed, and the LOCAL id
 *   (`dwlocal-…`) when it did not. Every design-workshop route resolves either id, so the
 *   navigation works immediately and goes on working once the sync pass fills the remote id in.
 */
export async function createWorkshopOrKeepItHere(
  header: DwCreateHeader,
  io: DwCreateIo = liveCreate
): Promise<{ id: string }> {
  /*
    NOTHING WAS SENT, SO NOTHING IS OUTSTANDING — AND THIS DRAFT IS NOT STAMPED.

    Offline, the workshop is created HERE, with a local id, and becomes a real record on the next
    connection. The alternative — refusing — makes the very first act of a fortnight in the field
    the one act that needs signal, and a designer standing in a room with the participants in front
    of them would open a paper notebook instead.
  */
  if (!io.couldReachServer()) return io.keepHere(header);

  /*
    READ BEFORE THE REQUEST GOES OUT, and that is what makes the stamp honest: it holds when the
    create was SENT, not when this browser gave up waiting for it, and those differ by a whole
    request timeout on the connections this feature exists for. `DwDraft.createSentAt` says the
    former on the tin, and it is the same instant Android reads its connectivity at.
  */
  const sentAt = io.now();
  try {
    return await io.post(header);
  } catch (err) {
    /*
      `isTransient` AND NOT `isUnreachable`. This is not a message: it is the decision whether to
      KEEP the workshop on this device and retry it, and "is it worth trying again" is exactly that
      question — a 5xx on a create is worth carrying rather than losing the room. The list page's
      failed-LOAD handler asks the other one, because there the answer becomes a sentence on screen
      and `isTransient` would tell a designer their connection was at fault when the server had
      answered.
    */
    if (!io.transient(err)) throw err;
    /*
      STAMPED — THE ONE LINE THIS MODULE EXISTS FOR.

      The request went out and its answer never came back, so the workshop may already exist on the
      server. The draft therefore records that a create is unaccounted for, and the sync pass looks
      before it sends: `resolveInterruptedCreate` adopts the workshop if it finds exactly one that
      could be this one, refuses and asks the designer if it finds several, and creates normally if
      it finds none.
    */
    return io.keepHere(header, { createSentAt: sentAt });
  }
}

/* ── THE DESIGNER'S ARM, AND WHY IT IS NOT A THIRD BRANCH OF THE FUNCTION ABOVE ───────────────────
 *
 * The owner's remaining clause: *"if they are offline, let them create one for the time being, and
 * when the internet comes back up, let them link it to one of the workshops that they have access
 * to."* A designer holding this draft is doing something the function above has no branch for —
 * they are not creating a workshop at all, now or later, because
 * `DESIGN_WORKSHOP_CREATOR_ROLES` does not admit them and this lane does not widen it. They are
 * putting a fortnight of fieldwork somewhere it will survive until a workshop exists to hold it.
 *
 * SO IT MUST NOT GO NEAR THE POST, and that is the whole reason it is a separate function rather
 * than a `if (!mayCreate)` line inside `createWorkshopOrKeepItHere`. That function's FIRST act is
 * `io.couldReachServer()`, and between the render that offered the control and the tap that pressed
 * it a phone can find a bar of signal — at which point the shared path would POST, collect a 403,
 * find it not transient, and rethrow. Nothing would be lost, but the designer would have met a
 * refusal for pressing the one control the app had just offered them, and the request had no
 * business being sent. Here there is no `post` in the file's reach at all.
 *
 * THE GATE IS STILL THE STORE'S. `createLocalDraft` asks `classifyDraftStart` itself — this passes
 * evidence about the network and no opinion about the rule, and an account outside
 * `canRunDesignWorkshops` is refused by the store with `DwCreateNotPermittedError` exactly as it
 * always has been. There is deliberately no second gate here: two gates is two rules.
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

/**
 * Keep a workshop on this device that this account may never create — for a designer with no signal.
 *
 * @param header what the offline start form collected. Only the title is required, as on the create
 *   form and as in the API: a workshop is opened in a room on day one and stage 1 fills in the rest.
 * @returns the LOCAL id (`dwlocal-…`) to navigate to. Every design-workshop route resolves it, and
 *   goes on resolving it after `adoptDraftIntoWorkshop` has pointed the draft at a real workshop.
 * @throws `DwCreateNotPermittedError` when the store refuses — an account that may not run design
 *   workshops at all, or a device that turns out to be reachable after all. Its `message` is the
 *   shared refusal, which names the next move, and the caller renders it unchanged.
 */
export async function startLocalDraftHere(header: DwCreateHeader): Promise<{ id: string }> {
  /*
    `serverUnreachable: true` IS THE CALLER'S EVIDENCE AND NOT A FLAG THAT MEANS "ALLOW IT".

    This function is reached only from a surface that has WATCHED `GET /design-workshops` fail and
    run the error through `isUnreachable` — the outbox's own answer to "was that the network",
    rather than `navigator.onLine`, which reports true through a captive portal that routes nothing
    and is exactly a village guest-house router. The store ORs it with its own `deviceLooksOffline()`
    floor; see `DwLocalDraftOptions.serverUnreachable` for why widening stops there.
  */
  const draft = await createLocalDraft(header, { serverUnreachable: true });
  return { id: draft.localId };
}
