"use client";

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
