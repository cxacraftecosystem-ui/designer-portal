/**
 * What changed in the DATA between two generated reports — and, just as importantly, what cannot
 * be known from what is stored.
 *
 * THE QUESTION THIS ANSWERS. A report goes to a ministry and comes back for revision three or four
 * times. Months later a reviewer asks "did you update the cost sheet before you resubmitted?" and
 * until now the system had no answer at all: four files existed, each with a checksum, and nothing
 * said what was different about them.
 *
 * WHY IT IS NOT A DIFF OF TWO DOCUMENTS. Comparing the .docx bytes would answer a question nobody
 * asked. Two files generated an hour apart differ in their generation date, their embedded photo
 * ordering and, if the accent colour was changed, in every heading — none of which is a change to
 * the fieldwork. What a reviewer means by "did it change" is whether the RECORD changed, so this
 * works on the record's own timestamps.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHAT IS GENUINELY KNOWABLE, AND WHAT IS NOT. Read this before extending anything below.
 *
 * NO SNAPSHOT OF THE STAGE DATA IS KEPT AT EXPORT TIME. `DwReportExport` records the file — its
 * checksum, size, page count, template and registry version — and not one field of what the file
 * said. Every stage row carries `createdAt` / `updatedAt` / `deletedAt`, and that is the entire
 * evidence base. So:
 *
 *   KNOWABLE, and stated as fact:
 *     • whether a stage was touched at all between two exports — and therefore, when it was not,
 *       that the two files carried IDENTICAL data for that stage. That is a proof, not a guess,
 *       and it is the honest answer to "did you change the cost sheet": no, provably not.
 *     • how many rows were added, REWRITTEN, or removed, per stage.
 *     • whether the workshop header (title, craft, cluster, dates — the cover page) was left alone.
 *       Only in that direction: see {@link ReportDiff.headerRowWritten} for why the positive case
 *       cannot be told apart from an ordinary stage save.
 *     • whether the two files are byte-identical, from the checksums the records already carry.
 *     • whether anything has been edited SINCE a file was generated, i.e. whether the copy on
 *       somebody's desk is already out of date.
 *
 *   NOT KNOWABLE, and therefore never claimed:
 *     • WHICH FIELD changed, or what it changed from. A rewritten row reports "rewritten", full stop.
 *     • WHETHER A REWRITTEN ROW'S ANSWERS ACTUALLY DIFFER. A stage is saved WHOLE — `save_stage`
 *       issues an update for every row the payload names, without comparing it to what is stored —
 *       so correcting one word in one description stamps every row of that stage with a fresh
 *       `updatedAt`. The count is therefore rows SAVED, never rows whose content differs, and the
 *       vocabulary on screen is "written" rather than "changed" for exactly that reason. This is
 *       also why the strong claim runs the other way: a stage nobody saved is a stage whose data
 *       is provably identical, and that direction has no such loophole.
 *     • how many times a row changed inside a window — `updatedAt` remembers only the last write.
 *     • what a stage's completeness was AT either export. Today's percentage is today's. It is
 *       reported only for stages proven untouched since the earlier file, where today's figure IS
 *       both files' figure; everywhere else it is withheld rather than presented as a delta.
 *     • anything about records the workshop merely POINTS AT — an artisan renamed, a linked
 *       product edited, a photograph's caption changed. Those rows live outside the workshop and
 *       carry their own timestamps, which this payload does not include.
 *
 *   A FUTURE SNAPSHOT WOULD ADD exactly the first two of those: storing the assembled
 *   `WorkshopData` (or its hash per entity) alongside each `DwReportExport` row would turn "stage 15
 *   was edited" into "the unit cost of the dyed yarn went from ₹420 to ₹455", and would survive the
 *   row being deleted afterwards. It is not free — the payload is the whole workshop, per export —
 *   which is why it is named here as the missing piece rather than approximated by guesswork.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * TWO CLOCKS, AND ONE OF THEM IS A PHONE'S. Stage timestamps are written by the server. An export
 * recorded by `POST /{id}/exports` carries the timestamp the DEVICE reported, because that is when
 * the file was made and the device had no network at the time. A handset whose clock is a day out
 * therefore shifts one end of the window by a day. {@link ReportDiff.deviceClockInvolved} says when
 * a comparison rests on a device's clock so the screen can say so, because the alternative is a
 * confident sentence about a window that never existed.
 *
 * PURE. No React, no fetch, no storage — the same shape as `lib/marketAnalysis.ts`, and for the same
 * reason: a designer flipping between generation 1 and generation 4 does it with arithmetic over
 * data already in hand, with no further request. (The history itself must be fetched — the export
 * table records files made on other devices by other people, so unlike a stage form this cannot be
 * served from the local draft.)
 */

