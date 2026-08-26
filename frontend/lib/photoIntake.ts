/**
 * Bulk photo intake — which stage does each photograph belong to, judged from its EXIF clock.
 *
 * THE PROBLEM THIS EXISTS FOR. A designer shoots two hundred photographs on a real camera over a
 * thirty-day workshop and then attaches each one by hand, stage by stage. It is the single most
 * tedious hour of the job, and it is entirely mechanical: the workshop's dates are already recorded
 * — stage 1's window, stage 3's schedule days, stage 13's per-prototype logs, stage 19's closing —
 * and EXIF already survives the upload pipeline untouched (`docs/MEDIA_PIPELINE.md` §5 refuses
 * re-encoding precisely so that it does). So the answer is computable from data the device already
 * holds, which is what makes it computable in the village where the fieldwork happened.
 *
 * PURE, AND THEREFORE PORTABLE. No React, no fetch, no database, no `File`. Dates and strings in,
 * ranked proposals out — the shape of `market_analysis.py` / `marketAnalysis.ts`, and for the same
 * reason: it runs in the browser, it will run unchanged on the handset when the Kotlin port is
 * written, and it can be tested by value rather than by driving a screen. Reading EXIF off a `File`
 * is a browser concern and lives in `lib/media.ts` (`readCaptureStamp`); everything below takes the
 * string that reader produced.
 *
 * ONE VALUE crosses that line, and it is deliberate: {@link DW_DEFAULT_MAX_ITEMS}, the ceiling the
 * server enforces on a multi-valued field that declares none. Everything else this file imports from
 * `lib/designWorkshops` is a type and vanishes at build. That constant is imported rather than
 * re-typed as `200` here because a second copy of the number is a second thing to forget when the
 * server changes it — see {@link effectiveMaxItems}, and the same rule stated at
 * docs/DESIGN_WORKSHOP.md:229-232. The cost is that this module's import graph now reaches
 * `lib/api` transitively; nothing in it is CALLED, no request is made at import, and the Kotlin port
 * reads the same figure off `DW_DEFAULT_MAX_ITEMS` in `StageSchema.kt`.
 *
 * IT PROPOSES. IT NEVER COMMITS. Nothing here attaches a photograph to anything. It returns a
 * ranking and the sentence that justifies it, and a human presses the button — the same rule, for
 * the same reason, as the identity-card OCR endpoint (`scan_identity_card` in
 * `backend/app/api/routes/design_workshops.py`): the one person who can tell whether this is really
 * the loom shot from the fourteenth is standing there holding the camera, and this module's whole
 * job is to save them the sorting, not the deciding. Hence {@link StageProposal.evidence}: a
 * proposal a designer cannot check is a proposal they will rubber-stamp, and a wrong one has to be
 * OBVIOUS rather than merely plausible.
 *
 * ── THE TIMEZONE RULE, which is the whole correctness argument ────────────────────────────────
 *
 * EXIF `DateTimeOriginal` is a NAIVE WALL CLOCK: "2026:02:14 19:40:12", with no zone attached. It is
 * what the camera's clock read. The workshop's dates are CALENDAR DATES — "2026-02-14" — with no
 * time and no zone either. Both sides are zoneless, so the correct comparison is calendar date
 * against calendar date, and this module NEVER BUILDS A `Date` FROM THE EXIF STRING to make it.
 *
 * That restraint is the entire defence against the off-by-5.5-hours bug. `new Date("2026-02-14
 * 19:40:12")` is parsed in the *browser's* zone; formatting it back in Asia/Kolkata on a laptop set
 * to UTC moves it to 15 Feb 01:10 and files every evening photograph one day late. Parsing as UTC
 * and rendering local does the same thing in the other direction. There is no round trip here to get
 * wrong: {@link parseExifStamp} pulls six integers out of the string and {@link photoDayIndex} does
 * arithmetic on the calendar triple.
 *
 * WHAT IS THEREFORE ASSUMED, and it is stated on screen rather than buried here: **the camera's
 * clock was set to the workshop's local time** (`recordedTimezone`, which defaults to Asia/Kolkata
 * throughout this repository). Under that assumption the wall clock's calendar date IS the
 * workshop's calendar date and no conversion is required or performed.
 *
 * WHEN THE CAMERA KNOWS BETTER, WE STOP ASSUMING. EXIF 2.31 added `OffsetTimeOriginal` ("+05:30"),
 * and a camera that writes one has told us its zone outright. Then there is a real instant, and
 * {@link resolveStamp} shifts it into the workshop's zone — so a camera left on UTC no longer files
 * every photograph taken after 18:30 on the following day. The shift is reported in the evidence,
 * because a designer who sees a date they did not expect deserves to know the clock was moved.
 *
 * WHAT IS NOT DONE, deliberately: a camera whose clock is simply WRONG — never set, or still on last
 * year — cannot be detected from one photograph, and this module does not try. It refuses instead
 * (see {@link OUTSIDE_GRACE_DAYS}), which puts the distance on screen where the pattern is obvious
 * across a batch. Inferring a constant clock drift from the batch as a whole is a real and better
 * answer; it is not attempted here rather than attempted badly.
 */

