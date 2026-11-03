package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CreateWorkshopOutcomeTest {

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
        assertEquals(
            "Running a design workshop requires Designer access or above.",
            (outcome as CreateOutcome.Refused).message
        )
    }

    @Test
    fun `no signal really is a local draft`() {
        assertEquals(CreateOutcome.Local, classifyCreate(IOException("no route to host"), isTransient))
    }

    @Test
    fun `a success is a local draft only in the sense that nothing failed`() {
        assertEquals(CreateOutcome.Local, classifyCreate(null, isTransient))
    }

    @Test
    fun `the transient HTTP codes stay transient`() {
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
