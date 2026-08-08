"use client";

/**
 * What an upload actually did, shown in full.
 *
 * THIS PANEL IS THE FEATURE, not diagnostics for it. The backend route says so in its own docstring
 * and it is worth restating here, because this is the component a later change is most likely to
 * quietly trim: a designer who uploads forty questions and is shown thirty-eight, with no way to
 * find out which two are missing or why, does not trust the import again — and every likely cause (a
 * merged cell, a formula Excel never calculated, "maybe" typed in the Required column) is invisible
 * from the result. So every problem the parser reported is printed, with its Excel row number, and
 * every question the edit rule superseded or retired is named with the server's own sentence.
 *
 * The row numbers are 1-based worksheet rows exactly as Excel's gutter shows them, which is what
 * makes them worth printing at all: "row 34" means press Ctrl+G and type 34.
 *
 * The counts are drawn even when everything went perfectly. A silent success and a success that
 * quietly dropped six rows must not look the same, and the only way to tell them apart is to always
 * say how many of each thing happened.
 */

import { AlertTriangle, CheckCircle2, Info } from "lucide-react";

import type { QFormUploadReport } from "@/lib/questionnaireForms";

/**
 * The counts worth printing, in the order a designer reads them, paired with the wording each one
 * needs. Held as data rather than as nine hand-written spans so a count cannot be added to the
 * report and silently left off the screen — the failure this whole panel exists to prevent, one
 * level down.
 */
function tallies(report: QFormUploadReport): Array<{ label: string; value: number }> {
  return [
    { label: "questions added", value: report.created },
    { label: "questions updated", value: report.updated ?? 0 },
    { label: "sections added", value: report.sections },
    { label: "left unchanged", value: report.unchanged },
    { label: "reworded into new questions", value: report.superseded },
    { label: "retired", value: report.retired },
    { label: "removed", value: report.removed }
  ].filter((entry) => entry.value > 0);
}

export function UploadReport({ report, className }: { report: QFormUploadReport; className?: string }) {
  const counts = tallies(report);
  const errors = report.problems.filter((problem) => problem.severity === "error");
  const warnings = report.problems.filter((problem) => problem.severity !== "error");
  const details = report.details ?? [];

  return (
    <section className={`panel grid gap-4 p-4 ${className ?? ""}`}>
      <div>
        <h2 className="font-display text-lg font-bold text-ink-900">What the upload did</h2>
        <p className="mt-1 text-sm leading-6 text-ink-muted">
          {counts.length ? (
            counts.map((entry, index) => (
              <span key={entry.label}>
                {index ? " · " : ""}
                <strong className="font-semibold text-ink-900">{entry.value}</strong> {entry.label}
              </span>
            ))
          ) : (
            <>Nothing in the workbook differed from what is already stored, so nothing was changed.</>
          )}
        </p>
        {report.versionAfter !== report.versionBefore ? (
          <p className="mt-1 text-xs leading-5 text-ink-500">
            This questionnaire moved from version {report.versionBefore} to {report.versionAfter}. The version counts edits
            made after answers existed, so anyone holding an older copy of the form can tell theirs is out of date.
          </p>
        ) : null}
      </div>

      {/*
        The edit rule's own account of itself, printed VERBATIM.

        `reason` is written on the server to be shown as-is, and paraphrasing it here would make this
        the fifth place that explains the supersede/retire rule slightly differently. A designer
        whose six reworded questions came back as six NEW questions has to be told that happened and
        that their answers are safe — a question count cannot say it.
      */}
      {details.length ? (
        <div className="grid gap-2">
          <h3 className="field-label">Questions the answers already recorded protected</h3>
          <ul className="grid gap-2">
            {details.map((detail) => (
              <li key={`${detail.action}-${detail.questionId}`} className="rounded-md border border-line-200 bg-surface-50 p-3">
                <div className="flex items-start gap-2">
                  <Info className="mt-0.5 h-4 w-4 shrink-0 text-field-600" aria-hidden />
                  <div className="min-w-0">
                    <p className="text-sm text-ink-900">{detail.reason}</p>
                    {detail.before ? (
                      <p className="mt-1 text-xs leading-5 text-ink-500">
                        Kept, with its answers: <span className="text-ink-700">{detail.before}</span>
                      </p>
                    ) : null}
                    {detail.after ? (
                      <p className="text-xs leading-5 text-ink-500">
                        Added as a new question: <span className="text-ink-700">{detail.after}</span>
                      </p>
                    ) : null}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {/*
        Errors before warnings, and both with the row number first. An "error" is a row where NOTHING
        was stored; a "warning" is a row that was stored but something had to be assumed. Those are
        two different jobs for the designer — one is a question they have lost, the other is a
        question that may say something they did not mean — so they are never merged into one list.
      */}
      {errors.length ? <ProblemList tone="error" title="Rows that could not be read" problems={errors} /> : null}
      {warnings.length ? <ProblemList tone="warning" title="Rows the import had to assume something about" problems={warnings} /> : null}

      {!errors.length && !warnings.length ? (
        <p className="flex items-center gap-2 text-sm text-ink-700">
          <CheckCircle2 className="h-4 w-4 text-success-600" aria-hidden />
          Every row in the workbook was read. Nothing was skipped.
        </p>
      ) : null}
    </section>
  );
}

function ProblemList({
  tone,
  title,
  problems
}: {
  tone: "error" | "warning";
  title: string;
  problems: QFormUploadReport["problems"];
}) {
  // amber-100/amber-800 and error-100/error-600 are the brand rungs; amber-50 and amber-200 are
  // stock Tailwind and do not pair with them (the config deep-merges the two scales).
  const skin =
    tone === "error"
      ? "border-red-200 bg-error-100 text-error-600"
      : "border-amber-500/30 bg-amber-100 text-amber-800";
  return (
    <div className="grid gap-2">
      <h3 className="field-label">
        {title} ({problems.length})
      </h3>
      <ul className="grid gap-2">
        {problems.map((problem, index) => (
          <li key={`${problem.sheet ?? ""}-${problem.row ?? index}-${index}`} className={`rounded-md border p-3 text-sm ${skin}`}>
            <div className="flex items-start gap-2">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
              <div className="min-w-0">
                <p className="font-semibold">
                  {problem.row !== null && problem.row !== undefined ? `Row ${problem.row}` : "This workbook"}
                  {problem.sheet ? ` · sheet "${problem.sheet}"` : ""}
                </p>
                <p className="mt-0.5 leading-6">{problem.reason}</p>
                {problem.value ? <p className="mt-1 break-words text-xs opacity-80">Cell text: {problem.value}</p> : null}
              </div>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
