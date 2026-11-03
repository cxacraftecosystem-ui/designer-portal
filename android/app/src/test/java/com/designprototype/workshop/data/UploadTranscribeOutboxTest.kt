package com.designprototype.workshop.data

import com.designprototype.workshop.questionnaire.QFeature
import com.designprototype.workshop.transcript.data.TranscribeChoices
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class UploadTranscribeOutboxTest {
    @After fun forgetChoices() = TranscribeChoices.clear()

    private fun pending(mediaType: String, mimeType: String, processing: List<String>?) = PendingMedia(
        localPath = "/data/user/0/app/files/outbox/abc",
        originalFilename = "clip",
        mimeType = mimeType,
        mediaType = mediaType,
        processing = processing,
    )

    private fun requestFor(pm: PendingMedia) = pendingMediaCompleteRequest(
        pm = pm,
        filename = "workshop-clip-1",
        linkedRecordType = "product",
        linkedRecordId = "cm_product_1",
        objectKey = "media/u1/abc",
        bucket = "field-media",
        publicUrl = null,
        sizeBytes = 2048,
        recordedAt = "2026-11-03T10:00:00Z",
    )

    @QFeature("F6")
    @Test fun `a queued file sends exactly the choice it was queued with`() {
        assertEquals(listOf("TRANSCRIPTION"), requestFor(pending("VIDEO", "video/mp4", listOf("TRANSCRIPTION"))).processingRequests)
        assertEquals(emptyList<String>(), requestFor(pending("AUDIO", "audio/mp4", emptyList())).processingRequests)
    }

    @QFeature("F6")
    @Test fun `a file queued by an older build keeps the old audio rule`() {
        assertEquals(listOf("TRANSCRIPTION"), requestFor(pending("AUDIO", "audio/mp4", null)).processingRequests)
        assertEquals(emptyList<String>(), requestFor(pending("VIDEO", "video/mp4", null)).processingRequests)
    }

    @QFeature("F6")
    @Test fun `the choice survives the outbox round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = pending("VIDEO", "video/mp4", listOf("TRANSCRIPTION"))
        val back = json.decodeFromString(PendingMedia.serializer(), json.encodeToString(PendingMedia.serializer(), original))
        assertEquals(listOf("TRANSCRIPTION"), back.processing)
    }

    @QFeature("F6")
    @Test fun `a workshop stage file stored on the phone carries its box to the upload`() {
        val json = Json { ignoreUnknownKeys = true }
        val ticked = DraftMedia(id = "m1", relativePath = "media/loom.mp4", mediaType = "VIDEO", transcribe = true)
        assertEquals(true, json.decodeFromString(DraftMedia.serializer(), json.encodeToString(DraftMedia.serializer(), ticked)).transcribe)
        assertNull(json.decodeFromString(DraftMedia.serializer(), """{"id":"old","relativePath":"media/a.m4a"}""").transcribe)
        val sync = File("src/main/java/com/designprototype/workshop/data/WorkshopSync.kt").readText()
        assert(sync.contains("transcribe = descriptor.transcribe,"))
    }

    @QFeature("F2")
    @Test fun `no upload path assumes audio is to be transcribed any more`() {
        val repository = File("src/main/java/com/designprototype/workshop/data/WorkshopRepository.kt").readText()
        assertEquals(0, Regex("""if \((staged\.)?mediaType == "AUDIO"\) listOf\("TRANSCRIPTION"\)""").findAll(repository).count())
    }
}
