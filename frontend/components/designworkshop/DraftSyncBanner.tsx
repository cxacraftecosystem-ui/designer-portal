"use client";

/**
 * The local design-workshop store, made visible — the sibling of `components/OutboxBanner`.
 *
 * A store nobody can see is worse than no store: the designer believes the workshop is filed, the
 * workshop is on one laptop, and nobody finds out until the report is short four stages. So this
 * sits above the page content whenever anything is outstanding and says the same three things the
 * outbox banner says — how much is waiting, that it is on THIS device only, and what will send it.
 *
 * THE WORDING IS THE OUTBOX BANNER'S, VERBATIM, wherever the two name the same thing ("saved on this
 * device only", the do-not-clear-the-browser sentence, "Sync now" / "Sending…", "Still no
 * connection", "Discard"). Two banners describing one situation in two vocabularies is how a
 * researcher comes to believe they are two different situations — and a designer moving between an
 * artisan form and a workshop stage in the same afternoon sees both.
 *
 * WHERE IT DIFFERS FROM THE OUTBOX, it differs because the thing behind it is different. An outbox
 * entry is finished work waiting for a wire; a workshop draft is work in progress that is MEANT to
 * live here for a fortnight. So a draft with unsent stages is not an alarm, and this banner offers
 * no Discard at all: the outbox's Discard exists because a rejected create may be a duplicate of a
 * record that already landed, and nothing here can be — a stage PUT is matched on `_clientKey` and a
 * workshop create is guarded by `remoteId`. Every refusal this store can raise (a workshop an admin
 * deleted, an answer the validator refused) is something a person can go and fix, so what is offered
 * is "Try again". Nothing on this banner deletes a photograph.
 */

import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { CloudOff, RefreshCw, TriangleAlert } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { useToast } from "@/components/ui/Toast";
import {
  getDraftsSnapshot,
  getServerDraftsSnapshot,
  refreshDrafts,
  retryDraft,
  setDraftSessionUser,
  subscribeDrafts,
  syncDesignWorkshopDrafts,
  type DwDraft
} from "@/lib/designWorkshopStore";

