"use client";

/**
 * One filed report, drawn read-only. The SAME component for the reporter's own list and for the
 * administrator's inbox.
 *
 * ── WHY ONE COMPONENT AND NOT TWO ──────────────────────────────────────────────────────────────
 *
 * The two readers want the same facts in the same order, and the one thing that must never differ
 * between them is the REDRESSAL TRAIL: the status, who acknowledged it, when, and what was written
 * back. If an administrator's inbox and the reporter's own page rendered that from two components,
 * the day they drift is the day somebody is told their grievance was resolved on a screen that does
 * not say by whom, while the admin's screen says it was. So the trail is drawn here, once.
 *
 * The two callers differ in exactly two ways, both passed as props: the inbox names the AUTHOR (the
 * reporter already knows who they are) and carries the decision controls. Everything else is shared.
 *
 * ── THE STATUS IS A WORD AND A TONE, NEVER A TONE ALONE ────────────────────────────────────────
 *
 * App-wide rule: colour never carries meaning by itself, so the judgement survives colour-blindness,
 * greyscale printing and forced-colours mode. The chip prints `statusLabel` — the SERVER's word for
 * it, not one this client invented — and the tone only ever adds emphasis to a word already there.
 *
 * ── AND IT IS `StatusBadge`-SHAPED WITHOUT BEING `StatusBadge` ─────────────────────────────────
 *
 * Adding SUBMITTED / ACKNOWLEDGED / RESOLVED to that component means entries in BOTH its `tone` and
 * `label` maps (§11.9), and those maps are the review ladder's — DRAFT, PENDING, APPROVED,
 * REJECTED, NEEDS_REVISION. Mixing a second vocabulary into one badge is how a reader comes to
 * believe a grievance was "approved". `lib/feedback.feedbackStatusTone` holds these three instead.
 */

import { formatDateTime } from "@/lib/format";
import { feedbackSeverityTone, feedbackStatusTone, type FeedbackReport } from "@/lib/feedback";

function Chip({ tone, children }: { tone: string; children: React.ReactNode }) {
  return (
    <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium ${tone}`}>{children}</span>
  );
}

export function FeedbackReportCard({
  report,
  showAuthor = false,
  actions
}: {
  report: FeedbackReport;
  /** The inbox names who filed it; the reporter's own list does not, because they know. */
  showAuthor?: boolean;
  /** The acknowledge / resolve controls. Absent for the reporter — a decision is not theirs to make. */
  actions?: React.ReactNode;
}) {
  const severityTone = feedbackSeverityTone(report.severity);

  return (
    <article className="panel p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="font-display font-bold leading-snug text-ink-900">{report.subject}</h3>
          <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-ink-500">
            <span className="font-medium text-ink-700">{report.kindLabel}</span>
            {report.areaLabel ? <span>· {report.areaLabel}</span> : null}
            <span>· {formatDateTime(report.createdAt)}</span>
            {showAuthor && report.user ? (
              <span>
                · {report.user.name} <span className="text-ink-300">{report.user.email}</span>
              </span>
            ) : null}
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          {/*
            Severity draws NOTHING when it was not answered — never a neutral "—" chip. A grey chip
            in the severity slot reads as a rung on the scale, and an admin triaging a queue would
            sort it below "Minor" rather than recognising it as a question nobody asked.
          */}
          {severityTone ? <Chip tone={severityTone}>{report.severityLabel}</Chip> : null}
          <Chip tone={feedbackStatusTone(report.status)}>{report.statusLabel}</Chip>
        </div>
      </div>

      <p className="mt-3 whitespace-pre-wrap text-sm leading-6 text-ink-700">{report.details}</p>

      {/*
        THE REDRESSAL TRAIL. Every line names a PERSON and a TIME, because "your grievance was seen"
        is a claim, and a claim with no name and no date on it is one an institution can make about a
        queue nobody read. A missing name beside a present timestamp is drawn honestly rather than
        hidden: both actor columns are ON DELETE SET NULL, so a report acknowledged by a colleague
        who has since left really was acknowledged and the account that did it really is gone.
      */}
      {report.acknowledgedAt || report.resolvedAt || report.responseNote ? (
        <div className="mt-3 grid gap-1 rounded-md border border-line-200 bg-surface-50 p-3 text-xs text-ink-500">
          {report.acknowledgedAt ? (
            <p>
              Seen by {report.acknowledgedBy?.name ?? "an administrator (account since removed)"} on{" "}
              {formatDateTime(report.acknowledgedAt)}
            </p>
          ) : null}
          {report.resolvedAt ? (
            <p>
              Resolved by {report.resolvedBy?.name ?? "an administrator (account since removed)"} on{" "}
              {formatDateTime(report.resolvedAt)}
            </p>
          ) : null}
          {report.responseNote ? (
            <p className="whitespace-pre-wrap text-sm leading-6 text-ink-700">{report.responseNote}</p>
          ) : null}
        </div>
      ) : null}

      {/*
        Captured context, and it is drawn ONLY where somebody would act on it — the inbox. The
        reporter's own copy leaves it off: it is their own browser string, they did not type it, and
        printing it back at them is noise on the screen where they are looking for an answer.
      */}
      {showAuthor && (report.clientLabel || report.clientVersion || report.pagePath || report.platform) ? (
        <p className="mt-2 break-words text-xs text-ink-300">
          {[report.clientLabel, report.clientVersion, report.pagePath, report.platform].filter(Boolean).join(" · ")}
        </p>
      ) : null}

      {actions ? <div className="mt-3 flex flex-wrap items-center gap-2">{actions}</div> : null}
    </article>
  );
}
