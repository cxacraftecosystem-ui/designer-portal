"use client";

/**
 * /admin/analytics — what the whole archive says, as opposed to what two hundred reports each say
 * once.
 *
 * THIS PAGE IS MOSTLY A REPORT ON WHAT CANNOT YET BE SAID, and that is the design rather than a
 * placeholder. Stage 22 follow-up happens months after a workshop closes and is not required for a
 * workshop to be complete, so on today's archive one workshop has recorded any follow-up at all.
 * Every adoption rate is therefore withheld by the server, and the screen's job is to make that
 * legible — the counts are shown, the rate is an em dash, and the sentence beside it says which
 * floor was not met. A page that filled those cells with percentages computed from one workshop
 * would be the failure mode this whole feature exists to prevent.
 *
 * THREE RULES THIS COMPONENT FOLLOWS AND MUST KEEP FOLLOWING.
 *
 * 1. **Nothing here computes.** Every figure and every sentence comes off the wire. The temptation
 *    is `adopted / observations` for a cell the server left null — which is precisely how a
 *    withheld figure becomes a published one. `adoptionRateText` is the only renderer of a rate.
 * 2. **The cautions render above the figures**, in the order the server sent them, because the
 *    server put them first for that reason. A caution below the number it qualifies is a footnote
 *    to something the reader already believes.
 * 3. **Two gates, both mirrored, neither invented here.** `require_admin` on
 *    GET /api/analytics/design-workshops is the boundary; ROUTE_GUARDS mirrors it (so the URL is
 *    refused, not merely unlinked) and the `permitted` check below is the third copy for the case
 *    where this component is reached some other way. Admin view is mirrored by
 *    ADMIN_CHROME_ROUTES, so an admin browsing with admin view off gets AppShell's panel and this
 *    component never mounts — no request, no flash of numbers.
 *
 * ON THE PROPORTION BARS. They are one hue at three steps (purple-700 / purple-300 / line-200),
 * never three hues, for two independent reasons that agree. The repository's visual contract has
 * exactly one action colour and forbids a second accent on a data screen; and the three states are
 * ORDERED — adopted, then under trial, then not adopted — so an ordinal ramp is the correct
 * encoding for them anyway rather than a compromise. The legend carries the words, so identity is
 * never colour alone.
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import { ChartNoAxesCombined, CircleAlert, Info } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { ResizableTh } from "@/components/ResizableTh";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { useAuth } from "@/components/AuthProvider";
import { isAdmin } from "@/lib/permissions";
import {
  adoptionRateText,
  isWithheld,
  loadWorkshopAnalytics,
  type AdoptionGroup,
  type WorkshopAnalytics
} from "@/lib/workshopAnalytics";

/** A rupee figure as an officer would read it. `Intl` so the grouping follows the Indian system. */
const RUPEES = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 });

/**
 * A rate, or the em dash that stands for "not stated" — and the two must not look alike.
 *
 * A withheld figure rendered in the same ink weight as a measured one is a dash a reader skims
 * past as a formatting quirk. Dropping it to muted ink says "there is nothing here" at a glance,
 * before the sentence underneath is read, which is the only place a reader will otherwise learn it.
 * `title` carries the reason to a pointer, so the distinction survives without the table cell
 * growing a paragraph.
 */
function RateFigure({
  group,
  className
}: {
  group: { rate: number | null; message: string };
  className: string;
}) {
  const withheld = isWithheld(group);
  return (
    <span
      className={`${className} ${withheld ? "text-ink-300" : "text-ink-900"}`}
      title={withheld ? group.message : undefined}
    >
      {adoptionRateText(group)}
    </span>
  );
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-md border border-line-200 bg-card p-4">
      <div className="field-label">{label}</div>
      <div className="mt-1 font-display text-2xl font-bold text-ink-900">{value}</div>
      {hint ? <p className="mt-1 text-xs leading-5 text-ink-500">{hint}</p> : null}
    </div>
  );
}

/**
 * One group's outcomes as an ordered proportion bar.
 *
 * A 2px card-coloured gap sits between segments so two adjacent fills never read as one, and a
 * segment is only drawn when it has something in it — a zero-width sliver with a border is a mark
 * that says "a little" when the answer is "none".
 */
