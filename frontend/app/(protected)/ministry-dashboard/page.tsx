"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Download, FileArchive, LayoutDashboard, Lock, RefreshCw, ShieldCheck, UserCog, Users } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { MegaCard } from "@/components/dashboard/MegaCard";
import { useMegaCards } from "@/components/dashboard/useMegaCards";
import { OpenableRow, OpenableRowLink } from "@/components/data/OpenableRow";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import { ministryExportsEnabled } from "@/lib/ministryExports";
import {
  DEFAULT_REGISTER_KIND,
  PEOPLE_KINDS,
  REGISTER_KINDS,
  downloadBeneficiaries,
  downloadRegister,
  fetchRegisterEntitlements,
  fetchRegisterSummary,
  hasProgressFigure,
  listRegisterDesignWorkshops,
  listRegisterOtherWorkshops,
  listRegisterPeople,
  personProgressSentence,
  progressSentence,
  receivedWords,
  standingMembersSentence,
  standingsFor,
  unclassifiedSentence,
  type DesignRegisterPage,
  type OtherRegisterPage,
  type RegisterEntitlements,
  type PeopleKind,
  type RegisterKind,
  type RegisterPeoplePage,
  type RegisterAttention,
  type RegisterPerson,
  type RegisterSummary
} from "@/lib/ministryDashboard";
import { isUnreachable } from "@/lib/offline";
import { canApproveDesignWorkshops, canManageMinistryExports, canReadWorkshopAnalytics, canSeeMinistryDashboard, roleLabel } from "@/lib/permissions";
import { registerRowHref } from "@/lib/reviewAids";
import { AttentionCards, MinistryAnalyticsPanel, StageDataExportPanel, StateBreakdown } from "./MinistryInsights";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

const PAGE_SIZE = 20;

const SEARCH_DEBOUNCE_MS = 300;

const REFRESH_MS = 30_000;

const PEOPLE_PAGE_SIZE = 20;

const PEOPLE_ICONS = { designers: Users, officers: UserCog, inspectors: ShieldCheck } as const;

function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "No connection — couldn't load the register.";
  }
  if (error.status === 403) {
    return error.message;
  }
  return `${error.message} Couldn't load the register.`;
}

