package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The document-preview cache: what a cached file is NAMED after, and what bounds the directory.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS IS A TEST AND NOT A CODE REVIEW
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The defect these cover shipped, and it shipped because it is INVISIBLE at every point a person
 * would look. `DwDocumentPreview` cached a fetched PDF under a name derived from the FILENAME, and
 * returned an existing file untouched. So a designer who replaced their CV with a corrected `cv.pdf`
 * — a new media row, a new id, the same filename — saw the SUPERSEDED document's first page, with
 * the new filename printed underneath it, on the one control in this product whose entire purpose is
 * answering "is this the right document". Nothing throws. Nothing logs. The page renders. The only
 * person who finds out is whoever receives the wrong CV.
 *
 * Reproducing it by hand costs two uploads, a force-close and a warm start, per attempt. Asserting
 * that two ids never share a cache name costs a millisecond, which is why the naming rule is pinned
 * here rather than trusted to the next reader of the file.
 *
 * THE BOUND IS TESTED FOR THE SAME REASON IN REVERSE: an unbounded cache is invisible until a
 * handset is full, at which point the symptom is somebody else's feature failing to save.
 */
class DwDocumentCacheTest {

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // THE KEY
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE REGRESSION ITSELF. Two media rows whose files are both called `cv.pdf` must not collide,
     * because that collision IS the bug: the second read hits the first row's bytes.
     */
    @Test
    fun `two media ids never share a cache name`() {
        val first = dwDocCacheName("cku1replacedcv0000000000")
        val second = dwDocCacheName("cku2correctedcv000000000")
        assertNotEquals(first, second)
    }

    /** And the other half of a cache: the SAME id must hit the same file, or nothing is ever cached. */
    @Test
    fun `one media id is stable across calls`() {
        assertEquals(dwDocCacheName("ckusurvey0000000000000001"), dwDocCacheName("ckusurvey0000000000000001"))
    }

    /**
     * A NAME CANNOT REACH THIS KEY AT ALL. Stated as its own assertion because the old key was
     * `name.hashCode()` plus `name.takeLast(40)`: if a filename ever leaks back into this function,
     * two documents called `survey.pdf` start sharing a file again and the round trip is silent.
     */
    @Test
    fun `the key ignores everything except the id`() {
        // Same id, and nothing else is passed — there is no parameter a filename could arrive by.
        val name = dwDocCacheName("ckuoneid00000000000000001")
        assertFalse(name.contains("cv"))
        assertFalse(name.contains("survey"))
    }

    /**
     * NO PATH SEPARATOR AND NO `..` SURVIVES. `resolve()` on the returned name must land inside the
     * cache directory: an id carrying a slash would otherwise write — and later read — outside it.
     * Today's ids are cuids and cannot, which is exactly when a guard like this is worth pinning.
     */
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

