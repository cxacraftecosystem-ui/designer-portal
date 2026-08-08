"use client";

/**
 * The offline outbox, made visible.
 *
 * An outbox nobody can see is worse than no outbox: the researcher believes the record is saved,
 * the record is on one laptop, and nobody finds out until the dataset is short. So this sits above
 * the page content whenever anything is queued and says three things plainly — how many entries are
 * waiting, that they are on THIS device only, and what will send them.
 *
 * It drains automatically when the connection returns (the `online` event) and offers "Sync now"
 * for the case the browser's own online flag is optimistic — captive portals and hotel wi-fi report
 * `navigator.onLine === true` while nothing routes.
 *
 * Entries the server permanently rejected stay listed with the reason, each with its own Discard,
 * because the only person who can decide whether a rejected record matters is the person who typed
 * it. Nothing here deletes an entry on its own.
 */

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { CloudOff, RefreshCw, Trash2, TriangleAlert } from "lucide-react";

import { useToast } from "@/components/ui/Toast";
import {
  discardOutboxEntry,
  getOutboxSnapshot,
  getServerOutboxSnapshot,
  refreshOutbox,
  subscribeOutbox,
  syncOutbox,
  type OutboxEntry
} from "@/lib/offline";

/** Files across every batch of one entry — what the user attached, not how the form grouped it. */
function fileCount(entry: OutboxEntry): number {
  return entry.media.reduce((sum, batch) => sum + batch.files.length, 0);
}

