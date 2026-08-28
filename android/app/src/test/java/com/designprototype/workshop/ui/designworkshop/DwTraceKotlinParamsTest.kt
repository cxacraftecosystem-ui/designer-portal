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

/**
 * **THE PARAMETER FLATTENING, WHICH IS THE ONE PART OF THE KOTLIN ENGINE PORT THAT CAN BE WRONG
 * WITHOUT ANYTHING FAILING.**
 *
 * Everything else in a trace announces its own breakage: a missing engine throws, an unreadable
 * photograph refuses, a cancelled job cancels. This layer does not. Route `edge.blurSigma` to
 * `edge.dogSigma` and every trace still succeeds — it succeeds with a slightly different drawing,
 * for the life of the product, on one of the two clients. Spell a key `preprocess.longEdge` and a
 * saved project opens on the portal and comes up at factory defaults on the handset, silently.
 *
 * So the expected values here come from SOMEWHERE ELSE wherever that is possible — the web engine's
 * own `params.ts`, read off disk and parsed; the vendored `TraceParams.sanitized()`'s own clamps; the
 * flattener the JavaScript route already ships. That is `DwSketchTraceWireTest`'s rule for the
 * sibling layer and `DwSketchTraceParamsTest`'s rule for the control table, and it is the only kind
 * of check that can catch a conversion which is consistently wrong.
 */
class DwTraceKotlinParamsTest {

    // ---------------------------------------------------------------------------------------------
    // Reading the web engine
    // ---------------------------------------------------------------------------------------------

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

    /** The `readonly` field names of one exported interface, in file order. */
    private fun webFields(name: String): List<String> {
        val marker = "export interface $name {"
        val start = webParams.indexOf(marker)
        assertTrue("`frontend/lib/trace/engine/params.ts` no longer declares `$name`", start >= 0)
        val end = webParams.indexOf("\n}", start)
        assertTrue("`$name` in params.ts is never closed", end > start)
        val body = webParams.substring(start + marker.length, end)
        return Regex("\n {2}readonly (\\w+)").findAll(body).map { it.groupValues[1] }.toList()
    }

    /**
     * Every dotted key the web engine's declarations imply, in the web engine's own order.
     *
     * Derived, not transcribed — including the section list, which comes from `TraceParams` itself
     * rather than from this test's memory of it. The two hand-written rules are the two structural
     * facts, and both are stated in `DwTraceKotlinParams.kt`'s header: `edge.flow` is a nested record
     * and expands in place, and `auto.handTuned` is an array and has no flat key.
     */
    private val webLeafKeys: List<String> by lazy {
        val out = ArrayList<String>()
        for (section in webFields("TraceParams")) {
            if (section == "styleId") {
                out += DW_TRACE_STYLE_ID_KEY
                continue
            }
            // `preprocess` -> `PreprocessParams`, and so on for all six.
            val iface = section.replaceFirstChar { it.uppercase() } + "Params"
            for (field in webFields(iface)) {
                when {
                    // The one nested record. Its interface is `FlowSettings`, not `FlowParams`.
                    section == "edge" && field == "flow" ->
                        webFields("FlowSettings").forEach { out += "edge.flow.$it" }
                    section == "auto" && field == "handTuned" -> Unit
                    else -> out += "$section.$field"
                }
            }
        }
        out
    }

    // ---------------------------------------------------------------------------------------------
    // The keys
    // ---------------------------------------------------------------------------------------------

    /**
     * THE ASSERTION THIS WHOLE FILE EXISTS FOR. Same names, same order, no exceptions.
     *
     * `DW_TRACE_LEAF_KEYS` is produced by encoding the vendored `TraceParams()` with kotlinx and
     * walking the JSON, so it is the KOTLIN data classes talking. `webLeafKeys` is produced by
     * parsing the TypeScript. Neither side is a copy of the other and neither is a copy of this test,
     * so this passes only if the two engines genuinely agree about what every leaf is called.
     */
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

    /**
     * The arithmetic in `DwTraceKotlinParams.kt`'s header, run rather than remembered.
     *
     * 74 leaves in `Params.kt`, less `auto.handTuned` — the one array, which `dwTraceFlatten` skips
     * for both runtimes — is 73 dotted keys. Both censuses have to land on it, so a regex here that
     * quietly matched nothing cannot pass.
     */
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

