package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DwDocumentCacheTest {

    @Test
    fun `two media ids never share a cache name`() {
        val first = dwDocCacheName("cku1replacedcv0000000000")
        val second = dwDocCacheName("cku2correctedcv000000000")
        assertNotEquals(first, second)
    }

    @Test
    fun `one media id is stable across calls`() {
        assertEquals(dwDocCacheName("ckusurvey0000000000000001"), dwDocCacheName("ckusurvey0000000000000001"))
    }

    @Test
    fun `the key ignores everything except the id`() {
        val name = dwDocCacheName("ckuoneid00000000000000001")
        assertFalse(name.contains("cv"))
        assertFalse(name.contains("survey"))
    }

    @Test
    fun `a hostile id cannot escape the cache directory`() {
        val cache = dir()
        listOf("../../etc/passwd", "a/b/c", "..", "", "   ", "\\windows\\system32").forEach { id ->
            val name = dwDocCacheName(id)
            assertFalse("separator survived for '$id': $name", name.contains('/'))
            assertFalse("separator survived for '$id': $name", name.contains('\\'))
            assertFalse("traversal survived for '$id': $name", name.contains(".."))
            assertTrue("empty name for '$id'", name.isNotBlank())
            assertEquals(
                "'$id' resolved outside the cache directory",
                cache.canonicalPath,
                cache.resolve(name).parentFile?.canonicalPath,
            )
        }
    }

    @Test
    fun `ids that sanitise away are still told apart`() {
        assertNotEquals(dwDocCacheName("///"), dwDocCacheName("..."))
    }

    private fun dir(): File = Files.createTempDirectory("dw-doc-preview-test").toFile()

    private fun write(dir: File, name: String, bytes: Int, ageMillis: Long): File {
        val file = File(dir, name)
        file.writeBytes(ByteArray(bytes))
        file.setLastModified(System.currentTimeMillis() - ageMillis)
        return file
    }

    @Test
    fun `a small directory is left alone`() {
        val dir = dir()
        val old = write(dir, "old", 1024, ageMillis = 90_000)
        val fresh = write(dir, "fresh", 1024, ageMillis = 0)
        dwTrimDocPreviewCache(fresh)
        assertTrue(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `over budget the oldest fetches are evicted and the new one is kept`() {
        val dir = dir()
        val mib = 1024 * 1024
        val oldest = write(dir, "oldest", 10 * mib, ageMillis = 300_000)
        val middle = write(dir, "middle", 10 * mib, ageMillis = 200_000)
        val newer = write(dir, "newer", 10 * mib, ageMillis = 100_000)
        val keep = write(dir, "keep", 10 * mib, ageMillis = 0)

        dwTrimDocPreviewCache(keep)

        assertFalse("the oldest fetch survived the trim", oldest.exists())
        assertFalse("the second-oldest fetch survived the trim", middle.exists())
        assertTrue("a file was evicted that did not need to be", newer.exists())
        assertTrue("the file that triggered the trim was deleted", keep.exists())
        val occupied = dir.listFiles()?.sumOf { it.length() } ?: 0L
        assertTrue("the directory is still over budget", occupied <= DW_DOC_PREVIEW_CACHE_BYTES)
    }

    @Test
    fun `a download in flight is not evicted`() {
        val dir = dir()
        val mib = 1024 * 1024
        val inFlight = write(dir, "other$DW_DOC_PART_SUFFIX", 20 * mib, ageMillis = 400_000)
        val keep = write(dir, "keep", 20 * mib, ageMillis = 0)

        dwTrimDocPreviewCache(keep)

        assertTrue("an in-flight download was evicted", inFlight.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun `an abandoned part is swept`() {
        val dir = dir()
        val abandoned = write(dir, "gone$DW_DOC_PART_SUFFIX", 1024, ageMillis = DW_DOC_PART_STALE_MILLIS + 60_000)
        val keep = write(dir, "keep", 1024, ageMillis = 0)

        dwTrimDocPreviewCache(keep)

        assertFalse("an abandoned part outlived the sweep", abandoned.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun `bytes in flight are not counted against the budget`() {
        val dir = dir()
        val mib = 1024 * 1024
        val inFlight = write(dir, "other$DW_DOC_PART_SUFFIX", 20 * mib, ageMillis = 60_000)
        val older = write(dir, "older", 10 * mib, ageMillis = 300_000)
        val newer = write(dir, "newer", 10 * mib, ageMillis = 200_000)
        val keep = write(dir, "keep", 1024, ageMillis = 0)

        dwTrimDocPreviewCache(keep)

        assertTrue("a real entry was evicted for bytes that are still being written", older.exists())
        assertTrue("a real entry was evicted for bytes that are still being written", newer.exists())
        assertTrue(inFlight.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun `a vanished directory is not a crash`() {
        val dir = dir()
        val keep = write(dir, "keep", 16, ageMillis = 0)
        keep.delete()
        dir.delete()
        dwTrimDocPreviewCache(keep)
    }
}
