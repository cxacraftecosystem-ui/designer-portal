"use client";

import { useEffect, useMemo, useState } from "react";

import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { inputValue, listDesignWorkshops, type DwField, type DwSummary, type DwValue } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import {
  deviceLooksOffline,
  workshopEmptyLabel,
  workshopListNotice,
  WORKSHOP_OPTION_PAGE_SIZE,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";

const SEARCH_DEBOUNCE_MS = 300;

export function workshopNameCreateLabel(term: string) {
  return `Use “${term}” as the name`;
}

export const WORKSHOP_NAME_REASSURANCE =
  "You can still type the name.";

function distinctTitles(
  rows: readonly DwSummary[],
  maxLength: number | undefined
): { titles: { title: string; when: string; count: number }[]; withheld: number } {
  const byTitle = new Map<string, { title: string; when: string; count: number }>();
  let withheld = 0;
  for (const row of rows) {
    const title = row.title?.trim();
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
    byTitle.set(title, { title, when: formatDate(row.startDate ?? row.createdAt ?? null), count: 1 });
  }
  return { titles: [...byTitle.values()], withheld };
}

export function StageWorkshopNameField({
  field,
  value,
  workshopKind,
  onChange,
  labelId,
  describedBy,
  invalid,
  disabled,
  dictation
}: {
  field: DwField;
  value: DwValue | undefined;
  workshopKind: string;
  onChange: (next: DwValue) => void;
  labelId: string;
  describedBy?: string;
  invalid?: boolean;
  disabled?: boolean;
  dictation?: (control: React.ReactNode) => React.ReactNode;
}) {
  const [list, setList] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  const [term, setTerm] = useState("");
  const [pending, setPending] = useState(true);

  const current = inputValue(value);

  useEffect(() => {
    let cancelled = false;
    const trimmed = term.trim();
    setPending(true);
    const timer = window.setTimeout(() => {
      listDesignWorkshops({
        page: 1,
        pageSize: WORKSHOP_OPTION_PAGE_SIZE,
        search: trimmed || undefined,
        workshopKind: workshopKind || undefined
      })
        .then((result) => {
          if (cancelled) return;
          setList({ kind: "ok", rows: result.items ?? [], total: result.total });
        })
        .catch(() => {
          if (!cancelled) setList({ kind: "failed" });
        })
        .finally(() => {
          if (!cancelled) setPending(false);
        });
    }, trimmed ? SEARCH_DEBOUNCE_MS : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [term, workshopKind]);

  const rows = useMemo<readonly DwSummary[]>(() => (list.kind === "ok" ? list.rows : []), [list]);
  const { titles, withheld } = useMemo(
    () => distinctTitles(rows, field.maxLength),
    [rows, field.maxLength]
  );

  const options = useMemo<DropdownOption[]>(() => {
    const built: DropdownOption[] = [];
    if (current && !titles.some((option) => option.title === current)) {
      built.push({ value: current, label: current, hint: "already on this workshop" });
    }
    for (const option of titles) {
      built.push({
        value: option.title,
        label: option.title,
        hint:
          option.count > 1
            ? `${option.count} workshops share this name`
            : option.when === "-"
              ? undefined
              : option.when
      });
    }
    return built;
  }, [current, titles]);

  const voice: WorkshopListVoice = {
    table: "design",
    accessList: false,
    scoped: true,
    reassurance: WORKSHOP_NAME_REASSURANCE,
    online: !deviceLooksOffline()
  };
  const searchedToNothing = list.kind === "ok" && list.rows.length === 0 && term.trim().length > 0;
  const notice = searchedToNothing ? "" : workshopListNotice(list, voice);

  return (
    <div className="grid gap-1">
      {(dictation ?? ((control: React.ReactNode) => control))(
        <Dropdown
          value={current}
          onChange={onChange}
          options={options}
          placeholder="Type the name, or pick one already on record"
          emptyLabel={workshopEmptyLabel(list, voice)}
          disabled={disabled}
          ariaLabel={field.label}
          describedBy={describedBy}
          serverQuery={{ value: term, onChange: setTerm, pending }}
          createAction={{ label: workshopNameCreateLabel, onCreate: onChange }}
        />
      )}
      <p className="text-xs leading-5 text-ink-500">
        {workshopKind ? "Names from workshops of this type." : "Names from workshops you can open."} Type a new one if
        it is not here.
      </p>
      {notice ? <p className="text-xs leading-5 text-ink-500">{notice}</p> : null}
      {withheld > 0 ? (
        <p className="text-xs leading-5 text-ink-500">
          {withheld} name{withheld === 1 ? "" : "s"} not offered: over {field.maxLength} characters.
        </p>
      ) : null}
    </div>
  );
}
