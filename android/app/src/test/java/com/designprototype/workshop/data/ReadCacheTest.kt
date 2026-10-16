package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.UnknownHostException

/**
 * A read-only screen opened with no signal shows the last list this phone read, says that it is a
 * copy and from when — and never shows a copy over an answer the server actually gave.
 */
class ReadCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Serializable
    data class Row(val id: String, val title: String)

    private val rows = listOf(Row("1", "Loom"), Row("2", "Kiln"))
    private val serializer = ListSerializer(Row.serializer())

    private fun http(code: Int) = HttpException(
        Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))
    )

    @Test
    fun a_live_read_is_returned_as_live_and_kept() = runBlocking {
        val root = folder.newFolder()
        val read = ReadCache.read(root, "u", "tasks:assigned:", serializer) { rows }
        assertEquals(rows, read.value)
        assertFalse(read.fromCache)
        assertEquals(rows, ReadCache.load(root, "u", "tasks:assigned:", serializer)?.first)
    }

    @Test
    fun no_signal_serves_the_kept_copy_with_its_date() = runBlocking {
        val root = folder.newFolder()
        ReadCache.read(root, "u", "k", serializer) { rows }

        val read = ReadCache.read(root, "u", "k", serializer) { throw UnknownHostException("no signal") }

        assertEquals(rows, read.value)
        assertTrue(read.fromCache)
        assertTrue(read.cachedAt!!.isNotBlank())
    }

    @Test
    fun a_server_error_or_throttle_serves_the_copy_too() = runBlocking {
        val root = folder.newFolder()
        ReadCache.read(root, "u", "k", serializer) { rows }
        for (code in listOf(408, 429, 500, 503)) {
            val read = ReadCache.read(root, "u", "k", serializer) { throw http(code) }
            assertTrue("HTTP $code", read.fromCache)
        }
    }

    @Test
    fun an_answered_refusal_is_shown_even_when_a_copy_exists() = runBlocking {
        val root = folder.newFolder()
        ReadCache.read(root, "u", "k", serializer) { rows }
        for (code in listOf(401, 403, 404, 422)) {
            val refusal = http(code)
            try {
                ReadCache.read(root, "u", "k", serializer) { throw refusal }
                fail("HTTP $code must not be answered from the device")
            } catch (e: HttpException) {
                assertSame(refusal, e)
            }
        }
    }

    @Test
    fun no_copy_rethrows_exactly_what_the_read_threw() = runBlocking {
        val root = folder.newFolder()
        val offline = IOException("offline")
        try {
            ReadCache.read(root, "u", "never-read", serializer) { throw offline }
            fail("expected the original failure")
        } catch (e: IOException) {
            assertSame(offline, e)
        }
    }

    @Test
    fun another_account_never_sees_this_accounts_copy() = runBlocking {
        val root = folder.newFolder()
        ReadCache.read(root, "u1", "k", serializer) { rows }
        assertNull(ReadCache.load(root, "u2", "k", serializer))
        try {
            ReadCache.read(root, "u2", "k", serializer) { throw IOException("offline") }
            fail("u2 has no copy of its own")
        } catch (_: IOException) {
        }
    }

    @Test
    fun the_split_between_unreachable_and_refused() {
        assertTrue(readCacheMayServe(IOException()))
        assertTrue(readCacheMayServe(UnknownHostException()))
        assertTrue(readCacheMayServe(http(503)))
        assertTrue(readCacheMayServe(http(429)))
        assertFalse(readCacheMayServe(http(401)))
        assertFalse(readCacheMayServe(http(404)))
        assertFalse(readCacheMayServe(IllegalStateException()))
    }

    @Test
    fun the_notice_says_it_is_a_copy() {
        val line = readCacheNotice("2026-10-16T08:35:00Z")
        assertTrue(line.startsWith("No connection — showing the copy saved on this phone on "))
        assertTrue(line.endsWith("It may be out of date."))
        assertEquals(
            "No connection — showing the copy saved on this phone. It may be out of date.",
            readCacheNotice(null),
        )
    }
}
