package com.designprototype.workshop.data

import android.content.Context
import com.designprototype.workshop.ui.RegisterLoad
import com.designprototype.workshop.ui.RegisterSource
import com.designprototype.workshop.ui.designWorkshopStanding
import com.designprototype.workshop.ui.fieldWorkshopStatusWord
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.LocalDate

internal const val DW_LOCAL_FIELD_WORKSHOPS = "AllottedFieldWorkshop"

internal const val DW_LOCAL_DESIGN_WORKSHOPS = "AllottedDesignWorkshop"

private const val DW_LOCAL_WORKSHOP_GENERATION = 1

internal fun dwLocalWorkshopKey(model: String, userId: String): String =
    DwReferenceStore.cacheKey("$model:$userId-$DW_LOCAL_WORKSHOP_GENERATION", "ALL", "", "")

private const val DW_LOCAL_ANSWERED_NONE = "__none__"

internal data class DwAllottedWorkshops(
    val fieldWorkshops: List<WorkshopDetailDto> = emptyList(),
    val designWorkshops: List<DesignWorkshopDto> = emptyList(),
    val fieldLoad: RegisterLoad = RegisterLoad(),
    val designLoad: RegisterLoad = RegisterLoad(),
)

internal fun dwLocalWorkshopEnded(lastDay: String?, today: LocalDate): Boolean {
    val end = lastDay?.take(10)?.takeIf { it.length == 10 } ?: return false
    return end < today.toString()
}

internal fun dwLocalFieldWorkshopIsCurrent(workshop: WorkshopDetailDto, today: LocalDate): Boolean =
    fieldWorkshopStatusWord(workshop, today) == null

internal fun dwLocalDesignWorkshopIsCurrent(workshop: DesignWorkshopDto, today: LocalDate): Boolean =
    designWorkshopStanding(workshop) == 0 &&
        !dwLocalWorkshopEnded(workshop.endDate ?: workshop.startDate, today)

internal fun dwLocalFieldWorkshopToOption(workshop: WorkshopDetailDto): DwReferenceOption =
    DwReferenceOption(
        id = workshop.id,
        label = workshop.title,
        hint = workshop.place,
        data = dwLocalFacts(
            "workshopType" to workshop.workshopType,
            "date" to workshop.date,
            "startDate" to workshop.startDate,
            "endDate" to workshop.endDate,
            "createdAt" to workshop.createdAt,
        ),
    )

internal fun dwLocalOptionToFieldWorkshop(option: DwReferenceOption): WorkshopDetailDto? =
    option.id.takeIf { it.isNotBlank() }?.let { id ->
        WorkshopDetailDto(
            id = id,
            title = option.label,
            workshopType = option.data.dwLocalText("workshopType").orEmpty(),
            place = option.hint,
            date = option.data.dwLocalText("date"),
            startDate = option.data.dwLocalText("startDate"),
            endDate = option.data.dwLocalText("endDate"),
            createdAt = option.data.dwLocalText("createdAt"),
        )
    }

internal fun dwLocalDesignWorkshopToOption(workshop: DesignWorkshopDto): DwReferenceOption =
    DwReferenceOption(
        id = workshop.id,
        label = workshop.title,
        hint = workshop.craftName.orEmpty(),
        data = dwLocalFacts(
            "status" to workshop.status,
            "startDate" to workshop.startDate,
            "endDate" to workshop.endDate,
            "createdAt" to workshop.createdAt,
            "clusterName" to workshop.clusterName,
            "state" to workshop.state,
        ),
    )

internal fun dwLocalOptionToDesignWorkshop(option: DwReferenceOption): DesignWorkshopDto? =
    option.id.takeIf { it.isNotBlank() }?.let { id ->
        DesignWorkshopDto(
            id = id,
            title = option.label,
            status = option.data.dwLocalText("status") ?: "DRAFT",
            craftName = option.hint.ifBlank { null },
            clusterName = option.data.dwLocalText("clusterName"),
            state = option.data.dwLocalText("state"),
            startDate = option.data.dwLocalText("startDate"),
            endDate = option.data.dwLocalText("endDate"),
            createdAt = option.data.dwLocalText("createdAt"),
        )
    }

private fun dwLocalFacts(vararg pairs: Pair<String, String?>): JsonObject = JsonObject(
    pairs.mapNotNull { (key, value) ->
        value?.takeIf { it.isNotBlank() }?.let { key to JsonPrimitive(it) }
    }.toMap()
)

