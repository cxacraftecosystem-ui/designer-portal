package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `a drop-only save records the keys and no refusal count at all`() {
        val record = StageSyncRecord(
            signature = "sha-of-the-accepted-body",
            refusedFields = 0,
            refusal = DwStageRefusalRecord(
                errors = emptyMap(),
                sent = emptyList(),
                at = "2026-09-03T09:00:00Z",
                droppedCustomKeys = listOf("dyeVatCount", "loomsWorking"),
            ),
        )

        assertEquals(0, record.refusedFields)
        assertFalse(record.permanent)
        assertFalse("the record survives precisely because of the dropped keys", record.refusal!!.isEmpty)
        assertEquals(2, record.refusal?.droppedCustomKeys?.size)
    }

    @Test
    fun `a workshop holding an answer the server did not store is NOT backed up`() {
        assertFalse(status(droppedAnswers = 1).isFullySynced)
        assertTrue(
            "and everything else really is clean, which is exactly why nothing else caught it",
            status().isFullySynced,
        )
    }

    @Test
    fun `a dropped answer is not a refused stage`() {
        assertFalse(status(droppedAnswers = 3).hasFailures)
    }

    @Test
    fun `the row says the answers were not stored, and never that they were refused`() {
        val summary = status(droppedAnswers = 1).summary
        assertEquals("1 answer not stored — the rest is saved online", summary)
        assertFalse("\n$summary", summary.contains("refused"))
        assertFalse("nothing here is waiting for a connection", summary.contains("waiting to upload"))
        assertEquals("2 answers not stored — the rest is saved online", status(droppedAnswers = 2).summary)
    }

    @Test
    fun `a refusal, a deletion and a drop each get their own clause`() {
        assertEquals(
            "2 answers not accepted, 1 stage with a deletion not sent, 3 answers not stored — " +
                "the rest is saved online",
            status(refusedAnswers = 2, unsentDeletions = 1, droppedAnswers = 3).summary,
        )
    }

    @Test
    fun `pending work still says it is waiting to upload`() {
        assertEquals("1 stage waiting to upload", status(pendingStages = 1, droppedAnswers = 2).summary)
    }

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
        assertTrue(
            "not 'correct it' — there is no box to correct:\n${banner.detail}",
            banner.detail.contains(
                "Open the workshop once with a connection to clear this."
            ),
        )
        assertFalse(
            "the promise that was false for a whole release:\n${banner.detail}",
            banner.detail.contains("a sync will NOT change that"),
        )
        assertFalse("\n${banner.detail}", banner.detail.contains("correct them"))
        assertTrue("and where the rest of the work is", banner.detail.contains("Everything else is saved online"))
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
        val banner = dwDeviceSyncBanner(
            workshops = 1, stages = 0, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 2, unsentDeletions = 0, droppedAnswers = 1,
        )!!
        assertEquals("2 answers not accepted · 1 answer not stored", banner.headline)
        assertTrue(banner.detail, banner.detail.contains("Those answers were not accepted"))
        assertTrue(banner.detail, banner.detail.contains("with a connection to clear this"))
        assertEquals(
            "the drop's sentence must appear exactly once",
            1,
            Regex("sections no longer ask").findAll(banner.detail).count(),
        )
        assertFalse(banner.waiting)
    }

    @Test
    fun `pending work beside a drop keeps the cloud-off icon and still names the drop`() {
        val banner = dwDeviceSyncBanner(
            workshops = 2, stages = 3, files = 0, bytesText = "0 B",
            failures = 0, refusedAnswers = 0, unsentDeletions = 0, droppedAnswers = 2,
        )!!
        assertTrue("three stages genuinely are waiting for a bar", banner.waiting)
        assertEquals("3 stages · 2 answers not stored", banner.headline)
        assertTrue(banner.detail, banner.detail.contains("upload whenever there is a connection"))
        assertTrue(banner.detail, banner.detail.contains("2 answers were not stored"))
    }

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
        assertEquals(emptySet<String>(), dwWithheldCustomKeys(definition("loomsWorking"), spec.key, null))
        assertEquals(
            setOf("loomsWorking", "dyeVatCount"),
            customEntry(null, definition("loomsWorking", "dyeVatCount"))!!.data.keys,
        )
    }

    @Test
    fun `a dropped key the re-read definition no longer declares is left out of the next body`() {
        val record = droppedRecord("dyeVatCount")
        val fresh = definition("loomsWorking")

        assertEquals(setOf("dyeVatCount"), dwWithheldCustomKeys(fresh, spec.key, record))

        val entry = customEntry(record, fresh)!!
        assertEquals(setOf("loomsWorking"), entry.data.keys)
        assertEquals(JsonPrimitive(12), entry.data["loomsWorking"])

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

        val afterClean = StageSyncRecord(
            signature = signatureOf(corrective.body),
            refusal = null,
            withheldCustomKeys = corrective.withheldCustom,
        )
        assertEquals("the count really is discharged", 0, afterClean.refusal?.droppedCustomKeys?.size ?: 0)

        val next = buildStageBody(
            spec, draft, emptyMap(), authoritative = true,
            customHeld = dwCustomHeldFor(fresh, spec.key),
            withheldCustomKeys = dwWithheldCustomKeys(fresh, spec.key, afterClean),
        )
        assertEquals(afterClean.signature, signatureOf(next.body))

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

    @Test
    fun `the withheld list is on disk, and an older draft decodes to an empty one`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val record = StageSyncRecord(signature = "sha", withheldCustomKeys = listOf("dyeVatCount"))
        val restored = json.decodeFromString(
            StageSyncRecord.serializer(),
            json.encodeToString(StageSyncRecord.serializer(), record),
        )
        assertEquals(listOf("dyeVatCount"), restored.withheldCustomKeys)

        val older = json.decodeFromString(
            StageSyncRecord.serializer(),
            """{"signature":"sha","refusedFields":0}""",
        )
        assertEquals(emptyList<String>(), older.withheldCustomKeys)
        assertEquals(emptySet<String>(), dwWithheldCustomKeys(null, spec.key, older))
    }

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
        assertEquals(0, clean.takeIf { !it.isEmpty }?.droppedCustomKeys?.size ?: 0)
        assertTrue(status(droppedAnswers = 0).isFullySynced)
    }

    @Test
    fun `a question that comes back is declared again, so the answer is sent again`() {
        val restored = definition("loomsWorking", "dyeVatCount")
        val record = droppedRecord("dyeVatCount").copy(withheldCustomKeys = listOf("dyeVatCount"))

        assertEquals(emptySet<String>(), dwWithheldCustomKeys(restored, spec.key, record))
        assertEquals(setOf("loomsWorking", "dyeVatCount"), customEntry(record, restored)!!.data.keys)
        assertEquals(
            emptyList<String>(),
            buildStageBody(
                spec, draft, emptyMap(), authoritative = true,
                customHeld = dwCustomHeldFor(restored, spec.key),
                withheldCustomKeys = dwWithheldCustomKeys(restored, spec.key, record),
            ).withheldCustom,
        )
        assertEquals(JsonPrimitive(3), draft.custom["dyeVatCount"])
    }

    @Test
    fun `a retired question still counts as declared and is never withheld`() {
        val record = droppedRecord("dyeVatCount")
        assertEquals(
            emptySet<String>(),
            dwWithheldCustomKeys(definition("loomsWorking", "dyeVatCount", retired = true), spec.key, record),
        )
    }

    @Test
    fun `a phone that has not re-read the sections keeps sending, and keeps counting`() {
        val record = droppedRecord("dyeVatCount")
        val stale = definition("loomsWorking", "dyeVatCount")
        assertEquals(emptySet<String>(), dwWithheldCustomKeys(stale, spec.key, record))
        assertEquals(setOf("loomsWorking", "dyeVatCount"), customEntry(record, stale)!!.data.keys)
        assertFalse(status(droppedAnswers = 1).isFullySynced)
    }

    @Test
    fun `a lost definition withholds only what the server itself named`() {
        val record = droppedRecord("dyeVatCount")
        assertEquals(setOf("dyeVatCount"), dwWithheldCustomKeys(null, spec.key, record))
        val entry = customEntry(record, null)!!
        assertEquals(setOf("loomsWorking"), entry.data.keys)
    }

    @Test
    fun `a device with nothing outstanding still draws no banner`() {
        assertEquals(
            null,
            dwDeviceSyncBanner(
                workshops = 0, stages = 0, files = 0, bytesText = "0 B",
                failures = 0, refusedAnswers = 0, unsentDeletions = 0, droppedAnswers = 0,
            ),
        )
    }
}
