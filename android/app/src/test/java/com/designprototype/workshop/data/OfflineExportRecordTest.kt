package com.designprototype.workshop.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline export log, and the truncated-dataset flag: the two new pieces of WIRE the export work
 * added, pinned at the only level a JVM test can reach them — the bytes.
 *
 * ── WHY THESE TWO AND NOT THE SCREENS ─────────────────────────────────────────────────────────
 *
 * Both changes are a value crossing a boundary and being read back by different code, later, on a
 * different day. That is exactly the kind of defect a unit test catches and a build does not: the
 * Kotlin compiles either way, and the failure only appears on a handset in a village.
 *
 * ── 1. THE QUEUED EXPORT LOG ──────────────────────────────────────────────────────────────────
 *
 * `recordDesignWorkshopExport` now enqueues on the offline outbox instead of dropping the row. The
 * outbox stores ONE opaque `payloadJson` string per entry, and the workshop id is a PATH segment on
 * `POST /design-workshops/{id}/exports` rather than a field of [ExportRecordBody] — so the id is
 * simply not in the body. [PendingExportRecord] exists to carry it.
 *
 * If that pairing ever breaks, the failure is silent and total: `createFromEntry` decodes the
 * payload, throws [SerializationException], and `isTransient` calls that PERMANENT (correctly — the
 * next pass reads the same bytes off the same disk). The entry is marked failed and the designer is
 * told the export "could not be uploaded" about a file already in the ministry officer's hands.
 * So this pins the round trip AND pins that a bare body — what the old code would have had to queue —
 * does not decode, which is the whole reason the wrapper is there.
 *
 * ── 2. THE TRUNCATED FLAG ─────────────────────────────────────────────────────────────────────
 *
 * MEASURED against the running API on 2026-08-09: `GET /api/export/dataset` as admin2@example.org
 * answers HTTP 200 with exactly the keys `[files, totalFiles, totalMedia, truncated]`. The DTO used
 * to declare the first three and drop `truncated` on the floor.
 *
 * The flag CANNOT be derived from the counts, and the second test says so in code rather than only
 * in a comment: a capped manifest is internally consistent — every file it names is fetched — so
 * `totalFiles == files.size` holds while the archive is missing everything past the cap. A partial
 * archive that presents itself as complete is worse than a failed one, because nobody goes back for
 * the rest.
 */
class OfflineExportRecordTest {

