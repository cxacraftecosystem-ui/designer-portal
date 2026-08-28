package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **THE TWELVE STAGE NAMES AND THE SIX PROTECTED KNOB STRINGS, PINNED AGAINST THE VENDORED ENGINE.**
 *
 * ── WHY THESE TWO TABLES GET A TEST AND THE REST OF THE SURFACE DOES NOT ──────────────────────
 *
 * Everything else in `DwSketchTrace*.kt` is a design decision that can be argued about. These two are
 * TRANSCRIPTIONS of a file nobody in this repository may edit — `frontend/lib/trace/engine/` is
 * vendored verbatim from the product owner's own codebase and hashed file by file in
 * `UPSTREAM-MANIFEST.txt` — and each fails silently and differently if it drifts:
 *
 *  · **A stage id that no longer exists** does not raise. [DwTraceProgressWeights] simply stops
 *    finding it, falls back to the engine's own `index / 12`, and the bar quietly goes back to being
 *    the thing this repository spent a page explaining is not a time estimate.
 *  · **A knob string that no longer matches** does not raise either, and is worse.
 *    `sanitizeAutoParams` trims and de-duplicates the hand-tuned list but does NOT validate it against
 *    `KNOB_NAMES` (`params.ts:702-710`), so a typo means auto-detection silently overwrites a value a
 *    designer set by hand, and nothing anywhere says so.
 *
 * `UPSTREAM-MANIFEST.txt` answers "has the vendored copy drifted?". It cannot answer "has OUR copy of
 * its strings drifted?" — a SHA-256 of `pipeline.ts` says nothing about a Kotlin list. That is this
 * test's whole job, and it is the same gap that makes a hand-written Kotlin port of the engine
 * unverifiable.
 */
class DwSketchTraceStagesTest {

    private fun vendored(path: String): String {
        val file = File("../../frontend/lib/trace/$path")
        assertTrue(
            "expected the vendored engine at ${file.absolutePath}. This test pins the handset's copy " +
                "of two of its tables; if the tree moved, fix the path — do not delete the assertion, " +
                "because a silent copy of an engine's contract is how a progress bar starts lying and " +
                "a hand-tuned value starts being overwritten.",
            file.exists(),
        )
        return file.readText(Charsets.UTF_8)
    }

    // ---------------------------------------------------------------------------------------------
    // The stages
    // ---------------------------------------------------------------------------------------------

    /**
     * The twelve `{ id, label }` pairs, in execution order, exactly as `pipeline.ts` declares them.
     *
     * Order is asserted and not just membership, because the weights and the "Stage 7 of 12" sentence
     * are both positional: a table in a different order would report a plausible wrong stage rather
     * than failing.
     */
    @Test
    fun `the twelve stages are the engine's own, in the engine's own order`() {
        val pipeline = vendored("engine/pipeline.ts")
        val declared = Regex("\\{ id: '([a-z]+)', label: '([^']+)' }")
            .findAll(pipeline)
            .map { DwTraceStage(it.groupValues[1], it.groupValues[2]) }
            .toList()

        assertEquals(
            "the vendored pipeline should declare twelve stages; it declares ${declared.size}. If a " +
                "newer upstream copy added one, decide here what the progress bar does about it — the " +
                "surface keys its rows on these ids (`pipeline.ts:210`).",
            12,
            declared.size,
        )
        assertEquals(
            "DW_TRACE_STAGES has drifted from `pipeline.ts`'s STAGES table",
            declared,
            DW_TRACE_STAGES,
        )
        assertEquals(12, DW_TRACE_STAGE_COUNT)
        assertEquals(
            "the stage ids must be unique; the weights are keyed by them",
            DW_TRACE_STAGES.size,
            DW_TRACE_STAGES.map { it.id }.toSet().size,
        )
        DW_TRACE_SLOW_STAGE_IDS.forEach { id ->
            assertTrue("`$id` is named as a slow stage but is not a stage", dwTraceStageIndex(id) >= 0)
        }
    }

