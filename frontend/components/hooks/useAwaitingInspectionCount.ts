"use client";

/**
 * HOW MANY OF THIS INSPECTOR'S WORKSHOPS ARE HANDED IN AND WAITING FOR REVIEW — the badge on
 * "Workshops to inspect", on the nav entry and on the desk's row (2026-10-18).
 *
 * `useAwaitingApprovalCount`'s store exactly: one module-level value and one request in the air, no
 * timer (a stale mount or the tab regaining focus refreshes it), and silent on failure — a count
 * that could not be read draws no badge, as zero does. The inspection screens call
 * {@link refreshAwaitingInspectionCount} after a send-back, which moves a report out of the count.
 */

import { useEffect, useState } from "react";

import { fetchAwaitingInspectionCount } from "@/lib/designWorkshopInspections";

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

/** Fetch the count now, unless an identical request is already in the air. Never rejects. */
export async function refreshAwaitingInspectionCount(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = fetchAwaitingInspectionCount()
    .then((value) => {
      cached = typeof value?.awaiting === "number" ? value.awaiting : null;
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
 * @param enabled pass "the badged surface is actually on screen". FALSE MUST NOT FETCH: the endpoint
 *   is `require_approving_authority`, so any other account would spend a request to be told 403 —
 *   logged on the server as an authorisation failure by an account doing nothing wrong. Both call
 *   sites pass "is the badged entry/row being drawn", which folds in `canApproveDesignWorkshops`.
 */
export function useAwaitingInspectionCount(enabled: boolean): number | null {
  const [value, setValue] = useState<number | null>(cached);

  useEffect(() => {
    if (!enabled) return;
    const subscriber: Subscriber = setValue;
    subscribers.add(subscriber);
    setValue(cached);
    if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshAwaitingInspectionCount();

    const onFocus = () => {
      if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshAwaitingInspectionCount();
    };
    window.addEventListener("focus", onFocus);
    return () => {
      subscribers.delete(subscriber);
      window.removeEventListener("focus", onFocus);
    };
  }, [enabled]);

  return enabled ? value : null;
}