export default function MinistryDashboardPage() {
  const { user, loading } = useAuth();

  const [kind, setKind] = useState<RegisterKind>(DEFAULT_REGISTER_KIND);
  const [standing, setStanding] = useState("");
  const [attention, setAttention] = useState<RegisterAttention | "">("");
  const [stateFilter, setStateFilter] = useState("");
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);

  const [data, setData] = useState<DesignRegisterPage | OtherRegisterPage | null>(null);
  const [dataKind, setDataKind] = useState<RegisterKind>(DEFAULT_REGISTER_KIND);
  const [summary, setSummary] = useState<RegisterSummary | null>(null);

  const [people, setPeople] = useState<Partial<Record<PeopleKind, RegisterPeoplePage>>>({});
  const [peopleError, setPeopleError] = useState<Partial<Record<PeopleKind, string>>>({});
  const [peoplePage, setPeoplePage] = useState<Partial<Record<PeopleKind, number>>>({});
  const [entitlements, setEntitlements] = useState<RegisterEntitlements | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [readAt, setReadAt] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [refreshNote, setRefreshNote] = useState("");
  const manualRefresh = useRef(false);
  const [busyDownload, setBusyDownload] = useState<string | null>(null);

  const [loadToken, setLoadToken] = useState(0);

  const currentLoad = useRef(0);

  const inFlight = useRef(false);

  const allowed = !loading && canSeeMinistryDashboard(user);

  const peopleCards = useMegaCards("ministry-dashboard");

  useEffect(() => {
    const term = query.trim();
    const timer = window.setTimeout(
      () => {
        setApplied(term);
        setPage(1);
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [query]);

  const chooseKind = useCallback((next: RegisterKind) => {
    setKind(next);
    setStanding("");
    setAttention("");
    setStateFilter("");
    setPage(1);
  }, []);

  const chooseAttention = useCallback((next: RegisterAttention | "") => {
    setKind("design");
    setAttention(next);
    setPage(1);
  }, []);

  const chooseState = useCallback((next: string) => {
    setKind("design");
    setStateFilter(next);
    setPage(1);
  }, []);

  const chooseStanding = useCallback((next: string) => {
    setStanding(next);
    setPage(1);
  }, []);

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    const generation = (currentLoad.current += 1);

    const requested = kind;
    const request =
      requested === "design"
        ? listRegisterDesignWorkshops({
            page,
            pageSize: PAGE_SIZE,
            search: applied || undefined,
            standing: standing || undefined,
            attention: attention || undefined,
            state: stateFilter || undefined
          })
        : listRegisterOtherWorkshops({ page, pageSize: PAGE_SIZE, search: applied || undefined, standing: standing || undefined });

    inFlight.current = true;
    request
      .finally(() => {
        inFlight.current = false;
      })
      .then((result) => {
        if (generation !== currentLoad.current) return;
        if (cancelled) return;
        setData(result);
        setDataKind(requested);
        setError(null);
        setReadAt(new Date().toISOString());
        if (manualRefresh.current) {
          manualRefresh.current = false;
          setRefreshNote(`Refreshed. ${result.total} ${result.total === 1 ? "workshop" : "workshops"}.`);
        }
      })
      .catch((err) => {
        if (generation !== currentLoad.current) return;
        if (cancelled) return;
        setError(describeFailure(err));
        if (manualRefresh.current) {
          manualRefresh.current = false;
          setRefreshNote("The register couldn't be refreshed.");
        }
      });

    return () => {
      cancelled = true;
    };
  }, [allowed, kind, page, applied, standing, attention, stateFilter, loadToken]);

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    fetchRegisterSummary()
      .then((result) => {
        if (!cancelled) setSummary(result);
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, [allowed, loadToken]);

  const openPeople = PEOPLE_KINDS.filter((entry) => peopleCards.isOpen(entry.id))
    .map((entry) => entry.id)
    .join(",");
  const peopleLanded = useRef<Partial<Record<PeopleKind, number>>>({});

  useEffect(() => {
    if (!allowed || !openPeople) return;
    let cancelled = false;
    const kinds = (openPeople.split(",") as PeopleKind[]).filter(
      (peopleKind) => peopleLanded.current[peopleKind] !== loadToken
    );
    kinds.forEach((peopleKind) => {
      listRegisterPeople(peopleKind, { page: peoplePage[peopleKind] ?? 1, pageSize: PEOPLE_PAGE_SIZE })
        .then((result) => {
          if (cancelled) return;
          peopleLanded.current[peopleKind] = loadToken;
          setPeople((current) => ({ ...current, [peopleKind]: result }));
          setPeopleError((current) => ({ ...current, [peopleKind]: undefined }));
        })
        .catch((err: unknown) => {
          if (cancelled) return;
          setPeopleError((current) => ({
            ...current,
            [peopleKind]: err instanceof Error ? err.message : "This list could not be read."
          }));
        });
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allowed, loadToken, openPeople]);

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    fetchRegisterEntitlements()
      .then((result) => {
        if (!cancelled) setEntitlements(result);
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, [allowed]);

  useEffect(() => {
    if (!allowed) return;

    const tick = () => {
      if (document.visibilityState === "hidden") return;
      if (inFlight.current) return;
      setLoadToken((token) => token + 1);
    };

    const timer = window.setInterval(tick, REFRESH_MS);

    const onVisible = () => {
      if (document.visibilityState === "visible") setLoadToken((token) => token + 1);
    };
    document.addEventListener("visibilitychange", onVisible);

    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [allowed]);

  useEffect(() => {
    if (!data) return;
    if (data.pages > 0 && page > data.pages) setPage(1);
  }, [data, page]);

  const runDownload = useCallback(
    async (name: string, run: () => Promise<unknown>) => {
      setDownloadError(null);
      setBusyDownload(name);
      try {
        await run();
      } catch (err) {
        setDownloadError(
          err instanceof ApiError
            ? err.message
            : "Couldn't start the download. Try again."
        );
      } finally {
        setBusyDownload(null);
      }
    },
    []
  );

  if (!loading && !canSeeMinistryDashboard(user)) {
    return (
      <div>
        <PageHeader title="Ministry dashboard" icon={<LayoutDashboard className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">Ministry access required</h1>
          <p className="mx-auto mt-3 max-w-xl text-sm leading-6 text-ink-500">
            For Assistant Directors, Regional Directors, Ministry Admins and the Master Admin. Admins:
            use Cross-workshop analytics. Designers: use Design workshops.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
          </p>
          <ActionGroup className="mt-7">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/design-workshops" className="field-button-secondary">
              Design workshops
            </Link>
          </ActionGroup>
        </section>
      </div>
    );
  }

  const rows = data?.items ?? [];
  const standings = standingsFor(kind);
  const switching = data !== null && dataKind !== kind;
  const membersSentence = standingMembersSentence(data ?? null, standing);
  const unclassified = summary ? unclassifiedSentence(summary.designWorkshops.unclassified) : null;

  return (
    <div>
      <PageHeader
        title="Ministry dashboard"
        description="Every workshop and each designer's progress."
        icon={<LayoutDashboard className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <Link href="/map?view=coverage" className="field-button-secondary">
              Coverage map
            </Link>
            {canManageMinistryExports(user) && ministryExportsEnabled(user) ? (
              <Link href="/ministry-exports" className="field-button-secondary">
                <FileArchive className="h-4 w-4" aria-hidden />
                Monthly packs
              </Link>
            ) : null}
          </>
        }
      />

      {data ? (
        <p className="section-band mb-4 border border-line-200 px-4 py-3 text-sm leading-6 text-ink-700">
          {data.scopeLabel}
        </p>
      ) : null}

      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}

      {summary ? (
        <section aria-labelledby="ministry-register-glance" className="mb-6">
          <h2 id="ministry-register-glance" className="mb-3 font-display text-lg font-bold text-ink-900">
            The programme at a glance
          </h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
            {[
              { label: "Design & prototype", value: summary.designWorkshops.total },
              { label: "Ongoing", value: summary.designWorkshops.ongoing },
              { label: "Completed", value: summary.designWorkshops.completed },
              { label: "Newly registered", value: summary.designWorkshops.registered },
              { label: "Other workshops", value: summary.otherWorkshops.total }
            ].map((card) => (
              <div key={card.label} className="panel p-4">
                <div className="text-xs font-medium text-ink-500">{card.label}</div>
                <div className="mt-2 font-display text-2xl font-bold tabular-nums text-ink-900">{card.value}</div>
              </div>
            ))}
          </div>
          {unclassified ? <p className="mt-2 text-xs leading-5 text-ink-500">{unclassified}</p> : null}
          {summary.designWorkshops.scope !== summary.otherWorkshops.scope ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">{summary.otherWorkshops.scopeLabel}</p>
          ) : null}
        </section>
      ) : null}

      {summary ? (
        <>
          <AttentionCards summary={summary.designWorkshops} selected={attention} onSelect={chooseAttention} />
          <StateBreakdown rows={summary.designWorkshops.byState} selected={stateFilter} onSelect={chooseState} />
        </>
      ) : null}
      {canReadWorkshopAnalytics(user) ? <MinistryAnalyticsPanel /> : null}

      <section aria-labelledby="ministry-register-filters" className="mb-4 grid gap-4">
        <h2 id="ministry-register-filters" className="sr-only">
          Which workshops to show
        </h2>

        <div role="group" aria-label="Type of workshop" className="grid gap-2.5 sm:grid-cols-2">
          {REGISTER_KINDS.map((option) => {
            const selected = option.value === kind;
            return (
              <button
                key={option.value}
                type="button"
                aria-pressed={selected}
                onClick={() => chooseKind(option.value)}
                className={
                  selected
                    ? "flex h-full w-full flex-col items-start gap-1 rounded-md border border-ministry-600 bg-ministry-50 px-3.5 py-3 text-left transition-shadow hover:shadow-md dark:bg-ministry-950/40"
                    : "flex h-full w-full flex-col items-start gap-1 rounded-md border border-line-200 bg-card px-3.5 py-3 text-left transition-shadow hover:border-ministry-300 hover:shadow-md"
                }
              >
                <span className="flex w-full items-center gap-1.5">
                  <span
                    className={
                      selected
                        ? "font-display text-sm font-bold text-ministry-700 dark:text-ministry-300"
                        : "font-display text-sm font-bold text-ink-900"
                    }
                  >
                    {option.label}
                  </span>
                  {selected ? (
                    <Check className="ml-auto h-4 w-4 shrink-0 text-ministry-700 dark:text-ministry-300" aria-hidden />
                  ) : null}
                </span>
                <span className="text-xs leading-5 text-ink-500">{option.note}</span>
              </button>
            );
          })}
        </div>

        <div>
          <div role="group" aria-label="Standing" className="flex flex-wrap gap-2">
            {standings.map((option) => {
              const selected = option.value === standing;
              return (
                <button
                  key={option.value || "all"}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => chooseStanding(option.value)}
                  className={
                    selected
                      ? "inline-flex items-center gap-1.5 rounded-full border border-ministry-700 bg-ministry-700 px-3 py-1.5 text-xs font-semibold text-white"
                      : "inline-flex items-center gap-1.5 rounded-full border border-line-200 bg-card px-3 py-1.5 text-xs font-medium text-ink-700 transition hover:border-ministry-300"
                  }
                >
                  {selected ? <Check className="h-3.5 w-3.5 shrink-0" aria-hidden /> : null}
                  {option.label}
                </button>
              );
            })}
          </div>
          {membersSentence ? <p className="mt-2 text-xs leading-5 text-ink-500">{membersSentence}</p> : null}
        </div>

        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query.trim());
            setPage(1);
          }}
          ariaLabel={kind === "design" ? "Search design and prototype workshops" : "Search recorded workshops"}
          placeholder={
            kind === "design"
              ? "Search by title, workshop code, craft or cluster"
              : "Search by title, place or description"
          }
        />
      </section>

      <section className="mb-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-3">
        <div className="text-xs leading-5 text-ink-500">
          {readAt ? (
            <>
              Updated at <span className="font-medium text-ink-700">{formatDateTime(readAt)}</span>.{error ? " Last refresh failed." : ""}
              {switching ? " Loading…" : ""}
            </>
          ) : (
            "Loading the register…"
          )}
          <button
            type="button"
            onClick={() => {
              manualRefresh.current = true;
              setRefreshNote("Refreshing the register…");
              setLoadToken((token) => token + 1);
            }}
            className="ml-2 inline-flex items-center gap-1 rounded-sm px-1.5 py-0.5 font-semibold text-ministry-700 underline-offset-2 hover:underline dark:text-ministry-300"
          >
            <RefreshCw className="h-3.5 w-3.5" aria-hidden />
            Refresh now
          </button>
        </div>

        <p role="status" aria-live="polite" className="sr-only">
          {refreshNote}
        </p>

        <ActionGroup>
          <button
            type="button"
            className="field-button-secondary"
            disabled={busyDownload !== null}
            onClick={() =>
              runDownload("register", () =>
                downloadRegister(kind, { search: applied || undefined, standing: standing || undefined })
              )
            }
          >
            <Download className="h-4 w-4" aria-hidden />
            {busyDownload === "register" ? "Preparing…" : "Download workshops"}
          </button>
          <button
            type="button"
            className="field-button"
            disabled={busyDownload !== null || entitlements?.beneficiaries === false}
            onClick={() => runDownload("beneficiaries", downloadBeneficiaries)}
          >
            <Download className="h-4 w-4" aria-hidden />
            {busyDownload === "beneficiaries" ? "Preparing…" : "Download beneficiaries"}
          </button>
        </ActionGroup>
      </section>

      <p className="mb-4 text-xs leading-5 text-ink-500">
        “Download workshops” exports this table, with no stage content. “Download beneficiaries” exports every
        artisan, identity numbers masked to the last four characters.
        {entitlements && !entitlements.beneficiaries ? (
          <span className="mt-1 block font-medium text-ink-700">{entitlements.beneficiariesRefusal}</span>
        ) : null}
      </p>
      {downloadError ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">
          {downloadError}
        </div>
      ) : null}

      {kind === "design" ? (
        <StageDataExportPanel
          filters={{
            search: applied || undefined,
            standing: standing || undefined,
            attention: attention || undefined,
            state: stateFilter || undefined
          }}
        />
      ) : null}

      <section className="panel overflow-hidden">
        {data === null && error ? (
          <div className="p-4 text-sm text-ink-700">
            Couldn&apos;t load the register. It retries, or use “Refresh now”.
          </div>
        ) : data === null ? (
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title={
                applied || standing
                  ? "No workshop here matches those filters"
                  : "No workshop is in this register yet"
              }
              body={
                applied || standing
                  ? "No matches. Clear the filters to see all."
                  : "There are no workshops here yet."
              }
            />
          </div>
        ) : (
          <div className="overflow-x-auto focus-visible:outline-2 focus-visible:outline-purple-700" tabIndex={0} role="region" aria-label="Ministry dashboard table" aria-live="off">
            {dataKind === "design" ? (
              <DesignTable
                rows={(data as DesignRegisterPage).items}
                hrefFor={(id) => registerRowHref("design", id, { approves: canApproveDesignWorkshops(user) })}
              />
            ) : (
              <OtherTable rows={(data as OtherRegisterPage).items} hrefFor={(id) => registerRowHref("other", id, { approves: false })} />
            )}
          </div>
        )}
        {data ? <Pagination onPage={setPage} page={data.page} pages={data.pages} total={data.total} /> : null}
      </section>

      <div className="mt-6 grid items-start gap-5 lg:grid-cols-2">
        {PEOPLE_KINDS.map((entry) => {
          const loaded = people[entry.id];
          const failed = peopleError[entry.id];
          return (
            <MegaCard
              key={entry.id}
              title={entry.title}
              note={entry.note}
              icon={PEOPLE_ICONS[entry.id]}
              tone="mango"
              count={loaded?.total ?? 0}
              countLabel={entry.countLabel}
              expanded={peopleCards.isOpen(entry.id)}
              onToggle={() => peopleCards.toggle(entry.id)}
            >
              <PeoplePanel
                kind={entry.id}
                data={loaded}
                error={failed}
                onPage={(next) => setPeoplePage((current) => ({ ...current, [entry.id]: next }))}
              />
            </MegaCard>
          );
        })}
      </div>
    </div>
  );
}

