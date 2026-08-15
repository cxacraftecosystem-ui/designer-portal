/**
 * The design-workshop API, typed — and the one place the field registry is cached.
 *
 * THE WHOLE POINT OF THIS FILE is that there is no per-stage form code anywhere in the web app.
 * `GET /api/design-workshops/schema` returns the registry that
 * `backend/app/services/stage_definitions.py` declares — 22 stages, 43 entities, 496 typed fields —
 * and every screen renders by walking `stage.entities -> entity.fields` and dispatching on
 * `field.type`. A field added to the registry appears on this client with no client change, which
 * is the only arrangement under which three surfaces (this form, the Android capture screens and
 * the report writer) can still agree about a field list two field seasons from now. Hand-writing
 * twenty-two forms would guarantee they drift, and the drift would be discovered as a blank cell in
 * a report already submitted to a ministry.
 *
 * Two shapes on this wire will trip a reader who assumes the rest of the repository's conventions:
 *
 * 1. **MONEY arrives as a JSON string, not a number.** `coerce_value` in `stage_schema.py` stores it
 *    as `f"{value:.2f}"` deliberately, so 1250.10 survives the JSON round trip instead of coming
 *    back as 1250.0999999999999 — the same reason Prisma hands every `Decimal` column over as a
 *    string. Every money-ish value is therefore typed `string | number | null`; read it with
 *    {@link readNumber} (which is `Number()` behind `Number.isFinite`) and seed an input with
 *    {@link inputValue} (which is `String()`). Typing one of these as `number` has emptied a
 *    dropdown twice in this repository, and the compiler cannot catch it because JSON is `any`.
 *
 * 2. **Report preview blocks are snake_case.** `_block_payload` in the route serialises the
 *    `ReportDocument` dataclasses with `dataclasses.asdict()` and does not rename anything, so the
 *    keys are `width_pct`, `info_rows`, `total_row`, `height_px` and so on — unlike every other
 *    endpoint in this API, which is camelCase because it is built from pydantic models. The types
 *    below spell them exactly as they arrive rather than "tidying" them, because a renamed key here
 *    would compile perfectly and render an empty block.
 */

import { API_BASE, ApiError, apiFetch, assertApiConfigured, buildQuery, describeApiDetail, getToken } from "@/lib/api";
import { prepareIdentityPhotograph } from "@/lib/identityCardImage";
import type { MarketFindingsPayload } from "@/lib/marketAnalysis";
import type { DwReportHistory } from "@/lib/reportDiff";
import { isStoredRichTextEmpty, richSummary, type StoredRichDoc } from "@/lib/richText";
import type { PageResult } from "@/lib/types";

/* ────────────────────────────────────────────────────────────────────────────
 * The registry — mirrors app/services/stage_schema.py's `*_to_dict` serialisers.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Every capture type the registry can declare. The set is deliberately small — the backend's own
 * comment says a new one has to earn its place four times over (web form, Android Composable,
 * validator, report renderer) — so this union is expected to stay short, and `FieldInput` has a
 * branch for every member. A `switch` over it with no `default` is what makes the compiler point at
 * `FieldInput` the day a twenty-third type is added.
 */
export type DwFieldType =
  | "TEXT"
  | "LONG_TEXT"
  /**
   * A structured document, never HTML — `backend/app/services/rich_text.py`, mirrored in the
   * browser by `lib/richText.ts`. The stored value is `{"blocks":[…]}` and NOT a string, so every
   * helper in this file that reads a value has a branch for it: {@link inputValue} returns "" (it
   * has no single-line form), {@link isFilled} asks whether the document holds any TEXT, and
   * {@link rowTitle} titles a row from its first line rather than from its JSON.
   */
  | "RICH_TEXT"
  | "INT"
  | "DECIMAL"
  | "MONEY"
  | "PERCENT"
  | "DATE"
  | "TIME"
  | "BOOL"
  | "ENUM"
  | "MULTI_ENUM"
  | "TAGS"
  | "IMAGE"
  | "IMAGE_LIST"
  | "FILE"
  | "AUDIO"
  | "VIDEO"
  | "GEO"
  | "REF"
  | "URL"
  | "PHONE"
  | "EMAIL";

/**
 * The prefix that marks a type token this build must NOT draw a working control for.
 *
 * WHY THE UNION IS NOT ENOUGH, AND WHY THIS LIVES HERE RATHER THAN IN `lib/customSections.ts`.
 * {@link DwFieldType} is closed and `FieldInput` switches over it with no `default`, which is what
 * makes the compiler point at that file the day a twenty-third registry type is added. But a
 * DESIGNER-DEFINED field's type is a string that arrived over the wire from a per-workshop
 * definition, and two of its cases cannot be served by the union at all:
 *
 *  - a token this build has never heard of, because the server moved on while this tab did not; and
 *  - a token this build knows PERFECTLY WELL and still must not draw — GEO, IMAGE, RICH_TEXT, REF.
 *    v1 admits none of them for a custom question, because five separate walkers translate a local
 *    media reference into a server id and every one of them enumerates the media fields OF THE ROW'S
 *    REGISTRY ENTITY, so a custom photograph syncs as a `dwlocal:` reference resolving to nothing:
 *    clean save, and the picture simply absent from the .docx.
 *
 * The second case is why "does `FieldInput` have a branch" is the wrong question and why a type
 * outside the twelve is re-tokenised through {@link unsupportedFieldType} before it ever reaches the
 * switch. The RAW TOKEN travels inside the new one so the read-only note can name it: a note that
 * will not say what the type was is a note a designer cannot report.
 *
 * It is declared beside the union rather than in the feature module because both halves of the
 * contract have to agree about one string — the writer of the token and the switch's `default:` arm
 * that reads it — and a prefix restated in two files is the shape of the wrong-key defect this
 * repository has already shipped twice.
 */
export const UNSUPPORTED_FIELD_TYPE_PREFIX = "UNSUPPORTED:";

/**
 * A type token that no `FieldInput` branch can match, carrying the real one for the note to print.
 *
 * Cast rather than widened, and the cast is the honest description of what is happening: this value
 * is deliberately NOT a member of the union, so that the exhaustive switch falls through to its
 * `default:` arm. Widening `DwFieldType` with `string` instead would silence the compiler at all 23
 * existing branches and lose the guarantee the closed union exists for.
 */
export function unsupportedFieldType(raw: string): DwFieldType {
  return `${UNSUPPORTED_FIELD_TYPE_PREFIX}${raw}` as DwFieldType;
}

/** The type as it should be NAMED to a designer: the raw token, with any marker prefix stripped. */
export function fieldTypeName(type: string): string {
  return type.startsWith(UNSUPPORTED_FIELD_TYPE_PREFIX)
    ? type.slice(UNSUPPORTED_FIELD_TYPE_PREFIX.length)
    : type;
}

/**
 * The three capture tiers from the source matrix.
 *
 * BASIC is the minimum a report needs and is the only tier a field may be `required` on — the
 * backend's `validate_registry` refuses the build otherwise, because a required STANDARD field
 * would make the completeness gate unsatisfiable in exactly the village without mains power that
 * the app was written for. ADVANCED is collapsed behind a disclosure here for the same reason: a
 * designer standing in a courtyard must be able to see the whole of what is actually required
 * without scrolling past forty optional boxes.
 */
export type DwTier = "BASIC" | "STANDARD" | "ADVANCED";

export type DwReportRole =
  | "NARRATIVE"
  | "KEY_VALUE"
  | "TABLE_COLUMN"
  | "CAPTION"
  | "GALLERY"
  | "COVER_FIELD"
  | "METRIC"
  | "BULLETS"
  | "HIDDEN";

export type DwCardinality = "SINGLETON" | "COLLECTION";

export type DwEnumOption = { value: string; label: string };

/**
 * One field descriptor.
 *
 * Everything after `required` is optional because `field_to_dict` emits only non-default keys — the
 * whole registry crosses the wire on every cold start and the empty strings are most of its bulk.
 * So `field.help` being undefined means "no help text", never "the server forgot"; nothing here may
 * treat an absent key as an error.
 */
