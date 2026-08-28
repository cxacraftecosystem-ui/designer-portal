package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.floor

/**
 * **THE HANDSET'S TRACING CONTROLS, PINNED AGAINST THE PORTAL'S.**
 *
 * ── WHY THIS TEST EXISTS AT ALL ───────────────────────────────────────────────────────────────
 *
 * `DwSketchTraceParams.kt` is a TRANSCRIPTION. Thirty-one labels, thirty-one hints and forty-odd
 * numeric bounds were copied out of `frontend/components/sketches/upload/traceParamTable.ts`, and
 * every one of them is a sentence a designer reads in a courtyard with no other documentation. A
 * transcription that nothing checks is a transcription that is already wrong: nothing about a
 * reworded hint or a widened maximum raises, warns or logs — the handset simply starts telling a
 * designer something the portal does not, about the same slider, on the same sketch.
 *
 * It matters more than an ordinary copy for the reason the whole feature exists: **the two clients
 * must produce the same line art from the same sheet.** A maximum that is wider here lets a handset
 * ask for something the portal will not, and the answer is a different drawing in a government
 * document.
 *
 * ── AND WHY IT READS THE TYPESCRIPT RATHER THAN A COPY OF IT ──────────────────────────────────
 *
 * `RecordEditHistoryRedactionTest` established the pattern in this module: Gradle runs unit tests
 * with `android/app` as the working directory, so two levels up is the monorepo root and the original
 * is right there. Pinning against a second Kotlin copy of the same strings would only prove that two
 * transcriptions agree with each other.
 *
 * ── WHAT IT DELIBERATELY DOES NOT ASSERT ──────────────────────────────────────────────────────
 *
 * [DwTraceControl.handsetNote] is this client's own sentence and is not in the portal's table at all,
 * which is exactly why it is a separate field. Pinning it would make the two impossible to tell
 * apart, and the next person to add a handset-only sentence would put it in `hint` to get past the
 * test.
 */
class DwSketchTraceParamsTest {

    // ---------------------------------------------------------------------------------------------
    // Reading the original
    // ---------------------------------------------------------------------------------------------

    private fun repoFile(path: String): String {
        val file = File("../../$path")
        assertTrue(
            "expected $path at ${file.absolutePath}. This test pins the handset's copy of the " +
                "portal's control table against the original; if the tree moved, fix the path — do " +
                "not delete the assertion, because a silent copy is exactly how the two clients come " +
                "to describe one slider differently.",
            file.exists(),
        )
        return file.readText(Charsets.UTF_8)
    }

    private val webTable: String by lazy {
        repoFile("frontend/components/sketches/upload/traceParamTable.ts")
    }

    /** Every `key: "…"` in the portal's table, in file order. Its own counting command, in Kotlin. */
    private val webKeys: List<String> by lazy {
        Regex("\n {4}key: \"([^\"]+)\"").findAll(webTable).map { it.groupValues[1] }.toList()
    }

    /** One table entry's source text: from its `key:` line to the next entry's, or to the end. */
    private fun webEntry(key: String): String {
        val marker = "\n    key: \"$key\""
        val start = webTable.indexOf(marker)
        assertTrue("the portal's table no longer declares a control keyed `$key`", start >= 0)
        val next = webTable.indexOf("\n    key: \"", start + marker.length)
        return if (next < 0) webTable.substring(start) else webTable.substring(start, next)
    }

    /** A number the way TypeScript spells it: `256`, not `256.0`. */
    private fun ts(value: Double): String =
        if (value == floor(value) && abs(value) < 1e9) value.toLong().toString() else value.toString()

    // ---------------------------------------------------------------------------------------------
    // The count
    // ---------------------------------------------------------------------------------------------

