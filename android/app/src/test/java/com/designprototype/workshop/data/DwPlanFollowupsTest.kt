package com.designprototype.workshop.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C9 and C11 on the handset: the copy marks leave the draft once a push is accepted (the web's
 * `copiesAfterPush`), and the stage-3 artisan-list import's pure halves and wiring.
 */
class DwPlanFollowupsTest {
    @Test
    fun anAcceptedPushMovesTheMarksIntoACopiedStamp() {
        val settled = dwCopiesAfterPush(
            copiedFrom = mapOf("history" to "w0", "craftIntroduction" to "w1"),
            provenance = DwStageProvenanceDto(),
            sent = mapOf("history" to "w0", "craftIntroduction" to "w1"),
        )!!
        assertEquals(emptyMap<String, String>(), settled.first)
        assertEquals(DW_COPIED_SOURCE, settled.second.singleton["history"]?.source)
        // So Keep as is sends the key to the server at the first press.
        assertEquals(setOf("history", "craftIntroduction"), dwPendingCopies(settled.first, settled.second.singleton, emptySet()))
    }

    @Test
    fun aBoxReCopiedDuringTheRoundTripKeepsItsNewerMark() {
        assertNull(dwCopiesAfterPush(mapOf("history" to "w9"), DwStageProvenanceDto(), mapOf("history" to "w0")))
    }

    @Test
    fun anExistingServerStampIsKept() {
        val stamp = DwFieldStampDto(by = "u1", at = "2026-10-17", source = DW_COPIED_SOURCE)
        val settled = dwCopiesAfterPush(
            mapOf("history" to "w0"),
            DwStageProvenanceDto(singleton = mapOf("history" to stamp)),
            mapOf("history" to "w0"),
        )!!
        assertEquals(stamp, settled.second.singleton["history"])
    }

    @Test
    fun theRosterReportPrintsEveryCountIncludingZeroes() {
        val tallies = dwRosterTallies(DwArtisanImportReportDto(rowsRead = 3, artisansCreated = 2, rowsRefused = 1))
        assertEquals(5, tallies.size)
        assertEquals("rows read" to 3, tallies[0])
        assertEquals("already recorded, linked to this workshop" to 0, tallies[2])
    }

    @Test
    fun aProblemNamesItsRowOrItsSheet() {
        assertEquals("Artisans · row 34", dwRosterProblemWhere(DwImportProblemDto(sheet = "Artisans", row = 34)))
        assertEquals("Details", dwRosterProblemWhere(DwImportProblemDto(sheet = "Details", row = null)))
    }

    @Test
    fun anEarlierUploadReadsAsTheWebHistoryLine() {
        val line = dwRosterHistoryLine(
            DwArtisanImportDto(
                rowsRead = 4, artisansCreated = 2, artisansLinked = 1, rowsRefused = 1,
                uploadedBy = "Asha", createdAt = "2026-10-17T10:00:00Z",
            )
        )
        assertEquals("2026-10-17 · Asha · 4 rows read · 2 created · 1 linked · 1 not added", line)
    }

    @Test
    fun theImportIsWiredToTheThreeRosterRoutesAndMountedOnStageThree() {
        val main = File("src/main/java/com/designprototype/workshop")
        val api = File(main, "data/WorkshopRepositoryApi.kt").readText()
        assertTrue(api.contains("@GET(\"design-workshops/{id}/roster/pro-forma\")"))
        assertTrue(api.contains("@POST(\"design-workshops/{id}/roster/upload\")"))
        assertTrue(api.contains("@GET(\"design-workshops/{id}/roster/imports\")"))
        val screen = File(main, "ui/designworkshop/StageScreen.kt").readText()
        assertTrue(screen.contains("if (spec.key == DW_PARTICIPANTS_STAGE) {"))
        assertTrue(screen.contains("DwRosterImportCard("))
        assertEquals(DW_PARTICIPANTS_STAGE, "WORKSHOP_PLAN_PARTICIPANTS_OPENING")
    }
}
