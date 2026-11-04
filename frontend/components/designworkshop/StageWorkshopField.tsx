"use client";

import { useEffect, useMemo, useState } from "react";

import { loadAccessibleWorkshops, workshopOccurrenceDate } from "@/components/forms/WorkshopSelect";
import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { inputValue, type DwField, type DwValue } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import type { Workshop } from "@/lib/types";

const NOT_RECORDED_LABEL = "Not recorded";

type TitleOption = {
  title: string;
  when: string;
  count: number;
};

function distinctTitles(workshops: Workshop[], maxLength: number | undefined): { options: TitleOption[]; withheld: number } {
  const byTitle = new Map<string, TitleOption>();
  let withheld = 0;
  for (const workshop of workshops) {
    const title = workshop.title?.trim();
    if (!title) continue;
    if (maxLength && title.length > maxLength) {
      withheld += 1;
      continue;
    }
    const existing = byTitle.get(title);
    if (existing) {
      existing.count += 1;
      continue;
    }
    byTitle.set(title, { title, when: formatDate(workshopOccurrenceDate(workshop) || null), count: 1 });
  }
  return { options: [...byTitle.values()], withheld };
}

export function StageWorkshopField({
  field,
  value,
  onChange,
  labelId,
  describedBy,
  invalid,
  disabled,
  dictation
}: {
  field: DwField;
  value: DwValue | undefined;
  onChange: (next: DwValue) => void;
  labelId: string;
  describedBy?: string;
  invalid?: boolean;
  disabled?: boolean;
  dictation?: (control: React.ReactNode) => React.ReactNode;
}) {
  const [workshops, setWorkshops] = useState<Workshop[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [typing, setTyping] = useState(false);

  useEffect(() => {
    let cancelled = false;
    loadAccessibleWorkshops()
      .then((rows) => {
        if (!cancelled) setWorkshops(rows);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const current = inputValue(value);
  const { options: titles, withheld } = useMemo(
    () => distinctTitles(workshops ?? [], field.maxLength),
    [workshops, field.maxLength]
  );

  const options = useMemo<DropdownOption[]>(() => {
    const rows: DropdownOption[] = [{ value: "", label: NOT_RECORDED_LABEL }];
    if (current && !titles.some((option) => option.title === current)) {
      rows.push({ value: current, label: current, hint: "already on this row" });
    }
    for (const option of titles) {
      rows.push({
        value: option.title,
        label: option.title,
        hint: option.count > 1 ? `${option.count} workshops share this title` : option.when === "-" ? undefined : option.when
      });
    }
    return rows;
  }, [current, titles]);

  if (typing || failed) {
    return (
      <div className="grid gap-1">
        {(dictation ?? ((control: React.ReactNode) => control))(
          <input
            className="field-input"
            type="text"
            maxLength={field.maxLength || undefined}
            aria-labelledby={labelId}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            value={current}
            disabled={disabled}
            onChange={(event) => onChange(event.target.value)}
          />
        )}
        {failed ? (
          <p className="text-xs text-ink-500">
            Workshops didn&apos;t load. Type the title, or reload.
          </p>
        ) : (
          <button
            type="button"
            className="justify-self-start text-xs font-medium text-purple-700 dark:text-purple-300 underline underline-offset-2"
            onClick={() => setTyping(false)}
            disabled={disabled}
          >
            Choose from the list of workshops instead
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="grid gap-1">
      <Dropdown
        value={current}
        onChange={onChange}
        options={options}
        placeholder={workshops === null ? "Loading workshops…" : "Choose the workshop"}
        emptyLabel="No workshops are open to this account"
        disabled={disabled || workshops === null}
        ariaLabel={field.label}
        describedBy={describedBy}
        searchable
      />
      <p className="text-xs text-ink-500">
        Only workshops you have access to are listed.{" "}
        <button
          type="button"
          className="font-medium text-purple-700 dark:text-purple-300 underline underline-offset-2"
          onClick={() => setTyping(true)}
          disabled={disabled}
        >
          Type a title instead
        </button>{" "}
        if this record was documented at a workshop that is not in the list.
      </p>
      {withheld > 0 ? (
        <p className="text-xs text-ink-500">
          {withheld} workshop{withheld === 1 ? "" : "s"} {withheld === 1 ? "is" : "are"} not listed because{" "}
          {withheld === 1 ? "its title is" : "their titles are"} longer than the {field.maxLength} characters this field
          allows. Type a shortened title instead.
        </p>
      ) : null}
    </div>
  );
}
