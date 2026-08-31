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

/**
 * The artisan's questionnaire panel, on both clients, and the one line in each that printed a
 * document instead of the words in it.
 *
 * ── WHAT WENT WRONG ───────────────────────────────────────────────────────────────────────────
 *
 * `QuestionnaireResponse.answerText` is a `String?` that has always held prose and, since the web's
 * interview answer box became a rich text box on 2026-08-31, sometimes holds the JSON encoding of a
 * document instead. `ArtisanQuestionnairePanel` in `FieldComponents.kt` printed the column straight,
 * so an answer somebody formatted arrived on this panel as the literal characters
 * `{"blocks":[{"kind":"PARAGRAPH",…}]}`. Not a crash and not a 500 — just the braces, in the place a
 * researcher reads an artisan's answer back, and with nothing anywhere reporting a problem because a
 * JSON-shaped string is not empty and so passes every emptiness check above it. That is the failure
 * `report_builder.format_value` records at its own RICH_TEXT branch, one surface further on.
 *
 * ── AND WHY THE WEB HALF IS ASSERTED HERE TOO ─────────────────────────────────────────────────
 *
 * This was NOT a parity gap: the same panel on the web printed `answer.answerText` just as raw, so
 * both clients were wrong in the same way and would have been fixed one at a time. The two are
 * pinned together so the next reader meets one rule and not two, exactly as `DashboardTileParityTest`
 * reads the web's `DashboardCard.tsx` for its half of a shared decision.
 *
 * ── WHY NOTHING HERE COMPOSES ─────────────────────────────────────────────────────────────────
 *
 * `ArtisanQuestionnairePanel` is a Composable and this is a desktop JVM test, so the defect is
 * decided the two ways it can be without a device: the flattener is EXECUTED, and the call that
 * reaches it is read out of the source. What none of this proves is that a phone paints the card.
 */
class ArtisanAnswerPanelTest {

    /** An answer a researcher could actually produce in that box: a bolded term, then a bullet. */
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
        // Both quotings, because the two ways a document reaches a reader print differently: the
        // stored column is JSON (double quotes) and a value that fell through to a Python `str()` is
        // a repr (single quotes). A test pinning one would pass while the other shipped.
        val shown = questionnaireAnswerPlain(stored)
        assertFalse(shown, shown.contains("{\"blocks\""))
        assertFalse(shown, shown.contains("{'blocks'"))
        assertFalse(shown, shown.contains("PARAGRAPH"))
        assertFalse(shown, shown.contains("BULLET_ITEM"))
    }

    @Test
    fun `an answer written before any of this renders exactly as it did`() {
        // Identity, not "close enough". Every answer in the column today is prose, and a flattener
        // that re-wrapped them would show up as a silent diff on every panel and every export.
        val typed = "Twelve looms, two of them idle."
        assertEquals(typed, questionnaireAnswerPlain(typed))
        assertEquals("", questionnaireAnswerPlain(null))
        assertEquals("", questionnaireAnswerPlain("   "))
        // Braces a researcher typed are a researcher's typing, not a document.
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

/**
 * A file of this repository, found by walking up from wherever the test runner started.
 *
 * The working directory of a Gradle test worker is not something to depend on, and a test that
 * skipped when it could not find its subject would prove nothing on the day somebody moves it.
 * Missing is a failure, loudly. Same helper and same reasoning as `DashboardTileParityTest` — and
 * the `..`-prefixed candidate is what lets it reach out of `android/` and into `frontend/`, which is
 * what the last test above needs.
 */
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
