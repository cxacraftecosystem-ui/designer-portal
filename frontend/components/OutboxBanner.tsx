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
 * Entries the server permanently rejected stay listed with the reason, and each offers TWO controls:
 * "Try again", which clears the refusal so the next pass re-sends it, and Discard, which throws the
 * record and its photographs away. For most of this banner's life it offered only the second. That
 * was survivable while every refusal really was somebody's decision — a duplicate Aadhaar, a field
 * the validator rejected — and it stopped being survivable the moment a 401 or a captive portal
 * could mark an entry: the researcher was then holding a queue whose only offered action deleted the
 * work. Nothing here deletes an entry on its own, and now nothing here forces a person to.
 *
 * "TRY AGAIN" IS ONLY SAFE BECAUSE OF WHAT IT MEETS. A drain that finds nothing left to send deletes
 * the entry and its files and reports it sent, so a control that clears a refusal is only benign if
 * every file the entry is still owed is genuinely still owed — which is why `lib/offline.ts` narrows
 * a partly refused media batch to the files that did not land instead of recording the batch as
 * finished. Adding this button before that was a silent deletion of the photographs the server had
 * turned down. The two changes belong together and should not be separated.
 */

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { CircleAlert, CloudOff, Link2, RefreshCw, Trash2, TriangleAlert } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast, type ToastTone } from "@/components/ui/Toast";
import { listResource } from "@/lib/api";
import {
  acknowledgeOutboxTrouble,
  danglingKeys,
  discardOutboxEntry,
  getOutboxHealth,
  getOutboxQueuedHere,
  getOutboxSnapshot,
  getServerOutboxHealth,
  getServerOutboxQueuedHere,
  getServerOutboxSnapshot,
  outboxEntriesForAnotherAccount,
  outboxIsAnswering,
  outboxOtherAccountLine,
  referenceFieldNoun,
  refreshOutbox,
  repickEmptyLine,
  repickOutboxEntry,
  retryOutboxEntry,
  setOutboxSessionUser,
  subscribeOutbox,
  syncOutbox,
  type OutboxEntry,
  type SyncResult
} from "@/lib/offline";

/** Files across every batch of one entry — what the user attached, not how the form grouped it. */
function fileCount(entry: OutboxEntry): number {
  return entry.media.reduce((sum, batch) => sum + batch.files.length, 0);
}

/**
 * What a completed pass should SAY, given what it did.
 *
 * Exported and pure so the choice can be asserted without a browser, a store and a refused save —
 * which is the condition under which the two-way version survived for as long as it did. It had
 * exactly two answers for a pass that sent nothing: "Still no connection" and "Nothing to send".
 * FOUR different situations were being reported as one of those two, and each was a lie:
 *
 *   • a media failure stopped the pass, so it read as "Still no connection" on a laptop with four
 *     bars — and offered nothing to do about it;
 *   • the sign-in had expired, which is neither of those things and is the one a person can fix in
 *     ten seconds if they are told;
 *   • another tab was draining the same queue — "Still no connection" sends the researcher out to
 *     look for signal they already have;
 *   • the device could not be read at all, and reported zero remaining, so a queue nobody can see
 *     was announced as an empty one. That is the worst of the four, because it is an affirmative
 *     all-clear: the answer to "may I hand this laptop on".
 *
 * The order is deliberate. What was SENT first, because that is the answer to the button. Then the
 * two the researcher can act on — an expired sign-in, and refusals waiting for a decision — because
 * "no connection" and "nothing to send" are facts about the world and a refusal is somebody's
 * decision waiting to be made. The store's own trouble comes above "nothing to send" for the reason
 * the fourth bullet gives; the red panel below says the same thing at greater length.
 *
 * BUT "SENT" AND "EXPIRED" ARE NOT EXCLUSIVE, AND THE ORDER ALONE MADE THE SECOND ONE VANISH. The
 * pass reaches an expiry MID-QUEUE by construction — it sends until a 401 and then breaks, keeping
 * whatever it had already sent — so a token that died on the third of nine entries produced "2 saved
 * entries sent · 6 still waiting", and the sentence naming what those six are waiting for was never
 * shown. Six still waiting is the true half; the reason is the actionable half, and it is the one
 * thing on this list a person can fix in ten seconds. So the expiry is folded into the description
 * rather than allowed to lose to the count.
 */
