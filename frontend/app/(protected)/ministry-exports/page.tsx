"use client";

/**
 * /ministry-exports — THE MINISTRY'S MONTHLY EXPORT PACKS. Web only, like every ministry authoring
 * screen.
 *
 * Three sections: the packs of one month (a card per scheme, Excel and PDF once ready), generating a
 * month on demand, and who is e-mailed when the scheduled packs are ready. The guard is the
 * `ROUTE_GUARDS` row (`MINISTRY_EXPORT_ROLES`); while the deployment has the feature off the page is
 * the not-found screen, as the API is.
 *
 * `?period=YYYY-MM` opens that month — it is the link the monthly e-mail carries. A download is a
 * five-minute link minted per click (`lib/ministryExports.downloadPackFile`), never stored here.
 */

import { notFound, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { FileArchive, FileSpreadsheet, FileText, RefreshCw, Trash2, UserPlus } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { SearchInput } from "@/components/SearchInput";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { SearchableMultiSelect, SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import { useToast } from "@/components/ui/Toast";
import { ApiError, describeApiDetail } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import {
  EVERY_SCHEME,
  POLL_MS,
  STATUS_WORDS,
  downloadPackFile,
  fetchPacks,
  fetchRecipients,
  fetchSchemes,
  generatePacks,
  initialPeriod,
  ministryExportsEnabled,
  packControls,
  periodLabel,
  recentPeriods,
  removeRecipient,
  saveRecipient,
  schemesSummary,
  searchEligibleAccounts,
  shouldPoll,
  sizeText,
  type EligibleAccount,
  type Pack,
  type PackFile,
  type Recipient,
  type Scheme
} from "@/lib/ministryExports";
import { roleLabel } from "@/lib/permissions";

function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const detail = (error.payload as { detail?: unknown } | null | undefined)?.detail;
    return describeApiDetail(detail ?? error.message, fallback);
  }
  return error instanceof Error && error.message ? error.message : fallback;
}