    /** The sentence a screen reader hears, and the fallback for a stage this build does not know. */
    @Test
    fun `the progress sentence names the stage and its position`() {
        assertEquals(
            "Detecting edges. Stage 7 of 12.",
            dwTraceProgressSentence(DwTraceProgress("edge", "Detecting edges", 0.5f)),
        )
        assertEquals(
            "a stage this build has never heard of is described by the engine's own label alone, " +
                "rather than by a position that would be wrong",
            "Doing something new",
            dwTraceProgressSentence(DwTraceProgress("brand-new", "Doing something new", 0.5f)),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The bar
    // ---------------------------------------------------------------------------------------------

    /**
     * Before this device has finished a trace, the bar is the engine's own stage count — and says so.
     *
     * The `index / 12` fractions are asserted because they are what makes the unweighted bar honest
     * about being a count: they are evenly spaced whatever the stages cost, and the last one the
     * engine ever emits is 0.917 rather than 1.0.
     */
    @Test
    fun `the unweighted bar is the engine's own stage count`() {
        val weights = DwTraceProgressWeights.Unweighted
        assertFalse("nothing has been measured yet, and the panel says so", weights.measured)
        assertEquals(0f, weights.fractionAt("prepare", 0f), 1e-6f)
        assertEquals(6f / 12f, weights.fractionAt("edge", 0f), 1e-6f)
        assertEquals(11f / 12f, weights.fractionAt("document", 0f), 1e-6f)
        assertTrue(
            "the engine's fraction never reaches 1.0 — the last event is 0.917 and then the result " +
                "arrives, so the surface has to snap to complete rather than wait for a full bar",
            weights.fractionAt("document", 0f) < 1f,
        )
    }

    /**
     * After one trace, the bar is weighted by what THIS device measured.
     *
     * The numbers below are the shape the feasibility spike measured at the product's own input cap:
     * one stage, `edge`, was 13,037 of a 16,655 ms trace. Unweighted, that stage begins at 0.5 and the
     * bar then sits there for four fifths of the wait; weighted, it begins where the work actually
     * begins.
     */
    @Test
    fun `a measured bar puts the long stage where the work is`() {
        val timings = listOf(
            DwTraceStageTiming("prepare", "Preparing image", 200L),
            DwTraceStageTiming("matte", "Separating background", 0L),
            DwTraceStageTiming("crop", "Cropping to the subject", 0L),
            DwTraceStageTiming("gray", "Converting to grey", 30L),
            DwTraceStageTiming("denoise", "Reducing noise", 400L),
            DwTraceStageTiming("contrast", "Enhancing contrast", 300L),
            DwTraceStageTiming("edge", "Detecting edges", 13037L),
            DwTraceStageTiming("cleanup", "Cleaning up", 500L),
            DwTraceStageTiming("skeleton", "Thinning strokes", 600L),
            DwTraceStageTiming("distance", "Measuring stroke width", 0L),
            DwTraceStageTiming("vectorize", "Tracing vectors", 1500L),
            DwTraceStageTiming("document", "Assembling document", 88L),
        )
        val weights = DwTraceProgressWeights.from(timings)
        assertTrue("these weights came from a real run", weights.measured)

        val edge = weights.fractionAt("edge", 0f)
        assertTrue(
            "unweighted, `edge` begins at 0.5 and the bar then stalls there for most of the trace; " +
                "weighted it must begin much earlier, because almost everything is still to come",
            edge < 0.09f,
        )
        val vectorize = weights.fractionAt("vectorize", 0f)
        assertTrue("`vectorize` begins after `edge` finishes", vectorize > 0.8f)
        // Monotonic, because a bar that goes backwards is worse than no bar.
        var previous = -1f
        DW_TRACE_STAGES.forEach { stage ->
            val at = weights.fractionAt(stage.id, 0f)
            assertTrue("the weighted bar went backwards at `${stage.id}`", at >= previous)
            previous = at
        }
    }

    /** A preview reports no timings at all, and an instant trace sums to zero. Neither may divide. */
    @Test
    fun `empty or zero timings fall back rather than dividing by nothing`() {
        assertFalse(DwTraceProgressWeights.from(emptyList()).measured)
        assertFalse(
            DwTraceProgressWeights.from(
                listOf(DwTraceStageTiming("prepare", "Preparing image", 0L)),
            ).measured,
        )
        assertEquals(
            "an unknown stage falls back to the fraction the engine itself sent",
            0.42f,
            DwTraceProgressWeights.from(emptyList()).fractionAt("nonesuch", 0.42f),
            1e-6f,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The knobs
    // ---------------------------------------------------------------------------------------------

    /**
     * The six wire strings `restoreHandTuned` matches on, exactly as `params.ts` spells them.
     *
     * `params.ts:725-731` calls them "a contract with the UI: the Android editor's `KNOBS` table uses
     * exactly these labels". This IS that Android editor's table, four years and one rewrite later,
     * and the contract has never been exercised by a shipping client — which is precisely why it is
     * pinned before anybody offers `AutoMode.APPLY` and finds out the hard way that a typo here is
     * unprotected rather than rejected.
     */
    @Test
    fun `the six protected knob names are the engine's own`() {
        val params = vendored("engine/params.ts")
        val block = params.substringAfter("export const Knobs = {").substringBefore("} as const;")
        assertTrue("`params.ts` no longer declares a `Knobs` table", block.isNotBlank())

        val declared = Regex("([A-Z_]+): '([^']+)',").findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            "the engine should protect six knobs; it protects ${declared.size}",
            6,
            declared.size,
        )
        assertEquals(
            "DW_TRACE_KNOBS has drifted from `params.ts`'s Knobs table. A wrong string here is not a " +
                "compile error and not a runtime error — it is auto-detection silently overwriting a " +
                "value a designer set by hand.",
            declared,
            DW_TRACE_KNOBS,
        )
        assertEquals(declared.values.toList(), DW_TRACE_KNOB_NAMES)
    }

    /**
     * Every protected knob names a control this panel actually draws.
     *
     * The wire string and the label are deliberately different — the engine says "corner threshold"
     * and the panel says "Keep corners" — so the mapping is the thing that has to be right, and a
     * knob pointing at a key no control uses would be a protection that protects nothing.
     */
    @Test
    fun `every protected knob maps onto a control the panel draws`() {
        val keys = DW_TRACE_CONTROLS.map { it.key }.toSet()
        DW_TRACE_KNOB_NAMES.forEach { name ->
            val key = DW_TRACE_KNOB_KEY_FOR_NAME[name]
            assertNotNull("no control key is mapped for the protected knob `$name`", key)
            assertTrue(
                "`$name` maps to `$key`, which no control on this panel draws",
                keys.any { it == key },
            )
        }
        assertEquals(
            "the mapping must cover every protected knob and nothing else",
            DW_TRACE_KNOB_NAMES.toSet(),
            DW_TRACE_KNOB_KEY_FOR_NAME.keys,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The "this control does nothing right now" sentences
    // ---------------------------------------------------------------------------------------------

    /**
     * The inactive-reason table, checked against the pipeline's own branching.
     *
     * The assertions below are deliberately about the branches this repository can be wrong about
     * rather than about every combination: each corresponds to a line in `pipeline.ts` that decides
     * whether a parameter is read at all, and the MEDIAN one is the trap — it is the branch the
     * `sketch` subject selects, which is the subject this stage seeds by default.
     */
    @Test
    fun `a control the pipeline is not reading says so`() {
        val pipeline = vendored("engine/pipeline.ts")
        assertTrue(
            "`pipeline.ts` no longer computes `skeletonize && !outlineMode`, so the outline-mode " +
                "sentence may no longer be true",
            pipeline.contains("p.cleanup.skeletonize && !outlineMode"),
        )
        assertTrue(
            "the MEDIAN branch no longer reads `medianRadius`, so the sentence about the noise slider " +
                "being inert may no longer be true",
            pipeline.contains("Denoise.median(grey, p.preprocess.medianRadius)"),
        )

        val outline = values(
            "output.vectorMode" to DwTraceValue.Choice("OUTLINE"),
            "cleanup.skeletonize" to DwTraceValue.Flag(true),
        )
        val skeletonize = DW_TRACE_TOGGLES.first { it.key == "cleanup.skeletonize" }
        assertTrue(
            "outline mode traces region boundaries, so the thinning toggle does nothing",
            dwTraceInactiveReason(skeletonize, outline) != null,
        )

        val median = values(
            "preprocess.denoise" to DwTraceValue.Choice("MEDIAN"),
            "preprocess.denoiseStrength" to DwTraceValue.Num(0.5),
        )
        val strength = DW_TRACE_SLIDERS.first { it.key == "preprocess.denoiseStrength" }
        assertTrue(
            "the `sketch` subject selects MEDIAN, and MEDIAN reads a radius rather than this slider — " +
                "the panel must say so, because nothing on the portal does",
            dwTraceInactiveReason(strength, median) != null,
        )

        val bilateral = values(
            "preprocess.denoise" to DwTraceValue.Choice("BILATERAL"),
            "preprocess.denoiseStrength" to DwTraceValue.Num(0.5),
        )
        assertEquals(
            "the bilateral filter does read the strength, so there is nothing to say",
            null,
            dwTraceInactiveReason(strength, bilateral),
        )

        val flow = values("edge.engine" to DwTraceValue.Choice("FDOG"))
        val blur = DW_TRACE_SLIDERS.first { it.key == "edge.blurSigma" }
        assertTrue("only Canny reads the pre-blur", dwTraceInactiveReason(blur, flow) != null)
        val canny = values("edge.engine" to DwTraceValue.Choice("CANNY"))
        assertEquals(null, dwTraceInactiveReason(blur, canny))
    }

    /** A tree with nothing in it must not make the panel claim things are switched off. */
    @Test
    fun `an empty tree produces no false claims`() {
        val empty = values()
        DW_TRACE_CONTROLS.forEach { control ->
            assertEquals(
                "`${control.key}` claimed to be inactive on a tree that says nothing at all. The " +
                    "absence of a leaf means this build and the engine are a version apart, which is " +
                    "what dwTraceMissingKeys reports — it is not evidence that a filter is off.",
                null,
                dwTraceInactiveReason(control, empty),
            )
        }
        assertEquals(
            "every control this build draws is missing from an empty tree",
            DW_TRACE_PARAM_COUNT,
            dwTraceMissingKeys(empty).size,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // What the cost gate refuses
    // ---------------------------------------------------------------------------------------------

    /**
     * The flow engine at full resolution is barred with a REMEDY, never silently swapped.
     *
     * A quiet substitution is the worst available option and the one this repository exists to
     * prevent: one sheet of paper would then produce two different drawings depending on which client
     * traced it.
     */
    @Test
    fun `the flow engine is barred with a remedy rather than substituted`() {
        val able = DwTraceAvailability(
            maxWorkingLongEdge = 2048,
            fdogMaxWorkingLongEdge = 1024,
            measuredOn = "a test",
        )
        val flowAtFull = values(
            "edge.engine" to DwTraceValue.Choice("FDOG"),
            "preprocess.workingLongEdge" to DwTraceValue.Num(2048.0),
        )
        val refusal = dwTraceCostRefusal(flowAtFull, able)
        assertTrue("the flow engine at 2048 px must be refused on a device capped at 1024", refusal != null)
        assertTrue(
            "a refusal without a remedy teaches a designer the feature is broken",
            refusal!!.contains("lower the trace resolution") || refusal.contains("different edge engine"),
        )
        assertTrue(
            "the refusal must say the portal will agree with whichever choice is made, or a designer " +
                "will reasonably assume the two clients now differ",
            refusal.contains("portal"),
        )

        val adaptiveAtFull = values(
            "edge.engine" to DwTraceValue.Choice("ADAPTIVE"),
            "preprocess.workingLongEdge" to DwTraceValue.Num(2048.0),
        )
        assertEquals(
            "every engine but flow clears the bar at this device's measured ceiling",
            null,
            dwTraceCostRefusal(adaptiveAtFull, able),
        )

        val tooBig = values(
            "edge.engine" to DwTraceValue.Choice("ADAPTIVE"),
            "preprocess.workingLongEdge" to DwTraceValue.Num(4096.0),
        )
        assertTrue(
            "a resolution above the device's measured ceiling is refused whatever the engine",
            dwTraceCostRefusal(tooBig, able) != null,
        )

        // THE FOURTH CASE USED TO BE "THIS PHONE CANNOT TRACE AT ALL" AND THERE IS NO SUCH PHONE.
        // `DwTraceAvailability` carried a `canTrace`/`refusal` pair while the tracer was a JavaScript
        // bundle needing a WebView at Chromium M97, and this assertion pinned that `dwTraceCostRefusal`
        // returned that refusal ahead of any resolution arithmetic. The engine is compiled into the
        // APK now, both fields are gone, and what replaces the case is the boundary below it: a
        // resolution AT the ceiling is allowed, so a designer is not refused for reaching the limit
        // this device was actually measured at.
        val atTheCeiling = values(
            "edge.engine" to DwTraceValue.Choice("FDOG"),
            "preprocess.workingLongEdge" to DwTraceValue.Num(1024.0),
        )
        assertEquals(
            "the flow ceiling is inclusive; refusing at exactly the measured limit would make the " +
                "number the panel prints unreachable",
            null,
            dwTraceCostRefusal(atTheCeiling, able),
        )
    }

    /** A duration a person can read, on both sides of a second. */
    @Test
    fun `durations read as sentences`() {
        assertEquals("820 milliseconds", dwTraceSeconds(820L))
        assertEquals("16.7 seconds", dwTraceSeconds(16655L))
        assertEquals("1.0 seconds", dwTraceSeconds(1000L))
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** A sanitised tree with only the leaves a test names. `wire` is opaque and unused here. */
    private fun values(vararg leaves: Pair<String, DwTraceValue>): DwTraceValues =
        DwTraceValues(leaves.toMap(), wire = "{}")
}
