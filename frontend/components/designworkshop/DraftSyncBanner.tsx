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
  acknowledgeStoreTrouble,
  getDraftsSnapshot,
  getServerDraftsSnapshot,
  getServerStoreHealth,
  getStoreHealth,
  refreshDrafts,
  retryDraft,
  setDraftSessionUser,
  storeIsAnswering,
  subscribeDrafts,
  syncDesignWorkshopDrafts,
  type DwDraft
} from "@/lib/designWorkshopStore";

/**
 * What a completed pass should SAY, given what it did.
 *
 * Exported and pure so the three-way choice can be asserted without a browser, a store and a
 * refused save — which is the condition under which the two-way version survived for as long as it
 * did. `DwSyncResult.failed` was maintained at six sites in `runSync` and read by nothing, so a pass
 * that refused six things answered the click in the same words, and the same tone, as a pass that
 * had nothing to do.
 */
export function syncOutcome(result: {
  workshopsCreated: number;
  stagesSent: number;
  mediaUploaded: number;
  failed: number;
  pending: number;
  stoppedOffline: boolean;
}): { kind: "sent" | "refused" | "offline" | "idle"; tone: "success" | "error" | "info"; title: string } {
  const sent = result.workshopsCreated + result.stagesSent + result.mediaUploaded;
  if (sent) {
    return { kind: "sent", tone: "success", title: `${sent} saved ${sent === 1 ? "change" : "changes"} sent` };
  }
  // Refusals first: "still no connection" is a fact about the wifi and "nothing to send" is a fact
  // about an empty queue, while a refusal is the only one of the three a person can act on.
  if (result.failed > 0) {
    return {
      kind: "refused",
      tone: "error",
      title: `${result.failed} ${result.failed === 1 ? "item was" : "items were"} refused`
    };
  }
  if (result.stoppedOffline) return { kind: "offline", tone: "error", title: "Still no connection" };
  return { kind: "idle", tone: "info", title: "Nothing to send" };
}

/**
 * What is outstanding on one draft, derived from the draft itself so there is no second source.
 *
 * Exported for the tests, which is worth the export: the fold warning below is the most
 * consequential sentence this component can draw and it reached no screen at all until now.
 */
export function outstanding(draft: DwDraft) {
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
  /*
    THE FOLD'S OWN WARNING, WHICH WAS COMPOSED CORRECTLY AND SHOWN TO NOBODY.

    `adoptServerStage`'s dirty branch folds the server's copy in and writes `foldNotice(fold)` to
    `DwDraftStage.foldNote`. A grep for that field across the whole frontend returned exactly two
    hits — the declaration and that one write. There was no reader anywhere, and the comment above
    the write said it was "kept on the record rather than raised as a toast, because the read that
    produced it may have happened while the designer was on another screen", which states an
    intention to display it later that nothing carried out.

    What was being discarded is the ONLY place on the web a designer is told that a fold has just
    armed a destructive save: "You had deleted everything in {entities} in this browser, so N rows
    the server still holds there have NOT been added back, and the next save will delete them on the
    server — including anything added there by somebody else since you deleted." Android draws its
    identical `DwStageFold.notice` on the stage screen, so the two surfaces disagreed about whether
    this warning exists at all.

    DRAWN HERE, ON THE BANNER, AND NOT ONLY ON THE STAGE FORM. This component is mounted in the
    protected layout, so it is on every screen the designer can reach — including the workshop list
    and the report, which is exactly where somebody goes after leaving the stage that folded. A fold
    only happens on a DIRTY stage, so a draft carrying a note always has unsent work and this banner
    is always rendered for it; the note cannot be composed and then hidden by the panel's own
    visibility rule. It is discharged by the push it warns about — see the acknowledgement sites in
    `designWorkshopStore` — so it neither repeats for ever nor disappears before the save it is
    about.
  */
  const folds = Object.values(draft.stages)
    .filter((stage) => (stage.foldNote ?? "").trim().length > 0)
    .map((stage) => ({ stageKey: stage.stageKey, note: stage.foldNote as string }));
  return {
    stages: stages.length,
    neverSent: draft.remoteId === null,
    headerOnly: draft.headerDirtyAt !== null,
    refusals,
    held,
    folds
  };
}

