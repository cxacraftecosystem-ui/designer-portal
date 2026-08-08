"use client";

/**
 * Stage 20's report settings, edited where the report is read.
 *
 * WHY THE SETTINGS LIVE ON THE REPORT SCREEN AS WELL AS ON THEIR OWN STAGE. Every one of these
 * answers is a question about the document beside it — does it carry a contents page, are the
 * headings numbered, does the transcript annexure go in — and there is exactly one place where
 * those questions can be answered with the evidence in view. Answering them on stage 20 and then
 * navigating here to see what happened is a round trip per decision, and the decision that gets
 * skipped is the transcript toggle, which is the one that changes the document most.
 *
 * IT IS THE SAME FORM, NOT A COPY OF IT. The fields are rendered by walking
 * `registry.stages[REPORT_GENERATION].entities.reportSettings` through {@link EntityForm}, which is
 * the one component that draws any registry entity. There is no per-stage form code anywhere in
 * this feature and this file adds none: add a field to `stage_definitions.STAGE_20` and it appears
 * here, with its help text, its tier and its enum options, without a line changing on this side.
 * A hand-built panel of "the settings that matter" would have frozen the field list on the day it
 * was written, and the next toggle the backend grew would be invisible in the one screen it
 * belongs on.
 *
 * WHAT THE SERVER DOES WITH THEM, HONESTLY. `wants_transcripts` reads `includeTranscripts` off the
 * saved stage-20 entry, so that toggle governs the preview and the file with no help from this
 * page. `pageSize`, `headerText` and `footerText` are NOT read from the entry by the report
 * pipeline — they reach a file only through `ReportGenerateIn`, which is why the page sends them
 * on the download. Everything else is a stored answer the templates will grow into. Saying which
 * is which is the caller's job and the page's copy does it; what must never happen is a control
 * that visibly moves and changes nothing anybody can see.
 *
 * A LOCAL DRAFT WINS, LATER. The stage editor banks unsynced edits in IndexedDB and pushes them
 * when there is a connection, so a save made here while stage 20 is dirty on this device would be
 * overwritten by that push — silently, minutes later, with the designer looking at the value they
 * chose. The panel refuses to save in that state and points at the stage instead, which is the
 * only place that can reconcile the two.
 */

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

/** The stage and the entity these settings live in. Both are backend keys, not display strings. */
export const REPORT_STAGE_KEY = "REPORT_GENERATION";
export const REPORT_SETTINGS_ENTITY = "reportSettings";

/** The stage-20 settings entity out of the registry, or null on a server that has not got one. */
export function reportSettingsEntity(registry: DwRegistry | null): DwEntity | null {
  const stage = registry?.stages.find((entry) => entry.key === REPORT_STAGE_KEY);
  return stage?.entities.find((entity) => entity.key === REPORT_SETTINGS_ENTITY) ?? null;
}

/** True when the saved settings ask for the transcript annexure — `wants_transcripts`, exactly. */
export function wantsTranscripts(settings: DwEntryData | undefined): boolean {
  return Boolean(settings?.includeTranscripts);
}

