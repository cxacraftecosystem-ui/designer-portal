package com.designprototype.workshop.ui

import com.designprototype.workshop.data.DW_LIST_MAX_PAGES
import com.designprototype.workshop.data.DW_LIST_PAGE_SIZE
import com.designprototype.workshop.data.PageResponse
import com.designprototype.workshop.data.readEveryPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ListReachTest {

    private fun server(total: Int, pageSize: Int, failFrom: Int = Int.MAX_VALUE): suspend (Int) -> PageResponse<String> = { page ->
        if (page >= failFrom) throw IOException("offline")
        val from = (page - 1) * pageSize
        PageResponse(
            items = (from until minOf(from + pageSize, total)).map { "r${it + 1}" },
            total = total,
            page = page,
            pageSize = pageSize,
            pages = (total + pageSize - 1) / pageSize,
        )
    }

    @Test
    fun `the server has more only while the pages read fall short of the total`() {
        assertTrue(serverHasMore(pagesRead = 1, pageSize = 100, total = 231))
        assertTrue(serverHasMore(pagesRead = 2, pageSize = 100, total = 231))
        assertFalse(serverHasMore(pagesRead = 3, pageSize = 100, total = 231))
        assertFalse(serverHasMore(pagesRead = 1, pageSize = 100, total = 100))
        assertFalse(serverHasMore(pagesRead = 1, pageSize = 0, total = 5))
    }

    @Test
    fun `revealing then fetching reaches the last of 231 records`() = runBlocking {
        val fetch = server(total = 231, pageSize = 100)
        val first = fetch(1)
        var rows = first.items
        var pages = 1
        var shown = 12
        var presses = 0
        while (true) {
            val reach = ListReach(shown = shown.coerceAtMost(rows.size), loaded = rows.size, serverMore = serverHasMore(pages, 100, first.total))
            if (!reach.more) break
            if (reach.needsFetch()) {
                rows = (rows + fetch(pages + 1).items).distinct()
                pages += 1
            }
            shown = reach.revealed(rows.size)
            presses++
            assertTrue("never loops forever", presses < 100)
        }
        assertEquals(231, rows.size)
        assertEquals(231, shown)
        assertEquals("r231", rows.take(shown).last())
    }

    @Test
    fun `the next button names how many it will add`() {
        assertEquals(20, ListReach(shown = 12, loaded = 100, serverMore = true).nextCount(231))
        assertEquals(11, ListReach(shown = 220, loaded = 231, serverMore = false).nextCount(231))
        assertFalse(ListReach(shown = 231, loaded = 231, serverMore = false).more)
    }

    @Test
    fun `reading every page returns the whole list`() = runBlocking {
        val all = readEveryPage(fetch = server(total = 431, pageSize = 100))
        assertEquals(431, all.items.size)
        assertEquals(431, all.total)
        assertEquals("r431", all.items.last())
    }

    @Test
    fun `a later page that fails keeps what was read and the real total`() = runBlocking {
        val partial = readEveryPage(fetch = server(total = 431, pageSize = 100, failFrom = 3))
        assertEquals(200, partial.items.size)
        assertEquals(431, partial.total)
        assertEquals(
            "Showing 200 of 431. The rest didn't load. Open this again to retry.",
            workshopCapLine(partial.items.size, partial.total, WorkshopListKind.FIELD),
        )
    }

    @Test
    fun `the workshop walks read far past the old 500 cap`() {
        assertTrue(DW_LIST_MAX_PAGES * DW_LIST_PAGE_SIZE >= 20_000)
    }

    @Test
    fun `the usage trail offers more while a page comes back full`() {
        assertTrue(usageTrailHasMore(50, 50))
        assertFalse(usageTrailHasMore(49, 50))
        assertFalse(usageTrailHasMore(0, 50))
    }

    @Test
    fun `the copy is short and names the counts`() {
        assertEquals("Showing 12 of 231", ListReachText.showing(12, 231))
        assertEquals("Show 20 more", ListReachText.showMore(20))
        assertEquals("Load all 431", ListReachText.loadAll(431))
    }
}