/** One recorded export, exactly as `GET /design-workshops/{id}/report-history` serialises it. */
export type DwExportRecord = {
  id: string;
  /**
   * THE FILE'S PLACE IN THE WORKSHOP'S WHOLE EXPORT RECORD — one-based, oldest first — as the
   * SERVER computed it. Absent on a payload from a repository that predates the field.
   *
   * IT IS THE SERVER'S TO COMPUTE AND THIS CLIENT MUST NOT PREFER ITS OWN. The export list is
   * capped at the newest hundred, so a client numbering the files it was sent restarts at 1 on
   * whichever file survived the cut: every "Generation N" on screen is then off by the number of
   * files dropped, and off by a DIFFERENT amount each time somebody generates another one — on a
   * number a designer quotes into a covering email to a ministry months later. This browser
   * derived it exactly that way, and `_export_payload(generation=…)` was added to close it.
   *
   * ABSENT IS AN ORDINARY STATE AND NOT AN ERROR: a repository one deploy behind this build
   * answers without the field. {@link generationOf} then falls back to the position inside the
   * window, which is exactly right until the cap bites and is DISCLOSED on screen the moment it
   * might not be — see {@link generationsAreAbsolute}.
   */
  generation?: number;
  format: string;
  templateId: string;
  fileName: string;
  /** Prisma `Int?`, so a number here — but null on a record made before the column was populated. */
  fileSizeBytes: number | null;
  pageCount: number | null;
  /** The SHA-256 of the bytes. THE ONLY THING THAT PROVES TWO FILES ARE THE SAME FILE. */
  checksumSha256: string | null;
  /** True when a phone produced the file with no network — see the clock note in the file header. */
  generatedOnDevice: boolean;
  /** The field registry's digest at generation. A change here means the field list itself moved. */
  schemaVersion: string | null;
  warnings: string | null;
  generatedAt: string | null;
  generatedById: string | null;
  /** Null when the account has since been deleted. Never substituted with the workshop's owner. */
  generatedByName: string | null;
};

/** One stage row's timestamps. No data — see the header for why there is none to have. */
export type DwEntryTimestamp = {
  id: string;
  stageKey: string;
  entityKey: string;
  ordinal: number;
  createdAt: string | null;
  updatedAt: string | null;
  /** Set when the row was removed. A removed cost line is a change, so deleted rows are included. */
  deletedAt: string | null;
};

export type DwReportHistory = {
  workshopId: string;
  /** The workshop header's last write. One timestamp, so it knows only the LAST edit. */
  workshopUpdatedAt: string | null;
  /** The server's own clock, because "since" must not be measured against a field laptop's. */
  serverTime: string;
  /**
   * TODAY's per-stage scores, keyed by stage key — carried here so the screen does not have to
   * fetch the entire workshop (every field of every row, plus the transcript annexure) to print a
   * percentage on a metered connection.
   *
   * It is today's figure and nothing stored says what a stage scored when a past report was made,
   * so it may be attached to an export ONLY where {@link StageChange.currentReflectsBoth} holds.
   * Typed loosely on purpose: this module is pure and must not depend on the API type module,
   * which imports it.
   */
  completeness: Record<string, { percent: number; requiredFilled: number; requiredTotal: number }>;
  exports: DwExportRecord[];
  exportsTruncated: boolean;
  entries: DwEntryTimestamp[];
  /** When true, no "provably unchanged" claim may be made — rows are missing from the evidence. */
  entriesTruncated: boolean;
};

