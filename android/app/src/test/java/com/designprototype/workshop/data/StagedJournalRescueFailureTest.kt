package com.designprototype.workshop.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StagedJournalRescueFailureTest {

    private lateinit var dir: File

    private val journal: File get() = File(dir, "staged-objects.json")
    private val damaged: List<File>
        get() = dir.listFiles()?.filter { it.name.startsWith("staged-objects.damaged-") }.orEmpty()

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

    private fun writeTruncatedJournal(): String {
        val text = """[{"objectKey":"media/one.mp4","owner":"dead-run","uploadId":"UP-1"},""" +
            """{"objectKey":"media/two.jpg","owner":"dead-run","checksum":"abc"},""" +
            """{"objectKey":"media/thr"""
        journal.writeText(text)
        return text
    }

    @Test
    fun `a journal that could not be set aside is left exactly where it was`() {
        val original = writeTruncatedJournal()

        StagedJournal.read(dir) { blocked }

        assertTrue("the damaged journal must still exist", journal.exists())
        assertEquals("and must still hold every byte it held", original, journal.readText())
        assertTrue("nothing was rescued, so no damaged- file may claim to hold it", damaged.isEmpty())
    }

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

    @Test
    fun `the next read retries the rescue and the refusal lifts`() {
        writeTruncatedJournal()
        StagedJournal.read(dir) { blocked }

        val second = StagedJournal.read(dir)
        assertEquals(listOf("media/one.mp4", "media/two.jpg"), second.map { it.objectKey })
        assertEquals("the bytes are now kept under a name support can ask for", 1, damaged.size)

        StagedJournal.write(dir, listOf(StagedObject(objectKey = "media/four.mp4", owner = "this-run")))
        assertEquals(listOf("media/four.mp4"), StagedJournal.read(dir).map { it.objectKey })
    }

    @Test
    fun `an unsalvageable journal that could not be set aside is not deleted`() {
        journal.writeText("")

        val entries = StagedJournal.read(dir) { blocked }

        assertTrue("nothing was legible, so nothing can be returned", entries.isEmpty())
        assertTrue("but the file itself must survive", journal.exists())
        assertTrue(damaged.isEmpty())
    }

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
