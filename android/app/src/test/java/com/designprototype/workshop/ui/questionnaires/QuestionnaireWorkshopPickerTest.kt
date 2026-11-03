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

class QuestionnaireWorkshopPickerTest {

    private fun row(id: String, title: String = id, craft: String? = null) =
        DesignWorkshopDto(id = id, title = title, craftName = craft)

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

    private fun withGrantAtTheEnd(): List<DesignWorkshopDto> =
        (0 until 120).map { row("own-$it") } +
            row("cmsik2jg8000eh8xc1lcy661a", "Ikat cluster, Nuapatna", craft = "Ikat")

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
        val option = drive(server(withGrantAtTheEnd())).first { it.value == "cmsik2jg8000eh8xc1lcy661a" }

        assertEquals("Ikat cluster, Nuapatna", option.label)
        assertEquals("Ikat", option.hint)
    }

    @Test
    fun `a designer under the ceiling still costs exactly one request`() {
        var calls = 0
        val rows = (0 until 40).map { row("own-$it") }
        val options = drive { page, size -> calls++; server(rows)(page, size) }

        assertEquals(40, options.size)
        assertEquals("one page of results must not cost a second request", 1, calls)
    }

    @Test
    fun `a workshop with no title is still selectable`() {
        val options = drive(server(listOf(row("w-1", title = ""))))

        assertEquals(1, options.size)
        assertEquals("Workshop", options.first().label)
        assertEquals("w-1", options.first().value)
    }

    private fun drive(
        fetch: suspend (Int, Int) -> DesignWorkshopPageDto
    ): List<com.designprototype.workshop.ui.SelectOption> = walk(fetch).options()

    private fun walk(fetch: suspend (Int, Int) -> DesignWorkshopPageDto): AttachableWorkshops {
        var result: AttachableWorkshops? = null
        var failure: Throwable? = null
        val block: suspend () -> AttachableWorkshops = { walkAttachableDesignWorkshops(fetch) }
        block.startCoroutine(object : Continuation<AttachableWorkshops> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(outcome: Result<AttachableWorkshops>) {
                result = outcome.getOrNull()
                failure = outcome.exceptionOrNull()
            }
        })
        failure?.let { throw it }
        return requireNotNull(result) { "the fake server suspended, which it must never do" }
    }
}