export function DesignWorkshopDraftBanner() {
  const drafts = useSyncExternalStore(subscribeDrafts, getDraftsSnapshot, getServerDraftsSnapshot);
  /**
   * Whether IndexedDB is answering at all — subscribed through the SAME publish the drafts use, so
   * the two snapshots can never describe different moments. See `DwStoreHealth` for why a store that
   * cannot be read must not be rendered as a store with nothing in it.
   */
  const health = useSyncExternalStore(subscribeDrafts, getStoreHealth, getServerStoreHealth);
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
    /*
      THE ROLE GOES WITH THE ID, and it answers a second question the store cannot answer alone: may
      this session MINT a workshop the server has never heard of? `createLocalDraft` is the offline
      create path, so a designer who is no longer allowed to start a workshop has to be refused HERE,
      in the courtyard, before twenty-two stages go into a record that can never be accepted — the
      server's 403 arrives two days too late to be a kindness. See
      `designWorkshopStore.mayMintLocalWorkshop` for why "nobody has told us yet" is not a refusal.
    */
    setDraftSessionUser(user?.id ?? null, user?.role ?? null);
    /*
      AND ON THE WAY OUT, BECAUSE SIGNING OUT UNMOUNTS THIS COMPONENT BEFORE THE EFFECT ABOVE CAN RUN.

      `AppShell` does `if (!user) return null;` before it ever produces `children`, and this banner is
      one of those children (protected layout). So the instant `logout()` sets `user` to null the
      whole subtree unmounts, and the re-run that would pass `null` never commits: `sessionUserId`
      kept the previous designer's id, with `sessionKnown` still true, for the entire signed-out
      period. The docstring on `setDraftSessionUser` states the opposite as a guarantee, and what
      actually held the door shut was that `syncDesignWorkshopDrafts` has exactly one call site — this
      component — inside the same unmounted subtree. A protection enforced by where a component
      happens to be mounted is not enforced.

      A cleanup is the smallest thing that closes it from inside this file. It also fires when the
      banner is unmounted for a reason OTHER than signing out — a route where `AppShell` swaps
      `children` for its blocked/chrome-hidden panel — and that direction is safe: the store then
      treats every draft as somebody else's, shows nothing and sends nothing, which is the fail-quiet
      end. The re-mount passes the real id straight back.

      The right home for this is above the `!user` return altogether — an effect in `AuthProvider` or
      a small client component in the root layout — so that the store's idea of the session does not
      depend on the render tree at all. That is a change to files outside this one and is recorded as
      outstanding.
    */
    return () => setDraftSessionUser(null, null);
    // `user?.role` joins the dependency list because a role change (an admin demoting an account
    // mid-session, or a re-auth as somebody else) has to reach the store: without it the effect
    // would not re-run and the store would go on believing this session may create a workshop.
  }, [sessionResolved, user?.id, user?.role]);

  const drain = useCallback(
    async (trigger: "auto" | "manual") => {
      setSyncing(true);
      try {
        const result = await syncDesignWorkshopDrafts();
        const outcome = syncOutcome(result);
        if (outcome.kind === "sent") {
          toast({
            id: "dw-draft-sync",
            tone: outcome.tone,
            title: outcome.title,
            description: result.pending ? `${result.pending} workshop(s) still waiting.` : "Everything on this device has been sent."
          });
        } else if (trigger === "manual") {
          // Only a click deserves an answer when nothing moved; an automatic pass stays quiet.
          /*
            THREE OUTCOMES, NOT TWO. `DwSyncResult.failed` is maintained at six sites in `runSync` —
            a refused photograph twice over, a stage whose registry entry is missing, a stage the
            server refused, a partly-refused stage, and a whole-workshop refusal — and it was read by
            nothing. So a pass in which the server refused six things answered the click in the same
            words, and the same informational tone, as a pass that had nothing to do: "Nothing to
            send". The refusals do appear as red lines further down this banner, but the toast is
            what renders on top and it is the direct answer to the button.

            Ordered refusals-first because a refusal is the only one of the three the designer can act
            on: "still no connection" is a fact about the wifi and "nothing to send" is a fact about
            an empty queue, while a refusal is somebody's decision waiting to be made.
          */
          toast({
            id: "dw-draft-sync",
            tone: outcome.tone,
            title: outcome.title,
            description:
              outcome.kind === "refused"
                ? "Nothing has been thrown away. Each one is listed below with what it needs."
                : outcome.kind === "offline"
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
      ({ state }) =>
        state.stages > 0 ||
        state.neverSent ||
        state.headerOnly ||
        state.refusals.length > 0 ||
        state.held.length > 0 ||
        state.folds.length > 0
    );

  /*
    "THIS DEVICE CANNOT TELL YOU WHAT IS HERE" IS NOT "THERE IS NOTHING HERE", AND THEY USED TO
    RENDER IDENTICALLY — as nothing at all.

    `refreshDrafts` reports an unreadable store as an empty array and `mutate` reports a refused
    write as `null`, both deliberately: neither may throw into a render or fail a designer's edit.
    The consequence was that the amber panel below — which carries the only sentence telling a
    designer not to clear this browser or hand the laptop on — simply vanished on the morning their
    store stopped answering, holding a fortnight of unsent stages. A false all-clear, produced by the
    store rather than by its absence, on the one surface that answers "may I pack up".

    Drawn ABOVE the early return, so it appears whether or not any drafts could be listed — the
    unreadable case is precisely the one in which `rows` is empty. Dismissible, because a disk freed
    an hour ago must be able to stop shouting: `acknowledgeStoreTrouble` clears the marks and repairs
    nothing, exactly like the "Try again" below it.
  */
  const trouble = !storeIsAnswering(health);

  if (!rows.length && !trouble) return null;

  const waiting = rows.filter(({ state }) => !state.refusals.length).length;

  const troublePanel = trouble ? (
    <section
      // `assertive`, unlike the amber panel's `polite`: the amber one describes a normal working
      // state that a designer reads when they choose to, and this one says the app has stopped being
      // able to answer the question the amber one exists to answer.
      aria-live="assertive"
      className="mb-4 grid gap-2 rounded-lg border border-error-600/40 bg-error-50 p-4 text-ink-900"
    >
      <div className="flex items-start gap-2">
        <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-error-600" aria-hidden />
        <div>
          <h2 className="font-display text-sm font-bold">This browser&rsquo;s local store is not answering</h2>
          <p className="mt-0.5 text-xs text-ink-700">
            {health.writeFailedAt
              ? "Something could not be written to this device's storage — usually a full disk, or a private-mode window whose storage the browser will not keep. Recent edits may not have been saved here, and this panel cannot tell you what is still waiting to be sent. Free some space, then reload before typing anything else."
              : "This device's storage could not be read, so the app cannot say what design-workshop work is still waiting here. Do NOT clear this browser's data and do not hand the laptop on: what is here may still be recoverable. Reload the page, and if this persists tell whoever runs the repository."}
          </p>
        </div>
      </div>
      <div>
        <button type="button" className="field-button-secondary" onClick={() => acknowledgeStoreTrouble()}>
          I have read this
        </button>
      </div>
    </section>
  ) : null;

  if (!rows.length) return troublePanel;

  return (
    <>
    {troublePanel}
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
              {/* The fold's warning — see `outstanding`'s note. Drawn in the refusal colour and NOT
                  in the muted "held" colour: what it describes is rows that are about to be deleted
                  on the server, which is the most consequential thing this banner ever says. */}
              {state.folds.map((fold) => (
                <p key={`fold-${fold.stageKey}`} className="mt-0.5 flex items-start gap-1 text-xs text-error-600">
                  <TriangleAlert className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
                  <span>
                    <span className="font-semibold">{fold.stageKey}: </span>
                    {fold.note}
                  </span>
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
    </>
  );
}
