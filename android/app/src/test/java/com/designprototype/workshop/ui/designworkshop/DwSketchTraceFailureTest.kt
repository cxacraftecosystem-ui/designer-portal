package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwSketchTraceFailureTest {

    private val forbidden = listOf(
        "something went wrong",
        "unknown error",
        "unexpected error",
        "an error occurred",
        "failed to",
        "null",
        "exception",
    )

    @Test
    fun `every failure kind has its own sentence`() {
        val sentences = DwTraceFailureKind.entries.associateWith { dwTraceSentence(it) }
        val distinct = sentences.values.toSet()
        assertEquals(
            "two kinds share a sentence, so a designer is being offered one remedy for two problems: " +
                sentences.entries.groupBy { it.value }.filterValues { it.size > 1 }.values,
            DwTraceFailureKind.entries.size,
            distinct.size,
        )
    }

    @Test
    fun `no sentence is a shrug`() {
        for (kind in DwTraceFailureKind.entries) {
            val sentence = dwTraceSentence(kind).lowercase()
            for (word in forbidden) {
                assertFalse("$kind says \"$word\": $sentence", sentence.contains(word))
            }
        }
    }

    @Test
    fun `every sentence is long enough to say what to do and short enough to read`() {
        for (kind in DwTraceFailureKind.entries) {
            val sentence = dwTraceSentence(kind)
            assertTrue(
                "$kind names no remedy: $sentence",
                listOf("update", "retry", "use ", "raise", "try ", "turn off", "choose")
                    .any { sentence.lowercase().contains(it) }
            )
            assertTrue("$kind is a paragraph, not a sentence: $sentence", sentence.length <= 420)
            assertTrue("$kind does not end a sentence: $sentence", sentence.trim().endsWith("."))
        }
    }

    @Test
    fun `a missing bundle blames the build and an old WebView blames the phone`() {
        val bundle = dwTraceSentence(DwTraceFailureKind.BUNDLE_MISSING)
        assertTrue("a missing bundle must send the designer to an app update: $bundle", bundle.contains("Update the app"))
        assertFalse("a missing bundle must not send anyone to Play", bundle.contains("Play Store"))
        assertTrue(
            "a missing bundle must say the photograph is safe: $bundle",
            bundle.contains("unaffected"),
        )

        val webview = dwTraceSentence(DwTraceFailureKind.SANDBOX_UNSUPPORTED)
        assertTrue("an old WebView must name it: $webview", webview.contains("Android System WebView"))
        assertTrue("an old WebView must name the remedy: $webview", webview.contains("Play Store"))
    }

    @Test
    fun `running out of memory and finding too many shapes name different remedies`() {
        val memory = dwTraceSentence(DwTraceFailureKind.OUT_OF_MEMORY)
        val shapes = dwTraceSentence(DwTraceFailureKind.RESULT_TOO_LARGE)
        assertTrue("memory must name the edge engine: $memory", memory.contains("edge engine"))
        assertTrue("too many shapes must name the speck control: $shapes", shapes.contains("Minimum speck"))
        assertFalse("too many shapes must not blame memory: $shapes", shapes.contains("memory"))
    }

    @Test
    fun `a failure about the photograph says the photograph is unaffected`() {
        for (kind in listOf(DwTraceFailureKind.IMAGE_UNREADABLE, DwTraceFailureKind.PROTOCOL_UNREADABLE)) {
            val sentence = dwTraceSentence(kind)
            assertTrue(
                "$kind must say the record is untouched: $sentence",
                sentence.contains("unaffected") || sentence.contains("nothing has been attached"),
            )
        }
    }

    @Test
    fun `an engine detail is carried up to the cap and dropped past it`() {
        val short = dwTraceSentence(DwTraceFailureKind.ENGINE_ERROR, "That image has no pixels.")
        assertTrue("a short detail must survive: $short", short.contains("That image has no pixels."))

        val long = dwTraceSentence(DwTraceFailureKind.ENGINE_ERROR, "x".repeat(DW_TRACE_DETAIL_MAX + 1))
        assertFalse("a long detail must be dropped", long.contains("xxxxxxxxxx"))
        assertTrue("dropping it must still leave a remedy: $long", long.contains("edge engine"))

        assertFalse(dwTraceSentence(DwTraceFailureKind.ENGINE_ERROR, "   ").endsWith(": "))
    }

    @Test
    fun `the exception message is the sentence`() {
        val failure = DwTraceHostFailure(DwTraceFailureKind.SANDBOX_DIED, "evaluating")
        assertEquals(dwTraceSentence(DwTraceFailureKind.SANDBOX_DIED, "evaluating"), failure.message)
        assertEquals(DwTraceFailureKind.SANDBOX_DIED, failure.kind)
    }
}
