package com.designprototype.workshop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesSyncTest {
    private val serverAt = "2026-10-16T08:00:00Z"
    private val serverMs = java.time.Instant.parse(serverAt).toEpochMilli()

    @Test
    fun nothing_queued_never_wins() {
        assertFalse(localPreferencesWin(null, serverAt))
        assertFalse(localPreferencesWin(null, null))
    }

    @Test
    fun a_later_change_on_this_phone_wins() {
        assertTrue(localPreferencesWin(serverMs + 60_000, serverAt))
    }

    @Test
    fun an_earlier_change_on_this_phone_loses_to_a_later_one_elsewhere() {
        assertFalse(localPreferencesWin(serverMs - 60_000, serverAt))
        assertFalse(localPreferencesWin(serverMs, serverAt))
    }

    @Test
    fun a_server_row_with_no_readable_time_loses_to_a_queued_change() {
        assertTrue(localPreferencesWin(serverMs, null))
        assertTrue(localPreferencesWin(serverMs, "not a date"))
    }
}
