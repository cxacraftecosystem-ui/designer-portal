package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.DW_LIST_PAGE_SIZE
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DesignWorkshopPageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * A questionnaire must be attachable to a GRANTED workshop, which lives past the first page.
 *
 * ── THE LIVE READING THIS IS PINNED TO ─────────────────────────────────────────────────────────────
 *
 * Not invented numbers. Against the running API, with an admin having put designer@example.org on
 * cmsik2jg8000eh8xc1lcy661a via `PUT /design-workshops/{id}/viewers`:
 *
 *   GET /api/design-workshops?pageSize=100&page=1 -> total 121, 100 items, granted workshop ABSENT
 *   GET /api/design-workshops?pageSize=100&page=2 ->            21 items, granted workshop at index 20
 *   GET /api/design-workshops/cmsik2jg8000eh8xc1lcy661a          -> 200 (404 before the grant)
 *   POST /api/questionnaires {designWorkshopId: cmsik2jg8000eh8xc1lcy661a} -> 201
 *
 * So the server says yes to the attachment and the picker never offered the row. A grant lands in that
 * position by construction rather than by luck: rows come back `createdAt desc`, and a grant is issued
 * against a workshop that already existed, so it is older than everything the grantee started.
 */
class QuestionnaireWorkshopPickerTest {

    private fun row(id: String, title: String = id, craft: String? = null) =
        DesignWorkshopDto(id = id, title = title, craftName = craft)

    /**
     * The API's own paging, including the part that caused this: a request for more than
     * [DW_LIST_PAGE_SIZE] is CLAMPED rather than refused, so a client cannot buy its way out with a
     * bigger page. Whatever size is asked for, this serves at most a hundred.
     */
    private fun server(
        rows: List<DesignWorkshopDto>,
        pageSize: Int = DW_LIST_PAGE_SIZE,
    ): suspend (Int, Int) -> DesignWorkshopPageDto = { page, requested ->
        val served = minOf(requested, pageSize)
        val from = (page - 1) * served
        DesignWorkshopPageDto(
            items = rows.subList(minOf(from, rows.size), minOf(from + served, rows.size)),
            total = rows.size,
            page = page,
            pageSize = served,
            pages = (rows.size + served - 1) / served,
        )
    }

    /** The live shape: 120 of the designer's own, then the granted one last. */
    private fun withGrantAtTheEnd(): List<DesignWorkshopDto> =
        (0 until 120).map { row("own-$it") } +
            row("cmsik2jg8000eh8xc1lcy661a", "Ikat cluster, Nuapatna", craft = "Ikat")

    // ── The defect itself ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the granted workshop past page one is offered as an attachment target`() {
        val options = drive(server(withGrantAtTheEnd()))

        assertEquals("every workshop the account may open must be offerable", 121, options.size)
        assertTrue(
            "row 121 — where a viewer grant lands in a createdAt-desc ordering — must be offered; " +
                "the server answers 201 for this attachment, so a picker that omits it is a dead end " +
                "with no error in it",
            options.any { it.value == "cmsik2jg8000eh8xc1lcy661a" }
        )
    }

    @Test
    fun `a granted workshop is recognisable by craft and cluster, not just its title`() {
        // A granted workshop is somebody else's fieldwork under a title the reader did not choose.
        val option = drive(server(withGrantAtTheEnd())).first { it.value == "cmsik2jg8000eh8xc1lcy661a" }

        assertEquals("Ikat cluster, Nuapatna", option.label)
        assertEquals("Ikat", option.hint)
    }

    @Test
    fun `a designer under the ceiling still costs exactly one request`() {
        // The other half of the bargain: paging must not tax the ordinary case. A second request goes
        // out only when the server has already said there is a second page, so the handful-of-workshops
        // designer on a metered rural connection pays exactly what they paid before this change.
        var calls = 0
        val rows = (0 until 40).map { row("own-$it") }
        val options = drive { page, size -> calls++; server(rows)(page, size) }

        assertEquals(40, options.size)
        assertEquals("one page of results must not cost a second request", 1, calls)
    }

    @Test
    fun `a workshop with no title is still selectable`() {
        // A blank label renders as an unpickable-looking empty row; the id behind it is what the
        // attachment needs, so it must stay offerable rather than be filtered out.
        val options = drive(server(listOf(row("w-1", title = ""))))

        assertEquals(1, options.size)
        assertEquals("Untitled workshop", options.first().label)
        assertEquals("w-1", options.first().value)
    }

    /** Runs the suspend function to completion on this thread; the fake server never suspends. */
    private fun drive(fetch: suspend (Int, Int) -> DesignWorkshopPageDto): List<com.designprototype.workshop.ui.SelectOption> {
        var result: List<com.designprototype.workshop.ui.SelectOption>? = null
        var failure: Throwable? = null
        val block: suspend () -> List<com.designprototype.workshop.ui.SelectOption> = {
            designWorkshopOptionsAcrossPages(fetch)
        }
        block.startCoroutine(object : Continuation<List<com.designprototype.workshop.ui.SelectOption>> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(outcome: Result<List<com.designprototype.workshop.ui.SelectOption>>) {
                result = outcome.getOrNull()
                failure = outcome.exceptionOrNull()
            }
        })
        failure?.let { throw it }
        return requireNotNull(result) { "the fake server suspended, which it must never do" }
    }
}
