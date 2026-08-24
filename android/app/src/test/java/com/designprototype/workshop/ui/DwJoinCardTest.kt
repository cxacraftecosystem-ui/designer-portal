package com.designprototype.workshop.ui

import com.designprototype.workshop.data.DwDecodeRefusal
import com.designprototype.workshop.data.DwDecodeResult
import com.designprototype.workshop.data.DwJoinCardDecode
import com.designprototype.workshop.data.DwJoinCardRefusal
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.data.DwWorkshopScan
import com.designprototype.workshop.data.decodeWorkshopCode
import com.designprototype.workshop.data.decodeWorkshopJoinCard
import com.designprototype.workshop.data.designWorkshopJoinCardPurposeMessage
import com.designprototype.workshop.data.formatWorkshopCodeForPrint
import com.designprototype.workshop.data.looksLikeJoinCard
import com.designprototype.workshop.data.readWorkshopScan
import com.designprototype.workshop.ui.designworkshop.dwJoinCardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE JOIN-CARD CLIENT'S OWN DECISIONS, EACH AS AN ASSERTION. No network, no disk, no clock.
 *
 * ── THE FAILURE THIS FILE EXISTS TO KEEP CLOSED ───────────────────────────────────────────────
 *
 * The server minted `DPW2:J:…` cards while `SUPPORTED_VERSIONS` on the handset was `setOf(1)`, so a
 * genuine card was answered "This card was printed by a newer version of the app (code format 2) than
 * the one on this device. **Update the app to read it**" — and there was no newer app. Somebody in a
 * courtyard holding a valid credential was sent to look for a build that did not exist.
 *
 * So the first two tests below are not about parsing. They are about which SENTENCE a v2 join card
 * produces, on both of the doors that can meet one.
 *
 * ── AND ONE REFERENCE VECTOR THAT IS NOT SELF-CONSISTENT ──────────────────────────────────────
 *
 * [CARD] was computed with an INDEPENDENT implementation of the FNV-1a check (in Python, from the
 * algorithm the backend module states) rather than by calling `workshopCodeCheck` here. That matters:
 * a test that builds its fixture with the same function it is testing agrees with itself no matter
 * what the function does, which is the exact way `DwWorkshopCodesTest` says its hand-pinned letters
 * "caught nothing when the browser added G". The same implementation reproduces this repository's
 * existing `DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD` fixture, which is what makes it trustworthy.
 */
class DwJoinCardTest {

    private companion object {
        const val WORKSHOP_ID = "cmsik2jg8000eh8xc1lcy661a"
        const val SECRET = "9TQ4V0KZ7BXMHR3NDPJ2WY"

        /** A genuine join card. Sixty characters, which is the QR budget the grammar was chosen for. */
        const val CARD = "DPW2:J:CMSIK2JG8000EH8XC1LCY661A.9TQ4V0KZ7BXMHR3NDPJ2WY:7AWF"

        /** The workshop's own TAG for the same workshop — a locator, and a different act entirely. */
        const val TAG = "DPW1:G:CMSIK2JG8000EH8XC1LCY661A:0PK3"

        /**
         * A FIXED "now" (2026-08-24T00:00:00Z), passed in rather than read from the clock.
         *
         * `dwJoinCardState` takes the instant as an argument for exactly this reason: a function that
         * read `System.currentTimeMillis()` itself could only be tested by choosing dates relative to
         * whenever the suite happens to run, and "expired" is a comparison whose two sides then both
         * move. The fixtures below sit deliberately on either side of this value.
         */
        const val NOW_MS = 1_787_529_600_000L
    }

    // ── The dead end, on both doors ─────────────────────────────────────────────────────────────

    /**
     * **A GENUINE JOIN CARD IS NEVER ANSWERED "UPDATE THE APP".**
     *
     * The single most important assertion in this file. `readWorkshopScan` dispatches on the LETTER
     * before any version gate, so a v2 card reaches the join parser and comes back as a card to
     * redeem — not as a version this build cannot read.
     */
    @Test
    fun `a genuine join card is read as a card to redeem and never as a newer format`() {
        val scan = readWorkshopScan(CARD)
        assertTrue("a v2 join card must dispatch to the join path", scan is DwWorkshopScan.JoinCard)
        val card = (scan as DwWorkshopScan.JoinCard).card
        assertEquals(WORKSHOP_ID, card.workshopId)
        assertEquals("the canonical card is what gets posted", CARD, card.code)
    }

