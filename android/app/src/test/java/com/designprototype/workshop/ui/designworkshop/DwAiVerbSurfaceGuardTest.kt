package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwAiVerbSurfaceGuardTest {

    private val surfaces = listOf(
        "DwAiVerbReviewSheet.kt",
        "DwAiVerbsPanel.kt",
        "DwMediaAiVerbs.kt",
        "DwAiVerbWording.kt",
        "DwAiVerbSurface.kt",
    )

    private fun sourceOf(name: String): String {
        val file = File("src/main/java/com/designprototype/workshop/ui/designworkshop/$name")
        assertTrue(
            "$name is missing — this guard is only meaningful while the file it guards exists, so a " +
                "rename must move this list rather than silently disarming it",
            file.exists()
        )
        return file.readText(Charsets.UTF_8)
    }

    private val forbidden = listOf(
        "onChange(",
        "insertText(",
        "ClipboardManager",
        "LocalClipboardManager",
        "setPrimaryClip",
        "SelectionContainer",
        "ACTION_SEND",
        "createChooser",
    )

    @Test
    fun `no verb surface can put the model words into a field or onto the clipboard`() {
        surfaces.forEach { name ->
            val source = sourceOf(name)
            forbidden.forEach { token ->
                assertFalse(
                    "$name contains `$token`. An AI output is a LAYER that a named person accepts or " +
                        "declines, never text applied in place — see this test's header for why, and " +
                        "`ai_verbs.expand` for the alternative this repository prefers. If the words " +
                        "belong in the field, the designer types them, and they are then that " +
                        "designer's sentences under that designer's name.",
                    source.contains(token)
                )
            }
        }
    }

    @Test
    fun `the review sheet says nothing has been applied and asks for a named acceptance`() {
        val sheet = sourceOf("DwAiVerbReviewSheet.kt")
        assertTrue(
            "the sheet must state that nothing has reached a document yet",
            sheet.contains("Nothing has been put in any document yet")
        )
        assertTrue(
            "accepting must be worded as a person putting their name to it, not as a form control",
            sheet.contains("accept it in my name")
        )
        assertTrue(
            "leaving a layer alone is a real third answer and must be on a labelled control",
            sheet.contains("Leave it for now")
        )
    }

    @Test
    fun `the expansion carries its invention caution where the decision is made`() {
        val sheet = sourceOf("DwAiVerbReviewSheet.kt")
        assertTrue(sheet.contains("EXPANDED"))
        assertTrue(
            "the caution must say the model supplied what is not in the note",
            sheet.contains("was supplied by")
        )
    }
}
