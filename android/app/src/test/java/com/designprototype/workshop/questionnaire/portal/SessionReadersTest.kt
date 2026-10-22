package com.designprototype.workshop.questionnaire.portal

import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.QuestionnaireInterviewDetailDto
import com.designprototype.workshop.data.completenessSentence
import com.designprototype.workshop.questionnaire.QFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C10 on the designer portal: a session's read view shows what the server's readers make of it. The
 * readers count the session's live takes and typed answers (`backend/app/questionnaire/readers.py`),
 * so a session with a typed answer and a take no longer lists answers or a recording as missing.
 */
class SessionReadersTest {
    private fun read(body: String) = ApiClient.json.decodeFromString(QuestionnaireInterviewDetailDto.serializer(), body)

    @QFeature("C10")
    @Test fun `the read view's meter is the readers' block for the session`() {
        val before = read(
            """{"id":"s1","title":"Interview_2026-10-21","completeness":{"rulesVersion":3,"filled":4,"total":6,"percent":67,"requiredMissing":0,
            "missing":[{"key":"answers","label":"Answers","words":"answers","level":"recommended"},{"key":"recording","label":"Recording","words":"recording","level":"recommended"}]}}"""
        ).completeness!!
        assertEquals("2 fields left: answers, recording", completenessSentence(before))
        val after = read("""{"id":"s1","completeness":{"rulesVersion":3,"filled":6,"total":6,"percent":100,"requiredMissing":0,"missing":[]}}""").completeness!!
        assertEquals("Complete", completenessSentence(after))
    }

    @QFeature("C10")
    @Test fun `the designer portal draws the readers on every session's read view`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "src/main/java") }.first { it.isDirectory }
        fun source(name: String) = root.walkTopDown().first { it.name == name }.readText()
        assertTrue(source("SessionReadView.kt").contains("LocalSessionReaders.current?.invoke(sessionId, attempt)"))
        assertTrue(source("QuestionnaireScreens.kt").contains("LocalSessionReaders provides { id, refresh -> DesignerSessionReaders(id, refresh) }"))
        assertTrue(source("QuestionnaireReaders.kt").contains(".interview(sessionId).completeness"))
    }
}