    /**
     * AND THE RECORD PARSER, WHICH SOME CALLER WILL ALWAYS REACH DIRECTLY, SAYS THE RIGHT THING TOO.
     *
     * `decodeWorkshopCode` cannot resolve a join card — `J` is deliberately absent from
     * [DwWorkshopRecordType] so that no record-lookup path has an entry to find — but its refusal must
     * send somebody to the join screen rather than to an app store. The branch is answered BEFORE the
     * version check for exactly that reason.
     */
    @Test
    fun `the record parser refuses a join card by naming the join path, not a newer version`() {
        val decoded = decodeWorkshopCode(CARD)
        assertTrue(decoded is DwDecodeResult.Refused)
        val refused = decoded as DwDecodeResult.Refused
        assertEquals(DwDecodeRefusal.JOIN_CARD, refused.reason)

        val lower = refused.message.lowercase()
        assertTrue("it must say what the card is", lower.contains("join card"))
        assertFalse("there is no newer app to update to", lower.contains("update the app"))
        assertFalse("nor a newer format to blame", lower.contains("newer version"))
        // ⚠ AND IT MUST NOT ECHO THE CARD. Every other refusal in that file may quote what was read;
        // this one carries a live 110-bit secret, and a message is the easiest place for one to end up
        // in a log.
        assertFalse("a refusal must never carry the secret", refused.message.contains(SECRET))
    }

    // ── The grammar ─────────────────────────────────────────────────────────────────────────────

    /** Sixty characters, and it survives being printed in groups of four and typed straight back. */
    @Test
    fun `a join card is sixty characters and survives the printed form`() {
        assertEquals(60, CARD.length)
        val printed = formatWorkshopCodeForPrint(CARD)
        assertTrue("the printed form is grouped", printed.contains(" "))
        val decoded = decodeWorkshopJoinCard(printed)
        assertTrue(decoded is DwJoinCardDecode.Ok)
        assertEquals(CARD, (decoded as DwJoinCardDecode.Ok).code)
    }

    /**
     * TOLERANT OF WHAT A HUMAN DOES TO IT, STRICT ABOUT WHAT IT MEANS.
     *
     * Lower case, a leading version zero, and Crockford's confusables in the SECRET all read back to
     * the same card. The confusable fold is applied to the secret and NOT to the id, which is the
     * asymmetry the server states: the secret's alphabet has no `O`, so an `O` in it can only be a
     * misread `0`, while a cuid legitimately contains both and "correcting" one would corrupt an id
     * that was typed correctly.
     */
    @Test
    fun `a card read off a card under a tin roof still reads as the same card`() {
        val typed = "dpw02:j:cmsik2jg8000eh8xc1lcy661a.9TQ4VOKZ7BXMHR3NDPJ2WY:7awf"
        val decoded = decodeWorkshopJoinCard(typed)
        assertTrue("O in the secret is a misread zero", decoded is DwJoinCardDecode.Ok)
        assertEquals(CARD, (decoded as DwJoinCardDecode.Ok).code)
    }

    /** A card one character out is a different card or no card, and the check is what notices. */
    @Test
    fun `a card one character out is refused rather than sent to the server`() {
        val wrong = CARD.replaceFirst("9TQ4", "9TQ5")
        val decoded = decodeWorkshopJoinCard(wrong)
        assertEquals(
            DwJoinCardRefusal.CHECK_FAILED,
            (decoded as DwJoinCardDecode.Refused).reason,
        )
    }

