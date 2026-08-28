package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            // The BUTTON no longer prints this one — it prints `dwTraceAdvancedRevealed`, which
            // counts the rows this device's engine will actually draw. This constant is what the
            // TABLE holds, and it stays because the two must agree on a healthy build; the assertion
            // that they do is `every hidden control is reachable through exactly one group heading`.
            "DW_TRACE_ADVANCED_COUNT must come from the table rather than from anybody's memory",
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

    // ---------------------------------------------------------------------------------------------
    // The one disclosure
    // ---------------------------------------------------------------------------------------------

    /**
     * The panel's own source.
     *
     * READ AS TEXT, WHICH IS THE ONLY TOOL THIS MODULE HAS FOR A COMPOSABLE. There is no Robolectric
     * and no Compose test rule in this source set, so the assertions below about what the panel WIRES
     * — the click label, the state description, the role, the reduced-motion branch — are grep with a
     * failure message on it. That is weaker than driving the tree and much stronger than nothing: the
     * failure mode these guard against is somebody deleting a line during a tidy-up, and a deleted
     * line is exactly what a substring search finds.
     */
    private val panelSource: String by lazy {
        val file =
            File("src/main/java/com/designprototype/workshop/ui/designworkshop/DwSketchTracePanel.kt")
        assertTrue(
            "expected the trace panel at ${file.absolutePath}. If the file moved, fix the path — do " +
                "not delete these assertions; they are what stops the disclosure quietly losing its " +
                "accessibility wiring.",
            file.exists(),
        )
        file.readText(Charsets.UTF_8)
    }

    /** The portal's panel, for the one phrase the two clients are not allowed to choose separately. */
    private val webPanel: String by lazy {
        repoFile("frontend/components/sketches/upload/SketchTraceField.tsx")
    }

    /**
     * A sanitised tree holding one plausible leaf for every control the handset draws.
     *
     * The VALUES are arbitrary and the KEYS are the point: every assertion below is about which rows
     * exist and where they are drawn, and a leaf the engine did not send is the one thing that
     * changes that answer.
     */
    private fun everyLeaf(): Map<String, DwTraceValue> = DW_TRACE_CONTROLS.associate { control ->
        control.key to when (control) {
            is DwTraceSlider -> DwTraceValue.Num(control.min)
            is DwTraceToggle -> DwTraceValue.Flag(false)
            is DwTraceChoice -> DwTraceValue.Choice(control.options.first().value)
            is DwTraceNumberChoice -> DwTraceValue.Num(control.options.first().value)
        }
    }

    private fun valuesOf(leaves: Map<String, DwTraceValue> = everyLeaf()): DwTraceValues =
        DwTraceValues(leaves, "{}")

    /**
     * **THE ASSERTION THAT STOPS A CONTROL FALLING INTO THE GAP BETWEEN TWO LISTS.**
     *
     * The panel draws the essential rows above the disclosure and the rest inside it. Those are two
     * renders of one table, selected by opposite tests on one `tier` field — and that is a property
     * somebody can break in one word. Setting a row's tier to a third value, or adding a fourth tier
     * and forgetting one of the two render sites, makes a control that is in the table, is counted by
     * `DW_TRACE_PARAM_COUNT`, is pinned label-for-label against the portal by the tests above, and is
     * on no screen anywhere. Nothing else in this file would notice: the count would still be 31 and
     * every label would still match.
     *
     * So the sum is asserted directly. Essential plus advanced is every control except the one this
     * client deliberately relocated to the export step, and that exception is named here rather than
     * left as a subtraction a reader has to work out.
     */
    @Test
    fun `the essential set plus the disclosure's set is the whole table`() {
        val primary = DW_TRACE_CONTROLS.filter { it.tier == DwTraceTier.PRIMARY }.map { it.key }
        val advanced = DW_TRACE_CONTROLS.filter { it.tier == DwTraceTier.ADVANCED }.map { it.key }
        val export = DW_TRACE_CONTROLS.filter { it.tier == DwTraceTier.EXPORT }.map { it.key }

        assertEquals(
            "a control cannot be drawn in two places at once",
            emptySet<String>(),
            primary.toSet() intersect advanced.toSet(),
        )
        assertEquals(
            "every control the handset draws must be in exactly one tier, or it is in the table and " +
                "on no screen",
            DW_TRACE_PARAM_COUNT,
            primary.size + advanced.size + export.size,
        )
        assertEquals(
            DW_TRACE_CONTROLS.map { it.key }.toSet(),
            (primary + advanced + export).toSet(),
        )
        assertEquals(
            "the export step owns exactly one control: the background, which is a property of the " +
                "file rather than of the tracing",
            listOf("output.background"),
            export,
        )
        assertEquals(
            "the panel's two parameter surfaces — what it opens with, and what the one disclosure " +
                "reveals — must between them reach every control except the relocated one",
            DW_TRACE_CONTROLS.map { it.key }.toSet() - "output.background",
            (primary + advanced).toSet(),
        )
        assertEquals(DW_TRACE_PRIMARY_KEYS.toSet(), primary.toSet())
    }

    /**
     * Every hidden control is reachable through exactly one of the disclosure's group headings.
     *
     * The headings are the pipeline's own stages and not a second taxonomy, because a designer looks
     * for a control by the stage it belongs to — and if the disclosure grouped by importance instead,
     * finding a cleanup control would require knowing whether somebody had called it essential.
     *
     * "Exactly one" is asserted rather than assumed: `dwTraceAdvancedGroups` walks the groups and
     * filters the table on each, so a control whose `group` is not in `DW_TRACE_GROUPS` at all would
     * simply never be returned — present in the table, counted nowhere, drawn nowhere.
     */
    @Test
    fun `every hidden control is reachable through exactly one group heading`() {
        val values = valuesOf()
        val groups = dwTraceAdvancedGroups(values)
        val drawn = groups.flatMap { it.second }.map { it.key }
        val advanced = DW_TRACE_CONTROLS.filter { it.tier == DwTraceTier.ADVANCED }.map { it.key }

        assertEquals("a control is drawn under two headings", drawn.size, drawn.toSet().size)
        assertEquals(
            "the disclosure does not reach every advanced control. A control in the table that no " +
                "heading holds is a setting a designer can never see or reset.",
            advanced.toSet(),
            drawn.toSet(),
        )
        assertEquals(advanced.size, dwTraceAdvancedRevealed(values))
        assertEquals(
            "on a healthy build the rows drawn and the table's own count must agree",
            DW_TRACE_ADVANCED_COUNT,
            dwTraceAdvancedRevealed(values),
        )

        assertTrue(
            "the disclosure invented a heading that is not one of the table's own: " +
                "${groups.map { it.first } - DW_TRACE_GROUPS.toSet()}",
            groups.all { it.first in DW_TRACE_GROUPS },
        )
        assertEquals(
            "the headings must appear in the table's own order, which is the order the panel draws " +
                "the essential rows in too",
            groups.map { it.first }.sortedBy { DW_TRACE_GROUPS.indexOf(it) },
            groups.map { it.first },
        )
        assertTrue("a heading was drawn over nothing", groups.none { it.second.isEmpty() })
        assertFalse(
            "the export group holds no advanced row, so its heading must not appear here",
            DW_TRACE_GROUP_EXPORT in groups.map { it.first },
        )
    }

    /**
     * A control this device's engine did not send is neither counted on the toggle nor promised by it.
     *
     * `DwTraceControlRow` skips a row whose leaf is missing, so counting the TABLE would put a number
     * on the button that the press does not produce — `traceParamTable.ts:553-564` records that exact
     * failure on the portal, where a button read "Show all 32 controls" and revealed 25. The number
     * here is counted off the rows, and `dwTraceMissingKeys` — which the panel prints in its own
     * sentence — filters on the same membership test, so the two cannot disagree with each other.
     */
    @Test
    fun `a control this engine did not send is neither counted nor promised`() {
        val skipped = "edge.blurSigma"
        val trimmed = valuesOf(everyLeaf() - skipped)

        assertEquals(listOf(skipped), dwTraceMissingKeys(trimmed))
        assertEquals(DW_TRACE_ADVANCED_COUNT - 1, dwTraceAdvancedRevealed(trimmed))
        assertTrue(
            "a skipped row is still being offered by a heading",
            dwTraceAdvancedGroups(trimmed).none { (_, rows) -> rows.any { it.key == skipped } },
        )
        assertTrue(
            "the toggle promised a row the press would not produce",
            dwTraceDisclosureLabel(false, dwTraceAdvancedRevealed(trimmed), 0)
                .contains("${DW_TRACE_ADVANCED_COUNT - 1} settings"),
        )
    }

    /**
     * Both clients call the press the same thing.
     *
     * The owner named the control: "an internal accordion with an action such as 'Show more
     * options'". Android owns wording in this repository and this is the exception — a designer who
     * has learned where the rest of the settings live on the laptop must not have to find them again
     * under a different name in a courtyard. The handset's own "Show everything (N more)" is the
     * better English and lost on that argument alone.
     */
    @Test
    fun `both clients call the press the same thing`() {
        assertEquals("Show more options", DW_TRACE_DISCLOSURE_ACTION)
        assertTrue(
            "the portal no longer says “$DW_TRACE_DISCLOSURE_ACTION”. One of the two clients has " +
                "renamed the press, and a designer now has to learn the same disclosure twice.",
            webPanel.contains(DW_TRACE_DISCLOSURE_ACTION),
        )
        assertTrue(
            "the portal no longer prints the open arm of this toggle",
            webPanel.contains("Hide the other "),
        )
        assertTrue(
            "the portal typed a total into its own label instead of deriving it — the failure its " +
                "own header records as claiming twenty-nine while the table held thirty-two",
            webPanel.contains("ADVANCED_COUNT"),
        )

        assertTrue(dwTraceDisclosureLabel(false, 24, 0).startsWith(DW_TRACE_DISCLOSURE_ACTION))
        assertTrue(dwTraceDisclosureLabel(false, 24, 0).endsWith("24 settings"))
        assertTrue(dwTraceDisclosureLabel(false, 24, 3).endsWith("3 changed"))
        assertEquals("Hide the other 24 settings", dwTraceDisclosureLabel(true, 24, 0))
        // A version skew can leave exactly one row behind the press, and "1 settings" is a sentence
        // nobody wrote on purpose.
        assertTrue(dwTraceDisclosureLabel(false, 1, 0).endsWith("1 setting"))
        assertEquals("Hide the other 1 setting", dwTraceDisclosureLabel(true, 1, 0))
    }

    /**
     * The words and the number are derived, and neither is typed into the panel.
     *
     * `DW_TRACE_PARAM_COUNT`'s header records why: the portal's own file "claimed twenty-nine while
     * the table held thirty-two", and a reader reconciling the two went hunting for three controls
     * that had never been dropped. A count in a Compose string literal is the same bug with a shorter
     * fuse, because nothing in a layout file is read by a test that counts anything.
     */
    @Test
    fun `the disclosure's words and count are derived rather than typed into the panel`() {
        assertFalse(
            "the disclosure's phrase is typed into the panel. It belongs to DW_TRACE_DISCLOSURE_ACTION " +
                "so the parity test above can read one place and settle both clients.",
            panelSource.contains("\"$DW_TRACE_DISCLOSURE_ACTION"),
        )
        assertFalse(
            "the panel still carries the old label; the two clients now name the same press " +
                "differently",
            panelSource.contains("Show everything"),
        )
        assertFalse(
            "the advanced count is written into a string in the panel rather than derived",
            panelSource.contains("$DW_TRACE_ADVANCED_COUNT settings"),
        )
        assertTrue(panelSource.contains("dwTraceDisclosureLabel(open, revealed,"))
        assertTrue(panelSource.contains("dwTraceAdvancedRevealed(values)"))
    }

    /**
     * The toggle counts what the toggle reveals, and the sentence names everything out of sight.
     *
     * These are two different questions on this client and only one on the portal, because the
     * handset has a tier that lives on the export step. A count of the export-step control on THIS
     * toggle would be the press claiming to reveal something it cannot, and a designer who opened it
     * and could not find the fourth name would be right to distrust the rest of the panel.
     */
    @Test
    fun `the toggle counts only the settings the press reveals`() {
        val before = valuesOf()
        val after = valuesOf(
            everyLeaf() +
                ("edge.blurSigma" to DwTraceValue.Num(4.0)) +
                // `Absent` is how a transparent export is spelled, and it is a real value rather than
                // a missing one — so this is a CHANGE to the export step's own control.
                ("output.background" to DwTraceValue.Absent),
        )

        val behind = dwTraceChangedBehindDisclosure(before, after)
        assertEquals("only the advanced tier is behind this press", listOf("Pre-blur"), behind)

        val notOnScreen = dwTraceChangedHiddenLabels(before, after, setOf(DwTraceTier.PRIMARY))
        assertTrue(
            "the sentence must name the export-step control when its card is not composed",
            "White background" in notOnScreen,
        )
        assertFalse(
            "the export step is never behind this press",
            "White background" in behind,
        )
    }

    /** The sentence under a closed toggle, which the portal prints character for character. */
    @Test
    fun `the hidden-changed sentence is the portal's own, and null when nothing moved`() {
        assertNull(
            "an empty list must not render an empty notice box",
            dwTraceHiddenChangedSentence(emptyList()),
        )
        assertEquals(
            "One setting that is not on screen has moved: Pre-blur.",
            dwTraceHiddenChangedSentence(listOf("Pre-blur")),
        )
        assertEquals(
            "2 settings that are not on screen have moved: Pre-blur, Close gaps.",
            dwTraceHiddenChangedSentence(listOf("Pre-blur", "Close gaps")),
        )
        assertTrue(
            "the portal stopped printing this sentence, so the two clients now describe the same " +
                "folded-away change in two different ways",
            webPanel.contains("that is not on screen has moved") &&
                webPanel.contains("that are not on screen have moved"),
        )
    }

    /**
     * The disclosure is a real control: a role, an action, and a state a screen reader can hear.
     *
     * A chevron carries none of those. `stateDescription` says what the section IS, `onClickLabel`
     * says what the press will DO — in the verb grammar TalkBack speaks it in, which is why the click
     * label is not the visible label — and `Role.Button` is what stops it being announced as text
     * with a mysterious action attached. `mergeDescendants` makes the label, the count and the changed
     * mark one announcement instead of three stops on the way to the press.
     */
    @Test
    fun `the disclosure exposes its state and its action separately`() {
        assertEquals("Collapsed", dwTraceDisclosureState(false))
        assertEquals("Expanded", dwTraceDisclosureState(true))
        assertEquals("show the other 24 settings", dwTraceDisclosureClickLabel(false, 24))
        assertEquals("hide the other 24 settings", dwTraceDisclosureClickLabel(true, 24))
        assertEquals("show the other 1 setting", dwTraceDisclosureClickLabel(false, 1))

        listOf(
            "stateDescription = dwTraceDisclosureState(open)",
            "onClickLabel = dwTraceDisclosureClickLabel(open, revealed)",
            "role = Role.Button,",
            "mergeDescendants = true",
        ).forEach {
            assertTrue(
                "the disclosure no longer passes `$it`. Without it the section is a row of text that " +
                    "happens to respond to a press.",
                panelSource.contains(it),
            )
        }
    }

    /**
     * The expand animation is held at a constant when reduced motion is on.
     *
     * One 18 dp chevron turns and nothing else does — see `DW_TRACE_DISCLOSURE_TURN_MS` for why the
     * height is deliberately not animated — so this preference has exactly one thing to switch off,
     * and it must actually switch it off rather than merely shorten it.
     */
    @Test
    fun `the expand animation is held at a constant under reduced motion`() {
        assertTrue(
            "the disclosure no longer reads the reduced-motion preference",
            panelSource.contains("LocalAppPreferences.current.reducedMotion"),
        )
        assertTrue(
            "the chevron's tween no longer collapses to zero under reduced motion",
            panelSource.contains("if (stillness) 0 else DW_TRACE_DISCLOSURE_TURN_MS"),
        )
    }

    /**
     * Collapsing the disclosure resets nothing.
     *
     * The parameters live in the panel's own sanitised tree and the rows only read it, so there is no
     * state under the press for a collapse to throw away — which is why this client can use a plain
     * conditional where `SketchTraceField.tsx` needed a mounted-but-hidden subtree. Two things are
     * pinned: that changing WHICH TIERS ARE VISIBLE never makes the change-reporting functions claim a
     * value moved, and that the toggle's handler does one thing.
     */
    @Test
    fun `collapsing the disclosure changes no parameter`() {
        val values = valuesOf()
        listOf(
            emptySet<DwTraceTier>(),
            setOf(DwTraceTier.PRIMARY),
            setOf(DwTraceTier.PRIMARY, DwTraceTier.ADVANCED),
            setOf(DwTraceTier.PRIMARY, DwTraceTier.ADVANCED, DwTraceTier.EXPORT),
        ).forEach { visible ->
            assertEquals(
                "opening or closing the disclosure must never look like a parameter moving",
                emptyList<String>(),
                dwTraceChangedHiddenLabels(values, values, visible),
            )
        }
        assertEquals(emptyList<String>(), dwTraceChangedLabels(values, values))
        assertTrue(
            "the disclosure's handler must flip one Boolean and touch nothing else. A tidy-up that " +
                "reset a parameter here would discard tuning a designer cannot see from the button " +
                "they pressed.",
            panelSource.contains("onToggle = { advancedOpen = !advancedOpen },"),
        )
    }
}
