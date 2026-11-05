package com.designprototype.workshop.data

import kotlinx.coroutines.CancellationException

const val DW_LIST_PAGE_SIZE = 100

const val DW_LIST_MAX_PAGES = 200

data class DesignWorkshopListing(
    val items: List<DesignWorkshopDto>,
    val total: Int,
    val truncated: Boolean,
)

class DesignWorkshopPageWalk(private val maxPages: Int = DW_LIST_MAX_PAGES) {

    private val ordered = LinkedHashMap<String, DesignWorkshopDto>()
    private var reportedTotal = 0
    private var pagesFetched = 0
    private var done = false

    val items: List<DesignWorkshopDto> get() = ordered.values.toList()

    val total: Int get() = reportedTotal

    val truncated: Boolean get() = ordered.size < reportedTotal

    fun listing(): DesignWorkshopListing =
        DesignWorkshopListing(items = items, total = reportedTotal, truncated = truncated)

    fun accept(page: DesignWorkshopPageDto): Int? {
        if (done) return null
        pagesFetched++
        reportedTotal = page.total
        page.items.forEach { row ->
            if (row.id.isNotBlank() && !ordered.containsKey(row.id)) ordered[row.id] = row
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

suspend fun walkDesignWorkshopPages(
    maxPages: Int = DW_LIST_MAX_PAGES,
    fetch: suspend (page: Int, pageSize: Int) -> DesignWorkshopPageDto,
): DesignWorkshopListing {
    val walk = DesignWorkshopPageWalk(maxPages)
    var next: Int? = 1
    var first = true
    while (next != null) {
        val answered = if (first) {
            fetch(next, DW_LIST_PAGE_SIZE)
        } else {
            try {
                fetch(next, DW_LIST_PAGE_SIZE)
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

suspend fun WorkshopRepository.visibleDesignWorkshops(
    search: String? = null,
    workshopKind: String? = null,
): DesignWorkshopListing =
    walkDesignWorkshopPages { page, pageSize ->
        designWorkshops(page = page, pageSize = pageSize, search = search, workshopKind = workshopKind)
    }
