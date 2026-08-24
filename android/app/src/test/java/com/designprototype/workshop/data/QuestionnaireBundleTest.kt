package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * THE FILE ONE DESIGNER HANDS ANOTHER WITH NO SIGNAL AT EITHER END, AND WHAT IT IS NOT ALLOWED TO
 * CARRY.
 *
 * The transport is Android's own share sheet — nearby share, Bluetooth object push, a cable, a shared
 * folder — and the app writes no transport code at all. What the app owns is this format, and the
 * format is where every safety rule either holds or does not, because the bytes arrive from a device
 * this app knows nothing about and whoever sent them can edit the JSON.
 *
 * Four rules are pinned here, and each one is a thing that would be invisible on a handset:
 *
 *  1. ANSWERS DO NOT TRAVEL. Somebody else's responses about a named artisan must not leave their
 *     phone, and the way that is guaranteed is that the format has no field for them — a structural
 *     absence, not a filter that a later edit could widen.
 *  2. NOTHING IN THE FILE CAN NAME AN OWNER, AN ID OR A REVIEW STATUS. Same argument. A bundle
 *     carrying `recordedBy: "Priya"`, synced from Ravi's phone, produces a server record owned by
 *     Ravi and a screen that says Priya, and that divergence is undetectable afterwards.
 *  3. A DAMAGED OR TRUNCATED FILE IS REFUSED WHOLE. Bluetooth object push has no resume, so a
 *     half-sent file is the ordinary failure — and a half-read questionnaire is one with sections
 *     silently missing, which is indistinguishable from a questionnaire that never had them.
 *  4. THE QR CARRIES A FINGERPRINT AND NEVER THE QUESTIONNAIRE. See the last test in this file for
 *     the arithmetic, which forbids it at every QR version and every error-correction level.
 */
class QuestionnaireBundleTest {

