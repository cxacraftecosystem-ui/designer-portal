package com.designprototype.workshop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * **AN ANSWER FROM OBJECT STORAGE IS NOT A LOST CONNECTION**, and until 2026-08-30 this client could
 * not tell the two apart.
 *
 * ── THE DEFECT, TRACED END TO END ────────────────────────────────────────────────────────────────
 *
 * Both S3 legs raised `IllegalStateException("Object storage upload failed: HTTP $code")`. The
 * status lived in the sentence and nowhere a triage function looks, so:
 *
 *   1. [WorkshopRepository.isTransient] has no arm for `IllegalStateException` and fell to its
 *      `else -> true` — *"anything else is worth another try"*.
 *   2. [isConnectionFailure] defers every non-`HttpException` to [WorkshopRepository.isTransient],
 *      so it answered **true**: the connection's fault.
 *   3. `WorkshopSyncEngine.uploadPending` read that, wrote `lastError = "The upload could not be
 *      completed."` and **returned false — stopping the entire pass**. Every remaining file and
 *      every stage behind that workshop was skipped, the offending file was never marked, so the
 *      next pass began at the same file and did the same thing. For ever.
 *
 * A designer standing in four bars of signal was told, on every pass, that their connection was the
 * problem — over a file the bucket had already considered and refused. That is word for word the
 * failure [isConnectionFailure]'s own KDoc says it was written to prevent (*"one stage the
 * repository will never accept told the designer their signal was gone on a phone showing four
 * bars"*), reproduced one layer down, because the object-storage leg speaks a different dialect of
 * failure from the API leg and nothing translated it.
 *
 * [StorageRefusedError] is the translation. These tests pin the two things that make it work: the
 * status survives as a NUMBER, and the class does not inherit from `IOException`.
 */
class DwStorageRefusalTriageTest {

    /**
     * THE LOAD-BEARING NEGATIVE.
     *
     * [WorkshopRepository.isTransient] answers `is IOException -> true`, documented as *"no answer at
     * all: no signal, DNS, a socket dropped mid-transfer"*. A refusal that S3 composed, signed and
     * sent is the opposite of that. Had [StorageRefusedError] extended `IOException` — the obvious
     * choice for something thrown by an HTTP client — it would have landed straight back in the arm
     * it exists to escape, while looking as though the bug had been fixed.
     *
     * `putToStorage`'s `catch (e: IOException)` uses the same boundary to decide what to retry, so a
     * 4xx wearing an IOException would also have quietly started being re-sent three times over.
     */
    @Test
    fun aRefusalIsNotAnIOException() {
        // Asked of the CLASS rather than of an instance. `x is IOException` is a check the Kotlin
        // compiler can already prove false today — it warns, which is a stronger guarantee than this
        // assertion and also a warning in a `--max-warnings`-clean build. Asked reflectively, the
        // expectation survives as an expectation: it still fails loudly the day somebody changes the
        // supertype, which is the only day it matters.
        assertFalse(
            "extending IOException would put this straight back into isTransient's network arm",
            IOException::class.java.isAssignableFrom(StorageRefusedError::class.java),
        )
    }

    /** The whole point of the class: the status is readable without parsing a sentence. */
    @Test
    fun theStatusSurvivesAsANumber() {
        val error = StorageRefusedError(413, "Object storage upload failed: HTTP 413")
        assertTrue(error.status == 413)
        assertTrue(error.message?.contains("413") == true)
    }

    /**
     * The three statuses that describe a MOMENT rather than the request, and therefore earn another
     * pass. Deliberately the same three [isConnectionFailure] grants the API leg — 401 excepted,
     * which is a credential and cannot reach a presigned URL that carries its own authority.
     */
    @Test
    fun aMomentaryAnswerEarnsAnotherPass() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            assertTrue("HTTP $status describes a moment, not the file", StorageRefusedError(status, "x").worthAnotherPass)
        }
    }

    /**
     * Everything else is the store's settled answer about THIS request, and re-sending 16 MB on a
     * metered field connection to collect it again costs a designer money for nothing.
     *
     * **403 IS IN THIS LIST ONLY BECAUSE `putToStorage` RE-SIGNS.** An expired signature is the
     * commonest 403 on this path and it IS the retryable kind — but it is now retried inside the
     * upload, against a signature minted seconds earlier. A 403 that survives that is the bucket
     * refusing this account or this key. If the re-sign is ever removed, this expectation has to
     * change with it, which is why it is written down as an expectation rather than left implicit.
     */
    @Test
    fun asettledRefusalDoesNotEarnAnother() {
        for (status in listOf(400, 401, 403, 404, 405, 411, 413, 415, 422)) {
            assertFalse("HTTP $status is about the request, not the moment", StorageRefusedError(status, "x").worthAnotherPass)
        }
    }

    /**
     * A part refusal reaches the same triage, through the same `uploadPending`, so it carries the
     * same type. `putPart` raising a bare `IllegalStateException` while `putToStorage` raised a
     * typed one would mean a 413 on a 300 MB video behaved differently from a 413 on a 3 MB
     * photograph — the larger file taking the worse path.
     */
    @Test
    fun bothLegsSpeakTheSameDialect() {
        val whole = StorageRefusedError(413, "Object storage upload failed: HTTP 413")
        val part = StorageRefusedError(413, "Part upload failed: HTTP 413")
        assertTrue(whole.worthAnotherPass == part.worthAnotherPass)
        assertTrue(whole.status == part.status)
    }
}
