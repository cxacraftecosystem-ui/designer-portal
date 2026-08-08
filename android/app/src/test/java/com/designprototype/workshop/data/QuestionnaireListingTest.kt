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

/**
 * The questionnaire list must reach the row a viewer grant added, and that row is always the LAST one.
 *
 * ── WHAT THIS PINS, AND THE LIVE READING IT WAS WRITTEN FROM ───────────────────────────────────────
 *
 * `QuestionnaireListScreen` asked for one page of 50 and ignored `total`. Signed in against the
 * running API as designer@example.org, with one `DesignWorkshopViewer` grant on
 * cmsik2jg8000eh8xc1lcy661a, `GET /api/questionnaires?pageSize=100` answers `total: 3` ordered
 * `createdAt desc`:
 *
 *   [0] oracle-probe        2026-08-08T01:39:26  owned by designer@example.org
 *   [1] oracle-real         2026-08-08T01:39:14  owned by designer@example.org
 *   [2] Shape probe renamed 2026-08-07T13:49:54  owned by a THIRD account, attached to the granted
 *                                                workshop — the only row the grant adds, and LAST
 *
 * and `mineOnly=true` answers `total: 2` with that row gone, which is what proves the grant is what
 * put it there. The position is structural, not an accident of this database: a grant is issued
 * against a workshop that already exists, so the form hanging off it is older than anything the
 * grantee has built, and `createdAt desc` puts older last. The window is when it becomes visible.
 */
class QuestionnaireListingTest {

    private fun row(id: String, title: String = id) =
        CustomQuestionnaireSummaryDto(id = id, title = title)

    /** `total` rows, ids "q-0"… , served in pages exactly as the API does. */
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

    // ── The defect itself ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the granted questionnaire that sorts last is in the list`() {
        // A season's fieldwork: 120 of the designer's own forms, and the colleague's form the grant
        // added sorting dead last because it is the oldest. Before the walk existed the screen held
        // rows 0-49 and the designer was told their colleague's questionnaire did not exist while the
        // API was answering 200 for it.
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
        // The old screen, reproduced exactly: one request, pageSize 50, `.items`, `total` discarded.
        // Pinned so that a future "simplification" back to a single request fails here with its
        // consequence named rather than silently re-hiding the row.
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
        // Paging must not tax the ordinary case: a second request is sent only when the server has
        // already said there is a second page, so a designer with 12 forms on a metered rural
        // connection pays exactly what they paid before.
        var calls = 0
        val listing = drive { page, size -> calls++; server(total = 12)(page, size) }

        assertEquals(1, calls)
        assertEquals(12, listing.items.size)
        assertFalse(listing.truncated)
    }

    // ── The hazards of offset paging over a live table ───────────────────────────────────────────

    @Test
    fun `a row repeated across pages is rendered once`() {
        // A questionnaire uploaded between the two requests shifts the ordering down and page 2
        // re-sends page 1's last row. Two identical cards is worse than one missing row: a designer
        // cannot tell which of them holds the sitting they recorded this morning.
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
        // A questionnaire deactivated between the count and the fetch leaves `total` ahead of what the
        // server can serve. Without the empty-page stop the client re-asks for nothing until the cap.
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

    // ── The connection, which in a village is the ordinary case ──────────────────────────────────

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
        // The list screen's effect is keyed on the search box, so every keystroke cancels the walk in
        // flight. Caught like a network failure, the abandoned keystroke's rows would render and the
        // banner would claim a truncated list when nothing had gone wrong.
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
        // The screen sets `offline` from this throwing. A questionnaire lives only on the server, so a
        // swallowed first-page failure renders as "you have not built one yet" — which sends a designer
        // off to rebuild a form they already uploaded.
        var thrown: Throwable? = null
        try {
            drive { _, _ -> throw java.io.IOException("no route to host") }
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue("page 1 must propagate so the screen can say it is offline", thrown is java.io.IOException)
    }

    // ── Parity with the proven walk ──────────────────────────────────────────────────────────────

    @Test
    fun `the generic walk decides identically to the design-workshop walk it generalises`() {
        // WHY THIS EXISTS. `walkDesignWorkshopPages` is left alone rather than rewired onto
        // `walkPagedListing`, so for a while there are two implementations of one four-rule policy —
        // and two copies of a rule are a rule that drifts. Pinned BY VALUE over a shared case table
        // rather than by inspection: each case is driven through BOTH and the outcomes diffed, so a
        // change to either that alters a stop condition, the de-duplication or the truncation flag
        // fails here by name.
        //
        // The cases are the hazards themselves: an ordinary short list, an exact multiple of the page
        // size, an over-reported total, a duplicate across pages, a blank id, and a list past the cap.
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

    // ── Driving the suspend walkers from a plain JUnit test ──────────────────────────────────────

    /**
     * Runs [walkPagedListing] to completion on this thread.
     *
     * `kotlin.coroutines.startCoroutine` from the STDLIB rather than `runBlocking`, so this test adds
     * no `kotlinx-coroutines-test` dependency to `app/build.gradle.kts` — a shared build file other
     * agents are working around. Every `fetch` below is synchronous, so the walker never actually
     * suspends; [checkNotNull] turns a future test that DID suspend into a named failure rather than a
     * mysterious null.
     */
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

    /** The same, for the walk this one generalises, so the parity case table can drive both. */
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
