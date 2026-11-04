export type DwExportRecord = {
  id: string;
  generation?: number;
  format: string;
  templateId: string;
  fileName: string;
  fileSizeBytes: number | null;
  pageCount: number | null;
  checksumSha256: string | null;
  generatedOnDevice: boolean;
  schemaVersion: string | null;
  warnings: string | null;
  generatedAt: string | null;
  generatedById: string | null;
  generatedByName: string | null;
};

export type DwEntryTimestamp = {
  id: string;
  stageKey: string;
  entityKey: string;
  ordinal: number;
  createdAt: string | null;
  updatedAt: string | null;
  deletedAt: string | null;
};

export type DwReportHistory = {
  workshopId: string;
  workshopUpdatedAt: string | null;
  serverTime: string;
  completeness: Record<string, { percent: number; requiredFilled: number; requiredTotal: number }>;
  exports: DwExportRecord[];
  exportsTruncated: boolean;
  entries: DwEntryTimestamp[];
  entriesTruncated: boolean;
};

export type StageChange = {
  stageKey: string;
  rowsAdded: number;
  rowsRewritten: number;
  rowsRemoved: number;
  rowsTransient: number;
  touched: boolean;
  currentReflectsBoth: boolean;
};

export type ReportDiff = {
  earlier: DwExportRecord;
  later: DwExportRecord;
  earlierGeneration: number;
  laterGeneration: number;
  windowFrom: string;
  windowTo: string;
  byStage: Record<string, StageChange>;
  touchedStageKeys: string[];
  untouchedStageKeys: string[];
  headerRowWritten: boolean | null;
  identicalFile: boolean;
  checksumComparable: boolean;
  templateChanged: boolean;
  formatDiffers: boolean;
  schemaVersionChanged: boolean;
  sizeDelta: number | null;
  pageDelta: number | null;
  deviceClockInvolved: boolean;
  timelineComplete: boolean;
};

function ms(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const value = Date.parse(iso);
  return Number.isFinite(value) ? value : null;
}

function presentAt(row: DwEntryTimestamp, at: number): boolean {
  const created = ms(row.createdAt);
  if (created === null || created > at) return false;
  const deleted = ms(row.deletedAt);
  return deleted === null || deleted > at;
}

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

    if (written !== null && written > from) change.currentReflectsBoth = false;
  }

  for (const change of Object.values(byStage)) {
    change.touched = change.rowsAdded > 0 || change.rowsRewritten > 0 || change.rowsRemoved > 0;
  }
  return byStage;
}

export function inGenerationOrder(history: DwReportHistory): DwExportRecord[] {
  return history.exports
    .filter((row) => ms(row.generatedAt) !== null)
    .sort((a, b) => (ms(a.generatedAt) ?? 0) - (ms(b.generatedAt) ?? 0));
}

export function generationsAreAbsolute(history: DwReportHistory): boolean {
  return history.exports.length > 0 && history.exports.every((row) => (row.generation ?? 0) > 0);
}

export function generationOf(history: DwReportHistory, exportId: string): number {
  const record = history.exports.find((row) => row.id === exportId);
  if (!record) return 0;
  if ((record.generation ?? 0) > 0) return record.generation as number;
  return inGenerationOrder(history).findIndex((row) => row.id === exportId) + 1;
}

export function diffExports(
  history: DwReportHistory,
  firstId: string,
  secondId: string
): ReportDiff | null {
  const ordered = inGenerationOrder(history);
  const a = ordered.find((row) => row.id === firstId);
  const b = ordered.find((row) => row.id === secondId);
  if (!a || !b) return null;

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

export function stagesTouchedSince(history: DwReportHistory, exportId: string): string[] {
  const record = history.exports.find((row) => row.id === exportId);
  if (!record?.generatedAt) return [];
  const byStage = stageChangesBetween(history.entries, record.generatedAt, history.serverTime);
  return Object.values(byStage).filter((stage) => stage.touched).map((stage) => stage.stageKey);
}

export function sameFileAs(history: DwReportHistory, exportId: string): DwExportRecord[] {
  const record = history.exports.find((row) => row.id === exportId);
  if (!record?.checksumSha256) return [];
  return history.exports.filter(
    (row) => row.id !== record.id && row.checksumSha256 === record.checksumSha256
  );
}
