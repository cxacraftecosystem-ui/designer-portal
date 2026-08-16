package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * READING THE ADMIN AUTHORSHIP & DIVERGENCE REPORT — the arithmetic and the copy, with no Compose in
 * sight.
 *
 * ── WHAT THE REPORT IS FOR ───────────────────────────────────────────────────────────────────────
 *
 * A design workshop keeps what the designer saw ON THE DAY; the shared records go on changing. Once a
 * value is hydrated onto a stage entry it is an ordinary string in `data` — a hydrated village and a
 * typed village are the same bytes, deliberately, because the report is a historical document
 * submitted to an office that keeps it (see `REFERENCE_HYDRATION`). So "this workshop says Barpali
 * and the artisan record now says Bargarh" is derivable from nothing on the wire except the
 * `reference` stamp, which names the record and the column, plus a live read of that record. That is
 * what `GET /design-workshops/{id}/provenance` is, and this file is how the handset reads it.
 *
 * ── DIVERGENCE IS NOT AN ERROR. NOTHING HERE MAY SUGGEST IT IS ───────────────────────────────────
 *
 * An artisan who moved village after the workshop makes every row that names them differ, and every
 * one of those rows is CORRECT. The screen exists so an admin can EXPLAIN why a submitted report and
 * the live directory disagree — not so a designer can be told off. So nothing in this file counts
 * "problems", nothing is named `stale` or `conflict`, no function returns a severity, and the two
 * things it does count are counted so the COMMON case ("nothing has moved") can be answered in one
 * line instead of with an empty table that reads like a failed load.
 *
 * ── WHY IT IS ALL PURE, AND WHY IT IS IN `data/` ─────────────────────────────────────────────────
 *
 * These are exactly the functions a JVM test can reach with no device, no Compose runtime and no
 * server, and they are where every decision this feature makes actually lives: which fields are
 * shown, how many there are, how a value becomes a line of text, and every sentence the screen says.
 * The web's equivalents (`frontend/lib/designWorkshopProvenance.ts`) are pure for the same reason and
 * are unit-tested at `frontend/e2e/workshop-provenance-unit.spec.ts`; `DwProvenanceReportTest` mirrors
 * that file case for case wherever the two surfaces make the same claim.
 *
 * ── THE COPY IS HERE TOO, AS CONSTANTS, AND THAT IS DELIBERATE ───────────────────────────────────
 *
 * A designer or an admin who reads one sentence on a laptop and a different one on the handset has
 * been told two things by one product. The sentences below are the browser's, character for
 * character, and they are constants rather than string literals inside a composable so that a test
 * can pin them without inflating a device — the same shape as [DW_WORKSHOP_CREATE_REFUSAL]. Each
 * carries the file it was copied from, so a reword on either surface has somewhere to look.
 */

// --------------------------------------------------------------------------------------
// Values
// --------------------------------------------------------------------------------------

/**
 * An empty cell in a comparison column: an EM DASH, U+2014, the character the browser prints.
 *
 * NOT the words "null", "none" or "empty". A blank cell here means "this side says nothing", which is
 * a real answer and one of the more interesting divergences on the screen — a workshop still holding
 * a phone number the canonical record has since had cleared. Naming it as a constant keeps the two
 * columns and the test on the same character; an en dash or a hyphen typed by hand into one of the
 * three would be invisible in review and wrong on screen.
 */
const val DW_COMPARISON_EMPTY = "—"

/**
 * One arbitrary JSON value as a single line of text, for a comparison column.
 *
 * `comparisonText` in `frontend/lib/designWorkshopProvenance.ts`, including the cases that look like
 * edge cases and are not:
 *
 *   * NULL, ABSENT AND THE EMPTY STRING are the em dash. All three mean "no value on this side".
 *   * `0` AND `false` ARE NOT. They are answers — a cost of zero, a "no" — and rendering either as
 *     an empty cell would tell an admin the record says nothing where it says something specific.
 *     This is the falsy trap that JavaScript and Kotlin fail differently, which is why both surfaces
 *     assert it rather than trusting the language.
 *   * A WHOLE NUMBER LOSES ITS TRAILING `.0` — see [dwNumberText], which is the one place the two
 *     languages disagree about what a number looks like.
 *   * AN OBJECT OR ARRAY IS PRINTED AS JSON. `spec.data(...)` yields GEO points and media references
 *     for some columns, and this view does not model them. Braces are ugly and honest; inventing a
 *     prose summary for a shape the screen cannot interpret would be a worse lie than the braces.
 *     The numbers INSIDE such an object are not normalised, so a GEO point pinned at a whole degree
 *     still reads `{"lat":21.0,"lon":83.0}` here and `{"lat":21,"lon":83}` in the browser. Naming it
 *     rather than fixing it: it would mean walking and re-serialising an arbitrary tree to correct
 *     punctuation inside a cell that is already admittedly raw, and both spellings are the same
 *     point. The SCALAR case is the one an admin actually compares, and that one matches.
 *
 * ONE FUNCTION, called from every column, rather than a `when` at each call site. `stored` and
 * `canonical` are the same kind of arbitrary JSON, so two renderings of them would eventually
 * disagree — and the place that disagreement shows is a table whose entire purpose is that the two
 * columns are comparable.
 */
