package com.designprototype.workshop.data

const val DW_REFERENCE_PAGE = 200

const val DW_REFERENCE_READ_CAP = 1000

data class DwReferencePages(
    val options: List<DwReferenceOption>,
    val first: DwReferenceResponseDto,
    val complete: Boolean,
)

suspend fun dwReadAllReferencePages(
    pageSize: Int = DW_REFERENCE_PAGE,
    cap: Int = DW_REFERENCE_READ_CAP,
    fetch: suspend (offset: Int, limit: Int) -> DwReferenceResponseDto,
): DwReferencePages {
    val first = fetch(0, pageSize)
    val byId = LinkedHashMap<String, DwReferenceOption>()
    first.options.forEach { byId.putIfAbsent(it.id, it) }
    var truncated = first.truncated
    var offset = first.options.size
    while (truncated && byId.size < cap) {
        val page = runCatching { fetch(offset, pageSize) }.getOrNull() ?: break
        val before = byId.size
        page.options.forEach { byId.putIfAbsent(it.id, it) }
        if (byId.size == before) break
        truncated = page.truncated
        offset += page.options.size
    }
    return DwReferencePages(options = byId.values.toList(), first = first, complete = !truncated)
}

fun dwMergeReferenceOptions(list: DwReferenceList, extra: List<DwReferenceOption>): DwReferenceList {
    val known = list.items.mapTo(HashSet()) { it.id }
    val added = extra.filter { known.add(it.id) }
    return if (added.isEmpty()) list else list.copy(items = list.items + added)
}

suspend fun <T> readAllListPages(
    maxPages: Int = 20,
    fetch: suspend (page: Int) -> PageResponse<T>,
): List<T> {
    val first = fetch(1)
    val rows = first.items.toMutableList()
    var page = 1
    while (page < first.pages.coerceAtMost(maxPages) && rows.size < first.total) {
        page += 1
        val next = fetch(page)
        if (next.items.isEmpty()) break
        rows += next.items
    }
    return rows
}
