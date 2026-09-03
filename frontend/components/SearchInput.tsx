"use client";

import { Search, X } from "lucide-react";

/**
 * Themed search bar: Search icon on the left, a red clear (X) button inside the bar while
 * there is a query, and Enter runs `onSubmit` (e.g. fires the list reload).
 *
 * ── IT HAS AN ACCESSIBLE NAME OF ITS OWN NOW — 2026-09-03 ──────────────────────────────────────
 *
 * The box carried `role="searchbox"` and no label at all, so its accessible name came from the
 * PLACEHOLDER — a fallback in the name computation, not a label. Two things follow from that and
 * both were live here. A placeholder is announced as the name only where the box is empty, so a
 * screen-reader user who tabbed back to a filled search box was told "searchbox" and nothing else;
 * and several call sites pass the bare default "Search", which names the control and not the
 * corpus, on pages that hold more than one list. The magnifier beside it is `aria-hidden`, so
 * nothing else on the control carries the subject.
 *
 * `aria-label={ariaLabel ?? placeholder}` — the placeholder is a sensible DEFAULT name where a call
 * site has already written a specific one ("Search artisans by name, craft or place"), and
 * `ariaLabel` is for the sites whose placeholder is generic or whose corpus is only obvious from
 * the page around it. Keep both terse; this is a name, not a sentence.
 *
 * NOT a `<label>` element: this control is dropped into flex rows and toolbars all over the app and
 * a visible label would change every one of those layouts. `aria-label` is the right instrument for
 * a control whose purpose is already visually obvious from its position and its icon.
 */
export function SearchInput({
  value,
  onChange,
  onSubmit,
  placeholder = "Search",
  ariaLabel
}: {
  value: string;
  onChange: (v: string) => void;
  onSubmit?: () => void;
  placeholder?: string;
  /** The control's accessible name. Defaults to {@link placeholder}. Name the corpus, tersely. */
  ariaLabel?: string;
}) {
  return (
    <div className="relative">
      <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-300" aria-hidden />
      <input
        type="text"
        role="searchbox"
        aria-label={ariaLabel ?? placeholder}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.preventDefault();
            onSubmit?.();
          }
        }}
        className="field-input pl-9 pr-9"
      />
      {value ? (
        <button
          type="button"
          aria-label="Clear search"
          title="Clear search"
          onClick={() => onChange("")}
          className="absolute right-2 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded-full text-error-600 transition hover:bg-error-100"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      ) : null}
    </div>
  );
}
