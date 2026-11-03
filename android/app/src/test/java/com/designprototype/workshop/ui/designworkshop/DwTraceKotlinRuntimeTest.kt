package com.designprototype.workshop.ui.designworkshop

import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.pipeline.EdgeEngine
import com.offlinetracer.pipeline.Pipeline
import com.offlinetracer.pipeline.Stages
import com.offlinetracer.pipeline.TraceParams
import com.offlinetracer.vector.FillRule
import com.offlinetracer.vector.LineCap
import com.offlinetracer.vector.LineJoin
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecPoint
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwTraceKotlinRuntimeTest {

    @Test
    fun `a real trace of a synthetic sheet comes back with geometry, an SVG and its stages`() {
        val src = syntheticSheet(160, 120)
        val events = ArrayList<DwTraceProgress>()
        val decoded = runBlocking {
            dwTraceKotlinTrace(src, TraceParams(), preview = false) { events.add(it) }
        }

        assertEquals("the document keeps the source frame", 160, decoded.width)
        assertEquals(120, decoded.height)
        assertEquals("nothing was downscaled at this size", 160, decoded.workingWidth)
        assertEquals(120, decoded.workingHeight)

        assertTrue("a bordered sheet must produce paths, not an empty document", decoded.shapeCount > 0)
        assertEquals(decoded.shapeCount, decoded.geometry.shapeCount)
        assertTrue(decoded.geometry.coords.isNotEmpty())
        assertTrue(decoded.nodeCount > decoded.shapeCount)
        decoded.geometry.validate()

        assertTrue("the SVG is the engine's own", decoded.svg.startsWith("<?xml version=\"1.0\""))
        assertTrue(decoded.svg.contains("standalone=\"no\""))
        assertTrue(decoded.svg.contains("version=\"1.1\""))
        assertTrue("px units, where the portal's writer emits none", decoded.svg.contains("width=\"160px\""))
        assertTrue("and the vectoriser's own path ids", decoded.svg.contains(" id=\"p"))
        assertEquals(
            "one <path> per shape",
            decoded.shapeCount,
            Regex("<path ").findAll(decoded.svg).count(),
        )

        assertEquals("every stage is timed", Stages.ALL.size, decoded.stages.size)
        assertEquals(Pipeline.stageIds(), decoded.stages.map { it.id })
        assertEquals(decoded.stages.sumOf { it.millis }, decoded.totalMillis)

        assertEquals(DW_TRACE_LEAF_KEYS.toSet(), decoded.appliedParams.keys)
        assertEquals(
            TraceParams().styleId,
            decoded.appliedParams.choice(DW_TRACE_STYLE_ID_KEY),
        )
    }

    @Test
    fun `progress arrives once per stage, in order, and never reaches one`() {
        val src = syntheticSheet(96, 72)
        val events = ArrayList<DwTraceProgress>()
        runBlocking { dwTraceKotlinTrace(src, TraceParams(), preview = false) { events.add(it) } }

        assertEquals(Pipeline.stageIds(), events.map { it.stageId })
        assertEquals(Stages.ALL.map { it.label }, events.map { it.label })
        assertEquals("the first event is zero", 0f, events.first().fraction, 0f)
        assertTrue(
            "the last event is ${events.last().fraction}, which must be short of 1",
            events.last().fraction < 1f,
        )
        for (i in 1 until events.size) {
            assertTrue(
                "fractions must not go backwards at ${events[i].stageId}",
                events[i].fraction > events[i - 1].fraction,
            )
        }
    }

    @Test
    fun `this engine suggests no style, because its classifier answers in sentences`() {
        val src = syntheticSheet(96, 72)
        val decoded = runBlocking { dwTraceKotlinTrace(src, TraceParams(), preview = false) {} }
        assertEquals("", decoded.suggestedStyleId)

        val profile = com.offlinetracer.imaging.Classify.profile(src)
        val suggestion = profile.suggestion
        assertTrue("the engine still says something", suggestion.isNotEmpty())
        assertTrue(
            "and it is still a sentence rather than an id: $suggestion",
            suggestion.contains(' ') && suggestion.endsWith("."),
        )
        assertFalse(
            "which is why it cannot go in a field the panel renders as a style",
            dwTraceKotlinPresetTables().styles.any { it.id == suggestion },
        )
    }

    @Test
    fun `a preview reports no progress and runs below the source resolution`() {
        val src = syntheticSheet(1200, 900)
        val events = ArrayList<DwTraceProgress>()
        val decoded = runBlocking {
            dwTraceKotlinTrace(src, previewFriendlyParams(), preview = true) { events.add(it) }
        }

        assertTrue("a preview must post nothing: got ${events.size}", events.isEmpty())
        assertEquals("the frame is still the source's", 1200, decoded.width)
        assertEquals(900, decoded.height)
        assertEquals("but the stages ran at the preview edge", 720, decoded.workingWidth)
        assertEquals(540, decoded.workingHeight)
        assertTrue("a preview does not classify", decoded.suggestedStyleId.isEmpty())
    }

    @Test
    fun `an empty sheet comes back with the engine's own sentence about why it is empty`() {
        val src = RgbaImage(64, 64).fill(RgbaImage.argb(255, 255, 255, 255))
        val decoded = runBlocking { dwTraceKotlinTrace(src, TraceParams(), preview = false) {} }

        assertEquals(0, decoded.shapeCount)
        assertTrue(
            "an empty drawing must say why it is empty, and it said: ${decoded.notes}",
            decoded.notes.any { it.contains("empty") },
        )
    }

    @Test
    fun `cancelling the coroutine stops the engine part-way and never reports a failure`() {
        val src = syntheticSheet(420, 315)
        val events = ArrayList<DwTraceProgress>()
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        var finished = false

        runBlocking {
            val job = launch {
                try {
                    dwTraceKotlinTrace(src, TraceParams(), preview = false) {
                        events.add(it)
                        if (!started.isCompleted) started.complete(Unit)
                    }
                    finished = true
                } catch (stop: CancellationException) {
                    cancelled = true
                    throw stop
                }
            }
            started.await()
            job.cancelAndJoin()
        }

        assertTrue("the trace must unwind as a cancellation", cancelled)
        assertFalse("and must not have produced a result", finished)
        assertTrue("it must have started", events.isNotEmpty())
        assertTrue(
            "it must have stopped part-way: ${events.size} of ${Stages.ALL.size} stages reported",
            events.size < Stages.ALL.size,
        )
    }

    @Test
    fun `a job cancelled before its body runs costs no stages at all`() {
        val src = syntheticSheet(420, 315)
        val events = ArrayList<DwTraceProgress>()
        var produced = false

        val job = runBlocking {
            val job = launch {
                dwTraceKotlinTrace(src, TraceParams(), preview = false) { events.add(it) }
                produced = true
            }
            job.cancelAndJoin()
            job
        }

        assertTrue("the job must end cancelled", job.isCancelled)
        assertFalse("and must not have produced a result", produced)
        assertTrue("no stage should have run: ${events.size}", events.isEmpty())
    }

    @Test
    fun `packed ARGB survives the crossing into the engine and back`() {
        val width = 7
        val height = 5
        val pixels = IntArray(width * height) { i ->
            RgbaImage.argb(255 - i, (i * 7) and 0xFF, (i * 29) and 0xFF, (i * 53) and 0xFF)
        }
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) {
            val row = IntArray(width) { x -> pixels[y * width + x] }
            dwTraceArgbRowToRgba(row, width, rgba, y * width * 4)
        }

        val image = dwTraceKotlinImageOf(rgba, width, height)
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        for (i in pixels.indices) {
            assertEquals("pixel $i", pixels[i], image.pixels[i])
        }
    }

    @Test
    fun `a short buffer is refused rather than read past`() {
        val failure = runCatching { dwTraceKotlinImageOf(ByteArray(8), 4, 4) }.exceptionOrNull()
        assertTrue(failure is DwTraceHostFailure)
        assertEquals(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            (failure as DwTraceHostFailure).kind,
        )
    }

    @Test
    fun `a document serialises into the layout the plates and the export walk`() {
        val ink = VecStyle(stroke = 0xFF102030.toInt(), strokeWidth = 2f, fill = null)
        val wash = VecStyle(
            stroke = null,
            strokeWidth = 1f,
            fill = 0x80FFFFFF.toInt(),
            fillRule = FillRule.NONZERO,
            cap = LineCap.BUTT,
            join = LineJoin.MITER,
            miterLimit = 3f,
            opacity = 0.5f,
        )
        val line = VecShape(
            VecPath(VecPoint(1f, 2f), listOf(VecSeg.Line(VecPoint(3f, 4f))), closed = false),
            ink,
        )
        val quad = VecShape(
            VecPath(
                VecPoint(5f, 6f),
                listOf(VecSeg.Quad(VecPoint(7f, 8f), VecPoint(9f, 10f))),
                closed = true,
            ),
            wash,
        )
        val cubic = VecShape(
            VecPath(
                VecPoint(11f, 12f),
                listOf(VecSeg.Cubic(VecPoint(13f, 14f), VecPoint(15f, 16f), VecPoint(17f, 18f))),
                closed = false,
            ),
            VecStyle(stroke = 0xFF102030.toInt(), strokeWidth = 2f, fill = null),
        )
        val doc = VecDocument(
            width = 40f,
            height = 30f,
            layers = listOf(
                VecLayer("a", "A", listOf(line, quad)),
                VecLayer("b", "B", listOf(cubic)),
            ),
            background = null,
        )

        val g = dwTraceKotlinGeometryOf(doc)
        g.validate()

        assertEquals("shapes are concatenated across layers", 3, g.shapeCount)
        assertEquals(listOf(0, 1, 2, 3), g.verbStarts.toList())
        assertEquals(listOf(0, 4, 10, 18), g.coordStarts.toList())
        assertEquals(
            listOf(DW_TRACE_VERB_LINE, DW_TRACE_VERB_QUAD, DW_TRACE_VERB_CUBIC),
            g.verbs.toList(),
        )
        assertEquals(listOf<Byte>(0, 1, 0), g.closed.toList())
        assertEquals(
            listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f, 14f, 15f, 16f, 17f, 18f),
            g.coords.toList(),
        )

        assertEquals("two distinct styles for three shapes", 2, g.styleTable.size)
        assertEquals(listOf(0, 1, 0), g.styleIndex.toList())
        assertEquals(0xFF102030.toInt(), g.styleOf(0).stroke)
        assertNull("a stroke-only style has no fill", g.styleOf(0).fill)
        assertNull("a fill-only style has no stroke", g.styleOf(1).stroke)
        assertEquals("EVENODD", g.styleOf(0).fillRule)
        assertEquals("ROUND", g.styleOf(0).cap)
        assertEquals("MITER", g.styleOf(1).join)
        assertEquals(0.5f, g.styleOf(1).opacity, 0f)
    }

    @Test
    fun `an empty document serialises into empty arrays rather than nothing`() {
        val doc = VecDocument(10f, 10f, listOf(VecLayer("trace", "Trace", emptyList())), null)
        val g = dwTraceKotlinGeometryOf(doc)
        g.validate()
        assertEquals(0, g.shapeCount)
        assertEquals(listOf(0), g.verbStarts.toList())
        assertEquals(listOf(0), g.coordStarts.toList())
    }

    @Test
    fun `the SVG carries no branding, no layer group and one path per shape`() {
        val svg = dwTraceKotlinSvgOf(twoShapeDocument())

        assertEquals(2, svg.shapesWritten)
        assertNull(svg.truncationNote)
        assertFalse("no other product's name", svg.svg.contains("Offline Tracer"))
        assertFalse("no title", svg.svg.contains("<title"))
        assertFalse("no desc", svg.svg.contains("<desc"))
        assertFalse("no layer grouping, as the portal's writer emits none", svg.svg.contains("<g "))
        assertEquals(2, Regex("<path ").findAll(svg.svg).count())
        assertTrue(svg.svg.contains("width=\"40px\""))
        assertTrue(svg.svg.contains("viewBox=\"0 0 40 30\""))
    }

    @Test
    fun `an opaque background is painted, and a transparent one is absent`() {
        val opaque = twoShapeDocument().copy(background = 0xFFFFFFFF.toInt())
        assertTrue(dwTraceKotlinSvgOf(opaque).svg.contains("<rect"))
        assertFalse(dwTraceKotlinSvgOf(twoShapeDocument()).svg.contains("<rect"))
    }

    @Test
    fun `the shape ceiling and its sentence are the portal's`() {
        val source = portalSvgWriter()
        val declared = Regex("MAX_SHAPES_PER_FILE = (\\d+)").find(source)
        assertNotNull("geometryToSvg.ts must still declare MAX_SHAPES_PER_FILE", declared)
        assertEquals(
            "the two clients must cut a drawing at the same place",
            declared!!.groupValues[1].toInt(),
            DW_TRACE_KOTLIN_MAX_SHAPES,
        )

        val note = dwTraceKotlinTruncationNote(shapeCount = 250000, shapesWritten = 200000)
        assertNotNull(note)
        for (fragment in listOf(
            "separate paths and the file holds the ",
            "and trace again ",
            "to get a drawing that fits.",
        )) {
            assertTrue(
                "the portal's sentence no longer contains \"$fragment\" — the two have drifted",
                source.contains(fragment),
            )
            assertTrue("this app's sentence must too", note!!.contains(fragment))
        }
        assertTrue("the counts are grouped: $note", note!!.contains("2,50,000"))
        assertTrue(note.contains("2,00,000"))
        assertTrue(note.contains("Minimum speck"))
        assertTrue(note.contains("Simplify"))
    }

    @Test
    fun `nothing is cut when the drawing fits`() {
        assertNull(dwTraceKotlinTruncationNote(shapeCount = 12, shapesWritten = 12))
        assertNull(dwTraceKotlinTruncationNote(shapeCount = 0, shapesWritten = 0))
    }

    @Test
    fun `counts are grouped the way the portal groups them`() {
        val cases = mapOf(
            999 to "999", 1000 to "1,000", 10000 to "10,000",
            100000 to "1,00,000", 250000 to "2,50,000", 1234567 to "12,34,567",
        )
        for ((value, expected) in cases) {
            val note = dwTraceKotlinTruncationNote(shapeCount = value, shapesWritten = 0)
            assertTrue("$value should read $expected, and the note was $note", note!!.contains(expected))
        }
    }

    @Test
    fun `the engine's stage list is nineteen and overlaps the surface table in seven ids`() {
        assertEquals(Stages.ALL.size, DW_TRACE_KOTLIN_STAGES.size)
        assertEquals(19, DW_TRACE_KOTLIN_STAGES.size)
        assertEquals(Pipeline.stageIds(), DW_TRACE_KOTLIN_STAGES.map { it.id })
        assertEquals(Stages.ALL.map { it.label }, DW_TRACE_KOTLIN_STAGES.map { it.label })
        assertEquals("the surface table is still the TypeScript's twelve", 12, DW_TRACE_STAGES.size)

        val shared = DW_TRACE_KOTLIN_STAGES.map { it.id }.filter { dwTraceStageIndex(it) >= 0 }
        assertEquals("seven ids appear in both engines' lists: $shared", 7, shared.size)
        val misplaced = shared.filter { id ->
            dwTraceStageIndex(id) != DW_TRACE_KOTLIN_STAGES.indexOfFirst { it.id == id }
        }
        assertEquals(
            "five of them sit at a different position, which is what the fix below exists for: $misplaced",
            5,
            misplaced.size,
        )

        val skeleton = DwTraceProgress("skeleton", "Thinning", 0.5f)
        assertEquals(
            "Thinning. Stage ${DW_TRACE_KOTLIN_STAGES.indexOfFirst { it.id == "skeleton" } + 1} of 19.",
            dwTraceProgressSentence(skeleton, DW_TRACE_KOTLIN_STAGES),
        )
        assertNotEquals(
            "the twelve-row table would speak a different number for this same event",
            dwTraceProgressSentence(skeleton, DW_TRACE_KOTLIN_STAGES),
            dwTraceProgressSentence(skeleton),
        )

        assertEquals(
            "Preparing image",
            dwTraceProgressSentence(DwTraceProgress("prepare", "Preparing image", 0f), DW_TRACE_KOTLIN_STAGES),
        )
    }

    @Test
    fun `a trace at the product's input cap costs what the header says it costs`() {
        val pixels = 1600L * 1200L
        val fdog = dwTraceKotlinPeakBytes(pixels, pixels, EdgeEngine.FDOG)
        val canny = dwTraceKotlinPeakBytes(pixels, pixels, EdgeEngine.CANNY)
        assertEquals("FDOG, to the nearest MB", 112L, Math.round(fdog / (1024.0 * 1024.0)))
        assertEquals("CANNY, to the nearest MB", 317L, Math.round(canny / (1024.0 * 1024.0)))
        assertTrue("CANNY is about three times FDOG", canny > fdog * 2.5)

        assertEquals(
            dwTraceKotlinPeakBytes(pixels, pixels, EdgeEngine.CANNY),
            dwTraceKotlinPeakBytes(pixels, pixels, EdgeEngine.XDOG),
        )
        assertTrue(
            "a downscaled trace must cost less",
            dwTraceKotlinPeakBytes(pixels, 720L * 540L, EdgeEngine.CANNY) < canny,
        )
    }

    @Test
    fun `the working pixel count follows the engine's never-upscale rule`() {
        val small = TraceParams().copy(
            preprocess = TraceParams().preprocess.copy(workingLongEdge = 800),
        )
        assertEquals(
            "scaled down to the long edge",
            800L * 600L,
            dwTraceKotlinWorkingPixels(1600, 1200, small),
        )
        assertEquals(
            "never up, so a small source is itself",
            400L * 300L,
            dwTraceKotlinWorkingPixels(400, 300, TraceParams()),
        )
    }

    @Test
    fun `a trace that will not fit is refused in a sentence with both numbers in it`() {
        val pixels = 1600L * 1200L
        val plenty = 1024L * 1024L * 1024L
        assertNull(
            "half a gigabyte of headroom fits a CANNY trace",
            dwTraceKotlinMemoryRefusal(pixels, pixels, EdgeEngine.CANNY, plenty),
        )

        val cramped = 128L * 1024L * 1024L
        val refusal = dwTraceKotlinMemoryRefusal(pixels, pixels, EdgeEngine.CANNY, cramped)
        assertNotNull("128 MB cannot hold a 317 MB trace", refusal)
        assertTrue("it names what is needed: $refusal", refusal!!.contains("317 MB"))
        assertTrue("and what there is", refusal.contains("96 MB"))
        assertTrue("and that nothing was started", refusal.contains("has not been started"))
        assertTrue("and the resolution remedy", refusal.contains("trace resolution"))
        assertTrue("and the engine remedy", refusal.contains("Canny"))

        val exact = dwTraceKotlinPeakBytes(pixels, pixels, EdgeEngine.FDOG)
        assertNotNull(
            "a heap with nothing left over must refuse",
            dwTraceKotlinMemoryRefusal(pixels, pixels, EdgeEngine.FDOG, exact),
        )
        assertNull(
            "and one with the reserve on top must not",
            dwTraceKotlinMemoryRefusal(
                pixels,
                pixels,
                EdgeEngine.FDOG,
                exact + DW_TRACE_KOTLIN_HEAP_RESERVE_BYTES,
            ),
        )
    }

    @Test
    fun `the heap reading is what this VM can still hand out`() {
        val heap = dwTraceKotlinHeapBytes()
        assertTrue("a running JVM has some headroom: $heap", heap > 0L)
        assertTrue("and never more than its ceiling", heap <= Runtime.getRuntime().maxMemory())
    }

    @Test
    fun `a preview lowers the working edge and touches nothing else`() {
        val base = TraceParams().sanitized()
        val preview = dwTraceKotlinPreviewParams(base)

        val before = dwTraceFlattenParams(base)
        val after = dwTraceFlattenParams(preview)
        val moved = before.keys.filter { before[it] != after[it] }

        assertEquals("exactly one leaf moves: $moved", listOf("preprocess.workingLongEdge"), moved)
        assertEquals(
            DwTraceValue.Num(DW_TRACE_KOTLIN_PREVIEW_LONG_EDGE.toDouble()),
            after["preprocess.workingLongEdge"],
        )
        assertEquals("and it is the worker's own number", 720, DW_TRACE_KOTLIN_PREVIEW_LONG_EDGE)
    }

    @Test
    fun `the preview edge is the vendored worker's PREVIEW_LONG_EDGE`() {
        val worker = File("../../frontend/lib/trace/worker/trace.worker.ts")
        assertTrue("expected the vendored worker at ${worker.absolutePath}", worker.exists())
        val declared = Regex("PREVIEW_LONG_EDGE = (\\d+)").find(worker.readText(Charsets.UTF_8))
        assertNotNull("trace.worker.ts must still declare PREVIEW_LONG_EDGE", declared)
        assertEquals(declared!!.groupValues[1].toInt(), DW_TRACE_KOTLIN_PREVIEW_LONG_EDGE)
    }

    private fun syntheticSheet(width: Int, height: Int): RgbaImage {
        val white = RgbaImage.argb(255, 255, 255, 255)
        val black = RgbaImage.argb(255, 0, 0, 0)
        val image = RgbaImage(width, height).fill(white)
        val inset = maxOf(4, minOf(width, height) / 10)
        val thickness = maxOf(3, minOf(width, height) / 24)
        for (y in inset until height - inset) {
            for (x in inset until width - inset) {
                val onBorder = x < inset + thickness || x >= width - inset - thickness ||
                    y < inset + thickness || y >= height - inset - thickness
                val onDiagonal = kotlin.math.abs((x - inset) * (height - 2 * inset) -
                    (y - inset) * (width - 2 * inset)) < thickness * maxOf(width, height) / 2
                if (onBorder || onDiagonal) image[x, y] = black
            }
        }
        return image
    }

    private fun previewFriendlyParams(): TraceParams {
        val base = TraceParams()
        return base.copy(edge = base.edge.copy(engine = EdgeEngine.ADAPTIVE)).sanitized()
    }

    private fun twoShapeDocument(): VecDocument {
        val style = VecStyle(stroke = 0xFF000000.toInt(), strokeWidth = 1.5f, fill = null)
        val shapes = listOf(
            VecShape(VecPath(VecPoint(1f, 1f), listOf(VecSeg.Line(VecPoint(9f, 1f)))), style),
            VecShape(VecPath(VecPoint(2f, 2f), listOf(VecSeg.Line(VecPoint(8f, 9f)))), style),
        )
        return VecDocument(40f, 30f, listOf(VecLayer("trace", "Trace", shapes)), null)
    }

    private fun portalSvgWriter(): String {
        val file = File("../../frontend/components/sketches/upload/geometryToSvg.ts")
        assertTrue(
            "expected the portal's SVG writer at ${file.absolutePath}. This test pins the ceiling " +
                "and the sentence the two clients must share; if the tree moved, fix the path — do " +
                "not delete the assertion, because a cap that differs between clients is a drawing " +
                "that fits on a laptop and is cut on a handset.",
            file.exists(),
        )
        return file.readText(Charsets.UTF_8)
    }
}
