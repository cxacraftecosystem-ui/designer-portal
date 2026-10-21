package com.designprototype.workshop.data

import com.designprototype.workshop.qLiveSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M13 (audit 21 Oct 2026): retried questionnaire creates must not make duplicates. */
class QuestionnaireIdempotencyKeysTest {

    private val serverRule = Regex("^[A-Za-z0-9_-]{8,128}$")

    @Test
    fun `adoption keys are stable per step and valid for the server`() {
        val first = adoptionIdempotencyKey("rq_1.dpwq", "s0", "q3")
        assertEquals(first, adoptionIdempotencyKey("rq_1.dpwq", "s0", "q3"))
        assertTrue(first != adoptionIdempotencyKey("rq_1.dpwq", "s0", "q4"))
        assertTrue(first, serverRule.matches(first))
        assertTrue(serverRule.matches(adoptionIdempotencyKey("", "q")))
        assertTrue(serverRule.matches(adoptionIdempotencyKey("x".repeat(300), "q")))
        assertTrue(serverRule.matches(adoptionIdempotencyKey("कारीगर", "q")))
    }

    @Test
    fun `adoption and sitting creates send the header`() {
        val api = qLiveSource("app/src/main/java/com/designprototype/workshop/data/WorkshopRepositoryApi.kt").readText()
        val entry = api.substringAfter("suspend fun createCustomEntry(").substringBefore("): CustomEntryDto")
        assertTrue(entry.contains("@Header(\"Idempotency-Key\")"))
        val repo = qLiveSource("app/src/main/java/com/designprototype/workshop/data/WorkshopRepository.kt").readText()
        assertTrue(repo.contains("idempotencyKey = adoptionIdempotencyKey(row.id, \"q\")"))
        assertTrue(repo.contains("idempotencyKey = adoptionIdempotencyKey(row.id, \"s\$sectionIndex\")"))
        assertTrue(repo.contains("idempotencyKey = adoptionIdempotencyKey(row.id, \"s\$sectionIndex\", \"q\$questionIndex\")"))
        val detail = qLiveSource("app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireDetailScreen.kt").readText()
        assertTrue(detail.contains("idempotencyKey = key,"))
    }
}
