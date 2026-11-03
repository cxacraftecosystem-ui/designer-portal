package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwDocumentOpenTargetTest {

    private fun file(name: String): File = File("/does/not/need/to/exist/$name")

    @Test
    fun `a document already fetched to this device is opened from the file, not the url`() {
        val cached = file("survey.pdf")
        val target = dwOpenTarget(
            justPicked = null,
            onDevice = cached,
            remoteUrl = "https://example.test/signed/survey.pdf",
        )
        assertEquals(DwOpenTarget.OnDevice(cached), target)
    }

    @Test
    fun `a cached document is offered with no url in sight`() {
        val cached = file("cv.pdf")
        assertEquals(
            DwOpenTarget.OnDevice(cached),
            dwOpenTarget(justPicked = null, onDevice = cached, remoteUrl = null),
        )
    }

    @Test
    fun `a file just picked wins over a cached one`() {
        val picked = file("corrected-cv.pdf")
        val cached = file("superseded-cv.pdf")
        assertEquals(
            DwOpenTarget.OnDevice(picked),
            dwOpenTarget(justPicked = picked, onDevice = cached, remoteUrl = "https://example.test/x"),
        )
    }

    @Test
    fun `with nothing on the device the url is what Open follows`() {
        val url = "https://example.test/signed/cv.pdf"
        assertEquals(
            DwOpenTarget.Remote(url),
            dwOpenTarget(justPicked = null, onDevice = null, remoteUrl = url),
        )
    }

    @Test
    fun `with neither a file nor a url there is nothing to offer`() {
        assertNull(dwOpenTarget(justPicked = null, onDevice = null, remoteUrl = null))
    }

    @Test
    fun `no expression in the preview pretends to be a fallback and returns null`() {
        val source = File("src/main/java/com/designprototype/workshop/ui/designworkshop/DwDocumentPreview.kt")
        assertTrue("the preview source moved: ${source.absolutePath}", source.isFile)
        val code = source.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .joinToString("") { line -> line.filterNot { it.isWhitespace() } }
        assertFalse(
            "`?.let { null }` is back in DwDocumentPreview: it is null for every input, so whatever " +
                "it reads as, it is dead code — see this test's header.",
            code.contains("?.let{null}"),
        )
        assertTrue("the comment filter left no code to guard", code.contains("fundwOpenTarget("))
    }

    @Test
    fun `a withheld file is never described as downloadable`() {
        val note = dwUndrawnDocumentNote(noun = "CV", openableHere = false)
        assertEquals(dwWithheldFileNote("CV"), note)
        assertFalse("a file with nothing to download was called downloadable", note.contains("download"))
    }

    @Test
    fun `a file this app cannot draw but can hand over is still called downloadable`() {
        val note = dwUndrawnDocumentNote(noun = "CV", openableHere = true)
        assertTrue(note.startsWith("Stored and downloadable."))
        assertNotEquals(dwWithheldFileNote("CV"), note)
    }

    @Test
    fun `the wording is the web's, to the character`() {
        assertEquals(
            "CV is stored, but this account may not open the file itself.",
            dwWithheldFileNote("CV"),
        )
        assertEquals(
            "Stored and downloadable. A PDF or a 3D model is shown inside the app; this one opens in " +
                "whatever program handles it on your device.",
            dwUndrawnDocumentNote(noun = "market survey", openableHere = true),
        )
    }

    @Test
    fun `the withheld sentence takes the reader's noun`() {
        assertEquals(
            "market survey is stored, but this account may not open the file itself.",
            dwWithheldFileNote("market survey"),
        )
    }
}
