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

class QuestionnaireAnswerTextTest {

    private fun paragraph(text: String, marks: Set<Mark> = emptySet()) =
        RichBlock(spans = listOf(RichSpan(text, marks)))

    @Test
    fun `an unformatted answer is stored as the words, not as JSON`() {
        val doc = RichDoc(blocks = listOf(paragraph("She learnt it from her mother.")))
        assertEquals("She learnt it from her mother.", questionnaireAnswerStored(doc))
    }

    @Test
    fun `an empty document stores nothing at all`() {
        assertEquals("", questionnaireAnswerStored(RichDoc()))
        assertEquals("", questionnaireAnswerStored(RichDoc(blocks = listOf(paragraph("   ")))))
    }

    @Test
    fun `typed prose survives a save, a reopen and a second save unchanged`() {
        val typed = "1. She learnt it from her mother\n2. Then from the co-operative"
        val once = questionnaireAnswerStored(questionnaireAnswerDoc(typed))
        val twice = questionnaireAnswerStored(questionnaireAnswerDoc(once))
        assertEquals(typed, once)
        assertEquals(typed, twice)
    }

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

    @Test
    fun `a document the web wrote reads as the words a person wrote`() {
        val stored = """{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"She dyes it in indigo","marks":["BOLD"]}]}]}"""
        assertEquals("She dyes it in indigo", questionnaireAnswerPlain(stored))
    }

    @Test
    fun `prose is returned by identity and never round-tripped`() {
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
        val broken = """{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"half a"""
        assertEquals(broken, questionnaireAnswerPlain(broken))
    }

    @Test
    fun `a typed list marker is NOT read back as a list`() {
        val doc = questionnaireAnswerDoc("1. She learnt it from her mother")
        assertEquals(BlockKind.PARAGRAPH, doc.blocks.single().kind)
        assertTrue(questionnaireAnswerIsProse(doc))
    }

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
