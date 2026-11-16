package com.designprototype.workshop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncRetryTest {
    @Test
    fun a_take_queued_while_a_pass_runs_gets_a_pass_of_its_own_even_offline() {
        assertTrue(backgroundSyncShouldRetry(signedIn = true, waitingAfterPass = 0, online = true, askedDuringPass = true))
        assertTrue(backgroundSyncShouldRetry(signedIn = true, waitingAfterPass = 0, online = false, askedDuringPass = true))
        assertFalse(backgroundSyncShouldRetry(signedIn = false, waitingAfterPass = 2, online = true, askedDuringPass = true))
        assertTrue(backgroundSyncShouldRetry(signedIn = true, waitingAfterPass = 1, online = true, askedDuringPass = false))
        assertFalse(backgroundSyncShouldRetry(signedIn = true, waitingAfterPass = 1, online = false, askedDuringPass = false))
    }
}
