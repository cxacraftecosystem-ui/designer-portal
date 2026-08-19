"use client";

/**
 * WHETHER THIS WORKSHOP'S MATERIAL MAY BE SENT OUT, read from the copy on this device.
 *
 * Every AI verb and every server dictation is gated on `DesignWorkshop.dictationConsent`, and a
 * client that could not see it would have to learn the answer by being refused — which for these
 * verbs means a round trip whose only possible outcome is a 409. So the controls read it before the
 * press and disable into a stated reason.
 *
 * ── WHY IT READS THE LOCAL DRAFT AND NOT THE API ────────────────────────────────────────────────
 *
 * `lib/designWorkshopStore` holds the whole workshop in IndexedDB and its `consent` record is
 * reconciled with the server's on every `adoptServerDetail`. Reading from there rather than issuing
 * a `GET /{id}` per control means: it answers in a courtyard with no signal, it answers instantly
 * rather than after a 756 ms round trip on the connection this repository measured, and a stage
 * drawing a dozen narrative fields does not put a dozen requests on the wire to learn one fact.
 *
 * ── AND WHY NOT A PROP THREADED DOWN FROM THE PAGE ──────────────────────────────────────────────
 *
 * `RichTextEditor`'s own header states its contract in capitals — *"THIS EDITOR HAS NO DATA LAYER
 * AND DELIBERATELY WANTS NONE"* — and threading a consent value through it would put a second data
 * fact into a component that has one (`workshopId`) only because dictation cannot work without it.
 * `DictationButton` sets the precedent: it takes the id and fetches what it needs itself.
 *
 * ── THE FLOOR IS NOT_RECORDED AND THAT IS THE FAIL-CLOSED DIRECTION ─────────────────────────────
 *
 * A draft this browser has never seen, a store that would not open, an id that matches nothing —
 * every one of them answers NOT_RECORDED, which is the same answer `dictation_consent.consent_of`
 * fails closed to. The cost of being wrong that way is a control disabled with a sentence saying how
 * to enable it; the cost of the other way is a control that promises to work and 409s.
 */

import { useEffect, useState } from "react";

import { isLocalWorkshopId, loadDraft } from "@/lib/designWorkshopStore";

export type WorkshopConsent = {
  /** "NOT_RECORDED" | "GRANTED" | "REFUSED", or a token a newer server sent. Never null. */
  decision: string;
  /**
   * False while the draft is still being read.
   *
   * A CALLER MUST NOT DRAW A REFUSAL BEFORE THIS IS TRUE. The floor answer is NOT_RECORDED, so a
   * control rendered against the unsettled value would flash "nobody has been asked" on every
   * workshop that has been asked — and a designer who reads that once stops trusting the sentence.
   */
  ready: boolean;
  /**
   * THE ID THE SERVER KNOWS THIS WORKSHOP BY, or null while it exists only on this device.
   *
   * ── WHY IT RIDES ON THIS HOOK ───────────────────────────────────────────────────────────────
   * Not a second concern bolted on: it comes free off the read that is already happening, and every
   * control that has to consult the consent before a send is a control that then has to make that
   * send — so the two facts are wanted at exactly the same moments and by exactly the same callers.
   * A separate hook would mean a second `loadDraft` per control, on the stage that draws eleven of
   * them.
   *
   * ── AND WHY IT IS NOT SIMPLY THE ROUTE PARAM ────────────────────────────────────────────────
   * The route param is the LOCAL draft id (`dwlocal-…`) for the whole life of a draft, and it stays
   * the local id after the draft has synced, because the stage page does not redirect. So neither
   * `workshopId` nor `isLocalWorkshopId(workshopId)` on its own answers "is there a server copy to
   * call": the first sends `dwlocal-…` into a route that answers 404 "Record not found" (the AI
   * verbs shipped doing exactly that), and the second withholds working verbs from a workshop the
   * server holds and tells the designer something false about it.
   *
   * The expression is the one `reportTarget.ts` and the stage page already use, deliberately
   * identical rather than a fourth paraphrase of one rule.
   *
   * Null while `ready` is false — a caller must not conclude "not on the server" from the unsettled
   * value, for the same reason it must not draw a consent refusal from it.
   */
  serverId: string | null;
};

export function useWorkshopConsent(workshopId: string | null | undefined): WorkshopConsent {
  const [state, setState] = useState<WorkshopConsent>({ decision: "NOT_RECORDED", ready: false, serverId: null });

  useEffect(() => {
    if (!workshopId) {
      // No workshop at all — a record form rather than a stage. There is nothing whose consent could
      // govern a send, and the caller draws no control at all in that case.
      setState({ decision: "NOT_RECORDED", ready: true, serverId: null });
      return;
    }
    // The `cancelled` flag is the right race guard for a one-shot effect keyed on an id (a
    // generation counter is for a list that refetches on filter changes, an AbortSignal for a fetch
    // that accepts one). `loadDraft` accepts neither and never throws.
    let cancelled = false;
    setState({ decision: "NOT_RECORDED", ready: false, serverId: null });
    void loadDraft(workshopId).then((draft) => {
      if (cancelled) return;
      setState({
        decision: draft?.consent?.decision ?? "NOT_RECORDED",
        ready: true,
        // A draft this browser has never opened answers null here, and a NON-local id falls back to
        // itself: an id that is not `dwlocal-…` IS the server's id, whatever this device holds.
        serverId: draft?.remoteId ?? (isLocalWorkshopId(workshopId) ? null : workshopId)
      });
    });
    return () => {
      cancelled = true;
    };
  }, [workshopId]);

  return state;
}