/** What happened to one stage's rows inside one window. */
export type StageChange = {
  stageKey: string;
  /** In the later file and not the earlier one. */
  rowsAdded: number;
  /**
   * In both files, and written in between.
   *
   * SAVED, NOT NECESSARILY DIFFERENT. A stage is saved whole and every row in the payload is
   * updated without comparison, so one corrected word stamps the lot. Never render this as
   * "changed" — see the header.
   */
  rowsRewritten: number;
  /** In the earlier file and not the later one. */
  rowsRemoved: number;
  /**
   * Created AND removed inside the window: real work, present in neither file. Counted separately
   * because it is not a difference between the two documents and must not be reported as one — but
   * it is not nothing either, and a designer who remembers doing it deserves to see it acknowledged
   * rather than be told the stage was untouched.
   */
  rowsTransient: number;
  /**
   * Something was written to this stage between the two files. NOT "the data differs" — see
   * {@link StageChange.rowsRewritten}. Its FALSE is the strong statement: nothing was written, so
   * both files carried identical data for this stage.
   */
  touched: boolean;
  /**
   * Nothing has touched this stage from the earlier file until now — so today's stage data, and
   * therefore today's completeness score, is exactly what BOTH files carried. This is the only
   * circumstance in which a percentage may honestly be attached to a past export.
   */
  currentReflectsBoth: boolean;
};

export type ReportDiff = {
  earlier: DwExportRecord;
  later: DwExportRecord;
  /** 1-based positions in the workshop's own history, oldest first — "generation 1", "generation 4". */
  earlierGeneration: number;
  laterGeneration: number;
  /** The window, as ISO strings: `(windowFrom, windowTo]`. */
  windowFrom: string;
  windowTo: string;
  /** Only stages that hold rows at all. A stage absent here has never held data in either file. */
  byStage: Record<string, StageChange>;
  /** Written to inside the window. Say "written", not "changed". */
  touchedStageKeys: string[];
  /** Not written to at all, so both files carried identical data for them. The provable half. */
  untouchedStageKeys: string[];
  /**
   * The workshop's OWN row was written inside the window — and read this carefully, because the
   * obvious reading of it is wrong.
   *
   * `save_stage` stamps the workshop row on EVERY stage save (it always writes `schemaVersion`,
   * and promotes stage 1's craft and cluster onto the header), so a true here means "the row was
   * touched", NOT "the cover details were edited". Presenting it as the latter would put a
   * confident sentence about the title and dates on screen every time a designer saved any stage
   * — which is most windows.
   *
   * FALSE, HOWEVER, IS A PROOF: nothing wrote the workshop row, so the title, craft, cluster and
   * dates that print on the cover are identical in both files. That asymmetry is the whole value
   * of the field, and the screen states the two cases differently.
   *
   * Null when the row carries no `updatedAt` at all.
   */
  headerRowWritten: boolean | null;
  /** Both checksums present and equal: the two files are byte-for-byte the same file. */
  identicalFile: boolean;
  /** Both checksums present, so `identicalFile` is a fact either way rather than an absence. */
  checksumComparable: boolean;
  templateChanged: boolean;
  /** A .docx and a .pdf of the same data are different files by construction, not by revision. */
  formatDiffers: boolean;
  /** The field registry moved between the two files: a field may have been added, dropped or retyped. */
  schemaVersionChanged: boolean;
  sizeDelta: number | null;
  pageDelta: number | null;
  /** At least one end of the window is a device's clock reading. Say so on screen. */
  deviceClockInvolved: boolean;
  /** False when the entry timeline was capped: no "unchanged" claim may be made from it. */
  timelineComplete: boolean;
};

/** Milliseconds since epoch, or null for a missing/unparseable timestamp. */
function ms(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const value = Date.parse(iso);
  return Number.isFinite(value) ? value : null;
}

/**
 * Was this row part of the record at instant `at`?
 *
 * The whole classification rests on this rather than on "was `updatedAt` inside the window", and
 * the difference is not pedantic: a row created and deleted between two exports has an `updatedAt`
 * squarely inside the window and appears in NEITHER file, so the naive test reports a difference
 * between two documents that are identical in that stage.
 *
 * A row with no `createdAt` — which nothing in this schema produces, but JSON is JSON — is treated
 * as absent rather than as present since the beginning of time, because inventing presence would
 * manufacture a "removed" row in a stage nobody touched.
 */
