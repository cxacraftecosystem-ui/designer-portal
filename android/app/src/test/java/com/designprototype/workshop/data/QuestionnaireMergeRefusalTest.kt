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

class QuestionnaireMergeRefusalTest {

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    private val duplicateSet =
        """{"detail":{"code":"duplicate_artisan_set","message":"Another interview already covers """ +
            """exactly this set of artisans.","existingInterviewId":"iv-d-black-pottery",""" +
            """"existingInterviewTitle":"D Black Pottery"}}"""

    private val answerConflict =
        """{"detail":{"code":"merge_answer_conflict","message":"Nothing was moved. 2 question(s) are """ +
            """answered differently in “F Wheel and kiln” and “D Black Pottery”: F3 — How long have """ +
            """you worked at this?; D1 — Who taught you. Open each one, keep the wording that is """ +
            """right, then merge.","conflicts":[{"questionId":"q-f3","sectionCode":"F","prompt":""" +
            """"How long have you worked at this?","fields":["answerText"]},{"questionId":"q-d1",""" +
            """"sectionCode":"D","prompt":"Who taught you","fields":["notes"]}]}}"""

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
        val body = """{"detail":{"code":"duplicate_artisan_set","message":"Taken.","existingInterviewId":"  ",""" +
            """"existingInterviewTitle":""}}"""
        val refusal = questionnaireSaveRefusal(body, fallback = "x")
        assertNull(refusal.holderId)
        assertNull(refusal.holderTitle)
    }

    @Test
    fun `an API older than this feature still yields its sentence`() {
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
        assertEquals("Unable to save questionnaire", questionnaireSaveRefusal("<html>504</html>", "Unable to save questionnaire").message)
        assertEquals("Unable to save questionnaire", questionnaireSaveRefusal(null, "Unable to save questionnaire").message)
        assertEquals("Unable to save questionnaire", questionnaireSaveRefusal("", "Unable to save questionnaire").message)
    }

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

    @Test
    fun `the 409 is read straight off an HttpException`() {
        val refusal = http(409, duplicateSet).questionnaireSaveRefusal("Unable to save questionnaire")
        assertTrue(refusal.isDuplicateSet)
        assertEquals("iv-d-black-pottery", refusal.holderId)
        assertEquals("D Black Pottery", refusal.holderTitle)
    }

    @Test
    fun `the error body is read once and once only`() {
        val failure = http(409, duplicateSet)
        assertEquals("D Black Pottery", failure.questionnaireSaveRefusal("Unable to save").holderTitle)
        assertNull(failure.questionnaireSaveRefusal("Unable to save").holderTitle)
    }

    @Test
    fun `anything that is not a 409 keeps the reader it already had`() {
        val body = """{"detail":[{"type":"value_error","loc":["body","title"],"msg":"Value error, A title is required."}]}"""
        val refusal = http(422, body).questionnaireSaveRefusal("Unable to save questionnaire")
        assertEquals("A title is required.", refusal.message)
        assertNull(refusal.code)
        assertFalse(refusal.isDuplicateSet)
    }

    @Test
    fun `a failure that never reached the server keeps the platform's own words`() {
        val refusal = java.io.IOException("Unable to resolve host \"api.example\"")
            .questionnaireSaveRefusal("Unable to save questionnaire")
        assertEquals("Unable to resolve host \"api.example\"", refusal.message)
        assertNull(refusal.code)
    }
}
