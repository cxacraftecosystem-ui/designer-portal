package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaUrlRefreshTest {

    private val mediaId = "cm-pit-loom-photo"

    private fun signed(stamp: String, expiresIn: Long): String =
        "https://bucket.s3.dualstack.ap-south-1.amazonaws.com/media/u1/pit-loom.jpg" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Date=$stamp" +
            "&X-Amz-Expires=$expiresIn" +
            "&X-Amz-SignedHeaders=host" +
            "&X-Amz-Signature=8f2c0b2ab1de"

    private val noon = 1_788_429_600_000L

    private val permanent = "https://cdn.example.test/media/u1/pit-loom.jpg"

    @Before
    fun clearLedger() {
        MediaUrlRefresh.resetLedger()
    }

    @Test
    fun `a stored CDN url is not a signature and never reads as expired`() {
        assertFalse(MediaUrlRefresh.isPresigned(permanent))
        assertNull(MediaUrlRefresh.expiresAtMillis(permanent))
        assertFalse(MediaUrlRefresh.isExpired(permanent, noon))
    }

    @Test
    fun `an absent url is inert everywhere`() {
        assertFalse(MediaUrlRefresh.isPresigned(null))
        assertNull(MediaUrlRefresh.expiresAtMillis(null))
        assertNull(MediaUrlRefresh.expiresAtMillis(""))
        assertFalse(MediaUrlRefresh.isExpired(null, noon))
    }

    @Test
    fun `the fixture instant is the one this file claims it is`() {
        assertEquals(noon, MediaUrlRefresh.expiresAtMillis(signed("20260903T100000Z", 1L))!! - 1000L)
    }

    @Test
    fun `the basic-format X-Amz-Date is parsed, because nothing here parses it by default`() {
        assertEquals(
            noon - 300_000L + 900_000L,
            MediaUrlRefresh.expiresAtMillis(signed("20260903T095500Z", 900L))!!
        )
    }

    @Test
    fun `a signature minted a minute ago is alive, one minted an hour ago is not`() {
        assertFalse(MediaUrlRefresh.isExpired(signed("20260903T095900Z", 900L), noon))
        assertTrue(MediaUrlRefresh.isExpired(signed("20260903T090000Z", 900L), noon))
    }

    @Test
    fun `a signature about to die inside the round trip is already dead`() {
        assertTrue(MediaUrlRefresh.isExpired(signed("20260903T094500Z", 905L), noon))
        assertFalse(MediaUrlRefresh.isExpired(signed("20260903T094500Z", 1200L), noon))
    }

    @Test
    fun `a url that does not say when it dies is not guessed at`() {
        val noDate = "https://b.s3.amazonaws.com/k?X-Amz-Signature=abc&X-Amz-Expires=900"
        val noExpiry = "https://b.s3.amazonaws.com/k?X-Amz-Signature=abc&X-Amz-Date=20260903T095500Z"
        val extendedStamp = signed("2026-09-03T09:55:00Z", 900L)
        val beforeEpoch = signed("19690903T095500Z", 900L)

        assertNull(MediaUrlRefresh.expiresAtMillis(noDate))
        assertNull(MediaUrlRefresh.expiresAtMillis(noExpiry))
        assertNull(MediaUrlRefresh.expiresAtMillis(extendedStamp))
        assertNull(MediaUrlRefresh.expiresAtMillis(beforeEpoch))
        assertFalse(MediaUrlRefresh.isExpired(extendedStamp, noon))
    }

    @Test
    fun `an impossible calendar date is refused rather than normalised`() {
        assertNull(MediaUrlRefresh.expiresAtMillis(signed("20260231T100000Z", 900L)))
        assertNull(MediaUrlRefresh.expiresAtMillis(signed("20261301T100000Z", 900L)))
        assertNull(MediaUrlRefresh.expiresAtMillis(signed("20260229T100000Z", 900L)))
        assertEquals(
            1_709_164_800_000L + 900_000L,
            MediaUrlRefresh.expiresAtMillis(signed("20240229T000000Z", 900L))!!
        )
    }

    @Test
    fun `a load failure on a row we can name is refreshed`() {
        assertEquals(
            MediaUrlRefresh.Verdict.REFRESH,
            MediaUrlRefresh.verdictFor(mediaId, signed("20260903T090000Z", 900L), null, online = true)
        )
    }

    @Test
    fun `THE LOOP GUARD - the same media and the same url are refreshed exactly once`() {
        val url = signed("20260903T090000Z", 900L)
        assertEquals(
            MediaUrlRefresh.Verdict.REFRESH,
            MediaUrlRefresh.verdictFor(mediaId, url, 403, online = true)
        )

        runBlocking {
            MediaUrlRefresh.refreshOnce(mediaId, url, 403, online = true) { signed("20260903T100000Z", 900L) }
        }

        assertEquals(
            MediaUrlRefresh.Verdict.ALREADY_TRIED,
            MediaUrlRefresh.verdictFor(mediaId, url, 403, online = true)
        )
    }

    @Test
    fun `the ledger keys on the URL, so a refreshed url may fail on its own terms`() {
        val stale = signed("20260903T090000Z", 900L)
        val fresh = signed("20260903T100000Z", 900L)
        runBlocking { MediaUrlRefresh.refreshOnce(mediaId, stale, 403, online = true) { fresh } }

        assertTrue(MediaUrlRefresh.hasAttempted(mediaId, stale))
        assertFalse(MediaUrlRefresh.hasAttempted(mediaId, fresh))
    }

    @Test
    fun `offline gives up rather than queueing, and says so`() {
        assertEquals(
            MediaUrlRefresh.Verdict.OFFLINE,
            MediaUrlRefresh.verdictFor(mediaId, permanent, 403, online = false)
        )
    }

    @Test
    fun `offline is decided BEFORE the ledger, so a failed offline read costs no attempt`() {
        val url = signed("20260903T090000Z", 900L)
        var reloads = 0

        runBlocking {
            repeat(4) { MediaUrlRefresh.refreshOnce(mediaId, url, null, online = false) { reloads++; null } }
        }

        assertEquals(0, reloads)
        assertFalse(MediaUrlRefresh.hasAttempted(mediaId, url))
        assertEquals(
            MediaUrlRefresh.Verdict.REFRESH,
            MediaUrlRefresh.verdictFor(mediaId, url, null, online = true)
        )
    }

    @Test
    fun `a draft capture with no server id has nothing to re-request`() {
        assertEquals(
            MediaUrlRefresh.Verdict.NO_RECORD,
            MediaUrlRefresh.verdictFor(null, "file:///data/x.jpg", null, online = true)
        )
        assertEquals(
            MediaUrlRefresh.Verdict.NO_RECORD,
            MediaUrlRefresh.verdictFor("  ", "file:///data/x.jpg", null, online = true)
        )
    }

    @Test
    fun `a 404 or a 5xx is left broken and honest`() {
        for (status in listOf(404, 410, 500, 503)) {
            assertEquals(
                "status $status",
                MediaUrlRefresh.Verdict.GONE,
                MediaUrlRefresh.verdictFor(mediaId, permanent, status, online = true)
            )
        }
    }

    @Test
    fun `one failure is one reload, and the fresh url comes back`() = runBlocking {
        val stale = signed("20260903T090000Z", 900L)
        val fresh = signed("20260903T100000Z", 900L)
        var reloads = 0

        val answer = MediaUrlRefresh.refreshOnce(mediaId, stale, null, online = true) {
            reloads++
            fresh
        }

        assertEquals(fresh, answer)
        assertEquals(1, reloads)
    }

    @Test
    fun `THE SECOND FAILURE OF THE SAME URL COSTS NOTHING`() = runBlocking {
        val stale = signed("20260903T090000Z", 900L)
        var reloads = 0
        val reload: suspend (String) -> String? = { reloads++; signed("20260903T100000Z", 900L) }

        MediaUrlRefresh.refreshOnce(mediaId, stale, null, online = true, reload = reload)
        val second = MediaUrlRefresh.refreshOnce(mediaId, stale, null, online = true, reload = reload)

        assertNull(second)
        assertEquals(1, reloads)
    }

    @Test
    fun `a server that hands back the SAME url is answered with null`() = runBlocking {
        val stale = signed("20260903T090000Z", 900L)
        assertNull(MediaUrlRefresh.refreshOnce(mediaId, stale, null, online = true) { stale })
    }

    @Test
    fun `a row whose url the server now withholds resolves to null`() = runBlocking {
        assertNull(MediaUrlRefresh.refreshOnce(mediaId, permanent, null, online = true) { null })
        assertNull(MediaUrlRefresh.refreshOnce(mediaId, permanent, null, online = true) { "   " })
    }

    @Test
    fun `a reload that throws is banked as the one attempt and never propagates`() = runBlocking {
        val url = signed("20260903T090000Z", 900L)
        val answer = MediaUrlRefresh.refreshOnce(mediaId, url, null, online = true) {
            throw IllegalStateException("404 from getMedia")
        }

        assertNull(answer)
        assertTrue(MediaUrlRefresh.hasAttempted(mediaId, url))
    }

    @Test
    fun `a 404 on the object never reaches the API at all`() = runBlocking {
        var reloads = 0
        val answer = MediaUrlRefresh.refreshOnce(mediaId, permanent, 404, online = true) {
            reloads++
            signed("20260903T100000Z", 900L)
        }

        assertNull(answer)
        assertEquals(0, reloads)
    }

    @Test
    fun `the expiry slack matches the web's, to the second`() {
        val expiresAtNoon = signed("20260903T094500Z", 900L)
        assertFalse(MediaUrlRefresh.isExpired(expiresAtNoon, noon - 11_000L))
        assertTrue(MediaUrlRefresh.isExpired(expiresAtNoon, noon - 10_000L))
    }
}