export function outboxOutcome(result: SyncResult): {
  kind: "sent" | "expired" | "refused" | "offline" | "busy" | "unreadable" | "idle";
  tone: ToastTone;
  title: string;
  description?: string;
} {
  if (result.synced) {
    /*
      A RECORD THAT LANDED UNFILED IS REPORTED HERE AND NOT AS A FAILURE, because it is not one.

      Folded into the SENT description rather than given its own arm, for the same reason the expiry
      below is: the two are not exclusive, and an arm under "sent" would never be reached on the pass
      that produced it. It replaces the count-only trailer, which is the half a person can already
      read off the banner; what they cannot see anywhere is the empty column.
    */
    const unfiled = result.sentUnfiled ?? [];
    return {
      kind: "sent",
      // Not "success" when the pass ended on an expiry: something was sent AND something needs doing,
      // and the tone is the only part of a toast a person takes in without reading it.
      tone: result.credentialExpired ? "error" : "success",
      title: `${result.synced} saved ${result.synced === 1 ? "entry" : "entries"} sent`,
      description: result.credentialExpired
        ? `Your sign-in expired part way through, so the rest did not go — ${result.remaining} ${
            result.remaining === 1 ? "entry is" : "entries are"
          } still on this device and nothing has been thrown away. Sign in again, then use Sync now.`
        : unfiled.length
          ? unfiled.join(" ")
          : result.remaining
            ? `${result.remaining} still waiting.`
            : "The outbox is empty."
    };
  }
  if (result.credentialExpired) {
    return {
      kind: "expired",
      tone: "error",
      title: "Your sign-in has expired",
      description:
        "Nothing has been sent and nothing has been thrown away — everything below is still on this device. Sign in again, then use Sync now."
    };
  }
  if (result.failed > 0) {
    return {
      kind: "refused",
      tone: "error",
      title: `${result.failed} ${result.failed === 1 ? "entry was" : "entries were"} refused`,
      description: "Nothing has been thrown away. Each one is listed below with what it needs."
    };
  }
  if (result.stoppedOffline) {
    return {
      kind: "offline",
      tone: "error",
      title: "Still no connection",
      description: "Everything stays queued on this device. Try again once you have signal."
    };
  }
  if (result.declined) {
    return {
      kind: "busy",
      tone: "info",
      title: "Another tab is sending these",
      description: `This device is already sending its outbox in another tab. ${result.remaining} still waiting — leave it open until it finishes.`
    };
  }
  if (result.storeUnreadable) {
    return {
      kind: "unreadable",
      tone: "error",
      title: "This device could not be read",
      description:
        "The app cannot say what is still waiting here, so do not assume it is empty. Reload the page, and do not clear this browser's data."
    };
  }
  /*
    NOTHING MOVED AND NOTHING FAILED — and there is one way that can be true with a queue that is not
    empty: every entry left belongs to another account (2026-09-03). "Nothing to send" is then
    accurate about this session and useless about the device, so the same line the banner draws is
    attached as the description rather than a second wording being invented for one toast.

    Reachable only through a pass somebody else started — "Sync now" is gated on `waiting`, which
    excludes these — so it is the belt rather than the surface. `?? 0` because `otherAccount` is
    optional: a hand-built result asserting something else is not obliged to state it.
  */
  const stranded = result.otherAccount ?? 0;
  return {
    kind: "idle",
    tone: "info",
    title: "Nothing to send",
    description: stranded ? outboxOtherAccountLine(stranded) : undefined
  };
}

/**
 * WHERE A DANGLING COLUMN'S REPLACEMENTS COME FROM — the endpoint, and the noun for the sentence.
 *
 * Only the two workshop columns are offered today, and that is a fact about the FAILURE rather than
 * a limit of this panel: a queued record's craft, artisan, product and tool ids come from registers
 * none of the six forms will let you enter a fabricated id into, while the two workshop links are
 * the ones an administrator can delete at the office between a courtyard save and the drain. A
 * column with no entry here still gets the sentence and the honest dead-end wording; it simply gets
 * no button, which is better than a button that opens an empty panel.
 */
