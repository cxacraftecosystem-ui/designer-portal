package com.designprototype.workshop.ui.designworkshop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwSketchTraceSessionTest {

    @Test
    fun `a bundle speaking another protocol is refused with the version it speaks`() {
        val host = FakeHost { """{"v":1,"state":"hello","contract":99,"pumped":true}""" }
        val failure = runCatching { runBlocking { DwTraceSession(host).hello() } }.exceptionOrNull()
        assertTrue(failure is DwTraceHostFailure)
        assertEquals(DwTraceFailureKind.BUNDLE_CONTRACT_MISMATCH, (failure as DwTraceHostFailure).kind)
        assertTrue("the refusal must name both versions", failure.detail.contains("99"))
    }

    @Test
    fun `the handshake reports which engine and which SVG writer the bundle carries`() {
        val host = FakeHost {
            """{"v":1,"state":"hello","contract":$DW_TRACE_CONTRACT,"pumped":true,""" +
                """"engine":"9268cb78","svgWriter":"buildSvg","notes":["Traced on this phone."]}"""
        }
        val hello = runBlocking { DwTraceSession(host).hello() }
        assertEquals("9268cb78", hello.engineManifestSha256)
        assertEquals("buildSvg", hello.svgWriter)
        assertTrue(hello.pumped)
        assertEquals(listOf("Traced on this phone."), hello.notes)
    }

    @Test
    fun `a pumped trace reports every stage boundary in order`() {
        val stages = listOf("prepare", "matte", "edge", "vectorize", "document")
        val host = FakeHost { script ->
            when {
                script.contains(".hello(") -> HELLO
                script.contains(".start(") -> running(stages[0], 0f)
                else -> {
                    val pumped = calls.count { it.contains(".pump(") }
                    if (pumped < stages.size) running(stages[pumped], pumped / 12f) else DONE
                }
            }
        }
        val seenProgress = mutableListOf<DwTraceProgress>()

        val decoded = runBlocking {
            val session = DwTraceSession(host)
            session.hello()
            session.trace(
                rgba = ByteArray(2 * 2 * 4),
                width = 2,
                height = 2,
                params = dwTraceValuesOf("""{"styleId":"clean-line"}"""),
                preview = false,
            ) { seenProgress += it }
        }

        assertEquals(stages, seenProgress.map { it.stageId })
        assertEquals("Detecting edges", seenProgress[2].label)
        assertEquals(2f / 12f, seenProgress[2].fraction, 1e-6f)
        assertTrue("the fraction never reaches one", seenProgress.all { it.fraction < 1f })
        assertEquals(1, decoded.geometry.shapeCount)
        assertEquals("<svg/>", decoded.svg)
        assertEquals(listOf("Traced at 720 px."), decoded.notes)
        assertEquals("sketch", decoded.autoSubjectId)
        assertEquals("pencil-sketch", decoded.suggestedStyleId)

        assertEquals(1, host.provided.size)
        assertEquals(DW_TRACE_IMAGE_DATA_NAME, host.provided[0].first)
        assertEquals(16, host.provided[0].second.size)
    }

    @Test
    fun `a bundle that cannot park answers done from start, with no pumps and no progress`() {
        val host = FakeHost { script ->
            if (script.contains(".hello(")) HELLO_UNPUMPED else DONE
        }
        val progress = mutableListOf<DwTraceProgress>()
        val decoded = runBlocking {
            val session = DwTraceSession(host)
            assertFalse(session.hello().pumped)
            session.trace(ByteArray(16), 2, 2, PARAMS, false) { progress += it }
        }
        assertEquals("no progress can come out of an evaluation that has not returned", 0, progress.size)
        assertEquals(1, decoded.geometry.shapeCount)
        assertFalse("nothing should have been pumped", host.calls.any { it.contains(".pump(") })
    }

    @Test
    fun `an engine error carries the engine's own sentence`() {
        val host = FakeHost { script ->
            if (script.contains(".hello(")) HELLO
            else """{"v":1,"state":"error","message":"That image has no pixels, so there is nothing to trace."}"""
        }
        val failure = runCatching {
            runBlocking { DwTraceSession(host).trace(ByteArray(16), 2, 2, PARAMS, false) {} }
        }.exceptionOrNull()
        assertTrue(failure is DwTraceHostFailure)
        assertEquals(DwTraceFailureKind.ENGINE_ERROR, (failure as DwTraceHostFailure).kind)
        assertTrue(failure.message!!.contains("That image has no pixels"))
    }

    @Test
    fun `a frame with no pixels and a buffer that is too short are told apart`() {
        val host = FakeHost { HELLO }
        val empty = runCatching {
            runBlocking { DwTraceSession(host).trace(ByteArray(0), 0, 0, PARAMS, false) {} }
        }.exceptionOrNull()
        assertEquals(DwTraceFailureKind.IMAGE_EMPTY, (empty as DwTraceHostFailure).kind)

        val short = runCatching {
            runBlocking { DwTraceSession(host).trace(ByteArray(8), 2, 2, PARAMS, false) {} }
        }.exceptionOrNull()
        assertEquals(DwTraceFailureKind.PROTOCOL_UNREADABLE, (short as DwTraceHostFailure).kind)

        assertEquals("nothing may be handed over before it is checked", 0, host.provided.size)
    }

    @Test
    fun `a cancel between stages sets the engine's token and unwinds as a cancellation`() {
        lateinit var job: Job
        val host = FakeHost { script ->
            when {
                script.contains(".hello(") -> HELLO
                script.contains(".cancel(") -> """{"v":1,"state":"cancelled"}"""
                script.contains(".start(") -> running("prepare", 0f)
                else -> {
                    job.cancel()
                    running("matte", 1 / 12f)
                }
            }
        }

        val thrown = runCatching {
            runBlocking {
                job = Job()
                withContext(job) {
                    DwTraceSession(host).trace(ByteArray(16), 2, 2, PARAMS, false) {}
                }
            }
        }.exceptionOrNull()

        assertTrue("a cancel must never surface as a DwTraceHostFailure", thrown is CancellationException)
        assertTrue(
            "the engine's own token must have been set before the isolate was touched",
            host.calls.any { it.contains(".cancel(") },
        )
        assertFalse("killing the isolate is the second instrument, not the first", host.closed)
    }

    @Test
    fun `an engine that ignores its token has the isolate closed underneath it`() {
        lateinit var job: Job
        var pumps = 0
        val host = FakeHost { script ->
            when {
                script.contains(".hello(") -> HELLO
                script.contains(".start(") -> running("prepare", 0f)
                else -> {
                    if (!script.contains(".cancel(")) {
                        pumps += 1
                        if (pumps == 1) job.cancel()
                    }
                    running("edge", 0.5f)
                }
            }
        }

        val thrown = runCatching {
            runBlocking {
                job = Job()
                withContext(job) {
                    DwTraceSession(host).trace(ByteArray(16), 2, 2, PARAMS, false) {}
                }
            }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue("the isolate must have been closed", host.closed)
        assertTrue(
            "and only after the token was given a few turns to work",
            host.calls.count { it.contains(".pump(") } >= DW_TRACE_CANCEL_PUMPS,
        )
    }

    @Test
    fun `a cancel nobody asked for is reported rather than swallowed`() {
        val host = FakeHost { script ->
            if (script.contains(".hello(")) HELLO else """{"v":1,"state":"cancelled"}"""
        }
        val failure = runCatching {
            runBlocking { DwTraceSession(host).trace(ByteArray(16), 2, 2, PARAMS, false) {} }
        }.exceptionOrNull()
        assertTrue(failure is DwTraceHostFailure)
        assertEquals(DwTraceFailureKind.PROTOCOL_UNREADABLE, (failure as DwTraceHostFailure).kind)
    }

    @Test
    fun `a trace that never finishes is bounded rather than spun forever`() {
        val host = FakeHost { script ->
            if (script.contains(".hello(")) HELLO else running("edge", 0.5f)
        }
        val failure = runCatching {
            runBlocking { DwTraceSession(host).trace(ByteArray(16), 2, 2, PARAMS, false) {} }
        }.exceptionOrNull()
        assertTrue(failure is DwTraceHostFailure)
        assertEquals(DwTraceFailureKind.PROTOCOL_UNREADABLE, (failure as DwTraceHostFailure).kind)
        assertTrue(host.calls.size <= DW_TRACE_MAX_PUMPS + 4)
    }

    @Test
    fun `an override is merged and sanitised by the engine, and the answer is carried verbatim`() {
        val answer = """{"styleId":"clean-line","edge":{"sensitivity":0.7}}"""
        val host = FakeHost { """{"v":1,"state":"value","params":${embed(answer)}}""" }
        val values = runBlocking {
            DwTraceSession(host).withOverrides(PARAMS, mapOf("edge.sensitivity" to DwTraceValue.Num(0.7)))
        }
        assertEquals(answer, values.wire)
        assertEquals(0.7, values.number("edge.sensitivity"))

        val script = host.calls.single()
        assertTrue("the base tree must go as a string", script.contains(embed(PARAMS.wire)))
        assertTrue("and so must the patch", script.contains("edge.sensitivity"))
    }

    @Test
    fun `an answer of the wrong shape is refused rather than half-read`() {
        val host = FakeHost { """{"v":1,"state":"presets","styles":[],"subjects":[]}""" }
        val failure = runCatching { runBlocking { DwTraceSession(host).defaults() } }.exceptionOrNull()
        assertEquals(
            DwTraceFailureKind.PROTOCOL_UNREADABLE,
            (failure as DwTraceHostFailure).kind,
        )
    }

    private class FakeHost(private val answer: FakeHost.(String) -> String) : DwTraceJsHost {
        val calls = mutableListOf<String>()
        val provided = mutableListOf<Pair<String, ByteArray>>()
        var closed = false

        override suspend fun provideNamedData(name: String, bytes: ByteArray) {
            provided += name to bytes
        }

        override suspend fun evaluate(script: String): String {
            calls += script
            return answer(script)
        }

        override fun close() {
            closed = true
        }
    }

    private val PARAMS = dwTraceValuesOf("""{"styleId":"clean-line","output":{"background":null}}""")

    private fun embed(json: String): String =
        "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun running(stageId: String, fraction: Float): String =
        """{"v":1,"state":"running","runId":1,"events":[{"stageId":"$stageId",""" +
            """"label":"${LABELS[stageId]}","fraction":$fraction}]}"""

    private companion object {
        val LABELS = mapOf(
            "prepare" to "Preparing image",
            "matte" to "Separating background",
            "edge" to "Detecting edges",
            "vectorize" to "Tracing vectors",
            "document" to "Assembling document",
        )

        const val HELLO =
            """{"v":1,"state":"hello","contract":1,"pumped":true,"engine":"9268cb78","svgWriter":"buildSvg"}"""

        const val HELLO_UNPUMPED =
            """{"v":1,"state":"hello","contract":1,"pumped":false,"engine":"9268cb78","svgWriter":"buildSvg"}"""

        const val DONE =
            """{"v":1,"state":"done","runId":1,"events":[],"result":{"svg":"<svg/>",""" +
                """"width":2,"height":2,"workingWidth":2,"workingHeight":2,"shapeCount":1,"nodeCount":3,""" +
                """"totalMillis":12,"background":null,"stages":[],"notes":["Traced at 720 px."],""" +
                """"autoSubjectId":"sketch","suggestedStyleId":"pencil-sketch",""" +
                """"appliedParams":"{\"styleId\":\"clean-line\"}","geometry":{""" +
                """"coords":"AAAAAAAAAAAAACBBAAAAAAAAIEEAACBB","verbs":"AAA=","verbStarts":"AAAAAAIAAAA=",""" +
                """"coordStarts":"AAAAAAYAAAA=","closed":"AQ==","styleIndex":"AAAAAA==",""" +
                """"styleTable":[{"stroke":4278190080,"strokeWidth":1.5,"fill":null,"fillRule":"EVENODD",""" +
                """"cap":"ROUND","join":"ROUND","miterLimit":4,"opacity":1}]}}}"""
    }
}
