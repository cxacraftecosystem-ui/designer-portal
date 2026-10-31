package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.isTempId
import com.designprototype.workshop.data.resolveTempId
import com.designprototype.workshop.qLiveSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M12 (audit 21 Oct 2026): a questionnaire created offline must stop using its temporary id once
 * it has uploaded, and an answer screen opened on a saved copy must leave read-only when the signal
 * returns.
 */
class QuestionnaireOfflineCreateTest {

    private val detail by lazy {
        qLiveSource("app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireDetailScreen.kt").readText()
    }

    @Test
    fun `a landed create resolves to its server id`() {
        assertEquals("q_real", resolveTempId("tmp-1", mapOf("tmp-1" to "q_real")))
        assertTrue(isTempId(resolveTempId("tmp-2", mapOf("tmp-1" to "q_real"))))
    }

    @Test
    fun `the router reopens the detail screen under the server id`() {
        assertTrue(detail.contains("onResolvedId(real)"))
        val main = qLiveSource("app/src/main/java/com/designprototype/workshop/MainActivity.kt").readText()
        assertTrue(main.contains("onResolvedId = { real -> screen = Screen.QuestionnaireDetail(real, from = s.from) }"))
    }

    @Test
    fun `server calls naming the questionnaire wait for its upload`() {
        listOf(
            "The questionnaire could not be attached.",
            "The kind could not be changed.",
            "The questionnaire could not be updated.",
        ).forEach { failure ->
            val before = detail.substringBefore("mutate(\"$failure\")").takeLast(120)
            assertTrue(failure, before.contains("uploadedOrSay()"))
        }
        assertTrue(detail.contains("if (!uploadedOrSay()) return@SittingsCard"))
        assertFalse(QUESTIONNAIRE_STILL_UPLOADING.contains("tmp"))
    }

    @Test
    fun `a saved copy is replaced by the live form once online`() {
        val answer = qLiveSource("app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireAnswerScreen.kt").readText()
        val watcher = answer.substringAfter("LaunchedEffect(questionnaireId, entryId, cachedNotice != null)")
            .substringBefore("val loaded = form")
        assertTrue(watcher.contains("repository.isOnline(appContext)"))
        assertTrue(watcher.contains("cachedNotice = null"))
        // The typing on screen is never overwritten by the reload.
        assertTrue(watcher.contains("!in answers"))
    }
}