const REPICK_SOURCES: Record<string, { endpoint: string; noun: string; label: (row: RepickRow) => string }> = {
  designWorkshopId: {
    endpoint: "/design-workshops",
    noun: "design & prototype workshop",
    label: (row) => [row.title, row.craftName, row.clusterName ?? row.state].filter(Boolean).join(" · ")
  },
  workshopId: {
    endpoint: "/workshops",
    noun: "workshop",
    label: (row) => [row.title, row.place].filter(Boolean).join(" · ")
  }
};

type RepickRow = {
  id: string;
  title?: string | null;
  place?: string | null;
  craftName?: string | null;
  clusterName?: string | null;
  state?: string | null;
};

/**
 * The panel behind "Re-pick it": choose what this queued record should point at instead.
 *
 * ── THE LIST IS FETCHED LIVE, EVERY TIME, AND NEVER FROM `lib/referenceCache.ts` ──
 *
 * R6 binds here HARDER than it does on a form. This is a question about which workshops the account
 * may file against, and a cached answer is wrong in the permissive direction — it would offer a
 * grant revoked in March, and the drain would meet the identical refusal one release later with the
 * researcher having been told the problem was fixed. `putCachedRegister` will not compile for
 * either of these two models; this fetch is what that refusal implies on this surface.
 *
 * ── AND WHY THE ANSWER IS NOT JUST AN ARRAY ──
 *
 * An empty array is three different facts — still asking, the read failed, the scope holds none —
 * and the one a reader assumes is the one that says *there are none*, which on this panel tells
 * somebody their only route out is closed. `repickEmptyLine` words the last two; `rows === null` is
 * the first.
 */
function RepickDialog({
  entry,
  field,
  onClose,
  onDone
}: {
  entry: OutboxEntry;
  field: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const source = REPICK_SOURCES[field];
  const [rows, setRows] = useState<RepickRow[] | null>(null);
  const [listed, setListed] = useState(false);
  const [choice, setChoice] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!source) return;
    let cancelled = false;
    listResource<RepickRow>(source.endpoint, { pageSize: 80 })
      .then((page) => {
        if (cancelled) return;
        setRows(page.items);
        setListed(true);
      })
      .catch(() => {
        // A THROW IS NOT AN EMPTY LIST. `listed` stays false, and `repickEmptyLine` then says the
        // list could not be READ rather than that the account has none — two sentences whose next
        // moves are a connection and an administrator, and somebody sent to the wrong one of those
        // loses a day.
        if (!cancelled) setRows([]);
      });
    return () => {
      cancelled = true;
    };
  }, [source]);

  const noun = source?.noun ?? referenceFieldNoun(field);
  const options = (rows ?? []).map((row) => ({ value: row.id, label: source?.label(row) || row.id }));

  return (
    <FieldDialog
      open
      onClose={onClose}
      busy={saving}
      icon={<Link2 className="h-4 w-4" aria-hidden />}
      title={`Point “${entry.label}” at another ${noun}`}
      /*
        STATE, ACT, ONE SHORT REASSURANCE (2026-09-03). "Nothing has been sent" is what a queued
        entry already means — the row it was opened from says so — so the half worth the words beside
        a picker is that nothing was deleted. Tersened with the whole outbox family, and with
        Android's, so one queue is not described in two voices.
      */
      description="Choose one that is on the server and this entry sends itself. Nothing was deleted."
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button
            type="button"
            className="field-button"
            disabled={saving || rows === null}
            onClick={async () => {
              setSaving(true);
              try {
                // An empty `choice` is the "none of them" answer and goes as an explicit null — see
                // `repickOutboxEntry`, which records it as a DECISION rather than as an empty list.
                await repickOutboxEntry(entry.id!, field, choice || null);
                onDone();
              } finally {
                setSaving(false);
              }
            }}
          >
            {saving ? "Saving…" : "Use this"}
          </button>
        </>
      }
    >
      {rows === null ? (
        <p className="text-sm text-ink-700">Reading the {noun} list{"…"}</p>
      ) : options.length === 0 ? (
        <p className="text-sm text-ink-700">{repickEmptyLine(noun, listed)}</p>
      ) : (
        <Dropdown
          value={choice}
          onChange={setChoice}
          searchable
          ariaLabel={`Choose a ${noun}`}
          placeholder={`File it under no ${noun}`}
          options={[{ value: "", label: `File it under no ${noun}` }, ...options]}
        />
      )}
    </FieldDialog>
  );
}