function presentAt(row: DwEntryTimestamp, at: number): boolean {
  const created = ms(row.createdAt);
  if (created === null || created > at) return false;
  const deleted = ms(row.deletedAt);
  return deleted === null || deleted > at;
}

/**
 * Every stage that holds rows, and what happened to it between two instants.
 *
 * Exported in its own right because "what changed since this file was generated" is the same
 * question with `to` set to the server's clock — and that is how a designer learns that the copy
 * already sitting on an officer's desk no longer matches the record.
 */
export function stageChangesBetween(
  entries: DwEntryTimestamp[],
  fromIso: string,
  toIso: string
): Record<string, StageChange> {
  const from = ms(fromIso);
  const to = ms(toIso);
  const byStage: Record<string, StageChange> = {};
  if (from === null || to === null) return byStage;

  for (const row of entries) {
    const change =
      byStage[row.stageKey] ??
      (byStage[row.stageKey] = {
        stageKey: row.stageKey,
        rowsAdded: 0,
        rowsRewritten: 0,
        rowsRemoved: 0,
        rowsTransient: 0,
        touched: false,
        // Starts true and is cleared by the first row that has been written since `from`. A stage
        // with rows none of which moved keeps it, which is precisely the claim being made.
        currentReflectsBoth: true
      });

    const inEarlier = presentAt(row, from);
    const inLater = presentAt(row, to);
    const written = ms(row.updatedAt);

    if (!inEarlier && inLater) change.rowsAdded += 1;
    else if (inEarlier && !inLater) change.rowsRemoved += 1;
    else if (inEarlier && inLater && written !== null && written > from && written <= to) {
      change.rowsRewritten += 1;
    } else if (!inEarlier && !inLater && written !== null && written > from && written <= to) {
      change.rowsTransient += 1;
    }

    // `updatedAt` moves on every write INCLUDING the soft delete, so one comparison covers created,
    // edited and removed. Anything written after `from` — inside the window or after it — means
    // today's data is no longer what the earlier file carried.
    if (written !== null && written > from) change.currentReflectsBoth = false;
  }

  for (const change of Object.values(byStage)) {
    change.touched = change.rowsAdded > 0 || change.rowsRewritten > 0 || change.rowsRemoved > 0;
  }
  return byStage;
}

/** The exports oldest first, dropping any that never recorded when they were generated. */
export function inGenerationOrder(history: DwReportHistory): DwExportRecord[] {
  // `filter` already returns a fresh array, so the in-place `sort` cannot reach the caller's.
  return history.exports
    .filter((row) => ms(row.generatedAt) !== null)
    .sort((a, b) => (ms(a.generatedAt) ?? 0) - (ms(b.generatedAt) ?? 0));
}

/**
 * Is every generation number on this payload the SERVER'S — the file's place in the whole export
 * record — rather than this browser's position inside a window that may have been cut?
 *
 * The screen prints the difference. When this is true, "Generation 7" means the seventh file this
 * workshop ever produced and is safe to quote into a covering email; when it is false AND the
 * window was truncated, the number counts only the hundred files that were sent, and every label
 * that prints one says so.
 *
 * EVERY, not SOME: one payload comes from one server, so a mixture cannot arise — and if it somehow
 * did, numbering half the list one way and half the other is the one outcome with no honest label.
 * `dwGenerationsAreAbsolute` in `data/DwReportHistory.kt` is the same test, deliberately: the two
 * clients must not name the same file two different generations.
 */
export function generationsAreAbsolute(history: DwReportHistory): boolean {
  return history.exports.length > 0 && history.exports.every((row) => (row.generation ?? 0) > 0);
}

/**
 * One file's generation number, or 0 for a file that has none.
 *
 * THE SERVER'S ANSWER FIRST, ALWAYS — see {@link DwExportRecord.generation}. The fallback, this
 * row's position among the dated exports that were actually sent, is correct right up to the moment
 * the hundred-file cap bites and no further.
 *
 * Zero for an export that never recorded a generation time: a generation number is a POSITION IN
 * TIME, so a row without one has none, and "Generation 0" would read as one.
 */
export function generationOf(history: DwReportHistory, exportId: string): number {
  const record = history.exports.find((row) => row.id === exportId);
  if (!record) return 0;
  if ((record.generation ?? 0) > 0) return record.generation as number;
  return inGenerationOrder(history).findIndex((row) => row.id === exportId) + 1;
}

