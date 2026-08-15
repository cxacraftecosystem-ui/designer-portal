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

/**
 * What the rich-text field PUBLISHES, and the photograph that used to fall through the gap.
 *
 * ── THE DEFECT THIS FILE EXISTS FOR ───────────────────────────────────────────────────────────
 *
 * `isEmptyDoc` was `doc.blocks.all { isJsBlank(it.text) }` — text only, no IMAGE arm — while the
 * model it judges (`RichBlock.isEmpty`, report/RichText.kt), the server (`RichBlock.is_empty`) and
 * the browser (`frontend/lib/richText.ts`) all judge an IMAGE on its MEDIA ID. An IMAGE block's
 * `text` is its CAPTION, so a photograph nobody captioned was, to the handset alone, nothing at
 * all. `RichTextEditor` asks that one predicate both of the questions it has about an edit — "is
 * this different from what I last published" (`signatureOf`) and "what do I hand to `onChange`" —
 * so the wrong answer arrived twice, in the two ways that both lose content:
 *
 *  * **Placing a photograph into a blank narrative published NOTHING.** Empty field signs "EMPTY";
 *    the picture goes in; the new document also signs "EMPTY"; `emit` returns at its guard and
 *    `onChange` is never called. The bytes were imported, the descriptor registered, the toast said
 *    "Photograph placed." — and the field value stayed null, so no report annexure and no page ever
 *    referenced the picture (`renderMediaAnnexure` gathers plates from FIELD VALUES).
 *  * **Clearing the caption of an image-only narrative published `null`.** Which `StageScreen`'s
 *    `put` implements as `this - key`: the field leaves the payload and the stored document goes
 *    with it at the next save. One press of Backspace, no warning, no way back.
 *
 * ── WHY THE ASSERTIONS ARE AT THIS LEVEL ──────────────────────────────────────────────────────
 *
 * `emit` is a local function inside a `@Composable`, so it cannot be called from a JVM test; what
 * CAN be is the pair it now delegates to, `docSignature` and `emittedValue` (RichTextOps.kt), which
 * are the whole of its decision — the composable adds only `lastEmitted`, a `String` this file
 * keeps in a `var` exactly as the editor keeps it in a `remember`. Every test below therefore walks
 * the real sequence: seed a document, remember its signature, apply the same op the editor applies,
 * and ask what the editor would have published. A test that only asserted `!isEmptyDoc(doc)` would
 * pass against a fix that never reached `emit`.
 *
 * `emptyDoc()` stands in for `forEditing(fromJson(null))`, which is private to the editor and is
 * `if (doc.blocks.isEmpty()) emptyDoc() else doc` — for a null value prop those are the same
 * document, and it is the document the field opens with.
 */
class RichTextEmitContractTest {

    private fun para(text: String) = RichBlock(kind = BlockKind.PARAGRAPH, spans = listOf(RichSpan(text)))

    /** A photograph with a caption, as the web editor writes one. */
    private fun image(mediaId: String, caption: String) = RichBlock(
        kind = BlockKind.IMAGE,
        spans = if (caption.isEmpty()) emptyList() else listOf(RichSpan(caption)),
        media = mediaId,
        widthPct = 60f,
    )

    /** The blocks of what the editor would publish, or `null` if it would publish `null`. */
    private fun publishedBlocks(doc: RichDoc) =
        emittedValue(doc)?.get("blocks")?.jsonArray?.map { it.jsonObject }

    // ── Path A: placing a photograph into a field that was blank ─────────────────────────────

    @Test
    fun `placing a photograph in a blank field publishes a document`() {
        // The field as it opens on a stage whose narrative has never been written: value prop null.
        val opened = emptyDoc()
        val lastEmitted = docSignature(opened)
        assertEquals("the blank field starts out signed as empty", EMPTY_DOC_SIGNATURE, lastEmitted)

        // The designer taps Photograph and picks a picture. `DwMediaBridge.attach` has already
        // copied the bytes in and handed back this id; NO CAPTION IS TYPED, because the caption is
        // the line the toast invites them to write AFTERWARDS.
        val placed = insertImage(opened, collapsedAt(DocPoint(0, 0)), "media-42").doc

        // THE ASSERTION THAT FAILS AGAINST THE BUG. Both signatures were "EMPTY", `emit` returned
        // at `if (signature == lastEmitted)`, and the picture was never handed to the form.
        assertNotEquals(
            "the document must not still sign as empty once it holds a photograph",
            lastEmitted,
            docSignature(placed),
        )

        val published = publishedBlocks(placed)
        assertNotNull("the field must publish a document, not null", published)
        val figure = published!!.single { it["kind"]!!.jsonPrimitive.content == "IMAGE" }
        // And the id has to be IN what is published: the report resolves plates from field values,
        // so an emitted document that dropped the id would be the same silent loss one layer down.
        assertEquals("media-42", figure["media"]!!.jsonPrimitive.content)
    }

