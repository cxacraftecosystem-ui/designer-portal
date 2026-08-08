/**
 * Offline full-text search across one whole design workshop — the pure half.
 *
 * WHY IT EXISTS. A workshop is 22 stages, 43 entities and ~496 fields. A designer who wants to know
 * where they wrote about indigo opens stages one at a time and reads, which on a handset in a
 * courtyard is not a search, it is an afternoon. `lib/designWorkshopStore` already holds the WHOLE
 * workshop in IndexedDB, so nothing here needs a server: this is an index over bytes the device is
 * already carrying. That is the entire point — the search has to work on the fortnight with no
 * signal, because that is the fortnight the fieldwork happens in.
 *
 * NO REACT, NO FETCH, NO INDEXEDDB, exactly like `lib/marketAnalysis.ts`. Everything below is
 * (registry, draft, query) in and results out, so it is testable without a browser and reusable by
 * anything that holds a draft — including, one day, a Kotlin port for the Android capture screens.
 *
 * FOUR DECISIONS ARE LOAD-BEARING AND LOOK LIKE DETAILS.
 *
 * 1. **The tokeniser is `[\p{L}\p{M}]+`, and the `\p{M}` is the whole feature in Odia.** The survey
 *    text this application collects is Odia, Hindi and transliterated English. Odia writes a
 *    consonant cluster as base + VIRAMA + base and a vowel as base + MATRA, and both the virama and
 *    the matra are general category M (combining mark), not L. Drop `\p{M}` and ତନ୍ତ ("tanta",
 *    loom) stops being one word and becomes the fragments ତନ and ତ. `market_analysis.py` was
 *    written with exactly this character class and it is called out at the top of that file and of
 *    `lib/marketAnalysis.ts`; JavaScript's `\w` has the same defect as Python's `\w`.
 *
 *    WHAT IT COSTS *HERE* is worth stating precisely, because it is not quite what it costs there.
 *    A prefix probe shatters the QUERY the same way it shattered the index, so an exact Odia query
 *    still limps back with the right row some of the time — and then:
 *      • the highlight lands on a fragment, and the designer is shown `[ତନ]୍[ତ]`, their own word
 *        broken across the mark that holds it together;
 *      • ତ alone becomes an indexed token, so every Odia word merely CONTAINING that consonant
 *        matches — "ତନ ଓ ତାର" is returned for a search for "ତନ୍ତ", which is a hit a designer
 *        cannot tell from a real one;
 *      • exactness is meaningless, so the ranking that puts the right field first collapses.
 *    In `market_analysis.py` the same error was fatal outright rather than corrosive, because a
 *    minimum-token-length floor then discarded the fragments and left nothing at all. Either way
 *    the class is the bug; all three symptoms have a test in
 *    `e2e/design-workshop-search-unit.spec.ts` and all three fail the moment `\p{M}` is removed.
 *
 * 2. **The fold strips U+0300–U+036F and NOTHING ELSE.** Folding diacritics is what lets "Meher"
 *    find "Mehér" and "Sambalpuri" find "Sāmbalpurī", which matters because transliterated Odia is
 *    spelled three ways by three people in the same workshop. But the naive spelling of that rule —
 *    NFKD, then delete every category-M character — is the bug in decision 1 wearing a different
 *    hat: it would delete the virama and the matras and reduce ରଙ୍ଗ to ରଗ, a word that exists
 *    nowhere. U+0300–U+036F is the Combining Diacritical Marks block, which is where NFKD puts the
 *    macron of ā and the dot-below of ṭ, and it contains no Indic mark at all — every Odia and
 *    Devanagari mark lives inside its own script block (U+0B00–U+0B7F, U+0900–U+097F). So the
 *    narrow range is both the useful rule and the safe one.
 *
 * 3. **A match is a token PREFIX, never a bare substring.** Typing "indi" finds "indigo"; typing
 *    "digo" does not. That is what an index buys — the alternative is re-scanning every character
 *    of the workshop on every keystroke — and it is also the semantics a designer expects from a
 *    search box. Highlighting follows the same rule, so what is marked on screen is exactly what
 *    matched, rather than a second opinion that occasionally disagrees with the ranking.
 *
 * 4. **A REF is indexed by its resolved LABEL or not at all.** A REF field stores a cuid. Indexing
 *    the cuid would let a designer "find" a hit whose snippet is twenty-five random characters,
 *    which is the artisan-dropdown defect this repository already shipped once. So a reference is
 *    resolved against the rows this device already holds (every `DwSketch`, `DwPrototype`,
 *    `DwParticipant`, `DwCostSheet`… named by a REF is a row of this same workshop) or, for the
 *    four models that live outside the workshop, against the display name the picker hydrated onto
 *    the row — {@link referenceDisplayHint}, which is offline by construction because the name is
 *    already sitting in the record. When neither answers, the value is left out. Silence is the
 *    honest result; a cuid in a result list is not.
 *
 * WHAT IS DELIBERATELY NOT INDEXED. Media fields (IMAGE, IMAGE_LIST, FILE, AUDIO, VIDEO) hold media
 * ids, which are cuids for the same reason a REF is — their CAPTIONS are indexed instead, and a
 * caption hit navigates to the media field it belongs to, because that is where the caption box is
 * drawn. Numbers, dates, booleans and coordinates are not text a person wrote and a search over
 * them returns matches nobody can read; the fields that state a price in prose are RICH_TEXT and
 * are indexed. Deprecated fields are skipped because there is no control on any form to navigate
 * to — a result a designer cannot reach is not a result.
 */

