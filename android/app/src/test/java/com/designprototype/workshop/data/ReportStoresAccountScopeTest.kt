package com.designprototype.workshop.data

import com.designprototype.workshop.qLiveSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M15 (audit 21 Oct 2026): the two report stores hold respondents' names and answers, so a copy is
 * served only to the account that read it, as the web's questionnaire cache already refuses.
 */
class ReportStoresAccountScopeTest {

    @Test
    fun `only the owning account is served a copy`() {
        assertTrue(dwCacheReadableBy("usr_a", "usr_a"))
        assertFalse(dwCacheReadableBy("usr_a", "usr_b"))
        assertFalse(dwCacheReadableBy("usr_a", null))
        // A copy an earlier build wrote carries no owner and is refused to everybody.
        assertFalse(dwCacheReadableBy("", "usr_a"))
    }

    @Test
    fun `the owner survives a round trip and an old copy decodes with none`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val cache = DwQuestionnaireCache(workshopId = "dw1", ownerUserId = "usr_a")
        val back = json.decodeFromString(DwQuestionnaireCache.serializer(), json.encodeToString(DwQuestionnaireCache.serializer(), cache))
        assertEquals("usr_a", back.ownerUserId)
        assertEquals("", json.decodeFromString(DwTranscriptCache.serializer(), """{"workshopId":"dw1"}""").ownerUserId)
    }

    @Test
    fun `every reader and writer names the account`() {
        val q = qLiveSource("app/src/main/java/com/designprototype/workshop/data/DwQuestionnaireStore.kt").readText()
        val t = qLiveSource("app/src/main/java/com/designprototype/workshop/data/DwTranscriptStore.kt").readText()
        assertTrue(q.contains("suspend fun load(context: Context, workshopId: String, viewerId: String?)"))
        assertTrue(t.contains("suspend fun load(context: Context, workshopId: String, viewerId: String?)"))
        assertTrue(q.contains("dwCacheReadableBy(it.ownerUserId, viewerId)"))
        assertTrue(t.contains("dwCacheReadableBy(it.ownerUserId, viewerId)"))
    }
}
