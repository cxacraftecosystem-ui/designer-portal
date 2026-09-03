/**
 * ONE EXPIRED MEDIA URL, REFRESHED ONCE — AND NEVER WITHOUT A LAST TIME.
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS EXISTS FOR
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `MediaFile.url` has always been a permanent, unauthenticated CDN link: the host plus the object
 * key, world-readable by bucket policy, good for ever to anybody it is ever copied to. Closing that
 * means the server stops emitting the permanent string and starts emitting a 15-minute signed one
 * (`MEDIA_PRESIGNED_READS` on the API), and then a human removes the bucket's public-read statement
 * so every previously leaked URL starts answering 403.
 *
 * Both halves break a client that assumes a URL it once received still works. This module is the
 * half of the repair that lives in the browser, and it is deliberately written to be SAFE WHETHER
 * OR NOT THE SERVER FLAG IS ON — it ships weeks before the flip and does nothing until a URL
 * actually fails.
 *
 * ── THE TWO WAYS A URL GOES BAD, AND THE TWO ANSWERS ────────────────────────────────────────────
 *
 *   1. **KNOWN BAD BEFORE IT IS DRAWN.** A signed URL carries its own death certificate:
 *      `X-Amz-Date` + `X-Amz-Expires` are right there in the query string. A payload that has been
 *      sitting in a store, a React tree or a background tab for twenty minutes can be READ as
 *      expired without asking anybody. {@link presignedUrlExpired} is that read, and refreshing on
 *      it means the reader never sees a broken frame at all.
 *   2. **DISCOVERED BAD WHEN IT IS DRAWN.** `<img>`, `<video>` and `<audio>` fire a bare `error`
 *      event with NO status on it — a 403, a 404, a DNS failure and a corrupt JPEG are all the same
 *      event. So the failure is classified by asking, once: {@link probeMediaUrl} issues a
 *      one-byte ranged GET and reads the real status back. (`Range` is not part of a presigned
 *      signature, so adding the header cannot invalidate the URL.)
 *
 * The answer to both is the same and is the ONLY thing this module does: re-request the owning
 * record — `GET /media/{id}`, which is entitlement-gated exactly as the list is — take whatever
 * `url` the server hands back now, and try that once.
 *
 * ── NEVER LOOP. THIS IS THE PROPERTY THE WHOLE MODULE IS BUILT AROUND ───────────────────────────
 *
 * A refresh that can retry is a refresh that can hammer, and the ledger has TWO LAYERS because one
 * of them turned out not to be a terminator.
 *
 * THE INNER LAYER DEDUPES BY EXACT QUESTION. Every attempt is recorded against
 * `${mediaId}\n${theUrlThatFailed}` ({@link attemptKey}) and {@link refreshVerdict} answers
 * `"already-tried"` for a repeat. That is what makes a re-render — or a second component mounting
 * the same photograph — cost nothing at all, the probe included.
 *
 * THE OUTER LAYER IS A BUDGET PER MEDIA ID, AND THE INNER ONE DOES NOT TERMINATE WITHOUT IT
 * (2026-09-03). The paragraph that stood here argued the (media, url) key was itself the stop —
 * *"a URL that fails again after being refreshed = a broken frame, and the reader is told, rather
 * than a request every few hundred milliseconds"*. That was true while URLs were permanent and it
 * stopped being true of the very change this module exists for: once reads are signed, every
 * refresh mints a DIFFERENT url, so the second failure is a different key, and so is the third. An
 * element that cannot render its bytes AT ALL — a corrupt JPEG, a codec this browser refuses, an
 * `<img>` pointed at a PDF — therefore re-armed `onError` for ever and issued `GET /media/{id}`
 * every few hundred milliseconds from a page that looks idle, which is exactly the storm the ledger
 * was written to prevent. {@link MEDIA_REFRESH_BUDGET} bounds it, and it counts REQUESTS PER MEDIA
 * ID whatever the url strings say; after that {@link refreshVerdict} answers `"budget-spent"` and
 * the element keeps the failure state it is already drawing.
 *
 * So:
 *
 *   * one failing image whose fresh link works = one `GET /media/{id}`, and one is the ordinary case;
 *   * a gallery of forty photographs whose signatures all expired together = forty refreshes at
 *     most, deduplicated to ONE request per media id by the in-flight map in {@link refreshMediaUrl}
 *     — which is also why the budget counts requests ISSUED and not callers asking: forty
 *     thumbnails of one file must spend one unit of it rather than forty;
 *   * a file whose fresh url fails too = one further attempt, and then a broken frame the reader
 *     can see and ask about.
 *
 * Both layers are per page-session on purpose. Neither is persisted, so a reload genuinely retries —
 * which is the right answer for a transient outage and the wrong one for a render loop.
 *
 * ── OFFLINE: THE RESIDUAL, STATED HONESTLY ──────────────────────────────────────────────────────
 *
 * `lib/designWorkshopStore.ts` keeps the BYTES of a capture only until the server acknowledges the
 * upload (`confirmLocalMedia` clears `blob` at that point, deliberately — see its header). After
 * that the browser holds a URL and nothing else. So an offline reader looking at a workshop whose
 * signed URLs have expired CANNOT be rescued: there is no local copy to draw and no network to ask
 * for a new link. {@link refreshVerdict} answers `"offline"` and this module does nothing, because
 * the alternative — a queued refresh that fires on reconnect — would repaint a screen the reader
 * has already left.
 *
 * That is a real regression against today's permanent URLs and it is the price of the URLs
 * expiring. It is bounded by the TTL and it is the reason the server flag is sequenced behind
 * client adoption rather than shipped with it. The durable fix is a byte cache for remote media
 * (the Android draft store already has one for its own captures); it is named in the handoff notes
 * and is not built here.
 *
 * ── WHY A MODULE AND NOT A HOOK ─────────────────────────────────────────────────────────────────
 *
 * Everything above is a decision, and there is no React renderer in this repo's devDependencies —
 * so a judgement written inside JSX is only ever exercised by somebody looking at a screen. Same
 * split, same reason, as `components/ui/selectFilter.ts` and `components/data/cappedList.ts`: the
 * pure half lives here and `e2e/media-url-refresh-unit.spec.ts` calls it. The thin React wrapper is
 * `components/hooks/useMediaUrl.ts`.
 */

