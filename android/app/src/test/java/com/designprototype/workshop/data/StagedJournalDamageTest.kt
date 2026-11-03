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

    private fun writeTruncatedJournal() {
        journal.writeText(
            """[{"objectKey":"media/one.mp4","owner":"dead-run","uploadId":"UP-1"},""" +
                """{"objectKey":"media/two.jpg","owner":"dead-run","checksum":"abc"},""" +
                """{"objectKey":"media/thr"""
        )
    }

    @Test
    fun `a truncated journal is not read as an empty one`() {
        writeTruncatedJournal()
        val entries = StagedJournal.read(dir)
        assertEquals(listOf("media/one.mp4", "media/two.jpg"), entries.map { it.objectKey })
        assertEquals("UP-1", entries.first { it.objectKey == "media/one.mp4" }.uploadId)
        assertEquals("abc", entries.first { it.objectKey == "media/two.jpg" }.checksum)
    }

    @Test
    fun `what was salvaged is written back so a later launch still has it`() {
        writeTruncatedJournal()
        StagedJournal.read(dir)
        val second = StagedJournal.read(dir)
        assertEquals(listOf("media/one.mp4", "media/two.jpg"), second.map { it.objectKey })
        assertEquals(1, damaged.size)
    }

    @Test
    fun `the damaged file is set aside rather than destroyed`() {
        writeTruncatedJournal()
        val original = journal.readText()
        StagedJournal.read(dir)
        assertEquals(1, damaged.size)
        assertEquals(original, damaged.single().readText())
    }

    @Test
    fun `recovered entries are owned by nobody living`() {
        writeTruncatedJournal()
        val entries = StagedJournal.read(dir)
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.owner == StagedJournal.RECOVERED_OWNER })
    }

    @Test
    fun `a zero-length journal is damage, not an empty journal`() {
        journal.writeText("")
        assertTrue(StagedJournal.read(dir).isEmpty())
        assertEquals(1, damaged.size)
        assertFalse(journal.exists())
    }

    @Test
    fun `a journal holding the wrong shape is quarantined`() {
        journal.writeText("""{"objectKey":"media/one.mp4"}""")
        StagedJournal.read(dir)
        assertEquals(1, damaged.size)
    }

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
        assertNull(dir.listFiles()?.firstOrNull { it.name.endsWith(".writing") })
    }

    @Test
    fun `writing an empty journal removes the file`() {
        StagedJournal.write(dir, listOf(StagedObject(objectKey = "media/one.mp4", owner = "run-a")))
        assertTrue(journal.exists())
        StagedJournal.write(dir, emptyList())
        assertFalse(journal.exists())
    }

    @Test
    fun `a failed write leaves the previous journal complete`() {
        val original = listOf(StagedObject(objectKey = "media/one.mp4", owner = "run-a", uploadId = "UP-1"))
        StagedJournal.write(dir, original)

        val blocker = File(dir, "staged-objects.json.writing")
        blocker.mkdirs()
        File(blocker, "occupied").writeText("x")

        val failure = runCatching {
            StagedJournal.write(dir, listOf(StagedObject(objectKey = "media/two.jpg", owner = "run-b")))
        }.exceptionOrNull()
        assertNotNull("the write must report its failure rather than half-perform it", failure)

        blocker.deleteRecursively()
        assertEquals(original, StagedJournal.read(dir))
        assertTrue("nothing was damaged, so nothing may be quarantined", damaged.isEmpty())
    }
}
