import {
  DW_DEFAULT_MAX_ITEMS,
  type DwEntryData,
  type DwField,
  type DwRegistry,
  type DwRow,
  type DwStageData,
  type DwValue
} from "@/lib/designWorkshops";

export const OUTSIDE_GRACE_DAYS = 2;

export const DEFAULT_TIMEZONE = "Asia/Kolkata";

export const MAX_PROPOSALS = 3;

const DATE_RANGES: ReadonlyArray<readonly [start: string, end: string]> = [
  ["startDate", "endDate"],
  ["startDate", "completedDate"],
  ["surveyStartDate", "surveyEndDate"]
];

export type IntakePhoto = {
  fileName: string;
  takenAt: string | null;
  takenAtOffset?: string | null;
};

export type WorkshopAnchor = {
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  entityKey: string;
  entityTitle: string;
  rowKey: string | null;
  rowLabel: string | null;
  fieldLabel: string;
  kind: "DAY" | "SPAN";
  start: string;
  end: string;
};

export type ProposalBasis =
  | "ON_DAY"
  | "IN_SPAN"
  | "NEAREST";

export type StageProposal = {
  anchor: WorkshopAnchor;
  basis: ProposalBasis;
  daysAway: number;
  evidence: string;
};

export type PhotoStamp = {
  date: string;
  minutes: number;
  raw: string;
  shiftedFrom: string | null;
};

export type PhotoIntakeRow = {
  fileName: string;
  stamp: PhotoStamp | null;
  refusal: string | null;
  proposals: StageProposal[];
};