import type { DwDraft, DwDraftStage } from "@/lib/designWorkshopStore";
import {
  inputValue,
  listValue,
  referenceDisplayHint,
  rowTitle,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwRegistry,
  type DwRow,
  type DwStage,
  type DwValue
} from "@/lib/designWorkshops";
import { fromStored, toPlain } from "@/lib/richText";

/* ────────────────────────────────────────────────────────────────────────────
 * The rules
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One token: a maximal run of letters and combining marks, OR a maximal run of digits.
 *
 * The first alternative is `[\p{L}\p{M}]+` verbatim — see decision 1 in the file header for why the
 * `\p{M}` cannot be dropped and what it costs in Odia when it is.
 *
 * The second alternative is a separate rule with a separate reason rather than a widening of the
 * first: a designer looks for "PT-01", "DPDW/0417" and "1650" as often as for a word, and a
 * letters-only tokeniser cannot see any of them, so a product code would be the one thing in the
 * workshop that could not be searched for. Keeping the runs separate is what makes "PT-01" tokenise
 * to `pt` + `01` and match a value spelled "PT‑01" with a non-breaking hyphen, or "PT 01".
 *
 * WHAT THAT COSTS, stated rather than discovered: a GROUPING SEPARATOR is a boundary like any other
 * punctuation, so "₹1,650" is two tokens, `1` and `650`. Typing "1,650" or "650" finds it; typing
 * "1650" does not. Normalising commas away inside a digit run was considered and refused — the
 * separator has to survive into the snippet's character offsets for the highlight to land on the
 * right characters, and a rule that quietly rewrote a designer's prices in one place and not the
 * other is worse than a boundary anybody can see.
 */
const TOKEN_RUN = /[\p{L}\p{M}]+|\p{N}+/gu;

/**
 * The Unicode Combining Diacritical Marks block, and deliberately nothing wider.
 *
 * See decision 2 in the file header. Widening this to `\p{M}` would delete the virama and every
 * matra, shatter Odia and Hindi, and turn this feature into the thing it exists to replace.
 */
const COMBINING_DIACRITICS = /[\u0300-\u036F]/g;

/**
 * Below this many characters a query is not searched at all.
 *
 * One letter matches most of the workshop and the result list is then a list of everything, which
 * is the answer a designer already had by opening the stages. The UI says so rather than drawing an
 * empty panel that looks broken.
 */
export const MIN_QUERY_CHARS = 2;

/**
 * The most results handed back.
 *
 * A cap is unavoidable — "ba" against a full workshop matches a fifth of it — and an un-announced
 * cap is this repository's most-repeated bug, so {@link WorkshopSearchResult} carries the true
 * total beside the capped list and the panel prints both. Sixty rather than several hundred because
 * every returned hit costs a snippet, and a designer who is looking at 143 results does not need
 * the 143rd drawn — they need to be told there are 143 and to type another word.
 */
export const MAX_HITS = 60;

/** How much of the surrounding sentence a snippet carries either side of the match, in characters. */
const SNIPPET_RADIUS = 90;

/** How far a window edge may travel to land on a space rather than mid-word. */
const SNIPPET_SNAP = 24;

/** The most highlighted runs drawn in one snippet — a value with 400 hits needs no more to be read. */
const MAX_HIGHLIGHTS = 12;

/**
 * An exact token equals the term; a longer token merely starts with it. Both are hits and the exact
 * one ranks first, so typing "tanta" puts the field that says "tanta" above the one that says
 * "tantakara". Two, not ten: this nudges the order, it does not bury a partial match.
 */
const SCORE_EXACT = 2;
const SCORE_PREFIX = 1;

/* ────────────────────────────────────────────────────────────────────────────
 * Folding
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A map from each UTF-16 unit of a folded string back into the string it came from.
 *
 * Two arrays because one source code point may fold to several units (NFKD turns ǳ into three) and
 * several may fold to none (a stray combining acute). Null means the identity map, which is what a
 * run of ASCII always is — see {@link fold}.
 */
type FoldOffsets = {
  startAt: number[];
  endAt: number[];
};

/**
 * The one folding rule, used for the index, for the query and for the snippet.
 *
 * `offsets` is filled only when the caller needs to point back at the original text — that is, for
 * the handful of results about to be DRAWN. Building the map for every value in the workshop would
 * cost two integers per character of a quarter-megabyte of prose, on the laptop least able to spare
 * it, to answer a question nobody asked about 99% of them.
 *
 * ASCII IS BATCHED, AND THAT IS THE DIFFERENCE BETWEEN 100ms AND 5ms. The obvious spelling of this
 * function calls `normalize("NFKD")` once per code point, and measured on the flagship workshop that
 * alone was 28ms per 111,000 characters — because almost no real value is *entirely* ASCII (one ₹,
 * one em dash or one Odia local name is enough to lose a whole-string fast path), so the slow branch
 * was taken for essentially every field. Below U+0080 there is no decomposition, no combining mark
 * and no case mapping that is not one unit in and one unit out, so a RUN of ASCII folds to
 * `toLowerCase()` with an identity map — provably, not approximately. Only the genuinely non-ASCII
 * code points pay for `normalize`, and in this corpus they are a few percent of the characters.
 */