export type DwField = {
  key: string;
  label: string;
  type: DwFieldType;
  tier: DwTier;
  required: boolean;
  help?: string;
  unit?: string;
  /** Name of the shared list in the registry's `enums` map — present only for ENUM / MULTI_ENUM. */
  enum?: string;
  /** The same list, inlined by the server so a form never has to join against `registry.enums`. */
  options?: DwEnumOption[];
  /** For REF: "Artisan", "Craft", or another Dw entity's PascalCase model name. */
  refModel?: string;
  /**
   * For REF: how wide the picker's net is, as the SERVER declared it.
   *
   * Emitted for every ref field, defaulted rather than omitted (see `field_to_dict`), and sent
   * straight back on `GET .../references` rather than being re-derived here. That round trip is the
   * whole point: if the client supplied its own default, the two ends would each hold an opinion
   * about how wide the artisan list should be, and the day they disagreed the picker would quietly
   * widen — offering artisans from another cluster to a designer who believes they are looking at
   * this one's roster, with nothing on screen saying so.
   */
  refScope?: string;
  /**
   * For REF: the key of the field ON THE SAME RECORD whose value narrows this picker.
   *
   * This is the cascade. `existingProduct.productRef` carries `refFilterBy: "artisanRef"`, so the
   * product dropdown on that row holds the products of the artisan chosen on that same row and
   * nothing else. It is a sibling key, never a global one — two rows of the same collection are two
   * different artisans and must offer two different product lists.
   */
  refFilterBy?: string;
  maxLength?: number;
  minValue?: number;
  maxValue?: number;
  reportRole?: DwReportRole;
  /** How this field computes itself when left blank, and the field keys it computes from. */
  /*
    KEEP IN STEP WITH `derive_value` IN `stage_schema.py`. A kind the server declares and this union
    omits is not a type error at the fetch boundary — JSON is `any` — it is a SILENTLY DEAD BRANCH:
    `lib/derivedFields.ts` stops computing that kind, the field stays blank as the designer types,
    and the value only appears after a save round trip. That is exactly what happened to `SUM`, which
    the registry declares for `costSheet.totalCost`, so a cost sheet's total did not add itself up in
    the browser while every other derived field did.
  */
  derivedKind?: "DAYS_BETWEEN" | "PRODUCT" | "SUM";
  derivedFrom?: string[];
  columnWidthPct?: number;
  /**
   * This field is the caption of that media field. It must be rendered directly underneath its
   * target and NEVER as a separate input: a caption box floating three fields away from the photo
   * it describes gets filled in about the wrong photo, and the report then prints the mismatch.
   */
  captionFor?: string;
  deprecated?: boolean;
  replacedBy?: string;
};

export type DwEntity = {
  key: string;
  name: string;
  cardinality: DwCardinality;
  title: string;
  description: string;
  parent: string;
  /** Which field titles a row in a collection list. May be "" — see {@link rowTitle}. */
  labelField: string;
  fields: DwField[];
};

export type DwStage = {
  number: number;
  key: string;
  title: string;
  purpose: string;
  notes: string;
  /** The source document's reviewer marked this stage as possibly droppable. Say so on screen. */
  optionalStage: boolean;
  entities: DwEntity[];
};

export type DwRegistry = {
  /** A content digest of every key, type and tier. Insensitive to labels — see `registry_version`. */
  version: string;
  enums: Record<string, DwEnumOption[]>;
  stages: DwStage[];
};

/* ────────────────────────────────────────────────────────────────────────────
 * Stored values
 * ──────────────────────────────────────────────────────────────────────────── */

export type DwGeoValue = { lat: number; lon: number; accuracy?: number };

/**
 * One stored answer.
 *
 * `string` covers TEXT through DATE/TIME **and MONEY** (see the file header). `number` covers INT,
 * DECIMAL and PERCENT. `string[]` covers MULTI_ENUM, TAGS and IMAGE_LIST. Media fields store a
 * media id, not a URL — `media_resolver` on the server looks the row up by id, and a client that
 * stored a presigned URL here would write a value that expires.
 */
export type DwValue = string | number | boolean | string[] | DwGeoValue | StoredRichDoc | null;

export type DwEntryData = Record<string, DwValue | undefined>;

/**
 * A row of a COLLECTION entity as the server hands it back.
 *
 * The three underscore-prefixed keys are added by `_stages_payload` and are NOT registry fields:
 * `_entryId` is the row's database id, `_ordinal` is its position, and `_clientKey` is the
 * idempotency key that lets a re-sent create match an existing row instead of duplicating it.
 *
 * `_entryId` and `_ordinal` travel as their own fields on the save envelope and must be stripped
 * out of `data`; `_clientKey` must be LEFT IN, because `save_stage` reads it out of `data` itself
 * (`entry.data.get("_clientKey")`) and matches on `(entityKey, clientKey)`. Strip that one and a
 * retried save after a dropped connection creates a second copy of the row. {@link entryDataOf}
 * draws exactly that line; the server explicitly excludes every underscore key from `droppedKeys`,
 * so leaving it in costs nothing.
 */
export type DwRow = DwEntryData & {
  _entryId?: string;
  _ordinal?: number;
  _clientKey?: string;
};

export type DwStageCompleteness = {
  stageKey: string;
  number: number;
  title: string;
  requiredTotal: number;
  requiredFilled: number;
  optionalTotal: number;
  optionalFilled: number;
  /**
   * Progress across BASIC-tier fields only. A stage with no required fields at all reads 100, not
   * 0 — dividing by zero to decide whether a designer may submit is how a stage becomes
   * permanently unsubmittable. Do not "fix" a 100% on an empty stage 22.
   */
  percent: number;
  isComplete: boolean;
  collectionCounts: Record<string, number>;
  /** Labels of the unfilled required fields, de-duplicated, in declaration order. */
  missing: string[];
};

export type DwStageData = {
  singleton: DwEntryData;
  collections: Record<string, DwRow[]>;
  /**
   * The answers to this workshop's own designer-defined questions for this stage.
   *
   * A THIRD SIBLING KEY AND NOT A NESTED OBJECT INSIDE `singleton`, because that is what
   * `_stages_payload` sends: the `_custom` row is a `DwStageEntry` of its own, one per (workshop,
   * stage), and the route gives it its own key precisely so it does not fall to the collection arm
   * and come back as `collections["_custom"]` with `_entryId` and `_ordinal` injected into it — a
   * phantom repeating entity on every stage that has custom answers.
   *
   * Optional because eight of the twenty-two stages send no such row for most workshops, and because
   * a server that predates the feature sends none at all. Absent means "no custom answers here",
   * never "the server forgot".
   */
  custom?: DwEntryData;
  completeness?: DwStageCompleteness | null;
  /**
   * The digest of the custom definition the score beside it was computed under.
   *
   * CARRIED BESIDE THE SCORE, and the pairing is the point rather than a convenience — the route's
   * own comment says so. A client holding an older definition, or none, would otherwise show the
   * server's higher `requiredTotal` for a stage it has never touched and its own lower one for a
   * stage it has, which is two arithmetics in one list with nothing on screen to say why.
   */
  customSchemaVersion?: string;
};

/* ────────────────────────────────────────────────────────────────────────────
 * The workshop header
 * ──────────────────────────────────────────────────────────────────────────── */

export type DwStatus = "DRAFT" | "IN_PROGRESS" | "COMPLETE" | "SUBMITTED" | "ARCHIVED";

/**
 * The list row. Everything from `workshopCode` down is DENORMALISED from stage 1 by
 * `promoted_values()` and is never written by hand, so it is null until stage 1 has been saved —
 * a freshly created workshop legitimately shows a title and nothing else.
 */
export type DwSummary = {
  id: string;
  title: string;
  templateId: string;
  status: DwStatus | string;
  workshopCode: string | null;
  scheme: string | null;
  craftName: string | null;
  clusterName: string | null;
  state: string | null;
  district: string | null;
  venue: string | null;
  startDate: string | null;
  endDate: string | null;
  designerName: string | null;
  implementingAgency: string | null;
  sponsor: string | null;
  notes: string | null;
  workshopId: string | null;
  createdById: string;
  createdAt: string | null;
  updatedAt: string | null;
  deletedAt: string | null;
};

export type DwDetail = DwSummary & {
  stages: Record<string, DwStageData>;
  completeness: Record<string, DwStageCompleteness>;
  schemaVersion: string;
  /**
   * The digest of THIS WORKSHOP'S designer-defined questions. A SECOND, SEPARATE STRING.
   *
   * **IT MUST NEVER BE FOLDED INTO `schemaVersion` OR INTO THE DRAFT'S `registryVersion`.** That
   * string is a content digest of every key, type and tier of the 496-field registry, and
   * `stageSpecFor` reads it as a KEY into the registry object store — so a composite value would miss
   * every cached registry and every stage would render from "whatever this browser happens to hold"
   * instead of from the field list it was captured with. It is also the reason the server keeps the
   * two apart: a designer's custom field moving `registry_version()` would make every handset in the
   * fleet treat its bundled 119 KB schema as stale the moment anyone anywhere added a question.
   *
   * Optional so a server that predates the feature reads as "" rather than as a type error at a
   * boundary the compiler cannot police anyway.
   */
  customSchemaVersion?: string;
};

export type DwStagesPayload = {
  stages: Record<string, DwStageData>;
  completeness: Record<string, DwStageCompleteness>;
  schemaVersion: string;
  /** See {@link DwDetail.customSchemaVersion} — a second string, never folded into the one above. */
  customSchemaVersion?: string;
};

export type DwTemplate = { id: string; name: string; description: string };

export type DwCreateBody = {
  title: string;
  templateId?: string;
  craftName?: string | null;
  clusterName?: string | null;
  state?: string | null;
  district?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  workshopId?: string | null;
  notes?: string | null;
};

export type DwUpdateBody = Partial<DwCreateBody> & { status?: string };

export type DwSaveEntry = {
  entityKey: string;
  /** Omit to create a row; send the row's `_entryId` to update it in place. */
  entryId?: string;
  ordinal?: number;
  data: DwEntryData;
  /**
   * "I am sending every key I HAVE, not every key there IS."
   *
   * Set only when this browser knows it has NOT read the server's copy of the row
   * (`serverLoadedAt === null`). The server then keeps the keys that are absent from `data`
   * instead of deleting them. Omitted — the default — the row's data is replaced wholesale,
   * which is what a browser that HAS read the row must do, because for it an absent key is a
   * real deletion.
   */
  merge?: boolean;
};

