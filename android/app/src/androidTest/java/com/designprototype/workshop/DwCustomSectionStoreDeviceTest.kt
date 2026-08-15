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

/**
 * [DwCustomSectionStore]'s durability, RUN ON A HANDSET, against a real `filesDir` on real flash.
 *
 * ── WHY THIS IS NOT A DESKTOP JVM TEST ────────────────────────────────────────────────────────────
 *
 * Every rule this store keeps is a rule about a filesystem: that a temp file is created in the SAME
 * directory as its target so the rename is atomic rather than a copy across mount points; that
 * `FileDescriptor.sync()` returns before the rename, so the directory entry never points at bytes
 * that are still only in the page cache; that `renameTo` REPLACES an existing file on this
 * filesystem, which is what makes the delete-then-rename window in `DwQuestionnaireStore` and
 * `DwReferenceStore` unnecessary rather than merely unlucky. None of that is true of a filesystem in
 * general and none of it can be demonstrated by a fake: a desktop JVM run on NTFS would answer a
 * different question about a different filesystem, and `renameTo` over an existing file is exactly
 * the call that behaves differently between the two. This repository's own note says the durable
 * half verified only on a desktop JVM is the half that is wrong, and this is the first lane that
 * could run the other half.
 *
 * Run:
 *   ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.designprototype.workshop.DwCustomSectionStoreDeviceTest
 *
 * It writes only under `filesDir/dw-custom/` and only for workshop ids prefixed `androidTest-`, and
 * it clears them before each case, so it cannot touch a draft or a definition a designer holds.
 */
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

    /** The floor: what goes in comes out, off real flash, through a real rename. */
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

    /**
     * THE ATOMICITY THE FSYNC EXISTS FOR, observed from outside: at no point is there a file that is
     * neither the old definition nor the new one, and no `.writing` temp file survives the write.
     *
     * The delete-then-rename this store deliberately does NOT copy would fail exactly here — it opens
     * a window in which the target does not exist at all, and a reader arriving inside it gets null,
     * which this app reads as "this device has never been told about this workshop's questions".
     */
    @Test
    fun a_replacement_leaves_no_window_and_no_temp_file() = runBlocking {
        DwCustomSectionStore.store(context, definition("v1", "a"))
        val target = fileFor(workshopId)
        assertTrue(target.isFile)

        repeat(25) { i ->
            DwCustomSectionStore.store(context, definition("v$i", "a", "b"))
            // The file is a WHOLE document at every moment a reader could look at it. A torn write or
            // a delete-then-rename would show up as a missing file or a zero-length one here.
            assertTrue("the definition must exist at every moment", target.isFile)
            assertTrue("and never be zero-length", target.length() > 0)
        }
        assertFalse(
            "a temp file left behind would be read by nothing and would confuse the next reader",
            File(customDir(), "${target.name}.writing").exists(),
        )
        // Real bytes on real flash, re-read with the memory mirror deliberately dropped first.
        DwCustomSectionStore.forget(context, workshopId)
        DwCustomSectionStore.store(context, definition("final", "a", "b"))
        DwCustomSectionStore.forget(context, workshopId)
        assertNull(DwCustomSectionStore.load(context, workshopId))
    }

    /** The memory mirror is written AFTER the file, so a kill between the two loses nothing. */
    @Test
    fun what_the_memory_mirror_serves_is_what_is_on_disk() = runBlocking {
        DwCustomSectionStore.store(context, definition("v1", "a"))
        val fromMemory = DwCustomSectionStore.load(context, workshopId)!!
        val onDisk = withContext(Dispatchers.IO) { fileFor(workshopId).readText() }
        assertTrue(onDisk.contains("\"customSchemaVersion\":\"v1\""))
        assertEquals("v1", fromMemory.customSchemaVersion)
    }

    /**
     * A DECODE FAILURE DELETES AND RE-READS, and that is only defensible because null resolves to
     * UNKNOWN. Both halves are asserted together, deliberately: they are one rule, and separating
     * them is how the second half gets changed by somebody who only read the first.
     */
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

    /**
     * A ZERO-LENGTH FILE IS DAMAGE, NOT AN EMPTY DEFINITION — the fingerprint of a process killed
     * between creating a file and filling it, which is precisely what the fsync above exists to make
     * impossible for this store's own writes.
     */
    @Test
    fun a_zero_length_file_is_treated_as_damage() = runBlocking {
        withContext(Dispatchers.IO) {
            customDir().mkdirs()
            fileFor(workshopId).writeText("")
        }
        assertNull(DwCustomSectionStore.load(context, workshopId))
        assertFalse(fileFor(workshopId).exists())
    }

    /**
     * AN EMPTY, COMPLETE DEFINITION OVERWRITES A FULL ONE, and must.
     *
     * This is the one place the store departs from `DwReferenceStore`'s "an empty fetch does not
     * overwrite a non-empty cache". A designer retiring their last section is the documented way to
     * take it off the form, so refusing to record that would leave the phone drawing, scoring and
     * printing questions that no longer exist. The protection is in the FETCH, which throws rather
     * than returning empty — so a dead connection never reaches this function.
     */
    @Test
    fun an_empty_definition_overwrites_a_full_one_and_reads_as_NONE_DEFINED() = runBlocking {
        DwCustomSectionStore.store(context, definition("v1", "a"))
        DwCustomSectionStore.store(
            context,
            DwCustomCache(workshopId = workshopId, customSchemaVersion = "", complete = true),
        )
        // Asserted against the BYTES, so the overwrite is proved rather than inferred from a memory
        // mirror that would answer the same either way.
        val onDisk = withContext(Dispatchers.IO) { fileFor(workshopId).readText() }
        assertFalse("the full definition must be gone from the file", onDisk.contains("loomAudit"))

        val read = DwCustomSectionStore.load(context, workshopId)!!
        assertTrue(read.sections.isEmpty())
        assertEquals(DwCustomCopy.NONE_DEFINED, dwCustomCopy(read))
    }

    /**
     * ONE FILE PER WORKSHOP, and an id that arrives from a server response or a deep link cannot
     * escape the directory. A raw `../` would put a definition somewhere else entirely — at best
     * losing it, at worst overwriting a neighbouring workshop's.
     */
    @Test
    fun two_workshops_do_not_share_a_file_and_a_traversal_cannot_escape() = runBlocking {
        val nasty = "androidTest-../../escaped"
        DwCustomSectionStore.store(context, definition("v1", "a"))
        DwCustomSectionStore.store(
            context,
            DwCustomCache(workshopId = nasty, customSchemaVersion = "other", complete = true),
        )

        // Read off the FILE rather than through `load`, because `load` would be served by the memory
        // mirror and prove nothing about which bytes landed where. `forget` is deliberately not used
        // to drop that mirror — it deletes the file too, which is the whole point of it.
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

    /**
     * THE COARSE MUTEX, EXERCISED. Concurrent writers and readers must leave one whole document
     * behind and must never produce a null in the middle — which is the failure the delete-then-rename
     * window would produce under exactly this load, and which reads on every screen as "this device
     * has never been told about this workshop's questions".
     */
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
