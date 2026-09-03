package com.designprototype.workshop.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * THREE ABSENCES, THREE OUTCOMES, AND THE QUEUE COULD TELL APART EXACTLY ONE OF THEM.
 *
 * ── WHAT WAS ACTUALLY WRONG ───────────────────────────────────────────────────────────────────────
 *
 * A queued record with no workshop against it, and a queued record pointing at a workshop the server
 * does not have, are three different things that the outbox wrote down as one:
 *
 *  1. THE DESIGNER CHOSE NOTHING. They opened a picker holding four workshops and picked the "None"
 *     row, because this artisan belongs to no design workshop. A DECISION — and one the server was
 *     never told, because `ApiClient.json` has `explicitNulls = false`, the key was dropped, and
 *     `model_dump(exclude_unset=True)` read the absence as "leave the stored value alone". The save
 *     answered 200 and the old link survived. `DesignWorkshopPicker`'s own KDoc has been describing
 *     this since the picker was written: *"a designer clearing the box, pressing Save, being told it
 *     saved, and finding the workshop still there."*
 *  2. THERE WAS NOTHING TO CHOOSE. No signal, and the access lists are never cached because a stale
 *     one is wrong in the permissive direction, so the picker was EMPTY. The designer made no
 *     decision at all — and reading their empty box as one is how a correction composed on the bus
 *     home silently strips a link nobody was ever shown.
 *  3. THE ID IS NOT ON THE SERVER. A 404 on the drain. Not transient, not a 409, not
 *     `extra_forbidden`, so it fell to kind 3 — REFUSED, ON A PERSON — and was parked for the life of
 *     the installation behind a Try again that fetches the identical 404 and a Throw away that
 *     destroys the only copy of the record and its photographs. Nothing named which field.
 *
 * R7 (`DROPDOWN_DESIGN.md` §0, §3.7) is the rule these tests exist to keep: *"An empty picker and a
 * dangling foreign key are opposite failures with opposite remedies… They must never be collapsed
 * into one message."* The first is fixed BEFORE the save; the second AFTER the drain, on the record
 * already on the device, by re-picking.
 *
 * ── THE INVARIANT UNDERNEATH, WHICH IS THE SAME ONE AS EVER ───────────────────────────────────────
 *
 * Nothing on any of these paths may delete. `OfflineOutbox.discard` stays the only door out of this
 * queue that is not a successful send, and only a person opens it. A re-pick rewrites one key; a
 * refusal writes a sentence; neither touches a staged byte.
 *
 * ── THE BODIES ARE REAL ───────────────────────────────────────────────────────────────────────────
 *
 * Copied from the routes this outbox replays. TRUE AS OF 2026-08-29, re-check with:
 *
 *     grep -rn "HTTP_404_NOT_FOUND" backend/app/services/records.py backend/app/services/design_workshops.py
 *
 * Both raise the BYTE-IDENTICAL `"Record not found"`, and `design_workshops.load_workshop_or_404`
 * says why in as many words: *"a 403 would confirm that the id exists to precisely the caller being
 * turned away."* That identity is the reason `danglingReferenceCandidates` reads the payload instead
 * of the body, and the reason it is allowed to answer with more than one candidate.
 */
class OutboxDanglingReferenceTest {

    /** The exact reader `OfflineOutbox` uses, so a decoding claim here is a claim about the queue. */
    private val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The exact wire encoder `ApiClient` builds Retrofit with. See `RecordPatchEncodingTest`. */
    private val wireJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    /** `records.require_record` and `design_workshops.load_workshop_or_404`, word for word. */
    private val notFound = """{"detail":"Record not found"}"""

    /** A reference refused by a validator rather than by an existence check: the field is in `loc`. */
    private val namedMissing =
        """{"detail":[{"type":"value_error","loc":["body","designWorkshopId"],""" +
            """"msg":"Value error, That design workshop does not exist."}]}"""

    private val extraForbidden =
        """{"detail":[{"type":"extra_forbidden","loc":["body","merge"],""" +
            """"msg":"Extra inputs are not permitted","input":true}]}"""

    private val artisanClash =
        """{"detail":{"code":"artisan_identity_conflict","message":"Giriraj Prasad (Bhuj) is already """ +
            """recorded with this Aadhaar number."}}"""

    /** An artisan filed under both a workshop and a design workshop — the ordinary record form. */
    private val bothLinks =
        """{"name":"Giriraj Prasad","place":"Bhuj","workshopId":"wk_1","designWorkshopId":"dw_9"}"""