function PeoplePanel({
  kind,
  data,
  error,
  onPage
}: {
  kind: PeopleKind;
  data: RegisterPeoplePage | undefined;
  error: string | undefined;
  onPage: (page: number) => void;
}) {
  if (error) {
    return (
      <p role="alert" className="text-sm leading-6 text-red-700 dark:text-red-400">
        {error} This list couldn&apos;t be loaded.
      </p>
    );
  }
  if (!data) return <p className="text-sm text-ink-700">Loading...</p>;

  return (
    <div className="grid gap-3">
      <p className="text-xs leading-5 text-ink-500">{data.scopeLabel}</p>

      {data.progressNote ? (
        <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          {data.progressNote}
        </p>
      ) : null}
      {data.withheldAccountsNote ? (
        <p className="text-xs leading-5 text-ink-500">{data.withheldAccountsNote}</p>
      ) : null}
      {data.unpostedAccountsNote ? (
        <p className="text-xs leading-5 text-ink-500">{data.unpostedAccountsNote}</p>
      ) : null}

      {data.rosterRepresentationNote ? (
        <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          {data.rosterRepresentationNote}
        </p>
      ) : null}
      {data.feedbackNote ? <p className="text-xs leading-5 text-ink-500">{data.feedbackNote}</p> : null}
      {data.scan?.truncated ? (
        <p className="text-xs leading-5 text-ink-500">
          This list was built from the first {data.scan.read} of {data.scan.total} workshops in scope.
        </p>
      ) : null}
      {data.unpostedAccountsTruncated ? (
        <p className="text-xs leading-5 text-ink-500">
          Some accounts couldn&apos;t be checked.
        </p>
      ) : null}

      {data.items.length === 0 ? (
        <EmptyState title={`No ${kind} in this scope`} />
      ) : (
        <div className="overflow-x-auto focus-visible:outline-2 focus-visible:outline-purple-700" tabIndex={0} role="region" aria-label="Ministry dashboard table" aria-live="off">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead className="bg-surface-50 text-xs uppercase text-ink-500">
              <tr>
                <th className="px-3 py-2">Name</th>
                <th className="px-3 py-2">Workshops</th>
                <th className="px-3 py-2">Standing</th>
                <th className="px-3 py-2">{kind === "inspectors" ? "Corrections" : "Progress"}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-200">
              {data.items.map((person) => (
                <PersonRow key={person.id} kind={kind} person={person} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pagination onPage={onPage} page={data.page} pages={data.pages} total={data.total} />
    </div>
  );
}

function PersonRow({ kind, person }: { kind: PeopleKind; person: RegisterPerson }) {
  return (
    <tr>
      <td className="px-3 py-2">
        <span className="block font-medium text-ink-900">{person.name ?? "Unnamed account"}</span>
        <span className="block text-xs text-ink-500">{roleLabel(person.role)}</span>
      </td>
      <td className="px-3 py-2 tabular-nums">
        {person.workshops}
        {kind === "designers" && typeof person.workshopsCreated === "number" ? (
          <span className="block text-xs text-ink-500">
            {person.workshopsCreated} opened · {person.workshopsNamedOn ?? 0} named on
          </span>
        ) : null}
        {kind === "officers" && person.byCapacity ? (
          <span className="block text-xs text-ink-500">
            {Object.entries(person.byCapacity)
              .map(([capacity, count]) => `${count} ${capacity.toLowerCase()}`)
              .join(" · ")}
            {person.unknownCapacity ? ` · ${person.unknownCapacity} unrecognised` : ""}
          </span>
        ) : null}
      </td>
      <td className="px-3 py-2 text-xs leading-5 text-ink-700">
        {person.registered} registered · {person.ongoing} ongoing · {person.completed} completed
        {person.unclassifiedStanding ? (
          <span className="block text-ink-500">{person.unclassifiedStanding} in no group</span>
        ) : null}
      </td>
      <td className="px-3 py-2 text-xs leading-5 text-ink-700">
        {kind === "inspectors" ? (
          typeof person.feedbackFiled === "number" ? (
            <>
              {person.feedbackFiled} filed
              <span className="block text-ink-500">{person.sendBacks ?? 0} sent a workshop back</span>
            </>
          ) : (
            <span className="text-ink-500">— not read</span>
          )
        ) : (
          personProgressSentence(person)
        )}
      </td>
    </tr>
  );
}

function DesignTable({ rows, hrefFor }: { rows: DesignRegisterPage["items"]; hrefFor: (id: string) => string }) {
  return (
    <table className="w-full min-w-[1100px] text-left text-sm">
      <thead className="bg-surface-50 text-xs uppercase text-ink-500">
        <tr>
          <ResizableTh>Workshop</ResizableTh>
          <ResizableTh>Designer</ResizableTh>
          <ResizableTh>Place</ResizableTh>
          <ResizableTh>Dates</ResizableTh>
          <ResizableTh>Artisans</ResizableTh>
          <ResizableTh>Standing</ResizableTh>
          <ResizableTh>Progress</ResizableTh>
          <ResizableTh>Last changed</ResizableTh>
        </tr>
      </thead>
      <tbody className="divide-y divide-line-200">
        {rows.map((row) => (
          <OpenableRow key={row.id} href={hrefFor(row.id)}>
            <td className="px-4 py-3">
              <OpenableRowLink className="block" href={hrefFor(row.id)}>
                {workshopDisplayTitle(row)}
              </OpenableRowLink>
              <span className="block text-xs text-ink-500">
                {row.workshopCode ?? "No workshop code yet"}
                {row.craftName ? ` · ${row.craftName}` : ""}
                {row.clusterName ? ` · ${row.clusterName}` : ""}
              </span>
            </td>
            <td className="px-4 py-3 text-ink-700">{row.designerName?.trim() || "Not named yet"}</td>
            <td className="px-4 py-3 text-ink-700">
              {[row.venue, row.district, row.state].filter(Boolean).join(", ") || "—"}
            </td>
            <td className="px-4 py-3 text-ink-700">
              {formatDate(row.startDate)}
              {row.endDate ? ` – ${formatDate(row.endDate)}` : ""}
            </td>
            <td className="px-4 py-3 tabular-nums text-ink-700">
              {row.beneficiaries === null ? (
                <span className="text-ink-500" title="Couldn't read the roster.">
                  not read
                </span>
              ) : (
                row.beneficiaries
              )}
            </td>
            <td className="px-4 py-3">
              <StatusBadge status={row.status} />
              {row.handedOnAt ? (
                <span className="mt-1 block text-xs text-ink-500">
                  Handed on {formatDate(row.handedOnAt)}
                  {row.handedOnTo?.trim() ? ` to ${row.handedOnTo.trim()}` : ""}
                  {receivedWords(row.received, formatDate) ? (
                    <span className="mt-0.5 block font-medium text-success-600">{receivedWords(row.received, formatDate)}</span>
                  ) : null}
                </span>
              ) : row.approvedAt ? (
                <span className="mt-1 block text-xs text-ink-500">Approved {formatDate(row.approvedAt)}</span>
              ) : null}
            </td>
            <td className="px-4 py-3">
              <ProgressCell progress={row.progress} />
            </td>
            <td className="px-4 py-3 text-ink-700">{row.updatedAt ? formatDateTime(row.updatedAt) : "—"}</td>
          </OpenableRow>
        ))}
      </tbody>
    </table>
  );
}

function OtherTable({ rows, hrefFor }: { rows: OtherRegisterPage["items"]; hrefFor: (id: string) => string }) {
  return (
    <table className="w-full min-w-[840px] text-left text-sm">
      <thead className="bg-surface-50 text-xs uppercase text-ink-500">
        <tr>
          <ResizableTh>Workshop</ResizableTh>
          <ResizableTh>Kind</ResizableTh>
          <ResizableTh>Place</ResizableTh>
          <ResizableTh>Dates</ResizableTh>
          <ResizableTh>Artisans</ResizableTh>
          <ResizableTh>Status</ResizableTh>
          <ResizableTh>Recorded</ResizableTh>
        </tr>
      </thead>
      <tbody className="divide-y divide-line-200">
        {rows.map((row) => (
          <OpenableRow key={row.id} href={hrefFor(row.id)}>
            <td className="px-4 py-3">
              <OpenableRowLink href={hrefFor(row.id)}>{workshopDisplayTitle(row)}</OpenableRowLink>
            </td>
            <td className="px-4 py-3 text-ink-700">
              {row.workshopType === "DESIGN_PROTOTYPE" ? "Design & prototype" : "Other"}
            </td>
            <td className="px-4 py-3 text-ink-700">{row.place || "—"}</td>
            <td className="px-4 py-3 text-ink-700">
              {formatDate(row.startDate ?? row.date)}
              {row.endDate ? ` – ${formatDate(row.endDate)}` : ""}
            </td>
            <td className="px-4 py-3 tabular-nums text-ink-700">{row.beneficiaries}</td>
            <td className="px-4 py-3">
              <StatusBadge status={row.status} />
            </td>
            <td className="px-4 py-3 text-ink-700">{row.createdAt ? formatDateTime(row.createdAt) : "—"}</td>
          </OpenableRow>
        ))}
      </tbody>
    </table>
  );
}

function ProgressCell({ progress }: { progress: DesignRegisterPage["items"][number]["progress"] }) {
  const sentence = progressSentence(progress);
  if (!hasProgressFigure(progress)) {
    return <span className="block max-w-88 text-xs leading-5 text-ink-500">{sentence}</span>;
  }
  const percent = progress.percent ?? 0;
  return (
    <div className="min-w-48 max-w-88">
      <ProgressBar
        percent={percent}
        aria-hidden
        className="bg-ministry-200 dark:bg-ministry-900"
        fillClassName="bg-ministry-600 transition-[width] duration-300 ease-out dark:bg-ministry-400"
      />
      <span className="mt-1 block text-xs leading-5 tabular-nums text-ink-500">{sentence}</span>
    </div>
  );
}
