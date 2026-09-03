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

/**
 * THE IDEMPOTENCY KEY THIS QUEUE MINTS, AND THE 409 IT MUST STILL PARK RATHER THAN RETRY.
 *
 * ── WHAT THIS DEFENDS ─────────────────────────────────────────────────────────────────────────────
 *
 * A queued create is POSTed, the server writes the row, and the reply dies in a tunnel. This handset
 * learned nothing, so the entry is still in the queue file and the next pass sends the identical
 * body: a SECOND government record for one save, under one designer's name, in an index nobody
 * reconciles.
 *
 * [PendingEntry.createdId] cannot see it. That field is proof this phone RECEIVED an answer — it
 * closes the case where a reply arrived and was written down, and is structurally blind to the case
 * where none ever came. The web outbox names the missing piece by name, and the sentence is the
 * specification for [PendingEntry.clientKey]: *"a few milliseconds of IndexedDB is as small as that
 * window gets without idempotency keys on the API."*
 *
 * ── THE FOUR WAYS THIS CAN BE GOT WRONG ───────────────────────────────────────────────────────────
 *
 *   1. A KEY SENT TO A ROUTE THAT DOES NOT DECLARE ONE. Every request body on that API is an
 *      `APIModel` with `extra="forbid"`, so a `clientKey` posted to `/artisans` is a 422 — read by
 *      this queue as a build disagreement and re-attempted once per app run, for ever, on a prepaid
 *      connection. The list of types is a DECISION and not an optimisation.
 *   2. A KEY ON A CORRECTION. The server's six UPDATE schemas do not declare it either, and the
 *      correction path decodes the same request classes the create path does — so the gate has to
 *      hold at queue time AND the correction branch has to leave the field alone.
 *   3. A KEY MINTED AT DRAIN TIME. It would be a new key on every pass, so the second send of a lost
 *      create would look to the server like a different create and produce the duplicate this whole
 *      field exists to prevent. It has to be exactly as old as the entry.
 *   4. AN ENTRY FROM AN EARLIER BUILD FAILING TO DECODE. The queue file on a handset that has been
 *      out of coverage for a fortnight was written by the build installed a fortnight ago; a field
 *      that is not defaulted turns that fortnight of fieldwork into a queue that will not open.
 *
 * ── AND THE HALF THAT IS DELIBERATELY UNCHANGED ───────────────────────────────────────────────────
 *
 * The server's replay branch answers a repeated key with the row it already made — 201, the same
 * shape, no flag — so a successful replay reaches [ReplayOutcome] as an ordinary success and the
 * entry is removed with its media attached. It NEVER arrives as a 409. That matters because a 409
 * still means what it has always meant here: somebody else's record is in the way. The last group of
 * tests pins that the clash path is untouched by this change, because the one thing that must not
 * happen is a designer being told to "open the clashing record" about their own earlier create.
 *
 * PURE THROUGHOUT. [outboxMintsClientKey] is a decision function, `PendingEntry` is a serializable
 * data class, and [isConflictRefusal] reads a status code — so a JVM test is the right place and,
 * for a queue that only misbehaves with no signal, the only one.
 */
class OutboxClientKeyTest {

    /** The exact reader `OfflineOutbox` uses, so a decoding claim here is a claim about the queue. */
    private val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    // ── 1. WHICH ENTRIES ARE GIVEN A KEY ─────────────────────────────────────────────────────────

    @Test
    fun `the four guarded record types get a key on a create`() {
        for (type in listOf("workshop", "product", "tool", "process")) {
            assertTrue(type, outboxMintsClientKey(type, targetId = null))
        }
    }

    @Test
    fun `artisan and craft do not, because both are already idempotent under a better key`() {
        // NOT AN OVERSIGHT. `Artisan.aadhaarNumber` is @unique and `artisans._guard_identity_conflicts`
        // answers a pre-write 409 NAMING the artisan already holding the number — the sentence
        // `outboxConflictSentence` quotes verbatim so the designer can go and find them. `Craft.name`
        // is @unique with its own 409. Two guards over one question can disagree about what a
        // duplicate is, and worse: the 409 arm below assumes a clash is SOMEBODY ELSE'S record, which
        // a collision with our own earlier create would falsify.
        //
        // It is also a hard refusal: neither `ArtisanCreate` nor `CraftCreate` declares `clientKey`,
        // and `APIModel` is extra="forbid", so sending one 422s the whole save.
        assertFalse(outboxMintsClientKey("artisan", targetId = null))
        assertFalse(outboxMintsClientKey("craft", targetId = null))
    }

