package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.report.toJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextStructuralGuardTest {

    private fun para(text: String) = RichBlock(
        kind = BlockKind.PARAGRAPH,
        spans = listOf(RichSpan(text)),
    )

    private fun image(mediaId: String, caption: String) = RichBlock(
        kind = BlockKind.IMAGE,
        spans = listOf(RichSpan(caption, marks = setOf(com.designprototype.workshop.report.Mark.BOLD))),
        media = mediaId,
        widthPct = 60f,
    )

    private fun table(cell: String) = RichBlock(
        kind = BlockKind.TABLE,
        rows = listOf(
            listOf(listOf(RichSpan("Head")), listOf(RichSpan("Also head"))),
            listOf(listOf(RichSpan(cell)), listOf(RichSpan("second"))),
        ),
    )

    private fun wholeDoc(doc: RichDoc) = DocRange(
        DocPoint(0, 0),
        DocPoint(doc.blocks.lastIndex, 10_000),
    )

    private fun storedBlocks(doc: RichDoc) = toJson(doc)["blocks"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `clearFormatting over a selection containing a photograph keeps the picture`() {
        val doc = RichDoc(listOf(para("before"), image("media-42", "a loom"), para("after")))

        val result = clearFormatting(doc, wholeDoc(doc))
        val stored = storedBlocks(result.doc)

        assertEquals("the image block is still an IMAGE", "IMAGE", stored[1]["kind"]!!.jsonPrimitive.content)
        assertNotNull("the picture survives the command", stored[1]["media"])
        assertEquals("media-42", stored[1]["media"]!!.jsonPrimitive.content)

        val captionSpans = stored[1]["spans"]!!.jsonArray.map { it.jsonObject }
        assertTrue("the caption keeps its text", captionSpans.any { it["text"]!!.jsonPrimitive.content == "a loom" })
        assertTrue("the caption's marks are cleared", captionSpans.all { it["marks"] == null })

        assertEquals("PARAGRAPH", stored[0]["kind"]!!.jsonPrimitive.content)
        assertEquals("PARAGRAPH", stored[2]["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clearFormatting over a selection containing a table keeps the grid`() {
        val doc = RichDoc(listOf(para("before"), table("Warp cost"), para("after")))

        val stored = storedBlocks(clearFormatting(doc, wholeDoc(doc)).doc)

        assertEquals("TABLE", stored[1]["kind"]!!.jsonPrimitive.content)
        val rows = stored[1]["rows"]
        assertNotNull("the grid survives the command", rows)
        assertEquals("both rows are still there", 2, rows!!.jsonArray.size)
    }

    @Test
    fun `setBlockKind over a selection containing structural blocks leaves them alone`() {
        val doc = RichDoc(listOf(para("intro"), table("Warp cost"), image("media-7", "detail"), para("outro")))

        val stored = storedBlocks(setBlockKind(doc, wholeDoc(doc), "HEADING", 2).doc)

        assertEquals("HEADING", stored[0]["kind"]!!.jsonPrimitive.content)
        assertEquals("TABLE", stored[1]["kind"]!!.jsonPrimitive.content)
        assertEquals("IMAGE", stored[2]["kind"]!!.jsonPrimitive.content)
        assertNotNull("the grid survives a heading press", stored[1]["rows"])
        assertEquals("media-7", stored[2]["media"]!!.jsonPrimitive.content)
        assertEquals("HEADING", stored[3]["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the guard is not a blanket skip - prose still loses its kind, align and level`() {
        val doc = RichDoc(
            listOf(
                RichBlock(
                    kind = BlockKind.HEADING,
                    spans = listOf(RichSpan("A centred heading", marks = setOf(com.designprototype.workshop.report.Mark.ITALIC))),
                    align = com.designprototype.workshop.report.Align.CENTER,
                    level = 2,
                )
            )
        )

        val stored = storedBlocks(clearFormatting(doc, wholeDoc(doc)).doc)[0]

        assertEquals("PARAGRAPH", stored["kind"]!!.jsonPrimitive.content)
        assertNull("align returns to LEFT and is therefore omitted", stored["align"])
        assertNull("level returns to 0 and is therefore omitted", stored["level"])
    }

    @Test
    fun `prose cannot be re-kinded INTO a structural block either`() {
        val doc = RichDoc(listOf(para("a sentence"), para("another")))

        for (kind in listOf("TABLE", "IMAGE")) {
            val result = setBlockKind(doc, wholeDoc(doc), kind, 0)
            assertEquals("$kind: the prose is left alone", doc, result.doc)
            assertEquals(
                "$kind: nothing is dropped on the next read",
                2,
                storedBlocks(result.doc).size,
            )
        }
    }

    @Test
    fun `the guard on the target kind is not a blanket refusal`() {
        val doc = RichDoc(listOf(para("a sentence")))

        assertEquals(
            "HEADING",
            storedBlocks(setBlockKind(doc, wholeDoc(doc), "HEADING", 2).doc)[0]["kind"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "QUOTE",
            storedBlocks(setBlockKind(doc, wholeDoc(doc), "QUOTE", 0).doc)[0]["kind"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `isStructural names exactly the two kinds whose content is not their spans`() {
        val structural = BlockKind.values().filter { it.isStructural }.toSet()
        assertEquals(setOf(BlockKind.TABLE, BlockKind.IMAGE), structural)
    }
}
