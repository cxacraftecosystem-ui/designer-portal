"use client";

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
  CONSENT_LINK_LABEL,
  consentRefused,
  type DwAiVerbAllowanceState,
  type DwAiVerbResult
} from "@/lib/aiVerbs";
import { ActionGroup } from "@/components/ui/ActionGroup";

const MENU_OPTIONS = [
  { value: "PROOFREAD", label: "Proofread this passage" },
  { value: "EXPAND", label: "Write this note out" },
  { value: "TRANSLATE", label: "Translate this passage" }
];

export type AiVerbSelectionMenuProps = {
  workshopId: string;
  disabled?: boolean;
  selectionChars: number;
  readPassage: () => string;
};

export function AiVerbSelectionMenu({ workshopId, disabled, selectionChars, readPassage }: AiVerbSelectionMenuProps) {
  const consent = useWorkshopConsent(workshopId);
  const [allowance, setAllowance] = useState<DwAiVerbAllowanceState | null>(null);
  const [running, setRunning] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [result, setResult] = useState<DwAiVerbResult | null>(null);
  const [sent, setSent] = useState<string | null>(null);
  const [target, setTarget] = useState<string | null>(null);

  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    void dwAiVerbAllowance().then((answer) => {
      if (mounted.current) setAllowance(answer);
    });
  }, []);

  const serverId = consent.serverId;

  const run = useCallback(
    async (verb: "PROOFREAD" | "EXPAND" | "TRANSLATE", targetLanguage?: string) => {
      if (!serverId) {
        setProblem(WORKSHOP_NOT_ON_SERVER_YET);
        return;
      }
      const passage = readPassage();
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
        setAllowance((current) =>
          current
            ? {
                ...current,
                aiVerbsLimit: answer.aiVerbsLimit,
                aiVerbsUsed: answer.aiVerbsUsed,
                aiVerbsRemaining: answer.aiVerbsRemaining,
                aiVerbDay: answer.aiVerbDay,
                aiVerbsByVerb: answer.aiVerbsByVerb,
                refusal: null
              }
            : null
        );
      } catch (err) {
        if (!mounted.current) return;
        setProblem(isVerbOffline(err) ? VERBS_NEED_A_CONNECTION : aiLayerProblem(err, "That could not be done."));
        void dwAiVerbAllowance().then((fresh) => {
          if (mounted.current && fresh) setAllowance(fresh);
        });
      } finally {
        if (mounted.current) setRunning(false);
      }
    },
    [readPassage, serverId]
  );

  const refused = consentRefused(consent.decision);
  const tooLong = selectionChars > MAX_VERB_TEXT_CHARS;
  const nothingSelected = selectionChars === 0;

  const blocked =
    verbWorkshopRefusal({ ready: consent.ready, serverId, decision: consent.decision }) ??
    (nothingSelected ? NOTHING_SELECTED : tooLong ? passageTooLong(selectionChars) : verbAllowanceRefusal(allowance));

  return (
    <div className="grid gap-1.5">
      <div className="flex flex-wrap items-center gap-2">
        <Sparkles className="h-3.5 w-3.5 text-purple-700 dark:text-purple-300" aria-hidden />
        {running ? (
          <span className="inline-flex items-center gap-1.5 text-xs font-medium text-ink-500">
            <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden />
            Working on the passage…
          </span>
        ) : (
          <Dropdown
            value=""
            advanceOnSelect={false}
            options={MENU_OPTIONS}
            placeholder="Ask AI about this passage"
            ariaLabel="Ask AI about the selected passage"
            className="min-w-56"
            disabled={Boolean(disabled) || blocked !== null}
            onChange={(value) => {
              if (value === "TRANSLATE") {
                setTarget("");
                return;
              }
              void run(value as "PROOFREAD" | "EXPAND");
            }}
          />
        )}
        {allowance && allowance.aiVerbsRemaining !== null && allowance.aiVerbsRemaining <= AI_VERB_COUNTDOWN_FROM ? (
          <span className="text-xs font-medium text-amber-800 dark:text-amber-100">
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
          <p className="text-xs leading-5 text-ink-500">
            A name or code, e.g. “Odia”. The original is kept.
          </p>
          {translationTargetRefusal(target) && target.trim() ? (
            <p className="text-xs leading-5 text-error-600">{translationTargetRefusal(target)}</p>
          ) : null}
          <ActionGroup>
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
          </ActionGroup>
        </div>
      ) : null}

      {blocked ? (
        <p className="text-xs leading-5 text-ink-500">
          {blocked}
          {consent.ready && refused ? (
            <>
              {" "}
              <Link href={`/design-workshops/${workshopId}#consent`} className="font-medium text-purple-700 dark:text-purple-300 underline">
                {CONSENT_LINK_LABEL}
              </Link>
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
        workshopId={serverId ?? workshopId}
        result={result}
        sourceText={sent}
        onAccepted={() => setResult(null)}
        onDeclined={() => setResult(null)}
        onClose={() => setResult(null)}
      />
    </div>
  );
}
