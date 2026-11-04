package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DwRetryKeepsServerNotesTest {

    private val droppedKeysOnly = DwStageRefusalRecord(
        errors = emptyMap(),
        sent = listOf(DwSentEntry(entityKey = "clusterCraftBackground")),
        at = "2026-08-13T03:20:00Z",
        droppedCustomKeys = listOf("dyeVatCount", "retiredQuestion"),
    )

    @Test
    fun `a dropped custom key survives Try again, though nothing was refused`() {
        val record = StageSyncRecord(
            signature = "sha-dropped",
            failure = "this workshop's own sections no longer ask 2 questions this phone still holds " +
                "an answer for, so they were not stored (dyeVatCount, retiredQuestion).",
            failedAt = "2026-08-13T03:20:00Z",
            refusedFields = 0,
            refusal = droppedKeysOnly,
        )

        assertEquals("nothing was REFUSED — this is the arm the old guard got wrong", 0, record.refusedFields)

        val after = retriedStageRecord(record)

        assertEquals("the sentence naming the un-stored answers", record.failure, after.failure)
        assertEquals(record.failedAt, after.failedAt)
        assertNotNull("and the addressing the stage card redraws from", after.refusal)
        assertEquals(listOf("dyeVatCount", "retiredQuestion"), after.refusal?.droppedCustomKeys)
    }

    @Test
    fun `a per-field refusal still survives Try again`() {
        val record = StageSyncRecord(
            signature = "sha-refused",
            failure = "the repository refused 3 of the answers in this stage…",
            failedAt = "2026-08-13T03:21:00Z",
            permanent = true,
            skewRun = "some-old-run",
            refusedFields = 3,
            refusal = DwStageRefusalRecord(
                errors = mapOf(),
                sent = listOf(DwSentEntry(entityKey = "existingProduct", ordinal = 0, rowKey = "r1")),
                at = "2026-08-13T03:21:00Z",
            ),
        )

        val after = retriedStageRecord(record)

        assertEquals(record.failure, after.failure)
        assertEquals(3, after.refusedFields)
        assertFalse(after.permanent)
        assertNull(after.skewRun)
    }

    @Test
    fun `a note about files still on this device is cleared, which is what the button is for`() {
        val record = StageSyncRecord(
            signature = "sha-files",
            failure = "three files are still on this device.",
            failedAt = "2026-08-13T03:22:00Z",
            refusedFields = 0,
            waitingOnFiles = true,
            refusal = null,
        )

        val after = retriedStageRecord(record)

        assertNull("a hold-up that resolves itself must not go on sticking", after.failure)
        assertNull(after.failedAt)
        assertFalse(after.waitingOnFiles)
    }

    @Test
    fun `a stage that never heard anything from the server is untouched and stays clean`() {
        val record = StageSyncRecord(signature = "sha-clean", syncedAt = "2026-08-13T03:23:00Z")

        val after = retriedStageRecord(record)

        assertNull(after.failure)
        assertNull(after.refusal)
        assertEquals(0, after.refusedFields)
        assertEquals("sha-clean", after.signature)
        assertEquals("the signature and the acknowledgement are not the button's to reset",
            "2026-08-13T03:23:00Z", after.syncedAt)
    }
}
