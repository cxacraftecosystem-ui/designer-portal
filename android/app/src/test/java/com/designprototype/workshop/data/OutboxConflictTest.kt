package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * A 409 IS ITS OWN ANSWER, AND THIS PHONE HAD NO WORD FOR IT.
 *
 * ── WHAT WAS ACTUALLY WRONG ───────────────────────────────────────────────────────────────────────
 *
 * `replayEntry` asked two questions of a failed create — is it worth retrying, and is it a dialect
 * mismatch — and everything that answered no to both came out as one `ReplayOutcome.Rejected`. So a
 * name that is 300 characters long, a permission this account does not hold, and THE REGISTER ALREADY
 * HOLDING THE ARTISAN WHOSE AADHAAR WAS TYPED were written into the queue as the same kind of thing,
 * printed under the same heading, and offered the same two buttons: a Try again that could only ever
 * fetch the identical 409, and a red one that deletes a day of fieldwork and its photographs.
 *
 * Only the third of those has a way forward, and it is the only one the app never described. The web
 * has had a dedicated branch since the incident (`frontend/lib/offline.ts`, the `status === 409` arm
 * of `runSync`), closing with *"Open the record it clashes with, carry across anything it is missing,
 * then discard this entry"*. These tests are that behaviour on the handset: the classification, the
 * sentence, and the invariant underneath both.
 *
 * ── THE INVARIANT UNDERNEATH ──────────────────────────────────────────────────────────────────────
 *
 * `frontend/lib/offline.ts` opens by naming the incident these tests exist to keep closed: the web
 * outbox "used to read a 409 as 'the create already landed and we simply lost the response', and drop
 * the entry and its files as sent … So the one answer that means 'someone else's record collides with
 * yours' was destroying the record AND the photographs and reporting success." Nothing on this path
 * may delete. `PendingEntry.createdId` — which KNOWS whether the create landed — is the only thing
 * allowed to reason about a lost response, and `OfflineOutbox.discard` is the only thing allowed to
 * delete, on a person's explicit instruction.
 *
 * ── THE BODIES ARE REAL ───────────────────────────────────────────────────────────────────────────
 *
 * Copied from the routes this outbox replays. TRUE AS OF 2026-08-27, re-check with:
 *
 *     grep -rn "HTTP_409_CONFLICT" backend/app/api/routes/
 *
 * `artisans.py::_identity_conflict` builds the object one; `crafts.py` the bare string;
 * `questionnaire.py::_DUPLICATE_SET_DETAIL` the interview one, verbatim.
 *
 * ── THE WORDS MOVED ON 2026-09-03; THE FACTS DID NOT ──────────────────────────────────────────────
 *
 * Every sentence here was rewritten to state its fact and stop: the reasoning that used to travel
 * with each clause — *"because what is in the way is not on this phone"*, *"and only then"* — now
 * lives in `outboxConflictSentence`'s KDoc. These tests moved with it and are deliberately written as
 * structural assertions about WHICH FACTS MUST APPEAR rather than as whole-string equality, so the
 * next person shortening a clause is stopped only when they drop something a designer acts on. The
 * two exact-equality assertions that remain are on the discard dialog, where the sentence is the last
 * thing read before an irreversible delete and every word of it is load-bearing.
 */
class OutboxConflictTest {

    /** The exact reader `OfflineOutbox` uses, so a decoding claim here is a claim about the queue. */
    private val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    private val artisanClash =
        """{"detail":{"code":"artisan_identity_conflict","field":"aadhaarNumber","maskedValue":""" +
            """"XXXX XXXX 4021","message":"Giriraj Prasad (Bhuj) is already recorded with this Aadhaar """ +
            """number. Open that artisan instead of creating a duplicate."}}"""

    /** No full stop, on purpose: this is what `crafts.py` actually sends. */
    private val craftClash = """{"detail":"Craft name already exists"}"""

    private val interviewClash =
        """{"detail":"An interview already exists for this exact set of artisans. There is a single """ +
            """shared entry per artisan set — open it to add or view answers instead of creating another."}"""

    private val extraForbidden =
        """{"detail":[{"type":"extra_forbidden","loc":["body","merge"],""" +
            """"msg":"Extra inputs are not permitted","input":true}]}"""

    private val fieldInvalid =
        """{"detail":[{"type":"string_too_long","loc":["body","name"],""" +
            """"msg":"String should have at most 200 characters"}]}"""

