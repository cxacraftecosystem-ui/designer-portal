"use client";

/**
 * WHAT ONE UPLOAD DID — the panel that turns a 201 into something an administrator can act on.
 *
 * Three hundred rows go in and a count comes back. Without this panel there is no way to find out
 * WHICH rows changed, which were skipped, or why — and the likeliest causes (a merged cell, a date
 * typed as text, a workshop number that picked up a non-breaking space from Word) are every one of
 * them invisible from the result.
 *
 * It copies the three load-bearing properties of `components/questionnaires/UploadReport.tsx`:
 *
 * 1. **The counts are held as DATA**, an array of `{label, value}`, so a count added to the wire
 *    cannot be silently left off the screen. That panel's own comment calls this "the failure this
 *    whole panel exists to prevent, one level down", and it is the same failure here.
 * 2. **Problems are split by SEVERITY** — `"error"` rows under "Rows that could not be read",
 *    everything else under "Rows the import had to assume something about". Collapsing them puts a
 *    lost workshop and a day-first date reading in one list of forty.
 * 3. **Server `reason` strings are rendered VERBATIM.** They are written on the server to be shown
 *    as-is; this is the fourth place in the stack that could paraphrase the rule and the one where
 *    paraphrasing it would cost an administrator their understanding of who owns what.
 *
 * Plus one section the questionnaire's has no equivalent of: **What changed** — a table of every
 * field one upload altered, with the `reason` sentence beside the rows that had already been opened
 * as workshops.
 */

import { AlertTriangle, CheckCircle2, FileSpreadsheet, Info } from "lucide-react";

import type { PlanProblem, PlanUploadReport as Report } from "./annualPlan";

/**
 * The counts worth printing, in the order an administrator reads them.
 *
 * HELD AS DATA AND NOT AS ELEVEN HAND-WRITTEN SPANS — see the module docstring. The zero-valued ones
 * are filtered out afterwards, EXCEPT `rowsRead`, which is printed even when it is zero because "0
 * rows read" is the single most useful thing to know about an upload that did nothing.
 */
function tallies(report: Report): Array<{ label: string; value: number; always?: boolean }> {
  return [
    { label: "rows read from the sheet", value: report.rowsRead, always: true },
    { label: "workshops added to the plan", value: report.created },
    { label: "workshops corrected", value: report.updated },
    { label: "already correct, left untouched", value: report.unchanged },
    { label: "brought back into the plan", value: report.reinstated },
    { label: "withdrawn from the plan", value: report.withdrawn },
    { label: "in the plan but not in this sheet", value: report.absent },
    { label: "of those, already opened as workshops", value: report.absentPromoted },
    { label: "corrected AFTER being opened as a workshop", value: report.updatedAfterPromotion }
  ];
}

function Tally({ report }: { report: Report }) {
  const rows = tallies(report).filter((entry) => entry.always || entry.value > 0);
  return (
    <dl className="mt-3 flex flex-wrap gap-x-6 gap-y-2">
      {rows.map((entry) => (
        <div key={entry.label} className="flex items-baseline gap-1.5">
          <dt className="sr-only">{entry.label}</dt>
          <dd className="text-base font-semibold text-ink-900">{entry.value}</dd>
          <span aria-hidden className="text-sm text-ink-muted">
            {entry.label}
          </span>
        </div>
      ))}
    </dl>
  );
}

/**
 * One problem row, in the shape `UploadReport` uses: the Excel gutter number first, because that is
 * what an administrator presses Ctrl+G and types, and the offending cell text under it when there
 * is one.
 */
function ProblemRow({ problem }: { problem: PlanProblem }) {
  return (
    <li className="border-t border-line-200 px-3 py-2 first:border-t-0">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-500">
        {problem.row != null ? `Row ${problem.row}` : "This workbook"}
        {problem.sheet ? ` · sheet "${problem.sheet}"` : null}
      </p>
      {/* VERBATIM. The sentence was written on the server to be shown as it is. */}
      <p className="mt-1 text-sm leading-6 text-ink-700">{problem.reason}</p>
      {problem.value ? (
        <p className="mt-1 break-words text-xs text-ink-muted">Cell text: {problem.value}</p>
      ) : null}
    </li>
  );
}

function ProblemBlock({
  title,
  body,
  tone,
  problems
}: {
  title: string;
  body: string;
  tone: "error" | "warning";
  problems: PlanProblem[];
}) {
  if (problems.length === 0) return null;
  const Icon = tone === "error" ? AlertTriangle : Info;
  return (
    <section className="mt-4">
      <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
        <span
          className={
            tone === "error"
              ? "grid h-6 w-6 place-items-center rounded-md bg-error-100 text-error-600"
              : "grid h-6 w-6 place-items-center rounded-md bg-amber-100 text-amber-800"
          }
        >
          <Icon className="h-3.5 w-3.5" aria-hidden />
        </span>
        {title} ({problems.length})
      </h3>
      <p className="mt-1 text-sm leading-6 text-ink-muted">{body}</p>
      <ul className="mt-2 rounded-md border border-line-200 bg-surface-50">
        {problems.map((problem, index) => (
          <ProblemRow key={`${problem.sheet}-${problem.row}-${index}`} problem={problem} />
        ))}
      </ul>
    </section>
  );
}

