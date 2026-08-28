package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * **THE MARSHALLING, WHICH IS WHERE THIS FEATURE CAN BE WRONG WITHOUT ANYTHING FAILING.**
 *
 * Every other way the handset tracer can break announces itself: a dead sandbox throws, a bad script
 * throws, a missing bundle refuses. The conversions in `DwSketchTraceWire.kt` do not. Swap two colour
 * channels and the engine traces happily — it traces a picture with red and blue exchanged, which on
 * a pencil sketch on cream paper looks very nearly right and comes out quietly different from the
 * portal's answer for the life of the product. Read the coordinates big-endian and the trace draws
 * noise. Read `output.background: null` as the string "null" and a toggle is stuck on forever.
 *
 * So these are the cases where the expected value comes from SOMEWHERE ELSE — the vendored engine's
 * own arithmetic, or the web module this is the mirror of — rather than from whatever this port
 * printed the day it was written. That is `DwSketchRectifyTest`'s rule for the sibling feature and it
 * is the only kind of test that can catch a conversion that is consistently wrong.
 *
 * What is NOT here is anything needing a device. `app/build.gradle.kts` declares JUnit 4 and no
 * Robolectric, so `android.graphics` is out of reach by construction — which is exactly why the
 * arithmetic was put in a file that imports no Android and the drawing was put in one that does.
 */
class DwSketchTraceWireTest {

    /* ── pixels in ──────────────────────────────────────────────────────────────────────────── */

    /**
     * The channel order, against `engine/buffers.ts:317-319` rather than against itself.
     *
     * That function is `px[i] = ((d[j+3] << 24) | (d[j] << 16) | (d[j+1] << 8) | d[j+2]) >>> 0` — so
     * the engine reads `[R, G, B, A]` and packs `0xAARRGGBB`, which is exactly what Android's
     * `Bitmap.getPixels` hands out. This case re-implements the ENGINE'S line and asserts the round
     * trip, so a swapped pair fails here rather than in a ministry document.
     */
    @Test
    fun `a row of packed ARGB becomes the bytes the engine reads`() {
        val pixels = intArrayOf(
            0xFF102030.toInt(), // opaque, r=16 g=32 b=48
            0x80AABBCC.toInt(), // half alpha
            0x00000000,
            0xFFFFFFFF.toInt(),
        )
        val out = ByteArray(pixels.size * 4)
        dwTraceArgbRowToRgba(pixels, pixels.size, out, 0)

        assertEquals("R", 0x10, out[0].toInt() and 0xFF)
        assertEquals("G", 0x20, out[1].toInt() and 0xFF)
        assertEquals("B", 0x30, out[2].toInt() and 0xFF)
        assertEquals("A", 0xFF, out[3].toInt() and 0xFF)

        for (i in pixels.indices) {
            val r = out[i * 4].toInt() and 0xFF
            val g = out[i * 4 + 1].toInt() and 0xFF
            val b = out[i * 4 + 2].toInt() and 0xFF
            val a = out[i * 4 + 3].toInt() and 0xFF
            // `RgbaImage.fromImageData`, transcribed from the vendored source.
            val repacked = (a shl 24) or (r shl 16) or (g shl 8) or b
            assertEquals("pixel $i does not survive the engine's own repacking", pixels[i], repacked)
        }
    }

    /** The row offset is honoured, so a frame is not written over itself one row at a time. */
    @Test
    fun `rows are written at their own offset`() {
        val out = ByteArray(2 * 2 * 4)
        dwTraceArgbRowToRgba(intArrayOf(0xFF010203.toInt(), 0xFF040506.toInt()), 2, out, 0)
        dwTraceArgbRowToRgba(intArrayOf(0xFF070809.toInt(), 0xFF0A0B0C.toInt()), 2, out, 8)
        assertEquals(0x01, out[0].toInt() and 0xFF)
        assertEquals(0x07, out[8].toInt() and 0xFF)
        assertEquals(0x0A, out[12].toInt() and 0xFF)
    }

