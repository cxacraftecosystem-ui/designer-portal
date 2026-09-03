package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN ANSWER THE SERVER HAD NOWHERE TO PUT WAS SCORED AS "BACKED UP TO THE SERVER".
 *
 * ── THE GAP, WHICH THIS REPOSITORY HAD ALREADY WRITTEN DOWN VERBATIM ─────────────────────────────
 *
 * `WorkshopSyncEngine.retryWorkshop` carried the whole of it in a comment: a stage save that reports
 * ONLY `droppedCustomKeys` — the designer edited their own sections on the web and this phone still
 * holds an answer to a question those sections no longer ask — writes the sentence, writes a non-null
 * [StageSyncRecord.refusal] carrying the keys, and leaves [StageSyncRecord.refusedFields] at **0**,
 * because nothing was REFUSED. `statusOf` then scored it as nothing at all: `failedStages` counts only
 * `permanent` records and [WorkshopSyncStatus.refusedAnswers] sums only `refusedFields`. The payload
 * was accepted, so its signature matches and no pass will ever send it again; nothing pending, nothing
 * failed, [WorkshopSyncStatus.isFullySynced] true, and the list row read "Backed up to the server" over
 * an answer that exists on this phone and nowhere else.
 *
 * That comment ended *"it needs a counter of its own on [WorkshopSyncStatus]"*. These pin the counter.
 *
 * ── THE THIRD INSTANCE OF ONE SHAPE ──────────────────────────────────────────────────────────────
 *
 * [WorkshopSyncStatus.refusedAnswers] and [WorkshopSyncStatus.unsentDeletions] were added for exactly
 * this: work this device holds that no future payload will carry, so the signature matches for ever
 * and nothing is ever pending. `DwUnsentDeletionTest` is the neighbour; what differs here is the
 * REMEDY, and every assertion below about wording exists because a designer sent to the wrong screen
 * by a shared sentence loses a day looking for a red box that was never drawn.
 */
class DwDroppedAnswerTest {

    private fun status(
        pendingStages: Int = 0,
        pendingMedia: Int = 0,
        refusedAnswers: Int = 0,
        unsentDeletions: Int = 0,
        droppedAnswers: Int = 0,
    ) = WorkshopSyncStatus(
        workshopId = "w1",
        remoteId = "srv-1",
        pendingStages = pendingStages,
        pendingMedia = pendingMedia,
        pendingMediaBytes = 0,
        failedStages = 0,
        failedMedia = 0,
        problems = emptyList(),
        lastSuccessAt = null,
        lastError = null,
        releasableMedia = 0,
        releasableBytes = 0,
        refusedAnswers = refusedAnswers,
        unsentDeletions = unsentDeletions,
        droppedAnswers = droppedAnswers,
    )

    // ── What the record the pass already writes actually holds ───────────────────────────────────

    /**
     * THE COUNT COMES OFF EVIDENCE THAT WAS ALREADY BEING WRITTEN, and this is why the fix is a sum
     * rather than a new wire field: `recordStageSent` has always stored `droppedCustomKeys` inside
     * [StageSyncRecord.refusal], from the same response that produced `refusedFields` beside it. What
     * was missing was that anything read it back.
     */
    @Test
    fun `a drop-only save records the keys and no refusal count at all`() {
        val record = StageSyncRecord(
            signature = "sha-of-the-accepted-body",
            refusedFields = 0,
            refusal = DwStageRefusalRecord(
                // EMPTY, and that is the whole point of the case: the response carried no `errors`
                // map at all. The record is still non-null because `isEmpty` consults the dropped
                // keys too — which is the one thing that kept any evidence of this on disk.
                errors = emptyMap(),
                sent = emptyList(),
                at = "2026-09-03T09:00:00Z",
                droppedCustomKeys = listOf("dyeVatCount", "loomsWorking"),
            ),
        )

        // THE TWO FACTS THAT MADE THIS INVISIBLE, asserted together: nothing was refused, and the
        // stage is not permanent — so neither `refusedAnswers` nor `failedStages` could ever see it.
        assertEquals(0, record.refusedFields)
        assertFalse(record.permanent)
        assertFalse("the record survives precisely because of the dropped keys", record.refusal!!.isEmpty)
        // And the evidence the counter is summed from is right there on the record.
        assertEquals(2, record.refusal?.droppedCustomKeys?.size)
    }