private fun JsonObject.dwLocalText(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun DwReferenceList.dwLocalRows(): List<DwReferenceOption> =
    items.filter { it.id.isNotBlank() && it.id != DW_LOCAL_ANSWERED_NONE }

internal fun dwLocalStored(model: String, options: List<DwReferenceOption>): DwReferenceList =
    DwReferenceList(
        model = model,
        items = options.ifEmpty { listOf(DwReferenceOption(id = DW_LOCAL_ANSWERED_NONE)) },
    )

private fun dwLocalLoad(list: DwReferenceList?): RegisterLoad =
    if (list == null) {
        RegisterLoad(source = RegisterSource.NONE)
    } else {
        RegisterLoad(source = RegisterSource.CACHED, fetchedAt = list.fetchedAt.takeIf { it.isNotBlank() })
    }

private fun <T> dwLocalCurrent(
    list: DwReferenceList?,
    decode: (DwReferenceOption) -> T?,
    isCurrent: (T) -> Boolean,
): List<T> = list?.dwLocalRows().orEmpty().mapNotNull(decode).filter(isCurrent)

private suspend fun dwLocalRead(
    load: suspend (String) -> DwReferenceList?,
    userId: String,
    today: LocalDate,
): DwAllottedWorkshops {
    if (userId.isBlank()) return DwAllottedWorkshops()
    val fieldList = load(dwLocalWorkshopKey(DW_LOCAL_FIELD_WORKSHOPS, userId))
    val designList = load(dwLocalWorkshopKey(DW_LOCAL_DESIGN_WORKSHOPS, userId))
    return DwAllottedWorkshops(
        fieldWorkshops = dwLocalCurrent(fieldList, ::dwLocalOptionToFieldWorkshop) {
            dwLocalFieldWorkshopIsCurrent(it, today)
        },
        designWorkshops = dwLocalCurrent(designList, ::dwLocalOptionToDesignWorkshop) {
            dwLocalDesignWorkshopIsCurrent(it, today)
        },
        fieldLoad = dwLocalLoad(fieldList),
        designLoad = dwLocalLoad(designList),
    )
}

internal suspend fun WorkshopRepository.cachedAllottedWorkshops(
    context: Context,
    today: LocalDate = LocalDate.now(),
): DwAllottedWorkshops = dwLocalRead(
    load = { key -> DwReferenceStore.load(context, key) },
    userId = cachedUser()?.id.orEmpty(),
    today = today,
)

internal suspend fun dwCachedAllottedWorkshops(
    root: File,
    userId: String,
    today: LocalDate = LocalDate.now(),
): DwAllottedWorkshops = dwLocalRead(
    load = { key -> DwReferenceStore.load(root, key) },
    userId = userId,
    today = today,
)

internal data class DwAllottedHalf<T, P>(
    val rows: List<T>,
    val page: P?,
    val load: RegisterLoad,
)

private suspend fun <T, P> dwLoadAllotted(
    load: suspend (String) -> DwReferenceList?,
    store: suspend (String, DwReferenceList) -> Unit,
    key: String?,
    model: String,
    decode: (DwReferenceOption) -> T?,
    encode: (T) -> DwReferenceOption,
    isCurrent: (T) -> Boolean,
    fetch: suspend () -> P,
    rowsOf: (P) -> List<T>,
    isTransient: (Throwable) -> Boolean,
    onCached: (List<T>, RegisterLoad) -> Unit,
): DwAllottedHalf<T, P> {
    var held = emptyList<T>()
    var heldLoad = RegisterLoad()
    if (key != null) {
        val file = load(key)
        held = dwLocalCurrent(file, decode, isCurrent)
        heldLoad = dwLocalLoad(file)
        onCached(held, heldLoad)
    }
    val attempt = runCatching { fetch() }
    attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    val page = attempt.getOrNull()
    val fresh = page?.let(rowsOf)?.filter(isCurrent)
    if (key != null && fresh != null) store(key, dwLocalStored(model, fresh.map(encode)))
    return DwAllottedHalf(
        rows = fresh ?: held,
        page = page,
        load = dwLocalRefreshLoad(attempt, heldLoad, isTransient),
    )
}

internal suspend fun <P> WorkshopRepository.loadAllottedFieldWorkshops(
    context: Context,
    fetch: suspend () -> P,
    rowsOf: (P) -> List<WorkshopDetailDto>,
    today: LocalDate = LocalDate.now(),
    onCached: (List<WorkshopDetailDto>, RegisterLoad) -> Unit = { _, _ -> },
): DwAllottedHalf<WorkshopDetailDto, P> = dwLoadAllotted(
    load = { key -> DwReferenceStore.load(context, key) },
    store = { key, list -> DwReferenceStore.store(context, key, list) },
    key = dwLocalKeyOrNull(DW_LOCAL_FIELD_WORKSHOPS, cachedUser()?.id.orEmpty()),
    model = DW_LOCAL_FIELD_WORKSHOPS,
    decode = ::dwLocalOptionToFieldWorkshop,
    encode = ::dwLocalFieldWorkshopToOption,
    isCurrent = { dwLocalFieldWorkshopIsCurrent(it, today) },
    fetch = fetch,
    rowsOf = rowsOf,
    isTransient = ::isTransient,
    onCached = onCached,
)

internal suspend fun <P> dwLoadAllottedFieldWorkshops(
    root: File,
    userId: String,
    fetch: suspend () -> P,
    rowsOf: (P) -> List<WorkshopDetailDto>,
    isTransient: (Throwable) -> Boolean,
    today: LocalDate = LocalDate.now(),
    onCached: (List<WorkshopDetailDto>, RegisterLoad) -> Unit = { _, _ -> },
): DwAllottedHalf<WorkshopDetailDto, P> = dwLoadAllotted(
    load = { key -> DwReferenceStore.load(root, key) },
    store = { key, list -> DwReferenceStore.store(root, key, list) },
    key = dwLocalKeyOrNull(DW_LOCAL_FIELD_WORKSHOPS, userId),
    model = DW_LOCAL_FIELD_WORKSHOPS,
    decode = ::dwLocalOptionToFieldWorkshop,
    encode = ::dwLocalFieldWorkshopToOption,
    isCurrent = { dwLocalFieldWorkshopIsCurrent(it, today) },
    fetch = fetch,
    rowsOf = rowsOf,
    isTransient = isTransient,
    onCached = onCached,
)

internal suspend fun <P> WorkshopRepository.loadAllottedDesignWorkshops(
    context: Context,
    fetch: suspend () -> P,
    rowsOf: (P) -> List<DesignWorkshopDto>,
    today: LocalDate = LocalDate.now(),
    onCached: (List<DesignWorkshopDto>, RegisterLoad) -> Unit = { _, _ -> },
): DwAllottedHalf<DesignWorkshopDto, P> = dwLoadAllotted(
    load = { key -> DwReferenceStore.load(context, key) },
    store = { key, list -> DwReferenceStore.store(context, key, list) },
    key = dwLocalKeyOrNull(DW_LOCAL_DESIGN_WORKSHOPS, cachedUser()?.id.orEmpty()),
    model = DW_LOCAL_DESIGN_WORKSHOPS,
    decode = ::dwLocalOptionToDesignWorkshop,
    encode = ::dwLocalDesignWorkshopToOption,
    isCurrent = { dwLocalDesignWorkshopIsCurrent(it, today) },
    fetch = fetch,
    rowsOf = rowsOf,
    isTransient = ::isTransient,
    onCached = onCached,
)

internal suspend fun <P> dwLoadAllottedDesignWorkshops(
    root: File,
    userId: String,
    fetch: suspend () -> P,
    rowsOf: (P) -> List<DesignWorkshopDto>,
    isTransient: (Throwable) -> Boolean,
    today: LocalDate = LocalDate.now(),
    onCached: (List<DesignWorkshopDto>, RegisterLoad) -> Unit = { _, _ -> },
): DwAllottedHalf<DesignWorkshopDto, P> = dwLoadAllotted(
    load = { key -> DwReferenceStore.load(root, key) },
    store = { key, list -> DwReferenceStore.store(root, key, list) },
    key = dwLocalKeyOrNull(DW_LOCAL_DESIGN_WORKSHOPS, userId),
    model = DW_LOCAL_DESIGN_WORKSHOPS,
    decode = ::dwLocalOptionToDesignWorkshop,
    encode = ::dwLocalDesignWorkshopToOption,
    isCurrent = { dwLocalDesignWorkshopIsCurrent(it, today) },
    fetch = fetch,
    rowsOf = rowsOf,
    isTransient = isTransient,
    onCached = onCached,
)

private fun dwLocalKeyOrNull(model: String, userId: String): String? =
    userId.takeIf { it.isNotBlank() }?.let { dwLocalWorkshopKey(model, it) }

internal suspend fun WorkshopRepository.refreshAllottedWorkshops(
    context: Context,
    today: LocalDate = LocalDate.now(),
): DwAllottedWorkshops {
    if (cachedUser()?.id.orEmpty().isBlank()) return DwAllottedWorkshops()

    val field = loadAllottedFieldWorkshops(
        context = context,
        fetch = { workshopsIMaySubmitToPage() },
        rowsOf = { it.items },
        today = today,
    )
    val design = loadAllottedDesignWorkshops(
        context = context,
        fetch = { visibleDesignWorkshops() },
        rowsOf = { it.items },
        today = today,
    )
    return DwAllottedWorkshops(
        fieldWorkshops = field.rows,
        designWorkshops = design.rows,
        fieldLoad = field.load,
        designLoad = design.load,
    )
}

private fun dwLocalRefreshLoad(
    attempt: Result<*>,
    held: RegisterLoad,
    isTransient: (Throwable) -> Boolean,
): RegisterLoad {
    if (attempt.isSuccess) return RegisterLoad(source = RegisterSource.LIVE, online = true)
    val cause = attempt.exceptionOrNull()
    return held.copy(online = cause != null && !isTransient(cause))
}