    /**
     * The working size, against `decodeToPixels.ts:110-125` case for case.
     *
     * `Math.round` and not a floor, because the two clients must hand the engine the same number of
     * pixels: one pixel of difference in the working frame moves every coordinate the engine reports,
     * and the parity harness compares coordinates.
     */
    @Test
    fun `the working size is the web's, rounding and all`() {
        assertEquals("a source inside the cap is untouched", 1600 to 1200, dwTraceWorkingSize(1600, 1200, 4096))
        assertEquals("and is never upscaled", 320 to 240, dwTraceWorkingSize(320, 240, 4096))
        // 6000 * 4096 / 9000 = 2730.666…, which rounds UP. A floor would answer 2730.
        assertEquals(4096 to 2731, dwTraceWorkingSize(9000, 6000, 4096))
        assertEquals("portrait caps on the long edge too", 2731 to 4096, dwTraceWorkingSize(6000, 9000, 4096))
        assertEquals("a plate keeps its aspect", 1024 to 768, dwTraceWorkingSize(1600, 1200, 1024))
        // A frame so thin the short edge rounds to zero still has a pixel in it — the hairline case
        // the parity corpus carries deliberately (2048x3, aspect 682:1).
        val (w, h) = dwTraceWorkingSize(2048, 3, 1024)
        assertEquals(1024, w)
        assertTrue("a rounded-away edge must not become zero", h >= 1)
    }

    /* ── geometry in ────────────────────────────────────────────────────────────────────────── */

    @Test
    fun `geometry decodes little-endian and mirrors the engine's layout`() {
        val decoded = dwTraceReadEnvelope(doneEnvelope())
        assertTrue(decoded is DwTraceEnvelope.Done)
        val geometry = (decoded as DwTraceEnvelope.Done).decoded.geometry

        assertEquals(1, geometry.shapeCount)
        assertTrue(geometry.isClosed(0))
        // Little-endian. Read the other way round, 10.0f (0x41200000) comes back as 5.1e-40.
        assertEquals(0f, geometry.coords[0], 0f)
        assertEquals(10f, geometry.coords[2], 0f)
        assertEquals(10f, geometry.coords[5], 0f)
        assertEquals(DW_TRACE_VERB_LINE, geometry.verbs[0])
        assertEquals(2, geometry.verbStarts[1])
        assertEquals(6, geometry.coordStarts[1])
    }

    /**
     * `0xff000000` is 4,278,190,080 — past `Int.MAX_VALUE`.
     *
     * A direct `toIntOrNull()` on that answers null, and null is the engine's spelling of "no
     * stroke", so getting this wrong does not throw: every line silently stops being drawn. The
     * narrowing through `Long` is the conversion, and `-16777216` is the ARGB `Int` Android paints
     * black with.
     */
    @Test
    fun `an opaque black stroke survives being larger than Int MAX_VALUE`() {
        val done = dwTraceReadEnvelope(doneEnvelope()) as DwTraceEnvelope.Done
        val style = done.decoded.geometry.styleOf(0)
        assertEquals(0xFF000000.toInt(), style.stroke)
        assertEquals(-16777216, style.stroke)
        assertNull("null is the only spelling of no fill", style.fill)
        assertEquals(1.5f, style.strokeWidth, 0f)
        assertEquals("EVENODD", style.fillRule)
    }

    /**
     * A self-inconsistent envelope is refused, and refused with a sentence.
     *
     * The coordinate-count case is the one that earns this method's existence: it does not crash. It
     * reads the next shape's numbers as this shape's curve and draws something plausible and wrong,
     * which is worse than a crash because nothing on screen says the geometry was the problem.
     */
    @Test
    fun `malformed geometry is refused rather than drawn`() {
        val cases = mapOf(
            "verbStarts too short" to doneEnvelope(verbStarts = u32(0)),
            "coordStarts disagrees with coords" to doneEnvelope(coordStarts = u32(0, 4)),
            "a verb that is not a segment" to doneEnvelope(verbs = u8(0, 9)),
            "a style index nothing declares" to doneEnvelope(styleIndex = u32(7)),
            "coordinates the verbs do not account for" to doneEnvelope(
                coords = f32(0f, 0f, 10f, 0f),
                coordStarts = u32(0, 4),
            ),
        )
        for ((why, envelope) in cases) {
            val failure = runCatching { dwTraceReadEnvelope(envelope) }.exceptionOrNull()
            assertTrue("$why was accepted", failure is DwTraceHostFailure)
            assertEquals(
                why,
                DwTraceFailureKind.PROTOCOL_UNREADABLE,
                (failure as DwTraceHostFailure).kind,
            )
        }
    }

