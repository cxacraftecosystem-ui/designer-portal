package com.designprototype.workshop.data

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * THE TWO QUESTIONNAIRE 409s, READ OUT OF THE WIRE EXACTLY ONCE.
 *
 * ── WHAT THIS STOPS FROM COMING BACK ─────────────────────────────────────────────────────────────
 *
 * `QuestionnaireForm`'s save handler printed `it.message`, which for a Retrofit failure is the
 * status line: a researcher whose correction collided with another interview was shown the string
 * **"HTTP 409 Conflict"**. The server's refusal names the interview standing in the way, and the
 * whole merge offer is built out of that name — so every field below is the difference between an
 * offer that can be made and a dead end.
 *
 * ── AND ONE MECHANICAL TRAP, WHICH IS WHY THE LAST TEST EXISTS ───────────────────────────────────
 *
 * Retrofit BUFFERS the error body and `string()` CONSUMES the buffer. Asking the same exception two
 * questions silently answers the second one with nothing — an empty string, which parses to no
 * detail, which looks exactly like a server that said nothing. `WorkshopRepository` states this three
 * times over; this file proves that the questionnaire's reader takes only one bite.
 */
class QuestionnaireMergeRefusalTest {

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    /** `duplicate_set_detail` — the artisan set another interview already holds, with the holder. */
    private val duplicateSet =
        """{"detail":{"code":"duplicate_artisan_set","message":"Another interview already covers """ +
            """exactly this set of artisans.","existingInterviewId":"iv-d-black-pottery",""" +
            """"existingInterviewTitle":"D Black Pottery"}}"""

    /** `merge_interview_into`'s answer-conflict refusal, which moved nothing and names each question. */
    private val answerConflict =
        """{"detail":{"code":"merge_answer_conflict","message":"Nothing was moved. 2 question(s) are """ +
            """answered differently in “F Wheel and kiln” and “D Black Pottery”: F3 — How long have """ +
            """you worked at this?; D1 — Who taught you. Open each one, keep the wording that is """ +
            """right, then merge.","conflicts":[{"questionId":"q-f3","sectionCode":"F","prompt":""" +
            """"How long have you worked at this?","fields":["answerText"]},{"questionId":"q-d1",""" +
            """"sectionCode":"D","prompt":"Who taught you","fields":["notes"]}]}}"""

    // ── 1. The refusal a merge offer can be built from ───────────────────────────────────────────

    @Test
    fun `the holder is read off the duplicate-set refusal`() {
        val refusal = questionnaireSaveRefusal(duplicateSet, fallback = "Unable to save questionnaire")
        assertTrue(refusal.isDuplicateSet)
        assertEquals("iv-d-black-pottery", refusal.holderId)
        assertEquals("D Black Pottery", refusal.holderTitle)
        assertEquals("Another interview already covers exactly this set of artisans.", refusal.message)
    }

    @Test
    fun `a vanished holder leaves the sentence and nothing to call`() {
        // `duplicate_set_detail` keeps both keys PRESENT and answers null when the holding row was
        // deleted between the unique violation and its read. The sentence still reads correctly on
        // its own, which is what every client printed before these keys existed.
        val body = """{"detail":{"code":"duplicate_artisan_set","message":"Another interview already """ +
            """covers exactly this set of artisans.","existingInterviewId":null,"existingInterviewTitle":null}}"""
        val refusal = questionnaireSaveRefusal(body, fallback = "Unable to save questionnaire")
        assertTrue(refusal.isDuplicateSet)
        assertNull(refusal.holderId)
        assertNull(refusal.holderTitle)
        assertEquals("Another interview already covers exactly this set of artisans.", refusal.message)
    }

    @Test
    fun `an empty id is not an id`() {
        // A blank would otherwise produce a confirm button that posts to `merge-into/` with an empty
        // path segment — a 404 on a fold the researcher was just promised.
        val body = """{"detail":{"code":"duplicate_artisan_set","message":"Taken.","existingInterviewId":"  ",""" +
            """"existingInterviewTitle":""}}"""
        val refusal = questionnaireSaveRefusal(body, fallback = "x")
        assertNull(refusal.holderId)
        assertNull(refusal.holderTitle)
    }

    @Test
    fun `an API older than this feature still yields its sentence`() {
        // The refusal used to be a bare string detail. A client that only knew the object shape would
        // print its own fallback over a sentence the server wrote for this exact moment.
        val refusal = questionnaireSaveRefusal(
            """{"detail":"Another interview already covers exactly this set of artisans."}""",
            fallback = "Unable to save questionnaire",
        )
        assertEquals("Another interview already covers exactly this set of artisans.", refusal.message)
        assertFalse(refusal.isDuplicateSet)
        assertNull(refusal.code)
    }

