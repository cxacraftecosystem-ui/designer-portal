"use client";

import { Children, type ReactNode } from "react";

/**
 * THE MULTI-COLUMN LIST OF MEDIA CARDS.
 *
 * ── WHAT THIS IS FOR ────────────────────────────────────────────────────────────────────────────
 *
 * "All media appears in the card format horizontally stacked over one another, for bigger screens,
 * have the same stacked in multiple horizontal stacks next to each other, so as to ensure that the
 * depth does not grow too long." `MediaPreviewTile` is the horizontal card; this is the "multiple
 * horizontal stacks next to each other" — one column on a phone, more as the width allows, with a
 * ceiling per call site because the right ceiling depends on what a card carries, not on the
 * viewport.
 *
 * The depth this exists to bound is real and measured: `ExistingMedia` drew its attachments as a
 * single `grid gap-3` column, so a record with a bulk import — the /media form takes a multi-file
 * selection against one linked record, and the panel pages to 100 at a time — was a hundred cards
 * of vertical scrolling on a laptop that had room for four abreast.
 *
 * ── WHY THE COLUMN COUNT IS A PROP AND NOT A CLASS STRING ───────────────────────────────────────
 *
 * `cn()` in this repo is a plain join, NOT tailwind-merge, so a caller cannot pass
 * `lg:grid-cols-2` to override a built-in `lg:grid-cols-3`: both utilities exist in the sheet and
 * Tailwind's own canonical ordering decides the winner, which puts `grid-cols-3` last and makes the
 * override silently do nothing. A closed set of whole class strings is the only way to offer the
 * choice and have it work, and being whole literal strings is also what keeps them scannable by the
 * JIT — a template-built class name never reaches the stylesheet at all.
 *
 * Breakpoints are the stock Tailwind ones (`sm` 640 · `lg` 1024 · `2xl` 1536); `tailwind.config.ts`
 * overrides no `screens` and this is not the place to start.
 *
 * ── WHY <ul role="list"> AND A WRAPPER <li> ─────────────────────────────────────────────────────
 *
 * A set of cards is a list, and it should be announced as one — "list, 12 items" is the difference
 * between a reader knowing how much media a record carries and discovering it by arrowing to the
 * end. Tailwind's preflight sets `list-style: none` on every `ul`, and Safari/VoiceOver drops list
 * semantics from a list styled that way, so the explicit `role="list"` is load-bearing rather than
 * redundant: without it this announces as a run of unrelated groups on the one browser most field
 * iPads use.
 *
 * The `<li>` is supplied here rather than asked of the caller so that every call site gets the
 * semantics by construction, and because it is also where the cards are made equal height: it is a
 * grid item (so it stretches to its row) that is itself `display: grid` (so its single child fills
 * it). Without that, a row of cards is ragged wherever one file has a longer name than its
 * neighbours. `min-w-0` is the usual flex/grid rule — a track that refuses to shrink below its
 * content's intrinsic width is how a long filename pushes a whole column sideways.
 */

const COLUMN_CLASS: Record<2 | 3 | 4, string> = {
  // Two: for cards that carry a block of their own — `ExistingMedia` hangs a transcript off each.
  2: "grid gap-3 lg:grid-cols-2",
  // Three: the capture grids' existing shape, kept verbatim so this component is a drop-in for them.
  3: "grid gap-3 sm:grid-cols-2 lg:grid-cols-3",
  // Four: a plain gallery of thumbnails, where a 1280px page really does have room for four.
  4: "grid gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4"
};

export function MediaCardGrid({
  children,
  label,
  maxColumns = 3
}: {
  children: ReactNode;
  /** Names the list for a screen reader — "Previously uploaded media", not "Media grid". */
  label: string;
  /** The widest this list is allowed to get. See COLUMN_CLASS for what each is for. */
  maxColumns?: 2 | 3 | 4;
}) {
  return (
    <ul role="list" aria-label={label} className={COLUMN_CLASS[maxColumns]}>
      {/*
        `Children.map` and not `children` directly, because the <li> has to wrap each card
        individually for the list to have items at all. It invokes this callback for holes as well
        as for elements — a caller's `{condition ? <Card/> : null}` arrives here as null — so the
        guard is what stops an empty <li> being announced as a thirteenth attachment. Keys are
        derived from the original children, so a caller's `key` still identifies the row.
      */}
      {Children.map(children, (child) =>
        child === null || child === undefined || typeof child === "boolean" ? null : (
          <li className="grid min-w-0">{child}</li>
        )
      )}
    </ul>
  );
}
