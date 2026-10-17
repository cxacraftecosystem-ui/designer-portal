package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response
import java.io.File

/**
 * The deferrals of the 2026-10-16 offline review, built: idempotent creates, corrections sent as
 * changed fields under a version precondition with "Review changes" when the record moved, and
 * questionnaire authoring under temporary ids. See `docs/ANDROID_OFFLINE_AUDIT.md`.
 */
class OfflineDeferralsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── Idempotency keys ───────────────────────────────────────────────────────────────────────

    @Test
    fun every_create_carries_a_key_and_no_correction_does() {
        for (type in listOf("artisan", "craft", "product", "tool", "workshop", "process", "questionnaire",
            OFFLINE_CUSTOM_QUESTIONNAIRE, OFFLINE_FEEDBACK_REPORT)) {
            assertTrue(type, outboxCarriesIdempotencyKey(type, targetId = null))
            assertFalse(type, outboxCarriesIdempotencyKey(type, targetId = "rec_1"))
        }
        assertFalse(outboxCarriesIdempotencyKey(OFFLINE_MEDIA_ONLY, targetId = null))
        assertFalse(outboxCarriesIdempotencyKey(OFFLINE_APP_FEEDBACK, targetId = null))
    }

    private fun http(code: Int, body: String = "{}", headers: okhttp3.Headers = headersOf()): HttpException {
        val raw = okhttp3.Response.Builder()
            .code(code)
            .message("x")
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("http://localhost/api/crafts").build())
            .headers(headers)
            .build()
        return HttpException(Response.error<Any>(body.toResponseBody("application/json".toMediaTypeOrNull()), raw))
    }

    @Test
    fun a_409_saying_the_first_send_is_still_running_is_retried_not_refused() {
        assertTrue(isIdempotencyInProgress(http(409, headers = headersOf("Idempotency-Status", "in-progress"))))
        assertFalse(isIdempotencyInProgress(http(409)))
        assertFalse(isIdempotencyInProgress(http(422, headers = headersOf("Idempotency-Status", "in-progress"))))
    }

    @Test
    fun a_queue_file_from_before_keys_and_bases_still_decodes() {
        val old = """{"id":"e1","type":"craft","payloadJson":"{}","label":"x","createdAt":"2026-10-01T00:00:00Z"}"""
        val entry = json.decodeFromString(PendingEntry.serializer(), old)
        assertNull(entry.idempotencyKey)
        assertNull(entry.changedKeys)
        assertNull(entry.baseUpdatedAt)
        assertFalse(entry.staleCorrection)
        assertNull(entry.tempId)
    }

    // ── Corrections: only what changed, under the base version ─────────────────────────────────

    @Test
    fun only_the_fields_a_person_changed_count_as_changed() {
        val record = buildJsonObject {
            put("name", JsonPrimitive("Ramesh"))
            put("phone", JsonPrimitive("9876543210"))
            put("notes", JsonNull)
            put("lengthInches", JsonPrimitive(12.5))
            put("updatedAt", JsonPrimitive("2026-10-16T08:00:00.000Z"))
        }
        val request = buildJsonObject {
            put("name", JsonPrimitive("Ramesh"))
            put("phone", JsonPrimitive("9123456780"))
            put("notes", JsonPrimitive(""))
            put("lengthInches", JsonPrimitive("12.5"))
            put("place", JsonPrimitive("Bhuj"))
            put("craftIds", kotlinx.serialization.json.JsonArray(emptyList()))
        }
        // phone moved; notes "" = null; 12.5 as text = 12.5; place is new; an empty list is nothing.
        assertEquals(setOf("phone", "place"), correctionChangedKeys(request, record))
    }

    @Test
    fun the_patch_carries_only_the_changes_and_the_precondition() {
        val body = buildJsonObject {
            put("name", JsonPrimitive("Ramesh"))
            put("phone", JsonPrimitive("9123456780"))
            put("designWorkshopId", JsonNull)
        }
        val narrowed = restrictCorrectionBody(body, setOf("phone"), "2026-10-16T08:00:00.000Z")
        assertEquals(setOf("phone", "expectedUpdatedAt"), narrowed.keys)
        assertEquals("2026-10-16T08:00:00.000Z", narrowed["expectedUpdatedAt"]!!.jsonPrimitive.content)
        // An entry from an earlier build: the whole body, no precondition — exactly as before.
        assertEquals(body, restrictCorrectionBody(body, null, null))
    }

    @Test
    fun the_servers_record_changed_refusal_is_recognised_and_nothing_else_is() {
        val stale = """{"detail":{"code":"record_changed","message":"Someone else changed this record after this edit was composed.","expectedUpdatedAt":"2026-10-16T08:00:00+00:00","currentUpdatedAt":"2026-10-16T09:30:00+00:00"}}"""
        assertEquals("2026-10-16T09:30:00+00:00", staleCorrectionCurrentVersion(409, stale))
        assertNull(staleCorrectionCurrentVersion(409, """{"detail":{"code":"duplicate_aadhaar","message":"x"}}"""))
        assertNull(staleCorrectionCurrentVersion(422, stale))
        assertNull(staleCorrectionCurrentVersion(409, null))
    }

    @Test
    fun the_error_body_can_be_peeked_without_consuming_it() {
        val error = http(409, body = """{"detail":{"code":"record_changed","currentUpdatedAt":"t"}}""")
        assertEquals("t", staleCorrectionCurrentVersion(409, peekErrorBody(error)))
        // Still readable afterwards by the classification that follows.
        assertTrue(error.response()!!.errorBody()!!.string().contains("record_changed"))
    }

    @Test
    fun review_changes_lists_only_my_changed_fields_that_now_disagree() {
        val mine = buildJsonObject {
            put("phone", JsonPrimitive("9123456780"))
            put("place", JsonPrimitive("Bhuj"))
            put("name", JsonPrimitive("Ramesh"))
        }
        val record = buildJsonObject {
            put("phone", JsonPrimitive("9000000000"))
            put("place", JsonPrimitive("Bhuj"))
            put("name", JsonPrimitive("Ramesh K"))
            put("updatedAt", JsonPrimitive("2026-10-16T09:30:00Z"))
        }
        val review = staleCorrectionReview(mine, record, changedKeys = setOf("phone", "place"))
        assertEquals(listOf(StaleCorrectionField("phone", "9123456780", "9000000000")), review.fields)
        assertEquals("2026-10-16T09:30:00Z", review.currentUpdatedAt)
    }

    @Test
    fun the_tray_marks_a_stale_correction_for_review() {
        val entry = PendingEntry(
            id = "e1", type = "artisan", payloadJson = "{}", label = "Ramesh", createdAt = "t",
            targetId = "a1", failure = STALE_CORRECTION_SENTENCE, staleCorrection = true,
        )
        val row = outboxFailureRows(listOf(entry)).single()
        assertTrue(row.stale)
        assertTrue(row.reason.contains("Review changes"))
    }

    // ── Questionnaire authoring under temporary ids ────────────────────────────────────────────

    @Test
    fun temporary_ids_are_recognised_and_resolved_once_known() {
        val temp = newTempId()
        assertTrue(isTempId(temp))
        assertFalse(isTempId("cmsik2jg8000eh8xc1lcy661a"))
        assertEquals(temp, resolveTempId(temp, emptyMap()))
        assertEquals("srv_1", resolveTempId(temp, mapOf(temp to "srv_1")))
        assertEquals("srv_9", resolveTempId("srv_9", mapOf(temp to "srv_1")))
    }

    @Test
    fun the_temp_id_map_survives_on_disk() {
        val file = File(folder.newFolder(), "temp-ids.json")
        TempIdMap.write(file, mapOf("tmp-a" to "s1"))
        TempIdMap.write(file, TempIdMap.read(file) + ("tmp-b" to "s2"))
        assertEquals(mapOf("tmp-a" to "s1", "tmp-b" to "s2"), TempIdMap.read(file))
        assertEquals(emptyMap<String, String>(), TempIdMap.read(File(folder.root, "absent.json")))
    }

    @Test
    fun steps_waiting_on_the_phone_are_drawn_on_the_form_under_their_temporary_ids() {
        val form = CustomQuestionnaireDto(
            id = "q1",
            title = "Loom survey",
            sections = listOf(
                CustomSectionDto(id = "s1", title = "Looms", sortOrder = 1, questions = listOf(
                    CustomQuestionDto(id = "x1", prompt = "How many looms?", sortOrder = 1),
                    CustomQuestionDto(id = "x2", prompt = "Old question", sortOrder = 2),
                )),
            ),
        )
        val newSection = newTempId()
        val newQuestion = newTempId()
        val ops = listOf(
            QFormOp(QFormOp.ADD_SECTION, "q1", tempId = newSection, title = "Dyes"),
            QFormOp(QFormOp.ADD_QUESTION, "q1", tempId = newQuestion, sectionId = newSection, prompt = "Which dyes?"),
            QFormOp(QFormOp.EDIT_QUESTION, "q1", questionId = "x1", isRequired = true),
            QFormOp(QFormOp.REMOVE_QUESTION, "q1", questionId = "x2"),
            QFormOp(QFormOp.RENAME_SECTION, "q1", sectionId = "s1", title = "Looms and frames"),
            QFormOp(QFormOp.RENAME_QUESTIONNAIRE, "q1", title = "Loom and dye survey"),
        )
        val drawn = applyPendingAuthoring(form, ops, emptyMap())
        assertEquals("Loom and dye survey", drawn.title)
        assertEquals(listOf("Looms and frames", "Dyes"), drawn.sections.map { it.title })
        assertEquals(listOf("x1"), drawn.sections[0].questions.map { it.id })
        assertTrue(drawn.sections[0].questions[0].isRequired)
        assertEquals(listOf(newQuestion), drawn.sections[1].questions.map { it.id })
        assertEquals(2, drawn.questionCount)
    }

    @Test
    fun a_step_already_resolved_is_not_drawn_twice() {
        val temp = newTempId()
        val form = CustomQuestionnaireDto(id = "q1", sections = listOf(CustomSectionDto(id = "s9", title = "Dyes")))
        val drawn = applyPendingAuthoring(
            form,
            listOf(QFormOp(QFormOp.ADD_SECTION, "q1", tempId = temp, title = "Dyes")),
            mapOf(temp to "s9"),
        )
        assertEquals(listOf("s9"), drawn.sections.map { it.id })
    }

    @Test
    fun the_server_id_of_a_created_section_or_question_is_found_in_the_answer() {
        val result = CustomQuestionnaireDto(
            id = "q1",
            sections = listOf(
                CustomSectionDto(id = "s1", title = "Dyes", sortOrder = 1),
                CustomSectionDto(id = "s2", title = "Dyes", sortOrder = 2, questions = listOf(
                    CustomQuestionDto(id = "x7", prompt = "Which dyes?", sortOrder = 1),
                )),
            ),
        )
        // Two sections with one title: the newest one no earlier temporary id has claimed.
        assertEquals("s2", createdSectionId(result, "Dyes", claimed = emptyList()))
        assertEquals("s1", createdSectionId(result, "Dyes", claimed = listOf("s2")))
        assertEquals("x7", createdQuestionId(result, "s2", "Which dyes?", claimed = emptyList()))
        assertNull(createdQuestionId(result, "s1", "Which dyes?", claimed = emptyList()))
    }

    @Test
    fun a_queued_authoring_step_round_trips() {
        val op = QFormOp(QFormOp.ADD_QUESTION, "tmp-q", tempId = "tmp-x", sectionId = "tmp-s", prompt = "P", isRequired = true)
        assertEquals(op, json.decodeFromString(QFormOp.serializer(), json.encodeToString(QFormOp.serializer(), op)))
        assertEquals("Questionnaire change", outboxKindLabel(OFFLINE_QFORM_OP, isUpdate = false))
    }

    // ── Release-review follow-ups (2026-10-16) ─────────────────────────────────────────────────

    @Test
    fun a_landed_correction_rebases_only_the_same_records_waiting_corrections() {
        fun e(id: String, type: String = "artisan", target: String? = "a1", failure: String? = null, base: String? = "t0") =
            PendingEntry(id = id, type = type, payloadJson = "{}", label = id, createdAt = "t",
                targetId = target, failure = failure, baseUpdatedAt = base)
        assertTrue(advancesWith(e("later"), "artisan", "a1", exceptId = "landed"))
        assertFalse(advancesWith(e("landed"), "artisan", "a1", exceptId = "landed"))
        assertFalse(advancesWith(e("other", target = "a2"), "artisan", "a1", exceptId = "landed"))
        assertFalse(advancesWith(e("craft", type = "craft"), "artisan", "a1", exceptId = "landed"))
        assertFalse("a refused one is left for its review", advancesWith(e("refused", failure = "x"), "artisan", "a1", "landed"))
        assertFalse("an older build's entry has no base to move", advancesWith(e("old", base = null), "artisan", "a1", "landed"))
    }

    @Test
    fun a_stored_create_answer_gives_back_the_record_and_its_steps() {
        val body = json.parseToJsonElement("""{"id":"p1","steps":[{"id":"s1"},{"id":"s2"}]}""")
        assertEquals("p1" to listOf("s1", "s2"), createdFromStoredAnswer(body))
        assertEquals("c1" to emptyList<String>(), createdFromStoredAnswer(json.parseToJsonElement("""{"id":"c1"}""")))
        assertNull(createdFromStoredAnswer(json.parseToJsonElement("""{"detail":"x"}""")))
        assertNull(createdFromStoredAnswer(null))
    }

    @Test
    fun draft_writes_ask_for_a_pass_at_most_once_a_minute_while_online() {
        assertTrue("offline, every write asks", draftWriteShouldRequest(online = false, nowMs = 5_000, lastRequestMs = 4_000))
        assertTrue("the first online write asks", draftWriteShouldRequest(online = true, nowMs = 5_000, lastRequestMs = 0))
        assertFalse(draftWriteShouldRequest(online = true, nowMs = 30_000, lastRequestMs = 5_000))
        assertTrue(draftWriteShouldRequest(online = true, nowMs = 65_000, lastRequestMs = 5_000))
    }
}
