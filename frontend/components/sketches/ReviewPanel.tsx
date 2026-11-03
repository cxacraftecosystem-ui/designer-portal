"use client";

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
  "Couldn't connect, so the scores and reviews couldn't be loaded. This isn't an empty list. Check your connection and try again.";

const QUIET_MS = 1200;

function sendNote(result: DwSyncResult, fixed: boolean): string {
  return syncPassNote(result, fixed ? "this arrangement is" : "the return to score order is");
}

type Props = {
  workshopId: string;
  round: RatingRound;
  readsStageRows: boolean;
  entityKey: RateableEntityKey;
  readOnlyReason?: string | null;
};

export function ReviewPanel({ workshopId, round, readsStageRows, entityKey, readOnlyReason = null }: Props) {
  const { user } = useAuth();
  const generation = useRef(0);
  const draftId = useRef<string | null>(null);
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
  const [arranging, setArranging] = useState(false);
  const [orderNote, setOrderNote] = useState<string | null>(null);
  const [orderProblem, setOrderProblem] = useState<string | null>(null);

  const load = useCallback(async () => {
    const run = ++generation.current;
    setLoading(true);
    setProblem(null);
    setUnreachable(false);

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
              "Couldn't load this round."
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

  useEffect(() => {
    if (!items) {
      setOrder([]);
      return;
    }
    setOrder((previous) =>
      arranged.current && previous.length
        ? reconcileOrder(previous, items)
        : openingOrder(items, stamp, readsStageRows ? heldOrder(rows) : null)
    );
  }, [items, readsStageRows, rows, stamp]);

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

  const canArrange =
    readsStageRows && items !== null && mayArrange(items) && stageKey !== null && !readOnlyReason;
  const fixedBy = stamp;

  const persist = useCallback(
    async (next: string[], nextStamp: FixedOrderStamp | null) => {
      const target = draftId.current;
      if (!target || !stageKey) {
        setOrderProblem(
          "Not saved — open this stage once online first."
        );
        return;
      }
      setSavingOrder(true);
      setOrderProblem(null);
      setOrderNote(null);
      try {
        let rearranged: DwRow[] | null = null;
        try {
          const draft = await loadDraft(target);
          const stage = draft?.stages[stageKey];
          if (!draft || !stage) {
            setOrderProblem(
              "Not saved — reload and try again."
            );
            return;
          }
          const held = stage.collections[entityKey] ?? [];
          if (held.length === 0 && next.length > 0) {
            setOrderProblem(
              "Not saved — open this stage once online first."
            );
            return;
          }
          const neverRead = (stage.serverLoadedAt ?? null) === null;
          const knownToServer = held.some((row) => typeof row._entryId === "string" && row._entryId);
          if (nextStamp === null && neverRead && knownToServer) {
            setOrderProblem(
              "Still in the designers' order. Open this stage once online first."
            );
            return;
          }
          rearranged = arrangeRows(held, next, nextStamp);
          await putDraftStage(draft.localId, stageKey, {
            singletons: stage.singletons,
            collections: { ...stage.collections, [entityKey]: rearranged },
            removedFrom: stage.removedFrom
          });
        } catch (error) {
          setOrderProblem(
            refusalText(
              error,
              "Couldn't save to this device. Leave private mode or free some space."
            )
          );
          return;
        }
        setRows(rearranged);
        setStamp(nextStamp);
        setOrderNote(
          nextStamp
            ? "Saved on this device. Uploading…"
            : "Back to score order, saved on this device. Uploading…"
        );
        try {
          setOrderNote(sendNote(await syncDesignWorkshopDrafts(), nextStamp !== null));
        } catch (error) {
          setOrderNote(
            isUnreachable(error)
              ? "Saved on this device. It uploads when you're online."
              : "Saved on this device. It uploads with the next sync."
          );
        }
      } finally {
        setSavingOrder(false);
      }
    },
    [entityKey, stageKey]
  );

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

  const flushRef = useRef(flush);
  useEffect(() => {
    flushRef.current = flush;
  }, [flush]);
  useEffect(() => () => flushRef.current(), []);

  function reorder(next: string[]) {
    arranged.current = true;
    setArranging(true);
    setOrder(next);
    const by = (user?.name ?? "").trim() || (user?.email ?? "").trim();
    if (!by) {
      setOrderProblem(
        "Not saved — sign in again."
      );
      return;
    }
    waiting.current = { order: next, stamp: { by, at: todayStamp() } };
    setOrderProblem(null);
    setOrderNote("Saves when you stop.");
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      timer.current = null;
      flush();
    }, QUIET_MS);
  }

  function returnToDefault() {
    if (!items) return;
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
      <div className="panel p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="font-display text-lg font-bold text-ink-900">
              {round === "PEER" ? "Peer review — this workshop" : "The wider pool of designers"}
            </h2>
            <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-muted">
              {round === "PEER"
                ? "Designers rate each other's work and set the order."
                : "Finished pieces, open to every designer. Listed by score."}
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
                <span className="font-semibold text-ink-900">This order was set by hand</span> — fixed by{" "}
                {fixedBy.by} on {formatDate(fixedBy.at)}. A new rating changes the scores on the cards but does not move
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
            <p className="text-ink-700">
              <span className="font-semibold text-ink-900">Your order</span> — new ratings won&apos;t move cards.
            </p>
          ) : canArrange ? (
            <p className="text-ink-700">
              <span className="font-semibold text-ink-900">By score</span> — unrated last. Move one to set your own order.
            </p>
          ) : readsStageRows ? (
            <p className="text-ink-700">
              <span className="font-semibold text-ink-900">These are in score order</span> — highest first, unrated last.
            </p>
          ) : (
            <p className="text-ink-700">
              <span className="font-semibold text-ink-900">These are in score order</span> — highest first, unrated last.
            </p>
          )}
        </div>

        {orderNote ? <p className="mt-2 text-sm text-ink-muted">{orderNote}</p> : null}
        {orderProblem ? (
          <p className="mt-2 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">
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

      {problem ? (
        <p
          className={
            unreachable
              ? "flex items-start gap-2 rounded-md border border-line-200 bg-amber-100 px-3 py-2 text-sm text-amber-800"
              : "flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800"
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
                  {tentativeWord && isTentativeRow(row) ? (
                    <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                      {tentativeWord}
                    </span>
                  ) : null}
                </span>
                <span className="block text-xs text-ink-muted">
                  {rowSubtitle(row) || "On this device — scores show when online."}
                </span>
              </span>
            </li>
          ))}
        </ol>
      ) : null}

      {items !== null && items.length === 0 ? (
        <p className="panel px-4 py-6 text-center text-sm text-ink-muted">
          {round === "PEER"
            ? "Nothing to review yet."
            : "Nothing finished yet. Set “Peer review closed on” on a piece to open it."}
        </p>
      ) : null}

      {items !== null && items.length > 0 ? (
        <RankableList
          order={order}
          labelFor={(id) => byId.get(id)?.label || "Untitled piece"}
          onReorder={reorder}
          disabledReason={
            canArrange
              ? null
              : !readsStageRows
                ? "Only that workshop's designers can reorder. You can rate."
                : readOnlyReason
                  ?
                    readOnlyReason
                  : items !== null && !mayArrange(items)
                    ? "Only this workshop's designers can reorder. You can rate."
                    : "Open this stage once online to reorder."
          }
          renderItem={(id) => {
            const item = byId.get(id);
            if (!item) return null;
            return (
              <ReviewCard
                item={item}
                subtitle={rowSubtitle(rowById.get(id))}
                tentative={tentativeWord && isTentativeRow(rowById.get(id)) ? tentativeWord : null}
                round={round}
                showPlaced={items !== null && mayArrange(items)}
                fixedOrder={fixedBy !== null || arranging}
                openHref={
                  readsStageRows && stageKey ? `/design-workshops/${workshopId}/stages/${stageKey}` : null
                }
                onRated={(rating: DesignRating) => {
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
          Couldn&apos;t load the form. Open the workshop once online.{" "}
          <Link href={`/design-workshops/${workshopId}`} className="underline">
            Open the workshop
          </Link>
        </p>
      ) : null}
    </section>
  );
}
