package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.report.Align
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.Mark
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT THE INTERVIEW FORM PUTS IN A `String?` COLUMN, PINNED.
 *
 * ── EVERY FAILURE BELOW IS SILENT, WHICH IS WHY THERE IS A TEST ──────────────────────────────────
 *
 * Nothing here throws. A broken encoder writes a valid string into a nullable text column and the
 * save succeeds; the symptom arrives later and somewhere else — `{"blocks":[{"kind":"PARAGRAPH"…`
 * printed into a consolidated page a ministry report is assembled from, or a researcher's numbered
 * list flattened into one paragraph on the second save, or a second recording's words concatenated
 * onto the end of a JSON string so that neither the editor nor any reader can make sense of the
 * answer again. None of those is visible in a diff and none of them raises.
 *
 * ── THE FOUR RULES ───────────────────────────────────────────────────────────────────────────────
 *
 * 1. **Prose stays prose.** An answer nobody formatted keeps exactly the bytes it kept before the
 *    box became an editor. That is what lets the two clients ship in either order and lets every
 *    reader that has not learnt to flatten go on working for every answer but the formatted ones.
 * 2. **Formatting is stored, and the test for "formatted" is a whitelist.** A block kind or a mark
 *    added later must default to "store as JSON", never to "quietly drop it" — the failure of the
 *    other polarity is a table flattened to pipe-separated lines on save that nobody finds until the
 *    report is printed.
 * 3. **Reading accepts both shapes.** The web writes documents into this column today, so a build
 *    that read only prose would show a colleague's answer as braces and overwrite it.
 * 4. **Appending knows the difference.** A transcript joined onto a stored document has to go INTO
 *    the document.
 */
class QuestionnaireAnswerTextTest {

    private fun paragraph(text: String, marks: Set<Mark> = emptySet()) =
        RichBlock(spans = listOf(RichSpan(text, marks)))

