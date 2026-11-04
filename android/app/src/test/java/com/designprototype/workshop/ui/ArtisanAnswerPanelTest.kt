package com.designprototype.workshop.ui

import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.Mark
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.report.toJson
import com.designprototype.workshop.ui.questionnaires.questionnaireAnswerPlain
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtisanAnswerPanelTest {

    private val formatted = RichDoc(
        blocks = listOf(
            RichBlock(
                spans = listOf(
                    RichSpan("The warp is dressed with "),
                    RichSpan("handspun", setOf(Mark.BOLD)),
                    RichSpan(" cotton."),
                ),
            ),
            RichBlock(kind = BlockKind.BULLET_ITEM, spans = listOf(RichSpan("Dyed with indigo"))),
        ),
    )

    private val stored: String get() = toJson(formatted).toString()

    @Test
    fun `a formatted answer is shown as the words in it`() {
        assertEquals(
            "The warp is dressed with handspun cotton.\n• Dyed with indigo",
            questionnaireAnswerPlain(stored),
        )
    }

    @Test
    fun `neither spelling of the envelope reaches the panel`() {
        val shown = questionnaireAnswerPlain(stored)
        assertFalse(shown, shown.contains("{\"blocks\""))
        assertFalse(shown, shown.contains("{'blocks'"))
        assertFalse(shown, shown.contains("PARAGRAPH"))
        assertFalse(shown, shown.contains("BULLET_ITEM"))
    }

    @Test
    fun `an answer written before any of this renders exactly as it did`() {
        val typed = "Twelve looms, two of them idle."
        assertEquals(typed, questionnaireAnswerPlain(typed))
        assertEquals("", questionnaireAnswerPlain(null))
        assertEquals("", questionnaireAnswerPlain("   "))
        assertEquals("{he charges 400 per metre}", questionnaireAnswerPlain("{he charges 400 per metre}"))
    }

    @Test
    fun `the handset panel reads the column through the boundary and not raw`() {
        val source = repoFile(
            "src/main/java/com/designprototype/workshop/ui/FieldComponents.kt",
            "app/src/main/java/com/designprototype/workshop/ui/FieldComponents.kt",
            "android/app/src/main/java/com/designprototype/workshop/ui/FieldComponents.kt",
        ).readText(Charsets.UTF_8)

        assertTrue(
            "FieldComponents.kt no longer flattens the stored answer before printing it",
            source.contains("Text(questionnaireAnswerPlain(answer.answerText)"),
        )
        assertFalse(
            "the raw column is being printed again",
            source.contains("Text(answer.answerText.orEmpty()"),
        )
    }

    @Test
    fun `the web panel reads it through its own boundary too`() {
        val source = repoFile(
            "../frontend/components/ArtisanQuestionnairePanel.tsx",
            "frontend/components/ArtisanQuestionnairePanel.tsx",
        ).readText(Charsets.UTF_8)

        assertTrue(
            "ArtisanQuestionnairePanel.tsx no longer flattens the stored answer",
            source.contains("plainFromStoredRichText(answer.answerText)"),
        )
        assertFalse(
            "the raw column is being printed again on the web",
            source.contains(">{answer.answerText}<"),
        )
    }
}

private fun repoFile(vararg relative: String): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        for (path in relative) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
        }
        dir = dir.parentFile
    }
    throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
}