    /** The one key that is not under a section, and the constant the surface already reads it by. */
    @Test
    fun `styleId is a top-level key`() {
        assertEquals("styleId", DW_TRACE_STYLE_ID_KEY)
        assertTrue(dwTraceIsLeafKey(DW_TRACE_STYLE_ID_KEY))
        assertEquals(
            "clean-line",
            dwTraceFlattenParams(TraceParams()).choiceAt(DW_TRACE_STYLE_ID_KEY),
        )
    }

    /**
     * Every control the panel draws names a leaf that exists.
     *
     * `dwTraceMissingKeys` prints a sentence when it does not, which is the honest behaviour at run
     * time; this is the check that no shipped build ever has to print it.
     */
    @Test
    fun `every control on the panel names a key this layer knows`() {
        val unknown = DW_TRACE_CONTROLS.map { it.key }.filterNot { dwTraceIsLeafKey(it) }
        assertEquals("controls keyed on leaves the engine does not have", emptyList<String>(), unknown)
        assertEquals(emptyList<String>(), dwTraceUnknownLeafKeys(DW_TRACE_CUT.keys))
    }

    // ---------------------------------------------------------------------------------------------
    // The round trip
    // ---------------------------------------------------------------------------------------------

    /**
     * `flatten(nest(v)) == v`, on the engine's defaults.
     *
     * The weaker of the two round-trip cases and the one that would pass even if half the table were
     * missing, since an unwired key would simply keep its default. It is here because it is the exact
     * sentence the brief asks for; the case below is the one with teeth.
     */
    @Test
    fun `the default tree survives a flatten and a nest unchanged`() {
        val flat = dwTraceFlattenParams(TraceParams())
        assertEquals(flat, dwTraceFlattenParams(dwTraceNestLeaves(flat)))
    }

    /**
     * **EVERY ONE OF THE 73 KEYS IS WIRED, PROVED ONE KEY AT A TIME.**
     *
     * For each key this moves that leaf ALONE to a value the engine's own clamps accept, nests the
     * one-key patch, flattens the answer, and demands the leaf came back EXACTLY as sent and
     * different from where it started. That is three faults in one assertion: a key the nest table
     * never names (the value would not move), a key routed to the wrong field (this leaf would not
     * move and another would), and a value mangled on the way through (it would come back changed).
     *
     * The candidate values are deliberately dull — 2, 0.5, a quarter, 4, and 1024 for the working
     * long edge, whose floor is 256 — and the first one the engine accepts unchanged is the one used.
     * Nothing here encodes a bound: if the engine clamps a candidate, the search simply moves on.
     */
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

    /**
     * The same round trip with EVERY leaf off its default at once, which is the case a per-key search
     * cannot catch: two keys that read each other's value swap cleanly one at a time.
     */
    @Test
    fun `a tree with every leaf moved survives a flatten and a nest unchanged`() {
        val moved = movedLeaves()
        assertEquals(73, moved.size)
        val nested = dwTraceNestLeaves(moved)
        assertEquals(moved, dwTraceFlattenParams(nested))
        // And it is genuinely a different tree, not the defaults arriving twice.
        val defaults = dwTraceFlattenParams(TraceParams())
        assertEquals(0, moved.count { (key, value) -> defaults[key] == value })
    }

    /** The tree form, through the wire the two clients actually exchange. */
    @Test
    fun `a tree survives being written to the wire and read back`() {
        val moved = dwTraceNestLeaves(movedLeaves())
        val params = moved.copy(
            // The one leaf a flat map cannot carry. It has to survive the wire or auto-detection
            // would forget which knobs the designer moved by hand.
            auto = moved.auto.copy(handTuned = setOf("edge sensitivity", "simplify")),
        ).sanitized()
        assertEquals(params, dwTraceParamsOfWire(dwTraceWireOf(params)))
        assertEquals(params, dwTraceParamsOf(dwTraceValuesOfParams(params)))
    }

    /**
     * `auto.handTuned` is carried, not dropped, when a patch goes past it.
     *
     * The failure this guards is silent and consequential: auto-detection would overwrite a knob the
     * designer had set by hand and `Knobs.restore` would have nothing to put back.
     */
    @Test
    fun `a patch cannot lose the hand-tuned knob list`() {
        val base = TraceParams().copy(
            auto = TraceParams().auto.copy(handTuned = setOf("stroke width")),
        )
        val after = dwTraceApplyLeaves(base, mapOf("edge.sensitivity" to DwTraceValue.Num(0.25)))
        assertEquals(setOf("stroke width"), after.auto.handTuned)
        assertEquals(0.25f, after.edge.sensitivity, 0f)
    }

    // ---------------------------------------------------------------------------------------------
    // Unknown keys
    // ---------------------------------------------------------------------------------------------