    @Test
    fun `a body that is not the API's falls back to the caller's sentence`() {
        // A gateway writes an HTML error page. Printing that at somebody would be worse than useless.
        assertEquals("Unable to save questionnaire", questionnaireSaveRefusal("<html>504</html>", "Unable to save questionnaire").message)
        assertEquals("Unable to save questionnaire", questionnaireSaveRefusal(null, "Unable to save questionnaire").message)
        assertEquals("Unable to save questionnaire", questionnaireSaveRefusal("", "Unable to save questionnaire").message)
    }

    // ── 2. The merge's own refusal ───────────────────────────────────────────────────────────────

    @Test
    fun `every conflicting question survives the decode`() {
        val refusal = questionnaireSaveRefusal(answerConflict, fallback = "The merge could not be completed.")
        assertTrue(refusal.isAnswerConflict)
        assertFalse(refusal.isDuplicateSet)
        assertEquals(listOf("q-f3", "q-d1"), refusal.conflicts.map { it.questionId })
        assertEquals(listOf("F", "D"), refusal.conflicts.map { it.sectionCode })
        assertEquals(
            listOf("F — How long have you worked at this?", "D — Who taught you"),
            refusal.conflicts.map { it.label },
        )
    }

    @Test
    fun `a conflict the server could not name falls back to its id`() {
        // `prompt` and `sectionCode` come off an included relation and are null for a question that
        // has since been deleted. A blank bullet would send a researcher looking for a question the
        // refusal never named.
        val body = """{"detail":{"code":"merge_answer_conflict","message":"Nothing was moved.",""" +
            """"conflicts":[{"questionId":"q-gone","sectionCode":null,"prompt":null}]}}"""
        val refusal = questionnaireSaveRefusal(body, fallback = "x")
        assertEquals("q-gone", refusal.conflicts.single().label)
    }

    @Test
    fun `a conflict row with no question id is dropped rather than drawn as a blank`() {
        val body = """{"detail":{"code":"merge_answer_conflict","message":"Nothing was moved.",""" +
            """"conflicts":[{"sectionCode":"F","prompt":"orphan"},{"questionId":"q-f3","prompt":"kept"}]}}"""
        val refusal = questionnaireSaveRefusal(body, fallback = "x")
        assertEquals(listOf("q-f3"), refusal.conflicts.map { it.questionId })
    }

    // ── 3. Through the exception, once ───────────────────────────────────────────────────────────

    @Test
    fun `the 409 is read straight off an HttpException`() {
        val refusal = http(409, duplicateSet).questionnaireSaveRefusal("Unable to save questionnaire")
        assertTrue(refusal.isDuplicateSet)
        assertEquals("iv-d-black-pottery", refusal.holderId)
        assertEquals("D Black Pottery", refusal.holderTitle)
    }

    @Test
    fun `the error body is read once and once only`() {
        // The trap this whole class is arranged around: a SECOND read of the same failure sees an
        // empty buffer. The point is not that the second call answers the fallback — it is that a
        // caller must never make it, and this records what happens if one does.
        val failure = http(409, duplicateSet)
        assertEquals("D Black Pottery", failure.questionnaireSaveRefusal("Unable to save").holderTitle)
        assertNull(failure.questionnaireSaveRefusal("Unable to save").holderTitle)
    }

    @Test
    fun `anything that is not a 409 keeps the reader it already had`() {
        // A 422 naming a field is unwrapped by `apiErrorMessage`, including pydantic's "Value error, "
        // prefix. Re-implementing that list here to serve one screen is how two readings of one error
        // body start disagreeing.
        val body = """{"detail":[{"type":"value_error","loc":["body","title"],"msg":"Value error, A title is required."}]}"""
        val refusal = http(422, body).questionnaireSaveRefusal("Unable to save questionnaire")
        assertEquals("A title is required.", refusal.message)
        assertNull(refusal.code)
        assertFalse(refusal.isDuplicateSet)
    }

    @Test
    fun `a failure that never reached the server keeps the platform's own words`() {
        // No connection, a timeout, a serialization fault: the platform message is the only thing
        // that knows what happened, and it is more informative than anything this reader could invent.
        val refusal = java.io.IOException("Unable to resolve host \"api.example\"")
            .questionnaireSaveRefusal("Unable to save questionnaire")
        assertEquals("Unable to resolve host \"api.example\"", refusal.message)
        assertNull(refusal.code)
    }
}
