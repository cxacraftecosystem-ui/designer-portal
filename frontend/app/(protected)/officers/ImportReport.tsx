"use client";

import { AlertTriangle, CheckCircle2, Info } from "lucide-react";

import type { ArtisanImportReport, ImportProblem } from "./oversight";

function tallies(report: ArtisanImportReport): Array<{ label: string; value: number }> {
  return [
    { label: "rows read", value: report.rowsRead },
    { label: "artisans created", value: report.artisansCreated },
    { label: "already recorded, linked to this workshop", value: report.artisansLinked },
    { label: "rows not imported", value: report.rowsRefused },
    { label: "participants added to stage 3", value: report.participantsCreated }
  ];
}

function ProblemList({ problems, tone }: { problems: ImportProblem[]; tone: "error" | "warning" }) {
  return (
    <ul className="mt-2 grid gap-2">
      {problems.map((problem, index) => (
        <li
          key={`${problem.row ?? "sheet"}-${index}`}
          className={`rounded-md border px-3 py-2 text-sm ${
            tone === "error"
              ? "border-red-200 bg-red-50 text-red-700"
              : "border-line-200 bg-surface-50 text-ink-700"
          }`}
        >
          <span className="block text-xs font-medium uppercase tracking-wide opacity-80">
            {problem.row === null ? "This sheet" : `Row ${problem.row}`}
            {problem.sheet ? ` · sheet "${problem.sheet}"` : ""}
          </span>
          <span className="mt-1 block">{problem.reason}</span>
          {problem.value ? (
            <span className="mt-1 block text-xs opacity-80">Cell text: {problem.value}</span>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

export function ImportReport({ report }: { report: ArtisanImportReport }) {
  const errors = report.problems.filter((problem) => problem.severity === "error");
  const warnings = report.problems.filter((problem) => problem.severity !== "error");

  return (
    <section className="panel mt-4 p-4" aria-live="polite">
      <h3 className="font-display text-base font-bold tracking-tight text-ink-900">
        What this upload did
      </h3>
      {report.sheet ? (
        <p className="mt-1 text-xs text-ink-500">
          Read from the “{report.sheet}” sheet.
        </p>
      ) : null}

      <dl className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {tallies(report).map((tally) => (
          <div key={tally.label} className="rounded-md border border-line-200 bg-surface-50 px-3 py-2">
            <dt className="text-xs text-ink-500">{tally.label}</dt>
            <dd className="font-display text-lg font-bold tracking-tight text-ink-900">
              {tally.value}
            </dd>
          </div>
        ))}
      </dl>

      {errors.length ? (
        <div className="mt-4">
          <h4 className="flex items-center gap-2 text-sm font-medium text-ink-900">
            <AlertTriangle className="h-4 w-4 text-error-600" aria-hidden />
            Rows that could not be read — nothing was created for these
          </h4>
          <ProblemList problems={errors} tone="error" />
        </div>
      ) : null}

      {warnings.length ? (
        <div className="mt-4">
          <h4 className="flex items-center gap-2 text-sm font-medium text-ink-900">
            <Info className="h-4 w-4 text-ink-500" aria-hidden />
            Rows the import had to assume something about — these were imported
          </h4>
          <ProblemList problems={warnings} tone="warning" />
        </div>
      ) : null}

      {!errors.length && !warnings.length ? (
        <p className="mt-4 flex items-center gap-2 text-sm text-ink-700">
          <CheckCircle2 className="h-4 w-4 text-success-600 dark:text-emerald-400" aria-hidden />
          Every row was read exactly as it was typed. Nothing was assumed and nothing was left out.
        </p>
      ) : null}

      <p className="mt-4 text-xs leading-5 text-ink-500">
        No duplicates, nothing overwritten — existing artisans were added as they are. Listed under
        Earlier uploads; the file wasn’t kept.
      </p>
    </section>
  );
}
