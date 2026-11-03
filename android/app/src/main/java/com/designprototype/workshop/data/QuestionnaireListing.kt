package com.designprototype.workshop.data

import kotlinx.coroutines.CancellationException

data class PagedListing<T>(
    val items: List<T>,
    val total: Int,
    val truncated: Boolean,
)

class PagedWalk<T>(
    private val maxPages: Int = DW_LIST_MAX_PAGES,
    private val idOf: (T) -> String,
) {

    private val ordered = LinkedHashMap<String, T>()
    private var reportedTotal = 0
    private var pagesFetched = 0
    private var done = false

    val items: List<T> get() = ordered.values.toList()

    val total: Int get() = reportedTotal

    val truncated: Boolean get() = ordered.size < reportedTotal

    fun listing(): PagedListing<T> =
        PagedListing(items = items, total = reportedTotal, truncated = truncated)

    fun accept(page: PageResponse<T>): Int? {
        if (done) return null
        pagesFetched++
        reportedTotal = page.total
        page.items.forEach { row ->
            val id = idOf(row)
            if (id.isNotBlank() && !ordered.containsKey(id)) ordered[id] = row
        }

        if (page.items.isEmpty()) return stop()
        if (ordered.size >= reportedTotal) return stop()
        if (pagesFetched >= maxPages) return stop()
        return pagesFetched + 1
    }

    fun stop(): Int? {
        done = true
        return null
    }
}

suspend fun <T> walkPagedListing(
    maxPages: Int = DW_LIST_MAX_PAGES,
    pageSize: Int = DW_LIST_PAGE_SIZE,
    idOf: (T) -> String,
    fetch: suspend (page: Int, pageSize: Int) -> PageResponse<T>,
): PagedListing<T> {
    val walk = PagedWalk(maxPages, idOf)
    var next: Int? = 1
    var first = true
    while (next != null) {
        val answered = if (first) {
            fetch(next, pageSize)
        } else {
            try {
                fetch(next, pageSize)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (offline: Throwable) {
                walk.stop()
                return walk.listing()
            }
        }
        first = false
        next = walk.accept(answered)
    }
    return walk.listing()
}

suspend fun WorkshopRepository.visibleQuestionnaires(
    search: String? = null,
    activeOnly: Boolean = true,
    filters: Map<String, String> = emptyMap(),
): PagedListing<CustomQuestionnaireSummaryDto> =
    walkPagedListing(idOf = { it.id }) { page, pageSize ->
        customQuestionnaires(
            page = page,
            pageSize = pageSize,
            search = search,
            activeOnly = activeOnly,
            filters = filters,
        )
    }
