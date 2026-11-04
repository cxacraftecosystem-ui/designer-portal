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
    private val known = LinkedHashMap<String, DesignWorkshopDto>()

    val hasMore: Boolean get() = rows.size < total

    fun reset() {
        job?.cancel()
        rows = emptyList(); total = 0; term = ""; loading = false; failed = false; page = 1
    }

    fun seed(items: List<DesignWorkshopDto>, total: Int) {
        items.forEach { known[it.id] = it }
        if (term.isNotBlank()) return
        rows = items; this.total = total; page = 1
    }

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

    fun rowsKeeping(chosenId: String, firstPage: List<DesignWorkshopDto>): List<DesignWorkshopDto> {
        val base = if (rows.isEmpty() && term.isBlank() && !failed) firstPage else rows
        if (chosenId.isBlank() || base.any { it.id == chosenId }) return base
        val chosen = known[chosenId] ?: firstPage.firstOrNull { it.id == chosenId } ?: return base
        return base + chosen
    }

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
