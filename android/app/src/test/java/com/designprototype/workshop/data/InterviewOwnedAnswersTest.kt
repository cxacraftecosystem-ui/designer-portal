package com.designprototype.workshop.data

import com.designprototype.workshop.qLiveSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H4 (audit 21 Oct 2026): a second researcher must be able to add to a shared interview, and a
 * queued interview refused over somebody else's answers must not be stuck for good.
 */
class InterviewOwnedAnswersTest {

    private val refusal = """{"detail":{"code":"ANSWER_OWNED_BY_OTHER",""" +
        """"message":"Two answers here were written by Meera.","questionIds":["q2","q3","q2"]}}"""

    @Test
    fun readsTheNamedAnswersFromTheRefusal() {
        val owned = ownedAnswersRefusal(403, refusal)!!
        assertEquals(listOf("q2", "q3"), owned.questionIds)
        assertEquals("Two answers here were written by Meera.", owned.message)
    }

    @Test
    fun anyOtherRefusalIsNotThisOne() {
        assertNull(ownedAnswersRefusal(409, refusal))
        assertNull(ownedAnswersRefusal(403, """{"detail":"Only the original answer contributor or an admin can change this response"}"""))
        assertNull(ownedAnswersRefusal(403, """{"detail":{"code":"ANSWER_OWNED_BY_OTHER","questionIds":[]}}"""))
    }

    @Test
    fun sendingWithoutThemKeepsEveryOtherAnswerAndKey() {
        val body = """{"title":"Interview","responses":[{"questionId":"q1","answerText":"a"},""" +
            """{"questionId":"q2","answerText":"b"},{"questionId":"q3","answerText":"c"}]}"""
        val out = Json.parseToJsonElement(withoutAnswers(body, listOf("q2", "q3"))!!) as JsonObject
        assertEquals("Interview", out["title"]!!.jsonPrimitive.content)
        val kept = (out["responses"] as JsonArray).map { ((it as JsonObject)["questionId"] as JsonPrimitive).content }
        assertEquals(listOf("q1"), kept)
    }

    @Test
    fun onlyTypedOrChangedAnswersAreSent() {
        val now = mapOf("q1" to "same", "q2" to " changed ", "q3" to "", "q4" to "new")
        val sent = interviewAnswersToSend(
            listOf("q1", "q2", "q3", "q4"),
            current = { now[it] },
            initial = mapOf("q1" to "same", "q2" to "before", "q3" to "theirs"),
        )
        assertEquals(listOf("q2" to "changed", "q4" to "new"), sent)
    }

    @Test
    fun theTrayOffersSendWithoutThem() {
        assertEquals("Send without that answer", outboxSendWithoutConflictsLabel(1))
        assertEquals("Send without those 2 answers", outboxSendWithoutConflictsLabel(2))
        val row = outboxFailureRows(
            listOf(
                PendingEntry(
                    id = "e1", type = "questionnaire", payloadJson = "{}", label = "Interview",
                    createdAt = "2026-10-21T00:00:00Z", failure = "Two answers here were written by Meera.",
                    conflictingAnswerIds = listOf("q2", "q3"),
                )
            )
        ).single()
        assertEquals(2, row.conflictingAnswers)
        val tray = qLiveSource("app/src/main/java/com/designprototype/workshop/ui/OfflineOutboxTray.kt").readText()
        assertTrue(tray.contains("repository.sendWithoutConflictingAnswers(appContext, row.entryId)"))
        val repo = qLiveSource("app/src/main/java/com/designprototype/workshop/data/WorkshopRepository.kt").readText()
        assertTrue(repo.contains("conflictingAnswerIds = owned.questionIds"))
    }
}
