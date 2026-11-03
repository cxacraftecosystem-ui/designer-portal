"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { RefreshCw } from "lucide-react";

import { useReportMediaUrls } from "@/components/designworkshop/report/ReportBlock";
import { ReportSheets } from "@/components/designworkshop/report/ReportSheet";
import { PreviewDisclosureButton } from "@/components/designworkshop/report/StagePreviewLayout";
import { buildLiveDocument, stageMediaIds, type LiveStage } from "@/components/designworkshop/report/liveDocument";
import type { PreviewBlock } from "@/components/designworkshop/report/previewModel";
import {
  sliceStageBlocks,
  spliceStageBlocks,
  stableBlocks,
  stageBlockRange
} from "@/components/designworkshop/report/stageSlice";
import { fetchMediaRow } from "@/lib/mediaRows";
import {
  fetchReportSources,
  previewDesignWorkshopReport,
  type DwPreview,
  type DwRegistry
} from "@/lib/designWorkshops";
import {
  draftSessionUserId,
  isLocalMediaRef,
  loadDraft,
  loadRegistry,
  readLocalMedia,
  type DwDraft
} from "@/lib/designWorkshopStore";
import type { ReportSources } from "@/lib/offlineReport/assemble";
import type { Registry } from "@/lib/offlineReport/builder";
import type { ImageRef } from "@/lib/offlineReport/model";
import { getSources, putSources } from "@/lib/offlineReport/reportCache";
import { paletteFromAccent } from "@/lib/reportTheme";
import { readableError } from "@/components/review/reviewErrors";
import { isUnreachable } from "@/lib/failureTriage";
import type { MediaFile } from "@/lib/types";

export { sliceStageBlocks };
export { StagePreviewLayout, useStagePreviewOpen } from "@/components/designworkshop/report/StagePreviewLayout";

const EMPTY_BLOCKS: PreviewBlock[] = [];

export function useStableBlocks(next: PreviewBlock[]): PreviewBlock[] {
  const [held, setHeld] = useState(next);
  const stable = useMemo(() => stableBlocks(held, next), [held, next]);
  if (stable !== held) setHeld(stable);
  return stable;
}

export const LIVE_DEBOUNCE_MS = 250;

export const PREVIEW_DEADLINE_MS = 20_000;

export class PreviewDeadlineError extends Error {
  constructor() {
    super("The preview took too long to build.");
    this.name = "PreviewDeadlineError";
  }
}

export function withDeadline<T>(work: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new PreviewDeadlineError()), ms);
    work.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      }
    );
  });
}

type Base = { blocks: PreviewBlock[]; warnings: string[]; meta: DwPreview["meta"]; fromServer: boolean };

type Shown = {
  blocks: PreviewBlock[];
  warnings: string[];
  pageSize: string;
  marginMm: number | null;
  headerText: string;
  footerText: string;
  accent: string | null;
  live: boolean;
};

