"use client";

/**
 * WHERE AN ARTISAN'S ANSWER GOES ON RECORD — the screen six different refusals send a designer to.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THIS EXISTS AT ALL, WHICH IS A DEFECT AND NOT A FEATURE REQUEST.
 *
 * `DesignWorkshop.dictationConsent` gates `POST /{id}/dictate` and all five AI verbs. Anything other
 * than GRANTED is refused with a 409 whose last clause is, verbatim: *"Open the workshop's own
 * screen and record the artisan's answer to that question — until somebody does, this stays
 * unavailable."* On the handset that screen exists (`StageIndexScreen`'s consent row). On the web it
 * did not, and `DwSummary` did not even decode the column — so a designer following that
 * instruction went looking for a control that had never been built. **A refusal that names a control
 * which does not exist is worse than the capability simply being absent**, and it is why this landed
 * before any verb button did.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * FOUR THINGS THIS CARD WILL NOT DO.
 *
 * 1. **IT DOES NOT REDUCE THE QUESTION TO A SWITCH.** {@link DW_CONSENT_QUESTION} is on screen in
 *    full, above the buttons, because the person who ASKS is whoever is holding the laptop: with a
 *    label reading "allow cloud dictation" what the artisan is actually asked is whatever that
 *    designer improvises, no two artisans are asked the same thing, and there is no way afterwards
 *    to know what any of them agreed to. That is the whole difference between a consent and a
 *    checkbox, and it is Android's argument, transliterated rather than re-derived.
 * 2. **IT OFFERS TWO ANSWERS AND NEVER A THIRD.** `DictationConsentIn` refuses NOT_RECORDED by name
 *    — *"somebody deliberately wrote down that nobody has been asked" is not a state anybody is in*
 *    — so there is no "clear this". Taking a consent back is recording REFUSED, which is a decision
 *    with a next move and a row in the log.
 * 3. **IT DOES NOT GREY OUT THE ANSWER THAT IS ALREADY ON RECORD.** Recording the same answer again
 *    is legitimate: an artisan asked a second time and saying the same thing is worth a second row
 *    in the log. The only disabling here is while a write is in flight.
 * 4. **IT DOES NOT REFUSE THE ANSWER WHEN THERE IS NO SIGNAL.** See {@link recordDraftConsent} — the
 *    answer lands on this device first and the server hears about it when there is something to hear
 *    it over. A control that recorded consent only when it could reach the server would refuse the
 *    answer at the exact moment the artisan gave it, which is the moment it exists for.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * AND THE ONE THING IT MUST SAY OUT LOUD: a locally recorded answer does NOT open the server's gate.
 * Every verb and every server dictation reads the column up there, so between recording "they
 * agreed" and the next sync a designer will still be refused. Reporting that as done would be the
 * "success for an act that did not happen" failure this repository's offline rules exist against.
 */

import { useState } from "react";
import { CheckCircle2, CloudOff, Loader2, ShieldQuestion } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { formatDateTime } from "@/lib/format";
import {
  DW_CONSENT_NO_LABEL,
  DW_CONSENT_QUESTION,
  DW_CONSENT_ROW_TITLE,
  DW_CONSENT_YES_LABEL,
  dictationConsentSentence,
  recordDesignWorkshopDictationConsent,
  type DwConsentDecision
} from "@/lib/designWorkshops";
import { markDraftConsentSynced, recordDraftConsent } from "@/lib/designWorkshopStore";
import { aiLayerProblem } from "@/lib/aiLayers";
import { canRunDesignWorkshops } from "@/lib/permissions";

/**
 * The sentence for somebody who may open this workshop and may not answer for it.
 *
 * A SENTENCE AND NOT A DISABLED BUTTON, which is the viewers row's rule on the handset and the same
 * one this app applies elsewhere: a greyed control refuses a tap without saying why, which is how
 * somebody concludes the app is broken. Copied from Android's `DW_CONSENT_NOT_YOURS_TO_RECORD` with
 * its last clause dropped, because that clause is about the phone's own speech recogniser and this
 * client has no equivalent worth naming.
 */
