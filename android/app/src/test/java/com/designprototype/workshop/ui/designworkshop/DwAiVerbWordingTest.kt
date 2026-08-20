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

/**
 * THE FOUR THINGS THE AI-VERB SURFACES DECIDE FOR THEMSELVES, TURNED INTO ASSERTIONS.
 *
 * ── WHAT IS BEING PINNED, AND WHY IT IS PINNED HERE RATHER THAN READ ────────────────────────────
 *
 * Every rule about these verbs lives in `data/DwAiVerbs.kt` and is tested beside it. What this file
 * covers is the part that is genuinely the SCREEN's, and each of the four has a failure mode that is
 * invisible by inspection — the composable still draws, the words are still English, and the only
 * thing wrong is that a designer is told something untrue about their own workshop.
 *
 *  1. **WHICH PASSAGE IS SENT.** [dwVerbPassageOf] answers the caret's whole paragraph or the shorter
 *     stretch inside it that the designer selected, and the layer records that answer as its EVIDENCE.
 *     A wrong answer here is a provenance record that does not match what was sent — rule 2's failure,
 *     printed in a government document. And it is the one decision this handset makes differently from
 *     the browser (paragraph-scoped, because a selection in this editor cannot leave one block), so
 *     the "collapsed caret means the paragraph" behaviour is the whole feature and not an edge case.
 *  2. **WHICH VERB A FILE IS OFFERED.** [dwMediaVerbsFor] mirrors `_VERB_MEDIA_TYPES`. Getting it
 *     wrong does not fail locally: it uploads a recording to a vision model and spends real credit to
 *     receive a parse error the designer cannot act on.
 *  3. **THE COUNTDOWN, AND THE SENTENCE WHERE THERE IS NO NUMBER TO COUNT.**
 *     [dwAiVerbCountdownLine] must answer NOTHING on an uncapped deployment. The `?: 0` an implementer
 *     reaches for turns "no ceiling" into "no runs left", which withdraws the whole feature from
 *     exactly the deployments that never limited it. This is also the function whose absence let the
 *     browser compute the same forty words in three places. [dwAiVerbAllowanceNote] fills the silence
 *     it leaves, and has the same defect available in mirror image: "no ceiling" and "this phone has
 *     been told nothing" are the SAME two nulls, and printing the second sentence for the first warns
 *     a designer about a ceiling that does not exist.
 *  4. **WHOSE WORDS A REFUSAL IS.** [dwAiVerbProblem] must print the SERVER's sentence verbatim
 *     wherever the server sent one. A client that paraphrased a consent 409 or a cap 429 would be the
 *     second voice on a rule, which is how a client and a server come to disagree about what a
 *     refusal means.
 *
 * NOT EXECUTED IN THIS ENVIRONMENT. There is no Gradle and no network here, so these assertions were
 * written to be checkable by reading and have not been run. Anything they would catch, they catch on
 * the first `testDebugUnitTest` on a machine that can fetch a toolchain.
 */
class DwAiVerbWordingTest {

