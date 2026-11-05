package com.designprototype.workshop.ui

import com.designprototype.workshop.data.occurrenceDate
import com.designprototype.workshop.data.workshopDisplayTitle
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.WorkshopDetailDto
import java.time.LocalDate

const val NO_DESIGN_WORKSHOP: String = "Not filed under a design workshop"

const val NO_FIELD_WORKSHOP: String = "Not linked to a workshop"

const val ATTACH_LATER: String = "Don't attach it yet"

const val TYPE_DETAILS_INSTEAD: String = "Don't link a workshop — type details below"

const val ANY_WORKSHOP_KIND: String = "Any type of workshop"

enum class WorkshopListKind(val noun: String, val searchDestination: String) {
    DESIGN(noun = "design workshops", searchDestination = "Design workshops"),
    FIELD(noun = "workshops", searchDestination = "Workshops"),
}

sealed interface WorkshopListState {

    data object Loading : WorkshopListState

    data object Failed : WorkshopListState

    data class Listed(val count: Int, val total: Int) : WorkshopListState
}

internal val BUNDLED_LIST_HAS_NO_SENTENCE: String? = null

internal fun cachedListLine(count: Int, noun: String, refreshedOn: String): String =
    "$count $noun on this device (refreshed $refreshedOn). refresh with a connection before concluding it is not on record."

internal fun offlineListLine(noun: String): String =
    "No $noun list yet. Connect once to download it."

internal fun couldNotListLine(noun: String): String =
    "Couldn't load $noun. You can still save without it."

internal fun scopedEmptyLine(noun: String): String =
    "No $noun available. An admin can give you access."

internal fun narrowedEmptyLine(noun: String): String =
    "No $noun of this type. Choose “$ANY_WORKSHOP_KIND” to see all."

internal fun unscopedEmptyLine(noun: String): String = "No $noun have been recorded yet."

internal fun atWorkshopEmptyLine(noun: String): String =
    "No $noun at this workshop yet — not a claim about your other $noun."

internal fun loadingListLine(noun: String): String = "Looking for your $noun…"

internal enum class RegisterSource {
    PENDING,

    LIVE,

    CACHED,

    NONE
}

internal data class RegisterLoad(
    val source: RegisterSource = RegisterSource.PENDING,
    val fetchedAt: String? = null,
    val online: Boolean = false
) {
    val loaded: Boolean
        get() = source == RegisterSource.LIVE || source == RegisterSource.CACHED
}

internal fun registerListNotice(
    noun: String,
    rows: Int,
    load: RegisterLoad,
    emptyLine: String? = null,
): String? = when {
    rows > 0 && load.source == RegisterSource.LIVE -> null
    rows > 0 -> load.fetchedAt
        ?.let { readableStamp(it) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { cachedListLine(rows, noun, it) }
    load.source == RegisterSource.PENDING -> loadingListLine(noun)
    load.loaded -> emptyLine ?: unscopedEmptyLine(noun)
    load.online -> couldNotListLine(noun)
    else -> offlineListLine(noun)
}

internal fun readableStamp(iso: String?): String {
    val raw = iso?.trim().orEmpty()
    if (raw.length < 19) return ""
    val instant = raw.substring(0, 19)
    val tail = raw.substring(19)
    val zone = when {
        tail.endsWith("Z") || tail.isEmpty() -> java.util.TimeZone.getTimeZone("UTC")
        else -> java.util.TimeZone.getTimeZone("GMT" + tail.takeLast(6))
    }
    val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.UK).apply { timeZone = zone }
    val parsed = runCatching { parser.parse(instant) }.getOrNull() ?: return ""
    return java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.UK).format(parsed)
}

internal fun workshopListNotice(
    state: WorkshopListState,
    kind: WorkshopListKind,
    online: Boolean,
    narrowed: Boolean = false,
    cached: RegisterLoad = RegisterLoad(),
    cachedRows: Int = 0,
): String? {
    if (state !is WorkshopListState.Listed && cached.source == RegisterSource.CACHED) {
        return if (cachedRows > 0) {
            readableStamp(cached.fetchedAt)
                .takeIf { it.isNotEmpty() }
                ?.let { cachedListLine(cachedRows, kind.noun, it) }
        } else {
            scopedEmptyLine(kind.noun)
        }
    }
    return when (state) {
        WorkshopListState.Loading -> loadingListLine(kind.noun)
        WorkshopListState.Failed -> if (online) couldNotListLine(kind.noun) else offlineListLine(kind.noun)
        is WorkshopListState.Listed ->
            if (state.count > 0) null
            else if (narrowed) narrowedEmptyLine(kind.noun)
            else scopedEmptyLine(kind.noun)
    }
}

internal fun listIsAnswerable(options: List<SelectOption>): Boolean = options.isNotEmpty()

internal fun workshopCapLine(shown: Int, total: Int, kind: WorkshopListKind): String? {
    if (shown <= 0 || total <= shown) return null
    return "Showing $shown of $total. The rest didn't load. Open this again to retry."
}

internal fun designWorkshopLabel(workshop: DesignWorkshopDto): String =
    workshopDisplayTitle(
        workshop.title,
        date = workshop.startDate,
        workshopCode = workshop.workshopCode,
        clusterName = workshop.clusterName,
        district = workshop.district,
        state = workshop.state,
        craftName = workshop.craftName,
    )

internal fun fieldWorkshopLabel(workshop: WorkshopDetailDto): String =
    workshopDisplayTitle(workshop.title, date = workshop.occurrenceDate(), place = workshop.place)