const EXIF_STAMP = /^(\d{4})[:-](\d{2})[:-](\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/;

const EXIF_OFFSET = /^(?:(Z)|([+-])(\d{2}):?(\d{2}))$/;

export function parseExifStamp(raw: string | null | undefined): {
  y: number;
  mo: number;
  d: number;
  hh: number;
  mm: number;
  ss: number;
} | null {
  if (!raw) return null;
  const match = EXIF_STAMP.exec(raw.trim());
  if (!match) return null;
  const [, y, mo, d, hh, mm, ss] = match;
  const parts = {
    y: Number(y),
    mo: Number(mo),
    d: Number(d),
    hh: Number(hh),
    mm: Number(mm),
    ss: ss ? Number(ss) : 0
  };
  if (parts.y < 1900 || parts.mo < 1 || parts.mo > 12 || parts.d < 1) return null;
  if (parts.d > daysInMonth(parts.y, parts.mo)) return null;
  if (parts.hh > 23 || parts.mm > 59 || parts.ss > 59) return null;
  return parts;
}

function daysInMonth(year: number, month: number): number {
  if (month === 2) return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0 ? 29 : 28;
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

function parseExifOffset(raw: string | null | undefined): number | null {
  if (!raw) return null;
  const match = EXIF_OFFSET.exec(raw.trim());
  if (!match) return null;
  if (match[1]) return 0;
  const sign = match[2] === "-" ? -1 : 1;
  return sign * (Number(match[3]) * 60 + Number(match[4]));
}

function zoneOffsetMinutesAt(timeZone: string, utcMs: number): number | null {
  try {
    const parts = new Intl.DateTimeFormat("en-US", { timeZone, timeZoneName: "longOffset" }).formatToParts(
      new Date(utcMs)
    );
    const name = parts.find((part) => part.type === "timeZoneName")?.value;
    if (!name) return null;
    if (name === "GMT" || name === "UTC") return 0;
    const match = /^(?:GMT|UTC)([+-])(\d{1,2})(?::(\d{2}))?$/.exec(name);
    if (!match) return null;
    const sign = match[1] === "-" ? -1 : 1;
    return sign * (Number(match[2]) * 60 + Number(match[3] ?? 0));
  } catch {
    return null;
  }
}

export function resolveStamp(photo: IntakePhoto, timeZone: string): PhotoStamp | null {
  const parts = parseExifStamp(photo.takenAt);
  if (!parts) return null;

  const raw = `${pad4(parts.y)}-${pad2(parts.mo)}-${pad2(parts.d)} ${pad2(parts.hh)}:${pad2(parts.mm)}`;
  const plain: PhotoStamp = {
    date: `${pad4(parts.y)}-${pad2(parts.mo)}-${pad2(parts.d)}`,
    minutes: parts.hh * 60 + parts.mm,
    raw,
    shiftedFrom: null
  };

  const declared = parseExifOffset(photo.takenAtOffset);
  if (declared === null) return plain;

  const utcMs = Date.UTC(parts.y, parts.mo - 1, parts.d, parts.hh, parts.mm, parts.ss) - declared * 60_000;
  const local = zoneOffsetMinutesAt(timeZone, utcMs);
  if (local === null || local === declared) return plain;

  const shifted = new Date(utcMs + local * 60_000);
  return {
    date: `${pad4(shifted.getUTCFullYear())}-${pad2(shifted.getUTCMonth() + 1)}-${pad2(shifted.getUTCDate())}`,
    minutes: shifted.getUTCHours() * 60 + shifted.getUTCMinutes(),
    raw,
    shiftedFrom: photo.takenAtOffset ?? null
  };
}

const pad2 = (value: number) => String(value).padStart(2, "0");
const pad4 = (value: number) => String(value).padStart(4, "0");

export function photoDayIndex(date: string | null | undefined): number | null {
  if (!date) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(date.trim());
  if (!match) return null;
  const [, y, mo, d] = match;
  const year = Number(y);
  const month = Number(mo);
  const day = Number(d);
  if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) return null;
  return Math.round(Date.UTC(year, month - 1, day) / 86_400_000);
}

export function formatAnchorDate(date: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(date);
  if (!match) return date;
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  return `${Number(match[3])} ${months[Number(match[2]) - 1] ?? match[2]} ${match[1]}`;
}

export function formatStamp(stamp: PhotoStamp): string {
  return `${formatAnchorDate(stamp.date)}, ${pad2(Math.floor(stamp.minutes / 60))}:${pad2(stamp.minutes % 60)}`;
}

function formatRange(anchor: WorkshopAnchor): string {
  if (anchor.start === anchor.end) return formatAnchorDate(anchor.start);
  return `${formatAnchorDate(anchor.start)} – ${formatAnchorDate(anchor.end)}`;
}

function dateValue(row: DwEntryData | undefined, key: string): string | null {
  const raw = row?.[key];
  if (typeof raw !== "string") return null;
  const trimmed = raw.trim().slice(0, 10);
  return photoDayIndex(trimmed) === null ? null : trimmed;
}

function rowLabelOf(labelField: string, row: DwRow): string | null {
  if (!labelField) return null;
  const raw = row[labelField];
  return typeof raw === "string" && raw.trim() ? raw.trim() : null;
}

export function buildAnchors(registry: DwRegistry, stages: Record<string, DwStageData | undefined>): WorkshopAnchor[] {
  const anchors: WorkshopAnchor[] = [];

  for (const stage of registry.stages) {
    const data = stages[stage.key];
    if (!data) continue;

    for (const entity of stage.entities) {
      const dateKeys = entity.fields.filter((field) => field.type === "DATE").map((field) => field.key);
      if (!dateKeys.length) continue;
      const labelOf = (key: string) => entity.fields.find((field) => field.key === key)?.label ?? key;

      const rows: Array<{ row: DwEntryData; key: string | null; label: string | null }> =
        entity.cardinality === "SINGLETON"
          ? [{ row: data.singleton ?? {}, key: null, label: null }]
          : (data.collections?.[entity.key] ?? []).map((row) => ({
              row,
              key: row._clientKey ?? row._entryId ?? null,
              label: rowLabelOf(entity.labelField, row)
            }));

      for (const { row, key, label } of rows) {
        const consumed = new Set<string>();

        for (const [startKey, endKey] of DATE_RANGES) {
          if (!dateKeys.includes(startKey) || !dateKeys.includes(endKey)) continue;
          const start = dateValue(row, startKey);
          const end = dateValue(row, endKey);
          if (!start || !end) continue;
          consumed.add(startKey);
          consumed.add(endKey);
          const [from, to] = photoDayIndex(start)! <= photoDayIndex(end)! ? [start, end] : [end, start];
          anchors.push({
            stageKey: stage.key,
            stageNumber: stage.number,
            stageTitle: stage.title,
            entityKey: entity.key,
            entityTitle: entity.title,
            rowKey: key,
            rowLabel: label,
            fieldLabel: `${labelOf(startKey)} – ${labelOf(endKey)}`,
            kind: "SPAN",
            start: from,
            end: to
          });
        }

        for (const dateKey of dateKeys) {
          if (consumed.has(dateKey)) continue;
          const day = dateValue(row, dateKey);
          if (!day) continue;
          anchors.push({
            stageKey: stage.key,
            stageNumber: stage.number,
            stageTitle: stage.title,
            entityKey: entity.key,
            entityTitle: entity.title,
            rowKey: key,
            rowLabel: label,
            fieldLabel: labelOf(dateKey),
            kind: "DAY",
            start: day,
            end: day
          });
        }
      }
    }
  }

  return anchors;
}

export function photoTargets(
  registry: DwRegistry,
  stageKey: string,
  entityKey: string
): Array<{ fieldKey: string; fieldLabel: string; multiple: boolean; maxItems?: number }> {
  const stage = registry.stages.find((candidate) => candidate.key === stageKey);
  const entity = stage?.entities.find((candidate) => candidate.key === entityKey);
  return (entity?.fields ?? [])
    .filter((field) => field.type === "IMAGE" || field.type === "IMAGE_LIST")
    .map((field) => ({
      fieldKey: field.key,
      fieldLabel: field.label,
      multiple: field.type === "IMAGE_LIST",
      maxItems: field.maxItems
    }));
}

function distanceTo(anchor: WorkshopAnchor, day: number): number {
  const from = photoDayIndex(anchor.start);
  const to = photoDayIndex(anchor.end);
  if (from === null || to === null) return Number.MAX_SAFE_INTEGER;
  if (day < from) return from - day;
  if (day > to) return day - to;
  return 0;
}

function widthOf(anchor: WorkshopAnchor): number {
  const from = photoDayIndex(anchor.start);
  const to = photoDayIndex(anchor.end);
  if (from === null || to === null) return Number.MAX_SAFE_INTEGER;
  return to - from + 1;
}

function compareCandidates(a: WorkshopAnchor, b: WorkshopAnchor, day: number): number {
  const distanceA = distanceTo(a, day);
  const distanceB = distanceTo(b, day);
  if (distanceA !== distanceB) return distanceA - distanceB;
  const widthA = widthOf(a);
  const widthB = widthOf(b);
  if (widthA !== widthB) return widthA - widthB;
  if (a.stageNumber !== b.stageNumber) return a.stageNumber - b.stageNumber;
  if (a.entityKey !== b.entityKey) return a.entityKey < b.entityKey ? -1 : 1;
  return (a.rowKey ?? "") < (b.rowKey ?? "") ? -1 : (a.rowKey ?? "") > (b.rowKey ?? "") ? 1 : 0;
}

function evidenceFor(stamp: PhotoStamp, anchor: WorkshopAnchor, basis: ProposalBasis, daysAway: number): string {
  const where = anchor.rowLabel
    ? `stage ${anchor.stageNumber}'s ${anchor.entityTitle} row “${anchor.rowLabel}”`
    : `stage ${anchor.stageNumber}'s ${anchor.entityTitle}`;
  const shift = stamp.shiftedFrom
    ? ` The camera recorded ${stamp.raw} at ${stamp.shiftedFrom}; that is the workshop-local time above.`
    : "";

  if (basis === "ON_DAY") {
    return `Taken ${formatStamp(stamp)} — ${where}, ${anchor.fieldLabel.toLowerCase()} ${formatAnchorDate(anchor.start)}.${shift}`;
  }
  if (basis === "IN_SPAN") {
    return `Taken ${formatStamp(stamp)} — inside ${where}, ${anchor.fieldLabel.toLowerCase()} ${formatRange(anchor)}.${shift}`;
  }
  const side = photoDayIndex(stamp.date)! < photoDayIndex(anchor.start)! ? "before" : "after";
  const days = `${daysAway} day${daysAway === 1 ? "" : "s"}`;
  return (
    `Taken ${formatStamp(stamp)} — nothing recorded covers that date. ` +
    `It is ${days} ${side} ${where}, ${anchor.fieldLabel.toLowerCase()} ${formatRange(anchor)}.${shift}`
  );
}

export function rankProposals(stamp: PhotoStamp, anchors: WorkshopAnchor[]): StageProposal[] {
  const day = photoDayIndex(stamp.date);
  if (day === null) return [];
  const usable = anchors.filter((anchor) => distanceTo(anchor, day) !== Number.MAX_SAFE_INTEGER);
  if (!usable.length) return [];

  const ordered = [...usable].sort((a, b) => compareCandidates(a, b, day));
  const nearest = distanceTo(ordered[0], day);
  if (nearest > OUTSIDE_GRACE_DAYS) return [];

  return ordered.slice(0, MAX_PROPOSALS).map((anchor) => {
    const daysAway = distanceTo(anchor, day);
    const basis: ProposalBasis = daysAway > 0 ? "NEAREST" : anchor.kind === "DAY" ? "ON_DAY" : "IN_SPAN";
    return { anchor, basis, daysAway, evidence: evidenceFor(stamp, anchor, basis, daysAway) };
  });
}

export function intakePhotos(
  photos: IntakePhoto[],
  anchors: WorkshopAnchor[],
  options?: { timeZone?: string }
): PhotoIntakeRow[] {
  const timeZone = options?.timeZone?.trim() || DEFAULT_TIMEZONE;
  const dated = anchors.filter((anchor) => photoDayIndex(anchor.start) !== null && photoDayIndex(anchor.end) !== null);
  const earliest = dated.reduce<number | null>(
    (low, anchor) => (low === null ? photoDayIndex(anchor.start)! : Math.min(low, photoDayIndex(anchor.start)!)),
    null
  );
  const latest = dated.reduce<number | null>(
    (high, anchor) => (high === null ? photoDayIndex(anchor.end)! : Math.max(high, photoDayIndex(anchor.end)!)),
    null
  );

  return photos.map((photo) => {
    const stamp = resolveStamp(photo, timeZone);
    if (!stamp) {
      return {
        fileName: photo.fileName,
        stamp: null,
        refusal:
          "No capture date in this file. Screenshots, scanned pages and anything sent through a messaging app " +
          "arrive with it stripped — choose the stage yourself.",
        proposals: []
      };
    }

    const proposals = rankProposals(stamp, dated);
    if (proposals.length) return { fileName: photo.fileName, stamp, refusal: null, proposals };

    if (earliest === null || latest === null) {
      return {
        fileName: photo.fileName,
        stamp,
        refusal:
          `Taken ${formatStamp(stamp)}, but this workshop has no dates recorded yet — fill in the start and end ` +
          "dates on stage 1 and the daily logs, and these photographs will place themselves.",
        proposals: []
      };
    }

    const day = photoDayIndex(stamp.date)!;
    const gap = day < earliest ? earliest - day : day - latest;
    const side = day < earliest ? "before the first" : "after the last";
    const edge = day < earliest ? earliest : latest;
    return {
      fileName: photo.fileName,
      stamp,
      refusal:
        `Taken ${formatStamp(stamp)} — ${gap.toLocaleString("en-IN")} day${gap === 1 ? "" : "s"} ${side} date ` +
        `recorded for this workshop (${formatAnchorDate(indexToDate(edge))}). No stage is proposed; if the camera's ` +
        "clock was wrong, choose the stage yourself.",
      proposals: []
    };
  });
}

function indexToDate(index: number): string {
  const at = new Date(index * 86_400_000);
  return `${pad4(at.getUTCFullYear())}-${pad2(at.getUTCMonth() + 1)}-${pad2(at.getUTCDate())}`;
}

export function intakeSummary(rows: PhotoIntakeRow[]): { total: number; proposed: number; manual: number } {
  const proposed = rows.filter((row) => row.proposals.length > 0).length;
  return { total: rows.length, proposed, manual: rows.length - proposed };
}

export function effectiveMaxItems(declared: DwField["maxItems"]): number {
  return typeof declared === "number" && declared > 0 ? declared : DW_DEFAULT_MAX_ITEMS;
}

function heldRefs(value: DwValue | undefined): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

export function appendMediaRef(
  value: DwValue | undefined,
  ref: string,
  multiple: boolean,
  maxItems?: number
): DwValue {
  if (!multiple) return ref;
  const existing = heldRefs(value);
  if (existing.includes(ref)) return existing;
  if (existing.length >= effectiveMaxItems(maxItems)) return existing;
  return [...existing, ref];
}

export function mediaRefRoom(value: DwValue | undefined, multiple: boolean, maxItems?: number): number {
  if (!multiple) return 1;
  return Math.max(0, effectiveMaxItems(maxItems) - heldRefs(value).length);
}
