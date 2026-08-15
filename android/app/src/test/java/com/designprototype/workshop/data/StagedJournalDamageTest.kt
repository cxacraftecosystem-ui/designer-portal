package com.designprototype.workshop.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A damaged staged-object journal is damage, and a journal is replaced atomically or not at all.
 *
 * WHAT WAS WRONG. `write` was a bare `file.writeText(...)`, which truncates the file and then writes,
 * and `read` was `runCatching { … }.getOrDefault(emptyList())`. A process killed inside that window —
 * a low-memory kill during a large upload over a village connection, which is precisely the condition
 * this journal exists for — left a truncated array; the next launch read it as ZERO entries with no
 * error and no trace, and the next `record` overwrote the remains.
 *
 * WHAT THAT COSTS, since nothing a designer can see is involved. The lost field is `uploadId`. Until
 * a multipart upload is completed there is no object at the key at all, only uploaded parts, which
 * are billed and which only `AbortMultipartUpload` can remove — and that call needs the uploadId. So
 * a journal read as empty is storage nobody can ever name again, which is exactly the loss the
 * `uploadId` field was added to prevent, arriving through the write path instead of the record shape.
 *
 * Its three siblings — `OfflineOutbox`, `WorkshopDraftStore` and `StageSchemaStore` — all write temp
 * file → `fd.sync()` → `renameTo` and all treat an unreadable file as damage. This journal's own KDoc
 * claimed "the same file + Mutex + kotlinx mechanism the offline outbox uses", which was true of the
 * mutex and false of the write. These tests hold it to the claim.
 *
 * The file layer takes a DIRECTORY rather than a Context so this can be a JVM test: there is no
 * Robolectric on this module's unit-test classpath, so a test cannot manufacture a Context, and a
 * defect about bytes on disk that can only be proved on a device is a defect nothing proves.
 */
class StagedJournalDamageTest {

    private lateinit var dir: File

    private val journal: File get() = File(dir, "staged-objects.json")
    private val damaged: List<File>
        get() = dir.listFiles()?.filter { it.name.startsWith("staged-objects.damaged-") }.orEmpty()

    @Before
    fun setUp() {
        dir = File.createTempFile("staged-journal", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Two whole entries and a third cut off mid-key — what a kill during `writeText` leaves behind. */
    private fun writeTruncatedJournal() {
        journal.writeText(
            """[{"objectKey":"media/one.mp4","owner":"dead-run","uploadId":"UP-1"},""" +
                """{"objectKey":"media/two.jpg","owner":"dead-run","checksum":"abc"},""" +
                """{"objectKey":"media/thr"""
        )
    }

    // ── Damage is not emptiness ──────────────────────────────────────────────────────────────────

    /**
     * The whole finding in one assertion: the truncated file must not read as "nothing staged".
     *
     * The uploadId is the part that matters. An entry recovered without it names a key that, for a
     * multipart, does not exist yet — so the sweep would call delete on a key and reclaim nothing.
     */
    @Test
    fun `a truncated journal is not read as an empty one`() {
        writeTruncatedJournal()
        val entries = StagedJournal.read(dir)
        assertEquals(listOf("media/one.mp4", "media/two.jpg"), entries.map { it.objectKey })
        assertEquals("UP-1", entries.first { it.objectKey == "media/one.mp4" }.uploadId)
        assertEquals("abc", entries.first { it.objectKey == "media/two.jpg" }.checksum)
    }

    /**
     * The recovered keys are RE-JOURNALLED, not merely returned.
     *
     * Returning them to one caller and leaving the disk empty would rescue nothing: the next launch
     * reads a file that is no longer there. The rescue has to survive the process that performed it.
     */
    @Test
    fun `what was salvaged is written back so a later launch still has it`() {
        writeTruncatedJournal()
        StagedJournal.read(dir)
        val second = StagedJournal.read(dir)
        assertEquals(listOf("media/one.mp4", "media/two.jpg"), second.map { it.objectKey })
        // …and the second read must not have quarantined anything, because the rewrite is valid JSON.
        assertEquals(1, damaged.size)
    }

    /** The original bytes are kept under a name a support request can ask for, never deleted. */
    @Test
    fun `the damaged file is set aside rather than destroyed`() {
        writeTruncatedJournal()
        val original = journal.readText()
        StagedJournal.read(dir)
        assertEquals(1, damaged.size)
        assertEquals(original, damaged.single().readText())
    }

    /**
     * A recovered entry is stamped as somebody else's so [StagedJournal.sweep] reclaims it.
     *
     * `sweep` takes exactly the entries whose owner is not this process's. A key recovered from a
     * damaged file belongs to a run that was killed — that is what damaged it — so carrying its
     * original owner forward is harmless but carrying THIS process's would pin the bytes in the
     * bucket for ever, which is the very outcome being fixed.
     */
    @Test
    fun `recovered entries are owned by nobody living`() {
        writeTruncatedJournal()
        val entries = StagedJournal.read(dir)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.owner == StagedJournal.RECOVERED_OWNER })
    }