internal fun designWorkshopStatusWord(status: String, handedOnAt: String? = null): String? =
    when (status.trim().uppercase()) {
        "PRE_SUBMISSION", "NEEDS_REVISION", "APPROVED", "SUBMITTED", "ARCHIVED" ->
            designWorkshopStatusLabel(status, handedOnAt)
        else -> null
    }

internal fun designWorkshopStatusLabel(status: String, handedOnAt: String? = null): String =
    when (status.trim().uppercase()) {
        "DRAFT" -> "Draft"
        "IN_PROGRESS" -> "In progress"
        "COMPLETE" -> "Complete"
        "PRE_SUBMISSION" -> "In pre-submission"
        "NEEDS_REVISION" -> "Needs revision"
        "APPROVED" -> "Approved"
        "SUBMITTED" -> if (handedOnAt.isNullOrBlank()) "Submitted" else "Handed on"
        "ARCHIVED" -> "Archived"
        else -> status.trim().lowercase().replace('_', ' ')
    }

internal fun designWorkshopHint(workshop: DesignWorkshopDto): String? = listOfNotNull(
    designWorkshopStatusWord(workshop.status, workshop.handedOnAt),
    workshop.craftName?.takeIf { it.isNotBlank() },
    workshop.clusterName?.takeIf { it.isNotBlank() } ?: workshop.state?.takeIf { it.isNotBlank() },
    workshop.startDate?.take(10)?.takeIf { it.isNotBlank() },
).joinToString(" · ").takeIf { it.isNotBlank() }

internal fun fieldWorkshopHint(workshop: WorkshopDetailDto, today: LocalDate = LocalDate.now()): String? =
    listOfNotNull(
        fieldWorkshopStatusWord(workshop, today),
        workshop.place.takeIf { it.isNotBlank() },
        fieldWorkshopOccurrence(workshop).take(10).takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotBlank() }

internal fun fieldWorkshopStatusWord(
    workshop: WorkshopDetailDto,
    today: LocalDate = LocalDate.now(),
): String? {
    val end = (workshop.endDate ?: workshop.date ?: workshop.startDate)?.take(10)?.takeIf { it.length == 10 }
        ?: return null
    return if (end < today.toString()) "Ended" else null
}

internal fun designWorkshopOccurrence(workshop: DesignWorkshopDto): String =
    workshop.startDate ?: workshop.createdAt ?: ""

internal fun fieldWorkshopOccurrence(workshop: WorkshopDetailDto): String =
    workshop.startDate ?: workshop.date ?: workshop.createdAt ?: ""

internal fun designWorkshopStanding(workshop: DesignWorkshopDto): Int =
    when (workshop.status.trim().uppercase()) {
        "SUBMITTED", "ARCHIVED", "APPROVED" -> 1
        else -> 0
    }

internal fun fieldWorkshopStanding(workshop: WorkshopDetailDto, today: LocalDate = LocalDate.now()): Int =
    if (fieldWorkshopStatusWord(workshop, today) == null) 0 else 1

internal fun offPageWorkshopRow(
    id: String,
    kind: WorkshopListKind,
    narrowed: Boolean = false,
): SelectOption = SelectOption(
    value = id,
    label = when (kind) {
        WorkshopListKind.DESIGN -> "The design workshop already on this record"
        WorkshopListKind.FIELD -> "The workshop already on this record"
    },
    hint = if (narrowed) {
        "Filed earlier · not in the list for the type chosen above"
    } else {
        "Filed earlier · could not list it just now, so its name is not shown"
    },
)

internal fun designWorkshopOptions(
    rows: List<DesignWorkshopDto>,
    offPageId: String = "",
    narrowed: Boolean = false,
): List<SelectOption> {
    val listed = rows
        .sortedWith(
            compareBy<DesignWorkshopDto> { designWorkshopStanding(it) }
                .thenByDescending { designWorkshopOccurrence(it) }
                .thenBy { designWorkshopLabel(it) }
                .thenBy { it.id }
        )
        .map { workshop ->
            SelectOption(
                value = workshop.id,
                label = designWorkshopLabel(workshop),
                hint = designWorkshopHint(workshop),
            )
        }
    val wanted = offPageId.trim()
    if (wanted.isEmpty() || listed.any { it.value == wanted }) return listed
    return listOf(offPageWorkshopRow(wanted, WorkshopListKind.DESIGN, narrowed)) + listed
}

internal fun fieldWorkshopOptions(
    rows: List<WorkshopDetailDto>,
    offPageId: String = "",
    today: LocalDate = LocalDate.now(),
): List<SelectOption> {
    val listed = rows
        .sortedWith(
            compareBy<WorkshopDetailDto> { fieldWorkshopStanding(it, today) }
                .thenByDescending { fieldWorkshopOccurrence(it) }
                .thenBy { fieldWorkshopLabel(it) }
                .thenBy { it.id }
        )
        .map { workshop ->
            SelectOption(
                value = workshop.id,
                label = fieldWorkshopLabel(workshop),
                hint = fieldWorkshopHint(workshop, today),
            )
        }
    val wanted = offPageId.trim()
    if (wanted.isEmpty() || listed.any { it.value == wanted }) return listed
    return listOf(offPageWorkshopRow(wanted, WorkshopListKind.FIELD)) + listed
}

internal fun workshopKindOptions(schema: SchemaResponse?): List<SelectOption> =
    schema?.enums?.get("WORKSHOP_KIND").orEmpty().map { option ->
        SelectOption(value = option.value, label = option.label)
    }

internal fun retainedWorkshopKind(chosen: String, offered: List<SelectOption>): String = when {
    offered.isEmpty() -> chosen
    offered.any { it.value == chosen } -> chosen
    else -> ""
}
