package com.designprototype.workshop

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.DwHeldState
import com.designprototype.workshop.data.DwSentEntry
import com.designprototype.workshop.data.DwStageRefusalRecord
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StageSyncRecord
import com.designprototype.workshop.data.WORKSHOP_DRAFT_SCHEMA_VERSION
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopSyncEngine
import com.designprototype.workshop.data.buildStageBody
import com.designprototype.workshop.data.dwFoldServerStage
import com.designprototype.workshop.data.dwRestoreStageRefusals
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.isAuthoritative
import com.designprototype.workshop.data.signatureOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DwStageAuthorityDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workshopId = "androidTest-authority"
    private val stageKey = "CLUSTER_PROFILE"

    private val spec = StageDto(
        number = 4,
        key = stageKey,
        title = "Cluster profile",
        entities = listOf(
            EntityDto(
                key = "clusterProfile", cardinality = "SINGLETON", title = "Cluster profile",
                fields = listOf(
                    FieldDto(key = "artisanHouseholds", label = "Artisan households", type = "INT"),
                    FieldDto(key = "loomsWorking", label = "Looms in working order", type = "INT"),
                )
            ),
            EntityDto(
                key = "tool", cardinality = "COLLECTION", title = "Tool",
                fields = listOf(FieldDto(key = "name", label = "Name", type = "TEXT"))
            ),
        )
    )

    private fun draftFile() = File(WorkshopDraftStore.workshopDir(context, workshopId), "draft.json")

    @Before
    fun clean() {
        WorkshopDraftStore.workshopDir(context, workshopId).deleteRecursively()
    }

    private fun writeShippedDraft() {
        val dir = WorkshopDraftStore.workshopDir(context, workshopId)
        dir.mkdirs()
        draftFile().writeText(
            """
            {
              "schemaVersion": 1,
              "workshopId": "$workshopId",
              "title": "Bagru, block printing",
              "templateId": "DCH_STANDARD",
              "stages": {
                "$stageKey": {
                  "stageId": "$stageKey",
                  "title": "Cluster profile",
                  "order": 4,
                  "values": { "artisanHouseholds": 412 },
                  "rows": [
                    { "id": "tool#t1", "values": { "name": "Charkha" } }
                  ],
                  "serverBaseline": true,
                  "emptiedEntities": ["tool"]
                }
              }
            }
            """.trimIndent()
        )
    }

    @Test
    fun a_draft_from_the_shipped_build_keeps_its_fieldwork_and_loses_only_the_claim() = runBlocking {
        writeShippedDraft()

        val loaded = WorkshopDraftStore.load(context, workshopId)
        assertNotNull("the shipped build's draft must still open at all", loaded)
        val stage = loaded!!.stages.getValue(stageKey)

        assertEquals(JsonPrimitive(412), stage.values["artisanHouseholds"])
        assertEquals(1, stage.rows.size)
        assertEquals(dwRowId("tool", "t1"), stage.rows.single().id)
        assertEquals("Bagru, block printing", loaded.title)

        assertFalse(
            "an unattributable claim of authority must not be carried across the rename",
            stage.stageSeen
        )

        assertEquals(listOf("tool"), stage.emptiedEntities)

        val body = buildStageBody(spec, stage, emptyMap(), isAuthoritative(stage, null)).body
        assertFalse(body.replaceCollections)
        assertTrue(body.entries.first { it.entityKey == "clusterProfile" }.merge)
        assertEquals(emptyList<String>(), body.emptiedEntities)
    }

    @Test
    fun the_ladder_stamps_the_new_version_on_the_document_it_rewrote() = runBlocking {
        writeShippedDraft()
        WorkshopDraftStore.load(context, workshopId)
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { it }

        val onDisk = draftFile().readText()
        assertTrue(onDisk.contains("\"schemaVersion\":$WORKSHOP_DRAFT_SCHEMA_VERSION"))
        assertFalse("the old key is gone rather than left to confuse", onDisk.contains("serverBaseline"))
        assertTrue("and the fieldwork is still there", onDisk.contains("412"))
    }

    @Test
    fun one_online_open_pays_back_what_the_rename_withdrew() = runBlocking {
        writeShippedDraft()
        val before = WorkshopDraftStore.load(context, workshopId)!!.stages.getValue(stageKey)
        assertFalse(before.stageSeen)

        val bucket = StageBucketDto(
            singleton = buildJsonObject { put("loomsWorking", 12) },
            collections = mapOf(
                "tool" to listOf(
                    buildJsonObject { put("_clientKey", "t1"); put("name", "Charkha") },
                    buildJsonObject { put("_entryId", "srv-9"); put("name", "Reed") },
                )
            ),
        )
        val fold = dwFoldServerStage(spec, before, bucket, stageKey)
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
            draft.copy(stages = draft.stages + (stageKey to fold.draft))
        }

        val after = WorkshopDraftStore.load(context, workshopId)!!.stages.getValue(stageKey)
        assertTrue("the reading survives the flash", after.stageSeen)
        assertTrue(after.customSeen)
        assertEquals("what the designer typed is untouched", JsonPrimitive(412), after.values["artisanHouseholds"])
        assertEquals("what the office typed is now here", JsonPrimitive(12), after.values["loomsWorking"])

        assertEquals("the fold adds nothing to a collection the designer emptied", 1, after.rows.size)
        assertEquals(
            listOf("Charkha"),
            after.rows.map { (it.values["name"] as JsonPrimitive).content },
        )
        assertEquals("and the collateral is counted so the screen can say so", mapOf("tool" to 1), fold.sweptRows)
        assertEquals(listOf("tool"), after.emptiedEntities)

        val body = buildStageBody(spec, after, emptyMap(), isAuthoritative(after, null)).body
        assertTrue("authority, earned by reading", body.replaceCollections)
        assertEquals(listOf("tool"), body.emptiedEntities)
        assertEquals(1, body.entries.count { it.entityKey == "tool" })
    }

    @Test
    fun a_row_this_handset_already_holds_is_not_duplicated_by_the_fold() = runBlocking {
        WorkshopDraftStore.updateStage(
            context, workshopId,
            StageDraft(
                stageId = stageKey,
                title = "Cluster profile",
                order = 4,
                rows = listOf(
                    DraftRow(id = dwRowId("tool", "t1"), values = mapOf("name" to JsonPrimitive("Charkha"))),
                ),
            ),
        )
        val before = WorkshopDraftStore.load(context, workshopId)!!.stages.getValue(stageKey)
        assertTrue("no deletion recorded, so the fold behaves normally", before.emptiedEntities.isEmpty())

        val fold = dwFoldServerStage(
            spec, before,
            StageBucketDto(
                collections = mapOf(
                    "tool" to listOf(
                        buildJsonObject { put("_clientKey", "t1"); put("name", "Charkha") },
                        buildJsonObject { put("_entryId", "srv-9"); put("name", "Reed") },
                    )
                ),
            ),
            stageKey,
        )
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
            draft.copy(stages = draft.stages + (stageKey to fold.draft))
        }

        val after = WorkshopDraftStore.load(context, workshopId)!!.stages.getValue(stageKey)
        assertEquals("recognised by its client key rather than doubled", 2, after.rows.size)
        assertEquals(
            listOf("Charkha", "Reed"),
            after.rows.map { (it.values["name"] as JsonPrimitive).content },
        )
        assertEquals(mapOf("tool" to 1), fold.addedRows)
        assertTrue("nothing was swept, because nothing was emptied", fold.sweptRows.isEmpty())
    }

    @Test
    fun a_deletion_that_cannot_travel_stops_the_workshop_claiming_it_is_backed_up() = runBlocking {
        val schema = SchemaResponse(version = "test", stages = listOf(spec))

        WorkshopDraftStore.updateStage(
            context, workshopId,
            StageDraft(
                stageId = stageKey,
                title = "Cluster profile",
                order = 4,
                values = mapOf("artisanHouseholds" to JsonPrimitive(412)),
                rows = emptyList(),
                emptiedEntities = listOf("tool"),
                stageSeen = false,
            ),
        )
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
            val stored = draft.stages.getValue(stageKey)
            val built = buildStageBody(spec, stored, emptyMap(), isAuthoritative(stored, null))
            draft.copy(
                remoteId = "srv-workshop-1",
                sync = draft.sync.copy(
                    stages = mapOf(stageKey to StageSyncRecord(signature = signatureOf(built.body))),
                ),
            )
        }

        val draft = WorkshopDraftStore.load(context, workshopId)!!
        val status = WorkshopSyncEngine.statusOf(context, schema, draft)

        assertEquals("nothing is pending — that is what made this invisible", 0, status.pendingStages)
        assertEquals(0, status.failedStages)
        assertEquals(0, status.refusedAnswers)
        assertEquals("the deletion is counted", 1, status.unsentDeletions)
        assertFalse("THE ASSERTION THE DEFECT FAILED", status.isFullySynced)
        assertEquals(
            "1 stage with a deletion not sent — the rest is saved online",
            status.summary,
        )
        val problem = status.problems.single()
        assertTrue(problem, problem.contains("Cluster profile"))
        assertTrue(problem, problem.contains("you deleted everything in tool"))
        assertTrue(problem, problem.contains("Open the stage online to send it"))
    }

    @Test
    fun one_read_is_all_it_takes_for_the_deletion_to_stop_being_outstanding() = runBlocking {
        val schema = SchemaResponse(version = "test", stages = listOf(spec))
        WorkshopDraftStore.updateStage(
            context, workshopId,
            StageDraft(
                stageId = stageKey, title = "Cluster profile", order = 4,
                values = mapOf("artisanHouseholds" to JsonPrimitive(412)),
                emptiedEntities = listOf("tool"),
                stageSeen = false,
            ),
        )
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { it.copy(remoteId = "srv-workshop-1") }
        assertEquals(
            1,
            WorkshopSyncEngine.statusOf(
                context, schema, WorkshopDraftStore.load(context, workshopId)!!,
            ).unsentDeletions,
        )

        val before = WorkshopDraftStore.load(context, workshopId)!!.stages.getValue(stageKey)
        val fold = dwFoldServerStage(
            spec, before,
            StageBucketDto(
                collections = mapOf(
                    "tool" to listOf(buildJsonObject { put("_entryId", "srv-9"); put("name", "Reed") })
                ),
            ),
            stageKey,
        )
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
            draft.copy(stages = draft.stages + (stageKey to fold.draft))
        }

        val after = WorkshopSyncEngine.statusOf(
            context, schema, WorkshopDraftStore.load(context, workshopId)!!,
        )
        assertEquals("the deletion can travel now, so it is no longer stuck", 0, after.unsentDeletions)
        assertTrue("it is pending instead, which is a thing a sync pass can finish", after.pendingStages > 0)
    }

    @Test
    fun a_refusal_survives_the_stage_being_left_and_the_process_dying() = runBlocking {
        val sent = listOf(
            DwSentEntry(entityKey = "clusterProfile"),
            DwSentEntry(entityKey = "tool", ordinal = 0, rowKey = "t1"),
            DwSentEntry(entityKey = "tool", ordinal = 1, rowKey = "t2"),
        )
        val errors = mapOf<String, JsonElement>(
            "tool[2]" to buildJsonObject { put("name", "Name is required") },
        )
        WorkshopDraftStore.updateStage(
            context, workshopId,
            StageDraft(stageId = stageKey, title = "Cluster profile", order = 4, stageSeen = true),
        )
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
            draft.copy(
                sync = draft.sync.copy(
                    stages = mapOf(
                        stageKey to StageSyncRecord(
                            signature = "whatever-the-refused-save-hashed-to",
                            refusedFields = 1,
                            failure = "the repository refused 1 of the answers in this stage … open " +
                                "the stage to see which answers, and what the repository holds.",
                            refusal = DwStageRefusalRecord(
                                errors = errors,
                                sent = sent,
                                at = "2026-08-13T02:30:00Z",
                                droppedCustomKeys = listOf("dyeVatCount"),
                            ),
                        )
                    ),
                ),
            )
        }

        val record = WorkshopDraftStore.load(context, workshopId)!!.sync.stages.getValue(stageKey)
        val stored = requireNotNull(record.refusal) { "the addressing did not survive the rename" }
        assertEquals("the error map is kept verbatim", errors, stored.errors)
        assertEquals(3, stored.sent.size)
        assertEquals("t2", stored.sent[2].rowKey)
        assertEquals(1, record.refusedFields)

        val report = requireNotNull(dwRestoreStageRefusals(spec, stored)) {
            "the stored refusal decoded to no card at all"
        }
        val refusal = report.refusals.single()
        assertEquals("tool", refusal.entityKey)
        assertEquals("the SECOND tool row, not the second entry", 1, refusal.rowIndex)
        assertEquals("tool[1]", refusal.address)
        assertEquals("Name", refusal.label)
        assertEquals("t2", refusal.rowKey)
        assertEquals(mapOf("tool[1]" to mapOf("name" to "Name is required")), report.byAddress)

        assertEquals(DwHeldState.UNRECORDED, refusal.held.state)
        assertTrue(report.needsRead)
        assertTrue(
            "and it is dated, so it reads as what the repository last said rather than as now:\n" +
                report.heading,
            report.heading.contains("at 2026-08-13T02:30:00Z"),
        )
        assertEquals(listOf("dyeVatCount"), report.droppedCustomKeys)

        WorkshopDraftStore.updateBookkeeping(context, workshopId) { draft ->
            draft.copy(
                sync = draft.sync.copy(
                    stages = draft.sync.stages.mapValues {
                        it.value.copy(refusedFields = 0, refusal = null, failure = null)
                    },
                ),
            )
        }
        val cleared = WorkshopDraftStore.load(context, workshopId)!!.sync.stages.getValue(stageKey)
        assertNull("the card must not outlive the correction", cleared.refusal)
        assertNull(dwRestoreStageRefusals(spec, cleared.refusal))
    }

    @Test
    fun a_stage_written_by_this_build_round_trips_its_reading_through_the_flash() = runBlocking {
        WorkshopDraftStore.updateStage(
            context, workshopId,
            StageDraft(
                stageId = stageKey,
                title = "Cluster profile",
                order = 4,
                values = mapOf("artisanHouseholds" to JsonPrimitive(412)),
                rows = listOf(DraftRow(id = dwRowId("tool", "t1"), values = mapOf("name" to JsonPrimitive("Charkha")))),
                stageSeen = true,
                customSeen = true,
            ),
        )
        val reloaded = WorkshopDraftStore.load(context, workshopId)!!.stages.getValue(stageKey)
        assertTrue(reloaded.stageSeen)
        assertTrue(reloaded.customSeen)
        assertTrue(buildStageBody(spec, reloaded, emptyMap(), isAuthoritative(reloaded, null)).body.replaceCollections)
    }
}
