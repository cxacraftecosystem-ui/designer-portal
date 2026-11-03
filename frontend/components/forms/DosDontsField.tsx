"use client";

import { useId, useState } from "react";

import { NumberedPointRows, joinNumbered, splitNumbered } from "@/components/forms/NumberedListInput";
import { RequiredMark } from "@/components/ui/RequiredMark";

export function DosDontsField({
  name,
  label,
  helper,
  defaultValue,
  required = true,
  fieldKey
}: {
  name: string;
  fieldKey?: string;
  label: string;
  helper?: string;
  defaultValue?: string | null;
  required?: boolean;
}) {
  const [items, setItems] = useState<string[]>(() => splitNumbered(defaultValue));
  const joined = joinNumbered(items);
  const groupId = useId();
  const helperId = `${groupId}-helper`;

  return (
    <div data-field={fieldKey} className="relative grid content-start gap-1.5" role="group" aria-labelledby={groupId} aria-describedby={helper ? helperId : undefined}>
      <span id={groupId} className="field-label">
        {label}
        <RequiredMark when={required} />
      </span>
      {helper ? (
        <p id={helperId} className="text-xs text-ink-muted">
          {helper}
        </p>
      ) : null}
      <NumberedPointRows items={items} onItems={setItems} />
      <textarea
        name={name}
        value={joined}
        required={required}
        onChange={() => undefined}
        tabIndex={-1}
        aria-hidden="true"
        className="pointer-events-none absolute h-0 w-0 resize-none border-0 p-0 opacity-0"
      />
    </div>
  );
}
