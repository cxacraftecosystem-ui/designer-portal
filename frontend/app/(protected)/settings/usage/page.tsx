"use client";

/**
 * /settings/usage — which screens are reached, how often, how fast, how often broken. NOT the
 * cross-workshop content comparison at /admin/analytics, and the name says so on purpose.
 *
 * **`/usage`, NOT `/analytics`, AND THE COLLISION IS THE REASON** — this page's own backing route,
 * `backend/app/api/routes/usage.py`, opens with the same sentence. `/admin/analytics` observes craft
 * outcomes and no person at all; this page observes people — which screens they reached, in what
 * order the platform recorded them arriving. Calling both "analytics" would leave every future
 * reader to work out which one a name meant, in a codebase where one of the two is a privacy
 * surface. So this one is titled, labelled and routed as "Usage" everywhere it appears.
 *
 * THREE RULES THIS COMPONENT FOLLOWS, copied from `/admin/analytics`'s own page because they are the
 * same three rules for the same reason:
 *
 * 1. **Nothing here computes.** Every figure comes off the wire. `isWithheldRoute` is the only
 *    branch a reader needs — a withheld row's numbers are `null`, and `null` becomes `0` through
 *    arithmetic and through `??`, so a page that fell back would publish a number the server
 *    explicitly refused to state.
 * 2. **The consent and collection posture renders FIRST, above the figures**, because a number with
 *    no stated method is a number nobody can check. Today that posture is: nothing is attributed to
 *    anyone (see `docs/DECISION-usage-consent-default.md`) — this page says so in the same words the
 *    API does, rather than leaving a reader to assume the rows carry names because an admin can see them.
 * 3. **Two gates, both mirrored, neither invented here.** `require_usage_reader` (Admin and above) on
 *    every route this page calls is the boundary; `ROUTE_GUARDS` mirrors it so the URL itself is
 *    refused for anyone below admin, not merely unlinked, and `permitted` below is the third copy
 *    for the case where this component is reached some other way. Admin view is mirrored by
 *    `ADMIN_CHROME_ROUTES`, so an admin browsing with admin view off never sees this card or this
 *    page — no request, no flash of figures about their colleagues' navigation.
 */

import { useEffect, useState } from "react";
import { Activity, CircleAlert } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { useAuth } from "@/components/AuthProvider";
import { isAdmin } from "@/lib/permissions";
import {
  daysAgoIso,
  durationText,
  isWithheldRoute,
  loadUsageCollection,
  loadUsageRoutes,
  nowIso,
  type UsageCollectionMethod,
  type UsageRoutesPage
} from "@/lib/usage";

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-md border border-line-200 bg-card p-4">
      <div className="field-label">{label}</div>
      <div className="mt-1 font-display text-2xl font-bold text-ink-900">{value}</div>
      {hint ? <p className="mt-1 text-xs leading-5 text-ink-500">{hint}</p> : null}
    </div>
  );
}

/** A count, or the withheld dash — the two must not look alike, or a reader skims past a refusal as
 *  though it were a small number. Muted ink plus a tooltip carrying the reason, exactly like
 *  `RateFigure` on the cross-workshop comparison page. */
function Metric({ value, reason }: { value: number | null; reason?: string }) {
  const withheld = value === null;
  return (
    <span className={withheld ? "text-ink-300" : "text-ink-900"} title={withheld ? reason : undefined}>
      {withheld ? "—" : value.toLocaleString("en-IN")}
    </span>
  );
}

/**
 * The consent and collection posture, rendered above every figure on the page.
 *
 * This is requirement 26's machine-readable half — `docs/METHODOLOGY-usage-instrumentation.md` is
 * the prose version — and it is fetched separately from the routes table because it answers a
 * different question ("how was this made", not "what does it say") and does not vary with the date
 * range a reader picks below.
 */
