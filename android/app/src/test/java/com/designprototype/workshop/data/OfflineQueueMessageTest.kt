package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineQueueMessageTest {

    private fun result(files: Int, unreadable: List<String> = emptyList()) =
        OfflineQueueResult(entryId = "e1", queuedFiles = files, unreadableFiles = unreadable)

    @Test
    fun `a clean save says one thing and does not mention files at all`() {
        val text = offlineSavedMessage(result(files = 4), isCorrection = false)
        assertEquals("Saved on this phone. It sends when you're online.", text)
        assertTrue(result(files = 4).allFilesQueued)
        assertFalse(text.contains("file"))
    }

    @Test
    fun `a correction says the office is still reading the old version`() {
        val text = offlineSavedMessage(result(files = 0), isCorrection = true)
        assertTrue(
            "a queued correction must not read as though the fix has landed:\n$text",
            text.contains("the office still sees the earlier version"),
        )
        assertTrue(text, text.contains("saved on this phone"))
        assertFalse(
            "a queued correction does not narrate the overwrite gap:\n$text",
            text.contains("your version wins"),
        )
        assertTrue(text, text.contains("It will be sent when you have a signal"))
        assertTrue("it is ${text.length} characters:\n$text", text.length <= 230)
        assertFalse(text, text.contains("Tell them"))
    }

    @Test
    fun `an unreadable capture is NAMED, and the record is still called safe`() {
        val text = offlineSavedMessage(
            result(files = 6, unreadable = listOf("IMG_2201.jpg", "clip-4.m4a")),
            isCorrection = false,
        )
        assertTrue(text.contains("IMG_2201.jpg"))
        assertTrue(text.contains("clip-4.m4a"))
        assertTrue("the count is there too, for a long list", text.contains("2 files"))
        assertTrue(
            "the record IS safe and saying so is what stops the designer re-typing it:\n$text",
            text.contains("the record is safe"),
        )
        assertTrue(
            "and the captures are not, said while they can still be taken again:\n$text",
            text.contains("Take them again if you still can"),
        )
    }

    @Test
    fun `a correction that lost a file says both things`() {
        val text = offlineSavedMessage(result(files = 1, unreadable = listOf("x.jpg")), isCorrection = true)
        assertTrue(text.contains("the office still sees the earlier version"))
        assertTrue(text.contains("x.jpg"))
    }

    @Test
    fun `the correction sentence is not many times the length of the clean one`() {
        val clean = offlineSavedMessage(result(files = 0), isCorrection = false)
        val correction = offlineSavedMessage(result(files = 0), isCorrection = true)
        assertTrue(
            "the correction is ${correction.length} characters against the clean save's ${clean.length}",
            correction.length <= clean.length * 4,
        )
    }

    @Test
    fun `allFilesQueued is about readability, not about how many were attached`() {
        assertTrue(result(files = 0).allFilesQueued)
        assertFalse(result(files = 9, unreadable = listOf("one.jpg")).allFilesQueued)
    }
}