import { apiFetch } from "@/lib/api";
import type { MediaFile } from "@/lib/types";

/** The two things this module needs off a media row. Anything carrying them can be refreshed. */
export type RefreshableMedia = {
  /** The persisted `MediaFile` id. Absent for a local, not-yet-uploaded preview — nothing to ask for. */
  id?: string | null;
  url?: string | null;
};

/**
 * What we managed to learn about a failed fetch.
 *
 * `"unreadable"` is not a lesser version of `"status"` — it is a different fact, and it is the
 * ordinary one when object storage does not return CORS headers for this origin. Treating it as
 * "probably fine" would leave every stale URL broken on exactly those deployments.
 */
export type MediaFailure = { kind: "status"; status: number } | { kind: "unreadable" };

/** What to do about one failure. Every value is a decision somebody can disagree with out loud. */
export type RefreshVerdict =
  /** Ask the server for this row again and try the URL it gives back. */
  | "refresh"
  /** No network. Nothing can be asked and nothing local can be substituted — see the header. */
  | "offline"
  /** A local preview with no persisted id: there is no record to re-request. */
  | "no-record"
  /** This exact (media, url) pair has already had its one attempt. THE INNER LOOP GUARD. */
  | "already-tried"
  /** This row has spent its whole {@link MEDIA_REFRESH_BUDGET}. THE TERMINATOR — see the header. */
  | "budget-spent"
  /** The server answered something a fresh link cannot fix — 404, 5xx. Leave it broken and honest. */
  | "gone";

/** SigV4 query parameters, spelled once. S3 emits them in this exact case. */
const SIGNATURE_PARAM = "X-Amz-Signature";
const DATE_PARAM = "X-Amz-Date";
const EXPIRES_PARAM = "X-Amz-Expires";

/**
 * Seconds of slack subtracted from a signature's own expiry before it is called dead.
 *
 * A URL that expires in four seconds is not worth drawing: the request has to be issued, cross a
 * village connection and be answered, and a signature that dies mid-transfer produces a truncated
 * image rather than a clean failure. Ten seconds is smaller than any TTL this server will ever
 * issue (900s today) and larger than a round trip, which is the whole requirement.
 */