function newKey(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `mx-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  }
}

const STATUS_TONE: Record<Pack["status"], string> = {
  QUEUED: "border-line-200 bg-surface-50 text-ink-700",
  BUILDING: "border-purple-300 bg-purple-50 text-purple-700",
  READY: "border-success-600/30 bg-success-100 text-success-600",
  EMPTY: "border-line-200 bg-surface-50 text-ink-500",
  FAILED: "border-red-200 bg-error-100 text-error-600"
};

export default function MinistryExportsPage() {
  return (
    <Suspense fallback={null}>
      <MinistryExports />
    </Suspense>
  );
}

function MinistryExports() {
  const { user, loading } = useAuth();
  if (!loading && user && !ministryExportsEnabled(user)) notFound();
  if (loading || !user) return null;
  return <MinistryExportsScreen />;
}

function MinistryExportsScreen() {
  const router = useRouter();
  const search = useSearchParams();
  const { toast } = useToast();
  const query = search.get("period");

  const [schemes, setSchemes] = useState<Scheme[]>([]);
  const [periods, setPeriods] = useState<string[]>([]);
  const [period, setPeriod] = useState<string | null>(null);
  const [packs, setPacks] = useState<Pack[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cardErrors, setCardErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const load = useRef(0);

  // The first read decides the month: `?period=`, else the newest with packs, else last month.
  useEffect(() => {
    let cancelled = false;
    Promise.all([fetchSchemes(), fetchPacks()])
      .then(([schemeAnswer, packAnswer]) => {
        if (cancelled) return;
        setSchemes(schemeAnswer.schemes);
        setPeriods(packAnswer.periods);
        setPeriod(initialPeriod(query, packAnswer.periods));
      })
      .catch((caught) => {
        if (!cancelled) setError(errorText(caught, "Could not load the packs."));
      });
    return () => {
      cancelled = true;
    };
    // `query` is read once, on arrival; choosing a month afterwards writes it back.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refresh = useCallback(async (month: string) => {
    const generation = ++load.current;
    try {
      const answer = await fetchPacks(month);
      if (generation !== load.current) return;
      setPacks(answer.items);
      setPeriods(answer.periods);
      setError(null);
    } catch (caught) {
      if (generation === load.current) setError(errorText(caught, "Could not load the packs."));
    }
  }, []);

  useEffect(() => {
    if (period) void refresh(period);
  }, [period, refresh]);

  // Poll while anything is queued or building; stop once every pack has finished.
  useEffect(() => {
    if (!period || !packs || !shouldPoll(packs)) return;
    const timer = window.setTimeout(() => void refresh(period), POLL_MS);
    return () => window.clearTimeout(timer);
  }, [packs, period, refresh]);

  const choosePeriod = (month: string) => {
    setPeriod(month);
    setPacks(null);
    setCardErrors({});
    router.replace(`/ministry-exports?period=${month}`, { scroll: false });
  };

  const periodPills = useMemo(() => {
    const all = new Set(periods);
    if (period) all.add(period);
    return [...all].sort().reverse().slice(0, 13);
  }, [periods, period]);

  const download = async (pack: Pack, file: PackFile) => {
    setBusy(`${pack.id}:${file}`);
    setCardErrors((current) => ({ ...current, [pack.id]: "" }));
    try {
      await downloadPackFile(pack.id, file);
    } catch (caught) {
      setCardErrors((current) => ({ ...current, [pack.id]: errorText(caught, "Could not open the file.") }));
    } finally {
      setBusy(null);
    }
  };

  const regenerate = async (pack: Pack) => {
    setBusy(`${pack.id}:regenerate`);
    try {
      await generatePacks(pack.period, [pack.scheme], newKey());
      await refresh(pack.period);
    } catch (caught) {
      setCardErrors((current) => ({ ...current, [pack.id]: errorText(caught, "Could not regenerate.") }));
    } finally {
      setBusy(null);
    }
  };

  const onGenerated = async (month: string, count: number) => {
    toast({ title: `${count} pack${count === 1 ? "" : "s"} queued for ${periodLabel(month)}`, tone: "success" });
    if (month === period) await refresh(month);
    else choosePeriod(month);
  };

  return (
    <div>
      <PageHeader
        title="Ministry exports"
        description="A monthly Excel workbook and PDF summary for each scheme."
        icon={<FileArchive className="h-5 w-5" aria-hidden />}
      />

      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}

      <section aria-labelledby="mx-packs" className="mb-8">
        <h2 id="mx-packs" className="mb-3 font-display text-lg font-bold text-ink-900">
          Packs
        </h2>
        {periodPills.length ? (
          // A segmented month picker, not an action row.
          <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label="Month">
            {periodPills.map((month) => (
              <button
                key={month}
                type="button"
                aria-pressed={month === period}
                onClick={() => choosePeriod(month)}
                className={
                  month === period
                    ? "rounded-full border border-purple-700 bg-purple-700 px-3 py-1.5 text-sm font-medium text-white"
                    : "rounded-full border border-line-200 bg-card px-3 py-1.5 text-sm text-ink-700 hover:border-purple-300 hover:bg-purple-50"
                }
              >
                {periodLabel(month)}
              </button>
            ))}
          </div>
        ) : null}

        {packs === null ? (
          <p className="text-sm text-ink-500">Loading…</p>
        ) : packs.length === 0 ? (
          <p className="panel px-4 py-6 text-center text-sm text-ink-500">
            No packs for {period ? periodLabel(period) : "this month"} yet.
          </p>
        ) : (
          <ul className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {packs.map((pack) => (
              <PackCard
                key={pack.id}
                pack={pack}
                busy={busy}
                error={cardErrors[pack.id] || null}
                onDownload={download}
                onRegenerate={regenerate}
              />
            ))}
          </ul>
        )}
      </section>

      <GenerateSection schemes={schemes} defaultPeriod={period} onGenerated={onGenerated} />

      <RecipientsSection schemes={schemes} />
    </div>
  );
}

function PackCard({
  pack,
  busy,
  error,
  onDownload,
  onRegenerate
}: {
  pack: Pack;
  busy: string | null;
  error: string | null;
  onDownload: (pack: Pack, file: PackFile) => void;
  onRegenerate: (pack: Pack) => void;
}) {
  const controls = packControls(pack);
  const counts = pack.counts;
  return (
    <li className="panel flex flex-col gap-3 p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="font-display text-base font-semibold text-ink-900">{pack.schemeLabel}</h3>
          <p className="text-xs text-ink-500">
            {pack.periodLabel}
            {pack.monthToDate ? " · month to date" : ""}
            {pack.revision > 1 ? ` · revision ${pack.revision}` : ""}
          </p>
        </div>
        <span className={`shrink-0 rounded-full border px-2.5 py-0.5 text-xs font-medium ${STATUS_TONE[pack.status]}`}>
          {STATUS_WORDS[pack.status]}
        </span>
      </div>

      {counts && pack.status !== "EMPTY" ? (
        <dl className="grid grid-cols-3 gap-2 text-center">
          {[
            { label: "Workshops", value: counts.workshops },
            { label: "Completed", value: counts.completed },
            { label: "Artisans", value: counts.participants }
          ].map((item) => (
            <div key={item.label} className="rounded-md bg-surface-50 px-2 py-2">
              <dt className="text-[11px] text-ink-500">{item.label}</dt>
              <dd className="font-display text-lg font-bold tabular-nums text-ink-900">{item.value}</dd>
            </div>
          ))}
        </dl>
      ) : null}

      <div className="text-xs leading-5 text-ink-500">
        {pack.snapshotAt ? <p>Snapshot {formatDateTime(pack.snapshotAt)}</p> : null}
        {counts?.truncated ? <p className="text-amber-800">Capped — the scheme has more workshops.</p> : null}
        {pack.files && pack.filesExpireOn ? <p>Files kept until {formatDate(pack.filesExpireOn)}</p> : null}
        {pack.status === "FAILED" && pack.error ? <p className="text-error-600">{pack.error}</p> : null}
        {pack.mail.sent || pack.mail.queued || pack.mail.failed ? (
          <p>
            E-mailed {pack.mail.sent}
            {pack.mail.queued ? ` · ${pack.mail.queued} sending` : ""}
            {pack.mail.failed ? ` · ${pack.mail.failed} failed` : ""}
          </p>
        ) : null}
      </div>

      {error ? (
        <p role="alert" className="text-sm text-error-600">
          {error}
        </p>
      ) : null}

      <ActionGroup className="mt-auto">
        {controls.download && pack.files ? (
          <>
            <button
              type="button"
              className="field-button"
              disabled={busy !== null}
              onClick={() => onDownload(pack, "xlsx")}
              title={sizeText(pack.files.xlsx.sizeBytes) || undefined}
            >
              <FileSpreadsheet className="h-4 w-4" aria-hidden />
              Excel
            </button>
            <button
              type="button"
              className="field-button-secondary"
              disabled={busy !== null}
              onClick={() => onDownload(pack, "pdf")}
              title={sizeText(pack.files.pdf.sizeBytes) || undefined}
            >
              <FileText className="h-4 w-4" aria-hidden />
              PDF
            </button>
          </>
        ) : null}
        {controls.regenerate ? (
          <button
            type="button"
            className="field-button-secondary"
            disabled={busy !== null}
            onClick={() => onRegenerate(pack)}
          >
            <RefreshCw className="h-4 w-4" aria-hidden />
            {busy === `${pack.id}:regenerate` ? "Queuing…" : "Regenerate"}
          </button>
        ) : null}
      </ActionGroup>
    </li>
  );
}

function GenerateSection({
  schemes,
  defaultPeriod,
  onGenerated
}: {
  schemes: Scheme[];
  defaultPeriod: string | null;
  onGenerated: (period: string, count: number) => Promise<void>;
}) {
  const choices = useMemo(() => recentPeriods(13), []);
  const [month, setMonth] = useState<string>("");
  const [selected, setSelected] = useState<string[]>([]);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const chosen = month || (defaultPeriod && choices.includes(defaultPeriod) ? defaultPeriod : choices[1]);

  const generate = async () => {
    setWorking(true);
    setError(null);
    try {
      const answer = await generatePacks(chosen, selected, newKey());
      await onGenerated(chosen, answer.items.length);
    } catch (caught) {
      setError(errorText(caught, "Could not generate the packs."));
    } finally {
      setWorking(false);
    }
  };

  return (
    <section aria-labelledby="mx-generate" className="panel mb-8 p-4 sm:p-5">
      <h2 id="mx-generate" className="mb-3 font-display text-lg font-bold text-ink-900">
        Generate
      </h2>
      <div className="grid gap-3 md:grid-cols-2">
        <div className="grid min-w-0 gap-1">
          <span className="field-label">Month</span>
          <SearchableSelect
            ariaLabel="Month"
            value={chosen}
            onChange={setMonth}
            options={choices.map((value, index) => ({
              value,
              label: periodLabel(value),
              hint: index === 0 ? "to date" : undefined
            }))}
          />
        </div>
        <div className="grid min-w-0 gap-1">
          <span className="field-label">Schemes</span>
          <SearchableMultiSelect
            ariaLabel="Schemes"
            values={selected}
            onChange={setSelected}
            placeholder={EVERY_SCHEME}
            confirmOnSelect={false}
            options={schemes.map((scheme) => ({
              value: scheme.key,
              label: scheme.label,
              hint: `${scheme.workshopCount} workshop${scheme.workshopCount === 1 ? "" : "s"}`
            }))}
          />
        </div>
      </div>
      {error ? (
        <p role="alert" className="mt-3 text-sm text-error-600">
          {error}
        </p>
      ) : null}
      <ActionGroup className="mt-4">
        <button type="button" className="field-button" disabled={working || !chosen} onClick={generate}>
          {working ? "Generating…" : "Generate"}
        </button>
      </ActionGroup>
    </section>
  );
}

function RecipientsSection({ schemes }: { schemes: Scheme[] }) {
  const [recipients, setRecipients] = useState<Recipient[] | null>(null);
  const [term, setTerm] = useState("");
  const [matches, setMatches] = useState<EligibleAccount[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState<string | null>(null);
  const searchRun = useRef(0);

  useEffect(() => {
    let cancelled = false;
    fetchRecipients()
      .then((answer) => {
        if (!cancelled) setRecipients(answer.recipients);
      })
      .catch((caught) => {
        if (!cancelled) setError(errorText(caught, "Could not load the recipients."));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const text = term.trim();
    if (!text) {
      setMatches([]);
      return;
    }
    const run = ++searchRun.current;
    const timer = window.setTimeout(() => {
      searchEligibleAccounts(text)
        .then((answer) => {
          if (run === searchRun.current) setMatches(answer.accounts);
        })
        .catch(() => {
          if (run === searchRun.current) setMatches([]);
        });
    }, 300);
    return () => window.clearTimeout(timer);
  }, [term]);

  const upsert = (row: Recipient) =>
    setRecipients((current) => {
      const rest = (current ?? []).filter((r) => r.userId !== row.userId);
      return [...rest, row].sort((a, b) => (a.name ?? "").localeCompare(b.name ?? ""));
    });

  const save = async (userId: string, nextSchemes: string[], active: boolean) => {
    setSaving(userId);
    setError(null);
    try {
      upsert(await saveRecipient(userId, nextSchemes, active));
    } catch (caught) {
      setError(errorText(caught, "Could not save."));
    } finally {
      setSaving(null);
    }
  };

  const remove = async (userId: string) => {
    setSaving(userId);
    setError(null);
    try {
      await removeRecipient(userId);
      setRecipients((current) => (current ?? []).filter((r) => r.userId !== userId));
    } catch (caught) {
      setError(errorText(caught, "Could not remove."));
    } finally {
      setSaving(null);
    }
  };

  const present = new Set((recipients ?? []).map((r) => r.userId));
  const offered = matches.filter((account) => !present.has(account.userId));
  const schemeOptions = schemes.map((scheme) => ({ value: scheme.key, label: scheme.label }));

  return (
    <section aria-labelledby="mx-recipients" className="panel p-4 sm:p-5">
      <h2 id="mx-recipients" className="font-display text-lg font-bold text-ink-900">
        Recipients
      </h2>
      <p className="mb-3 mt-1 text-sm text-ink-500">E-mailed when the month&apos;s packs are ready, on the 1st.</p>

      <SearchInput value={term} onChange={setTerm} placeholder="Add a Ministry Admin or admin" ariaLabel="Find an account" />
      {offered.length ? (
        <ul className="mt-2 divide-y divide-line-200 rounded-md border border-line-200">
          {offered.map((account) => (
            <li key={account.userId} className="flex items-center justify-between gap-3 px-3 py-2">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium text-ink-900">{account.name}</p>
                <p className="truncate text-xs text-ink-500">
                  {account.email} · {roleLabel(account.role)}
                </p>
              </div>
              <ActionGroup inline align="end">
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={saving !== null}
                  onClick={() => {
                    setTerm("");
                    void save(account.userId, [], true);
                  }}
                >
                  <UserPlus className="h-4 w-4" aria-hidden />
                  Add
                </button>
              </ActionGroup>
            </li>
          ))}
        </ul>
      ) : term.trim() && matches.length === 0 ? (
        <p className="mt-2 text-sm text-ink-500">No matching accounts.</p>
      ) : null}

      {error ? (
        <p role="alert" className="mt-3 text-sm text-error-600">
          {error}
        </p>
      ) : null}

      {recipients === null ? (
        <p className="mt-4 text-sm text-ink-500">Loading…</p>
      ) : recipients.length === 0 ? (
        <p className="mt-4 text-sm text-ink-500">Nobody receives the packs yet.</p>
      ) : (
        <ul className="mt-4 grid gap-3">
          {recipients.map((row) => (
            <li key={row.userId} className="rounded-md border border-line-200 p-3">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-ink-900">{row.name ?? "Account removed"}</p>
                  <p className="text-xs text-ink-500">
                    {row.email}
                    {row.role ? ` · ${roleLabel(row.role)}` : ""}
                  </p>
                  {!row.eligible ? (
                    <p className="mt-1 text-xs font-medium text-amber-800">
                      {row.ineligibleReason ?? "No longer eligible"} — gets no mail.
                    </p>
                  ) : null}
                </div>
                <ActionGroup inline align="end">
                  <button
                    type="button"
                    data-danger
                    className="field-danger inline-flex min-h-10 items-center gap-2 rounded-md px-3 py-2 text-sm"
                    disabled={saving !== null}
                    onClick={() => void remove(row.userId)}
                  >
                    <Trash2 className="h-4 w-4" aria-hidden />
                    Remove
                  </button>
                </ActionGroup>
              </div>
              <div className="mt-3 grid gap-3 md:grid-cols-2">
                <div className="grid min-w-0 gap-1">
                  <span className="field-label">Schemes</span>
                  <SearchableMultiSelect
                    ariaLabel={`Schemes for ${row.name ?? "this recipient"}`}
                    values={row.schemes}
                    onChange={(next) => void save(row.userId, next, row.active)}
                    placeholder={EVERY_SCHEME}
                    confirmOnSelect={false}
                    disabled={saving === row.userId}
                    options={schemeOptions}
                  />
                  <span className="sr-only">{schemesSummary(row.schemes, schemes)}</span>
                </div>
                <Toggle
                  label="Monthly e-mail"
                  checked={row.active}
                  busy={saving === row.userId}
                  busyLabel="Saving…"
                  onChange={(next) => void save(row.userId, row.schemes, next)}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
