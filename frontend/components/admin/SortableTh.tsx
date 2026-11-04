"use client";

import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react";

import { ResizableTh } from "@/components/ResizableTh";
import {
  nextRosterSort,
  sortActionLabel,
  type RosterFilters,
  type RosterKind
} from "@/components/admin/rosterFilters";

export type RosterSortControl = {
  kind: RosterKind;
  filters: RosterFilters;
  onChange: (next: RosterFilters) => void;
};

export function SortableTh({
  control,
  column,
  label,
  className = ""
}: {
  control: RosterSortControl;
  column: string;
  label: string;
  className?: string;
}) {
  const { kind, filters, onChange } = control;
  const active = filters.sort === column;
  const Icon = active ? (filters.dir === "asc" ? ArrowUp : ArrowDown) : ArrowUpDown;

  return (
    <ResizableTh
      aria-sort={active ? (filters.dir === "asc" ? "ascending" : "descending") : "none"}
      className={className}
    >
      <button
        type="button"
        title={sortActionLabel(kind, filters, column, label)}
        onClick={() => onChange(nextRosterSort(kind, filters, column))}
        className="-my-1 inline-flex max-w-full items-center gap-1.5 rounded py-1 text-left font-medium text-ink-500 transition-colors hover:text-purple-700 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-600/40"
      >
        <span className="min-w-0 truncate">{label}</span>
        <Icon
          className={`h-3.5 w-3.5 shrink-0 ${active ? "text-purple-700 dark:text-purple-300" : "text-ink-300"}`}
          aria-hidden
        />
      </button>
    </ResizableTh>
  );
}
