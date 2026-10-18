/**
 * The Monday summary for reviewers and officials — types, requests and the pure decisions the
 * Settings card and the `/digest/[id]` page make, apart from React so
 * `e2e/weekly-digest-unit.spec.ts` can call them.
 *
 * The server builds and sends it (`backend/app/services/weekly_digest.py`); the routes are the same
 * on the documentation portal and on both handsets:
 *
 *   GET  /notifications/digest              -> DigestPreference
 *   PUT  /notifications/digest  { enabled } -> DigestPreference (403 when not eligible)
 *   GET  /notifications/digests/{id}        -> Digest (404 for anyone but its owner)
 */

import { apiFetch } from "@/lib/api";
import { safeLink } from "@/lib/inAppNotifications";

export type DigestCoverage = {
  label: string;
  value: number;
  of: number | null;
  percent: number | null;
  scope?: string;
};

export type DigestLatest = {
  id: string;
  weekStart: string;
  waiting: number;
  overdue: number;
  newThisWeek: number;
  coverage: DigestCoverage | null;
};

export type DigestPreference = {
  eligible: boolean;
  enabled: boolean;
  emailAvailable: boolean;
  schedule: string;
  latest: DigestLatest | null;
};

export type DigestLine = { key: string; label: string; count: number; link: string };
export type DigestItem = { title: string; link: string; since: string | null };
export type DigestSection = {
  key: "waiting" | "overdue" | "new" | string;
  label: string;
  total: number;
  lines: DigestLine[];
  items?: DigestItem[];
};

export type Digest = {
  id: string;
  weekStart: string;
  generatedAt: string | null;
  sections: DigestSection[];
  coverage: DigestCoverage | null;
};

export const DIGEST_PREFERENCE_PATH = "/notifications/digest";
export const NOT_ELIGIBLE: DigestPreference = {
  eligible: false,
  enabled: false,
  emailAvailable: false,
  schedule: "Mondays, 08:00 IST",
  latest: null
};

export function digestPath(id: string): string {
  return `/notifications/digests/${encodeURIComponent(id)}`;
}

export function digestHref(id: string): string {
  return `/digest/${encodeURIComponent(id)}`;
}

export function readPreference(answer: Partial<DigestPreference> | null | undefined): DigestPreference {
  if (!answer || answer.eligible !== true) {
    return { ...NOT_ELIGIBLE, emailAvailable: answer?.emailAvailable === true };
  }
  return {
    eligible: true,
    enabled: answer.enabled !== false,
    emailAvailable: answer.emailAvailable === true,
    schedule: typeof answer.schedule === "string" ? answer.schedule : NOT_ELIGIBLE.schedule,
    latest: answer.latest ?? null
  };
}

export async function fetchDigestPreference(): Promise<DigestPreference> {
  try {
    return readPreference(await apiFetch<Partial<DigestPreference>>(DIGEST_PREFERENCE_PATH));
  } catch {
    // An older API or a failed read: draw no switch rather than one that cannot work.
    return { ...NOT_ELIGIBLE };
  }
}

export async function saveDigestPreference(enabled: boolean): Promise<DigestPreference> {
  return readPreference(
    await apiFetch<DigestPreference>(DIGEST_PREFERENCE_PATH, { method: "PUT", body: JSON.stringify({ enabled }) })
  );
}

export async function fetchDigest(id: string): Promise<Digest> {
  return apiFetch<Digest>(digestPath(id));
}

// ── The Settings card ────────────────────────────────────────────────────────────────────────────

export const DIGEST_LABEL = "Weekly summary";
export const DIGEST_DESCRIPTION = "Mondays at 08:00: what waits for you, what is overdue, what came in.";
export const LATEST_LINK = "Open last Monday's summary";

/** What the card draws, or null for an account the summary is not for. */
export function digestCardView(state: DigestPreference | null): {
  description: string;
  channel: string;
  latestHref: string | null;
} | null {
  if (!state?.eligible) return null;
  return {
    description: DIGEST_DESCRIPTION,
    channel: state.emailAvailable ? "By e-mail and here." : "Here, in your notifications.",
    latestHref: state.latest ? digestHref(state.latest.id) : null
  };
}

/** The optimistic switch: show the choice at once, keep it on success, put the old one back on failure. */
export function optimistic(state: DigestPreference, enabled: boolean): DigestPreference {
  return { ...state, enabled };
}

export async function toggleDigest(
  before: DigestPreference,
  enabled: boolean,
  save: (enabled: boolean) => Promise<DigestPreference> = saveDigestPreference
): Promise<{ state: DigestPreference; error: string | null }> {
  try {
    return { state: await save(enabled), error: null };
  } catch (err) {
    return { state: before, error: err instanceof Error ? err.message : "Your choice could not be saved. Try again." };
  }
}

// ── The page ─────────────────────────────────────────────────────────────────────────────────────

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "Week of 19 Oct" from "2026-10-19", read as a calendar date (never through `new Date`). */
export function weekTitle(weekStart: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(weekStart || "");
  if (!match) return "Weekly summary";
  return `Week of ${Number(match[3])} ${MONTHS[Number(match[2]) - 1] ?? ""}`.trim();
}

export type DigestTile = { key: string; label: string; figure: string; note: string | null };

/** The four tiles, in order: Waiting, Overdue, New, coverage. */
export function digestTiles(digest: Pick<Digest, "sections" | "coverage">): DigestTile[] {
  const total = (key: string) => digest.sections.find((section) => section.key === key)?.total ?? 0;
  const coverage = digest.coverage;
  let figure = "0";
  let note: string | null = null;
  if (coverage) {
    if (coverage.of === null || coverage.of === undefined) {
      figure = String(coverage.value ?? 0);
    } else {
      figure = coverage.percent === null || coverage.percent === undefined ? String(coverage.value ?? 0) : `${coverage.percent}%`;
      note = `${coverage.value ?? 0} of ${coverage.of}`;
    }
  }
  return [
    { key: "waiting", label: "Waiting", figure: String(total("waiting")), note: null },
    { key: "overdue", label: "Overdue", figure: String(total("overdue")), note: null },
    { key: "new", label: "New", figure: String(total("new")), note: null },
    { key: "coverage", label: coverage?.label || "Coverage", figure, note }
  ];
}

/** A section's rows: lines with a count, then at most five items. Links are app paths only. */
export function sectionRows(section: DigestSection): { lines: DigestLine[]; items: DigestItem[] } {
  const lines = (section.lines ?? []).filter((line) => line.count > 0 && safeLink(line.link) !== null);
  const items = (section.items ?? []).filter((item) => safeLink(item.link) !== null).slice(0, 5);
  return { lines, items };
}
