package com.designprototype.workshop.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AppUpdateIntegrityTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("dw-apk-integrity-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun apk(name: String, bytes: Int): File =
        File(root, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `a short file is a mismatch and a whole one is not`() {
        assertTrue(dwApkSizeMismatch(expectedBytes = 66_704_870L, actualBytes = 41_000_000L))
        assertFalse(dwApkSizeMismatch(expectedBytes = 66_704_870L, actualBytes = 66_704_870L))
        assertTrue(dwApkSizeMismatch(expectedBytes = 66_704_870L, actualBytes = 66_704_871L))
    }

    @Test
    fun `a release that declared no size downloads exactly as it always did`() {
        assertFalse(dwApkSizeMismatch(expectedBytes = null, actualBytes = 41_000_000L))
        assertFalse(dwApkSizeMismatch(expectedBytes = null, actualBytes = 0L))
    }

    @Test
    fun `a zero or negative declared size is treated as unknown`() {
        assertFalse(dwApkSizeMismatch(expectedBytes = 0L, actualBytes = 41_000_000L))
        assertFalse(dwApkSizeMismatch(expectedBytes = -1L, actualBytes = 41_000_000L))
    }

    @Test
    fun `a short download is deleted and reported with the message that offers a retry`() {
        val file = apk("design-workshop-v1001017.apk", bytes = 4_096)

        val thrown = runCatching { dwRequireWholeApk(file, expectedBytes = 66_704_870L) }.exceptionOrNull()

        assertEquals(
            "Couldn't download the update. Check your connection and try again.",
            thrown?.message,
        )
        assertEquals(DW_UPDATE_DOWNLOAD_RETRY_MESSAGE, thrown?.message)
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
        val file = apk("legacy.apk", bytes = 4_096)
        val returned = dwRequireWholeApk(file, expectedBytes = null)

        assertEquals(file, returned)
        assertTrue("an unverified download is still a download", returned.exists())
    }

    @Test
    fun `an empty body against a declared size is caught rather than installed`() {
        val file = apk("empty.apk", bytes = 0)

        val thrown = runCatching { dwRequireWholeApk(file, expectedBytes = 66_704_870L) }.exceptionOrNull()

        assertEquals(DW_UPDATE_DOWNLOAD_RETRY_MESSAGE, thrown?.message)
        assertFalse(file.exists())
    }

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