    @Test
    fun `a buffer that is not a whole number of floats is refused`() {
        val failure = runCatching {
            dwTraceReadEnvelope(doneEnvelope(coords = Base64.getEncoder().encodeToString(ByteArray(7))))
        }.exceptionOrNull()
        assertTrue(failure is DwTraceHostFailure)
        assertEquals(DwTraceFailureKind.PROTOCOL_UNREADABLE, (failure as DwTraceHostFailure).kind)
    }

    /* ── parameters ─────────────────────────────────────────────────────────────────────────── */

    /**
     * `JsonNull` IS a `JsonPrimitive`, so a `when` that tests the primitive first reads
     * `output.background: null` as the string "null".
     *
     * That is not a crash and not a wrong colour — it is the "White background" toggle reading as ON
     * forever, because the panel's test is `present(key)`. `engine/params.ts:359` records that null is
     * the ONLY spelling of a transparent export, so this one branch decides whether a designer can
     * ever produce one.
     */
    @Test
    fun `a null leaf is Absent and not the word null`() {
        val values = dwTraceValuesOf(PARAMS)
        assertEquals(DwTraceValue.Absent, values["output.background"])
        assertFalse("a null background must read as absent", values.present("output.background"))
        assertTrue("a set background must read as present", dwTraceValuesOf(PARAMS_WHITE).present("output.background"))
    }

    @Test
    fun `the tree flattens to the same dot paths the web's table uses`() {
        val values = dwTraceValuesOf(PARAMS)
        assertEquals(3.0, values.number("edge.flow.sigmaM"))
        assertEquals(1.6, values.number("output.strokeWidth"))
        assertEquals(true, values.flag("preprocess.claheEnabled"))
        assertEquals("SUGGEST", values.choice("auto.mode"))
        assertEquals("clean-line", values.styleId)
        assertEquals("clean-line", values.choice(DW_TRACE_STYLE_ID_KEY))
        // `auto.handTuned` is a string[] and is not a leaf a control reads. It is skipped here and
        // carried whole in `wire`, which is the next case.
        assertNull(values["auto.handTuned"])
    }

    /**
     * The tree that goes back is the tree that came, byte for byte.
     *
     * This is the property that lets the handset stay ignorant of 74 parameters. A leaf this Kotlin
     * has never heard of — one a newer vendored engine added — survives a full round trip through a
     * panel that cannot name it, because nothing here ever re-serialises `wire`.
     */
    @Test
    fun `the wire is carried verbatim, unknown leaves and all`() {
        val values = dwTraceValuesOf(PARAMS)
        assertEquals(PARAMS, values.wire)
        assertNotNull("an unrecognised leaf is still readable", values["somethingNewer.knob"])
        assertEquals(7.0, values.number("somethingNewer.knob"))
    }