    /** The refusals that are about the SHAPE, each with its own sentence and its own next action. */
    @Test
    fun `each way a card can be wrong has its own answer`() {
        assertEquals(
            DwJoinCardRefusal.EMPTY,
            (decodeWorkshopJoinCard("   ") as DwJoinCardDecode.Refused).reason,
        )
        assertEquals(
            DwJoinCardRefusal.NOT_A_JOIN_CARD,
            (decodeWorkshopJoinCard("https://example.test/pay") as DwJoinCardDecode.Refused).reason,
        )
        // OURS, AND IT NAMES A RECORD. Somebody scanning the workshop's tag when they were handed a
        // join card needs to be told which card to look for, not that their scanner is broken.
        assertEquals(
            DwJoinCardRefusal.RECORD_TAG,
            (decodeWorkshopJoinCard(TAG) as DwJoinCardDecode.Refused).reason,
        )
        // NO FULL STOP: the id and the secret are one part joined by one `.`, and a card missing it is
        // damaged rather than a card for something else.
        assertEquals(
            DwJoinCardRefusal.MALFORMED,
            (decodeWorkshopJoinCard("DPW2:J:CMSIK2JG8000EH8XC1LCY661A:7AWF") as DwJoinCardDecode.Refused).reason,
        )
        // A SECRET ONE CHARACTER SHORT IS NOT A NEAR MISS.
        assertEquals(
            DwJoinCardRefusal.MALFORMED,
            (decodeWorkshopJoinCard("DPW2:J:CMSIK2JG8000EH8XC1LCY661A.9TQ4V0KZ7BXMHR3NDPJ2W:7AWF")
                as DwJoinCardDecode.Refused).reason,
        )
        // A WORKSHOP THAT ONLY EXISTS ON ONE HANDSET. Answered before the id pattern, which a
        // `dwlocal-` id passes perfectly well.
        assertEquals(
            DwJoinCardRefusal.DEVICE_LOCAL,
            (decodeWorkshopJoinCard("DPW2:J:DWLOCAL-3F2504E04F8911D39A0C0305.9TQ4V0KZ7BXMHR3NDPJ2WY:XXXX")
                as DwJoinCardDecode.Refused).reason,
        )
    }

    /** The dispatch predicate reads the letter and nothing else — no secret, no lookup, no version. */
    @Test
    fun `the dispatch predicate reads the letter alone`() {
        assertTrue(looksLikeJoinCard(CARD))
        assertTrue("a damaged join card still claims to be one", looksLikeJoinCard("DPW2:J:x.y:ZZZZ"))
        assertFalse(looksLikeJoinCard(TAG))
        assertFalse(looksLikeJoinCard("not a code at all"))
        assertFalse(looksLikeJoinCard(null))
    }

    /** A record tag still goes down the record path, with the record parser's own answer. */
    @Test
    fun `a record tag is still read as a record`() {
        val scan = readWorkshopScan(TAG)
        assertTrue(scan is DwWorkshopScan.RecordCode)
        val record = scan as DwWorkshopScan.RecordCode
        assertEquals(DwWorkshopRecordType.DESIGN_WORKSHOP, record.ref.recordType)
        assertEquals(WORKSHOP_ID, record.ref.id)
    }

    // ── What the printed card says ──────────────────────────────────────────────────────────────

    /**
     * **THE JOIN CARD'S SENTENCE IS THE OPPOSITE OF THE TAG'S, AND BOTH ARE TRUE.**
     *
     * `designWorkshopCardPurposeMessage` says the tag "does not let them in by itself" and "is not a
     * password" — correct, because a v1 record code is a locator whose check digit anybody can compute.
     * A join card is a 110-bit credential that admits somebody with no administrator in the loop, and
     * describing it in the tag's words would be describing a real key as harmless. That is the mistake
     * this test exists to prevent, in the direction that actually costs somebody something: a printed
     * line people are told is safe is how a credential ends up photographed into a group chat.
     */
    @Test
    fun `the join card says it admits, is a key, and runs out`() {
        val message = designWorkshopJoinCardPurposeMessage()
        val lower = message.lowercase()

        assertTrue("it must say it admits somebody", lower.contains("lets one person onto this workshop"))
        assertTrue("it must say no admin is involved", lower.contains("no administrator"))
        assertTrue("it must be described as a key", lower.contains("like a key"))
        assertTrue("it must warn against copying it", lower.contains("do not photograph it"))
        assertTrue("it must say the card is spent", lower.contains("will not let anybody else in"))
        assertTrue("it must say it runs out", lower.contains("stops working after"))
        // AND IT MUST NOT BORROW THE TAG'S DENIALS, which are true of the tag and false of this.
        assertFalse(lower.contains("not a password"))
        assertFalse(lower.contains("asks an administrator"))
    }

    // ── The outcome the server reports ──────────────────────────────────────────────────────────

    /**
     * **A PROVISIONAL FOOTHOLD IS NOT MEMBERSHIP, AND AN UNKNOWN WORD IS NOT EITHER.**
     *
     * The server insists that any UI over a provisional outcome "must keep the state visibly and
     * persistently provisional … and must never dress it as membership", because somebody can work for
     * days into a workspace that turns out to be nothing. And a build one release behind a server that
     * grew a fourth outcome must fail towards "you are not in": the cost of the two errors is not
     * symmetric.
     */
    @Test
    fun `only a full grant and an existing membership count as being on the workshop`() {
        assertTrue(dwJoinCardOutcomeIsMembership("FULL"))
        assertTrue(dwJoinCardOutcomeIsMembership("ALREADY_A_MEMBER"))
        assertFalse("a foothold is not membership", dwJoinCardOutcomeIsMembership("PROVISIONAL"))
        assertFalse("an unknown answer is not membership", dwJoinCardOutcomeIsMembership("SOMETHING_NEW"))
        assertFalse(dwJoinCardOutcomeIsMembership(""))
    }

