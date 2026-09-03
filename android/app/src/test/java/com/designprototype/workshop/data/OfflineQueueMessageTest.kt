package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT A FORM IS ALLOWED TO PROMISE AFTER A SAVE WITH NO SIGNAL.
 *
 * `trySaveOffline` used to answer a bare `Boolean`, and a Boolean has exactly one sentence available
 * to it: *"Saved on this device. It'll upload automatically when you're back online."* That was said
 * whether all eight photographs had been copied or none of them had — and worse, an unreadable file
 * threw out of the staging loop, the call site flattened the throw to `false`, and `false` means
 * "we are online", so the form then went down the online path and lost the typed record outright.
 *
 * These pin the three sentences that replaced it. They are checked here rather than on a handset
 * because the only way to see them in the field is to fill in a form in a village with a corrupt
 * file in the gallery, and by then the record is either safe or it is not.
 */
class OfflineQueueMessageTest {

    private fun result(files: Int, unreadable: List<String> = emptyList()) =
        OfflineQueueResult(entryId = "e1", queuedFiles = files, unreadableFiles = unreadable)

    @Test
    fun `a clean save says one thing and does not mention files at all`() {
        val text = offlineSavedMessage(result(files = 4), isCorrection = false)
        assertEquals("Saved on this device. It will be sent when you have a signal.", text)
        assertTrue(result(files = 4).allFilesQueued)
        // Silence about the photographs is the point: a notice saying "all 4 files were saved" on top
        // of the SAVED tick is noise, and noise on this channel teaches people to dismiss it.
        assertFalse(text.contains("file"))
    }

    @Test
    fun `a correction says the office is still reading the old version`() {
        val text = offlineSavedMessage(result(files = 0), isCorrection = true)
        // This is the whole difference between the two promises. A new record does not exist anywhere
        // until it is sent; a correction has an OLDER VERSION OF ITSELF on the server in the
        // meantime, and a designer who has just fixed a wrong phone number needs to know that the
        // wrong one is still the one being read.
        assertTrue(
            "a queued correction must not read as though the fix has landed:\n$text",
            text.contains("the office still sees the earlier version"),
        )
        assertTrue(text, text.contains("saved on this device"))
        // AND WHO WINS, which the sentence used to leave out. `writeFromEntry` replays a correction as
        // a whole create-shaped body with no version and no If-Match, so when it finally goes it
        // overwrites every change anyone else made in between — silently, on both sides. The trade is
        // defensible for a register a small team keeps; making it silently is not.
        assertTrue(
            "a queued correction must say that it will overwrite a later edit:\n$text",
            text.contains("your version wins"),
        )
        assertTrue(text, text.contains("replaces the whole record"))
        // THREE FACTS, NOT FIVE (2026-09-03). This closed with "Tell them if that matters" — an
        // instruction naming nobody, on a toast that is gone in five seconds, addressed to a designer
        // who has already put the phone in their pocket. Every fact above survives; the ceiling is
        // what stops the paragraph growing back one clause at a time.
        assertTrue("it is ${text.length} characters:\n$text", text.length <= 230)
        assertFalse(text, text.contains("Tell them"))
    }

    @Test
    fun `an unreadable capture is NAMED, and the record is still called safe`() {
        val text = offlineSavedMessage(
            result(files = 6, unreadable = listOf("IMG_2201.jpg", "clip-4.m4a")),
            isCorrection = false,
        )
        // NAMED rather than counted, because the designer's next act is to look at the gallery and
        // decide which one to take again, and "2 files failed" does not tell them which.
        assertTrue(text.contains("IMG_2201.jpg"))
        assertTrue(text.contains("clip-4.m4a"))
        assertTrue("the count is there too, for a long list", text.contains("2 file(s)"))
        // THE HONEST HALF OF A WHOLE THIS APP CANNOT DELIVER. Those bytes were never obtainable; what
        // must not happen is the record being described as complete.
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

    /**
     * THE CLEAN SAVE IS THE MEASURE THE OTHERS ARE HELD TO.
     *
     * "Saved on this device. It will be sent when you have a signal." is the sentence this whole file
     * is about — one state, one promise, nine words — and it has never needed changing. Pinned as an
     * upper bound on its neighbours so that "terse" is a property of the surface rather than of
     * whichever string somebody looked at last.
     */
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
        // A record with no photographs at all is a clean save, not a failure. The forms call this on
        // every offline save, including the ones that never had a capture.
        assertTrue(result(files = 0).allFilesQueued)
        assertFalse(result(files = 9, unreadable = listOf("one.jpg")).allFilesQueued)
    }
}
