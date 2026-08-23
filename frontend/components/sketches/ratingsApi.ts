/**
 * The three rating routes, typed once, for every surface that reads or writes a judgement.
 *
 * ── WHY THIS SITS BESIDE THE COMPONENTS AND NOT IN `lib/` ───────────────────────────────────────
 *
 * `lib/designWorkshops.ts` is the wire for the 22-stage record and is shared with the report, the
 * readiness screen and the sync pass. The rating ledger is a different resource with a different
 * gate — `/design-ratings` is a separate router precisely so the pool round cannot be reached
 * through the workshop loader — and nothing outside this feature reads it today. It stays here
 * until a second feature needs it, at which point moving it is one file move and no behaviour.
 *
 * ── A RATING TYPED WITH NO SIGNAL IS QUEUED, AND FOR A LONG TIME IT WAS NOT ─────────────────────
 *
 * This file used to refuse the offline outbox, and the refusal was measured rather than lazy:
 * `lib/offline.ts`'s drain read the response of every replayed entry as a created RECORD and
 * required a top-level string `id`:
 *
 *     const createdId = typeof saved?.id === "string" && saved.id ? saved.id : null;
 *     if (!createdId) { await markFailure(progress, "The server accepted this but did not say
 *                       what it saved…"); }
 *
 * `POST /design-ratings` answers `{ "rating": {…}, "replayed": bool }` — no top-level id — so every
 * SUCCESSFUL replay of a queued rating would have been marked as a permanent captive-portal failure
 * and shown to the designer as work that never landed. That was a real reason not to queue, and its
 * cost was that a score, an assessment and a suggested change typed in a courtyard with no signal
 * were refused out loud and were gone when the tab closed — the ONE value on the whole sketches and
 * prototypes surface with no persistence path of any kind, while the sketch beside it and the
 * arrangement above it were both durable before they were accepted.
 *
 * The drain can now be told where to look: {@link OutboxEntry.savedIdIn} names the key the saved row
 * arrives under, so `"rating"` is read exactly where this route puts it and a 200 with nothing
 * readable there is still treated as the captive portal it probably is. Everything else the queue
 * needed, the server already had — the route is idempotent under replay, cannot produce a second row
 * (`@@unique([stageEntryId, reviewerId, round])`), and orders two deliveries of one capture by the
 * DEVICE clock rather than by arrival, which is what `ratedAt` is for and why it is sent here and
 * only here.
 *
 * `apiFetch` IS STILL TRIED FIRST, ALWAYS, and only a request that never reached the server is
 * queued. A 403 on somebody's own record and a 422 on a bad round are answers, not connection
 * failures, and queueing one would replay a refusal for ever behind a sentence promising it would
 * land. This is the same split `saveOrQueue` makes for the six record forms, made here rather than
 * through that helper because its `T extends { id: string }` constraint is precisely the invariant
 * this response shape cannot satisfy — and relaxing that constraint would let a future caller queue
 * a nested-id route with no `savedIdIn` at all, which is the failure this whole paragraph is about.
 *
 * ── EVERY REFUSAL IS 404 AND THAT IS NOT A BUG TO PAPER OVER ────────────────────────────────────
 *
 * A subject the caller may not see, a workshop they may not reach and an id that never existed all
 * answer "Record not found" with the same detail string. The data set is keyed by cuid and a 403
 * would turn any designer login into an enumeration of the ministry's archive. So no caller here
 * may branch on 404 to say "you do not have access" — see `lib/workshopCodeLookup.ts`, which
 * carries the same rule for the scanners.
 */

import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import { isTransient } from "@/lib/failureTriage";
import { queueOffline, syncOutbox } from "@/lib/offline";

import type { DesignRating, RatingRound, RoundRanking, SubjectLedger } from "./reviewRanking";

/**
 * One designer's judgement, on its way up.
 *
 * `ratedAt` IS OMITTED BY EVERY CALLER IN THIS CLIENT, deliberately. It is the courtyard moment —
 * when the person actually moved the control — and it exists so an outbox can order two deliveries
 * of the same capture. This client submits straight against the server, where the row's own
 * `createdAt` is that same moment, and the schema says so in as many words: "Omit it when the
 * rating is typed straight against the server". A browser stamping `new Date()` at send time would
 * be writing the sync clock into the field whose entire job is to not be the sync clock.
 */
export type DesignRatingBody = {
  subjectId: string;
  round: RatingRound;
  score: number;
  comment?: string | null;
  suggestion?: string | null;
};