export function OutboxBanner() {
  const entries = useSyncExternalStore(subscribeOutbox, getOutboxSnapshot, getServerOutboxSnapshot);
  /**
   * Whether IndexedDB is answering at all — subscribed through the SAME publish the entries use, so
   * the two snapshots can never describe different moments. See `OutboxStoreHealth` for why a store
   * that cannot be read must not be rendered as a store with nothing in it.
   */
  const health = useSyncExternalStore(subscribeOutbox, getOutboxHealth, getServerOutboxHealth);
  /**
   * How many saves this tab has banked into the outbox since it loaded — the EVENT behind the
   * "saved on this device" confirmation. See `getOutboxQueuedHere` for why the row count could not
   * be that event: rows also go up when the store is READ, and the two are indistinguishable from
   * here.
   */
  const queuedHere = useSyncExternalStore(subscribeOutbox, getOutboxQueuedHere, getServerOutboxQueuedHere);
  const [syncing, setSyncing] = useState(false);
  /**
   * The entry whose dangling column is being re-pointed, and which column.
   *
   * THE FIELD TRAVELS WITH THE ENTRY rather than being re-derived when the panel opens. An entry can
   * name more than one candidate (see `OutboxEntry.danglingField`), and re-deriving would let a
   * refresh between the click and the render change which box the person is answering about.
   */
  const [repicking, setRepicking] = useState<{ entry: OutboxEntry; field: string } | null>(null);
  const { toast } = useToast();
  const { user, loading: authLoading } = useAuth();

  /**
   * TELL THE QUEUE WHOSE SESSION THIS IS, before anything can drain.
   *
   * The twin of `DraftSyncBanner`'s `setDraftSessionUser` effect, one queue along and for the same
   * shared field laptop: `AuthProvider.logout` clears the token and the user and deliberately
   * nothing else, so designer A's queued records and their staged photographs survive the handover
   * — and without this, designer B's session drains them under B's token. See
   * `OutboxEntry.ownerUserId`.
   *
   * GUARDED ON THE AUTH PROBE FINISHING. `user` is null both before `GET /me` answers and after a
   * sign-out, and the two mean opposite things: stamping null in the first would leave a save made
   * in that window unowned for ever, which is the boundary quietly not existing for one record.
   *
   * AND CLEARED ON THE WAY OUT, for the reason `DraftSyncBanner`'s cleanup spells out: `AppShell`
   * returns null before it produces `children`, and this banner is one of those children, so the
   * instant `logout()` sets `user` to null the whole subtree unmounts and the re-run that would pass
   * null never commits. Without the cleanup the module would hold the previous designer's id through
   * the entire signed-out period — and the next sign-in's effect would then be the only thing
   * correcting it, after a mount drain has already run.
   */
  useEffect(() => {
    if (authLoading) return;
    setOutboxSessionUser(user?.id ?? null);
    return () => setOutboxSessionUser(null);
  }, [authLoading, user?.id]);

  // Confirming a queued save belongs HERE, not in each of the six forms that can queue one. A form
  // that queues just stops and scrolls; every one of them reaches `queueOffline`, so one watcher
  // covers every save path and cannot drift out of step with the banner beneath it.
  //
  // WHAT IS COUNTED IS SAVES, NOT ROWS, and the difference cost this message twice. Watching the row
  // count made a fresh mount's LOAD look like a burst of saves — a researcher opening the laptop the
  // next morning, online, was told her week-old queue had "just been saved on this device with no
  // connection" — and the baseline that fixed it could only be armed by a successful read, so when
  // the store was unreadable AT MOUNT the first real save afterwards armed the baseline silently and
  // said nothing. A counter that only a banked write moves has neither half of that: it starts at
  // zero every page load, a read never touches it, and there is no moment at which arming is missed.
  const announcedQueued = useRef(0);

  useEffect(() => {
    const seen = announcedQueued.current;
    if (queuedHere <= seen) return;
    const added = queuedHere - seen;
    announcedQueued.current = queuedHere;
    toast({
      id: "outbox-queued",
      tone: "info",
      title: `${added === 1 ? "Entry" : `${added} entries`} saved on this device`,
      description: "There is no connection, so it is queued below and sends itself when signal returns."
    });
  }, [queuedHere, toast]);

  const drain = useCallback(
    async (trigger: "auto" | "manual") => {
      setSyncing(true);
      try {
        const result = await syncOutbox();
        const outcome = outboxOutcome(result);
        // Only a click deserves an answer when nothing moved; an automatic pass stays quiet — except
        // when the pass has something to say that the researcher cannot otherwise learn. An expired
        // sign-in and a device that cannot read its own outbox are both silent failures of the
        // "it sends itself" promise, and an automatic pass is exactly when nobody is watching.
        const speaks =
          outcome.kind === "sent" || trigger === "manual" || outcome.kind === "expired" || outcome.kind === "unreadable";
        if (speaks) {
          toast({ id: "outbox-sync", tone: outcome.tone, title: outcome.title, description: outcome.description });
        }
      } catch (error) {
        /*
          A PASS CAN REJECT, AND AN UNHANDLED REJECTION IS NO ANSWER AT ALL.

          Every write the pass makes — the failure mark, the progress write, the delete — is an
          IndexedDB transaction, and on a field laptop twelve days into a study the ordinary way one
          of those ends is `QuotaExceededError`. There was no catch here, so the click produced
          nothing: no toast, no message, the spinner stopped and the queue was exactly as it had
          been. The store's own trouble panel below is drawn from the health record `lib/offline.ts`
          writes on the way past, so this only has to answer the person who pressed the button.
        */
        toast({
          id: "outbox-sync",
          tone: "error",
          title: "This device could not finish sending",
          description:
            error instanceof Error && error.message
              ? `${error.message} Nothing has been thrown away — reload the page and try again.`
              : "Nothing has been thrown away — reload the page and try again."
        });
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
   * IT ARMS NOTHING. This read used to set the baseline for the "n entries were just queued" toast,
   * and had to be careful about it in two directions at once — a load is not a save, and a load that
   * FAILED must not bank a baseline of zero for the real rows to arrive against later. Neither
   * question exists now that the toast counts banked saves rather than rows: see `queuedHere` above.
   */
  useEffect(() => {
    void refreshOutbox().then((rows) => {
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

  /*
    "THIS DEVICE CANNOT TELL YOU WHAT IS HERE" IS NOT "THERE IS NOTHING HERE", AND THEY USED TO
    RENDER IDENTICALLY — as nothing at all.

    `refreshOutbox` reports an unreadable store as an empty array, deliberately: it may not throw
    into whichever render happened to ask. The consequence was that this whole banner — which
    carries the only sentence telling a researcher not to clear this browser or hand the laptop on —
    simply vanished on the morning their store stopped answering, still holding a fortnight of
    queued records. A false all-clear, produced by the store rather than by its absence, on the one
    surface that answers "may I pack up".

    Drawn ABOVE the early return, so it appears whether or not any entries could be listed — the
    unreadable case is precisely the one in which the list is empty. Dismissible, because a disk
    freed an hour ago must be able to stop shouting: `acknowledgeOutboxTrouble` clears the marks and
    repairs nothing, exactly like the "Try again" below it. Word for word the sibling panel in
    `DraftSyncBanner`, because a designer meets both on the same screens and one device in two
    vocabularies reads as two different problems.
  */
  const trouble = !outboxIsAnswering(health);

  if (!entries.length && !trouble) return null;

  const rejected = entries.filter((entry) => entry.failure);
  /*
    THE THIRD THING A CONNECTION DOES NOT MOVE, AND IT USED TO ARRIVE AS "WAITING".

    An entry captured by another account carries no `failure` — nothing was ever sent and nothing was
    refused — so it fell into `waiting` and was drawn under a cloud-off icon inside a sentence
    promising it sends itself when the connection returns. No amount of signal will move it; the
    remedy is a different person signing in. Android's `outboxCountsOf` closed the identical door on
    the handset and its KDoc names this as the defect the whole banner exists to end, reached by a
    third route.

    SUBTRACTED RATHER THAN HIDDEN. The entries stay in the list below, because they are real
    fieldwork sitting in this browser's storage and a designer looking for a fortnight they captured
    yesterday needs to see it while the wrong person is signed in — a queue that silently drops to
    zero is how somebody concludes the work was lost and stops looking.
  */
  const otherAccount = outboxEntriesForAnotherAccount(entries, user?.id ?? null);
  const waiting = entries.length - rejected.length - otherAccount;

  const troublePanel = trouble ? (
    <section
      // `assertive`, unlike the amber panel's `polite`: the amber one describes a normal working
      // state that a researcher reads when they choose to, and this one says the app has stopped
      // being able to answer the question the amber one exists to answer.
      aria-live="assertive"
      className="mb-4 grid gap-2 rounded-lg border border-error-600/40 bg-error-50 p-4 text-ink-900"
    >
      <div className="flex items-start gap-2">
        <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-error-600" aria-hidden />
        <div>
          <h2 className="font-display text-sm font-bold">This browser&rsquo;s offline outbox is not answering</h2>
          <p className="mt-0.5 text-xs text-ink-700">
            {health.writeFailedAt
              ? "Something could not be written to this device's storage — usually a full disk, or a private-mode window whose storage the browser will not keep. A queued record may not have been saved here, and this panel cannot tell you what is still waiting to be sent. Free some space, then reload before typing anything else."
              : "This device's storage could not be read, so the app cannot say what saved records are still waiting here. Do NOT clear this browser's data and do not hand the laptop on: what is here may still be recoverable. Reload the page, and if this persists tell whoever runs the repository."}
          </p>
        </div>
      </div>
      <div>
        <button type="button" className="field-button-secondary" onClick={() => acknowledgeOutboxTrouble()}>
          I have read this
        </button>
      </div>
    </section>
  ) : null;

  if (!entries.length) return troublePanel;

  return (
    <>
    {repicking ? (
      <RepickDialog
        entry={repicking.entry}
        field={repicking.field}
        onClose={() => setRepicking(null)}
        onDone={() => {
          setRepicking(null);
          // Drain straight away, exactly as "Try again" does: the answer is then immediate — either
          // the entry goes, or it comes back saying what is now wrong — rather than waiting for the
          // next `online` event that may not come for a day.
          void drain("manual");
        }}
      />
    ) : null}
    {troublePanel}
    <section
      aria-live="polite"
      className="mb-4 grid gap-3 rounded-lg border border-amber-500/40 bg-amber-100/60 p-4 text-ink-900"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          {/*
            THE ICON FOLLOWS THE WAITING HALF ONLY (2026-09-03), and it used to draw unconditionally.
            This is Android's gate to the predicate — `outboxDeviceBanner` computes
            `showCloudOff = counts.waiting > 0` and `MainActivity` draws `CloudOff` or `ErrorOutline`
            off it — and the reason is the same one the heading and the sentence under it were both
            corrected for: a cloud-off over a queue holding only refusals, or only another account's
            entries, tells a designer to go and find a signal, and a signal is precisely what cannot
            move either of them. `waiting` is already the count with both of those subtracted, so the
            icon, the heading, the sentence and the "Sync now" button all read one number.

            `CircleAlert` and not the trouble panel's `TriangleAlert`: that panel says the store has
            stopped answering, and one glyph for both would put "your disk is full" and "read this
            refusal" in the same voice on the same screen. Both keep `text-amber-800` — this panel
            has one ink, and the icon is never the only thing that changed.
          */}
          {waiting ? (
            <CloudOff className="mt-0.5 h-4 w-4 shrink-0 text-amber-800" aria-hidden />
          ) : (
            <CircleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-800" aria-hidden />
          )}
          <div>
            <h2 className="font-display text-sm font-bold">
              {waiting
                ? `${waiting} ${waiting === 1 ? "entry is" : "entries are"} saved on this device only`
                : "Entries on this device need your attention"}
            </h2>
            {/*
              TERSENED WITH THE WHOLE OUTBOX FAMILY (2026-09-03), and one clause is not negotiable.
              "They were made without a connection and have not reached the repository yet" is the
              heading above said again, so it went. "They live in this browser, so do not clear its
              data or hand the laptop on" STAYS: `lib/offline.ts`'s `OutboxStoreHealth` note records
              the morning this whole panel disappeared over an unreadable store, and names this as
              the one sentence that answers "may I hand this laptop on". A shorter line must not be
              the way it goes missing a second time.
            */}
            <p className="mt-0.5 text-xs text-ink-700">
              {waiting
                ? "They send themselves when the connection returns. They live in this browser — do not clear its data or hand the laptop on until the outbox is empty."
                : rejected.length
                  ? "Nothing is waiting on the network. The entries below were refused by the server and need a decision."
                  : // Nothing waiting and nothing refused leaves exactly one reason the queue is not
                    // empty, and the line below states it. The refusal sentence would be a lie here:
                    // the server never saw these entries.
                    "Nothing here is waiting on the network."}
            </p>
            {/*
              ITS OWN LINE, AND NOT A CLAUSE ON EITHER OF THE TWO ABOVE — Android's
              `outboxDeviceBanner` gives its equivalent one for the same two reasons. Folded into the
              waiting sentence it would promise a send that cannot happen; folded into the refusal
              sentence it would send a designer to a row that was never refused and carries no reason
              to read. The words are `outboxOtherAccountLine`'s, so the sentence is pinned in a spec
              rather than written inside a component no test can render.
            */}
            {otherAccount ? (
              <p className="mt-1 text-xs font-medium text-amber-800">{outboxOtherAccountLine(otherAccount)}</p>
            ) : null}
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
              <div className="flex shrink-0 items-center gap-3">
                {/*
                  THE THIRD BUTTON, AND THE ONLY ROW IN THIS BANNER WITH A REMEDY THAT WORKS.

                  Everything else here is waiting on a permission, a newer build, or a comparison
                  only a person can make. This one is waiting on one click: the record is complete,
                  in this browser, with its photographs, and exactly one id in it is wrong. Without
                  it the row offered *Try again*, which fetches the identical 404, and *Discard*,
                  which destroys the only copy — "a dead end wearing the costume of a remedy", as
                  `lib/offline.ts` puts it about the 409 that got its own arm years before this one.

                  DRAWN ONLY WHERE THERE IS SOMETHING TO RE-PICK. A dangling column this panel has no
                  source for keeps its sentence and loses its button; an empty panel would be the
                  same dead end one screen further in.
                */}
                {danglingKeys(entry)
                  .filter((key) => key in REPICK_SOURCES)
                  .slice(0, 1)
                  .map((key) => (
                    <button
                      key={key}
                      type="button"
                      className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700"
                      disabled={syncing}
                      onClick={() => setRepicking({ entry, field: key })}
                    >
                      <Link2 className="h-3.5 w-3.5" aria-hidden />
                      Re-pick it
                    </button>
                  ))}
                {/*
                  TRY AGAIN COMES FIRST, AND IT IS THE LOAD-BEARING HALF.

                  Discard was the only control this banner ever offered a refused entry, and it
                  deletes the record and every photograph attached to it. That is the right control
                  for a duplicate the server will never accept — and the wrong one, catastrophically,
                  for the refusals that are nothing to do with the record: a seven-day token that
                  expired while the laptop was shut, a hotel wi-fi that answered the POST with its own
                  sign-in page, a server that was briefly down. A researcher who has read "the server
                  rejected this" and has only one button will eventually press it.

                  It clears the refusal and then drains, so the answer is immediate: either the entry
                  goes, or it comes back with what is now wrong. `retryOutboxEntry` re-sends nothing
                  by itself — see its note.
                */}
                <button
                  type="button"
                  className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700"
                  disabled={syncing}
                  onClick={async () => {
                    await retryOutboxEntry(entry.id!).catch(() => {
                      // The store refused the write, so the recorded refusal stands — the safe end,
                      // since nothing has been sent and nothing deleted. Swallowed rather than
                      // toasted because the trouble panel above is already being drawn: a failed
                      // write publishes on its own from `noteStoreFailure`, which it did not always
                      // do, and which is what makes this comment true rather than hopeful.
                    });
                    await drain("manual");
                  }}
                >
                  <RefreshCw className="h-3.5 w-3.5" aria-hidden />
                  Try again
                </button>
                <button
                  type="button"
                  className="inline-flex items-center gap-1 text-xs font-semibold text-error-600"
                  onClick={() =>
                    // A discard that cannot be written used to be an unhandled rejection: the row
                    // stayed on screen and nothing said why. It is the one action here that destroys
                    // work, so it is the one that must never fail in silence.
                    void discardOutboxEntry(entry.id!).catch(() =>
                      toast({
                        id: "outbox-discard",
                        tone: "error",
                        title: "This device would not let go of that entry",
                        description: "It is still here and nothing has been deleted. Reload the page and try again."
                      })
                    )
                  }
                >
                  <Trash2 className="h-3.5 w-3.5" aria-hidden />
                  Discard
                </button>
              </div>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
    </>
  );
}
