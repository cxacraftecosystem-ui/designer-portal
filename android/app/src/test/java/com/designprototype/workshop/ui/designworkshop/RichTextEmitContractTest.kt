package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextEmitContractTest {

    private fun para(text: String) = RichBlock(kind = BlockKind.PARAGRAPH, spans = listOf(RichSpan(text)))

    private fun image(mediaId: String, caption: String) = RichBlock(
        kind = BlockKind.IMAGE,
        spans = if (caption.isEmpty()) emptyList() else listOf(RichSpan(caption)),
        media = mediaId,
        widthPct = 60f,
    )

    private fun publishedBlocks(doc: RichDoc) =
        emittedValue(doc)?.get("blocks")?.jsonArray?.map { it.jsonObject }

    @Test
    fun `placing a photograph in a blank field publishes a document`() {
        val opened = emptyDoc()
        val lastEmitted = docSignature(opened)
        assertEquals("the blank field starts out signed as empty", EMPTY_DOC_SIGNATURE, lastEmitted)

        val placed = insertImage(opened, collapsedAt(DocPoint(0, 0)), "media-42").doc

        assertNotEquals(
            "the document must not still sign as empty once it holds a photograph",
            lastEmitted,
            docSignature(placed),
        )

        val published = publishedBlocks(placed)
        assertNotNull("the field must publish a document, not null", published)
        val figure = published!!.single { it["kind"]!!.jsonPrimitive.content == "IMAGE" }
        assertEquals("media-42", figure["media"]!!.jsonPrimitive.content)
    }

    @Test
    fun `placing a photograph mid-narrative publishes it too`() {
        val doc = RichDoc(listOf(para("The loom stands in the courtyard.")))
        val lastEmitted = docSignature(doc)

        val placed = insertImage(doc, collapsedAt(DocPoint(0, 4)), "media-42").doc

        assertNotEquals(lastEmitted, docSignature(placed))
        assertEquals(
            listOf("PARAGRAPH", "IMAGE"),
            publishedBlocks(placed)!!.map { it["kind"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `clearing the caption of an image-only field does not publish null`() {
        val doc = RichDoc(listOf(image("media-42", "a loom")))
        val lastEmitted = docSignature(doc)
        assertNotEquals("a captioned photograph is content", EMPTY_DOC_SIGNATURE, lastEmitted)

        val cleared = deleteRange(doc, DocRange(DocPoint(0, 0), DocPoint(0, "a loom".length))).doc

        val published = publishedBlocks(cleared)
        assertNotNull("clearing a caption must never null the field", published)
        assertEquals("media-42", published!!.single()["media"]!!.jsonPrimitive.content)
        assertEquals("IMAGE", published.single()["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `backspacing away the last character of a caption does not publish null`() {
        val doc = RichDoc(listOf(image("media-42", "x")))

        val cleared = deleteBackward(doc, collapsedAt(DocPoint(0, 1))).doc

        assertEquals("the caption really is gone", "", cleared.blocks.single().text)
        assertNotNull("but the photograph is not", publishedBlocks(cleared))
        assertEquals("media-42", cleared.blocks.single().media)
    }

    @Test
    fun `an untouched field still publishes null`() {
        assertNull(emittedValue(emptyDoc()))
        assertNull(emittedValue(RichDoc(listOf(para("   ")))))
        assertEquals(EMPTY_DOC_SIGNATURE, docSignature(emptyDoc()))
    }

    @Test
    fun `an image block that lost its media id is still an empty document`() {
        assertTrue(isEmptyDoc(RichDoc(listOf(RichBlock(kind = BlockKind.IMAGE)))))
        assertNull(emittedValue(RichDoc(listOf(RichBlock(kind = BlockKind.IMAGE)))))
    }

    @Test
    fun `the text arm still trims the way JavaScript trims, not the way Kotlin does`() {
        val bom = Char(0xFEFF).toString()
        assertFalse("Kotlin does not consider U+FEFF blank", bom.isBlank())
        assertTrue("JavaScript's trim does", isJsBlank(bom))

        assertTrue("so a pasted byte-order mark is an empty document here too", isEmptyDoc(RichDoc(listOf(para(bom)))))
        assertNull(emittedValue(RichDoc(listOf(para(Char(0xA0).toString())))))
    }
}
