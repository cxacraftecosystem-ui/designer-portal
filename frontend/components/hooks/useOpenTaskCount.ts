"use client";

/**
 * HOW MANY OPEN TASKS ARE ASSIGNED TO THE SIGNED-IN PERSON — the count behind the nav's Tasks badge.
 *
 * ── WHY IT EXISTS ───────────────────────────────────────────────────────────────────────────────
 *
 * Until this store there was no way to learn that somebody had handed you documentation work except
 * to open /tasks and look. This codebase has no email sender, no push transport and no job runner to
 * build one from — what it has is people who open screens — so the notification is a number on the
 * chrome that is already on every page, exactly as the access queue's is. The work itself is one tap
 * away, on the entry the badge sits on.
 *
 * ── WHY A SHARED STORE AND NOT A HOOK PER CALLER ────────────────────────────────────────────────
 *
 * Copied deliberately from {@link ../hooks/usePendingAccessCount}, which spells the argument out at
 * length: written as an ordinary `useEffect` fetch, every badge would issue its own request on every
 * mount and — worse — two of them would answer with different numbers for as long as one had
 * refreshed and the other had not. The nav draws this count in TWO places already (the desktop
 * dropdown and the sheet), so the disagreement would be visible inside one component. There is ONE
 * in-flight request, ONE cached value, and every subscriber re-renders together.
 *
 * ── NO TIMER, DELIBERATELY ──────────────────────────────────────────────────────────────────────
 *
 * A background poll on every page, forever, is a real cost paid by every signed-in account — and
 * unlike the access queue this endpoint is open to everyone, so it would be the whole user base. It
 * refreshes on the events that already mean "this person has come back to the app": a mount with a
 * stale value, and the tab regaining focus. {@link refreshOpenTaskCount} is exported for the third
 * event — the reader has just moved a task out of Open on /tasks, and the badge must not go on
 * claiming it is still waiting.
 *
 * What that leaves uncovered, stated rather than hidden: a task assigned to you by an admin while
 * you sit on one page reaches the badge on your next focus or after {@link FRESH_FOR_MS}, not the
 * instant it is created. There is no server push here to do better with.
 *
 * ── IT NEVER SHOWS AN ERROR ─────────────────────────────────────────────────────────────────────
 *
 * A failed count leaves the badge absent, not broken. A red box on the navigation bar of every page
 * because a background probe timed out would be a worse product than no badge at all, and /tasks
 * itself says its own failures out loud.
 */

import { useEffect, useState } from "react";

import { OPEN_TASK_COUNT_PATH } from "@/components/tasks/openTaskCount";
import { apiFetch } from "@/lib/api";
import type { PageResult } from "@/lib/types";

/** How long a fetched count is considered fresh enough to reuse on a new mount. */
const FRESH_FOR_MS = 60_000;

type Subscriber = (value: number | null) => void;

let cached: number | null = null;
let fetchedAt = 0;
let inFlight: Promise<void> | null = null;
const subscribers = new Set<Subscriber>();

function publish() {
  subscribers.forEach((notify) => notify(cached));
}

/**
 * Fetch the count now, unless an identical request is already in the air.
 *
 * Exported so a screen that has just CHANGED the queue can correct every badge in the app without
 * knowing where they are. Never rejects: see the header.
 *
 * `items` is never read — the request asks for a single row precisely so that it is not built. See
 * `OPEN_TASK_COUNT_PATH` for why.
 */
export async function refreshOpenTaskCount(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = apiFetch<PageResult<unknown>>(OPEN_TASK_COUNT_PATH)
    .then((page) => {
      cached = page.total;
      fetchedAt = Date.now();
      publish();
    })
    .catch(() => {
      /* silent by design — the badge simply does not appear this time */
    })
    .finally(() => {
      inFlight = null;
    });
  return inFlight;
}

/**
 * The count, or null while it is unknown.
 *
 * @param enabled pass "the badged entry is actually on screen". Unlike the access count this is not
 *   a permission question — `GET /tasks?view=assigned` is hard-pinned to the caller and asks for
 *   nothing but a login, which is why the Tasks nav entry is `can: everyone` — but a surface that is
 *   not drawing the badge must still not spend the request, and tying the fetch to what is rendered
 *   is the one expression that cannot drift from it.
 */
export function useOpenTaskCount(enabled: boolean): number | null {
  const [value, setValue] = useState<number | null>(cached);

  useEffect(() => {
    if (!enabled) return;
    const subscriber: Subscriber = setValue;
    subscribers.add(subscriber);
    setValue(cached);
    if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshOpenTaskCount();

    // The tab coming back to the front is the closest thing the web has to Android's app-wide poll,
    // and it is the moment that matters: somebody who left the app open yesterday is looking at
    // yesterday's number until something re-asks.
    const onFocus = () => {
      if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshOpenTaskCount();
    };
    window.addEventListener("focus", onFocus);
    return () => {
      subscribers.delete(subscriber);
      window.removeEventListener("focus", onFocus);
    };
  }, [enabled]);

  return enabled ? value : null;
}