fun dwComparisonText(value: JsonElement?): String = when (value) {
    // Absent and explicit-null collapse here on purpose and are safe to collapse: NEITHER means "the
    // record was deleted". That signal is [DwCanonicalComparisonDto.recordDeleted], and moving it
    // onto this value would reintroduce the web page's live defect — see that field's KDoc.
    null, JsonNull -> DW_COMPARISON_EMPTY
    is JsonPrimitive ->
        // `isString` is the whole distinction: a quoted "" is an empty answer, while the unquoted
        // literals `0` and `false` have `content` "0" and "false" and must survive as those words.
        when {
            value.isString -> if (value.content.isEmpty()) DW_COMPARISON_EMPTY else value.content
            else -> dwNumberText(value.content)
        }
    // JsonObject / JsonArray. `toString()` is kotlinx's compact serialization, which is what
    // `JSON.stringify` produces on the other surface.
    else -> value.toString()
}

/**
 * An unquoted JSON literal as the browser would print it: `127.0` becomes `127`, everything else is
 * left exactly as it arrived.
 *
 * WHY A COMPARISON SCREEN CANNOT SKIP THIS. Kotlin hands back the raw literal from the wire; the
 * browser has already been through `JSON.parse`, and `String(127.0)` in JavaScript is `"127"` —
 * there is no such thing as a trailing `.0` once a JSON number becomes a JS number. So the same
 * server value read on a laptop and a handset spelled itself two ways.
 *
 * It is not hypothetical. `coerce_value` stores a DECIMAL field as a raw Python float (MONEY
 * stringifies, DECIMAL does not), and the canonical side is a float too — `_inches_to_cm` returns
 * `round(float(v) * 2.54, 2)`. So a designer who types `127` into `lengthCm` gets `127.0` on the
 * wire, and the sharpest form of the bug is a single comparison row whose two columns spell one
 * number differently: stored `0`, canonical `0.0`, side by side, on the screen whose entire job is
 * showing whether two values are the same. The submitted .docx agrees with the browser here too —
 * `report_builder` prints DECIMAL with `%g` — so the handset was the odd one out of three surfaces.
 *
 * ONLY AN INTEGRAL, FINITE DOUBLE IS TOUCHED. `127.5` keeps its decimal, a value too large for a
 * `Long` keeps whatever the server sent rather than being rounded into a lie, and anything that is
 * not a number at all — `true`, or a literal this build does not expect — falls through untouched.
 */
private fun dwNumberText(literal: String): String {
    val number = literal.toDoubleOrNull() ?: return literal
    if (!number.isFinite() || number != Math.floor(number)) return literal
    if (number < Long.MIN_VALUE.toDouble() || number > Long.MAX_VALUE.toDouble()) return literal
    return number.toLong().toString()
}

/**
 * The canonical column of one comparison row: the deleted-record sentence, or the value.
 *
 * IN THE PURE LAYER RATHER THAN IN THE COMPOSABLE, AND THAT PLACEMENT IS THE POINT. This one
 * `if` is the whole difference between the handset and the web page's live defect — key it on
 * `canonical == null` instead of on the flag and you have told an admin that a record which is
 * sitting in the directory with one blank column has been deleted. While the choice lived inside
 * [DwProvenanceScreen] no JVM test could reach it: the test that claims this ground could assert
 * `hasMoved`, `!recordDeleted` and `dwComparisonText(null) == "—"` and still pass with the branch
 * inverted, because none of the three touches the branch. Here it is one line and it is pinnable.
 */
fun dwCanonicalText(comparison: DwCanonicalComparisonDto): String =
    if (comparison.recordDeleted) DW_PROVENANCE_RECORD_DELETED else dwComparisonText(comparison.canonical)

// --------------------------------------------------------------------------------------
// Which fields have something to show
// --------------------------------------------------------------------------------------

