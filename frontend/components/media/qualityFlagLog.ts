import type { CapturedFinding } from "@/components/media/photoGate";

/**
 * WHAT THIS DEVICE FOUND WRONG WITH A PHOTOGRAPH IT NEVERTHELESS UPLOADED, KEPT UNTIL SOMEBODY
 * RECORDS IT.
 *
 * ── THE GAP THIS CLOSES ───────────────────────────────────────────────────────────────────────────
 *
 * Stage 21's `mediaQualityFlag` table is where a workshop records that a stored photograph has
 * something wrong with it, and its `autoDetected` column exists to mark the ones the app raised. Up
 * to now NOTHING wrote it: the app raised its findings on the capture screen, they lived in one
 * component's state, and the row was retyped by hand hours later from memory — which in practice
 * means it was not typed at all. The registry note says as much in its own words.
 *
 * A finding cannot become a row at the moment it is raised, because the row needs a `mediaId` and at
 * capture time there is no id: the file has not finished uploading. And it cannot be held in React
 * state, because the capture happens on stage 4 in a courtyard and the archive table is filled in on
 * stage 21 at a desk, days later, in a different browser session. So it has to be written down
 * somewhere that outlives the page, keyed by the workshop.
 *
 * ── WHY `localStorage` AND NOT THE DRAFT STORE, WHICH WOULD BE THE BETTER HOME ────────────────────
 *
 * The right home is `lib/designWorkshopStore.ts`'s draft, which is already per-workshop, already
 * survives a restart, already syncs, and already holds every stage's data — a finding written there
 * would ride the same road as the photograph it is about. It was not used here because that file
 * belongs to another lane and was not mine to change in this wave, and because writing stage 21's
 * rows from stage 4's capture would be a cross-stage side effect a designer never asked for and
 * cannot see. This store is deliberately the smaller, dumber thing: a note pinned to the workshop,
 * which a screen offers and a person commits.
 *
 * ── IT IS AN AID AND NEVER A RECORD, WHICH IS WHY EVERY FAILURE HERE IS SILENT ────────────────────
 *
 * Losing this log loses nothing a designer typed. The photographs are uploaded, the flags can still
 * be entered by hand exactly as they are today, and the only cost is the convenience. That is the
 * whole reason every read and write below swallows its exception: `localStorage` THROWS rather than
 * returning null when a browser blocks site data (the same trap `useAdminView` is wrapped for), and
 * an exception thrown out of a media field's upload handler would take the upload's own success
 * reporting down with it. A convenience must never be able to break the thing it is convenient for.
 *
 * ── AND NOTHING ON SCREEN CLAIMS THIS IS FILED ────────────────────────────────────────────────────
 *
 * As of 2026-08-28 this store is WRITTEN and not yet READ: the screen that would offer these rows is
 * stage 21's collection editor (`components/designworkshop/EntityForm.tsx`), another lane's file.
 * So no sentence anywhere in the media field says a flag was recorded, because it has not been. The
 * moment a claim like that appears on a screen it must be checked against this paragraph.
 */

/** One key per workshop, so opening a second workshop cannot read the first one's findings. */
const KEY_PREFIX = "field_repo_dw_quality_findings:";

/**
 * How many findings one workshop's log may hold.
 *
 * A workshop with two 25-photograph motif galleries and a hundred other images cannot plausibly
 * raise more than a few dozen findings that survive the gate — the gate refuses blur and low
 * resolution outright, so what lands here is near-duplicates. 200 is far past any real workshop and
 * still small enough that the serialised log cannot approach a storage quota. Oldest entries are
 * dropped rather than newest refused: a fresh finding is the one somebody is about to act on.
 */
export const MAX_LOGGED_FINDINGS = 200;

function keyFor(workshopId: string): string {
  return `${KEY_PREFIX}${workshopId}`;
}

/**
 * Read one workshop's findings, oldest first. Always an array — a corrupt or absent entry is "no
 * findings", never an error, because there is nothing here a caller could do about the difference.
 */
export function readCaptureFindings(workshopId: string): CapturedFinding[] {
  if (typeof window === "undefined" || !workshopId) return [];
  try {
    const raw = window.localStorage.getItem(keyFor(workshopId));
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    // Validated member by member rather than cast: this string was last written by whatever build
    // the designer was running a fortnight ago, and a half-shaped row reaching a stage form would be
    // a worse failure than an empty log.
    return parsed.filter((entry): entry is CapturedFinding => {
      if (!entry || typeof entry !== "object") return false;
      const row = entry as Record<string, unknown>;
      return (
        typeof row.mediaId === "string" &&
        row.mediaId.length > 0 &&
        typeof row.fileName === "string" &&
        typeof row.flag === "string" &&
        typeof row.severity === "string" &&
        typeof row.note === "string" &&
        typeof row.raisedAt === "string"
      );
    });
  } catch {
    return [];
  }
}

/**
 * Append findings, de-duplicated by file and flag, keeping the newest of any collision.
 *
 * The de-duplication is by `mediaId:flag` and not by media id alone, because one photograph can
 * legitimately carry two different faults and they are two different rows in the archive. The newest
 * wins so that a re-measurement (a stage reopened, a row re-expanded) refreshes the reading in the
 * note rather than leaving yesterday's number attached to today's file.
 *
 * Returns what the log holds afterwards, so a caller that wants to react to it need not read back.
 */
export function recordCaptureFindings(workshopId: string, findings: CapturedFinding[]): CapturedFinding[] {
  if (typeof window === "undefined" || !workshopId || !findings.length) return readCaptureFindings(workshopId);
  const existing = readCaptureFindings(workshopId);
  const byKey = new Map<string, CapturedFinding>();
  for (const entry of existing) byKey.set(`${entry.mediaId}:${entry.flag}`, entry);
  for (const entry of findings) byKey.set(`${entry.mediaId}:${entry.flag}`, entry);
  // OLDEST DROPPED, NOT NEWEST REFUSED — see MAX_LOGGED_FINDINGS. `Map` preserves insertion order
  // and a re-set keeps a key's ORIGINAL position, so an updated finding does not jump the queue;
  // the slice therefore trims by age of first sighting, which is the order a reader expects.
  const next = [...byKey.values()].slice(-MAX_LOGGED_FINDINGS);
  try {
    window.localStorage.setItem(keyFor(workshopId), JSON.stringify(next));
  } catch {
    // Quota, private mode, or a browser set to block site data. The photographs are uploaded and the
    // flags can still be entered by hand; there is nothing here worth interrupting anybody over.
  }
  return next;
}

/**
 * Forget one workshop's findings.
 *
 * For the screen that COMMITS them — once the rows are in stage 21 the log has done its job, and a
 * log that outlives its commit would offer the same rows again on the next visit. No caller today;
 * it is exported alongside the reader because a store with no way to clear it is a store that grows
 * for the life of the browser profile.
 */
export function forgetCaptureFindings(workshopId: string): void {
  if (typeof window === "undefined" || !workshopId) return;
  try {
    window.localStorage.removeItem(keyFor(workshopId));
  } catch {
    // See the header: every failure here is silent by design.
  }
}
