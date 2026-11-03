"use client";

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { CloudOff, Lock, PencilRuler } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import {
  SketchesWorkspace,
  sketchesTabFromQuery,
  type SketchesTab
} from "@/components/sketches/SketchesWorkspace";
import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { RENDER_CAP } from "@/components/ui/selectFilter";
import { ApiError } from "@/lib/api";
import { getDesignWorkshop, listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canRunDesignWorkshops, roleLabel } from "@/lib/permissions";
import {
  CHOOSER_NO_WORKSHOPS_BODY,
  CHOOSER_NO_WORKSHOPS_TITLE,
  CHOOSER_OFFLINE_BODY,
  CHOOSER_OFFLINE_ROUTE_LEAD,
  CHOOSER_OFFLINE_ROUTE_LABEL,
  CHOOSER_OFFLINE_ROUTE_TAIL,
  CHOOSER_OFFLINE_TITLE,
  CHOOSER_REFUSED_FALLBACK,
  CHOOSER_REFUSED_TITLE
} from "./chooserSentences";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

const CHOOSER_PAGE = RENDER_CAP;

type ChosenVerdict = "unknown" | "allowed" | "refused" | "uncheckable";

function workshopLabel(summary: DwSummary): string {
  const title = workshopDisplayTitle(summary);
  const when = formatDate(summary.startDate ?? summary.createdAt ?? null);
  return when === "-" ? title : `${title} · ${when}`;
}

export default function SketchesAndPrototypesHubPage() {
  return (
    <Suspense fallback={<PageHeader title="Sketches and Prototypes" icon={<PencilRuler className="h-5 w-5" aria-hidden />} />}>
      <ChooseWorkshopThenSketches />
    </Suspense>
  );
}