export type DwSaveBody = {
  entries: DwSaveEntry[];
  /**
   * True replaces the named entities wholesale (the phone's whole-stage sync, so a row deleted on
   * the device is deleted here). The web form sends FALSE: it edits one row at a time and must not
   * delete rows another editor added between this page loading and Save being pressed.
   */
  replaceCollections?: boolean;
  /**
   * Collections this client has emptied: "I now hold zero rows of this entity, delete what you
   * still have."
   *
   * IT IS THE ONLY WAY DELETING THE LAST ROW OF A COLLECTION REACHES THE SERVER. The sweep is
   * scoped to the entities the payload actually NAMED — deliberately, so a partial save can no
   * longer wipe collections it never mentioned — and an emptied collection names itself nowhere:
   * `entries` is built from `collections[key] ?? []`, so zero rows sends zero entries and the
   * entity is invisible in the request. There is no per-row delete endpoint. Without this field
   * the deletion is accepted, reported as saved, and silently never happens.
   *
   * Send `removedFrom`, which the draft store already tracks per entity. Naming an entity that
   * still has rows in `entries` is harmless: those rows are in the payload and survive the sweep.
   * Read by the server only when `replaceCollections` is true, and ignored for singletons.
   */
  emptiedEntities?: string[];
  /** True enforces the BASIC-tier required fields and 422s if any is missing. */
  submit?: boolean;
};

export type DwSaveResult = {
  stageKey: string;
  /** How many rows were written — a COUNT, not the data. `created + updated`. */
  saved: number;
  created: number;
  updated: number;
  removed: number;
  /**
   * Per-field validation messages, keyed by `entityKey` for a SINGLETON and by
   * `` `${entityKey}[${index}]` `` for a collection row — where `index` is that entry's position in
   * the `entries` ARRAY THAT WAS SENT, not its ordinal within the entity and not its position in
   * the collection. A caller must therefore keep the order it built its entries in and translate
   * back, or every message after the first collection lands on the wrong row.
   */
  errors: Record<string, Record<string, string>>;
  /**
   * How many ANSWERS the save refused, counted by the server so both surfaces show one number.
   *
   * `errors` is two levels deep and carries no total, so a client computing its own headline picks
   * whichever reading its author saw first — and the two clients picked one each. The web counted
   * SCOPES and Android counted FIELDS, so one stage entry with three bad fields was "1 answer" on a
   * laptop and "3 answers" on the phone, off the same response body, both saying *answer*. Fields is
   * the right reading: an answer is what a designer typed into one box, a row is not an answer, and
   * the remedy the sentence gives them — open the stage and look at the marked boxes — is per-field.
   *
   * PREFER THIS OVER COUNTING `errors` LOCALLY. `countRefusedAnswers` in `designWorkshopStore` is
   * kept only as the fallback for a server that predates this field, and it is NOT equivalent: for a
   * scope whose value is a bare string it returns `Object.keys(str).length` — a CHARACTER COUNT —
   * where the server returns 1. See `refused_answer_count` in `services/design_workshops.py`, whose
   * non-mapping guard exists for exactly that reason.
   *
   * Optional because a client can be newer than the deployment it is talking to.
   */
  refusedAnswers?: number;
  /**
   * Field keys this build sent that the server's registry does not know, as "entity.field".
   *
   * This is how a server notices a client is running a newer registry than it is, and it is the
   * only signal that data a designer typed was not stored. It must be shown on screen — a silent
   * drop is a form that accepts an answer and discards it.
   */
  droppedKeys: string[];
  /**
   * Custom question keys this build sent that the workshop's DEFINITION does not carry.
   *
   * **ITS OWN FIELD, ITS OWN STATE AND ITS OWN SENTENCE — NEVER MERGED INTO `droppedKeys`.**
   * `droppedKeys` is the only client/server registry-drift signal this repository has and both
   * clients render it in those words: "this build is running ahead of the server's field list". A
   * custom key the definition does not carry is a DIFFERENT fact with a different remedy — the
   * definition was edited, not the app — and feeding it into that signal would fire the amber banner
   * on every save of every workshop that has a custom section, training the people who read it to
   * ignore the one message that matters.
   *
   * Optional: a server that predates the feature sends nothing, and reading that as an empty list is
   * correct.
   */
  droppedCustomKeys?: string[];
  completeness: DwStageCompleteness | null;
  schemaVersion: string;
  /**
   * The digest of the definition this save was VALIDATED AGAINST, so a client can tell its cached
   * copy is stale without a second request. "" for a workshop that has no definition, which is a
   * different fact from "I hold nothing" and is why the server does not omit it.
   */
  customSchemaVersion?: string;
};

export type DwExport = {
  id: string;
  format: string;
  templateId: string;
  fileName: string;
  fileSizeBytes: number | string | null;
  pageCount: number | null;
  checksumSha256: string | null;
  generatedOnDevice: boolean;
  generatedAt: string | null;
  warnings: string | null;
};

export type DwExportRecordBody = {
  format: string;
  templateId: string;
  fileName: string;
  generatedAt: string;
  fileSizeBytes?: number | null;
  pageCount?: number | null;
  checksumSha256?: string | null;
  warnings?: string | null;
};

/* ────────────────────────────────────────────────────────────────────────────
 * Report preview blocks — snake_case, see the file header.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One run of formatted text, as the preview endpoint serialises `report_model.Run`.
 *
 * **THE MARKS ARRIVE AS BOOLEANS, ALREADY RESOLVED.** `rich_text.to_preview_json` and the report
 * builder both hand the browser `bold` / `italic` / `underline` / `strike` rather than the `Mark`
 * enum members they came from, precisely so the browser never has to interpret the mark vocabulary
 * itself — that is the one place a fifth renderer would start to drift from the other four.
 *
 * `underline` and `strike` were added to `report_model.Run` for RICH_TEXT and are optional here for
 * one deployment cycle only: a server that predates that change sends neither key, and typing them
 * as required would make every run on an older deployment fail the type at runtime while compiling
 * perfectly. Read them with `?? false`, never assume they are present.
 *
 * There is deliberately no `code` field, and its absence is a real gap rather than an omission on
 * this side: `Run` has no monospace flag, so `_runs_for` in `rich_text.py` cannot carry the CODE
 * mark and neither this preview nor the .docx nor the PDF can show it. The words survive; the face
 * does not. Adding `code: bool` to `Run`, to `_runs_for`, to both writers and to the Kotlin pair is
 * what would close it — and the day it lands, this type and {@link DwRun}'s renderer gain one line.
 */
export type DwRun = {
  text: string;
  bold: boolean;
  italic: boolean;
  underline?: boolean;
  strike?: boolean;
  /** LATIN / DEVANAGARI / ODIA / … — the writers pick a font per run from this. */
  script: string;
  /** "RRGGBB", no leading hash — both writers want it bare, so prefix it before using it in CSS. */
  color: string | null;
};

export type DwImageRef = {
  /** A media id on the server (an absolute file path on the device). Fetch it via `GET /media/{id}`. */
  source: string;
  width_px: number;
  height_px: number;
  /** The EXIF orientation already resolved to a quarter turn: 0 / 90 / 180 / 270. */
  rotation_deg: number;
  mime_type: string;
};

export type DwTableColumn = {
  header: string;
  width_pct: number;
  align: string;
  numeric: boolean;
};

export type DwBlock =
  | {
      type: "COVER";
      title: string;
      subtitle: string;
      org_lines: string[];
      logo: DwImageRef | null;
      hero_image: DwImageRef | null;
      info_rows: Array<[string, string]>;
      footer_lines: string[];
    }
  | { type: "TOC"; title: string; depth: number }
  | { type: "HEADING"; level: number; runs: DwRun[]; number: string; bookmark: string }
  | { type: "PARAGRAPH"; runs: DwRun[]; style: string; align: string }
  | { type: "BULLETLIST"; items: DwRun[][]; ordered: boolean }
  | { type: "KEYVALUE"; pairs: Array<[string, DwRun[]]>; columns: number; label_width_pct: number }
  | {
      type: "TABLE";
      columns: DwTableColumn[];
      rows: DwRun[][][];
      caption: string;
      total_row: DwRun[][] | null;
      zebra: boolean;
    }
  | { type: "IMAGE"; image: DwImageRef; width_pct: number; align: string; caption: string }
  | { type: "IMAGEGRID"; images: Array<[DwImageRef, string]>; columns: number; caption: string }
  | { type: "METRICROW"; metrics: Array<[string, string, string]> }
  | { type: "CALLOUT"; kind: string; title: string; runs: DwRun[] }
  | { type: "SIGNATURE"; signatories: Array<[string, string]> }
  | { type: "SPACER"; height_pct: number }
  | { type: "PAGEBREAK" };

export type DwPreview = {
  meta: {
    title: string;
    subtitle: string;
    templateId: string;
    templateName: string;
    pageSize: string;
  };
  blocks: DwBlock[];
  /** Missing required fields, photos that could not be embedded. Render these; never hide them. */
  warnings: string[];
};

