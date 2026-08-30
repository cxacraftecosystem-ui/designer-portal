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

/**
 * THE USAGE-CONSENT RULES THIS HANDSET CANNOT BE ALLOWED TO GET WRONG.
 *
 * ── WHY THESE PARTICULAR ASSERTIONS, AND NOT A SCREENSHOT ───────────────────────────────────────
 *
 * `app/build.gradle.kts` carries no Robolectric and no `ui-test-junit4`, so the JVM suite cannot
 * compose a screen and look at it — the trade `WorkshopOptionsTest` already made and states. Every
 * ruling worth pinning was therefore lifted out of the composables into pure functions, and the ones
 * below are pinned here because each is a defect that would be **invisible on a developer's desk**:
 *
 *  * three of the four door policies only occur when a fetch fails or a device is offline;
 *  * the "never re-ask a refusal" rule only fires for an account somebody withdrew on ANOTHER day;
 *  * the withheld branch only fires on a route fewer than five identified accounts used;
 *  * and the two-clock decision line only differs on a device that answered before it synced.
 *
 * A wrong answer in any of them is a lie about a consent, which is not a smaller kind of wrong.
 */
class UsageConsentCopyTest {

    // -----------------------------------------------------------------------------------------
    // The door
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a notice in hand makes the tick required`() {
        assertEquals(
            UsageDoorPolicy.Blocking,
            usageDoorPolicy(noticeReady = true, stillFetching = false)
        )
        // Still fetching but ALREADY HOLDING a stored copy: the stored copy is what is on screen, so
        // the question can be put and the tick is required. This is the ordinary offline case.
        assertEquals(
            UsageDoorPolicy.Blocking,
            usageDoorPolicy(noticeReady = true, stillFetching = true)
        )
    }

    @Test
    fun `no notice from any source lets sign-in through rather than locking the fleet out`() {
        // THE MOST IMPORTANT ASSERTION IN THIS FILE, and the least obvious. `consent_notice()` is
        // computed on the server from the running collection policy, so one bad deploy can break
        // `GET /usage/consent/notice` while `POST /auth/login` beside it keeps working. A door that
        // insisted on a tick it could not put in front of anybody would be a permanently disabled
        // button on the one screen that has no other controls — every handset in every village.
        //
        // The requirement is NOT weakened by this: `UsageConsentGateScreen` blocks after sign-in,
        // where a token exists, `GET /usage/consent` carries the notice inline as a second source,
        // and the person can actually answer. The enforcement moves one screen later; it does not go
        // away. Anyone tightening this to `Blocking` must first say what happens to a fleet that
        // cannot read the notice.
        assertEquals(
            UsageDoorPolicy.AskLater,
            usageDoorPolicy(noticeReady = false, stillFetching = false)
        )
    }

    @Test
    fun `the door waits while the first fetch is in flight`() {
        assertEquals(
            UsageDoorPolicy.Waiting,
            usageDoorPolicy(noticeReady = false, stillFetching = true)
        )
    }

    @Test
    fun `the blocked reason names the checkbox, and says something different with no signal`() {
        val unticked = usageSignInBlockedReason(UsageDoorPolicy.Blocking, agreed = false, online = true)
        assertTrue("must name the control that unblocks it", unticked.orEmpty().contains("tick the box"))

        // A disabled button announces "disabled" and nothing else, so this sentence IS the
        // accessibility affordance. Nothing may return null while sign-in is actually blocked.
        assertNull(usageSignInBlockedReason(UsageDoorPolicy.Blocking, agreed = true, online = true))
        assertNull(usageSignInBlockedReason(UsageDoorPolicy.AskLater, agreed = false, online = true))

        // Two different sentences for waiting, on `outboxDeviceBanner`'s rule: telling somebody with
        // four bars to go and find a signal sends them up a hill that cannot help.
        val waitingOnline = usageSignInBlockedReason(UsageDoorPolicy.Waiting, agreed = false, online = true)
        val waitingOffline = usageSignInBlockedReason(UsageDoorPolicy.Waiting, agreed = false, online = false)
        assertNotEquals(waitingOnline, waitingOffline)
        assertTrue(waitingOffline.orEmpty().contains("no connection"))
    }

    @Test
    fun `the ask-later line says the question is coming and not that it was waived`() {
        // Somebody let past the door must not conclude the question has been answered on their
        // behalf, or the gate screen a moment later reads as the app asking twice.
        assertTrue(USAGE_ASK_LATER_LINE.contains("after signing in"))
        assertTrue(USAGE_ASK_LATER_LINE.contains("not be able to use the app until you have answered"))
    }

    // -----------------------------------------------------------------------------------------
    // The gate the client renders and never computes
    // -----------------------------------------------------------------------------------------

    @Test
    fun `blocking reads the server's boolean and nothing else`() {
        val account = UserDto(id = "u1", email = "a@b.c", name = "A", role = "DESIGNER")
        // No gate at all — a deployment older than the flow. Blocks nobody: it is not a claim that
        // the gate is open, it is the absence of one.
        assertFalse(usageConsentBlocks(account))
        assertFalse(usageConsentBlocks(null))

