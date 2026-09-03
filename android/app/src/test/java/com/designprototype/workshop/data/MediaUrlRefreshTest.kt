package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * THE SINGLE RETRY BEHIND AN EXPIRED MEDIA URL — AND, ABOVE ALL, THAT IT IS SINGLE.
 *
 * ── WHAT THIS DEFENDS ─────────────────────────────────────────────────────────────────────────────
 *
 * [MediaFileDto.url] is about to stop being permanent. The API grows a flag
 * (`MEDIA_PRESIGNED_READS`) that serves a 15-minute signature instead of the world-readable CDN
 * link, and afterwards a human removes the bucket's public-read statement — at which point every URL
 * this handset ever cached starts answering 403.
 *
 * THIS HANDSET IS WHY THAT FLAG EXISTS. 0.0.7 caches `url` in its offline store and draws
 * photographs from the cached string with no network at all, so expiring URLs handed to that build
 * make a designer's workshop silently lose its images on a phone that may not see a network for a
 * week. [MediaUrlRefresh] is the tolerance that makes 0.0.8 the build the flag can wait for, and
 * `WorkshopRepository.refreshMediaUrl` is the one caller that gives it a real network.
 *
 * ── THE FIVE WAYS THIS GOES WRONG, WHICH IS WHY EACH HAS A SECTION ────────────────────────────────
 *
 *   1. **IT LOOPS.** A photograph whose refreshed URL also fails re-arms Coil's `onError`, which
 *      refreshes, which re-arms. On a stage with forty attachments that is a request storm aimed at
 *      the API from a screen that looks idle, on a prepaid connection. Section 3 is the ledger and
 *      it is the section to break first if you are changing this file.
 *   2. **IT REFRESHES OFFLINE.** There is nothing to ask and nothing local to substitute for
 *      somebody else's file. Worse, a refusal recorded as an ATTEMPT would spend the one retry this
 *      object allows on a network that was not there, and the designer would then be holding a
 *      permanently broken image after coming back into signal. Section 3 pins the ORDER that
 *      prevents that, not merely the answer.
 *   3. **IT REFRESHES WHAT A REFRESH CANNOT FIX.** A 404 is a deleted object; a 5xx is storage being
 *      unwell. A fresh signature points at the same absence and adds load to the same trouble.
 *   4. **IT FIRES WHILE THE SERVER FLAG IS OFF.** This ships in 0.0.8, weeks before the flip. A
 *      permanent CDN link has no expiry to read, so nothing may be pre-emptively refreshed and the
 *      app must behave exactly as it did before. Section 1 pins that.
 *   5. **IT MISREADS THE DATE.** `X-Amz-Date` is ISO 8601 BASIC (`20260903T101112Z`), which nothing
 *      on this platform parses without being told to. Getting it wrong in one direction refreshes
 *      every image on the screen; in the other, none of them. Section 2 is arithmetic against known
 *      instants for that reason.
 *
 * NOTHING HERE NEEDS A DEVICE, RETROFIT OR COMPOSE, and that is why the policy lives in an object
 * rather than in the repository: [MediaUrlRefresh.refreshOnce] takes the reload as a lambda, so
 * "exactly once" can be COUNTED instead of reasoned about.
 */
class MediaUrlRefreshTest {

    private val mediaId = "cm-pit-loom-photo"

    /** A signed URL as S3 mints one: basic-format date, an expiry in seconds, a signature. */
    private fun signed(stamp: String, expiresIn: Long): String =
        "https://bucket.s3.dualstack.ap-south-1.amazonaws.com/media/u1/pit-loom.jpg" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Date=$stamp" +
            "&X-Amz-Expires=$expiresIn" +
            "&X-Amz-SignedHeaders=host" +
            "&X-Amz-Signature=8f2c0b2ab1de"

    /** 2026-09-03T10:00:00Z, the instant every signature below is measured against. */
    private val noon = 1_788_429_600_000L

    private val permanent = "https://cdn.example.test/media/u1/pit-loom.jpg"

    @Before
    fun clearLedger() {
        // The ledger is process-wide by design — one photograph is drawn by several composables at
        // once — so it has to be cleared between cases or the second test to touch a URL asserts
        // about the first test's history.
        MediaUrlRefresh.resetLedger()
    }

    // ── 1. A PERMANENT URL IS NEVER TOUCHED — the flag-off world ────────────────────────────────

    @Test
    fun `a stored CDN url is not a signature and never reads as expired`() {
        assertFalse(MediaUrlRefresh.isPresigned(permanent))
        assertNull(MediaUrlRefresh.expiresAtMillis(permanent))
        // With the server flag off every url in every payload takes this branch, so the pre-emptive
        // refresh in `AndroidSavedMediaPreview` can never fire and 0.0.8 behaves as 0.0.7 did.
        assertFalse(MediaUrlRefresh.isExpired(permanent, noon))
    }

    @Test
    fun `an absent url is inert everywhere`() {
        assertFalse(MediaUrlRefresh.isPresigned(null))
        assertNull(MediaUrlRefresh.expiresAtMillis(null))
        assertNull(MediaUrlRefresh.expiresAtMillis(""))
        assertFalse(MediaUrlRefresh.isExpired(null, noon))
    }

