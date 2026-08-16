"use client";

/**
 * HOW MANY PEOPLE ARE WAITING FOR AN ADMINISTRATOR TO LET THEM IN — the feature's whole notification
 * channel, shared by every surface that shows it.
 *
 * ── WHY A SHARED STORE AND NOT A HOOK PER CALLER ────────────────────────────────────────────────
 *
 * Two surfaces render this number today (the nav's Settings-hub badge and the tile on /admin) and
 * more will. Written as an ordinary `useEffect` fetch, each of them would issue its own request on
 * every mount, and — worse — they would answer with different numbers for as long as one of them had
 * refreshed and the other had not. A count that says 3 in the nav and 2 on the tile is a count
 * nobody trusts, and the admin's next move is to open the queue to find out which one lied. So there
 * is ONE in-flight request, ONE cached value, and every subscriber re-renders together.
 *
 * ── NO TIMER, DELIBERATELY ──────────────────────────────────────────────────────────────────────
 *
 * Android rides the app-wide 45-second poll it already runs; the web has no such loop, and adding
 * one would mean a background request every page, forever, for a number that changes when a stranger
 * tries to sign in. Instead it refreshes on the events that already mean "the admin came back to
 * this screen": a mount with a stale value, and the tab regaining focus. {@link refreshPendingAccessCount}
 * is exported for the third event — an admin has just approved or rejected somebody, and the badge
 * must not go on claiming the queue still holds them.
 *
 * ── IT NEVER SHOWS AN ERROR ─────────────────────────────────────────────────────────────────────
 *
 * A failed count leaves the badge absent, not broken. It is a nicety on somebody else's screen: an
 * admin who cannot reach the API has bigger problems than a missing number, and a red box on the
 * navigation bar of every page because a background probe timed out would be a worse product than no
 * badge at all. The queue itself is on /admin/access and says its own failures out loud.
 */

import { useEffect, useState } from "react";

import { fetchPendingAccessCount, type PendingAccessCount } from "@/lib/accessRoster";

/** How long a fetched count is considered fresh enough to reuse on a new mount. */
const FRESH_FOR_MS = 60_000;

type Subscriber = (value: PendingAccessCount | null) => void;

let cached: PendingAccessCount | null = null;
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
 */
export async function refreshPendingAccessCount(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = fetchPendingAccessCount()
    .then((value) => {
      cached = value;
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
 * @param enabled pass the caller's own permission check. FALSE MUST NOT FETCH: the endpoint is
 *   `require_access_manager`, so a researcher's every page load would spend a request to be told 403
 *   — and the 403 would be logged, on the server, as an authorisation failure by an account doing
 *   nothing wrong. The predicate is the caller's because only it knows whether the surface it is
 *   drawing is admin chrome (an admin browsing with admin view off must not see the badge either).
 */
export function usePendingAccessCount(enabled: boolean): PendingAccessCount | null {
  const [value, setValue] = useState<PendingAccessCount | null>(cached);

  useEffect(() => {
    if (!enabled) return;
    const subscriber: Subscriber = setValue;
    subscribers.add(subscriber);
    setValue(cached);
    if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshPendingAccessCount();

    // The tab coming back to the front is the closest thing the web has to Android's poll, and it is
    // the moment that matters: an admin who left the app open on the hub yesterday is looking at
    // yesterday's number until something re-asks.
    const onFocus = () => {
      if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshPendingAccessCount();
    };
    window.addEventListener("focus", onFocus);
    return () => {
      subscribers.delete(subscriber);
      window.removeEventListener("focus", onFocus);
    };
  }, [enabled]);

  return enabled ? value : null;
}
