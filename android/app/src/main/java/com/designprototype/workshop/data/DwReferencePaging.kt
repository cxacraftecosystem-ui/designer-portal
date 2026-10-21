package com.designprototype.workshop.data

/*
 * A REF picker's WHOLE list, read page by page (owner report, 2026-10-21).
 *
 * The picker used to ask for one page and take the server's default of 50, so "Pick the craft from
 * the list" held 50 of the 459 crafts on the national list and said "Partly loaded" while online.
 * It now reads every page at the server's ceiling, up to [DW_REFERENCE_READ_CAP] rows, caches the
 * lot for offline, and says "Partly loaded" only when the read really stopped short.
 */

/** The server's `REFERENCE_LIMIT_MAX`: rows per page. */
const val DW_REFERENCE_PAGE = 200

/** How many rows one picker reads before it stops and relies on a server search instead. */
const val DW_REFERENCE_READ_CAP = 1000

/** What reading every page produced: the options, the first page's ordering word, and whether it is whole. */
data class DwReferencePages(
    val options: List<DwReferenceOption>,
    val first: DwReferenceResponseDto,
    val complete: Boolean,
)

/**
 * Read pages from [fetch] until the server says the list is over.
 *
 * Stops, and reports the list as NOT complete, when the read reaches [cap] rows, or when a page
 * brings nothing new — which is how a server that ignores `offset` (one deployed before it) answers,
 * by sending the first page again. A throw from [fetch] on the first page propagates; a throw on a
 * later page keeps what was read and reports it as not complete.
 */
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

/** [list] with [extra] added after its own rows, by id; nothing it already holds is replaced. */
fun dwMergeReferenceOptions(list: DwReferenceList, extra: List<DwReferenceOption>): DwReferenceList {
    val known = list.items.mapTo(HashSet()) { it.id }
    val added = extra.filter { known.add(it.id) }
    return if (added.isEmpty()) list else list.copy(items = list.items + added)
}

/**
 * Every page of a `page`/`pageSize` list route, up to [maxPages]: the craft fallback used to read one
 * 500-row page, which is a silent cut the day the national list passes 500.
 */
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