    /** The same artisan filed under one. */
    private val oneLink = """{"name":"Giriraj Prasad","place":"Bhuj","designWorkshopId":"dw_9"}"""

    /** And filed under neither, which is the shape that must NOT offer a re-pick. */
    private val noLinks = """{"name":"Giriraj Prasad","place":"Bhuj"}"""

    private fun entry(
        payload: String = oneLink,
        failure: String? = null,
        unfiled: Map<String, String> = emptyMap(),
        danglingField: String? = null,
        targetId: String? = null,
        media: List<PendingMedia> = emptyList(),
    ) = PendingEntry(
        id = "entry-1",
        type = "artisan",
        payloadJson = payload,
        label = "Giriraj Prasad",
        createdAt = "2026-08-29T10:00:00Z",
        media = media,
        targetId = targetId,
        failure = failure,
        unfiled = unfiled,
        danglingField = danglingField,
    )

    private fun staged(name: String) = PendingMedia(
        localPath = "/data/user/0/app/files/outbox/media/$name",
        originalFilename = name,
        mimeType = "image/jpeg",
        mediaType = "IMAGE",
    )

    // ── The classification ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a missing reference is recognised and nothing else in the triage table is`() {
        assertTrue("require_record's 404", isMissingReferenceRefusal(http(404, notFound)))
        assertTrue("a validator's 422", isMissingReferenceRefusal(http(422, namedMissing)))

