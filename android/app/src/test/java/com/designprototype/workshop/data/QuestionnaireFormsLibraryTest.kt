package com.designprototype.workshop.data

import com.designprototype.workshop.QFeature
import com.designprototype.workshop.ui.questionnaires.FormMenuItem
import com.designprototype.workshop.ui.questionnaires.FormsTab
import com.designprototype.workshop.ui.questionnaires.bindingLabel
import com.designprototype.workshop.ui.questionnaires.bindingLines
import com.designprototype.workshop.ui.questionnaires.formMenuItems
import com.designprototype.workshop.ui.questionnaires.formSessionBlock
import com.designprototype.workshop.ui.questionnaires.formsInTab
import com.designprototype.workshop.ui.questionnaires.formsTabOf
import com.designprototype.workshop.ui.questionnaires.formsTabs
import com.designprototype.workshop.ui.questionnaires.labelForQuestionnaireKind
import com.designprototype.workshop.ui.questionnaires.mayDeleteQuestionnaire
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.zip.GZIPInputStream

/**
 * The designer-portal Android FORMS LIBRARY, end to end against a fake server that keeps the real
 * routes' shapes (UNIFY §1.1 rows A1–A16, D3; §4.2 Android "Forms"; §4.3 offline).
 *
 * Every flow here runs the app's own repository, outbox and file stores — only the network is fake —
 * so a route renamed, a key dropped or a temporary id left unresolved fails here.
 */
class QuestionnaireFormsLibraryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var context: InMemoryContext
    private val author = UserDto(id = "u-author", email = "a@example.org", name = "Asha", role = "DESIGNER")
    private val other = UserDto(id = "u-other", email = "o@example.org", name = "Omar", role = "DESIGNER")
    private val admin = UserDto(id = "u-admin", email = "m@example.org", name = "Meera", role = "ADMIN")

    // ── The fake server ──────────────────────────────────────────────────────────────────────────
    private val sent = ArrayList<String>()
    private val bodies = HashMap<String, String>()
    private val keys = HashMap<String, String?>()
    private var title = ""
    private var kind: String? = null
    private var deleted = false
    private val sections = ArrayList<Pair<String, String>>() // id, title
    private val questions = ArrayList<Triple<String, String, String>>() // id, sectionId, prompt

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true; coerceInputValues = true }

    @Before
    fun setUp() {
        context = InMemoryContext(folder.root)
    }

    @After
    fun tearDown() {
        ConnectivityObserver.answerForTests = null
    }

    private fun form(): String {
        val secs = sections.mapIndexed { i, (id, t) ->
            val qs = questions.filter { it.second == id }.mapIndexed { j, (qid, _, p) ->
                """{"id":"$qid","prompt":"$p","sortOrder":$j,"isActive":true}"""
            }
            """{"id":"$id","code":"${'A' + i}","title":"$t","sortOrder":$i,"isActive":true,"questions":[${qs.joinToString(",")}]}"""
        }
        val k = kind?.let { "\"$it\"" } ?: "null"
        val d = if (deleted) "\"2026-10-22T08:00:00Z\"" else "null"
        return """{"id":"q_1","title":"$title","ownerId":"u-author","kind":$k,"isActive":true,"version":1,"deletedAt":$d,"designWorkshopId":"dw_9","sections":[${secs.joinToString(",")}]}"""
    }

    private fun answer(route: String, query: String?, body: String): Triple<Int, String, Map<String, String>> {
        val none = emptyMap<String, String>()
        return when {
            route == "POST /api/questionnaires" -> {
                val o = json.parseToJsonElement(body).jsonObject
                title = o["title"]!!.jsonPrimitive.content
                kind = o["kind"]?.jsonPrimitive?.content
                Triple(201, form(), none)
            }
            route == "POST /api/questionnaires/q_1/sections" -> {
                sections += "s_${sections.size + 1}" to json.parseToJsonElement(body).jsonObject["title"]!!.jsonPrimitive.content
                Triple(201, form(), none)
            }
            route.matches(Regex("POST /api/questionnaires/q_1/sections/(s_\\d+)/questions")) -> {
                val sid = route.split("/")[5]
                questions += Triple("x_${questions.size + 1}", sid, json.parseToJsonElement(body).jsonObject["prompt"]!!.jsonPrimitive.content)
                Triple(201, form(), none)
            }
            route == "GET /api/questionnaires/q_1" -> Triple(200, form(), none)
            route == "DELETE /api/questionnaires/q_1" -> { deleted = true; Triple(204, "", none) }
            route == "POST /api/questionnaires/q_1/restore" -> { deleted = false; Triple(200, "{}", none) }
            route == "GET /api/questionnaires" && query.orEmpty().contains("deletedOnly=true") -> Triple(
                200,
                """{"items":[${if (deleted) """{"id":"q_1","title":"$title","ownerId":"u-author","deletedAt":"2026-10-22T08:00:00Z"}""" else ""}],"total":${if (deleted) 1 else 0},"page":1,"pageSize":100,"pages":1}""",
                none,
            )
            route == "GET /api/questionnaire/forms/q_1/bindings" -> Triple(
                200,
                """{"questionnaireId":"q_1","bindings":[{"designWorkshopId":"dw_2","isPrimary":false},{"designWorkshopId":"dw_9","isPrimary":true}]}""",
                none,
            )
            route == "GET /api/questionnaires/pro-forma" -> Triple(200, "PK-pro-forma", mapOf("Content-Disposition" to """attachment; filename="DesignWorkshop_Questionnaire_Pro-Forma.xlsx""""))
            route == "GET /api/questionnaires/q_1/xlsx" -> Triple(200, "PK-workbook", mapOf("Content-Disposition" to """attachment; filename="DesignWorkshop_Questionnaire_2026-10-22_Survey_Export.xlsx""""))
            route == "GET /api/questionnaires/q_1/question-set.xlsx" -> Triple(200, "PK-questions", mapOf("Content-Disposition" to """attachment; filename="DesignWorkshop_Questionnaire_2026-10-22_Survey_Questions.xlsx""""))
            route == "POST /api/questionnaires/upload" -> Triple(
                201,
                """{"questionnaire":${form()},"report":{"created":3,"sections":1,"entriesCreated":2,"answersImported":5}}""",
                none,
            )
            route == "POST /api/questionnaires/q_1/upload" -> Triple(
                200,
                """{"questionnaire":${form()},"report":{"updated":1,"superseded":1,"retired":1,"kept":2,"versionBefore":1,"versionAfter":2}}""",
                none,
            )
            else -> Triple(404, """{"detail":"Not Found"}""", none)
        }
    }

    private inner class FakeCall(private val req: Request) : Call {
        override fun request(): Request = req
        override fun addEventListener(eventListener: okhttp3.EventListener) = Unit
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun execute(): Response = respond()
        override fun enqueue(responseCallback: Callback) {
            val response = try {
                respond()
            } catch (e: java.io.IOException) {
                responseCallback.onFailure(this, e)
                return
            }
            responseCallback.onResponse(this, response)
        }
        override fun cancel() = Unit
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(req)

        private fun respond(): Response {
            val route = "${req.method} ${req.url.encodedPath}"
            val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            sent += route
            bodies[route] = body
            keys[route] = req.header("Idempotency-Key")
            val (code, text, headers) = answer(route, req.url.encodedQuery, body)
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code < 400) "OK" else "Refused")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private val api: WorkshopRepositoryApi by lazy {
        Retrofit.Builder()
            .baseUrl("http://localhost:8000/api/")
            .callFactory(object : Call.Factory { override fun newCall(request: Request): Call = FakeCall(request) })
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WorkshopRepositoryApi::class.java)
    }

    private fun repository(as_: UserDto = author): WorkshopRepository =
        WorkshopRepository(api, TokenStore(context).apply { setToken("token"); setUser(as_) })

    private fun xlsxPart() = MultipartBody.Part.createFormData(
        "file", "Survey.xlsx", "PK".toRequestBody("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".toMediaType()),
    )

    // ── A1–A4: the workbook ──────────────────────────────────────────────────────────────────────

    @QFeature("A1")
    @Test
    fun `the pro-forma downloads under the name the server gave it`() = runBlocking {
        val response = api.questionnaireProForma()
        assertTrue(response.isSuccessful)
        assertEquals("DesignWorkshop_Questionnaire_Pro-Forma.xlsx", filenameFromContentDisposition(response.headers()["Content-Disposition"]))
        assertEquals(listOf("GET /api/questionnaires/pro-forma"), sent)
    }

    @QFeature("A2")
    @Test
    fun `an uploaded workbook builds a form and its hand-filled answers arrive as sessions`() = runBlocking {
        val result = api.uploadQuestionnaire(xlsxPart())
        assertEquals("q_1", result.questionnaire.id)
        assertEquals(2, result.report.entriesCreated)
        assertEquals(5, result.report.answersImported)
        assertTrue(bodies.getValue("POST /api/questionnaires/upload").contains("filename=\"Survey.xlsx\""))
    }

    @QFeature("A3")
    @Test
    fun `the full workbook and the question set are two routes and two names`() = runBlocking {
        val workbook = api.questionnaireWorkbook("q_1")
        val questionSet = api.questionnaireQuestionSet("q_1")
        assertEquals("DesignWorkshop_Questionnaire_2026-10-22_Survey_Export.xlsx", filenameFromContentDisposition(workbook.headers()["Content-Disposition"]))
        assertEquals("DesignWorkshop_Questionnaire_2026-10-22_Survey_Questions.xlsx", filenameFromContentDisposition(questionSet.headers()["Content-Disposition"]))
        assertTrue(artefactCarriesRespondents(DwQuestionnaireArtefact.FULL_WORKBOOK))
        assertFalse(artefactCarriesRespondents(DwQuestionnaireArtefact.QUESTION_SET))
    }

    @QFeature("A4")
    @Test
    fun `a re-upload edits the form and reports what it superseded and retired`() = runBlocking {
        val result = api.reuploadQuestionnaire("q_1", xlsxPart())
        assertEquals(1, result.report.superseded)
        assertEquals(1, result.report.retired)
        assertEquals(2, result.report.versionAfter)
        assertEquals(listOf("POST /api/questionnaires/q_1/upload"), sent)
        // The re-upload is the author's or a content admin's, as every edit (owner, 22 Oct 2026).
        assertTrue(FormMenuItem.REUPLOAD in formMenuItems(mayEdit = true, mayDelete = true, deleted = false, onPhoneOnly = false))
        assertFalse(FormMenuItem.REUPLOAD in formMenuItems(mayEdit = false, mayDelete = false, deleted = false, onPhoneOnly = false))
    }

    // ── A6, A15, D3: authoring, online and offline ───────────────────────────────────────────────

    @QFeature("A6")
    @Test
    fun `an author builds a form online, section and question, each create carrying a key`() = runBlocking {
        ConnectivityObserver.answerForTests = true
        val repository = repository()
        val created = repository.createCustomQuestionnaireOrQueue(context, "Market walk", null, null, kind = "MARKET_SURVEY")!!
        assertEquals("q_1", created.id)
        repository.authorQuestionnaireOrQueue(context, QFormOp(op = QFormOp.ADD_SECTION, questionnaireId = "q_1", tempId = newTempId(), title = "Stalls"), "s")
        repository.authorQuestionnaireOrQueue(context, QFormOp(op = QFormOp.ADD_QUESTION, questionnaireId = "q_1", sectionId = "s_1", tempId = newTempId(), prompt = "What sells?", isRequired = false), "q")
        assertEquals(
            listOf("POST /api/questionnaires", "POST /api/questionnaires/q_1/sections", "POST /api/questionnaires/q_1/sections/s_1/questions"),
            sent,
        )
        assertTrue(sent.all { keys[it] != null })
        assertEquals(listOf("What sells?"), questions.map { it.third })
    }

    @QFeature("A15")
    @Test
    fun `a form authored with no signal uploads in order, its temporary ids replaced by the server's`() = runBlocking {
        ConnectivityObserver.answerForTests = false
        val repository = repository()
        val form = repository.createCustomQuestionnaireOrQueue(context, "Courtyard form", null, null)!!
        assertTrue(isTempId(form.id))
        val sectionTemp = newTempId()
        repository.authorQuestionnaireOrQueue(context, QFormOp(op = QFormOp.ADD_SECTION, questionnaireId = form.id, tempId = sectionTemp, title = "Tools"), "s")
        repository.authorQuestionnaireOrQueue(context, QFormOp(op = QFormOp.ADD_QUESTION, questionnaireId = form.id, sectionId = sectionTemp, tempId = newTempId(), prompt = "Which tools?", isRequired = true), "q")
        assertTrue("nothing left the phone", sent.isEmpty())
        // The library shows it under Mine, on this phone, before it has uploaded.
        val pending = repository.pendingQuestionnaires(context)
        assertEquals(listOf(form.id), pending.map { it.id })

        ConnectivityObserver.answerForTests = true
        repository.syncOutbox(context)

        assertEquals(
            listOf("POST /api/questionnaires", "POST /api/questionnaires/q_1/sections", "POST /api/questionnaires/q_1/sections/s_1/questions"),
            sent.filter { it.startsWith("POST /api/questionnaires") },
        )
        val map = TempIdMap.all(context)
        assertEquals("q_1", map[form.id])
        assertEquals("s_1", map[sectionTemp])
        assertEquals(listOf("Which tools?"), questions.map { it.third })
        assertTrue(repository.pendingQuestionnaires(context).isEmpty())
    }

    @QFeature("D3")
    @Test
    fun `a designer's own form is theirs, under Mine, with its author's controls`() = runBlocking {
        ConnectivityObserver.answerForTests = true
        val mine = repository().createCustomQuestionnaireOrQueue(context, "My form", null, null)!!
        val row = CustomQuestionnaireSummaryDto(id = mine.id, title = mine.title, ownerId = mine.ownerId)
        assertEquals(FormsTab.MINE, formsTabOf(row, author.id))
        assertTrue(mayDeleteQuestionnaire(author, mine.ownerId))
        assertFalse(mayDeleteQuestionnaire(other, mine.ownerId))
    }

    // ── A5: reuse ─────────────────────────────────────────────────────────────────────────────────

    @QFeature("A5")
    @Test
    fun `Reuse is offered on every live form, to any designer`() {
        assertTrue(FormMenuItem.REUSE in formMenuItems(mayEdit = false, mayDelete = false, deleted = false, onPhoneOnly = false))
        assertFalse(FormMenuItem.REUSE in formMenuItems(mayEdit = true, mayDelete = true, deleted = true, onPhoneOnly = false))
        assertTrue(reuseCanSendNow("q_1", null, emptyMap(), pendingForSource = 0, online = true))
        assertFalse(reuseCanSendNow("q_1", null, emptyMap(), pendingForSource = 1, online = true))
    }

    // ── A8: kinds ─────────────────────────────────────────────────────────────────────────────────

    @QFeature("A8")
    @Test
    fun `a form's kind is sent on create, named in words, and travels in a handoff`() = runBlocking {
        ConnectivityObserver.answerForTests = true
        repository().createCustomQuestionnaireOrQueue(context, "Market walk", null, null, kind = "MARKET_SURVEY")
        assertTrue(bodies.getValue("POST /api/questionnaires").contains("\"kind\":\"MARKET_SURVEY\""))
        assertEquals("Market survey", labelForQuestionnaireKind("MARKET_SURVEY"))
        val bundle = questionnaireBundleOf(json.decodeFromString(CustomQuestionnaireDto.serializer(), form().replace("\"sections\":[]", sectionsJsonForBundle())))
        assertEquals("MARKET_SURVEY", bundle.kind)
        val back = readQuestionnaireBundle(encodeQuestionnaireBundleV2(bundle, "b1", "2026-10-22T10:00:00+05:30")) as QuestionnaireBundleRead.Ok
        assertEquals("MARKET_SURVEY", back.bundle.kind)
        assertTrue(FormMenuItem.KIND in formMenuItems(mayEdit = true, mayDelete = true, deleted = false, onPhoneOnly = false))
    }

    private fun sectionsJsonForBundle() =
        """"sections":[{"id":"s_1","code":"A","title":"Stalls","sortOrder":0,"isActive":true,"questions":[{"id":"x_1","prompt":"What sells?","sortOrder":0,"isActive":true}]}]"""

    // ── A9: bindings, read-only ───────────────────────────────────────────────────────────────────

    @QFeature("A9")
    @Test
    fun `a form's workshops are listed primary first, from the server's bindings`() = runBlocking {
        val bindings = repository().formBindings("q_1")
        assertNotNull(bindings)
        val lines = bindingLines(bindings!!.bindings, "dw_9") { id -> mapOf("dw_9" to "Bagru", "dw_2" to "Sanganer")[id] }
        assertEquals(listOf("Bagru · Primary", "Sanganer"), lines.map(::bindingLabel))
        // An older server with no bindings route: the form's own workshop is its one binding.
        val fallback = bindingLines(null, "dw_9") { null }
        assertEquals(listOf("dw_9 · Primary"), fallback.map(::bindingLabel))
        assertTrue(bindingLines(null, null) { null }.isEmpty())
    }

    // ── A10: the handoff bundle, both extensions, idempotent ─────────────────────────────────────

    @QFeature("A10")
    @Test
    fun `a handoff file is the shared v2 envelope, reads back whole, and a frwq is opened too`() = runBlocking {
        val bundle = QuestionnaireBundle(
            title = "Cluster survey",
            kind = "WORKSHOP_INTERVIEW",
            sections = listOf(QuestionnaireBundleSection("A", "Tools", 0, listOf(QuestionnaireBundleQuestion("Which tools?", isRequired = true)))),
        )
        val bytes = encodeQuestionnaireBundleV2(bundle, "0123abcd", "2026-10-22T10:00:00+05:30")
        val wire = GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
        val envelope = json.parseToJsonElement(wire).jsonObject
        assertEquals(2, envelope["v"]!!.jsonPrimitive.content.toInt())
        assertEquals("0123abcd", envelope["bundleId"]!!.jsonPrimitive.content)
        assertEquals("A", envelope["questions"]!!.jsonArray.single().jsonObject["sectionCode"]!!.jsonPrimitive.content)
        assertFalse("no ids travel", wire.contains("\"id\""))

        val read = readQuestionnaireBundle(bytes) as QuestionnaireBundleRead.Ok
        assertEquals("0123abcd", read.bundleId)
        assertEquals(questionnaireHandoffCode(bundle), questionnaireHandoffCode(read.bundle))

        // A newer major version is refused, never half-read.
        val future = gzip("""{"v":3,"bundleId":"x","form":{"title":"F"},"sections":[],"questions":[]}""")
        assertEquals(QuestionnaireBundleRefusal.NEWER_VERSION, (readQuestionnaireBundle(future) as QuestionnaireBundleRead.Refused).reason)

        // Both extensions and both portals' types are recognised on the way in.
        assertTrue(hasQuestionnaireBundleExtension("Survey_Handoff.frwq"))
        assertTrue(hasQuestionnaireBundleExtension("x.DPWQ"))
        assertTrue(isQuestionnaireBundleDelivery("android.intent.action.SEND", QUESTIONNAIRE_BUNDLE_MIME_FR, null))
        assertTrue(isQuestionnaireBundleDelivery("android.intent.action.VIEW", "application/octet-stream", "FieldRepository_Questionnaire_2026-10-22_Survey_Handoff.frwq"))

        // Receiving the same file twice is one row (import is idempotent on the bundle id).
        val first = QuestionnaireBundleInbox.put(context, "a.frwq", bytes, read.bundle, read.bundleId)
        val again = QuestionnaireBundleInbox.put(context, "a (1).frwq", bytes, read.bundle, read.bundleId)
        assertEquals(first.id, again.id)
        assertEquals(1, QuestionnaireBundleInbox.all(context).size)
        assertEquals("WORKSHOP_INTERVIEW", first.kind)

        // Adopting it sends its kind and a stable Idempotency-Key.
        ConnectivityObserver.answerForTests = true
        repository().adoptReceivedQuestionnaire(context, first)
        assertTrue(bodies.getValue("POST /api/questionnaires").contains("\"kind\":\"WORKSHOP_INTERVIEW\""))
        assertEquals(adoptionIdempotencyKey(first.id, "q"), keys["POST /api/questionnaires"])

        // The file is named by the naming helper.
        assertEquals(
            "DesignWorkshop_Questionnaire_2026-10-22_Cluster-Survey_Handoff.dpwq",
            questionnaireHandoffFilename("Cluster survey", "2026-10-22"),
        )
    }

    private fun gzip(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    // ── A13: soft delete and restore ──────────────────────────────────────────────────────────────

    @QFeature("A13")
    @Test
    fun `the author deletes a form softly, it waits under Deleted, and Restore brings it back`() = runBlocking {
        val repository = repository()
        assertTrue(FormMenuItem.DELETE in formMenuItems(mayEdit = true, mayDelete = mayDeleteQuestionnaire(author, "u-author"), deleted = false, onPhoneOnly = false))
        assertFalse(FormMenuItem.DELETE in formMenuItems(mayEdit = false, mayDelete = mayDeleteQuestionnaire(other, "u-author"), deleted = false, onPhoneOnly = false))
        assertTrue("a content admin may delete anybody's form", mayDeleteQuestionnaire(admin, "u-author"))

        title = "Survey"
        repository.deleteCustomQuestionnaire("q_1")
        assertEquals(listOf("q_1"), repository.deletedCustomQuestionnaires().map { it.id })
        val opened = api.customQuestionnaire("q_1")
        assertNotNull(opened.deletedAt)
        assertEquals(listOf(FormMenuItem.RESTORE), formMenuItems(mayEdit = true, mayDelete = true, deleted = true, onPhoneOnly = false))
        assertEquals("Deleted — restore it to start a session.", formSessionBlock(true, deleted = true, activeQuestions = 3, onPhoneOnly = false))

        repository.restoreCustomQuestionnaire("q_1")
        assertTrue(repository.deletedCustomQuestionnaires().isEmpty())
        assertNull(api.customQuestionnaire("q_1").deletedAt)
        assertTrue(FormsTab.DELETED in formsTabs(mayListDeleted = true))
        assertFalse(FormsTab.DELETED in formsTabs(mayListDeleted = false))
    }

    // ── A16: the owner-stamped cache ─────────────────────────────────────────────────────────────

    @QFeature("A16")
    @Test
    fun `a form read online opens offline for the same account and never for another`() = runBlocking {
        title = "Survey"
        sections += "s_1" to "Tools"
        ConnectivityObserver.answerForTests = true
        val live = repository(author).customQuestionnaireCached(context, "q_1")
        assertFalse(live.fromCache)

        // No signal: the next read throws at the network and the phone's copy answers, for its owner.
        val offlineApi = Retrofit.Builder()
            .baseUrl("http://localhost:8000/api/")
            .callFactory(object : Call.Factory {
                override fun newCall(request: Request): Call = object : Call by FakeCall(request) {
                    override fun execute(): Response = throw java.io.IOException("no signal")
                    override fun enqueue(responseCallback: Callback) = responseCallback.onFailure(this, java.io.IOException("no signal"))
                }
            })
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WorkshopRepositoryApi::class.java)
        val offlineAsAuthor = WorkshopRepository(offlineApi, TokenStore(context).apply { setToken("token"); setUser(author) })
        val cached = offlineAsAuthor.customQuestionnaireCached(context, "q_1")
        assertTrue(cached.fromCache)
        assertEquals("Survey", cached.form.title)

        val offlineAsOther = WorkshopRepository(offlineApi, TokenStore(context).apply { setToken("token"); setUser(other) })
        assertTrue(runCatching { offlineAsOther.customQuestionnaireCached(context, "q_1") }.isFailure)
        assertTrue(formCacheReadableBy("u-author", "u-author"))
        assertFalse(formCacheReadableBy("u-author", "u-other"))
        assertFalse("an unowned copy is never served", formCacheReadableBy("", "u-author"))
    }

    // ── The library's tabs ────────────────────────────────────────────────────────────────────────

    @Test
    fun `each form sits in exactly one tab`() {
        val rows = listOf(
            CustomQuestionnaireSummaryDto(id = "q_mine", ownerId = "u-author", isShared = true),
            CustomQuestionnaireSummaryDto(id = "q_shared", ownerId = "u-admin", isShared = true),
            CustomQuestionnaireSummaryDto(id = "q_ws", ownerId = "u-other", designWorkshopId = "dw_9"),
            CustomQuestionnaireSummaryDto(id = TEMP_ID_PREFIX + "x", ownerId = null),
        )
        assertEquals(listOf("q_mine", TEMP_ID_PREFIX + "x"), formsInTab(rows, FormsTab.MINE, "u-author").map { it.id })
        assertEquals(listOf("q_shared"), formsInTab(rows, FormsTab.SHARED, "u-author").map { it.id })
        assertEquals(listOf("q_ws"), formsInTab(rows, FormsTab.WORKSHOP, "u-author").map { it.id })
        assertTrue(formsInTab(rows, FormsTab.DELETED, "u-author").isEmpty())
        assertTrue(formMenuItems(mayEdit = true, mayDelete = true, deleted = false, onPhoneOnly = true).isEmpty())
    }

    @Suppress("unused")
    private fun objectOf(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
}
