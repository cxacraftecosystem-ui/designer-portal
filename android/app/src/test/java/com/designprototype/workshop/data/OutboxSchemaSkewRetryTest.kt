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

/**
 * THE RECORDS OUTBOX IS THE SAME DEFECT AS THE STAGE ONE, ON THE OTHER QUEUE.
 *
 * `DwSchemaSkewRetryTest` covers a design workshop's stages. This covers the queue a researcher's
 * artisan, product or interview sits in when there is no signal — and it is the same bug, in the same
 * shape, three lines away in the same module:
 *
 *     syncOutbox: if (queued.failure != null) continue
 *
 * A queued create is posted as an `APIModel`, and `APIModel` is `extra="forbid"`
 * (backend/app/schemas/common.py) — so a handset that has learned a new key before the API has gets
 * the identical 422 the design workshop pass got for `merge` on 2026-08-08. Recorded as a plain
 * permanent refusal, that stranded a queued artisan AND every photograph attached to them, for good,
 * even after the deploy that closed the skew. The web outbox was given the run stamp
 * (`frontend/lib/offline.ts`); this phone's was not, which is two different ideas of what offline
 * means on the two clients whose whole point is that they behave the same way in a courtyard.
 *
 * THE BODY BELOW IS REAL, captured from the running API by posting an unknown key at an endpoint
 * whose model is `extra="forbid"`:
 *
 *   $ curl -X POST localhost:8000/api/auth/login -d '{"email":"a@b.com","password":"x","merge":true}'
 *   422 {"detail":[{"type":"string_too_short",…},{"type":"extra_forbidden","loc":["body","merge"],…}]}
 */
class OutboxSchemaSkewRetryTest {

    /** The exact reader `OfflineOutbox` uses, so a decoding claim here is a claim about the queue. */
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

    // ── The gate `syncOutbox` consults ───────────────────────────────────────────────────────────

    @Test
    fun `a queued record refused for a key the API did not know is re-attempted by the next app run`() {
        // The whole fix. Nobody taps anything: the researcher was told their record "could not be
        // uploaded", which is not a sentence anyone would answer by pressing a button they cannot see.
        val refusal = http(422, extraForbidden).apiRefusal("The server rejected this record.")
        val queued = entry(refusal.message, skewRun = if (refusal.schemaSkew) previousRun else null)

        assertTrue("an unknown key is the two builds disagreeing, not the researcher", refusal.schemaSkew)
        assertFalse(blocksRetry(queued.failure != null, queued.skewRun))
    }

    @Test
    fun `a queued record the validator rejected still waits for a person, exactly as before`() {
        // The direction that must NOT move. Re-sending a name that is too long gets the same answer
        // for ever, and the queue is right to step over it and say so.
        val refusal = http(422, fieldInvalid).apiRefusal("The server rejected this record.")
        val queued = entry(refusal.message, skewRun = if (refusal.schemaSkew) previousRun else null)

        assertFalse(refusal.schemaSkew)
        assertTrue(blocksRetry(queued.failure != null, queued.skewRun))
    }

    @Test
    fun `the run that recorded the refusal does not re-send it`() {
        // `syncOutbox` runs at sign-in, from the 45-second fallback timer and from the connectivity
        // callback. On a handset walking in and out of coverage that is dozens of passes an hour, and
        // against an API that really is too old every one of them would be a 422 nobody reads.
        assertTrue(blocksRetry(true, APP_RUN))
    }

    @Test
    fun `an entry that has not failed at all is untouched by any of this`() {
        assertFalse(blocksRetry(entry(failure = null).failure != null, null))
    }

    @Test
    fun `a refusal about the FILES keeps binding, because the record is already on the server`() {
        // The media arm of `replayEntry` returns `schemaSkew = false` on purpose. Multipart form-data
        // cannot produce `extra_forbidden`, and re-attempting the entry would re-POST a record the
        // server already holds — the duplicate the whole `createdId` mechanism exists to prevent.
        val queued = entry("It was saved, but 2 file(s) were refused: … Re-attach them on the record.")
        assertNull(queued.skewRun)
        assertTrue(blocksRetry(queued.failure != null, queued.skewRun))
    }

    // ── What is already on the phone ─────────────────────────────────────────────────────────────

    @Test
    fun `an entry queued by a build that predates this field decodes as one only a person can clear`() {
        // The safe direction, and the reason `skewRun` is defaulted rather than required: no refusal
        // already sitting in a researcher's queue is quietly turned into a retry by an app update.
        val onDisk = """{"id":"e1","type":"artisan","payloadJson":"{}","label":"Giriraj","createdAt":"x",""" +
            """"failure":"The server rejected this record.","failedAt":"2026-08-08T10:00:00Z"}"""
        val decoded = queueJson.decodeFromString(PendingEntry.serializer(), onDisk)

        assertNull(decoded.skewRun)
        assertTrue(blocksRetry(decoded.failure != null, decoded.skewRun))
    }

    @Test
    fun `the stamp survives a write and a read of the queue file`() {
        // `encodeDefaults = true`, so the field is really on disk rather than only in memory — an
        // entry whose stamp vanished when the process died would be re-sent on every launch for ever.
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
        // What `markFailure` writes on every call. If the stamp were only ever set and never cleared,
        // a record refused once for a dialect mismatch and then for a genuinely bad field would be
        // re-sent once per app open for the rest of the device's life.
        val skewed = entry("out of step", skewRun = previousRun)
        val nowRejected = skewed.copy(failure = "String should have at most 200 characters", skewRun = null)

        assertTrue(blocksRetry(nowRejected.failure != null, nowRejected.skewRun))
    }

    // ── One wording, both queues ─────────────────────────────────────────────────────────────────

    @Test
    fun `the sentence is the one the design workshop pass uses, not a second copy of it`() {
        // A researcher moves between the two queues on one phone and must not be told two different
        // stories about one refusal. `skewSentence` is shared for exactly that reason.
        val said = http(422, extraForbidden).apiRefusal("fallback").message
        val sentence = skewSentence("What this copy of the app sent for this record", said)

        assertTrue(sentence.contains("Extra inputs are not permitted"))
        assertTrue("nothing in it asks the researcher to correct anything", sentence.contains("Nothing you typed is wrong"))
        assertTrue(
            "and it promises exactly what the retry policy now keeps",
            sentence.contains("it will be sent by itself the next time you open the app after either has been updated")
        )
    }
}
