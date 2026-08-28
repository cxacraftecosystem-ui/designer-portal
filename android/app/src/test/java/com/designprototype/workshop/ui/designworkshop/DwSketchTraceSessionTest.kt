package com.designprototype.workshop.ui.designworkshop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE CONVERSATION WITH THE ENGINE, DRIVEN BY A STAND-IN.**
 *
 * `DwTraceJsHost` is three methods wide for exactly this reason, and the precedent is the file this
 * feature mirrors: `traceClient.ts` narrows the DOM `Worker` to five members so *"the whole of this
 * file can be driven from a Node spec"*, and `e2e/trace-engine-unit.spec.ts` drives it with a
 * stand-in worker. This is that, in Kotlin — every case below runs the real [DwTraceSession] against
 * a fake isolate on a machine with no handset attached.
 *
 * ── WHAT THESE CASES ARE ACTUALLY ABOUT ───────────────────────────────────────────────────────
 *
 * The pump protocol exists because a bare `JavaScriptIsolate` has microtasks and no task queue.
 * `Pipeline.run` yields between stages with `setTimeout(resolve, 0)` and `engine/pipeline.ts:214-224`
 * says what that yield is for: it is the only thing that lets a cancel be observed mid-trace. Shim
 * `setTimeout` as a microtask and the whole trace becomes one uninterruptible chain — no progress can
 * come out, and `CancellationToken` can never be set. Making the HOST the task queue gets both back,
 * and these are the cases that say so:
 *
 *  - progress arrives, one stage boundary at a time, in order;
 *  - a cancel between two pumps sets the engine's own token, and the engine's unwind is reported as a
 *    cancellation and NEVER as a failure;
 *  - a bundle that ignores the token gets its isolate closed underneath it rather than holding a
 *    designer's screen;
 *  - a bundle that cannot park at all still works, with no progress, and does not spin.
 */
class DwSketchTraceSessionTest {

    /* ── the handshake ──────────────────────────────────────────────────────────────────────── */

    /**
     * A bundle speaking another protocol is refused, not attempted.
     *
     * This is the one failure `UPSTREAM-MANIFEST.txt` structurally cannot catch: it hashes the
     * vendored TypeScript, not the JavaScript blob built from it, so an app shipping a stale bundle
     * traces with an old engine while the portal traces with a new one and both stay green. A
     * protocol one field different does not fail — it succeeds differently, which is how one sheet of
     * paper ends up as two different drawings.
     */
    @Test
    fun `a bundle speaking another protocol is refused with the version it speaks`() {
        val host = FakeHost { """{"v":1,"state":"hello","contract":99,"pumped":true}""" }
        val failure = runCatching { runBlocking { DwTraceSession(host).hello() } }.exceptionOrNull()
        assertTrue(failure is DwTraceHostFailure)
        assertEquals(DwTraceFailureKind.BUNDLE_CONTRACT_MISMATCH, (failure as DwTraceHostFailure).kind)
        assertTrue("the refusal must name both versions", failure.message!!.contains("99"))
    }

    @Test
    fun `the handshake reports which engine and which SVG writer the bundle carries`() {
        val host = FakeHost {
            """{"v":1,"state":"hello","contract":$DW_TRACE_CONTRACT,"pumped":true,""" +
                """"engine":"9268cb78","svgWriter":"buildSvg","notes":["Traced on this phone."]}"""
        }
        val hello = runBlocking { DwTraceSession(host).hello() }
        assertEquals("9268cb78", hello.engineManifestSha256)
        // NOT `SvgWriter.write`, and the difference is not cosmetic: `engine/svgWriter.ts:159-161`
        // stamps `<title>Offline Tracer export</title>` into every file it writes, and the portal
        // attaches `geometryToSvg.buildSvg`'s output instead. A handset using the other writer would
        // put another product's branding into a ministry submission, and spell every path differently.
        assertEquals("buildSvg", hello.svgWriter)
        assertTrue(hello.pumped)
        assertEquals(listOf("Traced on this phone."), hello.notes)
    }

    /* ── a whole trace ──────────────────────────────────────────────────────────────────────── */

    /**
     * Twelve stage boundaries, one evaluation each, in order — which is the whole argument for the
     * pump protocol over a single `trace()` call.
     */
    @Test
    fun `a pumped trace reports every stage boundary in order`() {
        val stages = listOf("prepare", "matte", "edge", "vectorize", "document")
        // `start` reports the first stage; each `pump` advances the trace by exactly one stage and
        // parks it again, which is the whole reason this protocol has two verbs instead of one.
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
        // The engine's own label, rendered as sent. `worker/trace.worker.ts:114-117` records what
        // re-typing engine wording in a client costs: "the two clients would eventually describe one
        // operation differently".
        assertEquals("Detecting edges", seenProgress[2].label)
        // `index / 12` at the START of the stage — a stage count, not a time estimate, and it never
        // reaches 1.0. The last event a real trace sends is 0.917.
        assertEquals(2f / 12f, seenProgress[2].fraction, 1e-6f)
        assertTrue("the fraction never reaches one", seenProgress.all { it.fraction < 1f })
        assertEquals(1, decoded.geometry.shapeCount)
        assertEquals("<svg/>", decoded.svg)
        assertEquals(listOf("Traced at 720 px."), decoded.notes)
        assertEquals("sketch", decoded.autoSubjectId)
        assertEquals("pencil-sketch", decoded.suggestedStyleId)

        // The pixels crossed as binary under the one name the protocol reserves, once.
        assertEquals(1, host.provided.size)
        assertEquals(DW_TRACE_IMAGE_DATA_NAME, host.provided[0].first)
        assertEquals(16, host.provided[0].second.size)
    }

