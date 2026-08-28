package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A FAILED LOAD MAY NOT BE WORDED AS AN ANSWER — the silent-emptiness class, on the Sketches &
 * prototypes chooser.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE DEFECT THESE PIN
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `SketchesAndPrototypesScreen` wrote a failed list as `emptyList()`, which fell into the `isEmpty()`
 * branch, which renders [DW_SKETCH_CHOOSER_NO_WORKSHOPS]. So a designer on twelve design workshops,
 * standing in a courtyard with no signal, was told *"You are not on any design workshop yet. Once an
 * administrator adds you to one…"* — told they had none, and sent to ask an administrator for the
 * twelve they already had. The failure itself went to the host's transient message line at the bottom
 * of the scrolling column, under the placeholder that had just said the opposite.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THE WORDS, AND NOT THE BRANCHES
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The branch order is now the guard — `listFailure` is tested before `isEmpty()` — and a JVM test
 * cannot compose a `@Composable` to check it; that is the instrumented suite's ground. What a JVM test
 * CAN pin is the property that made the miswiring harmful rather than merely untidy: these are three
 * different facts, so they must be three different sentences, and only one of them is entitled to send
 * anybody to an administrator. Written the other way round — one sentence reused for two states — the
 * branch order stops mattering and the defect can come back through any of them.
 *
 * Pinned in the spirit of `DwRefusalSentenceTruthTest`: the property, not the prose.
 */
class DwSketchChooserSentenceTest {

    private val failures = listOf(
        "offline" to DW_SKETCH_CHOOSER_OFFLINE,
        "refused" to DW_SKETCH_CHOOSER_REFUSED,
        "nothing lost" to DW_SKETCH_CHOOSER_NOTHING_LOST,
    )

    /**
     * THE REGRESSION ITSELF, as a rule about words. Only the state that actually got an answer may
     * name an administrator; a sentence about a request that never landed sends a designer on an
     * errand invented out of a failure.
     */
    @Test
    fun `only the answered-and-none sentence names an administrator`() {
        assertTrue(DW_SKETCH_CHOOSER_NO_WORKSHOPS.contains("administrator"))
        failures.forEach { (name, sentence) ->
            assertFalse(
                "the '$name' sentence sends a designer to an administrator about a request that " +
                    "failed — that is the defect this test exists for",
                sentence.contains("administrator", ignoreCase = true),
            )
        }
    }

    /**
     * AND THE CONVERSE. The empty-state sentence must not hedge into failure language either: a
     * newly onboarded designer being told something "could not" happen would read a correct, ordinary
     * answer as a broken app, and would keep pressing.
     */
    @Test
    fun `the answered-and-none sentence never claims a failure`() {
        listOf("could not", "no connection", "failed", "try again").forEach { hedge ->
            assertFalse(
                "the empty-state sentence contains failure language: '$hedge'",
                DW_SKETCH_CHOOSER_NO_WORKSHOPS.contains(hedge, ignoreCase = true),
            )
        }
    }

    /**
     * THE DISTINCTION ITSELF IS SAID OUT LOUD in the offline sentence, because a reader cannot infer
     * it: an empty list and an unaskable one look identical on screen unless one of them says which
     * it is.
     */
    @Test
    fun `the offline sentence separates an empty list from an unaskable one`() {
        assertTrue(DW_SKETCH_CHOOSER_OFFLINE.contains("could not be asked for"))
        assertTrue(DW_SKETCH_CHOOSER_OFFLINE.contains("not a list with nothing in it"))
    }

    /** RULE 10's other half: a failure must say what it cost, and this one costs nothing. */
    @Test
    fun `the failure block says that nothing is lost, and why it can`() {
        assertTrue(DW_SKETCH_CHOOSER_NOTHING_LOST.startsWith("Nothing is lost:"))
        // The REASON, not just the reassurance — a promise with no mechanism behind it is one a
        // reader has no way to weigh. This screen writes nothing anywhere, which is why it holds.
        assertTrue(DW_SKETCH_CHOOSER_NOTHING_LOST.contains("only reads"))
        // And the route that still works with no signal, because "nothing is lost" is cold comfort
        // to somebody who came here to open a stage.
        assertTrue(DW_SKETCH_CHOOSER_NOTHING_LOST.contains("Design workshops"))
    }

    /** Three states, three sentences. Any two of them being one is how the defect happened. */
    @Test
    fun `the three answers are three different sentences`() {
        val all = listOf(
            DW_SKETCH_CHOOSER_NO_WORKSHOPS,
            DW_SKETCH_CHOOSER_OFFLINE,
            DW_SKETCH_CHOOSER_REFUSED,
        )
        assertEquals("two of the three answers share a sentence", all.size, all.toSet().size)
        all.forEach { assertTrue("an answer is blank", it.isNotBlank()) }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WHAT A 3D MODEL DOES IN THE DELIVERED DOCUMENT
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE FACT, AND ONLY THE FACT.
     *
     * `DW_PROTOTYPE_3D_IN_THE_REPORT` makes one claim about a file this application does not
     * generate: a 3D model attached on stage 13 appears in the ministry .docx as the words
     * "1 document attached". That is `report_builder.format_value`'s output, and the sentence is
     * worth nothing unless it is exactly right — a designer who reads it as "the model is printed"
     * hands over a document a reviewer cannot see the prototype in, and one who reads it as "the
     * file is carried" hands over a document the file is not inside.
     *
     * So the literal is pinned, and so are the two things the sentence must never come to say. The
     * strings themselves are `report_builder.py`'s; TRUE AS OF 2026-08-27, re-check with:
     *
     *     grep -n "document attached" backend/app/services/report_builder.py
     *
     * A KOTLIN TEST CANNOT CHECK THE PYTHON, and that is the limit of this one. It pins that THIS
     * COPY of the claim still contains the words the generator writes; it cannot pin that the
     * generator still writes them. The only real guard is a backend test asserting the two facts
     * where they are decided — see the note in `docs/SKETCHES-PROTOTYPES-PARITY.md`.
     *
     * NOTHING IS ASSERTED ABOUT [DW_TURNTABLE_CAPTURE_ADVICE], deliberately. That constant is craft
     * advice — how many frames, what light — and a reader who disagrees with it must be free to
     * improve it without a test standing in the way. Its own KDoc makes the same separation, which
     * is why the two are two constants rather than one paragraph.
     */
    @Test
    fun `the prototype sentence names the exact words the document generator writes`() {
        assertTrue(
            "the .docx says this and the sentence must quote it",
            DW_PROTOTYPE_3D_IN_THE_REPORT.contains("1 document attached"),
        )
        // NEVER "printed", "shown", "drawn" or "included" ABOUT THE MODEL. `_images` places IMAGE and
        // IMAGE_LIST fields only; no writer in this product can draw a mesh.
        listOf("is printed", "is drawn", "is shown", "is rendered").forEach { promise ->
            assertFalse(
                "the sentence must not promise the model itself reaches the page: $promise",
                DW_PROTOTYPE_3D_IN_THE_REPORT.contains(promise, ignoreCase = true),
            )
        }
        // And it must go on saying what the designer can DO about it, which is the only reason the
        // fact is on a chooser at all.
        assertTrue(
            "the action is the point of saying any of this",
            DW_PROTOTYPE_3D_IN_THE_REPORT.contains("photograph the piece as well"),
        )
    }
}
