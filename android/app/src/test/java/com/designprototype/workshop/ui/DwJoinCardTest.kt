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

class DwJoinCardTest {

    private companion object {
        const val WORKSHOP_ID = "cmsik2jg8000eh8xc1lcy661a"
        const val SECRET = "9TQ4V0KZ7BXMHR3NDPJ2WY"

        const val CARD = "DPW2:J:CMSIK2JG8000EH8XC1LCY661A.9TQ4V0KZ7BXMHR3NDPJ2WY:7AWF"

        const val TAG = "DPW1:G:CMSIK2JG8000EH8XC1LCY661A:0PK3"

        const val NOW_MS = 1_787_529_600_000L
    }

    @Test
    fun `a genuine join card is read as a card to redeem and never as a newer format`() {
        val scan = readWorkshopScan(CARD)
        assertTrue("a v2 join card must dispatch to the join path", scan is DwWorkshopScan.JoinCard)
        val card = (scan as DwWorkshopScan.JoinCard).card
        assertEquals(WORKSHOP_ID, card.workshopId)
        assertEquals("the canonical card is what gets posted", CARD, card.code)
    }

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
        assertFalse("a refusal must never carry the secret", refused.message.contains(SECRET))
    }

    @Test
    fun `a join card is sixty characters and survives the printed form`() {
        assertEquals(60, CARD.length)
        val printed = formatWorkshopCodeForPrint(CARD)
        assertTrue("the printed form is grouped", printed.contains(" "))
        val decoded = decodeWorkshopJoinCard(printed)
        assertTrue(decoded is DwJoinCardDecode.Ok)
        assertEquals(CARD, (decoded as DwJoinCardDecode.Ok).code)
    }

    @Test
    fun `a card read off a card under a tin roof still reads as the same card`() {
        val typed = "dpw02:j:cmsik2jg8000eh8xc1lcy661a.9TQ4VOKZ7BXMHR3NDPJ2WY:7awf"
        val decoded = decodeWorkshopJoinCard(typed)
        assertTrue("O in the secret is a misread zero", decoded is DwJoinCardDecode.Ok)
        assertEquals(CARD, (decoded as DwJoinCardDecode.Ok).code)
    }

    @Test
    fun `a card one character out is refused rather than sent to the server`() {
        val wrong = CARD.replaceFirst("9TQ4", "9TQ5")
        val decoded = decodeWorkshopJoinCard(wrong)
        assertEquals(
            DwJoinCardRefusal.CHECK_FAILED,
            (decoded as DwJoinCardDecode.Refused).reason,
        )
    }

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
        assertEquals(
            DwJoinCardRefusal.RECORD_TAG,
            (decodeWorkshopJoinCard(TAG) as DwJoinCardDecode.Refused).reason,
        )
        assertEquals(
            DwJoinCardRefusal.MALFORMED,
            (decodeWorkshopJoinCard("DPW2:J:CMSIK2JG8000EH8XC1LCY661A:7AWF") as DwJoinCardDecode.Refused).reason,
        )
        assertEquals(
            DwJoinCardRefusal.MALFORMED,
            (decodeWorkshopJoinCard("DPW2:J:CMSIK2JG8000EH8XC1LCY661A.9TQ4V0KZ7BXMHR3NDPJ2W:7AWF")
                as DwJoinCardDecode.Refused).reason,
        )
        assertEquals(
            DwJoinCardRefusal.DEVICE_LOCAL,
            (decodeWorkshopJoinCard("DPW2:J:DWLOCAL-3F2504E04F8911D39A0C0305.9TQ4V0KZ7BXMHR3NDPJ2WY:XXXX")
                as DwJoinCardDecode.Refused).reason,
        )
    }

    @Test
    fun `the dispatch predicate reads the letter alone`() {
        assertTrue(looksLikeJoinCard(CARD))
        assertTrue("a damaged join card still claims to be one", looksLikeJoinCard("DPW2:J:x.y:ZZZZ"))
        assertFalse(looksLikeJoinCard(TAG))
        assertFalse(looksLikeJoinCard("not a code at all"))
        assertFalse(looksLikeJoinCard(null))
    }

    @Test
    fun `a record tag is still read as a record`() {
        val scan = readWorkshopScan(TAG)
        assertTrue(scan is DwWorkshopScan.RecordCode)
        val record = scan as DwWorkshopScan.RecordCode
        assertEquals(DwWorkshopRecordType.DESIGN_WORKSHOP, record.ref.recordType)
        assertEquals(WORKSHOP_ID, record.ref.id)
    }

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
        assertFalse(lower.contains("not a password"))
        assertFalse(lower.contains("asks an administrator"))
    }

    @Test
    fun `only a full grant and an existing membership count as being on the workshop`() {
        assertTrue(dwJoinCardOutcomeIsMembership("FULL"))
        assertTrue(dwJoinCardOutcomeIsMembership("ALREADY_A_MEMBER"))
        assertFalse("a foothold is not membership", dwJoinCardOutcomeIsMembership("PROVISIONAL"))
        assertFalse("an unknown answer is not membership", dwJoinCardOutcomeIsMembership("SOMETHING_NEW"))
        assertFalse(dwJoinCardOutcomeIsMembership(""))
    }

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

    @Test
    fun `across a reboot the sync reading is omitted rather than made up`() {
        val body = dwJoinCardEvidence(queued(), elapsedNowMs = 60_000L, clockJumped = false, sameBoot = false)

        assertEquals("the scan's own reading is still evidence", 12L, body.scannedAtElapsedSec)
        assertNull("nothing may be subtracted across two boots", body.syncedAtElapsedSec)
    }

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

    @Test
    fun `moving the date keeps the boot id and raises the flag`() {
        val before = DwBootMark("boot-one", lastElapsedMs = 900_000L, bootWallClockMs = 1_700_000_000_000L)
        val (after, jumped) = DwBootMark.advance(
            before,
            elapsedNowMs = 950_000L,
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
