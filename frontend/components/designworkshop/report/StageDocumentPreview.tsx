"use client";

/**
 * The document, as it stands, beside the stage being filled in — LIVE, on real A4 pages.
 *
 * Asked for on 2026-08-25 (*"designers can see how the document will look as they enter or modify
 * information"*) and rebuilt on 2026-10-16 after the owner judged the first version unusable. What
 * that version did, measured on a seeded workshop before this change:
 *
 *   • it drew the blocks with NONE of the report's stylesheet — the `rp-*` rules live inside
 *     `ReportSheets`, which this panel did not mount — so tables had no rules or header fill, the
 *     cover and callouts were unstyled HTML, and every photograph was drawn at its natural size;
 *   • it followed the SAVES, not the keystrokes: nothing changed while a designer typed, and every
 *     save blanked the panel to a "Building…" line for the length of a whole server-side build
 *     before drawing the document again, scrolled back to its top;
 *   • it was a disclosure ABOVE the form, so the paragraph being typed and the preview of it were
 *     never on screen at the same time.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * STILL NOT A FIFTH RENDERER — AND NOW LIVE ANYWAY
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The first version refused to build from the local draft because a browser-side builder would be a
 * fifth renderer nobody checks. That stopped being true on 2026-10-10: `lib/offlineReport` is a port
 * of the server's builder, held to the server's output block for block by
 * `offline-report-parity-unit.spec.ts`, and this panel already used it whenever it had no
 * connection. So the live document is built by THAT builder from the draft with this stage's unsaved
 * answers laid over it (`liveDocument.buildLiveDocument`), and the pages are drawn by the same
 * `ReportSheets` the report screen uses. Where the server's document is available, only this
 * stage's blocks are taken from the live build and spliced into it (`stageSlice.spliceStageBlocks`),
 * so every other page keeps the server's content and the page numbers stay the document's own.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * SMOOTH WHILE TYPING
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A rebuild runs {@link LIVE_DEBOUNCE_MS} after the last keystroke, never on every one. Unchanged
 * blocks keep their identity across rebuilds (`stableBlocks`), so React re-renders only the block
 * that changed; `ReportSheets` measures the new document off screen and swaps it in only once its
 * pages are known, so nothing flickers; photographs are sized from their dimensions before a byte
 * arrives, so a picture loading never moves a break; and nothing ever replaces the pages with a
 * loading line once there are pages to show.
 */

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ChevronDown, ChevronRight, FileText, RefreshCw } from "lucide-react";

import { useReportMediaUrls } from "@/components/designworkshop/report/ReportBlock";
import { ReportSheets } from "@/components/designworkshop/report/ReportSheet";
import { buildLiveDocument, stageMediaIds, type LiveStage } from "@/components/designworkshop/report/liveDocument";
import type { PreviewBlock } from "@/components/designworkshop/report/previewModel";
import {
  sliceStageBlocks,
  spliceStageBlocks,
  stableBlocks,
  stageBlockRange
} from "@/components/designworkshop/report/stageSlice";
import { apiFetch } from "@/lib/api";
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

const EMPTY_BLOCKS: PreviewBlock[] = [];

/**
 * `next`, with the block objects that did not change since the last render kept — see
 * `stageSlice.stableBlocks`. Held in state, not a ref, so the render that shows a rebuild is the
 * render that records it.
 */
export function useStableBlocks(next: PreviewBlock[]): PreviewBlock[] {
  const [held, setHeld] = useState(next);
  const stable = useMemo(() => stableBlocks(held, next), [held, next]);
  if (stable !== held) setHeld(stable);
  return stable;
}

/**
 * How long after the last keystroke the document is rebuilt. Long enough that a word typed at speed
 * is one rebuild, short enough that the page answers before the designer's eyes reach it.
 */
export const LIVE_DEBOUNCE_MS = 250;

/** Per-viewer convenience: whether the preview was left open. Never state anything depends on. */
const OPEN_KEY = "dw_stage_preview_open";

/**
 * Whether the preview is open, remembered in this browser — a designer who works with the paper
 * beside the form should not have to reopen it on every one of 22 stages. Closed by default: the
 * first open of every stage by every designer must not pay for building the whole document.
 */