    // ── The evidence, which is what makes the five columns worth having ─────────────────────────

    private fun queued(
        code: String = CARD,
        deviceUtc: String = "2026-08-20T06:30:00Z",
        elapsed: Long = 12_000L,
        boot: Long = 1_700_000_000_000L,
        bootId: String = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
    ) = DwPendingInduction(
        workshopId = WORKSHOP_ID,
        code = code,
        scannedAtDeviceUtc = deviceUtc,
        scannedAtElapsedMs = elapsed,
        bootWallClockMs = boot,
        kind = DW_INDUCTION_JOIN,
        bootId = bootId,
    )

    /**
     * THE STRUCTURED FORM OF THE CLOCK EVIDENCE, WHICH USED TO TRAVEL AS ENGLISH PROSE.
     *
     * The migration added `scannedAtElapsedSec`, `syncedAtElapsedSec`, `bootId` and
     * `clockJumpObserved`, and the only client path posted to a route that accepted none of them — so
     * the evidence went into a free-text `note` where no screen can sort or compare it. This is the
     * sortable form.
     *
     * SECONDS, NOT MILLISECONDS, because the column is an `Int` and a device up for 25 days would
     * overflow it in milliseconds. The precision is worthless anyway: the server derives "about how
     * long ago", and `dwSpanInWords` already rounds to hours and days for the human-readable half.
     */
    @Test
    fun `the evidence carries the monotonic pair in seconds and the boot it belongs to`() {
        val body = dwJoinCardEvidence(queued(), elapsedNowMs = 345_601_000L, clockJumped = false, sameBoot = true)

        assertEquals(CARD, body.code)
        assertEquals("2026-08-20T06:30:00Z", body.scannedAt)
        assertEquals(12L, body.scannedAtElapsedSec)
        assertEquals(345_601L, body.syncedAtElapsedSec)
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", body.bootId)
        assertFalse(body.clockJumpObserved)
    }

    /**
     * **ACROSS A REBOOT THE PAIR IS NOT A DURATION, SO ONLY HALF OF IT IS SENT.**
     *
     * `elapsedRealtime()` resets to zero on restart, so subtracting a reading from a previous boot
     * gives a number that is not an elapsed time at all — and the server would derive a scan instant
     * from it and show that to an admin as evidence. Absent evidence is honest; a nonsense subtraction
     * is not, which is the same reasoning that keeps every one of these fields nullable rather than
     * defaulting to zero.
     */
    @Test
    fun `across a reboot the sync reading is omitted rather than made up`() {
        val body = dwJoinCardEvidence(queued(), elapsedNowMs = 60_000L, clockJumped = false, sameBoot = false)

        assertEquals("the scan's own reading is still evidence", 12L, body.scannedAtElapsedSec)
        assertNull("nothing may be subtracted across two boots", body.syncedAtElapsedSec)
    }

    /** A row written by a build that recorded no scan time claims nothing rather than claiming zero. */
    @Test
    fun `an unknown device clock is sent as absent and not as a value`() {
        val body = dwJoinCardEvidence(
            queued(deviceUtc = "unknown"),
            elapsedNowMs = 20_000L,
            clockJumped = true,
            sameBoot = true,
        )
        assertNull(body.scannedAt)
        assertTrue("the flag is not an accusation, and it is still reported", body.clockJumpObserved)
    }

    // ── The boot mark, which is what makes the pair comparable at all ───────────────────────────

    /**
     * A REBOOT IS DETECTED BY THE MONOTONIC CLOCK GOING BACKWARDS, NOT BY THE WALL CLOCK.
     *
     * `elapsedRealtime()` only ever increases within a boot, so a reading below the last one this app
     * saw can only mean a restart — and that test is immune to the very thing the feature defends
     * against, somebody moving the date. Watching `currentTimeMillis() - elapsedRealtime()` instead
     * cannot tell a reboot from a clock change, and conflating those two is what `clockJumpObserved`
     * exists to keep apart.
     */
    @Test
    fun `a restart mints a new boot id and claims no clock jump`() {
        val before = DwBootMark("boot-one", lastElapsedMs = 900_000L, bootWallClockMs = 1_700_000_000_000L)
        val (after, jumped) = DwBootMark.advance(
            before,
            elapsedNowMs = 4_000L,
            wallNowMs = 1_700_000_004_000L,
            mint = { "boot-two" },
        )
        assertEquals("boot-two", after.bootId)
        assertEquals(4_000L, after.lastElapsedMs)
        assertFalse("a restart is not a clock change and must not be reported as one", jumped)
    }

