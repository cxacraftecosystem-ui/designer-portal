package com.designprototype.workshop.ui

import com.designprototype.workshop.data.USAGE_BASIS_OFFERED_IN_SETTINGS
import com.designprototype.workshop.data.USAGE_BASIS_REQUIRED_AT_SIGN_IN
import com.designprototype.workshop.data.USAGE_CONSENT_GRANTED
import com.designprototype.workshop.data.USAGE_CONSENT_NOT_RECORDED
import com.designprototype.workshop.data.USAGE_CONSENT_REFUSED
import com.designprototype.workshop.data.UsageConsentDecisionDto
import com.designprototype.workshop.data.UsageConsentGateDto
import com.designprototype.workshop.data.UsageConsentRecordDto
import com.designprototype.workshop.data.UsageRouteRow
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.usageCount
import com.designprototype.workshop.data.usageDurationText
import com.designprototype.workshop.data.usageMetricText
import com.designprototype.workshop.data.usageWithheld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageConsentCopyTest {

    @Test
    fun `the missing notice changes what is said and never what is required`() {
        assertEquals(
            usageDoorHint(agreed = false, noticeReady = true, stillFetching = false),
            usageDoorHint(agreed = false, noticeReady = false, stillFetching = false)
        )

        val unticked = usageDoorHint(agreed = false, noticeReady = true, stillFetching = false)
        assertTrue(
            "must name the control that unblocks it",
            unticked.orEmpty().lowercase().contains("tick the box")
        )
    }

    @Test
    fun `a tick with no notice to file it against says so and does not claim to be filed`() {
        val filed = usageDoorHint(agreed = true, noticeReady = true, stillFetching = false)
        assertNull("nothing to say once the answer can be recorded", filed)

        val unfiled = usageDoorHint(agreed = true, noticeReady = false, stillFetching = false)
        assertEquals(USAGE_NOTICE_NOT_FILED_LINE, unfiled)
        assertTrue(USAGE_NOTICE_NOT_FILED_LINE.contains("not filed yet"))
        assertTrue(USAGE_NOTICE_NOT_FILED_LINE.contains("asked again after signing in"))
    }

    @Test
    fun `the first fetch says nothing at all while it is in flight`() {
        assertNull(usageDoorHint(agreed = true, noticeReady = false, stillFetching = true))
        assertEquals(
            USAGE_TICK_TO_SIGN_IN,
            usageDoorHint(agreed = false, noticeReady = false, stillFetching = true)
        )
    }

    @Test
    fun `the label and the link read as one sentence, and the link is the tail of it`() {
        assertEquals("I agree to the terms and conditions", "$USAGE_AGREE_LABEL $USAGE_TERMS_LINK")
        assertEquals(USAGE_TERMS_LINK, USAGE_TERMS_LINK.lowercase())
        assertFalse(USAGE_AGREE_LABEL.endsWith("."))
        assertNotEquals(TERMS_TITLE, USAGE_TERMS_LINK)

        assertNotEquals(USAGE_TICK_TO_SIGN_IN, USAGE_TICK_TO_CONTINUE)
        assertTrue(USAGE_TICK_TO_CONTINUE.lowercase().contains("tick the box"))
    }

    @Test
    fun `the terms are nine numbered clauses and the served notice is the tenth`() {
        assertEquals(9, TERMS_CLAUSES.size)
        assertEquals((1..9).toList(), TERMS_CLAUSES.map { it.number })
        assertEquals(10, TERMS_RECORDING_CLAUSE_NUMBER)

        TERMS_CLAUSES.forEach { clause ->
            assertTrue("clause ${clause.number} has no title", clause.title.isNotBlank())
            assertTrue("clause ${clause.number} has no body", clause.body.isNotBlank())
        }
    }

    @Test
    fun `the clauses are the web's, verbatim`() {
        val identity = TERMS_CLAUSES.first { it.number == 4 }
        assertEquals("Identity numbers", identity.title)
        assertTrue(identity.body.contains("Aadhaar and Pehchan card numbers are stored masked"))
        assertTrue(identity.body.contains("never shown in lists, exports or reports"))

        val offline = TERMS_CLAUSES.first { it.number == 6 }
        assertEquals("Offline use", offline.title)
        assertTrue(offline.body.contains("do not uninstall the app or clear its data"))
        assertTrue(offline.body.contains("waiting to upload"))

        val changes = TERMS_CLAUSES.first { it.number == 9 }
        assertTrue(changes.body.contains("The version you agreed to is recorded against your account"))
    }

    @Test
    fun `no clause writes a sentence the server owns`() {
        val headings = listOf(
            "What is recorded",
            "What is never recorded",
            "How long it is kept",
            "Who can read it",
            "Taking it back",
        )
        TERMS_CLAUSES.forEach { clause ->
            headings.forEach { heading ->
                assertFalse(
                    "clause ${clause.number} restates the served notice's \"$heading\"",
                    clause.body.contains(heading) || clause.title == heading
                )
            }
        }
    }

    @Test
    fun `a missing notice hides one clause and never the agreement`() {
        assertFalse(TERMS_NOTICE_UNAVAILABLE_LINE.contains("terms could not"))
        assertTrue(TERMS_NOTICE_UNAVAILABLE_LINE.contains("Everything above still applies"))
        assertTrue(TERMS_NOTICE_UNAVAILABLE_LINE.contains("Settings"))
    }

    @Test
    fun `blocking reads the server's boolean and nothing else`() {
        val account = UserDto(id = "u1", email = "a@b.c", name = "A", role = "DESIGNER")
        assertFalse(usageConsentBlocks(account))
        assertFalse(usageConsentBlocks(null))

        assertTrue(
            usageConsentBlocks(
                account.copy(usageConsentGate = UsageConsentGateDto(state = USAGE_CONSENT_NOT_RECORDED, required = true))
            )
        )
        assertFalse(
            usageConsentBlocks(
                account.copy(usageConsentGate = UsageConsentGateDto(state = USAGE_CONSENT_REFUSED, required = false))
            )
        )
        assertTrue(
            usageConsentBlocks(
                account.copy(
                    usageConsentGate = UsageConsentGateDto(
                        state = USAGE_CONSENT_GRANTED,
                        required = true,
                        noticeVersion = "2026-09-01.1",
                        agreedVersion = "2026-08-30.1",
                    )
                )
            )
        )
    }

    @Test
    fun `never-asked is worded as neither a grant nor a refusal`() {
        val unasked = usageConsentHeading(USAGE_CONSENT_NOT_RECORDED)
        val refused = usageConsentHeading(USAGE_CONSENT_REFUSED)
        val granted = usageConsentHeading(USAGE_CONSENT_GRANTED)
        assertNotEquals(unasked, refused)
        assertNotEquals(unasked, granted)
        assertTrue(unasked.contains("Nobody has asked"))
        assertFalse(unasked.contains("declined"))
        assertFalse(unasked.contains("agreed"))
    }

    @Test
    fun `an answer this build cannot read falls to never-asked and never to granted`() {
        assertEquals(
            usageConsentHeading(USAGE_CONSENT_NOT_RECORDED),
            usageConsentHeading("WITHDRAWN_PENDING_REVIEW")
        )
    }

    @Test
    fun `the detail line prints the circumstance and not only the answer`() {
        val turnstile = usageConsentDetail(
            UsageConsentRecordDto(
                state = USAGE_CONSENT_GRANTED,
                at = "2026-08-30T09:15:00+00:00",
                basis = USAGE_BASIS_REQUIRED_AT_SIGN_IN,
                version = "2026-08-30.1",
            )
        )
        assertTrue(turnstile.orEmpty().contains("condition of using the platform"))
        assertTrue(turnstile.orEmpty().contains("2026-08-30.1"))

        val free = usageConsentDetail(
            UsageConsentRecordDto(
                state = USAGE_CONSENT_GRANTED,
                at = "2026-08-30T09:15:00+00:00",
                basis = USAGE_BASIS_OFFERED_IN_SETTINGS,
            )
        )
        assertTrue(free.orEmpty().contains("freely"))
        assertFalse(free.orEmpty().contains("condition of using the platform"))

        assertNull(usageConsentDetail(UsageConsentRecordDto(state = USAGE_CONSENT_GRANTED)))
    }

    @Test
    fun `a decision answered offline shows both clocks, and one answered live shows one`() {
        val offline = usageDecisionLine(
            UsageConsentDecisionDto(
                decision = USAGE_CONSENT_GRANTED,
                basis = USAGE_BASIS_REQUIRED_AT_SIGN_IN,
                recordedAt = "2026-08-10T09:00:00+00:00",
                createdAt = "2026-08-24T11:30:00+00:00",
            )
        )
        assertTrue(offline.contains("received on"))

        val live = usageDecisionLine(
            UsageConsentDecisionDto(
                decision = USAGE_CONSENT_REFUSED,
                basis = USAGE_BASIS_OFFERED_IN_SETTINGS,
                recordedAt = null,
                createdAt = "2026-08-24T11:30:00+00:00",
            )
        )
        assertFalse(live.contains("received on"))
        assertTrue(live.contains("Declined"))
        assertTrue(live.contains("in settings"))
    }

    @Test
    fun `the withdrawal copy promises no loss of access and no rewriting of the log`() {
        assertTrue(USAGE_WITHDRAW_CONFIRM.contains("does not sign you out"))
        assertTrue(USAGE_WITHDRAW_CONFIRM.contains("stays in your own log"))
    }

    @Test
    fun `an empty decision log is explained rather than left blank`() {
        assertTrue(USAGE_NO_DECISIONS_LINE.contains("have not been asked"))
    }

    @Test
    fun `a failed read never renders as an answer of none`() {
        val empty = "Nothing was recorded about you in this window."
        val offline = usageReadNotice(UsageReadState.Failed, "requests", online = false, emptyLine = empty)
        val failed = usageReadNotice(UsageReadState.Failed, "requests", online = true, emptyLine = empty)
        val loading = usageReadNotice(UsageReadState.Loading, "requests", online = true, emptyLine = empty)

        assertNotEquals(offline, failed)
        assertNotEquals(offline, loading)
        assertNotEquals(failed, loading)
        assertNotEquals(empty, offline)
        assertNotEquals(empty, failed)

        assertTrue(offline.orEmpty().contains("nothing to show until it connects"))
        assertEquals(loadingListLine("requests"), loading)
    }

    @Test
    fun `an answered read with rows says nothing at all`() {
        assertNull(
            usageReadNotice(UsageReadState.Answered(3), "requests", online = true, emptyLine = "none")
        )
        assertEquals(
            "none",
            usageReadNotice(UsageReadState.Answered(0), "requests", online = true, emptyLine = "none")
        )
    }

    @Test
    fun `a withheld metric is a dash and never a zero`() {
        assertEquals("—", usageMetricText(null))
        assertEquals("—", usageDurationText(null))
        assertEquals("0", usageMetricText(0))
        assertNotEquals(usageMetricText(0), usageMetricText(null))

        val withheld = UsageRouteRow(routeTemplate = "/x", withheld = true, withheldBecause = "too few")
        val quiet = UsageRouteRow(routeTemplate = "/y", withheld = false, requests = 0, ok = 0)
        assertTrue(usageWithheld(withheld))
        assertFalse(usageWithheld(quiet))
    }

    @Test
    fun `counts are grouped the Indian way`() {
        assertEquals("123", usageCount(123))
        assertEquals("1,234", usageCount(1234))
        assertEquals("12,345", usageCount(12345))
        assertEquals("1,23,456", usageCount(123456))
        assertEquals("12,34,567", usageCount(1234567))
        assertEquals("0", usageCount(0))
        assertEquals("-1,234", usageCount(-1234))
    }
}
