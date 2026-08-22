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
 * ── WHAT THIS FILE DELIBERATELY DOES NOT DO ─────────────────────────────────────────────────────
 *
 * **It does not queue a failed submission into the offline outbox**, and that is a measured
 * refusal rather than an omission. `lib/offline.ts`'s drain reads the response of every replayed
 * entry as a created RECORD and requires a top-level string `id`:
 *
 *     const createdId = typeof saved?.id === "string" && saved.id ? saved.id : null;
 *     if (!createdId) { await markFailure(progress, "The server accepted this but did not say
 *                       what it saved…"); }
 *
 * `POST /design-ratings` answers `{ "rating": {…}, "replayed": bool }` — no top-level id — so every
 * SUCCESSFUL replay of a queued rating would be marked as a permanent captive-portal failure and
 * shown to the designer as work that never landed. The server is already built for an outbox (it
 * is idempotent under replay and orders deliveries by the device clock), so the queue is worth
 * having; it needs either a shape the drain can read or a drain that can be told what to read, and
 * both are files this change does not own. Until then a rating submitted with no signal is REFUSED
 * OUT LOUD with the text left in the boxes, which loses nothing and claims nothing.
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
