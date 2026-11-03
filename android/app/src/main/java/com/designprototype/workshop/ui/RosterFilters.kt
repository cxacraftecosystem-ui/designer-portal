package com.designprototype.workshop.ui

import com.designprototype.workshop.data.ROLE_MATCH_READ_LIMIT
import java.time.LocalDate
import java.time.ZoneId

enum class RosterKind(val noun: String) {
    ACCESS("entries"),
    DESIGNER("designers"),
}

enum class RosterDir(val token: String) {
    ASC("asc"),
    DESC("desc"),
}

const val ADMIT_ROLE_DEFAULT: String = "default"

const val ROLE_NEVER_SIGNED_IN: String = "never-signed-in"

const val INSTITUTION_NONE: String = "none"

val ACCESS_STATUS_TOKENS: List<String> = listOf("ACTIVE", "PENDING", "REJECTED", "SUSPENDED")

val DESIGNER_STANDING_TOKENS: List<String> = listOf("active", "suspended")

val ACCESS_DATE_FIELDS: List<String> = listOf("added", "requested", "decided", "joined", "firstSeen")

val DESIGNER_DATE_FIELDS: List<String> = listOf("added", "firstSeen", "revoked")

val ROSTER_ROLE_LADDER: List<String> = listOf(
    "MASTER_ADMIN",
    "ADMIN",
    "MINISTRY_ADMIN",
    "REGIONAL_DIRECTOR",
    "ASSISTANT_DIRECTOR",
    "PROFESSOR",
    "INSPECTOR",
    "DESIGNER",
    "RESEARCHER",
    "FIELD_CONTRIBUTOR",
    "CROWDSOURCE_VOLUNTEER",
)

enum class SortValues { DATE, TEXT, COUNT, ENUM }

data class RosterSortSpec(
    val label: String,
    val defaultDir: RosterDir,
    val values: SortValues,
    val nullable: Boolean,
)

val ACCESS_SORTS: Map<String, RosterSortSpec> = linkedMapOf(
    "added" to RosterSortSpec("Added to the list", RosterDir.DESC, SortValues.DATE, nullable = false),
    "email" to RosterSortSpec("Email", RosterDir.ASC, SortValues.TEXT, nullable = false),
    "name" to RosterSortSpec("Name", RosterDir.ASC, SortValues.TEXT, nullable = true),
    "standing" to RosterSortSpec("Standing", RosterDir.ASC, SortValues.ENUM, nullable = false),
    "joined" to RosterSortSpec("Joined the platform", RosterDir.DESC, SortValues.DATE, nullable = true),
    "requested" to RosterSortSpec("Access requested", RosterDir.DESC, SortValues.DATE, nullable = true),
    "decided" to RosterSortSpec("Decision made", RosterDir.DESC, SortValues.DATE, nullable = true),
    "firstSeen" to RosterSortSpec("First signed in", RosterDir.DESC, SortValues.DATE, nullable = true),
    "attempts" to RosterSortSpec("Refused attempts", RosterDir.DESC, SortValues.COUNT, nullable = false),
)

val DESIGNER_SORTS: Map<String, RosterSortSpec> = linkedMapOf(
    "added" to RosterSortSpec("Added to the roster", RosterDir.DESC, SortValues.DATE, nullable = false),
    "email" to RosterSortSpec("Email", RosterDir.ASC, SortValues.TEXT, nullable = false),
    "name" to RosterSortSpec("Name", RosterDir.ASC, SortValues.TEXT, nullable = true),
    "institution" to RosterSortSpec("Institution", RosterDir.ASC, SortValues.TEXT, nullable = true),
    "firstSeen" to RosterSortSpec("First signed in", RosterDir.DESC, SortValues.DATE, nullable = true),
    "revoked" to RosterSortSpec("Access revoked", RosterDir.DESC, SortValues.DATE, nullable = true),
)

const val ROSTER_DEFAULT_SORT: String = "added"
val ROSTER_DEFAULT_DIR: RosterDir = RosterDir.DESC

fun rosterSorts(kind: RosterKind): Map<String, RosterSortSpec> =
    if (kind == RosterKind.ACCESS) ACCESS_SORTS else DESIGNER_SORTS

fun rosterSortSpec(kind: RosterKind, column: String): RosterSortSpec? = rosterSorts(kind)[column]