    /**
     * A patch is JSON, and a non-finite number has no JSON spelling.
     *
     * Dropping the key would be the worst of the three options available: the slider moves, the trace
     * runs, and the one parameter the designer changed is the one that did not change.
     * `NonFiniteValueTest` exists in this module because that class of bug has shipped here before.
     */
    @Test
    fun `a patch refuses a value that is not a number`() {
        assertEquals(
            """{"edge.sensitivity":0.5,"cleanup.bridgeGaps":true,"edge.engine":"ADAPTIVE","output.background":null}""",
            dwTracePatchJson(
                linkedMapOf(
                    "edge.sensitivity" to DwTraceValue.Num(0.5),
                    "cleanup.bridgeGaps" to DwTraceValue.Flag(true),
                    "edge.engine" to DwTraceValue.Choice("ADAPTIVE"),
                    "output.background" to DwTraceValue.Absent,
                ),
            ),
        )

        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val failure = runCatching {
                dwTracePatchJson(mapOf("output.simplify" to DwTraceValue.Num(bad)))
            }.exceptionOrNull()
            assertTrue("$bad was sent to the engine", failure is DwTraceHostFailure)
            assertTrue(
                "the refusal must name the key: ${failure?.message}",
                failure!!.message!!.contains("output.simplify"),
            )
        }
    }

    /**
     * A parameter tree is spliced into JavaScript SOURCE, so the escaping is a correctness question
     * and not a tidiness one.
     *
     * `U+2028` is legal inside a JSON string and was a syntax error inside a JavaScript string
     * literal until ES2019. A style id or a note carrying one would turn a script into a parse error
     * at run time, on a phone, with no build signal of any kind.
     */
    @Test
    fun `a string literal survives being spliced into a script`() {
        assertEquals("\"a\\\"b\"", dwTraceJsString("a\"b"))
        assertEquals("\"a\\\\b\"", dwTraceJsString("a\\b"))
        assertEquals("\"a\\nb\"", dwTraceJsString("a\nb"))
        assertEquals("\"a\\u2028b\"", dwTraceJsString("a\u2028b"))
        assertEquals("\"a\\u0000b\"", dwTraceJsString("a\u0000b"))
    }

    /* ── envelopes ──────────────────────────────────────────────────────────────────────────── */

    @Test
    fun `each envelope state decodes to its own answer`() {
        val hello = dwTraceReadEnvelope(
            """{"v":1,"state":"hello","contract":1,"pumped":true,"engine":"9268cb78","svgWriter":"buildSvg","notes":["a"]}""",
        )
        assertTrue(hello is DwTraceEnvelope.Hello)
        assertEquals(1, (hello as DwTraceEnvelope.Hello).hello.contract)
        assertTrue(hello.hello.pumped)
        assertEquals("buildSvg", hello.hello.svgWriter)
        assertEquals(listOf("a"), hello.hello.notes)

        val error = dwTraceReadEnvelope("""{"v":1,"state":"error","message":"no pixels"}""")
        assertEquals("no pixels", (error as DwTraceEnvelope.Failed).message)

        assertTrue(dwTraceReadEnvelope("""{"v":1,"state":"cancelled"}""") is DwTraceEnvelope.Cancelled)

        val running = dwTraceReadEnvelope(
            """{"v":1,"state":"running","events":[{"stageId":"edge","label":"Detecting edges","fraction":0.5}]}""",
        )
        val events = (running as DwTraceEnvelope.Running).events
        assertEquals(1, events.size)
        assertEquals("edge", events[0].stageId)
        assertEquals("Detecting edges", events[0].label)
        assertEquals(0.5f, events[0].fraction, 0f)

        val presets = dwTraceReadEnvelope(
            """{"v":1,"state":"presets","styles":[{"id":"clean-line","name":"Clean line","description":"d","group":"Line art"}],"subjects":[{"id":"sketch","name":"Sketch","description":"s","group":""}]}""",
        )
        val tables = (presets as DwTraceEnvelope.Presets).tables
        assertEquals("clean-line", tables.styles.single().id)
        assertEquals("Line art", tables.styles.single().group)
        assertEquals("sketch", tables.subjects.single().id)
    }

    /**
     * Anything that is not an envelope is a refusal naming what arrived, truncated.
     *
     * The truncation is the point. A `done` result can be megabytes of base64, and the whole of it in
     * a crash report is a crash report nobody reads; the first eighty characters are what tells an
     * HTML error page apart from a stray exception message.
     */
    @Test
    fun `something that is not an envelope is refused with a readable clue`() {
        for (text in listOf("", "not json", "[]", """{"v":1}""", """{"v":1,"state":"peculiar"}""")) {
            val failure = runCatching { dwTraceReadEnvelope(text) }.exceptionOrNull()
            assertTrue("\"$text\" was accepted", failure is DwTraceHostFailure)
            assertEquals(DwTraceFailureKind.PROTOCOL_UNREADABLE, (failure as DwTraceHostFailure).kind)
        }
        val huge = runCatching { dwTraceReadEnvelope("z".repeat(4000)) }.exceptionOrNull()
        assertTrue("a huge non-answer must not be quoted whole", huge!!.message!!.length < 400)
    }

    /* ── ceilings ───────────────────────────────────────────────────────────────────────────── */

    /**
     * The ceilings are conservative on a small phone and FDOG is always the tighter of the two.
     *
     * The relationship matters more than either number, because both are unmeasured guesses: FDOG was
     * measured at 5.7x the cost of every alternative, so a build where its ceiling ever equalled the
     * general one would be offering a designer a 67–117 second wait without saying so.
     */
    @Test
    fun `the ceilings bar FDOG below the general limit and shrink on a small phone`() {
        val (big, bigFdog) = dwTraceCeilings(5_927_968_768L) // the fleet's Galaxy M32, DwDeviceTier.kt:1115
        assertEquals(DW_TRACE_DEFAULT_MAX_WORKING_EDGE, big)
        assertEquals(DW_TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE, bigFdog)
        assertTrue("FDOG must never be allowed as high as the general ceiling", bigFdog < big)

        val (small, smallFdog) = dwTraceCeilings(1_900_000_000L)
        assertTrue("a 2 GB phone gets less", small < big)
        assertTrue(smallFdog < small)

        // A failed memory read takes the cautious half, for the reason `DwDeviceMeasurement`'s own
        // `lowRamDevice` gives about its null: a handset that would have said it was small must not
        // be promoted by a lookup that failed.
        assertEquals(small to smallFdog, dwTraceCeilings(null))
    }

    @Test
    fun `the isolate heap cap has a floor, a ceiling, and room for a measured trace`() {
        val m32 = dwTraceHeapCapBytes(5_927_968_768L)
        // The spike measured CANNY at 1600x1200 wanting +278 MB of JS heap. A cap below that turns an
        // ordinary trace into a refusal; that is the number this arithmetic has to clear.
        assertTrue("a measured CANNY trace must fit under the cap on the fleet's phone", m32 > 278L * 1024 * 1024)
        assertTrue(m32 <= 512L * 1024 * 1024)

        // And an ADAPTIVE trace's measured +93 MB must fit even on the smallest phone, and even when
        // the memory read failed entirely.
        assertTrue(dwTraceHeapCapBytes(1_000_000_000L) > 93L * 1024 * 1024)
        assertTrue(dwTraceHeapCapBytes(null) > 93L * 1024 * 1024)
    }

    /* ── fixtures ───────────────────────────────────────────────────────────────────────────── */

    /**
     * A parameter tree shaped like the engine's: nested, with a null, an array, and a leaf this
     * Kotlin has never heard of.
     *
     * Not the real `defaultTraceParams()` — that is 74 leaves and a copy of it here would be the
     * hand-transcribed second register `DwSketchTraceEngine.kt` refuses to keep. What is needed is one
     * of each SHAPE.
     */
    private val PARAMS =
        """{"styleId":"clean-line","preprocess":{"claheEnabled":true,"workingLongEdge":2048},""" +
            """"edge":{"engine":"FDOG","flow":{"sigmaM":3}},"cleanup":{"minBlobArea":24},""" +
            """"output":{"strokeWidth":1.6,"background":null},""" +
            """"auto":{"mode":"SUGGEST","handTuned":[],"minConfidence":0.55},""" +
            """"somethingNewer":{"knob":7}}"""

    private val PARAMS_WHITE = PARAMS.replace("\"background\":null", "\"background\":4294967295")

    private fun f32(vararg values: Float): String {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putFloat(value)
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    private fun u32(vararg values: Int): String {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putInt(value)
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    private fun u8(vararg values: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(values.size) { values[it].toByte() })

    /** Escaping written out by hand, so the fixtures do not depend on the code under test. */
    private fun embed(json: String): String =
        "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * One closed shape — start, two lines — with the pieces individually replaceable so a case can
     * corrupt exactly one of them.
     */
    private fun doneEnvelope(
        coords: String = f32(0f, 0f, 10f, 0f, 10f, 10f),
        verbs: String = u8(0, 0),
        verbStarts: String = u32(0, 2),
        coordStarts: String = u32(0, 6),
        closed: String = u8(1),
        styleIndex: String = u32(0),
    ): String =
        """{"v":1,"state":"done","runId":1,"events":[],"result":{""" +
            """"svg":"<svg/>","width":1600,"height":1200,"workingWidth":720,"workingHeight":540,""" +
            """"shapeCount":1,"nodeCount":3,"totalMillis":2903,"background":null,""" +
            """"stages":[{"id":"edge","label":"Detecting edges","millis":1301}],""" +
            """"notes":["Traced at 720 px."],"autoSubjectId":"sketch","suggestedStyleId":"pencil-sketch",""" +
            """"appliedParams":${embed(PARAMS)},""" +
            """"geometry":{"coords":"$coords","verbs":"$verbs","verbStarts":"$verbStarts",""" +
            """"coordStarts":"$coordStarts","closed":"$closed","styleIndex":"$styleIndex",""" +
            """"styleTable":[{"stroke":4278190080,"strokeWidth":1.5,"fill":null,"fillRule":"EVENODD",""" +
            """"cap":"ROUND","join":"ROUND","miterLimit":4,"opacity":1}]}}}"""
}
