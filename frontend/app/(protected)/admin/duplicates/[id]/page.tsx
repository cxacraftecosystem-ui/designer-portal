"use client";

/**
 * /admin/duplicates/[id] — one likely-duplicate pair.
 *
 * Within this portal: choose the record to keep and a side for each field, then Merge. Every history
 * entry, file, workshop link and audit row moves to the kept record, and the merge can be undone for
 * 30 days. With the Field Repository: the two records are never merged — Link them, or say they are
 * not the same person. Admin only, web only (the `/admin` route guard and `require_admin`).
 */

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { CopyCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { MergeFieldTable } from "@/components/duplicates/MergeFieldTable";
import { PageHeader } from "@/components/PageHeader";
import { readableError } from "@/components/review/reviewErrors";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { useToast } from "@/components/ui/Toast";
import { ApiError } from "@/lib/api";
import {
  BAND_LABEL,
  choicesPayload,
  defaultChoices,
  dismissPair,
  getPair,
  linkPair,
  mergeInterviews,
  mergePair,
  reopenPair,
  undoMerge,
  type MergeBlocker,
  type MergeChoice,
  type MergeResult,
  type PairPreview,
  type RelatedCounts
} from "@/lib/duplicates";
import { isAdmin } from "@/lib/permissions";

const RELATED_LABELS: [keyof RelatedCounts, string][] = [
  ["media", "Files"],
  ["products", "Products"],
  ["tools", "Tools"],
  ["workshops", "Workshops"],
  ["designWorkshops", "Design workshops"],
  ["interviews", "Interviews"],
  ["comments", "Comments"],
  ["revisions", "History"],
  ["reviewLogs", "Reviews"]
];

function blockersOf(error: unknown): MergeBlocker[] | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const detail = (error.payload as { detail?: { code?: string; blockers?: MergeBlocker[] } } | null)?.detail;
  return detail?.code === "merge_blocked" && Array.isArray(detail.blockers) ? detail.blockers : null;
}