function ChooseWorkshopThenSketches() {
  const router = useRouter();
  const search = useSearchParams();
  const { user } = useAuth();

  const chosen = (search.get("workshop") ?? "").trim();
  const tab = sketchesTabFromQuery(search.get("tab"));

  const [workshops, setWorkshops] = useState<DwSummary[] | null>(null);
  const [total, setTotal] = useState(0);
  const [offline, setOffline] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [checked, setChecked] = useState<{
    id: string;
    verdict: Exclude<ChosenVerdict, "unknown">;
    note?: string | null;
  } | null>(null);

  const allowed = canRunDesignWorkshops(user);

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    void (async () => {
      try {
        const result = await listDesignWorkshops({ page: 1, pageSize: CHOOSER_PAGE });
        if (cancelled) return;
        setWorkshops(result.items);
        setTotal(result.total);
        setOffline(false);
        setProblem(null);
      } catch (error) {
        if (cancelled) return;
        if (isUnreachable(error)) {
          setOffline(true);
          setProblem(null);
          return;
        }
        setOffline(false);
        setProblem(
          error instanceof Error && error.message ? error.message : CHOOSER_REFUSED_FALLBACK
        );
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [allowed, attempt]);

  const retry = useCallback(() => {
    setProblem(null);
    setWorkshops(null);
    setOffline(false);
    setChecked(null);
    setAttempt((n) => n + 1);
  }, []);

  const listed = useMemo(
    () => workshops !== null && workshops.some((summary) => summary.id === chosen),
    [workshops, chosen]
  );

  useEffect(() => {
    if (!allowed) return;
    if (!chosen || workshops === null || listed) return;
    if (checked?.id === chosen) return;
    let cancelled = false;
    void (async () => {
      try {
        await getDesignWorkshop(chosen);
        if (cancelled) return;
        setChecked({ id: chosen, verdict: "allowed" });
      } catch (error) {
        if (cancelled) return;
        if (isUnreachable(error)) {
          setChecked({ id: chosen, verdict: "uncheckable", note: null });
          return;
        }
        if (error instanceof ApiError && error.status === 404) {
          setChecked({ id: chosen, verdict: "refused" });
          return;
        }
        setChecked({
          id: chosen,
          verdict: "uncheckable",
          note:
            error instanceof Error && error.message
              ? error.message
              : "Something went wrong."
        });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [allowed, chosen, workshops, listed, checked]);

  const options = useMemo<DropdownOption[]>(
    () => (workshops ?? []).map((summary) => ({ value: summary.id, label: workshopLabel(summary) })),
    [workshops]
  );

  const verdict = useMemo<ChosenVerdict>(() => {
    if (!chosen || workshops === null) return "unknown";
    if (listed) return "allowed";
    return checked?.id === chosen ? checked.verdict : "unknown";
  }, [chosen, workshops, listed, checked]);

  const uncheckableNote =
    checked?.id === chosen && checked.verdict === "uncheckable" ? checked.note ?? null : null;

  const hrefFor = useCallback((workshopId: string, next: SketchesTab) => {
    const query = new URLSearchParams();
    if (workshopId) query.set("workshop", workshopId);
    if (next === "review") query.set("tab", next);
    const suffix = query.toString();
    return suffix ? `/sketches-and-prototypes?${suffix}` : "/sketches-and-prototypes";
  }, []);

  const chooseWorkshop = useCallback(
    (next: string) => {
      router.replace(hrefFor(next, tab), { scroll: false });
    },
    [hrefFor, router, tab]
  );

  const changeTab = useCallback(
    (next: SketchesTab) => {
      router.replace(hrefFor(chosen, next), { scroll: false });
    },
    [chosen, hrefFor, router]
  );

  const header = (
    <PageHeader
      title="Sketches and Prototypes"
      description="Upload, rate and rank a workshop's pieces."
      icon={<PencilRuler className="h-5 w-5" aria-hidden />}
    />
  );

  if (!allowed) {
    return (
      <div>
        {header}
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h2 className="font-display text-xl font-bold tracking-tight text-ink-900">Designer access required</h2>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            For designers, ministry officers and admins.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>. An admin can
            raise your access.
          </p>
          <ActionGroup className="mt-7">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/guide" className="field-button-secondary">
              Open the walkthrough
            </Link>
          </ActionGroup>
        </section>
      </div>
    );
  }

  if (offline) {
    return (
      <div>
        {header}
        <section className="panel px-4 py-6" aria-live="polite">
          <div className="mb-3 grid h-10 w-10 place-items-center rounded-full bg-field-200 text-field-600 dark:text-purple-300">
            <CloudOff className="h-5 w-5" aria-hidden />
          </div>
          <h2 className="text-base font-medium text-ink">{CHOOSER_OFFLINE_TITLE}</h2>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">{CHOOSER_OFFLINE_BODY}</p>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">
            {CHOOSER_OFFLINE_ROUTE_LEAD}
            <Link href="/design-workshops" className="underline">
              {CHOOSER_OFFLINE_ROUTE_LABEL}
            </Link>
            {CHOOSER_OFFLINE_ROUTE_TAIL}
          </p>
          <button type="button" className="field-button-secondary mt-4" onClick={retry}>
            Try again
          </button>
        </section>
      </div>
    );
  }

  if (problem) {
    return (
      <div>
        {header}
        <section className="panel px-4 py-6" aria-live="polite">
          <h2 className="text-base font-medium text-ink">{CHOOSER_REFUSED_TITLE}</h2>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">{problem}</p>
          <button type="button" className="field-button-secondary mt-4" onClick={retry}>
            Try again
          </button>
        </section>
      </div>
    );
  }

  if (workshops !== null && workshops.length === 0) {
    return (
      <div>
        {header}
        <EmptyState title={CHOOSER_NO_WORKSHOPS_TITLE} body={CHOOSER_NO_WORKSHOPS_BODY} />
      </div>
    );
  }

  const truncated = workshops !== null && total > workshops.length;

  return (
    <div>
      {header}

      <section className="panel mb-5 grid gap-3 p-4">
        <div className="grid gap-1">
          <span className="field-label">Which workshop</span>
          <Dropdown
            value={chosen}
            onChange={chooseWorkshop}
            options={options}
            placeholder={workshops === null ? "Looking for your workshops…" : "Choose a design workshop"}
            disabled={workshops === null}
            ariaLabel="Which design workshop"
            describedBy="sketches-workshop-why"
            advanceOnSelect={false}
            searchable
          />
        </div>
        <p id="sketches-workshop-why" className="max-w-3xl text-sm leading-6 text-ink-muted">
          Workshops you can open.
        </p>
        {workshops === null ? (
          <p className="text-xs text-ink-500" aria-live="polite">
            Looking for the design workshops you can open…
          </p>
        ) : null}
        {truncated ? (
          <p className="text-xs text-ink-500">
            Showing the first {workshops?.length ?? 0} of {total}. A workshop that is not in this chooser still opens
            here from a link that names it, and from its own page under{" "}
            <Link href="/design-workshops" className="underline">Design workshops</Link>.
          </p>
        ) : null}
      </section>

      {!chosen ? (
        <p className="panel px-4 py-6 text-center text-sm text-ink-muted">
          Choose a workshop above.
        </p>
      ) : verdict === "unknown" ? (
        <p className="panel px-4 py-6 text-center text-sm text-ink-muted" aria-live="polite">
          Checking that workshop…
        </p>
      ) : verdict === "uncheckable" ? (
        <section className="panel px-4 py-6" aria-live="polite">
          <div className="mb-3 grid h-10 w-10 place-items-center rounded-full bg-field-200 text-field-600 dark:text-purple-300">
            <CloudOff className="h-5 w-5" aria-hidden />
          </div>
          <h2 className="text-base font-medium text-ink">That workshop couldn&apos;t be checked</h2>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">
            {uncheckableNote
              ? `Couldn't check this workshop: ${uncheckableNote}`
              : "Checking this workshop needs a connection."}
          </p>
          <button type="button" className="field-button-secondary mt-4" onClick={retry}>
            Try again
          </button>
        </section>
      ) : verdict === "refused" ? (
        <section className="panel px-4 py-6" aria-live="polite">
          <h2 className="text-base font-medium text-ink">You can&apos;t open that workshop</h2>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">
            Check the link, or choose a workshop above.
          </p>
          <Link href="/design-workshops" className="field-button-secondary mt-4 inline-flex">
            Open the workshops list
          </Link>
        </section>
      ) : (
        <SketchesWorkspace key={chosen} workshopId={chosen} tab={tab} onTabChange={changeTab} idPrefix="sketches" />
      )}
    </div>
  );
}
