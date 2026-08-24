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

import { AlertTriangle, CheckCircle2, CopyPlus, Info, ShieldAlert } from "lucide-react";

import type { QFormProvenance, QFormUploadReport } from "@/lib/questionnaireForms";

/**
 * The counts worth printing, in the order a designer reads them, paired with the wording each one
 * needs. Held as data rather than as nine hand-written spans so a count cannot be added to the
 * report and silently left off the screen — the failure this whole panel exists to prevent, one
 * level down.
 */
function tallies(report: QFormUploadReport, copied: boolean): Array<{ label: string; value: number }> {
  return [
    // COPIED, NOT ADDED, when this report is about a reuse. "3 questions added · 2 sections added"
    // is an account of an EDIT, and on the original's own page — where this panel is drawn — it read
    // as three questions just added to the form whose fieldwork is running. Nothing was added to
    // anything: a new row was written carrying the questions of an old one.
    { label: copied ? "questions copied" : "questions added", value: report.created },
    { label: "questions updated", value: report.updated ?? 0 },
    { label: copied ? "sections copied" : "sections added", value: report.sections },
    { label: "left unchanged", value: report.unchanged },
    { label: "reworded into new questions", value: report.superseded },
    { label: "retired", value: report.retired },
    { label: "removed", value: report.removed },
    { label: "sittings recorded from the sheet", value: report.entriesCreated ?? 0 },
    { label: "answers imported", value: report.answersImported ?? 0 },
    // Printed as its own count rather than folded into the provenance sentence below, because a
    // number a designer can compare against what they saw in Excel is what turns "the app decided
    // something" into "the app decided this much".
    { label: "answers NOT re-recorded", value: report.answersSkipped ?? 0 }
  ].filter((entry) => entry.value > 0);
}

/**
 * The heading, tone and icon for one provenance outcome.
 *
 * A LOOKUP RATHER THAN A TERNARY, and that is a bug fix rather than tidying. This block used to read
 * `action === "answersNotImported" ? A : B`, so the moment a THIRD action existed every other value
 * fell into B — and B's heading is "The answers in this workbook were recorded under your name". A
 * reuse, which copies no answer at all and involves no workbook, would have announced itself as
 * having taken authorship of somebody's fieldwork: the exact false statement the provenance field was
 * added to prevent, made by the panel that exists to prevent it.
 *
 * The DEFAULT is therefore the cautious one. An action this component has never heard of gets the
 * amber treatment and the server's own sentence, which is honest about not knowing rather than
 * confidently wrong.
 */
function skinFor(
  provenance: QFormProvenance,
  /** The questionnaire this report is ABOUT, when it is not the page's own subject. See below. */
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
      icon: <Info className="mt-0.5 h-4 w-4 shrink-0 text-field-600" aria-hidden />
    };
  }
  if (provenance.action === "reused") {
    // NEUTRAL, not amber. Nothing was refused and nothing was lost: this is a copy that carried the
    // questions across on purpose. Amber here would teach designers that an ordinary reuse produces
    // a warning, which is the fastest way to make them stop reading the amber that matters.
    //
    // AND IT NAMES THE COPY RATHER THAN SAYING "THIS QUESTIONNAIRE", which is the second half of the
    // same bug the lookup above was written to fix. The heading is a claim about a subject, and this
    // panel is drawn on the page of the questionnaire that was copied FROM — so "This questionnaire
    // is a copy, and it carries no recorded answers" appeared two lines under that page's own banner
    // saying "This questionnaire and every sitting against it are untouched". One screen, two
    // statements, and the false one was about the form whose fieldwork is already running. The
    // ternary bug was fixed and the SUBJECT OF THE SENTENCE was not.
    return {
      box: "border-line-200 bg-surface-50",
      heading: "text-ink-900",
      body: "text-ink-700",
      title: subject
        ? `“${subject}” is a copy, and it carries no recorded answers`
        : "This questionnaire is a copy, and it carries no recorded answers",
      icon: <CopyPlus className="mt-0.5 h-4 w-4 shrink-0 text-field-600" aria-hidden />
    };
  }
  return {
    box: "border-amber-500/30 bg-amber-100",
    heading: "text-amber-800",
    body: "text-amber-800",
    title: "The answers in this workbook were not recorded against your copy",
    icon: <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-800" aria-hidden />
  };
}

export function UploadReport({
  report,
  className,
  subject
}: {
  report: QFormUploadReport;
  className?: string;
  /**
   * THE QUESTIONNAIRE THIS REPORT IS ABOUT, when that is not the questionnaire whose page this is.
   *
   * A REUSE REPORT IS ALWAYS ABOUT A ROW THAT IS NOT ON SCREEN. The copy is a new questionnaire; the
   * page it is reported on is the ORIGINAL's (or the list, where "this questionnaire" names nothing
   * at all). So every sentence in this panel that has a subject takes it from here, and the callers
   * pass the COPY's title. Left unset, the wording falls back to "this questionnaire" — correct only
   * for the upload paths, where the report really is about the page's own subject.
   */
  subject?: string | null;
}) {
  const copiedProvenance = report.provenance?.action === "reused";
  const counts = tallies(report, copiedProvenance);
  const provenance = report.provenance ?? null;
  // The provenance sentence is ALSO pushed into `problems` by the server, so that a client which
  // renders only the problem list still tells the designer about it. This panel renders both, so the
  // copy in the problem list is dropped here — printing one sentence twice, once in a neutral block
  // and once under "rows the import had to assume something about", reads as two separate events.
  const problems = provenance
    ? report.problems.filter((problem) => problem.reason !== provenance.reason)
    : report.problems;
  const errors = problems.filter((problem) => problem.severity === "error");
  const warnings = problems.filter((problem) => problem.severity !== "error");
  const details = report.details ?? [];
  const skin = provenance ? skinFor(provenance, subject) : null;
  // NO WORKBOOK WAS INVOLVED IN A REUSE, so neither the heading nor the "nothing changed" fallback
  // may mention one. This panel is shared with the two upload paths on purpose — one report shape,
  // one component — and the price of sharing it is that its own copy cannot assume a spreadsheet.
  const copied = copiedProvenance;

  return (
    <section className={`panel grid gap-4 p-4 ${className ?? ""}`}>
      <div>
        {/* THE HEADING NAMES THE COPY when it has a name to use. "What was copied", over a tally of
            questions and sections, reads on the original's page as a list of edits just made to the
            form being looked at — which is exactly what a reuse does not do. */}
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

      {/*
        WHAT HAPPENED TO THE ANSWERS THE WORKBOOK ALREADY CARRIED, and this block is the fix for a
        data-integrity defect rather than a nicety.

        The import used to write every answer column in an uploaded workbook as a sitting owned by
        THE UPLOADER. So a designer handed a colleague's downloaded file acquired that colleague's
        respondents — names, notes and answers — as their own recorded fieldwork, under their own
        name, in the questionnaire annexure of the report they submit to a ministry. Nothing said so.

        Silence is therefore not an option in EITHER direction now: an import that refused to
        re-record somebody else's answers has to say it refused, and one that did record them has to
        say whose name they went under. `reason` is written on the server to be shown as-is; this is
        the fourth place in the stack that could paraphrase the rule and the one where paraphrasing
        it would cost a designer their understanding of who owns what.
      */}
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
