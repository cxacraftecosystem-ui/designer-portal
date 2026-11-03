package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.util.Locale

class ReceivedQuestionnaireTest {

    private fun row(
        remoteId: String? = null,
        sectionsDone: Int = 0,
        adoptedAt: String? = null,
        failure: String? = null,
    ) = ReceivedQuestionnaire(
        id = "r1",
        filename = "cluster-survey-questions-v7.dpwq",
        receivedAt = "2026-08-24T09:00:00Z",
        title = "Cluster survey",
        sectionCount = 24,
        questionCount = 285,
        sourceVersion = 7,
        localPath = "/data/x/r1.dpwq",
        remoteId = remoteId,
        sectionsDone = sectionsDone,
        adoptedAt = adoptedAt,
        failure = failure,
    )

    @Test
    fun `a file that has not been adopted says what is in it`() {
        val text = receivedQuestionnaireStatus(row())
        assertTrue(text, text.contains("Not added yet"))
        assertTrue(text, text.contains("285 questions in 24 sections"))
        assertFalse(row().started)
        assertFalse(row().adopted)
    }

    @Test
    fun `a half-adopted file tells the designer NOT to start again`() {
        val partial = row(remoteId = "cq_new", sectionsDone = 9)
        val text = receivedQuestionnaireStatus(partial)
        assertTrue(partial.started)
        assertFalse(partial.adopted)
        assertTrue(text, text.contains("9 of 24 sections are on your account"))
        assertTrue(text, text.contains("do not start again"))
        assertTrue(text, text.contains("two copies"))
    }

    @Test
    fun `a row remembers that a section create was already attempted`() {
        assertFalse(row().sectionCreateStarted)
        val inFlight = row(remoteId = "cq_new", sectionsDone = 3).copy(sectionCreateStarted = true)
        assertTrue(inFlight.sectionCreateStarted)
        assertNull(inFlight.currentSectionRemoteId)
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<ReceivedQuestionnaire>(
            """{"id":"r1","filename":"x.dpwq","receivedAt":"2026-08-24T09:00:00Z","remoteId":"cq_1"}"""
        )
        assertFalse(old.sectionCreateStarted)
        assertTrue(old.started)
    }

    @Test
    fun `a stopped adoption carries the server's own reason`() {
        val text = receivedQuestionnaireStatus(row(remoteId = "cq_new", failure = "A section title was too long."))
        assertTrue(text, text.startsWith("Stopped: "))
        assertTrue(text, text.contains("A section title was too long."))
    }

    @Test
    fun `a finished adoption is finished, and says when`() {
        val done = row(remoteId = "cq_new", sectionsDone = 24, adoptedAt = "2026-08-24T11:15:00Z")
        assertTrue(done.adopted)
        val text = receivedQuestionnaireStatus(done)
        assertTrue(text, text.contains("Added to your questionnaires"))
        assertFalse(text, text.contains("2026-08-24T11:15:00Z"))
        assertTrue(text, text.contains(readableStamp("2026-08-24T11:15:00Z")!!))
        val recovered = receivedQuestionnaireStatus(
            row(remoteId = "cq_new", sectionsDone = 24, adoptedAt = "2026-08-24T11:15:00Z", failure = "old failure")
        )
        assertFalse(recovered, recovered.contains("old failure"))
    }

    @Test
    fun `the offline questionnaire notice refuses to imply an answer can be saved`() {
        val notice = cachedQuestionnaireNotice("2026-08-22T07:30:00Z", version = 7)
        assertTrue(notice, notice.contains("the copy this phone downloaded"))
        assertFalse(notice, notice.contains("2026-08-22T07:30:00Z"))
        assertTrue(notice, notice.contains(readableStamp("2026-08-22T07:30:00Z")!!))
        assertTrue(notice, notice.contains("version 7"))
        assertTrue(notice, notice.contains("ANSWERS CANNOT BE SAVED"))
        val undated = cachedQuestionnaireNotice(null, version = 2)
        assertFalse(undated, undated.contains(" on ("))
        assertTrue(undated, undated.contains("ANSWERS CANNOT BE SAVED"))
    }

    @Test
    fun `a stored instant is shown in this device's own time, and an unreadable one is not hidden`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals(
            "24 aug 2026, 02:42 pm",
            readableStamp("2026-08-24T09:12:33.221Z", kolkata, Locale.UK)?.lowercase(),
        )
        assertEquals(
            readableStamp("2026-08-24T09:12:33Z", kolkata, Locale.UK),
            readableStamp("2026-08-24T14:42:33+05:30", kolkata, Locale.UK),
        )
        assertNull(readableStamp(null, kolkata, Locale.UK))
        assertNull(readableStamp("   ", kolkata, Locale.UK))
        assertEquals("last tuesday", readableStamp("last tuesday", kolkata, Locale.UK))
    }
}
