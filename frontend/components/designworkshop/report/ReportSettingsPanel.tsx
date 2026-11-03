"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { CheckCircle2, ChevronDown, Mic, Settings2 } from "lucide-react";

import { EntityForm } from "@/components/designworkshop/EntityForm";
import {
  saveDesignWorkshopStage,
  type DwEntity,
  type DwEntryData,
  type DwRegistry,
  type DwTranscriptList,
  type DwValue
} from "@/lib/designWorkshops";
import { isUnreachable } from "@/lib/offline";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { TranscriptPreview } from "@/components/transcript/TranscriptPreview";

export const REPORT_STAGE_KEY = "REPORT_GENERATION";
export const REPORT_SETTINGS_ENTITY = "reportSettings";

export function reportSettingsEntity(registry: DwRegistry | null): DwEntity | null {
  const stage = registry?.stages.find((entry) => entry.key === REPORT_STAGE_KEY);
  return stage?.entities.find((entity) => entity.key === REPORT_SETTINGS_ENTITY) ?? null;
}

export function wantsTranscripts(settings: DwEntryData | undefined): boolean {
  return Boolean(settings?.includeTranscripts);
}

export function settingText(settings: DwEntryData | undefined, key: string): string {
  const value = settings?.[key];
  return typeof value === "string" ? value.trim() : "";
}

export function ReportSettingsPanel({
  workshopId,
  registry,
  settings,
  onSaved,
  online,
  draftPending,
  readOnlyReason = null,
  onDraftChange
}: {
  workshopId: string;
  registry: DwRegistry | null;
  settings: DwEntryData;
  onSaved: () => void;
  online: boolean;
  draftPending: boolean;
  readOnlyReason?: string | null;
  onDraftChange?: (values: DwEntryData | null) => void;
}) {
  const entity = useMemo(() => reportSettingsEntity(registry), [registry]);
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<DwEntryData>(settings);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setValues(settings);
  }, [settings]);

  const dirty = useMemo(() => JSON.stringify(values) !== JSON.stringify(settings), [values, settings]);

  useEffect(() => {
    onDraftChange?.(dirty ? values : null);
  }, [dirty, values, onDraftChange]);

  const change = useCallback((key: string, value: DwValue) => {
    setValues((current) => ({ ...current, [key]: value }));
    setSaved(false);
  }, []);

  const patch = useCallback((next: Record<string, DwValue>) => {
    setValues((current) => ({ ...current, ...next }));
    setSaved(false);
  }, []);

  async function save() {
    if (!entity || readOnlyReason) return;
    setSaving(true);
    setError(null);
    try {
      const payload: DwEntryData = {};
      for (const field of entity.fields) {
        if (field.deprecated) continue;
        payload[field.key] = values[field.key] ?? null;
      }
      await saveDesignWorkshopStage(workshopId, REPORT_STAGE_KEY, {
        entries: [{ entityKey: REPORT_SETTINGS_ENTITY, data: payload }]
      });
      setSaved(true);
      onSaved();
    } catch (err) {
      setError(
        isUnreachable(err)
          ? "Needs a connection to save. Your entries are kept."
          : err instanceof Error
            ? err.message
            : "Unable to save the report settings"
      );
    } finally {
      setSaving(false);
    }
  }

  if (!entity) {
    return null;
  }

  return (
    <section className="mb-5 grid gap-3">
      <div className="panel grid gap-3 p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex min-w-0 items-start gap-3">
            <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600 dark:text-purple-300">
              <Settings2 className="h-4 w-4" aria-hidden />
            </span>
            <div className="min-w-0">
              <h2 className="font-display text-base font-bold text-ink-900">Report settings</h2>
              <p className="mt-1 text-sm leading-6 text-ink-muted">
                Saved on stage 20 and used for every report.
              </p>
            </div>
          </div>
          <ActionGroup className="shrink-0">
            <Link href={`/design-workshops/${workshopId}/stages/${REPORT_STAGE_KEY}`} className="field-button-secondary">
              Open stage 20
            </Link>
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => setOpen((current) => !current)}
              aria-expanded={open}
            >
              <ChevronDown className={`h-4 w-4 transition ${open ? "rotate-180" : ""}`} aria-hidden />
              {open ? "Hide settings" : "Change settings"}
            </button>
          </ActionGroup>
        </div>

        {draftPending ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            Stage 20 has unsent changes. Let them upload before saving here.
          </p>
        ) : null}

        {error ? (
          <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700">{error}</p>
        ) : null}
      </div>

      {open ? (
        <>
          <EntityForm
            entity={entity}
            data={values}
            onChange={change}
            onPatch={patch}
            workshopId={workshopId}
            stageKey={REPORT_STAGE_KEY}
            disabled={saving || Boolean(readOnlyReason)}
          />
          <div className="panel flex flex-wrap items-center gap-3 p-4">
            <button
              type="button"
              className="field-button"
              onClick={save}
              disabled={saving || !dirty || !online || draftPending || Boolean(readOnlyReason)}
            >
              {saving ? "Saving…" : "Save settings"}
            </button>
            {readOnlyReason ? (
              <span className="text-sm text-ink-muted">{readOnlyReason}</span>
            ) : !online ? (
              <span className="text-sm text-ink-muted">You&rsquo;re offline — connect to save these.</span>
            ) : !dirty ? (
              <span className="text-sm text-ink-muted">
                {saved ? (
                  <span className="inline-flex items-center gap-1.5 text-success-600 dark:text-emerald-400">
                    <CheckCircle2 className="h-4 w-4" aria-hidden />
                    Saved.
                  </span>
                ) : (
                  "Nothing changed yet."
                )}
              </span>
            ) : (
              <span className="text-sm text-ink-muted">
                Unsaved.
              </span>
            )}
          </div>
        </>
      ) : null}
    </section>
  );
}

