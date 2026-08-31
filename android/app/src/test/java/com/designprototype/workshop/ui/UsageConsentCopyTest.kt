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
 *  * the door's one-line hint only says anything but "tick the box" when a fetch failed;
 *  * the "never re-ask a refusal" rule only fires for an account somebody withdrew on ANOTHER day;
 *  * the withheld branch only fires on a route fewer than five identified accounts used;
 *  * and the two-clock decision line only differs on a device that answered before it synced.
 *
 * A wrong answer in any of them is a lie about a consent, which is not a smaller kind of wrong.
 */
class UsageConsentCopyTest {

    // -----------------------------------------------------------------------------------------
    // The door, since 2026-08-30
    // -----------------------------------------------------------------------------------------
    //
    // WHAT THESE FOUR TESTS REPLACED, AND WHY THE REPLACEMENT IS NOT A WEAKENING.
    //
    // There used to be five tests here, over a `UsageDoorPolicy` with three arms and a
    // `usageSignInBlockedReason` with four. One of them was captioned "THE MOST IMPORTANT ASSERTION
    // IN THIS FILE": that a door which could not read the recording notice let the person through
    // rather than holding them at a permanently disabled button — a fleet-wide lockout out of one
    // bad deploy of `GET /usage/consent/notice`.
    //
    // That property is now structural rather than asserted, and it is stronger for it. The box no
    // longer agrees to the notice; it agrees to the TERMS, and `TERMS_CLAUSES` is nine constants
    // compiled into the binary. There is no fetch that can fail to produce the question, so there is
    // no state in which the door cannot be answered — which is why `UsageDoorState.mayProceed` is
    // now `agreed` and nothing else, exactly as `frontend/app/login/page.tsx` states it.
    //
    // What is left to pin is that the notice's ABSENCE changes nothing about the gate and is still
    // said out loud, so nobody re-derives the old escape hatch from a screen that looks stuck.

    @Test
    fun `the missing notice changes what is said and never what is required`() {
        // The same sentence with a notice and without one. THIS IS THE ASSERTION THE OLD POLICY
        // ENUM EXISTED FOR, inverted: a door that could not read the notice used to stop requiring
        // the tick, and now it requires exactly the same tick and says exactly the same thing,
        // because the terms it agrees to are in the binary either way.
        assertEquals(
            usageDoorHint(agreed = false, noticeReady = true, stillFetching = false),
            usageDoorHint(agreed = false, noticeReady = false, stillFetching = false)
        )

        // A disabled button announces "disabled" and nothing else, so this sentence IS the
        // accessibility affordance and must name the control that clears it. The web can say only
        // "Required to sign in." because its live region is bound to the checkbox by
        // `aria-describedby`; a Compose live region is a separate node with no such binding.
        val unticked = usageDoorHint(agreed = false, noticeReady = true, stillFetching = false)
        // Lower-cased on both sides: the sentence opens with the verb, so a case-sensitive test
        // here would pass only by accident of where the clause happens to sit.
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
        // Somebody let past the door must not conclude the question has been answered on their
        // behalf, or `UsageConsentGateScreen` a moment later reads as the app asking twice.
        assertEquals(USAGE_NOTICE_NOT_FILED_LINE, unfiled)
        assertTrue(USAGE_NOTICE_NOT_FILED_LINE.contains("not filed yet"))
        assertTrue(USAGE_NOTICE_NOT_FILED_LINE.contains("asked again after signing in"))
    }

    @Test
    fun `the first fetch says nothing at all while it is in flight`() {
        // It lasts a second or two on a fresh install, and a line that appears and vanishes on its
        // own is read as a fault. It blocks nothing either — there is nothing left to block on.
        assertNull(usageDoorHint(agreed = true, noticeReady = false, stillFetching = true))
        // Still the tick, though: "fetching" is not an excuse to skip the question.
        assertEquals(
            USAGE_TICK_TO_SIGN_IN,
            usageDoorHint(agreed = false, noticeReady = false, stillFetching = true)
        )
    }