    // ── The row's own answer ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a workshop holding an answer the server did not store is NOT backed up`() {
        // THE ASSERTION THE DEFECT FAILED, and the line that made "Backed up to the server" a lie.
        assertFalse(status(droppedAnswers = 1).isFullySynced)
        assertTrue(
            "and everything else really is clean, which is exactly why nothing else caught it",
            status().isFullySynced,
        )
    }

    /**
     * IT IS NOT A FAILURE AND MUST NOT BE COUNTED AS ONE. The save succeeded; twenty other fields
     * landed. Marking the stage refused would stop the pass sending anything else on it, and would put
     * a red mark on a form where nothing is wrong.
     */
    @Test
    fun `a dropped answer is not a refused stage`() {
        assertFalse(status(droppedAnswers = 3).hasFailures)
    }

    @Test
    fun `the row says the answers were not stored, and never that they were refused`() {
        val summary = status(droppedAnswers = 1).summary
        assertEquals("1 answer not stored — the rest is backed up", summary)
        // THE WORD MATTERS. "Refused" is an answer the repository read and declined, and its remedy is
        // to correct it on the form. This one it had nowhere to put, and correcting it is impossible:
        // the section that asked the question is gone. One word sends half the designers to a stage
        // with no marks on it to hunt for a box that does not exist.
        assertFalse("\n$summary", summary.contains("refused"))
        assertFalse("nothing here is waiting for a connection", summary.contains("waiting to upload"))
        assertEquals("2 answers not stored — the rest is backed up", status(droppedAnswers = 2).summary)
    }

    @Test
    fun `a refusal, a deletion and a drop each get their own clause`() {
        // All three can be true of one workshop, and each ends somewhere different for the person
        // holding the phone. Folded together, correcting the refusal would leave the row still
        // refusing to say "backed up" with nothing on screen explaining why.
        assertEquals(
            "2 answers refused, 1 stage with a deletion not sent, 3 answers not stored — " +
                "the rest is backed up",
            status(refusedAnswers = 2, unsentDeletions = 1, droppedAnswers = 3).summary,
        )
    }

    /** Pending work still dominates the line: it is the thing a connection will actually fix. */
    @Test
    fun `pending work still says it is waiting to upload`() {
        assertEquals("1 stage waiting to upload", status(pendingStages = 1, droppedAnswers = 2).summary)
    }

    // ── The banner above the list ────────────────────────────────────────────────────────────────

    /**
     * THE BANNER MUST NOT SAY "IT UPLOADS WHENEVER THERE IS A CONNECTION" OVER THIS.
     *
     * Making `droppedAnswers` a term of `isFullySynced` is what puts such a workshop into the caller's
     * `outstanding` list — and with no counter handed down it would contribute a workshop to the
     * banner's count and nothing to any of its numbers, so the headline would fall through to
     * "Waiting to upload". That is word for word the defect [dwDeviceSyncBanner] was written to end,
     * arriving for the third time, one field along each time.
     */
    @Test
    fun `a dropped answer is never described as waiting for a connection`() {
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 0, unsentDeletions = 0, droppedAnswers = 1,
        )!!
        assertEquals("1 answer not stored", banner.headline)
        assertFalse("a sync will not move it, so it must not wear the cloud-off icon", banner.waiting)
        assertFalse(
            "the sentence that sends a designer looking for signal:\n${banner.detail}",
            banner.detail.contains("it uploads whenever there is a connection"),
        )
        // AND THE ONE ACT THAT CLEARS IT, which is neither of the other two remedies on this surface.
        //
        // THE WORDING CHANGED WITH THE MECHANISM (2026-09-03). It used to say "a sync will NOT change
        // that. Open the workshop once with a connection so this phone re-reads the sections" — and
        // the second half was an instruction that provably did nothing, because `buildStageBody` sent
        // the orphaned key in every payload and the refusal was re-recorded on every pass. Now the
        // read is what unsticks it and the ORDINARY sync behind it is what carries it away, so the
        // sentence names both, in that order. See `dwWithheldCustomKeys`.
        assertTrue(
            "not 'correct it' — there is no box to correct:\n${banner.detail}",
            banner.detail.contains(
                "Open the workshop once with a connection — it re-reads the sections, and the next " +
                    "sync clears this."
            ),
        )
        assertFalse(
            "the promise that was false for a whole release:\n${banner.detail}",
            banner.detail.contains("a sync will NOT change that"),
        )
        assertFalse("\n${banner.detail}", banner.detail.contains("correct them"))
        assertTrue("and where the rest of the work is", banner.detail.contains("Everything else is on the server"))
    }

    @Test
    fun `one dropped answer reads as one, all the way through the sentence`() {
        val one = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 0, unsentDeletions = 0, droppedAnswers = 1,
        )!!
        assertTrue(one.detail, one.detail.contains("1 answer was not stored"))
        assertTrue(one.detail, one.detail.contains("no longer ask that question"))

        val many = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 0, unsentDeletions = 0, droppedAnswers = 4,
        )!!
        assertTrue(many.detail, many.detail.contains("4 answers were not stored"))
        assertTrue(many.detail, many.detail.contains("no longer ask those questions"))
    }

    @Test
    fun `a drop beside a refusal is appended rather than swallowed by the refusal arm`() {
        // The refusal arm runs first and ends "open the workshop, then the stage, to see which and
        // correct them" — an instruction that cannot be carried out for a dropped answer, because
        // nothing is marked on the stage. Both sentences have to be present, once each.
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 2, unsentDeletions = 0, droppedAnswers = 1,
        )!!
        assertEquals("2 answers refused · 1 answer not stored", banner.headline)
        assertTrue(banner.detail, banner.detail.contains("the repository has already read them and declined"))
        assertTrue(banner.detail, banner.detail.contains("re-reads the sections"))
        assertEquals(
            "the drop's sentence must appear exactly once",
            1,
            Regex("re-reads the sections").findAll(banner.detail).count(),
        )
        assertFalse(banner.waiting)
    }

    @Test
    fun `pending work beside a drop keeps the cloud-off icon and still names the drop`() {
        // Both halves are true of one device: the stages really are waiting for a connection, and the
        // dropped answer really is not. The first half must not be allowed to cover the second.
        val banner = dwDeviceSyncBanner(
            workshops = 2, stages = 3, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 0, unsentDeletions = 0, droppedAnswers = 2,
        )!!
        assertTrue("three stages genuinely are waiting for a bar", banner.waiting)
        assertEquals("3 stages · 2 answers not stored", banner.headline)
        assertTrue(banner.detail, banner.detail.contains("upload whenever there is a connection"))
        assertTrue(banner.detail, banner.detail.contains("2 answers were not stored"))
    }

    // ── The discharge, which the counter shipped without ─────────────────────────────────────────

    /**
     * THE COUNTER COULD NEVER RETURN TO ZERO, AND THE BANNER'S OWN REMEDY COULD NOT MOVE IT.
     *
     * The loop, every step of it this app's own code: `buildStageBody` sent the WHOLE stored custom
     * bucket, so an orphaned key rode in every payload; `plan_custom_write` dropped it every time,
     * because the definition genuinely no longer carries it; `recordStageSent` re-recorded the
     * identical refusal from that response; and `statusOf` summed `refusal.droppedCustomKeys` again.
     * Signature stable, refusal restamped, count restamped — for ever, under a banner telling the
     * designer to open the workshop with a connection, which changed nothing whatever.
     *
     * `dwWithheldCustomKeys` is the discharge, and it is TWO facts and never one: the server said it
     * had nowhere to put the key, AND this device's own definition no longer declares it. The tests
     * below pin both halves, because either one alone is a different defect — a stale opinion that
     * outlives its evidence, or a client-side deletion on every phone whose cache is merely behind.
     */
    private val spec = StageDto(
        number = 5,
        key = "TRADITIONAL_PROCESS_BASELINE",
        title = "Traditional process baseline",
        entities = listOf(
            EntityDto(
                key = "traditionalProcess", cardinality = "SINGLETON", title = "Traditional process",
                fields = listOf(FieldDto(key = "currentProblems", label = "Current problems", type = "LONG_TEXT")),
            ),
        ),
    )

    /** A definition that asks exactly the named questions at [spec]. */
    private fun definition(vararg keys: String, retired: Boolean = false) = DwCustomCache(
        workshopId = "w1",
        customSchemaVersion = "d1",
        complete = true,
        sections = listOf(
            DwCustomSectionDto(
                key = "loomAudit",
                stageKey = spec.key,
                fields = keys.map { DwCustomFieldDto(key = it, label = it, retired = retired) },
            ),
        ),
    )

    /** The record `recordStageSent` writes after a save the server dropped [keys] from. */
    private fun droppedRecord(vararg keys: String) = StageSyncRecord(
        signature = "sha-of-the-accepted-body",
        refusedFields = 0,
        refusal = DwStageRefusalRecord(
            errors = emptyMap(),
            sent = emptyList(),
            at = "2026-09-03T09:00:00Z",
            droppedCustomKeys = keys.toList(),
        ),
    )

    private val draft = StageDraft(
        stageId = spec.key,
        values = mapOf("currentProblems" to JsonPrimitive("Slow warping")),
        custom = mapOf(
            "loomsWorking" to JsonPrimitive(12),
            "dyeVatCount" to JsonPrimitive(3),
        ),
        customSeen = true,
    )

    private fun customEntry(
        record: StageSyncRecord?,
        definition: DwCustomCache?,
    ) = buildStageBody(
        spec,
        draft,
        emptyMap(),
        authoritative = true,
        customHeld = dwCustomHeldFor(definition, spec.key),
        withheldCustomKeys = dwWithheldCustomKeys(definition, spec.key, record),
    ).body.entries.firstOrNull { it.entityKey == "_custom" }

    @Test
    fun `with no refusal standing nothing is ever withheld`() {
        // THE COMMON CASE, AND IT MUST BE BYTE-IDENTICAL TO WHAT EVERY BUILD BEFORE THIS SENT. A
        // payload that changed for a stage with no refusal on it would restamp the signature of every
        // stage on every handset in the fleet, and every one of them would re-send once.
        assertEquals(emptySet<String>(), dwWithheldCustomKeys(definition("loomsWorking"), spec.key, null))
        assertEquals(
            setOf("loomsWorking", "dyeVatCount"),
            customEntry(null, definition("loomsWorking", "dyeVatCount"))!!.data.keys,
        )
    }

    /**
     * THE RECOVERY. The designer opened the workshop with a connection, `dwCustomDefinition` re-read
     * the sections, and the question the server dropped is not in them — so the key is held back and
     * the next payload is one the server can answer cleanly.
     */
    @Test
    fun `a dropped key the re-read definition no longer declares is left out of the next body`() {
        val record = droppedRecord("dyeVatCount")
        val fresh = definition("loomsWorking")

        assertEquals(setOf("dyeVatCount"), dwWithheldCustomKeys(fresh, spec.key, record))

        val entry = customEntry(record, fresh)!!
        // THE ORPHAN IS GONE AND THE LIVE ANSWER IS NOT. It is a strip and not an early return: a
        // bucket holding one dead key beside six good ones still sends the six.
        assertEquals(setOf("loomsWorking"), entry.data.keys)
        assertEquals(JsonPrimitive(12), entry.data["loomsWorking"])

        // AND IT REALLY IS A DIFFERENT PAYLOAD, which is what makes `statusOf` report the stage
        // pending and the pass send it. An identical body would match the recorded signature and
        // nothing would ever be sent again — the whole of the original defect.
        assertNotEquals(
            signatureOf(buildStageBody(spec, draft, emptyMap(), authoritative = true, customHeld = true).body),
            signatureOf(
                buildStageBody(
                    spec, draft, emptyMap(), authoritative = true, customHeld = true,
                    withheldCustomKeys = setOf("dyeVatCount"),
                ).body
            ),
        )
    }

    /**
     * THE FIX'S OWN TRAP, AND WHY THE BUILDER NEEDS A SECOND FIELD TO READ.
     *
     * The withholding is evidence-led — it needs the server to have said "no home for this key" — and
     * the evidence is destroyed by the very save it makes possible: a clean response writes
     * `refusal = null`, which is exactly what lets `droppedAnswers` reach zero. On that alone the next
     * pass would find no refusal, put the key straight back, get it dropped again, and the stage would
     * alternate between two payloads twice a minute for the life of the installation — a worse defect
     * than the permanent counter it replaced. [StageSyncRecord.withheldCustomKeys] is the durable
     * half: what the last payload actually LEFT OUT, which no response can report.
     */
    @Test
    fun `the withholding survives the clean save, so the stage sends once and then stops`() {
        val fresh = definition("loomsWorking")
        val afterDrop = droppedRecord("dyeVatCount")

        val corrective = buildStageBody(
            spec, draft, emptyMap(), authoritative = true,
            customHeld = dwCustomHeldFor(fresh, spec.key),
            withheldCustomKeys = dwWithheldCustomKeys(fresh, spec.key, afterDrop),
        )
        assertEquals(listOf("dyeVatCount"), corrective.withheldCustom)

        // What `recordStageSent` writes when that save comes back clean: no refusal, so the counter
        // clears — and the withheld list, so the builder remembers what the response cannot say.
        val afterClean = StageSyncRecord(
            signature = signatureOf(corrective.body),
            refusal = null,
            withheldCustomKeys = corrective.withheldCustom,
        )
        assertEquals("the count really is discharged", 0, afterClean.refusal?.droppedCustomKeys?.size ?: 0)

        // THE NEXT PASS BUILDS THE IDENTICAL BODY, so the signature matches and nothing is sent.
        val next = buildStageBody(
            spec, draft, emptyMap(), authoritative = true,
            customHeld = dwCustomHeldFor(fresh, spec.key),
            withheldCustomKeys = dwWithheldCustomKeys(fresh, spec.key, afterClean),
        )
        assertEquals(afterClean.signature, signatureOf(next.body))

        // AND ON THE REFUSAL ALONE IT WOULD NOT HAVE. This is the loop, asserted rather than argued.
        val forgetful = afterClean.copy(withheldCustomKeys = emptyList())
        assertNotEquals(
            "the key comes straight back, and the server drops it again",
            afterClean.signature,
            signatureOf(
                buildStageBody(
                    spec, draft, emptyMap(), authoritative = true,
                    customHeld = dwCustomHeldFor(fresh, spec.key),
                    withheldCustomKeys = dwWithheldCustomKeys(fresh, spec.key, forgetful),
                ).body
            ),
        )
    }

    /**
     * AND IT HAS TO SURVIVE THE PROCESS DYING, or the loop comes back on the next app open.
     *
     * The whole value of [StageSyncRecord.withheldCustomKeys] is that it outlives the response that
     * could not report it. A field that vanished with the composition would leave the next cold start
     * putting the orphaned key straight back into the payload.
     */
    @Test
    fun `the withheld list is on disk, and an older draft decodes to an empty one`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val record = StageSyncRecord(signature = "sha", withheldCustomKeys = listOf("dyeVatCount"))
        val restored = json.decodeFromString(
            StageSyncRecord.serializer(),
            json.encodeToString(StageSyncRecord.serializer(), record),
        )
        assertEquals(listOf("dyeVatCount"), restored.withheldCustomKeys)

        // Additive and defaulted, the rung `refusal` was added on: a draft written by an earlier build
        // has no such key and decodes into exactly the behaviour it had.
        val older = json.decodeFromString(
            StageSyncRecord.serializer(),
            """{"signature":"sha","refusedFields":0}""",
        )
        assertEquals(emptyList<String>(), older.withheldCustomKeys)
        assertEquals(emptySet<String>(), dwWithheldCustomKeys(null, spec.key, older))
    }

    /**
     * AND THE OTHER END OF THE SAME SAVE: the clean response writes a null refusal, and the counter
     * that is summed from it drops to zero on that disk write.
     *
     * Asserted on the two pure facts `recordStageSent` composes rather than by driving it — that
     * function needs a Context and a disk. `refusal = DwStageRefusalRecord(…).takeIf { !it.isEmpty }`
     * is the line; `isEmpty` is what decides whether anything is stored at all.
     */
    @Test
    fun `the clean save that follows writes no refusal, and the workshop is backed up`() {
        val clean = DwStageRefusalRecord(
            errors = emptyMap(),
            sent = listOf(DwSentEntry(entityKey = "_custom")),
            at = "2026-09-03T09:45:00Z",
            droppedCustomKeys = emptyList(),
        )
        assertTrue("so `takeIf { !it.isEmpty }` stores null", clean.isEmpty)
        assertNull("the refusal on the record is written null by the same save", clean.takeIf { !it.isEmpty })
        // Which is what `statusOf` sums, so the count is zero and the row may finally say so.
        assertEquals(0, clean.takeIf { !it.isEmpty }?.droppedCustomKeys?.size ?: 0)
        assertTrue(status(droppedAnswers = 0).isFullySynced)
    }

    /**
     * THE OTHER DIRECTION, AND THE REASON THE RULE IS TWO FACTS. The designer put the question BACK on
     * the web. The re-read definition declares it again, so the answer this phone never threw away is
     * sent again, unchanged — a withholding that could not be undone would be a silent client-side
     * deletion of a designer's own answer.
     */
    @Test
    fun `a question that comes back is declared again, so the answer is sent again`() {
        val restored = definition("loomsWorking", "dyeVatCount")
        // Both halves of the memory, so this is asserted against the WORST case: the server said the
        // key was dropped AND the last payload withheld it. The re-read definition overrides both.
        val record = droppedRecord("dyeVatCount").copy(withheldCustomKeys = listOf("dyeVatCount"))

        assertEquals(emptySet<String>(), dwWithheldCustomKeys(restored, spec.key, record))
        assertEquals(setOf("loomsWorking", "dyeVatCount"), customEntry(record, restored)!!.data.keys)
        // And the save that lands writes the memory back empty, so it cannot outlive its reason.
        assertEquals(
            emptyList<String>(),
            buildStageBody(
                spec, draft, emptyMap(), authoritative = true,
                customHeld = dwCustomHeldFor(restored, spec.key),
                withheldCustomKeys = dwWithheldCustomKeys(restored, spec.key, record),
            ).withheldCustom,
        )
        // Never deleted from the draft at any point — withholding is about the PAYLOAD only.
        assertEquals(JsonPrimitive(3), draft.custom["dyeVatCount"])
    }

    /**
     * A RETIRED FIELD IS STILL DECLARED, mirroring the server's own membership rule rather than
     * inventing a second one. `validate_custom_entry` builds `by_key` from `fields_for`, which
     * includes retired fields, so a retired key is NOT dropped — and withholding it here would strand
     * an answer the repository is perfectly willing to store.
     */
    @Test
    fun `a retired question still counts as declared and is never withheld`() {
        val record = droppedRecord("dyeVatCount")
        assertEquals(
            emptySet<String>(),
            dwWithheldCustomKeys(definition("loomsWorking", "dyeVatCount", retired = true), spec.key, record),
        )
    }

    /**
     * AND THE STALE-DEFINITION CASE, WHICH IS THE ONE THIS RULE MUST NOT ACT ON. The phone has NOT
     * re-read the sections, so its copy still declares the key: it goes on being sent, and the count
     * goes on standing, until the designer performs the act the banner names. That is the honest
     * state — the phone has no evidence yet that the question is gone for good.
     */
    @Test
    fun `a phone that has not re-read the sections keeps sending, and keeps counting`() {
        val record = droppedRecord("dyeVatCount")
        val stale = definition("loomsWorking", "dyeVatCount")
        assertEquals(emptySet<String>(), dwWithheldCustomKeys(stale, spec.key, record))
        assertEquals(setOf("loomsWorking", "dyeVatCount"), customEntry(record, stale)!!.data.keys)
        assertFalse(status(droppedAnswers = 1).isFullySynced)
    }

    /**
     * A DEVICE HOLDING NO DEFINITION AT ALL DECLARES NOTHING, so the server's own answer is the only
     * evidence there is and it is acted on. This is narrower than it looks: it withholds ONLY a key
     * the repository has already refused a home, and every other answer in the bucket still goes —
     * which is the promise `DwCustomPayloadTest` pins for a lost cache.
     */
    @Test
    fun `a lost definition withholds only what the server itself named`() {
        val record = droppedRecord("dyeVatCount")
        assertEquals(setOf("dyeVatCount"), dwWithheldCustomKeys(null, spec.key, record))
        val entry = customEntry(record, null)!!
        assertEquals(setOf("loomsWorking"), entry.data.keys)
    }

    @Test
    fun `a device with nothing outstanding still draws no banner`() {
        // The new counter must not make a clean phone grow a permanent bar: a bar people always see
        // is a bar people stop reading, and then the day it changes nobody notices.
        assertEquals(
            null,
            dwDeviceSyncBanner(
                workshops = 0, stages = 0, files = 0, bytesText = "0 B",
                failures = 0, refusedAnswers = 0, unsentDeletions = 0, droppedAnswers = 0,
            ),
        )
    }
}
