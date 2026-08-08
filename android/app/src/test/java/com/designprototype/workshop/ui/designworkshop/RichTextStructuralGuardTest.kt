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

/**
 * A prose command must never destroy an inline photograph or a table.
 *
 * THE DEFECT THIS PINS, WHICH WAS REACHABLE FROM A TOOLBAR PRESS. `clearFormatting` ended every
 * block it touched with `.copy(kind = PARAGRAPH, ...)` unconditionally, and `setBlockKindIn` did the
 * same. [toJson] writes `media` ONLY for an IMAGE block and `rows` ONLY for a TABLE block — so the
 * moment either was re-kinded, the next save omitted the field and the photograph or the whole grid
 * was gone from the record, on every surface, permanently. The gesture that did it was not a
 * destructive-looking one: "Clear formatting" pressed to strip a stray bold run out of a caption, or
 * the Paragraph button pressed over a selection that ran through a table on its way to a sentence
 * three blocks away. `frontend/lib/richText.ts` has guarded this since it was written
 * (`isStructuralKind`, applied in both commands); Android had no equivalent and no test.
 *
 * THE ASSERTIONS GO THROUGH [toJson] ON PURPOSE. The in-memory block keeps its `rows`/`media`
 * whatever its kind, so a test that only inspected `doc.blocks[i].media` would PASS against the bug.
 * The loss happens on the way to storage, so that is where it has to be observed.
 */
class RichTextStructuralGuardTest {

    private fun para(text: String) = RichBlock(
        kind = BlockKind.PARAGRAPH,
        spans = listOf(RichSpan(text)),
    )

    /** An inline photograph whose caption carries a mark, which is what a designer would clear. */
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

    /**
     * The whole document, selected from the first character to past the end of the last block.
     *
     * The far offset is deliberately larger than any block here and left to `clampRange`: a range
     * that collapsed on the last block would still reset its KIND (the command has no `to <= from`
     * guard, by design) while never touching a single mark — so the mark assertions would pass
     * without the code that clears marks ever running.
     */
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

        // The marks DO come off the caption — that is what the designer actually pressed the button
        // for, and a guard that skipped the block entirely would be the opposite bug.
        val captionSpans = stored[1]["spans"]!!.jsonArray.map { it.jsonObject }
        assertTrue("the caption keeps its text", captionSpans.any { it["text"]!!.jsonPrimitive.content == "a loom" })
        assertTrue("the caption's marks are cleared", captionSpans.all { it["marks"] == null })

        // The ordinary prose either side is still re-kinded, so the command still does its job.
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

        // The Heading button, pressed over a selection that runs through both structural blocks.
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
        // Guarding by SKIPPING the whole block would have been the easy fix and the wrong one: it
        // would leave a heading a heading. This is the control for that mistake.
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
        // THE SAME GUARD FROM THE OTHER SIDE: the sibling test protects the SOURCE block, this one
        // protects the TARGET kind. It is a deliberate divergence from the web and not parity —
        // `RichBlockKind` (richText.ts:84) includes "TABLE" and "IMAGE", so `setBlockKind(doc,
        // range, "TABLE")` type-checks in the browser too and the web has the same hole. Both are
        // saved from it only by the fact that no control passes either token; this port takes a
        // STRING (the toolbar reports a press as one) and `KIND_BY_NAME` has resolved both ever
        // since the constants were added to stop the phone destroying a photograph.
        //
        // A TABLE with no `rows` and an IMAGE with no `media` are both blocks that `toJson` writes
        // and `fromJson` then DROPS — "a table with nothing in it is not a table". The designer's
        // sentence stayed on screen until the stage was reopened and then was not there.
        val doc = RichDoc(listOf(para("a sentence"), para("another")))

        for (kind in listOf("TABLE", "IMAGE")) {
            val result = setBlockKind(doc, wholeDoc(doc), kind, 0)
            assertEquals("$kind: the prose is left alone", doc, result.doc)
            // And the round trip still holds, which is the loss the assertion above is standing in
            // for: a document whose blocks became empty structural ones comes back short.
            assertEquals(
                "$kind: nothing is dropped on the next read",
                2,
                storedBlocks(result.doc).size,
            )
        }
    }

    @Test
    fun `the guard on the target kind is not a blanket refusal`() {
        // The control. Refusing every `setBlockKind` would be the easy over-correction and would
        // leave the toolbar's five kind buttons doing nothing at all.
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
        // If a third such kind is ever added, this fails and points at the guard that must learn it.
        val structural = BlockKind.values().filter { it.isStructural }.toSet()
        assertEquals(setOf(BlockKind.TABLE, BlockKind.IMAGE), structural)
    }
}
