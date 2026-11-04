"use client";

import Link from "next/link";
import { AlertTriangle, CheckCircle2, FileSpreadsheet, Info, KeyRound } from "lucide-react";

import type { SanctionImportReport as Report, SanctionParseProblem } from "@/lib/sanctionOrders";

function tallies(report: Report): Array<{ label: string; value: number; always?: boolean }> {
  return [
    { label: "rows read from the sheet", value: report.rowsRead, always: true },
    { label: "orders recorded", value: report.recorded, always: true },
    { label: "rows you left out", value: report.skipped, always: true },
    { label: "rows not recorded", value: report.refused, always: true },
    { label: "designer accounts created", value: report.accountsCreated },
    { label: "sign-in links issued", value: report.credentialLinksIssued }
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

function ProblemRow({ problem }: { problem: SanctionParseProblem }) {
  return (
    <li className="border-t border-line-200 px-3 py-2 first:border-t-0">
      <p className="text-xs font-medium uppercase tracking-wide text-ink-500">
        {problem.row != null ? `Row ${problem.row}` : "This workbook"}
        {problem.sheet ? ` · sheet "${problem.sheet}"` : null}
      </p>
      <p className="mt-1 text-sm leading-6 text-ink-700">{problem.reason}</p>
      {problem.value ? (
        <p className="mt-1 wrap-break-word text-xs text-ink-muted">Cell text: {problem.value}</p>
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
  problems: SanctionParseProblem[];
}) {
  if (problems.length === 0) return null;
  const Icon = tone === "error" ? AlertTriangle : Info;
  return (
    <section className="mt-4">
      <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
        <span
          className={
            tone === "error"
              ? "grid h-6 w-6 place-items-center rounded-md bg-error-100 text-red-800"
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

export function SanctionImportReport({
  report,
  onDismiss
}: {
  report: Report;
  onDismiss: () => void;
}) {
  const errors = report.problems.filter((problem) => problem.severity === "error");
  const warnings = report.problems.filter((problem) => problem.severity !== "error");
  const accounted = report.recorded + report.skipped + report.refused;
  const sums = accounted === report.rowsRead;

  return (
    <div className="panel mb-5 p-4">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-ministry-50 text-ministry-700 dark:bg-ministry-950/40 dark:text-ministry-300">
          <FileSpreadsheet className="h-4 w-4" aria-hidden />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="font-display text-base font-semibold text-ink-900">
            {report.sourceFilename ? `"${report.sourceFilename}"` : "That workbook"} was read
            {report.sheet ? ` from the "${report.sheet}" sheet` : null}
          </h2>
          <Tally report={report} />
          {!sums ? (
            <p className="mt-2 flex items-start gap-2 text-sm leading-6 text-amber-800 dark:text-amber-100">
              <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
              These numbers do not add up: {accounted} rows are accounted for out of{" "}
              {report.rowsRead} read. Upload the sheet again — every order already recorded will be
              reported as a duplicate rather than recorded twice.
            </p>
          ) : null}
        </div>
        <button type="button" className="field-button-secondary shrink-0" onClick={onDismiss}>
          Done
        </button>
      </div>

      {report.accountsCreated > 0 ? (
        <p className="mt-4 flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <KeyRound className="mt-1 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span>
            <span className="font-medium text-ink-900">
              {report.accountsCreated} designer{report.accountsCreated === 1 ? "" : "s"} now
              {report.accountsCreated === 1 ? " has" : " have"} an account.
            </span>{" "}
            {report.credentialLinksIssued === report.accountsCreated
              ? "Each one's first sign-in link is above this report, with a message to send it in."
              : `${report.credentialLinksIssued} first sign-in link${report.credentialLinksIssued === 1 ? " is" : "s are"} above this report; the rows below say whose could not be issued.`}{" "}
            Copy each one now: a link is shown only once. If you miss one, use that designer&apos;s
            “Re-issue” button on the register below.
          </span>
        </p>
      ) : null}

      {report.created.length > 0 ? (
        <section className="mt-4">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
            <span className="grid h-6 w-6 place-items-center rounded-md bg-success-100 text-success-600">
              <CheckCircle2 className="h-3.5 w-3.5" aria-hidden />
            </span>
            Recorded ({report.created.length})
          </h3>
          <ul className="mt-2 rounded-md border border-line-200 bg-surface-50">
            {report.created.map((order) => (
              <li
                key={order.sanctionOrderId}
                className="border-t border-line-200 px-3 py-2 first:border-t-0"
              >
                <p className="text-xs font-medium uppercase tracking-wide text-ink-500">
                  Row {order.sheetRow}
                </p>
                <p className="mt-1 text-sm leading-6 text-ink-700">
                  <span className="font-medium text-ink-900">{order.sanctionOrderNo}</span> ·{" "}
                  {order.designers.map((designer) => designer.designerName).join(", ") ||
                    "no designer named"}{" "}
                  ·{" "}
                  <Link
                    href={`/design-workshops/${order.designWorkshopId}`}
                    className="text-ministry-700 underline underline-offset-2 dark:text-ministry-300"
                  >
                    open the workshop
                  </Link>
                </p>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <ProblemBlock
        title="Rows that were not recorded"
        body="Not saved. Fix them and upload again — orders already recorded won't be duplicated."
        tone="error"
        problems={errors}
      />
      <ProblemBlock
        title="Rows the import had to assume something about"
        body="These were recorded. Each line says what was assumed, so you can check it against the paper."
        tone="warning"
        problems={warnings}
      />
    </div>
  );
}
