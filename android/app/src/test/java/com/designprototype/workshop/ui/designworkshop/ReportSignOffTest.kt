package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwReportSignOff
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.ParagraphBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.SignatureBlock
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSignOffTest {

    private val schema = SchemaResponse(version = "test", stages = emptyList())

    private val draft = WorkshopDraft(
        workshopId = "local-test",
        title = "Barpali cluster",
        stages = mapOf(
            "WORKSHOP_SETUP" to StageDraft(
                stageId = "WORKSHOP_SETUP",
                values = mapOf("designerName" to JsonPrimitive("Asha Rao")),
            )
        ),
    )

    private fun build(signOff: DwReportSignOff?): ReportDocument {
        val plan = reportPlanFor(
            schema = schema,
            draft = draft,
            workshopId = draft.workshopId,
            requestedTemplateId = "DCH_STANDARD",
            requestedAccent = "",
            format = "DOCX",
            generatedAt = "2026-10-10T09:00:00Z",
            signOff = signOff,
        )
        return buildWorkshopDocument(
            schema = schema,
            draft = draft,
            workshopId = draft.workshopId,
            templateId = "DCH_STANDARD",
            warnings = emptyList(),
            accent = "",
            imageFor = { null },
            plan = plan,
        )
    }

    private fun afterSignatures(document: ReportDocument): List<String> {
        val at = document.blocks.indexOfFirst { it is SignatureBlock }
        assertTrue("the DCH template prints a Certification section with a signatory", at >= 0)
        return document.blocks.drop(at + 1)
            .filterIsInstance<ParagraphBlock>()
            .map { block -> block.runs.joinToString("") { it.text } }
    }

    @Test
    fun `an approved report names who approved it after the signatures`() {
        val lines = afterSignatures(
            build(
                DwReportSignOff(
                    approvedByName = "Ritu Menon",
                    approvedByRole = "MINISTRY_ADMIN",
                    approvedAt = "2026-10-09T10:15:00+00:00",
                )
            )
        )
        assertEquals(listOf("Approved by Ritu Menon (Ministry Admin) on 09 Oct 2026."), lines)
    }

    @Test
    fun `a handed-on report names the approval and the office it went to`() {
        val lines = afterSignatures(
            build(
                DwReportSignOff(
                    approvedByName = "Ritu Menon",
                    approvedByRole = "MASTER_ADMIN",
                    approvedAt = "2026-10-09T10:15:00+00:00",
                    handedOnTo = "Office of the Development Commissioner (Handicrafts)",
                    handedOnAt = "2026-10-10T08:00:00+00:00",
                )
            )
        )
        assertEquals(
            listOf(
                "Approved by Ritu Menon (Master Admin) on 09 Oct 2026.",
                "Handed on to Office of the Development Commissioner (Handicrafts) on 10 Oct 2026.",
            ),
            lines,
        )
    }

    @Test
    fun `a report nobody has signed off prints no sign-off at all`() {
        val document = build(null)
        assertTrue(afterSignatures(document).isEmpty())
        val printed = document.blocks.filterIsInstance<ParagraphBlock>()
            .joinToString("\n") { block -> block.runs.joinToString("") { it.text } }
        assertFalse(printed.contains("Approved by"))
        assertFalse(printed.contains("Handed on to"))
    }
}
