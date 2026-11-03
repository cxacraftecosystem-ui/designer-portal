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

class OutboxConflictTest {

    private val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    private val artisanClash =
        """{"detail":{"code":"artisan_identity_conflict","field":"aadhaarNumber","maskedValue":""" +
            """"XXXX XXXX 4021","message":"Giriraj Prasad (Bhuj) is already recorded with this Aadhaar """ +
            """number. Open that artisan instead of creating a duplicate."}}"""

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

    @Test
    fun `every create route this outbox replays has its 409 recognised`() {
        assertTrue(isConflictRefusal(http(409, artisanClash)))
        assertTrue(isConflictRefusal(http(409, craftClash)))
        assertTrue(isConflictRefusal(http(409, interviewClash)))
    }

    @Test
    fun `nothing else in the triage table is mistaken for a clash`() {
        assertFalse("401 is the credential, not the record", isConflictRefusal(http(401, """{"detail":"x"}""")))
        assertFalse(isConflictRefusal(http(408, """{"detail":"x"}""")))
        assertFalse(isConflictRefusal(http(429, """{"detail":"x"}""")))
        assertFalse(isConflictRefusal(http(422, extraForbidden)))
        assertFalse(isConflictRefusal(http(422, fieldInvalid)))
        assertFalse(isConflictRefusal(http(403, """{"detail":"You may not create artisans."}""")))
        assertFalse(isConflictRefusal(http(404, """{"detail":"Not found"}""")))
        assertFalse(isConflictRefusal(http(500, """{"detail":"boom"}""")))
        assertFalse(isConflictRefusal(IOException("Unable to resolve host")))
        assertFalse(isConflictRefusal(IllegalStateException("Unknown offline entry type: wat")))
    }

    @Test
    fun `a clash is never stamped as a disagreement between builds`() {
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
        assertEquals(emptyList<OutboxFailureRow>(), outboxFailureRows(listOf(entry(conflict = true))))
        assertFalse(outboxFailureRows(listOf(entry(failure = "Name is too long"))).single().conflict)
        assertTrue(outboxFailureRows(listOf(entry(failure = "clash", conflict = true))).single().conflict)
    }

    @Test
    fun `the clash sentence says what happened, what is still here, and what to do about it`() {
        val said = http(409, artisanClash).apiRefusal("The server rejected this record.").message
        val sentence = outboxConflictSentence(said, files = 3, isCorrection = false)

        assertTrue(sentence, sentence.startsWith("Not saved —"))
        assertTrue(
            "the existing artisan must survive into the sentence by name:\n$sentence",
            sentence.contains("Giriraj Prasad (Bhuj) is already recorded with this Aadhaar number."),
        )
        assertTrue(sentence, sentence.contains("Nothing was deleted"))
        assertTrue(sentence, sentence.contains("this entry and the 3 files saved with it are still here"))
        assertTrue(sentence, sentence.contains("Retrying alone gets the same answer."))
        assertTrue(sentence, sentence.contains("Open the clashing record"))
        assertTrue(sentence, sentence.contains("copy anything missing"))
        assertTrue(sentence, sentence.contains("then discard this entry"))
    }

    @Test
    fun `the clash sentence stays a few clauses and not a paragraph`() {
        val said = http(409, artisanClash).apiRefusal("fallback").message
        val ours = outboxConflictSentence(said, files = 3, isCorrection = false).replace(said, "")
        assertTrue("our half of it is ${ours.length} characters:\n$ours", ours.length <= 280)

        val correction = outboxConflictSentence(said, files = 3, isCorrection = true).replace(said, "")
        assertTrue("our half of it is ${correction.length} characters:\n$correction", correction.length <= 340)
    }

    @Test
    fun `the clash sentence never claims the record reached the server`() {
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

    @Test
    fun `the discard confirmation on a clash says which record goes and which stays`() {
        val warning = outboxDiscardConfirmation("Giriraj Prasad", files = 4, isConflict = true)

        assertTrue(warning.contains("“Giriraj Prasad” and the 4 files saved with it will be deleted"))
        assertTrue(warning.contains("This cannot be undone"))
        assertTrue(warning, warning.contains("The record it clashes with is not touched"))
        assertTrue(warning, warning.contains("What goes is this phone's copy"))
        assertTrue(warning, warning.contains("anything in it the other record does not have goes too"))
        assertTrue(warning, warning.contains("Check that first"))
    }

    @Test
    fun `the confirmation for an ordinary refusal is unchanged and says nothing about a clash`() {
        val warning = outboxDiscardConfirmation("Kutch mirror work", files = 1, isConflict = false)
        assertEquals(
            "“Kutch mirror work” and the 1 file saved with it will be deleted from this phone. " +
                "This cannot be undone, and none of it has been uploaded.",
            warning,
        )
    }

    @Test
    fun `the confirmation on a saved-but-files-refused row does not claim the record is unsent`() {
        val warning = outboxDiscardConfirmation(
            "Giriraj Prasad",
            files = 2,
            isConflict = false,
            savedOnServer = true,
        )
        assertFalse(
            "the one sentence this arm exists to stop:\n$warning",
            warning.contains("none of it has been uploaded"),
        )
        assertEquals(
            "“Giriraj Prasad” and the 2 files saved with it will be deleted from this phone. This " +
                "cannot be undone. The record is already online; re-entering it would duplicate it. " +
                "The 2 files weren't uploaded — re-attach them if you can.",
            warning,
        )
    }

    @Test
    fun `the saved arm is reached from the row the tray actually builds`() {
        val saved = entry(failure = "It was saved, but 1 file(s) were refused.", createdId = "artisan-77")
        assertTrue(outboxFailureRows(listOf(saved)).single().savedOnServer)
        assertFalse(outboxFailureRows(listOf(entry(failure = "Name is too long"))).single().savedOnServer)
    }

    @Test
    fun `an entry with no files is not offered a count of them`() {
        assertEquals(
            "“Rabari embroidery” will be deleted from this phone. This cannot be undone, and " +
                "none of it has been uploaded.",
            outboxDiscardConfirmation("Rabari embroidery", files = 0, isConflict = false),
        )
    }

    @Test
    fun `marking a clash keeps the payload and every staged file`() {
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
        val clashing = entry(failure = "clash", conflict = true, media = listOf(staged("IMG_0001.jpg")))
        val retried = clashing.copy(failure = null, failedAt = null, skewRun = null, conflict = false)

        assertFalse(retried.conflict)
        assertEquals("and it is not a row at all any more", emptyList<OutboxFailureRow>(), outboxFailureRows(listOf(retried)))
        assertFalse("and it is waiting again rather than parked", blocksRetry(retried.failure != null, retried.skewRun))
        assertEquals("nothing about the entry itself moved", clashing.media, retried.media)
    }

    @Test
    fun `the flag survives a write and a read of the queue file`() {
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
        val onDisk = """{"id":"e1","type":"artisan","payloadJson":"{}","label":"Giriraj","createdAt":"x",""" +
            """"failure":"The server rejected this record.","failedAt":"2026-08-08T10:00:00Z"}"""
        val decoded = queueJson.decodeFromString(PendingEntry.serializer(), onDisk)

        assertFalse(decoded.conflict)
        assertFalse(outboxFailureRows(listOf(decoded)).single().conflict)
        assertTrue("and it still waits for a person, exactly as it did", blocksRetry(decoded.failure != null, decoded.skewRun))
    }
}
