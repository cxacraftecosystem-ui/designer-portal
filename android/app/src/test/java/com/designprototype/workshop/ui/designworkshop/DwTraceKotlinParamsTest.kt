package com.designprototype.workshop.ui.designworkshop

import com.offlinetracer.pipeline.AutoMode
import com.offlinetracer.pipeline.DenoiseMode
import com.offlinetracer.pipeline.EdgeEngine
import com.offlinetracer.pipeline.MatteMode
import com.offlinetracer.pipeline.ThinningMode
import com.offlinetracer.pipeline.TraceParams
import com.offlinetracer.pipeline.VectorModeParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwTraceKotlinParamsTest {

    private fun repoFile(path: String): String {
        val file = File("../../$path")
        assertTrue(
            "expected $path at ${file.absolutePath}. This test pins the handset's dotted keys " +
                "against the web engine's own field names; if the tree moved, fix the path — do not " +
                "delete the assertion, because a divergent key spelling is exactly how a project " +
                "saved on one client comes up empty on the other.",
            file.exists(),
        )
        return file.readText(Charsets.UTF_8)
    }

    private val webParams: String by lazy { repoFile("frontend/lib/trace/engine/params.ts") }

    private fun webFields(name: String): List<String> {
        val marker = "export interface $name {"
        val start = webParams.indexOf(marker)
        assertTrue("`frontend/lib/trace/engine/params.ts` no longer declares `$name`", start >= 0)
        val end = webParams.indexOf("\n}", start)
        assertTrue("`$name` in params.ts is never closed", end > start)
        val body = webParams.substring(start + marker.length, end)
        return Regex("\n {2}readonly (\\w+)").findAll(body).map { it.groupValues[1] }.toList()
    }

    private val webLeafKeys: List<String> by lazy {
        val out = ArrayList<String>()
        for (section in webFields("TraceParams")) {
            if (section == "styleId") {
                out += DW_TRACE_STYLE_ID_KEY
                continue
            }
            val iface = section.replaceFirstChar { it.uppercase() } + "Params"
            for (field in webFields(iface)) {
                when {
                    section == "edge" && field == "flow" ->
                        webFields("FlowSettings").forEach { out += "edge.flow.$it" }
                    section == "auto" && field == "handTuned" -> Unit
                    else -> out += "$section.$field"
                }
            }
        }
        out
    }

    @Test
    fun `every dotted key is the web engine's own field path, in the web engine's own order`() {
        assertEquals(
            "the handset's flat parameter keys have drifted from `frontend/lib/trace/engine/" +
                "params.ts`. A key that differs is a saved project one client can read and the " +
                "other cannot.",
            webLeafKeys,
            DW_TRACE_LEAF_KEYS,
        )
    }

    @Test
    fun `there are seventy-three dotted keys, being the tree's seventy-four leaves less the array`() {
        assertEquals("the web engine's declarations no longer imply 73 flat keys", 73, webLeafKeys.size)
        assertEquals("the vendored tree no longer flattens to 73 keys", 73, DW_TRACE_LEAF_KEYS.size)
        assertFalse(
            "`auto.handTuned` is a Set<String>; DwTraceValue has no list case and the JavaScript " +
                "route's flattener skips arrays. Giving it a key here would hand the same panel two " +
                "different leaf sets depending on which runtime filled it.",
            dwTraceIsLeafKey("auto.handTuned"),
        )
    }

    @Test
    fun `styleId is a top-level key`() {
        assertEquals("styleId", DW_TRACE_STYLE_ID_KEY)
        assertTrue(dwTraceIsLeafKey(DW_TRACE_STYLE_ID_KEY))
        assertEquals(
            "clean-line",
            dwTraceFlattenParams(TraceParams()).choiceAt(DW_TRACE_STYLE_ID_KEY),
        )
    }

    @Test
    fun `every control on the panel names a key this layer knows`() {
        val unknown = DW_TRACE_CONTROLS.map { it.key }.filterNot { dwTraceIsLeafKey(it) }
        assertEquals("controls keyed on leaves the engine does not have", emptyList<String>(), unknown)
        assertEquals(emptyList<String>(), dwTraceUnknownLeafKeys(DW_TRACE_CUT.keys))
    }

    @Test
    fun `the default tree survives a flatten and a nest unchanged`() {
        val flat = dwTraceFlattenParams(TraceParams())
        assertEquals(flat, dwTraceFlattenParams(dwTraceNestLeaves(flat)))
    }

    @Test
    fun `every dotted key moves its own leaf and no other`() {
        val base = TraceParams()
        val before = dwTraceFlattenParams(base)
        for (key in DW_TRACE_LEAF_KEYS) {
            val was = before.getValue(key)
            val moved = candidatesFor(was).firstNotNullOfOrNull { candidate ->
                val after = dwTraceFlattenParams(dwTraceApplyLeaves(base, mapOf(key to candidate)))
                if (after.getValue(key) == candidate && candidate != was) after else null
            }
            assertNotNull(
                "no value moved `$key`. Either the nest table never names it, or it is routed to " +
                    "another field. It was $was and the candidates were ${candidatesFor(was)}.",
                moved,
            )
            val after = moved!!
            val changed = after.keys.filter { after[it] != before[it] }
            assertEquals("patching `$key` changed more than `$key`", listOf(key), changed)
        }
    }

    @Test
    fun `a tree with every leaf moved survives a flatten and a nest unchanged`() {
        val moved = movedLeaves()
        assertEquals(73, moved.size)
        val nested = dwTraceNestLeaves(moved)
        assertEquals(moved, dwTraceFlattenParams(nested))
        val defaults = dwTraceFlattenParams(TraceParams())
        assertEquals(0, moved.count { (key, value) -> defaults[key] == value })
    }

    @Test
    fun `a tree survives being written to the wire and read back`() {
        val moved = dwTraceNestLeaves(movedLeaves())
        val params = moved.copy(
            auto = moved.auto.copy(handTuned = setOf("edge sensitivity", "simplify")),
        ).sanitized()
        assertEquals(params, dwTraceParamsOfWire(dwTraceWireOf(params)))
        assertEquals(params, dwTraceParamsOf(dwTraceValuesOfParams(params)))
    }

    @Test
    fun `a patch cannot lose the hand-tuned knob list`() {
        val base = TraceParams().copy(
            auto = TraceParams().auto.copy(handTuned = setOf("stroke width")),
        )
        val after = dwTraceApplyLeaves(base, mapOf("edge.sensitivity" to DwTraceValue.Num(0.25)))
        assertEquals(setOf("stroke width"), after.auto.handTuned)
        assertEquals(0.25f, after.edge.sensitivity, 0f)
    }

    @Test
    fun `keys this build has never heard of are ignored rather than fatal`() {
        val base = TraceParams()
        val stale = mapOf(
            "preprocess.despeckle" to DwTraceValue.Num(3.0),
            "edge.flow.sigmaN" to DwTraceValue.Num(9.0),
            "auto.handTuned" to DwTraceValue.Choice("edge sensitivity"),
            "nonsense" to DwTraceValue.Flag(true),
            "" to DwTraceValue.Absent,
        )
        assertEquals(base.sanitized(), dwTraceApplyLeaves(base, stale))
        assertEquals(stale.keys.toList(), dwTraceUnknownLeafKeys(stale.keys))
    }

    @Test
    fun `a tree carrying fields this build has never heard of still opens`() {
        val wire = """
            {
              "preprocess": { "workingLongEdge": 1024, "despeckle": 3 },
              "edge": { "engine": "SOBEL", "sensitivity": 0.25 },
              "output": { "strokeColor": 4278190080, "background": 4294967295 },
              "somethingEntirelyNew": { "a": 1 },
              "styleId": "pencil"
            }
        """.trimIndent()
        val params = dwTraceParamsOfWire(wire)

        assertEquals(1024, params.preprocess.workingLongEdge)
        assertEquals(0.25f, params.edge.sensitivity, 0f)
        assertEquals("pencil", params.styleId)
        assertEquals(EdgeEngine.FDOG, params.edge.engine)
        assertEquals(0xFF000000.toInt(), params.output.strokeColor)
        assertEquals(0xFFFFFFFF.toInt(), params.output.background)
        assertEquals(TraceParams().cleanup, params.cleanup)
        assertEquals(TraceParams().matte, params.matte)
    }

    @Test
    fun `values outside the engine's range come back at the engine's bounds`() {
        val clamped = dwTraceNestLeaves(
            mapOf(
                "preprocess.workingLongEdge" to DwTraceValue.Num(99.0),
                "preprocess.gamma" to DwTraceValue.Num(1000.0),
                "preprocess.claheClip" to DwTraceValue.Num(0.0),
                "edge.dogK" to DwTraceValue.Num(1.0),
                "edge.sensitivity" to DwTraceValue.Num(-5.0),
                "cleanup.maxBridgeAngle" to DwTraceValue.Num(400.0),
                "output.corner" to DwTraceValue.Num(-1.0),
                "auto.minConfidence" to DwTraceValue.Num(0.0),
            ),
        )
        assertEquals(256, clamped.preprocess.workingLongEdge)
        assertEquals(10f, clamped.preprocess.gamma, 0f)
        assertEquals(1f, clamped.preprocess.claheClip, 0f)
        assertEquals(1.05f, clamped.edge.dogK, 0f)
        assertEquals(0f, clamped.edge.sensitivity, 0f)
        assertEquals(180f, clamped.cleanup.maxBridgeAngle, 0f)
        assertEquals(0f, clamped.output.corner, 0f)
        assertEquals(0.05f, clamped.auto.minConfidence, 0f)
    }

    @Test
    fun `integer leaves truncate toward zero`() {
        val up = dwTraceNestLeaves(mapOf("preprocess.workingLongEdge" to DwTraceValue.Num(2047.9999)))
        assertEquals(2047, up.preprocess.workingLongEdge)
        val down = dwTraceNestLeaves(mapOf("cleanup.minBlobArea" to DwTraceValue.Num(23.9)))
        assertEquals(23, down.cleanup.minBlobArea)
    }

    @Test
    fun `an empty style id becomes the engine's default style`() {
        val blank = dwTraceNestLeaves(mapOf(DW_TRACE_STYLE_ID_KEY to DwTraceValue.Choice("   ")))
        assertEquals("clean-line", blank.styleId)
    }

    @Test
    fun `nesting is idempotent`() {
        val once = dwTraceNestLeaves(movedLeaves())
        val twice = dwTraceNestLeaves(dwTraceFlattenParams(once))
        assertEquals(once, twice)
        assertEquals(dwTraceWireOf(once), dwTraceWireOf(twice))
    }

    @Test
    fun `packed colours cross as unsigned thirty-two bit numbers`() {
        val flat = dwTraceFlattenParams(TraceParams())
        assertEquals(4278190080.0, flat.numberAt("output.strokeColor"), 0.0)
        assertEquals(0xFF000000.toInt(), TraceParams().output.strokeColor)

        val white = dwTraceNestLeaves(mapOf("output.background" to DwTraceValue.Num(DW_TRACE_OPAQUE_WHITE)))
        assertEquals(0xFFFFFFFF.toInt(), white.output.background)
        assertEquals(
            DW_TRACE_OPAQUE_WHITE,
            dwTraceFlattenParams(white).numberAt("output.background"),
            0.0,
        )
    }

    @Test
    fun `a transparent background is absent, not missing and not a string`() {
        val transparent = dwTraceValuesOfParams(TraceParams())
        assertEquals(DwTraceValue.Absent, transparent["output.background"])
        assertFalse(transparent.present("output.background"))
        assertNull(dwTraceParamsOf(transparent).output.background)

        val white = dwTraceValuesOfParams(
            dwTraceNestLeaves(mapOf("output.background" to DwTraceValue.Num(DW_TRACE_OPAQUE_WHITE))),
        )
        assertTrue(white.present("output.background"))

        val back = dwTraceApplyLeaves(
            dwTraceParamsOf(white),
            mapOf("output.background" to DwTraceValue.Absent),
        )
        assertNull("Absent must set the leaf back to the engine's null", back.output.background)
    }

    @Test
    fun `enum leaves carry the member name`() {
        val picked = dwTraceNestLeaves(
            mapOf(
                "edge.engine" to DwTraceValue.Choice("ADAPTIVE"),
                "preprocess.denoise" to DwTraceValue.Choice("MEDIAN"),
                "matte.mode" to DwTraceValue.Choice("SALIENCY"),
                "cleanup.thinning" to DwTraceValue.Choice("GUO_HALL"),
                "output.vectorMode" to DwTraceValue.Choice("OUTLINE"),
                "auto.mode" to DwTraceValue.Choice("APPLY"),
            ),
        )
        assertEquals(EdgeEngine.ADAPTIVE, picked.edge.engine)
        assertEquals(DenoiseMode.MEDIAN, picked.preprocess.denoise)
        assertEquals(MatteMode.SALIENCY, picked.matte.mode)
        assertEquals(ThinningMode.GUO_HALL, picked.cleanup.thinning)
        assertEquals(VectorModeParam.OUTLINE, picked.output.vectorMode)
        assertEquals(AutoMode.APPLY, picked.auto.mode)

        val flat = dwTraceFlattenParams(picked)
        assertEquals("ADAPTIVE", flat.choiceAt("edge.engine"))
        assertEquals("GUO_HALL", flat.choiceAt("cleanup.thinning"))
        assertEquals("OUTLINE", flat.choiceAt("output.vectorMode"))
    }

    @Test
    fun `an unknown enum member falls back to that property's default`() {
        val base = dwTraceNestLeaves(mapOf("edge.engine" to DwTraceValue.Choice("CANNY")))
        assertEquals(EdgeEngine.CANNY, base.edge.engine)
        val after = dwTraceApplyLeaves(base, mapOf("edge.engine" to DwTraceValue.Choice("SOBEL")))
        assertEquals(
            "the web's enumOf falls back to the documented default, not to the previous value",
            EdgeEngine.FDOG,
            after.edge.engine,
        )
    }

    @Test
    fun `a value that is not a number is refused rather than quietly replaced`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val failure = runCatching {
                dwTraceNestLeaves(mapOf("edge.sensitivity" to DwTraceValue.Num(bad)))
            }.exceptionOrNull()
            assertTrue("$bad was accepted", failure is DwTraceHostFailure)
            assertEquals(
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                (failure as DwTraceHostFailure).kind,
            )
        }
    }

    @Test
    fun `a leaf sent as the wrong kind is refused`() {
        val wrong = listOf(
            "edge.sensitivity" to DwTraceValue.Choice("quite high"),
            "cleanup.skeletonize" to DwTraceValue.Num(1.0),
            "edge.engine" to DwTraceValue.Flag(true),
            "output.strokeColor" to DwTraceValue.Absent,
        )
        for ((key, value) in wrong) {
            val failure = runCatching { dwTraceNestLeaves(mapOf(key to value)) }.exceptionOrNull()
            assertTrue("$key accepted a $value", failure is DwTraceHostFailure)
            assertTrue(
                "the refusal must name the key it is about, and it said: ${failure?.message}",
                (failure as DwTraceHostFailure).detail.contains(key),
            )
        }
    }

    @Test
    fun `an unreadable tree is refused`() {
        for (bad in listOf("", "not json", "[1,2,3]", "\"a string\"")) {
            val failure = runCatching { dwTraceParamsOfWire(bad) }.exceptionOrNull()
            assertTrue("`$bad` was accepted as a parameter tree", failure is DwTraceHostFailure)
        }
    }

    private fun Map<String, DwTraceValue>.numberAt(key: String): Double =
        (getValue(key) as DwTraceValue.Num).value

    private fun Map<String, DwTraceValue>.choiceAt(key: String): String =
        (getValue(key) as DwTraceValue.Choice).value

    private val enumNames: List<String> =
        (EdgeEngine.entries.map { it.name } + DenoiseMode.entries.map { it.name } +
            MatteMode.entries.map { it.name } + AutoMode.entries.map { it.name } +
            ThinningMode.entries.map { it.name } + VectorModeParam.entries.map { it.name })
            .distinct()

    private fun candidatesFor(was: DwTraceValue): List<DwTraceValue> = when (was) {
        is DwTraceValue.Flag -> listOf(DwTraceValue.Flag(!was.value))
        is DwTraceValue.Num ->
            listOf(2.0, 0.5, 0.25, 4.0, 1024.0).map { DwTraceValue.Num(it) }
        is DwTraceValue.Choice -> enumNames.map { DwTraceValue.Choice(it) }
        DwTraceValue.Absent -> listOf(DwTraceValue.Num(DW_TRACE_OPAQUE_WHITE))
    }

    private fun movedLeaves(): Map<String, DwTraceValue> {
        val base = TraceParams()
        val before = dwTraceFlattenParams(base)
        val out = LinkedHashMap<String, DwTraceValue>(before.size)
        for (key in DW_TRACE_LEAF_KEYS) {
            val was = before.getValue(key)
            val hit = candidatesFor(was).firstOrNull { candidate ->
                val after = dwTraceFlattenParams(dwTraceApplyLeaves(base, mapOf(key to candidate)))
                after.getValue(key) == candidate && candidate != was
            }
            assertNotNull("no value moved `$key`", hit)
            out[key] = hit!!
        }
        return out
    }
}
