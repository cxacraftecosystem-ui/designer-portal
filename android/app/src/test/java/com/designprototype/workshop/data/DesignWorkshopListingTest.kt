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

/**
 * The design-workshop list must reach the SECOND page, because that is where a granted workshop is.
 *
 * ── WHAT THIS PINS, AND THE LIVE READING IT WAS WRITTEN FROM ───────────────────────────────────────
 *
 * `WorkshopListScreen` asked the server for one page of 100 and ignored `total`. The numbers below are
 * not invented: signed in against the running API as designer@example.org, with exactly one
 * `DesignWorkshopViewer` grant on cmsik2jg8000eh8xc1lcy661a,
 *
 *   GET /api/design-workshops?pageSize=100&page=1  ->  total 120, 100 items, granted workshop ABSENT
 *   GET /api/design-workshops?pageSize=100&page=2  ->  20 items,  granted workshop at index 19
 *
 * and asking for `pageSize=500` answers `pageSize: 100` — `normalize_pagination` clamps at
 * `MAX_PAGE_SIZE = 100` without saying so, so a bigger request is not a fix. The 119th row is where a
 * grant lands by construction: rows come back `createdAt desc` and a grant is issued against a
 * workshop that already existed, so it is older than everything the grantee started themselves.
 *
 * Ordering the walk's own hazards, all of which are real on an offset-paged live table:
 *  - a workshop created mid-walk shifts every row down and page 2 repeats page 1's last row,
 *  - a workshop soft-deleted mid-walk shifts rows up and one row is served on no page at all,
 *  - a server can report a `total` it cannot serve,
 *  - the connection can die between pages, which in a village is the ordinary case rather than the
 *    exceptional one.
 */
class DesignWorkshopListingTest {

    private fun row(id: String, title: String = id) = DesignWorkshopDto(id = id, title = title)

    /** `total` rows, ids "w-0"… , served in pages of [DW_LIST_PAGE_SIZE] exactly as the API does. */
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

    // ── The defect itself ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the granted workshop on page two is in the list`() {
        // The live reading, reproduced: 120 visible workshops, the granted one 119th. Before the walk
        // existed the screen held rows 0-99 and the designer was told their colleague's workshop did
        // not exist while the API was answering 200 for it.
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
        // The other half of the bargain. Paging must not tax the ordinary case: a second request is
        // sent only when the server has already said there is a second page, so a designer with 40
        // workshops on a metered rural connection pays exactly what they paid before.
        var calls = 0
        val listing = drive { page, size -> calls++; server(total = 40)(page, size) }

        assertEquals(1, calls)
        assertEquals(40, listing.items.size)
        assertFalse(listing.truncated)
    }

    @Test
    fun `an exact multiple of the page size does not spend a request on an empty page`() {
        // 100 rows: `ordered.size >= total` has to end the walk, or the client asks for page 2 and is
        // handed an empty body — a whole request of a field data allowance to learn nothing.
        var calls = 0
        val listing = drive { page, size -> calls++; server(total = 100)(page, size) }

        assertEquals(1, calls)
        assertEquals(100, listing.items.size)
        assertFalse(listing.truncated)
    }

    // ── The hazards of offset paging over a live table ───────────────────────────────────────────

    @Test
    fun `a row repeated across pages is rendered once`() {
        // A workshop created between the two requests shifts the ordering down and page 2 re-sends
        // page 1's last row. Two identical cards is worse than one missing row: the list's job is to
        // say what is and is not on this phone, and only one of the duplicates would carry the local
        // draft, so the same workshop would show two different sync states side by side.
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
        // A row soft-deleted between the count and the fetch leaves `total` ahead of what the server
        // can serve. Without the empty-page stop the client re-asks for nothing until the cap — four
        // wasted requests — and worse, would never terminate at all if the cap were ever removed.
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
        // 5 pages of 100. The cap is what makes this terminate on any server at all; `truncated` is
        // what stops it repeating the original defect — showing a prefix and letting the designer
        // infer from a missing workshop that the grant did not work.
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

    // ── The connection, which in a village is the ordinary case ──────────────────────────────────

    @Test
    fun `a connection that dies after page one keeps page one`() {
        // Edge first. Throwing away 100 rows that did arrive would make a connection that dropped
        // halfway indistinguishable from no connection at all — and the screen's offline fallback
        // would then hide the server rows it had already been handed.
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
        // The list screen's effect is keyed on the search box, so every keystroke cancels the walk in
        // flight. Written with `runCatching` — which catches Throwable — a cancelled walk came back as
        // a successful PARTIAL listing: the abandoned keystroke's rows rendered, and the banner told
        // the designer their list was truncated when nothing had gone wrong.
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
        // The screen sets `offline` from this throwing. Swallowing it would show an empty server half
        // with no explanation, and the designer's own drafts would look like the whole truth.
        var thrown: Throwable? = null
        try {
            drive { _, _ -> throw java.io.IOException("no route to host") }
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue("page 1 must propagate so the screen can say it is offline", thrown is java.io.IOException)
    }

    // ── Driving the suspend walker from a plain JUnit test ───────────────────────────────────────

    /**
     * Runs [walkDesignWorkshopPages] to completion on this thread.
     *
     * `kotlin.coroutines.startCoroutine` from the STDLIB rather than `runBlocking`, so this test adds
     * no `kotlinx-coroutines-test` dependency to `app/build.gradle.kts` — a shared build file three
     * other agents are working around. Every `fetch` below is synchronous, so the walker never
     * actually suspends and the continuation has completed by the time `startCoroutine` returns;
     * [checkNotNull] is what turns a future test that DID suspend into a named failure rather than a
     * mysterious null.
     */
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
