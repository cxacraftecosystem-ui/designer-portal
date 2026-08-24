package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.util.Locale

/**
 * THE TWO SENTENCES THAT DECIDE WHETHER A DESIGNER ENDS UP WITH ONE QUESTIONNAIRE OR TWO.
 *
 * Adopting a received file is not one request. There is no bulk JSON create route — the only bulk
 * import the API has takes an .xlsx, which this handset cannot build — so the 24-section instrument
 * costs one POST for the questionnaire, one per section and one per question: 310 requests on a field
 * connection, where the chance of all 310 landing is not one.
 *
 * The state that matters is therefore the MIDDLE one, and it is the state a designer meets. If the
 * row says "not added" while half the sections are already on their account, they will start again
 * and finish with two copies of a colleague's instrument, each missing a different half. So the
 * sentence has to say that the questionnaire already exists and that carrying on is the way out.
 *
 * The second sentence belongs to the offline READ of the custom questionnaire, which is a different
 * feature reached by the same designer on the same day: it is the notice above a form served from
 * this device's own copy, and its whole job is to say — before the questions are asked out loud, not
 * after a section has been filled in — that answers cannot be saved from it.
 */
class ReceivedQuestionnaireTest {

    private fun row(
        remoteId: String? = null,
        sectionsDone: Int = 0,
        adoptedAt: String? = null,
        failure: String? = null,
    ) = ReceivedQuestionnaire(
        id = "r1",
        filename = "cluster-survey-questions-v7.dpwq",
        receivedAt = "2026-08-24T09:00:00Z",
        title = "Cluster survey",
        sectionCount = 24,
        questionCount = 285,
        sourceVersion = 7,
        localPath = "/data/x/r1.dpwq",
        remoteId = remoteId,
        sectionsDone = sectionsDone,
        adoptedAt = adoptedAt,
        failure = failure,
    )

    @Test
    fun `a file that has not been adopted says what is in it`() {
        val text = receivedQuestionnaireStatus(row())
        assertTrue(text, text.contains("Not added yet"))
        assertTrue(text, text.contains("285 question(s) in 24 section(s)"))
        assertFalse(row().started)
        assertFalse(row().adopted)
    }

    @Test
    fun `a half-adopted file tells the designer NOT to start again`() {
        val partial = row(remoteId = "cq_new", sectionsDone = 9)
        val text = receivedQuestionnaireStatus(partial)
        assertTrue(partial.started)
        assertFalse(partial.adopted)
        assertTrue(text, text.contains("9 of 24 sections are on your account"))
        // This is the sentence that prevents the duplicate. Without it, "in progress" reads as
        // "nothing happened yet" to somebody whose connection dropped an hour ago.
        assertTrue(text, text.contains("do not start again"))
        assertTrue(text, text.contains("two copies"))
    }

    /**
     * THE WINDOW IN WHICH A SECTION EXISTS AND THE FILE SAYS IT DOES NOT.
     *
     * `currentSectionRemoteId` promises "so a resume does not create it twice", and between the POST
     * landing and the write of the returned id there is a moment where it is null and the section is
     * real. A low-memory kill there — the ordinary event during a 310-request adoption, not an exotic
     * one — sent the resume back down the create branch and left a permanent empty duplicate section
     * in somebody's instrument. `sectionCreateStarted` is what the resume reads to know it must LOOK
     * before it creates, and it must default to false so a row written by the previous build reads as
     * "no request in flight", which is the reading that was in force before the field existed.
     */
    @Test
    fun `a row remembers that a section create was already attempted`() {
        assertFalse(row().sectionCreateStarted)
        val inFlight = row(remoteId = "cq_new", sectionsDone = 3).copy(sectionCreateStarted = true)
        assertTrue(inFlight.sectionCreateStarted)
        assertNull(inFlight.currentSectionRemoteId)
        // Decoded from a file an earlier build wrote, i.e. with the key absent entirely.
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<ReceivedQuestionnaire>(
            """{"id":"r1","filename":"x.dpwq","receivedAt":"2026-08-24T09:00:00Z","remoteId":"cq_1"}"""
        )
        assertFalse(old.sectionCreateStarted)
        assertTrue(old.started)
    }

