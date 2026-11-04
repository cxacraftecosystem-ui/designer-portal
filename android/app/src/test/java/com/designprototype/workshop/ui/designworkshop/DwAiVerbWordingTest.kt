package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DW_AI_VERBS_SPENT
import com.designprototype.workshop.data.DW_AI_VERB_COUNTDOWN_FROM
import com.designprototype.workshop.data.DW_VERBS_NEED_A_CONNECTION
import com.designprototype.workshop.data.DW_VERBS_NOTHING_SELECTED
import com.designprototype.workshop.data.DwAiVerbCapRefused
import com.designprototype.workshop.data.DwAiVerbCapView
import com.designprototype.workshop.data.DwAiVerbRefused
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DwAiVerbWordingTest {

    private fun paragraph(text: String) = RichBlock(spans = listOf(RichSpan(text)))

    private val doc = RichDoc(
        listOf(
            paragraph("The dabu printers of Bagru mix gum and clay."),
            paragraph("Second paragraph, untouched."),
        )
    )

    @Test
    fun `a collapsed caret means the whole paragraph it is in`() {
        val at = collapsedAt(DocPoint(0, 12))
        assertEquals("The dabu printers of Bagru mix gum and clay.", dwVerbPassageOf(doc, at))
    }

    @Test
    fun `a collapsed caret in the second paragraph does not reach the first`() {
        val at = collapsedAt(DocPoint(1, 0))
        assertEquals("Second paragraph, untouched.", dwVerbPassageOf(doc, at))
    }

    @Test
    fun `a selection inside one paragraph is used verbatim and is not widened`() {
        val range = DocRange(DocPoint(0, 4), DocPoint(0, 17))
        assertEquals("dabu printers", dwVerbPassageOf(doc, range))
    }

    @Test
    fun `a selection dragged upward gives the same passage as the same one dragged down`() {
        val down = DocRange(DocPoint(0, 4), DocPoint(0, 17))
        val up = DocRange(DocPoint(0, 17), DocPoint(0, 4))
        assertEquals(dwVerbPassageOf(doc, down), dwVerbPassageOf(doc, up))
    }

    @Test
    fun `a range naming a block that is not there answers empty rather than throwing`() {
        assertEquals("", dwVerbPassageOf(RichDoc(emptyList()), collapsedAt(DocPoint(7, 3))))
    }

    @Test
    fun `a table contributes its cells and an image block contributes its caption`() {
        val table = RichBlock(
            kind = BlockKind.TABLE,
            rows = listOf(
                listOf(listOf(RichSpan("Stage")), listOf(RichSpan("Cost"))),
                listOf(listOf(RichSpan("Dyeing")), listOf(RichSpan("240"))),
            ),
        )
        val image = RichBlock(
            kind = BlockKind.IMAGE,
            media = "media-1",
            spans = listOf(RichSpan("The loom in the courtyard.")),
        )
        val withBoth = RichDoc(listOf(table, image))
        assertEquals("Stage\tCost\nDyeing\t240", dwVerbPassageOf(withBoth, collapsedAt(DocPoint(0, 0))))
        assertEquals("The loom in the courtyard.", dwVerbPassageOf(withBoth, collapsedAt(DocPoint(1, 0))))
    }

    @Test
    fun `the preview collapses newlines and marks its own truncation`() {
        val short = dwVerbPassagePreview("Two   lines\nof prose")
        assertEquals("Two lines of prose", short)

        val long = dwVerbPassagePreview("word ".repeat(200))
        assertTrue("a truncated preview must say so", long.endsWith("…"))
        assertTrue(long.length <= DW_VERB_PREVIEW_CHARS + 1)

        val narrative = dwVerbPassagePreview("क ".repeat(10_000))
        assertTrue(narrative, narrative.length <= DW_VERB_PREVIEW_CHARS + 1)
        assertTrue(narrative.endsWith("…"))
        assertTrue(narrative, !narrative.contains(" …"))
        val ragged = "\n  one \t\t two   \n"
        assertEquals("one two", dwVerbPassagePreview(ragged))
        assertEquals("", dwVerbPassagePreview(" \n\t "))
        assertEquals("अ".repeat(DW_VERB_PREVIEW_CHARS), dwVerbPassagePreview("अ".repeat(DW_VERB_PREVIEW_CHARS)))
        assertTrue(dwVerbPassagePreview("अ".repeat(DW_VERB_PREVIEW_CHARS + 1)).endsWith("…"))
    }

    @Test
    fun `the no-paragraph sentence is not the browser's and names the cursor`() {
        assertTrue(DW_NO_PARAGRAPH_TO_WORK_ON.contains("cursor"))
        assertTrue(DW_NO_PARAGRAPH_TO_WORK_ON.contains("paragraph"))
        assertNotEquals(DW_VERBS_NOTHING_SELECTED, DW_NO_PARAGRAPH_TO_WORK_ON)
    }

    @Test
    fun `each media type is offered exactly the verbs the server accepts`() {
        assertEquals(setOf(MEDIA_VERB_CAPTION), dwMediaVerbsFor("IMAGE"))
        assertEquals(setOf(MEDIA_VERB_SUBTITLES), dwMediaVerbsFor("AUDIO"))
        assertEquals(setOf(MEDIA_VERB_CAPTION, MEDIA_VERB_SUBTITLES), dwMediaVerbsFor("VIDEO"))
    }

    @Test
    fun `a document and a pdf are offered nothing at all`() {
        assertEquals(emptySet<String>(), dwMediaVerbsFor("PDF"))
        assertEquals(emptySet<String>(), dwMediaVerbsFor("DOCUMENT"))
        assertEquals(emptySet<String>(), dwMediaVerbsFor(null))
        assertEquals(emptySet<String>(), dwMediaVerbsFor("   "))
    }

    @Test
    fun `a prefixed enum spelling still matches and matching is case-insensitive`() {
        assertEquals(setOf(MEDIA_VERB_CAPTION), dwMediaVerbsFor("MediaType.IMAGE"))
        assertEquals(setOf(MEDIA_VERB_SUBTITLES), dwMediaVerbsFor("audio"))
    }

    @Test
    fun `an uncapped deployment draws no countdown at all`() {
        assertNull(dwAiVerbCountdownLine(remaining = null, day = "2026-08-19"))
    }

    @Test
    fun `a comfortable allowance draws no countdown either`() {
        assertNull(dwAiVerbCountdownLine(DW_AI_VERB_COUNTDOWN_FROM + 1, "2026-08-19"))
    }

    @Test
    fun `at the threshold it says the number the day and that dictation is unaffected`() {
        val line = dwAiVerbCountdownLine(DW_AI_VERB_COUNTDOWN_FROM, "2026-08-19")
        assertTrue(line != null && line.contains("$DW_AI_VERB_COUNTDOWN_FROM runs"))
        assertTrue(line!!.contains("2026-08-19"))
        assertTrue(line.contains("Dictation has its own separate allowance"))
    }

    @Test
    fun `one run left is singular and a day nobody named is simply omitted`() {
        assertTrue(dwAiVerbCountdownLine(1, "2026-08-19")!!.startsWith("1 run "))
        val noDay = dwAiVerbCountdownLine(1, null)
        assertTrue(noDay != null && noDay.contains("left today"))
    }

    @Test
    fun `nothing left still draws the line rather than nothing`() {
        assertTrue(dwAiVerbCountdownLine(0, "2026-08-19") != null)
    }

    @Test
    fun `an untold allowance and an uncapped one get different sentences`() {
        val untold = dwAiVerbAllowanceNote(
            DwAiVerbCapView(told = false, spent = false, limit = null, remaining = null)
        )
        val uncapped = dwAiVerbAllowanceNote(
            DwAiVerbCapView(told = true, spent = false, limit = null, remaining = null)
        )
        assertNull(untold)
        assertTrue(uncapped != null && uncapped.contains("no daily limit"))
        assertNotEquals(untold, uncapped)
        assertTrue(!uncapped!!.contains("not known"))
    }

    @Test
    fun `a known allowance leaves the line to the countdown`() {
        assertNull(
            dwAiVerbAllowanceNote(
                DwAiVerbCapView(told = true, spent = false, limit = 25, remaining = 3)
            )
        )
        assertNull(
            dwAiVerbAllowanceNote(
                DwAiVerbCapView(told = true, spent = true, limit = 0, remaining = 0)
            )
        )
    }

    @Test
    fun `a refusal that carried a sentence is printed verbatim`() {
        val detail =
            "Nobody has recorded yet whether material from this workshop may be sent to OpenAI's " +
                "language model, so this passage cannot be proofread there."
        assertEquals(detail, dwAiVerbProblem(DwAiVerbRefused(status = 409, detail = detail)))
    }

    @Test
    fun `a refusal with no body says nothing was written and what to do`() {
        val said = dwAiVerbProblem(DwAiVerbRefused(status = 409, detail = null))
        assertTrue(!said.contains("409"))
        assertTrue(said.contains("Try again"))
        assertTrue(said.contains("Nothing was written"))
    }

    @Test
    fun `a cap refusal with no sentence falls back and never invents the zero-cap case`() {
        val said = dwAiVerbProblem(DwAiVerbCapRefused(detail = null, retryAfterSeconds = null))
        assertEquals(DW_AI_VERBS_SPENT, said)
    }

    @Test
    fun `a request that reached nobody is answered in this client's words`() {
        assertEquals(DW_VERBS_NEED_A_CONNECTION, dwAiVerbProblem(IOException("unreachable")))
    }

    @Test
    fun `a layer kind this app does not know still gets a plain heading`() {
        val label = dwLayerKindLabel("SOME_NEWER_KIND")
        assertEquals("AI result", label)
        assertTrue(!label.contains("SOME_NEWER_KIND"))
        assertEquals("A layer with no kind recorded", dwLayerKindLabel(null))
        assertEquals("layer", dwLayerKindNoun("SOME_NEWER_KIND"))
    }

    @Test
    fun `the expansion is the kind whose note says it invents`() {
        val note = dwLayerKindNote("EXPANDED")
        assertTrue(note != null && note.contains("INVENTS"))
    }

    @Test
    fun `no tier is ever rendered as a numeral`() {
        listOf("TIER_1", "TIER_2", "TIER_3").forEach { tier ->
            val label = dwTierLabel(tier)
            assertTrue("$tier must not be drawn as a numeral: $label", label.none { it.isDigit() })
        }
        assertEquals("Where it ran was not recorded", dwTierLabel(null))
        assertTrue(!dwTierLabel("TIER_9").contains("TIER_9"))
        assertTrue(dwTierSentence("TIER_9").contains("Update the app"))
    }

    @Test
    fun `UNRECORDED is drawn as words and multi is not treated as a missing value`() {
        assertEquals("not recorded", dwProvenanceWord("UNRECORDED"))
        assertEquals("not recorded", dwProvenanceWord(""))
        assertEquals("openai", dwProvenanceWord("openai"))
        assertTrue(dwLanguageWords("multi").contains("code-switched"))
        assertEquals("not recorded", dwLanguageWords("UNRECORDED"))
        assertEquals("gpt-6-luna", dwModelWords("gpt-6-luna", null))
        assertEquals("gpt-6-luna · 2024-07-18", dwModelWords("gpt-6-luna", "2024-07-18"))
        assertEquals("not recorded", dwModelWords("UNRECORDED", "2024-07-18"))
    }
}