export type DwReportBody = {
  templateId?: string | null;
  formats: Array<"DOCX" | "PDF">;
  pageSize?: string | null;
  /**
   * The report's accent colour as `#RRGGBB`, for this one file — `ReportGenerateIn.themeAccent`.
   *
   * ONE COLOUR, NOT A PALETTE. The server derives the soft accent, the ink, the muted grey, the
   * rules, the zebra fill and the table header's text from it, so a single value recolours the
   * whole document coherently and no combination of independently-chosen colours exists to get
   * wrong. `undefined` means "whatever stage 20 says", and a stage that says nothing leaves the
   * template's own colour alone. A malformed value is ignored rather than rejected: a download a
   * designer is waiting on must not fail over a colour string.
   */
  themeAccent?: string | null;
  headerText?: string | null;
  footerText?: string | null;
  includePhotographs?: boolean | null;
  /**
   * Append an annexure carrying every transcript the workshop's recordings produced.
   *
   * THREE-VALUED ON PURPOSE, and the third value is the useful one. `undefined` means "whatever
   * stage 20's `includeTranscripts` says", which is what `wants_transcripts` on the server reads
   * when the request is silent; `true` or `false` overrides it FOR THIS FILE ONLY. That is what
   * lets a designer produce a short copy to read out in a meeting and a full copy for the file
   * from one set of saved settings, without editing those settings twice and without the second
   * edit being the one they forget. Sending `false` where you meant "unspecified" would silently
   * strip an annexure a designer had already asked for and saved.
   */
  includeTranscripts?: boolean | null;
  /**
   * Append an annexure carrying every AI layer a person has ACCEPTED, each named as machine-assisted
   * text and each carrying the tier, the model and the person who accepted it.
   *
   * TWO-VALUED, NOT THREE, AND THAT IS THE DIFFERENCE FROM `includeTranscripts` ABOVE. The tri-state
   * exists there to protect a saved answer: a template that has always printed transcripts must go
   * on printing them for a workshop saved before the toggle existed, so `undefined` has to mean
   * "leave the saved setting alone". There is no such history here. No template declares the
   * section, there is no stage-20 answer behind it, and `apply_report_settings` splices it in only
   * on an explicit `true` — so absent and `false` are the same instruction and there is nothing for
   * a third value to preserve.
   *
   * IT IS ALSO THE HONEST DEFAULT. An annexure of model prose is not something that should happen
   * TO a report. Plan §3 rule 4 exists so a reader can tell a machine's words from an author's, and
   * a section that appeared without being asked for would put that decision nowhere.
   *
   * NOTHING UNACCEPTED IS EVER PRINTED, whatever this says — acceptance is checked again at the
   * point of rendering. A report asked for the annexure with nothing accepted comes back unchanged,
   * with a warning beside the download saying why.
   */
  includeAiLayers?: boolean;
  record?: boolean;
};

/* ────────────────────────────────────────────────────────────────────────────
 * The transcript annexure
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One recording and the state of its transcript, as `GET /design-workshops/{id}/transcripts`
 * serialises `report_annexures.TranscriptItem`.
 *
 * THE SAME VALUE OBJECT THE ANNEXURE IS BUILT FROM. The endpoint exists so the annexure can be
 * READ BEFORE IT IS COMMITTED TO: a transcript annexure is the one part of a report whose contents
 * nobody has read, it can double the document's length, and generating sixty pages to find out
 * what is in it is not a preview. Two shapes would drift, and the drift would show as this screen
 * listing a recording the report then omitted.
 */
export type DwTranscriptItem = {
  mediaId: string;
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  entityKey: string;
  fieldKey: string;
  fieldLabel: string;
  /** The field's own label, falling back to the filename and then the media id. */
  label: string;
  filename: string;
  recordedAt: string;
  durationSeconds: number | null;
  durationText: string;
  speakerCount: number;
  wordCount: number;
  firstLine: string;
  /** The media queue's transcription status — QUEUED, RUNNING, DONE, FAILED. */
  status: string;
  hasTranscript: boolean;
  /**
   * Whether the annexure would carry this one. A recording with no transcript yet is still LISTED
   * — hiding it would mean a designer who made six recordings and sees four concludes two were
   * lost, when in fact two are still in the queue.
   */
  includedInReport: boolean;
};

export type DwTranscriptList = {
  items: DwTranscriptItem[];
  total: number;
  withTranscript: number;
  /** Null when no recording carries a duration, never 0 — see the note on the endpoint. */
  totalDurationSeconds: number | null;
};

export function listDesignWorkshopTranscripts(id: string) {
  return apiFetch<DwTranscriptList>(`/design-workshops/${id}/transcripts`);
}

/* ────────────────────────────────────────────────────────────────────────────
 * The registry cache
 * ──────────────────────────────────────────────────────────────────────────── */

let cachedRegistry: DwRegistry | null = null;
let registryInFlight: Promise<DwRegistry> | null = null;

/**
 * The field registry, fetched once per tab and thereafter served from memory, keyed by `version`.
 *
 * THE PAYLOAD IS LARGE — 496 field descriptors with help text, enum option lists and report roles —
 * and it is a pure constant on the server (`get_stage_schema` reads no database at all). Every
 * screen in this feature needs it: the list page to name the stages, the stage index to count them,
 * every stage form to render itself, and the report preview to caption its warnings. Fetching it
 * per navigation would put a few hundred kilobytes on the wire each time a designer moved between
 * two stages, on the metered rural connection this whole feature is written for.
 *
 * `refresh` re-asks the server and, WHEN THE VERSION IS UNCHANGED, deliberately returns the
 * *previously cached object* rather than the newly parsed one. Identity matters: components hold
 * the registry in `useMemo`/`useEffect` dependency arrays, and handing back a structurally identical
 * but referentially new object would re-run every one of them and rebuild every form's field list
 * for no reason. A CHANGED version replaces the cache wholesale, which is the signal that a field
 * was added, removed or retyped and every derived list must be rebuilt.
 *
 * The in-flight promise is shared so that three components mounting in the same commit — which is
 * exactly what the stage page does — issue one request between them rather than three.
 */
export async function fetchStageRegistry(options?: { refresh?: boolean }): Promise<DwRegistry> {
  if (!options?.refresh && cachedRegistry) return cachedRegistry;
  if (!options?.refresh && registryInFlight) return registryInFlight;

  const request = apiFetch<DwRegistry>("/design-workshops/schema")
    .then((next) => {
      if (cachedRegistry && cachedRegistry.version === next.version) return cachedRegistry;
      cachedRegistry = next;
      return next;
    })
    .finally(() => {
      // Cleared whether it resolved or threw. A rejected promise left parked here would serve the
      // same failure to every later caller for the life of the tab, so a connection that came back
      // would never be noticed and the forms would stay permanently blank.
      registryInFlight = null;
    });

  registryInFlight = request;
  return request;
}

/** The cached registry without touching the network — null before the first successful fetch. */
export function peekStageRegistry(): DwRegistry | null {
  return cachedRegistry;
}

/**
 * Seed the in-memory cache from a registry that did NOT come off the wire — the copy
 * `lib/designWorkshopStore.ts` keeps in IndexedDB, served when a tab has been out of signal since it
 * opened.
 *
 * It returns whatever is now cached rather than what was passed in, and that is the point: if this
 * tab already holds a registry of the same version, the ALREADY-CACHED OBJECT is handed back. The
 * identity contract described above is what stops every `useMemo` and effect in the feature
 * rebuilding its field list, and an offline fallback that quietly broke it would rebuild all 496
 * field descriptors on the machine least able to afford it. A different version replaces the cache,
 * because that genuinely is a different field list.
 */
