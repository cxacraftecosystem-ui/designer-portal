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
import { ChartNoAxesCombined } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { useAuth } from "@/components/AuthProvider";
import { isAdmin } from "@/lib/permissions";
import { WorkshopAnalyticsReport } from "@/components/analytics/WorkshopAnalyticsReport";
import { loadWorkshopAnalytics, type WorkshopAnalytics } from "@/lib/workshopAnalytics";

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
      description="Adoption, follow-ups, costs and recurring opportunities across workshops."
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
          body="Admins only."
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

  return (
    <>
      {header}
      <WorkshopAnalyticsReport data={data} />
    </>
  );
}