export function TranscriptAnnexurePanel({ transcripts }: { transcripts: DwTranscriptList | null }) {
  if (!transcripts) return null;

  const pending = transcripts.items.filter((item) => !item.includedInReport);
  const minutes = transcripts.totalDurationSeconds ? Math.round(transcripts.totalDurationSeconds / 60) : null;

  return (
    <section className="panel mb-5 grid gap-3 p-4">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600 dark:text-purple-300">
          <Mic className="h-4 w-4" aria-hidden />
        </span>
        <div className="min-w-0">
          <h2 className="font-display text-base font-bold text-ink-900">The transcript annexure</h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            {transcripts.withTranscript} of {transcripts.total} recording{transcripts.total === 1 ? "" : "s"} will be appended
            in full
            {minutes ? `, about ${minutes} minute${minutes === 1 ? "" : "s"} of audio` : ""}. This is what the toggle adds to
            the document — read it here rather than in the generated file.
          </p>
        </div>
      </div>

      {transcripts.items.length ? (
        <ul className="grid gap-2">
          {transcripts.items.map((item) => (
            <li
              key={item.mediaId}
              className={`rounded-md border px-3 py-2 ${
                item.includedInReport ? "border-line-200 bg-surface-50" : "border-amber-500/30 bg-amber-100"
              }`}
            >
              <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                <span className="text-sm font-medium text-ink-900">
                  {item.stageNumber}. {item.label}
                </span>
                <span className={`text-xs ${item.includedInReport ? "text-ink-500" : "text-amber-800 dark:text-amber-100"}`}>
                  {item.includedInReport
                    ? `${item.durationText} · ${item.wordCount} words${item.speakerCount > 1 ? ` · ${item.speakerCount} voices` : ""}`
                    : `Not in the annexure — transcription is ${item.status.toLowerCase() || "not finished"}`}
                </span>
              </div>
              <TranscriptPreview text={item.firstLine} lines={2} className="mt-1" />
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-ink-700">
          No recordings, so no annexure.
        </p>
      )}

      {pending.length ? (
        <p className="text-sm leading-6 text-ink-muted">
          {pending.length} recording{pending.length === 1 ? " is" : "s are"} still transcribing and would be missing now.
        </p>
      ) : null}
    </section>
  );
}
