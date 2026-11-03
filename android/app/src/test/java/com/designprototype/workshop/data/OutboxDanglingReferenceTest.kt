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
import java.io.File
import java.io.IOException

class OutboxDanglingReferenceTest {

    private val queueJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val wireJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    private val notFound = """{"detail":"Record not found"}"""

    private val namedMissing =
        """{"detail":[{"type":"value_error","loc":["body","designWorkshopId"],""" +
            """"msg":"Value error, That design workshop does not exist."}]}"""

    private val extraForbidden =
        """{"detail":[{"type":"extra_forbidden","loc":["body","merge"],""" +
            """"msg":"Extra inputs are not permitted","input":true}]}"""

    private val artisanClash =
        """{"detail":{"code":"artisan_identity_conflict","message":"Giriraj Prasad (Bhuj) is already """ +
            """recorded with this Aadhaar number."}}"""

    private val bothLinks =
        """{"name":"Giriraj Prasad","place":"Bhuj","workshopId":"wk_1","designWorkshopId":"dw_9"}"""

    private val oneLink = """{"name":"Giriraj Prasad","place":"Bhuj","designWorkshopId":"dw_9"}"""

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

    @Test
    fun `a missing reference is recognised and nothing else in the triage table is`() {
        assertTrue("require_record's 404", isMissingReferenceRefusal(http(404, notFound)))
        assertTrue("a validator's 422", isMissingReferenceRefusal(http(422, namedMissing)))

        assertFalse(isMissingReferenceRefusal(http(401, notFound)))
        assertFalse(isMissingReferenceRefusal(http(408, notFound)))
        assertFalse(isMissingReferenceRefusal(http(429, notFound)))
        assertFalse(isMissingReferenceRefusal(http(500, notFound)))
        assertFalse(isMissingReferenceRefusal(http(409, artisanClash)))
        assertFalse(isConflictRefusal(http(404, notFound)))
        assertFalse(isMissingReferenceRefusal(IOException("Unable to resolve host")))
        assertFalse(isMissingReferenceRefusal(IllegalStateException("Unknown offline entry type: wat")))
    }