        assertTrue(
            usageConsentBlocks(
                account.copy(usageConsentGate = UsageConsentGateDto(state = USAGE_CONSENT_NOT_RECORDED, required = true))
            )
        )
        // A REFUSAL DOES NOT BLOCK. If it did, withdrawing would cost the person access on their
        // very next sign-in, the withdrawal would be theatre, and the turnstile at the door would
        // stop being defensible. The server reports `required = false` for exactly this reason.
        assertFalse(
            usageConsentBlocks(
                account.copy(usageConsentGate = UsageConsentGateDto(state = USAGE_CONSENT_REFUSED, required = false))
            )
        )
        // A GRANT AGAINST A STALE NOTICE DOES block, and the client must not second-guess it: the
        // version comparison lives on the server so the two clients cannot disagree about it.
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

    // -----------------------------------------------------------------------------------------
    // The answer, in words
    // -----------------------------------------------------------------------------------------

    @Test
    fun `never-asked is worded as neither a grant nor a refusal`() {
        val unasked = usageConsentHeading(USAGE_CONSENT_NOT_RECORDED)
        val refused = usageConsentHeading(USAGE_CONSENT_REFUSED)
        val granted = usageConsentHeading(USAGE_CONSENT_GRANTED)
        assertNotEquals(unasked, refused)
        assertNotEquals(unasked, granted)
        // The whole reason the vocabulary is three states and not a boolean: telling somebody their
        // answer was "no" when nobody put the question is the failure the enum exists to prevent.
        assertTrue(unasked.contains("Nobody has asked"))
        assertFalse(unasked.contains("declined"))
        assertFalse(unasked.contains("agreed"))
    }

    @Test
    fun `an answer this build cannot read falls to never-asked and never to granted`() {
        // Fail-closed in the same direction `resolve_consent` does on the server. Guessing GRANTED
        // would tell somebody they had agreed to something on the strength of a token this build did
        // not recognise.
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
        // "You agreed" over a turnstile misleads by omission. The basis column exists so the record
        // can say it was a condition of access, and a client that stored it and did not show it
        // would be keeping an honest record and publishing a misleading one.
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

        // No date, no sentence — rather than a sentence with a raw ISO string or an empty slot in it.
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
        // A fortnight apart, which on this fleet is ordinary. A log that showed one of the two would
        // date a signature to the day it was filed.
        assertTrue(offline.contains("this server heard it on"))

        val live = usageDecisionLine(
            UsageConsentDecisionDto(
                decision = USAGE_CONSENT_REFUSED,
                basis = USAGE_BASIS_OFFERED_IN_SETTINGS,
                recordedAt = null,
                createdAt = "2026-08-24T11:30:00+00:00",
            )
        )
        // `recordedAt` is null by design when the answer was given straight against the server, and
        // a copy of `createdAt` in its place would read as "a device reported this" — which is false.
        assertFalse(live.contains("this server heard it on"))
        assertTrue(live.contains("Declined"))
        assertTrue(live.contains("in settings"))
    }