    // ── Building one ───────────────────────────────────────────────────────────────────────────

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
        // The retired wording, the superseded wording, the blank one and the whole retired section
        // are all gone. A retired question keeps the answers already recorded against it and must
        // never collect new ones — reborn as a live question on somebody else's phone it would.
        val prompts = bundle.sections.flatMap { it.questions }.map { it.prompt }
        assertFalse(prompts.contains("Retired wording"))
        assertFalse(prompts.contains("Reworded away"))
        assertFalse(prompts.contains("Should not travel"))
        assertTrue(prompts.none { it.isBlank() })
        // Trimmed, because a prompt read out loud does not have leading spaces and a digest computed
        // over one would differ between two phones that both had "the same" questionnaire.
        assertEquals("Which tools does the workshop own?", bundle.sections[1].questions.single().prompt)
        assertEquals("For the fortnight in Nirona", bundle.description)
        assertEquals(7, bundle.sourceVersion)
    }

    @Test
    fun `no answer, no respondent, no owner, no id and no status is IN the format`() {
        // The DTO this was built from carries all of them — an owner, a server id, a sitting with a
        // named respondent, an answer, and the id of the designer who recorded it.
        val json = canonicalQuestionnaireBundleJson(questionnaireBundleOf(form(twoSections)))
        listOf(
            "Rekha Devi",          // a respondent's name
            "Her loom was her mother's", // somebody else's answer about her
            "usr_priya",           // an owner and an author
            "cq_server_side_id",   // a server id the receiver could be made to address
            "entry_1", "ans_1",    // a sitting and an answer id
            "dw_1",                // the SENDER's design workshop, which means nothing on this account
        ).forEach { forbidden ->
            assertFalse(
                "the peer format leaked `$forbidden`",
                json.contains(forbidden),
            )
        }
        // And structurally: there is no key for any of it, so a later widening of the mapping cannot
        // quietly start filling one in.
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
        // The shipped instrument measures 48,026 bytes as it ships, 29,178 compact and 8,501 gzipped —
        // 3.4x, because it is hundreds of short prompts with a repeating key vocabulary, which is
        // DEFLATE's best case. This pins the direction rather than the ratio, which depends on the
        // prompts: a bundle must be smaller gzipped than as JSON, or the encode step is doing nothing.
        val bundle = questionnaireBundleOf(form(twoSections))
        val json = canonicalQuestionnaireBundleJson(bundle).toByteArray(Charsets.UTF_8)
        val gz = encodeQuestionnaireBundle(bundle)
        assertTrue("gzip made it bigger: ${json.size} -> ${gz.size}", gz.size < json.size)
    }

    // ── Reading one that arrived from a device this app knows nothing about ─────────────────────

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
        // The remedy is the whole value of the sentence: Bluetooth and nearby sharing cannot resume,
        // so "ask for it again" is the only thing that works, and a designer who is not told that
        // will keep opening the same broken file.
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
        // The handset in the village is the client least likely to be up to date, so it is the one
        // that meets the newer file. It must say "update the app", not drop what it did not parse.
        assertTrue(read.message, read.message.contains("Update the app"))
    }

    @Test
    fun `an older build's file still reads, because the fields are defaulted`() {
        // The inverse of the rule above, and it matters as much: two designers in a courtyard are on
        // two different builds, and the OLDER one is allowed to send.
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
        // A section with no questions is the same nothing wearing a heading.
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
        // The raw file was 25 KB and would have inflated to 64 MB. A cap on the compressed side —
        // which the receiving path also has — does nothing about this one; the ceiling has to be
        // enforced DURING the inflation, which is what `readQuestionnaireBundle` does.
        val bomb = gzipOf("A".repeat(QUESTIONNAIRE_BUNDLE_MAX_INFLATED + 1024))
        assertTrue("a bomb should compress small", bomb.size < 100_000)
        assertEquals(QuestionnaireBundleRefusal.TOO_LARGE, refusalFor(bomb))
    }

    // ── The name it lands under ────────────────────────────────────────────────────────────────

    @Test
    fun `the filename is derived, never taken`() {
        assertEquals(
            "Cluster-survey-questions-v7.dpwq",
            questionnaireBundleFilename("Cluster survey", 7),
        )
        // A title is typed by a person and reaches a filesystem path. `../` in it is a write
        // somewhere nobody looked.
        assertFalse(questionnaireBundleFilename("../../etc/passwd", 1).contains(".."))
        assertFalse(questionnaireBundleFilename("a/b\\c", 1).contains("/"))
        assertEquals("questionnaire-questions-v1.dpwq", questionnaireBundleFilename("   ", 1))
        // Devanagari survives, because a questionnaire titled in Hindi is the ordinary case here and
        // a stripped title would land as "questionnaire" for every one of them.
        assertTrue(questionnaireBundleFilename("कारीगर सर्वेक्षण", 2).startsWith("कारीगर-सर्वेक्षण"))
        assertTrue(questionnaireBundleFilename("x".repeat(200), 1).length < 120)
    }

    /**
     * THE VERSION CANNOT SEPARATE TWO EDITIONS, so the digest in the name has to.
     *
     * The server bumps a questionnaire's version on supersede and on retire only — adding a question
     * does not move it — so "export, add three questions, export again" produced two files with the
     * same name and different contents. In Downloads, which is where a designer picks the file for
     * nearby share, those two are indistinguishable, and handing over the older one puts the
     * recipient's scan of a fresh code into the "this is NOT the file that code was made for" branch
     * over a transfer that was never at fault.
     */
    @Test
    fun `the digest is in the name, so two editions of one version cannot collide`() {
        val one = questionnaireBundleFilename("Cluster survey", 7, "8QK4T2WMZ0R")
        val two = questionnaireBundleFilename("Cluster survey", 7, "9ZZ11TTWMZ0R")
        assertEquals("Cluster-survey-questions-v7-8QK4T2WMZ0R.dpwq", one)
        assertTrue(one != two)
        // The same questions twice is the same name twice: the mark identifies contents, not calls.
        assertEquals(one, questionnaireBundleFilename("Cluster survey", 7, "8QK4T2WMZ0R"))
        // A digest read off a file another device wrote is untrusted like the rest of it, and this
        // string reaches a filesystem path.
        assertFalse(questionnaireBundleFilename("Survey", 1, "../../etc").contains(".."))
        assertFalse(questionnaireBundleFilename("Survey", 1, "a/b").contains("/"))
        // Blank leaves the name exactly as it was, so a caller with no bundle in hand still works.
        assertEquals("Survey-questions-v1.dpwq", questionnaireBundleFilename("Survey", 1, ""))
    }

    // ── Which deliveries this app looks at ─────────────────────────────────────────────────────

    @Test
    fun `a share-sheet delivery is recognised by our own subtype, and an open by its extension too`() {
        val send = "android.intent.action.SEND"
        val view = "android.intent.action.VIEW"
        assertTrue(isQuestionnaireBundleDelivery(send, QUESTIONNAIRE_BUNDLE_MIME, "x.dpwq"))
        assertTrue(isQuestionnaireBundleDelivery(send, "$QUESTIONNAIRE_BUNDLE_MIME; charset=utf-8", null))
        // SEND, our subtype only — the app must not appear on the share sheet for every
        // octet-stream anybody ever shares. That is a cost paid by people who will never use this.
        assertFalse(isQuestionnaireBundleDelivery(send, "application/octet-stream", null))
        // ...AND NOT EVEN WHEN IT IS NAMED RIGHT. This used to answer true, which made the function
        // strictly wider than the manifest SEND filter it claims to mirror exactly. Harmless in
        // practice because the manifest gates first — and that is precisely the problem: a mirror
        // nobody can trust is worse than no mirror, and the next person to widen the manifest would
        // have read this as licence.
        assertFalse(isQuestionnaireBundleDelivery(send, "application/octet-stream", "survey.dpwq"))
        // VIEW is already aimed at one file, so the broad types are allowed WITH the extension.
        assertTrue(isQuestionnaireBundleDelivery(view, "application/octet-stream", "survey.dpwq"))
        assertTrue(isQuestionnaireBundleDelivery(view, "application/gzip", "survey.DPWQ"))
        assertTrue(isQuestionnaireBundleDelivery(view, "application/x-gzip", "survey.dpwq?download=1"))
        // A TYPELESS delivery is refused, and refusing it costs nothing: an <intent-filter> that
        // declares a mimeType never matches an intent carrying none, so this case cannot arrive
        // through the manifest at all. A file manager offering a typeless Uri comes in through the
        // document picker, which does not consult this function.
        assertFalse(isQuestionnaireBundleDelivery(view, null, "survey.dpwq"))
        assertFalse(isQuestionnaireBundleDelivery(view, "image/jpeg", "survey.dpwq"))
        assertFalse(isQuestionnaireBundleDelivery(view, "application/octet-stream", "survey.zip"))
        assertFalse(isQuestionnaireBundleDelivery(null, QUESTIONNAIRE_BUNDLE_MIME, "x.dpwq"))
        assertFalse(isQuestionnaireBundleDelivery("android.intent.action.MAIN", QUESTIONNAIRE_BUNDLE_MIME, "x.dpwq"))
    }

    // ── The notices the screens are required to show ───────────────────────────────────────────

    @Test
    fun `the two notices say the three things that cannot be left unsaid`() {
        // Before sending: what is in the file.
        assertTrue(QUESTIONNAIRE_BUNDLE_CONTENTS_NOTICE.contains("no answers"))
        assertTrue(QUESTIONNAIRE_BUNDLE_CONTENTS_NOTICE.contains("no respondents' names"))
        // Before adopting: who will own the result. The server sets the owner from the bearer token,
        // which is exactly what makes the file safe — and exactly what the designer must be told,
        // because it means the questionnaire becomes theirs and not the sender's.
        assertTrue(QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE.contains("NEW questionnaire on your account"))
        assertTrue(QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE.contains("uploaded by you"))
        assertTrue(QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE.contains("no answers"))
    }

    // ── The QR ─────────────────────────────────────────────────────────────────────────────────

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
        // It has to fit inside the encoder that is already in the app, at the ECC level the cards
        // print at, with room left over — no new QR version and no new dependency.
        assertTrue(
            "the code is ${code.length} characters and the encoder holds " +
                "${DwQrEncode.capacity(DwQrEncode.MAX_VERSION, DwQrEccLevel.Q)}",
            code.length <= DwQrEncode.capacity(DwQrEncode.MAX_VERSION, DwQrEccLevel.Q),
        )
        // And it genuinely draws, rather than merely being short enough on paper.
        assertTrue(DwQrEncode.encode(code, DwQrEccLevel.Q).size > 0)
    }

    @Test
    fun `a scanned code round-trips, and a mistyped one is refused`() {
        val bundle = questionnaireBundleOf(form(twoSections))
        val code = questionnaireHandoffCode(bundle)
        val read = readQuestionnaireHandoffCode(code)
        assertTrue("$read", read is QuestionnaireHandoffRead.Ok)
        assertEquals(code.split(":")[2], (read as QuestionnaireHandoffRead.Ok).digest)

        // A camera and a person both make the same mistakes, and the alphabet excludes I, L and O so
        // that both can be corrected rather than refused.
        val spoken = code.replace('1', 'I').replace('0', 'O').lowercase()
        val fromSpoken = readQuestionnaireHandoffCode(" $spoken ")
        assertTrue("$fromSpoken", fromSpoken is QuestionnaireHandoffRead.Ok)
        assertEquals(read.digest, (fromSpoken as QuestionnaireHandoffRead.Ok).digest)

        // One character wrong is caught by the check block rather than silently comparing a different
        // file. `workshopCodeCheck` is the same function the record cards use.
        val body = code.split(":")[2]
        val tamperedChar = if (body[3] == '2') '3' else '2'
        val tampered = "${code.split(":")[0]}:${code.split(":")[1]}:" +
            body.replaceRange(3, 4, tamperedChar.toString()) + ":${code.split(":")[3]}"
        assertTrue(readQuestionnaireHandoffCode(tampered) is QuestionnaireHandoffRead.Refused)

        // Not one of ours at all, and a code of ours that is about something else, get different
        // sentences — a designer who scanned a shop barcode and a designer who scanned a join card
        // have made two different mistakes.
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

        // The same file, whole.
        val received = (readQuestionnaireBundle(encodeQuestionnaireBundle(sent)) as QuestionnaireBundleRead.Ok).bundle
        val receivedDigest = questionnaireHandoffCode(received).split(":")[2]
        assertEquals(digest, receivedDigest)
        assertTrue(
            questionnaireHandoffVerdict(digest, receivedDigest).contains("the same questionnaire, whole")
        )

        // A questionnaire that lost a section on the way over — the ordinary shape of a failed
        // Bluetooth push, and the one a decoder cannot notice on its own, because a shorter
        // questionnaire is a valid questionnaire.
        val short = sent.copy(sections = sent.sections.drop(1))
        val shortDigest = questionnaireHandoffCode(short).split(":")[2]
        assertNotEquals(digest, shortDigest)
        val verdict = questionnaireHandoffVerdict(digest, shortDigest)
        assertTrue(verdict, verdict.contains("NOT the file that code was made for"))
        assertTrue("both causes are named, because the remedy differs", verdict.contains("changed after"))
    }

    @Test
    fun `no QR at any version or error-correction level can carry the questionnaire itself`() {
        // MEASURED on `backend/app/data/questionnaire_questions.json` — 24 sections, 285 questions,
        // the instrument this app actually ships: 8,501 bytes gzipped, 13,608 characters once
        // base32-encoded into the alphanumeric mode a QR can hold.
        val gzippedBytes = 8_501
        val base32Chars = 13_608

        // This app's encoder, at the level the cards print at.
        val ourCeiling = DwQrEncode.capacity(DwQrEncode.MAX_VERSION, DwQrEccLevel.Q)
        assertEquals(108, ourCeiling)
        assertTrue(base32Chars > ourCeiling * 100)

        // And the ceiling of the STANDARD, so that raising MAX_VERSION is never proposed as the fix:
        // a version-40 symbol at ECC level L — the weakest correction anybody would print — holds
        // 4,296 alphanumeric characters or 2,953 bytes.
        assertTrue("13,608 > 4,296", base32Chars > 4_296)
        assertTrue("8,501 > 2,953", gzippedBytes > 2_953)

        // Demonstrated rather than only asserted: the encoder refuses the payload and accepts the
        // fingerprint, which is the whole design in two lines.
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
        /** The QR-safe alphabet, only to turn arbitrary bytes into an encodable string above. */
        const val HANDOFF_TEST_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