private val DIRECTION_PHRASE: Map<SortValues, Map<RosterDir, String>> = mapOf(
    SortValues.DATE to mapOf(RosterDir.DESC to "newest first", RosterDir.ASC to "oldest first"),
    SortValues.TEXT to mapOf(RosterDir.ASC to "A to Z", RosterDir.DESC to "Z to A"),
    SortValues.COUNT to mapOf(RosterDir.DESC to "most first", RosterDir.ASC to "fewest first"),
    SortValues.ENUM to mapOf(
        RosterDir.ASC to "in standing order",
        RosterDir.DESC to "in reverse standing order",
    ),
)

fun sortDirectionPhrase(values: SortValues, dir: RosterDir): String =
    DIRECTION_PHRASE.getValue(values).getValue(dir)

fun nextSortDir(kind: RosterKind, filters: RosterFilters, column: String): RosterDir {
    val spec = rosterSortSpec(kind, column) ?: return filters.dir
    if (filters.sort == column) {
        return if (filters.dir == RosterDir.ASC) RosterDir.DESC else RosterDir.ASC
    }
    return spec.defaultDir
}

fun nextRosterSort(kind: RosterKind, filters: RosterFilters, column: String): RosterFilters {
    rosterSortSpec(kind, column) ?: return filters
    return filters.copy(sort = column, dir = nextSortDir(kind, filters, column))
}

fun sortRowHint(kind: RosterKind, filters: RosterFilters, column: String): String? {
    val spec = rosterSortSpec(kind, column) ?: return null
    val dir = nextSortDir(kind, filters, column)
    val action = sortDirectionPhrase(spec.values, dir)
    return if (spec.nullable && dir == RosterDir.DESC) {
        "$action — rows with no date come first"
    } else {
        action
    }
}

enum class RosterRange(val id: String, val label: String) {
    ANY("any", "Any time"),
    TODAY("today", "Today"),
    LAST_7_DAYS("7d", "Last 7 days"),
    LAST_30_DAYS("30d", "Last 30 days"),
    LAST_90_DAYS("90d", "Last 90 days"),
    THIS_MONTH("month", "This month"),
    THIS_YEAR("year", "This year"),
    CUSTOM("custom", "Custom range"),
}

fun resolveRosterRange(
    range: RosterRange,
    from: LocalDate?,
    to: LocalDate?,
    today: LocalDate = LocalDate.now(),
): Pair<String?, String?> {
    val endOfToday = rosterEndOfDay(today)
    return when (range) {
        RosterRange.ANY -> null to null
        RosterRange.TODAY -> rosterStartOfDay(today) to endOfToday
        RosterRange.LAST_7_DAYS -> rosterStartOfDay(today.minusDays(6)) to endOfToday
        RosterRange.LAST_30_DAYS -> rosterStartOfDay(today.minusDays(29)) to endOfToday
        RosterRange.LAST_90_DAYS -> rosterStartOfDay(today.minusDays(89)) to endOfToday
        RosterRange.THIS_MONTH -> rosterStartOfDay(today.withDayOfMonth(1)) to endOfToday
        RosterRange.THIS_YEAR -> rosterStartOfDay(today.withDayOfYear(1)) to endOfToday
        RosterRange.CUSTOM -> from?.let(::rosterStartOfDay) to to?.let(::rosterEndOfDay)
    }
}

private fun rosterStartOfDay(date: LocalDate): String =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

private fun rosterEndOfDay(date: LocalDate): String =
    date.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toString()

data class RosterFilters(
    val search: String = "",
    val status: Set<String> = emptySet(),
    val standing: String = "",
    val roles: Set<String> = emptySet(),
    val institutions: Set<String> = emptySet(),
    val dateField: String = "added",
    val range: RosterRange = RosterRange.ANY,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val sort: String = ROSTER_DEFAULT_SORT,
    val dir: RosterDir = ROSTER_DEFAULT_DIR,
)

fun emptyRosterFilters(kind: RosterKind): RosterFilters = RosterFilters(
    dateField = dateFieldOptions(kind).firstOrNull()?.value ?: "added",
)

