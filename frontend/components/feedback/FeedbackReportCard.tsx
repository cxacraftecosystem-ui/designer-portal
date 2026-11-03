"use client";

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
  showAuthor?: boolean;
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
                · {report.user.name} <span className="text-ink-500">{report.user.email}</span>
              </span>
            ) : null}
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          {severityTone ? <Chip tone={severityTone}>{report.severityLabel}</Chip> : null}
          <Chip tone={feedbackStatusTone(report.status)}>{report.statusLabel}</Chip>
        </div>
      </div>

      <p className="mt-3 whitespace-pre-wrap text-sm leading-6 text-ink-700">{report.details}</p>

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

      {showAuthor && (report.clientLabel || report.clientVersion || report.pagePath || report.platform) ? (
        <p className="mt-2 wrap-break-word text-xs text-ink-500">
          {[report.clientLabel, report.clientVersion, report.pagePath, report.platform].filter(Boolean).join(" · ")}
        </p>
      ) : null}

      {actions ? <div className="mt-3 flex flex-wrap items-center gap-2">{actions}</div> : null}
    </article>
  );
}
