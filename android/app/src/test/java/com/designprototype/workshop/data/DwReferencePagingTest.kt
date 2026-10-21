package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The craft picker held 50 of the 459 crafts and said "Partly loaded" while online (owner report,
 * 2026-10-21): it read one page of the references endpoint at the server's default size. These pin
 * the whole-list read, the honest "partly loaded" flag and the search merge.
 */
class DwReferencePagingTest {

    private val crafts = (1..459).map { DwReferenceOption(id = "c$it", label = "Craft $it") }

    /** The server: `limit` rows from `offset`, `truncated` while more remain. */
    private fun server(rows: List<DwReferenceOption>, calls: MutableList<Pair<Int, Int>>) =
        { offset: Int, limit: Int ->
            calls += offset to limit
            val page = rows.drop(offset).take(limit)
            DwReferenceResponseDto(options = page, truncated = offset + limit < rows.size)
        }

    @Test
    fun `all 459 crafts are read, in pages of 200, and the list is whole`() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val fetch = server(crafts, calls)
        val pages = dwReadAllReferencePages { o, l -> fetch(o, l) }
        assertEquals(459, pages.options.size)
        assertEquals(crafts.map { it.id }, pages.options.map { it.id })
        assertTrue(pages.complete)
        assertEquals(listOf(0 to 200, 200 to 200, 400 to 200), calls)
    }

    @Test
    fun `a short list is one request`() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val fetch = server(crafts.take(12), calls)
        val pages = dwReadAllReferencePages { o, l -> fetch(o, l) }
        assertEquals(12, pages.options.size)
        assertTrue(pages.complete)
        assertEquals(1, calls.size)
    }

    @Test
    fun `a server that ignores offset sends page one again and the list says it is partial`() = runBlocking {
        var calls = 0
        val pages = dwReadAllReferencePages { _, _ ->
            calls++
            DwReferenceResponseDto(options = crafts.take(200), truncated = true)
        }
        assertEquals(200, pages.options.size)
        assertFalse(pages.complete)
        assertEquals(2, calls)
    }

    @Test
    fun `the read stops at the cap and says it is partial`() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val big = (1..5000).map { DwReferenceOption(id = "a$it") }
        val fetch = server(big, calls)
        val pages = dwReadAllReferencePages { o, l -> fetch(o, l) }
        assertEquals(DW_REFERENCE_READ_CAP, pages.options.size)
        assertFalse(pages.complete)
    }

    @Test
    fun `a later page that fails keeps what was read, as partial`() = runBlocking {
        val pages = dwReadAllReferencePages { offset, _ ->
            if (offset > 0) error("no signal")
            DwReferenceResponseDto(options = crafts.take(200), truncated = true)
        }
        assertEquals(200, pages.options.size)
        assertFalse(pages.complete)
    }

    @Test
    fun `partly loaded only when the list is short`() {
        assertFalse(DwReferenceList(items = crafts, complete = true).partial)
        assertTrue(DwReferenceList(items = crafts.take(200), complete = false).partial)
        // A list cached before pages were read: one full page of 50 is the cut, fewer is the whole.
        assertTrue(DwReferenceList(items = crafts.take(50)).partial)
        assertFalse(DwReferenceList(items = crafts.take(7)).partial)
        assertNull(DwReferenceList().complete)
    }

    @Test
    fun `a server search adds what the list lacks and replaces nothing`() {
        val list = DwReferenceList(items = crafts.take(3), complete = false)
        val merged = dwMergeReferenceOptions(
            list,
            listOf(DwReferenceOption(id = "c2", label = "renamed"), DwReferenceOption(id = "c400", label = "Craft 400")),
        )
        assertEquals(listOf("c1", "c2", "c3", "c400"), merged.items.map { it.id })
        assertEquals("Craft 2", merged.items[1].label)
        assertSame(list, dwMergeReferenceOptions(list, crafts.take(2)))
    }

    @Test
    fun `every page of a list route is read`() = runBlocking {
        val rows = (1..1200).toList()
        val seen = mutableListOf<Int>()
        val all = readAllListPages { page ->
            seen += page
            val items = rows.drop((page - 1) * 500).take(500)
            PageResponse(items = items, total = rows.size, page = page, pageSize = 500, pages = 3)
        }
        assertEquals(rows, all)
        assertEquals(listOf(1, 2, 3), seen)
    }
}