export function adoptStageRegistry(registry: DwRegistry): DwRegistry {
  if (cachedRegistry && cachedRegistry.version === registry.version) return cachedRegistry;
  cachedRegistry = registry;
  return registry;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Endpoints
 * ──────────────────────────────────────────────────────────────────────────── */

export function listReportTemplates() {
  return apiFetch<DwTemplate[]>("/design-workshops/templates");
}

export type DwListParams = {
  page?: number;
  pageSize?: number;
  search?: string | null;
  statusFilter?: string | null;
  craftName?: string | null;
  state?: string | null;
  /**
   * Narrow an admin's view to their own workshops. A non-admin is scoped to their own by the server
   * regardless, so sending `false` never widens anything — the parameter cannot be used to see
   * someone else's fieldwork.
   */
  mineOnly?: boolean;
};

export function listDesignWorkshops(params: DwListParams) {
  // `buildQuery` takes no booleans and drops "" exactly as it drops null, so `mineOnly` is spelled
  // out as the literal "true" and omitted entirely when it is off.
  return apiFetch<PageResult<DwSummary>>(
    `/design-workshops${buildQuery({
      page: params.page,
      pageSize: params.pageSize,
      search: params.search ?? undefined,
      statusFilter: params.statusFilter ?? undefined,
      craftName: params.craftName ?? undefined,
      state: params.state ?? undefined,
      mineOnly: params.mineOnly ? "true" : undefined
    })}`
  );
}

export function createDesignWorkshop(body: DwCreateBody) {
  return apiFetch<DwSummary>("/design-workshops", { method: "POST", body: JSON.stringify(body) });
}

export function getDesignWorkshop(id: string) {
  return apiFetch<DwDetail>(`/design-workshops/${id}`);
}

export function patchDesignWorkshop(id: string, body: DwUpdateBody) {
  return apiFetch<DwSummary>(`/design-workshops/${id}`, { method: "PATCH", body: JSON.stringify(body) });
}

/**
 * Soft delete — the row and every stage entry stay, only `deletedAt` is set.
 *
 * Say so in the confirmation copy. Nothing else in this repository has a soft delete, so a reader
 * who has met the artisan/product/tool dialogs will assume this one is permanent and will hesitate
 * over a mis-tap that an admin can undo in one click.
 */
export function deleteDesignWorkshop(id: string) {
  return apiFetch<void>(`/design-workshops/${id}`, { method: "DELETE" });
}

/** Undo a soft delete. Admin only — the point of a safety net is that it is not per-user. */
export function restoreDesignWorkshop(id: string) {
  return apiFetch<DwSummary>(`/design-workshops/${id}/restore`, { method: "POST" });
}

export function listDesignWorkshopStages(id: string) {
  return apiFetch<DwStagesPayload>(`/design-workshops/${id}/stages`);
}

export function getDesignWorkshopStage(id: string, stageKey: string) {
  return apiFetch<DwStageData>(`/design-workshops/${id}/stages/${stageKey}`);
}

/**
 * Stage 9's computed findings, as the SERVER sees them.
 *
 * THE FALLBACK, NOT THE SOURCE. `lib/marketAnalysis.ts` is a port of the same pure arithmetic and
 * runs on the rows this browser already holds, which is the only way the analysis exists in the
 * village where the survey was taken. This endpoint answers the one case the port cannot: a device
 * that has never downloaded stage 8 and so has no rows to compute from. A panel that called it
 * first would be a panel that goes blank without a connection.
 */
export function getDesignWorkshopMarketAnalysis(id: string) {
  return apiFetch<MarketFindingsPayload>(`/design-workshops/${id}/market-analysis`);
}

/**
 * Save one whole stage in one write.
 *
 * `replaceCollections` defaults to FALSE here, which is the opposite of the API's own default. The
 * server's default (true) is right for the phone, which posts everything it holds for a stage after
 * two days offline and means "this is the complete truth for these entities". It is wrong for a web
 * form: this page edits one row at a time and holds only what it loaded, so replacing wholesale
 * would delete every row a second editor added in the meantime — silently, with no error and no way
 * to tell afterwards that it happened.
 */
export function saveDesignWorkshopStage(id: string, stageKey: string, body: DwSaveBody) {
  return apiFetch<DwSaveResult>(`/design-workshops/${id}/stages/${stageKey}`, {
    method: "PUT",
    body: JSON.stringify({ replaceCollections: false, submit: false, ...body })
  });
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reference pickers — the endpoint that stops a designer retyping the database
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row a REF picker offers.
 *
 * `data` is the part that matters and the part a naive reader skips. It is the chosen record's
 * display fields — the artisan's name, village, gender, specialisation; the product's category,
 * material and price — and it is handed over WITH the option precisely so that choosing fills the
 * row in immediately rather than after a second round trip. See {@link hydrateFromReference}.
 */
export type DwReferenceOption = {
  id: string;
  label: string;
  sublabel: string;
  data: Record<string, unknown>;
};

/**
 * A picker's whole answer, and every field on it is load-bearing on screen.
 *
 * `scopedToWorkshop` is FALSE when a WORKSHOP-scoped field fell back to the whole table — which the
 * server does deliberately, because a design workshop need not be linked to a Workshop record and a
 * permanently empty picker with no explanation is worse than a wide one. The form has to say which
 * of the two happened, or a designer reads "all documented artisans" as "the artisans at this
 * workshop" and picks somebody who was never in the room.
 *
 * `truncated` is the same obligation in the other direction: a list that quietly stops at the
 * server's limit is indistinguishable from a cluster with only fifty artisans.
 */
export type DwReferencePayload = {
  model: string;
  scope: string;
  scopedToWorkshop: boolean;
  filtered: boolean;
  truncated: boolean;
  options: DwReferenceOption[];
};

export type DwReferenceQuery = {
  model: string;
  /** The field's own `refScope`, sent back verbatim — see {@link DwField.refScope}. */
  scope?: string | null;
  /** The value of the field named by `refFilterBy`, on THIS row. See {@link DwField.refFilterBy}. */
  filterBy?: string | null;
  search?: string | null;
  limit?: number;
};

export function listStageReferences(workshopId: string, query: DwReferenceQuery) {
  return apiFetch<DwReferencePayload>(
    `/design-workshops/${workshopId}/references${buildQuery({
      model: query.model,
      scope: query.scope ?? undefined,
      filterBy: query.filterBy ?? undefined,
      search: query.search ?? undefined,
      limit: query.limit
    })}`
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * Reference hydration — the client half of a copy the server also makes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHICH DISPLAY FIELDS A CHOSEN REFERENCE WRITES ONTO THE ROW, keyed by `"entityKey.refFieldKey"`
 * and mapping the reference payload's own `data` keys to the entity's field keys.
 *
 * THIS IS A DELIBERATE SECOND COPY OF `REFERENCE_HYDRATION` in
 * `backend/app/services/stage_schema.py` (it was declared beside `hydrate_entries` in
 * `design_workshops.py` until the registry needed to validate and publish it), and the asymmetry
 * between the two copies is the safety property, so it must not be "tidied" into a guess.
 *
 * IT IS ALSO NO LONGER THE ONLY WAY TO GET IT. The server publishes the mapping on each REF field
 * as `refHydration`, which is what the Android client reads; this table is kept only because it is
 * already correct and converting the web is a change with no defect behind it. Whichever way it is
 * read, the two must not disagree — `test_the_web_carries_the_same_hydration_table` in
 * `backend/tests/test_reference_registry.py` fails the build if they do.
 *
 * The server hydrates authoritatively inside `save_stage`, from the live record, every time. This
 * table exists only so the boxes fill in AT THE KEYBOARD — a designer who picks an artisan and then
 * watches nine empty fields stay empty until they press Save has been given a dropdown that looks
 * broken, and will start typing the name in beside it, which is the behaviour the whole feature
 * exists to end.
 *
 * WHY IT IS NOT DERIVED BY MATCHING KEY NAMES, which is the obvious simplification and is actively
 * dangerous. On `existingProduct` the reference's `data.name` is the ARTISAN's name for the artisan
 * ref and the PRODUCT's name for the product ref, and the entity has a `name` field of its own that
 * means the product. A name-matching hydration would therefore write the artisan's name into the
 * product's name box on a row nobody had finished, the server's only-fill-blanks rule would then
 * refuse to correct it, and a ministry report would print a participant's name in its product
 * table.
 *
 * WHAT A MISSING ENTRY COSTS, which is the reason this can be allowed to drift at all: nothing is
 * written here, so the designer retypes one field, and the server fills it at save regardless. An
 * entry that is WRONG costs a wrong value nobody can see is wrong. So this table fails closed — an
 * unknown `entityKey.fieldKey` hydrates nothing rather than guessing.
 */
const DW_REFERENCE_HYDRATION: Record<string, Record<string, string>> = {
  "workshopSetup.craftRef": { craftName: "craftName", craftLocalName: "craftLocalName" },
  "participant.artisanRef": {
    name: "name",
    localName: "localName",
    specialisation: "specialisation",
    experienceYears: "experienceYears",
    gender: "gender",
    phone: "phone",
    village: "village",
    photo: "photo"
  },
  "tool.toolRef": {
    name: "name",
    localName: "localName",
    material: "material",
    usedFor: "usedFor",
    cost: "cost",
    photo: "photo"
  },
  // Widened with the server's, and the two must stay in step: `Process` holds notes and hangs off
  // a product, so a step row now carries what happens and which documented product's sequence it
  // came from instead of a bare name. `steps` and `preProcessAvailable` are deliberately absent —
  // `stage_schema.REFERENCE_HYDRATION` says why beside the decision.
  "processStep.processRef": { name: "name", notes: "description", productName: "documentedFor" },
  "existingProduct.artisanRef": { name: "artisanName" },
  "existingProduct.productRef": {
    name: "name",
    category: "category",
    material: "material",
    price: "price",
    use: "use",
    photo: "productPhotos"
  },
  "prototype.productRef": { name: "productName" }
};

/** MULTI_ENUM, TAGS and IMAGE_LIST hold a list; everything else holds one value. */
function isMultiField(field: DwField): boolean {
  return field.type === "MULTI_ENUM" || field.type === "TAGS" || field.type === "IMAGE_LIST";
}

/**
 * The patch a chosen reference makes to the rest of its row.
 *
 * The rules are `hydrate_entries`' rules, restated in TypeScript because they are the same rules and
 * getting them different would be worse than not having them here at all:
 *
 * - **Only blanks are filled**, including on a brand-new row. What is already in a box is what the
 *   designer typed or accepted in the room — a name the artisan prefers, a village the master record
 *   has wrong — and a picker that reverted every correction the moment it was used would be watched
 *   doing it, which is a worse failure than retyping.
 * - **Unless the row named a DIFFERENT record before**, in which case every mapped single-value
 *   field is rewritten. Leaving the previous artisan's name beside the new artisan's id is the one
 *   outcome worse than either alternative: the report and the research data then name two different
 *   people for the same row and nothing says which was meant.
 * - **A list is only ever seeded, never replaced.** The documented product's photograph is a
 *   starting point; overwriting a gallery with it would destroy the only copy of the photographs the
 *   designer took at the workshop.
 */
export function hydrateFromReference(
  entity: DwEntity,
  refField: DwField,
  option: DwReferenceOption,
  row: DwEntryData,
  previousRefId: string
): Record<string, DwValue> {
  const mapping = DW_REFERENCE_HYDRATION[`${entity.key}.${refField.key}`];
  if (!mapping) return {};
  const replaced = Boolean(previousRefId) && previousRefId !== option.id;
  const patch: Record<string, DwValue> = {};

  for (const [sourceKey, targetKey] of Object.entries(mapping)) {
    const raw = option.data?.[sourceKey];
    if (raw === null || raw === undefined || raw === "") continue;
    const target = entity.fields.find((candidate) => candidate.key === targetKey);
    if (!target || target.deprecated) continue;
    const multi = isMultiField(target);
    if (isFilled(row[targetKey]) && (!replaced || multi)) continue;
    // Only the two JSON scalars a display field can legitimately be. Anything else — an object the
    // payload grew later, a nested record — is skipped rather than stringified: "[object Object]"
    // in a participant table is a value that looks answered and is not.
    if (typeof raw !== "string" && typeof raw !== "number") continue;
    patch[targetKey] = multi ? [String(raw)] : raw;
  }
  return patch;
}

/**
 * What to print on a reference picker's trigger for a value chosen on an earlier visit.
 *
 * A REF stores an id and nothing else, so a stage re-opened next week has `"cm3k…"` in the field and
 * no name to show — and the options that would supply one are fifty rows deep behind a search nobody
 * has typed yet. Rendering the id is the artisan-dropdown bug this repository already shipped once:
 * it asks a designer to confirm "the right artisan" while showing them twenty-five random
 * characters.
 *
 * The name is already on the row, because hydration put it there. This reads it back out through the
 * SAME mapping, so the hint and the fill can never disagree about which box holds the name.
 */
export function referenceDisplayHint(entity: DwEntity, refField: DwField, row: DwEntryData): string {
  const mapping = DW_REFERENCE_HYDRATION[`${entity.key}.${refField.key}`];
  if (!mapping) return "";
  for (const sourceKey of ["name", "craftName"]) {
    const targetKey = mapping[sourceKey];
    if (!targetKey) continue;
    const text = inputValue(row[targetKey]).trim();
    if (text) return text;
  }
  return "";
}

/* ────────────────────────────────────────────────────────────────────────────
 * Two optional server capabilities — probed, never assumed
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Whether this server offers a route at all, cached per path for the life of the tab.
 *
 * A GET is used as the probe even though both routes are POST-only, because the answer is in the
 * STATUS and a GET has no body to be misread as work: 404 means the deployment predates the
 * feature, 405 (Method Not Allowed) means the route is there. A POST probe with an empty body would
 * reach the handler on a server that has it, and "did nothing harmful this time" is not a property
 * worth relying on across a refactor of somebody else's route.
 *
 * A network failure is deliberately NOT cached as "absent". Out here the connection drops for a
 * minute at a time, and a control that disappeared for the rest of the session because of one bad
 * moment is indistinguishable from a feature that was never deployed.
 */
const routeProbes = new Map<string, Promise<boolean>>();

export function serverOffersRoute(path: string): Promise<boolean> {
  const cached = routeProbes.get(path);
  if (cached) return cached;
  const probe = apiFetch<unknown>(path)
    .then(() => true)
    .catch((error) => {
      if (error instanceof ApiError) {
        if (error.status === 404) return false;
        return true;
      }
      routeProbes.delete(path);
      return false;
    });
  routeProbes.set(path, probe);
  return probe;
}

/**
 * The id-less dictation route. **Used only to ask whether this deployment offers dictation at all.**
 *
 * It is deliberately NOT where a clip is sent any more — see {@link dictateAudio}. It survives as the
 * probe target because `serverOffersRoute` asks a question about the DEPLOYMENT ("is there a
 * transcription service configured here at all") rather than about a workshop, and asking it against
 * a per-workshop URL would need a workshop id before the button has been drawn.
 */
export const DW_DICTATE_PATH = "/design-workshops/dictate";

/**
 * Where a clip is actually sent: the per-workshop route, **because it is the only one that can
 * enforce the artisan's consent.**
 *
 * `POST /design-workshops/dictate` takes no workshop id, so it can consult no workshop's
 * `dictationConsent` column — it hands the clip to the provider chain exactly as it did before the
 * consent feature existed. The gate lives on `POST /design-workshops/{id}/dictate`, and a gate the
 * clients do not post to gates nothing: the columns are written, the decision log is kept, and an
 * artisan's recorded voice still reaches ElevenLabs. That is this repository's recurring defect —
 * a feature complete everywhere except its call site — and it is why this constant exists.
 *
 * The body is unchanged: the same `file` and the same `languageHint`. Only the URL moved.
 */
export function dwDictatePathFor(workshopId: string): string {
  return `/design-workshops/${encodeURIComponent(workshopId)}/dictate`;
}
export const DW_OCR_IDENTITY_PATH = "/design-workshops/ocr/identity";

/**
 * What `POST /design-workshops/dictate` actually returns — all five keys, and only those.
 *
 * THIS DECLARED A `language` KEY THE ROUTE HAS NEVER SENT. The echo is named `languageHint`, and the
 * type carried neither `status` nor `provider` at all. It is the same defect the REQUEST half of
 * `dictateAudio` below was just fixed for, on the same round trip, in the opposite direction — a
 * client and a server agreeing about a name neither of them checks. It cost nothing only because
 * nothing read the key; a caller that had tried would have found `undefined` and had no way to tell
 * that from "the server did not say".
 *
 * `status` is the one that earns its place: without it the caller cannot tell EMPTY from
 * RATE_LIMITED from FAILED, and those are three different next moves — see
 * {@link dictationAnswerSentence}.
 */
export type DwDictationResult = {
  /** COMPLETED | EMPTY | FAILED | RATE_LIMITED, as `ai.transcribe_audio_bytes` resolved the chain. */
  status?: string | null;
  text?: string | null;
  /** Which provider in the chain answered. Diagnostic; never shown beside the words. */
  provider?: string | null;
  /** The tag we sent, echoed. It steers nothing today — see the note in `dictateAudio`. */
  languageHint?: string | null;
  message?: string | null;
};

/**
 * What to tell a designer when the round trip worked and produced no usable words.
 *
 * **THE PROVIDER CHAIN'S OWN MESSAGE MUST NOT BE PASSED THROUGH, and that is the whole point.** For
 * a throttled provider `ai.py` composes "Transcription rate-limited (HTTP 429); will retry
 * automatically." — true of the transcription QUEUE, where a clip is requeued behind a growing
 * cooldown without consuming an attempt, and FALSE HERE. This endpoint is synchronous and stores
 * nothing: nothing retries a dictation, ever. A designer who reads that promise waits for words that
 * are never coming, and the recording they made is already gone.
 *
 * The chain also folds a throttle into the error list and resolves to FAILED when another provider
 * hard-failed as well, so the retry phrase can arrive under a status that is NOT RATE_LIMITED. That
 * is why the phrase is matched as well as the status.
 *
 * Android has had this since its ladder was built (`dwDictationServerAnswerSentence` in
 * DwDictationLadder.kt); the browser did not, and printed `result.message` verbatim. Two surfaces,
 * one endpoint, one set of sentences.
 */
export function dictationAnswerSentence(result: DwDictationResult): string {
  const status = (result.status ?? "").toUpperCase();
  const message = (result.message ?? "").trim();
  const promisesARetry = /will retry automatically/i.test(message);
  if (status === "RATE_LIMITED" || promisesARetry) {
    return "The transcription service is busy just now and could not take this recording. Wait a moment and dictate it again, or type the answer in — nothing is queued, so it will not arrive later.";
  }
  if (status === "FAILED") {
    return "The server could not transcribe that recording. Try again, or type the answer in — and if it keeps failing, tell whoever runs the server.";
  }
  // EMPTY, or a status this build has not heard of. The round trip worked and there were no words in
  // it, so the next move is about the microphone and the room rather than about the connection.
  return "The recording came back with no words in it. Speak closer to the microphone, away from the loom if you can, and try again — or type the answer in.";
}

/**
 * Server-side dictation, for the browsers that have no `SpeechRecognition` of their own.
 *
 * Firefox ships none at all and Chromium's implementation streams the audio to Google, so a cluster
 * behind a proxy that blocks it has a microphone button that lights up and never returns a word.
 * Both cases end here. The recording is posted whole rather than streamed because the fallback's job
 * is to produce a transcript at all, not to produce one live — an interim readout that cannot exist
 * is better admitted than faked.
 */
export function dictateAudio(
  blob: Blob,
  language: string,
  /**
   * The workshop whose consent governs this clip. **Required, and not optional.**
   *
   * Optional would have been the smaller diff and the wrong one: an omitted id would have to fall
   * back to the id-less route, which enforces no consent — so every call site that forgot to pass it
   * would silently send an artisan's voice to a third-party provider without the gate. Making it
   * required moves that from a runtime possibility to a compile error.
   */
  workshopId: string
): Promise<DwDictationResult> {
  const form = new FormData();
  // The extension is carried in the part name because the server picks its decoder from the MIME
  // type: Safari records `audio/mp4` and Chrome `audio/webm`, and a hardcoded ".webm" would lie
  // about the bytes for every iPhone in the field.
  form.append("file", blob, `dictation.${blob.type.includes("mp4") ? "m4a" : "webm"}`);
  // `languageHint`, NOT `language`. The route declares `languageHint: str | None = Form(default=None)`
  // and reads nothing else, so this part spent its whole life being discarded on arrival and the
  // endpoint echoed `"languageHint": null` back to a caller that had just told it the language.
  //
  // IT COST NOTHING YET, WHICH IS WHY IT SURVIVED. Nothing downstream reads the hint today —
  // `transcribe_audio_bytes` is called with the bytes, the filename and the MIME type only, and
  // Deepgram is deliberately called with `language=multi` because a workshop is code-switched
  // mid-sentence. So the bug had no symptom: no wrong transcript, no error, just a field that was
  // never there. That is exactly the shape of defect this repository keeps finding late — a client
  // and a server agreeing about a name neither of them checks — and the day the chain is taught to
  // use the hint, the browser would have been the one surface silently not sending it.
  //
  // Found from the Android side, where the same part had to be named for the first time.
  form.append("languageHint", language);
  // THE PER-WORKSHOP URL, because it is the only one that can enforce the artisan's consent. See
  // `dwDictatePathFor`. This used to post to `DW_DICTATE_PATH`, which takes no workshop id and so
  // consults no consent column — the gate was built, the columns were written, and the clip went to
  // the provider anyway.
  return apiFetch<DwDictationResult>(dwDictatePathFor(workshopId), { method: "POST", body: form });
}

/** One number the server read off the card, after its own Verhoeff filtering. */
export type DwIdentityCandidate = {
  /** The digits themselves, unconfirmed. NEVER written to a field without a human saying so. */
  value?: string | null;
  /** "AADHAAR" / "PEHCHAN" — shown so a misclassification is visible rather than silent. */
  kind?: string | null;
  /** 0–0.95; the server clamps below 1.0 on purpose. Shown, because a designer confirming needs it. */
  confidence?: number | null;
  /** AADHAAR only: "XXXX XXXX 9012", the only form a surface other than the confirm panel may print. */
  masked?: string | null;
};

/**
 * The wire shape of `POST /design-workshops/ocr/identity`.
 *
 * THESE FIELD NAMES ARE THE SERVER'S, CHECKED AGAINST IT RATHER THAN REMEMBERED. This type used to
 * declare `number`, `documentType`, `name`, `confidence` and `message` — none of which the endpoint
 * has ever sent. `IdentityOcrResult.payload()` returns the five keys below, so
 * `(result.number ?? "").replace(/\D/g, "")` in `IdentityCardReader` was always "", and a PERFECT
 * read was reported to the designer as "No number could be read from that photograph": the card
 * looked unreadable, while the reader was simply listening on the wrong keys. Android's
 * `DwIdentityOcrDto` had the identical bug against the identical payload; both are now pinned by a
 * test that starts from the server's own bytes.
 */
export type DwIdentityOcrResult = {
  aadhaarCandidates?: DwIdentityCandidate[] | null;
  pehchanCandidates?: DwIdentityCandidate[] | null;
  /**
   * How many 12-digit runs the model produced that FAILED the checksum — a count, never the values,
   * because a rejected candidate is still somebody's misread identity number. Worth showing: "3
   * readings were rejected" means the card was found and misread (better light), while "nothing was
   * read" means it was not found at all (fill the frame). Two different next actions.
   */
  rejectedAadhaarCount?: number | null;
  provider?: string | null;
  /**
   * The server stating in the payload that this is a suggestion, not a commit. Absent is read as
   * TRUE by every consumer here — an older deployment or a proxy that rewrote the body must never be
   * read as permission to write an identity number without a person.
   */
  requiresConfirmation?: boolean | null;
};

/**
 * Read an identity number off a photograph of a card. The caller must not auto-commit the answer.
 *
 * THE PHOTOGRAPH IS REDRAWN BEFORE IT LEAVES THE TAB — see `lib/identityCardImage.ts`. Every path
 * that sends a card to the server goes through this one function, so the scale-down and the loss of
 * the EXIF block (which on a field photograph holds the GPS fix, the device serial and the second
 * the card was photographed) happen once here rather than being remembered at two call sites. A
 * failure to re-encode returns the original file, so this can never be the reason a card "could not
 * be read".
 */
export async function readIdentityCard(file: File): Promise<DwIdentityOcrResult> {
  const form = new FormData();
  form.append("file", await prepareIdentityPhotograph(file));
  return apiFetch<DwIdentityOcrResult>(DW_OCR_IDENTITY_PATH, { method: "POST", body: form });
}

/** A candidate this client is willing to put in front of a designer. */
export type DwIdentityChoice = { value: string; kind: "AADHAAR" | "PEHCHAN"; confidence: number | null };

/**
 * Strip a Pehchan card number to the ONE spelling the server stores.
 *
 * Mirrors `normalize_pehchan`: everything that is not a letter or a digit goes, and the rest is
 * upper-cased. There is no checksum on a PM Vishwakarma artisan ID, so normalisation is the only
 * thing standing between one card and two differently-punctuated records of it.
 */
export function normalizePehchan(value: string | null | undefined): string {
  return (value ?? "").replace(/[^A-Za-z0-9]/g, "").toUpperCase();
}

/**
 * The candidates worth offering for `kind`, best first, each re-checked in this client.
 *
 * Pure, exported and tested (`e2e/identity-ocr-unit.spec.ts`) because it is the whole filter between
 * a model's guess and a deduplication key. `isValidAadhaar` is passed in rather than imported so
 * this module stays free of React component imports; every caller hands it
 * `AadhaarField.aadhaarValidationError`, so there is exactly one checksum on this client.
 *
 * A candidate that fails is REFUSED, not warned about: the server has already applied the same
 * Verhoeff filter, so anything that fails here is a transport or shape problem rather than a card a
 * designer can do anything about.
 */
export function identityChoices(
  result: DwIdentityOcrResult,
  kind: "AADHAAR" | "PEHCHAN" | "ANY",
  aadhaarProblem: (digits: string) => string | null
): DwIdentityChoice[] {
  const out: DwIdentityChoice[] = [];
  const push = (choice: DwIdentityChoice) => {
    if (!out.some((existing) => existing.value === choice.value)) out.push(choice);
  };
  if (kind === "AADHAAR" || kind === "ANY") {
    for (const candidate of result.aadhaarCandidates ?? []) {
      const digits = (candidate?.value ?? "").replace(/\D/g, "");
      if (!digits || aadhaarProblem(digits)) continue;
      push({ value: digits, kind: "AADHAAR", confidence: typeof candidate?.confidence === "number" ? candidate.confidence : null });
    }
  }
  if (kind === "PEHCHAN" || kind === "ANY") {
    for (const candidate of result.pehchanCandidates ?? []) {
      const cleaned = normalizePehchan(candidate?.value);
      // The same 4–32 bound `pehchan_error` applies. Shorter is not a card number, longer is not one
      // either, and both are the shape a model returns when it read a caption instead of a code.
      if (cleaned.length < 4 || cleaned.length > 32) continue;
      push({ value: cleaned, kind: "PEHCHAN", confidence: typeof candidate?.confidence === "number" ? candidate.confidence : null });
    }
  }
  return out;
}

export function previewDesignWorkshopReport(id: string, templateId?: string | null) {
  return apiFetch<DwPreview>(`/design-workshops/${id}/report/preview${buildQuery({ templateId: templateId ?? undefined })}`);
}

export function listDesignWorkshopExports(id: string) {
  return apiFetch<DwExport[]>(`/design-workshops/${id}/exports`);
}

/**
 * The export history, plus the stage timestamps a diff between two of them is built from.
 *
 * A SUPERSET of {@link listDesignWorkshopExports} rather than a replacement for it, and the two
 * differences are the whole reason it exists: this one names WHO generated each file (the column
 * has always been populated and no endpoint returned it) and carries every stage row's
 * `createdAt` / `updatedAt` / `deletedAt` — INCLUDING deleted rows, which `GET /{id}` filters out
 * and which are exactly the change a diff must not miss.
 *
 * It answers with facts only; the comparison itself is `lib/reportDiff.ts`, which is pure so that
 * flipping between generation 1 and generation 4 costs no further request. Read that file's header
 * before showing anything derived from this payload — what a stored timestamp can and cannot prove
 * is the substance of the feature.
 */
export function fetchDesignWorkshopReportHistory(id: string) {
  return apiFetch<DwReportHistory>(`/design-workshops/${id}/report-history`);
}

export function recordDesignWorkshopExport(id: string, body: DwExportRecordBody) {
  return apiFetch<{ id: string }>(`/design-workshops/${id}/exports`, {
    method: "POST",
    body: JSON.stringify(body)
  });
}

export type DwReportFile = {
  blob: Blob;
  fileName: string;
  /** Split back out of the `x-report-warnings` header, which joins them with "; ". */
  warnings: string[];
  warningCount: number;
};

const REPORT_FALLBACK_NAME: Record<string, string> = { DOCX: "report.docx", PDF: "report.pdf" };

/**
 * Generate and return one report file's bytes.
 *
 * This is the one call in the feature that cannot go through `apiFetch`: that helper reads every
 * response as JSON or TEXT, and reading a .docx as text would hand back a mangled string cast to
 * the caller's type — a download that "succeeds" and produces a file Word refuses to open. So the
 * fetch is built by hand, with the same three obligations `apiFetch` discharges: refuse the request
 * when this build has no usable API address ({@link assertApiConfigured}), attach the bearer token,
 * and turn a failure body into the real sentence the server sent rather than "[object Object]".
 *
 * The warnings — a missing required field, a photo that could not be embedded — arrive in a HEADER
 * rather than in the file, because they describe the act of generating and not the document; an
 * officer opening the .docx next month must not find a note about what was missing on the day. That
 * makes surfacing them the CALLER'S JOB, and a caller that drops them ships a report with four
 * empty stages while telling the designer it worked.
 */
export async function downloadDesignWorkshopReport(id: string, body: DwReportBody): Promise<DwReportFile> {
  assertApiConfigured();

  const headers = new Headers({ "Content-Type": "application/json" });
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(`${API_BASE}/api/design-workshops/${id}/report`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
    cache: "no-store"
  });

  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json") ? await response.json() : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload ? (payload as { detail: unknown }).detail : undefined;
    // `statusText` is empty over HTTP/2 — which every deployed request is — so it can never be the
    // last resort on its own, or a body-less failure reaches the screen as a blank error box.
    throw new ApiError(
      response.status,
      describeApiDetail(detail, response.statusText || `The server refused the request (HTTP ${response.status}).`),
      payload
    );
  }

  const raw = response.headers.get("x-report-warnings") ?? "";
  return {
    blob: await response.blob(),
    fileName: fileNameFromDisposition(response.headers.get("content-disposition")) ?? REPORT_FALLBACK_NAME[body.formats[0]] ?? "report",
    warnings: raw
      .split(";")
      .map((line) => line.trim())
      .filter(Boolean),
    warningCount: Number.parseInt(response.headers.get("x-report-warning-count") ?? "0", 10) || 0
  };
}

/**
 * The server's chosen file name, out of `content-disposition`.
 *
 * Worth honouring rather than inventing one: `_report_file_name` strips the nine characters Windows
 * forbids outright, and a report named after a craft is routinely saved onto a departmental share,
 * where a name that fails to save is a report that was not delivered.
 */
function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    // A name that is not valid percent-encoding is still a usable name; decodeURIComponent throws
    // on a bare "%" and losing the download over a literal percent sign in a craft name would be
    // absurd.
    return match[1];
  }
}

