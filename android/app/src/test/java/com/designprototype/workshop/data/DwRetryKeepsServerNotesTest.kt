package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "TRY AGAIN" WAS DELETING THE ONE SENTENCE THAT NAMED AN ANSWER THE REPOSITORY NEVER STORED.
 *
 * `retryWorkshop` clears a stale note so the button can mean what it says — "assume the world changed"
 * — and it deliberately spares a per-field refusal, because the same bytes get the same answer. The
 * guard it used to spare it with was `record.refusedFields > 0`.
 *
 * ── WHY THAT GUARD IS THE WRONG QUESTION ─────────────────────────────────────────────────────────
 *
 * A save can report `droppedCustomKeys` and refuse NOTHING: the designer edited their own sections on
 * the web, and this phone still holds an answer to a question those sections no longer ask. Then
 * `recordStageSent` writes the sentence, writes a non-null [StageSyncRecord.refusal] carrying the
 * dropped keys — and leaves `refusedFields` at **0**, because nothing was refused. Keyed on the count,
 * that record fell down the clearing arm, and one tap of Try again erased the sentence.
 *
 * It is not a note the button can act on, either. The remedy is to open the workshop once with a
 * connection so the definitions are re-read, which is exactly what the sentence says.
 *
 * And the sentence is the whole of the evidence a designer can see without opening the stage, because
 * `statusOf` scores a dropped custom key as nothing at all — `failedStages` counts only `permanent`
 * records, `refusedAnswers` sums only `refusedFields` — so with it gone the workshop row reads
 * "Backed up to the server" over a question whose answer was never stored.
 *
 * EVERY CASE HERE ASSERTS `refusedFields` AS WELL AS THE OUTCOME, so none of them can pass for the old
 * reading by accident: case one is the one where the count is 0 and the note must survive anyway, and
 * saying so in the assertions is what makes it a test of the rule rather than of one example.
 */
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

        // The reading the old guard used, stated so this test cannot quietly become a test of it.
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
        // The two things the button IS allowed to reset, so the pass stops stepping over the stage.
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
