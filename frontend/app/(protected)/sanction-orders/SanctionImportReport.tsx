"use client";

/**
 * WHAT ONE SANCTION UPLOAD DID — the panel that turns a 201 into something an officer can act on.
 *
 * It is `annual-plan/PlanUploadReport.tsx`'s three load-bearing properties, deliberately, so that
 * the two importers read as the same author's work:
 *
 * 1. **The counts are held as DATA**, an array of `{label, value}`, so a count added to the wire
 *    cannot be silently left off the screen. `PlanUploadReport` calls that "the failure this whole
 *    panel exists to prevent, one level down", and it is the same failure here.
 * 2. **Problems are split by SEVERITY** — `"error"` rows under "Rows that were not recorded",
 *    everything else under "Rows the import had to assume something about". Collapsing them puts a
 *    lost ministry order and a day-first date reading in one list of forty.
 * 3. **Server `reason` strings are rendered VERBATIM.** They are written on the server to be shown
 *    as-is; this is the fourth place in the stack that could paraphrase a refusal and the one where
 *    paraphrasing it would cost an officer their understanding of who owns what.
 *
 * ── THE TWO THINGS THIS PANEL SAYS THAT THE ANNUAL PLAN'S DOES NOT HAVE TO ──────────────────────
 *
 * **HOW MANY PEOPLE THIS PRESS LET IN.** An annual-plan upload writes rows in a directory; this one
 * mints `User` accounts, admits them to the platform allow-list and empanels them. That number is
 * not derivable from the row counts — one order can create three accounts and the next none — and it
 * is the number an officer's next hour depends on.
 *
 * **THAT NO SIGN-IN LINK WAS ISSUED, AND WHAT TO DO ABOUT IT.** A link is shown once and can never be
 * shown again; there is no mailer in this product and the officer's clipboard is the transport. An
 * import that returned two hundred live credentials on one screen would change the SECURITY posture
 * of the feature — one accidental close strands two hundred designers, one screenshot is two hundred
 * credentials — so it mints none, and this panel has to say so in words or the accounts it made are
 * accounts nobody can sign in to.
 *
 * ── THE ARITHMETIC IS DRAWN SO IT CAN BE CHECKED ────────────────────────────────────────────────
 *
 * `rowsRead = recorded + skipped + refused`. Every one of those is drawn including the zeroes, which
 * is the only way an officer can tell an import that did nothing from one whose report lost a row.
 * When the four do not sum, the panel SAYS SO rather than hiding it — that mismatch is the symptom
 * of rows going missing between the two POSTs, and it is the only way anybody would ever see it.
 */

import Link from "next/link";
import { AlertTriangle, CheckCircle2, FileSpreadsheet, Info, KeyRound } from "lucide-react";

import type { SanctionImportReport as Report, SanctionParseProblem } from "@/lib/sanctionOrders";

/**
 * The counts worth printing, in the order an officer reads them.
 *
 * HELD AS DATA AND NOT AS SIX HAND-WRITTEN SPANS — see the module docstring. `always` marks the ones
 * printed even at zero: `rowsRead` because "0 rows read" is the most useful thing to know about an
 * upload that did nothing, and the other three because they are the terms of the sum.
 */
function tallies(report: Report): Array<{ label: string; value: number; always?: boolean }> {
  return [
    { label: "rows read from the sheet", value: report.rowsRead, always: true },
    { label: "orders recorded", value: report.recorded, always: true },
    { label: "rows you left out", value: report.skipped, always: true },
    { label: "rows not recorded", value: report.refused, always: true },
    { label: "designer accounts created", value: report.accountsCreated }
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

/** One problem row: the Excel gutter number first, because that is what Ctrl+G takes. */
function ProblemRow({ problem }: { problem: SanctionParseProblem }) {
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

export function SanctionImportReport({
  report,
  onDismiss
}: {
  report: Report;
  onDismiss: () => void;
}) {
  const errors = report.problems.filter((problem) => problem.severity === "error");
  const warnings = report.problems.filter((problem) => problem.severity !== "error");
  /**
   * THE SUM, CHECKED ON SCREEN RATHER THAN ASSUMED.
   *
   * `rowsRead` is the count the PREVIEW reported and this client echoed back; the other three are
   * the server's own. If they disagree, rows went missing between the two POSTs — and a panel that
   * quietly drew both numbers without comparing them is a panel in which that can never be noticed.
   */
  const accounted = report.recorded + report.skipped + report.refused;
  const sums = accounted === report.rowsRead;

  return (
    <div className="panel mb-5 p-4">
      <div className="flex items-start gap-3">
        {/*
          The ministry pair, because this panel is only ever drawn on `/sanction-orders` — one of the
          four surfaces `AppShell` stamps `data-surface="ministry"` on. Both halves are required: the
          ramp is literal and does not invert, so `bg-ministry-50` is a near-white peach in dark as
          well as light and `text-ministry-700` on a dark card is 2.44:1. The dark pair
          (`ministry-950/40` ground, `ministry-300` ink) is `CarryContextBanner`'s own device with the
          hue moved. Copied from `PlanUploadReport` rather than re-derived, so the two importers'
          panels cannot drift apart on a rung.
        */}
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
            <p className="mt-2 flex items-start gap-2 text-sm leading-6 text-amber-800">
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
        /*
          NOT DECORATION, AND THE ONE THING ON THIS PANEL AN OFFICER MUST NOT MISS. The import
          created accounts and issued no credentials for them, on purpose — see `lib/sanctionOrders`.
          Without this sentence the feature quietly produces N people who exist on the platform and
          cannot sign in, and the only thing that would ever tell anybody is a designer ringing up.
        */
        <p className="mt-4 flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <KeyRound className="mt-1 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span>
            <span className="font-medium text-ink-900">
              {report.accountsCreated} designer{report.accountsCreated === 1 ? "" : "s"} now
              {report.accountsCreated === 1 ? " has" : " have"} an account and no sign-in link.
            </span>{" "}
            An import issues none: a link is shown once and can never be shown again, and nothing is
            emailed by this product. Use “Re-issue sign-in link” on each order below when you are
            ready to send them.
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
                  {/* LEAD FIRST, AND NEVER RE-SORTED HERE — the server's order is the officer's. */}
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
        body="Nothing was written for these. Correct them in the sheet and upload it again — every order already recorded will be reported as a duplicate rather than recorded twice."
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
