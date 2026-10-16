"use client";

import { useEffect, useRef } from "react";

/**
 * A list page's search, page, filters and scroll position, kept for the length of the tab so that
 * Back from a record's read-only page lands where the reader left the list.
 *
 * WHY IT IS NEEDED NOW. Since every row opens its record (owner ruling, 2026-10-16), the list → record
 * → Back round trip is the commonest walk in the app. These pages hold their filters in component
 * state, which a navigation discards, so Back used to put the reader on page 1 of the default
 * workshop at the top of the table — a researcher working down page 4 of one artisan's products lost
 * their place on every record they opened.
 *
 * SESSION storage, not local: this is "where was I in this tab", not a preference, and a list
 * re-opened tomorrow should start from the page's own defaults. Every access is try/catch-wrapped —
 * storage throws outright when blocked, and a list that failed to render because it could not
 * remember its scroll position would be a far worse bug than the one this fixes.
 */

const PREFIX = "field_repo_list:";

/** What one list remembers. Every field is optional: a page stores what it has. */
export type ListMemory = {
  query?: string;
  applied?: string;
  page?: number;
  funnel?: { workshopId: string; craftId: string; artisanId: string };
  /** A page's other filters (a status, a kind, a toggle), by name. Strings only, so the shape stays checkable. */
  filters?: Record<string, string>;
  scrollY?: number;
};

/**
 * Reads a stored value back, accepting only the shape it wrote. A tampered or older entry yields
 * null rather than a half-valid filter. Pure, so it is tested without a browser.
 */
export function parseListMemory(raw: string | null | undefined): ListMemory | null {
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
  const source = parsed as Record<string, unknown>;
  const memory: ListMemory = {};
  if (typeof source.query === "string") memory.query = source.query;
  if (typeof source.applied === "string") memory.applied = source.applied;
  if (typeof source.page === "number" && Number.isInteger(source.page) && source.page >= 1) memory.page = source.page;
  if (typeof source.scrollY === "number" && Number.isFinite(source.scrollY) && source.scrollY >= 0) memory.scrollY = source.scrollY;
  const funnel = source.funnel as Record<string, unknown> | undefined;
  if (
    funnel &&
    typeof funnel === "object" &&
    typeof funnel.workshopId === "string" &&
    typeof funnel.craftId === "string" &&
    typeof funnel.artisanId === "string"
  ) {
    memory.funnel = { workshopId: funnel.workshopId, craftId: funnel.craftId, artisanId: funnel.artisanId };
  }
  const filters = source.filters;
  if (filters && typeof filters === "object" && !Array.isArray(filters)) {
    const kept: Record<string, string> = {};
    for (const [name, value] of Object.entries(filters as Record<string, unknown>)) {
      if (typeof value === "string") kept[name] = value;
    }
    memory.filters = kept;
  }
  return memory;
}

function read(key: string): ListMemory | null {
  try {
    return parseListMemory(window.sessionStorage.getItem(PREFIX + key));
  } catch {
    return null;
  }
}

function write(key: string, memory: ListMemory) {
  try {
    window.sessionStorage.setItem(PREFIX + key, JSON.stringify(memory));
  } catch {
    // Storage blocked or full — the list simply starts fresh next time.
  }
}

/**
 * `recall()` reads the stored entry the first time it is called and returns that same answer for
 * the life of the page — call it from an effect or a handler, never during render: the server
 * render has no storage, so a value read while rendering would differ between the two and React
 * would refuse the hydration. A page applies it at the moment it first knows its own defaults (the
 * funnel's first `onChange`, or its first load) and then stores its state through `state` every
 * render. `ready` says the rows are on screen, the only moment a scroll position means anything.
 */
export function useListMemory(key: string, state: Omit<ListMemory, "scrollY">, ready: boolean) {
  const recalled = useRef<ListMemory | null | undefined>(undefined);
  const latest = useRef<ListMemory>({ ...state });
  const scrollY = useRef<number | null>(null);
  const restored = useRef(false);

  function recall(): ListMemory | null {
    if (recalled.current === undefined) recalled.current = typeof window === "undefined" ? null : read(key);
    return recalled.current;
  }

  // Kept current every render so the unmount write below sees the last state, not the first. The
  // first write waits for `recall()` — writing before it would replace the entry being restored.
  useEffect(() => {
    latest.current = { ...state };
    if (recalled.current === undefined) return;
    write(key, { ...latest.current, scrollY: scrollY.current ?? 0 });
  });

  useEffect(() => {
    const onScroll = () => {
      scrollY.current = window.scrollY;
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      if (recalled.current !== undefined) write(key, { ...latest.current, scrollY: scrollY.current ?? 0 });
    };
  }, [key]);

  // Once, after the first page of rows is drawn: put the reader back where they were.
  useEffect(() => {
    if (!ready || restored.current) return;
    restored.current = true;
    const target = recall()?.scrollY ?? 0;
    if (target > 0) requestAnimationFrame(() => window.scrollTo(0, target));
    // `recall` is stable in effect: it reads one ref.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);

  return { recall };
}