function CollectionPosture({ method }: { method: UsageCollectionMethod }) {
  /*
    `unaskedPolicy === "ATTRIBUTED"`, COMPARED DIRECTLY — NOT SNIFFED OUT OF A SENTENCE.

    It used to read `unaskedPolicy !== "NOTHING" && collects.some(line =>
    line.startsWith("The account id"))`, on the assumption that only the ATTRIBUTED sentence in
    `_collects()` (`backend/app/api/routes/usage.py`) started that way. It is not an assumption the
    backend keeps: BOTH of that function's account-id sentences share the exact prefix "The account
    id" — one for ATTRIBUTED, one for ANONYMOUS — because both policies record an account-id line and
    only the wording *after* the prefix says which. `.startsWith` cannot tell them apart, so this
    read `true` under ANONYMOUS too, which is `DEFAULT_UNASKED_COLLECTION`
    (`backend/app/services/usage.py`) and today's actual live default. The result: an admin opening
    this page under the live default — the one page whose stated purpose is telling them the truth
    about this exact fact — was told "requests ARE attributed to an account id, without having asked"
    while every row's `userId` is written NULL. `unaskedPolicy` is the policy's own enum value on the
    wire — "NOTHING" | "ANONYMOUS" | "ATTRIBUTED" — so comparing it directly cannot drift from
    whichever way `_collects()` happens to word its sentence.
  */
  const attributed = method.consent.unaskedPolicy === "ATTRIBUTED";
  return (
    <section className="panel border-amber-200 bg-amber-50 p-4">
      <div className="flex items-start gap-3">
        <CircleAlert className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-700" aria-hidden />
        <div className="min-w-0 flex-1 text-sm leading-6 text-amber-900">
          <p className="font-semibold">
            {method.consent.flowExists
              ? "Consent is asked, and the rows below reflect what each account answered."
              : "No consent flow exists yet. This deployment's policy for the unasked is " +
                `${method.consent.unaskedPolicy}${attributed ? " — requests ARE attributed to an account id, without having asked." : "."}`}
          </p>
          <p className="mt-1">{method.consent.explanation}</p>
          <p className="mt-1 text-amber-800">{method.consent.refusalCost}</p>
          <p className="mt-2 text-xs text-amber-700">
            Full method, caps and buffer losses:{" "}
            <span className="font-mono">{method.document}</span>. Decision record:{" "}
            <span className="font-mono">{method.consent.document}</span>.
          </p>
        </div>
      </div>
    </section>
  );
}