object RosterLabels {
    const val SEARCH = "Search"
    const val ACCESS_SEARCH = "Search the allow-list by email, name or note"
    const val DESIGNER_SEARCH = "Search the roster by email, name or institution"
    const val ACCESS_STATUS = "Standing"
    const val ACCESS_ROLES = "Tier they join at"
    const val DESIGNER_STANDING = "Standing"
    const val DESIGNER_ROLES = "Tier of the linked account"
    const val DESIGNER_INSTITUTIONS = "Institution"
    const val DATE_FIELD = "Which date"
    const val DATE_RANGE = "Date range"
    const val DATE_PERIOD = "Period"
    const val DATE_FROM = "From"
    const val DATE_TO = "To"
    const val SORT = "Order"
    const val CLEAR_ALL = "Clear every filter"
    const val FILTERS = "Filters"
}

const val DESIGNER_ROLE_HINT: String =
    "Matched by email. Admins aren't gated by this roster."

const val ACCESS_INSTITUTION_NOTE: String =
    "Institution is on the designer roster, not here."

val ACCESS_STATUS_OPTIONS: List<SelectOption> = listOf(
    SelectOption("ACTIVE", "May sign in"),
    SelectOption("PENDING", "Waiting for a decision"),
    SelectOption("REJECTED", "Refused"),
    SelectOption("SUSPENDED", "Suspended"),
)

val DESIGNER_STANDING_OPTIONS: List<SelectOption> = listOf(
    SelectOption("", "Everyone ever empanelled"),
    SelectOption("active", "Only those who may sign in"),
    SelectOption("suspended", "Only those suspended"),
)

fun roleOptions(kind: RosterKind): List<SelectOption> {
    val ladder = ROSTER_ROLE_LADDER.map { SelectOption(it, FieldPermissions.label(it)) }
    val reserved = if (kind == RosterKind.ACCESS) {
        SelectOption(
            ADMIT_ROLE_DEFAULT,
            "At the default joining tier",
            "Admitted without a tier",
        )
    } else {
        SelectOption(
            ROLE_NEVER_SIGNED_IN,
            "Has never signed in",
            "Empanelled, never signed in",
        )
    }
    return ladder + reserved
}

fun institutionOptions(names: List<String>): List<SelectOption> {
    val seen = mutableSetOf(INSTITUTION_NONE)
    val rows = mutableListOf<SelectOption>()
    names.forEach { raw ->
        val value = raw.trim()
        if (value.isEmpty() || !seen.add(value)) return@forEach
        rows.add(SelectOption(value, value))
    }
    rows.add(SelectOption(INSTITUTION_NONE, "No institution recorded"))
    return rows
}

fun dateFieldOptions(kind: RosterKind): List<SelectOption> = if (kind == RosterKind.ACCESS) {
    listOf(
        SelectOption("added", "Added to the list"),
        SelectOption("requested", "Access requested"),
        SelectOption("decided", "Decision made"),
        SelectOption("joined", "Joined the platform"),
        SelectOption("firstSeen", "First signed in"),
    )
} else {
    listOf(
        SelectOption("added", "Added to the roster"),
        SelectOption("firstSeen", "First signed in"),
        SelectOption("revoked", "Access revoked"),
    )
}

val RANGE_OPTIONS: List<SelectOption> =
    RosterRange.entries.map { SelectOption(it.id, it.label) }

fun sortOptions(kind: RosterKind, filters: RosterFilters): List<SelectOption> =
    rosterSorts(kind).map { (column, spec) ->
        SelectOption(column, spec.label, sortRowHint(kind, filters, column))
    }

internal fun tokenList(values: Set<String>, order: List<String>): String? {
    val chosen = values.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (chosen.isEmpty()) return null
    val ordered = order.filter { it in chosen }
    val extras = chosen.filterNot { it in ordered }.sorted()
    return (ordered + extras).joinToString(",")
}

internal fun institutionList(values: Set<String>): String? {
    val chosen = values.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (chosen.isEmpty()) return null
    val named = chosen.filterNot { it == INSTITUTION_NONE }.sorted()
    val tail = if (INSTITUTION_NONE in chosen) listOf(INSTITUTION_NONE) else emptyList()
    return (named + tail).joinToString(",")
}

internal fun roleOrder(kind: RosterKind): List<String> =
    ROSTER_ROLE_LADDER + (if (kind == RosterKind.ACCESS) ADMIT_ROLE_DEFAULT else ROLE_NEVER_SIGNED_IN)