    private fun entry(
        failure: String? = null,
        skewRun: String? = null,
        conflict: Boolean = false,
        media: List<PendingMedia> = emptyList(),
        targetId: String? = null,
        createdId: String? = null,
    ) = PendingEntry(
        id = "entry-1",
        type = "artisan",
        payloadJson = """{"name":"Giriraj Prasad","aadhaarNumber":"XXXXXXXX4021"}""",
        label = "Giriraj Prasad",
        createdAt = "2026-08-27T10:00:00Z",
        media = media,
        targetId = targetId,
        createdId = createdId,
        failure = failure,
        skewRun = skewRun,
        conflict = conflict,
    )

    private fun staged(name: String) = PendingMedia(
        localPath = "/data/user/0/app/files/outbox/media/$name",
        originalFilename = name,
        mimeType = "image/jpeg",
        mediaType = "IMAGE",
    )

    // ── The classification ───────────────────────────────────────────────────────────────────────

    @Test
    fun `every create route this outbox replays has its 409 recognised`() {
        // Three different body SHAPES — an object with a code, a bare string, a sentence — because the
        // status is what is read and the body is only ever printed. A client that branched on the body
        // would recognise the artisan clash and quietly mis-file the other two.
        assertTrue(isConflictRefusal(http(409, artisanClash)))
        assertTrue(isConflictRefusal(http(409, craftClash)))
        assertTrue(isConflictRefusal(http(409, interviewClash)))
    }

    @Test
    fun `nothing else in the triage table is mistaken for a clash`() {
        // The three the connection owns. Marking any of these a clash would tell a designer standing
        // in a courtyard with no bars to go and open a record that does not exist.
        assertFalse("401 is the credential, not the record", isConflictRefusal(http(401, """{"detail":"x"}""")))
        assertFalse(isConflictRefusal(http(408, """{"detail":"x"}""")))
        assertFalse(isConflictRefusal(http(429, """{"detail":"x"}""")))
        // The two 422s, which are the pair `OutboxSchemaSkewRetryTest` spends itself on.
        assertFalse(isConflictRefusal(http(422, extraForbidden)))
        assertFalse(isConflictRefusal(http(422, fieldInvalid)))
        // A permission and a missing record are refusals a person acts on, but not by comparing two
        // records — the remedy the clash sentence sends them to would be nonsense for either.
        assertFalse(isConflictRefusal(http(403, """{"detail":"You may not create artisans."}""")))
        assertFalse(isConflictRefusal(http(404, """{"detail":"Not found"}""")))
        assertFalse(isConflictRefusal(http(500, """{"detail":"boom"}""")))
        // And the two shapes that never carry a status at all.
        assertFalse(isConflictRefusal(IOException("Unable to resolve host")))
        assertFalse(isConflictRefusal(IllegalStateException("Unknown offline entry type: wat")))
    }

    @Test
    fun `a clash is never stamped as a disagreement between builds`() {
        // `skewRun` means "an UPDATE to either side clears this, so try it again next app run". A
        // clash is cleared by a PERSON, so stamping it would re-POST the same losing create once per
        // app open for the life of the installation — against a prepaid connection, and every answer
        // the identical 409.
        val refusal = http(409, artisanClash).apiRefusal("The server rejected this record.")
        assertFalse("only a 422 carrying extra_forbidden is a skew", refusal.schemaSkew)

        val queued = entry(
            failure = outboxConflictSentence(refusal.message, files = 3, isCorrection = false),
            skewRun = if (refusal.schemaSkew) APP_RUN else null,
            conflict = true,
        )
        assertNull(queued.skewRun)
        assertTrue(
            "the automatic pass must park a clash for a person, exactly as it parks any other answered refusal",
            blocksRetry(queued.failure != null, queued.skewRun),
        )
    }

    @Test
    fun `the flag is not the failure and the failure is not the flag`() {
        // Both halves are needed and neither implies the other. `failure` is what STOPS the pass;
        // `conflict` is what the tray draws. An entry carrying the flag with no refusal recorded is
        // not a clash the screen should be showing — it is an entry a person has just asked to retry,
        // caught mid-way through `clearFailure` — and it is not a ROW either, which is the stronger
        // statement `outboxFailureRows` makes and the reason the flag rides on the row.
        assertEquals(emptyList<OutboxFailureRow>(), outboxFailureRows(listOf(entry(conflict = true))))
        assertFalse(outboxFailureRows(listOf(entry(failure = "Name is too long"))).single().conflict)
        assertTrue(outboxFailureRows(listOf(entry(failure = "clash", conflict = true))).single().conflict)
    }

    // ── The sentence ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the clash sentence says what happened, what is still here, and what to do about it`() {
        val said = http(409, artisanClash).apiRefusal("The server rejected this record.").message
        val sentence = outboxConflictSentence(said, files = 3, isCorrection = false)

