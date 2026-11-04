"use client";

import { AlertTriangle, CheckCircle2, CopyPlus, Info, ShieldAlert } from "lucide-react";

import type { QFormProvenance, QFormUploadReport } from "@/lib/questionnaireForms";

function tallies(report: QFormUploadReport, copied: boolean): Array<{ label: string; value: number }> {
  return [
    { label: copied ? "questions copied" : "questions added", value: report.created },
    { label: "questions updated", value: report.updated ?? 0 },
    { label: copied ? "sections copied" : "sections added", value: report.sections },
    { label: "left unchanged", value: report.unchanged },
    { label: "reworded into new questions", value: report.superseded },
    { label: "retired", value: report.retired },
    { label: "removed", value: report.removed },
    { label: "kept (only an admin can remove)", value: report.kept ?? 0 },
    { label: "sittings recorded from the sheet", value: report.entriesCreated ?? 0 },
    { label: "answers imported", value: report.answersImported ?? 0 },
    { label: "answers NOT re-recorded", value: report.answersSkipped ?? 0 }
  ].filter((entry) => entry.value > 0);
}

function skinFor(
  provenance: QFormProvenance,
  subject?: string | null
): {
  box: string;
  heading: string;
  body: string;
  title: string;
  icon: React.ReactNode;
} {
  if (provenance.action === "answersImported") {
    return {
      box: "border-line-200 bg-surface-50",
      heading: "text-ink-900",
      body: "text-ink-700",
      title: "The answers in this workbook were recorded under your name",
      icon: <Info className="mt-0.5 h-4 w-4 shrink-0 text-field-600 dark:text-purple-300" aria-hidden />
    };
  }
  if (provenance.action === "reused") {
    return {
      box: "border-line-200 bg-surface-50",
      heading: "text-ink-900",
      body: "text-ink-700",
      title: subject
        ? `“${subject}” is a copy, and it carries no recorded answers`
        : "This questionnaire is a copy, and it carries no recorded answers",
      icon: <CopyPlus className="mt-0.5 h-4 w-4 shrink-0 text-field-600 dark:text-purple-300" aria-hidden />
    };
  }
  return {
    box: "border-amber-500/30 bg-amber-100",
    heading: "text-amber-800",
    body: "text-amber-800",
    title: "The answers in this workbook were not recorded against your copy",
    icon: <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-800 dark:text-amber-100" aria-hidden />
  };
}

export function UploadReport({
  report,
  className,
  subject
}: {
  report: QFormUploadReport;
  className?: string;
  subject?: string | null;
}) {
  const copiedProvenance = report.provenance?.action === "reused";
  const counts = tallies(report, copiedProvenance);
  const provenance = report.provenance ?? null;
  const problems = provenance
    ? report.problems.filter((problem) => problem.reason !== provenance.reason)
    : report.problems;
  const errors = problems.filter((problem) => problem.severity === "error");
  const warnings = problems.filter((problem) => problem.severity !== "error");
  const details = report.details ?? [];
  const skin = provenance ? skinFor(provenance, subject) : null;
  const copied = copiedProvenance;

  return (
    <section className={`panel grid gap-4 p-4 ${className ?? ""}`}>
      <div>
        <h2 className="font-display text-lg font-bold text-ink-900">
          {copied ? (subject ? `What was copied into “${subject}”` : "What was copied") : "What the upload did"}
        </h2>
        <p className="mt-1 text-sm leading-6 text-ink-muted">
          {counts.length ? (
            counts.map((entry, index) => (
              <span key={entry.label}>
                {index ? " · " : ""}
                <strong className="font-semibold text-ink-900">{entry.value}</strong> {entry.label}
              </span>
            ))
          ) : (
            <>
              {copied
                ? subject
                  ? `The questionnaire “${subject}” was copied from has no active questions, so the copy is empty. Add sections and questions to it, or upload a workbook into it.`
                  : "The questionnaire this was copied from has no active questions, so the copy is empty. Add sections and questions to it, or upload a workbook into it."
                : "Nothing in the workbook differed from what is already stored, so nothing was changed."}
            </>
          )}
        </p>
        {report.versionAfter !== report.versionBefore ? (
          <p className="mt-1 text-xs leading-5 text-ink-500">
            This questionnaire moved from version {report.versionBefore} to {report.versionAfter}. The version counts edits
            made after answers existed, so anyone holding an older copy of the form can tell theirs is out of date.
          </p>
        ) : null}
      </div>

      {provenance && skin ? (
        <div className={`grid gap-2 rounded-md border p-3 ${skin.box}`}>
          <div className="flex items-start gap-2">
            {skin.icon}
            <div className="min-w-0">
              <p className={`text-sm font-semibold ${skin.heading}`}>{skin.title}</p>
              <p className={`mt-1 text-sm leading-6 ${skin.body}`}>{provenance.reason}</p>
            </div>
          </div>
        </div>
      ) : null}

      {details.length ? (
        <div className="grid gap-2">
          <h3 className="field-label">Questions the answers already recorded protected</h3>
          <ul className="grid gap-2">
            {details.map((detail) => (
              <li key={`${detail.action}-${detail.questionId}`} className="rounded-md border border-line-200 bg-surface-50 p-3">
                <div className="flex items-start gap-2">
                  <Info className="mt-0.5 h-4 w-4 shrink-0 text-field-600 dark:text-purple-300" aria-hidden />
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

      {errors.length ? <ProblemList tone="error" title="Rows that could not be read" problems={errors} /> : null}
      {warnings.length ? <ProblemList tone="warning" title="Rows the import had to assume something about" problems={warnings} /> : null}

      {!errors.length && !warnings.length ? (
        <p className="flex items-center gap-2 text-sm text-ink-700">
          <CheckCircle2 className="h-4 w-4 text-success-600 dark:text-emerald-400" aria-hidden />
          {copied
            ? "Every active question came across. Nothing was skipped."
            : "Every row in the workbook was read. Nothing was skipped."}
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
  const skin =
    tone === "error"
      ? "border-red-200 bg-error-100 text-red-800"
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
                {problem.value ? <p className="mt-1 wrap-break-word text-xs opacity-80">Cell text: {problem.value}</p> : null}
              </div>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
