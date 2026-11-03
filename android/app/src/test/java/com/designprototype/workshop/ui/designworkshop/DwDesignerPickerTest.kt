package com.designprototype.workshop.ui.designworkshop

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class DwDesignerPickerTest {

    private fun httpError(code: Int): HttpException = HttpException(
        Response.error<Any>(
            code,
            "{\"detail\":\"x\"}".toResponseBody("application/json".toMediaTypeOrNull())
        )
    )

    private val unreachable: (Throwable) -> Boolean = { it is IOException }

    @Test
    fun `a whole list says nothing at all`() {
        assertNull(dwDesignerPickerStandDown(offline = false, error = null, isConnectionFailure = unreachable))
    }

    @Test
    fun `with no connection the picker stands down and says the workshop can still be started`() {
        val said = dwDesignerPickerStandDown(offline = true, error = null, isConnectionFailure = unreachable)

        assertTrue("the stand-down must say something", !said.isNullOrBlank())
        assertTrue(
            "the sentence must say the workshop can still be started: $said",
            said!!.contains("Start the workshop now")
        )
    }

    @Test
    fun `a dropped connection is told as being offline, because to the admin it is`() {
        assertEquals(
            dwDesignerPickerStandDown(offline = true, error = null, isConnectionFailure = unreachable),
            dwDesignerPickerStandDown(
                offline = false,
                error = IOException("no route to host"),
                isConnectionFailure = unreachable,
            )
        )
    }

    @Test
    fun `a server that predates the endpoint is its own answer, not an offline one`() {
        val missing = dwDesignerPickerStandDown(
            offline = false,
            error = httpError(404),
            isConnectionFailure = unreachable,
        )
        val offline = dwDesignerPickerStandDown(offline = true, error = null, isConnectionFailure = unreachable)

        assertTrue("a missing endpoint must say something", !missing.isNullOrBlank())
        assertNotEquals("a 404 is not a lost connection", offline, missing)
    }

    @Test
    fun `an answered failure is not dressed up as a disconnection`() {
        val offline = dwDesignerPickerStandDown(offline = true, error = null, isConnectionFailure = unreachable)
        listOf(401, 403, 500, 502).forEach { code ->
            val said = dwDesignerPickerStandDown(
                offline = false,
                error = httpError(code),
                isConnectionFailure = unreachable,
            )
            assertTrue("HTTP $code must still say something", !said.isNullOrBlank())
            assertNotEquals("HTTP $code is not a lost connection", offline, said)
        }
    }
}