    @Test
    fun `a stopped adoption carries the server's own reason`() {
        val text = receivedQuestionnaireStatus(row(remoteId = "cq_new", failure = "A section title was too long."))
        assertTrue(text, text.startsWith("Stopped: "))
        assertTrue(text, text.contains("A section title was too long."))
    }

    @Test
    fun `a finished adoption is finished, and says when`() {
        val done = row(remoteId = "cq_new", sectionsDone = 24, adoptedAt = "2026-08-24T11:15:00Z")
        assertTrue(done.adopted)
        val text = receivedQuestionnaireStatus(done)
        assertTrue(text, text.contains("Added to your questionnaires"))
        // WHEN, but not as a machine stamp. This used to print
        // "Added to your questionnaires on 2026-08-24T11:15:00Z" — a UTC instant, to a designer in a
        // courtyard five and a half hours away from UTC. See `readableStamp`.
        assertFalse(text, text.contains("2026-08-24T11:15:00Z"))
        assertTrue(text, text.contains(readableStamp("2026-08-24T11:15:00Z")!!))
        // A failure recorded by an earlier attempt must not outrank the success that followed it.
        val recovered = receivedQuestionnaireStatus(
            row(remoteId = "cq_new", sectionsDone = 24, adoptedAt = "2026-08-24T11:15:00Z", failure = "old failure")
        )
        assertFalse(recovered, recovered.contains("old failure"))
    }

    @Test
    fun `the offline questionnaire notice refuses to imply an answer can be saved`() {
        val notice = cachedQuestionnaireNotice("2026-08-22T07:30:00Z", version = 7)
        assertTrue(notice, notice.contains("the copy this phone downloaded"))
        // WHEN, because a designer whose colleague says "I added four questions yesterday" needs to
        // find out that this copy predates them — and READABLY, in this device's own time of day,
        // because that is the comparison being made. The raw UTC instant answered it wrongly by five
        // and a half hours in the field.
        assertFalse(notice, notice.contains("2026-08-22T07:30:00Z"))
        assertTrue(notice, notice.contains(readableStamp("2026-08-22T07:30:00Z")!!))
        assertTrue(notice, notice.contains("version 7"))
        // AND THE REFUSAL, in the loudest words in the sentence. The read is now offline; the write
        // is exactly as refused as it was, and this is where that is said.
        assertTrue(notice, notice.contains("ANSWERS CANNOT BE SAVED"))
        // A copy with no recorded fetch time still reads as a sentence rather than as a gap.
        val undated = cachedQuestionnaireNotice(null, version = 2)
        assertFalse(undated, undated.contains(" on ("))
        assertTrue(undated, undated.contains("ANSWERS CANNOT BE SAVED"))
    }

    /**
     * The stamp itself, with the zone and the locale HANDED IN so this is pinned by value rather than
     * by whatever the machine running the suite is set to.
     */
    @Test
    fun `a stored instant is shown in this device's own time, and an unreadable one is not hidden`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        // 09:12 UTC is 14:42 in the courtyard. That five-and-a-half-hour gap is the whole reason this
        // function exists: the stamp is read to decide "is this copy older than this morning's edit".
        // Lower-cased on both sides: whether the JDK's CLDR data writes "pm" or "PM" for en-GB has
        // moved between releases and is not what this test is about.
        assertEquals(
            "24 aug 2026, 02:42 pm",
            readableStamp("2026-08-24T09:12:33.221Z", kolkata, Locale.UK)?.lowercase(),
        )
        // An offset form, which is what the server writes, reads the same as the instant form.
        assertEquals(
            readableStamp("2026-08-24T09:12:33Z", kolkata, Locale.UK),
            readableStamp("2026-08-24T14:42:33+05:30", kolkata, Locale.UK),
        )
        // NOTHING is not a dash and not a crash.
        assertNull(readableStamp(null, kolkata, Locale.UK))
        assertNull(readableStamp("   ", kolkata, Locale.UK))
        // AND A STAMP WE CANNOT READ COMES BACK UNCHANGED. It arrived in a file another handset wrote,
        // so it is untrusted input; blanking it would hide the only clue about what produced it.
        assertEquals("last tuesday", readableStamp("last tuesday", kolkata, Locale.UK))
    }
}