    /**
     * A saved project from an older build names leaves that no longer exist. IT MUST OPEN.
     *
     * The web reaches the same end by a different road — `withOverrides` spreads the stale property
     * onto the merged object and `sanitizeTraceParams` drops it on the next line — so a handset that
     * threw here would refuse a file the portal opens without comment.
     */
    @Test
    fun `keys this build has never heard of are ignored rather than fatal`() {
        val base = TraceParams()
        val stale = mapOf(
            "preprocess.despeckle" to DwTraceValue.Num(3.0),          // removed leaf
            "edge.flow.sigmaN" to DwTraceValue.Num(9.0),              // renamed leaf
            "auto.handTuned" to DwTraceValue.Choice("edge sensitivity"), // the array, as a name
            "nonsense" to DwTraceValue.Flag(true),                    // not even a path
            "" to DwTraceValue.Absent,                                // an empty key
        )
        assertEquals(base.sanitized(), dwTraceApplyLeaves(base, stale))
        assertEquals(stale.keys.toList(), dwTraceUnknownLeafKeys(stale.keys))
    }

    /** The same tolerance in the tree form, where a stale field would otherwise be a decode failure. */
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
        // An unknown enum member falls back to that property's default, which is `enumOf`'s contract
        // on the web and `coerceInputValues`' behaviour here. It must not throw and must not be null.
        assertEquals(EdgeEngine.FDOG, params.edge.engine)
        // The portal's unsigned colours, which are both above Int.MAX and would otherwise not
        // decode into an Int at all.
        assertEquals(0xFF000000.toInt(), params.output.strokeColor)
        assertEquals(0xFFFFFFFF.toInt(), params.output.background)
        // Sections the file never mentioned come up at the engine's defaults, not at zero.
        assertEquals(TraceParams().cleanup, params.cleanup)
        assertEquals(TraceParams().matte, params.matte)
    }

    // ---------------------------------------------------------------------------------------------
    // Clamping, which is the engine's and never this layer's
    // ---------------------------------------------------------------------------------------------

    /**
     * Out-of-range values come back at the ENGINE's bounds.
     *
     * Every expected value below is `TraceParams.sanitized()`'s, quoted from `Params.kt` beside it,
     * because the point of the assertion is that this layer contributes no bound of its own. If a
     * re-vendor moves one of these, this test fails and the right fix is to update the number here —
     * never to clamp on this side of the seam.
     */
    @Test
    fun `values outside the engine's range come back at the engine's bounds`() {
        val clamped = dwTraceNestLeaves(
            mapOf(
                "preprocess.workingLongEdge" to DwTraceValue.Num(99.0),    // MIN_WORKING_EDGE 256
                "preprocess.gamma" to DwTraceValue.Num(1000.0),            // 0.05..10
                "preprocess.claheClip" to DwTraceValue.Num(0.0),           // 1..16
                "edge.dogK" to DwTraceValue.Num(1.0),                      // 1.05..8
                "edge.sensitivity" to DwTraceValue.Num(-5.0),              // 0..1
                "cleanup.maxBridgeAngle" to DwTraceValue.Num(400.0),       // 0..MAX_ANGLE 180
                "output.corner" to DwTraceValue.Num(-1.0),                 // 0..180
                "auto.minConfidence" to DwTraceValue.Num(0.0),             // 0.05..1
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

    /**
     * Integer leaves truncate toward zero, which is `Math.trunc` and not `Math.round`.
     *
     * `DwSketchTraceParams.kt:217-225` records why it matters on the slider side: a leaf sent as
     * 2047.9999 comes back 2047, and a readout that settles one below the thumb looks like a broken
     * control. The handset's sliders round before they patch; this is the layer underneath agreeing.
     */
    @Test
    fun `integer leaves truncate toward zero`() {
        val up = dwTraceNestLeaves(mapOf("preprocess.workingLongEdge" to DwTraceValue.Num(2047.9999)))
        assertEquals(2047, up.preprocess.workingLongEdge)
        val down = dwTraceNestLeaves(mapOf("cleanup.minBlobArea" to DwTraceValue.Num(23.9)))
        assertEquals(23, down.cleanup.minBlobArea)
    }

    /** An empty style id falls back to the engine's own default rather than staying empty. */
    @Test
    fun `an empty style id becomes the engine's default style`() {
        val blank = dwTraceNestLeaves(mapOf(DW_TRACE_STYLE_ID_KEY to DwTraceValue.Choice("   ")))
        assertEquals("clean-line", blank.styleId)
    }

    /** Sanitising is idempotent, so nesting the same map twice cannot drift. */
    @Test
    fun `nesting is idempotent`() {
        val once = dwTraceNestLeaves(movedLeaves())
        val twice = dwTraceNestLeaves(dwTraceFlattenParams(once))
        assertEquals(once, twice)
        assertEquals(dwTraceWireOf(once), dwTraceWireOf(twice))
    }

    // ---------------------------------------------------------------------------------------------
    // Colours
    // ---------------------------------------------------------------------------------------------

    /**
     * The two colour leaves cross UNSIGNED, which is the one place the two engines spell a value
     * differently and therefore the one place a project can be silently mis-read.
     *
     * 4278190080 is `0xFF000000` as JavaScript writes it and -16777216 is the same colour as Kotlin
     * holds it. `DW_TRACE_OPAQUE_WHITE` is the handset's own constant for opaque white, patched by
     * the "White background" toggle, and it is above `Int.MAX_VALUE` — a naive `Double.toInt()` would
     * saturate it to 0x7FFFFFFF, which is a transparent mid-grey.
     */
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

    /**
     * `output.background: null` is a VALUE and not an absence — the only spelling of a transparent
     * export — so it has to round-trip as [DwTraceValue.Absent] in both directions.
     *
     * `dwTraceFlatten`'s own header records the bug this guards: `JsonNull` is a `JsonPrimitive`, so
     * a check in the wrong order reads it as the string "null" and the toggle sticks on forever.
     */
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

    // ---------------------------------------------------------------------------------------------
    // Enums
    // ---------------------------------------------------------------------------------------------

    /** The engine's enum members are their own wire strings, which is what the choice rows assume. */
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

    /**
     * A member name this build does not have falls back to the property's DEFAULT, not to the value
     * that was there — which is what the web's `enumOf` does after `withOverrides` has merged.
     */
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

    // ---------------------------------------------------------------------------------------------
    // Refusals
    // ---------------------------------------------------------------------------------------------

    /**
     * A non-finite number is refused rather than substituted, in the same words `dwTracePatchJson`
     * refuses one on the JavaScript route — "the slider moves, the trace runs, and the parameter the
     * designer changed is the one that did not change".
     */
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

    /** A leaf sent as the wrong kind is a caller bug, and a caller bug should be loud. */
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
                failure?.message.orEmpty().contains(key),
            )
        }
    }

    /** A wire that is not a JSON object is refused with a sentence, not a stack trace. */
    @Test
    fun `an unreadable tree is refused`() {
        for (bad in listOf("", "not json", "[1,2,3]", "\"a string\"")) {
            val failure = runCatching { dwTraceParamsOfWire(bad) }.exceptionOrNull()
            assertTrue("`$bad` was accepted as a parameter tree", failure is DwTraceHostFailure)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun Map<String, DwTraceValue>.numberAt(key: String): Double =
        (getValue(key) as DwTraceValue.Num).value

    private fun Map<String, DwTraceValue>.choiceAt(key: String): String =
        (getValue(key) as DwTraceValue.Choice).value

    /** Every enum member name the engine has, so a search can find one a given leaf accepts. */
    private val enumNames: List<String> =
        (EdgeEngine.entries.map { it.name } + DenoiseMode.entries.map { it.name } +
            MatteMode.entries.map { it.name } + AutoMode.entries.map { it.name } +
            ThinningMode.entries.map { it.name } + VectorModeParam.entries.map { it.name })
            .distinct()

    /**
     * Values worth trying for a leaf currently holding [was].
     *
     * Deliberately not a table of per-leaf bounds — that would be the clamp table this port refuses
     * to keep. The caller tries them in order and takes the first the engine hands back unchanged,
     * so a candidate outside some leaf's range is simply skipped.
     */
    private fun candidatesFor(was: DwTraceValue): List<DwTraceValue> = when (was) {
        is DwTraceValue.Flag -> listOf(DwTraceValue.Flag(!was.value))
        // 1024 is here for `preprocess.workingLongEdge` alone, whose floor is 256.
        is DwTraceValue.Num ->
            listOf(2.0, 0.5, 0.25, 4.0, 1024.0).map { DwTraceValue.Num(it) }
        is DwTraceValue.Choice -> enumNames.map { DwTraceValue.Choice(it) }
        // The only leaf that starts Absent is `output.background`, and it holds a packed colour.
        DwTraceValue.Absent -> listOf(DwTraceValue.Num(DW_TRACE_OPAQUE_WHITE))
    }

    /** Every leaf moved off its default at once, by the same search the per-key test uses. */
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
