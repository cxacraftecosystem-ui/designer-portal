package com.designprototype.workshop

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.designprototype.workshop.data.DwCustomCache
import com.designprototype.workshop.data.DwCustomCopy
import com.designprototype.workshop.data.DwCustomFieldDto
import com.designprototype.workshop.data.DwCustomSectionDto
import com.designprototype.workshop.data.DwCustomSectionStore
import com.designprototype.workshop.data.dwCustomCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DwCustomSectionStoreDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workshopId = "androidTest-custom-sections"

    private fun customDir() = File(context.filesDir, "dw-custom")

    private fun fileFor(id: String) = File(customDir(), "$id.json")

    private fun definition(version: String, vararg keys: String) = DwCustomCache(
        workshopId = workshopId,
        customSchemaVersion = version,
        complete = true,
        sections = listOf(
            DwCustomSectionDto(
                id = "sec-1",
                key = "loomAudit",
                stageKey = "TRADITIONAL_PROCESS_BASELINE",
                title = "Loom audit",
                fields = keys.map { key ->
                    DwCustomFieldDto(id = "fld-$key", key = key, label = key, type = "INT", required = true)
                },
            ),
        ),
    )

    @Before
    fun clean() = runBlocking {
        DwCustomSectionStore.forget(context, workshopId)
        customDir().listFiles().orEmpty()
            .filter { it.name.startsWith("androidTest-") }
            .forEach { it.delete() }
    }

    @Test
    fun writes_and_reads_back_a_definition() = runBlocking {
        val stored = DwCustomSectionStore.store(context, definition("v1", "loomsWorking"))
        assertTrue("store stamps a fetchedAt", stored.fetchedAt.isNotBlank())

        DwCustomSectionStore.forget(context, workshopId)
        assertNull("forget must clear the memory mirror as well as the file",
            DwCustomSectionStore.load(context, workshopId))

        DwCustomSectionStore.store(context, definition("v1", "loomsWorking"))
        val read = DwCustomSectionStore.load(context, workshopId)
        assertNotNull(read)
        assertEquals("v1", read!!.customSchemaVersion)
        assertEquals(listOf("loomsWorking"), read.sections.single().fields.map { it.key })
        assertEquals(DwCustomCopy.DEFINED, dwCustomCopy(read))
    }

    @Test
    fun a_replacement_leaves_no_window_and_no_temp_file() = runBlocking {
        DwCustomSectionStore.store(context, definition("v1", "a"))
        val target = fileFor(workshopId)
        assertTrue(target.isFile)

        repeat(25) { i ->
            DwCustomSectionStore.store(context, definition("v$i", "a", "b"))
            assertTrue("the definition must exist at every moment", target.isFile)
            assertTrue("and never be zero-length", target.length() > 0)
        }
        assertFalse(
            "a temp file left behind would be read by nothing and would confuse the next reader",
            File(customDir(), "${target.name}.writing").exists(),
        )
        DwCustomSectionStore.forget(context, workshopId)
        DwCustomSectionStore.store(context, definition("final", "a", "b"))
        DwCustomSectionStore.forget(context, workshopId)
        assertNull(DwCustomSectionStore.load(context, workshopId))
    }

    @Test
    fun what_the_memory_mirror_serves_is_what_is_on_disk() = runBlocking {
        DwCustomSectionStore.store(context, definition("v1", "a"))
        val fromMemory = DwCustomSectionStore.load(context, workshopId)!!
        val onDisk = withContext(Dispatchers.IO) { fileFor(workshopId).readText() }
        assertTrue(onDisk.contains("\"customSchemaVersion\":\"v1\""))
        assertEquals("v1", fromMemory.customSchemaVersion)
    }

    @Test
    fun a_corrupt_file_is_deleted_and_reads_as_never_read() = runBlocking {
        DwCustomSectionStore.store(context, definition("v1", "a"))
        DwCustomSectionStore.forget(context, workshopId)

        withContext(Dispatchers.IO) {
            customDir().mkdirs()
            fileFor(workshopId).writeText("{\"sections\": [ this is not json")
        }
        assertNull("an unparseable copy is not a definition", DwCustomSectionStore.load(context, workshopId))
        assertFalse("…and it is deleted, because the server still holds it", fileFor(workshopId).exists())
        assertEquals(
            "the phone must not claim this workshop has no custom questions",
            DwCustomCopy.UNKNOWN,
            dwCustomCopy(DwCustomSectionStore.load(context, workshopId)),
        )
    }

    @Test
    fun a_zero_length_file_is_treated_as_damage() = runBlocking {
        withContext(Dispatchers.IO) {
            customDir().mkdirs()
            fileFor(workshopId).writeText("")
        }
        assertNull(DwCustomSectionStore.load(context, workshopId))
        assertFalse(fileFor(workshopId).exists())
    }

    @Test
    fun an_empty_definition_overwrites_a_full_one_and_reads_as_NONE_DEFINED() = runBlocking {
        DwCustomSectionStore.store(context, definition("v1", "a"))
        DwCustomSectionStore.store(
            context,
            DwCustomCache(workshopId = workshopId, customSchemaVersion = "", complete = true),
        )
        val onDisk = withContext(Dispatchers.IO) { fileFor(workshopId).readText() }
        assertFalse("the full definition must be gone from the file", onDisk.contains("loomAudit"))

        val read = DwCustomSectionStore.load(context, workshopId)!!
        assertTrue(read.sections.isEmpty())
        assertEquals(DwCustomCopy.NONE_DEFINED, dwCustomCopy(read))
    }

    @Test
    fun two_workshops_do_not_share_a_file_and_a_traversal_cannot_escape() = runBlocking {
        val nasty = "androidTest-../../escaped"
        DwCustomSectionStore.store(context, definition("v1", "a"))
        DwCustomSectionStore.store(
            context,
            DwCustomCache(workshopId = nasty, customSchemaVersion = "other", complete = true),
        )

        val onDisk = withContext(Dispatchers.IO) { fileFor(workshopId).readText() }
        assertTrue("the second write must not have landed in the first workshop's file", onDisk.contains("\"v1\""))
        assertFalse(onDisk.contains("other"))
        assertEquals("v1", DwCustomSectionStore.load(context, workshopId)!!.customSchemaVersion)
        assertTrue(
            "every file this store writes stays inside dw-custom/",
            customDir().listFiles().orEmpty().all { it.parentFile == customDir() },
        )
        assertFalse(File(context.filesDir.parentFile, "escaped.json").exists())
        DwCustomSectionStore.forget(context, nasty)
    }

    @Test
    fun concurrent_writers_and_readers_never_see_a_partial_definition() = runBlocking {
        DwCustomSectionStore.store(context, definition("seed", "a"))
        val jobs = (0 until 12).map { i ->
            async(Dispatchers.IO) {
                DwCustomSectionStore.store(context, definition("v$i", "a", "b"))
                DwCustomSectionStore.load(context, workshopId)
            }
        }
        val reads = jobs.awaitAll()
        assertTrue("every concurrent read saw a whole definition", reads.all { it != null })
        assertTrue(reads.all { it!!.sections.single().fields.size == 2 })
        assertTrue(fileFor(workshopId).length() > 0)
    }
}
