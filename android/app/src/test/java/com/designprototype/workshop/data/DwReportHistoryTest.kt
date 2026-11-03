package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwReportHistoryTest {

    private val t0 = "2026-08-01T09:00:00+00:00"
    private val t1 = "2026-08-05T09:00:00+00:00"
    private val t2 = "2026-08-10T09:00:00Z"
    private val t3 = "2026-08-14T09:00:00+00:00"
    private val now = "2026-08-20T09:00:00+00:00"

    private fun export(
        id: String,
        at: String?,
        generation: Int = 0,
        checksum: String? = "aa",
        onDevice: Boolean = false,
        format: String = "DOCX",
        template: String = "DCH_STANDARD",
        schema: String? = "v1",
        size: Long? = 1_000L,
        pages: Int? = 10,
    ) = DwExportRecordDto(
        id = id,
        generation = generation,
        format = format,
        templateId = template,
        fileName = "$id.docx",
        fileSizeBytes = size,
        pageCount = pages,
        checksumSha256 = checksum,
        generatedOnDevice = onDevice,
        schemaVersion = schema,
        generatedAt = at,
    )

    private fun entry(
        id: String,
        stage: String,
        created: String?,
        updated: String? = created,
        deleted: String? = null,
    ) = DwEntryTimestampDto(
        id = id,
        stageKey = stage,
        entityKey = "$stage:main",
        createdAt = created,
        updatedAt = updated,
        deletedAt = deleted,
    )

    private fun history(
        exports: List<DwExportRecordDto> = listOf(export("e1", t0), export("e2", t2, onDevice = true)),
        entries: List<DwEntryTimestampDto> = listOf(
            entry("s1", "SETUP", created = "2026-07-30T09:00:00+00:00"),
            entry("c1", "COSTS", created = "2026-07-30T09:00:00+00:00", updated = t1),
            entry("c2", "COSTS", created = t1),
        ),
        workshopUpdatedAt: String? = t1,
        entriesTruncated: Boolean = false,
        exportsTruncated: Boolean = false,
        completeness: Map<String, StageCompletenessDto> = mapOf(
            "SETUP" to StageCompletenessDto(stageKey = "SETUP", percent = 80, requiredFilled = 4, requiredTotal = 5)
        ),
    ) = DwReportHistoryDto(
        workshopId = "w1",
        workshopUpdatedAt = workshopUpdatedAt,
        serverTime = now,
        completeness = completeness,
        exports = exports,
        exportsTruncated = exportsTruncated,
        entries = entries,
        entriesTruncated = entriesTruncated,
    )

    @Test
    fun `one stage was written to and the other is provably identical`() {
        val diff = dwDiffExports(history(), "e1", "e2")!!

        assertEquals(listOf("COSTS"), diff.touchedStageKeys)
        assertEquals(listOf("SETUP"), diff.untouchedStageKeys)
        assertTrue(diff.byStage.getValue("COSTS").touched)
        assertFalse(diff.byStage.getValue("SETUP").touched)
    }

    @Test
    fun `added, rewritten and removed are counted apart`() {
        val diff = dwDiffExports(history(), "e1", "e2")!!
        val costs = diff.byStage.getValue("COSTS")
        assertEquals("the row created inside the window is an addition", 1, costs.rowsAdded)
        assertEquals("the pre-existing row saved inside the window is a rewrite", 1, costs.rowsRewritten)
        assertEquals(0, costs.rowsRemoved)
    }

    @Test
    fun `a row struck out inside the window is REMOVED, which no other payload in this API can show`() {
        val struck = entry("c3", "COSTS", created = "2026-07-30T09:00:00+00:00", updated = t1, deleted = t1)
        val diff = dwDiffExports(history(entries = listOf(struck)), "e1", "e2")!!
        assertEquals(1, diff.byStage.getValue("COSTS").rowsRemoved)
        assertEquals(0, diff.byStage.getValue("COSTS").rowsRewritten)
    }

    @Test
    fun `a row created AND removed inside the window is transient, not a difference between the files`() {
        val ghost = entry("c9", "COSTS", created = t1, updated = t1, deleted = t1)
        val diff = dwDiffExports(history(entries = listOf(ghost)), "e1", "e2")!!
        val costs = diff.byStage.getValue("COSTS")
        assertEquals(1, costs.rowsTransient)
        assertEquals(0, costs.rowsAdded)
        assertEquals(0, costs.rowsRemoved)
        assertEquals(0, costs.rowsRewritten)
        assertFalse("a stage whose only movement was transient is not 'touched'", costs.touched)
    }

    @Test
    fun `a row created after the later file is in neither, and is not an addition`() {
        val future = entry("c8", "COSTS", created = t3)
        val diff = dwDiffExports(history(entries = listOf(future)), "e1", "e2")!!
        val costs = diff.byStage.getValue("COSTS")
        assertEquals(0, costs.rowsAdded)
        assertEquals(0, costs.rowsTransient)
        assertFalse(costs.touched)
        assertFalse(costs.currentReflectsBoth)
    }

    @Test
    fun `an unreadable createdAt makes a row absent rather than eternal`() {
        val broken = entry("c7", "COSTS", created = "not a date", updated = "not a date")
        val diff = dwDiffExports(history(entries = listOf(broken)), "e1", "e2")!!
        val costs = diff.byStage.getValue("COSTS")
        assertEquals(0, costs.rowsRemoved)
        assertEquals(0, costs.rowsAdded)
        assertFalse(costs.touched)
    }

    @Test
    fun `a percentage is attached only to a stage nothing has touched SINCE the earlier file`() {
        val diff = dwDiffExports(history(), "e1", "e2")!!
        val setup = diff.byStage.getValue("SETUP")
        val costs = diff.byStage.getValue("COSTS")

        assertTrue("SETUP was not written after the first file", setup.currentReflectsBoth)
        assertFalse("COSTS was", costs.currentReflectsBoth)

        val score = StageCompletenessDto(stageKey = "SETUP", percent = 80)
        assertEquals(
            "80% of required fields, unchanged",
            dwStageCompletenessNote(setup, score)
        )
        assertNull(dwStageCompletenessNote(costs, score))
        assertNull(dwStageCompletenessNote(setup, null))
    }

    @Test
    fun `an edit AFTER the later file still clears currentReflectsBoth`() {
        val late = entry("s2", "SETUP", created = "2026-07-30T09:00:00+00:00", updated = t3)
        val diff = dwDiffExports(history(entries = listOf(late)), "e1", "e2")!!
        val setup = diff.byStage.getValue("SETUP")
        assertFalse("the two files are still identical here", setup.touched)
        assertFalse("but today's data is not what they carried", setup.currentReflectsBoth)
    }

    @Test
    fun `which file is earlier is a fact about the timestamps, not about which picker was set`() {
        val forwards = dwDiffExports(history(), "e1", "e2")!!
        val backwards = dwDiffExports(history(), "e2", "e1")!!
        assertEquals(forwards.earlier.id, backwards.earlier.id)
        assertEquals(forwards.later.id, backwards.later.id)
        assertEquals(forwards.touchedStageKeys, backwards.touchedStageKeys)
    }

    @Test
    fun `a window with an open end is not a window`() {
        val undated = history(exports = listOf(export("e1", t0), export("eX", null)))
        assertNull(dwDiffExports(undated, "e1", "eX"))
        assertNull("an id nobody sent", dwDiffExports(history(), "e1", "nope"))
    }

    @Test
    fun `the server's generation is preferred over this client's position in the window`() {
        val served = history(
            exports = listOf(export("e1", t0, generation = 104), export("e2", t2, generation = 105)),
            exportsTruncated = true,
        )
        assertEquals(104, dwGenerationOf(served, "e1"))
        assertEquals(105, dwGenerationOf(served, "e2"))
        assertTrue(dwGenerationsAreAbsolute(served))

        val diff = dwDiffExports(served, "e1", "e2")!!
        assertEquals(104, diff.earlierGeneration)
        assertEquals(105, diff.laterGeneration)
    }

    @Test
    fun `a server that does not send one falls back to the position, and the label admits it`() {
        val old = history(exportsTruncated = true)
        assertFalse(dwGenerationsAreAbsolute(old))
        assertEquals(1, dwGenerationOf(old, "e1"))
        assertEquals(2, dwGenerationOf(old, "e2"))

        assertEquals(
            "Generation 2 of the 100 most recent",
            dwGenerationLabel(2, absolute = false, windowTruncated = true)
        )
        assertEquals("Generation 2", dwGenerationLabel(2, absolute = true, windowTruncated = true))
        assertEquals("Generation 2", dwGenerationLabel(2, absolute = false, windowTruncated = false))
    }

    @Test
    fun `an undated file has no generation and says so instead of printing zero`() {
        val served = history(exports = listOf(export("e1", t0), export("eX", null)))
        assertEquals(0, dwGenerationOf(served, "eX"))
        val label = dwGenerationLabel(0, absolute = false, windowTruncated = false)
        assertFalse(label.contains("0"))
        assertTrue(label.contains("cannot be compared"))
    }

    @Test
    fun `a truncated listing is disclosed differently once the numbers are the server's`() {
        val derived = dwDiffLimits(
            dwDiffExports(history(exportsTruncated = true), "e1", "e2")!!,
            history(exportsTruncated = true),
        )
        assertTrue(derived.any { it.contains("count only those hundred") })

        val served = history(
            exports = listOf(export("e1", t0, generation = 104), export("e2", t2, generation = 105)),
            exportsTruncated = true,
        )
        val absolute = dwDiffLimits(dwDiffExports(served, "e1", "e2")!!, served)
        assertTrue(absolute.any { it.contains("Only the most recent 100 files are listed") })
        assertFalse(absolute.any { it.contains("count only those hundred") })
    }

    @Test
    fun `two files with the same checksum are the same file, and it is said plainly`() {
        val same = history(exports = listOf(export("e1", t0, checksum = "ff"), export("e2", t2, checksum = "ff")))
        val diff = dwDiffExports(same, "e1", "e2")!!
        assertTrue(diff.identicalFile)
        assertTrue(diff.checksumComparable)
        assertTrue(dwFileFacts(diff) { it }.first().contains("byte-for-byte identical"))
    }

    @Test
    fun `a missing checksum is an absence of evidence, never evidence of a difference`() {
        val partial = history(exports = listOf(export("e1", t0, checksum = null), export("e2", t2, checksum = "ff")))
        val diff = dwDiffExports(partial, "e1", "e2")!!
        assertFalse(diff.checksumComparable)
        assertFalse(diff.identicalFile)
        val facts = dwFileFacts(diff) { it }
        assertTrue(facts.first().contains("cannot be compared by their contents"))
        assertFalse("no fact may claim the two files differ", facts.any { it.contains("The two files differ") })
    }

    @Test
    fun `a template change is named with the template's own name where one is known`() {
        val moved = history(
            exports = listOf(export("e1", t0, template = "DCH_STANDARD"), export("e2", t2, template = "AGENCY"))
        )
        val diff = dwDiffExports(moved, "e1", "e2")!!
        assertTrue(diff.templateChanged)
        val named = dwFileFacts(diff) { id -> if (id == "AGENCY") "Implementing agency format" else id }
        assertTrue(named.any { it.contains("Implementing agency format") })
        assertTrue(dwFileFacts(diff) { it }.any { it.contains("AGENCY") })
    }

    @Test
    fun `size and page deltas are withheld rather than guessed when either side never recorded one`() {
        val partial = history(
            exports = listOf(export("e1", t0, size = null, pages = null), export("e2", t2, size = 2_000L, pages = 12))
        )
        val diff = dwDiffExports(partial, "e1", "e2")!!
        assertNull(diff.sizeDelta)
        assertNull(diff.pageDelta)
        val facts = dwFileFacts(diff) { it }
        assertFalse(facts.any { it.contains("longer") || it.contains("larger") })
    }

    @Test
    fun `a difference in format is named as construction rather than as a revision`() {
        val mixed = history(exports = listOf(export("e1", t0, format = "DOCX"), export("e2", t2, format = "PDF")))
        val diff = dwDiffExports(mixed, "e1", "e2")!!
        assertTrue(diff.formatDiffers)
        assertTrue(dwFileFacts(diff) { it }.any { it.contains("not by revision") })
    }

    @Test
    fun `a registry version present on only one side is not a registry change`() {
        val partial = history(exports = listOf(export("e1", t0, schema = null), export("e2", t2, schema = "v2")))
        assertFalse(dwDiffExports(partial, "e1", "e2")!!.schemaVersionChanged)

        val moved = history(exports = listOf(export("e1", t0, schema = "v1"), export("e2", t2, schema = "v2")))
        assertTrue(dwDiffExports(moved, "e1", "e2")!!.schemaVersionChanged)
    }

    @Test
    fun `a file made on a phone puts the device's clock in the window, and the limits say so`() {
        val diff = dwDiffExports(history(), "e1", "e2")!!
        assertTrue("e2 was made on a phone with no network", diff.deviceClockInvolved)
        assertTrue(dwDiffLimits(diff, history()).any { it.contains("phone’s clock") })

        val serverOnly = history(exports = listOf(export("e1", t0), export("e2", t2, onDevice = false)))
        val quiet = dwDiffExports(serverOnly, "e1", "e2")!!
        assertFalse(quiet.deviceClockInvolved)
        assertFalse(dwDiffLimits(quiet, serverOnly).any { it.contains("device’s clock") })
    }

    @Test
    fun `the cover page is claimed identical only in the negative direction`() {
        val quiet = history(workshopUpdatedAt = "2026-07-01T09:00:00+00:00")
        val proof = dwDiffExports(quiet, "e1", "e2")!!
        assertEquals(false, proof.headerRowWritten)
        assertTrue(dwHeaderVerdict(proof)!!.contains("were not written at all"))

        val touched = dwDiffExports(history(), "e1", "e2")!!
        assertEquals(true, touched.headerRowWritten)
        assertTrue(dwHeaderVerdict(touched)!!.contains("does not mean the cover details changed"))

        assertNull(dwHeaderVerdict(dwDiffExports(history(workshopUpdatedAt = null), "e1", "e2")!!))
    }

    @Test
    fun `a capped timeline withdraws every claim of identical data`() {
        val capped = history(entriesTruncated = true)
        val diff = dwDiffExports(capped, "e1", "e2")!!
        assertFalse(diff.timelineComplete)

        assertEquals(listOf("SETUP"), diff.untouchedStageKeys)
        val headline = dwDiffHeadline(diff, absolute = true, windowTruncated = false)
        assertFalse(headline.contains("identical"))
        assertTrue(dwDiffLimits(diff, capped).any { it.contains("timeline was capped") })
    }

    @Test
    fun `the headline says WRITTEN and never CHANGED`() {
        val headline = dwDiffHeadline(dwDiffExports(history(), "e1", "e2")!!, absolute = true, windowTruncated = false)
        assertTrue(headline.contains("Generation 1 → generation 2"))
        assertTrue("1 stage was written to", headline.contains("1 stage was written to"))
        assertTrue(headline.contains("1 other stage carried identical data in both."))
        assertFalse(headline.contains("changed"))
    }

    @Test
    fun `nothing written at all is its own sentence rather than an empty list`() {
        val still = history(entries = listOf(entry("s1", "SETUP", created = "2026-07-30T09:00:00+00:00")))
        val headline = dwDiffHeadline(dwDiffExports(still, "e1", "e2")!!, absolute = true, windowTruncated = false)
        assertTrue(headline.contains("no stage of the workshop was written to"))
    }

    @Test
    fun `the four standing limits are always on screen`() {
        val plain = history(exports = listOf(export("e1", t0), export("e2", t2)))
        val limits = dwDiffLimits(dwDiffExports(plain, "e1", "e2")!!, plain)
        assertEquals(4, limits.size)
        assertTrue(limits[0].contains("Which field changed"))
        assertTrue(limits[1].contains("answers actually differ"))
    }

    @Test
    fun `the offline sentence says why, and that the fieldwork is safe`() {
        val said = dwReportHistoryFailure(unreachable = true, status = null, served = null)
        assertEquals(DW_REPORT_HISTORY_OFFLINE, said)
        assertTrue(said.contains("other devices by other people"))
        assertTrue(said.contains("Everything you have captured is still here."))
    }

    @Test
    fun `a dropped connection is told as being offline, and a refusal is not`() {
        assertEquals(
            DW_REPORT_HISTORY_OFFLINE,
            dwReportHistoryFailure(unreachable = true, status = 401, served = "Not authenticated")
        )
        assertEquals(
            "the server's own sentence is preferred over anything invented here",
            "You may not read this workshop.",
            dwReportHistoryFailure(unreachable = false, status = 403, served = "You may not read this workshop.")
        )
        assertTrue(
            dwReportHistoryFailure(unreachable = false, status = 404, served = null)
                .contains("Nothing has been deleted from this phone")
        )
        assertTrue(
            dwReportHistoryFailure(unreachable = false, status = 500, served = null)
                .contains("Nothing you have captured is affected")
        )
    }

    @Test
    fun `a workshop that never reached the repository is told the truth about its own files`() {
        assertFalse(DW_REPORT_HISTORY_LOCAL_ONLY.contains("no file has been generated"))
        assertTrue(DW_REPORT_HISTORY_LOCAL_ONLY.contains("still made on this phone"))
        assertTrue(DW_REPORT_HISTORY_LOCAL_ONLY.contains("listed here once the workshop has been uploaded"))
    }

    @Test
    fun `the same bytes recorded twice is surfaced rather than left in sixty-four hex characters`() {
        val twice = history(
            exports = listOf(
                export("e1", t0, generation = 1, checksum = "ff"),
                export("e2", t2, generation = 2, checksum = "ff"),
            )
        )
        val duplicates = dwSameFileAs(twice, "e2")
        assertEquals(listOf("e1"), duplicates.map { it.id })
        assertTrue(
            dwDuplicateFileNote(duplicates.map { dwGenerationOf(twice, it.id) })!!
                .contains("the same file as generation 1")
        )
        val unmatched = history(
            exports = listOf(export("e1", t0, checksum = null), export("e2", t2, checksum = null))
        )
        assertTrue(dwSameFileAs(unmatched, "e1").isEmpty())
        assertNull(dwDuplicateFileNote(emptyList()))
        assertNull(dwDuplicateFileNote(listOf(0)))
    }

    @Test
    fun `edits since the newest file are measured against the SERVER's clock`() {
        val after = history(
            entries = listOf(
                entry("s1", "SETUP", created = "2026-07-30T09:00:00+00:00", updated = t3),
                entry("c1", "COSTS", created = "2026-07-30T09:00:00+00:00", updated = t1),
            )
        )
        assertEquals(listOf("SETUP"), dwStagesTouchedSince(after, "e2"))
        assertTrue(dwStaleSinceNote(1)!!.contains("1 stage has been written to since this file"))
        assertTrue(dwStaleSinceNote(2)!!.contains("2 stages have been written to"))
        assertNull("nothing has moved, so nothing is said", dwStaleSinceNote(0))
        assertTrue(dwStagesTouchedSince(after, "unknown").isEmpty())
    }

    @Test
    fun `generation order is oldest first and drops what cannot be placed in time`() {
        val jumbled = history(
            exports = listOf(export("e2", t2), export("eX", null), export("e1", t0))
        )
        assertEquals(listOf("e1", "e2"), dwInGenerationOrder(jumbled).map { it.id })
    }

    @Test
    fun `a size prints the same on a phone as it does in the browser`() {
        assertEquals("-", dwExportSize(null))
        assertEquals("512 B", dwExportSize(512L))
        assertEquals("1.0 KB", dwExportSize(1024L))
        assertEquals("1.5 KB", dwExportSize(1536L))
        assertEquals("1.0 MB", dwExportSize(1024L * 1024))
        assertEquals("1.5 MB", dwExportSize(1024L * 1024 * 3 / 2))
        assertEquals("2.0 GB", dwExportSize(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `both spellings of an ISO stamp parse, because the payload carries both`() {
        assertNotNull(dwHistoryMillis("2026-08-10T09:00:00Z"))
        assertNotNull(dwHistoryMillis("2026-08-10T09:00:00+00:00"))
        assertEquals(dwHistoryMillis("2026-08-10T09:00:00Z"), dwHistoryMillis("2026-08-10T14:30:00+05:30"))
        assertNull(dwHistoryMillis(null))
        assertNull(dwHistoryMillis("   "))
        assertNull(dwHistoryMillis("last Tuesday"))
    }

    @Test
    fun `a picker row names the generation, the moment and the format`() {
        val label = dwExportOptionLabel(export("e2", t2, generation = 2), 2)
        assertTrue(label.startsWith("Generation 2 · "))
        assertTrue(label.endsWith(" · DOCX"))
        assertFalse(label.contains("T09:00:00"))
    }
}