import {
  DW_DEFAULT_MAX_ITEMS,
  type DwEntryData,
  type DwField,
  type DwRegistry,
  type DwRow,
  type DwStageData,
  type DwValue
} from "@/lib/designWorkshops";

/* ────────────────────────────────────────────────────────────────────────────
 * Thresholds
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * How far outside every recorded date a photograph may fall and still be proposed at all.
 *
 * A photograph a day or two outside the window is routine and real: the designer travelled in the
 * evening before, the last prototype was finished on an afternoon nobody logged, the closing ran
 * over. Proposing the nearest stage there is useful and the evidence says plainly that no recorded
 * date covers it.
 *
 * Beyond that the far likelier explanation is not "an undated workshop day" but "a camera clock that
 * was never set" — and a confident stage proposal derived from a clock that is wrong by months is
 * exactly the plausible-but-wrong answer this module must not produce. So past this distance it
 * REFUSES: the photograph is still listed, still assignable by hand, and the refusal names the gap
 * in days, which is how a batch shot on a camera stuck in 2019 announces itself at a glance.
 *
 * This is the same discipline as `MIN_SAMPLE_FOR_QUANTILES` in `market_analysis.py`: withholding a
 * figure the data cannot support is not a weaker answer than producing one, it is a better outcome.
 */
export const OUTSIDE_GRACE_DAYS = 2;

/** The default this repository uses everywhere a timezone is unstated (`app_settings.DEFAULT_TIMEZONE`). */
export const DEFAULT_TIMEZONE = "Asia/Kolkata";

/**
 * How many proposals one photograph offers.
 *
 * Three, not one: the interesting case is a photograph that falls between two logged days, where the
 * honest answer is "it is nearer this one, but here is the other". A single proposal would hide the
 * ambiguity that is the reason to look. More than three is a menu nobody reads for two hundred rows.
 */
export const MAX_PROPOSALS = 3;

/**
 * Date-field pairs that describe a RANGE rather than two separate days.
 *
 * This is the one fact about the dated fields that the registry does not itself declare. Every other
 * property of every anchor below — which stage, which entity, which row, the field's label — is read
 * from `stage_definitions.py` through the served registry, so a twenty-third stage with a dated
 * collection produces photo proposals with no change here. But the registry says only that
 * `startDate` and `endDate` are two DATE fields on one entity; that they bound a window is knowledge
 * that has to live somewhere, and a short explicit list is honest about being knowledge rather than
 * pretending to derive it from a naming convention that would also pair `sanctionOrderDate` with
 * something one day.
 *
 * A DATE field not named here becomes a single-day anchor, which is the safe default: a lone day
 * proposes only its own date, where a wrongly-inferred range would silently swallow a fortnight.
 */
const DATE_RANGES: ReadonlyArray<readonly [start: string, end: string]> = [
  ["startDate", "endDate"],
  ["startDate", "completedDate"],
  ["surveyStartDate", "surveyEndDate"]
];

/* ────────────────────────────────────────────────────────────────────────────
 * Types
 * ──────────────────────────────────────────────────────────────────────────── */

