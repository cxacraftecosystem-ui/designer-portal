package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

const val DW_COMPARISON_EMPTY = "—"

fun dwComparisonText(value: JsonElement?): String = when (value) {
    null, JsonNull -> DW_COMPARISON_EMPTY
    is JsonPrimitive ->
        when {
            value.isString -> if (value.content.isEmpty()) DW_COMPARISON_EMPTY else value.content
            else -> dwNumberText(value.content)
        }
    else -> value.toString()
}

private fun dwNumberText(literal: String): String {
    val number = literal.toDoubleOrNull() ?: return literal
    if (!number.isFinite() || number != Math.floor(number)) return literal
    if (number < Long.MIN_VALUE.toDouble() || number > Long.MAX_VALUE.toDouble()) return literal
    return number.toLong().toString()
}

fun dwCanonicalText(comparison: DwCanonicalComparisonDto): String =
    if (comparison.recordDeleted) DW_PROVENANCE_RECORD_DELETED else dwComparisonText(comparison.canonical)

val DwCanonicalComparisonDto.hasMoved: Boolean
    get() = diverged || recordDeleted

fun dwDivergedFields(entry: DwProvenanceEntryDto): List<Pair<String, DwCanonicalComparisonDto>> =
    entry.canonical.entries.filter { it.value.hasMoved }.map { it.key to it.value }

data class DwDivergenceTally(val records: Int, val fields: Int) {
    val nothingHasMoved: Boolean get() = fields == 0
}

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

const val DW_PROVENANCE_TITLE = "Authorship & divergence"

const val DW_PROVENANCE_LOADING = "Comparing this workshop against the shared records…"

const val DW_PROVENANCE_LOAD_FAILED = "Could not load the provenance view."

const val DW_PROVENANCE_NOTHING_MOVED = "Nothing has moved since this workshop was recorded"

const val DW_PROVENANCE_ALL_MATCH =
    "Every value this workshop copied from a shared record still matches that record."

const val DW_PROVENANCE_NOT_AN_ERROR_PREFIX =
    "A workshop keeps what the designer saw on the day, so a value that differs from the shared " +
        "record today is "
const val DW_PROVENANCE_NOT_AN_ERROR_EMPHASIS = "not an error"
const val DW_PROVENANCE_NOT_AN_ERROR_SUFFIX =
    " — an artisan who moved village after the workshop makes every row that names them " +
        "differ, and every one of those rows is right. This page exists so the difference can be " +
        "seen and explained, not corrected."

const val DW_PROVENANCE_COLUMN_FIELD = "Field"
const val DW_PROVENANCE_COLUMN_STORED = "This workshop"
const val DW_PROVENANCE_COLUMN_CANONICAL = "The record today"

const val DW_PROVENANCE_RECORD_DELETED = "The record this came from no longer exists"

fun dwDivergenceHeadline(tally: DwDivergenceTally): String {
    if (tally.nothingHasMoved) return DW_PROVENANCE_NOTHING_MOVED
    val fields = "${tally.fields} field${if (tally.fields == 1) "" else "s"}"
    val records = "${tally.records} record${if (tally.records == 1) "" else "s"}"
    return "$fields across $records now differ"
}

fun dwProvenanceEntryTitle(entry: DwProvenanceEntryDto, registry: SchemaResponse?): String {
    val stage = registry?.stages?.firstOrNull { it.key == entry.stageKey }
    val entity = stage?.entities?.firstOrNull { it.key == entry.entityKey }
    val stageLabel = stage?.title?.takeIf { it.isNotBlank() } ?: entry.stageKey
    val entityLabel = entity?.title?.takeIf { it.isNotBlank() } ?: entry.entityKey
    val head = "$stageLabel · $entityLabel"
    return if (entity?.cardinality == "COLLECTION") "$head · row ${entry.ordinal + 1}" else head
}
