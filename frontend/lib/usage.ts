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
  routeSource: "mounted" | "requested";
  limits: {
    maxWindowDays: number;
    maxRoutesPerRequest: number;
    minimumIdentifiedUsers: number;
  };
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
    flowExists: boolean;
    noticeVersion?: string;
    bases?: string[];
    askedAt?: string;
    withdrawalCosts?: string;
    explanation: string;
    consentStateWritten: string;
    refusalCost: string;
    document: string;
    priorDocument?: string;
  };
  readableBy: Record<string, string>;
  limits: {
    maxWindowDays: number;
    maxRoutesPerRequest: number;
    minimumIdentifiedUsers: number;
    maxTimelineBuckets?: number;
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

export function isWithheldRoute(row: { withheld: boolean }): boolean {
  return row.withheld;
}

export function durationText(ms: number | null): string {
  return ms === null ? "—" : `${Math.round(ms)} ms`;
}

export function daysAgoIso(days: number): string {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - days);
  return d.toISOString();
}

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

export type UsageConsentState = "NOT_RECORDED" | "GRANTED" | "REFUSED";

export type UsageConsentBasis = "REQUIRED_AT_SIGN_IN" | "OFFERED_IN_SETTINGS";

export type UsageConsentNotice = {
  version: string;
  title: string;
  required: boolean;
  requiredSentence: string;
  collects: string[];
  doesNotCollect: string[];
  durationCaveat: string;
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

export type UsageConsentRecord = {
  state: UsageConsentState;
  at: string | null;
  basis: UsageConsentBasis | string | null;
  version: string | null;
};

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

export type UsageConsentDecision = {
  id: string | null;
  decision: UsageConsentState | null;
  basis: UsageConsentBasis | string | null;
  noticeVersion: string | null;
  note: string | null;
  recordedAt: string | null;
  createdAt: string | null;
};

export type UsageWithdrawal = {
  bufferedDropped: number;
  storedDeleted: number;
  storedDeleteRan: boolean;
  explanation: string;
};

export type MyUsageConsent = {
  userId: string;
  consent: UsageConsentRecord;
  gate: UsageConsentGate;
  notice: UsageConsentNotice;
  decisions: UsageConsentDecision[];
};

export type UsageConsentResult = {
  userId: string;
  consent: UsageConsentRecord;
  gate: UsageConsentGate;
  decisions: UsageConsentDecision[];
  withdrawal?: UsageWithdrawal;
};

export function usageConsentGateOf(user: unknown): UsageConsentGate | null {
  if (!user || typeof user !== "object") return null;
  const gate = (user as { usageConsentGate?: unknown }).usageConsentGate;
  if (!gate || typeof gate !== "object") return null;
  const candidate = gate as Partial<UsageConsentGate>;
  if (typeof candidate.required !== "boolean" || typeof candidate.reason !== "string") return null;
  return candidate as UsageConsentGate;
}

export function loadUsageConsentNotice(): Promise<UsageConsentNotice> {
  return apiFetch<UsageConsentNotice>("/usage/consent/notice", {}, { redirectOn401: false });
}

export function loadMyUsageConsent(): Promise<MyUsageConsent> {
  return apiFetch<MyUsageConsent>("/usage/consent");
}

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

export function consentBasisText(basis: string | null | undefined): string {
  if (basis === "REQUIRED_AT_SIGN_IN") return "Required at sign-in";
  if (basis === "OFFERED_IN_SETTINGS") return "Chosen in Settings";
  return basis ? String(basis) : "Not recorded";
}

export function consentStateText(state: string | null | undefined): string {
  if (state === "GRANTED") return "Agreed";
  if (state === "REFUSED") return "Declined";
  if (state === "NOT_RECORDED" || !state) return "Not answered";
  return String(state);
}

export function consentMoment(iso: string | null | undefined): string {
  if (!iso) return "—";
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return String(iso);
  return at.toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });
}

export type UsageScope = {
  templates: string[];
  source: "requested" | "mounted";
  count: number;
  mountedTotal: number;
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
  bucket: string;
  requests: number | null;
  ok: number | null;
  clientErrors: number | null;
  serverErrors: number | null;
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
  header: string;
  known: string[];
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
  withheld: { routes: number; explanation: string };
  limits: UsageAggregateLimits;
  notes: string[];
};

export type UsageTrailEvent = {
  id: string;
  routeTemplate: string;
  method: string;
  statusCode: number;
  durationMs: number;
  clientApp: string;
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

export function loadMyUsageTrail(params: UsageRange & { limit?: number; offset?: number }): Promise<MyUsageTrail> {
  const search = rangeQuery(params);
  if (params.limit) search.set("limit", String(params.limit));
  if (params.offset) search.set("offset", String(params.offset));
  return apiFetch<MyUsageTrail>(`/usage/me/trail?${search.toString()}`);
}

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

export function errorRateText(rate: number | null): string {
  if (rate === null || rate === undefined || !Number.isFinite(rate)) return "—";
  return `${(rate * 100).toFixed(rate > 0 && rate < 0.01 ? 2 : 1)}%`;
}

export function bucketTickText(bucket: string, unit: "day" | "hour"): string {
  const at = new Date(bucket);
  if (Number.isNaN(at.getTime())) return bucket;
  if (unit === "hour") return `${String(at.getUTCHours()).padStart(2, "0")}:00`;
  return `${at.getUTCDate()}/${at.getUTCMonth() + 1}`;
}
