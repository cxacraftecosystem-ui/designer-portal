package com.designprototype.workshop.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A TRUNCATED UPDATE WAS HANDED TO THE SYSTEM INSTALLER AS THOUGH IT WERE THE BUILD.
 *
 * ── WHAT WAS ACTUALLY WRONG ───────────────────────────────────────────────────────────────────────
 *
 * `WorkshopRepository.downloadApk` checked `response.isSuccessful` and nothing else.
 * `isSuccessful` is a statement about the HEADERS; the body streams afterwards. So a link that dies
 * half-way through 66 MB, a proxy that truncates, and a captive portal answering a short page all
 * produced a file on disk, a `File` handed back, and `launchApkInstaller` started on it.
 *
 * The OS then refuses the file — `PackageInstaller` parses it and checks its signature, and that
 * remains the integrity boundary; nothing here replaces it. What the OS cannot do is say anything
 * this app can act on. Its refusal is a system dialog about a parse error, stacked over a FORCED
 * update prompt with no "Later" in it, while the app's own state has already moved on: `updateBusy`
 * false, `updateError` null, the screen behind looking as though nothing had gone wrong. The remedy
 * for a short download is to download it again, and that was the one instruction nobody was given.
 *
 * ── WHY THE TESTS ARE HERE AND NOT ON A HANDSET ───────────────────────────────────────────────────
 *
 * The only way to see this in the field is to interrupt a 66 MB download at exactly the wrong moment
 * on a phone whose update dialog cannot be dismissed. A desktop JVM writes a short file in
 * microseconds — the same argument `DwDownloadTest` makes about the transfer readout beside it.
 */
class AppUpdateIntegrityTest {

    private lateinit var root: File

    /**
     * A REAL DIRECTORY AND REAL BYTES, because half of what is under test is that the file is gone
     * afterwards. This module has no Robolectric and needs none: the functions take a [File] and a
     * [Long] and nothing else, which is why they are in a file of their own rather than inline in
     * `downloadApk`. Same arrangement, same reason, as `DwReferenceFallbackOwnerTest`'s.
     */
    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("dw-apk-integrity-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /**
     * A stand-in for a downloaded APK, of exactly [bytes] bytes.
     *
     * The CONTENT is irrelevant and deliberately so: what is being tested is a length check, which
     * claims nothing about what is in the file. Authenticity is the OS's signature verification, and
     * a test that filled these with plausible bytes would suggest otherwise.
     */
    private fun apk(name: String, bytes: Int): File =
        File(root, name).apply { writeBytes(ByteArray(bytes)) }

    // ── The rule ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a short file is a mismatch and a whole one is not`() {
        // THE ASSERTION THE DEFECT FAILED: nothing compared these two numbers at all.
        assertTrue(dwApkSizeMismatch(expectedBytes = 66_704_870L, actualBytes = 41_000_000L))
        assertFalse(dwApkSizeMismatch(expectedBytes = 66_704_870L, actualBytes = 66_704_870L))
        // And a body that is somehow LONGER is a mismatch too. It is not the release either way, and
        // "at least as many bytes as promised" is a weaker test with no case that needs it.
        assertTrue(dwApkSizeMismatch(expectedBytes = 66_704_870L, actualBytes = 66_704_871L))
    }

    /**
     * NULL IS NOT A FAILURE, AND THIS IS THE ASSERTION THAT KEEPS THE FLEET UPDATABLE.
     *
     * `AppRelease.sizeBytes` arrived with this change, so every release row published before it
     * carries null. A phone offered one of those must download it exactly as it always did —
     * refusing would leave every handset stuck on the build it is running, which is unrecoverable
     * without a cable and is far worse than the truncation being guarded against. Same rule, same
     * reason, as every defaulted field on `PendingEntry`: an absent fact is not a claim.
     */
    @Test
    fun `a release that declared no size downloads exactly as it always did`() {
        assertFalse(dwApkSizeMismatch(expectedBytes = null, actualBytes = 41_000_000L))
        assertFalse(dwApkSizeMismatch(expectedBytes = null, actualBytes = 0L))
    }

