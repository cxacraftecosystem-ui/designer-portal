package com.designprototype.workshop.ui.questionnaires

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a questionnaire's screen says about the connection — see `QuestionnaireOfflineCopy.kt`. */
class QuestionnaireOfflineCopyTest {

    private val all = listOf(
        WORKBOOK_UPLOAD_NEEDS_CONNECTION,
        WORKBOOK_UPLOAD_OFFLINE,
        SITTING_START_NEEDS_CONNECTION,
        SITTING_START_OFFLINE,
        REUSE_KEPT_OFFLINE,
    )

    @Test
    fun `the two online-only actions say they need a connection, on the control and when tapped`() {
        assertTrue(WORKBOOK_UPLOAD_NEEDS_CONNECTION.contains("connection"))
        assertTrue(WORKBOOK_UPLOAD_OFFLINE.contains("needs a connection"))
        assertTrue(SITTING_START_NEEDS_CONNECTION.contains("needs a connection"))
        assertTrue(SITTING_START_OFFLINE.contains("needs a connection"))
    }

    @Test
    fun `the tapped messages say what to do next`() {
        assertTrue(WORKBOOK_UPLOAD_OFFLINE.contains("upload it when you have a signal"))
        assertTrue(SITTING_START_OFFLINE.contains("Try again when you have a signal"))
    }

    @Test
    fun `a copy made with no signal is said to be kept and made later, not lost`() {
        assertTrue(REUSE_KEPT_OFFLINE.startsWith("Kept on this phone."))
        assertTrue(REUSE_KEPT_OFFLINE.contains("as soon as there is a connection"))
    }

    @Test
    fun `no sentence names an internal or admits a gap`() {
        val banned = listOf("outbox", "endpoint", "API", "server id", "not built", "not supported", "QFormOp", "409")
        all.forEach { sentence ->
            banned.forEach { word ->
                assertFalse("\"$sentence\" says $word", sentence.contains(word, ignoreCase = true))
            }
        }
    }
}