    @Test
    fun `placing a photograph mid-narrative publishes it too`() {
        // The same defect with prose already present is invisible — the paragraph alone makes the
        // document non-empty, so this case always worked. It is here as the control: a "fix" that
        // simply always publishes would pass the test above and fail nothing, and this pins that
        // the ordinary path is unchanged rather than newly special-cased.
        val doc = RichDoc(listOf(para("The loom stands in the courtyard.")))
        val lastEmitted = docSignature(doc)

        val placed = insertImage(doc, collapsedAt(DocPoint(0, 4)), "media-42").doc

        assertNotEquals(lastEmitted, docSignature(placed))
        assertEquals(
            listOf("PARAGRAPH", "IMAGE"),
            publishedBlocks(placed)!!.map { it["kind"]!!.jsonPrimitive.content },
        )
    }

    // ── Path B: editing the caption of a narrative that is only a photograph ─────────────────

    @Test
    fun `clearing the caption of an image-only field does not publish null`() {
        // A field whose whole content is one captioned photograph — the shape a designer reaches by
        // placing a picture and describing it, and the shape the web writes for the same gesture.
        val doc = RichDoc(listOf(image("media-42", "a loom")))
        val lastEmitted = docSignature(doc)
        assertNotEquals("a captioned photograph is content", EMPTY_DOC_SIGNATURE, lastEmitted)

        // They select the caption and delete it, meaning to retype it.
        val cleared = deleteRange(doc, DocRange(DocPoint(0, 0), DocPoint(0, "a loom".length))).doc

        // AGAINST THE BUG THIS WAS `null`, and `null` is not "no change" downstream — StageScreen's
        // `put` removes the key from the payload, so the next save wrote a payload with no entry for
        // this field and the stored document, photograph and all, was gone.
        val published = publishedBlocks(cleared)
        assertNotNull("clearing a caption must never null the field", published)
        assertEquals("media-42", published!!.single()["media"]!!.jsonPrimitive.content)
        assertEquals("IMAGE", published.single()["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `backspacing away the last character of a caption does not publish null`() {
        // The same loss reached one keystroke at a time rather than by selecting — the way it
        // actually happens, since a one-word caption is usually corrected, not replaced.
        val doc = RichDoc(listOf(image("media-42", "x")))

        val cleared = deleteBackward(doc, collapsedAt(DocPoint(0, 1))).doc

        assertEquals("the caption really is gone", "", cleared.blocks.single().text)
        assertNotNull("but the photograph is not", publishedBlocks(cleared))
        assertEquals("media-42", cleared.blocks.single().media)
    }

    // ── The other side of the predicate, which must not be widened by the fix ────────────────

    @Test
    fun `an untouched field still publishes null`() {
        // The rule the fix must not break: an editor that was opened, focused and left alone holds
        // one empty paragraph, and publishing `{"blocks":[…]}` for it would mark an unanswered
        // required narrative as filled — the completeness gate would read 100% on a report with a
        // blank introduction, which is the reason `isEmptyDoc` exists at all.
        assertNull(emittedValue(emptyDoc()))
        assertNull(emittedValue(RichDoc(listOf(para("   ")))))
        assertEquals(EMPTY_DOC_SIGNATURE, docSignature(emptyDoc()))
    }

    @Test
    fun `an image block that lost its media id is still an empty document`() {
        // The IMAGE arm reads the id, not the kind. A block claiming to be a photograph while
        // naming none is dropped by `fromJson` AND by the server's `from_json`, so treating it as
        // content would publish a document that reads back as empty — a field that looks filled on
        // this screen and blank on the next one.
        assertTrue(isEmptyDoc(RichDoc(listOf(RichBlock(kind = BlockKind.IMAGE)))))
        assertNull(emittedValue(RichDoc(listOf(RichBlock(kind = BlockKind.IMAGE)))))
    }

    @Test
    fun `the text arm still trims the way JavaScript trims, not the way Kotlin does`() {
        // WHY `isEmptyDoc` DELEGATES ONLY ITS IMAGE ARM TO `RichBlock.isEmpty` INSTEAD OF BECOMING
        // `doc.isEmpty` OUTRIGHT. `RichBlock.isEmpty` uses Kotlin's `String.isBlank`, which is
        // `Character.isWhitespace || isSpaceChar`; U+FEFF is category Cf and is neither, while
        // `String.prototype.trim` — which the browser and the server apply — does strip it. The two
        // lines below are that measurement, run rather than asserted from memory: swap the text arm
        // and a field holding one pasted byte-order mark counts as filled on the handset and blank
        // in the browser, for the same stored bytes.
        val bom = Char(0xFEFF).toString()
        assertFalse("Kotlin does not consider U+FEFF blank", bom.isBlank())
        assertTrue("JavaScript's trim does", isJsBlank(bom))

        assertTrue("so a pasted byte-order mark is an empty document here too", isEmptyDoc(RichDoc(listOf(para(bom)))))
        assertNull(emittedValue(RichDoc(listOf(para(Char(0xA0).toString())))))
    }
}
