package com.designprototype.workshop.data

import com.designprototype.workshop.report.toPlain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

/**
 * Offline full-text search across one whole design workshop, on the handset — the port of
 * `frontend/lib/workshopSearch.ts`.
 *
 * WHY IT EXISTS HERE OF ALL PLACES. A workshop is 22 stages, 43 entities and ~509 fields. A designer
 * who wants to know where they wrote about indigo opens stages one at a time and reads, which in a
 * courtyard is not a search, it is an afternoon. The web has had this box on the workshop overview
 * for a while and its own status line says "Searches every written answer on this device… No
 * connection needed" — which was true there and, until this file, was a promise made on the surface
 * that is least often offline to the one that is always online. [WorkshopDraftStore] already holds
 * the WHOLE workshop in `filesDir`, so nothing here needs a server: this is an index over bytes the
 * phone is already carrying, and it answers on the fortnight with no signal, because that is the
 * fortnight the fieldwork happens in.
 *
 * NO COMPOSE, NO RETROFIT, NO filesDir — registry and draft in, results out, the same shape as
 * [DwMarketAnalysis] and [DwSubmissionReadiness], so the whole thing is testable without a device.
 *
 * ── THE FOUR DECISIONS THAT LOOK LIKE DETAILS ────────────────────────────────────────────────────
 *
 * 1. **The tokeniser is `[\p{L}\p{M}]+`, and the `\p{M}` is the whole feature in Odia.** The survey
 *    text this application collects is Odia, Hindi and transliterated English. Odia writes a
 *    consonant cluster as base + VIRAMA + base and a vowel as base + MATRA, and both the virama and
 *    the matra are general category M (combining mark), not L. Drop `\p{M}` and ତନ୍ତ ("tanta", loom)
 *    stops being one word and becomes the fragments ତନ and ତ. That is not hypothetical: it is the
 *    defect `backend/app/services/market_analysis.py` was found to have, and [DwMarketAnalysis]
 *    carries the same character class for the same reason.
 *
 *    ON THE JVM SPECIFICALLY the trap has a second half. Java's `\w`, `\d` and `\b` are ASCII-only
 *    unless [java.util.regex.Pattern.UNICODE_CHARACTER_CLASS] is set, so the "obvious" spelling of a
 *    word run is not merely wrong about marks the way JavaScript's is — it does not match a single
 *    Odia character at all. The GENERAL CATEGORY classes `\p{L}`, `\p{M}` and `\p{N}` used below are
 *    Unicode-aware with no flag, which is why they are what is written here; `DwWorkshopSearchTest`
 *    pins that with real Odia text rather than trusting the sentence.
 *
 *    WHAT DROPPING IT COSTS is worth stating precisely, because it is corrosive rather than fatal
 *    here: a prefix probe shatters the QUERY the same way it shattered the index, so an exact Odia
 *    query still limps back with the right row some of the time — and then the highlight lands on a
 *    fragment and the designer is shown `[ତନ]୍[ତ]`, their own word broken across the mark that holds
 *    it together; ତ alone becomes an indexed token, so "ତନ ଓ ତାର" is returned for a search for
 *    "ତନ୍ତ", which is a hit a designer cannot tell from a real one; and exactness stops meaning
 *    anything, so the ranking that puts the right field first collapses. All three have a test.
 *
 * 2. **The fold strips U+0300–U+036F and NOTHING ELSE.** Folding diacritics is what lets "Meher"
 *    find "Mehér" and "Sambalpuri" find "Sāmbalpurī", which matters because transliterated Odia is
 *    spelled three ways by three people in one workshop. But the naive spelling of that rule — NFKD,
 *    then delete every category-M character — is decision 1 wearing a different hat: it would delete
 *    the virama and the matras and reduce ରଙ୍ଗ to ରଗ, a word that exists nowhere. U+0300–U+036F is
 *    the Combining Diacritical Marks block, where NFKD puts the macron of ā and the dot-below of ṭ,
 *    and it contains no Indic mark at all — every Odia and Devanagari mark lives in its own script
 *    block (U+0B00–U+0B7F, U+0900–U+097F). The narrow range is both the useful rule and the safe one.
 *
 * 3. **A match is a token PREFIX, never a bare substring.** Typing "indi" finds "indigo"; typing
 *    "digo" does not. That is what an index buys, and it is also the semantics a designer expects
 *    from a search box. Highlighting follows the same rule, so what is marked on screen is exactly
 *    what matched rather than a second opinion that occasionally disagrees with the ranking.
 *
 * 4. **A REF is indexed by its resolved LABEL or not at all.** A REF field stores a cuid. Indexing
 *    the cuid would let a designer "find" a hit whose snippet is twenty-five random characters, which
 *    is the artisan-dropdown defect this repository already shipped once. So a reference is resolved
 *    against the rows this device already holds, or — for the models that live outside the workshop —
 *    against the display name the picker hydrated onto the row (see [referenceDisplayHint]). When
 *    neither answers, the value is left out. Silence is the honest result; a cuid in a result list is
 *    not.
 *
 * WHAT IS DELIBERATELY NOT INDEXED. Media fields (IMAGE, IMAGE_LIST, FILE, AUDIO, VIDEO) hold media
 * ids, which are cuids for the same reason a REF is — their CAPTIONS are indexed instead, and a
 * caption hit navigates to the media field it belongs to, because that is where the caption box is
 * drawn. Numbers, dates, booleans and coordinates are not text a person wrote and a search over them
 * returns matches nobody can read; the fields that state a price in prose are RICH_TEXT and are
 * indexed. Deprecated fields are skipped because there is no control on any form to navigate to — a
 * result a designer cannot reach is not a result.
 *
 * ── WHERE THIS PORT DELIBERATELY DIVERGES FROM THE WEB ───────────────────────────────────────────
 *
 * Two places, both because the DRAFT STORE has a different shape here, and both narrower rather than
 * wider — they can cost a hit, never invent one. [StageDraft.values] is ONE flat map per stage rather
 * than a map per singleton entity, so only the stage's first singleton is indexed (see [collect]'s
 * caller); and the hydrated-name resolution in [referenceDisplayHint] is derived from the registry
 * rather than read from a hand-maintained table, because this client refuses to hold a second copy of
 * the server's hydration rules — `DwReferenceField` says why at length.
 */

