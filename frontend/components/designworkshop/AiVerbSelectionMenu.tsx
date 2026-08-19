"use client";

/**
 * THE THREE TEXT VERBS, AT THE CARET, IN THE FIELD THE DESIGNER IS ACTUALLY WRITING IN.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THIS IS HERE AND NOT ON THE AI-LAYERS SCREEN. A "proofread this" that lives three taps away
 * on a provenance screen is a feature nobody uses. The four surfaces this toolbar already owns —
 * marks, blocks, find, dictation — are the ones a designer reaches for mid-sentence, and this is a
 * fifth of exactly that kind.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * SELECTION-SCOPED AND NOT FIELD-SCOPED, WHICH IS THE DECISION WORTH DEFENDING. Dictation is
 * field-scoped because you speak into a whole field. A verb cannot be: `MAX_DOCUMENT_CHARS` for a
 * RICH_TEXT field is 200,000 and `MAX_VERB_TEXT_CHARS` is 20,000, so a field-level control would
 * routinely be refused on a stage-13 narrative and a designer would learn, correctly, that the
 * button is broken. A selection cannot do that — and the selection is also what the layer RECORDS as
 * its source, so what is highlighted is exactly what a later reader will see quoted as the evidence.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * **THERE IS NO CONTROL HERE THAT PUTS THE MODEL'S WORDS BACK INTO THE FIELD**, on any verb, and
 * the reasoning is written out in full in `AiVerbReviewDialog`'s header. In short: the server cannot
 * express that write (`DwStageEntry` is absent from `WRITABLE_TABLES`), a RICH_TEXT field is
 * compared across surfaces, and a clipboard button is a paste button with one extra keystroke. This
 * component's source is read by `frontend/e2e/ai-verbs-unit.spec.ts` and the spec fails if one
 * appears.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * FIVE STATES BEFORE THE PRESS, IN THE ORDER THE CODE CHECKS THEM, AND EACH ONE STATES ITS REASON
 * RATHER THAN LEAVING A GREYED CONTROL. `AiLayersPanel`'s rule 3 applied one screen earlier: *"a
 * control offered into a certain refusal teaches designers that refusals are noise — after which the
 * one that matters is clicked through too."* A greyed button with no sentence beside it is the same
 * lesson taught more slowly. (This list used to be in a different order from the ternary under it,
 * which is a small thing until the order is the fix — see rung 2.)
 *
 *   1. The draft is still being read. Inert, and SILENT rather than refusing.
 *   2. This workshop's consent is not GRANTED — a 409, never rendered as a permission problem,
 *      because a colleague of the same rank would be refused identically. The fix is on the
 *      workshop's own screen and this links to it.
 *   3. This workshop has no server copy at all, so every route it could call answers 404 — see
 *      `WORKSHOP_NOT_ON_SERVER_YET`, which records the defect this rung was added for, and
 *      `verbWorkshopRefusal` for why it sits below the consent rung rather than above it.
 *   4. Nothing is selected, or the selection is longer than the server will accept. This surface's
 *      own two rungs; the other two surfaces have no selection.
 *   5. Today's allowance is spent, in the server's own words.
 *
 * Rungs 1-3 and rung 5 are `verbWorkshopRefusal` and `verbAllowanceRefusal`, shared with the other
 * two surfaces so that one rule cannot acquire three orderings.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * **THE ROUTE PARAM IS NOT THE ID THAT GOES ON THE WIRE.** `workshopId` here is whatever is in the
 * URL, which is the `dwlocal-…` draft id for the whole life of a draft AND for the rest of the
 * session after it syncs. Every call below therefore uses `consent.serverId`, which is
 * `draft.remoteId` resolved off the same IndexedDB read the consent comes from; only the LINKS use
 * `workshopId`, because those are routes in this app and the local id is the right one there.
 *
 * AND AT `aiVerbsRemaining <= 3` A COUNTDOWN, but at `aiVerbsRemaining === null` NONE AT ALL. That
 * is `Dictation.tsx`'s existing guard (`dictationsRemaining !== null`) and it is load-bearing: "0
 * remaining" and "no ceiling" must not look alike, which is exactly what the obvious `?? 0` makes
 * them.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Loader2, Sparkles } from "lucide-react";

import { Dropdown } from "@/components/ui/Dropdown";
import { useWorkshopConsent } from "@/components/hooks/useWorkshopConsent";
import { AiVerbReviewDialog } from "@/components/designworkshop/AiVerbReviewDialog";
import {
  AI_VERB_COUNTDOWN_FROM,
  MAX_VERB_LANGUAGE_CHARS,
  MAX_VERB_TEXT_CHARS,
  NOTHING_SELECTED,
  VERBS_NEED_A_CONNECTION,
  WORKSHOP_NOT_ON_SERVER_YET,
  aiLayerProblem,
  dwAiVerbAllowance,
  expandDesignWorkshopNote,
  isVerbOffline,
  passageTooLong,
  proofreadDesignWorkshopText,
  translateDesignWorkshopText,
  translationTargetRefusal,
  verbAllowanceRefusal,
  verbWorkshopRefusal,
  type DwAiVerbAllowanceState,
  type DwAiVerbResult
} from "@/lib/aiVerbs";

/**
 * The three items, worded as what the designer is asking for rather than as the kind that results.
 *
 * "Write this note out" and not "expand": the verb's whole risk is that it INVENTS, and "expand"
 * reads as a note that got longer. The heading on the result says the strongest true thing about it
 * ("Prose written by AI from a designer's note") and this says the plainest true thing about the
 * request.
 */
