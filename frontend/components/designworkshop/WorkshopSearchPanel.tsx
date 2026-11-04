"use client";

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
          <span key={index}>{segment.text}</span>
        )
      )}
      {hit.snippet.clippedEnd ? "…" : null}
    </p>
  );
}

function Hit({ workshopId, hit }: { workshopId: string; hit: WorkshopSearchHit }) {
  return (
    <li className="border-b border-line-200 last:border-b-0">
      <Link
        href={workshopHitHref(workshopId, hit)}
        className="block px-4 py-3 transition hover:bg-surface-50"
      >
        <span className="flex flex-wrap items-baseline gap-x-2 gap-y-1 text-xs text-ink-500">
          <span className="font-medium text-ink-900">
            {hit.stageNumber}. {hit.stageTitle}
          </span>
          <span aria-hidden>·</span>
          <span>{hit.entityTitle}</span>
          {hit.recordTitle ? (
            <>
              <span aria-hidden>·</span>
              <span className="max-w-88 truncate">{hit.recordTitle}</span>
            </>
          ) : null}
          <span aria-hidden>·</span>
          <span className="font-medium text-purple-700 dark:text-purple-300">{hit.fieldLabel}</span>
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
  ready: boolean;
}) {
  const inputId = useId();
  const [query, setQuery] = useState("");
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
          type="text"
          role="searchbox"
          className="field-input pl-9 pr-9"
          placeholder="A word, a name, a product code — in any script"
          value={query}
          disabled={!ready}
          onChange={(event) => setQuery(event.target.value)}
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="none"
          spellCheck={false}
        />
        {query ? (
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

      <p className="mt-2 text-xs leading-5 text-ink-500" aria-live="polite">
        {!ready
          ? "Reading this workshop from this device…"
          : result.status === "IDLE"
            ? `Searches ${index.fieldCount} answers across ${index.stageCount} stage${index.stageCount === 1 ? "" : "s"}. Works offline.`
            : result.status === "TOO_SHORT"
              ? `Type at least ${MIN_QUERY_CHARS} characters.`
              : result.status === "NO_TERMS"
                ? "There are no words or numbers in that to search for."
                : result.total === 0
                  ? `No matches in ${index.fieldCount} answers.`
                  : result.truncated
                    ? `Top ${MAX_HITS} of ${result.total}. Add a word to narrow.`
                    : `${result.total} matching answer${result.total === 1 ? "" : "s"}.`}
      </p>

      {result.hits.length ? (
        <ol aria-label="Search results" className="mt-3 overflow-hidden rounded-md border border-line-200">
          {result.hits.map((hit) => (
            <Hit
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
