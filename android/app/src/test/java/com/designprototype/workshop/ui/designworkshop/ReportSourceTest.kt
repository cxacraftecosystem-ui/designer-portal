package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StageListDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.report.HeadingBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.Run
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SCREEN'S DATA PATH — what the report is built FROM, which is where the report was broken.
 *
 * [ReportDocumentTest] and [ReportTemplateDocumentTest] both pass while the defect is live, and that
 * is not a criticism of them: they hand [buildWorkshopDocument] a populated draft and assert on the
 * document it writes, and the writers were never wrong. A designer on a Galaxy M32 exported the
 * seeded flagship workshop — 22 stages, 270 rows on the server — and got a valid .docx with the
 * correct ministry cover page, the correct table-of-contents field and ten paragraphs, because
 * [ReportScreen] prepared it from `WorkshopDraftStore.load` and nothing else. The content never
 * reached the phone. Nothing between the local read and the finished file could have noticed.
 *
 * So these tests start one step earlier, at [reportSourceFor], and assert on SECTION COUNTS rather
 * than on prose: what must never regress is that a workshop whose stages exist on the server cannot
 * export as an empty document, and that filling it in never costs a designer their unsynced
 * fieldwork.
 */
class ReportSourceTest {

    // ── A registry with the shapes the real bug involved ──────────────────────────────────────────

    private fun textField(key: String, label: String, role: String = "KEY_VALUE") =
        FieldDto(key = key, label = label, type = "TEXT", reportRole = role)

    private fun collectionStage(number: Int, key: String, title: String, entityKey: String, entityTitle: String) =
        StageDto(
            number = number, key = key, title = title,
            entities = listOf(
                EntityDto(
                    key = entityKey, cardinality = "COLLECTION", title = entityTitle,
                    fields = listOf(textField("name", "Name"), textField("detail", "Detail")),
                )
            )
        )

