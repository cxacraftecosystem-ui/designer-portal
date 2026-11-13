package com.designprototype.workshop.data

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class DwSchemaSkewRetryTest {

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    private val extraForbidden =
        """{"detail":[{"type":"extra_forbidden","loc":["body","entries",0,"merge"],""" +
            """"msg":"Extra inputs are not permitted","input":true}]}"""

    private val fieldInvalid =
        """{"detail":[{"type":"string_too_long","loc":["body","title"],""" +
            """"msg":"String should have at most 200 characters"}]}"""

    private val previousRun = "run-that-recorded-the-refusal"

    @Test
    fun `an unknown key is a schema refusal, and the server's own sentence is still surfaced`() {
        val refusal = http(422, extraForbidden).apiRefusal("fallback")
        assertTrue("extra_forbidden means the two builds disagree, not that a person typed something wrong", refusal.schemaSkew)
        assertEquals("Merge: Extra inputs are not permitted.", refusal.message)
    }

    @Test
    fun `a field the validator rejected is NOT a schema refusal`() {
        val refusal = http(422, fieldInvalid).apiRefusal("fallback")
        assertFalse(refusal.schemaSkew)
        assertEquals("Title: String should have at most 200 characters.", refusal.message)
    }

    @Test
    fun `only a 422 qualifies`() {
        assertFalse(http(500, extraForbidden).apiRefusal("fallback").schemaSkew)
    }

    @Test
    fun `a refusal with nothing readable in it is never a schema refusal`() {
        assertFalse(http(422, "").apiRefusal("fallback").schemaSkew)
        assertFalse(http(422, "not json at all").apiRefusal("fallback").schemaSkew)
        assertFalse(http(422, """{"detail":"a plain string"}""").apiRefusal("fallback").schemaSkew)
        assertFalse(http(422, """{"nothing":1}""").apiRefusal("fallback").schemaSkew)
        assertFalse(RuntimeException("no connection").apiRefusal("fallback").schemaSkew)
    }

    @Test
    fun `the message half is unchanged, because every screen in the app already renders it`() {
        assertEquals("Merge: Extra inputs are not permitted.", http(422, extraForbidden).apiErrorMessage("fallback"))
        assertEquals("no such workshop", http(404, """{"detail":"no such workshop"}""").apiErrorMessage("x"))
        assertEquals(
            "Giriraj Prasad already holds that Aadhaar",
            http(409, """{"detail":{"message":"Giriraj Prasad already holds that Aadhaar"}}""").apiErrorMessage("x")
        )
        assertEquals("fallback", RuntimeException("").apiErrorMessage("fallback"))
    }

    @Test
    fun `both facts come out of ONE read of the error body`() {
        val error = http(422, extraForbidden)
        val once = error.apiRefusal("fallback")
        assertTrue(once.schemaSkew)
        assertEquals("Merge: Extra inputs are not permitted.", once.message)
        assertFalse(
            "a second read gets an empty buffer, which is exactly why there must only ever be one",
            error.apiRefusal("fallback").schemaSkew
        )
    }

    @Test
    fun `a refusal the designer can act on still binds, exactly as before`() {
        assertTrue("no skew recorded — only a person can clear it", blocksRetry(permanent = true, skewRun = null))
        assertTrue(blocksRetry(permanent = true, skewRun = ""))
    }

    @Test
    fun `a hold-up that is not a refusal never blocks`() {
        assertFalse(blocksRetry(permanent = false, skewRun = null))
        assertFalse(blocksRetry(permanent = false, skewRun = previousRun))
    }

    @Test
    fun `a schema refusal is not re-sent by the run that recorded it`() {
        assertTrue(blocksRetry(permanent = true, skewRun = APP_RUN))
    }

    @Test
    fun `and IS re-sent by the next one, which is the whole fix`() {
        assertFalse(blocksRetry(permanent = true, skewRun = previousRun))
    }

    @Test
    fun `the app run is one value for the life of the process`() {
        assertEquals(APP_RUN, APP_RUN)
        assertTrue(APP_RUN.isNotBlank())
        assertFalse(APP_RUN == previousRun)
    }

    @Test
    fun `a stage refused for a schema reason is retried after an update, and a rejected answer is not`() {
        val skew = http(422, extraForbidden).apiRefusal("the server refused this stage.")
        val rejected = http(422, fieldInvalid).apiRefusal("the server refused this stage.")

        val recordedForSkew = StageSyncRecord(
            failure = skew.message,
            permanent = true,
            skewRun = if (skew.schemaSkew) previousRun else null,
        )
        val recordedForRejection = StageSyncRecord(
            failure = rejected.message,
            permanent = true,
            skewRun = if (rejected.schemaSkew) previousRun else null,
        )

        assertFalse(
            "the next run tries the stage again, so a server that has been updated simply takes it",
            blocksRetry(recordedForSkew.permanent, recordedForSkew.skewRun)
        )
        assertTrue(
            "while an answer the validator rejected still waits for the person who typed it",
            blocksRetry(recordedForRejection.permanent, recordedForRejection.skewRun)
        )
        assertTrue(
            "and both are still SHOWN — a stage silently not syncing is worse than one that says so",
            recordedForSkew.failure != null && recordedForSkew.permanent
        )
    }

    @Test
    fun `a draft written before this field existed decodes as one only a person can clear`() {
        val fromDisk = StageSyncRecord(signature = "abc", failure = "the server refused this stage.", permanent = true)
        assertNull(fromDisk.skewRun)
        assertTrue(blocksRetry(fromDisk.permanent, fromDisk.skewRun))
    }
}
