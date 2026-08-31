package com.designprototype.workshop.ui.questionnaires

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * COPY AND DOWNLOAD OVER AN *OFFERED* TAKE — the half of the transcript surface that had neither.
 *
 * ── WHAT THE GAP COST, SO THAT THIS IS NOT READ AS PARITY BOOKKEEPING ──────────────────────────
 *
 * `QuestionnaireQuickTranscript` draws the amber plate that appears when a newer transcript arrives
 * for an answer box somebody has already typed in. It had exactly two buttons: **Add to answer**,
 * which APPENDS the machine's words to the person's own and cannot be undone from this screen, and
 * **Discard**, which throws the take away. Neither keeps the take as a separate second reading.
 *
 * So a researcher who wanted one had a single option left: retype it off the screen, in a courtyard,
 * from a box that empties the instant either button is pressed. The web has never had that problem —
 * `QuickTranscript` wraps the same offer in a `MarkdownDocument`, and that component IS Copy and
 * Download. This lane gave the handset the same two, through the row the STORED transcript already
 * uses, so a take saved from an offer and the same take saved from the media card are one file.
 *
 * ── WHAT IS ASSERTED, AND WITH WHICH INSTRUMENT ────────────────────────────────────────────────
 *
 * The naming rule is pure and is asserted directly, including the collision it exists to avoid. The
 * plate itself is a composable inside a module with no UI test harness, and its call site is buried
 * in a 19,000-line file, so both are read from the source — the instrument [DesignWorkshopCardTest]
 * established here for exactly this shape of rule.
 */
