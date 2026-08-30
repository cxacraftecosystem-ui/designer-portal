/**
 * The aggregate half of `/api/usage`, read back — which screens are reached, how often, how fast,
 * how often broken.
 *
 * See `backend/app/api/routes/usage.py`'s module docstring for the full argument; the short version
 * repeated here because it decides what this file may and may not do:
 *
 *  - `/usage/routes`, `/usage/timeline`, `/usage/latency`, `/usage/clients`, `/usage/screens` and
 *    `/usage/collection` are Admin-and-above and NEVER carry a user id — the distinct-account count
 *    is folded into an integer on the server, so a route module physically cannot leak one by
 *    accident, and none of the loaders here can either.
 *  - **THE TWO TRAILS ARE THE EXCEPTION, AND EACH CARRIES EXACTLY ONE ID BY DESIGN.**
 *    `/usage/me/trail` emits the caller's own and takes no account parameter at all — a person
 *    reading what the system recorded about them exercises no privilege. `/usage/accounts/{id}/
 *    trail` names a colleague, and is master-admin-only AND gated on that colleague's own consent
 *    being GRANTED. **Neither is a `?userId=` on any of the aggregates above**, and adding one
 *    would be the exact spelling `routes/usage.py`'s docstring forbids by name: a parameter is how
 *    a boundary gets crossed by somebody who never read the paragraph explaining it.
 *    This paragraph replaced one saying an account's own trail was "deliberately NOT read here",
 *    which was true until the route existed; leaving it would have described a client that no
 *    longer exists, on the file a later reader greps for the rule.
 *  - A withheld route comes back with every metric `null` and `withheld: true`. `null` becomes `0`
 *    through arithmetic and through `??`, so every reader of a `UsageRouteRow` must branch on
 *    `withheld` before touching a number — `isWithheldRoute` exists so nobody re-derives that check
 *    by hand. See `RateFigure` in `app/(protected)/admin/analytics/page.tsx` for the sibling
 *    convention this mirrors: a withheld figure renders in muted ink with the reason on a tooltip,
 *    never as a zero.
 *  - Nothing here computes a rate, a ratio or a re-sort. The server already decided the page's order
 *    (alphabetical by template, or the caller's own `?template=` order) and already decided which
 *    figures may be shown; recomputing either here would be the exact failure `workshopAnalytics.ts`
 *    was written to prevent, in a second place.
 */

import { apiFetch } from "@/lib/api";

export type UsageWindow = {
  from: string;
  to: string;
  days: number;
  maxDays: number;
  interval: string;
  naiveDatesReadAs: string;
};

export type UsageRouteRow = {
  routeTemplate: string;
  requests: number | null;
  identifiedUsers: number | null;
  withheld: boolean;
  withheldBecause?: string;
  ok: number | null;
  clientErrors: number | null;
  serverErrors: number | null;
  avgDurationMs: number | null;
  maxDurationMs: number | null;
};

