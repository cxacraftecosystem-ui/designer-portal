package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ImageGridBlock
import com.designprototype.workshop.report.ImageRef
import com.designprototype.workshop.report.KeyValueBlock
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.TableBlock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the handset SAYS about the photographs it referenced and could not find.
 *
 * ── THE DEFECT THESE PIN ──────────────────────────────────────────────────────────────────────
 *
 * `imageFor` answers null for any token that is not a LOCAL `DraftMedia.id` with bytes on disk, and
 * both of its consumers used to be `ids.mapNotNull(imageFor)`. That made the residue invisible in
 * every direction at once: no counter, nothing on the export screen (`ReportExport.droppedImages`
 * counts an `ImageRef` whose BYTES failed to load, and an id that never became an `ImageRef` never
 * reaches a writer), no line in the file, and — because `renderMediaAnnexure` returned on
 * `gathered.isEmpty()` — no 'Photographic record' section either, heading and contents entry
 * included.
 *
 * IT IS THE ORDINARY CASE, NOT AN EXOTIC ONE. [reportSourceFor] fills stages this handset never
 * opened from `designWorkshopStages`, and those answers carry the SERVER's `MediaFile` ids, which
 * resolve against nothing in `draft.media`. So a designer exporting a colleague's workshop got every
 * table, every paragraph, not one photograph, and a screen and an export-log row both reporting the
 * file as whole. The bytes are not lost — the office's copy prints them — which is why the sentence
 * these tests assert on says WHERE they are rather than only that they are missing.
 *
 * The assertions are about the SENTENCE and the SECTION, not about block counts: a test that pinned
 * the document's shape would fail on the next legitimate template change and be deleted.
 */
class ReportUnresolvedMediaTest {

    // ── the registry, cut to the one shape that matters ──────────────────────────────────────────