export function OutboxBanner() {
  const entries = useSyncExternalStore(subscribeOutbox, getOutboxSnapshot, getServerOutboxSnapshot);
  const [syncing, setSyncing] = useState(false);
  const { toast } = useToast();

  // Confirming a queued save belongs HERE, not in each of the six forms that can queue one. A form
  // that queues just stops and scrolls; the count going up is the event, wherever it came from, so
  // one watcher covers every save path and cannot drift out of step with the banner beneath it.
  //
  // Null until the store has been read, and DELIBERATELY not set by the first render: entries
  // survive a browser restart, so the mount snapshot is empty and the load that follows looks
  // exactly like three saves arriving at once. That is how a researcher opening the laptop the next
  // morning, online, was told her week-old queue had "just been saved on this device with no
  // connection". Only the load may set the baseline, and only a rise above it is an event.
  const announcedCount = useRef<number | null>(null);

  useEffect(() => {
    const seen = announcedCount.current;
    if (seen === null) return;
    announcedCount.current = entries.length;
    if (entries.length <= seen) return;
    const added = entries.length - seen;
    toast({
      id: "outbox-queued",
      tone: "info",
      title: `${added === 1 ? "Entry" : `${added} entries`} saved on this device`,
      description: "There is no connection, so it is queued below and sends itself when signal returns."
    });
  }, [entries.length, toast]);

  const drain = useCallback(
    async (trigger: "auto" | "manual") => {
      setSyncing(true);
      try {
        const result = await syncOutbox();
        if (result.synced) {
          toast({
            id: "outbox-sync",
            tone: "success",
            title: `${result.synced} saved ${result.synced === 1 ? "entry" : "entries"} sent`,
            description: result.remaining ? `${result.remaining} still waiting.` : "The outbox is empty."
          });
        } else if (trigger === "manual") {
          // Only a click deserves an answer when nothing moved; an automatic pass stays quiet.
          toast({
            id: "outbox-sync",
            tone: result.stoppedOffline ? "error" : "info",
            title: result.stoppedOffline ? "Still no connection" : "Nothing to send",
            description: result.stoppedOffline
              ? "Everything stays queued on this device. Try again once you have signal."
              : undefined
          });
        }
      } finally {
        setSyncing(false);
      }
    },
    [toast]
  );

  /**
   * Read the store once on mount — entries survive a browser restart, so a fresh tab has to look —
   * and SEND what is in it if this tab already has a connection.
   *
   * THE READ ALONE WAS NOT ENOUGH, and the gap is the ordinary case rather than the exotic one. The
   * `online` listener below never fires for a tab that was never offline: a researcher who queued a
   * record in a courtyard closes the laptop, opens it the next morning on the office wifi and loads
   * the app, and nothing sends until they happen to press a button. `DraftSyncBanner` learned this
   * for the design-workshop store and says so in the same words — draining on mount is what makes
   * "it sends itself" true.
   *
   * IT IS ALSO WHAT MAKES THE SCHEMA-SKEW POLICY REAL ON THIS QUEUE. `lib/offline.ts` re-attempts a
   * refusal caused by the client and the server disagreeing about the shape of a request ONCE PER
   * APP RUN, and writes a sentence onto the entry promising the researcher it "will be sent by
   * itself … you do not have to do anything". A new app run is a page load; without a drain here
   * nothing consults that policy on a page load, so the promise was kept by the workshop queue and
   * broken by this one. MEASURED, not assumed: with an entry refused by an earlier run seeded into
   * IndexedDB, a reload produced ZERO replay requests before this effect existed and one after.
   *
   * The baseline for the "n entries were just queued" toast is still taken from the rows as LOADED,
   * before this pass can remove any — a drain that empties the queue must not read as a save.
   */
  useEffect(() => {
    void refreshOutbox().then((rows) => {
      if (announcedCount.current === null) announcedCount.current = rows.length;
      if (!rows.length) return;
      // A tab that knows it is offline must not spend a request finding out; the listener below is
      // what carries it when the signal returns.
      if (typeof navigator !== "undefined" && navigator.onLine === false) return;
      void drain("auto");
    });
    // `drain`'s only dependency is `toast`, so it is stable for the life of the provider; listing it
    // would restart this on a toast-context change and run a second pass. `syncOutbox` shares one
    // pass between concurrent callers anyway, so the cost of that would be wasted work rather than a
    // duplicated record — but wasted work on a metered rural connection is still a cost.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The connection coming back is the whole point of the queue — drain without being asked.
  useEffect(() => {
    function onOnline() {
      if (getOutboxSnapshot().length) drain("auto");
    }
    window.addEventListener("online", onOnline);
    return () => window.removeEventListener("online", onOnline);
  }, [drain]);

  if (!entries.length) return null;

  const rejected = entries.filter((entry) => entry.failure);
  const waiting = entries.length - rejected.length;

  return (
    <section
      aria-live="polite"
      className="mb-4 grid gap-3 rounded-lg border border-amber-500/40 bg-amber-100/60 p-4 text-ink-900"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          <CloudOff className="mt-0.5 h-4 w-4 shrink-0 text-amber-800" aria-hidden />
          <div>
            <h2 className="font-display text-sm font-bold">
              {waiting
                ? `${waiting} ${waiting === 1 ? "entry is" : "entries are"} saved on this device only`
                : "Entries on this device need your attention"}
            </h2>
            <p className="mt-0.5 text-xs text-ink-700">
              {waiting
                ? "They were made without a connection and have not reached the repository yet. They send themselves when the connection returns — but they live in this browser, so do not clear its data or hand the laptop on until the outbox is empty."
                : "Nothing is waiting on the network. The entries below were refused by the server and need a decision."}
            </p>
          </div>
        </div>
        {waiting ? (
          <button type="button" className="field-button-secondary shrink-0" disabled={syncing} onClick={() => drain("manual")}>
            <RefreshCw className={`h-4 w-4 ${syncing ? "animate-spin" : ""}`} aria-hidden />
            {syncing ? "Sending…" : "Sync now"}
          </button>
        ) : null}
      </div>

      <ul className="grid gap-1.5">
        {entries.map((entry) => (
          <li
            key={entry.id}
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
          >
            <div className="min-w-0">
              <span className="text-sm font-medium">{entry.label}</span>
              <span className="ml-2 text-xs text-ink-500">
                {new Date(entry.createdAt).toLocaleString()}
                {fileCount(entry) ? ` · ${fileCount(entry)} file(s)` : ""}
              </span>
              {entry.failure ? (
                <p className="mt-0.5 flex items-start gap-1 text-xs text-error-600">
                  <TriangleAlert className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
                  {entry.failure}
                </p>
              ) : null}
            </div>
            {entry.failure ? (
              <button
                type="button"
                className="inline-flex items-center gap-1 text-xs font-semibold text-error-600"
                onClick={() => discardOutboxEntry(entry.id!)}
              >
                <Trash2 className="h-3.5 w-3.5" aria-hidden />
                Discard
              </button>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  );
}
