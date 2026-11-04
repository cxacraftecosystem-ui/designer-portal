"use client";

import { useId, useState } from "react";
import { ChevronDown, FileSpreadsheet, Lock, Share2, Upload } from "lucide-react";

export function ArtefactNotice({ className }: { className?: string }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();

  return (
    <section className={`panel grid gap-4 p-4 ${className ?? ""}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="font-display text-lg font-bold text-ink-900">Two downloads, and they are not the same file</h2>
          <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-muted">
            Share the <span className="font-medium text-ink-900">question set</span> (no answers). Keep the{" "}
            <span className="font-medium text-ink-900">full .xlsx</span> (all answers) to yourself.
          </p>
        </div>
        <button
          type="button"
          className="field-button-secondary"
          aria-expanded={open}
          aria-controls={open ? panelId : undefined}
          onClick={() => setOpen((current) => !current)}
        >
          <ChevronDown className={`h-4 w-4 ${open ? "rotate-180" : ""}`} aria-hidden />
          {open ? "Hide the detail" : "What is in each file?"}
        </button>
      </div>

      {open ? (
        <div id={panelId} className="grid gap-4">
          <ArtefactDetail />
        </div>
      ) : null}
    </section>
  );
}

function ArtefactDetail() {
  return (
    <>
      <div className="grid gap-3 md:grid-cols-2">
        <article className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
            <Share2 className="h-4 w-4 shrink-0 text-field-600 dark:text-purple-300" aria-hidden />
            Question set — safe to send
          </h3>
          <p className="text-sm leading-6 text-ink-700">
            Questions and sections only.
          </p>
          <p className="text-sm leading-6 text-ink-700">
            <span className="font-semibold text-ink-900">No answers or names.</span> Safe to share.
          </p>
        </article>

        <article className="grid gap-2 rounded-md border border-amber-500/30 bg-amber-100 p-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-amber-800 dark:text-amber-100">
            <Lock className="h-4 w-4 shrink-0" aria-hidden />
            Full .xlsx — your fieldwork
          </h3>
          <p className="text-sm leading-6 text-amber-800 dark:text-amber-100">
            Questions <span className="font-semibold">plus every answer and name</span>.
          </p>
          <p className="text-sm leading-6 text-amber-800 dark:text-amber-100">
            Re-upload this one after editing. Don&rsquo;t share it.
          </p>
        </article>
      </div>

      <div className="grid gap-2 rounded-md border border-line-200 p-3">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
          <Upload className="h-4 w-4 shrink-0 text-field-600 dark:text-purple-300" aria-hidden />
          Answers in an uploaded file
        </h3>
        <ul className="grid gap-1.5 text-sm leading-6 text-ink-700">
          <li className="flex items-start gap-2">
            <FileSpreadsheet className="mt-1 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
            <span>
              <span className="font-medium text-ink-900">Your pro-forma</span> — answers are imported under your name.
            </span>
          </li>
          <li className="flex items-start gap-2">
            <FileSpreadsheet className="mt-1 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
            <span>
              <span className="font-medium text-ink-900">A downloaded workbook</span> — questions only; answers are
              <span className="font-semibold"> not</span> imported.
            </span>
          </li>
        </ul>
      </div>
    </>
  );
}
