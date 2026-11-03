package com.designprototype.workshop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DwStorageRefusalTriageTest {

    @Test
    fun aRefusalIsNotAnIOException() {
        assertFalse(
            "extending IOException would put this straight back into isTransient's network arm",
            IOException::class.java.isAssignableFrom(StorageRefusedError::class.java),
        )
    }

    @Test
    fun theStatusSurvivesAsANumber() {
        val error = StorageRefusedError(413, "Object storage upload failed: HTTP 413")
        assertTrue(error.status == 413)
        assertTrue(error.message?.contains("413") == true)
    }

    @Test
    fun aMomentaryAnswerEarnsAnotherPass() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            assertTrue("HTTP $status describes a moment, not the file", StorageRefusedError(status, "x").worthAnotherPass)
        }
    }

    @Test
    fun asettledRefusalDoesNotEarnAnother() {
        for (status in listOf(400, 401, 403, 404, 405, 411, 413, 415, 422)) {
            assertFalse("HTTP $status is about the request, not the moment", StorageRefusedError(status, "x").worthAnotherPass)
        }
    }

    @Test
    fun bothLegsSpeakTheSameDialect() {
        val whole = StorageRefusedError(413, "Object storage upload failed: HTTP 413")
        val part = StorageRefusedError(413, "Part upload failed: HTTP 413")
        assertTrue(whole.worthAnotherPass == part.worthAnotherPass)
        assertTrue(whole.status == part.status)
    }
}