    /** The first run this app ever has is a fresh boot and claims nothing about any clock. */
    @Test
    fun `a first run mints an id and claims nothing`() {
        val (mark, jumped) = DwBootMark.advance(
            null,
            elapsedNowMs = 5_000L,
            wallNowMs = 1_700_000_005_000L,
            mint = { "boot-one" },
        )
        assertEquals("boot-one", mark.bootId)
        assertFalse(jumped)
    }

    /**
     * A CLOCK CHANGE KEEPS THE BOOT ID AND RAISES THE FLAG. The elapsed measure is still good — that
     * is the whole point of using a clock nobody can set — so throwing it away would lose the only
     * device-reported time worth anything.
     */
    @Test
    fun `moving the date keeps the boot id and raises the flag`() {
        val before = DwBootMark("boot-one", lastElapsedMs = 900_000L, bootWallClockMs = 1_700_000_000_000L)
        val (after, jumped) = DwBootMark.advance(
            before,
            elapsedNowMs = 950_000L,
            // Two days back, on a phone whose uptime carried on regardless.
            wallNowMs = 1_700_000_000_000L + 950_000L - 172_800_000L,
            mint = { "boot-two" },
        )
        assertEquals("the boot has not changed", "boot-one", after.bootId)
        assertTrue(jumped)
        assertEquals(
            "the FIRST estimate of this boot is kept, so the flag stays true for the rest of it",
            1_700_000_000_000L,
            after.bootWallClockMs,
        )
    }

    /**
     * ORDINARY DRIFT IS NOT A CLOCK CHANGE. The two clocks are read a few instructions apart and NTP
     * nudges the wall clock, so a strict comparison would report a jump on every single scan and the
     * flag would stop meaning anything.
     */
    @Test
    fun `ordinary drift is not reported as a clock change`() {
        val before = DwBootMark("boot-one", lastElapsedMs = 900_000L, bootWallClockMs = 1_700_000_000_000L)
        val (_, jumped) = DwBootMark.advance(
            before,
            elapsedNowMs = 901_000L,
            wallNowMs = 1_700_000_000_000L + 901_000L + 300L,
            mint = { "boot-two" },
        )
        assertFalse(jumped)
    }

    /**
     * THE HIGHEST READING IS KEPT, NOT THE LATEST. Two threads reading `elapsedRealtime()` a few
     * milliseconds apart must not be able to walk the stored value backwards and fake a reboot on the
     * next scan.
     */
    @Test
    fun `a few milliseconds of apparent regression is a race and not a restart`() {
        val before = DwBootMark("boot-one", lastElapsedMs = 900_000L, bootWallClockMs = 1_700_000_000_000L)
        val (after, _) = DwBootMark.advance(
            before,
            elapsedNowMs = 899_998L,
            wallNowMs = 1_700_000_899_998L,
            mint = { "boot-two" },
        )
        assertEquals("boot-one", after.bootId)
        assertEquals(900_000L, after.lastElapsedMs)
    }

    // ── The queue holds two different acts ─────────────────────────────────────────────────────

    /**
     * **AN ASK AND A JOIN CARD FOR ONE WORKSHOP ARE TWO ROWS, NOT ONE.**
     *
     * They go to two different endpoints and mean two different things. Keying the queue on the
     * workshop id alone would silently throw one away — in practice the CARD, because a card is
     * scanned after an ask has already failed to produce anything, which is the one of the two that
     * would actually have worked.
     */
    @Test
    fun `an ask and a join card for the same workshop do not merge`() {
        val ask = DwPendingInduction(
            workshopId = WORKSHOP_ID,
            code = TAG,
            scannedAtDeviceUtc = "2026-08-20T06:00:00Z",
            scannedAtElapsedMs = 1_000L,
            bootWallClockMs = 1_700_000_000_000L,
            kind = DW_INDUCTION_ASK,
        )
        val merged = dwMergeInduction(listOf(ask), queued())

        assertEquals(2, merged.size)
        assertEquals(setOf(DW_INDUCTION_ASK, DW_INDUCTION_JOIN), merged.map { it.kind }.toSet())
    }