    private fun photoSchema() = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 16, key = "FINAL_PROTOTYPE_DOCUMENTATION", title = "Final products",
                entities = listOf(
                    EntityDto(
                        key = "finalProduct", cardinality = "SINGLETON", title = "Final product",
                        fields = listOf(
                            FieldDto(key = "name", label = "Name", type = "TEXT", reportRole = "KEY_VALUE"),
                            FieldDto(
                                key = "photos", label = "Photographs", type = "IMAGE_LIST",
                                reportRole = "GALLERY", tier = "BASIC",
                            ),
                        ),
                    )
                ),
            ),
            StageDto(
                number = 20, key = "REPORT_GENERATION", title = "Report generation",
                entities = listOf(
                    EntityDto(
                        key = "reportSettings", cardinality = "SINGLETON", title = "Report settings",
                        fields = listOf(
                            FieldDto(key = "reportTitle", label = "Report title", type = "TEXT"),
                        ),
                    )
                ),
            ),
        ),
    )

    /**
     * A stage as [reportSourceFor] hands it over after a server read: the answers are the server's,
     * so the three ids in `photos` name `MediaFile` rows this device has never held.
     *
     * `m2` APPEARS TWICE ON PURPOSE. The media annexure re-resolves every id the stage sections
     * already tried, so a count that is not deduplicated reports six missing photographs for four
     * files — and a number a designer can disprove by counting is a number they stop reading.
     */
    private fun downloadedDraft() = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(
            "FINAL_PROTOTYPE_DOCUMENTATION" to StageDraft(
                stageId = "FINAL_PROTOTYPE_DOCUMENTATION",
                values = mapOf(
                    "name" to JsonPrimitive("Bandha table runner"),
                    "photos" to buildJsonArray {
                        add(JsonPrimitive("srv-media-1"))
                        add(JsonPrimitive("srv-media-2"))
                        add(JsonPrimitive("srv-media-2"))
                        add(JsonPrimitive("srv-media-3"))
                    },
                ),
            ),
        ),
    )

    private fun build(
        templateId: String,
        draft: WorkshopDraft?,
        imageFor: (String) -> ImageRef? = { null },
        onUnresolvedMedia: (List<String>) -> Unit = {},
    ): ReportDocument = buildWorkshopDocument(
        schema = photoSchema(),
        draft = draft,
        workshopId = draft?.workshopId ?: "local-test",
        templateId = templateId,
        warnings = emptyList(),
        accent = "",
        imageFor = imageFor,
        generatedAt = "2026-08-15T09:30:00Z",
        onUnresolvedMedia = onUnresolvedMedia,
    )

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    private fun textOf(block: Block): String = when (block) {
        is ParagraphBlock -> runText(block.runs)
        is HeadingBlock -> runText(block.runs)
        is KeyValueBlock -> block.pairs.joinToString("\n") { (label, runs) -> "$label: ${runText(runs)}" }
        is TableBlock -> block.rows.joinToString("\n") { row -> row.joinToString(" | ") { runText(it) } }
        else -> ""
    }

    private fun printedText(document: ReportDocument): String =
        document.blocks.joinToString("\n") { textOf(it) }

    private val resolvesEverything: (String) -> ImageRef? =
        { id -> ImageRef(source = "/data/media/$id.jpg", widthPx = 1600, heightPx = 1200) }

    // ── the count ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every media id the document could not resolve is counted once and handed back`() {
        var reported: List<String>? = null
        build("DETAILED_TECHNICAL", downloadedDraft()) { reported = it }

        assertEquals(
            "the unresolved ids must come back deduplicated, in first-use order",
            listOf("srv-media-1", "srv-media-2", "srv-media-3"),
            reported,
        )
    }

    @Test
    fun `an export that resolves every photograph reports nothing at all`() {
        var reported: List<String>? = null
        val document = build(
            "DETAILED_TECHNICAL", downloadedDraft(), imageFor = resolvesEverything,
        ) { reported = it }

        // The callback fires on the clean path too — a caller holding a screen counter has to be
        // able to CLEAR it, or a stale "3 photographs are missing" outlives the file it described.
        assertEquals("the clean path must report an empty list, not nothing", emptyList<String>(), reported)
        assertTrue(
            "no note may appear over a document that is whole:\n${document.warnings}",
            document.warnings.none { it.contains("not stored on the handset") },
        )
        assertTrue(
            "and none in the file either:\n${printedText(document)}",
            !printedText(document).contains("not stored on the handset"),
        )
    }

    // ── the screen's copy of it ──────────────────────────────────────────────────────────────────

    @Test
    fun `the count reaches the export screen through the document's warnings`() {
        val document = build("DETAILED_TECHNICAL", downloadedDraft())

        val note = document.warnings.singleOrNull { it.contains("not stored on the handset") }
        assertTrue("no warning carried the count:\n${document.warnings}", note != null)
        assertTrue(
            "the sentence must count the distinct files: $note",
            note!!.contains("3 photographs referenced by this workshop"),
        )
        assertTrue(
            "and must say where the bytes are, or a designer goes hunting for a fault on the " +
                "handset that is not there: $note",
            note.contains("office's copy of this report carries them"),
        )
    }

    // ── the file's copy of it ────────────────────────────────────────────────────────────────────

    @Test
    fun `the photographic record annexure keeps its heading and states what is missing`() {
        val document = build("DETAILED_TECHNICAL", downloadedDraft())

        val headings = document.blocks.filterIsInstance<HeadingBlock>().map { runText(it.runs) }
        assertTrue(
            "the whole annexure — heading, plates and contents entry — used to vanish:\n$headings",
            headings.any { it.contains("Photographic record") },
        )
        assertTrue(
            "the section must say why it has no plates:\n${printedText(document)}",
            printedText(document).contains("3 photographs referenced by this workshop"),
        )
        assertTrue(
            "and must not print an empty grid",
            document.blocks.filterIsInstance<ImageGridBlock>().isEmpty(),
        )
    }

    @Test
    fun `a template with no photographic record still says it in the file`() {
        // COMPACT_SUMMARY carries no ANNEXURE_MEDIA section, so the note has nowhere natural to sit
        // and goes to the foot of the document instead of nowhere. Four of the six templates are in
        // this case, and a short report is internally consistent — right cover, right contents,
        // fewer plates — so the file itself is the only place the gap can be detected.
        val document = build("COMPACT_SUMMARY", downloadedDraft())

        assertTrue(
            "nothing in the file admitted the photographs were left out:\n${printedText(document)}",
            printedText(document).contains("3 photographs referenced by this workshop"),
        )
    }

    @Test
    fun `the file says it once, never twice`() {
        val document = build("DETAILED_TECHNICAL", downloadedDraft())

        val said = document.blocks.count { textOf(it).contains("referenced by this workshop") }
        assertEquals(
            "the annexure's note and the document-wide note must not both print:\n" +
                printedText(document),
            1, said,
        )
    }

    // ── a workshop that genuinely has no photographs is unchanged ───────────────────────────────

    @Test
    fun `a workshop with no media at all prints no annexure and no note`() {
        val bare = WorkshopDraft(
            workshopId = "local-test",
            title = "Barpali cluster",
            stages = mapOf(
                "FINAL_PROTOTYPE_DOCUMENTATION" to StageDraft(
                    stageId = "FINAL_PROTOTYPE_DOCUMENTATION",
                    values = mapOf<String, JsonElement>("name" to JsonPrimitive("Bandha table runner")),
                ),
            ),
        )
        val document = build("DETAILED_TECHNICAL", bare)

        val headings = document.blocks.filterIsInstance<HeadingBlock>().map { runText(it.runs) }
        assertTrue(
            "'this workshop was never photographed' is a different fact and keeps the old " +
                "behaviour:\n$headings",
            headings.none { it.contains("Photographic record") },
        )
        assertTrue(
            "and nothing may be said about missing files:\n${document.warnings}",
            document.warnings.none { it.contains("not stored on the handset") },
        )
    }
}