const EXPIRY_SLACK_MS = 10_000;

/**
 * How many `GET /media/{id}` one media row may cost this page-session, whatever its urls look like.
 *
 * TWO, AND THE SECOND ONE IS NOT GENEROSITY. One is the ordinary repair: a signature expired and
 * the fresh one works. The second covers the one honest race this module cannot otherwise answer —
 * a payload whose url expired while the refresh itself was in flight, which is a real fifteen-minute
 * TTL against a village connection. A third would buy nothing: two consecutive fresh signatures
 * failing is a fact about the BYTES, and no number of new links repairs a file the browser cannot
 * decode.
 *
 * PER MEDIA ID AND PER PAGE-SESSION, deliberately at both ends. Per media id, because a gallery of
 * forty different photographs whose signatures expired together is forty genuine repairs and must
 * not be rationed against each other. Per page-session, because a reload has to retry — see the
 * header on why nothing here is persisted.
 */
export const MEDIA_REFRESH_BUDGET = 2;

/** Is this a signed URL at all? A permanent CDN link carries none of the SigV4 parameters. */
export function isPresignedUrl(url: string | null | undefined): boolean {
  if (!url) return false;
  return url.includes(`${SIGNATURE_PARAM}=`) && url.includes(`${EXPIRES_PARAM}=`);
}

/**
 * When this signed URL stops working, in epoch milliseconds, or null when it does not say.
 *
 * `X-Amz-Date` is ISO 8601 BASIC (`20260903T101112Z`) — no dashes, no colons — which
 * `new Date(...)` refuses outright in every browser. It is parsed by position rather than by
 * regex-and-hope, and a value that is not exactly sixteen characters of that shape yields null:
 * "this URL does not tell me when it dies" is a real answer and a great deal better than a
 * fabricated timestamp that makes a live URL look expired.
 */
export function presignedExpiryAt(url: string | null | undefined): number | null {
  if (!url) return null;
  let query: URLSearchParams;
  try {
    // A relative or malformed href is not a signed URL; a base makes the parse total.
    query = new URL(url, "https://placeholder.invalid").searchParams;
  } catch {
    return null;
  }
  const stamp = query.get(DATE_PARAM);
  const lifetime = Number(query.get(EXPIRES_PARAM));
  if (!stamp || !Number.isFinite(lifetime) || lifetime <= 0) return null;
  // 20260903T101112Z -> 2026-09-03T10:11:12Z
  const match = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(stamp);
  if (!match) return null;
  const [, year, month, day, hour, minute, second] = match;
  const signedAt = Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second)
  );
  if (!Number.isFinite(signedAt)) return null;
  return signedAt + lifetime * 1000;
}

/**
 * Is this URL already dead, by its own arithmetic?
 *
 * FALSE FOR A URL THAT IS NOT SIGNED, and that is what makes this whole feature inert while the
 * server flag is off: a permanent CDN link has no expiry, so nothing is ever pre-emptively
 * refreshed and the browser behaves exactly as it did before this module existed.
 */
export function presignedUrlExpired(url: string | null | undefined, nowMs: number): boolean {
  const expiresAt = presignedExpiryAt(url);
  if (expiresAt === null) return false;
  return nowMs >= expiresAt - EXPIRY_SLACK_MS;
}

/** The ledger key: one attempt per media row PER URL, so a refreshed URL may fail on its own terms. */
export function attemptKey(mediaId: string, url: string | null | undefined): string {
  return `${mediaId}\n${url ?? ""}`;
}

/**
 * Should this failure be answered with a refresh? Pure, and the whole policy of the module.
 *
 * ORDER IS THE ARGUMENT. `already-tried` and `budget-spent` are both checked before the status, so a
 * 403 that has already been answered cannot be answered again by a caller that forgot to look;
 * `offline` is checked before all of them, because refreshing without a network is not a smaller
 * version of refreshing, it is a request that will fail and be counted against a URL that might have
 * been fine. `already-tried` comes before `budget-spent` only because it is the more specific truth
 * about the same refusal — a repeat of a known url is not merely a row out of budget.
 *
 * `spent` IS REQUIRED AND NOT OPTIONAL, which is the same decision `attempted` made and for a
 * stronger reason: it is the only terminator this module has once reads are signed (header), and a
 * loop guard a caller may omit is a loop guard that will be omitted.
 *
 * A NON-403 STATUS IS DELIBERATELY NOT REFRESHED. 404 means the object is gone and a fresh link
 * points at the same absence; 5xx means storage is unwell and a second request makes it worse. The
 * honest outcome for both is a broken frame the reader can see, not a retry they cannot.
 */