function OutcomeBar({ group }: { group: AdoptionGroup }) {
  const total = group.observations;
  if (!total) return <div className="h-2 w-full rounded-full bg-line-200" aria-hidden />;
  const segments: { key: string; count: number; className: string }[] = [
    { key: "adopted", count: group.adopted, className: "bg-purple-700" },
    { key: "trial", count: group.trial, className: "bg-purple-300" },
    { key: "notAdopted", count: group.notAdopted, className: "bg-line-200" }
  ];
  return (
    <div
      className="flex h-2 w-full gap-[2px] overflow-hidden rounded-full"
      role="img"
      aria-label={`${group.adopted} adopted, ${group.trial} under trial, ${group.notAdopted} not adopted, of ${total}`}
    >
      {segments
        .filter((segment) => segment.count > 0)
        .map((segment) => (
          <span
            key={segment.key}
            className={`${segment.className} h-full rounded-full`}
            style={{ width: `${(segment.count / total) * 100}%` }}
          />
        ))}
    </div>
  );
}

function OutcomeLegend() {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-ink-500">
      <span className="inline-flex items-center gap-1.5">
        <span className="h-2 w-4 rounded-full bg-purple-700" aria-hidden /> Adopted
      </span>
      <span className="inline-flex items-center gap-1.5">
        <span className="h-2 w-4 rounded-full bg-purple-300" aria-hidden /> Under trial
      </span>
      <span className="inline-flex items-center gap-1.5">
        <span className="h-2 w-4 rounded-full bg-line-200" aria-hidden /> Not adopted
      </span>
      <span className="text-ink-300">Outcomes recorded as “not known” are excluded.</span>
    </div>
  );
}