class QuestionnaireOfferedTranscriptTest {

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // The name a saved take lands under.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the default offered-transcript name is a real name, not a placeholder`() {
        // The two parameters carry defaults so a surface may wire Copy WITHOUT Download — which is
        // the state every host is in until it has a repository and a Context to write bytes with.
        // The default must therefore still survive the cleaner: a base that cleaned down to nothing
        // would land as `transcript.md` and tell a researcher nothing about which take they saved.
        assertEquals(
            "Offered-transcript.md",
            transcriptDocumentFileName(QUESTIONNAIRE_OFFERED_TRANSCRIPT_FILENAME_BASE, TRANSCRIPT_DOCUMENT_EXTENSION)
        )
        assertNotEquals(
            "the default must not collapse to the cleaner's own fallback",
            "transcript.md",
            transcriptDocumentFileName(QUESTIONNAIRE_OFFERED_TRANSCRIPT_FILENAME_BASE, TRANSCRIPT_DOCUMENT_EXTENSION)
        )
    }

    @Test
    fun `two questions of one section save under two different names`() {
        // What the host actually passes: section code and question number. Short, unique inside a
        // sitting, and the two things the researcher has written on the page in front of them.
        val seven = transcriptDocumentFileName("Section-D-Q7-transcript", TRANSCRIPT_DOCUMENT_EXTENSION)
        val eight = transcriptDocumentFileName("Section-D-Q8-transcript", TRANSCRIPT_DOCUMENT_EXTENSION)
        assertEquals("Section-D-Q7-transcript.md", seven)
        assertEquals("Section-D-Q8-transcript.md", eight)
        assertNotEquals(seven, eight)
    }

    @Test
    fun `naming a take after its prompt would collide, which is why the host does not`() {
        // THE REASON THE RULE IS WHAT IT IS, pinned so that "name it after the question, that reads
        // better" fails here rather than in a Downloads folder. A questionnaire prompt runs to two
        // thousand characters and `transcriptDocumentFileName` cuts at sixty; real prompts in one
        // section share far more than sixty characters of preamble, so two takes would arrive under
        // ONE name — and the second save silently becomes a duplicate the researcher cannot tell
        // from the first.
        val shared = "Describe in full the raw materials this artisan uses, and for each one say "
        val promptA = shared + "where it is bought."
        val promptB = shared + "how much it costs."
        assertTrue("the two prompts must genuinely differ", promptA != promptB)
        assertEquals(
            "sixty characters of a shared preamble is the whole of both file names",
            transcriptDocumentFileName(promptA, TRANSCRIPT_DOCUMENT_EXTENSION),
            transcriptDocumentFileName(promptB, TRANSCRIPT_DOCUMENT_EXTENSION)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // The plate and its call site, read from the source.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A file of this repository, with CRLF normalised to LF before anything slices it.
     *
     * The tree is CRLF and every fragment matched below spans lines, so a slice taken on a literal
     * `"\n"` finds nothing in the file as it sits on disk — and the test would then pass against an
     * empty haystack, proving nothing at all. Missing is a failure, loudly, for the same reason.
     */
    private fun repoText(vararg relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
    }

    private fun panelSource(): String = repoText(
        "src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireTranscriptPanel.kt",
        "app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireTranscriptPanel.kt",
        "android/app/src/main/java/com/designprototype/workshop/ui/questionnaires/QuestionnaireTranscriptPanel.kt",
    )

    private fun mainActivitySource(): String = repoText(
        "src/main/java/com/designprototype/workshop/MainActivity.kt",
        "app/src/main/java/com/designprototype/workshop/MainActivity.kt",
        "android/app/src/main/java/com/designprototype/workshop/MainActivity.kt",
    )

    /** The offered-take plate alone — the block guarded by `offered?.let`. */
    private fun offeredPlate(): String {
        val source = panelSource()
        val start = source.indexOf("offered?.let { text ->")
        assertTrue(
            "the offered-take plate was not found — if `offered` was renamed, follow the rename",
            start >= 0
        )
        val end = source.indexOf("\n        problem?.let {", start)
        assertTrue("the end of the offered plate was not found", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `the offered take is drawn with Copy and Download`() {
        // The shared row and not a second pair of buttons: bytes, file name, extension and refusal
        // wording all have to match what the stored transcript does, because a take saved from here
        // and the same take saved from the media card must be one file with one name.
        val plate = offeredPlate()
        assertTrue(
            "the offered take must draw QuestionnaireTranscriptActions:\n$plate",
            "QuestionnaireTranscriptActions(" in plate
        )
        assertTrue("the row must be handed the offer's own text", "text = text," in plate)
        assertTrue("and the host's file name", "filenameBase = filenameBase," in plate)
        assertTrue("and the host's saver", "onSave = onSave" in plate)
    }

    @Test
    fun `the buttons live inside the plate, so they cannot appear when there is no take`() {
        // Drawn outside `offered?.let`, the row would sit under every one of two hundred answer
        // boxes offering to copy and save an empty string. The plate is the only place a take exists.
        val source = panelSource()
        val inPlate = offeredPlate().split("QuestionnaireTranscriptActions(").size - 1
        val inFile = source.split("QuestionnaireTranscriptActions(").size - 1
        assertEquals("the actions row must be drawn exactly once", 1, inFile)
        assertEquals("and that once must be inside the offered-take plate", 1, inPlate)
    }

    @Test
    fun `both new parameters default, so a host may wire Copy without Download`() {
        // `onSave = null` draws no Download button at all — the same real state
        // `QuestionnaireTranscriptActions` documents, and the state a surface composed without a
        // repository is genuinely in. A required parameter would force such a host to pass a
        // saver it cannot honour, which is a button that refuses when pressed.
        val source = panelSource()
        assertTrue(
            "filenameBase must default",
            "filenameBase: String = QUESTIONNAIRE_OFFERED_TRANSCRIPT_FILENAME_BASE," in source
        )
        assertTrue(
            "onSave must default to null",
            "onSave: ((fileName: String) -> Unit)? = null," in source
        )
    }

    @Test
    fun `the panel writes no bytes of its own`() {
        // WHY THE SAVER IS THE CALLER'S. The one function in this app that puts a file in Downloads
        // lives on `WorkshopRepository` and was learned from field failures — the IS_PENDING
        // handshake, the pre-Q permission check, the `filesDir` fallback, and the read-back of the
        // name MediaProvider actually used. A second copy here would be a second copy to get wrong.
        //
        // ASSERTED ON THE IMPORTS, not on the whole file. The reasoning above is written out in the
        // panel's own KDoc, so a search of the source for `WorkshopRepository` finds the comment that
        // forbids it and fails on its own explanation. What cannot lie is what the file is allowed to
        // NAME: a panel importing neither a repository, a Context nor MediaStore has nothing it could
        // write bytes with, whatever its prose says.
        val imports = panelSource().substringBefore("/**")
        assertTrue("the import block was not found", "import androidx.compose" in imports)
        for (forbidden in listOf("MediaStore", "workshop.data.", "LocalContext", "java.io.File")) {
            assertFalse("the panel must import no way to write a file: $forbidden", forbidden in imports)
        }
    }

    @Test
    fun `the host saves an offered take through the app's one writer`() {
        // And it re-reads the offer at the press rather than capturing it, so an offer that has just
        // been accepted or discarded cannot still be written to a file from a stale closure.
        val source = mainActivitySource()
        // Matched with `\s+` rather than by transcribing the file's own indentation, which at this
        // depth is forty-odd columns inside eight nested composables — a test that pinned it would
        // fail on a reformat that changed nothing about the rule it is defending.
        val call = Regex(
            """onSave = \{ fileName ->\s+offeredTranscript\[question\.id]\?\.let \{ offer ->\s+""" +
                """saveTranscriptToDownloads\(context, repository, scope, fileName, offer\)"""
        )
        assertTrue(
            "the per-question offer must save through saveTranscriptToDownloads, re-reading the offer",
            call.containsMatchIn(source)
        )
        assertTrue(
            "and be named by section code and question number, not by the prompt",
            "filenameBase = \"Section-\${section.code}-Q\${question.sortOrder}-transcript\"," in source
        )
    }
}
