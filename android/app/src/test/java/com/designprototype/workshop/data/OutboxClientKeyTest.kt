package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class OutboxClientKeyTest {

    private val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    @Test
    fun `the four guarded record types get a key on a create`() {
        for (type in listOf("workshop", "product", "tool", "process")) {
            assertTrue(type, outboxMintsClientKey(type, targetId = null))
        }
    }

    @Test
    fun `artisan and craft do not, because both are already idempotent under a better key`() {
        assertFalse(outboxMintsClientKey("artisan", targetId = null))
        assertFalse(outboxMintsClientKey("craft", targetId = null))
    }

    @Test
    fun `the entries that create nothing, or create something already guarded, get none`() {
        for (type in listOf(
            OFFLINE_MEDIA_ONLY,
            "questionnaire",
            OFFLINE_CUSTOM_QUESTIONNAIRE,
            OFFLINE_EXPORT_RECORD,
            OFFLINE_DESIGN_RATING,
        )) {
            assertFalse(type, outboxMintsClientKey(type, targetId = null))
        }
    }

    @Test
    fun `a correction never gets one, whatever record type it corrects`() {
        for (type in listOf("workshop", "product", "tool", "process", "artisan", "craft")) {
            assertFalse(type, outboxMintsClientKey(type, targetId = "rec-1"))
        }
    }

    @Test
    fun `an unknown type is refused rather than guessed at`() {
        assertFalse(outboxMintsClientKey("", targetId = null))
        assertFalse(outboxMintsClientKey("someFutureRecord", targetId = null))
        assertFalse(outboxMintsClientKey("Product", targetId = null))
        assertFalse(outboxMintsClientKey("products", targetId = null))
    }

    @Test
    fun `an entry written before this field existed still decodes, and sends no key`() {
        val legacy = """{"id":"e1","type":"product","payloadJson":"{}","label":"Bowl",""" +
            """"createdAt":"2026-08-20T10:00:00Z"}"""
        val decoded = queueJson.decodeFromString<PendingEntry>(legacy)
        assertNull("no key, and therefore no key on the wire", decoded.clientKey)
        assertNull(decoded.targetId)
        assertEquals("product", decoded.type)
    }

    @Test
    fun `a key survives the round trip through the queue file`() {
        val entry = PendingEntry(
            id = "e1",
            type = "product",
            payloadJson = """{"productName":"Bowl"}""",
            label = "Bowl",
            createdAt = "2026-09-03T10:00:00Z",
            clientKey = "3f2b0f8a-0000-4000-8000-000000000001",
        )
        val reread = queueJson.decodeFromString<PendingEntry>(
            queueJson.encodeToString(PendingEntry.serializer(), entry)
        )
        assertEquals(entry.clientKey, reread.clientKey)
    }

    @Test
    fun `an unknown future field does not stop an entry carrying a key from decoding`() {
        val forward = """{"id":"e1","type":"tool","payloadJson":"{}","label":"Chisel",""" +
            """"createdAt":"2026-09-03T10:00:00Z","clientKey":"k-1","somethingLater":{"a":1}}"""
        assertEquals("k-1", queueJson.decodeFromString<PendingEntry>(forward).clientKey)
    }

    @Test
    fun `a null key is dropped from the body rather than sent as null`() {
        val body = ApiClient.json.encodeToString(
            ProductCreateRequest.serializer(),
            ProductCreateRequest(
                craftName = "Pottery",
                place = "Bhuj",
                artisanName = "Giriraj Prasad",
                productName = "Bowl",
            ),
        )
        assertFalse("no clientKey key at all", body.contains("clientKey"))
    }

    @Test
    fun `a set key is sent, on all four request classes`() {
        val product = ApiClient.json.encodeToString(
            ProductCreateRequest.serializer(),
            ProductCreateRequest(
                craftName = "Pottery", place = "Bhuj", artisanName = "G", productName = "Bowl",
                clientKey = "k-p",
            ),
        )
        val tool = ApiClient.json.encodeToString(
            ToolCreateRequest.serializer(),
            ToolCreateRequest(
                craftName = "Pottery", place = "Bhuj", artisanName = "G", toolkitName = "Chisel",
                clientKey = "k-t",
            ),
        )
        val workshop = ApiClient.json.encodeToString(
            WorkshopCreateRequest.serializer(),
            WorkshopCreateRequest(
                title = "Bhuj visit", date = "2026-09-03", place = "Bhuj", clientKey = "k-w",
            ),
        )
        val process = ApiClient.json.encodeToString(
            ProcessCreateRequest.serializer(),
            ProcessCreateRequest(name = "Throwing", productId = "p1", clientKey = "k-pr"),
        )
        assertTrue(product.contains("\"clientKey\":\"k-p\""))
        assertTrue(tool.contains("\"clientKey\":\"k-t\""))
        assertTrue(workshop.contains("\"clientKey\":\"k-w\""))
        assertTrue(process.contains("\"clientKey\":\"k-pr\""))
    }

    @Test
    fun `copying the key onto a decoded payload changes nothing else about it`() {
        val queued = """{"craftName":"Pottery","place":"Bhuj","artisanName":"Giriraj Prasad",""" +
            """"productName":"Bowl","designWorkshopId":"dw-1"}"""
        val decoded = ApiClient.json.decodeFromString(ProductCreateRequest.serializer(), queued)
        val sent = decoded.copy(clientKey = "k-1")
        assertEquals(decoded.productName, sent.productName)
        assertEquals(decoded.designWorkshopId, sent.designWorkshopId)
        assertEquals(decoded.craftName, sent.craftName)
        assertEquals("k-1", sent.clientKey)
        assertFalse(
            ApiClient.json.encodeToString(ProductCreateRequest.serializer(), decoded)
                .contains("clientKey")
        )
    }

    @Test
    fun `a replayed create comes back as a success, never as a clash`() {
        for (code in intArrayOf(200, 201)) {
            try {
                http(code, """{"id":"p-1"}""")
                fail("Response.error accepted $code — a 2xx can now reach the refusal classifiers, re-verify isConflictRefusal")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("$code"))
            }
        }
    }

    @Test
    fun `a real 409 is still a clash, and the stale-correction 409 joins that path unchanged`() {
        val stale = """{"detail":{"code":"record_changed","message":"Someone else changed this """ +
            """record after this edit was composed.","expectedUpdatedAt":"2026-09-03T10:00:00+00:00",""" +
            """"currentUpdatedAt":"2026-09-03T11:30:00+00:00"}}"""
        assertTrue(isConflictRefusal(http(409, stale)))
        val sentence = outboxConflictSentence(
            said = "Someone else changed this record after this edit was composed.",
            files = 2,
            isCorrection = true,
        )
        assertTrue(sentence.contains("Someone else changed this record after this edit was composed."))
        assertTrue("nothing was destroyed", sentence.contains("Nothing was deleted"))
        assertTrue("the 2 staged captures are still here", sentence.contains("2 files"))
        assertTrue("retrying alone is a dead end", sentence.contains("Retrying alone gets the same answer."))
        assertTrue("and the remedy is to compare", sentence.contains("Open the clashing record"))
    }

    @Test
    fun `an entry parked on a clash keeps its key, so the retry is still the same create`() {
        val parked = PendingEntry(
            id = "e1",
            type = "workshop",
            payloadJson = """{"title":"Bhuj visit"}""",
            label = "Bhuj visit",
            createdAt = "2026-09-03T10:00:00Z",
            clientKey = "k-w",
            failure = "Not saved — the register already holds a clashing record.",
            conflict = true,
        )
        val reread = queueJson.decodeFromString<PendingEntry>(
            queueJson.encodeToString(PendingEntry.serializer(), parked)
        )
        assertEquals("k-w", reread.clientKey)
        assertTrue(reread.conflict)
        assertNull("and it never gained a created id", reread.createdId)
    }
}
