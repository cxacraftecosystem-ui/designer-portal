package com.designprototype.workshop.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineExportRecordTest {

    private val outboxJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val networkJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private val body = ExportRecordBody(
        format = "PDF",
        templateId = "ministry-standard",
        fileName = "Kutch-block-print-workshop-report.pdf",
        generatedAt = "2026-08-09T11:04:22Z",
        fileSizeBytes = 31_457_280L,
        pageCount = 48,
        checksumSha256 = "9f3c1b7ea4d05e2c8b1f6a3d7e90c4b25f8a1d6e3c07b9f24a8d5e1c6b3f70a9",
        warnings = "Stage 14 — Costing: 2 required fields missing",
    )

    @Test
    fun `the workshop id and the whole body survive the outbox`() {
        val queued = PendingExportRecord(workshopId = "cmsj1uoe900032ruqnx9518pw", body = body)

        val payloadJson = outboxJson.encodeToString(queued)
        val replayed = outboxJson.decodeFromString<PendingExportRecord>(payloadJson)

        assertEquals("cmsj1uoe900032ruqnx9518pw", replayed.workshopId)
        assertEquals(body, replayed.body)
        assertEquals(31_457_280L, replayed.body.fileSizeBytes)
        assertEquals(
            "9f3c1b7ea4d05e2c8b1f6a3d7e90c4b25f8a1d6e3c07b9f24a8d5e1c6b3f70a9",
            replayed.body.checksumSha256
        )
        assertEquals(48, replayed.body.pageCount)
    }

    @Test
    fun `a bare export body is not a queued export record`() {
        val bodyOnly = outboxJson.encodeToString(body)

        val threw = runCatching {
            outboxJson.decodeFromString<PendingExportRecord>(bodyOnly)
        }.exceptionOrNull()

        assertTrue(
            "A payload with no workshopId must be refused, not silently decoded: $threw",
            threw is SerializationException
        )
    }

    @Test
    fun `the export entry type collides with no record type`() {
        val recordTypes = listOf("artisan", "product", "tool", "workshop", "craft", "questionnaire", "process")

        assertFalse(
            "$OFFLINE_EXPORT_RECORD would be shadowed by a record branch in createFromEntry",
            OFFLINE_EXPORT_RECORD in recordTypes
        )
        assertTrue("The type must be a non-blank discriminator", OFFLINE_EXPORT_RECORD.isNotBlank())
    }

    @Test
    fun `a manifest with no truncated key parses as not truncated`() {
        val olderServer = """{"files":[{"path":"records/artisans.csv"}],"totalFiles":1,"totalMedia":0}"""

        val manifest = networkJson.decodeFromString<DatasetManifestDto>(olderServer)

        assertFalse(manifest.truncated)
        assertEquals(1, manifest.totalFiles)
        assertEquals(1, manifest.files.size)
    }

    @Test
    fun `truncated is carried and is not derivable from the counts`() {
        val cappedServer = """
            {"files":[{"path":"records/a.csv"},{"path":"records/b.csv"}],
             "totalFiles":2,"totalMedia":0,"truncated":true}
        """.trimIndent()

        val manifest = networkJson.decodeFromString<DatasetManifestDto>(cappedServer)

        assertTrue("The server said this archive hit its row cap", manifest.truncated)
        assertEquals(manifest.files.size, manifest.totalFiles)
    }

    @Test
    fun `the download result carries the flag and defaults to false`() {
        val short = WorkshopRepository.DatasetDownloadResult(
            displayLocation = "Downloads", saved = 4312, total = 4312, failed = 0, truncated = true
        )
        val whole = WorkshopRepository.DatasetDownloadResult(
            displayLocation = "Downloads", saved = 4312, total = 4312, failed = 0
        )

        assertTrue(short.truncated)
        assertFalse(whole.truncated)
        assertEquals(short.saved, whole.saved)
    }
}
