package com.designprototype.workshop.ui

import com.designprototype.workshop.data.TwoFactorStatusDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-step verification switch tells the truth (owner's report, 2026-10-17: switched on in the
 * emulator, never scanned, came back to "on, use a recovery code or the authenticator code"). It reads
 * On only when the server says `enabled`; setup reads "Setting up…"; an unfinished setup reads Off.
 * The web's `e2e/two-step-switch-unit.spec.ts` is the same table. Also [AppToggleRules].
 */
class TwoStepSwitchTest {
    private val off = TwoFactorStatusDto(enabled = false, pending = false)
    private val pending = TwoFactorStatusDto(enabled = false, pending = true)
    private val on = TwoFactorStatusDto(enabled = true, recoveryCodesRemaining = 8, enabledAt = "2026-10-17T09:30:00Z")

    @Test
    fun offIsOff() {
        assertEquals(TwoStepSwitch.View(false, "Off"), TwoStepSwitch.view(off, TwoStepSwitch.Phase.IDLE))
    }

    @Test
    fun settingUpIsNotOn() {
        val view = TwoStepSwitch.view(off, TwoStepSwitch.Phase.SETUP)
        assertFalse(view.checked)
        assertTrue(view.statusLine.startsWith("Setting up…"))
    }

    @Test
    fun anUnfinishedSetupReadsOffNeverOn() {
        val view = TwoStepSwitch.view(pending, TwoStepSwitch.Phase.IDLE)
        assertFalse(view.checked)
        assertTrue(view.statusLine.startsWith("Off · You started setting this up and did not finish"))
    }

    @Test
    fun onOnlyWhenTheServerSaysSoInPlainWords() {
        val view = TwoStepSwitch.view(on, TwoStepSwitch.Phase.IDLE)
        assertTrue(view.checked)
        assertTrue(view.statusLine, view.statusLine.matches(Regex("On since \\d{1,2} Oct 2026 · 8 recovery codes left")))
    }

    @Test
    fun turningOffStaysOnUntilACodeIsAccepted() {
        assertEquals(TwoStepSwitch.View(true, "Enter a code to turn it off."), TwoStepSwitch.view(on, TwoStepSwitch.Phase.DISABLE))
    }

    @Test
    fun countsAndDatesReadAsWords() {
        assertEquals("No recovery codes left", TwoStepSwitch.recoveryCodesLeft(0))
        assertEquals("1 recovery code left", TwoStepSwitch.recoveryCodesLeft(1))
        assertEquals("10 recovery codes left", TwoStepSwitch.recoveryCodesLeft(10))
        assertNull(TwoStepSwitch.onSince(null))
        assertNull(TwoStepSwitch.onSince("not a date"))
    }

    @Test
    fun backgroundingForTheAuthenticatorDoesNotDiscardButLeavingDoes() {
        assertTrue(TwoStepSwitch.leavingDiscards(TwoStepSwitch.Leave.CANCEL))
        assertTrue(TwoStepSwitch.leavingDiscards(TwoStepSwitch.Leave.SWITCH_OFF))
        assertTrue(TwoStepSwitch.leavingDiscards(TwoStepSwitch.Leave.SCREEN_CLOSED))
        // A short trip to the authenticator app keeps the key; a long absence drops it — the web's rule.
        assertFalse(TwoStepSwitch.leavingDiscards(TwoStepSwitch.Leave.BACKGROUNDED))
        assertFalse(TwoStepSwitch.leavingDiscards(TwoStepSwitch.Leave.BACKGROUNDED, 60_000L))
        assertFalse(TwoStepSwitch.leavingDiscards(TwoStepSwitch.Leave.BACKGROUNDED, TwoStepSwitch.AWAY_LIMIT_MILLIS))
        assertTrue(TwoStepSwitch.leavingDiscards(TwoStepSwitch.Leave.BACKGROUNDED, TwoStepSwitch.AWAY_LIMIT_MILLIS + 1))
        assertEquals(5L * 60L * 1000L, TwoStepSwitch.AWAY_LIMIT_MILLIS)
    }

    @Test
    fun aSetupTheServerExpiredWhileAwayIsOver() {
        assertTrue(TwoStepSwitch.setupExpired(TwoStepSwitch.Phase.SETUP, off))
        assertFalse(TwoStepSwitch.setupExpired(TwoStepSwitch.Phase.SETUP, pending))
        assertFalse(TwoStepSwitch.setupExpired(TwoStepSwitch.Phase.IDLE, off))
        assertFalse(TwoStepSwitch.setupExpired(TwoStepSwitch.Phase.SETUP, null))
    }

    @Test
    fun theStatusWireCarriesPendingAndAnOlderServerReadsAsNotPending() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString<TwoFactorStatusDto>("""{"enabled":false,"pending":true,"recovery_codes_remaining":0,"enabled_at":null}""").pending)
        assertFalse(json.decodeFromString<TwoFactorStatusDto>("""{"enabled":false,"recovery_codes_remaining":0,"enabled_at":null}""").pending)
    }

    @Test
    fun aToggleFlipsAndRefusesWhenDisabledOrBusy() {
        assertEquals(true, AppToggleRules.outcome(checked = false, enabled = true, busy = false))
        assertEquals(false, AppToggleRules.outcome(checked = true, enabled = true, busy = false))
        assertNull(AppToggleRules.outcome(checked = false, enabled = false, busy = false))
        assertNull(AppToggleRules.outcome(checked = true, enabled = true, busy = true))
        assertEquals("On", AppToggleRules.stateWords(checked = true, busy = false))
        assertEquals("Off, changing", AppToggleRules.stateWords(checked = false, busy = true))
    }
}
