"use client";

/**
 * WHERE AN ARTISAN'S ANSWER GOES ON RECORD — the screen six different refusals send a designer to.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THIS EXISTS AT ALL, WHICH IS A DEFECT AND NOT A FEATURE REQUEST.
 *
 * `DesignWorkshop.dictationConsent` gates `POST /{id}/dictate` and all five AI verbs. Consent is
 * assumed by default and can be revoked (owner decision, 19 Oct 2026), so only a REFUSED answer is
 * refused (409), with a sentence sending the designer here to change it. On the handset the same
 * control is `StageIndexScreen`'s consent row.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * WHAT THIS CARD DOES AND WILL NOT DO.
 *
 * 1. **IT IS A SWITCH, ON BY DEFAULT.** "Recordings may be transcribed" is on unless a refusal is on
 *    record. {@link DW_CONSENT_QUESTION} sits under the label in full, so every artisan is told the
 *    same words, on the laptop and on the handset.
 * 2. **IT RECORDS TWO ANSWERS AND NEVER A THIRD.** Switching off records REFUSED (which also stops
 *    pending transcriptions); switching on records GRANTED. `DictationConsentIn` refuses NOT_RECORDED
 *    by name, so there is no "clear this": NOT_RECORDED is only ever the absence of an answer.
 * 3. **IT DOES NOT REFUSE THE ANSWER WHEN THERE IS NO SIGNAL.** See {@link recordDraftConsent} — the
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
  "Only this workshop's designer or an admin can record this.";

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
  /**
   * Why this reader may not record the answer ON THIS WORKSHOP although their role may, or null.
   *
   * Today that is a post: whoever inspects or supervises a workshop does not write it, and the
   * consent is a write the server refuses them (`useHeldPostRefusal`). Drawn in place of the buttons,
   * for this card's own reason — a greyed control refuses a press without saying why. Without it the
   * answer would be taken on this device and refused on its way up, a recording that never counts.
   */
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
  // On unless a refusal is on record: no answer is consent, assumed by default.
  const on = token !== "REFUSED";

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
            ? "Saved on this device. It uploads with the workshop."
            : null
        );
        if (!stored) setProblem("Couldn't save the answer. Try again.");
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
        setOutcome("Recorded and saved online.");
      } catch (err) {
        // THE ANSWER IS STILL RECORDED. What the designer could not otherwise know is that the
        // SERVER's gate has not moved — so dictation and the AI verbs on this workshop will still be
        // refused until the next sync, and saying so is the whole value of this branch.
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
        {/* Colour never carries this on its own: the state is worded in the sentence below and the
            chip repeats it, so the judgement survives greyscale and forced-colours mode. */}
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

      {/* WHAT IS ON RECORD AND WHAT IT MEANS, in one sentence — "not recorded" on its own tells a
          designer nothing about why the controls on the stage they just left behaved as they did.
          Not said for "no answer yet" above the switch, whose description already opens "On by
          default" — one sentence, not the same one twice. */}
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
        // THE ONE THING A DESIGNER CANNOT OTHERWISE KNOW. The gate that refuses a verb is the
        // server's column, not this device's record, so an answer that has not been pushed leaves
        // every send on this workshop refused — with a message telling them to come and do the thing
        // they have just done.
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
          {/* Switching off does more than close the gate and the designer should know before they
              press: `cancel_pending_transcriptions` marks every queued transcription of this
              workshop's recordings as failed. Discovering it afterwards looks like lost work. */}
          <p className="text-xs leading-5 text-ink-500">
            Switching off also stops pending transcriptions. Recordings are kept.
          </p>
        </>
      ) : (
        <p className="text-xs leading-5 text-ink-500">{readOnlyReason ?? NOT_YOURS_TO_RECORD}</p>
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
