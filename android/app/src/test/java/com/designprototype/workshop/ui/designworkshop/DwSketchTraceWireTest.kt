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

class DwSketchTraceWireTest {

    @Test
    fun `a row of packed ARGB becomes the bytes the engine reads`() {
        val pixels = intArrayOf(
            0xFF102030.toInt(),
            0x80AABBCC.toInt(),
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
            val repacked = (a shl 24) or (r shl 16) or (g shl 8) or b
            assertEquals("pixel $i does not survive the engine's own repacking", pixels[i], repacked)
        }
    }

    @Test
    fun `rows are written at their own offset`() {
        val out = ByteArray(2 * 2 * 4)
        dwTraceArgbRowToRgba(intArrayOf(0xFF010203.toInt(), 0xFF040506.toInt()), 2, out, 0)
        dwTraceArgbRowToRgba(intArrayOf(0xFF070809.toInt(), 0xFF0A0B0C.toInt()), 2, out, 8)
        assertEquals(0x01, out[0].toInt() and 0xFF)
        assertEquals(0x07, out[8].toInt() and 0xFF)
        assertEquals(0x0A, out[12].toInt() and 0xFF)
    }

    @Test
    fun `the working size is the web's, rounding and all`() {
        assertEquals("a source inside the cap is untouched", 1600 to 1200, dwTraceWorkingSize(1600, 1200, 4096))
        assertEquals("and is never upscaled", 320 to 240, dwTraceWorkingSize(320, 240, 4096))
        assertEquals(4096 to 2731, dwTraceWorkingSize(9000, 6000, 4096))
        assertEquals("portrait caps on the long edge too", 2731 to 4096, dwTraceWorkingSize(6000, 9000, 4096))
        assertEquals("a plate keeps its aspect", 1024 to 768, dwTraceWorkingSize(1600, 1200, 1024))
        val (w, h) = dwTraceWorkingSize(2048, 3, 1024)
        assertEquals(1024, w)
        assertTrue("a rounded-away edge must not become zero", h >= 1)
    }

    @Test
    fun `geometry decodes little-endian and mirrors the engine's layout`() {
        val decoded = dwTraceReadEnvelope(doneEnvelope())
        assertTrue(decoded is DwTraceEnvelope.Done)
        val geometry = (decoded as DwTraceEnvelope.Done).decoded.geometry

        assertEquals(1, geometry.shapeCount)
        assertTrue(geometry.isClosed(0))
        assertEquals(0f, geometry.coords[0], 0f)
        assertEquals(10f, geometry.coords[2], 0f)
        assertEquals(10f, geometry.coords[5], 0f)
        assertEquals(DW_TRACE_VERB_LINE, geometry.verbs[0])
        assertEquals(2, geometry.verbStarts[1])
        assertEquals(6, geometry.coordStarts[1])
    }

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
        assertNull(values["auto.handTuned"])
    }

    @Test
    fun `the wire is carried verbatim, unknown leaves and all`() {
        val values = dwTraceValuesOf(PARAMS)
        assertEquals(PARAMS, values.wire)
        assertNotNull("an unrecognised leaf is still readable", values["somethingNewer.knob"])
        assertEquals(7.0, values.number("somethingNewer.knob"))
    }

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
                (failure as DwTraceHostFailure).detail.contains("output.simplify"),
            )
        }
    }

    @Test
    fun `a string literal survives being spliced into a script`() {
        assertEquals("\"a\\\"b\"", dwTraceJsString("a\"b"))
        assertEquals("\"a\\\\b\"", dwTraceJsString("a\\b"))
        assertEquals("\"a\\nb\"", dwTraceJsString("a\nb"))
        assertEquals("\"a\\u2028b\"", dwTraceJsString("a\u2028b"))
        assertEquals("\"a\\u0000b\"", dwTraceJsString("a\u0000b"))
    }

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

    @Test
    fun `the ceilings bar FDOG below the general limit and shrink on a small phone`() {
        val (big, bigFdog) = dwTraceCeilings(5_927_968_768L)
        assertEquals(DW_TRACE_DEFAULT_MAX_WORKING_EDGE, big)
        assertEquals(DW_TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE, bigFdog)
        assertTrue("FDOG must never be allowed as high as the general ceiling", bigFdog < big)

        val (small, smallFdog) = dwTraceCeilings(1_900_000_000L)
        assertTrue("a 2 GB phone gets less", small < big)
        assertTrue(smallFdog < small)

        assertEquals(small to smallFdog, dwTraceCeilings(null))
    }

    @Test
    fun `the isolate heap cap has a floor, a ceiling, and room for a measured trace`() {
        val m32 = dwTraceHeapCapBytes(5_927_968_768L)
        assertTrue("a measured CANNY trace must fit under the cap on the fleet's phone", m32 > 278L * 1024 * 1024)
        assertTrue(m32 <= 512L * 1024 * 1024)

        assertTrue(dwTraceHeapCapBytes(1_000_000_000L) > 93L * 1024 * 1024)
        assertTrue(dwTraceHeapCapBytes(null) > 93L * 1024 * 1024)
    }

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

    private fun embed(json: String): String =
        "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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
