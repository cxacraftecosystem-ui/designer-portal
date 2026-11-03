"use client";

import { Toggle } from "@/components/ui/Toggle";
import { useEffect, useState } from "react";
import { AlertTriangle, Check, Download, Loader2, Trash2 } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { Markdown } from "@/components/Markdown";
import {
  acceptDesignWorkshopAiLayer,
  aiLayerProblem,
  deleteDesignWorkshopAiLayer,
  layerKindLabel,
  layerKindNote,
  layerKindNoun,
  layerLanguagePair,
  layerProvenance,
  readAiPayload,
  tierLabel,
  tierSentence
} from "@/lib/aiLayers";
import {
  AI_VERB_COUNTDOWN_FROM,
  SUBTITLE_FORMAT_LABELS,
  downloadDesignWorkshopSubtitles,
  subtitleCueSummary,
  subtitleTimecode,
  type DwAiVerbResult,
  type DwSubtitleFormat
} from "@/lib/aiVerbs";
import { saveBlobToDisk } from "@/lib/designWorkshops";

const CUE_PREVIEW_LIMIT = 40;

export type AiVerbReviewDialogProps = {
  open: boolean;
  workshopId: string;
  result: DwAiVerbResult | null;
  sourceText?: string | null;
  onAccepted: () => void;
  onDeclined: () => void;
  onClose: () => void;
};

