package com.designprototype.workshop.ui.questionnaires

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionnaireTranscriptDocumentTest {

    @Test
    fun `the characters a filesystem refuses are replaced, not dropped`() {
        assertEquals(
            "D-RAW-MATERIALS-transcript.md",
            transcriptDocumentFileName("D: RAW/MATERIALS?transcript", "md")
        )
    }

    @Test
    fun `runs of whitespace and dashes collapse to one, and the ends are trimmed`() {
        assertEquals("a-b.md", transcriptDocumentFileName("  a   ---  b  ", "md"))
    }

    @Test
    fun `a name that cleans down to nothing still names a file`() {
        assertEquals("transcript.md", transcriptDocumentFileName("///", "md"))
        assertEquals("transcript.md", transcriptDocumentFileName("", "md"))
    }

    @Test
    fun `a two-thousand character prompt does not become a two-thousand character path segment`() {
        val name = transcriptDocumentFileName("क".repeat(400), "md")
        assertEquals(60 + ".md".length, name.length)
    }

    @Test
    fun `an emoji cut in half at the ceiling is dropped rather than left as half a character`() {
        val name = transcriptDocumentFileName("a".repeat(59) + "🧵", "md")
        assertEquals(59 + ".md".length, name.length)
        assertTrue(name.none { it.isHighSurrogate() || it.isLowSurrogate() })
    }

    @Test
    fun `the extension and the media type are markdown, and both clients say so`() {
        assertEquals("md", TRANSCRIPT_DOCUMENT_EXTENSION)
        assertEquals("text/markdown", TRANSCRIPT_DOCUMENT_MIME)
    }
}
