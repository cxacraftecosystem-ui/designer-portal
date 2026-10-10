"use client";

/**
 * HOW MANY REPORTS ARE WAITING FOR THE MINISTRY ADMIN'S APPROVAL — the count behind the badge on
 * "Reports to approve", on the nav entry and on the ministry desk's row for the same screen.
 *
 * A copy of {@link ./useAwaitingSanctionCount} in every respect that matters, for the reasons that
 * file argues at length: ONE module-level store and ONE in-flight request, because the nav (twice:
 * the desktop dropdown and the sheet) and the desk card on /dashboard are on a Ministry Admin's screen
 * at once and must never show two numbers; NO TIMER, refreshing on a stale mount and on the tab
 * regaining focus; and SILENT ON FAILURE — a count that could not be read leaves the badge absent,
 * exactly as a count of zero does, never a red box on every page's navigation.
 *
 * {@link refreshAwaitingApprovalCount} is exported for the third event, and here it HAS callers: the
 * approvals screens call it after every decision, because each one moves a report into or out of the
 * waiting count and the badge must not go on claiming the old number.
 */

import { useEffect, useState } from "react";

import { fetchAwaitingApprovalCount } from "@/lib/designWorkshopApprovals";

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
export async function refreshAwaitingApprovalCount(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = fetchAwaitingApprovalCount()
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
export function useAwaitingApprovalCount(enabled: boolean): number | null {
  const [value, setValue] = useState<number | null>(cached);

  useEffect(() => {
    if (!enabled) return;
    const subscriber: Subscriber = setValue;
    subscribers.add(subscriber);
    setValue(cached);
    if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshAwaitingApprovalCount();

    const onFocus = () => {
      if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshAwaitingApprovalCount();
    };
    window.addEventListener("focus", onFocus);
    return () => {
      subscribers.delete(subscriber);
      window.removeEventListener("focus", onFocus);
    };
  }, [enabled]);

  return enabled ? value : null;
}
