"use client";

import { Plus, X } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";

export function splitNumbered(value: string | null | undefined): string[] {
  const rows = (value ?? "")
    .split("\n")
    .map((row) => row.trim())
    .filter(Boolean);
  return rows.length ? rows : [""];
}

export function joinNumbered(items: string[]): string {
  return items
    .map((row) => row.trim())
    .filter(Boolean)
    .join("\n");
}

export function NumberedPointRows({
  items,
  onItems,
  disabled,
  onFocusRow,
  describedBy,
  invalid
}: {
  items: string[];
  onItems: (next: string[]) => void;
  disabled?: boolean;
  onFocusRow?: (index: number) => void;
  describedBy?: string;
  invalid?: boolean;
}) {
  const rowRefs = useRef<Array<HTMLInputElement | null>>([]);

  function update(next: string[]) {
    onItems(next.length ? next : [""]);
  }

  function focusRow(index: number) {
    requestAnimationFrame(() => rowRefs.current[index]?.focus());
  }

  function changeRow(index: number, raw: string) {
    if (raw.includes("\n")) {
      const segments = raw.split("\n");
      const next = [...items];
      next[index] = segments[0].trim();
      next.splice(index + 1, 0, ...segments.slice(1).map((segment) => segment.trim()));
      update(next);
    } else {
      update(items.map((item, j) => (j === index ? raw : item)));
    }
  }

  function addRowAfter(index: number) {
    const next = [...items];
    next.splice(index + 1, 0, "");
    update(next);
    focusRow(index + 1);
  }

  return (
    <>
      {items.map((item, index) => (
        <div key={index} className="flex items-center gap-2">
          <span aria-hidden className="w-5 shrink-0 text-right text-sm text-ink-muted">
            {index + 1}.
          </span>
          <input
            ref={(el) => {
              rowRefs.current[index] = el;
            }}
            className="field-input min-w-0 flex-1"
            type="text"
            aria-label={`Point ${index + 1}`}
            aria-describedby={index === 0 ? describedBy : undefined}
            aria-invalid={index === 0 && invalid ? true : undefined}
            value={item}
            disabled={disabled}
            onFocus={() => onFocusRow?.(index)}
            onChange={(event) => changeRow(index, event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                addRowAfter(index);
              }
            }}
          />
          {items.length > 1 ? (
            <button
              type="button"
              aria-label={`Remove point ${index + 1}`}
              className="shrink-0 rounded-md p-2 text-ink-muted transition hover:bg-field-100 hover:text-error-600"
              disabled={disabled}
              onClick={() => update(items.filter((_, j) => j !== index))}
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          ) : null}
        </div>
      ))}
      <button
        type="button"
        className="field-button-secondary inline-flex items-center gap-1 justify-self-start"
        disabled={disabled}
        onClick={() => addRowAfter(items.length - 1)}
      >
        <Plus className="h-4 w-4" aria-hidden />
        Add point
      </button>
    </>
  );
}

export function NumberedListField({
  value,
  onChange,
  labelId,
  disabled,
  describedBy,
  invalid,
  renderDictation
}: {
  value: string;
  onChange: (next: string) => void;
  labelId: string;
  disabled?: boolean;
  describedBy?: string;
  invalid?: boolean;
  renderDictation?: (commit: (text: string) => void) => ReactNode;
}) {
  const [items, setItems] = useState<string[]>(() => splitNumbered(value));
  const [lastEmitted, setLastEmitted] = useState(value);
  const [focusedRow, setFocusedRow] = useState(0);

  useEffect(() => {
    if (value === lastEmitted) return;
    setLastEmitted(value);
    setItems(splitNumbered(value));
  }, [value, lastEmitted]);

  function commit(next: string[]) {
    setItems(next);
    const joined = joinNumbered(next);
    setLastEmitted(joined);
    onChange(joined);
  }

  function dictateInto(text: string) {
    const index = Math.min(Math.max(focusedRow, 0), items.length - 1);
    const existing = items[index] ?? "";
    const joiner = !existing || /\s$/.test(existing) ? "" : " ";
    commit(items.map((item, j) => (j === index ? `${existing}${joiner}${text}` : item)));
  }

  return (
    <div className="grid content-start gap-1.5" role="group" aria-labelledby={labelId}>
      <NumberedPointRows
        items={items}
        onItems={commit}
        disabled={disabled}
        describedBy={describedBy}
        invalid={invalid}
        onFocusRow={setFocusedRow}
      />
      {renderDictation ? renderDictation(dictateInto) : null}
    </div>
  );
}