    // ── 2. READING A SIGNATURE'S OWN DEATH CERTIFICATE ──────────────────────────────────────────

    @Test
    fun `the fixture instant is the one this file claims it is`() {
        // The arithmetic below is only worth anything if `noon` really is 2026-09-03T10:00:00Z.
        // Asserted against the object's own parser at a zero offset rather than against a date
        // library, so this file has no second source of truth about time in it.
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
        // Expires at 10:00:05, read at 10:00:00. Five seconds is not enough to issue the request,
        // cross a village connection and receive an answer — and a signature that dies mid-transfer
        // yields a TRUNCATED image rather than a clean failure, which nothing downstream detects.
        assertTrue(MediaUrlRefresh.isExpired(signed("20260903T094500Z", 905L), noon))
        // Well clear of the slack, and therefore left alone.
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
        // A pre-epoch date would run the year loop zero times and answer a POSITIVE instant — a
        // signature reading as valid for the next fifty years. Refused rather than computed.
        assertNull(MediaUrlRefresh.expiresAtMillis(beforeEpoch))
        assertFalse(MediaUrlRefresh.isExpired(extendedStamp, noon))
    }

    @Test
    fun `an impossible calendar date is refused rather than normalised`() {
        // A parser that rolled 31 February forward to 3 March would answer a plausible instant for a
        // string no signer ever produced, which is exactly the kind of quiet plausibility that makes
        // a wrong answer survive review.
        assertNull(MediaUrlRefresh.expiresAtMillis(signed("20260231T100000Z", 900L)))
        assertNull(MediaUrlRefresh.expiresAtMillis(signed("20261301T100000Z", 900L)))
        // 2026 is not a leap year, so 29 February is a date that does not exist that year.
        assertNull(MediaUrlRefresh.expiresAtMillis(signed("20260229T100000Z", 900L)))
        // And the leap day that IS real parses to the right instant, or every February in a leap
        // year would be a day out — a whole day of signatures read as alive when they are dead.
        assertEquals(
            1_709_164_800_000L + 900_000L,
            MediaUrlRefresh.expiresAtMillis(signed("20240229T000000Z", 900L))!!
        )
    }

    // ── 3. THE VERDICT, AND THE LOOP GUARD ──────────────────────────────────────────────────────

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
        // Not a loophole. The second attempt is against a DIFFERENT string, which is a different
        // fact about the world; what the ledger forbids is re-asking the same question of the same
        // URL for as long as a screen is open.
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

        // Four failures on a train. None may be recorded, or the one retry this object allows would
        // be spent on a network that was not there — and the photograph would stay broken after the
        // designer came back into signal, which is the worst of both answers.
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

    // ── 4. THE REPOSITORY PATH, COUNTED ─────────────────────────────────────────────────────────

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
        // Same photograph, same url, a second `onError` — a recomposition, or the same file drawn by
        // a second composable. Nothing may go out.
        val second = MediaUrlRefresh.refreshOnce(mediaId, stale, null, online = true, reload = reload)

        assertNull(second)
        assertEquals(1, reloads)
    }

    @Test
    fun `a server that hands back the SAME url is answered with null`() = runBlocking {
        // The URL is not the problem, so installing it again would re-arm the renderer for a load
        // that has already failed — and the ledger could not stop the second one, because it keys on
        // the url and the url did not change.
        val stale = signed("20260903T090000Z", 900L)
        assertNull(MediaUrlRefresh.refreshOnce(mediaId, stale, null, online = true) { stale })
    }

    @Test
    fun `a row whose url the server now withholds resolves to null`() = runBlocking {
        // `MediaFileDto.url` absent is the encoder's ENTITLEMENT answer — a grant revoked between the
        // two reads — and not an error. Null means "leave the row as it is", which every media
        // surface on this handset already draws, because that field has been nullable throughout.
        assertNull(MediaUrlRefresh.refreshOnce(mediaId, permanent, null, online = true) { null })
        assertNull(MediaUrlRefresh.refreshOnce(mediaId, permanent, null, online = true) { "   " })
    }

    @Test
    fun `a reload that throws is banked as the one attempt and never propagates`() = runBlocking {
        // `refreshMediaUrl` is called from a Compose frame and from a `LaunchedEffect`. A 404 out of
        // Retrofit arrives as an exception, and letting it escape would crash a screen in order to
        // report that a thumbnail could not be re-signed.
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

    // ── 5. THE TWO CLIENTS AGREE ────────────────────────────────────────────────────────────────

    @Test
    fun `the expiry slack matches the web's, to the second`() {
        // `frontend/lib/mediaUrlRefresh.ts` subtracts ten seconds for the identical reason. Two
        // renderings of one rule: a handset that called a URL dead five seconds before the browser
        // did would refresh photographs the browser was still drawing, and the difference would be
        // reported as "the app and the site disagree about my workshop".
        val expiresAtNoon = signed("20260903T094500Z", 900L)
        assertFalse(MediaUrlRefresh.isExpired(expiresAtNoon, noon - 11_000L))
        assertTrue(MediaUrlRefresh.isExpired(expiresAtNoon, noon - 10_000L))
    }
}