    @Test
    fun `the withdrawal copy promises no loss of access and no rewriting of the log`() {
        // Both halves are load-bearing. The first is what makes a withdrawal a real choice rather
        // than a button nobody dares press; the second stops somebody reading their own decision log
        // afterwards and concluding the withdrawal did not work.
        assertTrue(USAGE_WITHDRAW_CONFIRM.contains("does not sign you out"))
        assertTrue(USAGE_WITHDRAW_CONFIRM.contains("stays in your own log"))
    }

    @Test
    fun `an empty decision log is explained rather than left blank`() {
        assertTrue(USAGE_NO_DECISIONS_LINE.contains("have not been asked"))
    }

    // -----------------------------------------------------------------------------------------
    // The read states
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a failed read never renders as an answer of none`() {
        val empty = "Nothing was recorded about you in this window."
        val offline = usageReadNotice(UsageReadState.Failed, "requests", online = false, emptyLine = empty)
        val failed = usageReadNotice(UsageReadState.Failed, "requests", online = true, emptyLine = empty)
        val loading = usageReadNotice(UsageReadState.Loading, "requests", online = true, emptyLine = empty)

        // Three different sentences for three different next moves — the whole shape `WorkshopOptions`
        // fixed for pickers, applied to a read-only screen. A single `emptyList()` for all three is
        // what lets a failed fetch on a phone with no signal render as a confident claim that the
        // platform holds no record of somebody.
        assertNotEquals(offline, failed)
        assertNotEquals(offline, loading)
        assertNotEquals(failed, loading)
        assertNotEquals(empty, offline)
        assertNotEquals(empty, failed)

        // The clause that does the work, kept from `offlineListLine`.
        assertTrue(offline.orEmpty().contains("not a claim that there are none"))
        // The loading line is the shared one, verbatim.
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

    // -----------------------------------------------------------------------------------------
    // The withheld figure
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a withheld metric is a dash and never a zero`() {
        // `null` reaches 0 through `?: 0` and through every arithmetic helper anybody would add, and
        // a zero drawn where the server refused to answer is this client publishing a figure the
        // server explicitly would not state — about a screen too few accounts used to report safely.
        assertEquals("—", usageMetricText(null))
        assertEquals("—", usageDurationText(null))
        assertEquals("0", usageMetricText(0))
        assertNotEquals(usageMetricText(0), usageMetricText(null))

        val withheld = UsageRouteRow(routeTemplate = "/x", withheld = true, withheldBecause = "too few")
        val quiet = UsageRouteRow(routeTemplate = "/y", withheld = false, requests = 0, ok = 0)
        assertTrue(usageWithheld(withheld))
        // A ROUTE WITH NO TRAFFIC IS NOT A WITHHELD ROUTE. Both look empty; only one of them is the
        // server declining to answer, and conflating them turns a refusal into a quiet fortnight.
        assertFalse(usageWithheld(quiet))
    }

    @Test
    fun `counts are grouped the Indian way`() {
        // The web calls `toLocaleString("en-IN")` at the same call sites, and every reader of this
        // screen is reading Indian figures. A default-locale `%,d` would group them in thousands and
        // the two clients would print the same number differently.
        assertEquals("123", usageCount(123))
        assertEquals("1,234", usageCount(1234))
        assertEquals("12,345", usageCount(12345))
        assertEquals("1,23,456", usageCount(123456))
        assertEquals("12,34,567", usageCount(1234567))
        assertEquals("0", usageCount(0))
        assertEquals("-1,234", usageCount(-1234))
    }
}
