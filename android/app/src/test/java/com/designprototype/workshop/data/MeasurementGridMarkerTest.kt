package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE GRAPH-PAPER MARKER, ON THE WIRE AND ACROSS THE OUTBOX — this client's third of a three-surface
 * contract.
 *
 * ── THE DEFECT ────────────────────────────────────────────────────────────────────────────────────
 *
 * A designer photographs an object on a 1-inch grid sheet to measure it, and does it BEFORE taking
 * any presentable picture — so the grid shot is usually the OLDEST image on the product or the tool.
 * The server picks a record's photograph with `createdAt ASC, id ASC`. It therefore picked the grid
 * shot, and the .docx handed to a Development Commissioner's office printed a sheet of ruled paper
 * captioned as the tool.
 *
 * ── AND THE HALF OF THE CLIENT THAT DID NOT SEND IT ───────────────────────────────────────────────
 *
 * The marker was written by the form's grid section and read by `uploadAttachments` — the ONLINE save
 * path. A new product or tool saved with NO SIGNAL never reaches that function: it goes through
 * `trySaveOffline` -> `WorkshopRepository.queueOffline` -> the outbox, and neither [PendingMedia] nor
 * [OfflineMediaSpec] carried a purpose. Offline is this app's PRIMARY field path — the designer
 * measuring an object on graph paper is standing in a cluster with no bars — so for most grid shots
 * nothing was sent at all, and neither of the server's transitional clauses covers that path:
 * [pendingMediaCompleteRequest] carries a `mediaFilename(...)` (never `grid-`/`measure-grid-`) and the
 * caption "Field media for X", not the `% grid (measurement) for %` the caption clause matches.
 *
 * ── WHY THESE CASES AND NOT ASSERTIONS ABOUT A HAND-BUILT DTO ─────────────────────────────────────
 *
 * An earlier version of this file constructed a [MediaCompleteRequest] inside the test and asserted
 * on its JSON, which pinned the DTO property and the literal and NOTHING ELSE: every case passed with
 * the whole of the production wiring reverted. So each case below drives a real production entry
 * point — [mediaPurposeMetadata], [pendingMediaCompleteRequest], the outbox's own serializer — and
 * encodes through [ApiClient.json] rather than a second `Json` built here, so a change to the shared
 * converter is a failure here too.
 *
 * The two things that can silently drift are still the two things pinned: the literal, and the shape
 * of the body it travels in. Nothing fails loudly when either moves — no build breaks, no request
 * errors, no log line appears — the report simply starts printing graph paper again, and the person
 * who finds out is an officer holding the document.
 */
class MeasurementGridMarkerTest {

    /** A queued grid photograph, exactly as `OfflineOutbox.stageMedia` writes one. */
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

    /**
     * THE LITERAL, WHICH IS THE WHOLE CONTRACT.
     *
     * `frontend/components/media/GridMeasurement.tsx` exports `MEASUREMENT_GRID_PURPOSE =
     * "MEASUREMENT_GRID"` and the server's exclusion clause compares against the same characters. If
     * this assertion is ever "fixed" by editing the expected string, the fix is in the wrong place:
     * all three surfaces move together or none of them do.
     */
    @Test
    fun `the purpose marker is the string the web and the server agree on`() {
        assertEquals("MEASUREMENT_GRID", MEASUREMENT_GRID_PURPOSE)
    }

    /**
     * THE ONE BUILDER EVERY SENDING PATH USES, asserted as a value.
     *
     * Both arms of `uploadAttachments` and the outbox replay all call [mediaPurposeMetadata]. Three
     * hand-written `buildJsonObject` blocks were three chances to spell the key differently, with
     * nothing anywhere to notice; this is the assertion that holds the key name.
     */
    @Test
    fun `the marker object is exactly the key the server excludes on`() {
        assertEquals(
            buildJsonObject { put("purpose", JsonPrimitive("MEASUREMENT_GRID")) },
            mediaPurposeMetadata(MEASUREMENT_GRID_PURPOSE),
        )
        // Nothing to declare is no key at all, not an empty one: the server compares the VALUE, and
        // `{"purpose": ""}` matches nothing while adding a field to every upload in the app.
        assertNull(mediaPurposeMetadata(null))
        assertNull(mediaPurposeMetadata("   "))
    }

    /**
     * THE QUEUED GRID SHOT REACHES THE SERVER MARKED — the case the offline path did not have.
     *
     * This drives [pendingMediaCompleteRequest], which is the function
     * `WorkshopRepository.uploadLocalFile` builds its body with, so removing `extraMetadata` from it
     * (or dropping `purpose` from [PendingMedia]) fails here. Encoded rather than compared as an
     * object because what matters is the bytes the API receives.
     */
    @Test
    fun `a grid capture queued offline still sends the purpose on reconnect`() {
        val body = encoded(requestFor(gridShot()))
        assertTrue(
            "the marker must survive the outbox or the report prints ruled paper: $body",
            body.contains("\"extraMetadata\":{\"purpose\":\"MEASUREMENT_GRID\"}"),
        )
    }

    /**
     * EVERY OTHER UPLOAD IN THE APP SENDS EXACTLY THE BODY IT ALWAYS SENT.
     *
     * This is what makes the field a zero-risk addition. The server's `MediaCompleteRequest` is an
     * `APIModel`, which sets `extra="forbid"`; it does declare `extraMetadata`, so a null would be
     * accepted — but a new key appearing on every media upload in the application, from every
     * installed handset, for the sake of one grid photograph is not a change worth making by
     * accident. `encodeDefaults = false` on [ApiClient.json] is what stops it, and an
     * `@EncodeDefault` annotation added to that property in a future tidy-up would undo it without
     * anybody noticing. Encoding through the SHARED converter is what makes this case able to see
     * that: built here, a second `Json` would go on saying "false" after the real one changed.
     */
    @Test
    fun `an ordinary queued upload carries no extraMetadata key at all`() {
        val body = encoded(requestFor(gridShot(purpose = null)))
        assertFalse("no new key on every upload in the app: $body", body.contains("extraMetadata"))
    }

    /**
     * AN ENTRY QUEUED BY AN OLDER BUILD STILL DECODES, and decodes to "no purpose".
     *
     * The outbox is a JSON file on the device, and the device this matters on is one that has been
     * out of signal for a fortnight — so the queue being read is routinely older than the code
     * reading it. A non-defaulted field here would throw `MissingFieldException` on the whole file,
     * which `OfflineOutbox` reports as a count that quietly drops: a day's fieldwork, lost to a
     * property added for one photograph. This is the pin on the `= null` default.
     */
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

    /**
     * THE MARKER SURVIVES A ROUND TRIP THROUGH THE QUEUE FILE.
     *
     * Writing it into the request is not enough: the entry is serialised to disk when the save
     * happens and read back on reconnect, which may be days later and after an app update. A
     * `@Transient` or a rename between the two would lose the marker in the gap with no error.
     */
    @Test
    fun `the marker survives being written to the outbox and read back`() {
        val written = ApiClient.json.encodeToString(PendingMedia.serializer(), gridShot())
        val read = ApiClient.json.decodeFromString(PendingMedia.serializer(), written)
        assertEquals(MEASUREMENT_GRID_PURPOSE, read.purpose)
    }
}
