"use client";

/**
 * "Where did I write about indigo?" — asked of a whole 22-stage workshop, with no network.
 *
 * WHY IT IS ON THE OVERVIEW PAGE. This is the screen a designer is already on when the question
 * occurs to them: they have opened the workshop, they are looking at the stage index, and the thing
 * they want is three stages down inside a collapsed row. Putting the box anywhere else would mean
 * navigating to a search in order to navigate to a stage.
 *
 * IT COSTS NOTHING AND ASKS FOR NOTHING. The index is built by `lib/workshopSearch` from the draft
 * this page has ALREADY loaded out of IndexedDB — see `buildWorkshopSearchIndex` — so there is no
 * request, no spinner and no difference in behaviour between a designer at a desk and one in a
 * courtyard with no signal for three days. That is not a nicety; a search that needed a server would
 * be missing in exactly the fortnight the fieldwork happens in.
 *
 * WHAT IS SAID OUT LOUD, because none of it is guessable from an empty list:
 *   • how much was searched (values, and how many stages actually hold text);
 *   • that the result list is capped, and what the true total was;
 *   • that a one-character query was refused rather than answered;
 *   • that a query of punctuation had nothing in it to search for.
 * A list that quietly stops is indistinguishable from a workshop with nothing in it, and that is the
 * single most repeated defect in this repository.
 */

import { useDeferredValue, useId, useMemo, useState } from "react";
import Link from "next/link";
import { Search, X } from "lucide-react";

import {
  MAX_HITS,
  MIN_QUERY_CHARS,
  searchWorkshop,
  workshopHitHref,
  type WorkshopSearchHit,
  type WorkshopSearchIndex
} from "@/lib/workshopSearch";

/**
 * One result's quoted text, with the matched words marked.
 *
 * `<mark>` because that is what it is, and a screen reader announces it — the highlight is not
 * decoration, it is the answer to "why is this row here". The purple pair is used rather than a
 * `bg-amber` wash because purple-100/purple-900 are LITERAL brand colours that do not invert, so the
 * mark reads identically in both themes; the themed neutrals would have needed a second rule.
 */
function Snippet({ hit }: { hit: WorkshopSearchHit }) {
  return (
    <p className="mt-1 text-sm leading-6 text-ink-700">
      {hit.snippet.clippedStart ? "…" : null}
      {hit.snippet.segments.map((segment, index) =>
        segment.match ? (
          <mark key={index} className="rounded bg-purple-100 px-0.5 text-purple-900">
            {segment.text}
          </mark>
        ) : (
          // A fragment rather than a span: the surrounding text is prose and must wrap as prose.
          <span key={index}>{segment.text}</span>
        )
      )}
      {hit.snippet.clippedEnd ? "…" : null}
    </p>
  );
}

/** One result: where it is, then what it says. */
function Hit({ workshopId, hit }: { workshopId: string; hit: WorkshopSearchHit }) {
  return (
    <li className="border-b border-line-200 last:border-b-0">
      <Link
        href={workshopHitHref(workshopId, hit)}
        className="block px-4 py-3 transition hover:bg-surface-50"
      >
        <span className="flex flex-wrap items-baseline gap-x-2 gap-y-1 text-xs text-ink-500">
          {/* The stage NUMBER as well as its title: a designer navigates this workshop by number,
              and "13" is what they will look for in the list below. */}
          <span className="font-medium text-ink-900">
            {hit.stageNumber}. {hit.stageTitle}
          </span>
          <span aria-hidden>·</span>
          <span>{hit.entityTitle}</span>
          {hit.recordTitle ? (
            <>
              <span aria-hidden>·</span>
              {/* The row, titled exactly as `CollectionTable` titles it, so the designer recognises
                  what they will land on rather than being shown a second name for it. */}
              <span className="max-w-[22rem] truncate">{hit.recordTitle}</span>
            </>
          ) : null}
          <span aria-hidden>·</span>
          <span className="font-medium text-purple-700">{hit.fieldLabel}</span>
        </span>
        <Snippet hit={hit} />
      </Link>
    </li>
  );
}