/** One photograph as the intake sees it: a name, and whatever clock the file carried. */
export type IntakePhoto = {
  fileName: string;
  /**
   * EXIF `DateTimeOriginal`, VERBATIM ("2026:02:14 10:22:33"), or null when the file carries none.
   *
   * Null is normal and must stay expressible. Screenshots, flatbed scans of a sanction order, and
   * anything that has been through WhatsApp all arrive with the EXIF stripped, and those are real
   * workshop photographs. The one thing that may never happen to them is being silently dropped or
   * silently guessed — see {@link PhotoIntakeRow.refusal}.
   */
  takenAt: string | null;
  /** EXIF `OffsetTimeOriginal` ("+05:30"), on the minority of cameras that write one. */
  takenAtOffset?: string | null;
};

/**
 * A dated thing in the workshop that a photograph could belong to.
 *
 * Built by {@link buildAnchors} from the registry plus the workshop's saved stage data. `start` and
 * `end` are inclusive `yyyy-mm-dd` calendar dates and are equal for a single day.
 */
export type WorkshopAnchor = {
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  entityKey: string;
  /** The entity's title as the registry words it ("Stage logs"), for the evidence sentence. */
  entityTitle: string;
  /**
   * Which row of a collection this came from — `_clientKey` or `_entryId`, whichever the row has.
   * Null for a singleton. A confirmation carries it so a photograph can join the right row.
   */
  rowKey: string | null;
  /** The row's own label ("Loom warping"), when the entity declares a label field. */
  rowLabel: string | null;
  /** The date field(s) this was read from, as the registry labels them ("Date", "Started – Completed"). */
  fieldLabel: string;
  kind: "DAY" | "SPAN";
  /** Inclusive, `yyyy-mm-dd`. Equal to `end` when `kind` is DAY. */
  start: string;
  end: string;
};

/** Why a proposal was made — the shape of the match, not its strength. */
export type ProposalBasis =
  /** The photograph's calendar date IS this anchor's single recorded day. */
  | "ON_DAY"
  /** The photograph's calendar date falls inside this anchor's recorded window. */
  | "IN_SPAN"
  /** Nothing recorded covers that date; this is the nearest dated thing, and it is close. */
  | "NEAREST";

export type StageProposal = {
  anchor: WorkshopAnchor;
  basis: ProposalBasis;
  /** Whole days from the photograph to the nearest edge of the anchor. 0 when it falls inside. */
  daysAway: number;
  /**
   * The sentence shown beside the proposal, e.g. *"Taken 14 Feb 2026, 10:22 — stage 13's Stage logs
   * row dated 14 Feb 2026."*
   *
   * Written HERE, not in the component, for the reason every cross-client string in this repository
   * is: the web, the handset and any later report of what was imported have to justify one decision
   * in one wording, or two clients describe the same proposal differently and a designer comparing
   * them has to work out whether they disagree.
   */
  evidence: string;
};

/** The parsed wall clock, reduced to the two things the ranking needs plus what the evidence prints. */
export type PhotoStamp = {
  /** Calendar date in the workshop's zone, `yyyy-mm-dd`. */
  date: string;
  /** Minutes since midnight, for display and for ordering photographs within a day. */
  minutes: number;
  /** The clock EXIF actually wrote, kept verbatim so the evidence can show the unshifted reading. */
  raw: string;
  /**
   * Set when the camera declared its own zone and the clock was moved into the workshop's.
   * Null on the ordinary path, where the wall clock is taken at face value.
   */
  shiftedFrom: string | null;
};

/** One row of the intake table: a photograph, what was read off it, and what is proposed. */
export type PhotoIntakeRow = {
  fileName: string;
  /** Null when the file carried no usable EXIF date. */
  stamp: PhotoStamp | null;
  /**
   * Why there is no proposal, in a sentence, when there is none — and null when there are proposals.
   *
   * Never empty and never a shrug: either the file carried no date, or it carried one that nothing
   * recorded comes near. Both are ordinary, both leave the photograph on screen for manual
   * assignment, and neither is allowed to look like a bug.
   */
  refusal: string | null;
  /** Best first, capped at {@link MAX_PROPOSALS}. Empty exactly when `refusal` is set. */
  proposals: StageProposal[];
};

/* ────────────────────────────────────────────────────────────────────────────
 * Reading the clock
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * EXIF's own date format, and the ISO spelling as a courtesy.
 *
 * EXIF writes colons in the date part ("2026:02:14 10:22:33") — that is the specified form, not a
 * typo to be normalised away. Some libraries and some phones hand back "2026-02-14T10:22:33"
 * instead, so both separators are accepted. Seconds are optional; a fractional part and a trailing
 * zone are tolerated and ignored, because the zone travels separately in `OffsetTimeOriginal` and a
 * value glued onto the end of this field is not one this module is willing to trust.
 */