function fold(text: string, offsets: FoldOffsets | null): string {
  let out = "";
  let index = 0;
  const length = text.length;

  while (index < length) {
    let ascii = index;
    while (ascii < length && text.charCodeAt(ascii) < 0x80) ascii += 1;
    if (ascii > index) {
      out += text.slice(index, ascii).toLowerCase();
      if (offsets) {
        for (let at = index; at < ascii; at += 1) {
          offsets.startAt.push(at);
          offsets.endAt.push(at + 1);
        }
      }
      index = ascii;
      continue;
    }

    // One non-ASCII code point — read as a CODE POINT so a surrogate pair is decomposed as the one
    // character it is rather than as two halves that normalise to nothing.
    const codePoint = String.fromCodePoint(text.codePointAt(index) as number);
    const width = codePoint.length;
    const piece = codePoint.normalize("NFKD").replace(COMBINING_DIACRITICS, "").toLowerCase();
    out += piece;
    if (offsets) {
      // Per UTF-16 UNIT, not per code point: the arrays are indexed by `folded.charAt(i)` and an
      // astral letter is two units. Pushing once per code point would slide every later offset by
      // one and put the highlight on the wrong word from the first emoji onwards.
      for (let unit = 0; unit < piece.length; unit += 1) {
        offsets.startAt.push(index);
        offsets.endAt.push(index + width);
      }
    }
    index += width;
  }
  return out;
}

/**
 * Case- and diacritic-folded text, for comparison only.
 *
 * Exported because the query, the index and the tests must all fold identically — a search box that
 * folds one way and an index that folds another is a search that silently misses.
 */
export function foldForSearch(text: string): string {
  return fold(text, null);
}

/**
 * The tokens of already-folded text, in order.
 *
 * An `exec` loop rather than `match` or `matchAll`. All three were measured over the flagship
 * workshop's 227,000 characters and 38,000 tokens and they land within noise of one another (21ms,
 * 21ms, 26ms); `exec` is chosen because it is the only one of the three that does not allocate a
 * result array per token, and this runs over every value in the workshop.
 *
 * `lastIndex` is reset on the way IN because {@link buildSnippet} shares this module-level `g`
 * regex and abandons its own scan part-way; a scan that silently started in the middle of the
 * previous one would index the tail of a value and nothing else.
 */
function tokensOf(folded: string): string[] {
  const out: string[] = [];
  TOKEN_RUN.lastIndex = 0;
  for (let match = TOKEN_RUN.exec(folded); match; match = TOKEN_RUN.exec(folded)) out.push(match[0]);
  return out;
}

/**
 * The distinct tokens of a raw string — the whole tokenising rule in one call.
 *
 * Exported for the tests, which have to be able to state what "ତନ୍ତ is one token" means without
 * building a workshop around it.
 */
export function tokeniseForSearch(text: string): string[] {
  return tokensOf(foldForSearch(text));
}

/* ────────────────────────────────────────────────────────────────────────────
 * The index
 * ──────────────────────────────────────────────────────────────────────────── */

/** One highlighted or unhighlighted run of a snippet. Segments, not HTML — see {@link buildSnippet}. */
export type SnippetSegment = { text: string; match: boolean };

export type SearchSnippet = {
  segments: SnippetSegment[];
  /** Text was dropped before the window, so the caller draws a leading ellipsis. */
  clippedStart: boolean;
  /** …and after it. */
  clippedEnd: boolean;
};

/** Where a hit is, in the words the stage index and the form already use for those places. */
export type WorkshopSearchHit = {
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  entityKey: string;
  /** "Participants" — the entity's own title, as the form's heading prints it. */
  entityTitle: string;
  /**
   * The row this answer belongs to, titled the way `CollectionTable` titles it, or null for a
   * singleton entity. A designer recognises "Sri Ghanashyam Meher", not "row 7".
   */
  recordTitle: string | null;
  /**
   * The row's `_clientKey` (or its server `_entryId` when it has never been keyed locally), so the
   * stage page can open the right row. Null for a singleton.
   */
  rowKey: string | null;
  fieldKey: string;
  fieldLabel: string;
  /**
   * The field key to SCROLL TO, which is `fieldKey` except for a caption: a caption input is drawn
   * underneath the media field it describes and has no wrapper of its own, so its anchor is the
   * media field's. See `captionFor` in the registry.
   */
  anchorFieldKey: string;
  snippet: SearchSnippet;
  /** Higher is a better match. See {@link SCORE_EXACT}; ties break in workshop order. */
  score: number;
};

/** One indexed value. Private: the shape is an implementation detail of {@link searchWorkshop}. */
type IndexEntry = {
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  entityKey: string;
  entityTitle: string;
  recordTitle: string | null;
  rowKey: string | null;
  fieldKey: string;
  fieldLabel: string;
  anchorFieldKey: string;
  /** The human-readable text, exactly as it will be quoted back in a snippet. */
  text: string;
  /** {@link foldForSearch} of `text`, kept so a hit does not re-fold a megabyte to be confirmed. */
  folded: string;
  /** Declaration order across the whole workshop, so equal scores sort into reading order. */
  order: number;
};

