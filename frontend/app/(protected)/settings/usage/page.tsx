"use client";

import { useCallback, useEffect, useState } from "react";
import { Activity, CircleAlert, UserSearch } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { Dropdown } from "@/components/ui/Dropdown";
import { FieldLabelProvider } from "@/components/ui/fieldLabel";
import { useAuth } from "@/components/AuthProvider";
import { readableError } from "@/components/review/reviewErrors";
import {
  ChartFrame,
  ChartLegend,
  ORDINAL_BG,
  UsageBarRows,
  UsageLineChart,
  UsagePercentileRows,
  UsageStackedBar,
  type BarRow,
  type LinePoint,
  type PercentileRow
} from "@/components/settings/usageCharts";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";
import { CalendarField } from "@/components/ui/CalendarField";
import {
  bucketTickText,
  consentMoment,
  consentStateText,
  daysAgoIso,
  durationText,
  errorRateText,
  isWithheldRoute,
  loadAccountUsageTrail,
  loadUsageClients,
  loadUsageCollection,
  loadUsageLatency,
  loadUsageRoutes,
  loadUsageScreens,
  loadUsageTimeline,
  nowIso,
  type AccountUsageTrail,
  type UsageClients,
  type UsageCollectionMethod,
  type UsageLatency,
  type UsageRoutesPage,
  type UsageScreens,
  type UsageTimeline
} from "@/lib/usage";
import { ActionGroup } from "@/components/ui/ActionGroup";

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-md border border-line-200 bg-card p-4">
      <div className="field-label">{label}</div>
      <div className="mt-1 font-display text-2xl font-bold text-ink-900">{value}</div>
      {hint ? <p className="mt-1 text-xs leading-5 text-ink-500">{hint}</p> : null}
    </div>
  );
}

function Metric({ value, reason }: { value: number | null; reason?: string }) {
  const withheld = value === null;
  return (
    <span className={withheld ? "text-ink-500" : "text-ink-900"} title={withheld ? reason : undefined}>
      {withheld ? "—" : value.toLocaleString("en-IN")}
    </span>
  );
}

function SectionError({ message }: { message: string }) {
  return <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{message}</div>;
}

function Loading({ what }: { what: string }) {
  return <section className="panel p-4 text-sm text-ink-500">Loading {what}…</section>;
}

function CollectionPosture({ method }: { method: UsageCollectionMethod }) {
  const attributed = method.consent.unaskedPolicy === "ATTRIBUTED";
  return (
    <section className="panel border-amber-200 bg-amber-50 p-4">
      <div className="flex items-start gap-3">
        <CircleAlert className="mt-0.5 h-5 w-5 shrink-0 text-amber-700" aria-hidden />
        <div className="min-w-0 flex-1 text-sm leading-6 text-amber-900">
          <p className="font-semibold">
            {method.consent.flowExists
              ? "People who declined are left out."
              : `Requests from accounts that haven't answered are recorded ${attributed ? "with their name" : "without a name"}.`}
          </p>

          {method.consent.flowExists && method.consent.askedAt ? (
            <p className="mt-1">{method.consent.askedAt}</p>
          ) : null}
          <p className="mt-1">{method.consent.explanation}</p>
          <p className="mt-1 text-amber-800 dark:text-amber-100">{method.consent.refusalCost}</p>
          <p className="mt-1 text-amber-800 dark:text-amber-100">{method.consent.consentStateWritten}</p>
          {method.consent.withdrawalCosts ? (
            <p className="mt-1 text-amber-800 dark:text-amber-100">{method.consent.withdrawalCosts}</p>
          ) : null}

          {method.consent.noticeVersion ? (
            <p className="mt-2 text-xs text-amber-700">Notice version in force: {method.consent.noticeVersion}.</p>
          ) : null}
        </div>
      </div>
    </section>
  );
}