    /**
     * A ZERO-LENGTH file is damage too, and this is the case a "the file is too small to tear" defence
     * gets wrong: `writeText` truncates first, so even a one-entry journal has a window in which the
     * file exists and is empty.
     */
    @Test
    fun `a zero-length journal is damage, not an empty journal`() {
        journal.writeText("")
        assertTrue(StagedJournal.read(dir).isEmpty())
        assertEquals(1, damaged.size)
        // Nothing was legible, so there is nothing to re-journal — and an absent file, not a `[]`, is
        // how this class spells "nothing staged". That is what makes a blank file damage next time.
        assertFalse(journal.exists())
    }

    /** Whole JSON that simply is not this shape is damage as well, not an empty list. */
    @Test
    fun `a journal holding the wrong shape is quarantined`() {
        journal.writeText("""{"objectKey":"media/one.mp4"}""")
        StagedJournal.read(dir)
        assertEquals(1, damaged.size)
    }

    // ── What must keep working ───────────────────────────────────────────────────────────────────

    @Test
    fun `an absent journal is still simply nothing staged`() {
        assertTrue(StagedJournal.read(dir).isEmpty())
        assertTrue(damaged.isEmpty())
    }

    @Test
    fun `a healthy journal round-trips and quarantines nothing`() {
        val entries = listOf(
            StagedObject(objectKey = "media/one.mp4", owner = "run-a", uploadId = "UP-1"),
            StagedObject(objectKey = "media/two.jpg", owner = "run-a", checksum = "abc"),
        )
        StagedJournal.write(dir, entries)
        assertEquals(entries, StagedJournal.read(dir))
        assertTrue(damaged.isEmpty())
        // No temp file may survive a successful write; a stray `.writing` would be read by nothing and
        // would grow one copy per save.
        assertNull(dir.listFiles()?.firstOrNull { it.name.endsWith(".writing") })
    }

    /** An empty journal is the ABSENCE of the file — see the zero-length test for why that matters. */
    @Test
    fun `writing an empty journal removes the file`() {
        StagedJournal.write(dir, listOf(StagedObject(objectKey = "media/one.mp4", owner = "run-a")))
        assertTrue(journal.exists())
        StagedJournal.write(dir, emptyList())
        assertFalse(journal.exists())
    }

    // ── Atomicity ────────────────────────────────────────────────────────────────────────────────

    /**
     * A WRITE THAT FAILS MUST NOT HAVE DESTROYED THE PREVIOUS JOURNAL.
     *
     * This is the assertion the old `writeText` could not pass, and it is the whole point of the temp
     * file: `writeText` truncates the target before it has written a byte, so a failure anywhere after
     * that point leaves a damaged file where a complete one used to be. Here the temp path is blocked
     * by a non-empty directory, so `FileOutputStream(temp)` cannot open — the earliest possible
     * failure — and the target must be untouched.
     *
     * Blocking the temp path is a stand-in for the real killer, which is the process dying between the
     * truncate and the flush; that cannot be staged inside a JVM test, and this proves the same
     * property: nothing observes a partially written journal, because the target is only ever replaced
     * by a rename.
     */
    @Test
    fun `a failed write leaves the previous journal complete`() {
        val original = listOf(StagedObject(objectKey = "media/one.mp4", owner = "run-a", uploadId = "UP-1"))
        StagedJournal.write(dir, original)

        val blocker = File(dir, "staged-objects.json.writing")
        blocker.mkdirs()
        File(blocker, "occupied").writeText("x") // non-empty, so the cleanup cannot remove it either

        val failure = runCatching {
            StagedJournal.write(dir, listOf(StagedObject(objectKey = "media/two.jpg", owner = "run-b")))
        }.exceptionOrNull()
        assertNotNull("the write must report its failure rather than half-perform it", failure)

        blocker.deleteRecursively()
        assertEquals(original, StagedJournal.read(dir))
        assertTrue("nothing was damaged, so nothing may be quarantined", damaged.isEmpty())
    }
}