        // 1. NOT SAVED, and said first. A refusal a designer half-reads is a refusal they assume went
        //    through in the end.
        assertTrue(sentence, sentence.startsWith("Not saved —"))
        // 2. THE SERVER'S OWN WORDS, VERBATIM — which are the only thing on the screen naming the
        //    record they have to go and find. THE ONE CLAUSE THE TERSE PASS DID NOT TOUCH, and the
        //    only one it may never touch: summarising it would throw away the whole remedy.
        assertTrue(
            "the existing artisan must survive into the sentence by name:\n$sentence",
            sentence.contains("Giriraj Prasad (Bhuj) is already recorded with this Aadhaar number."),
        )
        // 3. NOTHING DELETED, and how much is riding on it.
        assertTrue(sentence, sentence.contains("Nothing was deleted"))
        assertTrue(sentence, sentence.contains("this entry and the 3 files saved with it are still here"))
        // 4. RETRYING ALONE CANNOT WORK. Without this the designer walks up the hill to find a signal
        //    and does it again tomorrow. WHY it cannot work now lives in the KDoc, not on the row —
        //    the fact is what a person acts on, the reasoning is what a maintainer checks.
        assertTrue(sentence, sentence.contains("Retrying alone gets the same answer."))
        // 5. AN ORDER OF OPERATIONS THAT ENDS SOMEWHERE — the web's clause, in this app's voice.
        assertTrue(sentence, sentence.contains("Open the clashing record"))
        assertTrue(sentence, sentence.contains("copy anything missing"))
        assertTrue(sentence, sentence.contains("then discard this entry"))
    }

    /**
     * TERSE, AND MEASURABLY SO — because "we shortened it" is a claim that decays without one.
     *
     * The sentence that shipped ran to four long clauses carrying their own justifications, read by
     * somebody standing in a courtyard deciding whether to press a button that deletes the only copy
     * of a day's fieldwork. Every fact above still has to be in it; what may not come back is the
     * argument for each one, which is in `outboxConflictSentence`'s KDoc where it can be checked and
     * cannot be skimmed past.
     *
     * The bound is on OUR words only: the server's `detail` is quoted verbatim and can be any length,
     * so it is subtracted before measuring. Being generous is the point — this trips on a paragraph
     * growing back, not on a word.
     */
    @Test
    fun `the clash sentence stays a few clauses and not a paragraph`() {
        val said = http(409, artisanClash).apiRefusal("fallback").message
        // The ceilings are the shipped lengths with room to breathe, not a squeeze: the sentence this
        // replaced measured about 412 characters of our own words, and the correction arm more.
        val ours = outboxConflictSentence(said, files = 3, isCorrection = false).replace(said, "")
        assertTrue("our half of it is ${ours.length} characters:\n$ours", ours.length <= 280)

        val correction = outboxConflictSentence(said, files = 3, isCorrection = true).replace(said, "")
        assertTrue("our half of it is ${correction.length} characters:\n$correction", correction.length <= 340)
    }

    @Test
    fun `the clash sentence never claims the record reached the server`() {
        // THE INCIDENT, AS AN ASSERTION. The misreading that destroyed a queued record and its
        // photographs was "the create already landed and we simply lost the response". Nothing in the
        // sentence a designer reads may imply it, because the act it would invite is the delete.
        val sentence = outboxConflictSentence(
            http(409, interviewClash).apiRefusal("fallback").message,
            files = 8,
            isCorrection = false,
        )
        for (forbidden in listOf("already sent", "was saved", "has been saved", "already saved", "was uploaded")) {
            assertFalse("\"$forbidden\" appeared in:\n$sentence", sentence.contains(forbidden))
        }
        assertTrue("and it is explicit about the files:\n$sentence", sentence.contains("8 files"))
    }

    @Test
    fun `a correction that clashes gets the standing fact only a correction has`() {
        // A queued edit is not a queued create. There is nothing to "carry across" — the record
        // exists — and the office is meanwhile reading the version from before the correction, which
        // `offlineSavedMessage` already promises the designer at the moment they queue it. The clash
        // sentence has to keep that promise rather than quietly drop it.
        val sentence = outboxConflictSentence(
            http(409, artisanClash).apiRefusal("fallback").message,
            files = 1,
            isCorrection = true,
        )
        assertTrue(sentence, sentence.startsWith("Not applied —"))
        assertTrue(sentence, sentence.contains("the office still reads the earlier version"))
        assertTrue(sentence, sentence.contains("make the change there"))
        assertFalse(
            "there is nothing to copy into a record that already holds it:\n$sentence",
            sentence.contains("copy"),
        )
        assertTrue("one file is one file, not '1 files':\n$sentence", sentence.contains("the 1 file saved with it"))
    }

    @Test
    fun `a clash on an entry with no attachments does not invent a file clause`() {
        val sentence = outboxConflictSentence(
            http(409, craftClash).apiRefusal("fallback").message,
            files = 0,
            isCorrection = false,
        )
        assertFalse("\"0 files\" reads as an accusation that something went missing:\n$sentence", sentence.contains("0 file"))
        assertTrue(sentence, sentence.contains("this entry is still here"))
    }

    @Test
    fun `a server sentence with no full stop does not run into ours`() {
        // `crafts.py` sends "Craft name already exists" — no punctuation. Left alone it produced
        // "…already holds a clashing record. Craft name already exists Nothing was deleted…", one
        // unpunctuated clause a designer skims and abandons before the half that tells them what to
        // do. The same defect `WorkshopSync.refusal()` fixed for an answered 5xx.
        val sentence = outboxConflictSentence(
            http(409, craftClash).apiRefusal("fallback").message,
            files = 2,
            isCorrection = false,
        )
        assertTrue(sentence, sentence.contains("Craft name already exists. Nothing was deleted"))
        assertFalse(sentence, sentence.contains("already exists Nothing"))
    }

    @Test
    fun `a clash does not read like the two refusals it used to be filed with`() {
        // The regression this whole change exists to prevent: five kinds of "not synced" collapsed
        // into one FAILED. These three are what a designer sees, and no two of them may be the same
        // sentence.
        val said = http(409, artisanClash).apiRefusal("The server rejected this record.").message
        val clash = outboxConflictSentence(said, files = 2, isCorrection = false)
        val plain = http(422, fieldInvalid).apiRefusal("The server rejected this record.").message
        val skew = skewSentence(
            "What this copy of the app sent for this record",
            http(422, extraForbidden).apiRefusal("fallback").message,
        )

        assertNotEquals(clash, plain)
        assertNotEquals(clash, skew)
        assertFalse(
            "the skew sentence promises the app will send it by itself; a clash never will:\n$clash",
            clash.contains("it will be sent by itself"),
        )
        assertFalse(
            "and it must not tell the designer their entry is nobody's fault and needs no action:\n$clash",
            clash.contains("you do not have to do anything"),
        )
    }

    // ── The last thing said before anything is deleted ───────────────────────────────────────────

    @Test
    fun `the discard confirmation on a clash says which record goes and which stays`() {
        val warning = outboxDiscardConfirmation("Giriraj Prasad", files = 4, isConflict = true)

        assertTrue(warning.contains("“Giriraj Prasad” and the 4 files saved with it will be deleted"))
        assertTrue(warning.contains("This cannot be undone"))
        // The fear this arm exists for: the row NAMES an artisan on the server, so an unqualified
        // "this cannot be undone" over it reads as an offer to delete that artisan.
        assertTrue(warning, warning.contains("The record it clashes with is not touched"))
        // And the half that is a warning rather than a reassurance: what is being deleted is this
        // phone's copy, and what is IN it that the other record has not.
        assertTrue(warning, warning.contains("What goes is this phone's copy"))
        assertTrue(warning, warning.contains("anything in it the other record does not have goes too"))
        assertTrue(warning, warning.contains("Check that first"))
    }

    @Test
    fun `the confirmation for an ordinary refusal is unchanged and says nothing about a clash`() {
        val warning = outboxDiscardConfirmation("Kutch mirror work", files = 1, isConflict = false)
        assertEquals(
            "“Kutch mirror work” and the 1 file saved with it will be deleted from this device. " +
                "This cannot be undone, and nothing about it has reached the server.",
            warning,
        )
    }

    @Test
    fun `the confirmation on a saved-but-files-refused row does not claim the record is unsent`() {
        // Kind 5 of the six on `PendingEntry.createdId`: `replayEntry` wrote the record, two uploads
        // were refused, and the tray's own reason line for this row is the server's "It was saved,
        // but 2 file(s) were refused… Re-attach them on the record." The dialog over it used to say
        // the opposite — and a designer who believes the dialog re-enters a record the register
        // already holds, while the only copy of the two photographs goes with the entry.
        val warning = outboxDiscardConfirmation(
            "Giriraj Prasad",
            files = 2,
            isConflict = false,
            savedOnServer = true,
        )
        assertFalse(
            "the one sentence this arm exists to stop:\n$warning",
            warning.contains("nothing about it has reached the server"),
        )
        assertEquals(
            "“Giriraj Prasad” and the 2 files saved with it will be deleted from this device. This " +
                "cannot be undone. The record is already on the server and stays there — entering it " +
                "again would leave two of it. The 2 files are the part the server never got — attach " +
                "them to the record there instead, if you still can.",
            warning,
        )
    }

    @Test
    fun `the saved arm is reached from the row the tray actually builds`() {
        // The projection is the half a wording fix alone would have missed: the dialog can only
        // branch on what `outboxFailureRows` carried out of the entry.
        val saved = entry(failure = "It was saved, but 1 file(s) were refused.", createdId = "artisan-77")
        assertTrue(outboxFailureRows(listOf(saved)).single().savedOnServer)
        assertFalse(outboxFailureRows(listOf(entry(failure = "Name is too long"))).single().savedOnServer)
    }

    @Test
    fun `an entry with no files is not offered a count of them`() {
        assertEquals(
            "“Rabari embroidery” will be deleted from this device. This cannot be undone, and " +
                "nothing about it has reached the server.",
            outboxDiscardConfirmation("Rabari embroidery", files = 0, isConflict = false),
        )
    }

    // ── What is on the phone, and what stays on it ───────────────────────────────────────────────

    @Test
    fun `marking a clash keeps the payload and every staged file`() {
        // The invariant, at the layer that could break it. `markFailure` writes four fields with
        // `copy`; nothing about the entry's own contents may move. If a future edit ever reaches for
        // the media list here, this is the test that says why it must not: those bytes are the only
        // copy, and the artisan has gone home.
        val queued = entry(media = listOf(staged("IMG_0001.jpg"), staged("IMG_0002.jpg")))
        val marked = queued.copy(
            failure = outboxConflictSentence("Already recorded.", files = 2, isCorrection = false),
            failedAt = "2026-08-27T10:05:00Z",
            skewRun = null,
            conflict = true,
        )

        assertEquals(queued.payloadJson, marked.payloadJson)
        assertEquals(queued.media, marked.media)
        assertEquals(2, marked.media.size)
        assertNull("a clash is not a create that landed", marked.createdId)
    }

    @Test
    fun `a person retrying a clash clears the flag with the refusal, and keeps the work`() {
        // What `clearFailure` writes. The designer taps Try again because they have just deleted the
        // duplicate at the office, so the NEXT answer decides what this entry is — a stale flag would
        // leave the tray telling them to go and open a record the server no longer objects to.
        val clashing = entry(failure = "clash", conflict = true, media = listOf(staged("IMG_0001.jpg")))
        val retried = clashing.copy(failure = null, failedAt = null, skewRun = null, conflict = false)

        assertFalse(retried.conflict)
        assertEquals("and it is not a row at all any more", emptyList<OutboxFailureRow>(), outboxFailureRows(listOf(retried)))
        assertFalse("and it is waiting again rather than parked", blocksRetry(retried.failure != null, retried.skewRun))
        assertEquals("nothing about the entry itself moved", clashing.media, retried.media)
    }

    @Test
    fun `the flag survives a write and a read of the queue file`() {
        // `encodeDefaults = true`, so it is really on disk. A flag that vanished when the process died
        // would put the clash back in the tray as an ordinary refusal on the next app open — the
        // designer reading two different stories about one entry on two mornings.
        val stamped = entry(failure = "clash", conflict = true, media = listOf(staged("IMG_0001.jpg")))
        val round = queueJson.decodeFromString(
            PendingEntry.serializer(),
            queueJson.encodeToString(PendingEntry.serializer(), stamped)
        )

        assertTrue(round.conflict)
        assertEquals(stamped.media, round.media)
        assertTrue(outboxFailureRows(listOf(round)).single().conflict)
    }

    @Test
    fun `an entry queued by a build that predates this field decodes as an ordinary refusal`() {
        // The safe direction, and the reason the field is defaulted rather than required: the queue on
        // a handset that has been out of coverage for a fortnight was written by the build installed a
        // fortnight ago, and nothing in it may be re-described by an update it has never seen.
        val onDisk = """{"id":"e1","type":"artisan","payloadJson":"{}","label":"Giriraj","createdAt":"x",""" +
            """"failure":"The server rejected this record.","failedAt":"2026-08-08T10:00:00Z"}"""
        val decoded = queueJson.decodeFromString(PendingEntry.serializer(), onDisk)

        assertFalse(decoded.conflict)
        assertFalse(outboxFailureRows(listOf(decoded)).single().conflict)
        assertTrue("and it still waits for a person, exactly as it did", blocksRetry(decoded.failure != null, decoded.skewRun))
    }
}
