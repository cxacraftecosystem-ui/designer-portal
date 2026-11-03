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

class DwTraceKotlinPresetsTest {

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

    private val presetCall = Regex("""^ {2}preset\(\s*'([^']+)',\s*'([^']*)',\s*(GROUP_\w+),""", RegexOption.MULTILINE)

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

    @Test
    fun `the twenty style ids are the portal's, in the portal's order`() {
        assertEquals(portalStyles().map { it.id }, Styles.ALL.map { it.id })
        assertEquals(
            "the picker rows carry the register's ids untouched",
            Styles.ALL.map { it.id },
            dwTraceKotlinPresetTables().styles.map { it.id },
        )
    }

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

    @Test
    fun `the style rows are the engine's own strings`() {
        val rows = dwTraceKotlinPresetTables().styles
        assertEquals(20, rows.size)
        assertEquals(Styles.ALL.map { it.name }, rows.map { it.name })
        assertEquals(Styles.ALL.map { it.description }, rows.map { it.description })
        assertEquals(Styles.ALL.map { it.group }, rows.map { it.group })
    }

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
        assertEquals(
            listOf(
                "painting", "pottery", "textile", "jewellery", "sculpture",
                "architecture", "logo", "sketch", "photo",
            ),
            engine.filter { it in portal },
        )
        assertEquals(engine.filter { it in portal }, portal.filter { it in engine })
    }

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

    @Test
    fun `an unknown style is refused rather than silently replaced by the default`() {
        val failure = runCatching { dwTraceKotlinApplyStyle(TraceParams(), "clean-lines") }.exceptionOrNull()
        assertTrue("expected a refusal", failure is IllegalArgumentException)
        assertEquals("There is no style called \"clean-lines\".", failure?.message)
        assertEquals(dwTraceKotlinNoSuchStyleSentence("clean-lines"), failure?.message)

    }

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

    @Test
    fun `applying a subject leaves the auto block alone`() {
        val base = TraceParams().copy(
            auto = TraceParams().auto.copy(mode = AutoMode.OFF, subjectId = "photo"),
        ).sanitized()
        val applied = dwTraceKotlinApplySubject(base, "pottery")
        assertEquals(base.auto, applied.auto)
        assertEquals(AutoMode.OFF, applied.auto.mode)
    }

    @Test
    fun `the handset's knob labels are the Kotlin engine's own`() {
        assertEquals(Knobs.ALL, DW_TRACE_KNOB_NAMES)
        assertEquals(6, Knobs.ALL.size)
        assertEquals(Knobs.ALL.toSet(), DW_TRACE_KNOB_KEY_FOR_NAME.keys)
    }

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

        assertEquals(unprotectedRun.preprocess, protectedRun.preprocess)
    }

    @Test
    fun `applying a subject twice adjusts twice, unlike the portal's idempotent tables`() {
        val base = Styles.byId("clean-line")!!.params.sanitized()
        assertEquals("the engine default this arithmetic starts from", 24, base.cleanup.minBlobArea)

        val once = dwTraceKotlinApplySubject(base, "painting")
        val twice = dwTraceKotlinApplySubject(once, "painting")

        assertEquals(60, once.cleanup.minBlobArea)
        assertEquals(150, twice.cleanup.minBlobArea)
        assertNotEquals(once, twice)
        assertEquals(twice, twice.sanitized())
    }

    @Test
    fun `the portal's own carving id is refused with the remedy named`() {
        assertEquals("carving", dwTraceSubjectFor("DECORATIVE"))
        assertNull("this register really does not carry it", Subjects.byId("carving"))

        val failure = runCatching { dwTraceKotlinApplySubject(TraceParams(), "carving") }.exceptionOrNull()
        assertTrue("expected a refusal", failure is IllegalArgumentException)
        val message = failure?.message.orEmpty()
        assertTrue(message, message.startsWith("There is no subject called \"carving\"."))
        listOf("Wood carving", "Stone carving").forEach {
            assertTrue("the refusal must name “$it”", message.contains(it))
        }
        assertEquals(dwTraceKotlinNoSuchSubjectSentence("carving"), message)
    }

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

    @Test
    fun `an unknown subject that is nobody's id gets the bare refusal`() {
        val failure = runCatching { dwTraceKotlinApplySubject(TraceParams(), "pottery ceramics") }
            .exceptionOrNull()
        assertEquals("There is no subject called \"pottery ceramics\".", failure?.message)
        assertEquals("pottery", Subjects.byId("  pottery  ")?.id)
        assertEquals(
            "There is no subject called \"carving\".",
            dwTraceKotlinNoSuchSubjectSentence("  carving  ").substringBefore(" Choose"),
        )
    }
}