/* --------------------------------------------------------------------------------------
 * The rules
 * -------------------------------------------------------------------------------------- */

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
 * "1650" does not. Normalising commas away inside a digit run was considered and refused on the web —
 * the separator has to survive into the snippet's character offsets for the highlight to land on the
 * right characters — and refusing it here as well is what keeps the two boxes answering alike.
 */
private val DW_TOKEN_RUN = Regex("[\\p{L}\\p{M}]+|\\p{N}+")

/**
 * The Unicode Combining Diacritical Marks block, and deliberately nothing wider.
 *
 * See decision 2 in the file header. Widening this to `\p{M}` would delete the virama and every
 * matra, shatter Odia and Hindi, and turn this feature into the thing it exists to replace.
 */
private val DW_COMBINING_DIACRITICS = Regex("[\\u0300-\\u036F]")

/**
 * ECMAScript's whitespace set, spelled out, because Kotlin has no predicate that is it.
 *
 * `String.trim()` is `Character.isWhitespace`, which is FALSE for the no-break space U+00A0 — the
 * character a price or a product code pasted out of a spreadsheet carries — and Java's regex `\s` is
 * `[ \t\n\x0B\f\r]` and nothing else without [java.util.regex.Pattern.UNICODE_CHARACTER_CLASS].
 * JavaScript's `trim()` and `\s` are both WhiteSpace ∪ LineTerminator: the five ASCII controls, every
 * category-Zs/Zl/Zp character, and U+FEFF. Trimming a stored value differently from the browser puts
 * a different string in the snippet for the same answer, and snapping a snippet window differently
 * puts the window in a different place, so there is one set here and all three uses share it.
 *
 * U+0085 (NEL) is deliberately absent even though [DwPy.strip] takes it: that function is Python's
 * rule, for the modules that answer to the server's arithmetic. This one is JavaScript's rule,
 * because the search box on the web is what this box has to agree with.
 */
private val DW_JS_SPACE = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u00A0', '\u1680',
    '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
)

/** The same set as a character class, for the one place a run of it has to be collapsed. */
private val DW_JS_SPACE_RUN = Regex(
    "[\\u0009\\u000A\\u000B\\u000C\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A" +
        "\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+"
)

/** U+2000–U+200A is the set's one contiguous run, kept as a range rather than eleven chars. */
private fun dwIsJsSpace(ch: Char): Boolean =
    ch in DW_JS_SPACE || ch in '\u2000'..'\u200A'

/** ECMAScript's `String.prototype.trim`. See [DW_JS_SPACE] for why `trim()` is not it. */
private fun dwJsTrim(text: String): String = text.trim(::dwIsJsSpace)

/**
 * Below this many characters a query is not searched at all.
 *
 * One letter matches most of the workshop and the result list is then a list of everything, which is
 * the answer a designer already had by opening the stages. The screen says so rather than drawing an
 * empty panel that looks broken.
 */
const val DW_MIN_QUERY_CHARS = 2

/**
 * The most results handed back.
 *
 * A cap is unavoidable — "ba" against a full workshop matches a fifth of it — and an un-announced cap
 * is this repository's most-repeated bug, so [DwWorkshopSearchResult] carries the true total beside
 * the capped list and the screen prints both. Sixty rather than several hundred because every
 * returned hit costs a snippet, and a designer looking at 143 results does not need the 143rd drawn —
 * they need to be told there are 143 and to type another word.
 */
const val DW_MAX_HITS = 60

/** How much of the surrounding sentence a snippet carries either side of the match, in characters. */
private const val DW_SNIPPET_RADIUS = 90

/** How far a window edge may travel to land on a space rather than mid-word. */
private const val DW_SNIPPET_SNAP = 24

/** The most highlighted runs drawn in one snippet — a value with 400 hits needs no more to be read. */
private const val DW_MAX_HIGHLIGHTS = 12

/**
 * An exact token equals the term; a longer token merely starts with it. Both are hits and the exact
 * one ranks first, so typing "tanta" puts the field that says "tanta" above the one that says
 * "tantakara". Two, not ten: this nudges the order, it does not bury a partial match.
 */
private const val DW_SCORE_EXACT = 2
private const val DW_SCORE_PREFIX = 1

/* --------------------------------------------------------------------------------------
 * Shapes
 * -------------------------------------------------------------------------------------- */

/** One highlighted or unhighlighted run of a snippet. Segments, not markup — see [DwWorkshopSearch]. */
data class DwSnippetSegment(val text: String, val match: Boolean)

data class DwSearchSnippet(
    val segments: List<DwSnippetSegment>,
    /** Text was dropped before the window, so the caller draws a leading ellipsis. */
    val clippedStart: Boolean,
    /** …and after it. */
    val clippedEnd: Boolean,
)

/** Where a hit is, in the words the stage index and the form already use for those places. */
data class DwWorkshopSearchHit(
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val entityKey: String,
    /** "Participants" — the entity's own title, as the form's heading prints it. */
    val entityTitle: String,
    /**
     * The row this answer belongs to, titled the way the collection list titles it, or null for a
     * singleton entity. A designer recognises "Sri Ghanashyam Meher", not "row 7".
     */
    val recordTitle: String?,
    /** The row's client key, so the stage screen can open the right row. Null for a singleton. */
    val rowKey: String?,
    val fieldKey: String,
    val fieldLabel: String,
    /**
     * The field to SCROLL TO, which is [fieldKey] except for a caption: a caption input is drawn
     * underneath the media field it describes and has no wrapper of its own, so its anchor is the
     * media field's. See [FieldDto.captionFor].
     */
    val anchorFieldKey: String,
    val snippet: DwSearchSnippet,
    /** Higher is a better match. See [DW_SCORE_EXACT]; ties break in workshop order. */
    val score: Int,
)

/**
 * Why a result list is what it is.
 *
 * IDLE and NO_TERMS are different facts and the screen says different things about them: an empty box
 * shows nothing at all (an empty query must never show everything), while a box holding "₹₹" has been
 * typed into and deserves to be told there is nothing in it to search for.
 */
enum class DwSearchStatus { IDLE, TOO_SHORT, NO_TERMS, SEARCHED }

data class DwWorkshopSearchResult(
    val hits: List<DwWorkshopSearchHit>,
    /** Fields that matched, BEFORE [DW_MAX_HITS]. Printed, never swallowed. */
    val total: Int,
    val truncated: Boolean,
    val status: DwSearchStatus,
    /** The tokens the query was actually understood as — the screen can say so when it surprises. */
    val terms: List<String>,
)