/**
 * The searchable form of one workshop.
 *
 * `tokens` is sorted and `postings` runs parallel to it, which is what makes a prefix probe a binary
 * search and a walk rather than a pass over every distinct word on every keystroke.
 */
export type WorkshopSearchIndex = {
  entries: IndexEntry[];
  /** Every distinct token in the workshop, sorted by code unit. */
  tokens: string[];
  /** For each token, the entries that contain it, ascending and deduplicated. */
  postings: number[][];
  /** How many field values were indexed. The panel prints it so "no hits" is never ambiguous. */
  fieldCount: number;
  /** How many stages contributed at least one value. */
  stageCount: number;
  /** How long the build took, in milliseconds — measured, never guessed. */
  buildMs: number;
};

/** An empty index, for a page that has a registry but no draft yet. */
export function emptyWorkshopSearchIndex(): WorkshopSearchIndex {
  return { entries: [], tokens: [], postings: [], fieldCount: 0, stageCount: 0, buildMs: 0 };
}

/**
 * Every entity in the registry that a REF can name, keyed by the model name a REF field declares.
 *
 * `DwField.refModel` is the entity's `name` ("DwSketch", "DwPrototype", …) and `DwEntity.name` is
 * the same string, so this join needs no table to maintain and picks up a new referenceable entity
 * the day the registry declares one. The four models that are NOT entities of this registry —
 * Craft, Artisan, Process, ToolDocumentation, ProductDocumentation — are records the rest of the
 * repository owns and cannot be resolved offline from a workshop draft at all; they fall through to
 * the hydrated display name on the row instead.
 */
function entitiesByModel(registry: DwRegistry): Map<string, DwEntity> {
  const out = new Map<string, DwEntity>();
  for (const stage of registry.stages) {
    for (const entity of stage.entities) out.set(entity.name, entity);
  }
  return out;
}

/**
 * Every collection row in the workshop, keyed by the id a REF would store.
 *
 * Keyed by `_entryId` because that is what a reference picker writes — but `_clientKey` is keyed too
 * and costs nothing, so a workshop whose rows were created on this device and have not yet been
 * given server ids still resolves rather than silently degrading to "no label" for a whole day of
 * offline work.
 */
function rowsById(registry: DwRegistry, draft: DwDraft): Map<string, { entity: DwEntity; row: DwRow; index: number }> {
  const out = new Map<string, { entity: DwEntity; row: DwRow; index: number }>();
  for (const stage of registry.stages) {
    const stored = draft.stages[stage.key];
    if (!stored) continue;
    for (const entity of stage.entities) {
      if (entity.cardinality !== "COLLECTION") continue;
      const rows = stored.collections[entity.key] ?? [];
      rows.forEach((row, index) => {
        const entryId = typeof row._entryId === "string" ? row._entryId : "";
        const clientKey = typeof row._clientKey === "string" ? row._clientKey : "";
        if (entryId) out.set(entryId, { entity, row, index });
        if (clientKey) out.set(clientKey, { entity, row, index });
      });
    }
  }
  return out;
}

/** The label of one ENUM token, as a human sees it on the form. */
function enumLabel(registry: DwRegistry, field: DwField, token: string): string {
  const options = field.options ?? (field.enum ? registry.enums[field.enum] : undefined) ?? [];
  const match = options.find((option) => option.value === token);
  // A token with no option left in the registry is NOT dropped. It is a real answer a designer gave
  // against a list that has since changed, and hiding it from the search is how a record becomes
  // unfindable because somebody edited an enum. The raw token is the only name it still has.
  return match ? match.label : token;
}

/**
 * The label a REF stands for, or "" when this device cannot honestly produce one.
 *
 * Two resolutions, in this order, and no third:
 *
 *  1. **A row of this workshop.** Most REF fields point at another Dw* entity — a prototype names
 *     its sketch, a cost line names its cost sheet — and that row is already in this draft. Titling
 *     it with `rowTitle` is what makes the search result read the same as the list the designer
 *     picked it from.
 *  2. **The hydrated display name already on the row.** `Craft`, `Artisan`, `Process`,
 *     `ToolDocumentation` and `ProductDocumentation` live outside the workshop, so there is nothing
 *     here to look them up in — but the picker copied the name onto the row when it was chosen, and
 *     `referenceDisplayHint` reads it back through the same mapping that wrote it. Offline by
 *     construction.
 *
 * Anything else returns "" and is left out of the index. See decision 4 in the file header.
 */
function refLabel(
  models: Map<string, DwEntity>,
  rows: Map<string, { entity: DwEntity; row: DwRow; index: number }>,
  entity: DwEntity,
  field: DwField,
  record: DwEntryData,
  value: string
): string {
  const target = rows.get(value);
  if (target && (!field.refModel || models.get(field.refModel)?.key === target.entity.key)) {
    return rowTitle(target.entity, target.row, target.index);
  }
  return referenceDisplayHint(entity, field, record);
}

/**
 * The searchable text of one stored value, or "" when the field holds nothing a person can read.
 *
 * The `switch` has no `default` that stringifies the rest on purpose: a MONEY field would index as
 * "1650.00" and a GEO as "[object Object]", and a result whose snippet is a coordinate is a result
 * that wastes the one screen a designer has. New TEXTUAL types have to be added here deliberately.
 */