function AccountTrailPanel({ from, to }: { from: string; to: string }) {
  const [subject, setSubject] = useState("");
  const [trail, setTrail] = useState<AccountUsageTrail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [older, setOlder] = useState(false);

  async function loadOlder() {
    if (!trail) return;
    setOlder(true);
    try {
      const next = await loadAccountUsageTrail(trail.userId, { from, to, offset: trail.events.length });
      setTrail((current) => (current ? { ...current, limit: next.limit, events: [...current.events, ...next.events] } : current));
    } catch (err) {
      setError(readableError(err, "Older activity couldn't be loaded. Try again."));
    } finally {
      setOlder(false);
    }
  }

  async function load(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const id = subject.trim();
    if (!id) return;
    setBusy(true);
    setError(null);
    setTrail(null);
    try {
      setTrail(await loadAccountUsageTrail(id, { from, to }));
    } catch (err) {
      setError(readableError(err, "That person's activity couldn't be loaded. Try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel min-w-0 p-4">
      <div className="flex items-start gap-2.5">
        <UserSearch className="mt-0.5 h-5 w-5 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
        <div className="min-w-0">
          <h2 className="font-display font-bold text-ink-900">One person&apos;s activity</h2>
          <p className="mt-0.5 text-sm leading-6 text-ink-500">
            One person&apos;s steps, if they agreed.{" "}
            <span className="font-medium text-ink-700">
              Each view is logged.
            </span>
          </p>
        </div>
      </div>

      <form onSubmit={load} className="mt-3 flex flex-wrap items-end gap-3">
        <label className="grid min-w-0 flex-1 gap-1 text-sm">
          <span className="field-label">Account id</span>
          <input
            className="field-input"
            value={subject}
            onChange={(event) => setSubject(event.target.value)}
            placeholder="Paste the person's account id"
            autoComplete="off"
          />
        </label>
        <button type="submit" className="field-button" disabled={busy || !subject.trim()}>
          {busy ? "Loading…" : "Show activity"}
        </button>
      </form>

      {error ? (
        <div className="mt-3">
          <SectionError message={error} />
        </div>
      ) : null}

      {trail ? (
        <div className="mt-3 grid min-w-0 grid-cols-[minmax(0,1fr)] gap-2">
          <p className="text-sm text-ink-700 [overflow-wrap:anywhere]">
            <span className="font-mono text-xs">{trail.userId}</span> — their answer:{" "}
            <span className="font-medium">{consentStateText(trail.subjectConsent.state)}</span>
            {trail.subjectConsent.at ? <> since {consentMoment(trail.subjectConsent.at)}</> : null}.
          </p>
          {trail.events.length === 0 ? (
            <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
              Nothing recorded in this period.
            </p>
          ) : (
            <div className="overflow-x-auto focus-visible:outline-2 focus-visible:outline-purple-700" tabIndex={0} role="region" aria-label="Usage table">
              <table className="w-full min-w-[620px] text-left text-sm">
                <thead className="border-b border-line-200 text-xs uppercase tracking-wide text-ink-500">
                  <tr>
                    <th className="py-2 pr-3 font-medium">When</th>
                    <th className="py-2 pr-3 font-medium">Screen</th>
                    <th className="py-2 pr-3 font-medium">Method</th>
                    <th className="py-2 pr-3 font-medium">Status</th>
                    <th className="py-2 pr-3 font-medium">Took</th>
                    <th className="py-2 pr-3 font-medium">App</th>
                    <th className="py-2 font-medium">Answer at the time</th>
                  </tr>
                </thead>
                <tbody>
                  {trail.events.map((event) => (
                    <tr key={event.id} className="border-b border-line-200 last:border-0">
                      <td className="py-2 pr-3 text-ink-700">{consentMoment(event.at)}</td>
                      <td className="py-2 pr-3 font-mono text-xs text-ink-700">{event.routeTemplate}</td>
                      <td className="py-2 pr-3 text-ink-700">{event.method}</td>
                      <td className="py-2 pr-3 text-ink-700">{event.statusCode}</td>
                      <td className="py-2 pr-3 text-ink-700">{durationText(event.durationMs)}</td>
                      <td className="py-2 pr-3 text-ink-700">{event.clientApp}</td>
                      <td className="py-2 text-ink-700">{event.consentState ? consentStateText(event.consentState) : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="text-xs leading-5 text-ink-500">Showing {trail.events.length}, newest first.</p>
          {trail.events.length > 0 && trail.events.length % trail.limit === 0 ? (
            <ActionGroup>
              <button type="button" className="field-button-secondary min-h-10" data-testid="usage-trail-older" disabled={older} onClick={() => void loadOlder()}>
                {older ? "Loading…" : "Show older"}
              </button>
            </ActionGroup>
          ) : null}
          <ul className="list-disc space-y-1 pl-5 text-xs leading-5 text-ink-500">
            {trail.notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}

export default function UsagePage() {
  const { user, loading: authLoading } = useAuth();
  const permitted = isAdmin(user);
  const canReadOneAccount = isMasterAdmin(user);

  const [method, setMethod] = useState<UsageCollectionMethod | null>(null);
  const [methodError, setMethodError] = useState<string | null>(null);

  const [from, setFrom] = useState(() => daysAgoIso(7));
  const [to, setTo] = useState(() => nowIso());
  const [bucket, setBucket] = useState<"day" | "hour">("day");
  const [page, setPage] = useState(1);

  const [routes, setRoutes] = useState<UsageRoutesPage | null>(null);
  const [routesError, setRoutesError] = useState<string | null>(null);

  const [timeline, setTimeline] = useState<UsageTimeline | null>(null);
  const [timelineError, setTimelineError] = useState<string | null>(null);
  const [latency, setLatency] = useState<UsageLatency | null>(null);
  const [latencyError, setLatencyError] = useState<string | null>(null);
  const [clients, setClients] = useState<UsageClients | null>(null);
  const [clientsError, setClientsError] = useState<string | null>(null);
  const [screens, setScreens] = useState<UsageScreens | null>(null);
  const [screensError, setScreensError] = useState<string | null>(null);

  useEffect(() => {
    if (authLoading || !permitted) return;
    let cancelled = false;
    loadUsageCollection()
      .then((result) => {
        if (!cancelled) setMethod(result);
      })
      .catch((err) => {
        if (!cancelled) setMethodError(readableError(err, "Couldn't load how usage is recorded. Try again."));
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, permitted]);

  useEffect(() => {
    if (authLoading || !permitted) return;
    let cancelled = false;
    loadUsageRoutes({ from, to, page })
      .then((result) => {
        if (!cancelled) setRoutes(result);
      })
      .catch((err) => {
        if (!cancelled) setRoutesError(readableError(err, "Couldn't load usage by screen. Try again."));
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, permitted, from, to, page]);

  useEffect(() => {
    if (authLoading || !permitted) return;
    let cancelled = false;
    setTimelineError(null);
    setLatencyError(null);
    setClientsError(null);
    setScreensError(null);

    loadUsageTimeline({ from, to, bucket })
      .then((result) => !cancelled && setTimeline(result))
      .catch((err) => !cancelled && setTimelineError(readableError(err, "Couldn't load traffic over time. Try again.")));
    loadUsageLatency({ from, to })
      .then((result) => !cancelled && setLatency(result))
      .catch((err) => !cancelled && setLatencyError(readableError(err, "Couldn't load response times. Try again.")));
    loadUsageClients({ from, to })
      .then((result) => !cancelled && setClients(result))
      .catch((err) => !cancelled && setClientsError(readableError(err, "Couldn't load the split by app. Try again.")));
    loadUsageScreens({ from, to, limit: 10 })
      .then((result) => !cancelled && setScreens(result))
      .catch((err) => !cancelled && setScreensError(readableError(err, "Couldn't load screens. Try again.")));

    return () => {
      cancelled = true;
    };
  }, [authLoading, permitted, from, to, bucket]);

  const scopeText = useCallback(
    (scope: { count: number; notIncluded: number; maxPerRequest: number; source: string }) =>
      `Showing ${scope.count} screen${scope.count === 1 ? "" : "s"}${
        scope.source === "mounted" ? ", the first in alphabetical order" : ", the ones asked for"
      }, at most ${scope.maxPerRequest} at a time${
        scope.notIncluded > 0 ? `. ${scope.notIncluded} more screen${scope.notIncluded === 1 ? " is" : "s are"} not included` : ""
      }.`,
    []
  );

  const header = (
    <PageHeader
      title="Usage"
      description="Screen usage, speed and errors. Anonymous totals."
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
          body="Admins only."
        />
      </>
    );
  }

  return (
    <>
      {header}
      <div className="grid min-w-0 grid-cols-[minmax(0,1fr)] gap-4">
        {methodError ? (
          <SectionError message={methodError} />
        ) : method ? (
          <CollectionPosture method={method} />
        ) : (
          <Loading what="how usage is recorded" />
        )}

        <section className="panel p-4">
          <div className="flex flex-wrap items-end gap-4">
            <CalendarField
              label="From"
              className="w-44"
              value={from.slice(0, 10)}
              max={to.slice(0, 10)}
              maxLabel="To"
              clearable={false}
              onChange={(value) => {
                if (!value) return;
                setFrom(new Date(`${value}T00:00:00.000Z`).toISOString());
                setPage(1);
              }}
            />
            <CalendarField
              label="To"
              className="w-44"
              value={to.slice(0, 10)}
              min={from.slice(0, 10)}
              minLabel="From"
              clearable={false}
              onChange={(value) => {
                if (!value) return;
                setTo(new Date(`${value}T23:59:59.999Z`).toISOString());
                setPage(1);
              }}
            />
            <label className="grid gap-1 text-sm">
              <span id="usage-bucket-label" className="field-label">
                Chart detail
              </span>
              <FieldLabelProvider value="usage-bucket-label">
                <Dropdown
                  value={bucket}
                  onChange={(next) => setBucket(next === "hour" ? "hour" : "day")}
                  options={[
                    { value: "day", label: "One per day" },
                    { value: "hour", label: "One per hour" }
                  ]}
                  advanceOnSelect={false}
                />
              </FieldLabelProvider>
            </label>
            {routes ? (
              <p className="text-xs leading-5 text-ink-500">
                {routes.window.days} day{routes.window.days === 1 ? "" : "s"} chosen, up to {routes.window.maxDays} at a
                time. Times in UTC.
              </p>
            ) : null}
          </div>
        </section>

        {routesError ? (
          <SectionError message={routesError} />
        ) : !routes ? (
          <Loading what="usage" />
        ) : (
          <>
            <div className="grid grid-cols-[minmax(0,1fr)] gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Stat label="Requests on this page" value={routes.totalsForThisPage.requests.toLocaleString("en-IN")} />
              <Stat label="Succeeded" value={routes.totalsForThisPage.ok.toLocaleString("en-IN")} />
              <Stat
                label="Refused / failed"
                value={`${routes.totalsForThisPage.clientErrors.toLocaleString("en-IN")} / ${routes.totalsForThisPage.serverErrors.toLocaleString("en-IN")}`}
              />
              <Stat
                label="Screens hidden"
                value={String(routes.totalsForThisPage.routesWithheld)}
                hint={
                  routes.totalsForThisPage.routesWithheld > 0
                    ? `Fewer than ${routes.limits.minimumIdentifiedUsers} people used them in this period.`
                    : undefined
                }
              />
            </div>

            {timelineError ? (
              <SectionError message={timelineError} />
            ) : !timeline ? (
              <Loading what="traffic over time" />
            ) : (
              <div className="grid grid-cols-[minmax(0,1fr)] gap-4 xl:grid-cols-2">
                <ChartFrame
                  title="Traffic over time"
                  description={`Requests per ${timeline.bucket} (UTC). Hatched gaps are hidden periods.`}
                  caps={
                    <>
                      {timeline.series.length} point{timeline.series.length === 1 ? "" : "s"} drawn, at most{" "}
                      {timeline.limits.maxBuckets ?? "—"} per chart. {scopeText(timeline.scope)}
                    </>
                  }
                >
                  <UsageLineChart
                    unit="count"
                    ariaLabel={`Requests per ${timeline.bucket} over ${timeline.window.days} days, across ${timeline.scope.count} screens.`}
                    points={timeline.series.map<LinePoint>((row) => ({
                      label: bucketTickText(row.bucket, timeline.bucket),
                      title: row.bucket,
                      value: row.requests,
                      withheld: isWithheldRoute(row),
                      withheldBecause: row.withheldBecause
                    }))}
                  />
                </ChartFrame>

                <ChartFrame
                  title="Error rate over time"
                  description="Share of requests that failed."
                  caps={
                    <>
                      Gaps mean no requests.{" "}
                      {scopeText(timeline.scope)}
                    </>
                  }
                >
                  <UsageLineChart
                    unit="percent"
                    tone="error"
                    ariaLabel={`Share of requests that were refused or failed, per ${timeline.bucket}, over ${timeline.window.days} days.`}
                    points={timeline.series.map<LinePoint>((row) => ({
                      label: bucketTickText(row.bucket, timeline.bucket),
                      title: `${row.bucket} — ${errorRateText(row.errorRate)}`,
                      value: row.errorRate,
                      withheld: isWithheldRoute(row),
                      withheldBecause: row.withheldBecause
                    }))}
                  />
                </ChartFrame>
              </div>
            )}

            {latencyError ? (
              <SectionError message={latencyError} />
            ) : !latency ? (
              <Loading what="response times" />
            ) : (
              <ChartFrame
                title="How long each screen took"
                description={`Percentiles: ${latency.percentiles.join(", ")}.`}
                legend={
                  <ChartLegend
                    items={[
                      { label: "p50 — typical", className: ORDINAL_BG[0] },
                      { label: "p95", className: ORDINAL_BG[1] },
                      { label: "p99 — slowest", className: ORDINAL_BG[2] }
                    ]}
                  />
                }
                caps={
                  <>
                    {scopeText(latency.scope)} Server time only.
                  </>
                }
                table={
                  <ul className="mt-3 list-disc space-y-1 pl-5 text-xs leading-5 text-ink-500">
                    {latency.notes.map((note) => (
                      <li key={note}>{note}</li>
                    ))}
                  </ul>
                }
              >
                <UsagePercentileRows
                  ariaLabel={`Response times for ${latency.routes.length} screens, p50 to p99 on one shared scale.`}
                  rows={latency.routes.map<PercentileRow>((row) => ({
                    label: row.routeTemplate,
                    p50: row.p50Ms,
                    p95: row.p95Ms,
                    p99: row.p99Ms,
                    withheld: isWithheldRoute(row),
                    withheldBecause: row.withheldBecause,
                    noTraffic: !isWithheldRoute(row) && (row.requests ?? 0) === 0
                  }))}
                />
              </ChartFrame>
            )}

            {clientsError ? (
              <SectionError message={clientsError} />
            ) : !clients ? (
              <Loading what="the split by app" />
            ) : (
              <ChartFrame
                title="Web and Android"
                description="Which app each request came from."
                caps={
                  <>
                    Unknown apps count as &ldquo;{clients.fallback}&rdquo;.{" "}
                    {scopeText(clients.scope)}
                  </>
                }
                table={
                  <>
                    <div className="mt-3 overflow-x-auto focus-visible:outline-2 focus-visible:outline-purple-700" tabIndex={0} role="region" aria-label="Usage table">
                      <table className="w-full min-w-[520px] text-left text-sm">
                        <thead className="border-b border-line-200 text-xs uppercase tracking-wide text-ink-500">
                          <tr>
                            <th className="py-2 pr-3 font-medium">App</th>
                            <th className="py-2 pr-3 font-medium">Requests</th>
                            <th className="py-2 pr-3 font-medium">Error rate</th>
                            <th className="py-2 pr-3 font-medium">Identified accounts</th>
                            <th className="py-2 font-medium">Average time</th>
                          </tr>
                        </thead>
                        <tbody>
                          {clients.clients.map((row) => (
                            <tr key={row.clientApp} className="border-b border-line-200 last:border-0">
                              <td className="py-2 pr-3 font-mono text-xs text-ink-700">{row.clientApp}</td>
                              <td className="py-2 pr-3">
                                <Metric value={row.requests} reason={row.withheldBecause} />
                              </td>
                              <td
                                className={`py-2 pr-3 ${row.errorRate === null ? "text-ink-500" : "text-ink-900"}`}
                                title={row.errorRate === null ? row.withheldBecause ?? "No requests, so no rate." : undefined}
                              >
                                {errorRateText(row.errorRate)}
                              </td>
                              <td className="py-2 pr-3">
                                <Metric value={row.identifiedUsers} reason={row.withheldBecause} />
                              </td>
                              <td className={`py-2 ${row.avgDurationMs === null ? "text-ink-500" : "text-ink-900"}`}>
                                {isWithheldRoute(row) || row.avgDurationMs === null ? "—" : durationText(row.avgDurationMs)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <ul className="mt-2 list-disc space-y-1 pl-5 text-xs leading-5 text-ink-500">
                      {clients.notes.map((note) => (
                        <li key={note}>{note}</li>
                      ))}
                    </ul>
                  </>
                }
              >
                <UsageStackedBar
                  ariaLabel="Share of requests by app. The same figures are in the table underneath."
                  segments={clients.clients.map((row) => ({
                    label: row.clientApp,
                    value: row.requests,
                    withheld: isWithheldRoute(row),
                    withheldBecause: row.withheldBecause
                  }))}
                />
              </ChartFrame>
            )}

            {screensError ? (
              <SectionError message={screensError} />
            ) : !screens ? (
              <Loading what="the busiest and slowest screens" />
            ) : (
              <div className="grid grid-cols-[minmax(0,1fr)] gap-4 xl:grid-cols-2">
                <ChartFrame
                  title="Busiest screens"
                  description="The screens with the most requests."
                  caps={
                    <>
                      Top {screens.limit}. {screens.withheld.routes > 0 ? screens.withheld.explanation : null}{" "}
                      {scopeText(screens.scope)}
                    </>
                  }
                >
                  <UsageBarRows
                    unit="count"
                    ariaLabel={`The ${screens.busiest.length} busiest screens by request count.`}
                    rows={screens.busiest.map<BarRow>((row) => ({
                      label: row.routeTemplate,
                      value: row.requests,
                      withheld: isWithheldRoute(row),
                      withheldBecause: row.withheldBecause,
                      title: `${row.routeTemplate} — ${row.requests?.toLocaleString("en-IN") ?? "—"} requests`
                    }))}
                  />
                </ChartFrame>

                <ChartFrame
                  title="Slowest screens"
                  description="By average time."
                  caps={
                    <>
                      Top {screens.limit}. {screens.withheld.routes > 0 ? screens.withheld.explanation : null}{" "}
                      {scopeText(screens.scope)}
                    </>
                  }
                  table={
                    <ul className="mt-3 list-disc space-y-1 pl-5 text-xs leading-5 text-ink-500">
                      {screens.notes.map((note) => (
                        <li key={note}>{note}</li>
                      ))}
                    </ul>
                  }
                >
                  <UsageBarRows
                    unit="ms"
                    ariaLabel={`The ${screens.slowest.length} slowest screens by average time.`}
                    rows={screens.slowest.map<BarRow>((row) => ({
                      label: row.routeTemplate,
                      value: row.avgDurationMs,
                      withheld: isWithheldRoute(row),
                      withheldBecause: row.withheldBecause,
                      title: `${row.routeTemplate} — ${durationText(row.avgDurationMs)} on average`
                    }))}
                  />
                </ChartFrame>
              </div>
            )}

            <section className="panel overflow-hidden">
              <div className="border-b border-line-200 px-4 py-3">
                <h2 className="font-display font-bold text-ink-900">By screen</h2>
                <p className="text-sm text-ink-500">
                  {routes.routeSource === "mounted"
                    ? "Every screen that is measured."
                    : "The screens you asked about."}{" "}
                  The totals above cover this page only.
                </p>
                {routes.notMeasured.length > 0 ? (
                  <p className="mt-1 text-xs text-ink-500">
                    Never measured: {routes.notMeasured.join(", ")}.
                  </p>
                ) : null}
              </div>
              <div className="overflow-x-auto focus-visible:outline-2 focus-visible:outline-purple-700" tabIndex={0} role="region" aria-label="Usage table">
                <table className="w-full min-w-[760px] text-left text-sm">
                  <thead className="border-b border-line-200 text-xs uppercase tracking-wide text-ink-500">
                    <tr>
                      <th className="px-4 py-2 font-medium">Screen</th>
                      <th className="px-4 py-2 font-medium">Requests</th>
                      <th className="px-4 py-2 font-medium">Identified accounts</th>
                      <th className="px-4 py-2 font-medium">OK</th>
                      <th className="px-4 py-2 font-medium">Refused</th>
                      <th className="px-4 py-2 font-medium">Failed</th>
                      <th className="px-4 py-2 font-medium">Average time</th>
                      <th className="px-4 py-2 font-medium">Longest time</th>
                    </tr>
                  </thead>
                  <tbody>
                    {routes.items.length === 0 ? (
                      <tr>
                        <td className="px-4 py-6 text-sm text-ink-500" colSpan={8}>
                          No screens in this period.
                        </td>
                      </tr>
                    ) : (
                      routes.items.map((row) => (
                        <tr key={row.routeTemplate} className="border-b border-line-200 last:border-0">
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
                  <ActionGroup>
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
                  </ActionGroup>
                </div>
              ) : null}
            </section>

            {canReadOneAccount ? <AccountTrailPanel from={from} to={to} /> : null}

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
