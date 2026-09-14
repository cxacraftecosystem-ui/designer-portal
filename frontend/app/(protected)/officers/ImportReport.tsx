"use client";

/**
 * What an artisan-list upload actually did, shown in full.
 *
 * **THIS PANEL IS THE FEATURE, not diagnostics for it**, and it is the component a later change is
 * most likely to quietly trim. An officer who uploads fifteen artisans and is shown thirteen, with
 * no way to find out which two are missing or why, does not trust the import again — and every
 * likely cause is invisible from the result: a merged cell, a formula Excel never calculated, a
 * craft spelled the way the office spells it, a number with one digit wrong.
 *
 * ── THE COUNTS ARE HELD AS DATA, NOT AS FIVE HAND-WRITTEN SPANS ───────────────────────────────
 *
 * So a count cannot be added to the wire and silently left off the screen — the same failure this
 * panel exists to prevent, one level down. `components/questionnaires/UploadReport.tsx` is the
 * sibling this is modelled on and holds its tallies the same way, for the same stated reason.
 *
 * ── EVERY COUNT IS DRAWN, INCLUDING THE ZEROES ────────────────────────────────────────────────
 *
 * The questionnaire's report filters out zeroes; this one does not, and the difference is the
 * arithmetic. Five numbers here have to ADD UP — rows read = created + linked + refused — and a
 * reader can only check that if all of them are on screen. A report that printed "15 rows read · 13
 * created" and omitted a zero would have the reader hunting for two rows that were never refused.
 *
 * ── THE SPLIT IS BY SEVERITY, AND THE HEADINGS SAY WHAT EACH HALF MEANS ───────────────────────
 *
 * `severity === "error"` means NOTHING was created for that row; a warning means the row WAS
 * imported and something had to be assumed. Those are different things to do next — fix and
 * re-upload, versus check the record — and a single "problems" list makes them look the same.
 *
 * ── THE SERVER'S SENTENCE IS PRINTED VERBATIM ─────────────────────────────────────────────────
 *
 * Never paraphrased, never truncated, never mapped through a lookup. The parser knows which digit
 * was wrong, which state a district was checked against, and which earlier row holds the same
 * identity number; a client that summarised any of that would be throwing away the only thing that
 * makes the row fixable. It also means a masked identity number stays masked — the masking happens
 * at the one place the number is read, and nothing here re-masks or un-masks anything.
 *
 * ── AND A SUCCESS LINE WHEN THERE IS NOTHING TO REPORT ────────────────────────────────────────
 *
 * A silent success and a success that quietly dropped six rows must not look the same.
 */

import { AlertTriangle, CheckCircle2, Info } from "lucide-react";

import type { ArtisanImportReport, ImportProblem } from "./oversight";

/**
 * Every count on the wire, in the order an officer reads them.
 *
 * ADD A FIELD TO `ArtisanImportReport` AND IT BELONGS HERE. The type is the contract; this array is
 * the only thing that puts it on screen.
 */
function tallies(report: ArtisanImportReport): Array<{ label: string; value: number }> {
  return [
    { label: "rows read", value: report.rowsRead },
    { label: "artisans created", value: report.artisansCreated },
    // LINKED, NOT "already existed": the count is what HAPPENED to this workshop, which is that
    // somebody already in the repository was added to its roster. Nothing was created and nothing
    // was changed on their record.
    { label: "already recorded, linked to this workshop", value: report.artisansLinked },
    { label: "rows refused", value: report.rowsRefused },
    { label: "participant rows added to stage 3", value: report.participantsCreated }
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
            {/* `row === null` is a fact about the SHEET — an ignored column, a second sheet that was
                not used. Printing "Row null" would be worse than saying which it is. */}
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
          {/* WHICH SHEET WAS READ, always. A workbook with two plausible sheets is a workbook where
              somebody will one day be looking at the wrong one. */}
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
            Rows the import had to assume something about — these WERE imported
          </h4>
          <ProblemList problems={warnings} tone="warning" />
        </div>
      ) : null}

      {!errors.length && !warnings.length ? (
        <p className="mt-4 flex items-center gap-2 text-sm text-ink-700">
          <CheckCircle2 className="h-4 w-4 text-success-600" aria-hidden />
          Every row was read exactly as it was typed. Nothing was assumed and nothing was refused.
        </p>
      ) : null}

      <p className="mt-4 text-xs leading-5 text-ink-500">
        Nobody was created twice and nothing was overwritten. Where an artisan on this list was
        already in the repository they were added to this workshop’s participant list and their
        existing record was left exactly as it is — including anywhere the spreadsheet disagreed with
        it. This upload is kept as a record: it is listed under this workshop’s imports with the
        counts above and the rows it could not read. The workbook itself was not stored.
      </p>
    </section>
  );
}
