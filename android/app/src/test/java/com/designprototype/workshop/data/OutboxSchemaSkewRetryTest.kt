package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class OutboxSchemaSkewRetryTest {

    private val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    private val extraForbidden =
        """{"detail":[{"type":"extra_forbidden","loc":["body","merge"],""" +
            """"msg":"Extra inputs are not permitted","input":true}]}"""

    private val fieldInvalid =
        """{"detail":[{"type":"string_too_long","loc":["body","name"],""" +
            """"msg":"String should have at most 200 characters"}]}"""

    private val previousRun = "run-that-recorded-the-refusal"

    private fun entry(failure: String?, skewRun: String? = null) = PendingEntry(
        id = "entry-1",
        type = "artisan",
        payloadJson = """{"name":"Giriraj Prasad"}""",
        label = "Giriraj Prasad",
        createdAt = "2026-08-08T10:00:00Z",
        failure = failure,
        skewRun = skewRun,
    )

    @Test
    fun `a queued record refused for a key the API did not know is re-attempted by the next app run`() {
        val refusal = http(422, extraForbidden).apiRefusal("The server rejected this record.")
        val queued = entry(refusal.message, skewRun = if (refusal.schemaSkew) previousRun else null)

        assertTrue("an unknown key is the two builds disagreeing, not the researcher", refusal.schemaSkew)
        assertFalse(blocksRetry(queued.failure != null, queued.skewRun))
    }

    @Test
    fun `a queued record the validator rejected still waits for a person, exactly as before`() {
        val refusal = http(422, fieldInvalid).apiRefusal("The server rejected this record.")
        val queued = entry(refusal.message, skewRun = if (refusal.schemaSkew) previousRun else null)

        assertFalse(refusal.schemaSkew)
        assertTrue(blocksRetry(queued.failure != null, queued.skewRun))
    }

    @Test
    fun `the run that recorded the refusal does not re-send it`() {
        assertTrue(blocksRetry(true, APP_RUN))
    }

    @Test
    fun `an entry that has not failed at all is untouched by any of this`() {
        assertFalse(blocksRetry(entry(failure = null).failure != null, null))
    }

    @Test
    fun `a refusal about the FILES keeps binding, because the record is already on the server`() {
        val queued = entry("It was saved, but 2 file(s) were refused: … Re-attach them on the record.")
        assertNull(queued.skewRun)
        assertTrue(blocksRetry(queued.failure != null, queued.skewRun))
    }

    @Test
    fun `an entry queued by a build that predates this field decodes as one only a person can clear`() {
        val onDisk = """{"id":"e1","type":"artisan","payloadJson":"{}","label":"Giriraj","createdAt":"x",""" +
            """"failure":"The server rejected this record.","failedAt":"2026-08-08T10:00:00Z"}"""
        val decoded = queueJson.decodeFromString(PendingEntry.serializer(), onDisk)

        assertNull(decoded.skewRun)
        assertTrue(blocksRetry(decoded.failure != null, decoded.skewRun))
    }

    @Test
    fun `the stamp survives a write and a read of the queue file`() {
        val stamped = entry("out of step", skewRun = APP_RUN)
        val round = queueJson.decodeFromString(
            PendingEntry.serializer(),
            queueJson.encodeToString(PendingEntry.serializer(), stamped)
        )

        assertEquals(APP_RUN, round.skewRun)
        assertTrue("and this run still does not re-send it", blocksRetry(round.failure != null, round.skewRun))
    }

    @Test
    fun `a stamp is replaced, not inherited, when the next answer is one a person must settle`() {
        val skewed = entry("out of step", skewRun = previousRun)
        val nowRejected = skewed.copy(failure = "String should have at most 200 characters", skewRun = null)

        assertTrue(blocksRetry(nowRejected.failure != null, nowRejected.skewRun))
    }

    @Test
    fun `the sentence is the one the design workshop pass uses, not a second copy of it`() {
        val said = http(422, extraForbidden).apiRefusal("fallback").message
        val sentence = skewSentence("What this copy of the app sent for this record", said)

        assertTrue(sentence.contains("could not be saved because the app needs an update"))
        assertTrue("nothing in it asks the researcher to correct anything", sentence.contains("Nothing you typed is wrong"))
        assertTrue(
            "and it promises exactly what the retry policy now keeps",
            sentence.contains("it will be sent by itself the next time you open the app after an update")
        )
    }
}
