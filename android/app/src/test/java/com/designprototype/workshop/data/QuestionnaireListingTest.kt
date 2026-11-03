package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class QuestionnaireListingTest {

    private fun row(id: String, title: String = id) =
        CustomQuestionnaireSummaryDto(id = id, title = title)

    private fun server(
        total: Int,
        pageSize: Int = DW_LIST_PAGE_SIZE,
    ): (Int, Int) -> PageResponse<CustomQuestionnaireSummaryDto> = { page, _ ->
        val from = (page - 1) * pageSize
        val slice = (from until minOf(from + pageSize, total)).map { row("q-$it") }
        PageResponse(
            items = slice,
            total = total,
            page = page,
            pageSize = pageSize,
            pages = (total + pageSize - 1) / pageSize,
        )
    }

    @Test
    fun `the granted questionnaire that sorts last is in the list`() {
        val listing = drive(server(total = 121))

        assertEquals(121, listing.items.size)
        assertEquals(121, listing.total)
        assertFalse("a fully-read list must not claim to be a prefix", listing.truncated)
        assertNotNull(
            "the last row — where a viewer grant lands in a createdAt-desc ordering — must be present",
            listing.items.firstOrNull { it.id == "q-120" }
        )
    }

    @Test
    fun `one page of fifty cannot reach it — the behaviour this replaces`() {
        val onePage = server(total = 121, pageSize = 50)(1, 50)

        assertEquals(50, onePage.items.size)
        assertEquals(121, onePage.total)
        assertFalse(
            "the granted row is the 121st; a 50-row window is why the grant looked broken",
            onePage.items.any { it.id == "q-120" }
        )
    }

    @Test
    fun `a designer under the ceiling still costs exactly one request`() {
        var calls = 0
        val listing = drive { page, size -> calls++; server(total = 12)(page, size) }

        assertEquals(1, calls)
        assertEquals(12, listing.items.size)
        assertFalse(listing.truncated)
    }

    @Test
    fun `a row repeated across pages is rendered once`() {
        val pages = listOf(
            PageResponse(listOf(row("a"), row("b")), total = 3, page = 1, pageSize = 2, pages = 2),
            PageResponse(listOf(row("b"), row("c")), total = 3, page = 2, pageSize = 2, pages = 2),
        )
        val listing = drive { page, _ -> pages[page - 1] }

        assertEquals(listOf("a", "b", "c"), listing.items.map { it.id })
        assertFalse(listing.truncated)
    }

    @Test
    fun `an over-reported total ends the walk at the first empty page`() {
        var calls = 0
        val listing = drive { page, _ ->
            val items = if (page == 1) listOf(row("a"), row("b")) else emptyList()
            calls++
            PageResponse(items, total = 9, page = page, pageSize = 2, pages = 5)
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
            PageResponse(
                listOf(row("a"), row(""), row("")),
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

        assertEquals(DW_LIST_PAGE_SIZE, listing.items.size)
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

    @Test
    fun `the generic walk decides identically to the design-workshop walk it generalises`() {
        val cases = listOf(
            "short list" to listOf(Triple(listOf("a", "b"), 2, 1)),
            "exact multiple" to listOf(Triple((0 until 100).map { "r$it" }, 100, 1)),
            "over-reported total" to listOf(
                Triple(listOf("a", "b"), 9, 1),
                Triple(emptyList(), 9, 2),
            ),
            "duplicate across pages" to listOf(
                Triple(listOf("a", "b"), 3, 1),
                Triple(listOf("b", "c"), 3, 2),
            ),
            "blank ids" to listOf(Triple(listOf("a", "", ""), 3, 1)),
            "past the cap" to (1..DW_LIST_MAX_PAGES + 2).map { p ->
                Triple((0 until DW_LIST_PAGE_SIZE).map { "p$p-$it" }, 4000, p)
            },
        )

        val differences = mutableListOf<String>()
        cases.forEach { (name, pages) ->
            var dwCalls = 0
            val dw = driveDesignWorkshops { page, _ ->
                dwCalls++
                val (ids, total, _) = pages[minOf(page, pages.size) - 1]
                DesignWorkshopPageDto(
                    items = ids.map { DesignWorkshopDto(id = it) },
                    total = total, page = page, pageSize = DW_LIST_PAGE_SIZE,
                    pages = pages.size,
                )
            }
            var qCalls = 0
            val q = drive { page, _ ->
                qCalls++
                val (ids, total, _) = pages[minOf(page, pages.size) - 1]
                PageResponse(
                    items = ids.map { row(it) },
                    total = total, page = page, pageSize = DW_LIST_PAGE_SIZE,
                    pages = pages.size,
                )
            }

            val dwShape = Triple(dw.items.map { it.id }, dw.total, dw.truncated)
            val qShape = Triple(q.items.map { it.id }, q.total, q.truncated)
            if (dwShape != qShape) differences += "$name: design-workshop=$dwShape questionnaire=$qShape"
            if (dwCalls != qCalls) differences += "$name: requests design-workshop=$dwCalls questionnaire=$qCalls"
        }

        assertEquals(
            "the two walks must agree on every case; each line is one policy that has drifted",
            emptyList<String>(),
            differences
        )
    }

    private fun drive(
        fetch: (Int, Int) -> PageResponse<CustomQuestionnaireSummaryDto>,
    ): PagedListing<CustomQuestionnaireSummaryDto> {
        var outcome: Result<PagedListing<CustomQuestionnaireSummaryDto>>? = null
        val block: suspend () -> PagedListing<CustomQuestionnaireSummaryDto> = {
            walkPagedListing(idOf = { it.id }) { page, pageSize -> fetch(page, pageSize) }
        }
        block.startCoroutine(
            object : Continuation<PagedListing<CustomQuestionnaireSummaryDto>> {
                override val context: CoroutineContext = EmptyCoroutineContext
                override fun resumeWith(result: Result<PagedListing<CustomQuestionnaireSummaryDto>>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome) { "the walk suspended; every fetch in this test is synchronous" }
            .getOrThrow()
    }

    private fun driveDesignWorkshops(
        fetch: (Int, Int) -> DesignWorkshopPageDto,
    ): DesignWorkshopListing {
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
