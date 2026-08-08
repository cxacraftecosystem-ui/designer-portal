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

/**
 * How the Providers & API keys screen reads a `/secrets` failure — and therefore why an ordinary
 * admin is never sent to ask.
 *
 * WHY THIS FILE EXISTS. The hub tile above that screen was master-admin-only, and opening it to
 * plain admins was correct: the provider ranking on the same screen is `require_admin`
 * (backend/app/api/routes/settings.py:227, 236, 282) while only `/secrets` is `require_master_admin`
 * (backend/app/api/routes/secrets.py:39-108). The screen was said to handle the split on its own by
 * DISCOVERING the 403 and drawing "Master admin only" in the key list's place, "without ever setting
 * error, so no snackbar".
 *
 * THAT SENTENCE IS TRUE ONLY WHERE THERE IS SIGNAL, which is not where this app runs. A server that
 * cannot be reached does not answer 403; it throws [UnknownHostException] or
 * [SocketTimeoutException], neither of which is a refusal — so `error` WAS set, and the admin who
 * had just been let through the door got "Unable to load the managed keys" and a snackbar about a
 * resource they were never entitled to. The fix is the web's:
 * `{master ? <ApiKeysPanel/> : null}` — do not ask. `ApiKeysScreen` now takes `isMasterAdmin` and
 * calls `markRestricted()` instead of `load()`, which also stops a guaranteed-403 request being
 * billed to prepaid mobile data on every open.
 *
 * [secretsRefusedTheAccount] is the hinge that made the difference invisible, so it is walked here
 * rather than read. The 403 branch stays: the hint may only remove a request, and an account whose
 * rank changed on the server since sign-in must still be refused by the server.
 */
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
        // The three shapes a village handset actually produces. Each one used to reach the `error`
        // branch and raise a snackbar at an admin who was not allowed to load keys in the first
        // place — a failure they could neither act on nor make sense of.
        assertFalse("DNS failure offline", secretsRefusedTheAccount(UnknownHostException("api")))
        assertFalse("one bar, then nothing", secretsRefusedTheAccount(SocketTimeoutException("timeout")))
        assertFalse("captive portal / socket closed", secretsRefusedTheAccount(IOException("closed")))
    }

    @Test
    fun `a server fault is not a refusal either`() {
        // CloudFront answers 502/503/504 when the EC2 origin is briefly unhealthy. Reading any of
        // those as "you are not the master admin" would tell a master admin they had been demoted.
        assertFalse(secretsRefusedTheAccount(httpError(500)))
        assertFalse(secretsRefusedTheAccount(httpError(502)))
        assertFalse(secretsRefusedTheAccount(httpError(503)))
    }

    @Test
    fun `an expired session is not a refusal`() {
        // 401 is "sign in again", which the app answers elsewhere. Folding it in here would replace
        // that with a permanent "Master admin only" card the master admin could not get past.
        assertFalse(secretsRefusedTheAccount(httpError(401)))
    }

    @Test
    fun `404 is not a refusal`() {
        // A base URL pointing at a build without /secrets is a deployment fault, not an entitlement.
        assertFalse(secretsRefusedTheAccount(httpError(404)))
    }
}
