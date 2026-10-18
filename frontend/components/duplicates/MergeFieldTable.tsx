"use client";

import { Lock } from "lucide-react";

import { resultingValue, type MergeChoice, type MergeField } from "@/lib/duplicates";

const CHOICE_LABEL: Record<MergeChoice, string> = { A: "A", B: "B", BOTH: "Both" };

/**
 * One row per field: the two records' values and a radio for the side the kept record takes. Locked
 * rows (Aadhaar, Pehchan) come from whichever record holds them and cannot be chosen.
 */
export function MergeFieldTable({
  fields,
  picks,
  onPick,
  disabled
}: {
  fields: MergeField[];
  picks: Record<string, MergeChoice>;
  onPick: (key: string, choice: MergeChoice) => void;
  disabled?: boolean;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[40rem] text-left text-sm">
        <thead className="text-xs uppercase tracking-wide text-ink-500">
          <tr className="border-b border-line-200">
            <th className="px-3 py-2 font-medium">Field</th>
            <th className="px-3 py-2 font-medium">A</th>
            <th className="px-3 py-2 font-medium">B</th>
            <th className="px-3 py-2 font-medium">Keep</th>
          </tr>
        </thead>
        <tbody>
          {fields.map((field) => {
            const pick = picks[field.key] ?? field.default;
            const result = resultingValue(field, pick);
            return (
              <tr key={field.key} className="border-b border-line-200 align-top last:border-0">
                <th scope="row" className="px-3 py-2 font-medium text-ink-900">
                  {field.label}
                </th>
                <td className={`px-3 py-2 whitespace-pre-line ${pick === "A" || pick === "BOTH" ? "text-ink-900" : "text-ink-500"}`}>
                  {field.a ?? <span className="text-ink-300">—</span>}
                </td>
                <td className={`px-3 py-2 whitespace-pre-line ${pick === "B" || pick === "BOTH" ? "text-ink-900" : "text-ink-500"}`}>
                  {field.b ?? <span className="text-ink-300">—</span>}
                </td>
                <td className="px-3 py-2">
                  {field.locked ? (
                    <span className="inline-flex items-center gap-1 text-xs text-ink-500">
                      <Lock className="h-3.5 w-3.5" aria-hidden />
                      {result ? `From ${pick}` : "None"}
                    </span>
                  ) : (
                    <fieldset className="flex flex-wrap gap-3" disabled={disabled}>
                      <legend className="sr-only">{field.label}</legend>
                      {field.options.map((option) => (
                        <label key={option} className="inline-flex min-h-8 cursor-pointer items-center gap-1.5 text-sm text-ink-700">
                          <input
                            type="radio"
                            name={`merge-${field.key}`}
                            value={option}
                            checked={pick === option}
                            onChange={() => onPick(field.key, option)}
                            className="h-4 w-4 accent-purple-700"
                          />
                          {CHOICE_LABEL[option]}
                        </label>
                      ))}
                    </fieldset>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
