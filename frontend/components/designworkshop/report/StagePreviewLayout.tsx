"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { ChevronDown, ChevronRight, FileText } from "lucide-react";

export function PreviewDisclosureButton({
  open,
  onToggle,
  onIntent
}: {
  open: boolean;
  onToggle: () => void;
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

const OPEN_KEY = "dw_stage_preview_open";

export function useStagePreviewOpen(): [boolean, (next: boolean) => void] {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    try {
      if (window.localStorage.getItem(OPEN_KEY) === "1") setOpen(true);
    } catch {
    }
  }, []);
  const set = useCallback((next: boolean) => {
    setOpen(next);
    try {
      window.localStorage.setItem(OPEN_KEY, next ? "1" : "0");
    } catch {
    }
  }, []);
  return [open, set];
}

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
            ?
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