export function AiVerbReviewDialog({
  open,
  workshopId,
  result,
  sourceText,
  onAccepted,
  onDeclined,
  onClose
}: AiVerbReviewDialogProps) {
  const [busy, setBusy] = useState<"ACCEPT" | "DECLINE" | "DOWNLOAD" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [speakers, setSpeakers] = useState(false);

  const layerId = result?.layer.id ?? null;
  useEffect(() => {
    setProblem(null);
    setSpeakers(false);
  }, [layerId]);

  if (!result) return null;

  const layer = result.layer;
  const kind = String(layer.kind ?? "");
  const provenance = layerProvenance(layer);
  const note = layerKindNote(kind);
  const languages = layerLanguagePair(layer);
  const evidence = layer.source?.text ?? sourceText ?? null;
  const cues = kind === "SUBTITLES" ? subtitleCueSummary(layer.payload) : null;
  const payloadView = kind === "CAPTION" ? readAiPayload(layer.payload) : null;
  const confidence =
    payloadView?.shape === "ROWS"
      ? payloadView.rows.find((row) => row.key === "selfReportedConfidence")?.value ?? null
      : null;

  async function accept() {
    setBusy("ACCEPT");
    setProblem(null);
    try {
      await acceptDesignWorkshopAiLayer(workshopId, layer.id);
      onAccepted();
    } catch (err) {
      setProblem(aiLayerProblem(err, "This layer could not be accepted."));
    } finally {
      setBusy(null);
    }
  }

  async function decline() {
    setBusy("DECLINE");
    setProblem(null);
    try {
      await deleteDesignWorkshopAiLayer(workshopId, layer.id);
      onDeclined();
    } catch (err) {
      setProblem(aiLayerProblem(err, "This layer could not be declined."));
    } finally {
      setBusy(null);
    }
  }

  async function download(format: DwSubtitleFormat) {
    setBusy("DOWNLOAD");
    setProblem(null);
    try {
      const file = await downloadDesignWorkshopSubtitles(workshopId, layer.id, format, speakers);
      saveBlobToDisk(file.blob, file.fileName);
    } catch (err) {
      setProblem(aiLayerProblem(err, "That subtitle file could not be produced."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title={layerKindLabel(kind)}
      description="Read it, then decide whether to accept it in your name."
      tone="warning"
      showClose
      busy={busy !== null}
      className="max-w-3xl"
      footer={
        <div className="flex w-full flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <button
            type="button"
            className="inline-flex items-center gap-1.5 self-start text-xs font-medium text-error-600 underline"
            disabled={busy !== null}
            onClick={() => void decline()}
          >
            <Trash2 className="h-3.5 w-3.5" aria-hidden />
            {busy === "DECLINE" ? "Declining…" : "Decline it"}
          </button>
          <div className="flex flex-col gap-2 sm:flex-row">
            <button type="button" className="field-button-secondary justify-center" disabled={busy !== null} onClick={onClose}>
              Leave it for now
            </button>
            <button type="button" className="field-button justify-center" disabled={busy !== null} onClick={() => void accept()}>
              {busy === "ACCEPT" ? (
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
              ) : (
                <Check className="h-4 w-4" aria-hidden />
              )}
              {busy === "ACCEPT" ? "Accepting…" : "I have read it — accept it in my name"}
            </button>
          </div>
        </div>
      }
    >
      <div className="grid gap-4">
        {kind === "EXPANDED" ? (
          <p className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
            <span>
              AI-written from your note (below). Anything not in the note was added by the model — check it before quoting.
            </span>
          </p>
        ) : null}

        {note ? <p className="text-sm leading-6 text-ink-700">{note}</p> : null}

        {evidence ? (
          <section className="grid gap-1.5">
            <h3 className="field-label">What was sent</h3>
            <p className="max-h-40 overflow-y-auto whitespace-pre-wrap rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
              {evidence}
            </p>
          </section>
        ) : layer.source?.kind === "MEDIA" ? (
          <p className="text-sm leading-6 text-ink-500">
            Made from a workshop file. Check it against the file.
          </p>
        ) : layer.source?.kind === "LAYER" ? (
          <p className="text-sm leading-6 text-ink-500">
            Made from another AI layer.
          </p>
        ) : null}

        <section className="grid gap-1.5">
          <h3 className="field-label">What came back</h3>
          {(layer.text ?? "").trim() ? (
            <div className="max-h-80 overflow-y-auto rounded-md border border-line-200 bg-card px-3 py-2 text-sm text-ink-900">
              <Markdown text={layer.text ?? ""} />
            </div>
          ) : (
            <p className="text-sm leading-6 text-ink-500">
              No text — data below.
            </p>
          )}
        </section>

        {confidence !== null ? (
          <p className="text-xs leading-5 text-ink-500">
            Model&apos;s own confidence: {confidence}. Compare with the photo.
          </p>
        ) : null}

        {cues ? (
          <section className="grid gap-2">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h3 className="field-label">The cues</h3>
              <p className="text-xs text-ink-500">
                {cues.count.toLocaleString()} cue{cues.count === 1 ? "" : "s"}
                {cues.estimatedCues ? `, ${cues.estimatedCues.toLocaleString()} of them approximate` : ""}
                {cues.durationSeconds !== null ? ` · ${subtitleTimecode(cues.durationSeconds)}` : ""}
                {cues.language ? ` · ${cues.language}` : ""}
              </p>
            </div>
            {cues.cues.length ? (
              <div className="max-h-64 overflow-auto rounded-md border border-line-200">
                <table className="w-full text-left text-xs">
                  <thead className="bg-surface-50 text-ink-500">
                    <tr>
                      <th className="px-2 py-1.5 font-medium">From</th>
                      <th className="px-2 py-1.5 font-medium">To</th>
                      {cues.hasSpeakers ? <th className="px-2 py-1.5 font-medium">Speaker</th> : null}
                      <th className="px-2 py-1.5 font-medium">Line</th>
                    </tr>
                  </thead>
                  <tbody>
                    {cues.cues.slice(0, CUE_PREVIEW_LIMIT).map((cue, index) => (
                      <tr key={`${cue.start}-${index}`} className="border-t border-line-200 align-top">
                        <td className="whitespace-nowrap px-2 py-1.5 text-ink-500">{subtitleTimecode(cue.start)}</td>
                        <td className="whitespace-nowrap px-2 py-1.5 text-ink-500">{subtitleTimecode(cue.end)}</td>
                        {cues.hasSpeakers ? <td className="px-2 py-1.5 text-ink-500">{cue.speaker ?? "—"}</td> : null}
                        <td className="px-2 py-1.5 text-ink-900">
                          {cue.text}
                          {cue.estimated ? <span className="ml-1 text-ink-500">(approximate timing)</span> : null}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="text-sm leading-6 text-ink-500">This layer holds no readable cue list.</p>
            )}
            {cues.cues.length > CUE_PREVIEW_LIMIT ? (
              <p className="text-xs leading-5 text-ink-500">
                The first {CUE_PREVIEW_LIMIT} cues are shown. The file below carries all{" "}
                {cues.count.toLocaleString()}.
              </p>
            ) : null}

            {cues.hasSpeakers ? (
              <Toggle
                label="Label speakers on each line"
                description={<strong className="font-semibold">Labels are a best guess.</strong>}
                checked={speakers}
                onChange={setSpeakers}
              />
            ) : null}

            <div className="flex flex-wrap gap-2">
              {(["srt", "vtt"] as DwSubtitleFormat[]).map((format) => (
                <button
                  key={format}
                  type="button"
                  className="field-button-secondary"
                  disabled={busy !== null || !cues.cues.length}
                  onClick={() => void download(format)}
                >
                  <Download className="h-4 w-4" aria-hidden />
                  {SUBTITLE_FORMAT_LABELS[format]}
                </button>
              ))}
            </div>
            <p className="text-xs leading-5 text-ink-500">
              Downloading doesn&apos;t accept anything.
            </p>
          </section>
        ) : null}

        <section className="grid gap-1.5 rounded-md border border-line-200 bg-surface-50 px-3 py-2">
          <p className="text-xs leading-5 text-ink-700">
            <span className="font-medium text-ink-900">{tierLabel(layer.tier)}</span> · {tierSentence(layer.tier)}
          </p>
          <p className="text-xs leading-5 text-ink-500">
            Provider: {provenance.provider} · Model: {provenance.model} · Language: {provenance.language}
            {languages ? ` · From ${languages.from} into ${languages.into}` : ""}
          </p>
          <p className="text-xs leading-5 text-ink-500">
            Not printed until accepted. Accepting records your name.
          </p>
        </section>

        {result.aiVerbsRemaining !== null ? (
          <p
            className={
              result.aiVerbsRemaining <= AI_VERB_COUNTDOWN_FROM
                ? "text-xs leading-5 text-amber-800 dark:text-amber-100"
                : "text-xs leading-5 text-ink-500"
            }
          >
            {result.aiVerbsRemaining.toLocaleString()} run{result.aiVerbsRemaining === 1 ? "" : "s"} of the writing and
            captioning models left for {result.aiVerbDay}. Dictation has its own separate allowance and is unaffected.
          </p>
        ) : null}

        {problem ? (
          <p role="alert" className="text-sm leading-6 text-error-600">
            {problem}
          </p>
        ) : null}
      </div>
    </FieldDialog>
  );
}
