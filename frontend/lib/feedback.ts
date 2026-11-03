import { apiFetch } from "@/lib/api";

export type FeedbackChoice = { value: string; label: string };

export type FeedbackVocabulary = {
  kind: FeedbackChoice[];
  severity: FeedbackChoice[];
  area: FeedbackChoice[];
  status: FeedbackChoice[];
  client: FeedbackChoice[];
};

export type FeedbackActor = { id: string; name: string; email: string; role: string };

export type FeedbackReport = {
  id: string;
  userId: string;
  kind: string;
  kindLabel: string;
  severity: string | null;
  severityLabel: string;
  area: string | null;
  areaLabel: string;
  subject: string;
  details: string;
  client: string | null;
  clientLabel: string;
  clientVersion: string | null;
  platform: string | null;
  pagePath: string | null;
  status: string;
  statusLabel: string;
  acknowledgedAt: string | null;
  resolvedAt: string | null;
  responseNote: string | null;
  createdAt: string;
  updatedAt: string;
  user: FeedbackActor | null;
  acknowledgedBy: FeedbackActor | null;
  resolvedBy: FeedbackActor | null;
};

export type FeedbackReportPage = {
  items: FeedbackReport[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
  openCount?: number;
};

export type ClientContext = {
  client: "WEB";
  clientVersion: string;
  platform: string;
  pagePath: string;
};

const WEB_CLIENT_VERSION = "web";

export function captureClientContext(pathname: string): ClientContext {
  const platform = typeof navigator === "undefined" ? "" : navigator.userAgent || "";
  return {
    client: "WEB",
    clientVersion: WEB_CLIENT_VERSION,
    platform: platform.slice(0, 300),
    pagePath: pathname.split("?")[0].slice(0, 300)
  };
}

export function fetchFeedbackVocabulary(): Promise<FeedbackVocabulary> {
  return apiFetch<FeedbackVocabulary>("/feedback/vocabulary");
}

export function feedbackStatusTone(status: string): string {
  if (status === "RESOLVED") return "border-success-600/25 bg-success-100 text-success-600";
  if (status === "ACKNOWLEDGED") return "border-amber-500/30 bg-amber-100 text-amber-800";
  return "border-line-200 bg-surface-50 text-ink-500";
}

export function feedbackSeverityTone(severity: string | null): string | null {
  if (!severity) return null;
  if (severity === "CRITICAL" || severity === "HIGH") return "border-error-600/25 bg-error-100 text-error-600";
  if (severity === "MEDIUM") return "border-amber-500/30 bg-amber-100 text-amber-800";
  return "border-line-200 bg-surface-50 text-ink-500";
}

export type AppFeedback = {
  id?: string;
  userId?: string;
  rating?: number | null;
  easeOfUse?: number | null;
  reliability?: number | null;
  performance?: number | null;
  design?: number | null;
  features?: number | null;
  recommend?: number | null;
  comment?: string | null;
  likeMost?: string | null;
  improve?: string | null;
  bugs?: string | null;
  featureRequests?: string | null;
  role?: string | null;
  createdAt?: string;
  updatedAt?: string;
  user?: FeedbackActor | null;
};
