package com.designprototype.workshop.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DwCameraStepTest {

    @Test
    fun a_value_passes_through() = runBlocking {
        assertEquals("provider", dwCameraStep { "provider" })
    }

    @Test
    fun a_failure_is_no_camera() = runBlocking {
        assertNull(dwCameraStep<String> { throw IllegalStateException("camera service down") })
    }

    @Test
    fun a_cancelled_step_never_reports_a_refusal() = runBlocking {
        var refused = false
        val started = CompletableDeferred<Unit>()
        val bind = launch {
            val provider = dwCameraStep<String> {
                started.complete(Unit)
                awaitCancellation()
            }
            if (provider == null) refused = true
        }
        started.await()
        bind.cancel()
        bind.join()
        yield()
        assertFalse("a restarted bind must not close the scanner with 'camera in use'", refused)
    }
}
