package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PROPERTY, NOT THE PROSE.
 *
 * Every assertion here is about something that would be a DEFECT if it changed, and none of them
 * pins a wording that a writer should be free to improve. Three properties carry the whole feature:
 *
 *  1. A list that was not cut says NOTHING. A notice printed over a complete list teaches a reader to
 *     ignore the notice, which is worse than never having written one.
 *  2. Once a term has been typed, the sentence never sends the reader off to search for a name. That
 *     is the one thing they have already done, and handing it back to them is how a picker teaches
 *     its user that searching does not work; the only instruction left that means anything is to
 *     narrow what they typed.
 *  3. The picker's own filter box is mentioned only where there IS one. `SingleSelectField` on this
 *     screen has none, so a sentence describing "the box inside this picker" over the workshop
 *     dropdown sends a reader looking for a control that does not exist.
 *
 * The wordings themselves are the handset's copy of `flagCutNotice`
 * (frontend/components/data/cappedList.ts). TRUE AS OF 2026-08-27, re-check with:
 *
 *     grep -n "export function flagCutNotice" -A 12 frontend/components/data/cappedList.ts
 */
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
        // The caller passes the SETTLED term, and `appliedSearch` is already trimmed — but the box
        // upstream is not, and a term of three spaces must take the "nothing typed" branch rather
        // than print a pair of quotation marks with nothing between them.
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
