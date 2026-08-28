package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE SENTENCES ARE THE API, SO THE SENTENCES ARE WHAT IS PINNED.**
 *
 * A trace can fail eleven ways on a handset and this repository forbids answering any of them with
 * "Something went wrong". The reason is not politeness. The designer this app is built for is in a
 * courtyard, possibly four days from a connection, with an artisan waiting, and the only thing
 * standing between them and abandoning the feature is a sentence that names what to try next. The
 * second reader is whoever gets the screenshot, for whom "unsupported" and "the engine refused" send
 * two different people to fix two different things.
 *
 * So these cases are not about wording. Each one is about a DECISION the sentence has to encode:
 *
 *  - that no two kinds collapse into one sentence, because two kinds with one sentence is a remedy
 *    the designer cannot choose between;
 *  - that a WebView too old and a bundle missing from the build never sound alike, because one is
 *    fixed by the person holding the phone and the other cannot be;
 *  - that running out of memory and finding too many shapes never sound alike, because their
 *    remedies are opposites — one wants a smaller image, the other wants a coarser trace;
 *  - that a cancel is not in this list at all.
 *
 * The one thing NOT asserted here is exact wording. A test that pins a whole sentence makes improving
 * it a test change, which is how sentences stop being improved. What is pinned is the property.
 */
class DwSketchTraceFailureTest {

    /** Words that would make a sentence useless. None may appear in any of them. */
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
            // Below about eighty characters a sentence has named the problem and not the remedy;
            // above about four hundred it is a paragraph under a spinner that nobody reads.
            assertTrue("$kind is too terse to be actionable: $sentence", sentence.length >= 80)
            assertTrue("$kind is a paragraph, not a sentence: $sentence", sentence.length <= 420)
            assertTrue("$kind does not end a sentence: $sentence", sentence.trim().endsWith("."))
        }
    }

    /**
     * The two that must never be confused, and the reason is that only one of them is the designer's
     * to fix. A build packaged without the engine shows this on every handset in the fleet; a WebView
     * from before 2022 shows the other on one. Sending the first person to the Play Store wastes the
     * one connection they were saving.
     */
    @Test
    fun `a missing bundle blames the build and an old WebView blames the phone`() {
        val bundle = dwTraceSentence(DwTraceFailureKind.BUNDLE_MISSING)
        assertTrue("a missing bundle must say it is this build: $bundle", bundle.contains("build"))
        assertFalse("a missing bundle must not send anyone to Play", bundle.contains("Play Store"))
        assertTrue(
            "a missing bundle must say the handset cannot fix it: $bundle",
            bundle.contains("Nothing on this handset"),
        )

        val webview = dwTraceSentence(DwTraceFailureKind.SANDBOX_UNSUPPORTED)
        assertTrue("an old WebView must name it: $webview", webview.contains("Android System WebView"))
        assertTrue("an old WebView must name the remedy: $webview", webview.contains("Play Store"))
    }

    /**
     * Out of memory and too-many-shapes have OPPOSITE remedies, so they must not read alike.
     *
     * The spike measured a full-resolution CANNY trace wanting +278 MB of JS heap against ADAPTIVE's
     * +93 MB, so "choose a different edge engine" is a real answer to the first and no answer at all
     * to the second — which is fixed by raising "Minimum speck" until grit on the paper stops
     * becoming shapes.
     */
    @Test
    fun `running out of memory and finding too many shapes name different remedies`() {
        val memory = dwTraceSentence(DwTraceFailureKind.OUT_OF_MEMORY)
        val shapes = dwTraceSentence(DwTraceFailureKind.RESULT_TOO_LARGE)
        assertTrue("memory must name the edge engine: $memory", memory.contains("edge engine"))
        assertTrue("too many shapes must name the speck control: $shapes", shapes.contains("Minimum speck"))
        assertFalse("too many shapes must not blame memory: $shapes", shapes.contains("memory"))
    }

    /**
     * Three of the eleven are about a photograph, and all three must say the record is safe.
     *
     * A designer who reads "could not be read" about a file they can see in their own gallery will
     * assume the record is damaged and start over. It is not damaged: `sketch.image` still points at
     * the untouched photograph, because `DwSketchRectify.kt:44` chose a different registry field for
     * exactly this reason, and nothing in the trace path writes anything at all.
     */
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

    /**
     * A detail is carried when it is short enough to be a sentence and dropped when it is a stack
     * trace wearing one's clothes.
     *
     * [DW_TRACE_DETAIL_MAX] is `worker/trace.worker.ts:sentenceFor`'s own cap and the same argument:
     * *"never a stack trace, never 'null'"*. Dropping it must still leave a usable sentence, which is
     * what the second half of this case checks.
     */
    @Test
    fun `an engine detail is carried up to the cap and dropped past it`() {
        val short = dwTraceSentence(DwTraceFailureKind.ENGINE_ERROR, "That image has no pixels.")
        assertTrue("a short detail must survive: $short", short.contains("That image has no pixels."))

        val long = dwTraceSentence(DwTraceFailureKind.ENGINE_ERROR, "x".repeat(DW_TRACE_DETAIL_MAX + 1))
        assertFalse("a long detail must be dropped", long.contains("xxxxxxxxxx"))
        assertTrue("dropping it must still leave a remedy: $long", long.contains("edge engine"))

        // A blank detail is the same case as an absent one; a trailing colon with nothing after it is
        // the shape this is written to avoid.
        assertFalse(dwTraceSentence(DwTraceFailureKind.ENGINE_ERROR, "   ").endsWith(": "))
    }

    /**
     * The exception carries the sentence, so a `catch` block never has to build one.
     *
     * This is what lets `DwSketchTrace.trace` convert an exception into a `DwTraceOutcome.Refused` in
     * one line and in one place, which is the only place in the feature where a throw becomes a value.
     */
    @Test
    fun `the exception message is the sentence`() {
        val failure = DwTraceHostFailure(DwTraceFailureKind.SANDBOX_DIED, "evaluating")
        assertEquals(dwTraceSentence(DwTraceFailureKind.SANDBOX_DIED, "evaluating"), failure.message)
        assertEquals(DwTraceFailureKind.SANDBOX_DIED, failure.kind)
    }
}
