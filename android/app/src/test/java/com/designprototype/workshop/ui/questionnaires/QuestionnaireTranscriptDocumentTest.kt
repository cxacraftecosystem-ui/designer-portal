package com.designprototype.workshop.ui.questionnaires

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE NAME A SAVED TRANSCRIPT LANDS UNDER, PINNED.
 *
 * ── WHY A FILE NAME IS WORTH A TEST ──────────────────────────────────────────────────────────────
 *
 * These names are built from a recording's own filename and from section titles, and both are
 * untrusted text typed by a person or produced by a phone's recorder. A colon or a slash reaching
 * `MediaStore.Downloads.DISPLAY_NAME` is not a crash a designer can act on — it is a save that fails
 * or a file that lands somewhere else — and an unbounded name is a save that fails on a path-segment
 * limit for exactly the Devanagari and Odia titles this fleet produces most.
 *
 * `safeDocumentFileName` on the web is the same rules, and it has to be: the two clients hand the
 * same transcript to the same researcher, and a file that arrives under two different names from two
 * devices is one a person cannot tell apart from two different transcripts.
 */
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
        // A section titled entirely in punctuation is unlikely and possible; a save with no name at
        // all is an exception the designer meets after the transcript is already gone from the screen.
        assertEquals("transcript.md", transcriptDocumentFileName("///", "md"))
        assertEquals("transcript.md", transcriptDocumentFileName("", "md"))
    }

    @Test
    fun `a two-thousand character prompt does not become a two-thousand character path segment`() {
        val name = transcriptDocumentFileName("क".repeat(400), "md")
        // 60 characters of Devanagari is 180 bytes in UTF-8 — comfortably inside the 255-BYTE
        // segment limit several filesystems impose, which 400 characters is not.
        assertEquals(60 + ".md".length, name.length)
    }

    @Test
    fun `an emoji cut in half at the ceiling is dropped rather than left as half a character`() {
        // The one deliberate divergence from the web, which slices blind. A lone surrogate in a
        // DISPLAY_NAME is not a crash; it is a file whose name renders as a replacement box on the
        // handset that wrote it.
        val name = transcriptDocumentFileName("a".repeat(59) + "🧵", "md")
        assertEquals(59 + ".md".length, name.length)
        assertTrue(name.none { it.isHighSurrogate() || it.isLowSurrogate() })
    }

    @Test
    fun `the extension and the media type are markdown, and both clients say so`() {
        // `.txt` would lose nothing visually and would lose the thing that matters: a refined
        // transcript's SPEAKER LABELS are markdown, and so are the rules between takes.
        assertEquals("md", TRANSCRIPT_DOCUMENT_EXTENSION)
        assertEquals("text/markdown", TRANSCRIPT_DOCUMENT_MIME)
    }
}