/**
 * Hand a generated file to the browser's download machinery.
 *
 * The object URL is revoked on the next task rather than immediately: revoking it in the same tick
 * as the synthetic click races the browser's own read of it, and Safari in particular ends up
 * downloading nothing at all with no error anywhere.
 */
export function saveBlobToDisk(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Value helpers — the small functions every renderer in this feature shares.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A stored value as a number, or null.
 *
 * `Number()` behind `Number.isFinite` is the house rule for MONEY and every Prisma `Decimal`: those
 * arrive as JSON STRINGS, `Number("")` is 0 (not NaN), and `Number(null)` is 0 as well — so a plain
 * `Number(value)` turns "no answer" into a real, printable zero. A cost sheet that says ₹0.00 where
 * nobody has answered yet is worse than a blank, because it will be believed.
 */
export function readNumber(value: DwValue | undefined): number | null {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "boolean") return null;
  if (Array.isArray(value) || typeof value === "object") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * A stored value as the string an `<input>`'s `value` wants.
 *
 * `String()` and not a number round trip, again for MONEY: `String(1250.10)` is "1250.1", and
 * re-seeding an input from the parsed number silently drops the trailing zero the server took care
 * to preserve. Anything list- or object-shaped returns "" because it has no single-line form.
 */
export function inputValue(value: DwValue | undefined): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (Array.isArray(value) || typeof value === "object") return "";
  return String(value);
}

