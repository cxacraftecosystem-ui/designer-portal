package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SANCTIONING AUTHORITY'S SIGN-OFF AS THE HANDSET READS IT — decoded, frozen, and printed.
 *
 * The handset takes no decision here (approving, sending back and handing on are the web's), so what
 * can go wrong on this side is reading: a server that grew the keys must decode on an older build and
 * an older server must decode on this one; a report the authority signed off must be said to be
 * closed and a designer's own submission from before 2026-09-13 must NOT be; and the Certification
 * lines must be the server's, word for word, or the phone's copy and the office's copy of one approved
 * report disagree about who approved it.
 */
class DwReportApprovalTest {

    /** Configured exactly as `ApiClient` configures the converter Retrofit actually uses. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    // ── Decoding ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a list row from a server that predates the sign-off decodes with every new key empty`() {
        val row = json.decodeFromString(
            DesignWorkshopDto.serializer(),
            """{"id":"dw_1","title":"Barpali","status":"SUBMITTED","submissionRound":2}""",
        )
        assertEquals("SUBMITTED", row.status)
        assertNull(row.approvedAt)
        assertNull(row.approvedById)
        assertNull(row.approvedRound)
        assertNull(row.handedOnAt)
        assertNull(row.handedOnTo)
    }

    @Test
    fun `a list row carrying the sign-off decodes every key`() {
        val row = json.decodeFromString(
            DesignWorkshopDto.serializer(),
            """
            {"id":"dw_1","status":"SUBMITTED","approvedAt":"2026-10-09T10:15:00+00:00",
             "approvedById":"u_min","approvedRound":3,"handedOnAt":"2026-10-10T08:00:00+00:00",
             "handedOnTo":"Office of the Development Commissioner (Handicrafts)",
             "handedOnById":"u_master","handedOnExportId":"exp_1","lastHandedInAt":"2026-10-08T09:00:00+00:00"}
            """.trimIndent(),
        )
        assertEquals("2026-10-09T10:15:00+00:00", row.approvedAt)
        assertEquals("u_min", row.approvedById)
        assertEquals(3, row.approvedRound)
        assertEquals("2026-10-10T08:00:00+00:00", row.handedOnAt)
        assertEquals("Office of the Development Commissioner (Handicrafts)", row.handedOnTo)
    }

    @Test
    fun `the single read decodes the names and the role, and an older server reads as unsigned`() {
        val signed = json.decodeFromString(
            DesignWorkshopDetailDto.serializer(),
            """
            {"id":"dw_1","status":"APPROVED","approvedAt":"2026-10-09T10:15:00+00:00","approvedById":"u_min",
             "approvedRound":3,"approvedByName":"Ritu Menon","approvedByRole":"MINISTRY_ADMIN",
             "handedOnByName":null,"decisions":[{"id":"r1","kind":"APPROVED"}]}
            """.trimIndent(),
        )
        assertEquals("Ritu Menon", signed.approvedByName)
        assertEquals("MINISTRY_ADMIN", signed.approvedByRole)
        assertNull(signed.handedOnByName)

        val older = json.decodeFromString(DesignWorkshopDetailDto.serializer(), """{"id":"dw_1","status":"APPROVED"}""")
        assertNull(older.approvedAt)
        assertNull(older.approvedByName)
        assertNull(older.approvedByRole)
    }

    @Test
    fun `a correction suggestion says whether the approving authority wrote it, and defaults to no`() {
        val officer = json.decodeFromString(
            DwInspectionFeedbackDto.serializer(),
            """{"id":"f1","round":2,"note":"Fix the cost sheet.","sentBack":true,"actorId":"u_insp"}""",
        )
        assertFalse(officer.byApprovingAuthority)

        val authority = json.decodeFromString(
            DwInspectionFeedbackDto.serializer(),
            """{"id":"f2","round":3,"note":"The yield table is wrong.","sentBack":true,"actorId":"u_min","byApprovingAuthority":true}""",
        )
        assertTrue(authority.byApprovingAuthority)
    }

    // ── The freeze ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an approved report and a handed-on one are frozen, and nothing else is`() {
        assertTrue(dwIsFrozen("APPROVED", null))
        assertTrue(dwIsFrozen("SUBMITTED", "2026-10-10T08:00:00+00:00"))
        // THE LEGACY ROW. `SUBMITTED` meant the designer's own submission until 2026-09-13 and nothing
        // was backfilled, so a SUBMITTED row with no hand-on is still its designers' to reopen.
        assertFalse(dwIsFrozen("SUBMITTED", null))
        assertFalse(dwIsFrozen("SUBMITTED", ""))
        for (open in listOf("DRAFT", "IN_PROGRESS", "COMPLETE", "PRE_SUBMISSION", "NEEDS_REVISION", "ARCHIVED")) {
            assertFalse("$open must not be frozen", dwIsFrozen(open, null))
        }
        // A status from a newer server is not guessed at: the server refuses what it refuses.
        assertFalse(dwIsFrozen("SOME_FUTURE_STATE", "2026-10-10T08:00:00+00:00"))
        // The wire token's case is not the screen's business.
        assertTrue(dwIsFrozen(" approved ", null))
    }

    @Test
    fun `the frozen sentences are the server's, byte for byte`() {
        assertEquals(
            "This report has been approved, so it can no longer be changed. If something in it needs " +
                "correcting, ask the Ministry Admin to withdraw the approval; it then comes back to its " +
                "designers to correct and hand in again.",
            dwFrozenSentence("APPROVED", null),
        )
        assertEquals(
            "This report has been approved and handed on to the office, so it can no longer be changed. " +
                "If something in it needs correcting, ask the Ministry Admin to return it to its designers.",
            dwFrozenSentence("SUBMITTED", "2026-10-10T08:00:00+00:00"),
        )
        assertNull(dwFrozenSentence("SUBMITTED", null))
        assertNull(dwFrozenSentence("NEEDS_REVISION", null))
    }

    @Test
    fun `the memo keeps what the single read said, and a reopened report clears it`() {
        val id = "dw_memo_${System.nanoTime()}"
        DwReportFreeze.rememberRead(
            id,
            DesignWorkshopDetailDto(
                id = id, status = "APPROVED", approvedAt = "2026-10-09T10:15:00+00:00",
                approvedByName = "Ritu Menon", approvedByRole = "MINISTRY_ADMIN",
            ),
        )
        assertEquals(DW_FROZEN_APPROVED_SENTENCE, DwReportFreeze.sentenceFor(id))
        assertEquals("Ritu Menon", DwReportFreeze.signOffFor(id)?.approvedByName)

        // The approval withdrawn: the report is back in its designers' hands and must print nothing.
        DwReportFreeze.rememberRead(id, DesignWorkshopDetailDto(id = id, status = "NEEDS_REVISION"))
        assertNull(DwReportFreeze.sentenceFor(id))
        assertNull(DwReportFreeze.signOffFor(id))
    }

    // ── The Certification lines ──────────────────────────────────────────────────────────────────

    @Test
    fun `no sign-off is read off a report that is not approved or handed on`() {
        assertNull(dwReportSignOffOf(null))
        assertNull(dwReportSignOffOf(DesignWorkshopDetailDto(status = "PRE_SUBMISSION")))
        // A legacy SUBMITTED row: nobody approved it here, whatever columns it carries.
        assertNull(
            dwReportSignOffOf(DesignWorkshopDetailDto(status = "SUBMITTED", approvedAt = "2026-10-09T10:15:00+00:00"))
        )
        // APPROVED with no moment is a payload this build cannot print honestly.
        assertNull(dwReportSignOffOf(DesignWorkshopDetailDto(status = "APPROVED")))
    }

    @Test
    fun `an approved report prints who approved it, as the server prints it`() {
        val signOff = dwReportSignOffOf(
            DesignWorkshopDetailDto(
                status = "APPROVED", approvedAt = "2026-10-09T10:15:00+00:00",
                approvedByName = "Ritu Menon", approvedByRole = "MINISTRY_ADMIN",
            )
        )
        assertEquals(listOf("Approved by Ritu Menon (Ministry Admin) on 09 Oct 2026."), dwReportSignOffLines(signOff))
    }

    @Test
    fun `a handed-on report prints the approval and where it went`() {
        val signOff = dwReportSignOffOf(
            DesignWorkshopDetailDto(
                status = "SUBMITTED", approvedAt = "2026-10-09T10:15:00+00:00",
                approvedByName = "Ritu Menon", approvedByRole = "MASTER_ADMIN",
                handedOnAt = "2026-10-10T08:00:00+00:00",
                handedOnTo = "Office of the Development Commissioner (Handicrafts)",
            )
        )
        assertEquals(
            listOf(
                "Approved by Ritu Menon (Master Admin) on 09 Oct 2026.",
                "Handed on to Office of the Development Commissioner (Handicrafts) on 10 Oct 2026.",
            ),
            dwReportSignOffLines(signOff),
        )
    }

    @Test
    fun `a missing name, an unknown role and a blank office are said plainly and never guessed`() {
        assertEquals(
            listOf("Approved on 09 Oct 2026.", "Handed on to the office on 10 Oct 2026."),
            dwReportSignOffLines(
                DwReportSignOff(
                    approvedByName = "  ", approvedByRole = "MINISTRY_ADMIN",
                    approvedAt = "2026-10-09T10:15:00+00:00",
                    handedOnTo = "", handedOnAt = "2026-10-10T08:00:00+00:00",
                )
            ),
        )
        assertEquals(
            listOf("Approved by Ritu Menon on 09 Oct 2026."),
            dwReportSignOffLines(
                DwReportSignOff(approvedByName = "Ritu Menon", approvedByRole = null, approvedAt = "2026-10-09")
            ),
        )
        assertEquals(emptyList<String>(), dwReportSignOffLines(null))
        assertEquals(emptyList<String>(), dwReportSignOffLines(DwReportSignOff(approvedByName = "Ritu Menon")))
    }

    @Test
    fun `the approving roles have the contract's labels, and any other role is its words, as the server prints it`() {
        assertEquals("Ministry Admin", dwApproverRoleLabel("MINISTRY_ADMIN"))
        assertEquals("Master Admin", dwApproverRoleLabel("MASTER_ADMIN"))
        // An approver whose role has since changed: `str.title()` on the server, word for word here.
        assertEquals("Regional Director", dwApproverRoleLabel("REGIONAL_DIRECTOR"))
        assertEquals("Admin", dwApproverRoleLabel("ADMIN"))
        assertNull(dwApproverRoleLabel(null))
        assertNull(dwApproverRoleLabel("  "))
    }
}
