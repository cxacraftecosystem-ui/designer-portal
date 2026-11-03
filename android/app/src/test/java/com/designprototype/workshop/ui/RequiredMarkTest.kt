package com.designprototype.workshop.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredMarkTest {

    private val mark = Color(0xFFDC2626)

    private fun redSpanOf(label: String): Pair<String, Int>? {
        val built = dwRequiredMarked(label, mark)
        val styled = built.spanStyles.filter { it.item.color == mark }
        if (styled.isEmpty()) return null
        assertEquals("exactly one styled range is expected", 1, styled.size)
        val range = styled.single()
        return built.text.substring(range.start, range.end) to range.start
    }

    @Test
    fun `the mark is styled and the words are not`() {
        val (styled, start) = redSpanOf("Craft *")!!
        assertEquals(DW_REQUIRED_MARK, styled)
        assertEquals("Craft".length, start)
        assertEquals("Craft *", dwRequiredMarked("Craft *", mark).text)
    }

    @Test
    fun `a label with no mark is left entirely alone`() {
        assertEquals(null, redSpanOf("Local name"))
        assertEquals("Local name", dwRequiredMarked("Local name", mark).text)
    }

    @Test
    fun `an asterisk that is not trailing is not treated as the mark`() {
        assertEquals(null, redSpanOf("Rating * out of 5"))
        assertEquals(null, redSpanOf("Grade A*"))
    }

    @Test
    fun `the count is appended after the mark, not before it`() {
        assertEquals(null, redSpanOf("Crafts * (3 selected)"))
        val (styled, start) = redSpanOf("Crafts *")!!
        assertEquals(DW_REQUIRED_MARK, styled)
        assertEquals("Crafts".length, start)
    }

    @Test
    fun `the mark is strippable for prose, and stripping is a no-op without one`() {
        assertEquals("Photographs", dwWithoutRequiredMark("Photographs *"))
        assertEquals("Photographs", dwWithoutRequiredMark("Photographs"))
        assertEquals("Odd label *", dwWithoutRequiredMark("Odd label * *"))
    }

    @Test
    fun `degenerate labels do not throw`() {
        assertEquals("", dwRequiredMarked("", mark).text)
        val markOnly = dwRequiredMarked(DW_REQUIRED_MARK, mark)
        assertEquals(DW_REQUIRED_MARK, markOnly.text)
        assertTrue("a bare mark is still painted", markOnly.spanStyles.any { it.item.color == mark })
    }
}