    /**
     * TWO IDS THAT SANITISE TO THE SAME THING STILL GET TWO FILES. Both of these strip to nothing, so
     * without the hash over the whole id they would share the fallback name — and that is the
     * original defect wearing a different hat.
     */
    @Test
    fun `ids that sanitise away are still told apart`() {
        assertNotEquals(dwDocCacheName("///"), dwDocCacheName("..."))
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // THE BOUND
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    private fun dir(): File = Files.createTempDirectory("dw-doc-preview-test").toFile()

    private fun write(dir: File, name: String, bytes: Int, ageMillis: Long): File {
        val file = File(dir, name)
        file.writeBytes(ByteArray(bytes))
        file.setLastModified(System.currentTimeMillis() - ageMillis)
        return file
    }

    /** Under budget, nothing is touched — a trim that evicts on every fetch is not a cache. */
    @Test
    fun `a small directory is left alone`() {
        val dir = dir()
        val old = write(dir, "old", 1024, ageMillis = 90_000)
        val fresh = write(dir, "fresh", 1024, ageMillis = 0)
        dwTrimDocPreviewCache(fresh)
        assertTrue(old.exists())
        assertTrue(fresh.exists())
    }

    /**
     * OVER BUDGET, THE OLDEST GO AND THE JUST-FETCHED FILE STAYS. Deleting `keep` would be the worst
     * possible eviction: the download that triggered the trim is the one about to be rendered, so
     * losing it turns a successful fetch into an empty frame.
     */
    @Test
    fun `over budget the oldest fetches are evicted and the new one is kept`() {
        val dir = dir()
        val mib = 1024 * 1024
        val oldest = write(dir, "oldest", 10 * mib, ageMillis = 300_000)
        val middle = write(dir, "middle", 10 * mib, ageMillis = 200_000)
        val newer = write(dir, "newer", 10 * mib, ageMillis = 100_000)
        val keep = write(dir, "keep", 10 * mib, ageMillis = 0)

        dwTrimDocPreviewCache(keep)

        // 40 MiB against a 24 MiB budget: the two oldest go, which brings it to 20 MiB.
        assertFalse("the oldest fetch survived the trim", oldest.exists())
        assertFalse("the second-oldest fetch survived the trim", middle.exists())
        assertTrue("a file was evicted that did not need to be", newer.exists())
        assertTrue("the file that triggered the trim was deleted", keep.exists())
        val occupied = dir.listFiles()?.sumOf { it.length() } ?: 0L
        assertTrue("the directory is still over budget", occupied <= DW_DOC_PREVIEW_CACHE_BYTES)
    }

    /**
     * A `.part` IS NEVER EVICTED. It is a download in flight; deleting it pulls the file out from
     * under a stream that is still being written, and the card that was fetching it reports "No
     * connection" about a fetch that was working.
     */
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

    /**
     * AN ABANDONED `.part` IS REAPED, WHICH IS THE OTHER HALF OF THE RULE ABOVE. A part is spared
     * eviction because it may be a live download; the sweep is what stops that exemption becoming a
     * permanent one. Without it a fetch killed mid-write — a lost signal, the process going away —
     * leaves bytes in this directory that nothing will ever read and nothing will ever remove.
     */
    @Test
    fun `an abandoned part is swept`() {
        val dir = dir()
        val abandoned = write(dir, "gone$DW_DOC_PART_SUFFIX", 1024, ageMillis = DW_DOC_PART_STALE_MILLIS + 60_000)
        val keep = write(dir, "keep", 1024, ageMillis = 0)

        dwTrimDocPreviewCache(keep)

        assertFalse("an abandoned part outlived the sweep", abandoned.exists())
        assertTrue(keep.exists())
    }

    /**
     * A PART'S BYTES ARE NOT THE CACHE'S BYTES, AND THAT IS PINNED SEPARATELY FROM THE RULE ABOVE.
     * `a download in flight is not evicted` cannot tell the two apart: with the part uncounted that
     * directory is under budget, so the loop returns before any candidate is considered and the test
     * would still pass if parts were merely SKIPPED as candidates while their bytes were counted.
     * That arrangement is the one that wedges the cache — a 20 MiB download in flight would evict
     * every real entry beneath it on every fetch and still never bring the total down.
     */
    @Test
    fun `bytes in flight are not counted against the budget`() {
        val dir = dir()
        val mib = 1024 * 1024
        val inFlight = write(dir, "other$DW_DOC_PART_SUFFIX", 20 * mib, ageMillis = 60_000)
        val older = write(dir, "older", 10 * mib, ageMillis = 300_000)
        val newer = write(dir, "newer", 10 * mib, ageMillis = 200_000)
        val keep = write(dir, "keep", 1024, ageMillis = 0)

        dwTrimDocPreviewCache(keep)

        // 20 MiB of real entries against a 24 MiB budget once the part is left out of the sum, so
        // nothing is over budget and nothing may be evicted. Counting the part would make it 40.
        assertTrue("a real entry was evicted for bytes that are still being written", older.exists())
        assertTrue("a real entry was evicted for bytes that are still being written", newer.exists())
        assertTrue(inFlight.exists())
        assertTrue(keep.exists())
    }

    /** A trim pointed at a file whose directory has gone must not throw on a background thread. */
    @Test
    fun `a vanished directory is not a crash`() {
        val dir = dir()
        val keep = write(dir, "keep", 16, ageMillis = 0)
        keep.delete()
        dir.delete()
        dwTrimDocPreviewCache(keep)
    }
}