    // ── 1. Which passage is sent ─────────────────────────────────────────────────────────────────

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
        // A designer who dragged out a phrase means that phrase; widening it to the paragraph would
        // send more of an artisan's words to a provider than they chose to send.
        assertEquals("dabu printers", dwVerbPassageOf(doc, range))
    }

    @Test
    fun `a selection dragged upward gives the same passage as the same one dragged down`() {
        val down = DocRange(DocPoint(0, 4), DocPoint(0, 17))
        val up = DocRange(DocPoint(0, 17), DocPoint(0, 4))
        // `normaliseRange` puts the ends in document order. Without it the offsets would be read as
        // from=17, to=4 — `from >= to` — and the whole paragraph would be sent for a selection the
        // designer made backwards, which is the ordinary way of selecting the end of a sentence.
        assertEquals(dwVerbPassageOf(doc, down), dwVerbPassageOf(doc, up))
    }

    @Test
    fun `a range naming a block that is not there answers empty rather than throwing`() {
        // Nothing should produce one, but this is read on a click handler, and `clampPoint`'s own
        // KDoc names what an off-the-end point costs there: the app closing while somebody types.
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
        // An IMAGE block's spans ARE its caption, so a caret there is a caret in prose — proofreading
        // a caption is an ordinary thing to want, and it must not silently send nothing.
        assertEquals("The loom in the courtyard.", dwVerbPassageOf(withBoth, collapsedAt(DocPoint(1, 0))))
    }

    @Test
    fun `the preview collapses newlines and marks its own truncation`() {
        val short = dwVerbPassagePreview("Two   lines\nof prose")
        assertEquals("Two lines of prose", short)

        val long = dwVerbPassagePreview("word ".repeat(200))
        assertTrue("a truncated preview must say so", long.endsWith("…"))
        // A preview that simply stopped would be indistinguishable from a short paragraph, which is
        // the defect class this repository keeps re-fixing.
        assertTrue(long.length <= DW_VERB_PREVIEW_CHARS + 1)

        /*
          AND THE SAME ANSWER FOR A PASSAGE THE SIZE OF A REAL NARRATIVE, which is what the rewrite
          from a whitespace-run `Regex` split into a single bounded pass had to preserve. This input is
          20,000 characters — the server's own bound on a passage — and the whole of it used to be
          collapsed into intermediate strings, per keystroke, to produce these 160.
        */
        val narrative = dwVerbPassagePreview("क ".repeat(10_000))
        assertTrue(narrative, narrative.length <= DW_VERB_PREVIEW_CHARS + 1)
        assertTrue(narrative.endsWith("…"))
        // The ellipsis replaces the space that fell on the boundary rather than following it: a
        // preview reading "… …" would look like the passage itself had trailed off.
        assertTrue(narrative, !narrative.contains(" …"))
        // Leading and trailing whitespace is dropped and interior runs collapse to one space —
        // including a tab and a newline, and no space is left before the ellipsis.
        val ragged = "\n  one \t\t two   \n"
        assertEquals("one two", dwVerbPassagePreview(ragged))
        // A passage of nothing but whitespace is nothing at all, not a single space.
        assertEquals("", dwVerbPassagePreview(" \n\t "))
        // Exactly at the limit is NOT truncated, which is the boundary the extra character exists to
        // find: 160 characters is a preview, 161 is a truncation.
        assertEquals("अ".repeat(DW_VERB_PREVIEW_CHARS), dwVerbPassagePreview("अ".repeat(DW_VERB_PREVIEW_CHARS)))
        assertTrue(dwVerbPassagePreview("अ".repeat(DW_VERB_PREVIEW_CHARS + 1)).endsWith("…"))
    }

    @Test
    fun `the no-paragraph sentence is not the browser's and names the caret`() {
        // The one place the handset's wording diverges from the browser's, and the reason is in
        // `DW_NO_PARAGRAPH_TO_WORK_ON`'s own KDoc: "select the words first" is an instruction this
        // editor cannot honour. If somebody replaces this sentence with the shared constant, the
        // advice becomes impossible to follow rather than merely differently worded.
        assertTrue(DW_NO_PARAGRAPH_TO_WORK_ON.contains("caret"))
        assertTrue(DW_NO_PARAGRAPH_TO_WORK_ON.contains("paragraph"))
        // And it is a DIFFERENT sentence from the shared one rather than a copy of it. The panel
        // substitutes it for `DW_VERBS_NOTHING_SELECTED` at the press, so if the two ever became
        // equal the substitution would be silently pointless rather than visibly wrong.
        assertNotEquals(DW_VERBS_NOTHING_SELECTED, DW_NO_PARAGRAPH_TO_WORK_ON)
    }

    // ── 2. Which verb a file is offered ──────────────────────────────────────────────────────────

    @Test
    fun `each media type is offered exactly the verbs the server accepts`() {
        assertEquals(setOf(MEDIA_VERB_CAPTION), dwMediaVerbsFor("IMAGE"))
        assertEquals(setOf(MEDIA_VERB_SUBTITLES), dwMediaVerbsFor("AUDIO"))
        // A VIDEO is the only file both verbs accept — `_VERB_MEDIA_TYPES` lists it under CAPTION and
        // under SUBTITLES both.
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
        // The server compares with `endswith` because "the column's stored form has varied, and a
        // prefixed enum spelling must not silently match nothing". A client that compared with `==`
        // would offer no verbs at all on a deployment that started writing `MediaType.IMAGE`.
        assertEquals(setOf(MEDIA_VERB_CAPTION), dwMediaVerbsFor("MediaType.IMAGE"))
        assertEquals(setOf(MEDIA_VERB_SUBTITLES), dwMediaVerbsFor("audio"))
    }

    // ── 3. The countdown ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `an uncapped deployment draws no countdown at all`() {
        // `allowance_payload` sends null for both numbers where there is no ceiling, deliberately,
        // "because 0 remaining and 'no ceiling' must not look alike". The `?: 0` an implementer
        // reaches for is what turns an uncapped deployment into one that appears to be out of runs.
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
        // The DAY is the server's India-time date and not this phone's, so it is printed: a count
        // against a date nobody recognises is worse than a count with no date.
        assertTrue(line!!.contains("2026-08-19"))
        // The clause `cap_refusal` insists on: a designer told their AI allowance is gone reasonably
        // concludes dictation has stopped too. It has not; it is a different ceiling.
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
        // Zero is inside the threshold, so the number is drawn — the gate is what refuses the press,
        // and a countdown that vanished at exactly the moment it mattered would be the one reading a
        // designer cannot act on.
        assertTrue(dwAiVerbCountdownLine(0, "2026-08-19") != null)
    }

    // ── 3b. What to say when there is no number to count ─────────────────────────────────────────

    /**
     * **THE TWO STATES THE COUNTDOWN CANNOT DISTINGUISH, AND WHICH THE PANEL GOT WRONG.**
     *
     * `dwAiVerbCountdownLine` answers null whenever `remaining` is null, which is true both for a
     * phone that has been told nothing and for a deployment with no ceiling. `DwAiVerbsPanel` filled
     * that silence by branching on `limit == null && remaining == null` and printing "not known until
     * one goes through" for BOTH — telling a designer on an uncapped server that there might be a
     * ceiling nobody can see. That is the `?: 0` defect from the other direction, and
     * `DwAiVerbCapView.told` is what makes it expressible.
     */
    @Test
    fun `an untold allowance and an uncapped one get different sentences`() {
        val untold = dwAiVerbAllowanceNote(
            DwAiVerbCapView(told = false, spent = false, limit = null, remaining = null)
        )
        val uncapped = dwAiVerbAllowanceNote(
            DwAiVerbCapView(told = true, spent = false, limit = null, remaining = null)
        )
        assertTrue(untold != null && untold.contains("not known until one goes through"))
        assertTrue(uncapped != null && uncapped.contains("no daily ceiling"))
        // The point of the whole change: two facts, two sentences. Equal strings here would mean the
        // branch had collapsed back onto the numbers.
        assertNotEquals(untold, uncapped)
        // And the uncapped one must not tell a designer their allowance is unknown, which is the
        // specific false statement this repair removed.
        assertTrue(!uncapped!!.contains("not known"))
    }

    /**
     * WHERE A NUMBER IS KNOWN, THIS SAYS NOTHING — the countdown owns that line.
     *
     * Both drawn together would put two sentences about one ceiling under one button. The surfaces do
     * draw both, so the division has to be here rather than in each of them.
     */
    @Test
    fun `a known allowance leaves the line to the countdown`() {
        assertNull(
            dwAiVerbAllowanceNote(
                DwAiVerbCapView(told = true, spent = false, limit = 25, remaining = 3)
            )
        )
        // Including a ceiling of zero, which is a real setting and is the gate's refusal to word, not
        // this line's.
        assertNull(
            dwAiVerbAllowanceNote(
                DwAiVerbCapView(told = true, spent = true, limit = 0, remaining = 0)
            )
        )
    }

    // ── 4. Whose words a refusal is ──────────────────────────────────────────────────────────────

    @Test
    fun `a refusal that carried a sentence is printed verbatim`() {
        val detail =
            "Nobody has recorded yet whether material from this workshop may be sent to OpenAI's " +
                "language model, so this passage cannot be proofread there."
        assertEquals(detail, dwAiVerbProblem(DwAiVerbRefused(status = 409, detail = detail)))
    }

    @Test
    fun `a refusal with no body names the status and says nothing was written`() {
        val said = dwAiVerbProblem(DwAiVerbRefused(status = 409, detail = null))
        // The one place a code reaches a designer, and `DwAiVerbRefused`'s own KDoc says why: a 409
        // rewritten by a proxy carries no sentence, and "the server said no" would leave somebody
        // unable to tell a lost body from a real refusal of their work.
        assertTrue(said.contains("409"))
        assertTrue(said.contains("Nothing was written"))
    }

    @Test
    fun `a cap refusal with no sentence falls back and never invents the zero-cap case`() {
        val said = dwAiVerbProblem(DwAiVerbCapRefused(detail = null, retryAfterSeconds = null))
        // Compared against the shared constant rather than against a copy of its words, so a
        // paraphrase written here fails instead of quietly becoming a second voice on the rule. It
        // deliberately does NOT invent the zero-cap case: `cap_refusal` has a separate sentence for a
        // deployment that has switched these verbs off, and that one is the server's to write.
        assertEquals(DW_AI_VERBS_SPENT, said)
    }

    @Test
    fun `a request that reached nobody is answered in this client's words`() {
        // `IOException` is Retrofit's shape for a call that never arrived, so no server composed a
        // sentence for it — the one case this client is entitled to write one. Compared against the
        // shared constant so that a paraphrase in this layer fails rather than drifts, and so that the
        // half a designer would otherwise get wrong ("nothing has been queued") cannot be dropped.
        assertEquals(DW_VERBS_NEED_A_CONNECTION, dwAiVerbProblem(IOException("unreachable")))
    }

    // ── The vocabulary a reader meets ────────────────────────────────────────────────────────────

    @Test
    fun `a layer kind this build has never heard of carries the server's own word`() {
        // A deployment can be a release behind — `_verb_layer_kind` allows for exactly this — and a
        // blank heading over a passage somebody is about to accept is the one thing that must not
        // happen. The tier, the model and the acceptance are all still readable without the kind.
        val label = dwLayerKindLabel("SOME_NEWER_KIND")
        assertTrue(label.contains("SOME_NEWER_KIND"))
        assertEquals("A layer with no kind recorded", dwLayerKindLabel(null))
        // The noun phrase degrades to a word that fits inside a sentence, rather than to the whole
        // sentence the label degrades to.
        assertEquals("layer", dwLayerKindNoun("SOME_NEWER_KIND"))
    }

    @Test
    fun `the expansion is the kind whose note says it invents`() {
        // The caution the annexure prints under this heading and under no other. If this note ever
        // stops saying so, the designer signing for it reads a weaker warning than the ministry
        // officer who meets the same passage a year later.
        val note = dwLayerKindNote("EXPANDED")
        assertTrue(note != null && note.contains("INVENTS"))
    }

    @Test
    fun `no tier is ever rendered as a numeral`() {
        // `AiTier.number` exists "for prose only, never for a comparison": Tier 1 is the only tier
        // that works with no signal and Tier 3 is the only one carrying the craft keyterm list, so a
        // chip reading "Tier 3" invites the comparison the enum was chosen to prevent.
        listOf("TIER_1", "TIER_2", "TIER_3").forEach { tier ->
            val label = dwTierLabel(tier)
            assertTrue("$tier must not be drawn as a numeral: $label", label.none { it.isDigit() })
        }
        assertEquals("Tier not recorded", dwTierLabel(null))
        assertTrue(dwTierLabel("TIER_9").contains("TIER_9"))
        assertTrue(dwTierSentence("TIER_9").contains("does not know"))
    }

    @Test
    fun `UNRECORDED is drawn as words and multi is not treated as a missing value`() {
        // Both are REAL stored values. `UNRECORDED` printed raw reads as a code rather than as an
        // answer; `multi` printed as "not recorded" would hide the one fact a reader of a
        // code-switched interview needs, since Deepgram Nova-3 is deliberately called with it.
        assertEquals("not recorded", dwProvenanceWord("UNRECORDED"))
        assertEquals("not recorded", dwProvenanceWord(""))
        assertEquals("openai", dwProvenanceWord("openai"))
        assertTrue(dwLanguageWords("multi").contains("code-switched"))
        assertEquals("not recorded", dwLanguageWords("UNRECORDED"))
        // A model with no version recorded is named without a dangling separator.
        assertEquals("gpt-4o-mini", dwModelWords("gpt-4o-mini", null))
        assertEquals("gpt-4o-mini · 2024-07-18", dwModelWords("gpt-4o-mini", "2024-07-18"))
        assertEquals("not recorded", dwModelWords("UNRECORDED", "2024-07-18"))
    }
}
