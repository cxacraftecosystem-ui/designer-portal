package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Where were you when this stage was filled in?" — asked twenty-two times a workshop, and read.
 *
 * ── THE TWO DEFECTS THESE PIN, WHICH ARE ONE DEFECT ───────────────────────────────────────────────
 *
 * `DwRecordingPlaceCard` writes its answer into the stage singleton under `_recordingPlace`. The
 * underscore is the sync protocol's marker for "this never goes on the wire" — `WorkshopSync.wireData`
 * strips every such key by design, because the server has no column for it and would report a red
 * "the server did not recognise 1 field" on every stage of every sync. So the value lives on the
 * handset and only on the handset, and the local draft is what the on-device report is generated from.
 *
 * (1) IT NEVER PRINTED. `renderEntity` walks `entity.liveFields`, so an underscore key is unreachable
 *     by construction on the only path that prints a singleton's answers. A repo-wide grep for the key
 *     found the constant, the card, one write in `StageScreen` and no reader anywhere — while the
 *     card's own KDoc asserted the opposite in as many words ("the provenance prints even though it
 *     never leaves the phone"). A fortnight of capture effort, consumed by nothing.
 *
 * (2) ITS PRESENCE ALONE MADE A STAGE LOOK ANSWERED. `renderStageSection` tested
 *     `singletonValues.isEmpty()` to decide whether to omit the section. A stage a designer opened,
 *     answered the location card on, and left, therefore printed a numbered stage heading with
 *     "Not recorded." underneath it — and a line in the contents page. This is the same wrong question
 *     `ReportSource.holdsWork` was fixed for (there it kept the SERVER's copy of a whole stage out of
 *     the document); this is the second place it was being asked, and the fix is the same one.
 *
 * The assertions are about the SENTENCE and about whether the SECTION exists, never about block
 * counts or ordering — a test that pinned the document's shape would fail on the next legitimate
 * template change and be deleted.
 */
class ReportRecordingPlaceTest {

    // ── the registry and the draft, cut to the one shape that matters ────────────────────────────

    private fun schemaOf(vararg stages: StageDto) =
        SchemaResponse(version = "test", stages = stages.toList())

    private val stageFourteen = StageDto(
        number = 14, key = "PROTOTYPE_ITERATION", title = "Prototype iteration",
        entities = listOf(
            EntityDto(
                key = "iteration", cardinality = "SINGLETON", title = "Iteration",
                fields = listOf(
                    FieldDto(key = "summary", label = "What changed", type = "TEXT", reportRole = "KEY_VALUE"),
                ),
            )
        ),
    )

    /** Exactly what `dwLocationToValue` writes: the coordinate first, then the named address. */
    private fun place(
        village: String? = "Barpali",
        district: String? = "Bargarh",
        state: String? = "Odisha",
    ): JsonElement = buildJsonObject {
        put("lat", 21.18540)
        put("lon", 83.58750)
        village?.let { put("village", it) }
        district?.let { put("district", it) }
        state?.let { put("state", it) }
    }

