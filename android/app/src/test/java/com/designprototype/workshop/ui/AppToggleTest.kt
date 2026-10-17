package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind every Android setting switch ([AppToggle]): a tap flips the value only when the
 * row is enabled and not mid-change, and the words TalkBack hears say which state it is in. The web's
 * twin is `toggleKeys.ts`.
 */
class AppToggleTest {
    @Test
    fun interactiveOnlyWhenEnabledAndNotBusy() {
        assertTrue(AppToggleRules.interactive(enabled = true, busy = false))
        assertFalse(AppToggleRules.interactive(enabled = false, busy = false))
        assertFalse(AppToggleRules.interactive(enabled = true, busy = true))
        assertFalse(AppToggleRules.interactive(enabled = false, busy = true))
    }

    @Test
    fun aTapFlipsTheValue() {
        assertEquals(true, AppToggleRules.outcome(checked = false, enabled = true, busy = false))
        assertEquals(false, AppToggleRules.outcome(checked = true, enabled = true, busy = false))
    }

    @Test
    fun aDisabledRowRefusesTheTapInEitherState() {
        assertNull(AppToggleRules.outcome(checked = false, enabled = false, busy = false))
        assertNull(AppToggleRules.outcome(checked = true, enabled = false, busy = false))
    }

    @Test
    fun aBusyRowRefusesTheTapInEitherState() {
        assertNull(AppToggleRules.outcome(checked = false, enabled = true, busy = true))
        assertNull(AppToggleRules.outcome(checked = true, enabled = true, busy = true))
    }

    @Test
    fun stateWordsSayOnOffAndChanging() {
        assertEquals("On", AppToggleRules.stateWords(checked = true, busy = false))
        assertEquals("Off", AppToggleRules.stateWords(checked = false, busy = false))
        assertEquals("On, changing", AppToggleRules.stateWords(checked = true, busy = true))
        assertEquals("Off, changing", AppToggleRules.stateWords(checked = false, busy = true))
    }
}
