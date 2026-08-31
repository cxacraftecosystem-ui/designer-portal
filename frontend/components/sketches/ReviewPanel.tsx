"use client";

/**
 * The REVIEW tab: rate the other designers' work, and settle the order the pieces stand in.
 *
 * ── WHAT IT IS A VIEW OVER ──────────────────────────────────────────────────────────────────────
 *
 * Nothing here stores a sketch or a prototype. The pieces are `DwStageEntry` rows of the ordinary
 * 22-stage record — `sketch` in stage 11, `prototype` in stage 13 — and the ranking is their
 * `ordinal`, the same number the stage form's up/down arrows and the handset both already write.
 * This panel reads them through `GET /design-ratings/rounds/{round}` and writes an arrangement back
 * through the stage the rows live in. A second way to add a prototype is exactly what this
 * repository refuses, and this is not one.
 *
 * ── THE DEFAULT ORDER, THE FIXED ORDER, AND WHY THE PAGE MUST SAY WHICH ─────────────────────────
 *
 * The list is sorted by the quantitative data by default. A designer may rearrange it, and from
 * that moment the arrangement is theirs: a rating that lands afterwards changes the numbers on the
 * cards and moves none of them. The two states are told apart by
 * `sketch.rankFixedBy`/`rankFixedAt` (and the identical pair on `prototype`) — blank means the
 * computed score still governs — and the banner above the list says which one a reader is looking
 * at, in words, with the way back to the default beside it. See `reviewRanking.openingOrder`.
 *
 * **THE STAMP IS NOT ON THE RANKING RESPONSE.** `ranked_payload` sends positions and scores, not
 * the row's stored fields, so it can only be read where the stage rows themselves are readable —
 * which is the workshop's own tab, where they are in the local draft anyway.
 *
 * WHICH SETTLES WHAT THE POOL LIST IS: the score order, said in those words. A pool reviewer holds
 * no rows, so `fixedOrderStamp([])` is null, so `openingOrder` returns the score order — and the
 * banner has to say THAT. It used to say "this is the workshop's own arrangement" while the cards
 * beneath it each said "(this list is in score order)", which is three statements on one screen with
 * two of them wrong: the exact confusion the whole default-versus-override rule exists to prevent,
 * arrived at from the other side. The workshop's own placement is still shown, as a number on each
 * card, wherever the caller sees the whole collection — see `ReviewCard.showPlaced`.
 *
 * ── A NUDGE IS INSTANT AND THE SAVE IS COALESCED ─────────────────────────────────────────────────
 *
 * Ranking eight pieces with the arrows is eight moves in a few seconds, and each move used to be a
 * whole stage push with every arrow and every handle on the list disabled until it came back. On the
 * one-bar connection this feature is built for that is tens of seconds of dead UI and eight saves
 * where one was wanted, and a designer could not make two moves in a row without waiting. So the
 * reorder is applied to the list at once and the write is scheduled after {@link QUIET_MS} of quiet,
 * with a flush on unmount; the controls are never locked while it happens. The cost is named rather
 * than hidden: a tab closed inside that window loses the last nudge, which is why the window is short
 * and why the note above the list says the arrangement is not yet saved while it is open.
 *
 * ── WHY A REORDER GOES THROUGH THE DRAFT STORE AND NOT STRAIGHT AT THE API ──────────────────────
 *
 * There is no reorder endpoint, and there should not be one: the ordinal is written by the stage
 * save, inside the transaction that writes the rest of the stage. So a reorder here writes the new
 * arrangement into the local draft with `putDraftStage` and asks `syncDesignWorkshopDrafts` to
 * carry it up — the same two calls the stage form makes, which means it inherits the whole of the
 * protocol nobody should reimplement: `merge` on a stage this browser has never read, the sweep
 * withheld unless it has been earned, the per-stage failure record, and the banner in the protected
 * layout that reports what has not landed. It also means a reorder made with no signal is durable
 * and sends itself later, which on this fleet is the ordinary case rather than the exception.
 *
 * ── OFFLINE ─────────────────────────────────────────────────────────────────────────────────────
 *
 * The stage rows are read from IndexedDB before the network is asked, so with no signal the tab
 * still shows the pieces — in their stored order, named, with a sentence saying the scores and the
 * reviews could not be reached. It never shows an empty list for a connection failure: "no
 * prototypes" and "cannot reach the server" are different facts and this repository has shipped the
 * first as a disguise for the second more than once.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * NEITHER LIST ON THIS PANEL IS PARTITIONED TENTATIVE-FIRST, AND HERE IS WHY NOT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `sketch.isTentative` landed on 2026-08-30 with the owner's rule that tentative sketches come to
 * the top of the list. `lib/sketchTentative.ts` carries that partition and the rule for where it may
 * be applied: a surface that READS a list, never one that WRITES it. This panel is the second of the
 * two that write, and it fails the test three times over:
 *
 *   1. `order` IS THE WRITE. Whatever this list draws is what `arrangeRows` puts into the draft on
 *      the next save, and `buildStageEntries` turns that array into `ordinal`. Partitioning the
 *      display here would not be a view of the arrangement — it would BE a new arrangement, saved,
 *      and unticking the box could no longer restore a row's place.
 *   2. AN ARRANGEMENT SOMEBODY FIXED IS A DECISION. Where `rankFixedBy`/`rankFixedAt` are set, a
 *      designer took responsibility for this exact order and the banner above says so by name.
 *      Moving rows in it on the strength of a flag nobody stamped is the score-re-sorts-a-fixed-list
 *      failure the whole override rule exists to prevent, arrived at from a new direction.
 *   3. THE UNFIXED LIST IS THE SCORE ORDER, and the banner says "highest first, and pieces nobody
 *      has rated yet at the end". Tentative-first would make that sentence false — and it would be
 *      false only on this route: the POOL surface holds no rows at all (`readsStageRows` is false,
 *      its reader is refused the workshop), so it cannot read the flag, and the same list would be
 *      ordered one way for the workshop's own designers and another for everybody else. Two surfaces
 *      disagreeing about one list is the thing this file's header already spends three paragraphs
 *      refusing.
 *
 * THE OFFLINE LIST BELOW IS NOT PARTITIONED EITHER, for a fourth reason that is about this screen
 * rather than about writes: it is the stand-in for the ranked list, on the same tab, and a list that
 * reordered itself when the signal came back would be this panel telling a designer two different
 * things about one workshop depending on their connection.
 *
 * WHAT IS SHOWN INSTEAD is the word, on the card — see `tentativeWord` below. A reviewer choosing
 * between eight sketches is owed the fact that the maker has not settled on one of them; what they
 * are not owed is an order nobody chose.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { AlertTriangle, CloudOff, Loader2, PinOff, RefreshCw } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { isUnreachable } from "@/lib/failureTriage";
import { isTentativeRow, tentativeField } from "@/lib/sketchTentative";
import { formatDate } from "@/lib/format";
import type { DwRegistry, DwRow } from "@/lib/designWorkshops";
import {
  loadDraft,
  putDraftStage,
  syncDesignWorkshopDrafts,
  type DwSyncResult
} from "@/lib/designWorkshopStore";

import { fetchRoundRanking, refusalText } from "./ratingsApi";
import { readRegistry, readStageRows } from "./stageRows";
import { syncPassNote } from "./syncNote";
import { RankableList } from "./RankableList";
import { ReviewCard } from "./ReviewCard";
import {
  arrangeRows,
  fixedOrderStamp,
  heldOrder,
  mayArrange,
  openingOrder,
  reconcileOrder,
  rowSubtitle,
  todayStamp,
  type DesignRating,
  type FixedOrderStamp,
  type RankedItem,
  type RateableEntityKey,
  type RatingRound
} from "./reviewRanking";

const UNREACHABLE =
  "The server could not be reached, so the scores and the reviews are not on this screen. This is not an empty list — it is a list that could not be loaded.";

/** How long the list stays quiet before an arrangement is written. See the header. */
const QUIET_MS = 1200;

