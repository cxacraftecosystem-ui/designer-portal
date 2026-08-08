package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.Mark
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.report.toJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A KEYSTROKE must never destroy an inline photograph.
 *
 * `RichTextStructuralGuardTest` pins the two TOOLBAR commands (`clearFormatting`, `setBlockKind`).
 * This is the other half, and it is the half a designer reaches by accident: the two keys the editor
 * routes into the model itself — Enter through `splitBlock` and Backspace-at-offset-0 through
 * `deleteBackward` — both used to take a photograph with them.
 *
 * ── WHY THIS IS REACHABLE ON A HANDSET AT ALL ─────────────────────────────────────────────────
 *
 * The phone cannot yet PLACE a photograph inside prose; only the web editor can. It can very easily
 * be handed one: a designer sets a stage up in a browser, drops a picture of the loom into the
 * narrative, and the workshop is then opened on the M32 in a courtyard. That IMAGE block arrives in
 * the editor as an ordinary-looking line of text — its spans are the CAPTION — so:
 *
 *  * **Backspace at the start of that line** fell through the ladder's demotion rung and re-kinded
 *    the block to PARAGRAPH. [toJson] writes `media` ONLY for an IMAGE block, so the next auto-save
 *    omitted it: the photograph was gone from the record on every surface, permanently, and what was
 *    left behind was the caption, sitting under nothing, looking like a picture that failed to load.
 *  * **Enter anywhere in that caption** took the generic split, whose continuation block is built as
 *    `RichBlock(kind = rightKind, …)` with no `media` — an IMAGE carrying an empty id, which both
 *    `fromJson` and the server's `from_json` DROP on the next read. The second half of the caption
 *    survived on screen until the stage was reopened, and then was not there.
 *
 * Neither gesture looks destructive and neither showed anything on screen. The report is generated
 * at the close of the workshop and handed to a visiting ministry officer, which is where a designer
 * would have discovered that the photograph they placed in the narrative is not in the document.
 *
 * ── WHY THE ASSERTIONS GO THROUGH [toJson] ────────────────────────────────────────────────────
 *
 * The same reason the sibling test gives: an in-memory block keeps its `media` whatever its kind, so
 * a test that read `doc.blocks[i].media` would PASS against the bug. The loss happens on the way to
 * storage, so that is where it has to be observed.
 */
class RichTextInlineImageTest {

    private fun para(text: String) = RichBlock(
        kind = BlockKind.PARAGRAPH,
        spans = listOf(RichSpan(text)),
    )

    /** A photograph with a caption, as the web editor writes one. */
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

    // ── Enter, in a caption ──────────────────────────────────────────────────────────────────

    @Test
    fun `Enter in the middle of a caption keeps the picture and loses no text`() {
        val doc = RichDoc(listOf(para("before"), image("media-42", "a loom in Kotpad")))

        // The caret halfway through the caption, which is the case that used to lose its tail.
        val result = splitBlock(doc, collapsedAt(DocPoint(1, 6)))
        val stored = storedBlocks(result.doc)

        assertEquals(
            "the figure survives and a plain paragraph opens under it",
            listOf("PARAGRAPH", "IMAGE", "PARAGRAPH"),
            stored.map { it["kind"]!!.jsonPrimitive.content },
        )
        assertEquals("media-42", stored[1]["media"]!!.jsonPrimitive.content)
        // THE WHOLE CAPTION IS STILL THERE. The generic split would have cut it at offset 6 and put
        // "in Kotpad" into a block that the next read drops, so this is the assertion that fails
        // against the bug rather than merely describing the fix.
        assertEquals("a loom in Kotpad", textOf(stored[1]))
    }