/**
 * Has this field's canonical record moved away from what the workshop stored — in EITHER of the two
 * ways it can?
 *
 * **THE `recordDeleted` HALF IS NOT OPTIONAL AND ITS ABSENCE IS A LIVE BUG ON THE WEB.**
 * `canonical_divergence` computes `diverged` as `source is not None and str(stored) != str(canonical)`
 * — so for a DELETED record, where `source` is None, `diverged` is **false** and `recordDeleted` is
 * true (`backend/tests/test_entry_provenance.py::test_a_deleted_record_reads_as_deleted_rather_than_
 * disappearing` pins exactly that tuple). The browser's `divergedFields` filters on `comparison
 * ?.diverged` alone, so on real server data the deleted-record row is DROPPED — and the branch on the
 * page that renders "The record this came from no longer exists" is unreachable. Its unit spec passes
 * only because the fixture hand-writes `diverged: true`, a shape the server does not emit.
 *
 * That row is the most interesting one on the screen. It is precisely the case reference hydration
 * exists for: the record is gone and this workshop now holds the ONLY copy of what the designer saw.
 * Omitting it would make "the record is gone" look identical to "the field was never copied", which
 * is the one distinction an auditor opened this screen to make. So the handset ORs the two.
 *
 * Still not an error, and named for what it is. A deleted artisan record is an ordinary event in a
 * repository that merges duplicates; what it changes is who holds the last copy, not who was wrong.
 */
val DwCanonicalComparisonDto.hasMoved: Boolean
    get() = diverged || recordDeleted

/**
 * Every field of one entry whose stored value and canonical record have parted company, in the
 * server's field order.
 *
 * Mirrors `divergedFields` on the web, with the `recordDeleted` correction described on [hasMoved].
 * Field ORDER is the map's own iteration order and is never sorted: it is the order
 * `canonical_divergence` walked the stamps in, which is the order the fields sit in on the stage, and
 * an alphabetised table would put an artisan's phone number above their name for no reason a reader
 * could see.
 */
fun dwDivergedFields(entry: DwProvenanceEntryDto): List<Pair<String, DwCanonicalComparisonDto>> =
    entry.canonical.entries.filter { it.value.hasMoved }.map { it.key to it.value }

// --------------------------------------------------------------------------------------
// The summary line
// --------------------------------------------------------------------------------------

/**
 * How much has moved: [records] rows, holding [fields] fields between them.
 *
 * TWO NUMBERS AND NOT ONE, because they answer different questions and a single total cannot tell
 * them apart. "Three fields across one participant" is one artisan whose record was corrected;
 * "three fields across three participants" is a whole cluster's directory having moved on. An admin
 * opening this screen is deciding which of those they are looking at.
 *
 * [records] rather than the web's `entries`, which is the same count under a name that does not
 * collide with [DwProvenanceReportDto.entries] two lines away.
 */
data class DwDivergenceTally(val records: Int, val fields: Int) {
    val nothingHasMoved: Boolean get() = fields == 0
}

/** [DwDivergenceTally] for a whole report. Entries with nothing to show are counted in neither. */
fun dwDivergenceTally(report: DwProvenanceReportDto): DwDivergenceTally {
    var records = 0
    var fields = 0
    for (entry in report.entries) {
        val moved = dwDivergedFields(entry)
        if (moved.isNotEmpty()) {
            records += 1
            fields += moved.size
        }
    }
    return DwDivergenceTally(records = records, fields = fields)
}

// --------------------------------------------------------------------------------------
// The words. Every one of these is the browser's, character for character
// --------------------------------------------------------------------------------------

/**
 * The screen's own name. `<PageHeader title="Authorship & divergence" />` on the web page.
 *
 * NOT "Provenance", which is the route's word and not the reader's, and not "Data quality", which
 * would tell an admin that what they are about to look at is a fault list.
 */
const val DW_PROVENANCE_TITLE = "Authorship & divergence"

/** While the request is in flight. The web's loading panel, same sentence, same ellipsis character. */
const val DW_PROVENANCE_LOADING = "Comparing this workshop against the shared records…"

/** The web page's `catch` fallback, for a failure that arrives without a sentence of its own. */
const val DW_PROVENANCE_LOAD_FAILED = "Could not load the provenance view."

/**
 * THE HEADLINE WHEN NOTHING HAS MOVED — the common case, answered in one line.
 *
 * A workshop with no divergence is what an admin will meet most of the time, and they want that
 * answered before they scroll twenty-two stages. Rendering an empty table instead reads like a failed
 * load, which is why this is a first-class state on both surfaces rather than a fallthrough.
 */
const val DW_PROVENANCE_NOTHING_MOVED = "Nothing has moved since this workshop was recorded"

/** The line under it, which is what makes the empty state an ANSWER rather than an absence. */
const val DW_PROVENANCE_ALL_MATCH =
    "Every value this workshop copied from a shared record still matches that record."

/**
 * THE SENTENCE THE WHOLE SCREEN IS BUILT AROUND, and the reason it has no warning colours.
 *
 * Copied from the web page verbatim, including "This page" — which is a browser's word for itself and
 * reads a shade oddly on a handset. It stays, and the reason it stays is rule 3 of this port: where
 * both surfaces say the same thing they say it in the same words, and an admin who reads a firm
 * "divergence is not an error" paragraph on a laptop must not meet a reworded one on the phone and
 * wonder which product is telling the truth. The browser's `<strong>` around "not an error" is
 * carried across as bold on the handset; see [DwProvenanceScreen].
 */
