"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { ApiError, apiFetch } from "@/lib/api";
import type { InsightQuote, TranscriptInsightView } from "@/lib/types";

export const BATCH_MAX = 50;
export const POLL_MS = 15_000;
export const POLL_LIMIT_MS = 10 * 60_000;
export const POLL_SLOW_MS = 60_000;
export const SUMMARY_MAX_LINES = 5;
export const SUMMARY_LINE_MAX = 400;
export const QUOTES_MAX = 8;

export type InsightPatch = {
  version: number;
  summaryLines?: string[];
  quotes?: InsightQuote[];
  translationText?: string;
};

export function fetchInsight(mediaId: string): Promise<TranscriptInsightView> {
  return apiFetch<TranscriptInsightView>(`/media/${encodeURIComponent(mediaId)}/insights`);
}

export function patchInsight(mediaId: string, body: InsightPatch): Promise<TranscriptInsightView> {
  return apiFetch<TranscriptInsightView>(`/media/${encodeURIComponent(mediaId)}/insights`, {
    method: "PATCH",
    body: JSON.stringify(body)
  });
}

export function regenerateInsight(mediaId: string, discardEdits: boolean): Promise<TranscriptInsightView> {
  return apiFetch<TranscriptInsightView>(`/media/${encodeURIComponent(mediaId)}/insights/regenerate`, {
    method: "POST",
    body: JSON.stringify({ discardEdits })
  });
}

export function fetchInsightBatch(ids: string[]): Promise<{ items: Record<string, TranscriptInsightView> }> {
  return apiFetch(`/media/insights?ids=${ids.map(encodeURIComponent).join(",")}`);
}

export type BatchAnswer = TranscriptInsightView | null | "error";

type Waiter = (answer: BatchAnswer) => void;

export function createInsightBatcher(
  fetchBatch: (ids: string[]) => Promise<{ items: Record<string, TranscriptInsightView> }>,
  schedule: (run: () => void) => void = (run) => queueMicrotask(run)
) {
  let pending = new Map<string, Waiter[]>();
  let scheduled = false;

  async function flush() {
    scheduled = false;
    const batch = pending;
    pending = new Map();
    const ids = [...batch.keys()];
    for (let start = 0; start < ids.length; start += BATCH_MAX) {
      const slice = ids.slice(start, start + BATCH_MAX);
      let answers: Record<string, TranscriptInsightView> | null = null;
      let hidden = false;
      try {
        answers = (await fetchBatch(slice)).items ?? {};
      } catch (error) {
        hidden = error instanceof ApiError && error.status === 404;
      }
      for (const id of slice) {
        const answer: BatchAnswer = answers ? (answers[id] ?? null) : hidden ? null : "error";
        for (const waiter of batch.get(id) ?? []) waiter(answer);
      }
    }
  }

  return function load(id: string): Promise<BatchAnswer> {
    return new Promise((resolve) => {
      const waiters = pending.get(id) ?? [];
      waiters.push(resolve);
      pending.set(id, waiters);
      if (!scheduled) {
        scheduled = true;
        schedule(() => void flush());
      }
    });
  };
}

const sharedLoad = createInsightBatcher(fetchInsightBatch);

export function isWorking(view: TranscriptInsightView | null | undefined): boolean {
  return view?.state === "QUEUED" || view?.state === "PROCESSING";
}

export function pollDelay(elapsedMs: number): number {
  return elapsedMs > POLL_LIMIT_MS ? POLL_SLOW_MS : POLL_MS;
}

export function useTranscriptInsight(mediaId: string | null | undefined, { enabled = true } = {}) {
  const [view, setView] = useState<TranscriptInsightView | null | undefined>(undefined);
  const started = useRef<number>(0);

  useEffect(() => {
    if (!mediaId || !enabled) return;
    let cancelled = false;
    started.current = Date.now();
    void sharedLoad(mediaId).then((answer) => {
      if (cancelled) return;
      setView(answer === "error" ? null : answer);
    });
    return () => {
      cancelled = true;
    };
  }, [mediaId, enabled]);

  const working = isWorking(view);
  useEffect(() => {
    if (!mediaId || !working) return;
    let cancelled = false;
    let timer = 0;
    const next = () => {
      timer = window.setTimeout(() => {
        void sharedLoad(mediaId).then((answer) => {
          if (cancelled) return;
          if (answer !== "error") setView(answer);
          next();
        });
      }, pollDelay(Date.now() - started.current));
    };
    next();
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [mediaId, working]);

  const replace = useCallback((next: TranscriptInsightView) => {
    started.current = Date.now();
    setView(next);
  }, []);

  return { view, replace };
}

export function cleanSummaryLines(lines: string[]): string[] {
  return lines
    .map((line) => line.trim().slice(0, SUMMARY_LINE_MAX))
    .filter((line) => line.length > 0)
    .slice(0, SUMMARY_MAX_LINES);
}

export function cleanQuotes(quotes: InsightQuote[]): InsightQuote[] {
  return quotes
    .map((q) => ({ original: q.original.trim(), english: q.english.trim(), speaker: q.speaker?.trim() || null }))
    .filter((q) => q.original.length > 0)
    .slice(0, QUOTES_MAX);
}

export function editRows(lines: string[]): string[] {
  const rows = lines.slice(0, SUMMARY_MAX_LINES);
  while (rows.length < SUMMARY_MAX_LINES) rows.push("");
  return rows;
}

export function needsRegenerateConfirm(view: TranscriptInsightView | null | undefined): boolean {
  return Boolean(view?.insight?.edited);
}

export function modelLabel(view: TranscriptInsightView): string {
  const provenance = view.insight?.provenance;
  if (!provenance) return "";
  return provenance.modelId === "UNRECORDED" ? "model not recorded" : provenance.modelId;
}