    /** A second card for the same workshop replaces the first, and keeps the first scan's evidence. */
    @Test
    fun `a reprinted card replaces the one waiting and keeps the first scan time`() {
        val first = queued(elapsed = 1_000L, bootId = "boot-one")
        val reprint = queued(
            code = "DPW2:J:CMSIK2JG8000EH8XC1LCY661A.KKKKKKKKKKKKKKKKKKKKKK:XXXX",
            elapsed = 601_000L,
            bootId = "boot-one",
        )
        val merged = dwMergeInduction(listOf(first), reprint)

        assertEquals(1, merged.size)
        assertEquals("the card actually presented", reprint.code, merged.single().code)
        assertEquals("the FIRST scan's time survives", 1_000L, merged.single().scannedAtElapsedMs)
        assertEquals(
            "and the boot that reading belongs to survives with it",
            "boot-one",
            merged.single().bootId,
        )
    }

    // ── What an existing card's row says ───────────────────────────────────────────────────────

    /**
     * THE ORDER OF THE STATES IS THE MEANING, and cancelling is the one that must not overclaim.
     *
     * `revoke_grant` is explicit that revoking stops the card admitting anybody FURTHER and removes
     * nobody it has already let in — so an admin who read this row and believed otherwise would cancel
     * a misprinted batch and think they had removed the colleagues who used it.
     */
    @Test
    fun `a cancelled card says what cancelling does not do`() {
        val row = DwJoinCardDto(
            id = "tok",
            secretLast4 = "7AWF",
            maxUses = 1,
            usesConsumed = 0,
            revokedAt = "2026-08-21T10:00:00Z",
            expiresAt = "2026-09-05T10:00:00Z",
        )
        val state = dwJoinCardState(row, nowMs = NOW_MS)
        assertTrue(state.contains("Cancelled"))
        assertTrue(state.lowercase().contains("still on the workshop"))
        assertTrue(state.lowercase().contains("viewers screen"))
    }

    /** Spent beats out-of-date: a used card is used whether or not its date has passed. */
    @Test
    fun `a spent card reads as used up even after its date`() {
        val row = DwJoinCardDto(
            id = "tok",
            secretLast4 = "7AWF",
            maxUses = 1,
            usesConsumed = 1,
            expiresAt = "2020-01-01T00:00:00Z",
        )
        assertTrue(dwJoinCardState(row, nowMs = NOW_MS).startsWith("Used up"))
    }

    /**
     * AN OUT-OF-DATE CARD IS NOT DESCRIBED AS DEAD, because it is not: the server keeps a 30-day sync
     * grace, and a genuine scan taken before the card lapsed still reaches an administrator. Saying
     * "it will not work" would be wrong in the direction that makes somebody throw a card away.
     */
    @Test
    fun `an out-of-date card is honest about the sync grace`() {
        val row = DwJoinCardDto(
            id = "tok",
            secretLast4 = "7AWF",
            maxUses = 1,
            usesConsumed = 0,
            expiresAt = "2026-01-01T00:00:00Z",
        )
        val state = dwJoinCardState(row, nowMs = NOW_MS)
        assertTrue(state.startsWith("Out of date"))
        assertTrue(state.lowercase().contains("administrator"))
    }

    /** An unreadable date is NOT treated as expired: the column is NOT NULL on the server. */
    @Test
    fun `a card whose date this build cannot read is not called out of date`() {
        val row = DwJoinCardDto(
            id = "tok",
            secretLast4 = "7AWF",
            maxUses = 1,
            usesConsumed = 0,
            expiresAt = "next Tuesday",
        )
        assertTrue(dwJoinCardState(row, nowMs = NOW_MS).startsWith("Still good"))
    }

    /** The issuer is named when the server sent one — requirement 4's trail, on a screen. */
    @Test
    fun `a good card names who printed it`() {
        val row = DwJoinCardDto(
            id = "tok",
            secretLast4 = "7AWF",
            maxUses = 1,
            usesConsumed = 0,
            expiresAt = "2027-01-01T00:00:00Z",
            issuedBy = DwJoinCardIssuer(id = "u1", name = "Rekha", email = "rekha@example.test"),
        )
        val state = dwJoinCardState(row, nowMs = NOW_MS)
        assertTrue(state.startsWith("Still good"))
        assertTrue(state.contains("Printed by Rekha."))
    }
}