    @Test
    fun `Enter never produces a second block holding the same photograph`() {
        val doc = RichDoc(listOf(image("media-7", "detail")))

        val stored = storedBlocks(splitBlock(doc, collapsedAt(DocPoint(0, 6))).doc)

        // Copying `media` across the split would have been the other obvious way to write this arm,
        // and it prints the loom twice in the report from one press of Enter.
        assertEquals("exactly one block still names the picture", 1, stored.count { it["media"] != null })
        assertEquals("the caret's new home is an ordinary paragraph", "PARAGRAPH", stored[1]["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Enter in a caption leaves the caret in the new paragraph`() {
        val doc = RichDoc(listOf(image("media-7", "detail")))

        val result = splitBlock(doc, collapsedAt(DocPoint(0, 6)))

        // Not cosmetic: the editor asks the block at `selection.focus.block` for focus, and a caret
        // left in the caption means Enter appears to do nothing at all.
        assertEquals(DocPoint(1, 0), result.selection.focus)
    }

    // ── Backspace, at the start of a caption ─────────────────────────────────────────────────

    @Test
    fun `Backspace at the start of a caption removes the whole figure, not just the picture`() {
        val doc = RichDoc(listOf(para("before"), image("media-42", "a loom"), para("after")))

        val result = deleteBackward(doc, collapsedAt(DocPoint(1, 0)))
        val stored = storedBlocks(result.doc)

        assertEquals("the figure has gone entirely", listOf("PARAGRAPH", "PARAGRAPH"), stored.map { it["kind"]!!.jsonPrimitive.content })
        // The caption must not survive the picture. Against the bug this list was
        // ["before", "a loom", "after"] — the caption left stranded as a paragraph, which reads as a
        // photograph that failed to load rather than one the designer deleted.
        assertEquals(listOf("before", "after"), stored.map { textOf(it) })
    }

    @Test
    fun `Backspace at the start of a caption never demotes the block to a paragraph`() {
        // The narrow statement of the defect: a surviving block whose kind is PARAGRAPH is the bug,
        // because `toJson` then omits `media` and the record loses the photograph on the next save.
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
        // A document with no blocks has nowhere to put a caret, and the next keystroke indexes past
        // the end of the block list.
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
        // Compose maps a model point onto a `TextRange` in a real field; an off-the-end point is an
        // IndexOutOfBoundsException on the composition thread, which looks to a designer like the
        // app closing while they type rather than like a bad caret.
        val doc = RichDoc(listOf(para("only")))

        val result = removeImage(doc, 9)

        assertEquals(0, result.selection.focus.block)
    }

    // ── Backspace and Delete around a table ──────────────────────────────────────────────────

    @Test
    fun `Backspace at the start of a paragraph below a table does not feed it into the grid`() {
        val doc = RichDoc(listOf(table("Warp cost"), para("a sentence")))

        val result = deleteBackward(doc, collapsedAt(DocPoint(1, 0)))
        val stored = storedBlocks(result.doc)

        // Nothing reads a TABLE block's spans, so the merge that used to happen here deleted the
        // sentence from the screen AND from the file to one press of Backspace.
        assertEquals(listOf("TABLE", "PARAGRAPH"), stored.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals("a sentence", textOf(stored[1]))
        assertNotNull("the grid is untouched", stored[0]["rows"])
    }

    @Test
    fun `forward delete refuses every merge in which either side is structural`() {
        // All four combinations, because the merge writes its result into the block the caret is in
        // and `toJson` carries `media` and `rows` only for the kind that owns them — so whichever
        // side is structural is the side that is lost.
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
        // The control for the guard above: written as `isStructural` rather than as four `!=` tests,
        // so a blanket refusal would be the easy mistake and this is what catches it.
        val doc = RichDoc(listOf(para("one"), para("two")))

        val result = deleteForward(doc, collapsedAt(DocPoint(0, 3)))

        assertEquals(1, result.doc.blocks.size)
        assertEquals("onetwo", result.doc.blocks[0].text)
    }

    // ── The ordinary prose path through a caption still works ────────────────────────────────

    @Test
    fun `typing in a caption keeps the picture and the caption's marks`() {
        // The point of the model's shape: a caption IS prose, so nothing about editing one needs an
        // image-specific path. This is the assertion that the guards above did not achieve their
        // safety by making the block read-only.
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
        // One span, not two: `mergeSpans` fuses the inserted run into the identical-marked one
        // before it, which is invariant 2 and is what keeps a narrative from reaching the .docx
        // writer as several thousand runs.
        assertEquals(1, stored["spans"]!!.jsonArray.size)
    }

    @Test
    fun `a photograph with no caption still counts as content`() {
        // `isEmpty` is what the completeness gate reads. Judging an IMAGE on its text would report a
        // field whose only content is a photograph as unfilled, and the designer would be told they
        // had left a required narrative blank while a picture of the loom sat in it.
        val doc = RichDoc(listOf(image("media-42", "")))

        assertTrue("the document is not empty", !doc.isEmpty)
        assertEquals("media-42", storedBlocks(doc)[0]["media"]!!.jsonPrimitive.content)
    }
}