/** One indexed value. An implementation detail of [DwWorkshopSearch] and not a shape to render. */
internal data class DwIndexEntry(
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val entityKey: String,
    val entityTitle: String,
    val recordTitle: String?,
    val rowKey: String?,
    val fieldKey: String,
    val fieldLabel: String,
    val anchorFieldKey: String,
    /** The human-readable text, exactly as it will be quoted back in a snippet. */
    val text: String,
    /** The fold of [text], kept so a hit does not re-fold a megabyte to be confirmed. */
    val folded: String,
    /** Declaration order across the whole workshop, so equal scores sort into reading order. */
    val order: Int,
)

/**
 * The searchable form of one workshop.
 *
 * [tokens] is sorted and [postings] runs parallel to it, which is what makes a prefix probe a binary
 * search and a walk rather than a pass over every distinct word in the workshop on every keystroke.
 */
class DwWorkshopSearchIndex internal constructor(
    internal val entries: List<DwIndexEntry>,
    /** Every distinct token in the workshop, sorted by code UNIT — see [DwWorkshopSearch.buildIndex]. */
    internal val tokens: List<String>,
    /** For each token, the entries that contain it, ascending and deduplicated. */
    internal val postings: List<IntArray>,
    /** How many field values were indexed. The screen prints it so "no hits" is never ambiguous. */
    val fieldCount: Int,
    /** How many stages contributed at least one value. */
    val stageCount: Int,
    /** How long the build took, in milliseconds — measured, never guessed. */
    val buildMs: Long,
)

/* --------------------------------------------------------------------------------------
 * The module
 * -------------------------------------------------------------------------------------- */

object DwWorkshopSearch {

    /* ---------------------------------------------------------------------------------
     * Folding
     * --------------------------------------------------------------------------------- */

    /**
     * A map from each UTF-16 unit of a folded string back into the string it came from.
     *
     * Two arrays because one source code point may fold to several units (NFKD turns ǳ into three)
     * and several may fold to none (a stray combining acute). Built only for the handful of values
     * about to be DRAWN — see [fold].
     */
    private class FoldOffsets {
        val startAt = ArrayList<Int>()
        val endAt = ArrayList<Int>()
    }

    /**
     * The one folding rule, used for the index, for the query and for the snippet.
     *
     * [offsets] is filled only when the caller needs to point back at the original text — that is,
     * for the results about to be drawn. Building the map for every value in the workshop would cost
     * two boxed integers per character of a quarter-megabyte of prose, on the handset least able to
     * spare it, to answer a question nobody asked about 99% of them.
     *
     * ASCII IS BATCHED, and on this device that matters more than it does in a browser. The obvious
     * spelling normalises once per code point, and almost no real value is ENTIRELY ASCII — one ₹,
     * one em dash or one Odia local name is enough to lose a whole-string fast path — so the slow
     * branch would be taken for essentially every field of every stage. Below U+0080 there is no
     * decomposition, no combining mark and no case mapping that is not one unit in and one unit out,
     * so a RUN of ASCII folds to `lowercase()` with an identity map, provably rather than
     * approximately. Only the genuinely non-ASCII code points pay for [Normalizer].
     */
    private fun fold(text: String, offsets: FoldOffsets?): String {
        val out = StringBuilder(text.length)
        var index = 0
        val length = text.length

        while (index < length) {
            var ascii = index
            while (ascii < length && text[ascii].code < 0x80) ascii += 1
            if (ascii > index) {
                out.append(text.substring(index, ascii).lowercase())
                if (offsets != null) {
                    for (at in index until ascii) {
                        offsets.startAt.add(at)
                        offsets.endAt.add(at + 1)
                    }
                }
                index = ascii
                continue
            }

            // One non-ASCII code point — read as a CODE POINT so a surrogate pair is decomposed as
            // the one character it is rather than as two halves that normalise to nothing.
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            val single = String(Character.toChars(codePoint))
            val piece = DW_COMBINING_DIACRITICS
                .replace(Normalizer.normalize(single, Normalizer.Form.NFKD), "")
                .lowercase()
            out.append(piece)
            if (offsets != null) {
                // Per UTF-16 UNIT, not per code point: the arrays are indexed by `folded[i]` and an
                // astral letter is two units. Pushing once per code point would slide every later
                // offset by one and put the highlight on the wrong word from the first emoji onwards.
                for (unit in 0 until piece.length) {
                    offsets.startAt.add(index)
                    offsets.endAt.add(index + width)
                }
            }
            index += width
        }
        return out.toString()
    }

    /**
     * Case- and diacritic-folded text, for comparison only.
     *
     * Public because the query, the index and the tests must all fold identically — a search box that
     * folds one way and an index that folds another is a search that silently misses.
     */
    fun foldForSearch(text: String): String = fold(text, null)

    /**
     * The distinct tokens of a raw string, in order — the whole tokenising rule in one call.
     *
     * Public for the tests, which have to be able to state what "ତନ୍ତ is one token" means without
     * building a workshop around it.
     */
    fun tokenise(text: String): List<String> = tokensOf(foldForSearch(text))

    /**
     * The tokens of already-folded text, in order.
     *
     * A [Regex] is stateless on the JVM and [Regex.findAll] is a lazy sequence, so — unlike the
     * browser's shared `g` regex, which has to have its `lastIndex` reset on the way in because
     * [buildSnippet] abandons its own scan part-way — there is no shared cursor here to be left in
     * the middle of the previous value. Breaking out of a scan early costs nothing and risks nothing.
     */
    private fun tokensOf(folded: String): List<String> =
        DW_TOKEN_RUN.findAll(folded).map { it.value }.toList()

    /* ---------------------------------------------------------------------------------
     * Reading a stored value
     * --------------------------------------------------------------------------------- */

    /**
     * A stored value as the string a single-line box holds — the port of `inputValue`.
     *
     * NOT [DwValues.text], and the difference is the reason this exists: that one joins a JSON array
     * with ", " while `inputValue` returns "" for anything list- or object-shaped, so a TEXT field
     * that somehow holds an array would be indexed on this client and not on the other.
     */
    private fun inputValue(value: JsonElement?): String = when {
        value == null || value is JsonNull -> ""
        // `content` is "true"/"false" for a JSON boolean and the literal as written for a number,
        // which is what `String(value)` gives except for a trailing zero ("1250.10" vs "1250.1").
        // MONEY is the only type that spells one and it is not indexed; see [searchableText].
        value is JsonPrimitive -> value.content
        else -> ""
    }