    /**
     * The arithmetic, from the tables rather than from anybody's memory.
     *
     * `traceParamTable.ts:11-22` records three different wrong totals in its own history and the rule
     * that came out of it: the number lives in one constant and nowhere else. This is that rule with a
     * command behind it — and it is also where the ONE deliberate difference in size has to be
     * justified out loud rather than noticed later.
     */
    @Test
    fun `the handset draws the portal's controls minus exactly the cut list`() {
        assertEquals(
            "the portal's table should declare 32 controls; it declares ${webKeys.size}. If the " +
                "portal legitimately gained or lost one, this test is the place to decide what the " +
                "handset does about it.",
            32,
            webKeys.size,
        )

        val androidKeys = DW_TRACE_CONTROLS.map { it.key }
        assertEquals(
            "two controls in DW_TRACE_CONTROLS share a key",
            androidKeys.size,
            androidKeys.toSet().size,
        )
        assertEquals(
            "DW_TRACE_PARAM_COUNT must come from the table",
            androidKeys.size,
            DW_TRACE_PARAM_COUNT,
        )

        val missing = webKeys.toSet() - androidKeys.toSet()
        assertEquals(
            "every control the handset does not draw must be in DW_TRACE_CUT with its argument. " +
                "Silently dropping one is how a designer loses a setting with nowhere to read why.",
            DW_TRACE_CUT.keys,
            missing,
        )
        val extra = androidKeys.toSet() - webKeys.toSet()
        assertTrue(
            "the handset draws controls the portal does not: $extra. A control on one client only is " +
                "a setting whose value the other cannot reproduce.",
            extra.isEmpty(),
        )
        assertEquals(32, DW_TRACE_PARAM_COUNT + DW_TRACE_CUT.size)
        DW_TRACE_CUT.forEach { (key, why) ->
            assertTrue("the cut of `$key` needs an argument, not a blank", why.length > 60)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The words
    // ---------------------------------------------------------------------------------------------

    /**
     * Every label and every hint, character for character.
     *
     * These are the upstream's own words, kept so a designer who has tuned a trace on the laptop does
     * not have to learn a second vocabulary for the same slider — and, for `output.corner`, because
     * the control is INVERTED from intuition and the sentence is the only thing that says so.
     */
    @Test
    fun `every label and hint is the portal's own, character for character`() {
        DW_TRACE_CONTROLS.forEach { control ->
            val entry = webEntry(control.key)
            assertTrue(
                "the label for `${control.key}` has drifted.\n  handset: ${control.label}\n" +
                    "  portal : (not found in its entry)",
                entry.contains("label: \"${control.label}\""),
            )
            assertTrue(
                "the hint for `${control.key}` has drifted. These sentences are the only " +
                    "documentation a designer offline for a fortnight has.\n  handset: ${control.hint}",
                entry.contains("hint: \"${control.hint}\""),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The numbers
    // ---------------------------------------------------------------------------------------------

    /**
     * The sliders' display bounds, which are the portal's NARROWED maxima and not the engine's.
     *
     * The narrowing is the only place this product's constraints on the parameter surface are written
     * down — 4096 rather than the engine's 8192 because "a 4096 trace is already several seconds of a
     * worker thread on the phones this application is used from"; sharpen radius 8 rather than 32
     * because past that it is a local-contrast boost, which is a different control three rows up.
     * Widening one here would let a handset ask for something the portal will not.
     */
    @Test
    fun `every slider bound is the portal's own`() {
        DW_TRACE_SLIDERS.forEach { slider ->
            val entry = webEntry(slider.key)
            listOf("min" to slider.min, "max" to slider.max, "step" to slider.step).forEach { (name, value) ->
                assertTrue(
                    "`${slider.key}`'s $name has drifted: the handset says ${ts(value)}, and the " +
                        "portal's entry does not contain `$name: ${ts(value)}`.",
                    Regex("""\n\s+$name: ${Regex.escape(ts(value))},""").containsMatchIn(entry),
                )
            }
        }
    }

    /**
     * The trace resolution is a three-way choice here and a slider on the portal, and its options must
     * still sit inside the range the portal offers.
     */
    @Test
    fun `the trace resolution options sit inside the portal's own slider range`() {
        val entry = webEntry("preprocess.workingLongEdge")
        assertTrue("the portal's trace-resolution slider no longer starts at 256", entry.contains("min: 256"))
        assertTrue("the portal's trace-resolution slider no longer stops at 4096", entry.contains("max: 4096"))
        DW_TRACE_RESOLUTION.forEach { option ->
            assertTrue(
                "the “${option.label}” resolution (${option.value}) is outside the 256..4096 the " +
                    "portal offers, so one client would accept a trace the other refuses",
                option.value in 256.0..4096.0,
            )
            assertTrue(
                "the “${option.label}” resolution must state its cost in the row; a designer cannot " +
                    "discover a two-minute setting by trying it",
                option.note.length > 30,
            )
        }
        assertEquals("Fast, Standard and Detailed — three, not a slider", 3, DW_TRACE_RESOLUTION.size)
    }

    /** Every option of every choice, value and label, as the portal spells them. */
    @Test
    fun `every choice offers exactly the portal's options`() {
        DW_TRACE_CHOICES.forEach { choice ->
            val entry = webEntry(choice.key)
            val webOptions = Regex("""\{ value: "([^"]+)", label: "([^"]+)" }""")
                .findAll(entry)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
            assertEquals(
                "`${choice.key}`'s options have drifted. The portal omits `MODEL` and `SUBJECT` on " +
                    "purpose and the handset must omit exactly the same ones — an option that cannot " +
                    "work is worse than no option, and two routes to one value let the two disagree " +
                    "about which decided.",
                webOptions,
                choice.options.map { it.value to it.label },
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tiering
    // ---------------------------------------------------------------------------------------------

    /**
     * The six primary controls, and the one deliberate demotion from the portal's seven.
     *
     * Written down as an assertion rather than as prose because "which controls lead" is the decision
     * a hurried change is most likely to make by accident — adding a `tier = PRIMARY` to a row is one
     * word, and it silently pushes a phone's first screen past what a thumb can reach.
     */
    @Test
    fun `the primary surface is six controls, and strokeWidth is not one of them`() {
        assertEquals(
            "the handset leads with six controls plus the two preset pickers, which are not in this " +
                "table because a style writes the whole tree and a subject adjusts it",
            listOf(
                "preprocess.workingLongEdge",
                "preprocess.unsharpAmount",
                "cleanup.minBlobArea",
                "output.vectorMode",
                "output.simplify",
                "edge.sensitivity",
            ).sorted(),
            DW_TRACE_PRIMARY_KEYS.sorted(),
        )
        // The portal's own ESSENTIAL_KEYS, for the record, so a reader can see the one difference.
        val webEssential = Regex("""ESSENTIAL_KEYS: readonly string\[] = \[([^\]]*)]""")
            .find(webTable)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        assertTrue(
            "the portal still leads with `output.strokeWidth`; the handset demotes it because it is " +
                "applied at document assembly, after every decision has been made, so a designer " +
                "whose trace is wrong will not fix it with stroke width",
            webEssential.contains("output.strokeWidth"),
        )
        assertTrue(
            "output.strokeWidth must not be primary on the handset",
            "output.strokeWidth" !in DW_TRACE_PRIMARY_KEYS,
        )
        assertEquals(
            "the disclosure button prints this number, so it must come from the table",
            DW_TRACE_CONTROLS.count { it.tier == DwTraceTier.ADVANCED },
            DW_TRACE_ADVANCED_COUNT,
        )
        assertEquals(
            "exactly one control belongs to the export step: the background, which is a property of " +
                "the file rather than of the tracing",
            listOf("output.background"),
            DW_TRACE_CONTROLS.filter { it.tier == DwTraceTier.EXPORT }.map { it.key },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The scales
    // ---------------------------------------------------------------------------------------------

    /**
     * Travel and value are inverses, at both ends and in the middle, for all three mappings.
     *
     * A scale that is not its own inverse is a thumb that jumps when it is put down: the panel seeds
     * the slider from [fractionOf] and commits through [valueAt], so a mismatch between them makes
     * every control drift by a little each time it is touched.
     */
    @Test
    fun `travel and value are inverses on every slider`() {
        DW_TRACE_SLIDERS.forEach { slider ->
            listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f).forEach { t ->
                val value = slider.valueAt(t)
                assertTrue(
                    "`${slider.key}` produced $value at travel $t, outside ${slider.min}..${slider.max}",
                    value >= slider.min - 1e-9 && value <= slider.max + 1e-9,
                )
                val back = slider.fractionOf(value)
                // One step of the slider's own resolution is the honest tolerance: `valueAt` snaps.
                val stepTravel = if (slider.max > slider.min) {
                    (slider.step / (slider.max - slider.min)).toFloat()
                } else {
                    1f
                }
                assertTrue(
                    "`${slider.key}` is not its own inverse at travel $t: $t -> $value -> $back",
                    abs(back - t) <= maxOf(stepTravel * 4f, 0.02f),
                )
            }
        }
    }

    /**
     * The non-linear scales put the values the presets actually use where a thumb can reach them.
     *
     * This is the assertion the whole scale mechanism exists for. A 360 dp handset gives a ~328 dp
     * track and a fingertip lands reliably to about 8 dp, so there are roughly 41 distinguishable
     * positions; on a linear track, fifteen of `minBlobArea`'s twenty-six preset values sit inside the
     * first 2 dp of it.
     */
    @Test
    fun `the values the presets use are reachable by a thumb`() {
        val blob = DW_TRACE_SLIDERS.first { it.key == "cleanup.minBlobArea" }
        assertEquals(DwTraceScale.SQUARE, blob.scale)
        // 64 is the top of the band fifteen of the twenty-six preset values fall in. On a linear
        // track that band is 19 dp — about two fingertips for fifteen distinct values.
        assertTrue(
            "minBlobArea 6..64 spans ${blob.fractionOf(64.0) - blob.fractionOf(6.0)} of the track; " +
                "on a linear track it is 0.058, which is 19 dp on a 328 dp handset",
            blob.fractionOf(64.0) - blob.fractionOf(6.0) > 0.15f,
        )

        val path = DW_TRACE_SLIDERS.first { it.key == "output.minPathLength" }
        assertEquals(DwTraceScale.SQUARE, path.scale)
        assertTrue(
            "minPathLength 0..40 holds every preset value and must not be the bottom fifth of travel",
            path.fractionOf(40.0) > 0.35f,
        )

        val phi = DW_TRACE_SLIDERS.first { it.key == "edge.xdogPhi" }
        assertEquals(DwTraceScale.LOG, phi.scale)
        assertTrue("a log scale needs a positive minimum", phi.min > 0.0)
        /*
          THE ARGUMENT HERE IS THE HINT, NOT THE PRESETS, and this is the assertion that says so.
          "XDoG sharpness: 3 is soft graphite, 300 is a woodcut with no soft edge anywhere" — on a
          linear 0.1..300 track, everything from 0.1 to 6 is the first 2% of travel, so on a 328 dp
          handset the entire graphite half of the range that sentence describes is 6.5 dp wide and 3
          itself sits 3.2 dp from the left edge. A designer cannot ask for the thing the control's own
          documentation tells them to ask for.
        */
        assertTrue(
            "3 — the value this slider's hint names as soft graphite — sits at ${phi.fractionOf(3.0)} " +
                "of the track, which is under 20 dp from the left edge on a 328 dp handset",
            phi.fractionOf(3.0) > 0.3f,
        )
        // The five values the styles write, plus the engine's own default of 20. They were already
        // far enough apart on a linear track; the assertion is that a log track does not undo that.
        val stops = listOf(6.0, 20.0, 40.0, 60.0, 120.0, 200.0).map { phi.fractionOf(it) }
        stops.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "two adjacent xdogPhi values land ${abs(b - a)} of the track apart, which is under " +
                    "the ~8 dp a fingertip can distinguish on a 328 dp track",
                b - a > 0.024f,
            )
        }
    }

    /**
     * Snapping produces a number that survives being written down.
     *
     * `0.05 * 7` is 0.35000000000000003 in binary floating point. The engine would sanitise that to
     * itself, so nothing would fail — the two clients would simply hold different numbers for the same
     * thumb position, and the cross-runtime parity record would show it.
     */
    @Test
    fun `snapped values have no binary dust on them`() {
        DW_TRACE_SLIDERS.forEach { slider ->
            (0..40).forEach { i ->
                val value = slider.valueAt(i / 40f)
                val text = dwTraceFormatValue(value, slider.step)
                assertTrue(
                    "`${slider.key}` formatted $value as `$text`, which is not a number a readout " +
                        "should show",
                    text.length <= 8,
                )
                assertNotNull(text.toDoubleOrNull())
            }
        }
    }

    /** Integer leaves are committed as integers, because the engine's sanitiser TRUNCATES. */
    @Test
    fun `integer sliders commit whole numbers`() {
        DW_TRACE_SLIDERS.filter { it.integral }.forEach { slider ->
            (0..20).forEach { i ->
                val committed = slider.patch(slider.valueAt(i / 20f))[slider.key]
                val number = (committed as DwTraceValue.Num).value
                assertEquals(
                    "`${slider.key}` committed $number. `sanitizeTraceParams` truncates toward zero " +
                        "for integer leaves (params.ts:167-172), so a fractional value would come " +
                        "back one below the thumb's position.",
                    floor(number),
                    number,
                    0.0,
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Seeding the subject
    // ---------------------------------------------------------------------------------------------

    /**
     * The category-to-subject map, pinned against the bundled registry's own enum.
     *
     * A mapping keyed on a value the registry does not declare is a pre-selection that can never fire,
     * and it would fail exactly the way a typo in a preset id fails: silently, by doing nothing.
     */
    @Test
    fun `every mapped category exists in the bundled registry`() {
        val registry = File("src/main/assets/design-workshop-schema.json")
        assertTrue("the bundled registry is missing", registry.exists())
        val text = registry.readText(Charsets.UTF_8)
        DW_TRACE_SUBJECT_FOR_CATEGORY.keys.forEach { category ->
            assertTrue(
                "`$category` is not a PRODUCT_CATEGORY the bundled registry declares, so seeding the " +
                    "subject from it could never fire",
                text.contains("\"value\": \"$category\"") || text.contains("\"value\":\"$category\""),
            )
        }
        assertEquals("sketch", dwTraceSubjectFor(null))
        assertEquals("sketch", dwTraceSubjectFor(""))
        assertEquals("sketch", dwTraceSubjectFor("APPAREL"))
        assertEquals("jewellery", dwTraceSubjectFor("JEWELLERY"))
        assertEquals("textile", dwTraceSubjectFor(" SAREE "))
        assertEquals("carving", dwTraceSubjectFor("DECORATIVE"))
    }
}
