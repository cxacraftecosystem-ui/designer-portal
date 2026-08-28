package com.designprototype.workshop.ui.designworkshop

import com.offlinetracer.pipeline.AutoMode
import com.offlinetracer.pipeline.Knobs
import com.offlinetracer.pipeline.Styles
import com.offlinetracer.pipeline.Subjects
import com.offlinetracer.pipeline.TraceParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **THE KOTLIN ENGINE'S TWO PRESET REGISTERS, PINNED AGAINST THE PORTAL'S AND AGAINST THEMSELVES.**
 *
 * ── WHAT WOULD GO WRONG WITHOUT THIS ──────────────────────────────────────────────────────────
 *
 * `DwTraceKotlinPresets.kt` sits between two registers that are maintained by different people in
 * different languages, and every way they can part company fails silently:
 *
 *  · **A style id that stops matching** does not raise. `TraceParams.styleId` is persisted, so a
 *    handset would write an id the portal cannot resolve and the portal would fall back to
 *    `clean-line` — a different drawing from the same sheet, with nothing anywhere saying so.
 *  · **A style whose `params.styleId` is not its own id** does not raise either. The TypeScript
 *    register makes that impossible by construction (`styles.ts:46-47`); the Kotlin one writes the id
 *    by hand twenty times, so it is a convention, and a convention nothing checks is a typo waiting.
 *  · **The subject registers already disagree**, and the adapter's account of HOW they disagree is
 *    three hand-written lists. A list that drifts from the thing it describes puts a sentence on the
 *    wrong row of a picker, or leaves the right row silent.
 *  · **The two guards on `applySubject`** — the style's identity, and the designer's hand-tuned
 *    knobs — are enforcements of promises the vendored tables keep unaided today. That is exactly the
 *    state in which somebody deletes them as dead code.
 *
 * ── AND WHY IT READS THE TYPESCRIPT RATHER THAN A COPY OF IT ──────────────────────────────────
 *
 * The same reason `DwSketchTraceStagesTest` and `DwSketchTraceParamsTest` do: Gradle runs this
 * module's unit tests with `android/app` as the working directory, so two levels up is the monorepo
 * root and `frontend/lib/trace/engine/` — vendored verbatim and hashed in `UPSTREAM-MANIFEST.txt` — is
 * right there. Pinning against a second Kotlin transcription of those ids would only prove that two
 * transcriptions agree with each other.
 */
class DwTraceKotlinPresetsTest {

    /* ── The portal's own registers, read out of the vendored TypeScript ───────────────────────── */

    private fun vendored(path: String): String {
        val file = File("../../frontend/lib/trace/$path")
        assertTrue(
            "expected the vendored TypeScript engine at ${file.absolutePath}. This test is the only " +
                "mechanical check that the handset's Kotlin preset registers and the portal's agree " +
                "about ids a project is persisted under; if the tree moved, fix the path rather than " +
                "deleting the assertion.",
            file.exists(),
        )
        return file.readText(Charsets.UTF_8)
    }

    /** `preset('id', 'Name', GROUP_X, …)` — the only two-space-indented `preset(` calls are the table. */
    private val presetCall = Regex("""^ {2}preset\(\s*'([^']+)',\s*'([^']*)',\s*(GROUP_\w+),""", RegexOption.MULTILINE)

    /** `subject('id', 'Name', …)`, likewise. */
    private val subjectCall = Regex("""^ {2}subject\(\s*'([^']+)',\s*'([^']*)',""", RegexOption.MULTILINE)

    private val groupConst = Regex("""^const (GROUP_\w+) = '([^']*)';""", RegexOption.MULTILINE)

    private data class PortalStyle(val id: String, val name: String, val group: String)

    private fun portalStyles(): List<PortalStyle> {
        val src = vendored("engine/styles.ts")
        val groups = groupConst.findAll(src).associate { it.groupValues[1] to it.groupValues[2] }
        val rows = presetCall.findAll(src).map {
            val constant = it.groupValues[3]
            PortalStyle(
                id = it.groupValues[1],
                name = it.groupValues[2],
                group = groups[constant] ?: error("styles.ts names a group constant it never declares: $constant"),
            )
        }.toList()
        assertEquals("styles.ts should declare twenty presets", 20, rows.size)
        return rows
    }

