package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C28 on the designer portal's handset: the wire shapes decode, the pure rules hold, and the words
 * are the web's — the same checks the documentation portal's handset runs.
 */
class AnswerDraftsHandsetTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun machineSuggestedAnswersAreLabelledAndTypedOnesAreNot() {
        assertEquals("Machine-suggested", AnswerDrafts.badge(AnswerDrafts.ORIGIN_MACHINE))
        assertEquals("Machine-suggested · edited", AnswerDrafts.badge(AnswerDrafts.ORIGIN_MACHINE_EDITED))
        assertNull(AnswerDrafts.badge(null))
        assertTrue(AnswerDrafts.isMachineSuggested("MACHINE_SUGGESTED_EDITED"))
        assertFalse(AnswerDrafts.isMachineSuggested("TYPED"))
    }

    @Test
    fun acceptingSendsTheEditOrTheDraftAsWritten() {
        val pending = listOf(AnswerDraftDto("d1", "q1", "s1", "As drafted"), AnswerDraftDto("d2", "q2", "s1", "Second"))
        val answers = AnswerDrafts.answersToAccept(pending, mapOf("q2" to "Edited"))
        assertEquals(listOf("As drafted", "Edited"), answers.map { it.answerText })
    }

    @Test
    fun theWireShapesDecodeAndAReaderWhoMayNotDraftSeesNothing() {
        val list = json.decodeFromString(
            AnswerDraftListDto.serializer(),
            """{"items":[{"id":"d1","questionId":"q1","sectionId":"s1","draftText":"x","status":"PENDING"}],"transcripts":[{"id":"m1","name":"a.webm"}],"mayDraft":true}""",
        )
        assertEquals("q1", list.items.single().questionId)
        assertFalse(json.decodeFromString(AnswerDraftListDto.serializer(), """{"mayDraft":false}""").mayDraft)
        val response = json.decodeFromString(InterviewResponseDto.serializer(), """{"questionId":"q1","answerOrigin":"MACHINE_SUGGESTED"}""")
        assertEquals("MACHINE_SUGGESTED", response.answerOrigin)
        assertNull(json.decodeFromString(InterviewResponseDto.serializer(), """{"questionId":"q1"}""").answerOrigin)
    }

    @Test
    fun theWordsAreTheWebsWords() {
        val drafts = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "frontend/components/questionnaire/AnswerDraftsPanel.tsx") }
            .firstOrNull { it.isFile }
            ?.readText() ?: return
        for (phrase in listOf(AnswerDrafts.LABEL, AnswerDrafts.ACCEPT, AnswerDrafts.DRAFT, AnswerDrafts.PASTE, AnswerDrafts.NOTHING_FOUND)) {
            assertTrue("web lacks: $phrase", drafts.contains(phrase))
        }
    }
}