function searchableText(
  registry: DwRegistry,
  models: Map<string, DwEntity>,
  rows: Map<string, { entity: DwEntity; row: DwRow; index: number }>,
  entity: DwEntity,
  field: DwField,
  record: DwEntryData
): string {
  const value: DwValue | undefined = record[field.key];
  if (value === null || value === undefined) return "";
  switch (field.type) {
    case "TEXT":
    case "LONG_TEXT":
    case "URL":
    case "EMAIL":
    case "PHONE":
      return inputValue(value).trim();
    case "RICH_TEXT":
      // Through `toPlain(fromStored(...))` and NEVER the raw JSON: indexing the stored document
      // would make every narrative match "blocks", "spans" and "PARAGRAPH", and the snippet would
      // quote a serialisation at a designer instead of their own sentence. `toPlain` is also what
      // keeps a bulleted list reading as a list rather than as one run-on line.
      return toPlain(fromStored(value)).trim();
    case "TAGS":
      return listValue(value).join(" · ");
    case "ENUM":
      return enumLabel(registry, field, inputValue(value).trim());
    case "MULTI_ENUM":
      return listValue(value)
        .map((token) => enumLabel(registry, field, token))
        .join(" · ");
    case "REF":
      return refLabel(models, rows, entity, field, record, inputValue(value).trim());
    default:
      return "";
  }
}

/**
 * Build the whole searchable index for one draft. Once per draft load; never per keystroke.
 *
 * The singleton is read PER ENTITY out of `DwDraftStage.singletons` rather than through
 * `stageDataOf`, which flattens every singleton entity into one map. The flattening is lossless
 * today (a stage declares at most one singleton) but it throws away which entity an answer belonged
 * to, and this index has to name that entity on screen — a result that says only "stage 4" sends a
 * designer back to reading the stage.
 */
export function buildWorkshopSearchIndex(registry: DwRegistry, draft: DwDraft): WorkshopSearchIndex {
  const startedAt = now();
  const models = entitiesByModel(registry);
  const rows = rowsById(registry, draft);

  const entries: IndexEntry[] = [];
  const stagesWithText = new Set<string>();
  let order = 0;

  for (const stage of registry.stages) {
    const stored: DwDraftStage | undefined = draft.stages[stage.key];
    if (!stored) continue;
    const before = entries.length;
    for (const entity of stage.entities) {
      if (entity.cardinality === "SINGLETON") {
        const record = stored.singletons[entity.key];
        if (!record) continue;
        order = collect(registry, models, rows, stage, entity, record, null, null, entries, order);
      } else {
        const collection = stored.collections[entity.key] ?? [];
        collection.forEach((row, index) => {
          const rowKey =
            (typeof row._clientKey === "string" && row._clientKey) ||
            (typeof row._entryId === "string" && row._entryId) ||
            null;
          order = collect(
            registry,
            models,
            rows,
            stage,
            entity,
            row,
            recordTitleOf(models, rows, entity, row, index),
            rowKey,
            entries,
            order
          );
        });
      }
    }
    // "A stage that has a draft record" and "a stage that has a word in it" are different facts, and
    // the panel prints the second: opening a stage banks an empty record, so counting stored stages
    // would tell a designer the search covers 22 stages when nineteen of them hold nothing.
    if (entries.length > before) stagesWithText.add(stage.key);
  }

  // The postings are built in one pass over the entries and then sorted once, rather than kept
  // sorted as they grow: a Map of arrays is O(1) per token and the single sort at the end is what
  // turns "which words start with this" into a binary search instead of a scan over every distinct
  // word in the workshop, on every keystroke.
  const postingsByToken = new Map<string, number[]>();
  entries.forEach((entry, entryIndex) => {
    const seen = new Set<string>();
    for (const token of tokensOf(entry.folded)) {
      if (seen.has(token)) continue;
      seen.add(token);
      const bucket = postingsByToken.get(token);
      if (bucket) bucket.push(entryIndex);
      else postingsByToken.set(token, [entryIndex]);
    }
  });

  const tokens = [...postingsByToken.keys()].sort(compareCodeUnits);
  const postings = tokens.map((token) => postingsByToken.get(token) as number[]);

  return {
    entries,
    tokens,
    postings,
    fieldCount: entries.length,
    stageCount: stagesWithText.size,
    buildMs: now() - startedAt
  };
}

/** `performance.now()` where it exists — a Node test process and an old handset both still work. */
function now(): number {
  return typeof performance !== "undefined" && typeof performance.now === "function" ? performance.now() : Date.now();
}

