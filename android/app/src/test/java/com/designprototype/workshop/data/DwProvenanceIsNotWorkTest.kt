package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DwStageRead
import com.designprototype.workshop.ui.designworkshop.dwStageReadPlan
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN UNDERSCORE KEY IS NOT AN ANSWER, AND THE SYNC WALKS WERE COUNTING ONE AS WORK.
 *
 * ── THE KEY, AND WHY IT CAN NEVER TRAVEL ─────────────────────────────────────────────────────────
 *
 * `DwRecordingPlaceCard` offers "where were you when this stage was filled in?" on ALL TWENTY-TWO
 * stages, and writes a whole location object into the stage's singleton under `_recordingPlace`
 * (ui/designworkshop/DwLocationField.kt). The underscore is the sync protocol's own marker: `wireData`
 * drops every `_`-prefixed key from the payload by construction (WorkshopSync.kt, the
 * `if (key.startsWith("_")) return@forEach` line), because those keys are the protocol's and the
 * server answers an unknown key with a `droppedKeys` entry — a red line on every stage of every sync
 * for a value that was never going to be stored.
 *
 * So a stage whose ONLY content is a recording place holds nothing any payload can carry. Both of this
 * file's sync walks disagreed, because both asked `values.isNotEmpty()` — a question about the MAP
 * rather than about the answers in it:
 *
 *  * [dwStageSaysNothing] judged the stage non-empty, so the pass built a body for it, spent a metered
 *    PUT carrying an empty singleton, and created a server-side stage record for a stage nobody had
 *    answered. Once. Then the recorded signature matched and it went quiet — which is why this is a
 *    wasted request and a phantom record rather than a loop;
 *  * [dwStrandedStages] reported it as a stage this build cannot send, which is the PERMANENT half:
 *    the sentence sits on the workshop's status screen for the life of the draft, and that function's
 *    own comment says it must not fire "for no answers at all" — an untransmittable provenance note is
 *    exactly no answers at all.
 *
 * `ReportSource.holdsWork` already learned this rule the expensive way (its `values.isNotEmpty()` kept
 * a whole stage of the office's work OUT of the delivered document), and these two are the same rule
 * copied into the sync lane. It is now asked once, by [StageDraft.holdsAnswers], so the status a
 * designer reads and the payload a pass sends cannot answer it differently.
 *
 * ── AND ONE PLACE THE RULE MUST NOT GO, WHICH IS WHY THE LAST CASE IS HERE ───────────────────────
 *
 * `dwStageReadPlan` asks a DIFFERENT question — "would seeding this stage from the server lose
 * anything this device holds?" — and for it an underscore key is emphatically something held. See the
 * comment on that function: routing a provenance-only draft to [DwStageRead.SEED_FROM_SERVER] would
 * hand it to `fromRemote`, which adopts the server's bucket verbatim, and the next `persistLocally`
 * writes `values` wholesale — deleting the recording place off the device. The last case pins that
 * asymmetry so a later tidy-up that unifies the three tests fails here instead of in a courtyard.
 */
class DwProvenanceIsNotWorkTest {

    private val spec = StageDto(
        number = 14,
        key = "SAMPLING",
        title = "Sampling",
        entities = listOf(
            EntityDto(
                key = "sampling", cardinality = "SINGLETON", title = "Sampling",
                fields = listOf(FieldDto(key = "notes", label = "Notes", type = "LONG_TEXT")),
            ),
            EntityDto(
                key = "sampleLine", cardinality = "COLLECTION", title = "Sample",
                fields = listOf(FieldDto(key = "item", label = "Item", type = "TEXT")),
            ),
        ),
    )

    /** What the card writes: a full location object, under the protocol's own prefix. */
    private val recordingPlace = buildJsonObject {
        put("village", JsonPrimitive("Bagru"))
        put("district", JsonPrimitive("Jaipur"))
        put("state", JsonPrimitive("Rajasthan"))
    }

    /** The designer answered the provenance question on a stage they typed nothing else into. */
    private fun provenanceOnly(stageId: String = spec.key) = StageDraft(
        stageId = stageId,
        values = mapOf("_recordingPlace" to recordingPlace),
    )

    // ── The push/status gate ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a stage holding only untransmittable provenance has nothing to say`() {
        val stored = provenanceOnly()

        // FIRST, THE FACT THE OLD TEST TRIPPED OVER: the map is not empty, and that is all
        // `values.isNotEmpty()` was ever asking.
        assertTrue("the draft really does hold a key — that is the trap", stored.values.isNotEmpty())

        // THE ASSERTION THE DEFECT FAILED. Nothing under a `_` key reaches the wire, so a payload
        // built here would carry an empty singleton, no rows and no custom answers.
        assertTrue(
            "a key `wireData` strips is not something to say to the server",
            dwStageSaysNothing(spec, stored, record = null, workshopIsRemote = true),
        )
    }

    @Test
    fun `and the payload it would have sent proves the point`() {
        // Stated as arithmetic rather than as a story: this is what the metered request carried.
        val built = buildStageBody(spec, provenanceOnly(), emptyMap(), authoritative = false)
        assertTrue(
            "the singleton entry the PUT would have carried is empty",
            built.body.entries.single { it.entityKey == "sampling" }.data.isEmpty(),
        )
    }

    @Test
    fun `a retired stage holding only provenance is not a stranded stage`() {
        val schema = SchemaResponse(stages = listOf(StageDto(number = 1, key = "ORIENTATION", title = "Orientation")))
        val draft = WorkshopDraft(
            workshopId = "w1",
            stages = mapOf("SAMPLING" to provenanceOnly()),
        )

        // THE ASSERTION THE DEFECT FAILED, and the one with the longer tail: `dwStrandedStages` writes
        // a sentence onto the status screen that no action can ever discharge, because the answers it
        // names cannot be sent by any build.
        assertTrue(
            "an untransmittable provenance note is the 'no answers at all' case that comment excludes",
            dwStrandedStages(schema, draft).isEmpty(),
        )
    }

    // ── What must still count, in both walks ─────────────────────────────────────────────────────

    @Test
    fun `one real answer beside the provenance is work again`() {
        val stored = provenanceOnly().copy(
            values = mapOf(
                "_recordingPlace" to recordingPlace,
                "notes" to JsonPrimitive("Two looms idle; the warp yarn had not arrived."),
            ),
        )
        assertFalse(dwStageSaysNothing(spec, stored, record = null, workshopIsRemote = true))

        val schema = SchemaResponse(stages = listOf(StageDto(number = 1, key = "ORIENTATION", title = "Orientation")))
        assertEquals(
            1,
            dwStrandedStages(schema, WorkshopDraft(workshopId = "w1", stages = mapOf("SAMPLING" to stored))).size,
        )
    }

    @Test
    fun `rows and custom answers are untouched by this rule`() {
        // Eight of the twenty-two stages declare no singleton at all, so a draft whose `values` are
        // empty and whose rows or custom answers are not is the ORDINARY shape, and a rule about the
        // singleton map must not reach it.
        val rowsOnly = StageDraft(
            stageId = spec.key,
            values = mapOf("_recordingPlace" to recordingPlace),
            rows = listOf(DraftRow(id = dwRowId("sampleLine", "r1"), values = mapOf("item" to JsonPrimitive("Indigo")))),
        )
        val customOnly = StageDraft(
            stageId = spec.key,
            values = mapOf("_recordingPlace" to recordingPlace),
            custom = mapOf("dyeSource" to JsonPrimitive("Madder, bought in Bhuj")),
        )
        assertFalse(dwStageSaysNothing(spec, rowsOnly, record = null, workshopIsRemote = true))
        assertFalse(dwStageSaysNothing(spec, customOnly, record = null, workshopIsRemote = true))
    }

    @Test
    fun `a deletion the draft is entitled to state still speaks over the provenance`() {
        // The machinery this rule must not weaken: a draft holding a recording place AND an emptied
        // collection holds no answers and is still owed to the server. `dwStageSaysNothing`'s empty
        // test is now about the singleton's answers; the deletion arm below it is what carries this.
        val emptied = StageDraft(
            stageId = spec.key,
            values = mapOf("_recordingPlace" to recordingPlace),
            stageSeen = true,
            emptiedEntities = listOf("sampleLine"),
        )
        assertFalse(dwStageSaysNothing(spec, emptied, record = null, workshopIsRemote = true))
    }

    @Test
    fun `an empty stage the server has acknowledged still speaks`() {
        // Unchanged and load-bearing: an empty stage that HAS been sent is a stage somebody emptied.
        val sent = StageSyncRecord(signature = "whatever-the-last-save-hashed-to")
        assertFalse(dwStageSaysNothing(spec, provenanceOnly(), sent, workshopIsRemote = true))
    }

    // ── The asymmetry that must survive every future tidy-up ─────────────────────────────────────

    @Test
    fun `the stage READ still counts provenance as something this device holds`() {
        // NOT A COPY OF THE RULE ABOVE, AND MUST NEVER BECOME ONE. `dwStageReadPlan` asks whether
        // seeding from the server would LOSE something, and the recording place is something: the seed
        // arm adopts the server's bucket verbatim through `fromRemote` and the next `persistLocally`
        // writes `values` wholesale, so a provenance-only draft routed to SEED_FROM_SERVER loses the
        // one answer it had. The fold arm keeps it — it only ever ADDS keys the server holds — which
        // is why this stays where it is.
        assertEquals(
            DwStageRead.FOLD_SERVER_COPY,
            dwStageReadPlan(provenanceOnly(), remoteExists = true, canReach = true),
        )
        assertEquals(
            DwStageRead.DRAFT_AS_IS,
            dwStageReadPlan(provenanceOnly(), remoteExists = true, canReach = false),
        )
    }
}