        // The three the connection owns. Calling any of these a missing reference would hand a
        // designer with no bars a picker instead of telling them to find a signal.
        assertFalse(isMissingReferenceRefusal(http(401, notFound)))
        assertFalse(isMissingReferenceRefusal(http(408, notFound)))
        assertFalse(isMissingReferenceRefusal(http(429, notFound)))
        assertFalse(isMissingReferenceRefusal(http(500, notFound)))
        // A clash is the OPPOSITE failure: something already occupies what this record asked for.
        assertFalse(isMissingReferenceRefusal(http(409, artisanClash)))
        assertFalse(isConflictRefusal(http(404, notFound)))
        // And the two shapes that never carry a status at all.
        assertFalse(isMissingReferenceRefusal(IOException("Unable to resolve host")))
        assertFalse(isMissingReferenceRefusal(IllegalStateException("Unknown offline entry type: wat")))
    }

    @Test
    fun `a build disagreement is never mistaken for a missing reference`() {
        // Both are 422s and they end in opposite places: a skew is re-attempted by the NEXT APP RUN
        // by itself and no person can act on it, while a dangling id waits for one tap from a person
        // and no update will ever clear it. `replayEntry` gates the dangling arm on `!schemaSkew`;
        // this is that gate, as an assertion about the fact it reads.
        val skew = http(422, extraForbidden).apiRefusal("The server rejected this record.")
        assertTrue(skew.schemaSkew)
        val missing = http(422, namedMissing).apiRefusal("The server rejected this record.")
        assertFalse("a validator's refusal is not a dialect mismatch", missing.schemaSkew)
    }

    @Test
    fun `the server's own field name wins over anything read off the payload`() {
        // Pydantic puts the column in `loc`. Knowing beats inferring, and it is the only branch that
        // can narrow an entry carrying two links down to the one that is actually wrong.
        val refusal = http(422, namedMissing).apiRefusal("fallback")
        assertEquals(listOf("designWorkshopId"), refusal.namedFields)
        assertEquals(
            listOf("designWorkshopId"),
            danglingReferenceCandidates(payload = bothLinks, named = refusal.namedFields),
        )
        // `body` is not a column and an index is not a column, so a nested loc still finds the key.
        val nested = http(
            422,
            """{"detail":[{"type":"value_error","loc":["body","steps",0,"productId"],"msg":"x"}]}""",
        ).apiRefusal("fallback")
        assertEquals(listOf("steps", "productId"), nested.namedFields)
        assertFalse("\"body\" must never reach the caller", "body" in nested.namedFields)
    }

    @Test
    fun `a 404 that names no field offers every id the record actually sent, and only those`() {
        // Both routes answer the identical string, so there is nothing to narrow with. Naming both is
        // the honest answer; picking one by plausibility is the coin toss `DwResumedCreate.Ambiguous`
        // refuses for the same reason.
        assertEquals(
            listOf("designWorkshopId", "workshopId"),
            danglingReferenceCandidates(payload = bothLinks),
        )
        assertEquals(listOf("designWorkshopId"), danglingReferenceCandidates(payload = oneLink))
        // A key that is absent, null or blank cannot be what the server failed to find, and offering
        // it would be a picker for a box that was never filled in.
        assertEquals(emptyList<String>(), danglingReferenceCandidates(payload = noLinks))
        assertEquals(
            emptyList<String>(),
            danglingReferenceCandidates(payload = """{"name":"x","designWorkshopId":""}"""),
        )
        assertEquals(
            emptyList<String>(),
            danglingReferenceCandidates(payload = """{"name":"x","designWorkshopId":null}"""),
        )
        // A payload that will not parse is not an excuse to invent a candidate.
        assertEquals(emptyList<String>(), danglingReferenceCandidates(payload = "not json at all"))
    }

    @Test
    fun `a correction puts the record it corrects among the suspects`() {
        // `require_record(db.artisan, artisan_id)` runs BEFORE any key in the payload is looked at, so
        // an artisan an admin deleted at the office answers the same 404 as a workshop that never
        // existed. Telling that designer to re-pick a workshop would be a remedy that cannot work.
        val candidates = danglingReferenceCandidates(payload = oneLink, isCorrection = true)
        assertEquals(listOf(TARGET_RECORD_KEY, "designWorkshopId"), candidates)
        assertEquals(
            "artisan it corrects",
            referenceFieldNoun(TARGET_RECORD_KEY, recordNoun = outboxRecordNoun("artisan")),
        )
        assertEquals(
            "design & prototype workshop",
            referenceFieldNoun("designWorkshopId", recordNoun = outboxRecordNoun("artisan")),
        )
    }

    // ── The three outcomes, and that they are three ──────────────────────────────────────────────

    @Test
    fun `the three absences are three states on the entry and not one`() {
        val chosen = entry(unfiled = mapOf("designWorkshopId" to UNFILED_BY_CHOICE))
        val nothingToChoose = entry(unfiled = mapOf("designWorkshopId" to UNFILED_NO_OPTIONS))
        val dangling = entry(failure = "…", danglingField = "designWorkshopId")

        // 1. A CHOSEN absence reaches the wire as a clearance, and is the ONLY one that does.
        assertEquals(setOf("designWorkshopId"), chosen.clearedLinkKeys)
        assertEquals(emptySet<String>(), chosen.emptyPickerKeys)
        // 2. An absence with nothing to choose from sends NOTHING for that column — the stored link
        //    stands, because the designer was never offered the choice and their silence is not one.
        assertEquals(emptySet<String>(), nothingToChoose.clearedLinkKeys)
        assertEquals(setOf("designWorkshopId"), nothingToChoose.emptyPickerKeys)
        // 3. A dangling id is neither: the box was FILLED IN, with an id the server does not have.
        assertEquals(emptySet<String>(), dangling.clearedLinkKeys)
        assertEquals(emptySet<String>(), dangling.emptyPickerKeys)
        assertEquals(listOf("designWorkshopId"), dangling.danglingKeys)
    }

    @Test
    fun `the three outcomes get three sentences and no two of them share their remedy`() {
        val dangling = outboxDanglingSentence(
            said = "Record not found",
            nouns = listOf("design & prototype workshop"),
            files = 3,
            isCorrection = false,
        )
        val sentUnfiled = outboxSentUnfiledMessage("Giriraj Prasad", listOf("design & prototype workshop"))
        val clash = outboxConflictSentence("Giriraj Prasad (Bhuj) is already recorded", files = 3, isCorrection = false)

        // The dangling row is the only one that is still queued and the only one with a re-pick.
        assertTrue(dangling.contains("points at a design & prototype workshop that is not on the server"))
        assertTrue(dangling, dangling.contains("still here"))
        // The unfiled record SENT. Saying "still here" about it would send a designer to a tray that
        // no longer lists it.
        assertTrue(sentUnfiled.contains("was sent"))
        assertFalse("a record that has gone is not still here:\n$sentUnfiled", sentUnfiled.contains("still here"))
        // R7: the two must not word each other. A clash sends the designer to compare two records; a
        // dangling id sends them to one dropdown; neither instruction helps with the other.
        assertFalse("\n$dangling", dangling.contains("clashes with"))
        assertFalse("\n$dangling", dangling.contains("copy anything missing"))
        assertFalse("\n$clash", clash.contains("points at"))
        // And neither of them may borrow §3.5's empty-picker sentence, which is about a record that
        // has not been saved yet and promises it still can be.
        for (formWords in listOf("has not received", "there is nothing to pick here", "can be saved without it")) {
            assertFalse("\"$formWords\" belongs on a form, not in the tray:\n$dangling", dangling.contains(formWords))
            assertFalse("\"$formWords\" belongs on a form, not in the tray:\n$sentUnfiled", sentUnfiled.contains(formWords))
        }
    }

    // ── The sentence ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the dangling sentence names the field, what is still here, and that a bare retry cannot work`() {
        val sentence = outboxDanglingSentence(
            said = http(404, notFound).apiRefusal("The server rejected this record.").message,
            nouns = listOf("design & prototype workshop"),
            files = 3,
            isCorrection = false,
        )
        // 1. WHICH FIELD — the whole value of the row, and the thing "Record not found" cannot say.
        assertTrue(sentence.startsWith("This record points at a design & prototype workshop that is not on the server."))
        // The design document's own clause, byte for byte (DROPDOWN_DESIGN §3.7, O1 point 3).
        assertTrue(sentence.contains("Nothing is lost — open it, choose one that is, and it will send."))
        // 2. THE SERVER'S OWN WORDS, because a route that starts saying something more useful must
        //    not have to wait for a client release to be heard. NEVER SHORTENED, on any of these
        //    sentences, whatever else the terse pass touched.
        assertTrue(sentence.contains("The server said: Record not found."))
        // 3. NOTHING DELETED, and how much is riding on it.
        assertTrue(sentence, sentence.contains("This entry and the 3 files saved with it are still here"))
        assertTrue(sentence, sentence.contains("nothing was deleted"))
        // 4. THAT THE WALK UP THE HILL IS POINTLESS. The fact only; WHY it is pointless — "because
        //    what is missing is missing on the server" — is in the KDoc now (2026-09-03).
        assertTrue(sentence, sentence.contains("Retrying unchanged gets the same answer."))
        // And it never claims the record reached the server — the misreading that, on the 409 path,
        // destroyed a queued record and its photographs while reporting success.
        for (forbidden in listOf("was saved", "has been saved", "already saved", "was uploaded", "already sent")) {
            assertFalse("\"$forbidden\" appeared in:\n$sentence", sentence.contains(forbidden))
        }
    }

    @Test
    fun `an ambiguous answer is stated as one rather than guessed at`() {
        val sentence = outboxDanglingSentence(
            said = "Record not found",
            nouns = listOf("workshop", "artisan"),
            files = 0,
            isCorrection = false,
        )
        assertTrue(sentence.contains("It is a workshop or an artisan — the server's answer does not say which."))
        assertFalse("\"0 files\" reads as an accusation:\n$sentence", sentence.contains("0 file"))
        assertTrue(sentence, sentence.contains("This entry is still here"))
        // "an artisan", not "a artisan". The list is read aloud by a screen reader as often as it is
        // read on a screen.
        assertFalse(sentence.contains("a artisan"))
    }

    @Test
    fun `a 422 about a value is never dressed up as a missing reference`() {
        // THE FAILURE THIS ARM COULD HAVE ADDED. Every field validator on these routes answers 422 —
        // "String should have at most 200 characters", "That Aadhaar number fails its checksum" — and
        // an artisan form sends its links on the same request. Scanning the payload for ids on a 422
        // would print "points at a design & prototype workshop that is not on the server" over a
        // refusal about a NAME: confidently wrong, offering a remedy that cannot work, on a row whose
        // real reason the designer could have acted on in ten seconds.
        val badField = http(
            422,
            """{"detail":[{"type":"string_too_long","loc":["body","name"],""" +
                """"msg":"String should have at most 200 characters"}]}""",
        ).apiRefusal("The server rejected this record.")
        assertEquals(
            emptyList<String>(),
            danglingReferenceCandidates(
                payload = bothLinks,
                named = badField.namedFields,
                isCorrection = true,
                namedOnly = true,
            ),
        )
        // The SAME status with the SAME payload, when the server does name a reference, still answers.
        assertEquals(
            listOf("designWorkshopId"),
            danglingReferenceCandidates(
                payload = bothLinks,
                named = http(422, namedMissing).apiRefusal("x").namedFields,
                isCorrection = true,
                namedOnly = true,
            ),
        )
        // And a 404 is not gated, because on every route this outbox replays it means exactly one
        // thing: an id in this request could not be found.
        assertEquals(
            listOf(TARGET_RECORD_KEY, "designWorkshopId", "workshopId"),
            danglingReferenceCandidates(payload = bothLinks, isCorrection = true, namedOnly = false),
        )
    }

    @Test
    fun `a correction whose record is gone names the record and offers no picker`() {
        // A PATCH whose `require_record` fails carries no clue in the body, and the payload may hold
        // no reference at all. The suspect is then the record itself, which is the {id} in the path
        // and not a field — so there is a sentence and no Re-pick, rather than a picker that changes
        // a workshop on a record that no longer exists.
        val candidates = danglingReferenceCandidates(payload = noLinks, isCorrection = true)
        assertEquals(listOf(TARGET_RECORD_KEY), candidates)
        val row = outboxFailureRows(
            listOf(entry(payload = noLinks, failure = "…", danglingField = TARGET_RECORD_KEY, targetId = "ar_1"))
        ).single()
        assertEquals(listOf("artisan it corrects"), row.danglingNouns)
        assertEquals(emptyList<String>(), row.repickKeys)
    }

    @Test
    fun `a correction that dangles is not described as a record that was never saved`() {
        val sentence = outboxDanglingSentence(
            said = "Record not found",
            nouns = listOf("artisan it corrects", "design & prototype workshop"),
            files = 1,
            isCorrection = true,
        )
        assertTrue(sentence.startsWith("This correction points at"))
        assertTrue(sentence.contains("an artisan it corrects or a design & prototype workshop"))
        assertTrue("one file is one file, not '1 files':\n$sentence", sentence.contains("the 1 file saved with it"))
    }

    @Test
    fun `a server sentence with no full stop does not run into ours`() {
        // The same defect `outboxConflictSentence` fixed for `crafts.py`. A designer skims an
        // unpunctuated run-on and abandons it before the half that tells them what to do.
        val sentence = outboxDanglingSentence(
            said = "Record not found",
            nouns = listOf("workshop"),
            files = 0,
            isCorrection = false,
        )
        assertTrue(sentence, sentence.contains("The server said: Record not found. This entry is still here"))
    }

    @Test
    fun `the sent-unfiled message says which absence it was and what to do next`() {
        val message = outboxSentUnfiledMessage("Giriraj Prasad", listOf("design & prototype workshop"))
        assertTrue(message, message.startsWith("“Giriraj Prasad” was sent, filed under nothing"))
        // The whole point: WHICH of the two absences it was, named by the control that was empty.
        assertTrue(message.contains("there was no design & prototype workshop to choose from on this device"))
        assertFalse("“no a workshop” is a seam a reader learns to skim: $message", message.contains("no a "))
        // And the one act that fixes it. The disclaimer this used to carry — "That was never a claim
        // that none exist" — was an argument about a claim the app had not made, on a notification
        // that follows a SUCCESS; it is in `outboxSentUnfiledMessage`'s KDoc now (2026-09-03).
        assertTrue(message, message.contains("Open the record and file it now"))
        assertTrue("one line on a success notification: $message", message.length <= 200)
    }

    @Test
    fun `an empty re-pick list says WHICH empty it is`() {
        val failed = repickEmptyLine("design & prototype workshop", listed = false)
        val none = repickEmptyLine("design & prototype workshop", listed = true)
        // The two next moves are a connection and an administrator, and a designer sent to the wrong
        // one loses a day. "No workshops are open to this account" said after a timeout is the single
        // most repeated bug class in this repo.
        assertTrue(failed.contains("could not be read just now"))
        assertFalse("a failed read may never assert a scope is empty:\n$failed", failed.contains("open to this account"))
        assertTrue(none.contains("open to this account"))
        assertTrue(none.contains("An administrator can give you access to one."))
        // Both must promise the entry is safe: this dialog is the last route out of a parked record.
        assertTrue(failed.contains("stay on this phone"))
        assertTrue(none.contains("nothing is deleted"))
    }

    // ── The row the tray draws ───────────────────────────────────────────────────────────────────

    @Test
    fun `the row names the field in words and offers a re-pick only where there is a list`() {
        val row = outboxFailureRows(
            listOf(entry(failure = "…", danglingField = "designWorkshopId", media = listOf(staged("a.jpg"))))
        ).single()
        assertEquals(listOf("design & prototype workshop"), row.danglingNouns)
        assertEquals(listOf("designWorkshopId"), row.repickKeys)
        assertFalse("a dangling id is not a clash", row.conflict)
        assertFalse("and nothing of ours was written", row.savedOnServer)

        // A register this screen has no list for still gets its sentence and keeps its entry; what it
        // does not get is a button that opens an empty picker.
        val register = outboxFailureRows(
            listOf(
                entry(
                    payload = """{"name":"x","artisanId":"ar_3"}""",
                    failure = "…",
                    danglingField = "artisanId",
                )
            )
        ).single()
        assertEquals(listOf("artisan"), register.danglingNouns)
        assertEquals(emptyList<String>(), register.repickKeys)

        // The record a correction is aimed at is nameable and is NOT re-pickable: it is the {id} in
        // the PATCH path, not a field.
        val correction = outboxFailureRows(
            listOf(entry(failure = "…", danglingField = "$TARGET_RECORD_KEY,designWorkshopId", targetId = "ar_1"))
        ).single()
        assertEquals(listOf("artisan it corrects", "design & prototype workshop"), correction.danglingNouns)
        assertEquals(listOf("designWorkshopId"), correction.repickKeys)
    }

    @Test
    fun `the flag is not the failure, on this outcome as on the clash`() {
        // An entry carrying the marker with no refusal recorded is not a row: it is one a person has
        // just asked to retry, caught mid-way through `clearFailure`. Same statement, same reason, as
        // `OutboxConflictTest` makes about `conflict`.
        assertEquals(
            emptyList<OutboxFailureRow>(),
            outboxFailureRows(listOf(entry(danglingField = "designWorkshopId"))),
        )
        assertEquals(
            emptyList<String>(),
            outboxFailureRows(listOf(entry(failure = "Name is too long"))).single().danglingNouns,
        )
    }

    @Test
    fun `the discard dialog on a dangling row points at the button that works first`() {
        val warning = outboxDiscardConfirmation(
            label = "Giriraj Prasad",
            files = 3,
            isConflict = false,
            savedOnServer = false,
            isDangling = true,
        )
        assertTrue(warning.contains("Re-pick it fixes that without losing anything"))
        assertTrue(warning.contains("nothing about it has reached the server"))
        // And the arm is opt-in: every existing caller's sentence is untouched.
        val ordinary = outboxDiscardConfirmation("Giriraj Prasad", files = 3, isConflict = false)
        assertFalse(ordinary.contains("Re-pick"))
    }

    // ── An entry queued by an older build ────────────────────────────────────────────────────────

    @Test
    fun `an old queued row decodes into exactly the behaviour it was queued under`() {
        // The queue on a handset that has been out of coverage for a fortnight was written by the
        // build installed a fortnight ago. `designWorkshopId` only reached these forms on 2026-08-28,
        // so an entry from before it is silent about the column because it had never heard of it —
        // NOT because anybody asked for it to be cleared.
        val old = """
            [{"id":"old-1","type":"artisan","payloadJson":"{\"name\":\"Sushila Meher\"}",
              "label":"Sushila Meher","media":[],"createdAt":"2026-08-14T09:00:00Z",
              "createdStepIds":[],"uploadedMedia":[],"conflict":false}]
        """.trimIndent()
        val decoded = queueJson.decodeFromString<List<PendingEntry>>(old).single()

        assertEquals("the new state must be absent, not invented", emptyMap<String, String>(), decoded.unfiled)
        assertNull(decoded.danglingField)
        // THE LOAD-BEARING ONE. No evidence, no clearance: the replay omits the link columns and the
        // stored workshop stands. A build that read this silence as "unfile it" would strip a link on
        // a record nobody touched, under a 200, on the drain of a correction about something else.
        assertEquals(emptySet<String>(), decoded.clearedLinkKeys)
        assertEquals(emptySet<String>(), decoded.emptyPickerKeys)
        assertEquals(emptyList<String>(), decoded.danglingKeys)
        // And it still replays: the fields every earlier build did write are unchanged.
        assertEquals("artisan", decoded.type)
        assertNull(decoded.targetId)
        assertNull(decoded.createdId)
        assertNull(decoded.failure)
    }

    @Test
    fun `an old REFUSED row still draws the row it drew before, with no third button`() {
        val old = """
            [{"id":"old-2","type":"artisan","payloadJson":"{\"name\":\"x\",\"designWorkshopId\":\"dw_9\"}",
              "label":"Sushila Meher","media":[],"createdAt":"2026-08-14T09:00:00Z",
              "createdStepIds":[],"uploadedMedia":[],"conflict":false,
              "failure":"Record not found","failedAt":"2026-08-15T09:00:00Z"}]
        """.trimIndent()
        val row = outboxFailureRows(queueJson.decodeFromString<List<PendingEntry>>(old)).single()

        // The refusal is still listed, still verbatim, still retryable, still discardable — and it
        // gains no Re-pick, because this build has no evidence about WHY it was refused. The entry is
        // re-classified the next time the pass reaches it and answers, which is where the evidence
        // comes from; guessing here would put a picker over a row that may be a permission refusal.
        assertEquals("Record not found", row.reason)
        assertEquals(emptyList<String>(), row.danglingNouns)
        assertEquals(emptyList<String>(), row.repickKeys)
        assertFalse(row.conflict)
        assertFalse(row.awaitingUpdate)
    }

    @Test
    fun `a row written by this build round-trips through the queue file unchanged`() {
        // Written and read with the queue's own encoder, because a state that cannot survive
        // `OfflineOutbox.write` followed by `read` is a state that exists only until the app is next
        // opened — and the whole point of these three is that they outlive the screen that made them.
        val written = entry(
            unfiled = mapOf("designWorkshopId" to UNFILED_BY_CHOICE, "workshopId" to UNFILED_NO_OPTIONS),
            failure = "…",
            danglingField = "designWorkshopId,workshopId",
        )
        val text = queueJson.encodeToString(ListSerializer(PendingEntry.serializer()), listOf(written))
        val back = queueJson.decodeFromString<List<PendingEntry>>(text).single()
        assertEquals(written, back)
        assertEquals(setOf("designWorkshopId"), back.clearedLinkKeys)
        assertEquals(setOf("workshopId"), back.emptyPickerKeys)
        assertEquals(listOf("designWorkshopId", "workshopId"), back.danglingKeys)
    }

    @Test
    fun `an unfiled value this build does not recognise is ignored rather than acted on`() {
        // A newer build writing a third reason, read by this one after an APK downgrade — an ordinary
        // event in this fleet (`WorkshopDraftDowngradeTest`). A plain string decodes; an enum name
        // kotlinx cannot resolve would THROW, and `OfflineOutbox.read` can only read a throw as a
        // damaged queue file, which quarantines every unsent record on the device.
        val forward = """
            [{"id":"new-1","type":"artisan","payloadJson":"{\"name\":\"x\"}","label":"x",
              "media":[],"createdAt":"2026-09-01T09:00:00Z","createdStepIds":[],"uploadedMedia":[],
              "conflict":false,"unfiled":{"designWorkshopId":"somethingLaterThanThis"}}]
        """.trimIndent()
        val decoded = queueJson.decodeFromString<List<PendingEntry>>(forward).single()
        assertEquals(emptySet<String>(), decoded.clearedLinkKeys)
        assertEquals(emptySet<String>(), decoded.emptyPickerKeys)
        assertEquals(mapOf("designWorkshopId" to "somethingLaterThanThis"), decoded.unfiled)
    }

    // ── The reason the sentinel is needed at all ─────────────────────────────────────────────────

    @Test
    fun `the wire encoder drops a cleared workshop, which is why an explicit null has to be put back`() {
        // THE DEFECT, AS AN ASSERTION, in the shape `RecordPatchEncodingTest` pins its own. This is
        // what `WorkshopRepository.patchBodyWithClearances` exists to undo: `explicitNulls = false`
        // omits the key, `model_dump(exclude_unset=True)` reads the absence as "leave the stored value
        // alone", and the un-filing returns 200 having changed nothing.
        val cleared = ArtisanCreateRequest(name = "Giriraj Prasad", place = "Bhuj", designWorkshopId = null)
        val encoded = wireJson.encodeToString(ArtisanCreateRequest.serializer(), cleared)
        assertFalse(
            "if this ever starts appearing on its own, the clearance plumbing can be deleted:\n$encoded",
            encoded.contains("designWorkshopId"),
        )
        // And both columns are in the server's global clearable set, which is what makes putting the
        // null back effective rather than merely well-meant. `services/records.CLEARABLE_KEYS`.
        assertEquals(setOf("designWorkshopId", "workshopId"), WORKSHOP_LINK_KEYS)
    }
}
