package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back from a record, search comes back as it was left — the workshop scope included (2026-10-17). */
class SearchMemoryTest {

    private fun leftOn(focus: String?): SearchMemory = SearchMemory().apply {
        this.focus = focus
        filters = SearchFilters(types = setOfNotNull(focus))
        applied = SearchFilters(types = setOfNotNull(focus))
        page = 3
        workshopIds = setOf("w-2", "w-1")
    }

    @Test
    fun the_workshop_scope_comes_back_with_the_filters_and_the_page() {
        val recalled = leftOn("artisan").recallFor("artisan")!!
        assertEquals(setOf("w-1", "w-2"), recalled.workshopIds)
        assertEquals(3, recalled.page)
    }

    @Test
    fun another_way_in_does_not_inherit_the_scope() {
        assertNull(leftOn("artisan").recallFor("tool"))
    }

    @Test
    fun nothing_is_recalled_before_a_search_has_run() {
        val memory = SearchMemory().apply { workshopIds = setOf("w-1") }
        assertNull(memory.recallFor(null))
    }

    @Test
    fun a_fresh_way_in_forgets_the_scope_too() {
        val memory = leftOn(null)
        assertSame(memory, memory.recallFor(null))
        memory.clear()
        assertTrue(memory.workshopIds.isEmpty())
        assertNull(memory.recallFor(null))
    }
}