/** What is outstanding on one draft, derived from the draft itself so there is no second source. */
function outstanding(draft: DwDraft) {
  const stages = Object.values(draft.stages).filter((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0);
  const refusals = [
    ...(draft.failure?.permanent ? [draft.failure.message] : []),
    ...Object.values(draft.stages)
      .filter((stage) => stage.failure?.permanent)
      .map((stage) => `${stage.stageKey}: ${stage.failure?.message ?? ""}`)
  ];
  const held = Object.values(draft.stages)
    .filter((stage) => stage.failure && !stage.failure.permanent)
    .map((stage) => stage.failure?.message ?? "");
  return {
    stages: stages.length,
    neverSent: draft.remoteId === null,
    headerOnly: draft.headerDirtyAt !== null,
    refusals,
    held
  };
}

export function DesignWorkshopDraftBanner() {
  const drafts = useSyncExternalStore(subscribeDrafts, getDraftsSnapshot, getServerDraftsSnapshot);
  const [syncing, setSyncing] = useState(false);
  const { toast } = useToast();
  const { user, loading: authLoading } = useAuth();
  /** Whether `GET /me` has answered — nothing may be sent before it has. See below. */
  const sessionResolved = !authLoading;

  /**
   * TELL THE STORE WHO IS HOLDING THE LAPTOP.
   *
   * This component is mounted once, in the protected layout, so it is on every screen that can
   * reach a draft — which makes it the one place that can keep `lib/designWorkshopStore`'s idea of
   * the session in step without pulling IndexedDB code into the public landing page's bundle.
   *
   * The store filters every read and refuses every send for a draft owned by somebody else, which
   * is what stops designer B's session draining designer A's unsent workshop into B's account on a
   * shared field laptop. `AuthProvider.logout` deliberately does not clear the store — A's
   * fortnight has to survive the handover — so this is the only thing that separates the two.
   * Signed out (`user` null, auth resolved) is a real answer and is passed as one: every owned
   * draft goes quiet and nothing syncs until somebody signs in.
   */
  useEffect(() => {
    if (!sessionResolved) return;
    setDraftSessionUser(user?.id ?? null);
  }, [sessionResolved, user?.id]);

  const drain = useCallback(
    async (trigger: "auto" | "manual") => {
      setSyncing(true);
      try {
        const result = await syncDesignWorkshopDrafts();
        const sent = result.workshopsCreated + result.stagesSent + result.mediaUploaded;
        if (sent) {
          toast({
            id: "dw-draft-sync",
            tone: "success",
            title: `${sent} saved ${sent === 1 ? "change" : "changes"} sent`,
            description: result.pending ? `${result.pending} workshop(s) still waiting.` : "Everything on this device has been sent."
          });
        } else if (trigger === "manual") {
          // Only a click deserves an answer when nothing moved; an automatic pass stays quiet.
          toast({
            id: "dw-draft-sync",
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
   * Read the store once on mount, and send whatever is in it if this tab already has a connection.
   *
   * The `online` event below is not enough on its own, and the gap is the ordinary case rather than
   * the exotic one: a designer who captured a fortnight in a courtyard closes the laptop, opens it
   * the next morning on the office wifi and loads the app — the browser was never OFFLINE while this
   * tab existed, so no `online` event ever fires and the queue would sit there until they happened
   * to press a button. Draining on mount is what makes "it sends itself" true.
   *
   * Unlike the outbox banner there is deliberately NO "n entries were just queued" toast here. A
   * stage saved with no signal is the expected way this feature is used for two weeks, and an
   * announcement every time would be noise the designer learns to dismiss without reading — which is
   * exactly the state in which the one announcement that matters gets dismissed too.
   */
  useEffect(() => {
    // NOT ON MOUNT — ON THE MOUNT AFTER THE SESSION IS KNOWN. The drain sends with whatever token
    // is in `localStorage`, and the store cannot tell whose drafts these are until it has been
    // told who is signed in; a pass that ran first would be the shared-laptop misfiling all over
    // again, from the one effect that needs no click. `runSync` refuses on its own account too —
    // this is the half that stops it being a wasted request every time.
    if (!sessionResolved || !user) return;
    void refreshDrafts().then((rows) => {
      if (!rows.length) return;
      if (typeof navigator !== "undefined" && navigator.onLine === false) return;
      void drain("auto");
    });
    // `drain` is stable for the life of the provider (its only dependency is `toast`), and listing
    // it would re-run this on a toast-context change and start a second pass. `syncDesignWorkshopDrafts`
    // shares one pass between concurrent callers anyway, so the worst case is wasted work rather
    // than a duplicated workshop — but wasted work on a metered rural connection is still a cost.
    // `user.id` rather than `user`: the object identity changes on every `refreshMe`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionResolved, user?.id]);

  // The connection coming back is the whole point of the store — send without being asked.
  useEffect(() => {
    function onOnline() {
      if (getDraftsSnapshot().length) void drain("auto");
    }
    window.addEventListener("online", onOnline);
    return () => window.removeEventListener("online", onOnline);
  }, [drain]);

  const rows = drafts
    .map((draft) => ({ draft, state: outstanding(draft) }))
    .filter(
      ({ state }) => state.stages > 0 || state.neverSent || state.headerOnly || state.refusals.length > 0 || state.held.length > 0
    );

  if (!rows.length) return null;

  const waiting = rows.filter(({ state }) => !state.refusals.length).length;

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
                ? `${waiting} design ${waiting === 1 ? "workshop is" : "workshops are"} saved on this device only`
                : "Design workshops on this device need your attention"}
            </h2>
            <p className="mt-0.5 text-xs text-ink-700">
              {waiting
                ? "They were captured without a connection and have not reached the repository yet. They send themselves when the connection returns — but they live in this browser, so do not clear its data or hand the laptop on until everything here has sent."
                : "Nothing is waiting on the network. The workshops below were refused by the server and need a decision."}
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
        {rows.map(({ draft, state }) => (
          <li
            key={draft.localId}
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
          >
            <div className="min-w-0">
              <Link
                href={`/design-workshops/${draft.remoteId ?? draft.localId}`}
                className="text-sm font-medium underline-offset-2 hover:underline"
              >
                {draft.header.title || "Untitled design workshop"}
              </Link>
              <span className="ml-2 text-xs text-ink-500">
                {/* Every number on screen is a real count of a real thing, and the sentence says
                    which. A bare "pending" tells a designer nothing about whether they may pack up. */}
                {[
                  state.neverSent ? "not yet created on the server" : null,
                  state.stages ? `${state.stages} stage(s) waiting` : null,
                  state.headerOnly && !state.neverSent ? "workshop details waiting" : null
                ]
                  .filter(Boolean)
                  .join(" · ") || "waiting"}
                {" · "}
                {new Date(draft.updatedAt).toLocaleString()}
              </span>
              {state.held.map((message, index) => (
                <p key={`held-${index}`} className="mt-0.5 text-xs text-ink-700">
                  {message}
                </p>
              ))}
              {state.refusals.map((message, index) => (
                <p key={`refused-${index}`} className="mt-0.5 flex items-start gap-1 text-xs text-error-600">
                  <TriangleAlert className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
                  {message}
                </p>
              ))}
            </div>
            {state.refusals.length ? (
              // A manual retry, not a Discard. Nothing here is a duplicate of something already on
              // the server, so there is never a reason to offer to throw a workshop away from a
              // banner — and the refusals this store raises (a deleted workshop, a rejected answer)
              // are all things a person can go and fix.
              <button
                type="button"
                className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700"
                onClick={async () => {
                  await retryDraft(draft.localId);
                  await drain("manual");
                }}
              >
                <RefreshCw className="h-3.5 w-3.5" aria-hidden />
                Try again
              </button>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  );
}