const NOT_YOURS_TO_RECORD =
  "Only the designer running this workshop, or an administrator, can record this answer. Ask " +
  "whoever is running it to put the question to the artisan and record what they say.";

export type DictationConsentCardProps = {
  /** The draft's `localId`, so the answer can be written with no connection. Null = no local copy. */
  draftLocalId: string | null;
  /** The server's id for this workshop, or null while it exists only on this device. */
  remoteId: string | null;
  /** The answer as it stands: "NOT_RECORDED" | "GRANTED" | "REFUSED", or a token a newer server sent. */
  consent: string;
  /** When the ARTISAN answered — the courtyard moment, which can precede the sync by a fortnight. */
  recordedAt: string | null;
  /** Resolved on the single-record read only; null means this screen cannot name them. */
  recordedByName: string | null;
  /** False when this device holds an answer the server has not heard. THE load-bearing flag. */
  synced: boolean;
  /** Called with nothing once an answer has been written, so the page can re-read its draft. */
  onRecorded: () => void;
};

export function DictationConsentCard({
  draftLocalId,
  remoteId,
  consent,
  recordedAt,
  recordedByName,
  synced,
  onRecorded
}: DictationConsentCardProps) {
  const { user } = useAuth();
  const [busy, setBusy] = useState<DwConsentDecision | null>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const mayRecord = canRunDesignWorkshops(user);
  const token = (consent ?? "").trim().toUpperCase();
  const granted = token === "GRANTED";

  async function record(decision: DwConsentDecision) {
    setBusy(decision);
    setProblem(null);
    setOutcome(null);
    try {
      /*
        THE DEVICE FIRST, THE SERVER SECOND — and the two outcomes are reported apart.

        `recordDraftConsent` writes into IndexedDB, which is what the screen reads, so the answer is
        taken the instant it is given whatever the connection is doing. The push is attempted after
        and is allowed to fail: a failed push is NOT a failed recording, and raising it would tell a
        designer their consent was not taken when it was. The sync pass carries it afterwards —
        `pendingWork` reports an unsynced consent as outstanding work precisely so that a workshop
        with nothing else pending still gets its answer sent.
      */
      const stored = draftLocalId
        ? await recordDraftConsent(draftLocalId, decision, { id: user?.id ?? null, name: user?.name ?? null })
        : null;

      if (!remoteId) {
        // Nothing to push to: this workshop has never been to the server. A legitimate state for a
        // fortnight, not an error — but it must not be reported as "recorded and sent".
        setOutcome(
          stored
            ? "Recorded on this device. This workshop has not reached the repository yet, so the answer goes up with it."
            : null
        );
        if (!stored) setProblem("This browser could not store the answer, and there is no workshop on the server to send it to. Nothing has been recorded — try again.");
        onRecorded();
        return;
      }

      try {
        await recordDesignWorkshopDictationConsent(remoteId, decision, {
          // The moment the ARTISAN answered, off this device's clock, which is what the server stores
          // as the consent's own timestamp; its `createdAt` records when it heard. Taken from the
          // record just written so the two copies name one moment.
          recordedAt: stored?.consent?.recordedAt ?? null
        });
        if (draftLocalId) await markDraftConsentSynced(draftLocalId);
        setOutcome("Recorded, and the repository has it.");
      } catch (err) {
        // THE ANSWER IS STILL RECORDED. What the designer could not otherwise know is that the
        // SERVER's gate has not moved — so dictation and the AI verbs on this workshop will still be
        // refused until the next sync, and saying so is the whole value of this branch.
        setProblem(
          `${aiLayerProblem(err, "The repository did not accept the answer.")} ` +
            (stored
              ? "The answer is recorded on this device and will be sent with the next sync. Until it arrives, " +
                "dictation and the AI controls on this workshop will still be refused, because the server reads " +
                "its own copy."
              : "It could not be stored on this device either, so nothing has been recorded — try again.")
        );
      }
      onRecorded();
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="panel mb-5 grid gap-3 p-4" aria-labelledby="dw-consent-heading">
      <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
        <h2 id="dw-consent-heading" className="font-display text-sm font-bold text-ink-900">
          {DW_CONSENT_ROW_TITLE}
        </h2>
        {/* Colour never carries this on its own: the state is worded in the sentence below and the
            chip repeats it, so the judgement survives greyscale and forced-colours mode. */}
        <span
          className={
            granted
              ? "inline-flex items-center gap-1 rounded-full border border-success-600/25 bg-success-100 px-2 py-0.5 text-xs font-medium text-success-600"
              : "inline-flex items-center gap-1 rounded-full border border-amber-500/40 bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
          }
        >
          {granted ? <CheckCircle2 className="h-3 w-3" aria-hidden /> : <ShieldQuestion className="h-3 w-3" aria-hidden />}
          {token === "GRANTED" ? "They agreed" : token === "REFUSED" ? "They did not agree" : "Nobody has been asked"}
        </span>
      </div>

      {/* WHAT IS ON RECORD AND WHAT IT MEANS, in one sentence — "not recorded" on its own tells a
          designer nothing about why the controls on the stage they just left behaved as they did. */}
      <p className={granted ? "text-sm leading-6 text-ink-700" : "text-sm leading-6 text-amber-800"}>
        {dictationConsentSentence(consent)}
      </p>

      {token !== "NOT_RECORDED" ? (
        <p className="text-xs leading-5 text-ink-500">
          {recordedByName ? `Recorded by ${recordedByName}` : "Recorded"}
          {recordedAt ? ` on ${formatDateTime(recordedAt)}` : ""}
          {recordedAt ? "" : " — the moment was not recorded"}.
        </p>
      ) : null}

      {!synced ? (
        // THE ONE THING A DESIGNER CANNOT OTHERWISE KNOW. The gate that refuses a verb is the
        // server's column, not this device's record, so an answer that has not been pushed leaves
        // every send on this workshop refused — with a message telling them to come and do the thing
        // they have just done.
        <p className="flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          <CloudOff className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>
            This answer is on this device and has not reached the repository yet. The server checks its own copy, so
            dictation and the AI controls on this workshop will go on being refused until it arrives — it is sent with
            the next sync and nothing needs to be typed again.
          </span>
        </p>
      ) : null}

      {mayRecord ? (
        <>
          {/* THE QUESTION, IN FULL, ABOVE THE BUTTONS. See the file header for why it is not a
              switch label. */}
          <p className="rounded-md bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700">{DW_CONSENT_QUESTION}</p>
          {/* Recording REFUSED does more than close the gate and the designer should know before
              they press: `cancel_pending_transcriptions` marks every queued transcription of this
              workshop's recordings as failed. That is the difference between a consent and a
              preference, and discovering it afterwards looks like the app losing work. */}
          <p className="text-xs leading-5 text-ink-500">
            Recording “they did not agree” also stops any of this workshop&apos;s recordings that are still waiting to be
            written down. The recordings themselves are kept and can be listened to.
          </p>
          <div className="flex flex-col gap-2 sm:flex-row">
            {/* Both labels are sentences rather than verbs, and they are worded as the ARTISAN'S
                answer rather than as a setting the designer is choosing for them. */}
            <button
              type="button"
              className="field-button justify-center sm:flex-1"
              disabled={busy !== null}
              onClick={() => void record("GRANTED")}
            >
              {busy === "GRANTED" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
              {busy === "GRANTED" ? "Recording…" : DW_CONSENT_YES_LABEL}
            </button>
            <button
              type="button"
              className="field-button-secondary justify-center sm:flex-1"
              disabled={busy !== null}
              onClick={() => void record("REFUSED")}
            >
              {busy === "REFUSED" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
              {busy === "REFUSED" ? "Recording…" : DW_CONSENT_NO_LABEL}
            </button>
          </div>
        </>
      ) : (
        <p className="text-xs leading-5 text-ink-500">{NOT_YOURS_TO_RECORD}</p>
      )}

      {/* Announced, because the visible change either button makes is a line of prose several rows
          above the button that caused it. `status` for the success (nothing is wrong) and `alert`
          for the problem (a person has to decide what to do). */}
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