export type UsageRoutesPage = {
  items: UsageRouteRow[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
  window: UsageWindow;
  /** "mounted" = every measured route in the application; "requested" = the caller named some. */
  routeSource: "mounted" | "requested";
  limits: {
    maxWindowDays: number;
    maxRoutesPerRequest: number;
    minimumIdentifiedUsers: number;
  };
  /** The sum over THIS PAGE only, withheld rows excluded — never a platform total. See the route's
   *  own docstring: a field named `total` beside a paged list is read as the platform figure by
   *  everybody, every time, which is why the server named this one `totalsForThisPage` instead. */
  totalsForThisPage: {
    routes: number;
    routesWithheld: number;
    requests: number;
    ok: number;
    clientErrors: number;
    serverErrors: number;
  };
  notMeasured: string[];
  notes: string[];
};

export type UsageCollectionMethod = {
  collects: string[];
  doesNotCollect: string[];
  notMeasured: string[];
  consent: {
    unaskedPolicy: string;
    options: string[];
    /**
     * TRUE SINCE 2026-08-30 — there is a column, a route and a screen on both clients.
     *
     * It was `false` for as long as there were none, and an admin who quoted this endpoint in a
     * methods section during that period was quoting an honest No. `CollectionPosture` branches on
     * it and NOT on any sentence, for the same reason `unaskedPolicy` is compared to its enum token
     * rather than sniffed out of prose: the wording is the server's to change.
     */
    flowExists: boolean;
    /** The text people are being asked to agree to. Present since the flow shipped. */
    noticeVersion?: string;
    /** The circumstances an answer can be recorded under — the turnstile and the free choice. */
    bases?: string[];
    /** When the question is put, and why a grant at the door is not free consent. */
    askedAt?: string;
    /** What withdrawing costs. The answer is "nothing", and that asymmetry is what makes the
     *  condition of access defensible rather than merely documented. */
    withdrawalCosts?: string;
    explanation: string;
    consentStateWritten: string;
    refusalCost: string;
    document: string;
    /** The decision note that preceded the flow. Kept because its argument is frozen, not rewritten
     *  to agree with later code. */
    priorDocument?: string;
  };
  readableBy: Record<string, string>;
  limits: {
    maxWindowDays: number;
    maxRoutesPerRequest: number;
    minimumIdentifiedUsers: number;
    /** The ceiling on `/usage/timeline` buckets — a window and a bucket size that would exceed it
     *  are refused with a 400 rather than truncated silently. */
    maxTimelineBuckets?: number;
    /** The ceiling on rows either trail route will return in one page. */
    maxTrailRows?: number;
    rowsPerWrite: number;
    flushIntervalSeconds: number;
    bufferCeiling: number;
  };
  losses: {
    scope: string;
    buffered: number;
    written: number;
    droppedAtCeiling: number;
    abandonedAfterFailedWrites: number;
    failedFlushes: number;
    explanation: string;
  };
  knownLimitations: string[];
  retention: string;
  document: string;
};

/**
 * True exactly when the server withheld this row rather than reporting a real number.
 *
 * **ONE NAME FOR THIS CHECK, IN THE WHOLE CLIENT, DELIBERATELY.** It began as a route-row predicate
 * and is now taken by timeline buckets, latency rows and client rows as well, so the parameter is
 * the structural `{ withheld: boolean }` rather than `Pick<UsageRouteRow, …>`. A second predicate
 * named `isWithheld` sitting beside this one would be the shape of bug this repository files under
 * "two implementations of one rule": both correct on the day they were written, and one of them
 * quietly not updated when the rule moves. Every chart path in `usageCharts.tsx` calls THIS.
 */
export function isWithheldRoute(row: { withheld: boolean }): boolean {
  return row.withheld;
}

/** A duration in whole milliseconds, or the withheld dash. Never computed — read straight off the
 *  row, because a client-side average of an average is not the average of anything real. */
export function durationText(ms: number | null): string {
  return ms === null ? "—" : `${Math.round(ms)} ms`;
}

/** ISO instant `days` ago at local midnight — the default LEFT edge of the window. */
export function daysAgoIso(days: number): string {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - days);
  return d.toISOString();
}

/** ISO instant for right now — the default RIGHT edge of the window. */
export function nowIso(): string {
  return new Date().toISOString();
}

export function loadUsageRoutes(params: {
  from: string;
  to: string;
  page?: number;
  pageSize?: number;
}): Promise<UsageRoutesPage> {
  const search = new URLSearchParams({ from: params.from, to: params.to });
  if (params.page) search.set("page", String(params.page));
  if (params.pageSize) search.set("pageSize", String(params.pageSize));
  return apiFetch<UsageRoutesPage>(`/usage/routes?${search.toString()}`);
}

