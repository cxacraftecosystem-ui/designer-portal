"use client";

/**
 * The two light halves of the stage page's document preview: whether it is open, and the two-pane
 * layout it sits in.
 *
 * SPLIT OUT OF `StageDocumentPreview.tsx` SO THE STAGE FORM CAN DRAW WITHOUT THE PREVIEW'S WEIGHT. The
 * preview itself brings the report's page renderer, its chart and table blocks and the on-device
 * report builder — a large share of the script the stage form used to wait for on every open — and
 * it is closed by default. The stage page now imports these two from here and loads the preview only
 * once a designer opens it (or arrives with it remembered open). `StageDocumentPreview.tsx` re-exports
 * both, so nothing that imported them from there has to change.
 */

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { ChevronDown, ChevronRight, FileText } from "lucide-react";

/**
 * The preview's one control while it is closed, and its heading while it is open.
 *
 * Shared by the preview itself and by {@link StagePreviewShell}, so the button a designer presses
 * before the preview's script has arrived is the very same button they press after.
 */
export function PreviewDisclosureButton({
  open,
  onToggle,
  onIntent
}: {
  open: boolean;
  onToggle: () => void;
  /** Called when the pointer arrives on the button or focus does — the moment to fetch what it opens. */
  onIntent?: () => void;
}) {
  return (
    <button
      type="button"
      className="flex w-full items-center gap-2 px-4 py-3 text-left"
      aria-expanded={open}
      onClick={onToggle}
      onPointerEnter={onIntent}
      onFocus={onIntent}
    >
      {open ? (
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
  );
}

/**
 * The preview panel drawn by the stage page before the preview's own script is loaded.
 *
 * Closed, it is exactly the closed preview — the same panel and the same button — so nothing moves
 * when the real one replaces it. Open (the script is on its way), it says the document is being
 * built, which is what the real panel says first anyway. With `failure`, the script could not be
 * fetched, and the panel says that instead — the close button still works either way.
 */
export function StagePreviewShell({
  open,
  onOpenChange,
  onIntent,
  failure
}: {
  open: boolean;
  onOpenChange: (next: boolean) => void;
  onIntent?: () => void;
  failure?: ReactNode;
}) {
  return (
    <section className={open ? "panel mb-5 lg:mb-0 lg:flex lg:min-h-0 lg:flex-1 lg:flex-col" : "panel mb-5"} data-stage-preview>
      <PreviewDisclosureButton open={open} onToggle={() => onOpenChange(!open)} onIntent={onIntent} />
      {open ? (
        <div className="border-t border-line-200 p-3 sm:p-4">
          {failure ?? (
            <p role="status" aria-live="polite" className="text-sm text-ink-700">
              Building the document…
            </p>
          )}
        </div>
      ) : null}
    </section>
  );
}

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