    // ── 1. Prose stays prose ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an unformatted answer is stored as the words, not as JSON`() {
        val doc = RichDoc(blocks = listOf(paragraph("She learnt it from her mother.")))
        assertEquals("She learnt it from her mother.", questionnaireAnswerStored(doc))
    }

    @Test
    fun `an empty document stores nothing at all`() {
        assertEquals("", questionnaireAnswerStored(RichDoc()))
        // A box that was focused and left alone still emits a paragraph with nothing in it. Counting
        // that as an answer would mark an unanswered question answered on every completeness count
        // this form draws.
        assertEquals("", questionnaireAnswerStored(RichDoc(blocks = listOf(paragraph("   ")))))
    }

    @Test
    fun `typed prose survives a save, a reopen and a second save unchanged`() {
        // THE ROUND TRIP IS TESTED TWICE ON PURPOSE. One trip looks fine for almost any encoder; the
        // failures in this area are all second-save failures, where a marker the flattener wrote is
        // read back as structure and re-encoded as something else.
        val typed = "1. She learnt it from her mother\n2. Then from the co-operative"
        val once = questionnaireAnswerStored(questionnaireAnswerDoc(typed))
        val twice = questionnaireAnswerStored(questionnaireAnswerDoc(once))
        assertEquals(typed, once)
        assertEquals(typed, twice)
    }

    // ── 2. Formatting is stored ──────────────────────────────────────────────────────────────────

    @Test
    fun `a mark makes the answer a document`() {
        val doc = RichDoc(blocks = listOf(paragraph("indigo", setOf(Mark.BOLD))))
        val stored = questionnaireAnswerStored(doc)
        assertTrue(stored.startsWith("{"))
        assertTrue(stored.contains("\"blocks\""))
        assertTrue(stored.contains("BOLD"))
    }

    @Test
    fun `a list, a heading, an alignment, a table and a picture are each enough on their own`() {
        // The whitelist, exercised one field at a time. Any of these silently surviving as prose is
        // a designer's structure lost on save.
        assertFalse(questionnaireAnswerIsProse(RichDoc(listOf(RichBlock(kind = BlockKind.BULLET_ITEM)))))
        assertFalse(questionnaireAnswerIsProse(RichDoc(listOf(RichBlock(kind = BlockKind.HEADING, level = 2)))))
        assertFalse(questionnaireAnswerIsProse(RichDoc(listOf(RichBlock(align = Align.CENTER)))))
        assertFalse(
            questionnaireAnswerIsProse(
                RichDoc(listOf(RichBlock(kind = BlockKind.TABLE, rows = listOf(listOf(listOf(RichSpan("a")))))))
            )
        )
        assertFalse(
            questionnaireAnswerIsProse(RichDoc(listOf(RichBlock(kind = BlockKind.IMAGE, media = "media-1"))))
        )
        assertTrue(questionnaireAnswerIsProse(RichDoc(listOf(paragraph("plain")))))
    }

    @Test
    fun `a formatted answer reopens with its formatting intact`() {
        val doc = RichDoc(blocks = listOf(paragraph("indigo", setOf(Mark.BOLD))))
        val reopened = questionnaireAnswerDoc(questionnaireAnswerStored(doc))
        assertEquals(setOf(Mark.BOLD), reopened.blocks.single().spans.single().marks)
    }

    // ── 3. Reading accepts both shapes ───────────────────────────────────────────────────────────

    @Test
    fun `a document the web wrote reads as the words a person wrote`() {
        val stored = """{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"She dyes it in indigo","marks":["BOLD"]}]}]}"""
        // The read boundary. Without this the interview detail card prints the braces where an
        // artisan's answer belongs.
        assertEquals("She dyes it in indigo", questionnaireAnswerPlain(stored))
    }

    @Test
    fun `prose is returned by identity and never round-tripped`() {
        // Deliberate: a value that merely LOOKS structured — a sentence beginning with a brace, two
        // spaces somebody typed — is somebody's typing and must come back byte for byte.
        val prose = "  she said: {this is how we do it}  "
        assertEquals(prose, questionnaireAnswerPlain(prose))
    }

    @Test
    fun `nothing at all reads as the empty string, so callers keep their own blank wording`() {
        assertEquals("", questionnaireAnswerPlain(null))
        assertEquals("", questionnaireAnswerPlain(""))
        assertEquals("", questionnaireAnswerPlain("   "))
    }

    @Test
    fun `a truncated document is shown as the characters that are in the record`() {
        // NOT an empty box. A blank invites the researcher to overwrite a corrupt value with nothing,
        // which is the one outcome from which there is no recovery.
        val broken = """{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"half a"""
        assertEquals(broken, questionnaireAnswerPlain(broken))
    }

    @Test
    fun `a typed list marker is NOT read back as a list`() {
        // The divergence from `recordDocFromStored`, and the reason it is deliberate: a record column
        // stores a list AS its markers, this column stores a list as JSON. Reading markers here would
        // promote a typed sentence into a stored document on its next save.
        val doc = questionnaireAnswerDoc("1. She learnt it from her mother")
        assertEquals(BlockKind.PARAGRAPH, doc.blocks.single().kind)
        assertTrue(questionnaireAnswerIsProse(doc))
    }

    // ── 4. Appending knows the difference ────────────────────────────────────────────────────────

    @Test
    fun `a second take joins prose with one space, exactly as it always did`() {
        assertEquals(
            "she weaves the warp and she dyes it in indigo",
            questionnaireAnswerAppend("she weaves the warp", "and she dyes it in indigo")
        )
        assertEquals("the first take", questionnaireAnswerAppend("", "the first take"))
    }

    @Test
    fun `a second take goes INTO a stored document and never onto the end of its JSON`() {
        val formatted = questionnaireAnswerStored(RichDoc(blocks = listOf(paragraph("indigo", setOf(Mark.BOLD)))))
        val merged = questionnaireAnswerAppend(formatted, "and the warp is sized with rice")

        // Still a document, still parseable, and BOTH takes are in it — which is the whole point:
        // concatenating onto the JSON produced a value no reader could make sense of, and the
        // researcher's own formatting was the thing that put the box in that state.
        val doc = questionnaireAnswerDoc(merged)
        assertEquals(2, doc.blocks.size)
        assertEquals(setOf(Mark.BOLD), doc.blocks[0].spans.single().marks)
        assertEquals("and the warp is sized with rice", doc.blocks[1].spans.single().text)
    }

    @Test
    fun `a multi-line take becomes one paragraph per line and not one run-on sentence`() {
        val formatted = questionnaireAnswerStored(RichDoc(blocks = listOf(paragraph("indigo", setOf(Mark.BOLD)))))
        val merged = questionnaireAnswerAppend(formatted, "first take\nsecond take")
        assertEquals(3, questionnaireAnswerDoc(merged).blocks.size)
    }

    @Test
    fun `accepting an offer over a formatted answer keeps both, and cannot lose a syllable`() {
        // Rule 2 of `QuestionnaireTranscriptsTest`, on the one path that can meet a document: an
        // offer is only ever made because the box DIFFERS from the machine's words, and formatting
        // is one way to differ.
        val formatted = questionnaireAnswerStored(RichDoc(blocks = listOf(paragraph("my own words", setOf(Mark.ITALIC)))))
        val merged = questionnaireAcceptOffer(formatted, "the offered take")
        val plain = questionnaireAnswerPlain(merged)
        assertTrue(plain.contains("my own words"))
        assertTrue(plain.contains("the offered take"))
    }

    @Test
    fun `an empty take changes nothing`() {
        assertEquals("her own words", questionnaireAnswerAppend("her own words", "   "))
    }
}
