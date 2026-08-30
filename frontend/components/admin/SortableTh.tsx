"use client";

import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react";

import { ResizableTh } from "@/components/ResizableTh";
import {
  nextRosterSort,
  sortActionLabel,
  type RosterFilters,
  type RosterKind
} from "@/components/admin/rosterFilters";

/**
 * A COLUMN HEADER THAT SORTS THE LIST ON THE SERVER, and the first `aria-sort` in this frontend.
 *
 * ── WHAT IT IS BUILT ON, AND WHY IT IS NOT A NEW `<th>` ──────────────────────────────────────────
 *
 * `ResizableTh`, plus a button and one ARIA attribute. Every record table in this app already uses
 * that cell, and its `overflow-hidden` is load-bearing rather than cosmetic — `resize: horizontal`
 * has no effect on an element whose overflow is `visible`, so a hand-rolled `<th>` here would
 * silently take column resizing away from two admin tables. Wrapping it keeps the drag grip, the
 * padding and the resize behaviour identical to every other list on the site, and adds exactly the
 * affordance this control is for.
 *
 * ── THE ACCESSIBLE NAME IS THE COLUMN NAME, AND THE STATE IS `aria-sort` ─────────────────────────
 *
 * The ARIA authoring practice for a sortable table, and the shape DROPDOWN_DESIGN §4.8 asks for:
 * the button's accessible name is just the column's word, and `aria-sort="ascending" |
 * "descending" | "none"` on the `<th>` carries which way it is currently ordered. Putting the state
 * into the button's text instead would make the header cell read differently on every click and
 * would be announced again for every data cell underneath it, which is how a table with fourteen
 * columns becomes unusable with a screen reader.
 *
 * What the name cannot carry is what a click will DO — sort, re-sort or reverse — so that goes in
 * `title`, which becomes the accessible DESCRIPTION rather than the name (text content wins for the
 * name). `sortActionLabel` words it, including the one fact a reader would otherwise diagnose as a
 * broken screen: on a nullable column ordered newest-first, Postgres puts the rows with NO date
 * first, and on `firstSeen` that is not a glitch — it is every outstanding invitation, which is the
 * question the column exists to answer.
 *
 * ── THE ARROW IS A STATE, NOT A DECORATION ──────────────────────────────────────────────────────
 *
 * `ArrowUp` / `ArrowDown` on the sorted column, a dimmed `ArrowUpDown` on the others. Non-negotiable
 * 5 in the frontend contract is about a signal that exists only as motion; the same argument governs
 * a signal that exists only as colour or weight, and this repo's tables are dense enough that "the
 * bold one is the sorted one" is not a signal at all. `aria-hidden` on all three, because the
 * meaning is already in `aria-sort` and repeating it as an unlabelled image is noise.
 *
 * ── IT DOES NOT SORT ANYTHING ITSELF ────────────────────────────────────────────────────────────
 *
 * It hands the page a new {@link RosterFilters} and the page re-requests. Rule (iv): an on-device
 * sort of one page re-orders the rows that happen to be in the browser and calls it an order — so
 * "oldest first" shows the oldest of page one, which is a different and wrong answer, and paging
 * through it walks a list that is re-sorted per page. Android's designer roster did exactly this
 * and its `sortedWith` is deleted in the same wave for the same reason.
 */
export type RosterSortControl = {
  kind: RosterKind;
  filters: RosterFilters;
  /**
   * Where the new filters go.
   *
   * ⚠ RESET THE PAGER TO 1 IN THIS HANDLER. A re-ordered list has different rows at `OFFSET 40`, so
   * a reader who sorts while on page 3 lands somewhere arbitrary in a list they just re-ordered.
   * `nextRosterSort`'s own note says the same; it is repeated here because this is the prop the
   * mistake would be made on.
   */
  onChange: (next: RosterFilters) => void;
};

export function SortableTh({
  control,
  column,
  label,
  className = ""
}: {
  control: RosterSortControl;
  /** The `sort` token this header orders by — one of `ACCESS_SORTS` / `DESIGNER_SORTS`' keys. */
  column: string;
  /** The column's word, as the table shows it. It is the button's whole accessible name. */
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
        // `-my-1 py-1` so the hit area covers the cell's own vertical padding without changing the
        // header's height, and `uppercase`/`tracking` are inherited from the `<thead>` both roster
        // tables already set — a button resets neither, but it does reset `text-align`, so the row
        // would otherwise centre itself out of line with the non-sortable headers beside it.
        className="-my-1 inline-flex max-w-full items-center gap-1.5 rounded py-1 text-left font-medium text-ink-500 transition-colors hover:text-purple-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-600/40"
      >
        <span className="min-w-0 truncate">{label}</span>
        <Icon
          className={`h-3.5 w-3.5 shrink-0 ${active ? "text-purple-700" : "text-ink-300"}`}
          aria-hidden
        />
      </button>
    </ResizableTh>
  );
}