data class RosterQueryParams(
    val search: String? = null,
    val status: String? = null,
    val standing: String? = null,
    val roles: String? = null,
    val institutions: String? = null,
    val dateField: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val sort: String? = null,
    val dir: String? = null,
) {
    val newGrammarKeys: List<String>
        get() = buildList {
            if (status?.contains(',') == true) add("standing")
            if (standing != null) add("standing")
            if (roles != null) add("roles")
            if (institutions != null) add("institutions")
            if (dateFrom != null || dateTo != null) add("date range")
            if (sort != null) add("order")
        }.distinct()
}

fun rosterQueryParams(
    kind: RosterKind,
    filters: RosterFilters,
    today: LocalDate = LocalDate.now(),
): RosterQueryParams {
    val access = kind == RosterKind.ACCESS
    val (dateFrom, dateTo) = resolveRosterRange(filters.range, filters.from, filters.to, today)
    val dated = dateFrom != null || dateTo != null
    val ordered = filters.sort == ROSTER_DEFAULT_SORT && filters.dir == ROSTER_DEFAULT_DIR
    return RosterQueryParams(
        search = filters.search.trim().ifBlank { null },
        status = if (access) tokenList(filters.status, ACCESS_STATUS_TOKENS) else null,
        standing = if (access) null else filters.standing.ifBlank { null },
        roles = tokenList(filters.roles, roleOrder(kind)),
        institutions = if (access) null else institutionList(filters.institutions),
        dateField = if (dated) filters.dateField else null,
        dateFrom = if (dated) dateFrom else null,
        dateTo = if (dated) dateTo else null,
        sort = if (ordered) null else filters.sort,
        dir = if (ordered) null else filters.dir.token,
    )
}

fun hasActiveRosterFilters(kind: RosterKind, filters: RosterFilters): Boolean {
    val access = kind == RosterKind.ACCESS
    return filters.search.isNotBlank() ||
        (access && filters.status.isNotEmpty()) ||
        (!access && filters.standing.isNotBlank()) ||
        filters.roles.isNotEmpty() ||
        (!access && filters.institutions.isNotEmpty()) ||
        filters.range != RosterRange.ANY
}

fun sheetFilterCount(kind: RosterKind, filters: RosterFilters): Int {
    val access = kind == RosterKind.ACCESS
    return listOf(
        filters.roles.isNotEmpty(),
        !access && filters.institutions.isNotEmpty(),
        filters.range != RosterRange.ANY,
    ).count { it }
}

fun toggledAccessStatus(selected: Set<String>, token: String): Set<String> {
    val next = if (token in selected) selected - token else selected + token
    return if (next.size >= ACCESS_STATUS_TOKENS.size) emptySet() else next
}

fun clearRosterFilters(kind: RosterKind, filters: RosterFilters): RosterFilters =
    emptyRosterFilters(kind).copy(sort = filters.sort, dir = filters.dir)

fun roleMatchCutNotice(truncated: Boolean?, limit: Int? = ROLE_MATCH_READ_LIMIT): String? {
    if (truncated != true) return null
    val bound = if (limit != null && limit > 0) {
        "more than $limit accounts"
    } else {
        "too many accounts"
    }
    return "Some designers are missing from any page because $bound hold these tiers. Choose fewer tiers."
}

fun accessRoleCutNotice(truncated: Boolean?): String? {
    if (truncated != true) return null
    return "Some entries for these tiers are missing from any page. Choose fewer tiers or clear the filter."
}

fun institutionCutNotice(truncated: Boolean?, offered: Int): String? {
    if (truncated != true) return null
    if (offered <= 0) {
        return "Too many institutions to list. Use the search box above."
    }
    return "Only the first $offered institutions are listed. Use the search box above for others."
}

fun rosterFilterGrammarNotice(understood: Boolean?, sent: List<String>): String? {
    if (understood != false || sent.isEmpty()) return null
    return "Couldn't apply the ${namedControls(sent)} — showing the full roster. Nothing is missing."
}

fun rosterFilterRefusalHint(understood: Boolean?, sent: List<String>): String? {
    if (understood == true || sent.isEmpty()) return null
    return "The ${namedControls(sent)} may have stopped the list loading. Clearing them lists everybody again."
}

private fun namedControls(sent: List<String>): String = when (sent.size) {
    0 -> ""
    1 -> sent[0]
    2 -> "${sent[0]} and ${sent[1]}"
    else -> sent.dropLast(1).joinToString(", ") + " and " + sent.last()
}