export function refreshVerdict(input: {
  mediaId: string | null | undefined;
  url: string | null | undefined;
  failure: MediaFailure;
  online: boolean;
  attempted: (key: string) => boolean;
  /** How many requests this media id has already cost. {@link refreshesSpent} in production. */
  spent: (mediaId: string) => number;
}): RefreshVerdict {
  const { mediaId, url, failure, online, attempted, spent } = input;
  if (!mediaId) return "no-record";
  if (!online) return "offline";
  if (attempted(attemptKey(mediaId, url))) return "already-tried";
  if (spent(mediaId) >= MEDIA_REFRESH_BUDGET) return "budget-spent";
  // The probe could not read a status — CORS, a dropped connection, a blocked request. This is the
  // ORDINARY case on a bucket that does not publish CORS headers to this origin, and it is exactly
  // the case in which an expired signature is invisible. One request is the cost of being wrong,
  // and the ledger above has already bounded it at one.
  if (failure.kind === "unreadable") return "refresh";
  return failure.status === 403 ? "refresh" : "gone";
}

/**
 * Ask storage what it really thinks of this URL, with a one-byte ranged GET.
 *
 * WHY NOT `HEAD`. A presigned URL signs its HTTP method; a HEAD against a GET signature is refused
 * with a signature error, which would report every healthy URL as broken. `Range` is a plain
 * request header and is NOT part of the signature, so it can be added freely — and it means a
 * 40 MB video costs one byte to classify.
 *
 * Never throws. A rejected fetch (CORS, offline, blocked) is the `"unreadable"` answer, which is a
 * fact about what we could learn rather than a fact about the object.
 */
export async function probeMediaUrl(url: string): Promise<MediaFailure> {
  try {
    const response = await fetch(url, {
      method: "GET",
      headers: { Range: "bytes=0-0" },
      // Never let a cached 403 answer for a URL that has since been re-signed.
      cache: "no-store"
    });
    return { kind: "status", status: response.status };
  } catch {
    return { kind: "unreadable" };
  }
}

// ---------------------------------------------------------------------------------------------
// The ledger and the single-flight map — module state, deliberately
// ---------------------------------------------------------------------------------------------

/**
 * Every (media, url) pair that has had its one attempt.
 *
 * MODULE-LEVEL AND NOT PER COMPONENT, because the same photograph is mounted by several components
 * at once — a thumbnail in `MediaCarousel`, the same file open in `MediaLightbox`, the row in a
 * gallery behind them. A per-component ledger would give one expired URL three attempts and would
 * grow one more with every renderer somebody adds.
 */
const attempts = new Set<string>();

/**
 * How many `GET /media/{id}` this page-session has ISSUED for each media id — the outer ledger.
 *
 * A SECOND MAP RATHER THAN A COUNT DERIVED FROM {@link attempts}, because the two count different
 * things and the difference is the whole fix. `attempts` grows a new entry for every fresh url the
 * server mints, so it can never reach a bound; this counts the row, whatever it is called this time.
 */
const spend = new Map<string, number>();

/** In-flight refreshes by media id, so forty thumbnails of one file make one request. */
const inFlight = new Map<string, Promise<string | null>>();

/** Has this pair already had its attempt? Exported so the pure verdict can be driven by a test. */
export function hasAttempted(key: string): boolean {
  return attempts.has(key);
}

/** Requests this media id has cost so far. Exported for {@link refreshVerdict}'s `spent`. */
export function refreshesSpent(mediaId: string): number {
  return spend.get(mediaId) ?? 0;
}

/**
 * Is there a network? "I cannot tell" answers YES, deliberately.
 *
 * `navigator.onLine` is absent during a server render and is absent (or not a boolean) in the Node
 * runtime the unit specs run under. Reading a missing property as `false` would make this module
 * conclude "offline" everywhere it cannot see, and the offline branch is the one that gives up — so
 * an unknowable environment would silently disable the whole repair. `false` is only ever returned
 * when the browser has actually said so.
 */
