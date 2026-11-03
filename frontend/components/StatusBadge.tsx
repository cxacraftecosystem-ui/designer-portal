import type { RecordStatus } from "@/lib/types";

const tone: Record<string, string> = {
  DRAFT: "border-line-200 bg-surface-50 text-ink-500",
  PENDING: "border-amber-500/30 bg-amber-100 text-amber-800",
  APPROVED: "border-success-600/25 bg-success-100 text-success-600",
  REJECTED: "border-error-600/25 bg-error-100 text-red-800",
  NEEDS_REVISION: "border-purple-300 bg-purple-50 text-purple-700",
  IN_PROGRESS: "border-purple-300 bg-purple-50 text-purple-700",
  COMPLETE: "border-amber-500/30 bg-amber-100 text-amber-800",
  PRE_SUBMISSION: "border-amber-800 bg-amber-100 text-amber-800",
  SUBMITTED: "border-success-600/25 bg-success-100 text-success-600",
  ARCHIVED: "border-line-200 bg-field-200 text-ink-500"
};

const label: Record<string, string> = {
  DRAFT: "Draft",
  PENDING: "Pending",
  APPROVED: "Approved",
  REJECTED: "Rejected",
  NEEDS_REVISION: "Needs revision",
  IN_PROGRESS: "In progress",
  COMPLETE: "Complete",
  PRE_SUBMISSION: "Pre-submission",
  SUBMITTED: "Submitted",
  ARCHIVED: "Archived"
};

export function statusLabel(status: string): string {
  return label[status] ?? humanize(String(status));
}

function humanize(status: string): string {
  const words = status.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function StatusBadge({ status }: { status: RecordStatus | string }) {
  const className = tone[status] ?? tone.DRAFT;
  return (
    <span className={`rounded-full border px-2.5 py-1 text-xs font-medium ${className}`}>
      {label[status] ?? humanize(String(status))}
    </span>
  );
}