    /** The label of one ENUM token, as a human sees it on the form. */
    private fun enumLabel(schema: SchemaResponse, field: FieldDto, token: String): String {
        // `ifEmpty` and not a null check: the server omits `options` entirely at its default, so an
        // absent list and an empty one are the same payload here and both have to fall through to the
        // shared table. See [FieldDto]'s note on why every key is defaulted.
        val options = field.options.ifEmpty { schema.enums[field.enumName].orEmpty() }
        // A token with no option left in the registry is NOT dropped. It is a real answer a designer
        // gave against a list that has since changed, and hiding it from the search is how a record
        // becomes unfindable because somebody edited an enum. The raw token is the only name it has.
        return options.firstOrNull { it.value == token }?.label ?: token
    }

    /**
     * Every entity in the registry that a REF can name, keyed by the model name a REF field declares.
     *
     * [FieldDto.refModel] is the entity's [EntityDto.name] ("DwSketch", "DwPrototype", …), so this
     * join needs no table to maintain and picks up a new referenceable entity the day the registry
     * declares one. The six models that are NOT entities of this registry — Craft, Artisan, Process,
     * ToolDocumentation, ProductDocumentation, and (since the stage-7 instrument link, 2026-09-03)
     * Questionnaire — are records the rest of the repository owns and
     * cannot be resolved offline from a workshop draft at all; they fall through to
     * [referenceDisplayHint].
     */
    private fun entitiesByModel(schema: SchemaResponse): Map<String, EntityDto> {
        val out = HashMap<String, EntityDto>()
        for (stage in schema.stages) for (entity in stage.entities) out[entity.name] = entity
        return out
    }

    /** One collection row and enough about it to title it. */
    private class RowRef(val entity: EntityDto, val row: DraftRow, val index: Int)