export function WorkshopSearchPanel({
  workshopId,
  index,
  ready
}: {
  workshopId: string;
  index: WorkshopSearchIndex;
  /**
   * False while the draft is still being read off the device. The box is DISABLED rather than
   * hidden: a control that appears a moment after the page does is a control a designer's hand has
   * already moved past, and one that reports "nothing found" against a half-built index is worse.
   */
  ready: boolean;
}) {
  const inputId = useId();
  const [query, setQuery] = useState("");
  /**
   * The query the RESULTS are for, which may lag the box by a frame under load.
   *
   * `useDeferredValue` rather than a debounce timer: a debounce makes every designer wait the same
   * fixed delay whether or not the device needed it, and on the flagship workshop a query answers in
   * under 4ms. This keeps the keystroke itself at full speed on a slow handset and lets the list
   * catch up, with no arbitrary number to tune.
   */
  const deferred = useDeferredValue(query);
  const result = useMemo(() => searchWorkshop(index, deferred), [index, deferred]);

  return (
    <section className="panel mb-5 p-4">
      <label className="field-label" htmlFor={inputId}>
        Search this workshop
      </label>
      <div className="relative mt-1">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-500" aria-hidden />
        <input
          id={inputId}
          // `type="text"` with `role="searchbox"`, which is what `components/SearchInput.tsx` does
          // and for the same reason: `type="search"` makes Chromium draw its own clear button
          // inside the box, so the themed one beside it reads as two X's that do different things.
          // Unlike that component this one carries a real `<label htmlFor>`, because it is the only
          // control in its panel and an accessible name of "placeholder" is not a name.
          type="text"
          role="searchbox"
          className="field-input pl-9 pr-9"
          placeholder="A word, a name, a product code — in any script"
          value={query}
          disabled={!ready}
          onChange={(event) => setQuery(event.target.value)}
          autoComplete="off"
          // `off` and `none`: the box takes Odia and Hindi as often as English, and a browser that
          // autocapitalises or autocorrects a transliterated village name changes what was typed.
          autoCorrect="off"
          autoCapitalize="none"
          spellCheck={false}
        />
        {query ? (
          // Same treatment as the clear button on every list page's search bar, so the two read as
          // one control rather than two that happen to look alike.
          <button
            type="button"
            className="absolute right-2 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center rounded-full text-error-600 transition hover:bg-error-100"
            aria-label="Clear search"
            title="Clear search"
            onClick={() => setQuery("")}
          >
            <X className="h-4 w-4" aria-hidden />
          </button>
        ) : null}
      </div>

      {/*
        THE STATUS LINE, and it is `aria-live="polite"` for a reason: the results below are not
        announced by anything when a list re-renders under an input that still holds focus, so a
        designer using a screen reader would type six letters into silence. Polite, never assertive —
        it must not interrupt them mid-word.
      */}
      <p className="mt-2 text-xs leading-5 text-ink-500" aria-live="polite">
        {!ready
          ? "Reading this workshop from this device…"
          : result.status === "IDLE"
            ? `Searches every written answer on this device — ${index.fieldCount} across ${index.stageCount} stage${index.stageCount === 1 ? "" : "s"}. No connection needed.`
            : result.status === "TOO_SHORT"
              ? `Type at least ${MIN_QUERY_CHARS} characters.`
              : result.status === "NO_TERMS"
                ? "There are no words or numbers in that to search for."
                : result.total === 0
                  ? `Nothing in this workshop holds ${result.terms.length === 1 ? "that word" : "all of those words"}. Every answer saved on this device was searched — ${index.fieldCount} of them.`
                  : result.truncated
                    ? `Showing the ${MAX_HITS} closest of ${result.total} matching answers. Add another word to narrow it.`
                    : `${result.total} matching answer${result.total === 1 ? "" : "s"}.`}
      </p>

      {/* `aria-label` so that assistive tech — and a spec — can tell these rows apart from the
          22-stage index directly below, which is also a list of list items. */}
      {result.hits.length ? (
        <ol aria-label="Search results" className="mt-3 overflow-hidden rounded-md border border-line-200">
          {result.hits.map((hit) => (
            <Hit
              // Unique by construction: one entry per (stage, entity, row, field).
              key={`${hit.stageKey}.${hit.entityKey}.${hit.rowKey ?? ""}.${hit.fieldKey}`}
              workshopId={workshopId}
              hit={hit}
            />
          ))}
        </ol>
      ) : null}
    </section>
  );
}