    /**
     * A ZERO IS "NO CLAIM", NOT "THIS RELEASE IS EMPTY".
     *
     * The schema refuses a zero on the way in (`ge=1`) and the workflow's floor guard refuses
     * anything under 40 MiB long before that, so this is defence against a value neither end should
     * ever produce. Read as a claim, a stray 0 would refuse every download of that release for ever,
     * on a forced-update dialog — the two ends agree about it independently rather than each trusting
     * the other's care.
     */
    @Test
    fun `a zero or negative declared size is treated as unknown`() {
        assertFalse(dwApkSizeMismatch(expectedBytes = 0L, actualBytes = 41_000_000L))
        assertFalse(dwApkSizeMismatch(expectedBytes = -1L, actualBytes = 41_000_000L))
    }

    // ── What the download actually does about it ─────────────────────────────────────────────────

    @Test
    fun `a short download is deleted and reported with the message that offers a retry`() {
        val file = apk("design-workshop-v1001017.apk", bytes = 4_096)

        val thrown = runCatching { dwRequireWholeApk(file, expectedBytes = 66_704_870L) }.exceptionOrNull()

        // THE MESSAGE IS THE EXISTING RETRYABLE ONE, deliberately and not for want of a better: a body
        // that stopped part-way IS a connection failure, and `MainActivity` prints `it.message` into
        // `updateError` and re-enables the confirm button on the same pass. One situation, one
        // sentence — the alternative is two spellings of "the download did not finish" on one screen.
        assertEquals(
            "Unable to download the update — check your connection and try again.",
            thrown?.message,
        )
        assertEquals(DW_UPDATE_DOWNLOAD_RETRY_MESSAGE, thrown?.message)
        // NOTHING THAT FAILED ITS CHECK MAY SURVIVE AS SOMETHING THAT LOOKS LIKE A RESULT. Left on
        // disk it is a partial APK addressable by path, on a phone whose storage the photographs
        // need — and `launchApkInstaller` takes a `File`.
        assertFalse("the truncated file is still on the disk", file.exists())
    }

    @Test
    fun `a whole download is returned untouched`() {
        val file = apk("whole.apk", bytes = 8_192)
        val returned = dwRequireWholeApk(file, expectedBytes = 8_192L)

        assertEquals(file, returned)
        assertTrue(returned.exists())
        assertEquals(8_192L, returned.length())
    }

    @Test
    fun `a release with no declared size is neither deleted nor refused`() {
        // The compatibility path, asserted where it actually costs something: this is the branch every
        // phone in the field takes until a release published by this build reaches it.
        val file = apk("legacy.apk", bytes = 4_096)
        val returned = dwRequireWholeApk(file, expectedBytes = null)

        assertEquals(file, returned)
        assertTrue("an unverified download is still a download", returned.exists())
    }

    @Test
    fun `an empty body against a declared size is caught rather than installed`() {
        // The captive-portal and dead-socket shape: a 200, a `File`, and nothing in it. Before the
        // check this reached `launchApkInstaller`, which is the worst version of the failure — the
        // system dialog says least about the emptiest file.
        val file = apk("empty.apk", bytes = 0)

        val thrown = runCatching { dwRequireWholeApk(file, expectedBytes = 66_704_870L) }.exceptionOrNull()

        assertEquals(DW_UPDATE_DOWNLOAD_RETRY_MESSAGE, thrown?.message)
        assertFalse(file.exists())
    }

    /**
     * THE SENTENCE IS RETRYABLE IN WORDS AS WELL AS IN BEHAVIOUR.
     *
     * A designer reading it is standing in front of a dialog they cannot dismiss. It has to name the
     * act that gets them out of it, and it must not read as a verdict about the phone or the build —
     * both of which would send them to a support request instead of to the button directly below.
     */
    @Test
    fun `the message names the act rather than describing a dead end`() {
        assertTrue(DW_UPDATE_DOWNLOAD_RETRY_MESSAGE.contains("try again"))
        for (deadEnd in listOf("corrupt", "damaged", "not supported", "contact")) {
            assertFalse(
                "\"$deadEnd\" appeared in:\n$DW_UPDATE_DOWNLOAD_RETRY_MESSAGE",
                DW_UPDATE_DOWNLOAD_RETRY_MESSAGE.contains(deadEnd),
            )
        }
    }
}