/**
 * What to say about a sync pass this panel's arrangement save has just run.
 *
 * THE FOUR OUTCOMES AND THE REASONING BEHIND THEM NOW LIVE IN `./syncNote`, because the UPLOAD tab
 * needed the identical answer for a file it had just staged — see that file's header for why
 * `failed === 0 && !stoppedOffline` is the shape of two passes that carried nothing of ours. This
 * wrapper is kept so the subject of the sentence is decided where the fact is known: only this panel
 * knows whether it just fixed an order or returned to the default one.
 */
function sendNote(result: DwSyncResult, fixed: boolean): string {
  return syncPassNote(result, fixed ? "this arrangement is" : "the return to score order is");
}

type Props = {
  workshopId: string;
  round: RatingRound;
  /**
   * Whether this surface may read and write the workshop's own stage rows.
   *
   * True on the workshop route, whose reader has already been through `load_workshop_or_404`;
   * false on the pool route, whose reader is by definition somebody that helper turns away. It
   * governs the local draft, not the ranking — the ranking has its own, narrower door.
   */
  readsStageRows: boolean;
  /** Which piece is being reviewed. The pool round only ever ranks prototypes; see the page. */
  entityKey: RateableEntityKey;
};

export function ReviewPanel({ workshopId, round, readsStageRows, entityKey }: Props) {
  const { user } = useAuth();
  const generation = useRef(0);
  const draftId = useRef<string | null>(null);
  /** True once the designer has moved something in this session — see the order effect. */
  const arranged = useRef(false);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [stageKey, setStageKey] = useState<string | null>(null);
  const [rows, setRows] = useState<DwRow[]>([]);
  const [items, setItems] = useState<RankedItem[] | null>(null);
  const [order, setOrder] = useState<string[]>([]);
  const [stamp, setStamp] = useState<FixedOrderStamp | null>(null);
  const [loading, setLoading] = useState(true);
  const [problem, setProblem] = useState<string | null>(null);
  const [unreachable, setUnreachable] = useState(false);
  const [savingOrder, setSavingOrder] = useState(false);
  /*
    TRUE FROM THE MOMENT A CARD MOVES, WHICH IS BEFORE THE STAMP EXISTS.

    `stamp` is what the ROWS say, so it only becomes non-null once the write has landed — and the
    coalescing window between the nudge and the write is therefore a moment when the list is the
    designer's arrangement and every card would still be printing "(this list is in score order)".
    That is the same contradiction the pool banner was carrying, in miniature. This flag is the
    screen's own answer to "has somebody moved something here", and it is reset only by a fresh load.
  */
  const [arranging, setArranging] = useState(false);
  const [orderNote, setOrderNote] = useState<string | null>(null);
  const [orderProblem, setOrderProblem] = useState<string | null>(null);

  const load = useCallback(async () => {
    const run = ++generation.current;
    setLoading(true);
    setProblem(null);
    setUnreachable(false);

    /*
      THE REGISTRY, THEN THIS DEVICE'S ROWS, THEN THE REPOSITORY'S — in that order, and through the
      one reader the UPLOAD tab uses as well. `readStageRows` hands the disk answer over the moment
      it exists (`onLocal`) so the tab renders from IndexedDB before the network is asked, and folds
      the repository's copy in afterwards through `adoptServerStage`, which refuses to overwrite a
      stage edited locally since its last push. A reorder writes rows back wholesale, so it has to
      be written over rows this browser has actually read.

      `fromServer` is FALSE on the pool surface: its reader is refused this workshop by
      `load_workshop_or_404`, so asking would spend a request to be told 404 on a page that is
      working exactly as designed.
    */
    const spec = await readRegistry();
    if (run !== generation.current) return;
    setRegistry(spec);

    const held = await readStageRows(workshopId, spec, entityKey, {
      fromServer: readsStageRows,
      onLocal: (local, id) => {
        if (run !== generation.current) return;
        draftId.current = id;
        setRows(local);
        setStamp(fixedOrderStamp(local));
      }
    });
    if (run !== generation.current) return;
    setStageKey(held.stageKey);
    draftId.current = held.draftId;
    setRows(held.rows);
    setStamp(fixedOrderStamp(held.rows));

    try {
      const ranking = await fetchRoundRanking({ round, workshopId, entityKey });
      if (run !== generation.current) return;
      setItems(ranking.items);
    } catch (error) {
      if (run !== generation.current) return;
      setItems(null);
      setUnreachable(isUnreachable(error));
      setProblem(
        isUnreachable(error)
          ? UNREACHABLE
          : refusalText(
              error,
              "This round could not be read. If this workshop's pieces have not been declared finished, there is nothing in the wider round yet."
            )
      );
    } finally {
      if (run === generation.current) setLoading(false);
    }
  }, [entityKey, readsStageRows, round, workshopId]);

  useEffect(() => {
    arranged.current = false;
    setArranging(false);
    setOrder([]);
    void load();
  }, [load]);

  /*
    THE ARRANGEMENT IS DERIVED FROM WHAT ARRIVED, EXCEPT WHERE THIS DESIGNER HAS ALREADY MOVED
    SOMETHING. `openingOrder` picks the score order or the placed order depending on the stamp;
    `reconcileOrder` keeps a hand-made arrangement across a refresh, dropping pieces that have gone
    and appending ones that are new. Without the `arranged` guard a background refresh would undo a
    drag the designer had just made and not yet saved.
  */
  useEffect(() => {
    if (!items) {
      setOrder([]);
      return;
    }
    /*
      THE LOCAL ROW ORDER IS PASSED IN, AND ON A FIXED LIST IT BEATS THE SERVER'S ORDINAL. A reorder
      is durable before it is accepted — it is in the draft the moment it is made and goes up on the
      next sync, which can be days later or never — and `placedPosition` is the ordinal AS THE SERVER
      HOLDS IT. Ordering by that in the window between meant a designer who reordered offline and
      reloaded the page saw the list in its old order underneath "this order was settled deliberately
      — fixed by you on <today>": the arrangement looked thrown away and the banner insisted it was
      not. `heldOrder` is only consulted where the rows are actually readable; the pool surface passes
      null and gets the server's ordinal, which is all it has. See `reviewRanking.openingOrder`.
    */
    setOrder((previous) =>
      arranged.current && previous.length
        ? reconcileOrder(previous, items)
        : openingOrder(items, stamp, readsStageRows ? heldOrder(rows) : null)
    );
  }, [items, readsStageRows, rows, stamp]);

  /**
   * The registry's own word for the tentative flag, or null — see this file's header for why it is a
   * WORD here and not an ordering.
   *
   * READ OFF THE REGISTRY so the two clients and the stage form all print the same string, and null
   * wherever it cannot be known: a registry this browser has never downloaded, an entity that
   * declares no such field (`prototype` does not), and the pool round, which holds no rows to read
   * the value from in the first place.
   */
  const tentativeWord = useMemo(() => {
    if (!registry) return null;
    for (const stage of registry.stages) {
      const entity = stage.entities.find((candidate) => candidate.key === entityKey);
      if (entity) return tentativeField(entity)?.label ?? null;
    }
    return null;
  }, [entityKey, registry]);

  const byId = useMemo(() => new Map((items ?? []).map((item) => [item.subjectId, item])), [items]);
  const rowById = useMemo(() => {
    const map = new Map<string, DwRow>();
    for (const row of rows) if (typeof row._entryId === "string") map.set(row._entryId, row);
    return map;
  }, [rows]);

  const canArrange = readsStageRows && items !== null && mayArrange(items) && stageKey !== null;
  const fixedBy = stamp;

  const persist = useCallback(
    async (next: string[], nextStamp: FixedOrderStamp | null) => {
      const target = draftId.current;
      if (!target || !stageKey) {
        setOrderProblem(
          "This arrangement has not been saved: this browser has no local copy of the stage these pieces live in. Open the stage once with a connection, then try again."
        );
        return;
      }
      setSavingOrder(true);
      setOrderProblem(null);
      setOrderNote(null);
      try {
        /*
          TWO PHASES, TWO SENTENCES, AND THEY ARE NOT INTERCHANGEABLE. "It is on this device" and
          "the repository has it" are different facts with different remedies, and this function used
          to report them out of one `try`: any throw from `syncDesignWorkshopDrafts`, which runs
          AFTER `putDraftStage` has already succeeded, printed "this arrangement could not be saved
          on this device" — telling a designer the durable write had failed when it had not. The
          device write is its own block now and owns its own message.
        */
        let rearranged: DwRow[] | null = null;
        try {
          const draft = await loadDraft(target);
          const stage = draft?.stages[stageKey];
          if (!draft || !stage) {
            setOrderProblem(
              "This arrangement has not been saved: the local copy of this stage has gone. Reload the page and try again."
            );
            return;
          }
          const held = stage.collections[entityKey] ?? [];
          /*
            NOTHING IS WRITTEN OVER AN EMPTY COLLECTION WHILE THE SERVER IS SHOWING A FULL ONE.

            `arrangeRows` is total on ids it cannot find, which is right — an unsent row has no
            `_entryId` — but it means an order of eight pieces applied to zero local rows produces
            zero rows and quietly blanks this entity in the draft. That state is reachable: the
            ranking request succeeded while the stage read beside it did not, so the cards on screen
            came from the server and the draft never got its rows. Refusing here costs one sentence;
            the alternative empties the sketch list on this device and needs a reload to notice.
          */
          if (held.length === 0 && next.length > 0) {
            setOrderProblem(
              "This arrangement has not been saved: this browser has not been able to read the stage these pieces live in, so there is nothing here to rearrange. Open that stage once with a connection, then try again."
            );
            return;
          }
          /*
            ── CLEARING THE STAMP IS REFUSED ON A STAGE THIS BROWSER HAS NEVER READ ───────────────

            Because on such a stage it cannot be done, and this panel used to say it had been.
            Returning to the default writes `rankFixedBy: ""` / `rankFixedAt: ""`; `coerce_value`
            reads a blank string as `None` and `validate_entry` then leaves the key out of `cleaned`
            altogether; and every row of a never-read stage is sent with `merge: true`, whose branch
            in `save_stage` is `clean = {**previous, **clean}`. So the repository keeps the stamp it
            already holds, the list stays "settled deliberately" for ever, and the old code reported
            "Back to score order… Saved, and sent to the repository."

            REFUSED BEFORE THE DEVICE WRITE, deliberately: a device showing score order while the
            repository holds a stamp is a worse state than a refusal a designer can read and act on.
            Only rows the repository already knows about are at risk — a row with no `_entryId` has
            no `previous` there for the merge to preserve — so a workshop still working entirely
            offline returns to the default as normal.
          */
          const neverRead = (stage.serverLoadedAt ?? null) === null;
          const knownToServer = held.some((row) => typeof row._entryId === "string" && row._entryId);
          if (nextStamp === null && neverRead && knownToServer) {
            setOrderProblem(
              "The list is still in the designers' order. Returning to score order cannot be sent from here yet: this browser has never read the repository's copy of this stage, so its saves are merges — the repository keeps every field this device leaves blank, and clearing the stamp IS a blank. Open this stage once with a connection, then return to score order."
            );
            return;
          }
          rearranged = arrangeRows(held, next, nextStamp);
          /*
            `custom` IS OMITTED, NOT PASSED THROUGH. Omitting it means "I have nothing to say about the
            designer's own questions", which is what a review tab has to say; passing `{}` would read
            as a designer clearing every custom answer in this stage, and a stage save replaces that
            row wholesale with no revision behind it.
          */
          await putDraftStage(draft.localId, stageKey, {
            singletons: stage.singletons,
            collections: { ...stage.collections, [entityKey]: rearranged },
            removedFrom: stage.removedFrom
          });
        } catch (error) {
          setOrderProblem(
            refusalText(
              error,
              "This arrangement could not be saved on this device. If the browser is in private mode or its storage is full, nothing can be kept here."
            )
          );
          return;
        }
        setRows(rearranged);
        setStamp(nextStamp);
        setOrderNote(
          nextStamp
            ? "Saved on this device. Sending it to the repository…"
            : "Back to score order, saved on this device. Sending it to the repository…"
        );
        try {
          setOrderNote(sendNote(await syncDesignWorkshopDrafts(), nextStamp !== null));
        } catch (error) {
          // The arrangement IS saved on this device — that happened above and is not in doubt here.
          // Only the sending is, so only the sending is what this sentence is about.
          setOrderNote(
            isUnreachable(error)
              ? "Saved on this device. There is no connection, so it sends itself when one returns."
              : "Saved on this device, but sending it did not complete. It goes up with the next sync — the banner above the page follows it."
          );
        }
      } finally {
        setSavingOrder(false);
      }
    },
    [entityKey, stageKey]
  );

  /* ── The coalescing timer, which is the whole of "a nudge is instant" ───────────────────────── */

  const waiting = useRef<{ order: string[]; stamp: FixedOrderStamp | null } | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const flush = useCallback(() => {
    if (timer.current !== null) {
      clearTimeout(timer.current);
      timer.current = null;
    }
    const next = waiting.current;
    waiting.current = null;
    if (next) void persist(next.order, next.stamp);
  }, [persist]);

  /*
    FLUSHED ON UNMOUNT THROUGH A REF, so the cleanup is armed once — on mount, torn down on unmount —
    while still calling the CURRENT `flush`. Depending on `flush` directly would re-arm the cleanup
    every time `persist` was rebuilt, and every one of those teardowns would write.
  */
  const flushRef = useRef(flush);
  useEffect(() => {
    flushRef.current = flush;
  }, [flush]);
  useEffect(() => () => flushRef.current(), []);

  function reorder(next: string[]) {
    arranged.current = true;
    setArranging(true);
    setOrder(next);
    /*
      THE NAME, NOT THE ACCOUNT ID. `rankFixedBy` is TEXT for a checked reason — `User` is not one
      of the five models a REF field can resolve against, and a name is what the report can print
      where a cuid is not. The email is the fallback for an account with no name rather than a
      blank, because "fixed by — on 12 August" is not a sentence.
    */
    const by = (user?.name ?? "").trim() || (user?.email ?? "").trim();
    if (!by) {
      setOrderProblem(
        "This arrangement has not been saved: this session has no name to record against it, and an order fixed by nobody is not a decision anyone can read back."
      );
      return;
    }
    /*
      SCHEDULED, NOT SENT — see the header. The list has already moved; what waits is the write, so
      that ranking eight pieces with the arrows is one stage save and not eight, with nothing
      disabled in between. The note says "about to be saved" rather than "saved", because those are
      two different promises and this short window is the one place they differ.
    */
    waiting.current = { order: next, stamp: { by, at: todayStamp() } };
    setOrderProblem(null);
    setOrderNote("Arranged. Keep going — this is written to the device a moment after you stop.");
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      timer.current = null;
      flush();
    }, QUIET_MS);
  }

  function returnToDefault() {
    if (!items) return;
    // A nudge still waiting is SUPERSEDED rather than raced: it would write the arrangement this
    // click exists to undo, and whichever of the two landed second would be the one on screen.
    if (timer.current !== null) {
      clearTimeout(timer.current);
      timer.current = null;
    }
    waiting.current = null;
    arranged.current = false;
    setArranging(false);
    const next = openingOrder(items, null);
    setOrder(next);
    void persist(next, null);
  }

  const offlineList = unreachable && rows.length > 0;

  return (
    <section className="grid gap-4">
      {/* ── The state of the order, said in words above the list ─────────────────────────── */}
      <div className="panel p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="font-display text-lg font-bold text-ink-900">
              {round === "PEER" ? "Peer review — this workshop" : "The wider pool of designers"}
            </h2>
            <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-muted">
              {round === "PEER"
                ? "The designers on this workshop rate each other's work and settle the order it stands in."
                : "Pieces this workshop has declared finished, open to every designer on the platform. They are listed in score order; rearranging them belongs to the workshop that made them."}
            </p>
          </div>
          <button type="button" className="field-button-secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} aria-hidden />
            Refresh
          </button>
        </div>

        <div className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm">
          {fixedBy ? (
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-ink-700">
                <span className="font-semibold text-ink-900">This order was settled deliberately</span> — fixed by{" "}
                {fixedBy.by} on {formatDate(fixedBy.at)}. A new rating changes the scores on the cards and does not move
                them.
              </p>
              {canArrange ? (
                <button
                  type="button"
                  className="field-button-secondary"
                  onClick={returnToDefault}
                  disabled={savingOrder}
                >
                  <PinOff className="h-4 w-4" aria-hidden />
                  Return to score order
                </button>
              ) : null}
            </div>
          ) : arranging ? (
            /*
              THE WINDOW BETWEEN THE NUDGE AND THE WRITE, said rather than papered over. The stamp is
              read off the rows and the rows have not been written yet, so neither of the two settled
              sentences is true of this screen for the next second or so.
            */
            <p className="text-ink-700">
              <span className="font-semibold text-ink-900">You have arranged this list</span> — it is yours from here,
              and it is being recorded against your name. A new rating will change the scores on the cards and will not
              move them.
            </p>
          ) : canArrange ? (
            /*
              GATED ON `canArrange`, THE SAME CONDITION THE CONTROLS ARE. It read `readsStageRows`,
              which is a weaker test — so a browser with the tab open but no local copy of the stage was
              invited to "move one" by this sentence while the sentence UNDER the list, three inches
              away, said the list could not be moved. An invitation and a refusal about the same list on
              one screen is the screen contradicting itself, and the handset gates its counterpart on
              the control's own condition for exactly this reason.
            */
            <p className="text-ink-700">
              <span className="font-semibold text-ink-900">This is the default order</span> — highest score first, and
              pieces nobody has rated yet at the end. Move one and the arrangement becomes yours, recorded against your
              name.
            </p>
          ) : readsStageRows ? (
            /*
              THE ORDER IS THE SAME AND THE INVITATION IS NOT TRUE. Separated from the pool branch below
              rather than folded into it, because that one ends "whether this workshop's own designers
              have settled an order of their own is not on this response" — which is false here: the
              response carried the ordinals, and what is missing is this browser's copy of the stage.
              One sentence covering both would have to drop the part that is worth saying.
            */
            <p className="text-ink-700">
              <span className="font-semibold text-ink-900">These are in score order</span> — highest first, and pieces
              nobody has rated yet at the end. They cannot be rearranged from here; the sentence under the list says
              why.
            </p>
          ) : (
            /*
              THE POOL LIST IS IN SCORE ORDER AND SAYS SO. This branch used to read "this is the
              workshop's own arrangement" — which was false twice over: `readsStageRows` is false
              here, so there are no rows, so `fixedOrderStamp([])` is null, so `openingOrder` returns
              the SCORE order; and every card underneath simultaneously printed "(this list is in
              score order)". A screen cannot tell a reader which of the two orders they are looking
              at by giving them both answers. Where the workshop's own placement is knowable it is on
              the cards as a number instead — see `ReviewCard.showPlaced`.
            */
            <p className="text-ink-700">
              <span className="font-semibold text-ink-900">These are in score order</span> — highest first, and pieces
              nobody has rated yet at the end. Whether this workshop&apos;s own designers have settled an order of
              their own is not on this response, so this page does not claim either way.
            </p>
          )}
        </div>

        {orderNote ? <p className="mt-2 text-sm text-ink-muted">{orderNote}</p> : null}
        {orderProblem ? (
          <p className="mt-2 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
            {orderProblem}
          </p>
        ) : null}
        {savingOrder ? (
          <p className="mt-2 inline-flex items-center gap-2 text-sm text-ink-muted">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
            Saving the arrangement…
          </p>
        ) : null}
      </div>

      {/* ── What could not be reached, never disguised as an empty list ───────────────────── */}
      {problem ? (
        <p
          className={
            unreachable
              ? "flex items-start gap-2 rounded-md border border-line-200 bg-amber-100 px-3 py-2 text-sm text-amber-800"
              : "flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600"
          }
        >
          {unreachable ? (
            <CloudOff className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          ) : (
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          )}
          <span>{problem}</span>
        </p>
      ) : null}

      {loading && items === null ? <p className="text-sm text-ink-muted">Reading this round…</p> : null}

      {/*
        THE OFFLINE LIST IS THE STAGE ROWS THEMSELVES, in the order this device holds them. It
        carries no scores because there are none on this device, and it says so rather than
        printing a zero — an unrated piece and an unreachable server must not look alike.
      */}
      {offlineList ? (
        <ol className="grid gap-2">
          {rows.map((row, index) => (
            <li
              key={row._entryId ?? row._clientKey ?? index}
              className="panel flex items-center gap-3 px-4 py-3 text-sm"
            >
              <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-field-200 text-xs font-semibold text-ink-700">
                {index + 1}
              </span>
              <span className="min-w-0">
                <span className="flex flex-wrap items-center gap-2">
                  <span className="min-w-0 truncate font-medium text-ink-900">
                    {typeof row.name === "string" && row.name ? row.name : "Untitled piece"}
                  </span>
                  {/* The same word the online cards carry, so the tab does not gain and lose a fact
                      about a sketch with the signal. It does not reorder this list either — see the
                      header's fourth reason. */}
                  {tentativeWord && isTentativeRow(row) ? (
                    <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                      {tentativeWord}
                    </span>
                  ) : null}
                </span>
                <span className="block text-xs text-ink-muted">
                  {rowSubtitle(row) || "On this device — its score and its reviews are on the server."}
                </span>
              </span>
            </li>
          ))}
        </ol>
      ) : null}

      {/*
        AN EMPTY ROUND NAMES THE FIELD THAT WOULD FILL IT.

        The pool sentence said only that nothing had been declared finished, which leaves a designer
        with a true statement and nowhere to go: "declared finished" is not the name of anything they
        can see on a form. The field is real and the label is the registry's own —
        `stage_definitions.py` declares `f("peerRoundClosedAt", "Peer review closed on", DATE, …)`,
        cited at `app/(protected)/design-review/page.tsx:26` — so it is named here, which is the half
        of the handset's sentence this page can honestly carry.

        AND NOT THE COUNT, WHICH IS THE OTHER HALF. The handset says how many of the pieces it holds
        carry the date; this surface deliberately holds no rows (`readsStageRows` is false on the pool
        page, and its reader is refused this workshop by `load_workshop_or_404`), so a count here would
        be a number invented to match the shape of the other client's sentence. Saying why there is no
        count costs one clause and is the difference between "cannot" and "did not bother".
      */}
      {items !== null && items.length === 0 ? (
        <p className="panel px-4 py-6 text-center text-sm text-ink-muted">
          {round === "PEER"
            ? "There is nothing to review in this workshop yet. Pieces appear here as they are added to the stage they belong to."
            : "Nothing in this workshop has been declared finished, so nothing is open to the wider pool yet. A piece is opened by its “Peer review closed on” date, which is set on the piece’s own stage form, one piece at a time. This page holds no copy of those pieces, so it cannot say how many are waiting."}
        </p>
      ) : null}

      {items !== null && items.length > 0 ? (
        <RankableList
          order={order}
          labelFor={(id) => byId.get(id)?.label || "Untitled piece"}
          onReorder={reorder}
          /*
            A SAVE IN FLIGHT NO LONGER DISABLES THE LIST. It used to, and the cost was the feature:
            every arrow press was a full stage push with both arrows and the handle disabled on EVERY
            row until it returned, so ranking eight prototypes on a one-bar connection was eight
            serialised pushes with the list dead between each one, and two moves in a row were
            impossible. The writes are coalesced instead (see `reorder`), and the draft store — not
            the disabled state — is what makes an unsent arrangement durable. "Return to score order"
            still waits, because that one is a single deliberate act and racing it with a queued
            nudge is a genuine conflict.
          */
          /*
            THREE REASONS, ASKED IN THE ORDER THE FACTS OVERRIDE EACH OTHER — the round, then the
            permission, then what this browser holds. It used to be two, and the second of them blamed
            "no local copy of the stage" for every way `canArrange` could be false, including the two
            that have nothing to do with this browser. The handset's counterpart carries the same three
            in the same order, and its own comment records that the ordering is a fix for a shipped
            defect: a reader who was refused the collection was told the DEVICE was at fault.

            The third names a remedy, because it is the only one of the three a designer can act on.
          */
          disabledReason={
            canArrange
              ? null
              : !readsStageRows
                ? "The order here is the score order, and it is not yours to rearrange: the placed order is the makers' own stage row order, which only that workshop's designers and an admin can change. Your rating is what you contribute to the ranking on this page."
                : items !== null && !mayArrange(items)
                  ? "This list is not yours to rearrange: the placed order is the makers' own stage row order, and the repository sends it as a position only to that workshop's own designers and to an admin. Your rating is what you contribute to the ranking on this page."
                  : "This arrangement cannot be changed from here: this browser has no local copy of the stage these pieces live in. Open this workshop's stage once with a connection and the arrows and the drag handle come back."
          }
          renderItem={(id) => {
            const item = byId.get(id);
            if (!item) return null;
            return (
              <ReviewCard
                item={item}
                subtitle={rowSubtitle(rowById.get(id))}
                /*
                  NULL WHEREVER IT CANNOT BE KNOWN, never `false`. On the pool round there are no
                  rows, so this is not "the maker has settled on it" — it is "this surface cannot
                  say", and drawing the absence as a settled piece would be an answer invented to
                  fill a slot. The card omits the chip either way; the difference is that nothing
                  here claims the second thing.
                */
                tentative={tentativeWord && isTentativeRow(rowById.get(id)) ? tentativeWord : null}
                round={round}
                /*
                  THE SERVER'S DISCLOSURE, NOT THIS SURFACE'S IDENTITY. It read `readsStageRows`, which
                  is false on /design-review — so a workshop member or an admin reading their own
                  workshop's pool round was sent the raw ordinal and the card threw it away. The gap
                  between the two orders IS the feature (see this card's own note beside the two
                  numbers), and half of it was being deleted on one of the two surfaces.

                  `mayArrange` is the same test the arrows are gated on and means the same thing here:
                  `RankedItem.ordinal` is sent only to callers who already see the whole collection, so
                  its presence is the honest answer to "is `placedPosition` a position in the whole
                  collection" as well as to "may I write a new one back".
                */
                showPlaced={items !== null && mayArrange(items)}
                fixedOrder={fixedBy !== null || arranging}
                openHref={
                  readsStageRows && stageKey ? `/design-workshops/${workshopId}/stages/${stageKey}` : null
                }
                onRated={(rating: DesignRating) => {
                  /*
                    PATCHED IN PLACE RATHER THAN RE-FETCHED, and the list is deliberately NOT
                    re-sorted. The average this designer just changed would otherwise move the card
                    out from under their cursor the instant they pressed the button — which is the
                    score re-sorting a list while somebody is working through it, the exact
                    behaviour the override rule exists to prevent. The next Refresh brings the new
                    averages in with the arrangement rules applied once, in one place.
                  */
                  setItems((current) =>
                    (current ?? []).map((entry) =>
                      entry.subjectId === id ? { ...entry, myRating: rating } : entry
                    )
                  );
                }}
              />
            );
          }}
        />
      ) : null}

      {registry === null ? (
        <p className="text-xs text-ink-muted">
          This browser holds no field registry yet, so the stage these pieces live in could not be named. Open the
          workshop once with a connection.{" "}
          <Link href={`/design-workshops/${workshopId}`} className="underline">
            Open the workshop
          </Link>
        </p>
      ) : null}
    </section>
  );
}