    /**
     * A bundle that cannot park still traces, and does not spin.
     *
     * `hostGlobals.ts` — deleted with the rest of the JavaScript route — shimmed `setTimeout` as a
     * microtask, which made a trace one
     * uninterruptible chain — so its `start()` cannot answer `running` and must answer `done`. That
     * bundle reports `"pumped": false` and gets no progress and a Cancel that closes the isolate.
     * **Degrading is deliberate; degrading silently is not**, which is why `pumped` is reported at
     * the handshake and carried into what the panel shows.
     */
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

    /* ── the guards before anything is handed over ──────────────────────────────────────────── */

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

    /* ── cancellation ───────────────────────────────────────────────────────────────────────── */

    /**
     * A cancel between two pumps sets the engine's own token, and the unwind is a cancellation.
     *
     * This is the property the pump protocol exists to preserve. `engine/pipeline.ts:238-246` checks
     * its token in exactly one place, `RunContext.begin` — between stages — and the loop's own
     * `isActive` check falls between two pumps, which is the same place. So the two notions of
     * "cancelled" line up rather than one approximating the other.
     *
     * **And it must never be reported as a failure.** `worker/trace.worker.ts:156-157` says so for
     * the web, and a `Refused` here would put an error line under a drawing the designer chose to
     * stop, which reads as something they broke.
     */
    @Test
    fun `a cancel between stages sets the engine's token and unwinds as a cancellation`() {
        lateinit var job: Job
        val host = FakeHost { script ->
            when {
                script.contains(".hello(") -> HELLO
                script.contains(".cancel(") -> """{"v":1,"state":"cancelled"}"""
                script.contains(".start(") -> running("prepare", 0f)
                else -> {
                    // Cancelled at the first pump, which is a stage boundary and nowhere else.
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

    /**
     * A bundle that will not stop does not get to hold a designer's screen.
     *
     * The token is set, [DW_TRACE_CANCEL_PUMPS] turns of courtesy follow, and then the isolate is
     * closed underneath it. Closing is the blunt instrument and is used second — but it IS used,
     * because the alternative is a Cancel button that does nothing for as long as the bundle likes.
     */
    @Test
    fun `an engine that ignores its token has the isolate closed underneath it`() {
        lateinit var job: Job
        var pumps = 0
        val host = FakeHost { script ->
            when {
                script.contains(".hello(") -> HELLO
                script.contains(".start(") -> running("prepare", 0f)
                else -> {
                    // Answers `running` forever, cancel included.
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

    /**
     * A bundle that reports a cancel nobody asked for is a protocol fault, not a silent stop.
     *
     * Without this branch a buggy bundle could end every trace by answering `cancelled`, and the
     * panel — which is required never to show a cancel as an error — would show nothing at all. A
     * trace that ends with no drawing and no sentence is the ambiguity `pipeline.ts:46-50` calls the
     * bug class this project takes most seriously.
     */
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

    /**
     * A bundle that never finishes is bounded.
     *
     * Twelve stages plus a start is fourteen turns; [DW_TRACE_MAX_PUMPS] is sixty-four. A loop with
     * no bound is a coroutine spinning on a phone in a courtyard with nothing on screen to say so.
     */
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

    /* ── the parameter calls ────────────────────────────────────────────────────────────────── */

    /**
     * A patch goes to the engine's own `withOverrides`, and what comes back is what the engine said.
     *
     * There is no Kotlin merge in between and there must never be. `engine/params.ts` declares 74
     * leaves with 74 individually-argued clamps, several encoding a measured incident rather than a
     * taste, and `sanitizeTraceParams` is documented idempotent (`params.ts:806-810`) precisely so a
     * UI can run it on every slider tick without ever disagreeing with the pipeline.
     */
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

    /* ── the stand-in ───────────────────────────────────────────────────────────────────────── */

    /**
     * An isolate that answers from a script rather than from V8.
     *
     * It records every evaluation and every buffer, so a case can assert not only what came back but
     * WHAT WAS ASKED — which is how "the pixels crossed once, as binary, under the reserved name" is
     * a testable claim rather than a comment.
     */
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
        /** The engine's own labels (`engine/pipeline.ts:172-185`), never reworded by a client. */
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

        /** One closed shape, so a finished trace has geometry a plate could be drawn from. */
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