    /**
     * A row's stable identity, in the order the stage screen will recognise it.
     *
     * [DraftRow.id] is `entityKey#rowId` and the suffix IS the `_clientKey` the sync sends, which is
     * the same identity the web reads out of the row object. The server's own `_entryId` is the
     * fallback for the one shape that has neither: a row seeded from a stage read before this build's
     * key scheme existed.
     *
     * A THIRD COPY of a rule [DwSubmissionReadiness] and [DwWorkshopCards] already hold privately,
     * and the duplication is stated rather than hidden — both of those are private to their own
     * modules. If any of the three moves, all three move.
     */
    private fun rowKeyOf(row: DraftRow): String? {
        val clientKey = row.id.substringAfter(DW_ROW_KEY_SEPARATOR, "")
        if (clientKey.isNotEmpty()) return clientKey
        return (row.values["_entryId"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    }

    /**
     * Every collection row in the workshop, keyed by the id a REF would store.
     *
     * Keyed by `_entryId` because that is what a reference picker writes — but the client key is
     * keyed too and costs nothing, so a workshop whose rows were created on this device and have not
     * yet been given server ids still resolves rather than silently degrading to "no label" for a
     * whole day of offline work.
     */
    private fun rowsById(schema: SchemaResponse, draft: WorkshopDraft?): Map<String, RowRef> {
        val out = HashMap<String, RowRef>()
        for (stage in schema.stages) {
            val stored = draft?.stages?.get(stage.key) ?: continue
            for (entity in stage.collections) {
                stored.rowsFor(entity.key).forEachIndexed { index, row ->
                    val ref = RowRef(entity, row, index)
                    (row.values["_entryId"] as? JsonPrimitive)
                        ?.takeIf { it.isString && it.content.isNotEmpty() }
                        ?.let { out[it.content] = ref }
                    rowKeyOf(row)?.let { out[it] = ref }
                }
            }
        }
        return out
    }

    /**
     * The display name a reference picker hydrated onto this row, or "" when there is none.
     *
     * THE WEB READS THIS OUT OF A HAND-WRITTEN TABLE (`DW_REFERENCE_HYDRATION`) and this client
     * deliberately has no such table: `DwReferenceField` refuses to hold "a second, client-side copy
     * of the registry's hydration rules", because the moment it drifted an inline-created artisan
     * would fill a row differently from the same artisan picked off the list an hour later. So the
     * key is DERIVED from the registry instead, by the two rules the server's own `fromref(…)`
     * markers actually follow:
     *
     *  * `craftRef` → `craftName`, `artisanRef` → `artisanName`, `productRef` → `productName` — the
     *    ref's own key with `Ref` traded for `Name`, which is how an entity spells the hydrated name
     *    when it holds more than one of them;
     *  * `processRef` → `documentedProcessName` — the same spelling under the `documented` prefix an
     *    entity uses when its hydrated boxes describe a record documented ELSEWHERE, rather than
     *    something observed at this workshop (see below); then
     *  * `interviewRef` → `interviewTitle` — the same stem trade for a model whose label column is
     *    a TITLE and not a name (added 2026-08-24 with the sixth reference model);
     *  * `documentedFor` — the box a stage-5 process picker's PARENT product writes into, reached
     *    only when this field is a cascade parent (added 2026-08-24 with the product→process
     *    cascade); then
     *  * plain `name`, which is how it spells the only one.
     *
     * On the registry as it stands that reproduces the web's table entry for entry, for all eleven of
     * its rows (`workshopSetup.craftRef`→craftName, `participant.artisanRef`→name,
     * `processStep.processRef`→name, `tool.toolRef`→name, `existingProduct.artisanRef`→artisanName,
     * `existingProduct.productRef`→name, `prototype.productRef`→productName,
     * `traditionalProcess.processRef`→documentedProcessName,
     * `traditionalProcess.productRef`→documentedFor, `processStep.productRef`→documentedFor,
     * `artisanBaseline.interviewRef`→interviewTitle). A ref that grows a hydrated name
     * spelled some fourth way gets no hint and is left out of the index, which is the honest silence
     * decision 4 asks for rather than a wrong label.
     *
     * ── WHY THE `documented` RULE HAD TO BE ADDED, 2026-08-16 ────────────────────────────────────
     *
     * IT WAS ADDED TO FIX A LIVE PARITY DEFECT, and the shape of the defect is the argument for
     * keeping this derived rather than tabulated. The reference-hydration table was widened on the
     * server and the web from seven pairs to eight; the eighth is stage 5's `traditionalProcess`
     * singleton, whose hydrated boxes are all prefixed `documented…` precisely BECAUSE the stage
     * holds two kinds of writing side by side — what the designer watched happen in the workshop,
     * and what a researcher recorded about the process somewhere else months earlier. Naming the
     * imported one `processName` would have put it in the box the observed one belongs in.
     *
     * The two rules above could not spell `documentedProcessName`, so this client produced no hint
     * for that ref and left it out of the offline index — while the web indexed it. A designer
     * searching their own phone for a workshop by its documented process found nothing, and the same
     * search on a laptop found it. `DwWorkshopSearchRegistryTest` caught this because it walks the
     * registry and compares against the web's table; it is the reason that test exists.
     *
     * A THIRD SPELLING IS STILL A RULE AND NOT A TABLE. It is derived from the ref's own key for
     * every entity that adopts the prefix, so the next `documented…Ref` needs no edit here — which
     * is the property `DwReferenceField` refused to give up when it declined to mirror the web.
     *
     * ── WHY `Title` AND `documentedFor` HAD TO BE ADDED, 2026-08-24 ───────────────────────
     *
     * THE SAME PARITY DEFECT AS 2026-08-16, TWICE OVER, and again caught by the registry test rather
     * than in a courtyard. The table went from eight rows to eleven: a sixth reference model
     * (`QuestionnaireInterview`) and both stage-5 process pickers gaining a `productRef` parent.
     *
     * The sixth model's label is its TITLE — `REFERENCE_MODELS`'s `label` lambda reads `r.title` — so
     * `artisanBaseline` spells the hydrated name `interviewTitle` and the two `…Name` rules could not
     * reach it. The web hit the identical wall and answered it the identical way, by adding
     * `interviewTitle` to `referenceDisplayHint`'s source list.
     *
     * `documentedFor` is the one that could not be a bare fourth spelling, and the reason is worth
     * recording: `processStep` declares `documentedFor` AND its own `name`, and its `processRef`
     * resolves through `name`. Appending `documentedFor` to the list unconditionally would therefore
     * have relabelled every step's process with its product — turning a missing hint into a wrong
     * one, which decision 4 rates as the worse outcome. The cascade-parent condition is read off
     * `refFilterBy`, so it stays a rule derived from the registry and needs no edit here when the
     * next cascade is declared.
     *
     * THE GUARD IS NOT OPTIONAL. This runs only for a model that is NOT an entity of this registry.
     * Without it, a `prototype.sketchRef` whose sketch row is missing from the draft would fall
     * through to the prototype's OWN `name` and index the prototype's name as the sketch's label —
     * a plausible-looking hit naming the wrong record, which is worse than no hit at all.
     */
    private fun referenceDisplayHint(
        models: Map<String, EntityDto>,
        entity: EntityDto,
        field: FieldDto,
        record: Map<String, JsonElement>,
    ): String {
        if (field.refModel.isEmpty() || models.containsKey(field.refModel)) return ""
        val stem = field.key.removeSuffix("Ref")
        val derived = stem + "Name"
        // `processRef` → `documentedProcessName`. Built from the same stem, so it costs nothing on an
        // entity that does not use the prefix — `entity.field(key)` simply finds no such box.
        val documented = "documented" + stem.replaceFirstChar { it.uppercase() } + "Name"
        // `interviewRef` -> `interviewTitle`, for a model whose label column is a TITLE rather than a
        // name. The same stem trade as `derived`, so it costs nothing on a model labelled `name`.
        val titled = stem + "Title"
        // `documentedFor` -- reached ONLY for a cascade PARENT, and which fields those are is read off
        // the registry rather than listed here: a parent is a field that some other live field's
        // `refFilterBy` points at. The condition is what keeps this correct on `processStep`, which
        // declares BOTH `documentedFor` and its own `name`; unconditionally, that step's `processRef`
        // would resolve through `documentedFor` and label the process with the product's name.
        val cascadeParent = entity.liveFields.any { it.refFilterBy == field.key }
        val candidates = buildList {
            add(derived)
            add(documented)
            add(titled)
            if (cascadeParent) add("documentedFor")
            add("name")
        }
        for (key in candidates) {
            val target = entity.field(key)?.takeIf { !it.deprecated } ?: continue
            val type = DwFieldType.of(target.type)
            if (type != DwFieldType.TEXT && type != DwFieldType.LONG_TEXT) continue
            val text = dwJsTrim(inputValue(record[key]))
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    /**
     * The label a REF stands for, or "" when this device cannot honestly produce one.
     *
     * Two resolutions, in this order, and no third. Most REF fields point at another Dw* entity — a
     * prototype names its sketch, a cost line names its cost sheet — and that row is already in this
     * draft, so titling it with the same rule the collection list titles it with is what makes the
     * result read the same as the list the designer picked it from. Everything else falls to
     * [referenceDisplayHint]. See decision 4 in the file header.
     */
    private fun refLabel(
        models: Map<String, EntityDto>,
        rows: Map<String, RowRef>,
        entity: EntityDto,
        field: FieldDto,
        record: Map<String, JsonElement>,
        value: String,
    ): String {
        val target = rows[value]
        if (target != null && (field.refModel.isEmpty() || models[field.refModel]?.key == target.entity.key)) {
            return dwCardRowTitle(target.entity, target.row.values, target.index)
        }
        return referenceDisplayHint(models, entity, field, record)
    }

    /**
     * The searchable text of one stored value, or "" when the field holds nothing a person can read.
     *
     * The `when` has no `else` that stringifies the rest on purpose: a MONEY field would index as
     * "1650.00" and a GEO as its JSON, and a result whose snippet is a coordinate wastes the one
     * screen a designer has. New TEXTUAL types have to be added here deliberately.
     */
    private fun searchableText(
        schema: SchemaResponse,
        models: Map<String, EntityDto>,
        rows: Map<String, RowRef>,
        entity: EntityDto,
        field: FieldDto,
        record: Map<String, JsonElement>,
    ): String {
        val value = record[field.key]
        if (value == null || value is JsonNull) return ""
        return when (DwFieldType.of(field.type)) {
            DwFieldType.TEXT,
            DwFieldType.LONG_TEXT,
            DwFieldType.URL,
            DwFieldType.EMAIL,
            DwFieldType.PHONE -> dwJsTrim(inputValue(value))

            // Through the document reader and NEVER the raw JSON: indexing the stored value would
            // make every narrative match "blocks", "spans" and "PARAGRAPH", and the snippet would
            // quote a serialisation at a designer instead of their own sentence. `toPlain` is also
            // what keeps a bulleted list reading as a list rather than as one run-on line, and it is
            // the single implementation of that flattening on this client.
            DwFieldType.RICH_TEXT -> dwJsTrim(toPlain(value))

            DwFieldType.TAGS -> DwValues.list(value).joinToString(" · ")
            DwFieldType.ENUM -> enumLabel(schema, field, dwJsTrim(inputValue(value)))
            DwFieldType.MULTI_ENUM ->
                DwValues.list(value).joinToString(" · ") { enumLabel(schema, field, it) }

            DwFieldType.REF ->
                refLabel(models, rows, entity, field, record, dwJsTrim(inputValue(value)))

            else -> ""
        }
    }

    /* ---------------------------------------------------------------------------------
     * The index
     * --------------------------------------------------------------------------------- */

    /** An empty index, for a screen that has a registry but no draft yet. */
    fun emptyIndex(): DwWorkshopSearchIndex =
        DwWorkshopSearchIndex(emptyList(), emptyList(), emptyList(), 0, 0, 0L)

    /**
     * Build the whole searchable index for one draft. Once per draft load; never per keystroke.
     *
     * ONLY THE STAGE'S FIRST SINGLETON IS INDEXED, and that is a shape difference rather than a
     * choice: [StageDraft] holds ONE flat `values` map for the whole stage, so a second singleton
     * entity has nowhere of its own to read from and walking both would index the same answers twice
     * under two different headings. [StageDto.singleton] is the same accessor `StageScreen` draws
     * from, so what is searched is exactly what a designer can see. Every stage in the registry today
     * declares at most one; if one ever declares two, the draft store is what has to change first.
     */
    fun buildIndex(schema: SchemaResponse, draft: WorkshopDraft?): DwWorkshopSearchIndex {
        val startedAt = System.nanoTime()
        val models = entitiesByModel(schema)
        val rows = rowsById(schema, draft)

        val entries = ArrayList<DwIndexEntry>()
        val stagesWithText = HashSet<String>()
        var order = 0

        for (stage in schema.stages) {
            val stored = draft?.stages?.get(stage.key) ?: continue
            val before = entries.size
            for (entity in stage.entities) {
                if (entity.cardinality == "SINGLETON") {
                    if (entity.key != stage.singleton?.key) continue
                    order = collect(
                        schema, models, rows, stage, entity, stored.values, null, null, entries, order,
                    )
                } else {
                    stored.rowsFor(entity.key).forEachIndexed { index, row ->
                        order = collect(
                            schema, models, rows, stage, entity, row.values,
                            recordTitleOf(models, rows, entity, row, index),
                            rowKeyOf(row), entries, order,
                        )
                    }
                }
            }
            // "A stage that has a draft record" and "a stage that has a word in it" are different
            // facts, and the screen prints the second: opening a stage banks an empty record, so
            // counting stored stages would tell a designer the search covers 22 stages when nineteen
            // of them hold nothing.
            if (entries.size > before) stagesWithText.add(stage.key)
        }

        // The postings are built in one pass over the entries and then sorted once, rather than kept
        // sorted as they grow: a map of lists is O(1) per token and the single sort at the end is
        // what turns "which words start with this" into a binary search instead of a scan over every
        // distinct word in the workshop, on every keystroke.
        val postingsByToken = HashMap<String, MutableList<Int>>()
        entries.forEachIndexed { entryIndex, entry ->
            val seen = HashSet<String>()
            for (token in tokensOf(entry.folded)) {
                if (!seen.add(token)) continue
                postingsByToken.getOrPut(token) { ArrayList() }.add(entryIndex)
            }
        }

        // Kotlin's natural String order compares UTF-16 code UNITS, which is what the browser's `<`
        // does and therefore what this has to be. NOT [DwPy.compareStrings], which is Python's
        // code-POINT order: that is the right rule for the modules that answer to the server's sorts
        // and the wrong one here, where the only thing the order has to guarantee is that every token
        // starting with a prefix sits in one contiguous run after it — the assumption [prefixRange]
        // walks on.
        val tokens = postingsByToken.keys.sorted()
        val postings = tokens.map { postingsByToken.getValue(it).toIntArray() }

        return DwWorkshopSearchIndex(
            entries = entries,
            tokens = tokens,
            postings = postings,
            fieldCount = entries.size,
            stageCount = stagesWithText.size,
            buildMs = (System.nanoTime() - startedAt) / 1_000_000L,
        )
    }

    /**
     * What to call one collection row in a result.
     *
     * [dwCardRowTitle] is this client's port of the web's `rowTitle` and is used unchanged wherever
     * it can answer — a search result has to name a row the way the list the designer will land on
     * names it. The one place it cannot is an entity whose [EntityDto.labelField] is itself a REF
     * (`sketchReview.sketchRef`, `costSheet.productRef`, `certificate.participantRef`,
     * `followUp.productRef`, `prototypeValidation.prototypeRef`), where it would read the cuid
     * straight off the row and title it with that. Those resolve through the same reference resolver
     * the index uses, so the row reads "Phoda kumbha table runner".
     */
    private fun recordTitleOf(
        models: Map<String, EntityDto>,
        rows: Map<String, RowRef>,
        entity: EntityDto,
        row: DraftRow,
        index: Int,
    ): String {
        val labelField = entity.field(entity.labelField)
        if (labelField != null && DwFieldType.of(labelField.type) == DwFieldType.REF) {
            val resolved = refLabel(
                models, rows, entity, labelField, row.values,
                dwJsTrim(inputValue(row.values[labelField.key])),
            )
            if (dwJsTrim(resolved).isNotEmpty()) return dwJsTrim(resolved)
        }
        return dwCardRowTitle(entity, row.values, index)
    }

    /** Index every readable field of one record. Returns the next declaration order. */
    private fun collect(
        schema: SchemaResponse,
        models: Map<String, EntityDto>,
        rows: Map<String, RowRef>,
        stage: StageDto,
        entity: EntityDto,
        record: Map<String, JsonElement>,
        recordTitle: String?,
        rowKey: String?,
        out: MutableList<DwIndexEntry>,
        order: Int,
    ): Int {
        var next = order
        // A deprecated field is a dead input: no form draws it, so a hit on one could not be
        // navigated to and would be a result that goes nowhere.
        for (field in entity.liveFields) {
            val text = searchableText(schema, models, rows, entity, field, record)
            if (text.isEmpty()) continue
            out.add(
                DwIndexEntry(
                    stageKey = stage.key,
                    stageNumber = stage.number,
                    stageTitle = stage.title,
                    entityKey = entity.key,
                    entityTitle = entity.title,
                    recordTitle = recordTitle,
                    rowKey = rowKey,
                    fieldKey = field.key,
                    fieldLabel = field.label,
                    // A caption has no control of its own — the stage screen hands it to the media
                    // field to draw underneath itself — so its anchor is the media field it captions.
                    anchorFieldKey = field.captionFor.ifEmpty { field.key },
                    text = text,
                    folded = foldForSearch(text),
                    order = next,
                )
            )
            next += 1
        }
        return next
    }

    /* ---------------------------------------------------------------------------------
     * Searching
     * --------------------------------------------------------------------------------- */

    private fun nothing(status: DwSearchStatus, terms: List<String> = emptyList()) =
        DwWorkshopSearchResult(emptyList(), 0, false, status, terms)

    /**
     * Run one query against a built index.
     *
     * AN EMPTY QUERY RETURNS NOTHING, not everything. A list of 509 fields is not an answer, it is
     * the workshop again in a smaller box, and it would make the panel expensive to draw on every
     * open of a screen whose job is the stage index.
     *
     * Terms are ANDed. Typing more words narrows, which is the only behaviour that makes an
     * incremental search worth typing into: "kumbha border" finds the field that holds both words and
     * not the eighteen that hold either.
     */
    fun search(index: DwWorkshopSearchIndex, query: String): DwWorkshopSearchResult {
        val raw = dwJsTrim(query)
        if (raw.isEmpty()) return nothing(DwSearchStatus.IDLE)
        // Counted in CODE POINTS, the way [DwMarketAnalysis] counts them: `length` reads an astral
        // letter as two and would let a one-character query through a two-character floor.
        if (raw.codePointCount(0, raw.length) < DW_MIN_QUERY_CHARS) return nothing(DwSearchStatus.TOO_SHORT)

        val terms = tokenise(raw)
        if (terms.isEmpty()) return nothing(DwSearchStatus.NO_TERMS, terms)

        /** entry index -> score so far. Rebuilt per term and intersected, so AND falls out of it. */
        var surviving: Map<Int, Int>? = null
        for (term in terms) {
            val perTerm = HashMap<Int, Int>()
            for (tokenIndex in prefixRange(index.tokens, term)) {
                val weight = if (index.tokens[tokenIndex] == term) DW_SCORE_EXACT else DW_SCORE_PREFIX
                for (entryIndex in index.postings[tokenIndex]) {
                    // The BEST way this term matched this entry, not the sum: a field holding "tanta
                    // tantakara" must not outrank one holding "tanta" merely by repeating a prefix.
                    val existing = perTerm[entryIndex]
                    if (existing == null || existing < weight) perTerm[entryIndex] = weight
                }
            }
            val previous = surviving
            surviving = if (previous == null) {
                perTerm
            } else {
                val merged = HashMap<Int, Int>()
                // Walk the SMALLER map. A common word ("the") has thousands of postings and a rare
                // one ("phoda") has three; iterating the rare side is what keeps a two-word query as
                // cheap as the rarest word in it.
                val small = if (previous.size <= perTerm.size) previous else perTerm
                val large = if (small === previous) perTerm else previous
                for ((entryIndex, score) in small) {
                    val other = large[entryIndex] ?: continue
                    merged[entryIndex] = score + other
                }
                merged
            }
            if (surviving.isEmpty()) break
        }

        val matched = surviving ?: emptyMap()
        // Equal relevance falls back to WORKSHOP ORDER — stage 1 before stage 22, and within a stage
        // the declaration order the form draws. A designer scanning the list is then reading it in
        // the order they wrote it, which is the only ordering they can predict. `order` is unique per
        // entry, so this is a total order and the hash map's own iteration order cannot leak into it.
        val ranked = matched.entries.sortedWith(
            compareByDescending<Map.Entry<Int, Int>> { it.value }
                .thenBy { index.entries[it.key].order }
        )

        val hits = ranked.take(DW_MAX_HITS).map { (entryIndex, score) ->
            val entry = index.entries[entryIndex]
            DwWorkshopSearchHit(
                stageKey = entry.stageKey,
                stageNumber = entry.stageNumber,
                stageTitle = entry.stageTitle,
                entityKey = entry.entityKey,
                entityTitle = entry.entityTitle,
                recordTitle = entry.recordTitle,
                rowKey = entry.rowKey,
                fieldKey = entry.fieldKey,
                fieldLabel = entry.fieldLabel,
                anchorFieldKey = entry.anchorFieldKey,
                snippet = buildSnippet(entry.text, terms),
                score = score,
            )
        }

        return DwWorkshopSearchResult(
            hits = hits,
            total = ranked.size,
            truncated = ranked.size > hits.size,
            status = DwSearchStatus.SEARCHED,
            terms = terms,
        )
    }

    /**
     * The indices of every token in [tokens] that starts with [term].
     *
     * Binary search for the lower bound, then walk while the prefix holds. Sorting by code unit is
     * what makes that walk correct: every string beginning with a prefix sorts contiguously after it.
     */
    private fun prefixRange(tokens: List<String>, term: String): List<Int> {
        var low = 0
        var high = tokens.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (tokens[middle] < term) low = middle + 1 else high = middle
        }
        val out = ArrayList<Int>()
        var index = low
        while (index < tokens.size && tokens[index].startsWith(term)) {
            out.add(index)
            index += 1
        }
        return out
    }

    /* ---------------------------------------------------------------------------------
     * Snippets
     * --------------------------------------------------------------------------------- */

    /**
     * A window of [text] around the first match, with the matched tokens marked.
     *
     * SEGMENTS, NOT MARKUP. Returning a string with markers in it would oblige every caller to parse
     * it back out, and the text being highlighted is prose a designer typed — including, legitimately,
     * the marker. Segments cannot be misread by construction.
     *
     * The highlight is placed on TOKENS that start with a term, which is the same rule that decided
     * the result was a result. Highlighting every substring occurrence instead would mark "indigo"
     * inside "indigoid" on a search for "indi" — correct — and also mark "digo" inside "indigo" on a
     * search for "digo", which did not match and never should be seen to.
     */
    private fun buildSnippet(text: String, terms: List<String>): DwSearchSnippet {
        val offsets = FoldOffsets()
        val folded = fold(text, offsets)

        // The scan STOPS. A stage-13 narrative runs to three thousand characters and holds four
        // hundred tokens, of which the snippet can use at most the handful inside one window;
        // tokenising all of them, for every result, for every keystroke, is what made a common query
        // take 30ms on the flagship workshop in the browser.
        val from = ArrayList<Int>()
        val to = ArrayList<Int>()
        var ceiling = Int.MAX_VALUE
        for (match in DW_TOKEN_RUN.findAll(folded)) {
            val start = match.range.first
            if (start > ceiling || from.size >= DW_MAX_HIGHLIGHTS) break
            if (terms.none { match.value.startsWith(it) }) continue
            from.add(start)
            to.add(start + match.value.length)
            // Everything past the first match's window is discarded below, so there is no reason to
            // read it.
            if (from.size == 1) ceiling = start + match.value.length + DW_SNIPPET_RADIUS
        }

        if (from.isEmpty()) {
            // Nothing to anchor on. This happens when the caller asks for a snippet of a value that
            // did not match — and, defensively, if the two folds ever disagreed. Quoting the head of
            // the field is still useful and is never wrong; inventing a highlight would be.
            val head = text.take(DW_SNIPPET_RADIUS * 2)
            return DwSearchSnippet(
                segments = listOfNotNull(segment(collapse(head), false)),
                clippedStart = false,
                clippedEnd = head.length < text.length,
            )
        }

        val windowStart = snapForward(folded, maxOf(0, from[0] - DW_SNIPPET_RADIUS), from[0])
        val windowEnd = snapBack(folded, minOf(folded.length, to[0] + DW_SNIPPET_RADIUS), to[0])

        val segments = ArrayList<DwSnippetSegment>()
        var cursor = windowStart
        for (i in from.indices) {
            if (to[i] <= windowStart || from[i] >= windowEnd) continue
            val start = maxOf(from[i], windowStart)
            val end = minOf(to[i], windowEnd)
            if (start > cursor) segment(collapse(slice(text, offsets, cursor, start)), false)?.let(segments::add)
            segment(collapse(slice(text, offsets, start, end)), true)?.let(segments::add)
            cursor = end
        }
        if (cursor < windowEnd) {
            segment(collapse(slice(text, offsets, cursor, windowEnd)), false)?.let(segments::add)
        }

        return DwSearchSnippet(
            segments = segments,
            clippedStart = windowStart > 0,
            clippedEnd = windowEnd < folded.length,
        )
    }

    /** A segment, or null when collapsing left nothing to draw. */
    private fun segment(text: String, match: Boolean): DwSnippetSegment? =
        if (text.isEmpty()) null else DwSnippetSegment(text, match)

    /** The original text behind a folded range. Empty when the range is empty — never null. */
    private fun slice(text: String, offsets: FoldOffsets, from: Int, to: Int): String {
        if (to <= from) return ""
        val start = offsets.startAt.getOrElse(from) { 0 }
        val end = offsets.endAt.getOrElse(to - 1) { text.length }
        return text.substring(start, end)
    }

    /**
     * Runs of whitespace become one space.
     *
     * A RICH_TEXT field flattens to prose with newlines and list markers in it, and a snippet that
     * kept them would draw four blank lines in the middle of a result row. Done on the way OUT, after
     * every offset has been used, so it can never move a highlight.
     */
    private fun collapse(text: String): String = DW_JS_SPACE_RUN.replace(text, " ")

    /** Move a window's start forward onto a word boundary, but never past the match it is framing. */
    private fun snapForward(folded: String, from: Int, limit: Int): Int {
        if (from == 0) return 0
        var index = from
        val stop = minOf(limit, from + DW_SNIPPET_SNAP)
        while (index < stop) {
            if (dwIsJsSpace(folded[index])) return index + 1
            index += 1
        }
        return from
    }

    /** …and its end back, but never before the match. */
    private fun snapBack(folded: String, to: Int, limit: Int): Int {
        if (to == folded.length) return to
        var index = to
        val stop = maxOf(limit, to - DW_SNIPPET_SNAP)
        while (index > stop) {
            if (dwIsJsSpace(folded[index - 1])) return index - 1
            index -= 1
        }
        return to
    }

    /* ---------------------------------------------------------------------------------
     * Getting there
     * --------------------------------------------------------------------------------- */

    /**
     * Where a hit points, in the terms THIS client navigates by — the receiving half of the search,
     * and the port of `readStageFocus` in `lib/workshopSearch.ts`.
     *
     * THE WEB SPELLS THIS AS A URL because both ends of it are code in different files: the panel
     * writes `?find=entity.field&row=key` and the stage page reads it back, so `readStageFocus` is
     * one of a pair whose whole job is to stop the two ends spelling one query parameter differently.
     * There is no address bar here. `AndroidManifest.xml` declares MAIN/LAUNCHER and no
     * `<intent-filter>` for that path, so nothing on this device could ever hand a URL in — writing
     * the address into a query string and immediately parsing it back would be a second spelling of
     * something we are already holding, which is the very failure the web pair exists to prevent.
     * [DwStageFocus] says the same thing about [DwReadinessItem.href], and this is that decision
     * applied to the surface that produces it rather than to the one that consumes it.
     *
     * So this is the whole contract, in one function, for the same reason: [DwSubmissionReadiness]
     * and this module both hand [StageScreen] a [DwStageFocus], and it opens the row or the ADVANCED
     * disclosure that holds the field, scrolls it into view with a `BringIntoViewRequester` and marks
     * it. [DwWorkshopSearchHit.anchorFieldKey] rather than the field's own key, because a caption
     * input is drawn inside the media field it describes and focusing its key would scroll to nothing.
     */
    fun focusOf(hit: DwWorkshopSearchHit): DwStageFocus = DwStageFocus(
        stageKey = hit.stageKey,
        entityKey = hit.entityKey,
        fieldKey = hit.anchorFieldKey,
        rowKey = hit.rowKey,
    )
}