    @Test
    fun `the entries that create nothing, or create something already guarded, get none`() {
        // A media-only entry performs no create at all; `questionnaire` is guarded by
        // `QuestionnaireInterview.artisanSetKey @unique`; the export log and the design rating post to
        // routes that are idempotent by construction — the rating route answers `replayed` for exactly
        // this reason. Every one of them would 422 on an undeclared key.
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
        // A non-null targetId is an edit, and an edit goes to the record's PATCH route — whose schema
        // does NOT declare `clientKey`. A key there is `extra_forbidden`: re-attempted once per app
        // run for ever, on a connection the designer pays for by the megabyte.
        //
        // The correction's OWN idempotency question is a different one and is not answered here. See
        // `offlineSavedMessage`'s "WHY THE CORRECTION SENTENCE SAYS WHO WINS", and `expectedUpdatedAt`
        // on the server's six update schemas — built, and waiting for a client to send it.
        for (type in listOf("workshop", "product", "tool", "process", "artisan", "craft")) {
            assertFalse(type, outboxMintsClientKey(type, targetId = "rec-1"))
        }
    }

    @Test
    fun `an unknown type is refused rather than guessed at`() {
        // A type this build does not know is a type whose route this build cannot vouch for, and the
        // cost of guessing wrong is a 422 on the whole save. The default answer is "no key".
        assertFalse(outboxMintsClientKey("", targetId = null))
        assertFalse(outboxMintsClientKey("someFutureRecord", targetId = null))
        // Spelling is exact: a queue file holds the literal string, whatever a later build renames.
        assertFalse(outboxMintsClientKey("Product", targetId = null))
        assertFalse(outboxMintsClientKey("products", targetId = null))
    }

    // ── 2. THE FIELD ON THE ENTRY, AND THE FORTNIGHT-OLD QUEUE FILE ──────────────────────────────

    @Test
    fun `an entry written before this field existed still decodes, and sends no key`() {
        /*
          THE HALF THAT DECIDES WHETHER THIS IS SAFE TO SHIP. The queue file on a handset that has
          been out of coverage for a fortnight was written by the build installed a fortnight ago and
          has no `clientKey` in it. A field without a default would make that file undecodable — a
          fortnight of fieldwork behind a queue that will not open, which is a strictly worse outcome
          than the duplicate this field exists to prevent. Same rule, same reason, as `ownerUserId`.
        */
        val legacy = """{"id":"e1","type":"product","payloadJson":"{}","label":"Bowl",""" +
            """"createdAt":"2026-08-20T10:00:00Z"}"""
        val decoded = queueJson.decodeFromString<PendingEntry>(legacy)
        assertNull("no key, and therefore no key on the wire", decoded.clientKey)
        assertNull(decoded.targetId)
        assertEquals("product", decoded.type)
    }

    @Test
    fun `a key survives the round trip through the queue file`() {
        // The whole mechanism depends on the SAME key going out on every pass, and the passes are
        // separated by an app restart, a reinstall's restore, or a fortnight. If it does not survive
        // the file it does not survive anything.
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
        // `ignoreUnknownKeys` is what lets a LATER build write the queue file an EARLIER one reads —
        // the reverse of the case above, and the one that happens on a downgrade or a shared handset
        // running two installs. Pinned here because the key is the field a future build is most
        // likely to be joined by.
        val forward = """{"id":"e1","type":"tool","payloadJson":"{}","label":"Chisel",""" +
            """"createdAt":"2026-09-03T10:00:00Z","clientKey":"k-1","somethingLater":{"a":1}}"""
        assertEquals("k-1", queueJson.decodeFromString<PendingEntry>(forward).clientKey)
    }

    // ── 3. THE KEY ON THE WIRE ───────────────────────────────────────────────────────────────────

