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

/**
 * WHAT HAPPENS TO A DRAFT THAT IS ALREADY ON A HANDSET, ON A HANDSET.
 *
 * The authority fix turns on a claim that is written to flash and read back by a build that did not
 * write it. `serverBaseline` became [StageDraft.stageSeen] and its value is DELIBERATELY NOT CARRIED
 * ACROSS — a `true` on disk is indistinguishable between "this device read the server's copy" and
 * "this device wrote to the server once, having read nothing", and carrying the second is what
 * deletes the office's fieldwork on the next save.
 *
 * ── WHY THIS IS NOT A DESKTOP JVM TEST ────────────────────────────────────────────────────────────
 *
 * The desktop suite pins the decode; it cannot pin the LOAD, which is what actually happens to a
 * designer's draft. That path runs the version ladder, the quarantine-on-damage branch, the atomic
 * temp → `fd.sync()` → `renameTo` write and the store's own `Json` settings against a real filesDir —
 * and the thing being asserted here is precisely that a document written by the SHIPPED build (1.1.19,
 * versionCode 1001019, which is what is installed on this handset) survives the rename with its
 * fieldwork intact and its authority withdrawn. A fake filesystem would answer a different question.
 *
 * Run:
 *   ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.designprototype.workshop.DwStageAuthorityDeviceTest
 *
 * It writes only under `filesDir/workshops/androidTest-…` and clears those directories before each
 * case, so it cannot touch a workshop a designer holds. AGP UNINSTALLS THE APP when the run finishes;
 * reinstall with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
 */
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

    /**
     * Returns Unit EXPLICITLY, and that is not a style point: `deleteRecursively()` answers Boolean,
     * so an expression body here gives JUnit a `@Before` that is not void — which it refuses by
     * failing the whole class with `initializationError: Failed to instantiate test runner class`,
     * a message that names neither this method nor its return type.
     */
    @Before
    fun clean() {
        WorkshopDraftStore.workshopDir(context, workshopId).deleteRecursively()
    }

    /**
     * A draft EXACTLY as the shipped build writes it — schemaVersion 1, `serverBaseline: true`, a
     * fortnight of answers under it — put on the flash by hand and then opened by this build.
     */
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

        // THE FIELDWORK IS UNTOUCHED. This is a bookkeeping change and not a data change, and that
        // sentence is only worth writing down if something checks it on the device.
        assertEquals(JsonPrimitive(412), stage.values["artisanHouseholds"])
        assertEquals(1, stage.rows.size)
        assertEquals(dwRowId("tool", "t1"), stage.rows.single().id)
        assertEquals("Bagru, block printing", loaded.title)

        // THE CLAIM IS NOT. `serverBaseline` cannot be attributed, so it does not survive the rename.
        assertFalse(
            "an unattributable claim of authority must not be carried across the rename",
            stage.stageSeen
        )

        // And the deletion the designer made is still owed to the repository rather than discarded.
        assertEquals(listOf("tool"), stage.emptiedEntities)

        // So the very next payload merges: the office's fields cannot be swept by a draft that has
        // never read them.
        val body = buildStageBody(spec, stage, emptyMap(), isAuthoritative(stage, null)).body
        assertFalse(body.replaceCollections)
        assertTrue(body.entries.first { it.entityKey == "clusterProfile" }.merge)
        assertEquals(emptyList<String>(), body.emptiedEntities)
    }

    @Test
    fun the_ladder_stamps_the_new_version_on_the_document_it_rewrote() = runBlocking {
        writeShippedDraft()
        WorkshopDraftStore.load(context, workshopId)
        // Re-saved through the store so the stamped version reaches the flash, which is the state the
        // NEXT open reads. A ladder that migrates on every load without recording it is a ladder that
        // runs for ever.
        WorkshopDraftStore.updateBookkeeping(context, workshopId) { it }

        val onDisk = draftFile().readText()
        assertTrue(onDisk.contains("\"schemaVersion\":$WORKSHOP_DRAFT_SCHEMA_VERSION"))
        assertFalse("the old key is gone rather than left to confuse", onDisk.contains("serverBaseline"))
        assertTrue("and the fieldwork is still there", onDisk.contains("412"))
    }

    @Test
    fun one_online_open_pays_back_what_the_rename_withdrew() = runBlocking {
        // The fold is what makes "until the stage is next read" mean one online visit rather than
        // never, and it has to survive the write — a fold held only in composition would be re-fetched
        // on every open and lost entirely to a designer who read the stage and went back out of signal.
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

        /*
          AND THE DELETION IS HONOURED RATHER THAN REVERSED, WHICH THIS CASE USED TO ASSERT BACKWARDS.

          This fixture is the shipped build's draft: one tool row (`t1`, Charkha) AND
          `emptiedEntities: ["tool"]`. It used to assert that the fold produced TWO rows — Charkha
          recognised by its client key, plus the office's `srv-9` Reed added — and then that the payload
          carried `emptiedEntities`. Both were true and together they were a contradiction: the payload
          named every row the fold had just added back, and the server's sweep skips any row the payload
          touched (`design_workshops.py`, `if row.id in touched_ids: continue`), so the deletion
          travelled and removed nothing. The test asserted the mechanism and never the outcome.

          Now the emptied entity is not folded back at all. Charkha stays because the DRAFT holds it —
          the fold never deletes — and the row this device has never seen is not resurrected into a
          collection the designer emptied.
        */
        assertEquals("the fold adds nothing to a collection the designer emptied", 1, after.rows.size)
        assertEquals(
            listOf("Charkha"),
            after.rows.map { (it.values["name"] as JsonPrimitive).content },
        )
        assertEquals("and the collateral is counted so the screen can say so", mapOf("tool" to 1), fold.sweptRows)
        // And the deletion is still waiting, now for a save that is finally entitled to carry it.
        assertEquals(listOf("tool"), after.emptiedEntities)

        val body = buildStageBody(spec, after, emptyMap(), isAuthoritative(after, null)).body
        assertTrue("authority, earned by reading", body.replaceCollections)
        assertEquals(listOf("tool"), body.emptiedEntities)
        // The one row the draft holds is NAMED, so it survives the sweep it travels with; the office's
        // `srv-9` is not named, so the sweep is what finally removes it.
        assertEquals(1, body.entries.count { it.entityKey == "tool" })
    }

    /**
     * THE ROW MATCH ITSELF, ON A COLLECTION THE DESIGNER DID NOT EMPTY.
     *
     * Split out of the case above, which could no longer carry it: its fixture holds a recorded
     * deletion, and the fold now declines to add anything back into an emptied collection — so the
     * client-key match it was also asserting had nothing left to match against. Without this, the
     * duplication `_clientKey` exists to prevent would have lost its only on-device test.
     */
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

    /**
     * A DELETION THAT CANNOT TRAVEL, COUNTED BY THE THING THAT DECIDES "BACKED UP TO THE SERVER".
     *
     * ── WHY THIS CANNOT BE A DESKTOP JVM TEST, WHICH IS THE POINT OF PUTTING IT HERE ──────────────
     *
     * `statusOf` is the function whose answer becomes the words on the list row, and it is not pure: it
     * reads the draft off flash through the version ladder, reads this workshop's custom definition off
     * flash through [DwCustomSectionStore], and rebuilds every stage's payload to compare signatures.
     * The desktop suite pins [WorkshopSyncStatus.isFullySynced] and [WorkshopSyncStatus.summary] as
     * arithmetic over counts; only a real `filesDir` can answer whether the COUNT is ever produced.
     *
     * And this defect lived precisely in the gap between those two: the arithmetic was right and the
     * count was never taken. `statusOf` never read `emptiedEntities` at all, so a workshop holding a
     * deletion that no payload can carry answered `isFullySynced = true` and the row said "Backed up to
     * the server". A test of the arithmetic alone would have passed all the way through the defect.
     */
    @Test
    fun a_deletion_that_cannot_travel_stops_the_workshop_claiming_it_is_backed_up() = runBlocking {
        val schema = SchemaResponse(version = "test", stages = listOf(spec))

        // The designer deleted the last tool row in a courtyard. The stage has NOT been read from this
        // device, so `buildStageBody` cannot carry the deletion — see its `emptiedEntities` argument.
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
            // A server record, so there is something up there that could still be holding the rows, and
            // a matching signature, so nothing is pending and the OLD code had nothing left to report.
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
            "1 stage with a deletion not sent — the rest is backed up",
            status.summary,
        )
        // And the sentence tells the designer what to do about it, naming the stage.
        val problem = status.problems.single()
        assertTrue(problem, problem.contains("Cluster profile"))
        assertTrue(problem, problem.contains("you deleted everything in tool"))
        assertTrue(problem, problem.contains("Open the stage once with a connection"))
    }

    /**
     * AND IT CLEARS ITSELF THE ONLY WAY IT SHOULD: by the stage being read.
     *
     * Worth pinning because the count is deliberately conservative — it fires whenever the draft records
     * a deletion it is not yet authoritative enough to send, without knowing whether the server really
     * holds those rows. A conservative count that could STICK would be a permanent false alarm on the
     * one screen a designer uses to decide whether they can leave a cluster.
     */
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

        // The designer opens the stage with a connection. The fold earns the reading — and declines to
        // put the deleted rows back, which is what makes the next payload able to state the deletion.
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

    /**
     * THE REFUSAL CARD SURVIVES LEAVING THE STAGE — WHICH IS WHAT THE APP ITSELF TELLS THE DESIGNER TO DO.
     *
     * ── THE DEFECT, MEASURED ON THIS HANDSET BEFORE THIS TEST WAS WRITTEN ─────────────────────────
     *
     * Two drafts on this device carry refusals. Read off its own flash with
     * `run-as com.designprototype.workshop cat files/workshops/…/draft.json`:
     *
     *   REFUSAL-LENS / DESIGN_BRIEF             refusedFields 2   refusal key present? NO
     *   REFUSAL-LENS / MARKET_ANALYSIS_DIRECTION refusedFields 5   refusal key present? NO
     *
     * and the `failure` sentence stored beside each of them ends: *"open the stage to see which
     * answers, and what the repository holds."* The count was on the flash; the ADDRESSING was nowhere,
     * because the card lived in `StageScreen`'s `remember(stageKey)` state. So the instruction the app
     * gave was the action that destroyed the evidence — the designer arrives at a stage with nothing on
     * it while the workshop row goes on saying "7 answers refused — the rest is backed up".
     *
     * ── WHY THIS IS A DEVICE TEST AND WHAT IT DOES AND DOES NOT COVER ─────────────────────────────
     *
     * Leaving a stage and coming back is exactly "the composition is gone; re-read the draft from
     * flash", and flash is the half a desktop JVM cannot exercise: the store's atomic
     * temp → `fd.sync()` → `renameTo`, its own `Json` settings, and the version ladder that decodes a
     * document this build may not have written. This walks that, then re-decodes the card from what
     * came back — the same [dwRestoreStageRefusals] the stage screen calls on open.
     *
     * It does NOT assert that Compose draws the card; that is `refusals` being non-null, which the
     * screen branches on directly, and the sentences themselves are pinned on the JVM.
     */
    @Test
    fun a_refusal_survives_the_stage_being_left_and_the_process_dying() = runBlocking {
        // A refusal exactly as `recordStageSent` writes it: the server's error map verbatim, and the
        // ORDERING of the entries that were sent — `tool[2]` indexes the ARRAY, so it is the second
        // tool row, not the second row of the collection and not ordinal 2.
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

        // THE STAGE IS LEFT AND THE PROCESS DIES. All that is left is the document on the flash.
        val record = WorkshopDraftStore.load(context, workshopId)!!.sync.stages.getValue(stageKey)
        val stored = requireNotNull(record.refusal) { "the addressing did not survive the rename" }
        assertEquals("the error map is kept verbatim", errors, stored.errors)
        assertEquals(3, stored.sent.size)
        assertEquals("t2", stored.sent[2].rowKey)
        assertEquals(1, record.refusedFields)

        // AND THE CARD COMES BACK — re-decoded against the registry this build actually holds, by the
        // same function the stage screen calls on open.
        val report = requireNotNull(dwRestoreStageRefusals(spec, stored)) {
            "the stored refusal decoded to no card at all"
        }
        val refusal = report.refusals.single()
        assertEquals("tool", refusal.entityKey)
        assertEquals("the SECOND tool row, not the second entry", 1, refusal.rowIndex)
        assertEquals("tool[1]", refusal.address)
        assertEquals("Name", refusal.label)
        assertEquals("t2", refusal.rowKey)
        // The boxes the form marks are addressable again, which is the other half of the remedy.
        assertEquals(mapOf("tool[1]" to mapOf("name" to "Name is required")), report.byAddress)

        // What the repository HOLDS is deliberately not stored — it was measured by a read that may be
        // a day old — so it says so in that word, and asks for one read.
        assertEquals(DwHeldState.UNRECORDED, refusal.held.state)
        assertTrue(report.needsRead)
        assertTrue(
            "and it is dated, so it reads as what the repository last said rather than as now:\n" +
                report.heading,
            report.heading.contains("at 2026-08-13T02:30:00Z"),
        )
        // The same response's dropped custom key rode along, so the card cannot claim everything else
        // was saved while this response said otherwise.
        assertEquals(listOf("dyeVatCount"), report.droppedCustomKeys)

        // AND CORRECTING THE ANSWER CLEARS IT. `recordStageSent` stores null on a save that comes back
        // clean — one event clearing the count and the addressing together, so a red mark can never
        // outlive its correction.
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
