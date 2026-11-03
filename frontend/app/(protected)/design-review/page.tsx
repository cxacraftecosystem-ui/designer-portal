"use client";

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Globe2, Lock } from "lucide-react";
import Link from "next/link";

import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { refusalText } from "@/components/sketches/ratingsApi";
import { ReviewPanel } from "@/components/sketches/ReviewPanel";
import type { RateableEntityKey } from "@/components/sketches/reviewRanking";
import { Dropdown } from "@/components/ui/Dropdown";
import { RENDER_CAP } from "@/components/ui/selectFilter";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import { isUnreachable } from "@/lib/failureTriage";
import { canRunDesignWorkshops, roleLabel } from "@/lib/permissions";
import { listPoolWorkshops, poolCountsLabel, poolFirstKind, type PoolWorkshop } from "@/lib/poolDirectory";
import { formatDate } from "@/lib/format";
import { workshopDisplayTitle } from "@/lib/workshopTitle";
import type { PageResult } from "@/lib/types";
import {
  designWorkshopOptions,
  workshopCutSentence,
  workshopEmptyLabel,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import { ActionGroup } from "@/components/ui/ActionGroup";

const ENTITIES: ReadonlyArray<{ key: RateableEntityKey; label: string; hint: string }> = [
  {
    key: "prototype",
    label: "Prototypes",
    hint: "The prototypes in this workshop's pool round."
  },
  {
    key: "sketch",
    label: "Sketches",
    hint: "Including ones never prototyped."
  }
];

const CHOOSER_PAGE = RENDER_CAP;

const SEARCH_DEBOUNCE_MS = 300;

type ListFailure = { unreachable: boolean; note: string | null };

export default function DesignReviewPage() {
  return (
    <Suspense fallback={<PageHeader title="Design review" icon={<Globe2 className="h-5 w-5" aria-hidden />} />}>
      <DesignReview />
    </Suspense>
  );
}

function DesignReview() {
  const router = useRouter();
  const search = useSearchParams();
  const { user } = useAuth();
  const workshopId = (search.get("workshop") ?? "").trim();
  const [typed, setTyped] = useState("");
  const [entityKey, setEntityKey] = useState<RateableEntityKey>("prototype");
  const [titleQuery, setTitleQuery] = useState("");
  const [searching, setSearching] = useState(false);

  const [workshops, setWorkshops] = useState<DwSummary[] | null>(null);
  const [total, setTotal] = useState(0);
  const [listFailure, setListFailure] = useState<ListFailure | null>(null);
  const [attempt, setAttempt] = useState(0);

  const allowed = canRunDesignWorkshops(user);

  const [pool, setPool] = useState<PageResult<PoolWorkshop> | null>(null);
  const [poolPage, setPoolPage] = useState(1);
  const [poolQuery, setPoolQuery] = useState("");
  const [poolFailure, setPoolFailure] = useState<string | null>(null);
  const [poolAttempt, setPoolAttempt] = useState(0);

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    const timer = window.setTimeout(
      () => {
        listPoolWorkshops({ page: poolPage, pageSize: 10, search: poolQuery })
          .then((result) => {
            if (cancelled) return;
            setPool(result);
            setPoolFailure(null);
          })
          .catch((error) => {
            if (cancelled) return;
            setPoolFailure(
              isUnreachable(error)
                ? "No connection — couldn't load open workshops."
                : "Couldn't load open workshops. Try again."
            );
          });
      },
      poolQuery.trim() ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [allowed, poolPage, poolQuery, poolAttempt]);

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    setSearching(true);
    const timer = window.setTimeout(() => {
    void (async () => {
      try {
        const result = await listDesignWorkshops({
          page: 1,
          pageSize: CHOOSER_PAGE,
          search: titleQuery.trim() || undefined
        });
        if (cancelled) return;
        setWorkshops(result.items);
        setTotal(result.total);
        setListFailure(null);
        setSearching(false);
      } catch (error) {
        if (cancelled) return;
        const offline = isUnreachable(error);
        setSearching(false);
        setListFailure({
          unreachable: offline,
          note: offline
            ? null
            : refusalText(error, "Couldn't load your workshops.")
        });
      }
    })();
    }, titleQuery.trim() ? SEARCH_DEBOUNCE_MS : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [allowed, attempt, titleQuery]);

  const retry = useCallback(() => {
    setListFailure(null);
    setWorkshops(null);
    setAttempt((n) => n + 1);
  }, []);

  const listState = useMemo<WorkshopListState<DwSummary>>(
    () =>
      listFailure
        ? { kind: "failed" }
        : workshops === null
          ? { kind: "loading" }
          : { kind: "ok", rows: workshops, total },
    [listFailure, workshops, total]
  );

  const voice = useMemo<WorkshopListVoice>(
    () => ({ table: "design", scoped: true, online: !listFailure?.unreachable }),
    [listFailure]
  );

  const set = useMemo(
    () => designWorkshopOptions(listState, { group: true, offPage: { mode: "refuse" } }),
    [listState]
  );

  const listedRow = useMemo(
    () => (workshops ?? []).find((summary) => summary.id === workshopId) ?? null,
    [workshops, workshopId]
  );

  const hrefFor = useCallback((id: string) => {
    const query = new URLSearchParams();
    if (id) query.set("workshop", id);
    const suffix = query.toString();
    return suffix ? `/design-review?${suffix}` : "/design-review";
  }, []);

  const chooseListed = useCallback(
    (next: string) => {
      router.replace(hrefFor(next), { scroll: false });
    },
    [hrefFor, router]
  );

  const open = useCallback(
    (event: React.FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      const value = typed.trim();
      if (!value) return;
      const fromUrl = value.match(/design-workshops\/([^/?#]+)/);
      const id = fromUrl ? fromUrl[1] : value;
      router.push(hrefFor(id));
    },
    [hrefFor, router, typed]
  );

  if (!canRunDesignWorkshops(user)) {
    return (
      <div>
        <PageHeader title="Design review" icon={<Globe2 className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">Designer access required</h1>
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

  const chosen = ENTITIES.find((entity) => entity.key === entityKey) ?? ENTITIES[0];
  const asking = workshops === null && !listFailure;
  const ready = workshops !== null && !listFailure;
  const workshopCut = workshopCutSentence(set, { term: titleQuery, searchable: true });

  return (
    <div>
      <PageHeader
        title="Design review"
        description="Rate sketches and prototypes from other workshops."
        icon={<Globe2 className="h-5 w-5" aria-hidden />}
      />

      <section className="panel mb-5 grid gap-3 p-4" aria-labelledby="design-review-pool-heading">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="design-review-pool-heading" className="font-display text-base font-bold tracking-tight text-ink-900">
            Workshops open to the pool
          </h2>
          {pool ? (
            <span className="text-xs text-ink-500">
              {pool.total} workshop{pool.total === 1 ? "" : "s"}
            </span>
          ) : null}
        </div>
        <p className="max-w-3xl text-sm leading-6 text-ink-muted">
          Workshops that opened finished pieces for review.
        </p>
        <input
          className="field-input max-w-md"
          type="search"
          value={poolQuery}
          onChange={(event) => {
            setPoolQuery(event.target.value.slice(0, 120));
            setPoolPage(1);
          }}
          placeholder="Search these workshops by title"
          aria-label="Search the workshops open to the pool"
        />
        {poolFailure ? (
          <div className="rounded-md border border-line-200 bg-field-100 px-3 py-2" aria-live="polite">
            <p className="text-xs leading-5 text-ink-700">{poolFailure}</p>
            <button type="button" className="field-button-secondary mt-2" onClick={() => setPoolAttempt((n) => n + 1)}>
              Try again
            </button>
          </div>
        ) : pool === null ? (
          <p className="text-xs text-ink-500" aria-live="polite">
            Looking for the workshops open to the pool…
          </p>
        ) : pool.items.length === 0 ? (
          <p className="text-sm text-ink-700" aria-live="polite">
            {poolQuery.trim()
              ? `No workshop open to the pool matches “${poolQuery.trim()}”.`
              : "None yet."}
          </p>
        ) : (
          <ul className="grid gap-2 sm:grid-cols-2">
            {pool.items.map((item) => {
              const active = item.workshopId === workshopId;
              return (
                <li key={item.workshopId}>
                  <button
                    type="button"
                    aria-pressed={active}
                    onClick={() => {
                      setEntityKey(poolFirstKind(item.openCounts));
                      router.push(hrefFor(item.workshopId), { scroll: false });
                    }}
                    className={
                      active
                        ? "grid w-full gap-0.5 rounded-md border border-purple-700 bg-purple-50 px-3 py-2 text-left"
                        : "grid w-full gap-0.5 rounded-md border border-line-200 bg-card px-3 py-2 text-left hover:border-purple-300 hover:bg-purple-50"
                    }
                  >
                    <span className="truncate text-sm font-medium text-ink-900">{workshopDisplayTitle(item)}</span>
                    <span className="text-xs text-ink-500">
                      {poolCountsLabel(item.openCounts)}
                      {item.startDate ? ` · ${formatDate(item.startDate)}` : ""}
                      {item.endDate ? ` – ${formatDate(item.endDate)}` : ""}
                      {active ? " · reading now" : ""}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
        {pool && pool.pages > 1 ? (
          <div className="flex items-center gap-2 text-xs text-ink-500">
            <button
              type="button"
              className="field-button-secondary"
              disabled={poolPage <= 1}
              onClick={() => setPoolPage((n) => Math.max(1, n - 1))}
            >
              Previous
            </button>
            Page {pool.page} of {pool.pages}
            <button
              type="button"
              className="field-button-secondary"
              disabled={poolPage >= pool.pages}
              onClick={() => setPoolPage((n) => n + 1)}
            >
              Next
            </button>
          </div>
        ) : null}
      </section>

      <section className="panel mb-5 grid gap-5 p-4">
        <div className="grid gap-1">
          <span className="field-label">A workshop you can open yourself</span>
          <Dropdown
            value={listedRow ? workshopId : ""}
            onChange={chooseListed}
            options={set.options}
            serverQuery={{ value: titleQuery, onChange: setTitleQuery, pending: searching }}
            placeholder={
              asking ? "Looking for your workshops…" : listFailure ? "This list could not be loaded" : "Choose one of your design workshops"
            }
            emptyLabel={workshopEmptyLabel(listState, voice)}
            disabled={!ready || (!titleQuery.trim() && set.options.length === 0)}
            ariaLabel="A design workshop you can open yourself"
            describedBy="design-review-scope design-review-empty design-review-truncation"
            advanceOnSelect={false}
          />
          <p id="design-review-scope" className="max-w-3xl text-sm leading-6 text-ink-muted">
            Workshops you can open.
          </p>
          <p className="max-w-3xl text-xs leading-5 text-ink-500">
            Same pieces as its Review tab.
          </p>
          {asking ? (
            <p className="text-xs text-ink-500" aria-live="polite">
              Looking for the design workshops you can open…
            </p>
          ) : null}
          {listFailure ? (
            <div className="rounded-md border border-line-200 bg-field-100 px-3 py-2" aria-live="polite">
              <p className="text-xs leading-5 text-ink-700">
                {listFailure.unreachable
                  ? "No connection. Try again."
                  : listFailure.note}
              </p>
              {listFailure.unreachable ? null : (
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  You can still paste a link below.
                </p>
              )}
              <button type="button" className="field-button-secondary mt-2" onClick={retry}>
                Try again
              </button>
            </div>
          ) : null}
          {ready && set.options.length === 0 ? (
            <p id="design-review-empty" className="text-xs leading-5 text-ink-500" aria-live="polite">
              {titleQuery.trim()
                ? `Nothing matches “${titleQuery.trim()}”.`
                : "No workshops yet. You can paste a link below."}
            </p>
          ) : null}
          {workshopCut ? (
            <p id="design-review-truncation" className="text-xs leading-5 text-ink-500">
              {workshopCut} The {set.drawn} added most recently, sorted by workshop date. Paste a link below, or use{" "}
              <Link href="/design-workshops" className="underline">
                Design workshops
              </Link>
              .
            </p>
          ) : null}
        </div>

        <form onSubmit={open} className="grid gap-1">
          <label className="grid gap-1">
            <span className="field-label">Or paste a workshop link or id</span>
            <input
              className="field-input"
              value={typed}
              onChange={(event) => setTyped(event.target.value)}
              placeholder="Paste the workshop's link, or its id"
              aria-describedby="design-review-why"
            />
          </label>
          <p id="design-review-why" className="max-w-3xl text-sm leading-6 text-ink-muted">
            Each round ranks one workshop&apos;s pieces.
          </p>
          <div>
            <button type="submit" className="field-button" disabled={!typed.trim()}>
              Open this round
            </button>
          </div>
        </form>

        {workshopId ? (
          <p className="text-xs leading-5 text-ink-500" aria-live="polite">
            {listedRow ? (
              <>
                Now reading{" "}
                <span className="font-medium text-ink-700">{workshopDisplayTitle(listedRow)}</span>.
              </>
            ) : ready ? (
              <>
                Now reading the workshop <span className="font-mono text-ink-700">{workshopId}</span>. It isn&apos;t in
                your list above; its round is shown below.
              </>
            ) : (
              <>
                Now reading the workshop <span className="font-mono text-ink-700">{workshopId}</span>. Its round is shown
                below.
              </>
            )}
          </p>
        ) : null}
      </section>

      {workshopId ? (
        <>
          <div role="group" aria-label="What to review" className="mb-4 flex flex-wrap items-center gap-2">
            {ENTITIES.map((entity) => {
              const active = entity.key === entityKey;
              return (
                <button
                  key={entity.key}
                  type="button"
                  aria-pressed={active}
                  onClick={() => setEntityKey(entity.key)}
                  className={
                    active
                      ? "rounded-md border border-purple-700 bg-purple-700 px-3 py-1.5 text-sm font-semibold text-white"
                      : "rounded-md border border-line-200 bg-card px-3 py-1.5 text-sm font-medium text-ink-700 hover:border-purple-300 hover:bg-purple-50"
                  }
                >
                  {entity.label}
                  <span className={active ? "ml-2 text-[11px] font-normal text-white/80" : "sr-only"}>
                    {active ? "showing" : "not showing"}
                  </span>
                </button>
              );
            })}
            <span className="text-xs text-ink-muted">{chosen.hint}</span>
          </div>
          <ReviewPanel workshopId={workshopId} round="POOL" readsStageRows={false} entityKey={entityKey} />
        </>
      ) : (
        <p className="panel px-4 py-6 text-center text-sm text-ink-muted">
          Choose a workshop above, or paste a link.
        </p>
      )}
    </div>
  );
}
