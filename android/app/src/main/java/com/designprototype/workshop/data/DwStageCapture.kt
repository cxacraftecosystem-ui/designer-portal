package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeParseException

/*
 * Stage capture that fills itself in (owner decisions of 2026-10-17): numbering a new row (C6), the
 * counts that replace typed boxes (C7, C8), and the server's one-tap suggestions (A14, A16, B10,
 * C6, C7, C10). The handset's ports of `stage_schema.next_auto_number`, `app/services/stage_computed.py`
 * and the payload of `app/services/stage_assist.py`; the web's are `lib/autoNumber.ts`,
 * `lib/stageComputed.ts` and `lib/stageAssist.ts`. Pure, so it runs with no signal.
 */

private fun JsonElement?.text(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> contentOrNull.orEmpty()
    else -> toString()
}

private fun JsonElement?.isBlankValue(): Boolean = when (this) {
    null, JsonNull -> true
    is JsonArray -> isEmpty()
    is JsonPrimitive -> isString && content.isEmpty()
    else -> false
}

private fun JsonElement?.toIntOrNull(): Int? = text().trim().toDoubleOrNull()?.toInt()

/** A row's two identities: the server's `_entryId` and this device's `_clientKey`/row id. */
fun dwRowKeys(values: Map<String, JsonElement>, rowId: String? = null): Set<String> =
    listOfNotNull(values["_entryId"].text().ifBlank { null }, values["_clientKey"].text().ifBlank { null }, rowId).toSet()

object DwAutoNumber {
    const val SEQUENCE = "#"

    fun format(prefix: String, n: Int): String = "$prefix-${n.toString().padStart(2, '0')}"

    /** The number a NEW row takes in [field], or null when it numbers nothing. */
    fun nextNumber(field: FieldDto, rows: List<Map<String, JsonElement>>, scope: JsonElement? = null): JsonElement? {
        if (field.autoNumber.isBlank()) return null
        val within = field.autoNumberWithin
        val considered = rows.filter { within.isBlank() || it[within].text() == scope.text() }
        var highest = 0
        if (field.autoNumber == SEQUENCE) {
            considered.forEach { row -> row[field.key].text().trim().toIntOrNull()?.let { highest = maxOf(highest, it) } }
            return JsonPrimitive(maxOf(highest, considered.size) + 1)
        }
        val pattern = Regex("^\\s*${Regex.escape(field.autoNumber)}\\s*-?\\s*(\\d+)\\s*$", RegexOption.IGNORE_CASE)
        considered.forEach { row ->
            pattern.matchEntire(row[field.key].text())?.let { highest = maxOf(highest, it.groupValues[1].toInt()) }
        }
        return JsonPrimitive(format(field.autoNumber, maxOf(highest, considered.size) + 1))
    }

