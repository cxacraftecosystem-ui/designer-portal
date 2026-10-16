package com.designprototype.workshop.data

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The name a file saved by Download lands under, and the extension its mime type earns. The same
 * cases as `frontend/e2e/local-media-download-unit.spec.ts`, so both clients save one file under one
 * name.
 */
class MediaDownloadsTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    private val now = at(2026, 10, 16, 14, 30, 5)

    private fun name(displayName: String?, mime: String?, captured: Long? = null) =
        MediaDownloads.downloadFileName(displayName, mime, captured, nowMillis = now, zone = zone)

    @Test
    fun codecParametersAndCaseAreDropped() {
        assertEquals("audio/webm", MediaDownloads.baseMimeType("Audio/WebM; codecs=opus"))
        assertEquals("webm", MediaDownloads.extensionForMime("audio/webm;codecs=opus"))
    }

    @Test
    fun theContainersTheRecordersAndCamerasProduceAreNamed() {
        assertEquals("m4a", MediaDownloads.extensionForMime("audio/mp4"))
        assertEquals("ogg", MediaDownloads.extensionForMime("audio/ogg"))
        assertEquals("mp3", MediaDownloads.extensionForMime("audio/mpeg"))
        assertEquals("mp4", MediaDownloads.extensionForMime("video/mp4"))
        assertEquals("mov", MediaDownloads.extensionForMime("video/quicktime"))
        assertEquals("jpg", MediaDownloads.extensionForMime("image/jpeg"))
        assertEquals("heic", MediaDownloads.extensionForMime("image/heic"))
        assertEquals("pdf", MediaDownloads.extensionForMime("application/pdf"))
    }

    @Test
    fun matchingIsExactAndUnknownIsNull() {
        assertNull(MediaDownloads.extensionForMime("application/vnd.something-pdf"))
        assertNull(MediaDownloads.extensionForMime(""))
        assertNull(MediaDownloads.extensionForMime(null))
        assertNull(MediaDownloads.extensionForMime("application/octet-stream"))
    }

    @Test
    fun aFilesOwnNameIsKept() {
        assertEquals("IMG_2041.jpg", name("IMG_2041.jpg", "image/jpeg"))
        assertEquals("dictation-1.m4a", name("dictation-1.m4a", "audio/mp4"))
    }

    @Test
    fun theExtensionIsAddedOnlyWhenTheNameHasNone() {
        assertEquals("interview take 2.m4a", name("interview take 2", "audio/mp4"))
        assertEquals("notes", name("notes", "application/octet-stream"))
    }

    @Test
    fun aNameIsMadeSafeForAFileSystem() {
        assertEquals("a-b-c-.png", name("a/b:c*?\"<>|.png", "image/png"))
        assertEquals("hidden.pdf", name("..hidden.pdf", "application/pdf"))
    }

    @Test
    fun anUnnamedRecordingIsADatedVoiceNote() {
        assertEquals("voice-note-2026-10-16-143005.webm", name(null, "audio/webm;codecs=opus"))
        assertEquals("voice-note-2026-10-16-143005.m4a", name("blob", "audio/mp4"))
        assertEquals("voice-note-2026-10-16-143005.ogg", name("   ", "audio/ogg"))
    }

    @Test
    fun theCaptureTimeWinsWhenKnown() {
        assertEquals("voice-note-2026-01-02-030405.m4a", name(null, "audio/mp4", at(2026, 1, 2, 3, 4, 5)))
    }

    @Test
    fun otherUnnamedKindsAreNamedByKindAndBinOnlyWhenNothingIsKnown() {
        assertEquals("video-2026-10-16-143005.mp4", name(null, "video/mp4"))
        assertEquals("photo-2026-10-16-143005.jpg", name(null, "image/jpeg"))
        assertEquals("file-2026-10-16-143005.pdf", name(null, "application/pdf"))
        assertEquals("file-2026-10-16-143005.bin", name(null, null))
    }

    @Test
    fun theStampIsZeroPaddedLocalTime() {
        assertEquals("2026-03-04-050607", MediaDownloads.fileDateStamp(at(2026, 3, 4, 5, 6, 7), zone))
    }

    @Test
    fun theOutboxTrayCarriesEachQueuedFileButNeverTheBody() {
        val entry = PendingEntry(
            id = "e1",
            type = "artisan",
            label = "Artisan · Giriraj",
            payloadJson = "{\"aadhaarNumber\":\"secret\"}",
            media = listOf(
                PendingMedia(localPath = "/data/outbox/a.m4a", originalFilename = "a.m4a", mimeType = "audio/mp4", mediaType = "AUDIO"),
            ),
            createdAt = "2026-10-16T09:00:00Z",
            failure = "Refused",
        )
        val row = outboxFailureRows(listOf(entry)).single()
        assertEquals(listOf(OutboxQueuedFile("/data/outbox/a.m4a", "a.m4a", "audio/mp4")), row.files)
    }
}
