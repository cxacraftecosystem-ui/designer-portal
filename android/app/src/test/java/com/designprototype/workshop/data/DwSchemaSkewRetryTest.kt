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

/**
 * A REFUSAL MUST NOT OUTLIVE THE BUG THAT CAUSED IT.
 *
 * WHAT HAPPENED, ON THE WEB, TWICE, ON 2026-08-08. A designer opened a workshop and read:
 *
 *   "The repository refused stage 'CLUSTER_CRAFT_BACKGROUND': merge: Extra inputs are not permitted
 *    … it will keep being refused until the answer that caused it is corrected — this is not a
 *    connection problem. Open the stage, then use Try again."
 *
 * The client had sent the then-new `merge` flag to an API that predated it. `APIModel` is
 * `extra="forbid"` (backend/app/schemas/common.py), so every save came back 422. By the time the
 * banner was read the API had been taught `merge` (backend/app/schemas/design_workshops.py) and the
 * identical request answered 200 — but the refusal had been recorded `permanent = true`, and a
 * permanent refusal is stepped over by every future pass. The app could not recover from a version
 * skew after the skew had closed.
 *
 * THIS PHONE SENDS THE SAME FLAG. `buildStageBody` passes `merge = !authoritative` on every singleton
 * (WorkshopSync.kt), and `pushStages` recorded every non-connection failure as `permanent = true`.
 * The handset was one deploy away from the same stuck banner, on the device least able to be told to
 * clear its storage — so the policy is mirrored here rather than left as a web-only fix.
 *
 * THE BODIES BELOW ARE REAL, captured from the running API today by posting an unknown key at an
 * endpoint whose model is `extra="forbid"`:
 *
 *   $ curl -X POST localhost:8000/api/auth/login -d '{"email":"a@b.com","password":"x","merge":true}'
 *   422 {"detail":[{"type":"string_too_short","loc":["body","password"],…},
 *                  {"type":"extra_forbidden","loc":["body","merge"],"msg":"Extra inputs are not permitted",…}]}
 *
 * `type` is what is matched, never the prose: the message is written for people and may be reworded
 * or translated, while the discriminator is part of pydantic's contract.
 */
class DwSchemaSkewRetryTest {

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    /** An unknown key, exactly as the API names it. */
    private val extraForbidden =
        """{"detail":[{"type":"extra_forbidden","loc":["body","entries",0,"merge"],""" +
            """"msg":"Extra inputs are not permitted","input":true}]}"""

    /** A field the validator rejected — the refusal the old sentence was written for, and right for. */
    private val fieldInvalid =
        """{"detail":[{"type":"string_too_long","loc":["body","title"],""" +
            """"msg":"String should have at most 200 characters"}]}"""

    /** Some earlier run. Any value that is not this process's stands for "the app was reopened". */
    private val previousRun = "run-that-recorded-the-refusal"

    // ── Telling the two refusals apart ───────────────────────────────────────────────────────────

    @Test
    fun `an unknown key is a schema refusal, and the server's own sentence is still surfaced`() {
        val refusal = http(422, extraForbidden).apiRefusal("fallback")
        assertTrue("extra_forbidden means the two builds disagree, not that a person typed something wrong", refusal.schemaSkew)
        assertEquals("Extra inputs are not permitted", refusal.message)
    }

    @Test
    fun `a field the validator rejected is NOT a schema refusal`() {
        // The distinction the whole fix rests on: this one IS about something a person typed, so it
        // must keep binding until they change it.
        val refusal = http(422, fieldInvalid).apiRefusal("fallback")
        assertFalse(refusal.schemaSkew)
        assertEquals("String should have at most 200 characters", refusal.message)
    }