    /** `start` plus `day - 1` days, as yyyy-mm-dd, or null. */
    fun dateOfDay(start: String?, day: JsonElement?): String? {
        val n = day.toIntOrNull() ?: return null
        if (n < 1) return null
        return try {
            LocalDate.parse(start.orEmpty().take(10)).plusDays((n - 1).toLong()).toString()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * [next] values with every auto-numbered or auto-dated box filled where the rules allow: a row
     * that is NEW (its id is not in [previous]) or whose scope was just chosen, blank boxes only.
     * Rows are numbered in order so a batch of ten reads S-04 … S-13. An existing number is never
     * touched.
     */
    fun apply(
        entity: EntityDto,
        previous: List<Pair<String, Map<String, JsonElement>>>,
        next: List<Pair<String, Map<String, JsonElement>>>,
        startDate: String?,
    ): List<Pair<String, Map<String, JsonElement>>> {
        val numbered = entity.fields.filter { !it.deprecated && it.autoNumber.isNotBlank() }
        val dated = entity.fields.filter { !it.deprecated && it.autoDateFromDay.isNotBlank() }
        if (numbered.isEmpty() && dated.isEmpty()) return next
        val before = previous.toMap()
        val out = mutableListOf<Pair<String, Map<String, JsonElement>>>()
        next.forEachIndexed { index, (id, original) ->
            val old = before[id]
            var values = original
            for (field in numbered) {
                if (!values[field.key].isBlankValue()) continue
                val within = field.autoNumberWithin
                val scopeChanged = within.isNotBlank() && old != null && old[within].text() != values[within].text()
                if (old != null && !scopeChanged) continue
                if (within.isNotBlank() && values[within].isBlankValue()) continue
                // The rows before this one as numbered so far, and the EXISTING rows after it — never
                // the other new rows still to come, or a batch would skip numbers.
                val others = out.map { it.second } + next.drop(index + 1).filter { it.first in before }.map { it.second }
                val value = nextNumber(field, others, if (within.isNotBlank()) values[within] else null) ?: continue
                values = values + (field.key to value)
            }
            for (field in dated) {
                if (!values[field.key].isBlankValue()) continue
                if (old != null && old[field.autoDateFromDay].text() == values[field.autoDateFromDay].text()) continue
                val date = dateOfDay(startDate, values[field.autoDateFromDay]) ?: continue
                values = values + (field.key to JsonPrimitive(date))
            }
            out += id to values
        }
        return out
    }
}

object DwStageComputed {
    val FEMALE_TOKENS = setOf("female", "f", "woman", "women", "girl", "महिला", "स्त्री")

    fun isWoman(gender: JsonElement?): Boolean = gender.text().trim().lowercase() in FEMALE_TOKENS

    fun absentDays(row: Map<String, JsonElement>): Set<String> =
        (row["absentDays"] as? JsonArray).orEmpty().mapNotNull { it.toIntOrNull()?.toString() }.toSet()

    /**
     * Days attended typed before the tick-list existed and never ticked since: no `absentDays` list
     * and a stored `attendedDays`. Never read back as "All present" (`is_legacy_attendance`).
     */
    fun isLegacyAttendance(row: Map<String, JsonElement>): Boolean =
        row["absentDays"] !is JsonArray && row["attendedDays"].text().isNotBlank()

    fun attendedDays(row: Map<String, JsonElement>, days: List<Int>): Int? =
        if (days.isEmpty() || isLegacyAttendance(row)) null
        else days.count { it.toString() !in absentDays(row) }

    fun named(rows: List<Map<String, JsonElement>>): List<Map<String, JsonElement>> =
        rows.filter { it["name"].text().isNotBlank() || !it["artisanRef"].isBlankValue() }

    data class RosterValues(val count: Int?, val women: Int?, val completed: Int?)

    fun rosterValues(rows: List<Map<String, JsonElement>>, days: List<Int>): RosterValues {
        val people = named(rows)
        if (people.isEmpty()) return RosterValues(null, null, null)
        return RosterValues(
            count = people.size,
            women = people.count { isWoman(it["gender"]) },
            completed = if (days.isEmpty() || people.any { isLegacyAttendance(it) }) null
            else people.count { attendedDays(it, days) == days.size },
        )
    }

    /** The schedule's day numbers, else [fallback] (the server's 1..duration). */
    fun workshopDays(schedule: List<Map<String, JsonElement>>, fallback: List<Int>): List<Int> {
        val numbers = schedule.mapNotNull { it["dayNumber"].toIntOrNull() }.filter { it in 1..366 }.distinct().sorted()
        return numbers.ifEmpty { fallback }
    }

    /** Stage 1's duration: the stored box, else start to end inclusive (`setup_duration`). */
    fun setupDuration(setup: Map<String, JsonElement>?): Int? {
        if (setup == null) return null
        setup["durationDays"].toIntOrNull()?.takeIf { it > 0 }?.let { return minOf(it, 366) }
        return try {
            val start = LocalDate.parse(setup["startDate"].text().take(10))
            val end = LocalDate.parse(setup["endDate"].text().take(10))
            val days = (end.toEpochDay() - start.toEpochDay() + 1).toInt()
            if (days > 0) minOf(days, 366) else null
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** 1..duration from stage 1, the server's fallback when the schedule has no rows. */
    fun setupDays(setup: Map<String, JsonElement>?): List<Int> = setupDuration(setup)?.let { (1..it).toList() }.orEmpty()

    /** Stage 1's start date as yyyy-mm-dd, or null. */
    fun setupStart(setup: Map<String, JsonElement>?): String? = try {
        LocalDate.parse(setup?.get("startDate").text().take(10)).toString()
    } catch (_: DateTimeParseException) {
        null
    }

    /** Mark the row present or absent on [day], keeping the stored list sorted. */
    fun withAttendance(row: Map<String, JsonElement>, day: Int, present: Boolean): Map<String, JsonElement> {
        val absent = absentDays(row).toMutableSet()
        if (present) absent.remove(day.toString()) else absent.add(day.toString())
        return row + ("absentDays" to JsonArray(absent.sortedBy { it.toInt() }.map { JsonPrimitive(it) }))
    }
}

@Serializable
data class DwAssistSuggestionDto(
    val id: String = "",
    /** FILL | ADD_ROWS */
    val kind: String = "",
    val entityKey: String = "",
    /** The row a FILL targets (`_entryId` or `_clientKey`); null for the stage's singleton. */
    val rowKey: String? = null,
    val title: String = "",
    val detail: String = "",
    val values: Map<String, JsonElement> = emptyMap(),
    val rows: List<Map<String, JsonElement>> = emptyList(),
    val replaces: Boolean = false,
    val due: Boolean = false,
)

@Serializable
data class DwAssistComputedDto(
    val singleton: Map<String, Int?> = emptyMap(),
    val rows: Map<String, Map<String, Map<String, Int?>>> = emptyMap(),
)

@Serializable
data class DwAssistSittingDto(
    val questionnaireId: String = "",
    val title: String = "",
    val count: Int = 0,
    val latest: String? = null,
    val respondents: List<String> = emptyList(),
)

/** `GET /design-workshops/{id}/stages/{stageKey}/assist`. */
@Serializable
data class DwStageAssistDto(
    val stageKey: String = "",
    val suggestions: List<DwAssistSuggestionDto> = emptyList(),
    val computed: DwAssistComputedDto = DwAssistComputedDto(),
    val attendanceDays: List<Int> = emptyList(),
    val startDate: String? = null,
    val sittings: List<DwAssistSittingDto> = emptyList(),
)

object DwStageAssist {
    /** The values of a FILL that still land on a blank box (all of them for a `replaces` FILL). */
    fun fillValues(suggestion: DwAssistSuggestionDto, target: Map<String, JsonElement>?): Map<String, JsonElement> =
        suggestion.values.filter { (key, _) -> suggestion.replaces || target?.get(key).isBlankValue() }

    fun targetRow(
        suggestion: DwAssistSuggestionDto,
        rows: List<Pair<String, Map<String, JsonElement>>>,
    ): Pair<String, Map<String, JsonElement>>? =
        suggestion.rowKey?.let { key -> rows.firstOrNull { (id, values) -> key in dwRowKeys(values, id) } }

    /** Whether the suggestion still has something to do against the form as it stands. */
    fun stillApplies(
        suggestion: DwAssistSuggestionDto,
        singleton: Map<String, JsonElement>,
        collections: Map<String, List<Pair<String, Map<String, JsonElement>>>>,
    ): Boolean {
        if (suggestion.kind == "ADD_ROWS") return true
        val target = if (suggestion.rowKey == null) singleton
        else targetRow(suggestion, collections[suggestion.entityKey].orEmpty())?.second ?: return false
        val values = fillValues(suggestion, target)
        if (suggestion.replaces) return values.any { (k, v) -> target[k].text() != v.text() }
        return values.isNotEmpty()
    }

    fun actionWord(suggestion: DwAssistSuggestionDto): String = when {
        suggestion.due -> "Record"
        suggestion.kind == "ADD_ROWS" && suggestion.rows.size > 1 -> "Add ${suggestion.rows.size}"
        suggestion.kind == "ADD_ROWS" -> "Add"
        else -> "Use"
    }
}

/** A15: the stage name logged last for a prototype, by log date then position. */
fun dwLastStageName(prototypeKeys: Set<String>, logs: List<Map<String, JsonElement>>): String {
    var bestDate = ""
    var best = ""
    logs.forEach { log ->
        val name = log["stageName"].text().trim()
        if (name.isEmpty() || log["prototypeRef"].text() !in prototypeKeys) return@forEach
        val date = log["logDate"].text()
        if (best.isEmpty() || date >= bestDate) {
            bestDate = date
            best = name
        }
    }
    return best
}

/**
 * Stage 3's assist as the handset can work it out with no signal (C6/C7): the workshop days and the
 * start date from the cached stage 1 draft, and "Fill the schedule" — the server's
 * `schedule_suggestions`, row for row. The server's answer stays the authority when there is one;
 * this only fills what it left empty, and stands in whole when it is null (offline, or a workshop
 * never sent to the server). Every other stage gets [assist] back untouched.
 */
fun dwLocalStageAssist(
    stageKey: String,
    assist: DwStageAssistDto?,
    setup: Map<String, JsonElement>?,
    schedule: List<Map<String, JsonElement>>,
): DwStageAssistDto? {
    if (stageKey != "WORKSHOP_PLAN_PARTICIPANTS_OPENING") return assist
    val start = DwStageComputed.setupStart(setup)
    val days = DwStageComputed.setupDays(setup)
    val base = assist ?: DwStageAssistDto(stageKey = stageKey)
    val suggestions = if (assist != null) base.suggestions
    else listOfNotNull(dwFillTheSchedule(start, days.size, schedule))
    return base.copy(
        suggestions = suggestions,
        attendanceDays = base.attendanceDays.ifEmpty { days },
        startDate = base.startDate ?: start,
    )
}

/** "Fill the schedule": one row per workshop day not yet scheduled, dated from the start. */
fun dwFillTheSchedule(start: String?, duration: Int, schedule: List<Map<String, JsonElement>>): DwAssistSuggestionDto? {
    if (start == null || duration <= 0) return null
    val have = schedule.mapNotNull { it["dayNumber"].toIntOrNull() }.toSet()
    val first = LocalDate.parse(start)
    val rows = (1..duration).filter { it !in have }.map { n ->
        mapOf<String, JsonElement>(
            "dayNumber" to JsonPrimitive(n),
            "date" to JsonPrimitive(first.plusDays((n - 1).toLong()).toString()),
        )
    }
    if (rows.isEmpty()) return null
    val plural = if (rows.size == 1) "day" else "days"
    // `_short_date`'s "5 Oct 2026", in English whatever the handset's language.
    val shortDate = first.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
    return DwAssistSuggestionDto(
        id = "schedule:fill",
        kind = "ADD_ROWS",
        entityKey = "workshopScheduleDay",
        title = "Fill the schedule",
        detail = "Adds ${rows.size} $plural from $shortDate, numbered and dated.",
        rows = rows,
    )
}

/** The `jsonPrimitive` content of [value] or "", for display. */
fun dwDisplayText(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    is JsonArray -> value.joinToString(", ") { runCatching { it.jsonPrimitive.content }.getOrDefault(it.toString()) }
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    else -> value.toString()
}

/** The computed boxes that differ from what the screen holds (C7/C8). */
data class DwComputedPatch(
    val singleton: Map<String, JsonElement> = emptyMap(),
    val rows: Map<String, List<Pair<String, Map<String, JsonElement>>>> = emptyMap(),
)

private fun JsonElement?.asCount(): Int? = text().trim().toDoubleOrNull()?.toInt()

/**
 * What to write so every computed count equals its rows: the roster's totals and days attended on
 * stage 3 (counted here, offline), the survey's responses on stage 8 and the completions and
 * certificates on stage 19 (from the server's answer). Nothing for a count that cannot be formed,
 * which leaves a value typed on an older workshop where it was.
 */
fun dwComputedPatch(
    stageKey: String,
    assist: DwStageAssistDto?,
    singleton: Map<String, JsonElement>,
    collections: Map<String, List<Pair<String, Map<String, JsonElement>>>>,
): DwComputedPatch {
    val single = mutableMapOf<String, Int>()
    val rowRules = mutableMapOf<String, Pair<String, (String, Map<String, JsonElement>) -> Int?>>()
    when (stageKey) {
        "WORKSHOP_PLAN_PARTICIPANTS_OPENING" -> {
            val days = DwStageComputed.workshopDays(
                collections["workshopScheduleDay"].orEmpty().map { it.second },
                assist?.attendanceDays.orEmpty(),
            )
            val values = DwStageComputed.rosterValues(collections["participant"].orEmpty().map { it.second }, days)
            values.count?.let { single["totalArtisans"] = it }
            values.women?.let { single["womenParticipants"] = it }
            rowRules["participant"] = "attendedDays" to { _, row -> DwStageComputed.attendedDays(row, days) }
        }
        "MARKET_SURVEY_CAPTURE" -> if (assist != null) {
            val count = collections["surveyResponse"].orEmpty().size + assist.sittings.sumOf { it.count }
            if (count > 0) single["responsesCollected"] = count
        }
        "INSPECTION_CLOSING" -> if (assist != null) {
            assist.computed.singleton["participantsCompleted"]?.let { single["participantsCompleted"] = it }
            val byRow = assist.computed.rows["certificate"].orEmpty()
            rowRules["certificate"] = "daysAttended" to { rowId, row ->
                dwRowKeys(row, rowId).firstNotNullOfOrNull { byRow[it]?.get("daysAttended") }
            }
        }
    }
    val singlePatch = single
        .filter { (key, value) -> singleton[key].asCount() != value }
        .mapValues { (_, value) -> JsonPrimitive(value) as JsonElement }
    val rowPatch = rowRules.mapNotNull { (entityKey, rule) ->
        val (fieldKey, compute) = rule
        var dirty = false
        val next = collections[entityKey].orEmpty().map { (rowId, row) ->
            val value = compute(rowId, row)
            // A counted participant carries its tick-list, so it is never read back as a typed one.
            val stamp = entityKey == "participant" && row["absentDays"] !is JsonArray
            if (value == null || (row[fieldKey].asCount() == value && !stamp)) rowId to row
            else {
                dirty = true
                val counted = row + (fieldKey to JsonPrimitive(value))
                rowId to (if (stamp) counted + ("absentDays" to JsonArray(emptyList())) else counted)
            }
        }
        if (dirty) entityKey to next else null
    }.toMap()
    return DwComputedPatch(singlePatch, rowPatch)
}
