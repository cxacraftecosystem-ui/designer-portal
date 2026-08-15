package com.designprototype.workshop.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A quarantine that cannot be performed must not destroy the bytes it exists to keep.
 *
 * WHAT WAS WRONG, AND WHY IT IS WORSE THAN THE DEFECT IT WAS FIXING. The staged-object journal was
 * taught to treat a damaged file as damage — set it aside under a `staged-objects.damaged-…` name,
 * salvage the entries still legible in it, re-journal those — because reading damage as an empty
 * journal strands uploaded multipart parts nothing can ever name again. But the set-aside was
 * `runCatching { file.renameTo(kept) }`, which swallows the exception AND discards the `false` that a
 * failed rename RETURNS (renameTo reports failure by return value, not by throwing, so runCatching
 * sees a call that succeeded and did nothing), and the very next line rewrote the journal regardless.
 * On the one path where the rescue did not happen, the rescue's own next statement deleted or
 * replaced the only copy of the bytes — and for an unsalvageable file it was a DELETE, because this
 * class spells an empty journal as the absence of the file. A durability fix that loses the data on
 * its unhappy path is worse than none, because everything downstream now trusts it.
 *
 * HOW THE FAILURE IS STAGED, since a rename inside a temp directory does not fail on its own. `read`
 * takes the destination name as a parameter with a production default — the same kind of seam, and
 * for the same reason, as the directory-instead-of-Context one the class already carries: a branch
 * that can only be proved on a device is a branch nothing proves. The destination handed in here is
 * an existing NON-EMPTY DIRECTORY, which `Files.move` refuses on Windows and on Linux alike; making
 * the parent directory read-only would have been a POSIX-only trick that proves nothing on the host
 * these tests actually run on.
 *
 * WHAT MUST REMAIN TRUE. The original finding (audit 2026-08-15, "the staged-object journal … reads a
 * damaged file as empty") is closed by the sibling `StagedJournalDamageTest`, and nothing here may
 * reopen it: damage is still never emptiness, and the caller is still handed the salvage — the whole
 * rule being that a silent empty list is the worst outcome available, whether the rescue worked or
 * not.
 */
class StagedJournalRescueFailureTest {

    private lateinit var dir: File

    private val journal: File get() = File(dir, "staged-objects.json")
    private val damaged: List<File>
        get() = dir.listFiles()?.filter { it.name.startsWith("staged-objects.damaged-") }.orEmpty()

    /**
     * A destination the rescue cannot possibly have: a directory with something in it. `Files.move`
     * with REPLACE_EXISTING replaces an EMPTY directory and refuses a populated one, on every
     * filesystem this app is built for and on the host that runs these tests.
     */
    private lateinit var blocked: File

    @Before
    fun setUp() {
        dir = File.createTempFile("staged-journal-rescue", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
        blocked = File(dir, "occupied-destination").apply {
            mkdirs()
            File(this, "in-the-way").writeText("x")
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Two whole entries and a third cut off mid-key — what a kill during a truncating write leaves. */
    private fun writeTruncatedJournal(): String {
        val text = """[{"objectKey":"media/one.mp4","owner":"dead-run","uploadId":"UP-1"},""" +
            """{"objectKey":"media/two.jpg","owner":"dead-run","checksum":"abc"},""" +
            """{"objectKey":"media/thr"""
        journal.writeText(text)
        return text
    }

    // ── The finding ──────────────────────────────────────────────────────────────────────────────

    /**
     * THE ASSERTION THE OLD CODE COULD NOT PASS. If the damaged file cannot be moved aside, it is
     * still the only copy of itself, so nothing may be written over it — not the salvage, and least
     * of all the delete that an unsalvageable journal used to trigger.
     */
    @Test
    fun `a journal that could not be set aside is left exactly where it was`() {
        val original = writeTruncatedJournal()

        StagedJournal.read(dir) { blocked }

        assertTrue("the damaged journal must still exist", journal.exists())
        assertEquals("and must still hold every byte it held", original, journal.readText())
        assertTrue("nothing was rescued, so no damaged- file may claim to hold it", damaged.isEmpty())
    }

    /**
     * And the caller is STILL not told "nothing is staged". This is the rule the whole class is built
     * on: an empty list is indistinguishable from an empty bucket, so the keys salvage could read are
     * returned even when they could not be re-journalled — this launch's sweep can still name them,
     * and if the process dies first the damaged file is on disk for the next launch to try again.
     */
    @Test
    fun `the salvage is returned even though it could not be written back`() {
        writeTruncatedJournal()

        val entries = StagedJournal.read(dir) { blocked }

        assertEquals(listOf("media/one.mp4", "media/two.jpg"), entries.map { it.objectKey })
        assertEquals("UP-1", entries.first { it.objectKey == "media/one.mp4" }.uploadId)
        assertTrue(
            "recovered entries belong to a dead run and must be sweepable",
            entries.all { it.owner == StagedJournal.RECOVERED_OWNER },
        )
    }

    /**
     * THE SECOND DOOR. Not writing inside `quarantine` closes only half of it: every entry point of
     * this class is a read and then a write in one critical section, so `record`'s own write — a few
     * microseconds later, carrying only what salvage could decode — would finish the destruction the
     * quarantine declined to do, and the fragment salvage could not decode would be gone with it.
     * The write is refused instead, out loud, which is the only way the caller learns anything at all
     * (this class deliberately raises no designer-facing alert; see its `quarantine` KDoc).
     */
    @Test
    fun `a write over an unrescued journal is refused rather than performed`() {
        val original = writeTruncatedJournal()
        StagedJournal.read(dir) { blocked }

        val failure = runCatching {
            StagedJournal.write(dir, listOf(StagedObject(objectKey = "media/four.mp4", owner = "this-run")))
        }.exceptionOrNull()

        assertNotNull("the write must refuse rather than replace the only copy", failure)
        assertTrue(
            "and say which file and why: ${failure?.message}",
            failure?.message?.contains("staged-objects.json") == true,
        )
        assertEquals("the damaged bytes are untouched", original, journal.readText())
    }

    /**
     * The refusal is not a wedge. The damaged file was never rewritten, so the NEXT read tries the
     * rescue again against a fresh name; when the cause was momentary the journal heals itself and
     * writing resumes. Without this the first unrenamable moment would strand every key staged for
     * the rest of the process's life — trading one silent loss for a louder one.
     */
    @Test
    fun `the next read retries the rescue and the refusal lifts`() {
        writeTruncatedJournal()
        StagedJournal.read(dir) { blocked }

        val second = StagedJournal.read(dir) // production naming: a destination that is not blocked
        assertEquals(listOf("media/one.mp4", "media/two.jpg"), second.map { it.objectKey })
        assertEquals("the bytes are now kept under a name support can ask for", 1, damaged.size)

        // …and the journal is writable again, because there is nothing unrescued left to protect.
        StagedJournal.write(dir, listOf(StagedObject(objectKey = "media/four.mp4", owner = "this-run")))
        assertEquals(listOf("media/four.mp4"), StagedJournal.read(dir).map { it.objectKey })
    }

    /**
     * An UNSALVAGEABLE journal is the case the old code destroyed most completely: `write` with an
     * empty list is a DELETE, so a blank or unparseable file whose rescue failed was simply removed.
     */
    @Test
    fun `an unsalvageable journal that could not be set aside is not deleted`() {
        journal.writeText("")

        val entries = StagedJournal.read(dir) { blocked }

        assertTrue("nothing was legible, so nothing can be returned", entries.isEmpty())
        assertTrue("but the file itself must survive", journal.exists())
        assertTrue(damaged.isEmpty())
    }

    // ── What must keep working ───────────────────────────────────────────────────────────────────

    /**
     * The refusal is keyed to the file that is actually at risk. A healthy journal in another
     * workshop's directory — or in this one, once the rescue has happened — must write normally, or
     * one damaged file on one device would stop the app journalling anything at all.
     */
    @Test
    fun `an unrelated journal is unaffected by an unrescued one`() {
        writeTruncatedJournal()
        StagedJournal.read(dir) { blocked }

        val other = File(dir, "other-store").apply { mkdirs() }
        StagedJournal.write(other, listOf(StagedObject(objectKey = "media/five.mp4", owner = "this-run")))
        assertEquals(listOf("media/five.mp4"), StagedJournal.read(other).map { it.objectKey })
        assertNull(
            "and reading the other store must not have disturbed the damaged one",
            damaged.firstOrNull(),
        )
    }
}