export default function DuplicatePairPage() {
  const { id } = useParams<{ id: string }>();
  const { user, loading: authLoading } = useAuth();
  const permitted = isAdmin(user);
  const { toast } = useToast();
  const [pair, setPair] = useState<PairPreview | null>(null);
  const [survivor, setSurvivor] = useState<"A" | "B">("A");
  const [picks, setPicks] = useState<Record<string, MergeChoice>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [merged, setMerged] = useState<MergeResult | null>(null);
  const [consent, setConsent] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      const preview = await getPair(id);
      setPair(preview);
      const keep = preview.defaultSurvivor ?? "A";
      setSurvivor(keep);
      setPicks(defaultChoices(preview.fields ?? [], keep));
    } catch (err) {
      setError(readableError(err, "Couldn't load this pair."));
    }
  }, [id]);

  useEffect(() => {
    if (authLoading || !permitted) return;
    void load();
  }, [authLoading, permitted, load]);

  const blockers = useMemo(() => pair?.blockers ?? [], [pair]);

  if (!authLoading && !permitted) {
    return (
      <>
        <PageHeader title="Duplicate artisans" icon={<CopyCheck className="h-5 w-5" aria-hidden />} />
        <RestrictedPanel title="Admin access required" body="Admins only." />
      </>
    );
  }

  function chooseSurvivor(next: "A" | "B") {
    setSurvivor(next);
    if (pair?.fields) setPicks(defaultChoices(pair.fields, next));
  }

  async function act(fn: () => Promise<void>, fallback: string) {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (err) {
      const refused = blockersOf(err);
      if (refused && pair) setPair({ ...pair, blockers: refused });
      else setError(readableError(err, fallback));
    } finally {
      setBusy(false);
    }
  }

  const doMerge = () =>
    act(async () => {
      if (!pair?.b) return;
      const result = await mergePair(pair.id, {
        survivor,
        choices: choicesPayload(pair.fields ?? [], picks),
        expectedUpdatedAt: { a: pair.a.updatedAt, b: pair.b.updatedAt }
      });
      setMerged(result);
    }, "Couldn't merge.");

  const doUndo = () =>
    act(async () => {
      if (!merged) return;
      await undoMerge(merged.merge.id);
      setMerged(null);
      toast({ tone: "success", title: "Merge undone" });
      await load();
    }, "Couldn't undo the merge.");

  const header = (
    <PageHeader
      title={pair ? (pair.kind === "CROSS_PORTAL" ? "Field Repository match" : "Likely duplicate") : "Loading…"}
      description={pair ? `${BAND_LABEL[pair.band]} · ${Math.round(pair.score * 100)}%` : undefined}
      icon={<CopyCheck className="h-5 w-5" aria-hidden />}
    />
  );

  if (merged) {
    return (
      <>
        {header}
        <section className="panel grid gap-3 p-6 text-center">
          <p className="text-base font-semibold text-ink-900">Merged. Undo within 30 days.</p>
          {merged.warnings.map((w) => (
            <p key={w.code + w.message} className="text-sm text-amber-800">{w.message}</p>
          ))}
          <ActionGroup>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={doUndo}>
              {busy ? "Undoing…" : "Undo"}
            </button>
            <Link className="field-button" href={`/artisans/${merged.artisan.id}`}>
              Open {merged.artisan.name}
            </Link>
          </ActionGroup>
        </section>
      </>
    );
  }

  return (
    <>
      {header}
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {pair === null ? (
        <p className="text-sm text-ink-500">Loading…</p>
      ) : (
        <div className="grid gap-4">
          <section className="panel grid gap-2 p-4">
            <ul className="flex flex-wrap gap-1.5" aria-label="Why">
              {pair.reasons.map((reason) => (
                <li key={reason.code} className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-xs text-ink-700">
                  {reason.label}
                </li>
              ))}
            </ul>
            {pair.status !== "OPEN" ? (
              <p className="text-sm text-ink-700">
                {pair.status === "LINKED" ? "Linked." : "Marked as different people."}
              </p>
            ) : null}
          </section>

          {pair.kind === "CROSS_PORTAL" ? (
            <CrossPortalPair
              pair={pair}
              consent={consent}
              onConsent={setConsent}
              busy={busy}
              onLink={() => act(async () => { await linkPair(pair.id); toast({ tone: "success", title: "Linked" }); await load(); }, "Couldn't link.")}
              onDismiss={() => act(async () => { await dismissPair(pair.id); await load(); }, "Couldn't save.")}
              onReopen={() => act(async () => { await reopenPair(pair.id); await load(); }, "Couldn't save.")}
            />
          ) : pair.b ? (
            <>
              <section className="panel grid gap-3 p-4">
                <h2 className="font-display text-base font-semibold text-ink-900">Keep</h2>
                <div className="grid gap-3 sm:grid-cols-2" role="radiogroup" aria-label="Record to keep">
                  {(["A", "B"] as const).map((side) => {
                    const record = side === "A" ? pair.a : pair.b!;
                    const counts = pair.related?.[side === "A" ? "a" : "b"];
                    return (
                      <label
                        key={side}
                        className={`flex cursor-pointer gap-3 rounded-md border p-3 ${survivor === side ? "border-purple-700 bg-purple-50 dark:bg-purple-950/30" : "border-line-200 bg-card"}`}
                      >
                        <input
                          type="radio"
                          name="survivor"
                          checked={survivor === side}
                          onChange={() => chooseSurvivor(side)}
                          className="mt-1 h-4 w-4 accent-purple-700"
                        />
                        <span className="min-w-0">
                          <span className="block text-sm font-semibold text-ink-900">
                            {side} · {record.name}
                          </span>
                          <span className="block text-xs text-ink-500">{[record.place, record.craft].filter(Boolean).join(" · ")}</span>
                          {counts ? (
                            <span className="mt-1 block text-xs text-ink-500">
                              {RELATED_LABELS.filter(([key]) => counts[key]).map(([key, label]) => `${label} ${counts[key]}`).join(" · ") || "Nothing linked"}
                            </span>
                          ) : null}
                          <Link href={`/artisans/${record.id}`} target="_blank" className="mt-1 inline-block text-xs font-semibold text-purple-700 dark:text-purple-300">
                            Open
                          </Link>
                        </span>
                      </label>
                    );
                  })}
                </div>
              </section>

              {blockers.length ? (
                <section className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700" role="alert">
                  <ul className="grid gap-2">
                    {blockers.map((blocker) => (
                      <li key={blocker.code + blocker.message} className="flex flex-wrap items-center justify-between gap-2">
                        <span>{blocker.message}</span>
                        {blocker.action?.kind === "merge_interviews" ? (
                          <ActionGroup inline align="end">
                            <button
                              type="button"
                              className="field-button-secondary"
                              disabled={busy}
                              onClick={() =>
                                act(async () => {
                                  const action = blocker.action!;
                                  await mergeInterviews(action.sourceId, action.targetId, action.duplicatePairId);
                                  await load();
                                }, "Couldn't merge the interviews.")
                              }
                            >
                              Merge interviews
                            </button>
                          </ActionGroup>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                </section>
              ) : null}
              {pair.warnings?.length ? (
                <section className="rounded-md border border-amber-500 bg-amber-100 p-4 text-sm text-amber-800">
                  {pair.warnings.map((warning) => (
                    <p key={warning.code + warning.message}>{warning.message}</p>
                  ))}
                </section>
              ) : null}

              <section className="panel p-2">
                <MergeFieldTable
                  fields={pair.fields ?? []}
                  picks={picks}
                  disabled={busy}
                  onPick={(key, choice) => setPicks((current) => ({ ...current, [key]: choice }))}
                />
              </section>

              <ActionGroup>
                {pair.status === "OPEN" ? (
                  <button type="button" className="field-button-secondary" disabled={busy} onClick={() => act(async () => { await dismissPair(pair.id); await load(); }, "Couldn't save.")}>
                    Not the same
                  </button>
                ) : (
                  <button type="button" className="field-button-secondary" disabled={busy} onClick={() => act(async () => { await reopenPair(pair.id); await load(); }, "Couldn't save.")}>
                    Reopen
                  </button>
                )}
                <button type="button" className="field-button" disabled={busy || blockers.length > 0} onClick={doMerge}>
                  {busy ? "Merging…" : "Merge"}
                </button>
              </ActionGroup>
            </>
          ) : null}
        </div>
      )}
    </>
  );
}

function CrossPortalPair({
  pair,
  consent,
  onConsent,
  busy,
  onLink,
  onDismiss,
  onReopen
}: {
  pair: PairPreview;
  consent: boolean;
  onConsent: (value: boolean) => void;
  busy: boolean;
  onLink: () => void;
  onDismiss: () => void;
  onReopen: () => void;
}) {
  const remote = pair.remote && !("unavailable" in pair.remote) ? pair.remote : null;
  const field = (key: string) => {
    const value = remote?.fields?.[key];
    return value === null || value === undefined || value === "" ? null : String(value);
  };
  const rows: [string, string | null, string | null][] = [
    ["Name", pair.a.name, field("name")],
    ["Place", pair.a.place ?? null, field("place") ?? ([remote?.statedAddress?.village, remote?.statedAddress?.district].filter(Boolean).join(", ") || null)],
    ["Craft", pair.a.craft ?? null, remote?.craft?.name ?? null],
    ["Gender", null, field("gender")],
    ["Date of birth", null, field("dateOfBirth")?.slice(0, 10) ?? null]
  ];
  return (
    <>
      <section className="panel overflow-x-auto p-2">
        <table className="w-full min-w-[32rem] text-left text-sm">
          <thead className="text-xs uppercase tracking-wide text-ink-500">
            <tr className="border-b border-line-200">
              <th className="px-3 py-2 font-medium">Field</th>
              <th className="px-3 py-2 font-medium">This portal</th>
              <th className="px-3 py-2 font-medium">Field Repository</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(([label, here, there]) => (
              <tr key={label} className="border-b border-line-200 last:border-0">
                <th scope="row" className="px-3 py-2 font-medium text-ink-900">{label}</th>
                <td className="px-3 py-2 text-ink-700">{here ?? <span className="text-ink-300">—</span>}</td>
                <td className="px-3 py-2 text-ink-700">{there ?? <span className="text-ink-300">—</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {!remote ? <p className="px-3 py-2 text-sm text-ink-500">The Field Repository record can&apos;t be shown right now.</p> : null}
      </section>
      {pair.status === "OPEN" ? (
        <section className="panel grid gap-3 p-4">
          {/* Consent: an agreement, not a setting, so a checkbox (form primitives rule). */}
          <label className="flex items-start gap-2 text-sm text-ink-700">
            <input type="checkbox" className="mt-0.5 h-4 w-4 accent-purple-700" checked={consent} onChange={(e) => onConsent(e.target.checked)} />
            The artisan agreed to share their details between the portals.
          </label>
          <ActionGroup>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={onDismiss}>
              Not the same
            </button>
            <button type="button" className="field-button" disabled={busy || !consent || !remote} onClick={onLink}>
              Link
            </button>
          </ActionGroup>
        </section>
      ) : (
        <ActionGroup>
          <button type="button" className="field-button-secondary" disabled={busy} onClick={onReopen}>
            Reopen
          </button>
        </ActionGroup>
      )}
    </>
  );
}
