"use client";

/**
 * WHAT THE MODEL PRODUCED, WHAT IT WAS PRODUCED FROM, AND THE ONE QUESTION: does this stand in your
 * name?
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * THERE IS NO COPY BUTTON, NO "USE THIS TEXT", NO "REPLACE MY SELECTION", ON ANY VERB, AND THERE
 * MUST NEVER BE ONE. THIS IS THE SINGLE MOST LIKELY DEFECT IN THE WHOLE FEATURE.
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * It will feel broken. A proofread a designer cannot put back into their own sentence reads like a
 * half-finished feature, and the obvious kindness is a clipboard button. It is not an oversight:
 *
 *  · Plan §3 forbids any AI-produced value feeding a field that is compared across surfaces. A
 *    RICH_TEXT stage field IS compared across surfaces, and the same audio through Tier 1 on a phone
 *    and Tier 3 in the cloud differs legitimately and for ever — so the first cross-surface
 *    divergence test to fail would be blamed on a bug that is actually the design.
 *  · The server cannot even EXPRESS the write. `LayerWritePlan.__post_init__` refuses any table
 *    outside `WRITABLE_TABLES`, and `DwStageEntry` is deliberately absent. On the server the rule is
 *    true by construction; on the client it is true only by there being nothing to press.
 *  · **A clipboard button is a paste button with one extra keystroke.** The cross-surface argument
 *    does not count keystrokes.
 *
 * And the alternative is one this repository actively prefers, in `ai_verbs.expand`'s own words: *"A
 * designer who wants those words in the field types them, at which point they are that designer's
 * sentences under that designer's name — which is a true statement, unlike anything a paste button
 * could produce."*
 *
 * `frontend/e2e/ai-verbs-unit.spec.ts` reads this file's SOURCE and fails if `onChange(`,
 * `navigator.clipboard`, `commit(`, `document.execCommand` or `StoredRichDoc` appears in it. That is
 * deliberate: adding a paste button should be a failing test rather than a helpful commit.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY A DIALOG AND WHY IT IS NOT FOLLOWED BY A CONFIRM.
 *
 * The text has to be READ before it is signed for, and `AiLayersPanel`'s list deliberately does not
 * carry text (`includeText` is all-or-nothing and off by default, because a workshop can hold
 * twenty-five interviews). The 201 from a verb DOES carry the text — `_finish_verb` passes
 * `include_text=True` — so this is the one moment the words are on screen at all. That makes this
 * dialog the confirm. Stacking the panel's `confirm()` on top of it would be the "trains people to
 * click" failure that panel's own header names; its confirm exists precisely BECAUSE the panel may
 * not have the text on screen.
 */

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
  SUBTITLES_DEPLOYMENT_KEY_NOTE,
  SUBTITLE_FORMAT_LABELS,
  downloadDesignWorkshopSubtitles,
  subtitleCueSummary,
  subtitleTimecode,
  type DwAiVerbResult,
  type DwSubtitleFormat
} from "@/lib/aiVerbs";
import { saveBlobToDisk } from "@/lib/designWorkshops";

/** How many cues are drawn before the table says it stopped. A screen is not a subtitle editor. */
const CUE_PREVIEW_LIMIT = 40;