/** A stored value as a list, whatever arrived. MULTI_ENUM / TAGS / IMAGE_LIST all live here. */
export function listValue(value: DwValue | undefined): string[] {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === "string");
  if (typeof value === "string" && value) return [value];
  return [];
}

/** The GEO shape, or null. Guarded because a half-written coordinate is worse than none. */
export function geoValue(value: DwValue | undefined): DwGeoValue | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const candidate = value as Partial<DwGeoValue>;
  if (!Number.isFinite(candidate.lat) || !Number.isFinite(candidate.lon)) return null;
  return {
    lat: candidate.lat as number,
    lon: candidate.lon as number,
    ...(Number.isFinite(candidate.accuracy) ? { accuracy: candidate.accuracy as number } : {})
  };
}

/**
 * Does this value count as answered?
 *
 * Character-for-character the server's `_is_filled`, and it has to stay that way: this is what the
 * form uses to decide whether to mark a required field, and `stage_completeness` is what decides
 * whether the stage may be submitted. Two different answers to "is this filled in" is a form that
 * says a stage is complete and a Save that refuses it.
 */
export function isFilled(value: DwValue | undefined): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === "string") return value.trim().length > 0;
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === "object") {
    // A RICH_TEXT document is judged on its TEXT, not on the presence of the JSON — the same
    // special case `_is_filled` makes on the server, and it has to stay in step here. An editor
    // that was focused and left alone still holds `{"blocks":[{"kind":"PARAGRAPH","spans":[]}]}`,
    // which has a key and is therefore "an object with keys": counting it as answered would put a
    // required narrative field at 100% on a stage nobody has written a word into.
    if ("blocks" in value) return !isStoredRichTextEmpty(value);
    return Object.keys(value).length > 0;
  }
  return true;
}