/** One of the three dimension tables. Empty is stated, never rendered as an empty table body. */
function AdoptionTable({
  title,
  columnLabel,
  description,
  groups,
  excluded,
  noun
}: {
  title: string;
  columnLabel: string;
  description: string;
  groups: AdoptionGroup[];
  excluded: number;
  noun: string;
}) {
  return (
    <section className="panel mt-4 overflow-hidden">
      <div className="border-b border-line-200 px-4 py-3">
        <h3 className="font-display font-bold text-ink-900">{title}</h3>
        <p className="text-sm text-ink-500">{description}</p>
        {excluded > 0 ? (
          <p className="mt-1 text-xs text-ink-500">
            {excluded} recorded outcome{excluded === 1 ? "" : "s"} belong to workshops with no {noun}{" "}
            on the cover sheet and {excluded === 1 ? "is" : "are"} not in this table.
          </p>
        ) : null}
      </div>
      {groups.length === 0 ? (
        <div className="p-4 text-sm text-ink-500">
          No workshop with a recorded follow-up has a {noun} on its cover sheet yet.
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[820px] text-left text-sm">
            <thead className="bg-surface-50 text-xs uppercase text-ink-500">
              <tr>
                <ResizableTh>{columnLabel}</ResizableTh>
                <ResizableTh className="text-right">Workshops</ResizableTh>
                <ResizableTh className="text-right">Outcomes</ResizableTh>
                <ResizableTh className="text-right">Adopted</ResizableTh>
                <ResizableTh className="text-right">Rate</ResizableTh>
                <ResizableTh>Outcome mix</ResizableTh>
              </tr>
            </thead>
            <tbody>
              {groups.map((group) => (
                <tr key={group.label} className="border-t border-line-200 align-top">
                  <td className="px-4 py-3">
                    <div className="font-medium text-ink-900">{group.label}</div>
                    <p className="mt-1 max-w-xl text-xs leading-5 text-ink-500">{group.message}</p>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-ink-700">
                    {group.workshops}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-ink-700">
                    {group.observations}
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-ink-700">
                    {group.adopted}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <RateFigure
                      group={group}
                      className="font-display text-base font-bold tabular-nums"
                    />
                  </td>
                  <td className="w-56 px-4 py-4">
                    <OutcomeBar group={group} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

export default function WorkshopAnalyticsPage() {
  const { user, loading: authLoading } = useAuth();
  const permitted = isAdmin(user);

  const [data, setData] = useState<WorkshopAnalytics | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (authLoading || !permitted) return;
    // One shot, no filters, no race: the page has a single request and nothing re-fires it. A
    // `cancelled` flag is still the right guard — a StrictMode remount would otherwise set state on
    // a torn-down component.
    let cancelled = false;
    loadWorkshopAnalytics()
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Unable to load the comparison");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, permitted]);

  const header = (
    <PageHeader
      title="Cross-workshop analytics"
      description="What the archive says together: which crafts and clusters produce designs that are taken up, how the 3, 6 and 12-month follow-ups stand, what products are costed against, and which design opportunities recur."
      icon={<ChartNoAxesCombined className="h-5 w-5" aria-hidden />}
      actions={
        <Link href="/design-workshops" className="field-button-secondary">
          Design workshops
        </Link>
      }
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
          body="Comparing adoption, costs and outcomes across workshops aggregates fieldwork from clusters and designers beyond your own, so it is available to admins and the master admin."
        />
      </>
    );
  }

  if (error) {
    return (
      <>
        {header}
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      </>
    );
  }

  if (!data) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Reading the archive…</section>
      </>
    );
  }

  const { coverage, survival, costRatios, opportunities, reach } = data;

  return (
    <>
      {header}

      {/*
        THE CAUTIONS, FIRST AND UNMISSABLE. Amber rather than the page's own neutrals because this
        is advisory and must not read as an error — nothing has failed, the archive is simply
        thinner than the figures below would otherwise imply. `amber-100`/`amber-800` are the two
        brand rungs; the stock amber steps beside them do not pair.
      */}
      {data.cautions.length > 0 ? (
        <section className="mb-5 rounded-md border border-line-200 bg-amber-100 p-4">
          <div className="flex items-start gap-2">
            <CircleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-800" aria-hidden />
            <div>
              <h2 className="font-display text-sm font-bold text-amber-800">
                Read these before the numbers
              </h2>
              <ul className="mt-2 grid gap-1.5">
                {data.cautions.map((caution) => (
                  <li key={caution} className="text-sm leading-6 text-amber-800">
                    {caution}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </section>
      ) : null}

      <section className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat
          label="Workshops in the archive"
          value={coverage.workshopsInArchive.toLocaleString("en-IN")}
          hint="Deleted workshops are excluded."
        />
        <Stat
          label="With any follow-up"
          value={coverage.workshopsWithFollowUp.toLocaleString("en-IN")}
          hint="Stage 22. Everything about adoption below describes only these."
        />
        <Stat
          label="With cost sheets"
          value={coverage.workshopsWithCostSheets.toLocaleString("en-IN")}
          hint="Stage 17, behind the cost-to-price comparison."
        />
        <Stat
          label="Naming design opportunities"
          value={coverage.workshopsWithOpportunities.toLocaleString("en-IN")}
          hint="Stage 9, behind the recurrence list."
        />
      </section>

      {data.notes.length > 0 ? (
        <section className="mt-4 rounded-md border border-line-200 bg-surface-50 p-4">
          <div className="flex items-start gap-2">
            <Info className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
            <div>
              <h2 className="font-display text-sm font-bold text-ink-900">
                What was set aside, and why
              </h2>
              <ul className="mt-2 grid gap-1.5">
                {data.notes.map((note) => (
                  <li key={note} className="text-sm leading-6 text-ink-500">
                    {note}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </section>
      ) : null}

      {/* ---------------------------------------------------------------- Adoption */}

      <h2 className="mt-8 font-display text-xl font-bold text-ink-900">Adoption</h2>
      <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-500">
        Each workshop is counted once, at the latest follow-up interval it reached, so a workshop
        visited three times does not outweigh one visited once. A design under trial counts against
        adoption and never for it.
      </p>

      <section className="panel mt-4 p-4">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <div className="field-label">Across the archive</div>
            <div className="mt-1">
              <RateFigure group={data.overall} className="font-display text-4xl font-bold" />
            </div>
          </div>
          <div className="min-w-[16rem] flex-1">
            <OutcomeBar group={data.overall} />
            <div className="mt-2">
              <OutcomeLegend />
            </div>
          </div>
        </div>
        <p className="mt-3 max-w-3xl text-sm leading-6 text-ink-700">{data.overall.message}</p>
      </section>

      <AdoptionTable
        title="By craft"
        columnLabel="Craft"
        description="Which craft families produce designs that are taken up."
        groups={data.byCraft}
        excluded={coverage.missingDimension.craft}
        noun="craft"
      />
      <AdoptionTable
        title="By cluster"
        columnLabel="Cluster"
        description="Which clusters come back with results, and which are yet to be revisited."
        groups={data.byCluster}
        excluded={coverage.missingDimension.cluster}
        noun="cluster"
      />
      <AdoptionTable
        title="By state"
        columnLabel="State"
        description="The same outcomes rolled up to the state on each workshop's cover sheet."
        groups={data.byState}
        excluded={coverage.missingDimension.state}
        noun="state"
      />

      {/* ---------------------------------------------------------- 3 / 6 / 12 months */}

      <h2 className="mt-8 font-display text-xl font-bold text-ink-900">
        At 3, 6 and 12 months
      </h2>
      <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-500">
        These are cross-sections — how the designs stood at each interval — and not a survival curve.
        The designs seen at 12 months are not necessarily the designs seen at 3.
      </p>

      <section className="mt-4 grid gap-3 md:grid-cols-3">
        {survival.crossSections.map((section) => (
          <div key={section.interval} className="panel p-4">
            <div className="field-label">{section.label}</div>
            <div className="mt-1">
              <RateFigure group={section} className="font-display text-3xl font-bold" />
            </div>
            <div className="mt-1 text-sm text-ink-700">
              {section.adopted} of {section.observations} adopted
            </div>
            <p className="mt-2 text-xs leading-5 text-ink-500">{section.message}</p>
          </div>
        ))}
      </section>

      <section className="panel mt-3 p-4">
        <h3 className="font-display font-bold text-ink-900">
          Following one design from visit to visit
        </h3>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-700">{survival.message}</p>
        {survival.cohortPossible ? (
          <div className="mt-3 grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="Seen at 3 and 12 months" value={String(survival.trackedTo12)} />
            <Stat label="Still adopted at 12" value={String(survival.stillAdoptedAt12)} />
            <Stat
              label="Not revisited after 3"
              value={String(survival.unobservedAfter3)}
              hint="Not counted in either direction — nobody went back."
            />
            {/*
              Shown even when it is zero. A tile that appears only when rows were excluded reads,
              on the run where it is absent, as though nothing was excluded — which is true, but a
              reader cannot tell that from a page that simply does not mention it.
            */}
            <Stat
              label="No product reference"
              value={String(survival.rowsWithoutProduct)}
              hint="Records that can join no cohort, so they are outside the three figures beside this one."
            />
          </div>
        ) : null}
      </section>

      {/* --------------------------------------------------------------- Cost sheets */}

      <h2 className="mt-8 font-display text-xl font-bold text-ink-900">
        What products cost against what they fetch
      </h2>
      <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-500">
        The expected price as a multiple of the total cost to make, per cluster. 1.0× is at cost.
        {costRatios.sheetsWithoutCluster > 0
          ? ` ${costRatios.sheetsWithoutCluster} cost sheet${costRatios.sheetsWithoutCluster === 1 ? "" : "s"} belong to workshops with no cluster recorded and ${costRatios.sheetsWithoutCluster === 1 ? "is" : "are"} not compared here.`
          : ""}
      </p>

      {costRatios.groups.length === 0 ? (
        // Deliberately NOT `EmptyState`: it hardcodes an <h2>, and this section already has one
        // above it — two level-2 headings for one section is a structure a screen reader reads as
        // two sections.
        <section className="panel mt-4 p-4">
          <h3 className="font-display font-bold text-ink-900">No cluster has costed products yet</h3>
          <p className="mt-1 text-sm leading-6 text-ink-500">
            Stage 17 cost sheets exist, but none belongs to a workshop with a cluster on its cover
            sheet.
          </p>
        </section>
      ) : (
        <section className="panel mt-4 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Cluster</ResizableTh>
                  <ResizableTh className="text-right">Sheets</ResizableTh>
                  <ResizableTh className="text-right">Lowest</ResizableTh>
                  <ResizableTh className="text-right">Median</ResizableTh>
                  <ResizableTh className="text-right">Highest</ResizableTh>
                  <ResizableTh className="text-right">Below cost</ResizableTh>
                </tr>
              </thead>
              <tbody>
                {costRatios.groups.map((group) => (
                  <tr key={group.cluster} className="border-t border-line-200 align-top">
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">{group.cluster}</div>
                      <p className="mt-1 max-w-xl text-xs leading-5 text-ink-500">
                        {group.message}
                      </p>
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums text-ink-700">
                      {group.sheets}
                    </td>
                    {/* An em dash, never a 0.00× — a withheld distribution is not a ratio of zero. */}
                    <td className="px-4 py-3 text-right tabular-nums text-ink-700">
                      {group.ratio ? `${group.ratio.minimum.toFixed(2)}×` : "—"}
                    </td>
                    <td className="px-4 py-3 text-right font-display text-base font-bold tabular-nums text-ink-900">
                      {group.ratio ? `${group.ratio.median.toFixed(2)}×` : "—"}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums text-ink-700">
                      {group.ratio ? `${group.ratio.maximum.toFixed(2)}×` : "—"}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums text-ink-700">
                      {group.belowCost > 0 ? (
                        <span className="font-semibold text-error-600">{group.belowCost}</span>
                      ) : (
                        group.belowCost
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {/* ------------------------------------------------------- Design opportunities */}

      <h2 className="mt-8 font-display text-xl font-bold text-ink-900">
        Design opportunities that recur
      </h2>
      <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-500">
        From the {opportunities.workshopsNaming.toLocaleString("en-IN")} workshop
        {opportunities.workshopsNaming === 1 ? "" : "s"} that named any. A theme has not recurred
        until it appears in two different workshops.
      </p>

      <div className="mt-4 grid gap-3 lg:grid-cols-2">
        <section className="panel overflow-hidden">
          <div className="border-b border-line-200 px-4 py-3">
            <h3 className="font-display font-bold text-ink-900">Target categories</h3>
            <p className="text-sm text-ink-500">
              A closed list, so two workshops choosing the same one have chosen the same thing.
            </p>
          </div>
          {opportunities.categories.length === 0 ? (
            <div className="p-4 text-sm text-ink-500">
              No product category has been named as a target by more than one workshop yet.
            </div>
          ) : (
            <ul className="divide-y divide-line-200">
              {opportunities.categories.map((category) => (
                <li key={category.category} className="flex items-baseline justify-between px-4 py-3">
                  <span className="text-sm text-ink-900">{category.category}</span>
                  <span className="text-xs text-ink-500">
                    {category.workshops} workshops · {category.occurrences} opportunities
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="panel overflow-hidden">
          <div className="border-b border-line-200 px-4 py-3">
            <h3 className="font-display font-bold text-ink-900">Words that recur</h3>
            {/*
              Said plainly, because the alternative is a reader treating a word count as a finding.
              It is a place to look; the examples beside it are what a reader actually judges.
            */}
            <p className="text-sm text-ink-500">
              A word count over the opportunity titles, not a grouping by meaning. Two designers
              writing “bag” may not have meant the same product.
            </p>
          </div>
          {opportunities.terms.length === 0 ? (
            <div className="p-4 text-sm text-ink-500">
              No word appears in the design opportunities of more than one workshop yet.
            </div>
          ) : (
            <ul className="divide-y divide-line-200">
              {opportunities.terms.slice(0, 20).map((term) => (
                <li key={term.term} className="px-4 py-3">
                  <div className="flex items-baseline justify-between gap-3">
                    <span className="text-sm font-medium text-ink-900">{term.term}</span>
                    <span className="shrink-0 text-xs text-ink-500">
                      {term.workshops} workshops · {term.occurrences} mentions
                    </span>
                  </div>
                  {term.examples.length > 0 ? (
                    <p className="mt-1 text-xs leading-5 text-ink-500">
                      {term.examples.join(" · ")}
                    </p>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
          {opportunities.terms.length > 20 ? (
            <div className="border-t border-line-200 px-4 py-2 text-xs text-ink-500">
              The 20 most widespread of {opportunities.terms.length} recurring words are listed.
            </div>
          ) : null}
        </section>
      </div>

      {/* ------------------------------------------------------------------- Money */}

      <h2 className="mt-8 font-display text-xl font-bold text-ink-900">What came back</h2>
      <section className="panel mt-4 p-4">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
          <Stat
            label="Revenue recorded"
            value={`₹${RUPEES.format(reach.revenue)}`}
            hint={`On ${reach.rowsWithRevenue} of ${reach.rows} standing follow-up records.`}
          />
          <Stat
            label="Units sold"
            value={RUPEES.format(reach.unitsSold)}
            hint={`On ${reach.rowsWithUnitsSold} of ${reach.rows}.`}
          />
          <Stat
            label="Orders received"
            value={RUPEES.format(reach.orders)}
            hint={`On ${reach.rowsWithOrders} of ${reach.rows}.`}
          />
        </div>
        <p className="mt-3 max-w-3xl text-sm leading-6 text-ink-700">{reach.message}</p>
      </section>

      {/* ------------------------------------------------- What cannot be computed */}

      <h2 className="mt-8 font-display text-xl font-bold text-ink-900">
        What this cannot tell you
      </h2>
      <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-500">
        Comparisons that were asked for and that the captured fields cannot support. They are listed
        rather than omitted, so the missing field is named instead of being looked for again.
      </p>
      <section className="panel mt-4 divide-y divide-line-200">
        {data.notComputed.map((entry) => (
          <div key={entry.question} className="p-4">
            <h3 className="font-display font-bold text-ink-900">{entry.question}</h3>
            <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-500">{entry.reason}</p>
          </div>
        ))}
      </section>
    </>
  );
}
