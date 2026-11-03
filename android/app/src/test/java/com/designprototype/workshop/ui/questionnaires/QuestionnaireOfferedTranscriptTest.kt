package com.designprototype.workshop.ui.questionnaires

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QuestionnaireOfferedTranscriptTest {

    @Test
    fun `the default offered-transcript name is a real name, not a placeholder`() {
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
        val seven = transcriptDocumentFileName("Section-D-Q7-transcript", TRANSCRIPT_DOCUMENT_EXTENSION)
        val eight = transcriptDocumentFileName("Section-D-Q8-transcript", TRANSCRIPT_DOCUMENT_EXTENSION)
        assertEquals("Section-D-Q7-transcript.md", seven)
        assertEquals("Section-D-Q8-transcript.md", eight)
        assertNotEquals(seven, eight)
    }

    @Test
    fun `naming a take after its prompt would collide, which is why the host does not`() {
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
        val source = panelSource()
        val inPlate = offeredPlate().split("QuestionnaireTranscriptActions(").size - 1
        val inFile = source.split("QuestionnaireTranscriptActions(").size - 1
        assertEquals("the actions row must be drawn exactly once", 1, inFile)
        assertEquals("and that once must be inside the offered-take plate", 1, inPlate)
    }

    @Test
    fun `both new parameters default, so a host may wire Copy without Download`() {
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
        val imports = panelSource().lines().filter { it.startsWith("import ") }.joinToString("\n")
        assertTrue("the import block was not found", "import androidx.compose" in imports)
        for (forbidden in listOf("MediaStore", "workshop.data.", "LocalContext", "java.io.File")) {
            assertFalse("the panel must import no way to write a file: $forbidden", forbidden in imports)
        }
    }

}
