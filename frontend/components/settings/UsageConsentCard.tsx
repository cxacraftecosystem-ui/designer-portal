"use client";

import { useCallback, useEffect, useState } from "react";
import { History, ShieldCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { UsageConsentDisclosure } from "@/components/settings/UsageConsentNotice";
import { useToast } from "@/components/ui/Toast";
import {
  consentBasisText,
  consentMoment,
  durationText,
  loadMyUsageConsent,
  loadMyUsageTrail,
  recordUsageConsent,
  withdrawUsageConsent,
  daysAgoIso,
  nowIso,
  type MyUsageConsent,
  type MyUsageTrail,
  type UsageConsentDecision,
  type UsageConsentResult
} from "@/lib/usage";
import { ActionGroup } from "@/components/ui/ActionGroup";

function AnswerChip({ state }: { state: string }) {
  const copy: Record<string, { label: string; className: string }> = {
    GRANTED: { label: "Agreed", className: "border-success-600/30 bg-success-100 text-success-600" },
    REFUSED: { label: "Declined", className: "border-line-200 bg-surface-50 text-ink-700" },
    NOT_RECORDED: { label: "Not answered", className: "border-amber-500/40 bg-amber-100 text-amber-900" }
  };
  const chosen = copy[state] ?? { label: state, className: "border-line-200 bg-surface-50 text-ink-700" };
  return (
    <span className={`rounded-full border px-2.5 py-0.5 text-xs font-semibold ${chosen.className}`}>{chosen.label}</span>
  );
}

function DecisionRow({ row }: { row: UsageConsentDecision }) {
  return (
    <li className="grid gap-0.5 border-b border-line-200 py-2 last:border-0">
      <div className="flex flex-wrap items-center gap-2">
        <AnswerChip state={row.decision ?? "NOT_RECORDED"} />
        <span className="text-sm text-ink-900">{consentMoment(row.createdAt)}</span>
        {row.recordedAt && row.recordedAt !== row.createdAt ? (
          <span className="text-xs text-ink-500">answered on the device at {consentMoment(row.recordedAt)}</span>
        ) : null}
      </div>
      <p className="text-xs leading-5 text-ink-500">
        {consentBasisText(row.basis)}
        {row.noticeVersion ? ` · notice ${row.noticeVersion}` : ""}
      </p>
      {row.note ? <p className="text-xs leading-5 text-ink-700">{row.note}</p> : null}
    </li>
  );
}

function MyTrail() {
  const [trail, setTrail] = useState<MyUsageTrail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    setBusy(true);
    setError(null);
    try {
      setTrail(await loadMyUsageTrail({ from: daysAgoIso(7), to: nowIso() }));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't load your record. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h3 className="text-sm font-medium text-ink-900">What has been recorded about you</h3>
          <p className="text-xs leading-5 text-ink-500">
            The last seven days, newest first.
          </p>
        </div>
        <button type="button" className="field-button-secondary" onClick={load} disabled={busy}>
          {busy ? "Reading…" : trail ? "Refresh" : "Show my record"}
        </button>
      </div>

      {error ? <p className="text-sm text-error-600">{error}</p> : null}

      {trail ? (
        <>
          {trail.events.length === 0 ? (
            <p className="rounded-md border border-line-200 bg-card px-3 py-2 text-sm leading-6 text-ink-700">
              Nothing is recorded against your account in this window. {trail.gate.reason}
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[520px] text-left text-sm">
                <thead className="border-b border-line-200 text-xs uppercase tracking-wide text-ink-500">
                  <tr>
                    <th className="py-2 pr-3 font-medium">When</th>
                    <th className="py-2 pr-3 font-medium">Screen</th>
                    <th className="py-2 pr-3 font-medium">Method</th>
                    <th className="py-2 pr-3 font-medium">Status</th>
                    <th className="py-2 pr-3 font-medium">Took</th>
                    <th className="py-2 font-medium">App</th>
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
                      <td className="py-2 text-ink-700">{event.clientApp}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="text-xs leading-5 text-ink-500">
            Showing {trail.events.length} of at most {trail.maxRows} per page, over{" "}
            {trail.window.days} day{trail.window.days === 1 ? "" : "s"}.
          </p>
          <ul className="grid list-disc gap-1 pl-5 text-xs leading-5 text-ink-500">
            {trail.notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  );
}

export function UsageConsentCard() {
  const { refreshMe } = useAuth();
  const { toast } = useToast();
  const confirm = useConfirm();

  const [state, setState] = useState<MyUsageConsent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setState(await loadMyUsageConsent());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't load your recording answer. Check your connection and try again.");
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    loadMyUsageConsent()
      .then((result) => {
        if (!cancelled) setState(result);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Couldn't load your recording answer. Check your connection and try again.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const applyResult = useCallback(
    async (result: UsageConsentResult) => {
      setState((current) =>
        current ? { ...current, consent: result.consent, gate: result.gate, decisions: result.decisions } : current
      );
      await refreshMe();
    },
    [refreshMe]
  );

  async function agree() {
    if (!state) return;
    setBusy(true);
    setError(null);
    const recordedAt = new Date().toISOString();
    try {
      const result = await recordUsageConsent({
        decision: "GRANTED",
        basis: "OFFERED_IN_SETTINGS",
        noticeVersion: state.notice.version,
        recordedAt
      });
      await applyResult(result);
      toast({ title: "Recorded", description: "Your use of the platform is now recorded against your account.", tone: "success" });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Your answer couldn't be saved. Try again.");
    } finally {
      setBusy(false);
    }
  }

  async function withdraw() {
    if (!state) return;
    const ok = await confirm({
      title: "Stop recording, and delete what is stored?",
      body: "Recording stops straight away, and everything already recorded about you is deleted.",
      note: "It does not sign you out or stop you using anything. Your earlier answers stay listed below.",
      confirmLabel: "Withdraw",
      tone: "danger"
    });
    if (!ok) return;

    setBusy(true);
    setError(null);
    try {
      const result = await withdrawUsageConsent({
        noticeVersion: state.notice.version,
        recordedAt: new Date().toISOString()
      });
      await applyResult(result);
      toast({
        title: "Withdrawn",
        description: result.withdrawal?.explanation ?? "Recording has stopped for this account.",
        tone: result.withdrawal && !result.withdrawal.storedDeleteRan ? "error" : "success",
        duration: result.withdrawal && !result.withdrawal.storedDeleteRan ? 0 : undefined
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't withdraw. Try again.");
    } finally {
      setBusy(false);
    }
  }

  if (error && !state) {
    return (
      <section className="panel p-5">
        <h2 className="font-display font-bold text-ink-900">Recording how you use this platform</h2>
        <p className="mt-2 text-sm text-error-600">{error}</p>
        <button type="button" className="field-button-secondary mt-3" onClick={() => void load()}>
          Try again
        </button>
      </section>
    );
  }

  if (!state) {
    return (
      <section className="panel p-5 text-sm text-ink-500">Reading your recording answer…</section>
    );
  }

  const granted = state.consent.state === "GRANTED";

  const showAgree = state.gate.required || !granted;

  return (
    <section className="panel p-5">
      <div className="flex items-start gap-2.5">
        <ShieldCheck className="mt-0.5 h-5 w-5 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="font-display font-bold text-ink-900">{state.notice.title}</h2>
            <AnswerChip state={state.consent.state} />
          </div>
          <p className="mt-1 text-sm leading-6 text-ink-700">{state.gate.reason}</p>
        </div>
      </div>

      <dl className="mt-4 grid gap-2 text-sm sm:grid-cols-3">
        <div>
          <dt className="field-label">Answered</dt>
          <dd className="mt-0.5 text-ink-900">{consentMoment(state.consent.at)}</dd>
        </div>
        <div>
          <dt className="field-label">Circumstance</dt>
          <dd className="mt-0.5 text-ink-900">{consentBasisText(state.consent.basis)}</dd>
        </div>
        <div>
          <dt className="field-label">Notice you answered</dt>
          <dd className="mt-0.5 font-mono text-xs text-ink-900">
            {state.consent.version ?? "—"}
            {state.consent.version && state.consent.version !== state.gate.noticeVersion ? (
              <span className="ml-1 font-sans text-ink-500">(current is {state.gate.noticeVersion})</span>
            ) : null}
          </dd>
        </div>
      </dl>

      <div className="mt-4">
        <UsageConsentDisclosure notice={state.notice} />
      </div>

      <div className="mt-4 flex flex-wrap gap-2">
        <ActionGroup className="max-sm:w-full">{showAgree ? (
          <button type="button" className="field-button" onClick={agree} disabled={busy}>
            {busy ? "Working…" : granted ? "Agree to the updated notice" : "Agree to be recorded"}
          </button>
        ) : null}
        {granted ? (
          <button
            type="button"
            data-danger className="field-danger disabled:cursor-not-allowed disabled:opacity-60"
            onClick={withdraw}
            disabled={busy}
          >
            {busy ? "Working…" : "Withdraw, and delete what is stored"}
          </button>
        ) : null}
</ActionGroup>
        <p className="w-full text-xs leading-5 text-ink-500">{state.notice.withdrawal.costsNothing}</p>
      </div>

      <div className="mt-4 grid gap-3">
        <MyTrail />

        <div className="rounded-md border border-line-200 p-3">
          <div className="flex items-center gap-2">
            <History className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
            <h3 className="text-sm font-medium text-ink-900">Your decisions</h3>
          </div>
          {state.decisions.length === 0 ? (
            <p className="mt-1 text-sm text-ink-500">
              You haven&apos;t answered yet.
            </p>
          ) : (
            <ul className="mt-1">
              {state.decisions.map((row) => (
                <DecisionRow key={row.id ?? `${row.createdAt}-${row.decision}`} row={row} />
              ))}
            </ul>
          )}
        </div>
      </div>

      {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
    </section>
  );
}
