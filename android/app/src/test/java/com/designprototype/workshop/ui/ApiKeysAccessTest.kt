package com.designprototype.workshop.ui

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ApiKeysAccessTest {

    private fun httpError(code: Int): HttpException = HttpException(
        Response.error<Any>(code, "{\"detail\":\"x\"}".toResponseBody("application/json".toMediaTypeOrNull()))
    )

    @Test
    fun `403 is the server refusing this account, which is a state and not a failure`() {
        assertTrue(
            "GET /secrets answers 403 for anyone below master admin; that must draw the restricted card",
            secretsRefusedTheAccount(httpError(403))
        )
    }

    @Test
    fun `no signal is not a refusal, which is the whole reason the caller passes isMasterAdmin`() {
        assertFalse("DNS failure offline", secretsRefusedTheAccount(UnknownHostException("api")))
        assertFalse("one bar, then nothing", secretsRefusedTheAccount(SocketTimeoutException("timeout")))
        assertFalse("captive portal / socket closed", secretsRefusedTheAccount(IOException("closed")))
    }

    @Test
    fun `a server fault is not a refusal either`() {
        assertFalse(secretsRefusedTheAccount(httpError(500)))
        assertFalse(secretsRefusedTheAccount(httpError(502)))
        assertFalse(secretsRefusedTheAccount(httpError(503)))
    }

    @Test
    fun `an expired session is not a refusal`() {
        assertFalse(secretsRefusedTheAccount(httpError(401)))
    }

    @Test
    fun `404 is not a refusal`() {
        assertFalse(secretsRefusedTheAccount(httpError(404)))
    }
}
