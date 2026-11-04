package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPickerCopyTest {

    @Test
    fun `a list that was not cut says nothing at all`() {
        assertNull(taskPickerCutNotice(truncated = false, noun = "people", term = "", localFilter = true))
        assertNull(taskPickerCutNotice(truncated = false, noun = "people", term = "giri", localFilter = true))
        assertNull(taskPickerCutNotice(truncated = false, noun = "workshops", term = "", localFilter = false))
    }

    @Test
    fun `with a term typed it never tells the reader to search`() {
        val said = taskPickerCutNotice(truncated = true, noun = "people", term = "giri", localFilter = true)!!
        assertFalse(
            "telling somebody to go and search when they have just searched is the defect this branch exists for",
            said.contains("search for a name", ignoreCase = true),
        )
        assertTrue("and it must name what they typed", said.contains("giri"))
        assertTrue("and say what to do instead", said.contains("narrow", ignoreCase = true))
    }

    @Test
    fun `with nothing typed it names the box that can reach past the cut`() {
        val said = taskPickerCutNotice(truncated = true, noun = "artisans", term = "", localFilter = true)!!
        assertTrue(said.contains("search for a name", ignoreCase = true))
        assertTrue("and the noun is the caller's", said.contains("artisans"))
    }

    @Test
    fun `the picker's own box is mentioned only where the picker has one`() {
        val withBox = taskPickerCutNotice(truncated = true, noun = "people", term = "", localFilter = true)!!
        val withoutBox = taskPickerCutNotice(truncated = true, noun = "workshops", term = "", localFilter = false)!!

        assertTrue(withBox.contains("only filters what is already listed"))
        assertFalse(
            "SingleSelectField has no filter box; naming one sends the reader looking for it",
            withoutBox.contains("only filters what is already listed"),
        )
        assertNotEquals(withBox, withoutBox)
    }

    @Test
    fun `whitespace is not a search term`() {
        val blank = taskPickerCutNotice(truncated = true, noun = "people", term = "   ", localFilter = true)
        val none = taskPickerCutNotice(truncated = true, noun = "people", term = "", localFilter = true)
        assertEquals(none, blank)
    }

    @Test
    fun `the two branches are not interchangeable`() {
        val untyped = taskPickerCutNotice(truncated = true, noun = "people", term = "", localFilter = true)
        val typed = taskPickerCutNotice(truncated = true, noun = "people", term = "giri", localFilter = true)
        assertNotEquals(untyped, typed)
    }
}