    @Test
    fun `the label and the link read as one sentence, and the link is the tail of it`() {
        // The owner's instruction on 2026-08-30, word for word: "Keep an I agree to terms and
        // conditions with terms and conditions underlined." The two halves are separate constants
        // because they are separate controls — see `UsageAgreeRow` for why a link inside a
        // `toggleable` row would tick the box instead of opening the terms — so the one thing a
        // test can check is that they still compose back into the sentence that was asked for.
        assertEquals("I agree to the terms and conditions", "$USAGE_AGREE_LABEL $USAGE_TERMS_LINK")
        // Mid-sentence, so lower case and no full stop. `TERMS_TITLE` is the heading.
        assertEquals(USAGE_TERMS_LINK, USAGE_TERMS_LINK.lowercase())
        assertFalse(USAGE_AGREE_LABEL.endsWith("."))
        assertNotEquals(TERMS_TITLE, USAGE_TERMS_LINK)

        // The gate screen asks the same question and is not signing anybody in, so its one-word
        // difference is deliberate and must not be collapsed into the door's sentence.
        assertNotEquals(USAGE_TICK_TO_SIGN_IN, USAGE_TICK_TO_CONTINUE)
        assertTrue(USAGE_TICK_TO_CONTINUE.lowercase().contains("tick the box"))
    }

    // -----------------------------------------------------------------------------------------
    // The terms themselves
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the terms are nine numbered clauses and the served notice is the tenth`() {
        assertEquals(9, TERMS_CLAUSES.size)
        // Numbered in order with no gaps and no repeats: the number is drawn from the data, so a
        // clause inserted without renumbering would put two 4s on a legal agreement.
        assertEquals((1..9).toList(), TERMS_CLAUSES.map { it.number })
        // Derived, never a literal 10 — this is the same rule the guide page's step count is held
        // to on the web, for the same reason: a number written down twice is a number that drifts.
        assertEquals(10, TERMS_RECORDING_CLAUSE_NUMBER)

        TERMS_CLAUSES.forEach { clause ->
            assertTrue("clause ${clause.number} has no title", clause.title.isNotBlank())
            assertTrue("clause ${clause.number} has no body", clause.body.isNotBlank())
        }
    }

    @Test
    fun `the clauses are the web's, verbatim`() {
        // THE POINT OF THIS TEST IS THE COMMENT ON IT. `frontend/app/terms/page.tsx` carries the
        // identical nine, and the tick that accepts them is the same tick on both clients — so two
        // wordings would not be an inconsistency, they would be two different agreements, one of
        // which somebody accepted without ever seeing it. Nothing can diff two repositories from
        // here, so what is pinned is the load-bearing clause of each of the two that a well-meaning
        // editor is most likely to "improve": the one about regulated identity numbers, and the one
        // about work still sitting in an outbox.
        val identity = TERMS_CLAUSES.first { it.number == 4 }
        assertEquals("Identity numbers", identity.title)
        assertTrue(identity.body.contains("Aadhaar and Pehchan card numbers are stored masked"))
        assertTrue(identity.body.contains("never shown in lists, exports or reports"))

        val offline = TERMS_CLAUSES.first { it.number == 6 }
        assertEquals("Offline use", offline.title)
        assertTrue(offline.body.contains("do not uninstall the app or clear its data"))
        assertTrue(offline.body.contains("outbox"))

        // Clause 9 is the one that makes the version column mean something to a reader.
        val changes = TERMS_CLAUSES.first { it.number == 9 }
        assertTrue(changes.body.contains("The version you agreed to is recorded against your account"))
    }

    @Test
    fun `no clause writes a sentence the server owns`() {
        // The boundary `UsageCopy.kt`'s header draws, enforced where it is easiest to cross by
        // accident. What is collected, what is not, the retention answer and the withdrawal terms
        // arrive in `GET /usage/consent/notice` and are rendered by `UsageNoticeBody` as clause 10.
        // A clause here that paraphrased any of them would be the same decision described twice —
        // and on a consent screen that is not an inconsistency, it is two different consents.
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
        // Nine of the ten clauses need no network at all, which is the whole reason the terms are a
        // screen in this app rather than a link to the web: the handsets are used where there is no
        // signal, and a link to a page a phone cannot load is worse than no link. So the sentence
        // clause 10 falls back to must not claim the TERMS are unavailable while nine of them are on
        // the screen underneath it — a reader told that stops trusting either statement.
        assertFalse(TERMS_NOTICE_UNAVAILABLE_LINE.contains("terms could not"))
        assertTrue(TERMS_NOTICE_UNAVAILABLE_LINE.contains("Everything above still applies"))
        // And it names the other place the same text lives, because "later, in Settings" is the
        // honest next move for somebody with no signal and "try harder now" is not.
        assertTrue(TERMS_NOTICE_UNAVAILABLE_LINE.contains("Settings"))
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
