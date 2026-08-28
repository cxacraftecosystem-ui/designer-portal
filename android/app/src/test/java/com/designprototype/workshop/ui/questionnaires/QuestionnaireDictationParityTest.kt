package com.designprototype.workshop.ui.questionnaires

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE MICROPHONES ON THE THREE QUESTIONNAIRE SCREENS, AND THE THREE BOXES THAT MUST NOT HAVE ONE.
 *
 * ── WHY THIS TEST EXISTS ────────────────────────────────────────────────────────────────────────
 *
 * The owner asked twice, in these words: *"all the record pages should have dictation options
 * available, wherever applicable so as to reduce the friction as much as possible"*, and then
 * *"dictation should be a default for other record pages as well."* The first sweep put a microphone
 * under every applicable box on the artisan, product, process, tool and craft forms and flipped
 * `TextInput`'s `dictate` default to TRUE so that an omission could not silently lose one.
 *
 * These three screens were the gap that flip could not close, because none of them builds its boxes
 * from `TextInput` — they draw `OutlinedTextField` directly, and a default in `MainActivity.kt`
 * reaches nothing here. Five boxes across the three had no microphone at all while the WEB's twin of
 * each already had one, which is the parity gap in the direction that matters most: the handset is
 * the device actually carried into the workshop.
 *
 * ── WHY IT IS A SOURCE READ ─────────────────────────────────────────────────────────────────────
 *
 * The property is "this box is drawn with the shared prose control, and that box is not". It is
 * expressible only as the shape of a call, there is no Compose UI harness in this module, and a
 * behaviour test would need one per box. `DashboardTileParityTest` and
 * `sketches-parity-matrix-unit.spec.ts` use the same instrument for the same reason.
 *
 * ── THE RULE BEING PINNED, BOTH WAYS ────────────────────────────────────────────────────────────
 *
 * **A phrase is dictated; a name, a code and a number are not.** A recogniser returns the nearest
 * DICTIONARY word for a token that is not one, so "S1" comes back as "Yes one" and a respondent
 * called Kamla comes back as "camera" — and the respondent's name is the one string these sittings
 * are later searched by. The record forms already draw this line (an artisan's address and notes get
 * a microphone; the name, phone and Aadhaar number do not), and the web's answer page states it in
 * these same words at these same two boxes.
 *
 * ASSERTED IN BOTH DIRECTIONS ON PURPOSE. Only the negative half catches the likelier regression,
 * which is not "somebody removed a microphone" but "somebody added one to the section code because
 * it looked like a text box".
 */
class QuestionnaireDictationParityTest {

    /**
     * The sitting screen: the answer and the note carry microphones, the respondent's name does not.
     *
     * `dictate = !locked` and not a bare `true`, and the reason is worth stating: `enabled = !locked`
     * already greys these boxes when somebody else recorded the answer, and a LIVE microphone over a
     * box that cannot accept text would transcribe a whole answer into nothing. Passing the same
     * predicate to both is what stops the button drawing at all rather than drawing dead.
     */
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

    /**
     * The authoring screen: a title, a description, a section heading, a question and its hint.
     *
     * All five are prose somebody composes, and the web's `app/(protected)/questionnaires/[id]`
     * carries the same five microphones in the same five places.
     */
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

    /**
     * The list screen: the create dialog dictates, the SEARCH box does not.
     *
     * A search box is a filter over a list already on screen, not a record anybody is composing, and
     * nothing in `SearchScreen.kt` dictates either. Naming the exclusion here is what keeps the two
     * decisions from drifting into "the list screen dictates everything with a label".
     */
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

    /**
     * Is [label] drawn by `RecordProseField` with a live `dictate`, rather than a bare Material box?
     *
     * READ BACKWARDS FROM THE LABEL RATHER THAN FORWARDS FROM THE CALL, because these files hold
     * several of each and a forward window would happily match the NEXT box's control. Walking back
     * to the nearest opening call and checking which one it was is exact however the arguments are
     * ordered or however long a comment between them grows — a proximity window is a guess about
     * that length, and this repository has already had one assertion pass for the wrong reason and
     * another fail on a comment somebody added.
     */
    private fun proseFieldFor(screen: String, label: String): Boolean {
        val at = screen.indexOf(label)
        if (at < 0) return false
        val opens = screen.lastIndexOf("Field(", at)
        if (opens < 0) return false
        val call = screen.substring(maxOf(0, opens - 24), at)
        // The label may sit on either control; `dictate =` is what says a microphone is offered.
        return call.contains("RecordProseField(") && screen.substring(opens, minOf(screen.length, at + 400))
            .contains("dictate = ")
    }

    /**
     * A screen's source, found by walking up from wherever the test runner started.
     *
     * The working directory of a Gradle test worker is not something to depend on, and a test that
     * SKIPPED when it could not find its subject would prove nothing on the day somebody moves it.
     * Missing is a failure, loudly — the same helper and the same reasoning as
     * `DashboardTileParityTest` and `DwSketchTraceCropTest`.
     */
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
