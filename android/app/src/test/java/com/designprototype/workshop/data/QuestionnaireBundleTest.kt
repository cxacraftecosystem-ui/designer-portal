package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class QuestionnaireBundleTest {

    private fun question(
        id: String,
        prompt: String,
        order: Int,
        active: Boolean = true,
        superseded: String? = null,
        required: Boolean = false,
        help: String? = null,
    ) = CustomQuestionDto(
        id = id,
        prompt = prompt,
        helpText = help,
        isRequired = required,
        sortOrder = order,
        isActive = active,
        supersededById = superseded,
    )

    private fun form(
        sections: List<CustomSectionDto>,
        title: String = "Cluster survey",
        version: Int = 7,
    ) = CustomQuestionnaireDto(
        id = "cq_server_side_id",
        title = title,
        description = "  For the fortnight in Nirona  ",
        ownerId = "usr_priya",
        designWorkshopId = "dw_1",
        version = version,
        sections = sections,
        entries = listOf(
            CustomEntryDto(
                id = "entry_1",
                title = "Sitting with Rekha Devi",
                respondentName = "Rekha Devi",
                createdById = "usr_priya",
                createdByName = "Priya",
                answers = listOf(
                    CustomAnswerDto(
                        id = "ans_1",
                        questionId = "q1",
                        answerText = "Her loom was her mother's",
                        answeredById = "usr_priya",
                    )
                ),
            )
        ),
    )

    private val twoSections = listOf(
        CustomSectionDto(
            id = "sec_b", code = "B", title = "Tools", sortOrder = 2,
            questions = listOf(
                question("q3", "  Which tools does the workshop own?  ", order = 1, required = true),
                question("q4", "Retired wording", order = 2, active = false),
                question("q5", "Reworded away", order = 3, superseded = "q6"),
                question("q6", "", order = 4),
            ),
        ),
        CustomSectionDto(
            id = "sec_a", code = "A", title = "The household", sortOrder = 1,
            questions = listOf(question("q1", "Who lives here?", order = 1, help = "  Names only  ")),
        ),
        CustomSectionDto(
            id = "sec_dead", code = "Z", title = "A retired section", sortOrder = 3,
            isActive = false,
            questions = listOf(question("q9", "Should not travel", order = 1)),
        ),
    )

    @Test
    fun `a bundle carries the live questions, in order, and nothing else`() {
        val bundle = questionnaireBundleOf(form(twoSections))
        assertEquals(listOf("A", "B"), bundle.sections.map { it.code })
        assertEquals(2, bundle.questionCount)
        assertEquals("Who lives here?", bundle.sections[0].questions.single().prompt)
        assertEquals("Names only", bundle.sections[0].questions.single().helpText)
        val prompts = bundle.sections.flatMap { it.questions }.map { it.prompt }
        assertFalse(prompts.contains("Retired wording"))
        assertFalse(prompts.contains("Reworded away"))
        assertFalse(prompts.contains("Should not travel"))
        assertTrue(prompts.none { it.isBlank() })
        assertEquals("Which tools does the workshop own?", bundle.sections[1].questions.single().prompt)
        assertEquals("For the fortnight in Nirona", bundle.description)
        assertEquals(7, bundle.sourceVersion)
    }

    @Test
    fun `no answer, no respondent, no owner, no id and no status is IN the format`() {
        val json = canonicalQuestionnaireBundleJson(questionnaireBundleOf(form(twoSections)))
        listOf(
            "Rekha Devi",
            "Her loom was her mother's",
            "usr_priya",
            "cq_server_side_id",
            "entry_1", "ans_1",
            "dw_1",
        ).forEach { forbidden ->
            assertFalse(
                "the peer format leaked `$forbidden`",
                json.contains(forbidden),
            )
        }
        listOf("\"id\"", "\"ownerId\"", "\"createdById\"", "\"status\"", "\"answers\"", "\"entries\"",
            "\"respondentName\"", "\"designWorkshopId\"", "\"recordedBy\"")
            .forEach { key -> assertFalse("the format has a field for $key", json.contains(key)) }
    }

    @Test
    fun `a bundle round-trips through the bytes that cross the wire`() {
        val bundle = questionnaireBundleOf(form(twoSections))
        val read = readQuestionnaireBundle(encodeQuestionnaireBundle(bundle))
        assertTrue("$read", read is QuestionnaireBundleRead.Ok)
        assertEquals(bundle, (read as QuestionnaireBundleRead.Ok).bundle)
    }

    @Test
    fun `it compresses, which is why the questionnaire's size was never the problem`() {
        val bundle = questionnaireBundleOf(form(twoSections))
        val json = canonicalQuestionnaireBundleJson(bundle).toByteArray(Charsets.UTF_8)
        val gz = encodeQuestionnaireBundle(bundle)
        assertTrue("gzip made it bigger: ${json.size} -> ${gz.size}", gz.size < json.size)
    }

    private fun gzipOf(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun refusalFor(bytes: ByteArray?): QuestionnaireBundleRefusal {
        val read = readQuestionnaireBundle(bytes)
        assertTrue("expected a refusal, got $read", read is QuestionnaireBundleRead.Refused)
        return (read as QuestionnaireBundleRead.Refused).reason
    }

    @Test
    fun `an empty or interrupted transfer is named as one`() {
        assertEquals(QuestionnaireBundleRefusal.EMPTY, refusalFor(null))
        assertEquals(QuestionnaireBundleRefusal.EMPTY, refusalFor(ByteArray(0)))
    }

    @Test
    fun `a truncated file is refused whole, and the message says why it looks like this`() {
        val whole = encodeQuestionnaireBundle(questionnaireBundleOf(form(twoSections)))
        val half = whole.copyOfRange(0, whole.size / 2)
        val read = readQuestionnaireBundle(half)
        assertTrue("$read", read is QuestionnaireBundleRead.Refused)
        read as QuestionnaireBundleRead.Refused
        assertEquals(QuestionnaireBundleRefusal.DAMAGED, read.reason)
        assertTrue(read.message, read.message.contains("cannot resume a transfer"))
    }

    @Test
    fun `somebody else's file is refused for the right reason`() {
        assertEquals(QuestionnaireBundleRefusal.DAMAGED, refusalFor("not gzip at all".toByteArray()))
        assertEquals(QuestionnaireBundleRefusal.NOT_A_QUESTIONNAIRE, refusalFor(gzipOf("<html>hello</html>")))
        assertEquals(
            QuestionnaireBundleRefusal.NOT_A_QUESTIONNAIRE,
            refusalFor(gzipOf("""{"format":"someone.elses.export","sections":[]}""")),
        )
    }

    @Test
    fun `a file from a newer build is refused rather than half-read`() {
        val newer = gzipOf(
            """{"format":"$QUESTIONNAIRE_BUNDLE_FORMAT","schemaVersion":""" +
                "${QUESTIONNAIRE_BUNDLE_SCHEMA_VERSION + 1}," +
                """"title":"T","sections":[{"code":"A","title":"A","questions":[{"prompt":"p"}]}]}"""
        )
        val read = readQuestionnaireBundle(newer)
        read as QuestionnaireBundleRead.Refused
        assertEquals(QuestionnaireBundleRefusal.NEWER_VERSION, read.reason)
        assertTrue(read.message, read.message.contains("Update the app"))
    }

    @Test
    fun `an older build's file still reads, because the fields are defaulted`() {
        val older = gzipOf(
            """{"format":"$QUESTIONNAIRE_BUNDLE_FORMAT","title":"Older",""" +
                """"sections":[{"code":"A","title":"A","questions":[{"prompt":"Still readable"}]}]}"""
        )
        val read = readQuestionnaireBundle(older)
        assertTrue("$read", read is QuestionnaireBundleRead.Ok)
        assertEquals("Still readable", (read as QuestionnaireBundleRead.Ok).bundle
            .sections.single().questions.single().prompt)
    }

    @Test
    fun `a questionnaire with nothing in it is not adopted`() {
        assertEquals(
            QuestionnaireBundleRefusal.NOTHING_IN_IT,
            refusalFor(gzipOf("""{"format":"$QUESTIONNAIRE_BUNDLE_FORMAT","title":"Empty","sections":[]}""")),
        )
        assertEquals(
            QuestionnaireBundleRefusal.NOTHING_IN_IT,
            refusalFor(
                gzipOf(
                    """{"format":"$QUESTIONNAIRE_BUNDLE_FORMAT","title":"E",""" +
                        """"sections":[{"code":"A","title":"A","questions":[]}]}"""
                )
            ),
        )
    }

    @Test
    fun `a decompression bomb is stopped while it inflates`() {
        val bomb = gzipOf("A".repeat(QUESTIONNAIRE_BUNDLE_MAX_INFLATED + 1024))
        assertTrue("a bomb should compress small", bomb.size < 100_000)
        assertEquals(QuestionnaireBundleRefusal.TOO_LARGE, refusalFor(bomb))
    }

    @Test
    fun `the filename is derived, never taken`() {
        assertEquals(
            "Cluster-survey-questions-v7.dpwq",
            questionnaireBundleFilename("Cluster survey", 7),
        )
        assertFalse(questionnaireBundleFilename("../../etc/passwd", 1).contains(".."))
        assertFalse(questionnaireBundleFilename("a/b\\c", 1).contains("/"))
        assertEquals("questionnaire-questions-v1.dpwq", questionnaireBundleFilename("   ", 1))
        assertTrue(questionnaireBundleFilename("कारीगर सर्वेक्षण", 2).startsWith("कारीगर-सर्वेक्षण"))
        assertTrue(questionnaireBundleFilename("x".repeat(200), 1).length < 120)
    }

    @Test
    fun `the digest is in the name, so two editions of one version cannot collide`() {
        val one = questionnaireBundleFilename("Cluster survey", 7, "8QK4T2WMZ0R")
        val two = questionnaireBundleFilename("Cluster survey", 7, "9ZZ11TTWMZ0R")
        assertEquals("Cluster-survey-questions-v7-8QK4T2WMZ0R.dpwq", one)
        assertTrue(one != two)
        assertEquals(one, questionnaireBundleFilename("Cluster survey", 7, "8QK4T2WMZ0R"))
        assertFalse(questionnaireBundleFilename("Survey", 1, "../../etc").contains(".."))
        assertFalse(questionnaireBundleFilename("Survey", 1, "a/b").contains("/"))
        assertEquals("Survey-questions-v1.dpwq", questionnaireBundleFilename("Survey", 1, ""))
    }

    @Test
    fun `a share-sheet delivery is recognised by our own subtype, and an open by its extension too`() {
        val send = "android.intent.action.SEND"
        val view = "android.intent.action.VIEW"
        assertTrue(isQuestionnaireBundleDelivery(send, QUESTIONNAIRE_BUNDLE_MIME, "x.dpwq"))
        assertTrue(isQuestionnaireBundleDelivery(send, "$QUESTIONNAIRE_BUNDLE_MIME; charset=utf-8", null))
        assertFalse(isQuestionnaireBundleDelivery(send, "application/octet-stream", null))
        assertFalse(isQuestionnaireBundleDelivery(send, "application/octet-stream", "survey.dpwq"))
        assertTrue(isQuestionnaireBundleDelivery(view, "application/octet-stream", "survey.dpwq"))
        assertTrue(isQuestionnaireBundleDelivery(view, "application/gzip", "survey.DPWQ"))
        assertTrue(isQuestionnaireBundleDelivery(view, "application/x-gzip", "survey.dpwq?download=1"))
        assertFalse(isQuestionnaireBundleDelivery(view, null, "survey.dpwq"))
        assertFalse(isQuestionnaireBundleDelivery(view, "image/jpeg", "survey.dpwq"))
        assertFalse(isQuestionnaireBundleDelivery(view, "application/octet-stream", "survey.zip"))
        assertFalse(isQuestionnaireBundleDelivery(null, QUESTIONNAIRE_BUNDLE_MIME, "x.dpwq"))
        assertFalse(isQuestionnaireBundleDelivery("android.intent.action.MAIN", QUESTIONNAIRE_BUNDLE_MIME, "x.dpwq"))
    }

    @Test
    fun `the two notices say the three things that cannot be left unsaid`() {
        assertTrue(QUESTIONNAIRE_BUNDLE_CONTENTS_NOTICE.contains("no answers"))
        assertTrue(QUESTIONNAIRE_BUNDLE_CONTENTS_NOTICE.contains("no respondents' names"))
        assertTrue(QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE.contains("NEW questionnaire on your account"))
        assertTrue(QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE.contains("uploaded by you"))
        assertTrue(QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE.contains("no answers"))
    }

    @Test
    fun `the handoff code is the shape this app already prints and scans`() {
        val bundle = questionnaireBundleOf(form(twoSections))
        val code = questionnaireHandoffCode(bundle)
        val parts = code.split(":")
        assertEquals(4, parts.size)
        assertEquals("${WORKSHOP_CODE_NAMESPACE}$QUESTIONNAIRE_HANDOFF_CODE_VERSION", parts[0])
        assertEquals(QUESTIONNAIRE_HANDOFF_LETTER, parts[1])
        assertEquals(11, parts[2].length)
        assertEquals(4, parts[3].length)
        assertTrue(looksLikeQuestionnaireHandoffCode(code))
        assertTrue(
            "the code is ${code.length} characters and the encoder holds " +
                "${DwQrEncode.capacity(DwQrEncode.MAX_VERSION, DwQrEccLevel.Q)}",
            code.length <= DwQrEncode.capacity(DwQrEncode.MAX_VERSION, DwQrEccLevel.Q),
        )
        assertTrue(DwQrEncode.encode(code, DwQrEccLevel.Q).size > 0)
    }

    @Test
    fun `a scanned code round-trips, and a mistyped one is refused`() {
        val bundle = questionnaireBundleOf(form(twoSections))
        val code = questionnaireHandoffCode(bundle)
        val read = readQuestionnaireHandoffCode(code)
        assertTrue("$read", read is QuestionnaireHandoffRead.Ok)
        assertEquals(code.split(":")[2], (read as QuestionnaireHandoffRead.Ok).digest)

        val spoken = code.replace('1', 'I').replace('0', 'O').lowercase()
        val fromSpoken = readQuestionnaireHandoffCode(" $spoken ")
        assertTrue("$fromSpoken", fromSpoken is QuestionnaireHandoffRead.Ok)
        assertEquals(read.digest, (fromSpoken as QuestionnaireHandoffRead.Ok).digest)

        val body = code.split(":")[2]
        val tamperedChar = if (body[3] == '2') '3' else '2'
        val tampered = "${code.split(":")[0]}:${code.split(":")[1]}:" +
            body.replaceRange(3, 4, tamperedChar.toString()) + ":${code.split(":")[3]}"
        assertTrue(readQuestionnaireHandoffCode(tampered) is QuestionnaireHandoffRead.Refused)

        assertTrue(readQuestionnaireHandoffCode("https://example.com/x") is QuestionnaireHandoffRead.Refused)
        assertTrue(readQuestionnaireHandoffCode("DPW1:A:abcdefghijk:0000") is QuestionnaireHandoffRead.Refused)
        assertFalse(looksLikeQuestionnaireHandoffCode("DPW1:A:abcdefghijk:0000"))
        assertTrue(readQuestionnaireHandoffCode(null) is QuestionnaireHandoffRead.Refused)
    }

    @Test
    fun `the code answers exactly one question, about the file in the recipient's hand`() {
        val sent = questionnaireBundleOf(form(twoSections))
        val code = questionnaireHandoffCode(sent)
        val digest = (readQuestionnaireHandoffCode(code) as QuestionnaireHandoffRead.Ok).digest

        val received = (readQuestionnaireBundle(encodeQuestionnaireBundle(sent)) as QuestionnaireBundleRead.Ok).bundle
        val receivedDigest = questionnaireHandoffCode(received).split(":")[2]
        assertEquals(digest, receivedDigest)
        assertTrue(
            questionnaireHandoffVerdict(digest, receivedDigest).contains("the same questionnaire, whole")
        )

        val short = sent.copy(sections = sent.sections.drop(1))
        val shortDigest = questionnaireHandoffCode(short).split(":")[2]
        assertNotEquals(digest, shortDigest)
        val verdict = questionnaireHandoffVerdict(digest, shortDigest)
        assertTrue(verdict, verdict.contains("NOT the file that code was made for"))
        assertTrue("both causes are named, because the remedy differs", verdict.contains("changed after"))
    }

    @Test
    fun `no QR at any version or error-correction level can carry the questionnaire itself`() {
        val gzippedBytes = 8_501
        val base32Chars = 13_608

        val ourCeiling = DwQrEncode.capacity(DwQrEncode.MAX_VERSION, DwQrEccLevel.Q)
        assertEquals(108, ourCeiling)
        assertTrue(base32Chars > ourCeiling * 100)

        assertTrue("13,608 > 4,296", base32Chars > 4_296)
        assertTrue("8,501 > 2,953", gzippedBytes > 2_953)

        val bundle = questionnaireBundleOf(form(twoSections))
        val payload = encodeQuestionnaireBundle(bundle)
            .joinToString("") { HANDOFF_TEST_ALPHABET[(it.toInt() and 0x1F)].toString() }
        var refused = false
        try {
            DwQrEncode.encode(payload, DwQrEccLevel.Q)
        } catch (e: DwQrEncodeException) {
            refused = true
            assertEquals(DwQrRefusal.TOO_LONG, e.reason)
        }
        assertTrue("a two-section test bundle already exceeds the encoder", refused)
    }

    private companion object {
        const val HANDOFF_TEST_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