/** What the server answers a submission with: the stored row, and whether this was a replay. */
export type DesignRatingSaved = { rating: DesignRating; replayed: boolean };

/**
 * Submit a rating, or amend the one this caller already left.
 *
 * ONE ROUTE FOR BOTH, because the client cannot know which it is asking for — that is the point of
 * the endpoint, and it is why the answer is 200 on the create path too.
 */
export function submitDesignRating(body: DesignRatingBody) {
  return apiFetch<DesignRatingSaved>("/design-ratings", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

/** Where the saved row sits inside this route's answer. See {@link OutboxEntry.savedIdIn}. */
const RATING_SAVED_IN = "rating";

/** The endpoint, once, because the queued entry and the live call must not be able to disagree. */
const RATINGS_ENDPOINT = "/design-ratings";

/**
 * Sent, or kept on this device to send itself.
 *
 * `sent` IS THE HALF A CARD CANNOT GUESS, and it exists because the sentence shown over a queued
 * rating used to be a promise nobody kept. See {@link submitOrQueueDesignRating}: a rating queued
 * because of a transient that is NOT the browser being offline is now handed straight to the outbox
 * drain, and `sent` says whether that pass carried it. It is deliberately NOT a `saved` row — the
 * drain does not hand the response body back to this caller — so a `sent: true` rating still cannot
 * be folded into the round's ranking until the page is read again, which is what the card says.
 */
export type DesignRatingOutcome =
  | { queued: true; sent: boolean }
  | { queued: false; saved: DesignRatingSaved };

/**
 * Submit a rating, or keep it on this device and let the outbox deliver it.
 *
 * ── `ratedAt` IS STAMPED HERE AND NOWHERE ELSE IN THIS CLIENT, WHICH IS THE POINT OF THE FIELD ───
 *
 * {@link DesignRatingBody} says why the direct path omits it: submitted straight against the server
 * the row's own `createdAt` IS the moment the designer moved the control, and a browser stamping
 * `new Date()` at send time would be writing the sync clock into the one field whose job is to not
 * be the sync clock. A QUEUED rating is the case that field exists for, and the two facts diverge
 * the moment the tab closes for the night: the rating was made in the courtyard and delivered from
 * the office three days later, and `record_rating` orders two deliveries of one capture by this
 * number rather than by arrival — see its docstring on "the tunnel that restores a stale score".
 *
 * So the stamp is taken at the moment of QUEUEING, which is the moment of capture: this function is
 * called from the submit handler, synchronously with the designer pressing the button, and the
 * failing request that sends it down this path takes seconds rather than days.
 *
 * A DOUBLE PRESS WITH NO SIGNAL QUEUES TWICE, AND THAT IS ACCEPTED RATHER THAN UNNOTICED. There is
 * no de-duplication key on the queue, so two entries go up; the second carries the later `ratedAt`,
 * the route stores whichever is newer and answers `replayed` for the other, and no second row can
 * exist. Two banner lines for one judgement is the whole cost, and it is paid in exchange for never
 * having to decide on a device whether two presses were one intention.
 */
export async function submitOrQueueDesignRating(
  body: DesignRatingBody,
  label: string
): Promise<DesignRatingOutcome> {
  const queue = async (): Promise<DesignRatingOutcome> => {
    await queueOffline({
      label,
      endpoint: RATINGS_ENDPOINT,
      method: "POST",
      body: JSON.stringify({ ...body, ratedAt: new Date().toISOString() }),
      // A rating carries no file. Passed explicitly because `media` is not optional on the entry and
      // an omitted array would be an entry the drain's media loop cannot iterate.
      media: [],
      savedIdIn: RATING_SAVED_IN
    });
    /*
      ── AND THEN ASK THE OUTBOX TO CARRY IT, WHICH FOR ONE REVISION NOTHING DID ──────────────────

      This function used to stop at the IndexedDB write, under a card reading "It sends itself when
      this device next has a connection". Nothing on this page drains the outbox: `OutboxBanner`
      drains on MOUNT and on the `online` EVENT, and `online` never fires for a tab that was never
      offline. So a rating queued because of a transient with `navigator.onLine === true` — a dropped
      TLS handshake, a DNS blip, a VPN flap, the ordinary office case rather than an exotic one — sat
      in IndexedDB until somebody happened to reload the page, while the sentence on the card said
      the opposite. It was never data loss (the banner lists it and offers "Sync now"), which is
      exactly why nobody would have noticed.

      The same defect was found and fixed twenty lines away in `UploadTabHost.attach` in this same
      wave — "the `online` event never fires for a tab that was never offline" is that file's own
      header — and the fix here is the same one: ask the pass that exists. `syncOutbox` shares a
      single pass between concurrent callers and is held under a Web Lock across tabs, so a click
      landing on top of a banner drain joins it rather than replaying the entry twice.

      NOT WHEN THE BROWSER ALREADY KNOWS IT IS OFFLINE — the same shortcut the offline check above
      takes, and the same one `OutboxBanner`'s drain-on-mount takes. Spending a request and a 30s
      timeout to be told what `navigator.onLine` already said is a cost paid on a metered rural
      connection for nothing.

      THE FAILURE OF THE PASS IS NOT THIS FUNCTION'S FAILURE. The rating is durable either way; the
      caller asked to record a judgement and that has happened. So a throw is swallowed here and the
      answer is `sent: false` — the honest state, and the one the banner is the authority on.

      AWAITED, AND THE EXTRA WAIT IS THE PRICE OF THE SENTENCE BEING TRUE. This adds a second attempt
      to a press that has already spent one, so a card can sit in "Saving…" twice as long on a bad
      connection. The alternative — fire and forget — gets the send started and leaves the client
      unable to say whether it happened, which is the state this change exists to end: the queue is
      not the problem, the sentence over it was. The entry is durable before the pass begins, so
      nothing is at risk during the wait, and `syncOutbox` joins a pass already in flight rather than
      starting a rival one. `UploadTabHost.attach` makes the same trade for the same reason.
    */
    let sent = false;
    if (!(typeof navigator !== "undefined" && navigator.onLine === false)) {
      try {
        const pass = await syncOutbox();
        // BOTH conditions. `synced > 0` alone could be another entry going up while this one failed;
        // `remaining === 0` alone is also true of a pass that declined against an empty store it
        // could not read. Together they are "this device is holding nothing, and something moved".
        sent = pass.synced > 0 && pass.remaining === 0;
      } catch {
        sent = false;
      }
    }
    return { queued: true, sent };
  };

  // Known-offline: do not burn a request and a 30s timeout to learn what the browser already knows.
  // The same shortcut `saveOrQueue` and `FieldInput`'s `settle` take.
  if (typeof navigator !== "undefined" && navigator.onLine === false) return queue();

  try {
    return { queued: false, saved: await submitDesignRating(body) };
  } catch (error) {
    // Only a request that never reached the server may be queued. An `ApiError` means the server saw
    // this rating and said no — a 403 on the designer's own record, a 422 on an unknown round, a 404
    // on a subject they may not see — and replaying that for ever would hide a refusal behind a
    // promise. `isTransient` is asked as well as the class, so a 503 during a deploy window and a 429
    // from a rate limiter are NOT queued either: they are answers, and the boxes keep the text.
    if (isTransient(error) && !(error instanceof ApiError)) return queue();
    throw error;
  }
}

/** Who rated this sketch or prototype, when and how — redacted server-side on the way out. */
export function fetchSubjectLedger(subjectId: string, round: RatingRound) {
  return apiFetch<SubjectLedger>(`/design-ratings/subjects/${subjectId}${buildQuery({ round })}`);
}

/**
 * One round's pieces, each with its score, its DEFAULT position and its PLACED position.
 *
 * `workshopId` is required for BOTH rounds, including POOL, and that is structural rather than an
 * oversight in this client: the placed order IS `DwStageEntry.ordinal`, which orders the rows of
 * one collection inside one workshop, so there is no such thing as a placed position across
 * workshops. The pool round is the same list read by a wider audience, not a wider list.
 */
export function fetchRoundRanking(params: {
  round: RatingRound;
  workshopId: string;
  entityKey: string;
}) {
  const { round, workshopId, entityKey } = params;
  return apiFetch<RoundRanking>(
    `/design-ratings/rounds/${round}${buildQuery({ workshopId, entityKey })}`
  );
}

/**
 * The sentence a refusal should show, taken from the refusal itself where there is one.
 *
 * `apiFetch` has already unpacked FastAPI's `detail` into `ApiError.message` — including the 422
 * list form, which stringifies to "[object Object]" if a caller reads the payload directly — so the
 * message IS the server's own words and re-parsing `payload` here would be the third private copy
 * of `describeApiDetail` this repository has had to hunt down.
 *
 * A caller that wants to say something different about a connection failure must ask
 * `isUnreachable` FIRST: this function has no opinion about the network and will happily print a
 * fetch failure's technical message at a designer.
 */
export function refusalText(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.message.trim() ? error.message : fallback;
}