/** The registry fields a form actually renders: deprecated ones are dead inputs, captions belong to their media field. */
export function formFields(entity: DwEntity): DwField[] {
  return entity.fields.filter((field) => !field.deprecated && !field.captionFor);
}

/** The caption field declared for `mediaKey`, if the entity has one. */
export function captionFieldFor(entity: DwEntity, mediaKey: string): DwField | null {
  return entity.fields.find((field) => !field.deprecated && field.captionFor === mediaKey) ?? null;
}

/**
 * Split an entity's fields into the two groups the form draws.
 *
 * BASIC and STANDARD are shown together and in declaration order — the source document ordered them
 * the way a designer works through them, and re-sorting by tier would scatter a three-box question
 * across two sections. ADVANCED goes behind the disclosure.
 */
export function splitByTier(fields: DwField[]): { primary: DwField[]; advanced: DwField[] } {
  return {
    primary: fields.filter((field) => field.tier !== "ADVANCED"),
    advanced: fields.filter((field) => field.tier === "ADVANCED")
  };
}

/**
 * The title of one collection row.
 *
 * Falls back through the label field, then the first filled text-ish field, then the ordinal —
 * never to the row's id. A list of CUIDs is the same defect the artisan dropdowns shipped: it asks
 * a designer to pick "the right prototype" while showing them twenty-five random characters.
 */
export function rowTitle(entity: DwEntity, row: DwRow, index: number): string {
  const labelled = entity.labelField ? inputValue(row[entity.labelField]) : "";
  if (labelled.trim()) return labelled.trim();
  for (const field of entity.fields) {
    if (field.deprecated) continue;
    // A RICH_TEXT field has no single-line form, so `inputValue` returns "" for it and it would be
    // skipped — leaving a row whose only prose is a narrative titled "Prototype 3". `richSummary`
    // is the browser's copy of `rich_text.summary`, so the row reads the same here as it does in
    // the server's own row labels.
    if (field.type === "RICH_TEXT") {
      const summary = richSummary(row[field.key], 80);
      if (summary) return summary;
      continue;
    }
    if (field.type !== "TEXT" && field.type !== "LONG_TEXT") continue;
    const text = inputValue(row[field.key]).trim();
    if (text) return text;
  }
  return `${entity.title} ${index + 1}`;
}

/**
 * A row's registry data ready to send: `_entryId` and `_ordinal` removed, `_clientKey` KEPT.
 *
 * See {@link DwRow} for why the asymmetry is not an oversight — the server reads the client key out
 * of `data`, and dropping it turns a retry after a lost connection into a duplicated row.
 */
export function entryDataOf(row: DwRow): DwEntryData {
  const out: DwEntryData = {};
  for (const [key, value] of Object.entries(row)) {
    if (key === "_entryId" || key === "_ordinal") continue;
    out[key] = value as DwValue;
  }
  return out;
}

/** A blank row for `entity`, seeded only with the client key that keeps a re-save idempotent. */
export function blankRow(): DwRow {
  return { _clientKey: newClientKey() };
}

/**
 * A client-side idempotency key for a row that has no server id yet.
 *
 * `crypto.randomUUID` is not available on every browser this app has to run on (it needs a secure
 * context, and a field laptop reaching a LAN address over plain http is not one), so the fallback
 * is not academic — without it, adding a row would throw and the whole stage form would blank.
 */
export function newClientKey(): string {
  const cryptoApi = typeof crypto !== "undefined" ? crypto : undefined;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function") return cryptoApi.randomUUID();
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** Every stage's completeness rolled into one figure, for the list row and the header. */
export function overallPercent(completeness: Record<string, DwStageCompleteness> | undefined): number {
  const scores = Object.values(completeness ?? {});
  if (!scores.length) return 0;
  const required = scores.reduce((sum, score) => sum + score.requiredTotal, 0);
  const filled = scores.reduce((sum, score) => sum + score.requiredFilled, 0);
  // Same rule as the server's `percent`: nothing required means nothing outstanding, not 0%.
  if (required === 0) return 100;
  return Math.round((100 * filled) / required);
}

/** The plain text of a run sequence — for a heading's anchor text and an image's alt attribute. */
export function runsText(runs: DwRun[] | undefined): string {
  return (runs ?? []).map((run) => run.text).join("");
}
