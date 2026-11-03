"use client";

import { useState } from "react";
import { CheckCircle2, CloudOff, ShieldOff } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { Toggle } from "@/components/ui/Toggle";
import { formatDateTime } from "@/lib/format";
import {
  DW_CONSENT_QUESTION,
  DW_CONSENT_ROW_TITLE,
  DW_CONSENT_SWITCH_LABEL,
  dictationConsentSentence,
  recordDesignWorkshopDictationConsent,
  type DwConsentDecision
} from "@/lib/designWorkshops";
import { markDraftConsentSynced, recordDraftConsent } from "@/lib/designWorkshopStore";
import { aiLayerProblem } from "@/lib/aiLayers";
import { canRunDesignWorkshops } from "@/lib/permissions";

const NOT_YOURS_TO_RECORD =
  "Only this workshop's designer or an admin can record this.";

export type DictationConsentCardProps = {
  draftLocalId: string | null;
  remoteId: string | null;
  consent: string;
  recordedAt: string | null;
  recordedByName: string | null;
  synced: boolean;
  onRecorded: () => void;
  readOnlyReason?: string | null;
};

export function DictationConsentCard({
  draftLocalId,
  remoteId,
  consent,
  recordedAt,
  recordedByName,
  synced,
  onRecorded,
  readOnlyReason = null
}: DictationConsentCardProps) {
  const { user } = useAuth();
  const [busy, setBusy] = useState<DwConsentDecision | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const mayRecord = canRunDesignWorkshops(user) && !readOnlyReason;
  const token = (consent ?? "").trim().toUpperCase();
  const on = token !== "REFUSED";

  async function record(decision: DwConsentDecision) {
    setBusy(decision);
    setProblem(null);
    setOutcome(null);
    try {
      const stored = draftLocalId
        ? await recordDraftConsent(draftLocalId, decision, { id: user?.id ?? null, name: user?.name ?? null })
        : null;

      if (!remoteId) {
        setOutcome(
          stored
            ? "Saved on this device. It uploads with the workshop."
            : null
        );
        if (!stored) setProblem("Couldn't save the answer. Try again.");
        onRecorded();
        return;
      }

      try {
        await recordDesignWorkshopDictationConsent(remoteId, decision, {
          recordedAt: stored?.consent?.recordedAt ?? null
        });
        if (draftLocalId) await markDraftConsentSynced(draftLocalId);
        setOutcome("Recorded and saved online.");
      } catch (err) {
        setProblem(
          `${aiLayerProblem(err, "The answer couldn't be saved.")} ` +
            (stored
              ? "Saved on this device; AI tools unlock after it uploads."
              : "Couldn't save it. Try again.")
        );
      }
      onRecorded();
    } finally {
      setBusy(null);
    }
  }

  return (
    <section id="consent" className="panel mb-5 grid scroll-mt-28 gap-3 p-4" aria-labelledby="dw-consent-heading">
      <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
        <h2 id="dw-consent-heading" className="font-display text-sm font-bold text-ink-900">
          {DW_CONSENT_ROW_TITLE}
        </h2>
        <span
          className={
            on
              ? "inline-flex items-center gap-1 rounded-full border border-success-600/25 bg-success-100 px-2 py-0.5 text-xs font-medium text-success-600"
              : "inline-flex items-center gap-1 rounded-full border border-amber-500/40 bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
          }
        >
          {on ? <CheckCircle2 className="h-3 w-3" aria-hidden /> : <ShieldOff className="h-3 w-3" aria-hidden />}
          {token === "GRANTED" ? "They agreed" : token === "REFUSED" ? "Switched off" : "On by default"}
        </span>
      </div>

      {token !== "NOT_RECORDED" || !mayRecord ? (
        <p className={on ? "text-sm leading-6 text-ink-700" : "text-sm leading-6 text-amber-800 dark:text-amber-100"}>
          {dictationConsentSentence(consent)}
        </p>
      ) : null}

      {token !== "NOT_RECORDED" ? (
        <p className="text-xs leading-5 text-ink-500">
          {recordedByName ? `Recorded by ${recordedByName}` : "Recorded"}
          {recordedAt ? ` on ${formatDateTime(recordedAt)}` : ""}
          {recordedAt ? "" : " — the moment was not recorded"}.
        </p>
      ) : null}

      {!synced ? (
        <p className="flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          <CloudOff className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>
            Not uploaded yet. The server applies this answer after the next sync.
          </span>
        </p>
      ) : null}

      {mayRecord ? (
        <>
          <Toggle
            label={DW_CONSENT_SWITCH_LABEL}
            description={DW_CONSENT_QUESTION}
            checked={on}
            busy={busy !== null}
            busyLabel="Saving…"
            onChange={(next) => void record(next ? "GRANTED" : "REFUSED")}
          />
          <p className="text-xs leading-5 text-ink-500">
            Switching off also stops pending transcriptions. Recordings are kept.
          </p>
        </>
      ) : (
        <p className="text-xs leading-5 text-ink-500">{readOnlyReason ?? NOT_YOURS_TO_RECORD}</p>
      )}

      {outcome ? (
        <p role="status" className="text-xs leading-5 text-ink-500">
          {outcome}
        </p>
      ) : null}
      {problem ? (
        <p role="alert" className="text-xs leading-5 text-error-600">
          {problem}
        </p>
      ) : null}
    </section>
  );
}
