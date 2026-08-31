/**
 * The feedback register, as the web client sees it: the wire types, the vocabulary fetch, and the
 * one function that decides what this browser says about itself.
 *
 * ── WHY THE VOCABULARY IS FETCHED AND NOT COMPILED IN ────────────────────────────────────────────
 *
 * SKILL.md §16: when a feature lands on both clients the shared vocabulary must come from the
 * SERVER. Two clients each holding their own copy of "the kinds of feedback there are" will one day
 * describe the same submission differently, and the research this register exists for is then
 * counting two categories that are one. `backend/app/services/feedback_vocabulary.py` is the single
 * definition; `GET /feedback/vocabulary` serves it with the labels attached, and this module's only
 * job is to type the answer. Nothing here holds a list of kinds — deliberately, and if you find
 * yourself adding a `const KINDS = [...]` to make a dropdown render before the fetch lands, that is
 * the drift this paragraph is here to stop.
 *
 * Every row also arrives with its label already resolved (`kind` AND `kindLabel`), so a surface that
 * only PRINTS a stored report never needs the vocabulary at all — which matters because a report
 * filed under a category since retired still has to print, and the server's `label_for` falls back
 * to the raw key rather than raising.
 */

import { apiFetch } from "@/lib/api";

/** One member of a served list: the stored value, and the words a person reads. */
export type FeedbackChoice = { value: string; label: string };

/** Every closed list, exactly as `GET /feedback/vocabulary` answers it. */
export type FeedbackVocabulary = {
  kind: FeedbackChoice[];
  severity: FeedbackChoice[];
  area: FeedbackChoice[];
  status: FeedbackChoice[];
  client: FeedbackChoice[];
};

/** The four identity fields every feedback surface prints about a person. Never more. */
export type FeedbackActor = { id: string; name: string; email: string; role: string };

/**
 * One filed report. The `*Label` fields are the server's words for the stored values beside them —
 * render the label, submit and filter on the value.
 */
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

/** The house list envelope, plus the one extra number the inbox route adds. */
export type FeedbackReportPage = {
  items: FeedbackReport[];
  total: number;
  page: number;
  pageSize: number;
  pages: number;
  /** Reports nobody has read yet, across the WHOLE table — never just this page. */
  openCount?: number;
};

/** What the client says about itself. Captured, never asked — see the field notes below. */
export type ClientContext = {
  client: "WEB";
  clientVersion: string;
  platform: string;
  pagePath: string;
};

/**
 * The web build's version string.
 *
 * A LITERAL, AND THE HONEST ONE AVAILABLE. There is no `NEXT_PUBLIC_APP_VERSION` in this
 * deployment's environment — `lib/api.ts` and `lib/placeSearch.ts` are the only `process.env`
 * readers in the client, and neither is a version — so anything computed here would be invented.
 * What a triaging admin needs from this column is "which build was this written on", and the
 * `package.json` version is 0.1.0 for every web build ever cut, which answers nothing.
 *
 * So it says `web` and the DATE the report was filed carries the rest, which is a fact the server
 * records anyway. Wiring a real build id in is a one-line change here plus one environment variable
 * (`NEXT_PUBLIC_APP_VERSION`, read at build time, inlined by Next) and it is worth doing — but a
 * column confidently holding a version number that is the same string on every deploy is worse than
 * one that admits it does not know, because an admin would filter on it.
 */
const WEB_CLIENT_VERSION = "web";

