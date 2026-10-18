package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "CHECKED, NO CORRECTIONS" ON THE HANDSET (C5, 2026-10-17): the web's verdict, the same words, the
 * same refusal, and the answer folded back into the read the screen holds.
 */
class DwInspectionClearanceTest {

    private fun read(
        round: Int = 2,
        cleared: String? = null,
        notes: List<DwInspectionFeedbackDto> = emptyList(),
    ) = DwInspectionDetailDto(id = "w-1", status = "PRE_SUBMISSION", submissionRound = round, myClearedAt = cleared, inspectionFeedback = notes)

    @Test
    fun aNoteOnThisRoundBlocksTheVerdict() {
        val mine = DwInspectionFeedbackDto(id = "a", round = 2, note = "Fix it.", actorId = "me")
        assertTrue(dwClearanceBlocked(read(notes = listOf(mine)), "me"))
        assertEquals("You've filed notes on this round.", dwClearanceDescription(read(notes = listOf(mine)), "me"))
    }

    @Test
    fun anOlderRoundOrSomebodyElsesNoteDoesNot() {
        val older = DwInspectionFeedbackDto(id = "a", round = 1, note = "Old.", actorId = "me")
        val theirs = DwInspectionFeedbackDto(id = "b", round = 2, note = "Theirs.", actorId = "other")
        assertFalse(dwClearanceBlocked(read(notes = listOf(older, theirs)), "me"))
        assertEquals("Shown to the approving authority.", dwClearanceDescription(read(), "me"))
    }

    @Test
    fun theStatusLineIsADateLikeTheWebs() {
        assertEquals("Since 17 Oct 2026", dwClearanceStatus("2026-10-17T12:00:00Z"))
        assertNull(dwClearanceStatus(null))
        assertNull(dwClearanceStatus("not a date"))
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "backend").isDirectory }
        val panel = File(root, "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/InspectionFeedbackPanel.kt").readText()
        assertTrue(panel.contains("status = dwClearanceStatus(record.myClearedAt)"))
        assertFalse(panel.contains("it.take(10)"))
    }

    @Test
    fun aClearedRoundCanAlwaysBeTakenBack() {
        val mine = DwInspectionFeedbackDto(id = "a", round = 2, note = "Fix it.", actorId = "me")
        assertFalse(dwClearanceBlocked(read(cleared = "2026-10-17T09:00:00Z", notes = listOf(mine)), "me"))
    }

    @Test
    fun theAnswerCarriesTheVerdictBack() {
        val answer = DwInspectionFeedbackAnswerDto(status = "PRE_SUBMISSION", submissionRound = 2, myClearedAt = "2026-10-17T09:00:00Z")
        assertEquals("2026-10-17T09:00:00Z", read().withFeedbackAnswer(answer).myClearedAt)
        val withdrawn = DwInspectionFeedbackAnswerDto(status = "PRE_SUBMISSION", submissionRound = 2)
        assertNull(read(cleared = "x").withFeedbackAnswer(withdrawn).myClearedAt)
    }

    @Test
    fun theLabelAndTheRoutesAreTheWebsAndTheServers() {
        assertEquals("Checked, no corrections", DW_CLEARANCE_LABEL)
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "backend").isDirectory }
        val web = File(root, "frontend/app/(protected)/design-workshop-inspections/[id]/page.tsx").readText()
        assertTrue(web.contains("label=\"$DW_CLEARANCE_LABEL\""))
        val server = File(root, "backend/app/api/routes/design_workshop_inspections.py").readText()
        assertTrue(server.contains("@router.post(\"/{workshop_id}/clearance\")"))
        assertTrue(server.contains("@router.delete(\"/{workshop_id}/clearance\")"))
        val api = File(root, "android/app/src/main/java/com/designprototype/workshop/data/WorkshopRepositoryApi.kt").readText()
        assertTrue(api.contains("@POST(\"design-workshop-inspections/{id}/clearance\")"))
        assertTrue(api.contains("@DELETE(\"design-workshop-inspections/{id}/clearance\")"))
    }
}