    private fun draftOf(values: Map<String, JsonElement>) = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(
            "PROTOTYPE_ITERATION" to StageDraft(stageId = "PROTOTYPE_ITERATION", values = values),
        ),
    )

    private fun build(draft: WorkshopDraft): ReportDocument = buildWorkshopDocument(
        schema = schemaOf(stageFourteen),
        draft = draft,
        workshopId = draft.workshopId,
        templateId = "DCH_STANDARD",
        templateName = "DCH standard",
        warnings = emptyList(),
        accent = "",
        imageFor = { null },
        generatedAt = "2026-08-15T09:30:00Z",
    )

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun textOf(block: Block): String = when (block) {
        is ParagraphBlock -> runText(block.runs)
        is HeadingBlock -> runText(block.runs)
        is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
        else -> ""
    }

    private fun printedText(document: ReportDocument): String =
        document.blocks.joinToString("\n") { textOf(it) }

    private fun headings(document: ReportDocument): List<String> =
        document.blocks.filterIsInstance<HeadingBlock>().map { runText(it.runs) }

    // ── (1) it prints ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the place a stage was recorded in prints under that stage's heading`() {
        val document = build(
            draftOf(
                mapOf(
                    "summary" to JsonPrimitive("Narrowed the border to two inches."),
                    DW_RECORDING_PLACE_KEY to place(),
                )
            )
        )

        val printed = printedText(document)
        assertTrue(
            "the recording place reached no part of the document:\n$printed",
            printed.contains("Recorded at Barpali, Bargarh, Odisha"),
        )
        // THE COORDINATE AS WELL AS THE NAMES. The names are what a reviewer reads; the coordinate is
        // the half an auditor can check, and a provenance line carrying only the first is a claim
        // nobody can verify. Five decimals, Locale.ROOT — a phone set to a comma-decimal locale must
        // not print "21,18540" into a pair that is itself comma-separated.
        assertTrue(
            "the coordinate is missing, so the line cannot be audited:\n$printed",
            printed.contains("(21.18540, 83.58750)"),
        )
        // The one line in this document the SERVER cannot reproduce, so it says so itself. A reader
        // holding both copies has no access to the source comment that explains the difference.
        assertTrue(
            "nothing in the line admits the office's copy lacks it:\n$printed",
            printed.contains("the office's copy of this report does not carry this line"),
        )
    }

    @Test
    fun `a place with no named address still prints its coordinate`() {
        // The card accepts a raw reading — a courtyard the state/district lists do not name. Printing
        // nothing for it would lose the one form of provenance that needs no lookup to be true.
        val document = build(
            draftOf(
                mapOf(
                    "summary" to JsonPrimitive("Narrowed the border to two inches."),
                    DW_RECORDING_PLACE_KEY to place(village = null, district = null, state = null),
                )
            )
        )

        val printed = printedText(document)
        assertTrue("the unnamed reading printed nothing:\n$printed", printed.contains("Recorded at 21.18540, 83.58750"))
        assertFalse(
            "an empty name list must not print as stray punctuation:\n$printed",
            printed.contains("Recorded at  ") || printed.contains("Recorded at (") || printed.contains(", ("),
        )
    }

    @Test
    fun `a stage nobody answered the card on prints no provenance line`() {
        val document = build(draftOf(mapOf("summary" to JsonPrimitive("Narrowed the border."))))

        assertFalse(
            "a line was invented for a card that was never answered:\n${printedText(document)}",
            printedText(document).contains("Recorded at"),
        )
    }

    // ── (2) it is not mistaken for an answer ─────────────────────────────────────────────────────

    @Test
    fun `a stage holding only a recording place is omitted from the document entirely`() {
        // The defect: `singletonValues.isEmpty()` was false for a map holding one underscore key, so
        // the section survived `omitIfEmpty` — all six templates set it true — and printed a numbered
        // stage heading over "Not recorded.", plus a contents-page line, for a stage with nothing in
        // it. Multiply by the twenty-two stages the card is offered on.
        val document = build(draftOf(mapOf(DW_RECORDING_PLACE_KEY to place())))

        assertFalse(
            "a stage with no answers printed a heading anyway:\n${headings(document)}",
            headings(document).any { it.contains("Prototype iteration") },
        )
        assertFalse(
            "and with it the provenance line, standing over nothing:\n${printedText(document)}",
            printedText(document).contains("Recorded at"),
        )
    }

    @Test
    fun `the provenance line does not suppress the empty-section note`() {
        /*
          THE SECTION IS DRAWN AND HAS NOTHING TO SAY, which is reachable without contriving anything:
          `retiredOnTheServer` is a value this draft holds under a key the registry no longer declares
          — an ordinary consequence of a phone holding a stage captured before a field was retired.
          `answered` is non-empty, so the section survives `omitIfEmpty`; `renderEntity` walks
          `entity.liveFields` and finds nothing to print, so `wrote` stays false and the section's
          "Not recorded." note fires.

          The invariant under test is that the provenance line has NOT set `wrote`. "Not recorded." is
          a statement about the designer's answers to the pro-forma, and a note about where somebody
          was standing is not one of them — counting it would silently delete that note from every
          section a designer happened to record a place on, which is the direction of failure this
          whole file keeps closing.
        */
        val document = build(
            draftOf(
                mapOf(
                    "retiredOnTheServer" to JsonPrimitive("an answer to a question no longer asked"),
                    DW_RECORDING_PLACE_KEY to place(),
                )
            )
        )

        val printed = printedText(document)
        assertTrue(
            "the section was not drawn at all, so this test no longer covers what it says:\n$printed",
            printed.contains("Recorded at Barpali"),
        )
        assertTrue(
            "the provenance line was counted as content and suppressed the note:\n$printed",
            printed.contains("Not recorded."),
        )
    }
}