export type AiVerbReviewDialogProps = {
  open: boolean;
  workshopId: string;
  /** The whole 201, because the allowance travels on it and the layer alone would lose the count. */
  result: DwAiVerbResult | null;
  /**
   * The passage that was sent, when the caller is holding it.
   *
   * Only used as a FALLBACK for `result.layer.source.text` — the server sends the evidence back on
   * every supplied-text layer, and the server's copy is the one the annexure will print, so a
   * disagreement between the two must resolve towards the stored one.
   */
  sourceText?: string | null;
  /** The layer was accepted; the caller should re-read whatever list it is showing. */
  onAccepted: () => void;
  /** The layer was declined and deleted. Same. */
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
  // Reset per layer rather than per open: a designer who runs a second verb without closing the
  // dialog would otherwise read the first run's refusal under the second run's words.
  useEffect(() => {
    setProblem(null);
    setSpeakers(false);
  }, [layerId]);

  if (!result) return null;

  const layer = result.layer;
  const kind = String(layer.kind ?? "");
  const provenance = layerProvenance(layer);
  const note = layerKindNote(kind);
  // Read through a named function so that SOMETHING reads these two columns and a test can watch it
  // do so — see `layerLanguagePair`.
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
      // CLOSING IS THE CALLER'S, and `onClose` is deliberately NOT called as well: all three call
      // sites close by clearing the result they hold, and two of them re-read a list in the same
      // handler — so calling both would put two identical GETs on the wire, on the metered rural
      // connection this whole application is written for.
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
      // NEVER AN `<a href>`: every media route is bearer-authenticated and an anchor sends no
      // Authorization header, so a link would 401. And the FILE NAME is the server's, because it is
      // what distinguishes the speaker-labelled file from the anonymised one.
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
      // The annexure's own words for this kind, so the person signing recognises what they signed
      // for when they meet it again in the .docx a year later.
      title={layerKindLabel(kind)}
      description="Nothing has been put in any document yet. Read it, then decide whether it stands in your name."
      tone="warning"
      // Three ways forward, and an X would have to silently mean one of them — but this dialog's
      // "close" IS the third way (leave it for now, the layer stays listed and inert), so the X is
      // honest here and is kept. Escape and the backdrop resolve to the same, which loses nothing.
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
              {/* Worded and not only spun: the reduced-motion rules zero every animation-duration,
                  so a frozen spinner is the whole of the feedback for readers who asked for less. */}
              {busy === "ACCEPT" ? "Accepting…" : "I have read it — accept it in my name"}
            </button>
          </div>
        </div>
      }
    >
      <div className="grid gap-4">
        {kind === "EXPANDED" ? (
          // THE ONE KIND THAT INVENTS, WARNED ABOUT WHERE THE DECISION IS MADE. This carries the
          // substance of `report_ai_layers.EXPANDED_NOTE`, which the annexure prints under this
          // heading and under no other — so the caution a ministry officer will read is the caution
          // the designer read before signing. amber-100/amber-800 because amber-50/200 are stock
          // Tailwind and do not pair with the brand rungs.
          <p className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
            <span>
              This passage was written by a machine from your short note, which is quoted below as its source. Anything
              in it that is not in that note — a detail, a reason, a connection between two things — was supplied by the
              model and was not recorded in the field. Treat the note as the record and this as a reading of it, and
              check any specific claim against the workshop&apos;s own material before quoting it.
            </span>
          </p>
        ) : null}

        {note ? <p className="text-sm leading-6 text-ink-700">{note}</p> : null}

        {/* WHAT WAS SENT, verbatim and above the output, because accepting is a statement that
            somebody checked one against the other. `layer_payload` calls this "the evidence travels
            with the layer" and it is the only copy there is for a supplied-text source. */}
        {evidence ? (
          <section className="grid gap-1.5">
            <h3 className="field-label">What was sent</h3>
            <p className="max-h-40 overflow-y-auto whitespace-pre-wrap rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
              {evidence}
            </p>
          </section>
        ) : layer.source?.kind === "MEDIA" ? (
          <p className="text-sm leading-6 text-ink-500">
            Made from a file attached to this workshop. Check the sentence against the photograph or the recording
            itself, which is the evidence it stands on — it is on the stage this file is attached to.
          </p>
        ) : layer.source?.kind === "LAYER" ? (
          <p className="text-sm leading-6 text-ink-500">
            Made from another layer of this workshop. Both are drawn together, one inside the other, on the AI layers
            screen.
          </p>
        ) : null}

        {/* WHAT CAME BACK. Through the shared Markdown renderer, which is the house rule for
            transcripts and AI text — never a <pre>, and never dangerouslySetInnerHTML (this one has
            no rehype-raw, so any HTML in the model's answer stays escaped). */}
        <section className="grid gap-1.5">
          <h3 className="field-label">What came back</h3>
          {(layer.text ?? "").trim() ? (
            <div className="max-h-80 overflow-y-auto rounded-md border border-line-200 bg-card px-3 py-2 text-sm text-ink-900">
              <Markdown text={layer.text ?? ""} />
            </div>
          ) : (
            <p className="text-sm leading-6 text-ink-500">
              This layer carries no prose of its own — its content is the structured data below.
            </p>
          )}
        </section>

        {confidence !== null ? (
          // NEVER A BARE PERCENTAGE. `ai_verbs.caption` stores `confidenceIsCalibrated: false`
          // deliberately, because nothing in this repository has ever calibrated a model's
          // confidence against anything — and a number beside a caption is read as a measurement of
          // correctness. It travels so a designer deciding whether to accept can see it, and it
          // travels labelled so nobody builds a gate on it.
          <p className="text-xs leading-5 text-ink-500">
            The model reported {confidence} as its own confidence in this description. That is the model&apos;s own
            estimate, which nothing has checked against anything — it is not a measurement of whether the sentence is
            right. The photograph is.
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
            {/* A LIST THAT QUIETLY STOPS IS INDISTINGUISHABLE FROM A SHORT LIST — the most repeated
                defect class in this repository. The download below carries every cue. */}
            {cues.cues.length > CUE_PREVIEW_LIMIT ? (
              <p className="text-xs leading-5 text-ink-500">
                The first {CUE_PREVIEW_LIMIT} cues are shown. The file below carries all{" "}
                {cues.count.toLocaleString()}.
              </p>
            ) : null}

            {cues.hasSpeakers ? (
              <label className="flex items-start gap-2 text-xs leading-5 text-ink-700">
                <input
                  type="checkbox"
                  className="mt-0.5"
                  checked={speakers}
                  onChange={(event) => setSpeakers(event.target.checked)}
                />
                <span>
                  Put the speaker label in front of each line. <strong className="font-semibold">The labels are the
                  engine&apos;s own guess</strong> — nobody told it how many people were in the room or who they were,
                  and it can merge two quiet voices or split one person who moved away from the microphone. The .vtt
                  carries that caution inside the file; SubRip has no comment syntax and cannot, so a .srt carries the
                  labels alone.
                </span>
              </label>
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
            {/* THE DOWNLOAD IS NOT GATED ON ACCEPTANCE, matching the route, and it says so: requiring
                acceptance first would mean accepting subtitles nobody has watched, which is the
                opposite of what acceptance is for. */}
            <p className="text-xs leading-5 text-ink-500">
              You can download and watch these against the recording before deciding. Downloading changes nothing and
              does not accept anything.
            </p>
          </section>
        ) : null}

        {/* THE PROVENANCE, ALWAYS, IN WORDS AND NEVER A NUMERAL. "not recorded" is a real answer and
            is drawn in the same quiet type as a recorded one — a row whose provider nobody stored is
            the ordinary case, and an error colour would tell a designer their archive is broken. */}
        <section className="grid gap-1.5 rounded-md border border-line-200 bg-surface-50 px-3 py-2">
          <p className="text-xs leading-5 text-ink-700">
            <span className="font-medium text-ink-900">{tierLabel(layer.tier)}</span> · {tierSentence(layer.tier)}
          </p>
          <p className="text-xs leading-5 text-ink-500">
            Provider: {provenance.provider} · Model: {provenance.model} · Language: {provenance.language}
            {languages ? ` · From ${languages.from} into ${languages.into}` : ""}
          </p>
          {kind === "SUBTITLES" ? (
            // THE ONE VERB THAT NEVER RUNS ON THE DESIGNER'S OWN KEY, said beside the provenance line
            // that would otherwise imply it might have. See `SUBTITLES_DEPLOYMENT_KEY_NOTE`.
            <p className="text-xs leading-5 text-ink-500">{SUBTITLES_DEPLOYMENT_KEY_NOTE}</p>
          ) : null}
          <p className="text-xs leading-5 text-ink-500">
            Accepting records your name and the moment against this {layerKindNoun(kind)}. Until somebody does, it is
            listed on the AI layers screen and no report will print it.
          </p>
        </section>

        {/* THE RUNNING ALLOWANCE, from the 201's own numbers rather than from a second request.
            Drawn only when there IS a ceiling: `aiVerbsRemaining` is null on an uncapped deployment
            and "0 left" must never be how "no ceiling" looks. */}
        {result.aiVerbsRemaining !== null ? (
          <p
            className={
              result.aiVerbsRemaining <= AI_VERB_COUNTDOWN_FROM
                ? "text-xs leading-5 text-amber-800"
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