const EXIF_STAMP = /^(\d{4})[:-](\d{2})[:-](\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/;

/** `OffsetTimeOriginal`: "+05:30", "-08:00", "Z". */
const EXIF_OFFSET = /^(?:(Z)|([+-])(\d{2}):?(\d{2}))$/;

/**
 * Pull the six integers out of an EXIF timestamp, or return null.
 *
 * Returns null — not a guess and not today — for every unusable reading, and the unusable readings
 * are not exotic: a camera with no clock battery writes "0000:00:00 00:00:00", and a stripped tag
 * comes back as an empty string. Both must land in {@link PhotoIntakeRow.refusal} beside the
 * genuinely date-less files, because "the camera said midnight on the year zero" is not a date and
 * anything that treats it as one files a photograph two thousand years before the workshop.
 */
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
  // The zero sentinel and every out-of-range component. `mo`/`d` are checked against the real month
  // length rather than a flat 31 so that "2026:02:30" — which a corrupt tag can absolutely produce —
  // is refused rather than silently rolling forward into March.
  if (parts.y < 1900 || parts.mo < 1 || parts.mo > 12 || parts.d < 1) return null;
  if (parts.d > daysInMonth(parts.y, parts.mo)) return null;
  if (parts.hh > 23 || parts.mm > 59 || parts.ss > 59) return null;
  return parts;
}

/** Days in a Gregorian month. Spelled out because the leap rule decides whether 29 Feb is refused. */
function daysInMonth(year: number, month: number): number {
  if (month === 2) return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0 ? 29 : 28;
  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

/** Minutes east of UTC declared by an `OffsetTimeOriginal`, or null when there isn't one. */
function parseExifOffset(raw: string | null | undefined): number | null {
  if (!raw) return null;
  const match = EXIF_OFFSET.exec(raw.trim());
  if (!match) return null;
  if (match[1]) return 0;
  const sign = match[2] === "-" ? -1 : 1;
  return sign * (Number(match[3]) * 60 + Number(match[4]));
}

/**
 * What a named zone's UTC offset was at a given instant, in minutes east.
 *
 * `Intl` carries the zone rules the platform already ships, so this costs no dependency and no
 * bundled tz database — which matters, because a bulk import has to work on a laptop that has been
 * offline for a week. It is asked about an INSTANT, never about a wall clock, so there is no
 * ambiguity to resolve: this is only ever reached once `OffsetTimeOriginal` has given us a real
 * instant to convert.
 *
 * Null when the platform does not know the zone. The caller then declines to shift and says so,
 * which is right: silently falling back to the browser's own zone is how a designer in Delhi and a
 * reviewer in London get different stage assignments from one file.
 */
function zoneOffsetMinutesAt(timeZone: string, utcMs: number): number | null {
  try {
    const parts = new Intl.DateTimeFormat("en-US", { timeZone, timeZoneName: "longOffset" }).formatToParts(
      new Date(utcMs)
    );
    const name = parts.find((part) => part.type === "timeZoneName")?.value;
    if (!name) return null;
    // "GMT" alone is a legitimate answer and means zero; "GMT+05:30" and "GMT-8" are the rest.
    if (name === "GMT" || name === "UTC") return 0;
    const match = /^(?:GMT|UTC)([+-])(\d{1,2})(?::(\d{2}))?$/.exec(name);
    if (!match) return null;
    const sign = match[1] === "-" ? -1 : 1;
    return sign * (Number(match[2]) * 60 + Number(match[3] ?? 0));
  } catch {
    return null;
  }
}

/**
 * Turn one photograph's EXIF reading into a workshop-local calendar date and time.
 *
 * The ordinary path does no arithmetic at all: the camera had no zone to declare, the assumption
 * stated in the file header applies, and the wall clock's date is used as written. The shift below
 * happens only when the camera DID declare a zone and that zone differs from the workshop's — which
 * is the case that would otherwise put every evening photograph on the wrong day.
 */
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

  // The camera told us its zone, so there is a genuine instant here rather than a wall clock. Note
  // that `Date.UTC` is safe in a way `new Date(string)` is not: it takes INTEGERS and is explicitly
  // UTC, so the browser's own zone never enters the calculation.
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

/* ────────────────────────────────────────────────────────────────────────────
 * Calendar arithmetic
 * ──────────────────────────────────────────────────────────────────────────── */

/** `yyyy-mm-dd` as a day number, for subtraction. Null for anything that is not one. */
export function photoDayIndex(date: string | null | undefined): number | null {
  if (!date) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(date.trim());
  if (!match) return null;
  const [, y, mo, d] = match;
  const year = Number(y);
  const month = Number(mo);
  const day = Number(d);
  if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) return null;
  // Integers into an explicitly-UTC constructor, then divided down to whole days. The result is a
  // count of calendar days and carries no time and no zone — which is the only thing that may be
  // compared against a `DATE` field, and the reason this is not `new Date(value).getTime()`.
  return Math.round(Date.UTC(year, month - 1, day) / 86_400_000);
}

