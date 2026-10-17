package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DesignWorkshopPageDto
import com.designprototype.workshop.ui.loadMoreLabel
import com.designprototype.workshop.ui.pagedCountLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DA-39: the Sketches & prototypes picker reaches EVERY workshop — server search plus "Load more" —
 * instead of twenty rows and a sentence about the rest.
 */
class DwWorkshopPagedListTest {

    private val all = (1..45).map { DesignWorkshopDto(id = "w$it", title = "Workshop $it") }

    /** A fake `GET /design-workshops`: title search, 20 per page. */
    private val calls = mutableListOf<Triple<Int, Int, String?>>()
    private val fetch: suspend (Int, Int, String?) -> DesignWorkshopPageDto = { page, size, search ->
        calls += Triple(page, size, search)
        val hits = all.filter { search == null || it.title.contains(search, ignoreCase = true) }
        DesignWorkshopPageDto(items = hits.drop((page - 1) * size).take(size), total = hits.size, page = page, pageSize = size)
    }

    private fun seeded(): DwWorkshopPagedList = DwWorkshopPagedList(20).also { it.seed(all.take(20), all.size) }

    @Test
    fun loadMoreAppendsUntilEveryWorkshopIsHere() = runBlocking {
        val list = seeded()
        assertTrue(list.hasMore)
        list.loadMore(this, fetch)
        list.awaitIdle()
        assertEquals(40, list.rows.size)
        list.loadMore(this, fetch)
        list.awaitIdle()
        assertEquals(45, list.rows.size)
        assertFalse(list.hasMore)
        val done = list.loadMoreAction(this, fetch)
        assertEquals("no footer once the last page is in", done.total, done.shown)
        assertFalse(done.failed)
        assertEquals(listOf(2, 3), calls.map { it.first })
    }

    @Test
    fun searchAsksTheServerAndReplacesTheRows() = runBlocking {
        val list = seeded()
        list.search(this, "  workshop 4 ", fetch)
        list.awaitIdle()
        assertEquals("trimmed term goes to the server", "workshop 4", calls.single().third)
        assertEquals(listOf("w4", "w40", "w41", "w42", "w43", "w44", "w45"), list.rows.map { it.id })
        assertFalse(list.hasMore)
    }

    @Test
    fun theChosenWorkshopKeepsItsNameWhileASearchShowsOthers() = runBlocking {
        val list = seeded()
        list.search(this, "Workshop 44", fetch)
        list.awaitIdle()
        val shown = list.rowsKeeping("w3", all.take(20))
        assertEquals("the first row is a real match", listOf("w44", "w3"), shown.map { it.id })
    }

    @Test
    fun aFailedPageIsOfferedAgainNotHidden() = runBlocking {
        val list = seeded()
        list.fetchPage(2, "") { _, _, _ -> error("offline") }
        assertTrue(list.failed)
        assertEquals("Couldn't load. Try again", loadMoreLabel(list.loadMoreAction(this, fetch)))
        assertEquals("the first page is kept", 20, list.rows.size)
    }

    @Test
    fun countLineSaysHowManyAreHere() {
        assertEquals("20 of 45 shown", pagedCountLine(20, 45))
        assertEquals("45 options", pagedCountLine(45, 45))
        assertEquals("1 option", pagedCountLine(1, 1))
        assertEquals("0 options", pagedCountLine(0, 0))
    }

    @Test
    fun theScreenNoLongerPointsElsewhereForTheRest() {
        val src = com.designprototype.workshop.ui.mainSource("ui/designworkshop/SketchesAndPrototypesScreen.kt")
        assertFalse(src.contains("more in Design workshops"))
        assertFalse(src.contains("not shown here"))
        assertTrue(src.contains("onSearch = { term -> picker.search("))
        assertTrue(src.contains("loadMore = picker.loadMoreAction("))
    }

    /** Waits for the list's in-flight page, polling the state the screen itself reads. */
    private suspend fun DwWorkshopPagedList.awaitIdle() {
        repeat(200) {
            kotlinx.coroutines.delay(10)
            if (!loading && calls.isNotEmpty()) return
        }
    }
}
