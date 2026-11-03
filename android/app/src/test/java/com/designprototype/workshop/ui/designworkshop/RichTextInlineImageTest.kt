package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.IMAGE_WIDTH_STEP_PCT
import com.designprototype.workshop.report.ImageBlock
import com.designprototype.workshop.report.ImageRef
import com.designprototype.workshop.report.Mark
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.report.fromJson
import com.designprototype.workshop.report.toJson
import com.designprototype.workshop.report.toReportBlocks
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextInlineImageTest {

    private fun para(text: String) = RichBlock(
        kind = BlockKind.PARAGRAPH,
        spans = listOf(RichSpan(text)),
    )

    private fun image(mediaId: String, caption: String) = RichBlock(
        kind = BlockKind.IMAGE,
        spans = if (caption.isEmpty()) emptyList() else listOf(RichSpan(caption)),
        media = mediaId,
        widthPct = 60f,
    )

    private fun table(cell: String) = RichBlock(
        kind = BlockKind.TABLE,
        rows = listOf(
            listOf(listOf(RichSpan("Head"))),
            listOf(listOf(RichSpan(cell))),
        ),
    )

    private fun storedBlocks(doc: RichDoc) = toJson(doc)["blocks"]!!.jsonArray.map { it.jsonObject }

    private fun kinds(doc: RichDoc) = storedBlocks(doc).map { it["kind"]!!.jsonPrimitive.content }

    private fun textOf(block: kotlinx.serialization.json.JsonObject) =
        block["spans"]!!.jsonArray.joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }

    @Test
    fun `Enter in the middle of a caption keeps the picture and loses no text`() {
        val doc = RichDoc(listOf(para("before"), image("media-42", "a loom in Kotpad")))

        val result = splitBlock(doc, collapsedAt(DocPoint(1, 6)))
        val stored = storedBlocks(result.doc)

        assertEquals(
            "the figure survives and a plain paragraph opens under it",
            listOf("PARAGRAPH", "IMAGE", "PARAGRAPH"),
            stored.map { it["kind"]!!.jsonPrimitive.content },
        )
        assertEquals("media-42", stored[1]["media"]!!.jsonPrimitive.content)
        assertEquals("a loom in Kotpad", textOf(stored[1]))
    }

    @Test
    fun `Enter never produces a second block holding the same photograph`() {
        val doc = RichDoc(listOf(image("media-7", "detail")))

        val stored = storedBlocks(splitBlock(doc, collapsedAt(DocPoint(0, 6))).doc)

        assertEquals("exactly one block still names the picture", 1, stored.count { it["media"] != null })
        assertEquals("the caret's new home is an ordinary paragraph", "PARAGRAPH", stored[1]["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Enter in a caption leaves the caret in the new paragraph`() {
        val doc = RichDoc(listOf(image("media-7", "detail")))

        val result = splitBlock(doc, collapsedAt(DocPoint(0, 6)))

        assertEquals(DocPoint(1, 0), result.selection.focus)
    }

    @Test
    fun `Backspace at the start of a caption removes the whole figure, not just the picture`() {
        val doc = RichDoc(listOf(para("before"), image("media-42", "a loom"), para("after")))

        val result = deleteBackward(doc, collapsedAt(DocPoint(1, 0)))
        val stored = storedBlocks(result.doc)

        assertEquals("the figure has gone entirely", listOf("PARAGRAPH", "PARAGRAPH"), stored.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals(listOf("before", "after"), stored.map { textOf(it) })
    }

    @Test
    fun `Backspace at the start of a caption never demotes the block to a paragraph`() {
        val doc = RichDoc(listOf(para("before"), image("media-42", "a loom")))

        val stored = storedBlocks(deleteBackward(doc, collapsedAt(DocPoint(1, 0))).doc)

        assertTrue(
            "no block may be left carrying the caption without the picture",
            stored.none { it["kind"]!!.jsonPrimitive.content == "PARAGRAPH" && textOf(it) == "a loom" },
        )
    }

    @Test
    fun `Backspace lands the caret at the end of the block above`() {
        val doc = RichDoc(listOf(para("before"), image("media-42", "a loom")))

        val result = deleteBackward(doc, collapsedAt(DocPoint(1, 0)))

        assertEquals(DocPoint(0, "before".length), result.selection.focus)
    }

    @Test
    fun `removing the only block leaves one empty paragraph rather than nothing`() {
        val result = removeImage(RichDoc(listOf(image("media-1", ""))), 0)

        assertEquals(1, result.doc.blocks.size)
        assertEquals(BlockKind.PARAGRAPH, result.doc.blocks[0].kind)
        assertEquals(DocPoint(0, 0), result.selection.focus)
    }

    @Test
    fun `removeImage refuses to act on a block that is not a photograph`() {
        val doc = RichDoc(listOf(para("keep me")))

        val result = removeImage(doc, 0)

        assertEquals("the document is untouched", 1, result.doc.blocks.size)
        assertEquals("keep me", result.doc.blocks[0].text)
    }

    @Test
    fun `removeImage clamps a caret that names a block outside the document`() {
        val doc = RichDoc(listOf(para("only")))

        val result = removeImage(doc, 9)

        assertEquals(0, result.selection.focus.block)
    }

    @Test
    fun `Backspace at the start of a paragraph below a table does not feed it into the grid`() {
        val doc = RichDoc(listOf(table("Warp cost"), para("a sentence")))

        val result = deleteBackward(doc, collapsedAt(DocPoint(1, 0)))
        val stored = storedBlocks(result.doc)

        assertEquals(listOf("TABLE", "PARAGRAPH"), stored.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals("a sentence", textOf(stored[1]))
        assertNotNull("the grid is untouched", stored[0]["rows"])
    }

    @Test
    fun `forward delete refuses every merge in which either side is structural`() {
        val cases = listOf(
            "paragraph into image" to RichDoc(listOf(para("text"), image("m", "cap"))),
            "image into paragraph" to RichDoc(listOf(image("m", "cap"), para("text"))),
            "paragraph into table" to RichDoc(listOf(para("text"), table("cell"))),
            "table into paragraph" to RichDoc(listOf(table("cell"), para("text"))),
        )
        for ((name, doc) in cases) {
            val caretAtEnd = collapsedAt(DocPoint(0, doc.blocks[0].text.length))
            assertEquals("$name: both blocks survive", kinds(doc), kinds(deleteForward(doc, caretAtEnd).doc))
        }
    }

    @Test
    fun `forward delete still merges two ordinary paragraphs`() {
        val doc = RichDoc(listOf(para("one"), para("two")))

        val result = deleteForward(doc, collapsedAt(DocPoint(0, 3)))

        assertEquals(1, result.doc.blocks.size)
        assertEquals("onetwo", result.doc.blocks[0].text)
    }

    @Test
    fun `typing in a caption keeps the picture and the caption's marks`() {
        val doc = RichDoc(
            listOf(
                RichBlock(
                    kind = BlockKind.IMAGE,
                    spans = listOf(RichSpan("a loom", marks = setOf(Mark.BOLD))),
                    media = "media-42",
                    widthPct = 60f,
                )
            )
        )

        val result = insertText(doc, collapsedAt(DocPoint(0, 6)), " in Kotpad", listOf("BOLD"))
        val stored = storedBlocks(result.doc)[0]

        assertEquals("IMAGE", stored["kind"]!!.jsonPrimitive.content)
        assertEquals("media-42", stored["media"]!!.jsonPrimitive.content)
        assertEquals("a loom in Kotpad", textOf(stored))
        assertEquals(1, stored["spans"]!!.jsonArray.size)
    }

    @Test
    fun `a photograph with no caption still counts as content`() {
        val doc = RichDoc(listOf(image("media-42", "")))

        assertTrue("the document is not empty", !doc.isEmpty)
        assertEquals("media-42", storedBlocks(doc)[0]["media"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a photograph is placed after the caret's block and never splits the sentence`() {
        val doc = RichDoc(listOf(para("The loom stands in the courtyard."), para("after")))

        val result = insertImage(doc, collapsedAt(DocPoint(0, 4)), "media-42", "The loom")

        assertEquals(
            "the sentence is intact and the figure is under it",
            listOf("The loom stands in the courtyard.", "The loom", "after"),
            result.doc.blocks.map { it.text },
        )
        assertEquals(BlockKind.IMAGE, result.doc.blocks[1].kind)
        assertEquals(3, result.doc.blocks.size)
    }

    @Test
    fun `the caret lands at the end of the new caption`() {
        val result = insertImage(RichDoc(listOf(para("intro"))), collapsedAt(DocPoint(0, 5)), "m", "a loom")

        assertEquals(DocPoint(1, "a loom".length), result.selection.focus)
    }

    @Test
    fun `a blank media id is refused rather than inserted as a placeholder`() {
        val doc = RichDoc(listOf(para("intro")))

        assertEquals(doc, insertImage(doc, collapsedAt(DocPoint(0, 0)), "   ").doc)
        assertEquals(doc, insertImage(doc, collapsedAt(DocPoint(0, 0)), "").doc)
    }

    @Test
    fun `a caption's newline becomes a space rather than a second paragraph`() {
        val result = insertImage(RichDoc(listOf(para("x"))), collapsedAt(DocPoint(0, 1)), "m", "warp\nweft")

        assertEquals("warp weft", result.doc.blocks[1].text)
    }

    @Test
    fun `an over-long media id is truncated to what the parser will keep`() {
        val long = "m".repeat(200)

        val placed = insertImage(RichDoc(listOf(para("x"))), collapsedAt(DocPoint(0, 0)), long)

        assertEquals(64, placed.doc.blocks[1].media.length)
        assertEquals(placed.doc.blocks[1].media, fromJson(toJson(placed.doc)).blocks[1].media)
    }

    @Test
    fun `a placed photograph survives the round trip through storage`() {
        val placed = insertImage(
            RichDoc(listOf(para("The dye vat was rebuilt in March."))),
            collapsedAt(DocPoint(0, 33)),
            "9f3c1b7e-0000-4a2b-9c1d-000000000042",
            "The rebuilt vat",
            widthPct = 40f,
        ).doc

        val reread = fromJson(toJson(placed))

        assertEquals("the block count is unchanged", placed.blocks.size, reread.blocks.size)
        val figure = reread.blocks[1]
        assertEquals(BlockKind.IMAGE, figure.kind)
        assertEquals("9f3c1b7e-0000-4a2b-9c1d-000000000042", figure.media)
        assertEquals("The rebuilt vat", figure.text)
        assertEquals(40f, figure.widthPct, 0.001f)
    }

    @Test
    fun `a placed photograph reaches the report as an ImageBlock at the width it was given`() {
        val placed = insertImage(
            RichDoc(listOf(para("Before."), para("After."))),
            collapsedAt(DocPoint(0, 7)),
            "media-42",
            "A loom in Kotpad",
            widthPct = 40f,
        ).doc

        val blocks = toReportBlocks(
            doc = fromJson(toJson(placed)),
            resolveMedia = { id ->
                if (id == "media-42") {
                    ImageRef(source = "/data/media/42.jpg", widthPx = 1600, heightPx = 1200)
                } else {
                    null
                }
            },
        )

        val figure = blocks.filterIsInstance<ImageBlock>().single()
        assertEquals("/data/media/42.jpg", figure.image.source)
        assertEquals("A loom in Kotpad", figure.caption)
        assertEquals(40f, figure.widthPct, 0.001f)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is ParagraphBlock)
        assertTrue(blocks[2] is ParagraphBlock)
    }

    @Test
    fun `a photograph the resolver cannot find leaves an honest gap, caption and all`() {
        val doc = RichDoc(listOf(para("Before."), image("server-side-id", "A loom in Kotpad")))

        val blocks = toReportBlocks(doc = doc, resolveMedia = { null })

        assertTrue("no figure is printed", blocks.filterIsInstance<ImageBlock>().isEmpty())
        assertEquals("and no orphaned caption either", 1, blocks.size)
    }

    @Test
    fun `the width steps and stops at the bounds the parser enforces`() {
        val doc = RichDoc(listOf(para("x"), image("m", "c")))

        assertEquals(50f, setImageWidth(doc, 1, -10f).doc.blocks[1].widthPct, 0.001f)
        assertEquals(100f, setImageWidth(doc, 1, 900f).doc.blocks[1].widthPct, 0.001f)
        assertEquals(10f, setImageWidth(doc, 1, -900f).doc.blocks[1].widthPct, 0.001f)
    }

    @Test
    fun `the two width controls step by the web's step and land on the web's ladder`() {
        assertEquals(15f, IMAGE_WIDTH_STEP_PCT, 0.001f)

        val placed = insertImage(
            RichDoc(listOf(para("x"))),
            collapsedAt(DocPoint(0, 1)),
            "media-42",
        ).doc
        assertEquals("placed at the shared default", 70f, placed.blocks[1].widthPct, 0.001f)

        val wider = setImageWidth(placed, 1, IMAGE_WIDTH_STEP_PCT).doc
        assertEquals(85f, wider.blocks[1].widthPct, 0.001f)
        assertEquals(
            "and the second press reaches the ceiling exactly, as it does on the web",
            100f,
            setImageWidth(wider, 1, IMAGE_WIDTH_STEP_PCT).doc.blocks[1].widthPct,
            0.001f,
        )

        val narrower = setImageWidth(placed, 1, -IMAGE_WIDTH_STEP_PCT).doc
        assertEquals(55f, narrower.blocks[1].widthPct, 0.001f)
        assertEquals(40f, setImageWidth(narrower, 1, -IMAGE_WIDTH_STEP_PCT).doc.blocks[1].widthPct, 0.001f)
    }

    @Test
    fun `the width command refuses to touch prose`() {
        val doc = RichDoc(listOf(para("keep me")))

        assertEquals(doc, setImageWidth(doc, 0, 10f).doc)
        assertEquals("and an index outside the document is a no-op too", doc, setImageWidth(doc, 9, 10f).doc)
    }
}