    @Test
    fun `a queued save naming a workshop the designer is not on, or one that is gone, still offers a re-pick`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "backend").isDirectory }
        val gate = File(root, "backend/app/services/record_design_workshop.py").readText()
        val said = Regex("""NOT_ON_WORKSHOP = \(\s*"([^"]+)"\s*\)""").find(gate)?.groupValues?.get(1)
        assertTrue("NOT_ON_WORKSHOP is one string literal", said != null)
        assertTrue(gate.contains("status_code=status.HTTP_404_NOT_FOUND, detail=NOT_ON_WORKSHOP"))
        assertFalse(gate.contains("HTTP_403_FORBIDDEN, detail=NOT_ON_WORKSHOP"))

        val body = """{"detail":"$said"}"""
        assertTrue(isMissingReferenceRefusal(http(404, body)))
        assertEquals(listOf("designWorkshopId"), danglingReferenceCandidates(payload = oneLink))
        val sentence = outboxDanglingSentence(
            said = http(404, body).apiRefusal("The server rejected this record.").message,
            nouns = listOf(referenceFieldNoun("designWorkshopId", recordNoun = outboxRecordNoun("artisan"))),
            files = 2,
            isCorrection = false,
        )
        assertTrue(sentence, sentence.startsWith("This record points at a design & prototype workshop"))
        assertTrue(sentence, sentence.contains(said!!))
        assertFalse(isMissingReferenceRefusal(http(403, body)))
    }

    @Test
    fun `a build disagreement is never mistaken for a missing reference`() {
        val skew = http(422, extraForbidden).apiRefusal("The server rejected this record.")
        assertTrue(skew.schemaSkew)
        val missing = http(422, namedMissing).apiRefusal("The server rejected this record.")
        assertFalse("a validator's refusal is not a dialect mismatch", missing.schemaSkew)
    }

    @Test
    fun `the server's own field name wins over anything read off the payload`() {
        val refusal = http(422, namedMissing).apiRefusal("fallback")
        assertEquals(listOf("designWorkshopId"), refusal.namedFields)
        assertEquals(
            listOf("designWorkshopId"),
            danglingReferenceCandidates(payload = bothLinks, named = refusal.namedFields),
        )
        val nested = http(
            422,
            """{"detail":[{"type":"value_error","loc":["body","steps",0,"productId"],"msg":"x"}]}""",
        ).apiRefusal("fallback")
        assertEquals(listOf("steps", "productId"), nested.namedFields)
        assertFalse("\"body\" must never reach the caller", "body" in nested.namedFields)
    }

    @Test
    fun `a 404 that names no field offers every id the record actually sent, and only those`() {
        assertEquals(
            listOf("designWorkshopId", "workshopId"),
            danglingReferenceCandidates(payload = bothLinks),
        )
        assertEquals(listOf("designWorkshopId"), danglingReferenceCandidates(payload = oneLink))
        assertEquals(emptyList<String>(), danglingReferenceCandidates(payload = noLinks))
        assertEquals(
            emptyList<String>(),
            danglingReferenceCandidates(payload = """{"name":"x","designWorkshopId":""}"""),
        )
        assertEquals(
            emptyList<String>(),
            danglingReferenceCandidates(payload = """{"name":"x","designWorkshopId":null}"""),
        )
        assertEquals(emptyList<String>(), danglingReferenceCandidates(payload = "not json at all"))
    }

    @Test
    fun `a correction puts the record it corrects among the suspects`() {
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

    @Test
    fun `the three absences are three states on the entry and not one`() {
        val chosen = entry(unfiled = mapOf("designWorkshopId" to UNFILED_BY_CHOICE))
        val nothingToChoose = entry(unfiled = mapOf("designWorkshopId" to UNFILED_NO_OPTIONS))
        val dangling = entry(failure = "…", danglingField = "designWorkshopId")

        assertEquals(setOf("designWorkshopId"), chosen.clearedLinkKeys)
        assertEquals(emptySet<String>(), chosen.emptyPickerKeys)
        assertEquals(emptySet<String>(), nothingToChoose.clearedLinkKeys)
        assertEquals(setOf("designWorkshopId"), nothingToChoose.emptyPickerKeys)
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

        assertTrue(dangling.contains("points at a design & prototype workshop that could not be found"))
        assertTrue(dangling, dangling.contains("still here"))
        assertTrue(sentUnfiled.contains("was sent"))
        assertFalse("a record that has gone is not still here:\n$sentUnfiled", sentUnfiled.contains("still here"))
        assertFalse("\n$dangling", dangling.contains("clashes with"))
        assertFalse("\n$dangling", dangling.contains("copy anything missing"))
        assertFalse("\n$clash", clash.contains("points at"))
        for (formWords in listOf("has not received", "there is nothing to pick here", "can be saved without it")) {
            assertFalse("\"$formWords\" belongs on a form, not in the tray:\n$dangling", dangling.contains(formWords))
            assertFalse("\"$formWords\" belongs on a form, not in the tray:\n$sentUnfiled", sentUnfiled.contains(formWords))
        }
    }

    @Test
    fun `the dangling sentence names the field, what is still here, and that a bare retry cannot work`() {
        val sentence = outboxDanglingSentence(
            said = http(404, notFound).apiRefusal("The server rejected this record.").message,
            nouns = listOf("design & prototype workshop"),
            files = 3,
            isCorrection = false,
        )
        assertTrue(sentence.startsWith("This record points at a design & prototype workshop that could not be found."))
        assertTrue(sentence.contains("Nothing is lost — open it, choose another, and it will send."))
        assertTrue(sentence.contains(" Record not found."))
        assertTrue(sentence, sentence.contains("This entry and the 3 files saved with it are still here"))
        assertTrue(sentence, sentence.contains("nothing was deleted"))
        assertTrue(sentence, sentence.contains("Retrying unchanged gets the same answer."))
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
        assertTrue(sentence.contains("It is a workshop or an artisan."))
        assertFalse("\"0 files\" reads as an accusation:\n$sentence", sentence.contains("0 file"))
        assertTrue(sentence, sentence.contains("This entry is still here"))
        assertFalse(sentence.contains("a artisan"))
    }

    @Test
    fun `a 422 about a value is never dressed up as a missing reference`() {
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
        assertEquals(
            listOf("designWorkshopId"),
            danglingReferenceCandidates(
                payload = bothLinks,
                named = http(422, namedMissing).apiRefusal("x").namedFields,
                isCorrection = true,
                namedOnly = true,
            ),
        )
        assertEquals(
            listOf(TARGET_RECORD_KEY, "designWorkshopId", "workshopId"),
            danglingReferenceCandidates(payload = bothLinks, isCorrection = true, namedOnly = false),
        )
    }

    @Test
    fun `a correction whose record is gone names the record and offers no picker`() {
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
        val sentence = outboxDanglingSentence(
            said = "Record not found",
            nouns = listOf("workshop"),
            files = 0,
            isCorrection = false,
        )
        assertTrue(sentence, sentence.contains("Record not found. This entry is still here"))
    }

    @Test
    fun `the sent-unfiled message says which absence it was and what to do next`() {
        val message = outboxSentUnfiledMessage("Giriraj Prasad", listOf("design & prototype workshop"))
        assertTrue(message, message.startsWith("“Giriraj Prasad” was sent, filed under nothing"))
        assertTrue(message.contains("there was no design & prototype workshop to choose from on this device"))
        assertFalse("“no a workshop” is a seam a reader learns to skim: $message", message.contains("no a "))
        assertTrue(message, message.contains("Open the record and file it now"))
        assertTrue("one line on a success notification: $message", message.length <= 200)
    }

    @Test
    fun `an empty re-pick list says WHICH empty it is`() {
        val failed = repickEmptyLine("design & prototype workshop", listed = false)
        val none = repickEmptyLine("design & prototype workshop", listed = true)
        assertTrue(failed.contains("could not be read just now"))
        assertFalse("a failed read may never assert a scope is empty:\n$failed", failed.contains("open to this account"))
        assertTrue(none.contains("open to this account"))
        assertTrue(none.contains("An administrator can give you access to one."))
        assertTrue(failed.contains("stay on this phone"))
        assertTrue(none.contains("nothing is deleted"))
    }

    @Test
    fun `the row names the field in words and offers a re-pick only where there is a list`() {
        val row = outboxFailureRows(
            listOf(entry(failure = "…", danglingField = "designWorkshopId", media = listOf(staged("a.jpg"))))
        ).single()
        assertEquals(listOf("design & prototype workshop"), row.danglingNouns)
        assertEquals(listOf("designWorkshopId"), row.repickKeys)
        assertFalse("a dangling id is not a clash", row.conflict)
        assertFalse("and nothing of ours was written", row.savedOnServer)

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

        val correction = outboxFailureRows(
            listOf(entry(failure = "…", danglingField = "$TARGET_RECORD_KEY,designWorkshopId", targetId = "ar_1"))
        ).single()
        assertEquals(listOf("artisan it corrects", "design & prototype workshop"), correction.danglingNouns)
        assertEquals(listOf("designWorkshopId"), correction.repickKeys)
    }

    @Test
    fun `the flag is not the failure, on this outcome as on the clash`() {
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
        assertTrue(warning.contains("none of it has been uploaded"))
        val ordinary = outboxDiscardConfirmation("Giriraj Prasad", files = 3, isConflict = false)
        assertFalse(ordinary.contains("Re-pick"))
    }

    @Test
    fun `an old queued row decodes into exactly the behaviour it was queued under`() {
        val old = """
            [{"id":"old-1","type":"artisan","payloadJson":"{\"name\":\"Sushila Meher\"}",
              "label":"Sushila Meher","media":[],"createdAt":"2026-08-14T09:00:00Z",
              "createdStepIds":[],"uploadedMedia":[],"conflict":false}]
        """.trimIndent()
        val decoded = queueJson.decodeFromString<List<PendingEntry>>(old).single()

        assertEquals("the new state must be absent, not invented", emptyMap<String, String>(), decoded.unfiled)
        assertNull(decoded.danglingField)
        assertEquals(emptySet<String>(), decoded.clearedLinkKeys)
        assertEquals(emptySet<String>(), decoded.emptyPickerKeys)
        assertEquals(emptyList<String>(), decoded.danglingKeys)
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

        assertEquals("Record not found", row.reason)
        assertEquals(emptyList<String>(), row.danglingNouns)
        assertEquals(emptyList<String>(), row.repickKeys)
        assertFalse(row.conflict)
        assertFalse(row.awaitingUpdate)
    }

    @Test
    fun `a row written by this build round-trips through the queue file unchanged`() {
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

    @Test
    fun `the wire encoder drops a cleared workshop, which is why an explicit null has to be put back`() {
        val cleared = ArtisanCreateRequest(name = "Giriraj Prasad", place = "Bhuj", designWorkshopId = null)
        val encoded = wireJson.encodeToString(ArtisanCreateRequest.serializer(), cleared)
        assertFalse(
            "if this ever starts appearing on its own, the clearance plumbing can be deleted:\n$encoded",
            encoded.contains("designWorkshopId"),
        )
        assertEquals(setOf("designWorkshopId", "workshopId"), WORKSHOP_LINK_KEYS)
    }
}
