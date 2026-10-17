package com.designprototype.workshop.ui.designworkshop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DesignWorkshopPageDto
import com.designprototype.workshop.ui.SelectLoadMore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A workshop picker's list, searched and paged BY THE SERVER (UX audit DA-39).
 *
 * The Sketches & prototypes picker used to hold the first twenty workshops and print "N more in
 * Design workshops" under them, with no way to reach the rest from where the designer stood. This
 * holds whatever the box last asked `GET /design-workshops?search=` for, and appends each "Load more"
 * page, so every workshop the account can see is reachable from the picker itself.
 *
 * The fetch is a parameter rather than the repository, so the paging rules are unit-tested on the
 * JVM without a server.
 */
@Stable
internal class DwWorkshopPagedList(private val pageSize: Int) {
    var rows by mutableStateOf<List<DesignWorkshopDto>>(emptyList())
        private set
    var total by mutableIntStateOf(0)
        private set
    var term by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var page = 1
    private var job: Job? = null
    /** Every workshop seen, so the chosen one keeps its name while a search shows others. */
    private val known = LinkedHashMap<String, DesignWorkshopDto>()

    val hasMore: Boolean get() = rows.size < total

    fun reset() {
        job?.cancel()
        rows = emptyList(); total = 0; term = ""; loading = false; failed = false; page = 1
    }

    /** The screen's first page, which the picker starts from when nothing has been typed. */
    fun seed(items: List<DesignWorkshopDto>, total: Int) {
        items.forEach { known[it.id] = it }
        if (term.isNotBlank()) return
        rows = items; this.total = total; page = 1
    }

    /** Ask the server for [typed], 300ms after the last keystroke. A blank term is page one again. */
    fun search(
        scope: CoroutineScope,
        typed: String,
        fetch: suspend (page: Int, pageSize: Int, search: String?) -> DesignWorkshopPageDto
    ) {
        val next = typed.trim()
        if (next == term && (rows.isNotEmpty() || loading)) return
        term = next
        job?.cancel()
        job = scope.launch {
            if (next.isNotEmpty()) delay(SEARCH_DEBOUNCE_MS)
            fetchPage(1, next, fetch)
        }
    }

    /** The next page of the current term, appended. A no-op while a page is in flight. */
    fun loadMore(
        scope: CoroutineScope,
        fetch: suspend (page: Int, pageSize: Int, search: String?) -> DesignWorkshopPageDto
    ) {
        if (loading) return
        val nextPage = if (failed && rows.isEmpty()) 1 else page + 1
        job = scope.launch { fetchPage(nextPage, term, fetch) }
    }

    internal suspend fun fetchPage(
        pageNumber: Int,
        search: String,
        fetch: suspend (page: Int, pageSize: Int, search: String?) -> DesignWorkshopPageDto
    ) {
        loading = true
        failed = false
        try {
            val answer = fetch(pageNumber, pageSize, search.ifBlank { null })
            answer.items.forEach { known[it.id] = it }
            rows = if (pageNumber == 1) answer.items else (rows + answer.items).distinctBy { it.id }
            total = answer.total
            page = pageNumber
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
        loading = false
    }

    /**
     * The rows to offer: the server's answer (or [firstPage] before anything is typed), with the
     * chosen workshop LAST when the answer does not contain it, so the field never loses its name and
     * the first row (what the keyboard's search key picks) is always a real match.
     */
    fun rowsKeeping(chosenId: String, firstPage: List<DesignWorkshopDto>): List<DesignWorkshopDto> {
        val base = if (rows.isEmpty() && term.isBlank() && !failed) firstPage else rows
        if (chosenId.isBlank() || base.any { it.id == chosenId }) return base
        val chosen = known[chosenId] ?: firstPage.firstOrNull { it.id == chosenId } ?: return base
        return base + chosen
    }

    /**
     * The picker's paging state: the "20 of 45 shown" count, and the "Load more" footer the sheet
     * draws while rows remain or the last page failed.
     */
    fun loadMoreAction(
        scope: CoroutineScope,
        fetch: suspend (page: Int, pageSize: Int, search: String?) -> DesignWorkshopPageDto
    ): SelectLoadMore =
        SelectLoadMore(
            shown = rows.size,
            total = total,
            loading = loading,
            failed = failed,
            onClick = { loadMore(scope, fetch) }
        )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