/**
 * Print a `yyyy-mm-dd` as "14 Feb 2026", from its integer parts.
 *
 * Deliberately NOT `lib/format.ts`'s `formatDate`, which does `new Date("2026-02-14")` — parsed as
 * UTC midnight and then rendered in the reader's zone, so it prints the 13th for anybody west of
 * Greenwich. That is invisible in most of the app and would be the headline defect here, in the one
 * feature whose entire subject is which day a photograph belongs to.
 */
export function formatAnchorDate(date: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(date);
  if (!match) return date;
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  return `${Number(match[3])} ${months[Number(match[2]) - 1] ?? match[2]} ${match[1]}`;
}

/** "14 Feb 2026, 10:22" — the photograph's own reading, as the evidence opens with it. */
export function formatStamp(stamp: PhotoStamp): string {
  return `${formatAnchorDate(stamp.date)}, ${pad2(Math.floor(stamp.minutes / 60))}:${pad2(stamp.minutes % 60)}`;
}

/** "12–26 Feb 2026" for a window, "14 Feb 2026" for a day. */
function formatRange(anchor: WorkshopAnchor): string {
  if (anchor.start === anchor.end) return formatAnchorDate(anchor.start);
  return `${formatAnchorDate(anchor.start)} – ${formatAnchorDate(anchor.end)}`;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Building the anchors from the registry
 * ──────────────────────────────────────────────────────────────────────────── */

/** A DATE value as stored: `yyyy-mm-dd`, or something that is not one. */
function dateValue(row: DwEntryData | undefined, key: string): string | null {
  const raw = row?.[key];
  if (typeof raw !== "string") return null;
  const trimmed = raw.trim().slice(0, 10);
  return photoDayIndex(trimmed) === null ? null : trimmed;
}

/** The row's own name, when the entity declares a label field and the row filled it in. */
function rowLabelOf(labelField: string, row: DwRow): string | null {
  if (!labelField) return null;
  const raw = row[labelField];
  return typeof raw === "string" && raw.trim() ? raw.trim() : null;
}

/**
 * Every dated thing in this workshop, as anchors a photograph can be matched against.
 *
 * DRIVEN BY THE REGISTRY, not by a list of stages. It walks `registry.stages` for fields of type
 * DATE and reads their values out of the saved stage data, so stage 1's window, stage 3's schedule
 * days, stage 13's prototypes and their per-day logs, and stage 19's closing all become anchors
 * without being named here — and a twenty-third stage with a dated collection becomes one the day it
 * is declared, with no change to this file and none to the handset. That is the same property the
 * stage forms have and it is the reason the registry is served rather than duplicated.
 *
 * `singleton` holds the fields of every SINGLETON entity of the stage flattened into one object (see
 * `splitSingletons` in `lib/designWorkshopStore.ts`), so a singleton's dates are read straight out
 * of it rather than from a per-entity sub-object.
 */
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
              // `_clientKey` first: it is the idempotency key that survives a row being re-created by
              // a replayed save, where `_entryId` is only present once the server has seen the row.
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
          // A window typed backwards is still a window. Ordering the pair rather than discarding it
          // matters because the fix — a designer noticing the dates are the wrong way round — is one
          // they can only make if the photographs did not silently vanish from the proposal in the
          // meantime. Nothing about fieldwork may be blocked by a typo in a date box.
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

/**
 * The IMAGE / IMAGE_LIST fields a confirmed photograph could actually be filed into, per stage.
 *
 * Registry-read like everything else, and returned rather than chosen: stage 3 has both "Opening
 * photographs" and "Event photographs", and which of the two a given photograph belongs in is not
 * knowable from a timestamp. The surface offers the entity's own list and defaults to its first,
 * which is the BASIC-tier one by declaration order.
 *
 * ── AND IT CARRIES THE FIELD'S CEILING, WHICH IS WHY `maxItems` IS ON EVERY TARGET ─────────────
 *
 * A destination is not just a place, it is a place with room in it or without. {@link
 * appendMediaRef} is the only door a confirmed intake writes through and it cannot look a field up,
 * so the ceiling has to travel WITH the target or the whole intake path runs uncapped — which is
 * exactly what it did until 2026-08-26, straight past the two motif galleries' declared 20 and past
 * the server's own default of 200. Android carries the same figure the same way,
 * `DwPhotoIntake.DwPhotoTarget.maxItems` into `DwIntakeDestination.maxItems`.
 *
 * VERBATIM FROM THE REGISTRY, undefined and all: `field_to_dict` emits `maxItems` only for a field
 * that declares one, and that absence has to survive the trip because it decides two different
 * things — {@link effectiveMaxItems} turns it into the number ENFORCED (200), while a caller writing
 * a sentence may only PRINT the number when it is present (docs/DESIGN_WORKSHOP.md:229-232).
 * Resolving it here would collapse those two into one and hand every caller a 200 it is not allowed
 * to say out loud.
 */
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

/* ────────────────────────────────────────────────────────────────────────────
 * Ranking
 * ──────────────────────────────────────────────────────────────────────────── */

/** Whole days from `day` to the nearest edge of the anchor; 0 when it falls inside. */
function distanceTo(anchor: WorkshopAnchor, day: number): number {
  const from = photoDayIndex(anchor.start);
  const to = photoDayIndex(anchor.end);
  if (from === null || to === null) return Number.MAX_SAFE_INTEGER;
  if (day < from) return from - day;
  if (day > to) return day - to;
  return 0;
}

/** How many days an anchor covers. 1 for a single day. */
function widthOf(anchor: WorkshopAnchor): number {
  const from = photoDayIndex(anchor.start);
  const to = photoDayIndex(anchor.end);
  if (from === null || to === null) return Number.MAX_SAFE_INTEGER;
  return to - from + 1;
}

/**
 * Order two candidate anchors for one photograph. Lower sorts first.
 *
 * THE RULE IS: NARROWER EVIDENCE WINS. Containment first, then distance, then width. A photograph
 * taken on the fourteenth is inside stage 1's thirty-day window AND on stage 13's log row for the
 * fourteenth, and both are true — but "the log entry for that exact day" is a reason and "somewhere
 * in the month the workshop ran" is barely one. Ranking by width is what puts the specific claim
 * above the vacuous one, and it is why stage 1's whole-workshop span is a useful last resort rather
 * than a permanent winner that would swallow every photograph in the batch.
 *
 * Stage number is the final tie-break so the order is total and stable: two anchors that match a
 * photograph equally well must not swap places between renders, or a designer scanning two hundred
 * rows sees the list reshuffle under them.
 */
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

/** The sentence under a proposal. See {@link StageProposal.evidence} for why it lives here. */
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

/**
 * Rank the anchors for one photograph whose clock has already been read.
 *
 * Exported on its own so the ranking can be tested by value — one photograph, a handful of anchors,
 * an expected order — without constructing a registry.
 */
export function rankProposals(stamp: PhotoStamp, anchors: WorkshopAnchor[]): StageProposal[] {
  const day = photoDayIndex(stamp.date);
  if (day === null) return [];
  const usable = anchors.filter((anchor) => distanceTo(anchor, day) !== Number.MAX_SAFE_INTEGER);
  if (!usable.length) return [];

  const ordered = [...usable].sort((a, b) => compareCandidates(a, b, day));
  const nearest = distanceTo(ordered[0], day);
  // Refusal, not a long-range guess. See OUTSIDE_GRACE_DAYS.
  if (nearest > OUTSIDE_GRACE_DAYS) return [];

  return ordered.slice(0, MAX_PROPOSALS).map((anchor) => {
    const daysAway = distanceTo(anchor, day);
    const basis: ProposalBasis = daysAway > 0 ? "NEAREST" : anchor.kind === "DAY" ? "ON_DAY" : "IN_SPAN";
    return { anchor, basis, daysAway, evidence: evidenceFor(stamp, anchor, basis, daysAway) };
  });
}

/**
 * The whole intake: every photograph, what was read off it, and what is proposed for it.
 *
 * ORDER IS PRESERVED. The rows come back in the order the designer picked the files, which on a
 * camera dump is filename order and therefore usually shooting order. Sorting by timestamp here
 * would scatter the date-less files — the screenshots and the scans, which are exactly the ones
 * needing a human — to one end of a two-hundred-row list.
 *
 * NOTHING IS EVER DROPPED. A file with no EXIF date, a file with a broken one, and a file the
 * workshop's dates come nowhere near all come back as rows with a `refusal` and no proposal, ready
 * for manual assignment. A bulk importer that silently discarded the awkward third of a camera dump
 * would be worse than no bulk importer, because the designer would never learn which third.
 */
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

    // Name the distance rather than saying "no match". A batch off a camera whose clock was never
    // set reads "1,847 days before" on every row, and that sentence diagnoses the camera in one
    // glance where a bare refusal would have the designer re-checking the workshop's dates.
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

/** Day number back to `yyyy-mm-dd`, for the refusal sentence. */
function indexToDate(index: number): string {
  const at = new Date(index * 86_400_000);
  return `${pad4(at.getUTCFullYear())}-${pad2(at.getUTCMonth() + 1)}-${pad2(at.getUTCDate())}`;
}

/**
 * A one-line summary of a finished intake, for the surface's header.
 *
 * Counted rather than described: "180 of 200 placed" is the number a designer needs to decide
 * whether to press Confirm, and the twenty are the ones they then go and look at. Truncation and
 * omission must always be visible in this repository (see the silent-emptiness class in the frontend
 * reference); this is that rule applied to an import.
 */
export function intakeSummary(rows: PhotoIntakeRow[]): { total: number; proposed: number; manual: number } {
  const proposed = rows.filter((row) => row.proposals.length > 0).length;
  return { total: rows.length, proposed, manual: rows.length - proposed };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Writing a photograph into a field
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * THE CEILING A MULTI-VALUED FIELD IS ACTUALLY ENFORCED AGAINST — what it declared, or the server's
 * own default.
 *
 * The twin of `effectiveMaxItems` in `components/designworkshop/FieldInput.tsx` and of
 * `dwEffectiveMaxItems` in `StageSchema.kt`, and the three of them read the SAME 200 —
 * {@link DW_DEFAULT_MAX_ITEMS}, exported from `lib/designWorkshops.ts` — because the number being in
 * one place is the only thing that stops the day the server changes it from being discovered as a
 * refused save. FieldInput's copy is private to a `"use client"` module and cannot be imported into a
 * module this pure, which is why there are two of these three lines on this client rather than one.
 *
 * `> 0` IS NOT DEFENSIVE PADDING. Undefined is how the web registry says "not declared" and 0 is how
 * Android's `FieldDto.maxItems` says it, so both have to read as the default here rather than as a
 * ceiling of zero — a gallery that refused its FIRST photograph would be the same silent loss in the
 * opposite direction.
 *
 * ABSENT MEANS 200, NEVER "UNBOUNDED", and that is the half of docs/DESIGN_WORKSHOP.md:229-232 this
 * whole file failed until 2026-08-26. `coerce_value` (backend/app/services/stage_schema.py:1822)
 * REFUSES an over-long array rather than trimming it, and `save_stage` then restores the refused key
 * from the previous entry — so the two-hundred-and-first photograph does not cost itself, it costs
 * every photograph the field was about to store, reported as one error with the uploading done.
 */
export function effectiveMaxItems(declared: DwField["maxItems"]): number {
  return typeof declared === "number" && declared > 0 ? declared : DW_DEFAULT_MAX_ITEMS;
}

/** The strings a stored IMAGE_LIST is holding. Shared by the two functions below so they cannot disagree. */
function heldRefs(value: DwValue | undefined): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

/**
 * Add one media reference to whatever a field already holds, up to that field's ceiling.
 *
 * ── THE CEILING, WHICH THIS PATH IGNORED ALTOGETHER UNTIL 2026-08-26 ──────────────────────────────
 *
 * This is the door for TWO of the browser's three write paths into a media field — the bulk photo
 * import and the UPLOAD tab; `MediaField` in FieldInput.tsx is the third and has had a ceiling since
 * it was written. This one had no cap of any kind, so a confirmed import of two hundred photographs
 * appended every one of them into a gallery whose registry entry says twenty. Not a cosmetic
 * overrun: the server REFUSES the whole field rather than truncating it (see
 * {@link effectiveMaxItems}), so the stage save that followed lost the field's entire write while
 * the bytes sat in this browser's IndexedDB. An undefined `maxItems` is "the registry declared none",
 * which means {@link DW_DEFAULT_MAX_ITEMS} and never "no limit".
 *
 * A REF THE FIELD ALREADY HOLDS IS NOT GROWTH, so the ceiling is tested after the identity check
 * above it: confirming the same photograph into a full gallery twice must stay the no-op it always
 * was rather than become a refusal a designer is then told about.
 *
 * ── AND WHAT THIS FUNCTION CANNOT DO, WHICH ITS CALLERS MUST ──────────────────────────────────────
 *
 * A REFUSAL IS NOT REPORTED FROM HERE. It returns a value, not a receipt, so a caller that counts
 * what it wrote has to ask {@link mediaRefRoom} FIRST and route what does not fit into its own "not
 * attached" sentence — rule 10 of the frontend contract, and the same duty the confirm walk already
 * discharges for a row that was deleted under it. Both callers now do:
 * `app/(protected)/design-workshops/[id]/photos/page.tsx` asks before it stages a single byte and
 * names the files it turned away beside the rows that went missing, and
 * `components/sketches/UploadTabHost.tsx` does the same for a turn of turntable frames and counts
 * only what landed in the sentence it prints. Anything that adds a FOURTH write through here owes
 * the designer the same sentence.
 *
 * The Kotlin twin is `DwPhotoIntake.appendMediaRef`, which differs in exactly one deliberate way:
 * it keeps a bare string a list field somehow holds, where this reads only an ARRAY. Its KDoc argues
 * that case; the two are otherwise the same function, ceiling included.
 */
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

/**
 * How many more references this field can take — asked BEFORE {@link appendMediaRef} is called, by
 * any caller that then has to tell the designer what landed.
 *
 * SPLIT OUT RATHER THAN RETURNED, because a nullable return from {@link appendMediaRef} would let a
 * caller write `?? previous` and be exactly where it started, whereas a question that has to be asked
 * in its own statement is a question that appears in the diff. Kotlin's `DwPhotoIntake.mediaRefFits`
 * is the same guard for the same reason, and the capture card's `room` in FieldInput.tsx is the same
 * arithmetic — this is deliberately that same word, so a reader who knows one knows this.
 *
 * A COUNT AND NOT A YES/NO, AND NOT A REFERENCE TO ASK ABOUT, because of WHEN the browser has to ask.
 * On the handset the file is already copied and has an id by the time the confirm walk runs, so
 * Kotlin can ask about a specific reference; here the reference does not exist until
 * `stageLocalMedia` mints it, so the only answerable question before the write is "is there room".
 * A count is also the shape a caller trimming a whole turn in one go would need; the two callers here
 * ask per file inside their own loops instead, because the file before it is what filled the field.
 *
 * ASKING FIRST IS WHAT KEEPS THE BYTES OUT OF STORAGE. `stageLocalMedia` writes the blob into
 * IndexedDB and the sync pass uploads every staged row it finds, so staging a photograph the field
 * then refuses would put a file nothing references on the wire and leave a copy in browser storage
 * for the rest of the fortnight. FieldInput's `acceptFiles` makes the same choice in the same words:
 * trim "before a byte is uploaded".
 *
 * ONE for a single-valued field, which REPLACES rather than appends and therefore always has room for
 * the next file — the field the designer overwrites is a different complaint (`UploadTabHost` states
 * it beside the picker) and not a refusal to report here.
 */
export function mediaRefRoom(value: DwValue | undefined, multiple: boolean, maxItems?: number): number {
  if (!multiple) return 1;
  return Math.max(0, effectiveMaxItems(maxItems) - heldRefs(value).length);
}