export function loadUsageCollection(): Promise<UsageCollectionMethod> {
  return apiFetch<UsageCollectionMethod>("/usage/collection");
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * CONSENT — the answer, the notice a person is answering, and the gate a client renders from
 *
 * ONE SOURCE FOR THE COPY, AND IT IS THE SERVER. Every sentence a person reads before agreeing is
 * fetched from `GET /api/usage/consent/notice` and rendered verbatim; nothing in this client
 * paraphrases, summarises or reorders it. That is not tidiness. The same payload is rendered by the
 * Android sign-in screen, and the sentences are computed on the server FROM THE POLICY ACTUALLY IN
 * FORCE (`usage.collects()` reads `DEFAULT_UNASKED_COLLECTION` rather than asserting a value), so a
 * deployment that changed what it records would publish a changed notice on the same deploy. A copy
 * written out in TSX here and again in Kotlin there is how one decision comes to be described two
 * ways — and here that would not be an inconsistency, it would be two different consents.
 *
 * NOTHING IN THIS FILE DECIDES WHETHER TO ASK. `gate.required` is computed by
 * `usage.consent_gate()` and is two facts folded into one — have they agreed, and did they agree to
 * the CURRENT text. The moment this client folds it for itself, the web and the handset disagree on
 * the first deploy that moves `NOTICE_VERSION` while only one of them ships. So `usageConsentGate`
 * is read, never derived, and there is deliberately no exported helper here that compares versions.
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/** The three states, and never a boolean. `NOT_RECORDED` is "nobody has been asked", which is a
 *  different fact from a refusal nobody ever made — see `backend/app/services/usage.py`. */
export type UsageConsentState = "NOT_RECORDED" | "GRANTED" | "REFUSED";

/**
 * The CIRCUMSTANCE an answer was given in, stored beside the answer itself.
 *
 * This is the column that makes the sign-in gate defensible rather than merely documented. A grant
 * collected at a turnstile is a condition of access and therefore not freely given; recording it as
 * a bare `GRANTED` would file a turnstile as a free choice. Every withdrawal is
 * `OFFERED_IN_SETTINGS` and the server supplies that itself — a client cannot file a withdrawal as
 * though it had been demanded of somebody.
 */
export type UsageConsentBasis = "REQUIRED_AT_SIGN_IN" | "OFFERED_IN_SETTINGS";

/** `GET /api/usage/consent/notice` — UNGATED on the server, because a person deciding whether to
 *  agree has not agreed yet and, on the web sign-in screen, holds no token either. */
export type UsageConsentNotice = {
  version: string;
  title: string;
  required: boolean;
  requiredSentence: string;
  collects: string[];
  doesNotCollect: string[];
  durationCaveat: string;
  /** Keyed by route: which accounts may read what. Rendered as written — a route missing from it
   *  would make the notice false for everybody who has already answered. */
  readableBy: Record<string, string>;
  withdrawal: {
    where: string;
    costsNothing: string;
    does: string[];
    doesNot: string[];
  };
  retention: string;
  document: string;
};

/** One account's stored answer. Four fields rather than one, because "GRANTED" alone cannot say
 *  when, under what circumstances, or to which text. */
export type UsageConsentRecord = {
  state: UsageConsentState;
  at: string | null;
  basis: UsageConsentBasis | string | null;
  version: string | null;
};

/**
 * Whether this account must be asked NOW, and the sentence saying why.
 *
 * `required` and `reason` are both the server's. Three states reach three different sentences
 * because the next moves differ: nobody-has-asked is answered by asking; a REFUSED account is
 * working normally and telling it to go and agree would be false; a stale version wants a fresh
 * reading rather than a first one. Rendering `reason` is what keeps this client from inventing a
 * fourth.
 */
export type UsageConsentGate = {
  state: UsageConsentState;
  required: boolean;
  reason: string;
  noticeVersion: string;
  agreedVersion: string | null;
  agreedAt: string | null;
  basis: UsageConsentBasis | string | null;
  answerAt: string;
  noticeAt: string;
};

/**
 * One row of the append-only decision log.
 *
 * TWO CLOCKS, AND THEY ARE NOT A DUPLICATION. `recordedAt` is when the box was ticked, as the
 * client's clock reported it (null when the answer was given straight against the server, where a
 * copy would later read as "a device reported this" and be false); `createdAt` is when the server
 * heard it. A fortnight of no signal makes them differ by a fortnight, and a reader who can see
 * only one cannot tell today's answer from one given before the handset last synced.
 */
export type UsageConsentDecision = {
  id: string | null;
  decision: UsageConsentState | null;
  basis: UsageConsentBasis | string | null;
  noticeVersion: string | null;
  note: string | null;
  recordedAt: string | null;
  createdAt: string | null;
};

/** What a withdrawal actually reached. `storedDeleteRan: false` means collection has stopped and
 *  the deletion has NOT happened — which is why `explanation` is rendered rather than a zero. */
export type UsageWithdrawal = {
  bufferedDropped: number;
  storedDeleted: number;
  storedDeleteRan: boolean;
  explanation: string;
};

/** `GET /api/usage/consent` — this account's own answer, its history, and the notice. */
export type MyUsageConsent = {
  userId: string;
  consent: UsageConsentRecord;
  gate: UsageConsentGate;
  notice: UsageConsentNotice;
  decisions: UsageConsentDecision[];
};

/** What `POST /api/usage/consent` and `POST /api/usage/consent/withdraw` answer with. */
export type UsageConsentResult = {
  userId: string;
  consent: UsageConsentRecord;
  gate: UsageConsentGate;
  decisions: UsageConsentDecision[];
  withdrawal?: UsageWithdrawal;
};

/**
 * The usage-consent gate off an account row, or null.
 *
 * ── WHY THIS IS A FUNCTION HERE AND NOT A FIELD ON `User` ───────────────────────────────────────
 *
 * `serialize_user` (`backend/app/api/routes/auth.py`) is `jsonable_encoder(user)` minus the password
 * hash, plus this one derived key — so `usageConsentGate` reaches `POST /auth/login`, `GET /auth/me`
 * and `GET /me` with no route changes at all. It is nonetheless read through a narrowing accessor
 * rather than added to `lib/types.ts`'s `User`, for two reasons that both hold:
 *
 *  1. **The field is genuinely optional in time.** The frontend and the API deploy separately, so
 *     there is a window in which this build is talking to a server that predates the column. An
 *     accessor that returns `null` for that case makes every caller deal with it; an optional field
 *     invites `user.usageConsentGate!.required`, which throws on the front door of the app.
 *  2. It is checked at runtime rather than asserted. `required` is the one field a client must never
 *     compute — see `UsageConsentGate` — so a payload that carries the key without a usable boolean
 *     is treated as an absent gate rather than as a permissive one. **Absent means "do not claim to
 *     know", never "no consent is needed".**
 *
 * When somebody owns `lib/types.ts`, the honest home for this is `usageConsentGate?: UsageConsentGate`
 * on `User`, and this function becomes the reader that still tolerates its absence.
 */
export function usageConsentGateOf(user: unknown): UsageConsentGate | null {
  if (!user || typeof user !== "object") return null;
  const gate = (user as { usageConsentGate?: unknown }).usageConsentGate;
  if (!gate || typeof gate !== "object") return null;
  const candidate = gate as Partial<UsageConsentGate>;
  if (typeof candidate.required !== "boolean" || typeof candidate.reason !== "string") return null;
  return candidate as UsageConsentGate;
}

/**
 * The notice, with no token and no session.
 *
 * `redirectOn401: false` is load-bearing here for the same reason `AuthProvider.refreshMe` passes
 * it: this call is made on /login, a public page, by a browser that may still be holding a
 * six-week-old token in localStorage. On the default, that token would be sent, refused, and the
 * visitor hard-navigated to the sign-in form they are already looking at — mid-read, before they
 * have typed anything.
 */
export function loadUsageConsentNotice(): Promise<UsageConsentNotice> {
  return apiFetch<UsageConsentNotice>("/usage/consent/notice", {}, { redirectOn401: false });
}

export function loadMyUsageConsent(): Promise<MyUsageConsent> {
  return apiFetch<MyUsageConsent>("/usage/consent");
}

/**
 * Record this account's own answer.
 *
 * `noticeVersion` IS WHAT THIS SCREEN ACTUALLY SHOWED, never a constant typed here — a client that
 * sends a version it did not display records an agreement to text the person never saw. It comes
 * off the fetched notice and travels back untouched.
 *
 * `recordedAt` IS WHEN THE BOX WAS TICKED, not when the request left. On sign-in the two are
 * separated by a network round trip and a password check; on a bad connection that is not seconds.
 * A server-side `createdAt` already records when this API heard the answer, so sending the moment
 * of the tick is the only way the pair means anything — and a time more than fifteen minutes in the
 * future is refused by the server rather than stored.
 */
export function recordUsageConsent(payload: {
  decision: Extract<UsageConsentState, "GRANTED" | "REFUSED">;
  basis: UsageConsentBasis;
  noticeVersion: string;
  recordedAt?: string;
  note?: string;
}): Promise<UsageConsentResult> {
  return apiFetch<UsageConsentResult>("/usage/consent", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

/**
 * Take it back. A named door rather than `decision: "REFUSED"` typed by hand, because the server
 * supplies both the decision and the basis — so this client cannot file a withdrawal as though it
 * had been demanded of somebody at a door.
 *
 * IT MUST NOT SIGN ANYBODY OUT AND MUST NOT REMOVE ANY CAPABILITY. That asymmetry is the whole
 * design: the gate at sign-in makes agreeing a condition of access, and this is what a person
 * retains. A withdrawal that cost access would be theatre. Callers therefore refresh the account
 * and stay exactly where they are.
 */
export function withdrawUsageConsent(payload: {
  noticeVersion: string;
  recordedAt?: string;
  note?: string;
}): Promise<UsageConsentResult> {
  return apiFetch<UsageConsentResult>("/usage/consent/withdraw", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

/**
 * The one sentence this client computes about a decision row — and it is a LABEL, not a judgement.
 *
 * `basis` is rendered in words on every row for the reason the column exists: "agreed" on nine
 * thousand accounts means something entirely different when it was a turnstile, and a log that
 * showed only the answer would let a future reader — or a methods section — mistake the two.
 * Unknown tokens fall through to the raw value rather than to a guess, because a basis this build
 * has never heard of is a fact about a newer server and not an error.
 */
export function consentBasisText(basis: string | null | undefined): string {
  if (basis === "REQUIRED_AT_SIGN_IN") return "Required at sign-in — a condition of access, not a free choice";
  if (basis === "OFFERED_IN_SETTINGS") return "Offered in settings — freely given, or freely taken back";
  return basis ? String(basis) : "Not recorded";
}

/** A stored instant as a person reads it, or the em dash. Never "now" and never a relative phrase:
 *  a consent record is a dated document and "3 days ago" is not a date. */
export function consentMoment(iso: string | null | undefined): string {
  if (!iso) return "—";
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return String(iso);
  return at.toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE RICHER AGGREGATES — traffic over time, error rate, latency percentiles, client split,
 * busiest and slowest screens, and the two request trails.
 *
 * THE THREE STATES EVERY ONE OF THESE CARRIES, AND THE REASON THIS FILE REPEATS THEM. A reader of
 * any row below has to tell apart:
 *
 *   * **nothing happened** — `requests: 0`, `withheld: false`, and every rate `null` because 0/0 is
 *     "nothing happened" and not "nothing went wrong";
 *   * **withheld** — `withheld: true` and EVERY metric `null`, because fewer identified accounts
 *     used it than the server's floor;
 *   * **no traffic on a route that exists** — nulls with `withheld: false`.
 *
 * `null` becomes `0` through arithmetic, through `??` and through `Number()`, so a chart that
 * plotted the second as a point on the axis would publish a figure the server explicitly refused to
 * state — a refusal drawn as a measurement. `isWithheld` below is the only branch a caller needs,
 * and it exists so nobody re-derives that check by hand in a chart path.
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/** The named, capped set of screens an aggregate answered about. Never "the platform" — there is
 *  deliberately no route that answers about every screen, because that is a whole-window scan and
 *  the schema builds no index for one. */
export type UsageScope = {
  templates: string[];
  source: "requested" | "mounted";
  count: number;
  mountedTotal: number;
  /** How many mounted screens are OUTSIDE the answer. Printed, always — a slice presented as the
   *  whole is the silent-truncation class this repository keeps re-filing. */
  notIncluded: number;
  maxPerRequest: number;
};

export type UsageAggregateLimits = {
  maxWindowDays: number;
  maxRoutesPerRequest: number;
  minimumIdentifiedUsers: number;
  maxBuckets?: number;
};

export type UsageTimelineBucket = {
  /** The UTC calendar hour or day the bucket STARTS at, as the server labelled it. Not a local-time
   *  day: a report that straddles a timezone will not agree with one computed in that timezone. */
  bucket: string;
  requests: number | null;
  ok: number | null;
  clientErrors: number | null;
  serverErrors: number | null;
  /** The share of requests that answered 4xx or 5xx, 0..1. `null` where there were no requests. */
  errorRate: number | null;
  identifiedUsers: number | null;
  withheld: boolean;
  withheldBecause?: string;
};

export type UsageTimeline = {
  window: UsageWindow;
  bucket: "day" | "hour";
  scope: UsageScope;
  series: UsageTimelineBucket[];
  limits: UsageAggregateLimits;
  notes: string[];
};

export type UsageLatencyRow = {
  routeTemplate: string;
  requests: number | null;
  identifiedUsers: number | null;
  withheld: boolean;
  withheldBecause?: string;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
  maxDurationMs: number | null;
};

export type UsageLatency = {
  window: UsageWindow;
  scope: UsageScope;
  percentiles: string[];
  routes: UsageLatencyRow[];
  limits: UsageAggregateLimits;
  notes: string[];
};

export type UsageClientRow = {
  clientApp: string;
  requests: number | null;
  ok: number | null;
  clientErrors: number | null;
  serverErrors: number | null;
  errorRate: number | null;
  identifiedUsers: number | null;
  avgDurationMs: number | null;
  withheld: boolean;
  withheldBecause?: string;
};

export type UsageClients = {
  window: UsageWindow;
  scope: UsageScope;
  clients: UsageClientRow[];
  /** The request header a client must send to be counted as itself — `x-client-app`. */
  header: string;
  known: string[];
  /** What a request that did not send the header is filed under. NOT a kind of client. */
  fallback: string;
  limits: UsageAggregateLimits;
  notes: string[];
};

export type UsageScreens = {
  window: UsageWindow;
  scope: UsageScope;
  limit: number;
  busiest: UsageRouteRow[];
  slowest: UsageRouteRow[];
  /** Withheld screens are EXCLUDED from both orderings rather than placed in them, and counted here
   *  so the ranking can be read as covering less than the whole scope. */
  withheld: { routes: number; explanation: string };
  limits: UsageAggregateLimits;
  notes: string[];
};

/** One recorded request, as either trail route returns it. Seven fields and no eighth — there is no
 *  interpolated path here, so no record id, and nothing anybody typed. */
export type UsageTrailEvent = {
  id: string;
  routeTemplate: string;
  method: string;
  statusCode: number;
  durationMs: number;
  clientApp: string;
  /** The answer THIS ROW was collected under. It can differ from the account's answer today. */
  consentState: string | null;
  at: string;
};

export type MyUsageTrail = {
  userId: string;
  window: UsageWindow;
  limit: number;
  offset: number;
  maxRows: number;
  events: UsageTrailEvent[];
  consent: UsageConsentRecord;
  collection: { unaskedPolicy: string; attributesUnaskedRequests: boolean; explanation: string };
  gate: UsageConsentGate;
  notes: string[];
};

export type AccountUsageTrail = {
  userId: string;
  window: UsageWindow;
  limit: number;
  offset: number;
  maxRows: number;
  events: UsageTrailEvent[];
  subjectConsent: UsageConsentRecord;
  readBy: string;
  notes: string[];
};

/** The window every aggregate takes. Both edges required by the server; the cap is on the response
 *  as well as in the refusal, so it is never something a caller has to already know. */
type UsageRange = { from: string; to: string };

function rangeQuery(range: UsageRange): URLSearchParams {
  return new URLSearchParams({ from: range.from, to: range.to });
}

export function loadUsageTimeline(params: UsageRange & { bucket: "day" | "hour" }): Promise<UsageTimeline> {
  const search = rangeQuery(params);
  search.set("bucket", params.bucket);
  return apiFetch<UsageTimeline>(`/usage/timeline?${search.toString()}`);
}

export function loadUsageLatency(params: UsageRange): Promise<UsageLatency> {
  return apiFetch<UsageLatency>(`/usage/latency?${rangeQuery(params).toString()}`);
}

export function loadUsageClients(params: UsageRange): Promise<UsageClients> {
  return apiFetch<UsageClients>(`/usage/clients?${rangeQuery(params).toString()}`);
}

export function loadUsageScreens(params: UsageRange & { limit?: number }): Promise<UsageScreens> {
  const search = rangeQuery(params);
  if (params.limit) search.set("limit", String(params.limit));
  return apiFetch<UsageScreens>(`/usage/screens?${search.toString()}`);
}

/** The caller's own trail. `get_current_user` and nothing more — a person reading what the system
 *  recorded about them exercises no privilege, and this is what makes the notice's "you can see
 *  exactly what we hold about you" true rather than aspirational. */
export function loadMyUsageTrail(params: UsageRange & { limit?: number; offset?: number }): Promise<MyUsageTrail> {
  const search = rangeQuery(params);
  if (params.limit) search.set("limit", String(params.limit));
  if (params.offset) search.set("offset", String(params.offset));
  return apiFetch<MyUsageTrail>(`/usage/me/trail?${search.toString()}`);
}

/**
 * ONE NAMED COLLEAGUE'S trail — the most sensitive read in this feature.
 *
 * MASTER ADMIN ALONE (`deps.can_read_person_usage`), and only where that account's own answer is
 * GRANTED. Its own path segment rather than a `?userId=` on any of the aggregates: a parameter is
 * how a boundary gets crossed by somebody who never read the paragraph explaining it, and
 * `routes/usage.py`'s docstring forbids that spelling by name. Every call writes one server log
 * line naming the reader, the subject and the window; there is deliberately no durable audit table
 * yet, and the screen says so rather than implying one.
 */
export function loadAccountUsageTrail(
  userId: string,
  params: UsageRange & { limit?: number; offset?: number }
): Promise<AccountUsageTrail> {
  const search = rangeQuery(params);
  if (params.limit) search.set("limit", String(params.limit));
  if (params.offset) search.set("offset", String(params.offset));
  return apiFetch<AccountUsageTrail>(
    `/usage/accounts/${encodeURIComponent(userId)}/trail?${search.toString()}`
  );
}

/**
 * A server-sent share (0..1) as a percentage, or the withheld/absent dash.
 *
 * **THE `null` CHECK IS THE WHOLE FUNCTION.** `errorRate` is `null` in two different circumstances
 * the server keeps apart — a bucket with no requests at all, and a bucket it withheld — and in
 * neither is the answer "0%". `(rate ?? 0) * 100` would print a clean, confident zero for both: for
 * an empty hour it would claim nothing went wrong when nothing happened, and for a withheld one it
 * would publish a figure the server declined to state. The rounding is presentation and not a
 * computation: the share itself is the server's.
 */
export function errorRateText(rate: number | null): string {
  if (rate === null || rate === undefined || !Number.isFinite(rate)) return "—";
  return `${(rate * 100).toFixed(rate > 0 && rate < 0.01 ? 2 : 1)}%`;
}

/**
 * A UTC bucket label as an axis tick.
 *
 * The label is the moment the bucket STARTS and the server says so; this only shortens it. Hour
 * buckets print the hour, day buckets print the day, and both stay in UTC — converting to the
 * reader's timezone here would silently re-bucket the data, drawing an hour's traffic under a
 * neighbouring hour's tick with nothing on screen to say it had moved.
 */
export function bucketTickText(bucket: string, unit: "day" | "hour"): string {
  const at = new Date(bucket);
  if (Number.isNaN(at.getTime())) return bucket;
  if (unit === "hour") return `${String(at.getUTCHours()).padStart(2, "0")}:00`;
  return `${at.getUTCDate()}/${at.getUTCMonth() + 1}`;
}