    /**
     * Four of the twenty-two, by their real keys so the shipped DCH_STANDARD catalogue names them
     * and the document is assembled exactly as it is in the field.
     */
    private val schema = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 1, key = "WORKSHOP_SETUP", title = "Workshop setup",
                entities = listOf(
                    EntityDto(
                        key = "setup", cardinality = "SINGLETON", title = "Workshop setup",
                        fields = listOf(
                            textField("craftName", "Craft"),
                            textField("clusterName", "Cluster"),
                            textField("state", "State"),
                        ),
                    )
                )
            ),
            collectionStage(3, "WORKSHOP_PLAN_PARTICIPANTS_OPENING", "Participants", "participant", "Participants"),
            collectionStage(11, "SKETCH_DEVELOPMENT", "Sketch development", "sketch", "Sketches"),
            collectionStage(17, "COSTING_MARKET_LINKAGE", "Costing", "costSheet", "Cost sheets"),
        )
    )

    private val stageTitles = schema.stages.map { it.title }.toSet()

    // ── The server's answer ──────────────────────────────────────────────────────────────────────

    private fun jsonRow(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.toMap().mapValues { JsonPrimitive(it.value) })

    private fun rows(entityKey: String, count: Int): List<JsonObject> = (1..count).map { index ->
        JsonObject(
            mapOf(
                "name" to JsonPrimitive("$entityKey $index"),
                "detail" to JsonPrimitive("recorded on the web"),
                // Server bookkeeping travels INSIDE the row object — see [StageBucketDto].
                "_entryId" to JsonPrimitive("$entityKey-entry-$index"),
                "_ordinal" to JsonPrimitive(index),
            )
        )
    }

    /** 20 participants, 10 sketches, 4 cost sheets and a filled stage 1 — the reported workshop. */
    private val remote = StageListDto(
        stages = mapOf(
            "WORKSHOP_SETUP" to StageBucketDto(
                singleton = jsonRow(
                    "craftName" to "Sambalpuri ikat",
                    "clusterName" to "Barpali",
                    "state" to "Odisha",
                )
            ),
            "WORKSHOP_PLAN_PARTICIPANTS_OPENING" to StageBucketDto(
                collections = mapOf("participant" to rows("participant", 20))
            ),
            "SKETCH_DEVELOPMENT" to StageBucketDto(
                collections = mapOf("sketch" to rows("sketch", 10))
            ),
            "COSTING_MARKET_LINKAGE" to StageBucketDto(
                collections = mapOf("costSheet" to rows("costSheet", 4))
            ),
        )
    )

    // ── Reading the built document ───────────────────────────────────────────────────────────────

    private fun build(draft: WorkshopDraft?): ReportDocument = buildWorkshopDocument(
        schema = schema,
        draft = draft,
        workshopId = WORKSHOP_ID,
        templateId = "DCH_STANDARD",
        warnings = emptyList(),
        accent = "",
        imageFor = { null },
        // Fixed, so nothing here depends on the clock.
        generatedAt = "2026-02-10T09:00:00Z",
    )

    private fun runText(runs: List<Run>) = runs.joinToString("") { it.text }

    /**
     * How many of the 22 stages actually made it into the file.
     *
     * A stage with nothing in it is skipped whole by `renderStageSection`, heading and all, which is
     * exactly why the delivered document was short rather than full of empty headings — and it is
     * what makes a count the honest measure here.
     */
    private fun stageSections(document: ReportDocument): Int =
        document.blocks.filterIsInstance<HeadingBlock>()
            .count { it.level == 1 && runText(it.runs).trim() in stageTitles }

    private fun sourceFor(
        local: WorkshopDraft?,
        remoteId: String? = WORKSHOP_ID,
        answer: StageListDto? = remote,
    ) = reportSourceFor(
        schema = schema,
        workshopId = WORKSHOP_ID,
        local = local,
        remoteId = remoteId,
        remote = answer,
    )

    private fun localDraft(vararg stages: Pair<String, StageDraft>) = WorkshopDraft(
        workshopId = WORKSHOP_ID,
        title = "Barpali cluster",
        remoteId = WORKSHOP_ID,
        stages = stages.toMap(),
    )

    // ── The defect ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a workshop whose stages are on the server does not export an empty document`() {
        val onDevice = localDraft()

        // THE OLD DATA PATH, reproduced: the local draft and nothing else.
        val deviceOnly = build(onDevice)
        assertEquals(
            "this is the delivered defect — a cover page and no stages",
            0,
            stageSections(deviceOnly),
        )

        val merged = build(sourceFor(onDevice).draft)
        assertEquals(
            "every stage the server holds should reach the document",
            schema.stages.size,
            stageSections(merged),
        )
        assertTrue(
            "the merged document should be substantially longer than the device-only one " +
                "(${merged.blocks.size} vs ${deviceOnly.blocks.size} blocks)",
            merged.blocks.size > deviceOnly.blocks.size,
        )
    }

    @Test
    fun `every row the server holds reaches the merged draft`() {
        val source = sourceFor(localDraft())
        val stages = source.draft!!.stages

        assertEquals(20, stages.getValue("WORKSHOP_PLAN_PARTICIPANTS_OPENING").rowsFor("participant").size)
        assertEquals(10, stages.getValue("SKETCH_DEVELOPMENT").rowsFor("sketch").size)
        assertEquals(4, stages.getValue("COSTING_MARKET_LINKAGE").rowsFor("costSheet").size)
        assertEquals(3, stages.getValue("WORKSHOP_SETUP").values.size)
        assertEquals(schema.stages.size, source.filledFromServer.size)
    }

    // ── The part that must not go wrong ──────────────────────────────────────────────────────────

    @Test
    fun `a stage this device has work for is NOT overwritten by the server's copy`() {
        // Three sketches drawn in a courtyard this morning and never synced, against ten on the
        // server. Filling the report in must not cost the designer the three.
        val unsynced = StageDraft(
            stageId = "SKETCH_DEVELOPMENT",
            values = mapOf("intent" to JsonPrimitive("drawn at the loom, not yet sent") as JsonElement),
            rows = (1..3).map { index ->
                DraftRow(
                    id = dwRowId("sketch", "local-$index"),
                    values = mapOf("name" to JsonPrimitive("courtyard sketch $index") as JsonElement),
                )
            },
        )

        val source = sourceFor(localDraft("SKETCH_DEVELOPMENT" to unsynced))
        val merged = source.draft!!
        val kept = merged.stages.getValue("SKETCH_DEVELOPMENT")

        assertEquals("the local rows must survive, all three of them", 3, kept.rowsFor("sketch").size)
        assertEquals("the local stage must be kept whole, not blended", unsynced, kept)
        assertTrue(source.keptFromDevice.contains("SKETCH_DEVELOPMENT"))
        assertTrue(source.filledFromServer.none { it == "SKETCH_DEVELOPMENT" })

        // …and the merge is per stage, so the other three stages are still filled in. An
        // all-or-nothing rule would protect the sketches by throwing away the other 34 rows.
        assertEquals(3, source.filledFromServer.size)
        assertEquals(20, merged.stages.getValue("WORKSHOP_PLAN_PARTICIPANTS_OPENING").rowsFor("participant").size)
    }

    @Test
    fun `an empty local shell does not keep the server's copy out`() {
        // A stage screen opened and closed without a keystroke leaves a row behind. Treating that as
        // "this device has work here" is how the server's twenty participants would stay out of the
        // report — the same distinction `workshopCardRows` draws.
        val shell = StageDraft(stageId = "WORKSHOP_PLAN_PARTICIPANTS_OPENING", title = "Participants")
        val source = sourceFor(localDraft("WORKSHOP_PLAN_PARTICIPANTS_OPENING" to shell))

        assertEquals(
            20,
            source.draft!!.stages.getValue("WORKSHOP_PLAN_PARTICIPANTS_OPENING").rowsFor("participant").size,
        )
        assertTrue(source.filledFromServer.contains("WORKSHOP_PLAN_PARTICIPANTS_OPENING"))
    }

    @Test
    fun `a stage held only on this device is untouched and still printed`() {
        val onlyHere = StageDraft(
            stageId = "FIELD_ONLY_STAGE",
            values = mapOf("note" to JsonPrimitive("captured with no signal") as JsonElement),
        )
        val source = sourceFor(localDraft("FIELD_ONLY_STAGE" to onlyHere))

        assertEquals(onlyHere, source.draft!!.stages.getValue("FIELD_ONLY_STAGE"))
        assertTrue(source.keptFromDevice.contains("FIELD_ONLY_STAGE"))
    }

    // ── Saying which copy the file was built from ────────────────────────────────────────────────

    @Test
    fun `a workshop that is not on the server says so and keeps the local draft`() {
        val onDevice = localDraft(
            "SKETCH_DEVELOPMENT" to StageDraft(
                stageId = "SKETCH_DEVELOPMENT",
                values = mapOf("intent" to JsonPrimitive("local only") as JsonElement),
            )
        )
        val source = sourceFor(onDevice, remoteId = null, answer = null)

        val note = source.deviceOnlyNote
        assertNotNull("a device-only export must be announced before the file is handed over", note)
        assertTrue(note!!.contains("not been created on the server"))
        assertEquals(onDevice.stages, source.draft!!.stages)
        assertTrue(source.builtFromLine.isNotEmpty())
    }

    @Test
    fun `a failed read is announced and never mistaken for an empty workshop`() {
        val onDevice = localDraft(
            "SKETCH_DEVELOPMENT" to StageDraft(
                stageId = "SKETCH_DEVELOPMENT",
                values = mapOf("intent" to JsonPrimitive("local only") as JsonElement),
            )
        )
        // The workshop EXISTS on the server; the request failed. `WorkshopCodesScreen` keeps these
        // two apart and so must this — telling a designer in a courtyard that their workshop is
        // empty, when the request merely timed out, is how a person is talked out of a report.
        val source = sourceFor(onDevice, remoteId = WORKSHOP_ID, answer = null)

        val note = source.deviceOnlyNote
        assertNotNull(note)
        assertTrue(note!!.contains("no connection"))
        assertEquals(onDevice.stages, source.draft!!.stages)
        assertTrue(source.filledFromServer.isEmpty())
    }

    @Test
    fun `a successful read carries no warning and still says what it was built from`() {
        val source = sourceFor(localDraft())
        assertNull("nothing to warn about once the server has answered", source.deviceOnlyNote)
        assertTrue(source.builtFromLine.isNotEmpty())
    }

    // ── Two exports of unchanged data are the same file ──────────────────────────────────────────

    @Test
    fun `merging is deterministic so a re-export is byte-identical`() {
        // `recordDesignWorkshopExport` sends a SHA-256 and the web's report history answers "the
        // revised copy you sent was the same file as last time" from it alone. Row ids seeded from a
        // random source would make every re-export look like a new document.
        val first = sourceFor(localDraft())
        val second = sourceFor(localDraft())
        assertEquals(first.draft, second.draft)
        assertEquals(build(first.draft).blocks, build(second.draft).blocks)
    }

    private companion object {
        const val WORKSHOP_ID = "22222222-2222-4222-8222-222222222222"
    }
}
