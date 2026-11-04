package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class DesignWorkshopListingTest {

    private fun row(id: String, title: String = id) = DesignWorkshopDto(id = id, title = title)

    private fun server(total: Int, pageSize: Int = DW_LIST_PAGE_SIZE): (Int, Int) -> DesignWorkshopPageDto =
        { page, _ ->
            val from = (page - 1) * pageSize
            val slice = (from until minOf(from + pageSize, total)).map { row("w-$it") }
            DesignWorkshopPageDto(
                items = slice,
                total = total,
                page = page,
                pageSize = pageSize,
                pages = (total + pageSize - 1) / pageSize,
            )
        }

    @Test
    fun `the granted workshop on page two is in the list`() {
        val listing = drive(server(total = 120))

        assertEquals(120, listing.items.size)
        assertEquals(120, listing.total)
        assertFalse("a fully-read list must not claim to be a prefix", listing.truncated)
        assertTrue(
            "the 119th row — where a viewer grant lands in a createdAt-desc ordering — must be present",
            listing.items.any { it.id == "w-118" }
        )
    }

    @Test
    fun `a designer under the ceiling still costs exactly one request`() {
        var calls = 0
        val listing = drive { page, size -> calls++; server(total = 40)(page, size) }

        assertEquals(1, calls)
        assertEquals(40, listing.items.size)
        assertFalse(listing.truncated)
    }

    @Test
    fun `an exact multiple of the page size does not spend a request on an empty page`() {
        var calls = 0
        val listing = drive { page, size -> calls++; server(total = 100)(page, size) }

        assertEquals(1, calls)
        assertEquals(100, listing.items.size)
        assertFalse(listing.truncated)
    }

    @Test
    fun `a row repeated across pages is rendered once`() {
        val pages = listOf(
            DesignWorkshopPageDto(items = listOf(row("a"), row("b")), total = 3, page = 1, pageSize = 2, pages = 2),
            DesignWorkshopPageDto(items = listOf(row("b"), row("c")), total = 3, page = 2, pageSize = 2, pages = 2),
        )
        val listing = drive { page, _ -> pages[page - 1] }

        assertEquals(listOf("a", "b", "c"), listing.items.map { it.id })
        assertFalse(listing.truncated)
    }

    @Test
    fun `an over-reported total ends the walk at the first empty page`() {
        var calls = 0
        val listing = drive { page, _ ->
            calls++
            val items = if (page == 1) listOf(row("a"), row("b")) else emptyList()
            DesignWorkshopPageDto(items = items, total = 9, page = page, pageSize = 2, pages = 5)
        }

        assertEquals(2, calls)
        assertEquals(2, listing.items.size)
        assertTrue("a list that came back short of the server's own count must say so", listing.truncated)
    }

    @Test
    fun `the walk is capped and admits the list is a prefix`() {
        var calls = 0
        val listing = drive { page, size -> calls++; server(total = 4000)(page, size) }

        assertEquals(DW_LIST_MAX_PAGES, calls)
        assertEquals(DW_LIST_MAX_PAGES * DW_LIST_PAGE_SIZE, listing.items.size)
        assertEquals(4000, listing.total)
        assertTrue(listing.truncated)
    }

    @Test
    fun `a blank id is dropped rather than collapsing into a phantom row`() {
        val listing = drive { _, _ ->
            DesignWorkshopPageDto(
                items = listOf(row("a"), row(""), row("")),
                total = 3, page = 1, pageSize = 100, pages = 1,
            )
        }
        assertEquals(listOf("a"), listing.items.map { it.id })
    }

    @Test
    fun `a connection that dies after page one keeps page one`() {
        val listing = drive { page, size ->
            if (page > 1) throw java.io.IOException("connection reset")
            server(total = 250)(page, size)
        }

        assertEquals(100, listing.items.size)
        assertEquals(250, listing.total)
        assertTrue(listing.truncated)
    }

    @Test
    fun `cancellation is not mistaken for a dropped connection`() {
        var thrown: Throwable? = null
        try {
            drive { page, size ->
                if (page > 1) throw CancellationException("newer search")
                server(total = 250)(page, size)
            }
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue(
            "cancellation must propagate, not be reported as a truncated list",
            thrown is CancellationException
        )
    }

    @Test
    fun `a failure on the very first page is not swallowed`() {
        var thrown: Throwable? = null
        try {
            drive { _, _ -> throw java.io.IOException("no route to host") }
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue("page 1 must propagate so the screen can say it is offline", thrown is java.io.IOException)
    }

    private fun drive(fetch: (Int, Int) -> DesignWorkshopPageDto): DesignWorkshopListing {
        var outcome: Result<DesignWorkshopListing>? = null
        val block: suspend () -> DesignWorkshopListing = {
            walkDesignWorkshopPages { page, pageSize -> fetch(page, pageSize) }
        }
        block.startCoroutine(
            object : Continuation<DesignWorkshopListing> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<DesignWorkshopListing>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome) { "the walk suspended; every fetch in this test is synchronous" }
            .getOrThrow()
    }
}