    @Test
    fun `a null key is dropped from the body rather than sent as null`() {
        /*
          `ApiClient.json` has `explicitNulls = false`, and this test is what makes every
          compatibility claim in `WorkshopCreateRequest.clientKey`'s KDoc true rather than hoped for:

            * an ONLINE save (the forms build the request with no key) is byte-identical to what this
              build has always sent;
            * a CORRECTION carries no key, so it cannot be `extra_forbidden` on a PATCH route;
            * an entry from an earlier build replays exactly as it did.
        */
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
        // The four the server declares the field on. A fifth would be a 422 and a sixth is
        // `outboxMintsClientKey`'s job to refuse before it ever gets here.
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
        /*
          THE KEY IS MERGED AT REPLAY, NOT STORED IN `payloadJson`, and this pins that the merge is
          non-destructive. `payloadJson` is the form's own serialisation, written at queue time so
          "a later schema change cannot alter what the user actually saved"; the key is bookkeeping
          about the SEND, so it lives beside the payload and is copied on. The web outbox does the
          same thing in `bodyWithClearances` for the same stated reason.
        */
        val queued = """{"craftName":"Pottery","place":"Bhuj","artisanName":"Giriraj Prasad",""" +
            """"productName":"Bowl","designWorkshopId":"dw-1"}"""
        val decoded = ApiClient.json.decodeFromString(ProductCreateRequest.serializer(), queued)
        val sent = decoded.copy(clientKey = "k-1")
        assertEquals(decoded.productName, sent.productName)
        assertEquals(decoded.designWorkshopId, sent.designWorkshopId)
        assertEquals(decoded.craftName, sent.craftName)
        assertEquals("k-1", sent.clientKey)
        // And the copy is what a correction must NOT do: the same decoded object with no key on it
        // still encodes without the field, which is what `writeFromEntry`'s update branch sends.
        assertFalse(
            ApiClient.json.encodeToString(ProductCreateRequest.serializer(), decoded)
                .contains("clientKey")
        )
    }

    // ── 4. THE 409 IS UNCHANGED, AND STILL PARKS THE ENTRY ───────────────────────────────────────

    @Test
    fun `a replayed create comes back as a success, never as a clash`() {
        /*
          THE SERVER SIDE OF THE CONTRACT, ASSERTED HERE BECAUSE THE HANDSET DEPENDS ON IT. The four
          create routes answer a repeated `clientKey` with the row they already made — 201, the same
          shape, no `replayed` flag — so a successful replay is an ordinary success to `replayEntry`
          and the entry is removed with its media attached.

          If that ever became a 409 instead, `outboxConflictSentence` would tell a designer that the
          register "already holds a clashing record" and send them to compare it against their own
          earlier create — a dead end that reads as somebody else's fault. This test states the
          expectation in the one place a reader of the client will look for it: a 2xx is not a clash.

          THE ASSERTION IS STRUCTURAL, NOT BEHAVIOURAL (corrected 2026-09-03; the first draft called
          `isConflictRefusal(http(201, …))` and learned the stronger fact the hard way): Retrofit's
          `Response.error` refuses any code below 400 at construction, so a replayed 201 CANNOT
          exist as an `HttpException` at all — it arrives as `Response.isSuccessful` and never
          reaches the refusal classifiers. Pinning that refusal is pinning the whole guarantee.
        */
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
        /*
          `expectedUpdatedAt`'s refusal — the server half of the offline-correction precondition —
          answers 409 with `{"code":"record_changed"}`. It lands in exactly this branch, which is the
          intended outcome rather than a coincidence: `isConflictRefusal` reads the STATUS ONLY, so
          the new refusal reaches `outboxConflictSentence`'s correction arm, whose remedy is already
          the right one — *"Open the clashing record, make the change there, then discard this
          entry."* That is compare-and-re-apply, which is what a stale correction needs.

          The entry is PARKED, not retried and never deleted: `Rejected(conflict = true)` is written
          by `markFailure` and `blocksRetry` holds it for a person. `OutboxConflictTest` pins that
          machinery in full; this asserts only that the new body reaches it.
        */
        val stale = """{"detail":{"code":"record_changed","message":"Someone else changed this """ +
            """record after this edit was composed.","expectedUpdatedAt":"2026-09-03T10:00:00+00:00",""" +
            """"currentUpdatedAt":"2026-09-03T11:30:00+00:00"}}"""
        assertTrue(isConflictRefusal(http(409, stale)))
        // And the sentence a designer reads carries the server's own words plus the standing facts.
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
        // A person tapping Try again re-sends the SAME entry. If the key were dropped or re-minted on
        // the way through a failure, that retry would be a different create to the server — which is
        // the duplicate this field exists to prevent, arriving by the one door a designer opens
        // deliberately.
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