const MENU_OPTIONS = [
  { value: "PROOFREAD", label: "Proofread this passage" },
  { value: "EXPAND", label: "Write this note out" },
  { value: "TRANSLATE", label: "Translate this passage" }
];

export type AiVerbSelectionMenuProps = {
  workshopId: string;
  /** The editor is read-only, or a write is in flight elsewhere. */
  disabled?: boolean;
  /** How many characters are selected. In the toolbar's signature, so the control re-renders in step. */
  selectionChars: number;
  /**
   * The selected text, read LAZILY at the moment of the press.
   *
   * A callback and not a string, deliberately: a 20,000-character passage rebuilt on every arrow key
   * is the stutter `refreshChrome`'s signature exists to prevent. Only the LENGTH travels through
   * the render path; the words are fetched once, here, when somebody actually asks for them.
   */
  readPassage: () => string;
};

export function AiVerbSelectionMenu({ workshopId, disabled, selectionChars, readPassage }: AiVerbSelectionMenuProps) {
  // Read here rather than threaded through the editor — see `useWorkshopConsent` for why, and for
  // why nothing may be drawn from it until `ready`.
  const consent = useWorkshopConsent(workshopId);
  const [allowance, setAllowance] = useState<DwAiVerbAllowanceState | null>(null);
  const [running, setRunning] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [result, setResult] = useState<DwAiVerbResult | null>(null);
  const [sent, setSent] = useState<string | null>(null);
  /** The second step of translate: the target language, and the refusal for what is typed so far. */
  const [target, setTarget] = useState<string | null>(null);

  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  /**
   * Ask the ceiling once, so a designer learns it without spending a run to be refused by it.
   *
   * `dwAiVerbAllowance` never throws and de-dupes concurrent callers, so a stage drawing a dozen
   * narrative fields asks once between all of them — and every one of them then shows the SAME
   * number, which is the second property that dedupe buys. A null answer means the deployment does
   * not offer the pre-flight route (it does not exist yet — see that function) or there is no
   * connection; either way nothing is disabled on a ceiling nobody can see.
   */
  useEffect(() => {
    void dwAiVerbAllowance().then((answer) => {
      if (mounted.current) setAllowance(answer);
    });
  }, []);

  const serverId = consent.serverId;

  const run = useCallback(
    async (verb: "PROOFREAD" | "EXPAND" | "TRANSLATE", targetLanguage?: string) => {
      // The same re-check-at-the-press argument as the passage below, and for a sharper reason: this
      // component outlives the sync that gives a workshop its server id (the stage page keeps its
      // mount across it), so an id read at mount is a fact that can change under a control that is
      // still on screen. Reading it here means the press uses whatever is true now.
      if (!serverId) {
        setProblem(WORKSHOP_NOT_ON_SERVER_YET);
        return;
      }
      const passage = readPassage();
      // Re-checked at the press and not only at the render: the selection can change between the
      // toolbar's last re-render and the menu closing, and a body the server would 422 must not
      // leave here.
      if (!passage.trim()) {
        setProblem(NOTHING_SELECTED);
        return;
      }
      if (passage.length > MAX_VERB_TEXT_CHARS) {
        setProblem(passageTooLong(passage.length));
        return;
      }
      setRunning(true);
      setProblem(null);
      try {
        const answer =
          verb === "PROOFREAD"
            ? await proofreadDesignWorkshopText(serverId, { text: passage })
            : verb === "EXPAND"
              ? await expandDesignWorkshopNote(serverId, passage)
              : await translateDesignWorkshopText(serverId, { text: passage }, targetLanguage ?? "");
        if (!mounted.current) return;
        setSent(passage);
        setResult(answer);
        setTarget(null);
        /*
          THE ALLOWANCE IS RE-READ FROM THE ANSWER, NEVER DECREMENTED BY ONE.

          A cap counter can move without a layer appearing — `_count_refused_run` spends the
          allowance for any run that reached a provider and then failed, "because the credit is spent
          by the call" — so a client that subtracted one per success would drift low and, worse,
          would report a count that disagreed with the server's the moment anything failed. The 201
          carries `allowance_payload` for exactly this.
        */
        setAllowance((current) =>
          current
            ? {
                ...current,
                aiVerbsLimit: answer.aiVerbsLimit,
                aiVerbsUsed: answer.aiVerbsUsed,
                aiVerbsRemaining: answer.aiVerbsRemaining,
                aiVerbDay: answer.aiVerbDay,
                aiVerbsByVerb: answer.aiVerbsByVerb,
                // The refusal is the SERVER's sentence and this client may not author one, so a
                // fresh 201 clears it rather than composing a replacement.
                refusal: null
              }
            : null
        );
      } catch (err) {
        if (!mounted.current) return;
        // The server's own sentence, verbatim, whatever the status — a 409 consent refusal, a 429
        // cap refusal, a 422 placement refusal and a 503 "no key is configured" all already name the
        // next move. Only a genuine loss of signal is answered in this client's words, because the
        // server said nothing.
        setProblem(isVerbOffline(err) ? VERBS_NEED_A_CONNECTION : aiLayerProblem(err, "That could not be done."));
        /*
          AND THE ALLOWANCE IS RE-READ RATHER THAN ASSUMED UNCHANGED, for the reason above: a
          designer can watch their remaining count fall by one and still get a 422, because the run
          reached a provider before it failed. Leaving the old number on screen would tell them a run
          they paid for did not happen.
        */
        void dwAiVerbAllowance().then((fresh) => {
          if (mounted.current && fresh) setAllowance(fresh);
        });
      } finally {
        if (mounted.current) setRunning(false);
      }
    },
    [readPassage, serverId]
  );

  /* ── The five pre-press states, in the server's own order ─────────────────── */

  const granted = consent.decision.trim().toUpperCase() === "GRANTED";
  const tooLong = selectionChars > MAX_VERB_TEXT_CHARS;
  const nothingSelected = selectionChars === 0;

  /*
    THE THREE WORKSHOP-LEVEL RUNGS COME FROM `verbWorkshopRefusal`, WHICH ALL THREE SURFACES SHARE —
    the reading state, the consent state and the no-server-copy state, in that order, for the reasons
    written out there. `??` and not `||`, because "" is a rung and not an absence of one: it means
    "still reading", it must keep the control inert, and `||` would fall through it to the selection
    rungs and offer the menu on a workshop whose consent has not been read yet.

    The two selection rungs are this surface's own — a menu that runs over a passage — and the
    ceiling is last on every surface.
  */
  const blocked =
    verbWorkshopRefusal({ ready: consent.ready, serverId, decision: consent.decision }) ??
    (nothingSelected ? NOTHING_SELECTED : tooLong ? passageTooLong(selectionChars) : verbAllowanceRefusal(allowance));

  return (
    <div className="grid gap-1.5">
      <div className="flex flex-wrap items-center gap-2">
        <Sparkles className="h-3.5 w-3.5 text-purple-700" aria-hidden />
        {running ? (
          <span className="inline-flex items-center gap-1.5 text-xs font-medium text-ink-500">
            <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
            Working on the passage…
          </span>
        ) : (
          <Dropdown
            value=""
            // A menu of ACTIONS rather than a field of values, which is what `advanceOnSelect: false`
            // is for — moving focus to the next field after choosing "Proofread this passage" would
            // take the caret out of the very document the verb is about to run over.
            advanceOnSelect={false}
            options={MENU_OPTIONS}
            placeholder="Ask AI about this passage"
            ariaLabel="Ask AI about the selected passage"
            className="min-w-56"
            disabled={Boolean(disabled) || blocked !== null}
            onChange={(value) => {
              if (value === "TRANSLATE") {
                // A SECOND STEP, because `targetLanguage` is required and is a choice only the
                // designer can make. `sourceLanguage` is deliberately not asked for: it is an
                // OBSERVATION the run may already have made, and the server records what it detected
                // rather than defaulting it to English.
                setTarget("");
                return;
              }
              void run(value as "PROOFREAD" | "EXPAND");
            }}
          />
        )}
        {/* THE COUNTDOWN, AND ONLY WHERE THERE IS A CEILING TO COUNT DOWN FROM. */}
        {allowance && allowance.aiVerbsRemaining !== null && allowance.aiVerbsRemaining <= AI_VERB_COUNTDOWN_FROM ? (
          <span className="text-xs font-medium text-amber-800">
            {allowance.aiVerbsRemaining} run{allowance.aiVerbsRemaining === 1 ? "" : "s"} left today (
            {allowance.aiVerbDay})
          </span>
        ) : null}
      </div>

      {target !== null ? (
        <div className="grid gap-1.5 rounded-md border border-line-200 bg-surface-50 px-3 py-2">
          <label className="field-label" htmlFor="ai-verb-target-language">
            Translate into
          </label>
          <input
            id="ai-verb-target-language"
            className="field-input"
            value={target}
            maxLength={MAX_VERB_LANGUAGE_CHARS}
            placeholder="Odia, Hindi, English…"
            onChange={(event) => setTarget(event.target.value)}
          />
          {/* THE COPY EXPLAINS THE BOUND RATHER THAN A REGEX. `_LANGUAGE_TOKEN` bounds the SHAPE by
              construction, and there is deliberately no closed list of languages — this fleet works
              in nineteen and several of them (Marwari, Garhwali) have no code to name, so a list
              would refuse the exact languages this system exists to record. */}
          <p className="text-xs leading-5 text-ink-500">
            A name or a code — “Odia”, “or”, “English”. There is no list to choose from on purpose: several of the
            languages in these recordings have no code at all. The original stays exactly where it is; a translation
            stands beside it.
          </p>
          {translationTargetRefusal(target) && target.trim() ? (
            <p className="text-xs leading-5 text-error-600">{translationTargetRefusal(target)}</p>
          ) : null}
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className="field-button"
              disabled={running || translationTargetRefusal(target) !== null}
              onClick={() => void run("TRANSLATE", target.trim())}
            >
              Translate this passage
            </button>
            <button type="button" className="field-button-secondary" disabled={running} onClick={() => setTarget(null)}>
              Cancel
            </button>
          </div>
        </div>
      ) : null}

      {/* THE REASON, IN PLACE OF THE CONTROL'S SILENCE. Never a tooltip: a tooltip does not exist on
          a touch screen and is not read by anybody who did not already suspect there was something
          to read. */}
      {blocked ? (
        <p className="text-xs leading-5 text-ink-500">
          {blocked}
          {consent.ready && !granted ? (
            <>
              {" "}
              <Link href={`/design-workshops/${workshopId}`} className="font-medium text-purple-700 underline">
                Open the workshop&apos;s own screen
              </Link>
              .
            </>
          ) : null}
        </p>
      ) : null}

      {problem ? (
        <p role="alert" className="text-xs leading-5 text-error-600">
          {problem}
        </p>
      ) : null}

      <AiVerbReviewDialog
        open={result !== null}
        // THE SERVER'S ID, because accept, decline and the subtitle download are all server routes —
        // the route param would 404 on all three from a stage still open under its `dwlocal-…` URL.
        // The fallback is unreachable (a `result` exists only where a run succeeded, which needs a
        // server id) and is here so this stays a `string` without an assertion.
        workshopId={serverId ?? workshopId}
        result={result}
        sourceText={sent}
        // The layer is on the AI layers screen either way; there is no list on this surface to
        // refresh, and the editor's own document is deliberately untouched by every outcome.
        onAccepted={() => setResult(null)}
        onDeclined={() => setResult(null)}
        onClose={() => setResult(null)}
      />
    </div>
  );
}
