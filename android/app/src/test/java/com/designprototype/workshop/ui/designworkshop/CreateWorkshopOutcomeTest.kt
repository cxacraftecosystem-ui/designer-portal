package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What the designer is told when starting a design workshop does not work.
 *
 * The create dialog wrapped `POST /design-workshops` in `runCatching` and passed `remote.isFailure`
 * straight through as "this is a local-only workshop", so a 403 — the exact answer a RESEARCHER
 * gets, reproduced against the running API as
 *
 *     403 {"detail":"Running a design workshop requires Designer access or above."}
 *
 * — produced the sentence "Started on this device. Send it to the server from this list once you
 * have a connection." There is a connection. The server refused the account, and it will refuse it
 * again every time. The designer captures a fortnight of stages and photographs believing the work
 * is queued.
 *
 * The distinction is `WorkshopRepository.isTransient`, and it is injected here rather than reached
 * for so the decision can be asserted with no HTTP stack — and so there is only ever one definition
 * of "offline" in this app.
 *
 * ── AND ONE OF THE TRANSIENT CODES BELOW IS A CREATE THAT MAY ALREADY HAVE LANDED ────────────────
 *
 * 408 — and any read timeout, which arrives here as an `IOException` — means the request went out
 * and the answer did not come back. `CreateOutcome.Local` is still the right classification (the
 * work must not be thrown away), but the local draft it mints carries `remoteId = null` for a
 * workshop the server may have committed, and `POST /design-workshops` de-duplicates nothing. That
 * is closed on the writer's side rather than here: the dialog stamps `DraftSyncState.createSentAt`
 * in the same write — but ONLY for a create sent over a validated connection, because a create with
 * no signal at all cannot have landed and stamping it would arm the resolver on the ordinary field
 * path — and `WorkshopSync` asks the server what became of it before posting again. See
 * `DwInterruptedCreateTest`, which pins that decision.
 */
class CreateWorkshopOutcomeTest {

    /** Stands in for `WorkshopRepository.isTransient` without dragging Retrofit into a JVM test. */
    private val isTransient: (Throwable) -> Boolean = { error ->
        when (error) {
            is IOException -> true
            is Refusal -> error.code == 401 || error.code == 408 || error.code == 429 || error.code >= 500
            else -> true
        }
    }

    private class Refusal(val code: Int, message: String) : RuntimeException(message)

    @Test
    fun `a 403 is reported as a refusal and never as being offline`() {
        val outcome = classifyCreate(
            Refusal(403, "Running a design workshop requires Designer access or above."),
            isTransient,
        )

        assertTrue("a 403 must not be presented as a queued local draft", outcome is CreateOutcome.Refused)
        // The SERVER'S OWN WORDS, not a generic sentence. "Designer access or above" is the one piece
        // of information that tells the person what to do next; a fallback message does not.
        assertEquals(
            "Running a design workshop requires Designer access or above.",
            (outcome as CreateOutcome.Refused).message
        )
    }

    @Test
    fun `no signal really is a local draft`() {
        // The behaviour that must NOT regress in the other direction. A courtyard with no signal is
        // the ordinary case this whole app is built around, and a workshop that could not be started
        // there would be the feature failing at the only moment it is asked for.
        assertEquals(CreateOutcome.Local, classifyCreate(IOException("no route to host"), isTransient))
    }

    @Test
    fun `a success is a local draft only in the sense that nothing failed`() {
        assertEquals(CreateOutcome.Local, classifyCreate(null, isTransient))
    }

    @Test
    fun `the transient HTTP codes stay transient`() {
        // 401 is the credential expiring rather than the record being refused; re-signing in fixes it,
        // so condemning the workshop would throw away work for a session timeout. 5xx is the server
        // having a bad minute.
        listOf(401, 408, 429, 500, 502, 504).forEach { code ->
            assertEquals(
                "HTTP $code should be treated as transient",
                CreateOutcome.Local,
                classifyCreate(Refusal(code, "transient"), isTransient)
            )
        }
    }

    @Test
    fun `every permanent 4xx is a refusal`() {
        listOf(400, 403, 404, 409, 422).forEach { code ->
            assertTrue(
                "HTTP $code should be reported to the designer, not queued",
                classifyCreate(Refusal(code, "refused"), isTransient) is CreateOutcome.Refused
            )
        }
    }
}