/** A settings value as a string, for the two the page forwards on the download. */
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
  draftPending
}: {
  workshopId: string;
  registry: DwRegistry | null;
  /** The SERVER's copy. Re-seeding on a new object identity is what makes a save stick. */
  settings: DwEntryData;
  /** Fired after the write lands. The caller re-reads the workshop and rebuilds the preview. */
  onSaved: () => void;
  online: boolean;
  /** Stage 20 has edits on this device that have not reached the server yet. */
  draftPending: boolean;
}) {
  const entity = useMemo(() => reportSettingsEntity(registry), [registry]);
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<DwEntryData>(settings);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * Re-seed when the server's copy changes identity.
   *
   * The page re-reads the workshop after every successful save, so this runs exactly when there is
   * a new truth to show. It deliberately does NOT merge with what is on screen: a merge would keep
   * a value the server rejected and coerced, and the designer would then be looking at a number
   * that is not in the record.
   *
   * ⚠ It must NOT clear `saved`. The confirmation is set by the save and the re-read arrives a
   * moment later, so clearing it here would flash the tick and take it away again — and the one
   * thing this panel has to be unambiguous about is whether the settings the preview below was
   * rebuilt from are the ones on screen. `change` and `patch` clear it, which is the only event
   * that actually invalidates it.
   */
  useEffect(() => {
    setValues(settings);
  }, [settings]);

  const dirty = useMemo(() => JSON.stringify(values) !== JSON.stringify(settings), [values, settings]);

  const change = useCallback((key: string, value: DwValue) => {
    setValues((current) => ({ ...current, [key]: value }));
    setSaved(false);
  }, []);

  const patch = useCallback((next: Record<string, DwValue>) => {
    setValues((current) => ({ ...current, ...next }));
    setSaved(false);
  }, []);

  async function save() {
    if (!entity) return;
    setSaving(true);
    setError(null);
    try {
      // Only the registry's own live keys are sent. `values` starts life as the server's entry,
      // which carries nothing else today — but a future payload key that is not a field would be
      // rejected by `save_stage` and take the whole save with it, and losing a designer's report
      // settings to a key they never typed is not a risk worth taking to save one filter.
      //
      // `formFields` is deliberately NOT used here: it drops caption fields, which belong to their
      // media field on screen but are ordinary stored answers, and a save built from it would
      // blank a photograph's caption every time the settings were touched. Deprecated fields are
      // dropped, because writing null over one erases a value the registry can no longer show.
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
        // `isUnreachable`, not `isTransient`: the latter counts every 5xx as "try again later", so a
        // settings save the server ANSWERED and refused was reported as a missing connection —
        // exactly the sentence the download handler on this page's parent was written to stop
        // telling people. A server that spoke gets its own words shown.
        isUnreachable(err)
          ? "These settings are stored on the server, so they cannot be saved without a connection. Nothing has been lost — the boxes still hold what you typed."
          : err instanceof Error
            ? err.message
            : "Unable to save the report settings"
      );
    } finally {
      setSaving(false);
    }
  }

  if (!entity) {
    // Either the registry has not arrived yet or the server is older than stage 20. Nothing is
    // drawn in both cases, and that is right for both: the panel is an editor for a thing that
    // does not exist here, and everything else on the page — the preview, the download, the
    // warnings — works without it. A placeholder saying "settings unavailable" would be a
    // permanent apology on a page that is not actually missing anything a designer needs.
    return null;
  }

  return (
    <section className="mb-5 grid gap-3">
      <div className="panel grid gap-3 p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex min-w-0 items-start gap-3">
            <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600">
              <Settings2 className="h-4 w-4" aria-hidden />
            </span>
            <div className="min-w-0">
              <h2 className="font-display text-base font-bold text-ink-900">Report settings</h2>
              <p className="mt-1 text-sm leading-6 text-ink-muted">
                Saved on stage 20 and read by the server when it builds this report. The transcript annexure, the page size
                and the running head and foot are also applied to the file this page generates.
              </p>
            </div>
          </div>
          <div className="flex shrink-0 flex-wrap items-center gap-2">
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
          </div>
        </div>

        {draftPending ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            Stage 20 has changes saved on this device that have not reached the server yet. Saving from here would be undone
            the moment those changes sync, so the Save button is off until they have — open stage 20 and let it send, then
            come back.
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
            disabled={saving}
          />
          <div className="panel flex flex-wrap items-center gap-3 p-4">
            <button
              type="button"
              className="field-button"
              onClick={save}
              disabled={saving || !dirty || !online || draftPending}
            >
              {saving ? "Saving…" : "Save settings"}
            </button>
            {/* Said BEFORE the click. A disabled button with the reason beside it is a fact about
                the world; a button that fails when pressed is an app that looks broken. */}
            {!online ? (
              <span className="text-sm text-ink-muted">No connection — these are stored on the server.</span>
            ) : !dirty ? (
              <span className="text-sm text-ink-muted">
                {saved ? (
                  <span className="inline-flex items-center gap-1.5 text-success-600">
                    <CheckCircle2 className="h-4 w-4" aria-hidden />
                    Saved. The preview below has been rebuilt from them.
                  </span>
                ) : (
                  "Nothing changed yet."
                )}
              </span>
            ) : (
              <span className="text-sm text-ink-muted">
                Unsaved. The preview below still shows the settings the server holds.
              </span>
            )}
          </div>
        </>
      ) : null}
    </section>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * What the transcript annexure would contain
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The recordings the annexure would carry, listed before it is committed to.
 *
 * WHY THIS IS NOT OPTIONAL FURNITURE. A transcript annexure is the one part of a report whose
 * contents nobody has read: the text was produced by the media queue from audio a designer
 * recorded and moved on from, it can double the document's length, and generating sixty pages to
 * find out what is in it is not a preview. `GET /design-workshops/{id}/transcripts` serialises the
 * very `TranscriptItem` objects `append_transcript_annexure` consumes, so this list cannot claim a
 * recording the annexure then omits.
 *
 * RECORDINGS WITH NO TRANSCRIPT YET ARE LISTED, marked as not included. Hiding them would mean a
 * designer who made six recordings and sees four concludes that two were lost, when in fact two
 * are still in the queue — which is the silent-emptiness failure this repository keeps hitting.
 */
export function TranscriptAnnexurePanel({ transcripts }: { transcripts: DwTranscriptList | null }) {
  if (!transcripts) return null;

  const pending = transcripts.items.filter((item) => !item.includedInReport);
  const minutes = transcripts.totalDurationSeconds ? Math.round(transcripts.totalDurationSeconds / 60) : null;

  return (
    <section className="panel mb-5 grid gap-3 p-4">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600">
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
                <span className={`text-xs ${item.includedInReport ? "text-ink-500" : "text-amber-800"}`}>
                  {item.includedInReport
                    ? `${item.durationText} · ${item.wordCount} words${item.speakerCount > 1 ? ` · ${item.speakerCount} voices` : ""}`
                    : `Not in the annexure — transcription is ${item.status.toLowerCase() || "not finished"}`}
                </span>
              </div>
              {item.firstLine ? (
                <p className="mt-1 line-clamp-2 text-xs leading-5 text-ink-500">{item.firstLine}</p>
              ) : null}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-ink-700">
          This workshop has no recordings, so the annexure would be empty and no pages are added.
        </p>
      )}

      {pending.length ? (
        // Every gap is stated. An annexure a designer explicitly asked for that is quietly two
        // recordings short is the failure the whole panel exists to prevent.
        <p className="text-sm leading-6 text-ink-muted">
          {pending.length} recording{pending.length === 1 ? " is" : "s are"} still being transcribed and will be missing from
          the annexure if the file is generated now. The report itself will not say so.
        </p>
      ) : null}
    </section>
  );
}