    private fun portalSubjects(): List<Pair<String, String>> {
        val rows = subjectCall.findAll(vendored("engine/subjects.ts"))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertEquals("subjects.ts should declare ten subjects", 10, rows.size)
        return rows
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * The style register
     * ──────────────────────────────────────────────────────────────────────────── */

    /**
     * The twenty ids, and their order, are the portal's exactly.
     *
     * ORDER AND NOT ONLY MEMBERSHIP, because both registers call the order part of the persisted
     * contract ("append to the end, never renumber", `Styles.kt:911-913`) and because the picker is
     * drawn in it. This is the assertion that lets a project move between clients.
     */
    @Test
    fun `the twenty style ids are the portal's, in the portal's order`() {
        assertEquals(portalStyles().map { it.id }, Styles.ALL.map { it.id })
        assertEquals(
            "the picker rows carry the register's ids untouched",
            Styles.ALL.map { it.id },
            dwTraceKotlinPresetTables().styles.map { it.id },
        )
    }

    /**
     * Every style's tree reports the style it is.
     *
     * `styleId` is what a saved trace is filed under, so a preset whose `params.styleId` disagreed
     * with its own `id` would persist a drawing under a name that produces a different drawing. See
     * the class header for why this is a convention here and a construction on the portal.
     */
    @Test
    fun `every style's params report that style's own id`() {
        Styles.ALL.forEach { style ->
            assertEquals(
                "Styles.ALL[\"${style.id}\"] carries params.styleId = \"${style.params.styleId}\"",
                style.id,
                style.params.styleId,
            )
        }
    }

    /**
     * Names, descriptions and groups are the engine's own strings, not a transcription of them.
     *
     * `DwSketchTracePresets.kt:65-66`: "There is no Kotlin table of style names in this repository and
     * there must not be one." This is that rule as an assertion — the day somebody "improves" a
     * description in the adapter, this fails instead of the two registers quietly parting.
     */
    @Test
    fun `the style rows are the engine's own strings`() {
        val rows = dwTraceKotlinPresetTables().styles
        assertEquals(20, rows.size)
        assertEquals(Styles.ALL.map { it.name }, rows.map { it.name })
        assertEquals(Styles.ALL.map { it.description }, rows.map { it.description })
        assertEquals(Styles.ALL.map { it.group }, rows.map { it.group })
    }

    /**
     * The display-text divergence `DwTraceKotlinPresets.kt`'s header states, pinned to the digit.
     *
     * A comment that counts something is a claim, and this repository's rule is that a count must be
     * true when it is written. This is how it stays true: change either register and the header's
     * "one name" and "fourteen of the twenty groups" fail here rather than rotting in a docblock.
     */
    @Test
    fun `exactly one style name and fourteen style groups differ from the portal's`() {
        val portal = portalStyles().associateBy { it.id }
        val differingNames = Styles.ALL.filter { portal.getValue(it.id).name != it.name }.map { it.id }
        val differingGroups = Styles.ALL.filter { portal.getValue(it.id).group != it.group }.map { it.id }

        assertEquals(listOf("comic"), differingNames)
        assertEquals("Comic ink", Styles.ALL.first { it.id == "comic" }.name)
        assertEquals("Comic", portal.getValue("comic").name)

        assertEquals(14, differingGroups.size)
        assertEquals(
            setOf("Drawing", "Print & relief", "Fabrication", "Education"),
            Styles.ALL.map { it.group }.toSet(),
        )
        assertEquals(
            setOf("Line art", "Drawing", "Technical", "Print & relief", "Making"),
            portal.values.map { it.group }.toSet(),
        )
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * The subject register, and the divergence this adapter admits to
     * ──────────────────────────────────────────────────────────────────────────── */

    /**
     * The two registers differ by exactly the rows the adapter's own lists name.
     *
     * THE POINT OF THIS TEST IS THE FAILURE MODE, not the pass. `DW_TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE`
     * decides which rows carry a sentence on screen and
     * `DW_TRACE_SUBJECTS_ONLY_ON_THE_PORTAL` decides which ids get a refusal that names the remedy
     * instead of a dead end. Either list drifting from the registers it describes is a picker that
     * lies quietly, so the difference is recomputed here from both sources rather than trusted.
     */
    @Test
    fun `the subject registers differ by exactly the rows this adapter names`() {
        val engine = Subjects.ALL.map { it.id }
        val portal = portalSubjects().map { it.first }
        assertEquals(12, engine.size)
        assertEquals(10, portal.size)

        assertEquals(
            DW_TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE,
            engine.filter { it !in portal },
        )
        assertEquals(
            DW_TRACE_SUBJECTS_ONLY_ON_THE_PORTAL,
            portal.filter { it !in engine },
        )
        // The nine that ARE shared are spelled identically on both sides and appear in the same
        // relative order, which is what makes a subject choice mean the same thing on both clients.
        assertEquals(
            listOf(
                "painting", "pottery", "textile", "jewellery", "sculpture",
                "architecture", "logo", "sketch", "photo",
            ),
            engine.filter { it in portal },
        )
        assertEquals(engine.filter { it in portal }, portal.filter { it in engine })
    }

    /**
     * The notes table covers exactly the unmatched rows — no row silent, no row over-explained.
     */
    @Test
    fun `every unmatched subject row carries a divergence sentence and no other row does`() {
        assertEquals(
            DW_TRACE_SUBJECTS_ONLY_ON_THIS_ENGINE,
            DW_TRACE_SUBJECT_DIVERGENCE_NOTES.keys.toList(),
        )
        val rows = dwTraceKotlinPresetTables().subjects.associateBy { it.id }
        assertEquals(12, rows.size)
        rows.values.forEach { row ->
            val note = DW_TRACE_SUBJECT_DIVERGENCE_NOTES[row.id]
            if (note == null) {
                assertTrue(
                    "\"${row.id}\" has a portal counterpart and should not claim otherwise",
                    DW_TRACE_SUBJECT_DIVERGENCE_NOTES.values.none { row.description.contains(it) },
                )
            } else {
                assertTrue(
                    "\"${row.id}\" has no portal counterpart and must say so on screen",
                    row.description.endsWith(note),
                )
            }
        }
    }

    /**
     * Each subject row is the engine's hint verbatim, then this client's sentences — in that order.
     *
     * The hint is the material's own explanation and belongs first; a designer skimming the sheet
     * reads what the preset does before they read what the two clients disagree about.
     */
    @Test
    fun `every subject row opens with the engine's own hint and states that adjusting compounds`() {
        val rows = dwTraceKotlinPresetTables().subjects
        assertEquals(Subjects.ALL.map { it.id }, rows.map { it.id })
        assertEquals(Subjects.ALL.map { it.name }, rows.map { it.name })
        rows.forEachIndexed { index, row ->
            val hint = Subjects.ALL[index].hint
            assertTrue("\"${row.id}\" does not open with the engine's own hint", row.description.startsWith(hint))
            assertTrue(
                "\"${row.id}\" does not say that adjusting again compounds",
                row.description.contains(DW_TRACE_SUBJECT_COMPOUNDS_NOTE),
            )
            assertEquals("subjects are a flat list, so there is no group to fold in", "", row.group)
        }
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * Applying a style
     * ──────────────────────────────────────────────────────────────────────────── */

    /**
     * A style is the preset's whole tree, and the base is discarded — including the `auto` block.
     *
     * The discard is asserted rather than merely documented because it is the one place two runtimes
     * behind one button could differ: `bridge.ts:475-484` takes the base, writes `void paramsJson` and
     * returns the preset alone, and a Kotlin implementation that "helpfully" kept something would give
     * the same designer two different drawings depending on which build they were holding.
     */
    @Test
    fun `applying a style returns the preset's whole tree and keeps nothing from the base`() {
        val base = TraceParams().copy(
            auto = TraceParams().auto.copy(mode = AutoMode.OFF, handTuned = setOf(Knobs.SIMPLIFY)),
        ).sanitized()

        Styles.ALL.forEach { style ->
            val applied = dwTraceKotlinApplyStyle(base, style.id)
            assertEquals("\"${style.id}\" should come back whole", style.params.sanitized(), applied)
            assertEquals(style.id, applied.styleId)
            assertEquals(
                "a style replaces the tree, so the base's auto block does not survive it",
                style.params.auto,
                applied.auto,
            )
            assertEquals("the register's presets are already legal", applied, applied.sanitized())
        }
    }

    /** An id nothing carries is refused in the portal's own words, never substituted for `clean-line`. */
    @Test
    fun `an unknown style is refused rather than silently replaced by the default`() {
        val failure = runCatching { dwTraceKotlinApplyStyle(TraceParams(), "clean-lines") }.exceptionOrNull()
        assertTrue("expected a refusal", failure is IllegalArgumentException)
        assertEquals("There is no style called \"clean-lines\".", failure?.message)
        assertEquals(dwTraceKotlinNoSuchStyleSentence("clean-lines"), failure?.message)

        // THE FOURTH ASSERTION IS GONE, AND THE SENTENCE IS STILL PINNED BY THE LINE ABOVE IT.
        // It read the spelling out of `frontend/lib/trace/android/bridge.ts` — the JavaScript route's
        // host shim — so that the two runtimes explained one problem the same way. That route is
        // deleted, there is no second spelling to agree with, and the file it read no longer exists.
        // What survives is what the check was actually protecting: the message is
        // `dwTraceKotlinNoSuchStyleSentence`'s and not a literal at the throw site, so there is one
        // place this sentence is written.
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * Applying a subject
     * ──────────────────────────────────────────────────────────────────────────── */

    /**
     * All 240 style × subject pairs keep the four fields that are what a style *is*.
     *
     * API-CONTRACT §4.3: `adjust` "never touches `edge.engine`, `output.vectorMode`,
     * `output.fillClosed` or `styleId` — those are what a style *is*". Asserted across the whole
     * cross-product because the failure is per-table: one careless `engine =` in one of twelve entries
     * would turn "colouring book" into something that is no longer colouring book, on export, silently.
     */
    @Test
    fun `no subject can change what a style is`() {
        Styles.ALL.forEach { style ->
            Subjects.ALL.forEach { subject ->
                val base = style.params.sanitized()
                val applied = dwTraceKotlinApplySubject(base, subject.id)
                val where = "${style.id} + ${subject.id}"
                assertEquals("$where changed the edge engine", base.edge.engine, applied.edge.engine)
                assertEquals("$where changed the vector mode", base.output.vectorMode, applied.output.vectorMode)
                assertEquals("$where changed the fill", base.output.fillClosed, applied.output.fillClosed)
                assertEquals("$where changed the style id", base.styleId, applied.styleId)
                assertEquals("$where left an illegal value", applied, applied.sanitized())
            }
        }
    }

    /**
     * The guard above is a BELT, and today the tables need no braces — which is why it is easy to
     * delete and why this test says so out loud.
     *
     * `Subjects.kt:657-661` gives the reason to keep it: the subject tables "are data, they are edited
     * by hand", and the automatic path applies one without a user having asked. If this test ever
     * fails, a vendored table has started changing a style's identity and the enforcement in
     * `dwTraceKotlinApplySubject` is the only thing standing between that and somebody's export.
     */
    @Test
    fun `the vendored subject tables keep the identity promise unaided today`() {
        Styles.ALL.forEach { style ->
            Subjects.ALL.forEach { subject ->
                val base = style.params.sanitized()
                val raw = subject.adjust(base)
                val where = "${style.id} + ${subject.id}"
                assertEquals("$where: raw adjust moved the edge engine", base.edge.engine, raw.edge.engine)
                assertEquals("$where: raw adjust moved the vector mode", base.output.vectorMode, raw.output.vectorMode)
                assertEquals("$where: raw adjust moved the fill", base.output.fillClosed, raw.output.fillClosed)
                assertEquals("$where: raw adjust moved the style id", base.styleId, raw.styleId)
            }
        }
    }

    /** The `auto` block belongs to the screen, not to the subject table, and survives an adjustment. */
    @Test
    fun `applying a subject leaves the auto block alone`() {
        val base = TraceParams().copy(
            auto = TraceParams().auto.copy(mode = AutoMode.OFF, subjectId = "photo"),
        ).sanitized()
        val applied = dwTraceKotlinApplySubject(base, "pottery")
        assertEquals(base.auto, applied.auto)
        assertEquals(AutoMode.OFF, applied.auto.mode)
    }

    /* ── The hand-tuned knobs ──────────────────────────────────────────────────────────────────── */

    /**
     * The six knob strings this handset already transcribed are the six the engine actually honours.
     *
     * LOAD-BEARING FOR `applySubject`, which passes `auto.handTuned` straight to [Knobs.restore]:
     * `AutoParams.sanitized()` trims and de-duplicates that set but does **not** validate it against
     * the six names (`Params.kt:294-305`), so a label that is one character out is silently
     * unprotected — the knob a designer set by hand gets overwritten and nothing anywhere says so.
     * `DW_TRACE_KNOBS` was written against the TypeScript register; this is the other half of that
     * check, against the Kotlin one.
     */
    @Test
    fun `the handset's knob labels are the Kotlin engine's own`() {
        assertEquals(Knobs.ALL, DW_TRACE_KNOB_NAMES)
        assertEquals(6, Knobs.ALL.size)
        assertEquals(Knobs.ALL.toSet(), DW_TRACE_KNOB_KEY_FOR_NAME.keys)
    }

    /**
     * A knob named in `handTuned` survives an adjustment; the same knob unnamed does not.
     *
     * Both halves, because either alone is passable by an implementation that does nothing: a test
     * that only checked the protected case would pass against a `applySubject` that never adjusts
     * anything at all.
     */
    @Test
    fun `a hand-tuned knob is restored after a subject and an unprotected one is not`() {
        val tuned = TraceParams().copy(
            edge = TraceParams().edge.copy(sensitivity = 0.37f),
            cleanup = TraceParams().cleanup.copy(minBlobArea = 31),
            output = TraceParams().output.copy(
                simplify = 1.9f,
                corner = 95f,
                strokeWidth = 2.1f,
                minPathLength = 4.5f,
            ),
        ).sanitized()

        val protectedRun = dwTraceKotlinApplySubject(
            tuned.copy(auto = tuned.auto.copy(handTuned = Knobs.ALL.toSet())),
            "painting",
        )
        assertEquals(0.37f, protectedRun.edge.sensitivity, 0f)
        assertEquals(31, protectedRun.cleanup.minBlobArea)
        assertEquals(1.9f, protectedRun.output.simplify, 0f)
        assertEquals(95f, protectedRun.output.corner, 0f)
        assertEquals(2.1f, protectedRun.output.strokeWidth, 0f)
        assertEquals(4.5f, protectedRun.output.minPathLength, 0f)

        val unprotectedRun = dwTraceKotlinApplySubject(tuned, "painting")
        assertTrue(
            "with nothing declared hand-tuned the subject must actually move the blob floor",
            unprotectedRun.cleanup.minBlobArea != 31,
        )
        assertTrue(
            "…and the sensitivity",
            unprotectedRun.edge.sensitivity != 0.37f,
        )

        // Only the named knobs come back. Everything else the subject moved stays moved in both runs,
        // which is what makes `handTuned` a protection rather than a switch that turns subjects off.
        assertEquals(unprotectedRun.preprocess, protectedRun.preprocess)
    }

    /* ── The compounding, pinned so it cannot become a surprise ────────────────────────────────── */

    /**
     * **A SUBJECT COMPOUNDS HERE AND IS IDEMPOTENT ON THE PORTAL. THIS TEST EXISTS TO PIN THAT.**
     *
     * `subjects.ts:41-43` documents the TypeScript `adjust` as idempotent, and it is: those tables are
     * absolute overrides pushed through `withOverrides` (`subjects.ts:56-63`). The Kotlin tables are
     * relative — `Subjects.kt:21-24` claims only that applying twice cannot leave the legal range —
     * so a second application multiplies a second time.
     *
     * `DwSketchTracePanel.kt:684` re-applies a subject on a tap, so this is reachable by a designer
     * pressing the same button twice. It is deliberately NOT corrected here: a third behaviour
     * invented in the adapter would belong to neither engine. It is stated on every row by
     * [DW_TRACE_SUBJECT_COMPOUNDS_NOTE], reported by the panel's own overwrite notice, and left as an
     * owner's decision. If somebody takes that decision, this test is where the change lands.
     */
    @Test
    fun `applying a subject twice adjusts twice, unlike the portal's idempotent tables`() {
        val base = Styles.byId("clean-line")!!.params.sanitized()
        assertEquals("the engine default this arithmetic starts from", 24, base.cleanup.minBlobArea)

        val once = dwTraceKotlinApplySubject(base, "painting")
        val twice = dwTraceKotlinApplySubject(once, "painting")

        assertEquals(60, once.cleanup.minBlobArea)
        assertEquals(150, twice.cleanup.minBlobArea)
        assertNotEquals(once, twice)
        // Still legal, which is the only promise `Subjects.kt:21-24` actually makes about a second run.
        assertEquals(twice, twice.sanitized())
    }

    /* ── The refusals ─────────────────────────────────────────────────────────────────────────── */

    /**
     * A portal-only id is refused with the three rows that replaced it, not with "no such thing".
     *
     * `DwSketchTraceParams.kt:1094` maps the `DECORATIVE` product category to `"carving"` and
     * `DwSketchTracePanel.kt:404` seeds the picker from it, so a decorative record reaches this
     * runtime holding an id it does not carry. The sentence is what a designer standing in a courtyard
     * gets instead of a dead button.
     */
    @Test
    fun `the portal's own carving id is refused with the remedy named`() {
        assertEquals("carving", dwTraceSubjectFor("DECORATIVE"))
        assertNull("this register really does not carry it", Subjects.byId("carving"))

        val failure = runCatching { dwTraceKotlinApplySubject(TraceParams(), "carving") }.exceptionOrNull()
        assertTrue("expected a refusal", failure is IllegalArgumentException)
        val message = failure?.message.orEmpty()
        assertTrue(message, message.startsWith("There is no subject called \"carving\"."))
        listOf("Wood carving", "Stone carving", "Metalwork").forEach {
            assertTrue("the refusal must name “$it”", message.contains(it))
        }
        assertEquals(dwTraceKotlinNoSuchSubjectSentence("carving"), message)
    }

    /* ────────────────────────────────────────────────────────────────────────────
     * The same two halves at the DwTraceValues boundary
     * ──────────────────────────────────────────────────────────────────────────── */

    /**
     * The `DwTraceValues` forms are the `TraceParams` forms with a codec round trip around them, and
     * nothing else — asserted rather than assumed, because "nothing else" is the whole claim.
     */
    @Test
    fun `the values-level halves are the params-level halves plus a round trip`() {
        val base = TraceParams().sanitized()
        val wired = dwTraceValuesOfParams(base)

        assertEquals(
            dwTraceKotlinApplyStyle(base, "woodcut"),
            dwTraceParamsOf(dwTraceKotlinApplyStyle(wired, "woodcut")),
        )
        assertEquals(
            dwTraceKotlinApplySubject(base, "stone-carving"),
            dwTraceParamsOf(dwTraceKotlinApplySubject(wired, "stone-carving")),
        )
        assertEquals(
            "a refusal must not become something else on the way through the codec",
            dwTraceKotlinNoSuchSubjectSentence("carving"),
            runCatching { dwTraceKotlinApplySubject(wired, "carving") }.exceptionOrNull()?.message,
        )
    }

    /**
     * **THE HAND-TUNED SET HAS NO DOTTED KEY, AND IS STILL HONOURED ACROSS THE WIRE.**
     *
     * `auto.handTuned` is an array, and `dwTraceFlatten` skips arrays because no control reads one
     * (`DwSketchTraceWire.kt:1144-1146`) — so it is absent from [DwTraceValues.keys] and a caller
     * reading the flat map would conclude nothing is protected. `dwTraceParamsOf` goes through
     * [DwTraceValues.wire] instead, which carries it. This test is the proof that the guard survives
     * the boundary a runtime actually calls across, and not only the one the tests above use.
     */
    @Test
    fun `a hand-tuned knob survives a subject even though it has no leaf key`() {
        val tuned = TraceParams().copy(
            cleanup = TraceParams().cleanup.copy(minBlobArea = 31),
        ).let { it.copy(auto = it.auto.copy(handTuned = setOf(Knobs.MIN_BLOB_AREA))) }.sanitized()

        val wired = dwTraceValuesOfParams(tuned)
        assertNull("handTuned is an array, so it is not a leaf", wired["auto.handTuned"])
        assertTrue("…and therefore not in the flat map at all", wired.keys.none { it.startsWith("auto.handTuned") })

        val applied = dwTraceParamsOf(dwTraceKotlinApplySubject(wired, "painting"))
        assertEquals(31, applied.cleanup.minBlobArea)
        assertEquals(setOf(Knobs.MIN_BLOB_AREA), applied.auto.handTuned)
    }

    /** Anything else gets the bridge's bare sentence — there is nothing helpful to add about a typo. */
    @Test
    fun `an unknown subject that is nobody's id gets the bare refusal`() {
        val failure = runCatching { dwTraceKotlinApplySubject(TraceParams(), "pottery ceramics") }
            .exceptionOrNull()
        assertEquals("There is no subject called \"pottery ceramics\".", failure?.message)
        // `Subjects.byId` trims, so a padded id is a hit rather than a refusal, and the refusal
        // sentence must not print the untrimmed spelling of something that would have worked.
        assertEquals("pottery", Subjects.byId("  pottery  ")?.id)
        assertEquals(
            "There is no subject called \"carving\".",
            dwTraceKotlinNoSuchSubjectSentence("  carving  ").substringBefore(" The portal's"),
        )
    }
}