function useProbedImageRefs(ids: string[], known: Record<string, unknown> | undefined): Record<string, ImageRef> {
  const [refs, setRefs] = useState<Record<string, ImageRef>>({});
  const asked = useRef(new Set<string>());
  const key = ids.join(",");
  useEffect(() => {
    let cancelled = false;
    const missing = ids.filter((id) => !(known && id in known) && !asked.current.has(id));
    if (!missing.length) return;
    missing.forEach((id) => asked.current.add(id));
    void (async () => {
      const found: Record<string, ImageRef> = {};
      for (const id of missing) {
        try {
          let blob: Blob | null = null;
          let mime = "image/jpeg";
          if (isLocalMediaRef(id)) {
            const local = await readLocalMedia(id);
            blob = local?.blob ?? null;
            mime = local?.mimeType ?? mime;
          } else {
            const file = await fetchMediaRow(id);
            if (file.url) {
              const response = await fetch(file.url);
              if (response.ok) blob = await response.blob();
              mime = file.mimeType ?? mime;
            }
          }
          if (!blob) continue;
          const bitmap = await createImageBitmap(blob, { imageOrientation: "from-image" });
          found[id] = { source: id, width_px: bitmap.width, height_px: bitmap.height, rotation_deg: 0, mime_type: mime };
          bitmap.close();
        } catch {
        }
      }
      if (!cancelled && Object.keys(found).length) setRefs((current) => ({ ...current, ...found }));
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, known]);
  return refs;
}

export function StageDocumentPreview({
  workshopId,
  draftId,
  stageKey,
  stageTitle,
  refreshToken,
  localOnly,
  live,
  open,
  onOpenChange
}: {
  workshopId: string;
  draftId?: string;
  stageKey?: string;
  stageTitle: string;
  refreshToken: number;
  localOnly: boolean | null;
  live?: LiveStage | null;
  open?: boolean;
  onOpenChange?: (next: boolean) => void;
}) {
  const [ownOpen, setOwnOpen] = useState(false);
  const isOpen = open ?? ownOpen;
  const pane = open !== undefined && isOpen;
  const setOpen = onOpenChange ?? setOwnOpen;

  const [base, setBase] = useState<Base | null>(null);
  const [baseError, setBaseError] = useState<{ message: string; offline: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const [showAll, setShowAll] = useState(false);

  const [draft, setDraft] = useState<DwDraft | null>(null);
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [sources, setSources] = useState<ReportSources | null>(null);
  const [liveDoc, setLiveDoc] = useState<Shown | null>(null);
  const generation = useRef(0);
  const drawn = useRef<number | null>(null);

  const load = useCallback(
    async (token: number) => {
      const mine = generation.current + 1;
      generation.current = mine;
      setBusy(true);
      const userId = draftSessionUserId();
      const offline = typeof navigator !== "undefined" && navigator.onLine === false;
      const fromServer = localOnly === false && !offline;
      const run = async () => {
        const [nextDraft, nextRegistry] = await Promise.all([
          draftId ? loadDraft(draftId).catch(() => null) : Promise.resolve(null),
          loadRegistry()
            .then((r) => r.registry)
            .catch(() => null)
        ]);
        let kept: ReportSources | null = null;
        if (localOnly === false) {
          if (fromServer) {
            kept = await fetchReportSources(workshopId).catch(() => null);
            if (kept && userId) void putSources(workshopId, userId, kept).catch(() => undefined);
          }
          if (!kept) kept = (await getSources(workshopId, userId).catch(() => null))?.sources ?? null;
        }
        if (generation.current !== mine) return;
        setDraft(nextDraft);
        setRegistry(nextRegistry);
        setSources(kept);

        let payload: DwPreview | null = null;
        let error: unknown = null;
        if (fromServer) {
          try {
            payload = await previewDesignWorkshopReport(workshopId);
          } catch (caught) {
            error = caught;
          }
        }
        if (generation.current !== mine) return;
        if (payload) {
          setBase({ blocks: payload.blocks as PreviewBlock[], warnings: payload.warnings, meta: payload.meta, fromServer: true });
          setBaseError(null);
        } else if (error && !isUnreachable(error) && !nextDraft) {
          setBaseError({ message: readableError(error, "The document could not be built just now."), offline: false });
        } else if (!nextDraft || !nextRegistry) {
          setBaseError({
            message: "This workshop isn't on this device.",
            offline: error ? isUnreachable(error) : offline
          });
        } else {
          setBase(null);
          setBaseError(null);
        }
        drawn.current = token;
      };
      try {
        await withDeadline(run(), PREVIEW_DEADLINE_MS);
      } catch {
        if (generation.current === mine) {
          setBaseError({ message: "The preview couldn't be built.", offline: false });
        }
      } finally {
        if (generation.current === mine) setBusy(false);
      }
    },
    [workshopId, draftId, localOnly]
  );

  useEffect(() => {
    if (!isOpen || localOnly === null) return;
    if (drawn.current === refreshToken) return;
    void load(refreshToken);
  }, [load, localOnly, isOpen, refreshToken]);

  useEffect(() => {
    if (!isOpen || draft || !draftId || localOnly === null) return;
    let cancelled = false;
    void loadDraft(draftId)
      .then((found) => {
        if (!cancelled && found) setDraft(found);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [isOpen, draft, draftId, localOnly, live]);

  const registryStage = useMemo(
    () => registry?.stages.find((stage) => stage.key === stageKey) ?? null,
    [registry, stageKey]
  );
  const mediaIds = useMemo(
    () => (live && registryStage ? stageMediaIds(registryStage as never, live) : []),
    [live, registryStage]
  );
  const probed = useProbedImageRefs(isOpen ? mediaIds : [], sources?.media);

  useEffect(() => {
    if (!isOpen || !draft || !registry || !stageKey) return;
    const timer = window.setTimeout(() => {
      try {
        const stageNow = live ?? draftStageAsLive(draft, stageKey);
        const built = buildLiveDocument(registry as unknown as Registry, draft, stageKey, stageNow, sources, probed);
        const meta = built.document.meta;
        const blocks = built.document.blocks as unknown as PreviewBlock[];
        setLiveDoc({
          blocks,
          warnings: built.warnings,
          pageSize: meta.page_size,
          marginMm: meta.margin_mm,
          headerText: meta.header_text,
          footerText: meta.footer_text,
          accent: built.document.theme.accent ? `#${built.document.theme.accent}` : null,
          live: Boolean(live)
        });
      } catch {
      }
    }, liveDoc ? LIVE_DEBOUNCE_MS : 0);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, draft, registry, stageKey, live, sources, probed]);

  const assembled: Shown | null = useMemo(() => {
    let next: Shown | null = null;
    if (base && liveDoc) {
      const spliced = spliceStageBlocks(base.blocks, liveDoc.blocks, stageTitle);
      next = spliced
        ? { ...liveDoc, blocks: spliced, warnings: base.warnings, pageSize: base.meta.pageSize || liveDoc.pageSize }
        : liveDoc;
    } else if (liveDoc) next = liveDoc;
    else if (base)
      next = {
        blocks: base.blocks,
        warnings: base.warnings,
        pageSize: base.meta.pageSize,
        marginMm: (base.meta as { marginMm?: number | null }).marginMm ?? null,
        headerText: "",
        footerText: "",
        accent: null,
        live: false
      };
    return next;
  }, [base, liveDoc, stageTitle]);
  const stable = useStableBlocks(assembled?.blocks ?? EMPTY_BLOCKS);
  const shown: Shown | null = useMemo(() => (assembled ? { ...assembled, blocks: stable } : null), [assembled, stable]);

  const range = useMemo(() => (shown ? stageBlockRange(shown.blocks, stageTitle) : null), [shown, stageTitle]);
  const mediaUrls = useReportMediaUrls(shown?.blocks);
  const palette = useMemo(() => paletteFromAccent(shown?.accent ?? null), [shown?.accent]);
  const blockCount = range ? range[1] - range[0] : 0;

  const sectionRef = useRef<HTMLElement | null>(null);
  const placedFor = useRef<string | null>(null);
  const placeKey = range ? `${stageKey}:${showAll ? "all" : "stage"}` : null;
  useEffect(() => {
    if (!isOpen || !placeKey || placedFor.current === placeKey) return;
    let frame = 0;
    let attempts = 0;
    const place = () => {
      const section = sectionRef.current;
      const target = section?.querySelector<HTMLElement>('[data-rp-focus="start"]');
      if (!section || !target) {
        if (attempts++ < 60) frame = requestAnimationFrame(place);
        return;
      }
      let pane: HTMLElement | null = target.parentElement;
      while (pane && !(pane.scrollHeight > pane.clientHeight + 1 && /auto|scroll/.test(getComputedStyle(pane).overflowY))) {
        pane = pane.parentElement;
        if (pane === document.body || pane === document.documentElement) pane = null;
      }
      placedFor.current = placeKey;
      if (!pane) return;
      pane.scrollTop += target.getBoundingClientRect().top - pane.getBoundingClientRect().top - 12;
    };
    frame = requestAnimationFrame(place);
    return () => cancelAnimationFrame(frame);
  }, [isOpen, placeKey, shown]);

  return (
    <section
      ref={sectionRef}
      className={pane ? "panel mb-5 lg:mb-0 lg:flex lg:min-h-0 lg:flex-1 lg:flex-col" : "panel mb-5"}
      data-stage-preview
    >
      <PreviewDisclosureButton open={isOpen} onToggle={() => setOpen(!isOpen)} />

      {isOpen ? (
        <div className={pane ? "border-t border-line-200 p-3 sm:p-4 lg:flex lg:min-h-0 lg:flex-1 lg:flex-col" : "border-t border-line-200 p-3 sm:p-4"}>
          {localOnly === null ? (
            <p className="text-sm leading-6 text-ink-700">Getting the document ready…</p>
          ) : (
            <>
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-md bg-surface-50 px-3 py-2">
                <p className="min-w-0 flex-1 text-xs leading-5 text-ink-500">
                  {shown?.live
                    ? "Updates as you type."
                    : "As last saved."}
                </p>
                <button
                  type="button"
                  className="field-button-secondary shrink-0"
                  disabled={busy}
                  onClick={() => void load(refreshToken)}
                >
                  <RefreshCw className="h-4 w-4" aria-hidden />
                  {busy ? "Refreshing…" : "Refresh"}
                </button>
              </div>

              <p role="status" aria-live="polite" className={shown ? "sr-only" : "mb-3 text-sm text-ink-700"}>
                {shown ? "The document is ready below." : baseError ? "" : "Building the document…"}
              </p>

              {baseError && !shown ? (
                <div
                  role="alert"
                  className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
                >
                  <p className="min-w-0 flex-1">
                    {baseError.offline
                      ? "Couldn't build the preview. It retries on the next save."
                      : baseError.message}
                  </p>
                  <button
                    type="button"
                    className="shrink-0 font-semibold underline underline-offset-2"
                    disabled={busy}
                    onClick={() => {
                      setBaseError(null);
                      void load(refreshToken);
                    }}
                  >
                    Try again
                  </button>
                </div>
              ) : null}

              {shown ? (
                <>
                  {shown.warnings.length ? (
                    <details className="mb-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
                      <summary className="cursor-pointer font-semibold">
                        {shown.warnings.length} report warning{shown.warnings.length === 1 ? "" : "s"} (whole document)
                      </summary>
                      <ul className="mt-1 ml-5 list-disc space-y-0.5">
                        {shown.warnings.map((warning, index) => (
                          <li key={index}>{warning}</li>
                        ))}
                      </ul>
                      <p className="mt-2 text-xs leading-5">
                        Not printed in the files.
                      </p>
                    </details>
                  ) : null}

                  {range === null ? (
                    <p className="mb-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                      This stage isn&rsquo;t in the document yet, so the whole document is shown.
                    </p>
                  ) : (
                    <div className="mb-3 flex flex-wrap items-center gap-3">
                      <p className="text-xs text-ink-500">
                        {showAll
                          ? `Showing every page; this stage contributes ${blockCount} block${blockCount === 1 ? "" : "s"}.`
                          : "Showing the pages this stage falls on."}
                      </p>
                      <button
                        type="button"
                        className="text-xs font-medium text-purple-700 dark:text-purple-300 underline underline-offset-2"
                        onClick={() => setShowAll((current) => !current)}
                      >
                        {showAll ? "Show only this stage's pages" : "Show every page"}
                      </button>
                    </div>
                  )}

                  <ReportSheets
                    blocks={shown.blocks}
                    pageSize={shown.pageSize}
                    marginMm={shown.marginMm}
                    headerText={shown.headerText}
                    footerText={shown.footerText}
                    mediaUrls={mediaUrls}
                    palette={palette}
                    paletteChosen={Boolean(shown.accent)}
                    focusRange={showAll ? null : range}
                    compact
                    scrollPages={pane}
                  />
                </>
              ) : null}
            </>
          )}
        </div>
      ) : null}
    </section>
  );
}

function draftStageAsLive(draft: DwDraft, stageKey: string): LiveStage {
  const stage = draft.stages[stageKey];
  if (!stage) return { singleton: {}, collections: {} };
  return {
    singleton: Object.assign({}, ...Object.values(stage.singletons ?? {})),
    collections: stage.collections ?? {},
    custom: stage.custom
  };
}