export default function UsagePage() {
  const { user, loading: authLoading } = useAuth();
  const permitted = isAdmin(user);

  const [method, setMethod] = useState<UsageCollectionMethod | null>(null);
  const [methodError, setMethodError] = useState<string | null>(null);

  const [from, setFrom] = useState(() => daysAgoIso(7));
  const [to, setTo] = useState(() => nowIso());
  const [page, setPage] = useState(1);

  const [routes, setRoutes] = useState<UsageRoutesPage | null>(null);
  const [routesError, setRoutesError] = useState<string | null>(null);

  // The method loads once — it does not vary with the date range below.
  useEffect(() => {
    if (authLoading || !permitted) return;
    let cancelled = false;
    loadUsageCollection()
      .then((result) => {
        if (!cancelled) setMethod(result);
      })
      .catch((err) => {
        if (!cancelled) setMethodError(err instanceof Error ? err.message : "Unable to load the collection method");
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, permitted]);

  // The routes table refetches on the window or the page changing. `from`/`to` reset `page` to 1
  // through the button handlers below, so this effect never has to guess which of the two moved.
  useEffect(() => {
    if (authLoading || !permitted) return;
    let cancelled = false;
    loadUsageRoutes({ from, to, page })
      .then((result) => {
        if (!cancelled) setRoutes(result);
      })
      .catch((err) => {
        if (!cancelled) setRoutesError(err instanceof Error ? err.message : "Unable to load usage by screen");
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, permitted, from, to, page]);

  const header = (
    <PageHeader
      title="Usage"
      description="Which screens are reached, how often, how fast, and how often broken — aggregated across every account, with nobody's name in the answer."
      icon={<Activity className="h-5 w-5" aria-hidden />}
    />
  );

  if (authLoading) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Checking access…</section>
      </>
    );
  }

  if (!permitted) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body="Usage aggregates navigation across every account on the platform, so it is available to admins and the master admin. It is never a record of one colleague's afternoon — see the note on this page for what it deliberately does not show."
        />
      </>
    );
  }

  return (
    <>
      {header}
      <div className="grid gap-4">
        {methodError ? (
          <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{methodError}</div>
        ) : method ? (
          <CollectionPosture method={method} />
        ) : (
          <section className="panel p-4 text-sm text-ink-500">Reading the collection method…</section>
        )}

        <section className="panel p-4">
          <div className="flex flex-wrap items-end gap-4">
            <label className="grid gap-1 text-sm">
              <span className="field-label">From</span>
              <input
                type="date"
                className="field-input"
                value={from.slice(0, 10)}
                max={to.slice(0, 10)}
                onChange={(event) => {
                  if (!event.target.value) return;
                  setFrom(new Date(`${event.target.value}T00:00:00.000Z`).toISOString());
                  setPage(1);
                }}
              />
            </label>
            <label className="grid gap-1 text-sm">
              <span className="field-label">To</span>
              <input
                type="date"
                className="field-input"
                value={to.slice(0, 10)}
                min={from.slice(0, 10)}
                onChange={(event) => {
                  if (!event.target.value) return;
                  // The END of the named day, so "To" is inclusive of the day a reader picks — the
                  // API's own window is half-open [from, to), and a bare midnight on the end date
                  // would silently drop everything that happened on it.
                  setTo(new Date(`${event.target.value}T23:59:59.999Z`).toISOString());
                  setPage(1);
                }}
              />
            </label>
            {routes ? (
              <p className="text-xs leading-5 text-ink-500">
                {routes.window.days} day{routes.window.days === 1 ? "" : "s"}, up to {routes.window.maxDays} allowed in
                one request. Dates with no time are read as UTC midnight.
              </p>
            ) : null}
          </div>
        </section>

        {routesError ? (
          <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{routesError}</div>
        ) : !routes ? (
          <section className="panel p-6 text-sm text-ink-500">Reading usage…</section>
        ) : (
          <>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Stat label="Requests on this page" value={routes.totalsForThisPage.requests.toLocaleString("en-IN")} />
              <Stat label="Succeeded" value={routes.totalsForThisPage.ok.toLocaleString("en-IN")} />
              <Stat
                label="Client / server errors"
                value={`${routes.totalsForThisPage.clientErrors.toLocaleString("en-IN")} / ${routes.totalsForThisPage.serverErrors.toLocaleString("en-IN")}`}
              />
              <Stat
                label="Screens withheld"
                value={String(routes.totalsForThisPage.routesWithheld)}
                hint={
                  routes.totalsForThisPage.routesWithheld > 0
                    ? `Fewer than ${routes.limits.minimumIdentifiedUsers} identified accounts used them in this window.`
                    : undefined
                }
              />
            </div>

            <section className="panel overflow-hidden">
              <div className="border-b border-line-200 px-4 py-3">
                <h2 className="font-display font-bold text-ink-900">By screen</h2>
                <p className="text-sm text-ink-500">
                  {routes.routeSource === "mounted"
                    ? "Every measured screen this deployment currently serves."
                    : "The screens you asked about."}{" "}
                  Sums above are for this page only — never a platform total; no arm of this API produces one.
                </p>
                {routes.notMeasured.length > 0 ? (
                  <p className="mt-1 text-xs text-ink-500">
                    Not measured, on any window: {routes.notMeasured.join(", ")}.
                  </p>
                ) : null}
              </div>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[760px] text-left text-sm">
                  <thead className="border-b border-line-200 text-xs uppercase tracking-wide text-ink-500">
                    <tr>
                      <th className="px-4 py-2 font-medium">Screen</th>
                      <th className="px-4 py-2 font-medium">Requests</th>
                      <th className="px-4 py-2 font-medium">Identified accounts</th>
                      <th className="px-4 py-2 font-medium">OK</th>
                      <th className="px-4 py-2 font-medium">Client errors</th>
                      <th className="px-4 py-2 font-medium">Server errors</th>
                      <th className="px-4 py-2 font-medium">Avg duration</th>
                      <th className="px-4 py-2 font-medium">Max duration</th>
                    </tr>
                  </thead>
                  <tbody>
                    {routes.items.length === 0 ? (
                      <tr>
                        <td className="px-4 py-6 text-sm text-ink-500" colSpan={8}>
                          No screens in this window.
                        </td>
                      </tr>
                    ) : (
                      routes.items.map((row) => (
                        <tr key={row.routeTemplate} className="border-b border-line-100 last:border-0">
                          <td className="px-4 py-2 font-mono text-xs text-ink-700">{row.routeTemplate}</td>
                          <td className="px-4 py-2">
                            <Metric value={row.requests} reason={row.withheldBecause} />
                          </td>
                          <td className="px-4 py-2">
                            <Metric value={row.identifiedUsers} reason={row.withheldBecause} />
                          </td>
                          <td className="px-4 py-2">
                            <Metric value={row.ok} reason={row.withheldBecause} />
                          </td>
                          <td className="px-4 py-2">
                            <Metric value={row.clientErrors} reason={row.withheldBecause} />
                          </td>
                          <td className="px-4 py-2">
                            <Metric value={row.serverErrors} reason={row.withheldBecause} />
                          </td>
                          <td className="px-4 py-2">
                            {isWithheldRoute(row) ? (
                              <Metric value={null} reason={row.withheldBecause} />
                            ) : (
                              durationText(row.avgDurationMs)
                            )}
                          </td>
                          <td className="px-4 py-2">
                            {isWithheldRoute(row) ? (
                              <Metric value={null} reason={row.withheldBecause} />
                            ) : (
                              durationText(row.maxDurationMs)
                            )}
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
              {routes.pages > 1 ? (
                <div className="flex items-center justify-between border-t border-line-200 px-4 py-3 text-sm">
                  <span className="text-ink-500">
                    Page {routes.page} of {routes.pages} — {routes.total} screen{routes.total === 1 ? "" : "s"} in total
                  </span>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      className="field-button-secondary"
                      disabled={routes.page <= 1}
                      onClick={() => setPage((current) => Math.max(1, current - 1))}
                    >
                      Previous
                    </button>
                    <button
                      type="button"
                      className="field-button-secondary"
                      disabled={routes.page >= routes.pages}
                      onClick={() => setPage((current) => Math.min(routes.pages, current + 1))}
                    >
                      Next
                    </button>
                  </div>
                </div>
              ) : null}
            </section>

            <ul className="list-disc space-y-1 pl-5 text-xs leading-5 text-ink-500">
              {routes.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          </>
        )}
      </div>
    </>
  );
}
