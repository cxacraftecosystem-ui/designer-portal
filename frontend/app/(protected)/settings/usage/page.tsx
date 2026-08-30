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
 * 1. **Nothing here computes.** Every figure comes off the wire — every count, every percentile,
 *    every rate, and every ranking. `isWithheldRoute` is the only branch a reader needs: a withheld
 *    row's numbers are `null`, and `null` becomes `0` through arithmetic and through `??`, so a page
 *    that fell back would publish a number the server explicitly refused to state. **The charts make
 *    that rule sharper rather than softer** — a table cell shows an em dash and a reader moves on,
 *    but a plotted point at zero is a confident claim, so every chart path draws a stated GAP where
 *    a figure is absent and never a mark on the baseline. The only arithmetic in this file's
 *    dependencies is axis geometry (`usageCharts.tsx`), which turns a figure into a coordinate and
 *    never into a different figure.
 * 2. **The consent and collection posture renders FIRST, above the figures**, because a number with
 *    no stated method is a number nobody can check. That posture CHANGED on 2026-08-30: there is now
 *    a consent flow, so the banner no longer says nobody has been asked. What it says instead is the
 *    two things that are still true and still qualify every figure below — a refusing account is not
 *    recorded at all, so these aggregates describe everyone who did not refuse; and every row
 *    written before the flow shipped carries `consentState` NULL, which means nobody was asked.
 * 3. **Two gates, both mirrored, neither invented here.** `require_usage_reader` (Admin and above) on
 *    the aggregate routes is the boundary; `ROUTE_GUARDS` mirrors it so the URL itself is refused for
 *    anyone below admin, not merely unlinked, and `permitted` below is the third copy for the case
 *    where this component is reached some other way. Admin view is mirrored by
 *    `ADMIN_CHROME_ROUTES`, so an admin browsing with admin view off never sees this card or this
 *    page — no request, no flash of figures about their colleagues' navigation.
 *
 * ── AND A FOURTH RULE THIS PAGE DID NOT NEED UNTIL NOW ─────────────────────────────────────────
 *
 * 4. **ONE NAMED PERSON'S TRAIL IS A DIFFERENT KIND OF THING FROM EVERYTHING ELSE ON THIS PAGE, AND
 *    IT IS GATED DIFFERENTLY.** Every other panel here is an aggregate that carries no user id at
 *    all. `AccountTrailPanel` at the bottom reads one colleague's request-by-request trail, and it
 *    is behind `isMasterAdmin` — a FOURTH gate on this page, mirroring the server's brand-new
 *    `deps.can_read_person_usage`, which is deliberately not a widened `can_read_usage`. It is
 *    additionally refused by the server unless that colleague's own consent is GRANTED, every read
 *    writes a line to the server log naming the reader and the subject, and there is no durable
 *    audit table — the panel says all three rather than implying otherwise. It sits last and behind
 *    its own heading because an admin who came here for "which screens are slow" should not
 *    encounter it on the way.
 */

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
import {
  bucketTickText,
  consentMoment,
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

function SectionError({ message }: { message: string }) {
  return <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{message}</div>;
}

function Loading({ what }: { what: string }) {
  return <section className="panel p-4 text-sm text-ink-500">Reading {what}…</section>;
}

/**
 * The consent and collection posture, rendered above every figure on the page.
 *
 * This is requirement 26's machine-readable half — `docs/METHODOLOGY-usage-instrumentation.md` is
 * the prose version — and it is fetched separately from the routes table because it answers a
 * different question ("how was this made", not "what does it say") and does not vary with the date
 * range a reader picks below.
 *
 * ── WHAT CHANGED ON 2026-08-30, AND THE TWO SENTENCES THAT DID NOT ─────────────────────────────
 *
 * There is a consent flow now: a required question at sign-in on both clients, a settings card that
 * withdraws, four columns on `User` and an append-only decision log. `consent.flowExists` says so,
 * and this banner's headline follows it rather than continuing to tell admins that nobody has been
 * asked. **What has NOT changed is that the banner is still a caution**, and these two are why:
 *
 *   * a REFUSING account is not recorded at all — not even anonymously — so every figure below
 *     describes everyone who did not refuse, and anybody quoting one has to say so;
 *   * every row written before the flow shipped carries `consentState` NULL, which means nobody was
 *     asked. Nothing backfills them, and whether they are deleted is an open decision.
 *
 * ── `unaskedPolicy === "ATTRIBUTED"`, COMPARED DIRECTLY — NOT SNIFFED OUT OF A SENTENCE ─────────
 *
 * It used to read `unaskedPolicy !== "NOTHING" && collects.some(line => line.startsWith("The account
 * id"))`, on the assumption that only the ATTRIBUTED sentence in `usage.collects()` started that
 * way. It is not an assumption the backend keeps: BOTH of that function's account-id sentences share
 * the exact prefix "The account id" — one for ATTRIBUTED, one for ANONYMOUS — because both policies
 * record an account-id line and only the wording *after* the prefix says which. `.startsWith` cannot
 * tell them apart, so this read `true` under ANONYMOUS too, which is `DEFAULT_UNASKED_COLLECTION`
 * and today's actual live default. The result: an admin opening this page under the live default —
 * the one page whose stated purpose is telling them the truth about this exact fact — was told
 * "requests ARE attributed to an account id, without having asked" while every row's `userId` is
 * written NULL. `unaskedPolicy` is the policy's own enum value on the wire, so comparing it directly
 * cannot drift from whichever way `collects()` happens to word its sentence. **The same rule now
 * governs `flowExists`:** it is the boolean, never a sentence, and never a version-string comparison
 * done here.
 */
function CollectionPosture({ method }: { method: UsageCollectionMethod }) {
  const attributed = method.consent.unaskedPolicy === "ATTRIBUTED";
  return (
    <section className="panel border-amber-200 bg-amber-50 p-4">
      <div className="flex items-start gap-3">
        <CircleAlert className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-700" aria-hidden />
        <div className="min-w-0 flex-1 text-sm leading-6 text-amber-900">
          <p className="font-semibold">
            {method.consent.flowExists
              ? "Consent is asked, and every figure below is drawn from accounts that did not refuse."
              : "No consent flow exists yet. This deployment's policy for the unasked is " +
                `${method.consent.unaskedPolicy}${attributed ? " — requests ARE attributed to an account id, without having asked." : "."}`}
          </p>

          {/* The server's own sentences, in the order it wrote them. Every one of these is a fact a
              reader needs BEFORE the numbers, not a footnote to a figure they already believe. */}
          {method.consent.flowExists && method.consent.askedAt ? (
            <p className="mt-1">{method.consent.askedAt}</p>
          ) : null}
          <p className="mt-1">{method.consent.explanation}</p>
          <p className="mt-1 text-amber-800">{method.consent.refusalCost}</p>
          <p className="mt-1 text-amber-800">{method.consent.consentStateWritten}</p>
          {method.consent.withdrawalCosts ? (
            <p className="mt-1 text-amber-800">{method.consent.withdrawalCosts}</p>
          ) : null}

          <p className="mt-2 text-xs text-amber-700">
            {method.consent.noticeVersion ? (
              <>
                Notice in force: <span className="font-mono">{method.consent.noticeVersion}</span>.{" "}
              </>
            ) : null}
            Full method, caps and buffer losses: <span className="font-mono">{method.document}</span>. Decision record:{" "}
            <span className="font-mono">{method.consent.document}</span>
            {method.consent.priorDocument ? (
              <>
                , which supersedes <span className="font-mono">{method.consent.priorDocument}</span>
              </>
            ) : null}
            .
          </p>
        </div>
      </div>
    </section>
  );
}

/**
 * ONE NAMED COLLEAGUE'S TRAIL. Master admin only, and the most sensitive read on this page.
 *
 * **THE GATE HERE IS `isMasterAdmin` AND NOT `isAdmin`**, mirroring `deps.can_read_person_usage`,
 * which the backend deliberately created as a NEW predicate rather than widening `can_read_usage`.
 * Its argument, in one line: the aggregates sit at Admin because a record of what colleagues did is
 * more revealing than the roster, and a named person's minute-by-minute trail is strictly more
 * revealing again than that aggregate — so it cannot sit at the same rank.
 *
 * **THE THREE REFUSALS ARE RENDERED AS SENTENCES AND NEVER AS AN EMPTY TABLE.** A 404 is "no such
 * account"; a 409 is "that person refused" or "nobody has asked them yet", and those two carry
 * different words because the next moves differ — there is no remedy at all for the first, and
 * telling an administrator to go and ask again is how somebody learns that a refusal is negotiable.
 * An empty list in place of any of them would be read as "this person has never used the app".
 *
 * **NO SEARCH BOX, AND THAT IS DELIBERATE.** The field takes an account id that the reader already
 * has. A picker over the account list would turn "read one colleague's trail, having decided to"
 * into "browse colleagues and pick one", which is a different act; there is no route that lists
 * accounts for this purpose and this panel does not invent one.
 */
function AccountTrailPanel({ from, to }: { from: string; to: string }) {
  const [subject, setSubject] = useState("");
  const [trail, setTrail] = useState<AccountUsageTrail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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
      // The server's own sentence, verbatim. It is the only text that knows which of the three
      // refusals this was, and nothing this bundle could write in its place would know it.
      setError(readableError(err, "That account's trail could not be read."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel p-4">
      <div className="flex items-start gap-2.5">
        <UserSearch className="mt-0.5 h-5 w-5 shrink-0 text-purple-700" aria-hidden />
        <div className="min-w-0">
          <h2 className="font-display font-bold text-ink-900">One account&apos;s trail</h2>
          <p className="mt-0.5 text-sm leading-6 text-ink-500">
            Request by request, for one named account, over the window chosen above. Readable by the master admin alone,
            and only where that person&apos;s own answer is <span className="font-medium text-ink-700">GRANTED</span> —
            an account that refused, or that nobody has asked, has no attributed rows at all.{" "}
            <span className="font-medium text-ink-700">
              Every read of this writes a line to the server log naming you, them and the window. There is no durable
              audit table; that is stated rather than implied.
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
            placeholder="The account id you already hold"
            autoComplete="off"
          />
        </label>
        <button type="submit" className="field-button" disabled={busy || !subject.trim()}>
          {busy ? "Reading…" : "Read the trail"}
        </button>
      </form>

      {error ? (
        <div className="mt-3">
          <SectionError message={error} />
        </div>
      ) : null}

      {trail ? (
        <div className="mt-3 grid gap-2">
          <p className="text-sm text-ink-700">
            <span className="font-mono text-xs">{trail.userId}</span> — their own answer is{" "}
            <span className="font-medium">{trail.subjectConsent.state}</span>
            {trail.subjectConsent.at ? <> since {consentMoment(trail.subjectConsent.at)}</> : null}.
          </p>
          {trail.events.length === 0 ? (
            <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
              No requests were attributed to this account in this window. A window that begins before they agreed is
              genuinely empty at the start, and that is not the same fact as &ldquo;nothing was done&rdquo;.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[620px] text-left text-sm">
                <thead className="border-b border-line-200 text-xs uppercase tracking-wide text-ink-500">
                  <tr>
                    <th className="py-2 pr-3 font-medium">When</th>
                    <th className="py-2 pr-3 font-medium">Screen</th>
                    <th className="py-2 pr-3 font-medium">Method</th>
                    <th className="py-2 pr-3 font-medium">Status</th>
                    <th className="py-2 pr-3 font-medium">Server took</th>
                    <th className="py-2 pr-3 font-medium">Client</th>
                    <th className="py-2 font-medium">Consent on the row</th>
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
                      {/* The answer THAT ROW was collected under — not the account's answer today. */}
                      <td className="py-2 text-ink-700">{event.consentState ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="text-xs leading-5 text-ink-500">
            Showing {trail.events.length} of at most {trail.maxRows} rows per page, newest first. There is no withholding
            floor here: the subject is named in the request, so there is no group for a floor to protect them inside.
          </p>
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
  // The FOURTH gate on this page — see rule 4 in the module comment. A separate predicate, not a
  // widened one, because the server's is a separate predicate for a written reason.
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

  // The method loads once — it does not vary with the date range below.
  useEffect(() => {
    if (authLoading || !permitted) return;
    let cancelled = false;
    loadUsageCollection()
      .then((result) => {
        if (!cancelled) setMethod(result);
      })
      .catch((err) => {
        if (!cancelled) setMethodError(readableError(err, "Unable to load the collection method"));
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
        if (!cancelled) setRoutesError(readableError(err, "Unable to load usage by screen"));
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, permitted, from, to, page]);

  /*
    THE FOUR NEW AGGREGATES, IN ONE EFFECT AND WITH FOUR SEPARATE ERROR SLOTS.

    One effect because they share exactly one trigger — the window (and, for the timeline, the bucket
    size). Four error slots because they can fail independently and for genuinely different reasons:
    a 366-day window at hourly buckets is refused by `/usage/timeline` alone, on a cap
    (`maxBuckets`) that does not exist on the other three. Collapsing them into one banner would
    report a refusal about buckets as though the latency figures had failed too, and would hide three
    working panels behind one message.

    A single `cancelled` flag guards all four: this is a one-shot effect per window, not a list page,
    so the `cancelled`-flag convention is the right one of the three and a generation counter would
    be ceremony. Errors are cleared at the top of each run so a recovered window does not keep
    showing the last window's refusal.
  */
  useEffect(() => {
    if (authLoading || !permitted) return;
    let cancelled = false;
    setTimelineError(null);
    setLatencyError(null);
    setClientsError(null);
    setScreensError(null);

    loadUsageTimeline({ from, to, bucket })
      .then((result) => !cancelled && setTimeline(result))
      .catch((err) => !cancelled && setTimelineError(readableError(err, "Unable to load traffic over time")));
    loadUsageLatency({ from, to })
      .then((result) => !cancelled && setLatency(result))
      .catch((err) => !cancelled && setLatencyError(readableError(err, "Unable to load latency percentiles")));
    loadUsageClients({ from, to })
      .then((result) => !cancelled && setClients(result))
      .catch((err) => !cancelled && setClientsError(readableError(err, "Unable to load the client split")));
    loadUsageScreens({ from, to, limit: 10 })
      .then((result) => !cancelled && setScreens(result))
      .catch((err) => !cancelled && setScreensError(readableError(err, "Unable to rank the screens")));

    return () => {
      cancelled = true;
    };
  }, [authLoading, permitted, from, to, bucket]);

  /**
   * The scope sentence every aggregate must carry.
   *
   * `notIncluded` is printed WHENEVER IT IS NON-ZERO and never rounded away. These routes answer
   * about a named, capped set of screens and there is deliberately no route that answers about the
   * platform — so a figure drawn without this sentence is a slice presented as the whole.
   */
  const scopeText = useCallback(
    (scope: { count: number; notIncluded: number; maxPerRequest: number; source: string }) =>
      `${scope.count} screen${scope.count === 1 ? "" : "s"} in this answer${
        scope.source === "mounted" ? " (the first the server mounts, in sorted order)" : " (the ones asked about)"
      }, at most ${scope.maxPerRequest} per request${
        scope.notIncluded > 0 ? `. ${scope.notIncluded} mounted screen${scope.notIncluded === 1 ? " is" : "s are"} outside it` : ""
      }.`,
    []
  );

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
          <SectionError message={methodError} />
        ) : method ? (
          <CollectionPosture method={method} />
        ) : (
          <Loading what="the collection method" />
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
            <label className="grid gap-1 text-sm">
              <span id="usage-bucket-label" className="field-label">
                Buckets
              </span>
              {/*
                THE THEMED DROPDOWN. This was a native `<select>` until 2026-08-30, kept that way on
                a reason that was half right: "the themed control cannot take the `id` this
                `<label htmlFor>` relationship needs".

                WHAT WAS RIGHT is that a `<label>` cannot name a `<button>` — HTML-AAM builds a
                button's name from its own contents and the label association plays no part. A bare
                swap here would have announced "One per day, combobox": the answer without the
                question. (The `<label>` above wraps its control rather than using `htmlFor`, so the
                association was implicit, but the conclusion is the same either way.)

                WHAT WAS WRONG is that there is no way to give the trigger a name. `FieldLabelProvider`
                publishes this `<span>`'s id, `SearchableSelect` reads it out of context and composes
                `aria-labelledby="usage-bucket-label <trigger id>"`, and the accname algorithm
                concatenates the two into "Buckets One per day" — the question AND the answer, which
                is what the native control announced and what a bare `ariaLabel` would have lost.
                `components/FormControls.Field` is the same mechanism; the `<label>` here is kept
                rather than replaced by it so this control stays visually identical to the two date
                fields beside it, which are not dropdowns and cannot use `Field` either.

                WRAPPING A `<button>` IN A `<label>` IS SAFE HERE, which is worth saying because it
                looks like a stray-click trap: `AnchoredPopover` portals the open panel to `<body>`,
                so an option row is not a DOM descendant of this label and a click on it cannot be
                forwarded anywhere. `Field` carries the same note.

                NO `searchable`: two options from a constant vocabulary, which is far below §11.5's
                threshold — this is the case that threshold was calibrated on.
              */}
              <FieldLabelProvider value="usage-bucket-label">
                <Dropdown
                  value={bucket}
                  onChange={(next) => setBucket(next === "hour" ? "hour" : "day")}
                  options={[
                    { value: "day", label: "One per day" },
                    { value: "hour", label: "One per hour" }
                  ]}
                  // This control re-queries the screen in place; there is nothing to advance to, and
                  // `focusNextField` would jump the reader into the pager below the chart.
                  advanceOnSelect={false}
                />
              </FieldLabelProvider>
            </label>
            {routes ? (
              <p className="text-xs leading-5 text-ink-500">
                {routes.window.days} day{routes.window.days === 1 ? "" : "s"}, up to {routes.window.maxDays} allowed in
                one request. Dates with no time are read as UTC midnight, and buckets are UTC calendar days or hours —
                not local ones.
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

            {/* ── Traffic and error rate: TWO CHARTS, NEVER ONE WITH TWO AXES ─────────────── */}
            {timelineError ? (
              <SectionError message={timelineError} />
            ) : !timeline ? (
              <Loading what="traffic over time" />
            ) : (
              <div className="grid gap-4 xl:grid-cols-2">
                <ChartFrame
                  title="Traffic over time"
                  description={`Requests per UTC ${timeline.bucket}, across the screens in scope. A bucket with no traffic is a measured zero and is plotted; a withheld bucket is a hatched gap and is not.`}
                  caps={
                    <>
                      {timeline.series.length} bucket{timeline.series.length === 1 ? "" : "s"} drawn, at most{" "}
                      {timeline.limits.maxBuckets ?? "—"} per request. {scopeText(timeline.scope)}
                    </>
                  }
                >
                  <UsageLineChart
                    unit="count"
                    ariaLabel={`Requests per ${timeline.bucket} over ${timeline.window.days} days, across ${timeline.scope.count} screens. The figures are in the table below this page's by-screen section.`}
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
                  description="The share of requests that answered 4xx or 5xx. Its own chart and its own axis — a second scale laid over the traffic line would put a crossing point on screen that is an artefact of where the axis was started."
                  caps={
                    <>
                      Null — drawn as a gap — wherever there were no requests at all: 0 of 0 is
                      &ldquo;nothing happened&rdquo;, not &ldquo;nothing went wrong&rdquo;.{" "}
                      {scopeText(timeline.scope)}
                    </>
                  }
                >
                  <UsageLineChart
                    unit="percent"
                    tone="error"
                    ariaLabel={`Share of requests answering 4xx or 5xx, per ${timeline.bucket}, over ${timeline.window.days} days.`}
                    points={timeline.series.map<LinePoint>((row) => ({
                      label: bucketTickText(row.bucket, timeline.bucket),
                      title: `${row.bucket} — ${errorRateText(row.errorRate)}`,
                      // Read straight off the wire. `(rate ?? 0)` here would draw a confident 0%
                      // for an empty hour and for a withheld one alike.
                      value: row.errorRate,
                      withheld: isWithheldRoute(row),
                      withheldBecause: row.withheldBecause
                    }))}
                  />
                </ChartFrame>
              </div>
            )}

            {/* ── Latency percentiles ─────────────────────────────────────────────────────── */}
            {latencyError ? (
              <SectionError message={latencyError} />
            ) : !latency ? (
              <Loading what="latency percentiles" />
            ) : (
              <ChartFrame
                title="How long the server took, per screen"
                description={`Median and tail: ${latency.percentiles.join(", ")}. These cannot be derived from the averages in the table below — an average cannot see a tail, and a screen whose mean is 120 ms and whose p99 is four seconds is broken for one request in a hundred.`}
                legend={
                  <ChartLegend
                    items={[
                      { label: "p50 — the middle request", className: ORDINAL_BG[0] },
                      { label: "p95", className: ORDINAL_BG[1] },
                      { label: "p99 — the tail", className: ORDINAL_BG[2] }
                    ]}
                  />
                }
                caps={
                  <>
                    {scopeText(latency.scope)} Server time only — from this API receiving the request to it finishing
                    the answer. It is not what anybody waited for.
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
                  ariaLabel={`Server duration percentiles for ${latency.routes.length} screens, p50 to p99 on one shared axis.`}
                  rows={latency.routes.map<PercentileRow>((row) => ({
                    label: row.routeTemplate,
                    p50: row.p50Ms,
                    p95: row.p95Ms,
                    p99: row.p99Ms,
                    withheld: isWithheldRoute(row),
                    withheldBecause: row.withheldBecause,
                    // The THIRD state: a screen with traffic of zero has no distribution, which is
                    // not the same fact as one the server declined to show.
                    noTraffic: !isWithheldRoute(row) && (row.requests ?? 0) === 0
                  }))}
                />
              </ChartFrame>
            )}

            {/* ── Client split ────────────────────────────────────────────────────────────── */}
            {clientsError ? (
              <SectionError message={clientsError} />
            ) : !clients ? (
              <Loading what="the client split" />
            ) : (
              <ChartFrame
                title="Web and Android"
                description={`Which client each request said it was, from the ${clients.header} header.`}
                caps={
                  <>
                    Known clients: {clients.known.join(", ")}. Anything that did not send the header is filed under{" "}
                    <span className="font-mono">{clients.fallback}</span>. {scopeText(clients.scope)}
                  </>
                }
                table={
                  <>
                    <div className="mt-3 overflow-x-auto">
                      <table className="w-full min-w-[520px] text-left text-sm">
                        <thead className="border-b border-line-200 text-xs uppercase tracking-wide text-ink-500">
                          <tr>
                            <th className="py-2 pr-3 font-medium">Client</th>
                            <th className="py-2 pr-3 font-medium">Requests</th>
                            <th className="py-2 pr-3 font-medium">Error rate</th>
                            <th className="py-2 pr-3 font-medium">Identified accounts</th>
                            <th className="py-2 font-medium">Avg duration</th>
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
                                className={`py-2 pr-3 ${row.errorRate === null ? "text-ink-300" : "text-ink-900"}`}
                                title={row.errorRate === null ? row.withheldBecause ?? "No requests, so no rate." : undefined}
                              >
                                {errorRateText(row.errorRate)}
                              </td>
                              <td className="py-2 pr-3">
                                <Metric value={row.identifiedUsers} reason={row.withheldBecause} />
                              </td>
                              <td className={`py-2 ${row.avgDurationMs === null ? "text-ink-300" : "text-ink-900"}`}>
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
                  ariaLabel="Share of requests by client. The same figures are in the table underneath."
                  segments={clients.clients.map((row) => ({
                    label: row.clientApp,
                    value: row.requests,
                    withheld: isWithheldRoute(row),
                    withheldBecause: row.withheldBecause
                  }))}
                />
              </ChartFrame>
            )}

            {/* ── Busiest and slowest ─────────────────────────────────────────────────────── */}
            {screensError ? (
              <SectionError message={screensError} />
            ) : !screens ? (
              <Loading what="the busiest and slowest screens" />
            ) : (
              <div className="grid gap-4 xl:grid-cols-2">
                <ChartFrame
                  title="Busiest screens"
                  description="Ranked on the server, not here — a withheld row carries null in every metric, and null sorts as zero through a JavaScript comparator."
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
                  description="Ranked on the MEAN server duration, which cannot see a tail. The percentile chart above is the honest answer to “which screens are slow”."
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
                    ariaLabel={`The ${screens.slowest.length} slowest screens by mean server duration.`}
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
                        // `border-line-200`, NOT `border-line-100`: there is no `line-100` rung in
                        // `tailwind.config.ts` (`line: { 200: … }` is the whole scale), so the class
                        // was purged and these rows fell back to `border-b`'s preflight default —
                        // literal gray-200, which does not invert, so every row rule on this table
                        // stayed light-grey in dark mode. §3.6 names exactly this trap. Every table
                        // added to this page alongside the charts uses `line-200`; this one now
                        // agrees with them.
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

            {/* LAST, AND BEHIND A GATE OF ITS OWN — see rule 4 in the module comment. */}
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
