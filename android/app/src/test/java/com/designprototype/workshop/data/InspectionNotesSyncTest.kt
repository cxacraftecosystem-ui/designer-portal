package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException

/**
 * THE REPOSITORY'S INSPECTION CALLS, OVER A REAL RETROFIT AND A SCRIPTED TRANSPORT.
 *
 * Real Retrofit so the paths, the verbs and the bodies are the ones the annotations produce — a
 * suggestion posted to the wrong route is the failure a fake service would hide. The transport is
 * scripted per "VERB path" and never opens a socket; an unscripted request is a test failure.
 *
 * What is held here, in the order an officer meets it: a note written without signal is KEPT and
 * waits; with signal it is sent with its device moment and leaves the phone; a report that moved on
 * holds it with the reason and sends NOTHING; the server's own refusal holds it too; an answer lost
 * on the way back does not file it twice; and the kept copy of a workshop is read back with no
 * signal and deleted the moment the assignment is gone.
 */
class InspectionNotesSyncTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var context: InMemoryContext
    private val script = HashMap<String, () -> Pair<Int, String>>()
    private val sent = ArrayList<Pair<String, String>>()

    private val inspector = UserDto(id = "u-inspector", email = "i@example.org", name = "Ira", role = "INSPECTOR")

    @Before
    fun setUp() {
        context = InMemoryContext(folder.root)
    }

    private fun repository(): WorkshopRepository {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
        val factory = object : Call.Factory {
            override fun newCall(request: Request): Call = ScriptedCall(request)
        }
        val api = Retrofit.Builder()
            .baseUrl("http://localhost:8000/api/")
            .callFactory(factory)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WorkshopRepositoryApi::class.java)
        val tokens = TokenStore(context).apply { setUser(inspector) }
        return WorkshopRepository(api, tokens)
    }

    private fun on(verb: String, path: String, answer: () -> Pair<Int, String>) {
        script["$verb $path"] = answer
    }

    private fun offline(verb: String, path: String) {
        script["$verb $path"] = { throw IOException("no signal") }
    }

    private fun detailJson(status: String, round: Int, feedback: String = "[]") =
        """{"id":"w-1","title":"Kantha, Bolpur","status":"$status","submissionRound":$round,""" +
            """"readOnly":true,"mayRecordFeedback":true,"inspectionFeedback":$feedback}"""

    private fun answerJson(status: String, round: Int, feedback: String) =
        """{"id":"w-1","status":"$status","submissionRound":$round,"mayRecordFeedback":true,""" +
            """"inspectionFeedback":$feedback}"""

    private fun read(status: String = "PRE_SUBMISSION", round: Int = 2) =
        DwInspectionDetailDto(id = "w-1", title = "Kantha, Bolpur", status = status, submissionRound = round, mayRecordFeedback = true)

    private fun store() = DwInspectionStore.of(context)

    // ── Filing ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with signal a suggestion is posted to the feedback route with its device moment and leaves the phone`() {
        on("GET", "/api/design-workshop-inspections/w-1") { 200 to detailJson("PRE_SUBMISSION", 2) }
        on("POST", "/api/design-workshop-inspections/w-1/feedback") {
            201 to answerJson("PRE_SUBMISSION", 2, """[{"id":"f-1","round":2,"note":"Fix stage 4.","actorId":"u-inspector"}]""")
        }
        val report = runBlocking {
            repository().fileInspectionNote(context, read(), DwInspectionNoteKind.SUGGESTION, "  Fix stage 4. ", "STAGE_4")
        }
        assertEquals(1, report.sent)
        assertEquals(1, report.refreshed["w-1"]?.inspectionFeedback?.size)
        val (route, body) = sent.single { it.first.startsWith("POST") }
        assertEquals("POST /api/design-workshop-inspections/w-1/feedback", route)
        assertTrue(body, body.contains("\"note\":\"Fix stage 4.\""))
        assertTrue(body, body.contains("\"stageKey\":\"STAGE_4\""))
        assertTrue(body, body.contains("\"recordedAt\":\""))
        assertTrue("a round is the server's to copy, never the client's to send", !body.contains("round"))
        assertTrue(store().notes().isEmpty())
    }

    @Test
    fun `a send-back goes to the send-back route and nowhere else`() {
        on("GET", "/api/design-workshop-inspections/w-1") { 200 to detailJson("PRE_SUBMISSION", 2) }
        on("POST", "/api/design-workshop-inspections/w-1/send-back") {
            200 to answerJson("NEEDS_REVISION", 2, """[{"id":"f-1","round":2,"note":"Redo.","sentBack":true,"actorId":"u-inspector"}]""")
        }
        val report = runBlocking {
            repository().fileInspectionNote(context, read(), DwInspectionNoteKind.SEND_BACK, "Redo.", null)
        }
        assertEquals(1, report.sent)
        assertEquals("NEEDS_REVISION", report.refreshed["w-1"]?.status)
        assertEquals(listOf("POST /api/design-workshop-inspections/w-1/send-back"), sent.filter { it.first.startsWith("POST") }.map { it.first })
    }

    @Test
    fun `without signal the note is kept on the phone and waits`() {
        offline("GET", "/api/design-workshop-inspections/w-1")
        val report = runBlocking {
            repository().fileInspectionNote(context, read(), DwInspectionNoteKind.SEND_BACK, "Redo stage 9.", null)
        }
        assertEquals(0, report.sent)
        assertEquals(1, report.waiting)
        val kept = store().notes().single()
        assertTrue(kept.waiting)
        assertEquals("Redo stage 9.", kept.note)
        assertEquals(2, kept.draftedRound)
    }

    // ── Back online, under the conflict rules ───────────────────────────────────────────────────

    @Test
    fun `a send-back the report moved on from is held with the reason, nothing is posted, and nothing is lost`() {
        offline("GET", "/api/design-workshop-inspections/w-1")
        runBlocking { repository().fileInspectionNote(context, read(), DwInspectionNoteKind.SEND_BACK, "Redo stage 9.", null) }

        // Signal returns; the designer withdrew the report in the meantime.
        on("GET", "/api/design-workshop-inspections/w-1") { 200 to detailJson("IN_PROGRESS", 2) }
        val report = runBlocking { repository().syncInspectionNotes(context) }

        assertEquals(1, report.held)
        assertTrue("nothing may be posted on a report that is no longer under review", sent.none { it.first.startsWith("POST") })
        val kept = store().notes().single()
        assertEquals("Redo stage 9.", kept.note)
        assertTrue(kept.held!!, kept.held!!.contains("moved on"))
    }

    @Test
    fun `a note written about a round that has since been handed in again is held, not filed on the new round`() {
        offline("GET", "/api/design-workshop-inspections/w-1")
        runBlocking { repository().fileInspectionNote(context, read(round = 2), DwInspectionNoteKind.SUGGESTION, "Check the dates.", null) }

        on("GET", "/api/design-workshop-inspections/w-1") { 200 to detailJson("PRE_SUBMISSION", 3) }
        runBlocking { repository().syncInspectionNotes(context) }
        assertTrue(sent.none { it.first.startsWith("POST") })
        assertTrue(store().notes().single().held!!.contains("round 3"))

        // The officer reads round 3 and re-files deliberately: now it goes.
        on("POST", "/api/design-workshop-inspections/w-1/feedback") {
            201 to answerJson("PRE_SUBMISSION", 3, """[{"id":"f-9","round":3,"note":"Check the dates.","actorId":"u-inspector"}]""")
        }
        val held = store().notes().single()
        val report = runBlocking { repository().refileInspectionNote(context, held.id, read(round = 3)) }
        assertEquals(1, report.sent)
        assertTrue(store().notes().isEmpty())
    }

    @Test
    fun `the server's own refusal holds the note with its sentence and keeps it`() {
        offline("GET", "/api/design-workshop-inspections/w-1")
        runBlocking { repository().fileInspectionNote(context, read(), DwInspectionNoteKind.SEND_BACK, "Redo.", null) }

        on("GET", "/api/design-workshop-inspections/w-1") { 200 to detailJson("PRE_SUBMISSION", 2) }
        // The race the server closes inside its transaction: withdrawn between the read and the write.
        on("POST", "/api/design-workshop-inspections/w-1/send-back") {
            422 to """{"detail":"This report has not been handed in for inspection yet, so there is nothing to comment on."}"""
        }
        val report = runBlocking { repository().syncInspectionNotes(context) }
        assertEquals(1, report.held)
        val kept = store().notes().single()
        assertTrue(kept.held!!, kept.held!!.contains("moved on before your send-back arrived"))
        assertEquals("Redo.", kept.note)
    }

    @Test
    fun `a fault on the far side leaves the note waiting, not refused`() {
        offline("GET", "/api/design-workshop-inspections/w-1")
        runBlocking { repository().fileInspectionNote(context, read(), DwInspectionNoteKind.SUGGESTION, "Fix.", null) }
        on("GET", "/api/design-workshop-inspections/w-1") { 200 to detailJson("PRE_SUBMISSION", 2) }
        on("POST", "/api/design-workshop-inspections/w-1/feedback") { 503 to """{"detail":"busy"}""" }
        val report = runBlocking { repository().syncInspectionNotes(context) }
        assertEquals(1, report.waiting)
        assertTrue(store().notes().single().waiting)
    }

    @Test
    fun `a note whose answer was lost is recognised on the register and not filed twice`() {
        offline("GET", "/api/design-workshop-inspections/w-1")
        runBlocking { repository().fileInspectionNote(context, read(), DwInspectionNoteKind.SUGGESTION, "Fix stage 2.", null) }
        val kept = store().notes().single()
        // The server re-spells the moment: microseconds and an explicit offset instead of `Z`.
        val echoed = java.time.OffsetDateTime.parse(kept.recordedAt)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx"))

        on("GET", "/api/design-workshop-inspections/w-1") {
            200 to detailJson(
                "PRE_SUBMISSION",
                2,
                """[{"id":"f-1","round":2,"note":"Fix stage 2.","actorId":"u-inspector","recordedAt":"$echoed"}]"""
            )
        }
        val report = runBlocking { repository().syncInspectionNotes(context) }
        assertEquals(1, report.sent)
        assertTrue("it is already on the record; posting it again would file it twice", sent.none { it.first.startsWith("POST") })
        assertTrue(store().notes().isEmpty())
    }

    @Test
    fun `an assignment that ended holds every note on that workshop and forgets the kept read`() {
        on("GET", "/api/design-workshop-inspections/w-1") { 200 to detailJson("PRE_SUBMISSION", 2) }
        runBlocking { repository().readWorkshopUnderInspection(context, "w-1") }
        assertNotNull(store().detail("w-1", "u-inspector"))
        store().add(
            DwQueuedInspectionNote(
                id = "n-1", workshopId = "w-1", note = "Fix.", recordedAt = dwInspectionNow(),
                ownerUserId = "u-inspector", draftedRound = 2, draftedStatus = "PRE_SUBMISSION"
            )
        )

        on("GET", "/api/design-workshop-inspections/w-1") { 404 to """{"detail":"Record not found"}""" }
        runBlocking { repository().syncInspectionNotes(context) }
        assertTrue(store().notes().single().held!!.contains("no longer open to you"))
        assertNull(store().detail("w-1", "u-inspector"))
    }

    // ── Reading without signal ──────────────────────────────────────────────────────────────────

    @Test
    fun `a workshop read once is read back from the phone without signal, and says when it was saved`() {
        on("GET", "/api/design-workshop-inspections/w-1") { 200 to detailJson("PRE_SUBMISSION", 2) }
        val live = runBlocking { repository().readWorkshopUnderInspection(context, "w-1") }
        assertTrue(live is DwInspectionRead.Live)

        offline("GET", "/api/design-workshop-inspections/w-1")
        val saved = runBlocking { repository().readWorkshopUnderInspection(context, "w-1") }
        assertTrue(saved is DwInspectionRead.Saved)
        assertEquals("Kantha, Bolpur", saved.detail.title)
    }

    @Test
    fun `a not-open-to-you read deletes the kept copy before it is rethrown`() {
        on("GET", "/api/design-workshop-inspections/w-1") { 200 to detailJson("PRE_SUBMISSION", 2) }
        runBlocking { repository().readWorkshopUnderInspection(context, "w-1") }

        on("GET", "/api/design-workshop-inspections/w-1") { 404 to """{"detail":"Record not found"}""" }
        val failure = runCatching { runBlocking { repository().readWorkshopUnderInspection(context, "w-1") } }.exceptionOrNull()
        assertEquals(404, (failure as HttpException).code())

        offline("GET", "/api/design-workshop-inspections/w-1")
        val afterwards = runCatching { runBlocking { repository().readWorkshopUnderInspection(context, "w-1") } }.exceptionOrNull()
        assertTrue("no copy may outlive the assignment", afterwards is IOException)
    }

    @Test
    fun `the review queue reads the assigned list and falls back to the kept one without signal`() {
        on("GET", "/api/design-workshop-inspections") {
            200 to """{"items":[{"id":"w-1","status":"PRE_SUBMISSION"},{"id":"w-2","status":"NEEDS_REVISION"}],"total":2,"page":1,"pageSize":100,"pages":1}"""
        }
        val (rows, more, savedAt) = runBlocking { repository().inspectionReviewQueue(context) }
        assertEquals(listOf("w-1", "w-2"), rows.map { it.id })
        assertEquals(false, more)
        assertNull(savedAt)
        assertTrue(sent.first().second.isEmpty())

        offline("GET", "/api/design-workshop-inspections")
        val (keptRows, _, keptAt) = runBlocking { repository().inspectionReviewQueue(context) }
        assertEquals(listOf("w-1", "w-2"), keptRows.map { it.id })
        assertNotNull(keptAt)
    }

    // ── The transport ───────────────────────────────────────────────────────────────────────────

    private inner class ScriptedCall(private val req: Request) : Call {
        override fun request(): Request = req
        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun execute(): Response = answer()
        override fun enqueue(responseCallback: Callback) {
            val response = try {
                answer()
            } catch (e: IOException) {
                responseCallback.onFailure(this, e)
                return
            }
            responseCallback.onResponse(this, response)
        }
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = ScriptedCall(req)

        private fun answer(): Response {
            val route = "${req.method} ${req.url.encodedPath}"
            val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            sent += route to body
            val (code, text) = checkNotNull(script[route]) { "unscripted request: $route" }.invoke()
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code < 400) "OK" else "Refused")
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
