package com.designprototype.workshop.data

import com.designprototype.workshop.qLiveSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L11 (audit 21 Oct 2026): the stale Android sentence and comments about questionnaires. */
class QueuedAuthoringMessageTest {

    @Test
    fun `an online phone is never told to wait for a signal`() {
        assertEquals("Saved on this phone; added when online.", queuedAuthoringMessage(online = false, waitsForParent = false, earlierRefused = false))
        listOf(
            queuedAuthoringMessage(online = true, waitsForParent = false, earlierRefused = true),
            queuedAuthoringMessage(online = true, waitsForParent = true, earlierRefused = false),
            queuedAuthoringMessage(online = true, waitsForParent = false, earlierRefused = false),
        ).forEach { assertFalse(it, it.contains("when online")) }
        assertTrue(queuedAuthoringMessage(online = true, waitsForParent = false, earlierRefused = true).contains("not accepted"))
    }

    @Test
    fun `the stale comments are gone`() {
        val api = qLiveSource("app/src/main/java/com/designprototype/workshop/data/WorkshopRepositoryApi.kt").readText()
        assertFalse(api.contains("Deactivation is what this API has instead of a delete"))
        assertFalse(api.contains("this\n    // API's stand-in for a delete"))
        val detail = qLiveSource("app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireDetailScreen.kt").readText()
        assertFalse(detail.contains("which will fail with a network error while"))
    }
}