export function PlanUploadReport({ report }: { report: Report }) {
  const errors = report.problems.filter((problem) => problem.severity === "error");
  const warnings = report.problems.filter((problem) => problem.severity !== "error");

  return (
    <div className="panel mb-5 p-4">
      <div className="flex items-start gap-3">
        {/*
          The ministry pair, because this panel is only ever drawn on `/annual-plan` — one of the
          four surfaces `AppShell` stamps `data-surface="ministry"` on. Both halves are required:
          the ramp is literal and does not invert, so `bg-ministry-50` is a near-white peach in dark
          as well as light and `text-ministry-700` on a dark card is 2.44:1. The dark pair
          (`ministry-950/40` ground, `ministry-300` ink) is `CarryContextBanner`'s own device with
          the hue moved, and measures 10.06:1.
        */}
        <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-ministry-50 text-ministry-700 dark:bg-ministry-950/40 dark:text-ministry-300">
          <FileSpreadsheet className="h-4 w-4" aria-hidden />
        </span>
        <div className="min-w-0">
          <h2 className="display-title text-lg">
            The {report.planYearLabel} plan was read
            {report.sourceFilename ? ` from ${report.sourceFilename}` : ""}
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            {report.sheet ? `Read from the "${report.sheet}" sheet. ` : ""}
            Uploading the same sheet again changes nothing — a row is only written when something in
            it actually differs.
          </p>
          <Tally report={report} />
        </div>
      </div>

      {report.updatedAfterPromotion > 0 ? (
        <p className="mt-4 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
          {report.updatedAfterPromotion === 1
            ? "One of the corrected rows has already been opened as a design & prototype workshop."
            : `${report.updatedAfterPromotion} of the corrected rows have already been opened as design & prototype workshops.`}{" "}
          The plan now says the new value and <strong>the workshop itself has not been touched</strong>
          {" "}— correct it on the workshop&apos;s own screen. The rows are marked below.
        </p>
      ) : null}

      {report.changes.length > 0 ? (
        <section className="mt-4">
          <h3 className="text-sm font-semibold text-ink-900">What changed ({report.changes.length})</h3>
          <div className="mt-2 overflow-x-auto rounded-md border border-line-200">
            <table className="w-full min-w-[46rem] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase tracking-wide text-ink-500">
                <tr>
                  <th className="px-3 py-2 font-medium">Workshop No.</th>
                  <th className="px-3 py-2 font-medium">Field</th>
                  <th className="px-3 py-2 font-medium">From</th>
                  <th className="px-3 py-2 font-medium">To</th>
                </tr>
              </thead>
              <tbody>
                {report.changes.map((change, index) => (
                  <tr key={`${change.workshopNo}-${change.field}-${index}`} className="border-t border-line-200">
                    <td className="px-3 py-2 align-top font-medium text-ink-900">
                      {change.workshopNo}
                      {change.sheetRow != null ? (
                        <span className="block text-xs font-normal text-ink-muted">row {change.sheetRow}</span>
                      ) : null}
                    </td>
                    <td className="px-3 py-2 align-top text-ink-700">{change.fieldLabel}</td>
                    <td className="px-3 py-2 align-top text-ink-muted">{change.from ?? "—"}</td>
                    <td className="px-3 py-2 align-top text-ink-900">
                      {change.to ?? "—"}
                      {/* VERBATIM, and this is the one that matters most. */}
                      {change.reason ? (
                        <span className="mt-1 block text-xs leading-5 text-amber-800">{change.reason}</span>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {report.changesTruncated ? (
            <p className="mt-2 text-sm text-ink-muted">
              Only the first {report.changes.length} changes are listed. More were made — re-upload
              the same sheet to see nothing change, which is how you confirm the rest landed.
            </p>
          ) : null}
        </section>
      ) : null}

      {report.absentRows.length > 0 ? (
        <section className="mt-4">
          <h3 className="text-sm font-semibold text-ink-900">
            In the plan, not in this sheet ({report.absentRows.length})
          </h3>
          <ul className="mt-2 rounded-md border border-line-200 bg-surface-50">
            {report.absentRows.map((row) => (
              <li key={row.workshopNo} className="border-t border-line-200 px-3 py-2 first:border-t-0">
                <p className="text-sm font-medium text-ink-900">{row.workshopNo}</p>
                {/* VERBATIM. */}
                <p className="mt-0.5 text-sm leading-6 text-ink-muted">{row.reason}</p>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <ProblemBlock
        tone="error"
        title="Rows that could not be read"
        body="Nothing was stored for these. Everything else in the sheet was imported."
        problems={errors}
      />
      <ProblemBlock
        tone="warning"
        title="Rows the import had to assume something about"
        body="These were imported. Each one names what was assumed, so you can correct the sheet and upload it again."
        problems={warnings}
      />

      {report.problems.length === 0 ? (
        <p className="mt-4 flex items-center gap-2 text-sm text-ink-muted">
          <CheckCircle2 className="h-4 w-4 text-success-600" aria-hidden />
          Every row in that sheet was read cleanly.
        </p>
      ) : null}
    </div>
  );
}
