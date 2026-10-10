"use client";

/**
 * A WORKSHOP'S FILES AND ITS OWN QUESTIONS, DRAWN READ-ONLY — for its inspectors and its monitors.
 *
 * Shared by `/design-workshop-inspections/[id]` and `/officers/monitored/[id]` (sweep item F5). The
 * pure halves — which file a field points at, how a file is drawn, which custom answer belongs to
 * which question — live in `lib/workshopReaderMedia.ts`; this file only draws them.
 *
 * NOTHING HERE WRITES. There is no upload, replace, caption, transcribe or delete control, by
 * construction: these components receive files and answers and render them. The links they draw are
 * short-lived signed links handed out by the reader's own surface, so a link copied out of the page
 * stops working within minutes; "Refresh files" asks for fresh ones.
 */

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { FileText, ImageOff, RefreshCw } from "lucide-react";

import { FieldProvenance } from "@/components/designworkshop/FieldProvenance";
import type { DwCustomDefinition } from "@/lib/customSections";
import type { DwEntryData, DwFieldStamp, DwValue } from "@/lib/designWorkshops";
import { isUnreachable } from "@/lib/offline";
import {
  customAnswersForStage,
  getReaderMedia,
  mediaIndex,
  readableSize,
  readerMediaKind,
  resolveFieldMedia,
  type ReaderMediaFile,
  type ReaderMediaList,
  type ReaderSurface
} from "@/lib/workshopReaderMedia";

export type ReaderMediaState = "loading" | "ready" | "failed";

type ReaderMediaContextValue = {
  byId: Map<string, ReaderMediaFile>;
  state: ReaderMediaState;
};

const ReaderMediaContext = createContext<ReaderMediaContextValue>({ byId: new Map(), state: "loading" });

/** Load one workshop's files for a read-only surface. `enabled` false holds the request. */
export function useReaderMedia(surface: ReaderSurface, workshopId: string, enabled: boolean) {
  const [list, setList] = useState<ReaderMediaList | null>(null);
  const [state, setState] = useState<ReaderMediaState>("loading");
  const [failure, setFailure] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    setState("loading");
    getReaderMedia(surface, workshopId)
      .then((result) => {
        if (cancelled) return;
        setList(result);
        setState("ready");
        setFailure(null);
      })
      .catch((err) => {
        if (cancelled) return;
        setState("failed");
        setFailure(
          isUnreachable(err)
            ? "The files could not be loaded — check the connection and try again. The stages below are unaffected."
            : "The files could not be loaded. Try again; the stages below are unaffected."
        );
      });
    return () => {
      cancelled = true;
    };
  }, [surface, workshopId, enabled, attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  return { list, state, failure, retry };
}

/** Puts one workshop's files where every media field below can find them. */
export function ReaderMediaProvider({
  list,
  state,
  children
}: {
  list: ReaderMediaList | null;
  state: ReaderMediaState;
  children: ReactNode;
}) {
  const value = useMemo(() => ({ byId: mediaIndex(list), state }), [list, state]);
  return <ReaderMediaContext.Provider value={value}>{children}</ReaderMediaContext.Provider>;
}

/** One file, drawn the way its type is read: a picture, a player, or a link to open it. */
export function ReaderMediaTile({ file }: { file: ReaderMediaFile }) {
  const kind = readerMediaKind(file);
  const name = file.originalFilename?.trim() || "Untitled file";
  const caption = file.caption?.trim();

  if (!file.url) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500">
        <ImageOff className="h-4 w-4 shrink-0" aria-hidden />
        <span>
          {name} is recorded here but cannot be opened right now. Try Refresh files.
        </span>
      </div>
    );
  }

  return (
    <figure className="grid gap-1">
      {kind === "image" ? (
        <a href={file.url} target="_blank" rel="noopener noreferrer" className="block w-fit">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={file.url}
            alt={caption || name}
            loading="lazy"
            className="h-32 w-32 rounded-md border border-line-200 bg-surface-50 object-cover"
          />
        </a>
      ) : kind === "audio" ? (
        <audio controls preload="none" src={file.url} className="w-full max-w-sm">
          <a href={file.url}>{name}</a>
        </audio>
      ) : kind === "video" ? (
        <video controls preload="metadata" src={file.url} className="max-h-64 w-full max-w-md rounded-md">
          <a href={file.url}>{name}</a>
        </video>
      ) : (
        <a
          href={file.url}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex w-fit items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-900 hover:bg-surface-100"
        >
          <FileText className="h-4 w-4 shrink-0" aria-hidden />
          Open {name}
        </a>
      )}
      <figcaption className="max-w-sm truncate text-xs text-ink-500">
        {caption ? `${caption} · ` : ""}
        {kind === "image" ? name : readableSize(file.sizeBytes) || name}
      </figcaption>
    </figure>
  );
}

