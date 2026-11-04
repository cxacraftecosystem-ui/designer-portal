package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchableSelectEmptyStateTest {

    private fun options(n: Int): List<SelectOption> =
        (1..n).map { SelectOption(value = "id-$it", label = "Option $it") }

    @Test
    fun `the threshold is eight and it does not move`() {
        assertEquals(8, SEARCH_THRESHOLD)
    }

    @Test
    fun `with no ruling from the caller the count still decides, on the same boundary as before`() {
        assertFalse("an empty list takes the menu", resolveSearchable(null, options(0)))
        assertFalse("one row takes the menu", resolveSearchable(null, options(1)))
        assertFalse("seven rows still take the menu", resolveSearchable(null, options(7)))
        assertTrue("eight rows take the sheet", resolveSearchable(null, options(8)))
        assertTrue("nine rows take the sheet", resolveSearchable(null, options(9)))
        assertTrue("a long list takes the sheet", resolveSearchable(null, options(200)))
    }

    @Test
    fun `false overrules a long list whose options are one truncated page`() {
        assertFalse(resolveSearchable(false, options(20)))
        assertFalse(resolveSearchable(false, options(200)))
    }

    @Test
    fun `true overrules a short or empty list that would otherwise change shape`() {
        assertTrue(resolveSearchable(true, options(2)))
        assertTrue(resolveSearchable(true, options(7)))
        assertTrue("a list that shrank to nothing keeps its surface", resolveSearchable(true, options(0)))
    }

    @Test
    fun `an empty list prints the caller's sentence and nothing else`() {
        val sentences = listOf(
            "This list is empty.",
            "4 design workshops on this device, last refreshed 12 March. If the one you want is " +
                "missing, refresh with a connection before concluding it is not on record.",
            "This device has not received the artisans list yet, so there is nothing to pick here. " +
                "That is not a claim that there are none. Connect once and the list is kept on the " +
                "device from then on.",
            "The districts list could not be loaded, so this is not showing what exists. Nothing " +
                "you have entered is at risk — this record can be saved without it.",
            "No design workshops are open to this account. An administrator can give you access to one."
        )
        sentences.forEach { sentence ->
            assertEquals(
                sentence,
                pickerEmptyLine(searching = false, query = "", emptyMessage = sentence, listIsEmpty = true)
            )
        }
    }

    @Test
    fun `a search that matched nothing is a different sentence from a list with nothing in it`() {
        val empty = "This device has not received the artisans list yet."
        val matched = pickerEmptyLine(
            searching = true,
            query = "bagru",
            emptyMessage = empty,
            listIsEmpty = false
        )

        assertEquals("Nothing matches “bagru”.", matched)
        assertNotEquals(empty, matched)
    }

    @Test
    fun `a list with no members keeps its own sentence however much is typed over it`() {
        val empty = "No records for this on the device yet. Connect once and reopen this stage."
        listOf("R", "Ram", "Ram Kumar", "  ram bagru  ", "zzzz").forEach { typed ->
            assertEquals(
                "typed: $typed",
                empty,
                pickerEmptyLine(searching = true, query = typed, emptyMessage = empty, listIsEmpty = true)
            )
        }
    }

    @Test
    fun `the live region reports the size of an empty list rather than a failed search`() {
        assertEquals("0 options", countLine(shown = 0, total = 0, searching = false))
        assertEquals("0 options", countLine(shown = 0, total = 0, searching = true))
    }

    @Test
    fun `a list with members counts exactly as it did`() {
        assertEquals("1 option", countLine(shown = 1, total = 1, searching = false))
        assertEquals("74 options", countLine(shown = 74, total = 74, searching = false))
        assertEquals("12 of 74 match", countLine(shown = 12, total = 74, searching = true))
        assertEquals("No matches", countLine(shown = 0, total = 74, searching = true))
    }

    @Test
    fun `the echoed term is trimmed`() {
        assertEquals(
            "Nothing matches “bagru”.",
            pickerEmptyLine(
                searching = true,
                query = "  bagru  ",
                emptyMessage = "This list is empty.",
                listIsEmpty = false
            )
        )
    }
}
