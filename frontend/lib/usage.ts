/**
 * The aggregate half of `/api/usage`, read back — which screens are reached, how often, how fast,
 * how often broken.
 *
 * See `backend/app/api/routes/usage.py`'s module docstring for the full argument; the short version
 * repeated here because it decides what this file may and may not do:
 *
 *  - `/usage/routes` and `/usage/collection` are Admin-and-above and NEVER carry a user id — the
 *    distinct-account count is folded into an integer on the server, so a route module physically
 *    cannot leak one by accident, and this module has no function that could either. `/usage/me`
 *    (one account's own trail) is deliberately NOT read here: this page is the aggregate, cross-
 *    account view, and the self-service half belongs to whoever is asking about themselves, not to
 *    an admin screen.
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
    flowExists: boolean;
    explanation: string;
    consentStateWritten: string;
    refusalCost: string;
    document: string;
  };
  readableBy: Record<string, string>;
  limits: {
    maxWindowDays: number;
    maxRoutesPerRequest: number;
    minimumIdentifiedUsers: number;
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

/** True exactly when the server withheld this row rather than reporting a real number. */
export function isWithheldRoute(row: Pick<UsageRouteRow, "withheld">): boolean {
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