export function useStagePreviewOpen(): [boolean, (next: boolean) => void] {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    try {
      if (window.localStorage.getItem(OPEN_KEY) === "1") setOpen(true);
    } catch {
      // Blocked storage: the panel simply starts closed.
    }
  }, []);
  const set = useCallback((next: boolean) => {
    setOpen(next);
    try {
      window.localStorage.setItem(OPEN_KEY, next ? "1" : "0");
    } catch {
      // Not remembered; nothing else changes.
    }
  }, []);
  return [open, set];
}

/**
 * The stage form and the paper, side by side from `lg` up while the preview is open.
 *
 * Two independently scrolling panes, the house pattern for two views of one thing (the map page,
 * §10.3): the form scrolls with the page, the paper is `sticky` and scrolls inside itself with
 * `overscroll-contain`, so reaching the end of the paper never lurches the form. Below `lg` there is
 * no room for two panes and the preview sits above the form as before.
 */
export function StagePreviewLayout({ open, preview, children }: { open: boolean; preview: ReactNode; children: ReactNode }) {
  return (
    <div
      className={
        open
          ? "lg:grid lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)] lg:items-start lg:gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(0,32rem)]"
          : undefined
      }
    >
      <div
        className={
          open
            ? // A BOUNDED pane that does not itself scroll: the preview inside it pins its controls
              // and scrolls only the paper (`ReportSheets`' `scrollPages`), so nothing a designer
              // needs to reach — the page count, the zoom, "Show every page" — ever scrolls away.
              "lg:sticky lg:top-24 lg:order-2 lg:flex lg:h-[calc(100dvh-7.5rem)] lg:flex-col"
            : undefined
        }
      >
        {preview}
      </div>
      <div className={open ? "min-w-0 lg:order-1" : undefined}>{children}</div>
    </div>
  );
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
  /** Whether what is on the paper includes the form's unsaved answers. */
  live: boolean;
};

/**
 * Dimensions for photographs the kept sources do not describe — attached since they were fetched.
 * Read from the picture itself, once per id, from the device for a `dwlocal:` photograph and from
 * the repository otherwise.
 */
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
            const file = await apiFetch<MediaFile>(`/media/${id}`);
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
          // Unreadable here: the builder leaves it out and the report's own warning names it.
        }
      }
      if (!cancelled && Object.keys(found).length) setRefs((current) => ({ ...current, ...found }));
    })();
    return () => {
      cancelled = true;
    };
    // `key` is `ids` by value.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, known]);
  return refs;
}