export function isOnline(): boolean {
  if (typeof navigator === "undefined") return true;
  return typeof navigator.onLine === "boolean" ? navigator.onLine : true;
}

/** Forget every recorded attempt. FOR TESTS AND FOR NOTHING ELSE — see the loop guard in the header. */
export function resetMediaRefreshLedger(): void {
  attempts.clear();
  spend.clear();
  inFlight.clear();
}

/**
 * Re-request one media row and return the `url` the server hands back now, or null.
 *
 * NULL IS A REAL ANSWER AND HAS THREE CAUSES, none of which a caller should treat as an error to
 * report: the row is gone (404), the caller is not entitled to the bytes (the encoder withholds
 * `url` — see `records._MEDIA_URL_KEYS`), or the row genuinely has no URL. In all three the right
 * behaviour on screen is the state that is already drawn, which every media component in this app
 * handles because `MediaFile.url` has been optional since long before this module.
 *
 * The attempt is recorded against the URL that FAILED, before the request goes out, so a refresh
 * that itself fails still costs exactly one.
 */
export async function refreshMediaUrl(
  mediaId: string,
  failedUrl: string | null | undefined
): Promise<string | null> {
  attempts.add(attemptKey(mediaId, failedUrl));
  const pending = inFlight.get(mediaId);
  if (pending) return pending;
  // THE BUDGET IS SPENT HERE AND NOT ABOVE, and the position is the rule (2026-09-03): it bounds
  // REQUESTS, and forty thumbnails of one photograph make one. Counting callers instead would spend
  // a row's whole budget on the first paint of a gallery, at which point the second failure — the
  // signature that died mid-refresh — could not be answered at all.
  spend.set(mediaId, refreshesSpent(mediaId) + 1);

  const request = apiFetch<MediaFile>(`/media/${mediaId}`)
    .then((row) => row?.url ?? null)
    .catch(() => null)
    .finally(() => {
      inFlight.delete(mediaId);
    });
  inFlight.set(mediaId, request);
  return request;
}

/**
 * The whole reactive path in one call: classify the failure, decide, refresh once, or answer null.
 *
 * Returns the URL to try next, or null for "leave what is on screen alone". A caller that gets null
 * must NOT clear the src it is already holding — a broken frame the reader can see beats a blank
 * one they cannot ask about.
 */
export async function resolveFailedMediaUrl(media: RefreshableMedia): Promise<string | null> {
  const url = media.url ?? null;
  if (!url) return null;

  // THE FOUR VERDICTS THAT NEED NO NETWORK ARE DECIDED FIRST, AND THIS ORDER IS A DEFECT THAT WAS
  // MEASURED RATHER THAN IMAGINED. Probing before the ledger meant a second `onError` for the same
  // URL — a re-render, or a second component mounting the same photograph — still issued a ranged
  // GET to object storage before the ledger refused it: no API call, but a storage request per
  // failure, for ever, from a page that looks idle. Asking with `"unreadable"` is what makes the
  // question probe-free: it is the most permissive failure, so it yields "refresh" exactly when the
  // four local guards (no id, offline, already tried, out of budget) all pass, and nothing else.
  // The budget joined them on 2026-09-03 and had to join them HERE: a row that has spent it is the
  // loop case, and answering it after a probe would leave the storage request in the loop.
  const local = refreshVerdict({
    mediaId: media.id,
    url,
    failure: { kind: "unreadable" },
    online: isOnline(),
    attempted: hasAttempted,
    spent: refreshesSpent
  });
  if (local !== "refresh") return null;

  const failure = await probeMediaUrl(url);
  const verdict = refreshVerdict({
    mediaId: media.id,
    url,
    failure,
    online: isOnline(),
    attempted: hasAttempted,
    spent: refreshesSpent
  });
  if (verdict !== "refresh") return null;
  const fresh = await refreshMediaUrl(media.id as string, url);
  // A server that hands back the SAME string has told us the URL is not the problem. Returning it
  // would re-arm every renderer for a load that already failed, and the ledger would not stop the
  // second one — it keys on the url, and the url did not change.
  return fresh && fresh !== url ? fresh : null;
}
