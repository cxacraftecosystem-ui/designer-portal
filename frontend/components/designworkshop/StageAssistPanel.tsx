"use client";

/**
 * Suggestions for this stage, one tap each — the server's `stage_assist`, drawn.
 *
 * Owner decisions of 2026-10-17 (A14, A16, B10, C6, C7, C10): the stage already knows these answers,
 * so it offers them. Nothing is written without the tap, only blank boxes are filled (a "Use ₹X"
 * over a contradicting subtotal is the one exception, and it says what it replaces), and every value
 * stays editable afterwards. Hidden when there is nothing to offer.
 */

import { Lightbulb } from "lucide-react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import type { DwEntryData, DwRow } from "@/lib/designWorkshops";
import { stillApplies, type DwAssistSuggestion } from "@/lib/stageAssist";

export function StageAssistPanel({
  suggestions,
  singleton,
  collections,
  onApply,
  disabled = false
}: {
  suggestions: readonly DwAssistSuggestion[];
  singleton: DwEntryData;
  collections: Record<string, DwRow[]>;
  onApply: (suggestion: DwAssistSuggestion) => void;
  disabled?: boolean;
}) {
  const live = suggestions.filter((s) => stillApplies(s, singleton, collections));
  if (!live.length) return null;
  return (
    <section className="panel grid gap-3 p-4" aria-labelledby="stage-assist-heading">
      <h2 id="stage-assist-heading" className="flex items-center gap-2 text-sm font-semibold text-ink-900">
        <Lightbulb className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
        Suggestions
      </h2>
      <ul className="grid gap-2">
        {live.map((suggestion) => (
          <li
            key={suggestion.id}
            className="flex flex-col gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 sm:flex-row sm:items-center"
          >
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-ink-900">
                {suggestion.title}
                {suggestion.due ? (
                  <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">Due</span>
                ) : null}
              </p>
              {suggestion.detail ? <p className="text-xs leading-5 text-ink-500">{suggestion.detail}</p> : null}
            </div>
            <ActionGroup inline align="end" className="max-sm:w-full">
              <button
                type="button"
                className="field-button-secondary"
                disabled={disabled}
                onClick={() => onApply(suggestion)}
              >
                {actionWord(suggestion)}
              </button>
            </ActionGroup>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function actionWord(suggestion: DwAssistSuggestion): string {
  if (suggestion.due) return "Record";
  if (suggestion.kind === "ADD_ROWS") return suggestion.rows && suggestion.rows.length > 1 ? `Add ${suggestion.rows.length}` : "Add";
  return "Use";
}