/**
 * Compare two exports by id, in whichever order they were chosen.
 *
 * Returns null when either id is unknown or either export never recorded a generation time — a
 * window with an open end is not a window, and guessing one would put a confident verdict on a
 * comparison that was never made.
 */
export function diffExports(
  history: DwReportHistory,
  firstId: string,
  secondId: string
): ReportDiff | null {
  const ordered = inGenerationOrder(history);
  const a = ordered.find((row) => row.id === firstId);
  const b = ordered.find((row) => row.id === secondId);
  if (!a || !b) return null;

  // The user picks two files; which is EARLIER is a fact about their timestamps, not about the
  // order the two dropdowns happened to be set in.
  const [earlier, later] = (ms(a.generatedAt) ?? 0) <= (ms(b.generatedAt) ?? 0) ? [a, b] : [b, a];
  const windowFrom = earlier.generatedAt as string;
  const windowTo = later.generatedAt as string;

  const byStage = stageChangesBetween(history.entries, windowFrom, windowTo);
  const touchedStageKeys = Object.values(byStage).filter((s) => s.touched).map((s) => s.stageKey);
  const untouchedStageKeys = Object.values(byStage).filter((s) => !s.touched).map((s) => s.stageKey);

  const headerWritten = ms(history.workshopUpdatedAt);
  const from = ms(windowFrom) as number;
  const to = ms(windowTo) as number;

  const bothChecksums = Boolean(earlier.checksumSha256 && later.checksumSha256);

  return {
    earlier,
    later,
    // The server's numbers where the server sent them; this window's positions only where it did
    // not. `diffExports` is the other place a generation number is printed, and the two must agree
    // — see {@link generationOf}.
    earlierGeneration: generationOf(history, earlier.id),
    laterGeneration: generationOf(history, later.id),
    windowFrom,
    windowTo,
    byStage,
    touchedStageKeys,
    untouchedStageKeys,
    headerRowWritten: headerWritten === null ? null : headerWritten > from && headerWritten <= to,
    identicalFile: bothChecksums && earlier.checksumSha256 === later.checksumSha256,
    checksumComparable: bothChecksums,
    templateChanged: earlier.templateId !== later.templateId,
    formatDiffers: earlier.format !== later.format,
    schemaVersionChanged:
      Boolean(earlier.schemaVersion && later.schemaVersion) &&
      earlier.schemaVersion !== later.schemaVersion,
    sizeDelta:
      earlier.fileSizeBytes !== null && later.fileSizeBytes !== null
        ? later.fileSizeBytes - earlier.fileSizeBytes
        : null,
    pageDelta:
      earlier.pageCount !== null && later.pageCount !== null
        ? later.pageCount - earlier.pageCount
        : null,
    deviceClockInvolved: earlier.generatedOnDevice || later.generatedOnDevice,
    timelineComplete: !history.entriesTruncated
  };
}

/**
 * Which stages have been written to since one export was generated — "the file you sent is out of
 * date".
 *
 * Measured against the SERVER's clock, which the history payload carries for exactly this reason:
 * a field laptop an hour behind would otherwise report an hour of edits as not having happened.
 */
export function stagesTouchedSince(history: DwReportHistory, exportId: string): string[] {
  const record = history.exports.find((row) => row.id === exportId);
  if (!record?.generatedAt) return [];
  const byStage = stageChangesBetween(history.entries, record.generatedAt, history.serverTime);
  return Object.values(byStage).filter((stage) => stage.touched).map((stage) => stage.stageKey);
}

/**
 * Exports sharing a checksum with this one — the same bytes recorded more than once.
 *
 * Worth surfacing rather than leaving to a reader to spot in sixty-four hex characters: it is how a
 * designer discovers that the "revised" copy they sent was the same file as last time, which is a
 * mistake nothing else in the system would catch.
 */
export function sameFileAs(history: DwReportHistory, exportId: string): DwExportRecord[] {
  const record = history.exports.find((row) => row.id === exportId);
  if (!record?.checksumSha256) return [];
  return history.exports.filter(
    (row) => row.id !== record.id && row.checksumSha256 === record.checksumSha256
  );
}
