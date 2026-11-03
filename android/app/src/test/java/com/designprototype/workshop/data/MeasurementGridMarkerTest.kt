package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementGridMarkerTest {

    private fun gridShot(purpose: String? = MEASUREMENT_GRID_PURPOSE) = PendingMedia(
        localPath = "/data/user/0/app/files/outbox/abc.jpg",
        originalFilename = "IMG_20260819_101324.jpg",
        mimeType = "image/jpeg",
        mediaType = "IMAGE",
        caption = "Field media for Pit loom",
        recordName = "Pit loom",
        purpose = purpose,
    )

    private fun requestFor(pm: PendingMedia) = pendingMediaCompleteRequest(
        pm = pm,
        filename = "tool-pit-loom-1.jpg",
        linkedRecordType = "tool",
        linkedRecordId = "cmsik2jg8000eh8xc1lcy661a",
        objectKey = "media/u1/abc.jpg",
        bucket = "field-media",
        publicUrl = "https://cdn.example/media/u1/abc.jpg",
        sizeBytes = 91_233,
        recordedAt = "2026-08-19T10:13:24Z",
    )

    private fun encoded(request: MediaCompleteRequest): String =
        ApiClient.json.encodeToString(MediaCompleteRequest.serializer(), request)

    @Test
    fun `the purpose marker is the string the web and the server agree on`() {
        assertEquals("MEASUREMENT_GRID", MEASUREMENT_GRID_PURPOSE)
    }

    @Test
    fun `the marker object is exactly the key the server excludes on`() {
        assertEquals(
            buildJsonObject { put("purpose", JsonPrimitive("MEASUREMENT_GRID")) },
            mediaPurposeMetadata(MEASUREMENT_GRID_PURPOSE),
        )
        assertNull(mediaPurposeMetadata(null))
        assertNull(mediaPurposeMetadata("   "))
    }

    @Test
    fun `a grid capture queued offline still sends the purpose on reconnect`() {
        val body = encoded(requestFor(gridShot()))
        assertTrue(
            "the marker must survive the outbox or the report prints ruled paper: $body",
            body.contains("\"extraMetadata\":{\"purpose\":\"MEASUREMENT_GRID\"}"),
        )
    }

    @Test
    fun `an ordinary queued upload carries no extraMetadata key at all`() {
        val body = encoded(requestFor(gridShot(purpose = null)))
        assertFalse("no new key on every upload in the app: $body", body.contains("extraMetadata"))
    }

    @Test
    fun `an outbox entry written before the marker existed still decodes`() {
        val legacy = """
            {"localPath":"/x/abc.jpg","originalFilename":"IMG_1.jpg","mimeType":"image/jpeg",
             "mediaType":"IMAGE","caption":"Field media for Pit loom","batchIndex":1}
        """.trimIndent()
        val decoded = ApiClient.json.decodeFromString(PendingMedia.serializer(), legacy)
        assertNull("an older entry must decode as unmarked, not fail the file", decoded.purpose)
        assertFalse(encoded(requestFor(decoded)).contains("extraMetadata"))
    }

    @Test
    fun `the marker survives being written to the outbox and read back`() {
        val written = ApiClient.json.encodeToString(PendingMedia.serializer(), gridShot())
        val read = ApiClient.json.decodeFromString(PendingMedia.serializer(), written)
        assertEquals(MEASUREMENT_GRID_PURPOSE, read.purpose)
    }
}
