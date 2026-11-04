"use client";

import { useId } from "react";

import { RichTextField } from "@/components/richtext/RichTextField";
import { decodeStoredRichText, plainFromStoredRichText } from "@/components/richtext/storedRichText";

export function AnswerTextEditor({
  value,
  onChange,
  onCommit,
  readOnly,
  ariaLabel,
  required
}: {
  value: string;
  onChange: (value: string) => void;
  onCommit: () => void;
  readOnly?: boolean;
  ariaLabel: string;
  required?: boolean;
}) {
  const labelId = useId();
  return (
    <div
      className="grid gap-1"
      data-answer-editor
      data-locked={readOnly || undefined}
      aria-required={required || undefined}
      onBlur={(event) => {
        const next = event.relatedTarget as Node | null;
        if (next && event.currentTarget.contains(next)) return;
        onCommit();
      }}
    >
      <span id={labelId} className="sr-only">
        {ariaLabel}
      </span>
      <RichTextField
        name="answer"
        label={ariaLabel}
        labelledBy={labelId}
        defaultValue={value}
        disabled={readOnly}
        explainWhenUnavailable={false}
        onValueChange={onChange}
      />
    </div>
  );
}

export function answerParts(stored: string): { text: string; richText: string | null } {
  const decoded = decodeStoredRichText(stored);
  if (decoded && typeof decoded === "object") return { text: plainFromStoredRichText(stored), richText: stored };
  return { text: stored, richText: null };
}

export function answerEditorValue(answer: { text?: string | null; richText?: unknown } | null | undefined): string {
  const rich = answer?.richText;
  if (typeof rich === "string" && rich.trim()) return rich;
  if (rich && typeof rich === "object") return JSON.stringify(rich);
  return answer?.text ?? "";
}