    /**
     * The outbox's own encoder, copied from `OfflineOutbox`/`WorkshopRepository.offlineJson`
     * verbatim. A test that used a laxer configuration than production would pass over a payload
     * production cannot read.
     */
    private val outboxJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The RETROFIT decoder, copied from `ApiClient.retrofit` verbatim — including
     * `coerceInputValues`, which is what turns an explicit `"truncated": null` from an older server
     * into the declared default rather than an exception.
     */
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
        warnings = "Stage 14 — Costing: 2 required field(s) missing",
    )

    // ── 1. The queued export log ────────────────────────────────────────────────────────────────

    /**
     * The workshop id and every field of the body survive a spell on disk.
     *
     * `fileSizeBytes` and `checksumSha256` are asserted individually rather than only through data
     * class equality: the checksum is the entire point of the record (the office's report history
     * answers "was the revised copy the same file as last time" from it and from nothing else), and
     * a Long that came back as something else would be a silent corruption of the size column.
     */
    @Test
    fun `the workshop id and the whole body survive the outbox`() {
        val queued = PendingExportRecord(workshopId = "cmsj1uoe900032ruqnx9518pw", body = body)

        val payloadJson = outboxJson.encodeToString(queued)
        val replayed = outboxJson.decodeFromString<PendingExportRecord>(payloadJson)

        // The id is a PATH segment, so losing it means the replay has no route to POST to.
        assertEquals("cmsj1uoe900032ruqnx9518pw", replayed.workshopId)
        assertEquals(body, replayed.body)
        assertEquals(31_457_280L, replayed.body.fileSizeBytes)
        assertEquals(
            "9f3c1b7ea4d05e2c8b1f6a3d7e90c4b25f8a1d6e3c07b9f24a8d5e1c6b3f70a9",
            replayed.body.checksumSha256
        )
        assertEquals(48, replayed.body.pageCount)
    }

    /**
     * A bare [ExportRecordBody] — the only thing the call had to hand before the wrapper existed —
     * must NOT decode as a [PendingExportRecord].
     *
     * This is the assertion that keeps the wrapper alive. Without it, a later simplification that
     * queued the body directly would compile, would encode, and would only fail days later on the
     * replay, permanently, on a phone.
     */
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

    /**
     * The export entry's type string collides with no record type.
     *
     * `createFromEntry` is one `when (entry.type)` over string literals. If [OFFLINE_EXPORT_RECORD]
     * were ever changed to one of those words, the earlier branch would win and the replay would try
     * to decode an export payload as a create-record request — a permanent refusal for the export
     * AND, for the branches that reach the network first, a junk record posted to the repository.
     */
    @Test
    fun `the export entry type collides with no record type`() {
        val recordTypes = listOf("artisan", "product", "tool", "workshop", "craft", "questionnaire", "process")

        assertFalse(
            "$OFFLINE_EXPORT_RECORD would be shadowed by a record branch in createFromEntry",
            OFFLINE_EXPORT_RECORD in recordTypes
        )
        assertTrue("The type must be a non-blank discriminator", OFFLINE_EXPORT_RECORD.isNotBlank())
    }

    // ── 2. The truncated dataset flag ───────────────────────────────────────────────────────────

    /**
     * An older server that has never heard of `truncated` still parses, and reads as NOT truncated.
     *
     * Defaulting to false is the safe direction only because the flag is additive: a server that
     * omits it is a server whose caps this client cannot know about. Declaring it without a default
     * would fail the whole manifest and break the download outright for every older deployment.
     */
    @Test
    fun `a manifest with no truncated key parses as not truncated`() {
        val olderServer = """{"files":[{"path":"records/artisans.csv"}],"totalFiles":1,"totalMedia":0}"""

        val manifest = networkJson.decodeFromString<DatasetManifestDto>(olderServer)

        assertFalse(manifest.truncated)
        assertEquals(1, manifest.totalFiles)
        assertEquals(1, manifest.files.size)
    }

    /**
     * The flag is carried when the server sets it — AND is not derivable from the counts.
     *
     * The payload below is the shape MEASURED from the live route (`[files, totalFiles, totalMedia,
     * truncated]`). Note what it asserts: `totalFiles` equals `files.size` while `truncated` is
     * true. Every count agrees with every other count and the archive is still short, which is why
     * the download card cannot work this out for itself and has to be told.
     */
    @Test
    fun `truncated is carried and is not derivable from the counts`() {
        val cappedServer = """
            {"files":[{"path":"records/a.csv"},{"path":"records/b.csv"}],
             "totalFiles":2,"totalMedia":0,"truncated":true}
        """.trimIndent()

        val manifest = networkJson.decodeFromString<DatasetManifestDto>(cappedServer)

        assertTrue("The server said this archive hit its row cap", manifest.truncated)
        // The counts are INTERNALLY CONSISTENT while the archive is incomplete. This is the whole
        // argument for carrying the flag: nothing here distinguishes it from a complete export.
        assertEquals(manifest.files.size, manifest.totalFiles)
    }

    /**
     * The result the download card actually reads carries the flag through unchanged, and defaults
     * to "not truncated" so a construction that predates the field cannot silently claim a cap.
     */
    @Test
    fun `the download result carries the flag and defaults to false`() {
        val short = WorkshopRepository.DatasetDownloadResult(
            displayLocation = "Downloads", saved = 4312, total = 4312, failed = 0, truncated = true
        )
        val whole = WorkshopRepository.DatasetDownloadResult(
            displayLocation = "Downloads", saved = 4312, total = 4312, failed = 0
        )

        // Same counts, opposite meaning — see the test above.
        assertTrue(short.truncated)
        assertFalse(whole.truncated)
        assertEquals(short.saved, whole.saved)
    }
}
