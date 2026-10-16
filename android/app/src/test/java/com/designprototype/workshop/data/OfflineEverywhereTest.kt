package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-10-16 offline review's smaller pieces: when a background pass asks to run again, the two
 * outbox additions (app feedback, loose media with its workshop and GPS fix), and the measurement
 * markers riding a form draft. See `docs/ANDROID_OFFLINE_AUDIT.md`.
 */
class OfflineEverywhereTest {

    // ── BackgroundSync ─────────────────────────────────────────────────────────────────────────

    @Test
    fun a_background_pass_retries_only_for_work_a_connection_could_have_moved() {
        assertTrue(backgroundSyncShouldRetry(signedIn = true, waitingAfterPass = 3, online = true))
        // No network: the job's own network constraint brings it back; a backoff would only spin.
        assertFalse(backgroundSyncShouldRetry(signedIn = true, waitingAfterPass = 3, online = false))
        assertFalse(backgroundSyncShouldRetry(signedIn = true, waitingAfterPass = 0, online = true))
        // Nobody to send it as: it waits on disk for the next sign-in, it is never sent anonymously.
        assertFalse(backgroundSyncShouldRetry(signedIn = false, waitingAfterPass = 3, online = true))
    }

    // ── App feedback in the outbox ─────────────────────────────────────────────────────────────

    @Test
    fun queued_app_feedback_has_a_name_in_the_tray() {
        assertEquals("App feedback", outboxKindLabel(OFFLINE_APP_FEEDBACK, isUpdate = false))
    }

    @Test
    fun queued_app_feedback_round_trips_through_the_queue_file() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val body = FeedbackUpsertRequest(rating = 4, comment = "Works with no signal")
        val entry = PendingEntry(
            id = "e1",
            type = OFFLINE_APP_FEEDBACK,
            payloadJson = json.encodeToString(FeedbackUpsertRequest.serializer(), body),
            label = "Your rating of the app",
            createdAt = "2026-10-16T08:00:00Z",
            ownerUserId = "u1",
        )
        val back = json.decodeFromString(PendingEntry.serializer(), json.encodeToString(PendingEntry.serializer(), entry))
        assertEquals(body, json.decodeFromString(FeedbackUpsertRequest.serializer(), back.payloadJson))
        assertEquals("u1", back.ownerUserId)
    }

    // ── Loose media: workshop and GPS through the outbox ───────────────────────────────────────

    private fun media(designWorkshopId: String? = null, location: LocationRequest? = null) = PendingMedia(
        localPath = "/data/outbox/media/a.jpg",
        originalFilename = "a.jpg",
        mimeType = "image/jpeg",
        mediaType = "IMAGE",
        caption = "Courtyard",
        designWorkshopId = designWorkshopId,
        location = location,
    )

    private fun complete(pm: PendingMedia, type: String, id: String) = pendingMediaCompleteRequest(
        pm = pm,
        filename = "a.jpg",
        linkedRecordType = type,
        linkedRecordId = id,
        objectKey = "media/u/a.jpg",
        bucket = null,
        publicUrl = null,
        sizeBytes = 10,
        recordedAt = "2026-10-16T08:00:00Z",
    )

    @Test
    fun a_queued_loose_upload_carries_its_workshop_and_fix_to_the_server() {
        val fix = LocationRequest(latitude = 23.25, longitude = 69.66)
        val request = complete(media(designWorkshopId = "dw1", location = fix), "artisan", "a1")
        assertEquals("dw1", request.designWorkshopId)
        assertEquals(fix, request.location)
    }

    @Test
    fun a_record_forms_queued_file_sends_the_same_body_as_before() {
        val request = complete(media(), "tool", "t1")
        assertNull(request.designWorkshopId)
        assertNull(request.location)
        assertEquals("tool", request.linkedRecordType)
        assertEquals("t1", request.linkedRecordId)
        val wire = ApiClient.json.encodeToString(MediaCompleteRequest.serializer(), request)
        assertFalse(wire.contains("designWorkshopId"))
        assertFalse(wire.contains("\"location\""))
    }

    @Test
    fun a_loose_upload_with_no_record_chosen_sends_no_link_as_the_online_path_does() {
        val request = complete(media(), "", "")
        assertNull(request.linkedRecordType)
        assertNull(request.linkedRecordId)
    }

    @Test
    fun a_queue_file_written_before_these_fields_still_decodes() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"localPath":"/p","originalFilename":"a.jpg","mimeType":"image/jpeg","mediaType":"IMAGE"}"""
        val pm = json.decodeFromString(PendingMedia.serializer(), old)
        assertNull(pm.designWorkshopId)
        assertNull(pm.location)
    }

    // ── Measurement markers ride the draft ─────────────────────────────────────────────────────

    @Test
    fun an_accepted_measurement_keeps_its_marker_across_a_restored_draft() {
        val marker = buildJsonObject { put("method", JsonPrimitive("PHOTO_GEOMETRY")) }
        val before = DwMeasurementMarkers().apply { accept("lengthInches", "12.4", marker) }
        val draft = before.toDraft()

        val after = DwMeasurementMarkers().apply { restoreDraft(draft) }

        val body = after.body(mapOf("lengthInches" to "12.4"))!!
        assertEquals("PHOTO_GEOMETRY", body["lengthInches"]!!.jsonObject["method"]!!.jsonPrimitive.content)
        // And a hand edit after the restore still drops it, exactly as before.
        assertNull(after.body(mapOf("lengthInches" to "12.5")))
    }

    @Test
    fun no_accepted_measurement_is_an_empty_draft_field() {
        assertEquals("", DwMeasurementMarkers().toDraft())
        DwMeasurementMarkers().restoreDraft("not json") // never throws
    }
}
