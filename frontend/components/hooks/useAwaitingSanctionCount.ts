"use client";

/**
 * HOW MANY SANCTIONED WORKSHOPS ARE STALLED ON THEIR DESIGNER — the count behind the ministry's
 * "Sanction orders" badge, on the nav entry and on the ministry desk's row for the same screen.
 *
 * ── WHY IT EXISTS, AND WHY IT IS THE CHEAPEST THING IN THIS RELEASE ─────────────────────────────
 *
 * `GET /sanction-orders/awaiting-count` has been on the server since the register shipped, its
 * docstring calls itself **THE NOTIFICATION**, and `fetchAwaitingSanctionCount` in
 * `lib/sanctionOrders.ts` is documented with the single word "The badge." — and nothing has ever
 * called it. An officer records an order, the designer's account is created and a sign-in link is
 * handed over, and from that moment the only way to learn that the designer never filled in stage 1
 * was to open the register and read the readiness pill on every row. The number the server was
 * already prepared to give is now on the chrome the officer is already looking at.
 *
 * "Awaiting" is the server's word and the server's arithmetic: the designer has not yet filled
 * stage 1's thirteen required fields. It is scored by `stage_completeness` from stage-1 JSON, so
 * there is no column to filter on and no second count that could be made here — see the endpoint's
 * own note about why `readiness` is not a query parameter.
 *
 * ── WHY A SHARED STORE AND NOT A HOOK PER CALLER ────────────────────────────────────────────────
 *
 * Copied deliberately from {@link ./usePendingAccessCount}, which spells the argument out at length,
 * and from {@link ./useOpenTaskCount}, which copied it first. Two components draw this number — the
 * nav (twice: the desktop dropdown and the sheet) and `MinistryDeskCard` on /dashboard — and on a
 * ministry officer's dashboard BOTH are on screen at once. Written as an ordinary `useEffect` fetch
 * they would issue two requests per page load and, worse, answer with different numbers for as long
 * as one had refreshed and the other had not. A badge that says 3 in the nav and 2 on the desk is a
 * badge nobody trusts, and the officer's next move is to open the register to find out which one
 * lied. So there is ONE in-flight request, ONE cached value, and every subscriber re-renders
 * together.
 *
 * ── NO TIMER, DELIBERATELY ──────────────────────────────────────────────────────────────────────
 *
 * Android rides an app-wide 45-second poll; the web has no such loop and adding one would mean a
 * background request on every page, forever, for a number that changes when a designer somewhere
 * finishes typing. It refreshes on the events that already mean "this officer has come back to the
 * app": a mount with a stale value, and the tab regaining focus. {@link refreshAwaitingSanctionCount}
 * is exported for the third event — the officer has just recorded an order, which creates exactly
 * one more workshop with nothing filled in yet, so the badge must not go on claiming the old number.
 * Nothing calls it today; `/sanction-orders` is another slice's file and the call belongs beside its
 * `recordSanctionOrder`. Until then the register's own page is one navigation away from a remount,
 * which re-asks as soon as {@link FRESH_FOR_MS} has passed.
 *
 * ── IT NEVER SHOWS AN ERROR, AND THAT IS ALSO HOW IT DEGRADES ───────────────────────────────────
 *
 * A failed count leaves the badge absent, not broken — the rule both hooks above state. It is worth
 * restating here because this one is the first badge whose endpoint a deployment might genuinely not
 * have: a server older than the sanction register answers 404, `apiFetch` rejects, the `.catch`
 * swallows it, `cached` stays null and every surface renders exactly what it renders when the count
 * is zero, which is nothing. No red box on the navigation bar of every page because a background
 * probe failed, and no empty pill.
 */

import { useEffect, useState } from "react";

import { fetchAwaitingSanctionCount } from "@/lib/sanctionOrders";

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
 * Exported so a screen that has just CHANGED the register can correct every badge in the app
 * without knowing where they are. Never rejects: see the header.
 *
 * The response is unwrapped to a plain number here rather than carried as `{ awaiting }` to the
 * callers. `usePendingAccessCount` keeps its payload because `capReached` is a second fact a badge
 * has to render; this endpoint returns one key and one number, and a wrapper type reaching three
 * render sites would be three places to unwrap it.
 */
export async function refreshAwaitingSanctionCount(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = fetchAwaitingSanctionCount()
    .then((value) => {
      cached = value.awaiting;
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
 * @param enabled pass "the badged surface is actually on screen". FALSE MUST NOT FETCH, and here
 *   that is a permission question as well as a rendering one: `/sanction-orders/awaiting-count` is
 *   `require_sanction_recorder`, so a designer's every page load would spend a request to be told
 *   403 — and the 403 would be logged, on the server, as an authorisation failure by an account
 *   doing nothing wrong. Both call sites pass "is the badged entry/row being drawn", which folds in
 *   `canRecordSanctionOrders` — the frontend mirror of that dependency — in the one expression that
 *   cannot drift from what is rendered.
 */
export function useAwaitingSanctionCount(enabled: boolean): number | null {
  const [value, setValue] = useState<number | null>(cached);

  useEffect(() => {
    if (!enabled) return;
    const subscriber: Subscriber = setValue;
    subscribers.add(subscriber);
    setValue(cached);
    if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshAwaitingSanctionCount();

    // The tab coming back to the front is the closest thing the web has to Android's app-wide poll,
    // and it is the moment that matters: an officer who left the app open on the dashboard
    // yesterday is looking at yesterday's number until something re-asks.
    const onFocus = () => {
      if (Date.now() - fetchedAt > FRESH_FOR_MS) void refreshAwaitingSanctionCount();
    };
    window.addEventListener("focus", onFocus);
    return () => {
      subscribers.delete(subscriber);
      window.removeEventListener("focus", onFocus);
    };
  }, [enabled]);

  return enabled ? value : null;
}