/**
 * Everything this browser can truthfully say about itself, for a report being filed right now.
 *
 * ── WHY THIS IS TAKEN RATHER THAN ASKED ────────────────────────────────────────────────────────
 *
 * A bug report that does not say which app, which version and which screen is a bug report nobody
 * can reproduce; asking a researcher for their browser's user-agent string is asking them to go and
 * find something they have no reason to know, and the answer they would invent is worse than none.
 *
 * ── WHAT IT DELIBERATELY DOES NOT COLLECT ──────────────────────────────────────────────────────
 *
 * No screen size, no language, no timezone, no fingerprintable set. The test applied to each field
 * was "would an administrator reproducing this fault use it", and only these three pass. The user
 * agent is TRIMMED to 300 characters to match the column, because a modern UA string plus a
 * frozen-brand list runs long and a value the server would refuse is a report that does not save.
 *
 * `pagePath` is the PATH ONLY — never `location.href`. A query string on these routes carries record
 * ids (`?edit=<cuid>`), and a grievance register is not a place to accumulate a trail of which
 * records somebody was looking at. The route is what identifies the screen.
 */
export function captureClientContext(pathname: string): ClientContext {
  const platform = typeof navigator === "undefined" ? "" : navigator.userAgent || "";
  return {
    client: "WEB",
    clientVersion: WEB_CLIENT_VERSION,
    platform: platform.slice(0, 300),
    pagePath: pathname.split("?")[0].slice(0, 300)
  };
}

/**
 * The served vocabulary.
 *
 * `revalidateFromHttpCache` is deliberately NOT passed. §14.1: that opt-out has exactly one caller
 * in this client (`GET /design-workshops/schema`, which is a pure constant with an ETag) and
 * `e2e/registry-conditional-get-unit.spec.ts` counts the opt-ins and fails at two. This endpoint
 * would qualify on the same grounds and is not worth breaking that test's arithmetic for: it is one
 * small request, made once when a feedback form opens.
 */
export function fetchFeedbackVocabulary(): Promise<FeedbackVocabulary> {
  return apiFetch<FeedbackVocabulary>("/feedback/vocabulary");
}

/**
 * The tone a status is drawn in.
 *
 * NOT `StatusBadge`, and that is a decision rather than an oversight: adding SUBMITTED /
 * ACKNOWLEDGED / RESOLVED to that component means entries in BOTH its `tone` and `label` maps
 * (§11.9), and those maps are the review ladder's — DRAFT, PENDING, APPROVED, REJECTED,
 * NEEDS_REVISION. Mixing a second vocabulary into one badge is how a reader comes to believe a
 * grievance was "approved". These three tones live here, beside the only screens that draw them.
 *
 * Every tone is paired with a WORD at every call site, never colour alone — the app-wide rule, and
 * the reason the returned string is only ever a background and a border.
 */
export function feedbackStatusTone(status: string): string {
  if (status === "RESOLVED") return "border-success-600/25 bg-success-100 text-success-600";
  if (status === "ACKNOWLEDGED") return "border-amber-500/30 bg-amber-100 text-amber-800";
  return "border-line-200 bg-surface-50 text-ink-500";
}

/**
 * The tone a severity is drawn in, or null for "not answered".
 *
 * NULL RATHER THAN A NEUTRAL CHIP, because severity is optional and an unanswered one must draw
 * NOTHING. A grey "—" chip in the severity slot reads as a rung on the scale, and an administrator
 * triaging a queue would sort it below LOW rather than recognising it as a question nobody asked.
 */
export function feedbackSeverityTone(severity: string | null): string | null {
  if (!severity) return null;
  if (severity === "CRITICAL" || severity === "HIGH") return "border-error-600/25 bg-error-100 text-error-600";
  if (severity === "MEDIUM") return "border-amber-500/30 bg-amber-100 text-amber-800";
  return "border-line-200 bg-surface-50 text-ink-500";
}

/**
 * The SATISFACTION SURVEY row — one per account, upserted by `PUT /feedback/me`.
 *
 * A DIFFERENT TYPE FROM `FeedbackReport`, AND THE TWO MUST NOT BE MERGED. This is a standing answer
 * to "how is the app working for you", revised in place as the answer changes and pointless to
 * accumulate. A report is the opposite: many per person, each frozen at the moment it was written,
 * each with its own life. `backend/prisma/schema.prisma` carries the argument on both models.
 */
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