export function StageDocumentPreview({
  /**
   * The id the REPOSITORY knows this workshop by — never the route param. The caller resolves it
   * with `reportServerId`; where it has none (`localOnly` true or null) nothing is asked of it.
   */
  workshopId,
  /** The route id, which `loadDraft` matches whichever id it is. */
  draftId,
  stageKey,
  stageTitle,
  /** Bumped after every successful save: the server's document has changed. */
  refreshToken,
  /** Does this workshop exist ONLY on this device? `null` while the caller cannot say. */
  localOnly,
  /** What the form holds right now, unsaved. Absent, the preview follows the saves. */
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
  /** Mounted inside `StagePreviewLayout`'s pane — controlled, and open. */
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

  /**
   * The whole document at the last save — the server's where there is one, the device's otherwise —
   * plus what the live build needs: the draft, the registry and the kept sources.
   *
   * THE PREVIOUS PAGES STAY UP WHILE THIS RUNS. A refresh used to blank the panel to a loading line
   * for a whole server-side build; a designer who had just saved lost the page they were reading.
   */
  const load = useCallback(
    async (token: number) => {
      const mine = generation.current + 1;
      generation.current = mine;
      setBusy(true);
      const userId = draftSessionUserId();
      const offline = typeof navigator !== "undefined" && navigator.onLine === false;
      const fromServer = localOnly === false && !offline;
      try {
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
            message: "This workshop is not saved on this device, so its document cannot be built here.",
            offline: error ? isUnreachable(error) : offline
          });
        } else {
          // The device builds the whole document below, from the same draft; no base is needed.
          setBase(null);
          setBaseError(null);
        }
        drawn.current = token;
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

  /*
    THE DRAFT CAN ARRIVE AFTER THE PANEL OPENS. A stage opened for the first time on this device is
    banked by the stage page a moment after it renders, so the read in `load` can find nothing; it is
    asked again on the next edit rather than waiting for a save that may never come.
  */
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

  /**
   * THE LIVE BUILD, on a debounce. The draft is the workshop as this device holds it; the form's
   * own values replace this stage in it; the builder is the one the parity spec holds to the server.
   */
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
        // A build that throws leaves the last good pages up; the server's document is still shown.
      }
    }, liveDoc ? LIVE_DEBOUNCE_MS : 0);
    return () => window.clearTimeout(timer);
    // `liveDoc` decides only whether the FIRST build waits; it must not re-arm the timer.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, draft, registry, stageKey, live, sources, probed]);

  /**
   * What is put on the paper: the server's document with this stage's live blocks spliced in, or
   * the device's whole live document where there is no server copy (or the stage has no heading in
   * the server's copy yet). Blocks that did not change keep their identity.
   */
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

  /*
    BRING THE STAGE'S HEADING TO THE TOP OF THE PANE, ONCE per stage and per "show every page"
    choice — never on a rebuild, so the paper does not move under a designer who is typing and
    reading. Only the pane's OWN scroll is touched: `scrollIntoView` would also scroll the page and
    drag the form out from under the caret (§10.1). Below `lg` the pane does not scroll by itself and
    nothing happens.
  */
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
        // The pages are drawn a frame or two after the document arrives, once measured.
        if (attempts++ < 60) frame = requestAnimationFrame(place);
        return;
      }
      // The nearest box between the heading and the page that scrolls by itself: the paper's own
      // scroller in the pane (`scrollPages`). Never the document.
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
      <button
        type="button"
        className="flex w-full items-center gap-2 px-4 py-3 text-left"
        aria-expanded={isOpen}
        onClick={() => setOpen(!isOpen)}
      >
        {isOpen ? (
          <ChevronDown className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        ) : (
          <ChevronRight className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        )}
        <FileText className="h-4 w-4 shrink-0 text-field-600 dark:text-purple-300" aria-hidden />
        <span className="min-w-0 flex-1 text-sm font-medium text-ink-900">
          Document preview
          <span className="ml-2 font-normal text-ink-500">— how this stage prints in the report</span>
        </span>
      </button>

      {isOpen ? (
        <div className={pane ? "border-t border-line-200 p-3 sm:p-4 lg:flex lg:min-h-0 lg:flex-1 lg:flex-col" : "border-t border-line-200 p-3 sm:p-4"}>
          {localOnly === null ? (
            <p className="text-sm leading-6 text-ink-700">Getting the document ready…</p>
          ) : (
            <>
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-md bg-surface-50 px-3 py-2">
                <p className="min-w-0 flex-1 text-xs leading-5 text-ink-500">
                  {shown?.live
                    ? "Updates as you type — the same document the .docx and .pdf are written from."
                    : "The same document the .docx and .pdf are written from, as last saved."}
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

              {/* One region, always mounted, so assistive technology hears the first build finish. */}
              <p role="status" aria-live="polite" className={shown ? "sr-only" : "mb-3 text-sm text-ink-700"}>
                {shown ? "The document is ready below." : baseError ? "" : "Building the document…"}
              </p>

              {baseError && !shown ? (
                <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
                  {baseError.offline
                    ? "The document couldn't be built just now. Everything captured is safe, and it will be built again on the next save."
                    : baseError.message}
                </p>
              ) : null}

              {shown ? (
                <>
                  {shown.warnings.length ? (
                    <details className="mb-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
                      <summary className="cursor-pointer font-semibold">
                        {shown.warnings.length} thing{shown.warnings.length === 1 ? "" : "s"} the report can&rsquo;t print
                        as it stands — across the whole document, not only this stage
                      </summary>
                      <ul className="mt-1 ml-5 list-disc space-y-0.5">
                        {shown.warnings.map((warning, index) => (
                          <li key={index}>{warning}</li>
                        ))}
                      </ul>
                      <p className="mt-2 text-xs leading-5">
                        Most of them name the stage they came from. They are not printed in the .docx or the .pdf.
                      </p>
                    </details>
                  ) : null}

                  {range === null ? (
                    <p className="mb-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                      This stage has no heading of its own in the document yet — usually because nothing in it has been
                      filled in, or because the chosen template words the heading differently. The whole document is
                      shown instead.
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

/** A stage as the draft holds it, in the shape the form holds it — for a preview with no live form. */
function draftStageAsLive(draft: DwDraft, stageKey: string): LiveStage {
  const stage = draft.stages[stageKey];
  if (!stage) return { singleton: {}, collections: {} };
  return {
    singleton: Object.assign({}, ...Object.values(stage.singletons ?? {})),
    collections: stage.collections ?? {},
    custom: stage.custom
  };
}