/** Code-unit order, so a prefix's matches are always one contiguous run of {@link tokens}. */
function compareCodeUnits(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/**
 * What to call one collection row in a result.
 *
 * `rowTitle` is the form's own rule and is used unchanged wherever it can answer — a search result
 * has to name a row the way the list the designer will land on names it. The one place it cannot is
 * an entity whose `labelField` is itself a REF (`sketchReview.sketchRef`, `costSheet.productRef`,
 * `certificate.participantRef`, `followUp.productRef`, `prototypeValidation.prototypeRef`), where it
 * would title the row with a cuid. Those resolve through the same reference resolver the index uses,
 * so the row reads "Phoda kumbha table runner" rather than "cmsik8g9n007th8xcx2lhb323".
 */
function recordTitleOf(
  models: Map<string, DwEntity>,
  rows: Map<string, { entity: DwEntity; row: DwRow; index: number }>,
  entity: DwEntity,
  row: DwRow,
  index: number
): string {
  const labelField = entity.labelField ? entity.fields.find((field) => field.key === entity.labelField) : undefined;
  if (labelField?.type === "REF") {
    const resolved = refLabel(models, rows, entity, labelField, row, inputValue(row[labelField.key]).trim());
    if (resolved.trim()) return resolved.trim();
  }
  return rowTitle(entity, row, index);
}

/** Index every readable field of one record. Returns the next declaration order. */
function collect(
  registry: DwRegistry,
  models: Map<string, DwEntity>,
  rows: Map<string, { entity: DwEntity; row: DwRow; index: number }>,
  stage: DwStage,
  entity: DwEntity,
  record: DwEntryData,
  recordTitle: string | null,
  rowKey: string | null,
  out: IndexEntry[],
  order: number
): number {
  let next = order;
  for (const field of entity.fields) {
    // A deprecated field is a dead input: no form draws it, so a hit on one could not be navigated
    // to and would be a result that goes nowhere.
    if (field.deprecated) continue;
    const text = searchableText(registry, models, rows, entity, field, record);
    if (!text) continue;
    out.push({
      stageKey: stage.key,
      stageNumber: stage.number,
      stageTitle: stage.title,
      entityKey: entity.key,
      entityTitle: entity.title,
      recordTitle,
      rowKey,
      fieldKey: field.key,
      fieldLabel: field.label,
      // A caption has no control of its own — `FieldGrid` hands it to the media field to draw
      // underneath itself — so its anchor is the media field it captions.
      anchorFieldKey: field.captionFor ?? field.key,
      text,
      folded: foldForSearch(text),
      order: next
    });
    next += 1;
  }
  return next;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Searching
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Why a result list is what it is.
 *
 * IDLE and NO_TERMS are different facts and the panel says different things about them: an empty box
 * shows nothing at all (an empty query must never show everything), while a box holding "₹₹" has
 * been typed into and deserves to be told that there is nothing in it to search for.
 */
export type WorkshopSearchStatus = "IDLE" | "TOO_SHORT" | "NO_TERMS" | "SEARCHED";

export type WorkshopSearchResult = {
  hits: WorkshopSearchHit[];
  /** Fields that matched, BEFORE {@link MAX_HITS}. Printed, never swallowed. */
  total: number;
  truncated: boolean;
  status: WorkshopSearchStatus;
  /** The tokens the query was actually understood as — the panel can say so when it surprises. */
  terms: string[];
};

/**
 * An empty answer, minted fresh each time.
 *
 * A shared constant would hand every caller the SAME `hits` and `terms` arrays, and one caller that
 * sorted its results in place would reorder somebody else's — the class of bug that is invisible
 * until two panels are on screen at once.
 */
function nothing(status: WorkshopSearchStatus, terms: string[] = []): WorkshopSearchResult {
  return { hits: [], total: 0, truncated: false, status, terms };
}

/**
 * Run one query against a built index.
 *
 * AN EMPTY QUERY RETURNS NOTHING, not everything. A list of 4,000 fields is not an answer, it is the
 * workshop again in a smaller box, and it would make the panel expensive to render on every load of
 * a page whose job is the stage index.
 *
 * Terms are ANDed. Typing more words narrows, which is the only behaviour that makes an incremental
 * search worth typing into: "kumbha border" finds the field that holds both words and not the
 * eighteen that hold either.
 */
export function searchWorkshop(index: WorkshopSearchIndex, query: string): WorkshopSearchResult {
  const raw = query.trim();
  if (!raw) return nothing("IDLE");
  // Counted in CODE POINTS, the way `lib/marketAnalysis.ts` counts them: `.length` reads an astral
  // letter as two and would let a one-character query through a two-character floor.
  if ([...raw].length < MIN_QUERY_CHARS) return nothing("TOO_SHORT");

  const terms = tokeniseForSearch(raw);
  if (!terms.length) return nothing("NO_TERMS", terms);

  /** entry index -> score so far. Rebuilt per term and intersected, so AND falls out of the loop. */
  let surviving: Map<number, number> | null = null;
  for (const term of terms) {
    const perTerm = new Map<number, number>();
    for (const tokenIndex of prefixRange(index.tokens, term)) {
      const weight = index.tokens[tokenIndex] === term ? SCORE_EXACT : SCORE_PREFIX;
      for (const entryIndex of index.postings[tokenIndex]) {
        // The BEST way this term matched this entry, not the sum: a field holding "tanta tantakara"
        // must not outrank one holding "tanta" merely by repeating a prefix.
        const existing = perTerm.get(entryIndex);
        if (existing === undefined || existing < weight) perTerm.set(entryIndex, weight);
      }
    }
    if (surviving === null) {
      surviving = perTerm;
    } else {
      const merged = new Map<number, number>();
      // Walk the SMALLER map. A common word ("the") has thousands of postings and a rare one
      // ("phoda") has three; iterating the rare side is what keeps a two-word query as cheap as the
      // rarest word in it.
      const [small, large] = surviving.size <= perTerm.size ? [surviving, perTerm] : [perTerm, surviving];
      for (const [entryIndex, score] of small) {
        const other = large.get(entryIndex);
        if (other !== undefined) merged.set(entryIndex, score + other);
      }
      surviving = merged;
    }
    if (!surviving.size) break;
  }

  const matched = surviving ?? new Map<number, number>();
  const ranked = [...matched.entries()].sort((a, b) => {
    if (b[1] !== a[1]) return b[1] - a[1];
    // Equal relevance falls back to WORKSHOP ORDER — stage 1 before stage 22, and within a stage the
    // declaration order the form draws. A designer scanning the list is then reading it in the order
    // they wrote it, which is the only ordering they can predict.
    return index.entries[a[0]].order - index.entries[b[0]].order;
  });

  const hits = ranked.slice(0, MAX_HITS).map(([entryIndex, score]) => {
    const entry = index.entries[entryIndex];
    return {
      stageKey: entry.stageKey,
      stageNumber: entry.stageNumber,
      stageTitle: entry.stageTitle,
      entityKey: entry.entityKey,
      entityTitle: entry.entityTitle,
      recordTitle: entry.recordTitle,
      rowKey: entry.rowKey,
      fieldKey: entry.fieldKey,
      fieldLabel: entry.fieldLabel,
      anchorFieldKey: entry.anchorFieldKey,
      snippet: buildSnippet(entry.text, terms),
      score
    } satisfies WorkshopSearchHit;
  });

  return {
    hits,
    total: ranked.length,
    truncated: ranked.length > hits.length,
    status: "SEARCHED",
    terms
  };
}

/**
 * The indices of every token in `tokens` that starts with `term`.
 *
 * Binary search for the lower bound, then walk while the prefix holds. Sorting by code unit is what
 * makes that walk correct: every string beginning with a prefix sorts contiguously after it.
 */
function prefixRange(tokens: string[], term: string): number[] {
  let low = 0;
  let high = tokens.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (tokens[middle] < term) low = middle + 1;
    else high = middle;
  }
  const out: number[] = [];
  for (let index = low; index < tokens.length && tokens[index].startsWith(term); index += 1) out.push(index);
  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Snippets
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A window of `text` around the first match, with the matched tokens marked.
 *
 * SEGMENTS, NOT MARKUP. Returning a string with `<mark>` in it would oblige every caller to render
 * it with `dangerouslySetInnerHTML`, and the text being highlighted is prose a designer typed —
 * including, legitimately, angle brackets. Segments cannot inject anything by construction.
 *
 * The highlight is placed on TOKENS that start with a term, which is the same rule that decided the
 * result was a result. Highlighting every substring occurrence instead would mark "indigo" inside
 * "indigoid" on a search for "indi" — correct — and also mark "digo" inside "indigo" on a search for
 * "digo", which did not match and never should be seen to.
 */
function buildSnippet(text: string, terms: string[]): SearchSnippet {
  const offsets: FoldOffsets = { startAt: [], endAt: [] };
  const folded = fold(text, offsets);

  // Scanned with `exec` rather than `matchAll` so the walk can STOP. A stage-13 narrative runs to
  // three thousand characters and holds four hundred tokens, of which the snippet can use at most
  // the handful inside one window; tokenising all of them, for every result, for every keystroke,
  // is what made a common query take 30ms on the flagship workshop. The regex's `lastIndex` is
  // reset first because it is a module-level object shared with `tokensOf`, and a `g`
  // regex that starts mid-string silently returns a different answer.
  const ranges: Array<{ from: number; to: number }> = [];
  let ceiling = Number.POSITIVE_INFINITY;
  TOKEN_RUN.lastIndex = 0;
  for (let match = TOKEN_RUN.exec(folded); match; match = TOKEN_RUN.exec(folded)) {
    const from = match.index;
    if (from > ceiling || ranges.length >= MAX_HIGHLIGHTS) break;
    if (!terms.some((term) => match[0].startsWith(term))) continue;
    ranges.push({ from, to: from + match[0].length });
    // Everything past the first match's window is discarded below, so there is no reason to read it.
    if (ranges.length === 1) ceiling = from + match[0].length + SNIPPET_RADIUS;
  }
  TOKEN_RUN.lastIndex = 0;

  if (!ranges.length) {
    // Nothing to anchor on. This happens when the caller asks for a snippet of a value that did not
    // match — and, defensively, if the two folds ever disagreed. Quoting the head of the field is
    // still useful and is never wrong; inventing a highlight would be.
    const head = text.slice(0, SNIPPET_RADIUS * 2);
    return {
      segments: [{ text: collapse(head), match: false }],
      clippedStart: false,
      clippedEnd: head.length < text.length
    };
  }

  const anchor = ranges[0];
  const windowStart = snapForward(folded, Math.max(0, anchor.from - SNIPPET_RADIUS), anchor.from);
  const windowEnd = snapBack(folded, Math.min(folded.length, anchor.to + SNIPPET_RADIUS), anchor.to);

  const segments: SnippetSegment[] = [];
  let cursor = windowStart;
  for (const range of ranges) {
    if (range.to <= windowStart || range.from >= windowEnd) continue;
    const from = Math.max(range.from, windowStart);
    const to = Math.min(range.to, windowEnd);
    if (from > cursor) push(segments, slice(text, offsets, cursor, from), false);
    push(segments, slice(text, offsets, from, to), true);
    cursor = to;
  }
  if (cursor < windowEnd) push(segments, slice(text, offsets, cursor, windowEnd), false);

  return {
    segments,
    clippedStart: windowStart > 0,
    clippedEnd: windowEnd < folded.length
  };
}

/** The original text behind a folded range. Empty when the range is empty — never undefined. */
function slice(text: string, offsets: FoldOffsets, from: number, to: number): string {
  if (to <= from) return "";
  const start = offsets.startAt[from] ?? 0;
  const end = offsets.endAt[to - 1] ?? text.length;
  return text.slice(start, end);
}

function push(segments: SnippetSegment[], text: string, match: boolean): void {
  const collapsed = collapse(text);
  if (collapsed) segments.push({ text: collapsed, match });
}

/**
 * Runs of whitespace become one space.
 *
 * A RICH_TEXT field flattens to prose with newlines and list markers in it, and a snippet that kept
 * them would draw four blank lines in the middle of a result row. Done on the way OUT, after every
 * offset has been used, so it can never move a highlight.
 */
function collapse(text: string): string {
  return text.replace(/\s+/g, " ");
}

/** Move a window's start forward onto a word boundary, but never past the match it is framing. */
function snapForward(folded: string, from: number, limit: number): number {
  if (from === 0) return 0;
  for (let index = from; index < Math.min(limit, from + SNIPPET_SNAP); index += 1) {
    if (/\s/.test(folded[index])) return index + 1;
  }
  return from;
}

/** …and its end back, but never before the match. */
function snapBack(folded: string, to: number, limit: number): number {
  if (to === folded.length) return to;
  for (let index = to; index > Math.max(limit, to - SNIPPET_SNAP); index -= 1) {
    if (/\s/.test(folded[index - 1])) return index - 1;
  }
  return to;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Navigating to a hit
 *
 * The URL contract lives here, next to the thing that produces it, because both ends of it are code
 * in different files — the panel writes it and the stage page reads it. Two spellings of one query
 * parameter is a link that lands on the right stage and highlights nothing, which looks exactly like
 * a search that found a stale result.
 * ──────────────────────────────────────────────────────────────────────────── */

/** `?find=entityKey.fieldKey` — which box on the stage the designer asked for. */
export const FOCUS_FIELD_PARAM = "find";

/** `&row=<clientKey>` — which row of a collection holds it. Absent for a singleton. */
export const FOCUS_ROW_PARAM = "row";

/** The value of {@link FOCUS_FIELD_PARAM}. One function so the two ends cannot spell it differently. */
export function fieldFocusKey(entityKey: string, fieldKey: string): string {
  return `${entityKey}.${fieldKey}`;
}

/** What the stage page was asked to open and scroll to. */
export type StageFocus = { entityKey: string; fieldKey: string; rowKey: string | null };

/**
 * The link that opens one stage with one box on it focused.
 *
 * THE ONLY PLACE THIS URL IS SPELLED. `workshopHitHref` below produces it for a search result and
 * `lib/submissionReadiness` produces it for an outstanding required field, and both have to agree
 * with `readStageFocus` — a second construction site is a link that lands on the right stage and
 * highlights nothing, which reads exactly like a stale result rather than like a broken link.
 *
 * `anchorFieldKey` rather than the field's own key: a caption input is drawn underneath the media
 * field it describes and has no wrapper of its own, so what gets scrolled to is the media field.
 */
export function stageFieldHref(
  workshopId: string,
  stageKey: string,
  entityKey: string,
  anchorFieldKey: string,
  rowKey: string | null = null
): string {
  const params = new URLSearchParams({ [FOCUS_FIELD_PARAM]: fieldFocusKey(entityKey, anchorFieldKey) });
  if (rowKey) params.set(FOCUS_ROW_PARAM, rowKey);
  return `/design-workshops/${workshopId}/stages/${stageKey}?${params.toString()}`;
}

/** The link that opens a hit's stage with that hit's field focused. */
export function workshopHitHref(workshopId: string, hit: WorkshopSearchHit): string {
  return stageFieldHref(workshopId, hit.stageKey, hit.entityKey, hit.anchorFieldKey, hit.rowKey);
}

/**
 * Read the focus back out of a stage URL, or null when there is none.
 *
 * Deliberately tolerant: a hand-edited or truncated parameter yields null and the stage opens
 * normally. A malformed link must not be able to stop a designer reaching a form.
 */
export function readStageFocus(params: { get(name: string): string | null } | null | undefined): StageFocus | null {
  const raw = params?.get(FOCUS_FIELD_PARAM);
  if (!raw) return null;
  const separator = raw.indexOf(".");
  if (separator <= 0 || separator === raw.length - 1) return null;
  return {
    entityKey: raw.slice(0, separator),
    fieldKey: raw.slice(separator + 1),
    rowKey: params?.get(FOCUS_ROW_PARAM) || null
  };
}

/** The DOM attribute the stage form stamps on every field wrapper, so a hit can be scrolled to. */
export const FIELD_ANCHOR_ATTRIBUTE = "data-dw-field";

/** …and the row it belongs to, for a collection. */
export const ROW_ANCHOR_ATTRIBUTE = "data-dw-row";
