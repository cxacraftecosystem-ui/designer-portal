package com.designprototype.workshop.ui.questionnaires

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QuestionnaireDictationParityTest {

    @Test
    fun `the sitting screen dictates the answer and the note, and not the respondent`() {
        val screen = source("QuestionnaireAnswerScreen.kt")

        assertTrue(
            "the Answer box must be the shared prose control — it is the longest typing in this app",
            screen.contains("""label = "Answer"""") && proseFieldFor(screen, """label = "Answer""""),
        )
        assertTrue(
            "the per-question note must carry a microphone too; it is where \"she hesitated here\" goes",
            proseFieldFor(screen, """label = "Note (optional)""""),
        )
        assertTrue(
            "the sitting's Notes box is prose and is dictated on the web already",
            proseFieldFor(screen, """label = "Notes""""),
        )
        assertTrue(
            "a locked answer must not offer a live microphone over a box that cannot accept its text",
            screen.contains("dictate = !locked"),
        )
        assertFalse(
            "the respondent's name must stay a plain box — a recogniser returns the nearest " +
                "dictionary word, and this is the string the sittings are searched by",
            proseFieldFor(screen, """label = "Who is answering""""),
        )
    }

    @Test
    fun `the authoring screen dictates every box a designer composes`() {
        val screen = source("QuestionnaireDetailScreen.kt")

        for (label in listOf(
            """label = "Title *"""",
            """label = "Description"""",
            """label = "Question *"""",
            """label = "Hint shown under the question"""",
            """label = "Notes"""",
        )) {
            assertTrue("$label must be drawn with RecordProseField", proseFieldFor(screen, label))
        }
        assertTrue(
            "the section-title dialog is shared by both call sites and must dictate through `label`",
            proseFieldFor(screen, "label = label"),
        )
        assertFalse(
            "the respondent's name stays bare here as well; the two copies of this dialog must agree",
            proseFieldFor(screen, """label = "Who is answering""""),
        )
    }

    @Test
    fun `the list screen dictates the new questionnaire and leaves the search box alone`() {
        val screen = source("QuestionnaireListScreen.kt")

        assertTrue("a new questionnaire's title is prose", proseFieldFor(screen, """label = "Title *""""))
        assertTrue("and so is its description", proseFieldFor(screen, """label = "Description""""))
        assertFalse(
            "the search box is a filter, not a record — dictating it would put a microphone on a " +
                "control whose whole job is to be typed into and cleared",
            proseFieldFor(screen, """label = "Search""""),
        )
    }

    private fun proseFieldFor(screen: String, label: String): Boolean {
        val at = screen.indexOf(label)
        if (at < 0) return false
        val opens = screen.lastIndexOf("Field(", at)
        if (opens < 0) return false
        val call = screen.substring(maxOf(0, opens - 24), at)
        return call.contains("RecordProseField(") && screen.substring(opens, minOf(screen.length, at + 400))
            .contains("dictate = ")
    }

    private fun source(fileName: String): String {
        val relative = listOf(
            "src/main/java/com/designprototype/workshop/ui/questionnaires/$fileName",
            "app/src/main/java/com/designprototype/workshop/ui/questionnaires/$fileName",
            "android/app/src/main/java/com/designprototype/workshop/ui/questionnaires/$fileName",
        )
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile
        }
        throw AssertionError("$fileName not found from ${File(".").absolutePath}")
    }
}