    @Test
    fun `only a 422 qualifies`() {
        // A 500 carrying the same words is a server fault. No update to either side would change it,
        // and re-sending it once per app run would be noise on a prepaid connection for ever.
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
        // `apiErrorMessage` now delegates to `apiRefusal`. It has to answer exactly what it always
        // did — the three FastAPI `detail` shapes — or a dozen unrelated screens change their wording.
        assertEquals("Extra inputs are not permitted", http(422, extraForbidden).apiErrorMessage("fallback"))
        assertEquals("no such workshop", http(404, """{"detail":"no such workshop"}""").apiErrorMessage("x"))
        assertEquals(
            "Giriraj Prasad already holds that Aadhaar",
            http(409, """{"detail":{"message":"Giriraj Prasad already holds that Aadhaar"}}""").apiErrorMessage("x")
        )
        assertEquals("fallback", RuntimeException("").apiErrorMessage("fallback"))
    }

    @Test
    fun `both facts come out of ONE read of the error body`() {
        // Retrofit buffers the error body and reading CONSUMES it. Asking the exception separately
        // for its message and for its type would answer "not a schema refusal" to every failure on
        // the device — a stage that never syncs, with nothing on any screen saying why.
        val error = http(422, extraForbidden)
        val once = error.apiRefusal("fallback")
        assertTrue(once.schemaSkew)
        assertEquals("Extra inputs are not permitted", once.message)
        assertFalse(
            "a second read gets an empty buffer, which is exactly why there must only ever be one",
            error.apiRefusal("fallback").schemaSkew
        )
    }

    // ── How long a recorded refusal binds ────────────────────────────────────────────────────────

    @Test
    fun `a refusal the designer can act on still binds, exactly as before`() {
        assertTrue("no skew recorded — only a person can clear it", blocksRetry(permanent = true, skewRun = null))
        assertTrue(blocksRetry(permanent = true, skewRun = ""))
    }

    @Test
    fun `a hold-up that is not a refusal never blocks`() {
        // "Three files are still on this device" — it clears itself when they upload, and always did.
        assertFalse(blocksRetry(permanent = false, skewRun = null))
        assertFalse(blocksRetry(permanent = false, skewRun = previousRun))
    }

    @Test
    fun `a schema refusal is not re-sent by the run that recorded it`() {
        // This engine is driven from sign-in, a 45-second timer and the connectivity callback, and a
        // handset walking in and out of coverage fires that callback dozens of times an hour. Against
        // a server that really is too old, retrying on every pass is a data bill for 422s nobody reads.
        assertTrue(blocksRetry(permanent = true, skewRun = APP_RUN))
    }

    @Test
    fun `and IS re-sent by the next one, which is the whole fix`() {
        // The designer does not have to know that Try again would help, and has no reason to think it
        // would: the refusal they were shown blamed an answer of theirs.
        assertFalse(blocksRetry(permanent = true, skewRun = previousRun))
    }

    @Test
    fun `the app run is one value for the life of the process`() {
        // Load-bearing in both directions. Changing between reads would defeat the "not every pass"
        // guarantee above; being constant across launches would mean nothing is ever re-attempted.
        assertEquals(APP_RUN, APP_RUN)
        assertTrue(APP_RUN.isNotBlank())
        assertFalse(APP_RUN == previousRun)
    }

    // ── The two halves together: the sequence that was reported ──────────────────────────────────

    @Test
    fun `a stage refused for a schema reason is retried after an update, and a rejected answer is not`() {
        // What `pushStages` records, on the two bodies it can actually get back, and what the next
        // app run then does with each. The stamp is the pass's one line:
        //     skewRun = if (refusal.schemaSkew) APP_RUN else null
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
        // `skewRun` is defaulted, so a record already on a phone comes back null — which means "keep
        // binding". The safe direction: no refusal already on disk is quietly turned into a retry.
        val fromDisk = StageSyncRecord(signature = "abc", failure = "the server refused this stage.", permanent = true)
        assertNull(fromDisk.skewRun)
        assertTrue(blocksRetry(fromDisk.permanent, fromDisk.skewRun))
    }
}