/** A media field's files, read-only, in the order the designer filed them. */
export function ReaderMediaValue({ value }: { value: DwValue | undefined }) {
  const { byId, state } = useContext(ReaderMediaContext);
  const { shown, elsewhere } = resolveFieldMedia(value, byId);
  const total = shown.length + elsewhere;

  if (state === "loading") {
    return (
      <span className="text-sm text-ink-500">
        {total} file{total === 1 ? "" : "s"} recorded here. Loading…
      </span>
    );
  }
  if (state === "failed") {
    return (
      <span className="text-sm text-ink-500">
        {total} file{total === 1 ? "" : "s"} recorded here. They could not be loaded — see Files above.
      </span>
    );
  }
  return (
    <div className="grid gap-2">
      {shown.length ? (
        <div className="flex flex-wrap gap-3">
          {shown.map((file) => (
            <ReaderMediaTile file={file} key={file.id} />
          ))}
        </div>
      ) : null}
      {elsewhere ? (
        <span className="text-xs leading-5 text-ink-500">
          {elsewhere} more file{elsewhere === 1 ? " is" : "s are"} kept with another record and{" "}
          {elsewhere === 1 ? "is" : "are"} not shown here.
        </span>
      ) : null}
    </div>
  );
}

/** Every file of the workshop in one place, with a way to fetch fresh links. */
export function ReaderFilesPanel({
  list,
  state,
  failure,
  onRetry
}: {
  list: ReaderMediaList | null;
  state: ReaderMediaState;
  failure: string | null;
  onRetry: () => void;
}) {
  const files = list?.items ?? [];
  return (
    <section className="panel mb-4 grid gap-3 p-4" aria-live="polite">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-display text-base font-bold tracking-tight text-ink-900">
          Files{state === "ready" ? ` · ${files.length}${list?.truncated ? "+" : ""}` : ""}
        </h2>
        <button type="button" className="field-button-secondary inline-flex items-center gap-1" onClick={onRetry}>
          <RefreshCw className="h-3.5 w-3.5" aria-hidden />
          Refresh files
        </button>
      </div>
      <p className="text-xs leading-5 text-ink-500">
        The photographs, recordings and attachments filed with this workshop, to look at and listen to. Nothing here
        can be changed, and the links stop working a few minutes after the page loads — Refresh files renews them.
      </p>
      {state === "loading" ? <p className="text-sm text-ink-700">Loading the files…</p> : null}
      {state === "failed" ? <p className="text-sm text-red-700">{failure}</p> : null}
      {state === "ready" && files.length === 0 ? (
        <p className="text-sm text-ink-700">No files have been filed with this workshop.</p>
      ) : null}
      {state === "ready" && files.length > 0 ? (
        <div className="flex flex-wrap gap-4">
          {files.map((file) => (
            <ReaderMediaTile file={file} key={file.id} />
          ))}
        </div>
      ) : null}
      {list?.truncated ? (
        <p className="text-xs text-ink-500">Showing the first {files.length} files. Each stage below shows its own.</p>
      ) : null}
    </section>
  );
}

/** The answers to the workshop's own questions on one stage, with their wording and their authors. */
export function ReaderCustomAnswers({
  definition,
  stageKey,
  values,
  stamps
}: {
  definition: DwCustomDefinition | null | undefined;
  stageKey: string;
  values: DwEntryData | undefined;
  stamps?: Record<string, DwFieldStamp>;
}) {
  const { blocks, unmatched } = customAnswersForStage(definition, stageKey, values, stamps);
  if (!blocks.length && !unmatched) return null;
  return (
    <div className="grid gap-3">
      {blocks.map((block) => (
        <div key={block.title}>
          <h3 className="mb-1 text-sm font-medium text-ink-700">
            {block.title}
            <span className="ml-2 text-xs font-normal text-ink-500">
              {block.retired ? "questions this workshop added · no longer asked" : "questions this workshop added"}
            </span>
          </h3>
          <div className="grid divide-y divide-line-200">
            {block.answers.map((answer) => (
              <div className="grid gap-0.5 py-2" key={answer.field.key}>
                <span className="field-label">
                  {answer.field.label}
                  {answer.field.retired ? " (no longer asked)" : ""}
                </span>
                <span className="whitespace-pre-wrap text-sm leading-6 text-ink-900">
                  {answer.text}
                  {answer.field.unit ? ` ${answer.field.unit}` : ""}
                </span>
                <FieldProvenance stamp={answer.stamp} />
              </div>
            ))}
          </div>
        </div>
      ))}
      {unmatched ? (
        <p className="text-xs leading-5 text-ink-500">
          {unmatched} more answer{unmatched === 1 ? " was" : "s were"} given to a question this workshop has since
          removed.
        </p>
      ) : null}
    </div>
  );
}
