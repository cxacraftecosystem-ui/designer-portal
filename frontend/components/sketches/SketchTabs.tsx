"use client";

/**
 * The two tabs at the top of Sketches & Prototypes — UPLOAD and REVIEW.
 *
 * ── WHY A COMPONENT AND NOT TWO BUTTONS IN THE PAGE ─────────────────────────────────────────────
 *
 * There are three ad-hoc `role="tablist"` strips in this app already (`/data` twice, `/settings/
 * tasks` once) and none of them is keyboard-operable in the way the pattern requires: they are
 * buttons with `aria-selected`, so every tab is a separate tab stop and the arrow keys do nothing.
 * That is survivable on a filter strip with three options; it is not survivable here, where the two
 * tabs are the whole navigation of the screen. This one implements the roving tabindex properly —
 * one tab stop for the strip, arrows and Home/End to move between them — and the next surface that
 * needs tabs should reach for it rather than adding a fourth strip.
 *
 * ── WHAT MAKES A TAB HERE ACCESSIBLE ────────────────────────────────────────────────────────────
 *
 * * `role="tablist"` with a name, `role="tab"` with `aria-selected` and `aria-controls`, and a
 *   `role="tabpanel"` that names its tab back through `aria-labelledby`.
 * * ONE tab stop: the selected tab is `tabIndex={0}` and the rest are `-1`, so Tab moves past the
 *   strip rather than through it, and the arrows move within it. That is what the pattern promises
 *   and what a screen reader announces ("tab, 2 of 2").
 * * The selection is carried by a WORD as well as by the filled pill — colour never carries meaning
 *   on its own in this app, and a purple pill among pale ones is exactly the distinction that
 *   vanishes in greyscale, in forced-colours mode and for a colour-blind reader. The selected tab
 *   therefore says "showing" in its own text, and the unselected ones say "not showing" to the
 *   assistive layer.
 * * The panel is focusable (`tabIndex={-1}`) so a caller can move focus into it after a change
 *   without inventing a target.
 *
 * MOTION: the pill's border and background are a plain CSS `transition`, which the global
 * reduced-motion rules in `globals.css` zero for both sources. There is deliberately no `layoutId`
 * slide and no moving indicator here — that is framer-motion, which writes inline styles CSS cannot
 * reach and would need its own JS branch for a 150ms animation. (This paragraph and the one above
 * described a sliding UNDERLINE for a while. There has never been one: the strip's only
 * `border-bottom` is on the container and is drawn identically whichever tab is selected. Two
 * sentences of documentation for a thing that was not there, which is the failure house rule 2 is
 * about — if a moving indicator is wanted later, it goes in the code first.)
 */

import { useRef } from "react";
import type { LucideIcon } from "lucide-react";

export type SketchTab<Key extends string> = {
  key: Key;
  label: string;
  icon: LucideIcon;
  /** One line under the strip saying what this tab is for. */
  hint: string;
};

type Props<Key extends string> = {
  tabs: ReadonlyArray<SketchTab<Key>>;
  active: Key;
  onChange: (key: Key) => void;
  /** Names the strip for assistive technology. */
  label: string;
  /** Prefix for the generated ids, so two strips on one page cannot collide. */
  idPrefix: string;
};

export function SketchTabs<Key extends string>({ tabs, active, onChange, label, idPrefix }: Props<Key>) {
  const refs = useRef(new Map<string, HTMLButtonElement>());

  function focusTab(key: Key) {
    onChange(key);
    // Focus follows selection, which is what this pattern's automatic-activation form asks for: a
    // reader arrowing along the strip is CHOOSING, and leaving focus behind on the old tab would
    // make the next arrow press move from somewhere they are no longer looking.
    refs.current.get(key)?.focus();
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLButtonElement>, index: number) {
    const last = tabs.length - 1;
    let next: number | null = null;
    if (event.key === "ArrowRight") next = index === last ? 0 : index + 1;
    else if (event.key === "ArrowLeft") next = index === 0 ? last : index - 1;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = last;
    if (next === null) return;
    event.preventDefault();
    focusTab(tabs[next].key);
  }

  const current = tabs.find((tab) => tab.key === active) ?? tabs[0];

  return (
    <div>
      <div role="tablist" aria-label={label} className="flex flex-wrap gap-2 border-b border-line-200 pb-2">
        {tabs.map((tab, index) => {
          const selected = tab.key === active;
          const Icon = tab.icon;
          return (
            <button
              key={tab.key}
              ref={(node) => {
                if (node) refs.current.set(tab.key, node);
                else refs.current.delete(tab.key);
              }}
              type="button"
              role="tab"
              id={`${idPrefix}-tab-${tab.key}`}
              aria-selected={selected}
              aria-controls={`${idPrefix}-panel-${tab.key}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(tab.key)}
              onKeyDown={(event) => onKeyDown(event, index)}
              className={
                selected
                  ? "inline-flex items-center gap-2 rounded-md border border-purple-700 bg-purple-700 px-4 py-2 text-sm font-semibold text-white shadow-cta transition"
                  : "inline-flex items-center gap-2 rounded-md border border-line-200 bg-card px-4 py-2 text-sm font-medium text-ink-700 transition hover:border-purple-300 hover:bg-purple-50"
              }
            >
              <Icon className="h-4 w-4" aria-hidden />
              {tab.label}
              {/*
                THE SELECTED TAB SAYS SO IN WORDS as well as in colour. `aria-selected` covers the
                assistive layer; this covers the reader who can see the screen but not the hue.
              */}
              <span className={selected ? "text-[11px] font-normal text-white/80" : "sr-only"}>
                {selected ? "showing" : "not showing"}
              </span>
            </button>
          );
        })}
      </div>
      <p className="mt-2 text-sm text-ink-muted">{current.hint}</p>
    </div>
  );
}

/** The panel half of the pair. Rendered by the page so each tab keeps its own content in view. */
export function SketchTabPanel({
  idPrefix,
  tabKey,
  children
}: {
  idPrefix: string;
  tabKey: string;
  children: React.ReactNode;
}) {
  return (
    <div
      role="tabpanel"
      id={`${idPrefix}-panel-${tabKey}`}
      aria-labelledby={`${idPrefix}-tab-${tabKey}`}
      tabIndex={-1}
      className="mt-5 outline-none"
    >
      {children}
    </div>
  );
}