const val DW_PROVENANCE_NOT_AN_ERROR_PREFIX =
    "A workshop keeps what the designer saw on the day, so a value that differs from the shared " +
        "record today is "
const val DW_PROVENANCE_NOT_AN_ERROR_EMPHASIS = "not an error"
const val DW_PROVENANCE_NOT_AN_ERROR_SUFFIX =
    " — an artisan who moved village after the workshop makes every row that names them " +
        "differ, and every one of those rows is right. This page exists so the difference can be " +
        "seen and explained, not corrected."

/** The three column headings of the comparison table, in the browser's order and its words. */
const val DW_PROVENANCE_COLUMN_FIELD = "Field"
const val DW_PROVENANCE_COLUMN_STORED = "This workshop"
const val DW_PROVENANCE_COLUMN_CANONICAL = "The record today"

/**
 * THE DELETED-RECORD SENTENCE, rendered in the canonical column and never omitted.
 *
 * Not an error and the most interesting state on the screen: the record this value was copied from is
 * gone, so this workshop holds the only copy of what the designer saw — which is the entire argument
 * for copying in the first place.
 */
const val DW_PROVENANCE_RECORD_DELETED = "The record this came from no longer exists"

/**
 * The headline when something HAS moved: "N fields across M records now differ".
 *
 * Pluralised on each number independently, as the browser does, because "1 fields across 2 records"
 * is the kind of sentence that makes a reader distrust the number beside it. "differ" and not
 * "are wrong", "have drifted" or "need fixing" — the neutral word is the whole point of the screen.
 */
fun dwDivergenceHeadline(tally: DwDivergenceTally): String {
    if (tally.nothingHasMoved) return DW_PROVENANCE_NOTHING_MOVED
    val fields = "${tally.fields} field${if (tally.fields == 1) "" else "s"}"
    val records = "${tally.records} record${if (tally.records == 1) "" else "s"}"
    return "$fields across $records now differ"
}

/**
 * One entry's heading: the stage and entity in the words the registry gives them, plus the row.
 *
 * `${stage.title} · ${entity.title}` and the ` · row {ordinal + 1}` suffix are the web page's
 * `entryTitle` and its ordinal clause. ORDINAL IS SHOWN ONE-BASED because it is shown to a person:
 * `_ordinal` is zero-based everywhere in this protocol and "row 0" is a sentence no admin counting
 * participants down a table will match against what is in front of them.
 *
 * THE ROW CLAUSE IS FOR COLLECTIONS ONLY, AND THE ORDINAL CANNOT TELL YOU WHICH KIND OF ROW THIS IS.
 * Both surfaces asked it anyway — "is there an ordinal?" as a proxy for "is this one of a list?" —
 * and both were wrong the same way. [DwProvenanceEntryDto.ordinal] is never absent: the column is
 * `Int @default(0)`, its schema comment reads "A singleton's is always 0", and the route emits it
 * unconditionally. So a SINGLETON — the workshop's own setup answers, the traditional-process
 * write-up, both of which hydrate from shared records and land on this screen — was headed
 * "Workshop setup · Workshop details · row 1", naming a position in a list of one that is not a list
 * at all. The registry is already loaded for the titles and it knows the difference, so ask it.
 *
 * FALLS BACK TO THE KEYS RATHER THAN TO NOTHING, at every level. The registry is fetched separately
 * and may be a release behind the server that produced this report, so a stage or entity it has never
 * heard of is an ordinary state — and a key is readable, whereas a blank heading over a table of
 * differences leaves an admin unable to say which record they are looking at. An entity the registry
 * cannot name gets NO row clause: nothing here can say whether it repeats, and a wrong row number is
 * worse than none. The web falls back the same way, with one difference worth naming: it uses `??`,
 * so a registry entry present with an EMPTY title renders as an empty segment there and as the key
 * here. Blank is strictly worse than the key, and the case is degenerate on both.
 */
fun dwProvenanceEntryTitle(entry: DwProvenanceEntryDto, registry: SchemaResponse?): String {
    val stage = registry?.stages?.firstOrNull { it.key == entry.stageKey }
    val entity = stage?.entities?.firstOrNull { it.key == entry.entityKey }
    val stageLabel = stage?.title?.takeIf { it.isNotBlank() } ?: entry.stageKey
    val entityLabel = entity?.title?.takeIf { it.isNotBlank() } ?: entry.entityKey
    val head = "$stageLabel · $entityLabel"
    return if (entity?.cardinality == "COLLECTION") "$head · row ${entry.ordinal + 1}" else head
}
