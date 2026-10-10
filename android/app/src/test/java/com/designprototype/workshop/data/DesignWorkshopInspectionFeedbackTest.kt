package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * THE INSPECTOR'S NOTE ON THE HANDSET: the conflict rules, the refusals, the duplicate guard, the
 * review queue's split, and the store that keeps notes and reads on the phone.
 *
 * Every rule here decides whether an officer's sentence reaches a designer's desk, is held for its
 * author, or waits — and none of the three may ever be "gone". So each is pinned as a pure function
 * with no network, and the store is driven over a real directory.
 */
class DesignWorkshopInspectionFeedbackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun note(
        kind: DwInspectionNoteKind = DwInspectionNoteKind.SUGGESTION,
        round: Int = 2,
        status: String = "PRE_SUBMISSION",
        owner: String = "u-inspector",
        id: String = "n-1",
        workshop: String = "w-1",
        recordedAt: String = "2026-10-10T09:15:00.123Z",
        text: String = "Stage 4 names the wrong cluster.",
    ) = DwQueuedInspectionNote(
        id = id,
        workshopId = workshop,
        workshopTitle = "Kantha, Bolpur",
        kind = kind,
        note = text,
        recordedAt = recordedAt,
        ownerUserId = owner,
        draftedRound = round,
        draftedStatus = status,
    )

    // ── The conflict rule ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a note goes when the report is still the round it was written about`() {
        assertEquals(DwInspectionNoteCheck.Send, dwInspectionNoteCheck(note(), "PRE_SUBMISSION", 2))
    }

    @Test
    fun `a second officer's send-back on an already sent-back report still goes, as on the web`() {
        // The server answers this with a 200 and a row; holding it would lose a colleague's note.
        val check = dwInspectionNoteCheck(note(kind = DwInspectionNoteKind.SEND_BACK), "NEEDS_REVISION", 2)
        assertEquals(DwInspectionNoteCheck.Send, check)
    }

    @Test
    fun `a report withdrawn from review holds the note, says so plainly, and says nothing was sent`() {
        val check = dwInspectionNoteCheck(note(kind = DwInspectionNoteKind.SEND_BACK), "IN_PROGRESS", 2)
        assertTrue(check is DwInspectionNoteCheck.Hold)
        val sentence = (check as DwInspectionNoteCheck.Hold).sentence
        assertTrue(sentence, sentence.contains("moved on"))
        assertTrue(sentence, sentence.contains("in progress"))
        assertTrue(sentence, sentence.contains("Nothing was sent"))
        assertTrue(sentence, sentence.contains("kept here"))
    }

    @Test
    fun `a report handed in again since the note was written holds it rather than filing it on a round nobody read`() {
        val check = dwInspectionNoteCheck(note(round = 2), "PRE_SUBMISSION", 3)
        assertTrue(check is DwInspectionNoteCheck.Hold)
        val sentence = (check as DwInspectionNoteCheck.Hold).sentence
        assertTrue(sentence, sentence.contains("round 2"))
        assertTrue(sentence, sentence.contains("round 3"))
        assertTrue(sentence, sentence.contains("Nothing was sent"))
    }

    @Test
    fun `an approved report holds the note`() {
        assertTrue(dwInspectionNoteCheck(note(), "APPROVED", 2) is DwInspectionNoteCheck.Hold)
    }

    // ── What a send's failure means ─────────────────────────────────────────────────────────────

    @Test
    fun `no answer, a signed-out phone and a fault on the far side all wait rather than refuse`() {
        listOf(null, 401, 408, 429, 500, 502, 503).forEach { status ->
            assertEquals(
                "status $status must leave the note queued",
                DwInspectionSendOutcome.Wait,
                dwInspectionSendOutcome(status, "whatever", DwInspectionNoteKind.SUGGESTION)
            )
        }
    }

    @Test
    fun `the server's not-under-review refusal is said as the report moving on, and keeps the note`() {
        val said = "This report has not been handed in for inspection yet, so there is nothing to comment on."
        val outcome = dwInspectionSendOutcome(422, said, DwInspectionNoteKind.SEND_BACK)
        assertTrue(outcome is DwInspectionSendOutcome.Refused)
        val sentence = (outcome as DwInspectionSendOutcome.Refused).sentence
        assertTrue(sentence, sentence.contains("moved on before your send-back arrived"))
        assertTrue(sentence, sentence.contains("Nothing was filed, and your note is kept here."))
    }

    @Test
    fun `an ended assignment is a refusal that keeps the note`() {
        val outcome = dwInspectionSendOutcome(404, "Record not found", DwInspectionNoteKind.SUGGESTION)
        val sentence = (outcome as DwInspectionSendOutcome.Refused).sentence
        assertTrue(sentence, sentence.contains("no longer open to you"))
        assertTrue(sentence, sentence.contains("kept here"))
    }

    @Test
    fun `any other refusal passes the server's own sentence through, once`() {
        val said = "Comments are required when sending a record back for revision"
        val sentence = (dwInspectionSendOutcome(422, said, DwInspectionNoteKind.SEND_BACK) as DwInspectionSendOutcome.Refused).sentence
        assertTrue(sentence, sentence.startsWith("$said. "))
        assertEquals(1, Regex("kept here").findAll(sentence).count())
    }

    // ── A lost answer does not file twice ───────────────────────────────────────────────────────

    private fun row(actor: String, text: String, recordedAt: String?) = DwInspectionFeedbackDto(
        id = "f-${text.hashCode()}",
        round = 2,
        note = text,
        actorId = actor,
        recordedAt = recordedAt,
    )

    @Test
    fun `a note already on the register by its device moment is recognised, whatever the spelling of the moment`() {
        // `…Z` goes out, `…+00:00` with microseconds comes back.
        val register = listOf(row("u-inspector", "Stage 4 names the wrong cluster.", "2026-10-10T09:15:00.123000+00:00"))
        assertTrue(dwInspectionNoteAlreadyFiled(note(), register))
    }

    @Test
    fun `the same words by another officer, or at another moment, are not this note`() {
        assertFalse(
            dwInspectionNoteAlreadyFiled(note(), listOf(row("u-other", "Stage 4 names the wrong cluster.", "2026-10-10T09:15:00.123Z")))
        )
        assertFalse(
            dwInspectionNoteAlreadyFiled(note(), listOf(row("u-inspector", "Stage 4 names the wrong cluster.", "2026-10-10T09:16:00Z")))
        )
        assertFalse(
            dwInspectionNoteAlreadyFiled(note(), listOf(row("u-inspector", "Stage 4 names the wrong cluster.", null)))
        )
    }

    // ── The box ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an empty note and an over-long one are refused with a sentence, a real one is not`() {
        assertTrue(dwInspectionNoteProblem("   ")!!.isNotBlank())
        assertTrue(dwInspectionNoteProblem("x".repeat(DW_INSPECTION_NOTE_MAX + 1))!!.contains("$DW_INSPECTION_NOTE_MAX"))
        assertNull(dwInspectionNoteProblem("Stage 4 names the wrong cluster."))
    }

    @Test
    fun `the queued body carries the device moment and the stage, and never a round`() {
        val body = note().copy(stageKey = "STAGE_4").body()
        assertEquals("2026-10-10T09:15:00.123Z", body.recordedAt)
        assertEquals("STAGE_4", body.stageKey)
        assertEquals("Stage 4 names the wrong cluster.", body.note)
    }

    @Test
    fun `a write's answer replaces the register and the header, and keeps the stages`() {
        val read = DwInspectionDetailDto(
            id = "w-1",
            status = "PRE_SUBMISSION",
            submissionRound = 2,
            stages = mapOf("STAGE_1" to StageBucketDto()),
            mayRecordFeedback = true,
        )
        val answer = DwInspectionFeedbackAnswerDto(
            id = "w-1",
            status = "NEEDS_REVISION",
            submissionRound = 2,
            inspectionFeedback = listOf(row("u-inspector", "Fix it.", null)),
            mayRecordFeedback = true,
        )
        val merged = read.withFeedbackAnswer(answer)
        assertEquals("NEEDS_REVISION", merged.status)
        assertEquals(1, merged.inspectionFeedback.size)
        assertEquals(setOf("STAGE_1"), merged.stages.keys)
    }

    @Test
    fun `the register reads newest round first`() {
        val rounds = dwFeedbackRounds(
            listOf(
                DwInspectionFeedbackDto(id = "a", round = 1, note = "one"),
                DwInspectionFeedbackDto(id = "b", round = 3, note = "three"),
                DwInspectionFeedbackDto(id = "c", round = 1, note = "one again"),
            )
        )
        assertEquals(listOf(3, 1), rounds.map { it.round })
        assertEquals(listOf("a", "c"), rounds[1].rows.map { it.id })
    }

    @Test
    fun `a blank officer name is said as one and never guessed`() {
        assertEquals("An officer no longer named", dwFeedbackAuthor(DwInspectionFeedbackDto(actorName = " ")))
    }

    // ── The review queue ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the queue puts handed-in reports first, sent-back ones under them, and counts the rest`() {
        val queue = dwInspectionReviewQueue(
            listOf(
                DesignWorkshopDto(id = "a", status = "PRE_SUBMISSION"),
                DesignWorkshopDto(id = "b", status = "NEEDS_REVISION"),
                DesignWorkshopDto(id = "c", status = "IN_PROGRESS"),
                DesignWorkshopDto(id = "d", status = "pre_submission"),
                DesignWorkshopDto(id = "a", status = "PRE_SUBMISSION"),
            )
        )
        assertEquals(listOf("a", "d"), queue.awaitingYou.map { it.id })
        assertEquals(listOf("b"), queue.withDesigners.map { it.id })
        assertEquals(listOf("c"), queue.notHandedIn.map { it.id })
    }

    // ── The store ───────────────────────────────────────────────────────────────────────────────

    private fun store() = DwInspectionStore(File(folder.root, "inspections"))

    @Test
    fun `notes are kept, updated by id, and removed only when asked`() {
        val store = store()
        store.add(note(id = "n-1"))
        store.add(note(id = "n-2"))
        store.update(note(id = "n-1").copy(held = "moved on"))
        assertEquals(listOf("moved on", null), store.notes().map { it.held })
        // An update for a note no longer queued does not resurrect it.
        store.remove("n-2")
        store.update(note(id = "n-2"))
        assertEquals(listOf("n-1"), store.notes().map { it.id })
    }

    @Test
    fun `another account's notes and kept reads are never handed to this one`() {
        val store = store()
        store.add(note(owner = "u-someone-else"))
        assertTrue(store.notesFor("u-inspector").isEmpty())
        store.saveDetail(DwSavedInspection("u-someone-else", "2026-10-10T09:00:00Z", DwInspectionDetailDto(id = "w-1")))
        assertNull(store.detail("w-1", "u-inspector"))
        store.saveList(DwSavedInspectionList("u-someone-else", "2026-10-10T09:00:00Z", listOf(DesignWorkshopDto(id = "w-1")), 1))
        assertNull(store.list("u-inspector"))
    }

    @Test
    fun `an ended assignment takes its kept read and its list row with it`() {
        val store = store()
        store.saveDetail(DwSavedInspection("u-inspector", "2026-10-10T09:00:00Z", DwInspectionDetailDto(id = "w-1", title = "Kantha")))
        store.saveList(
            DwSavedInspectionList(
                "u-inspector",
                "2026-10-10T09:00:00Z",
                listOf(DesignWorkshopDto(id = "w-1"), DesignWorkshopDto(id = "w-2")),
                2
            )
        )
        assertEquals("Kantha", store.detail("w-1", "u-inspector")?.detail?.title)
        store.forgetWorkshop("w-1")
        assertNull(store.detail("w-1", "u-inspector"))
        assertEquals(listOf("w-2"), store.list("u-inspector")?.items?.map { it.id })
        assertEquals(1, store.list("u-inspector")?.total)
    }

    @Test
    fun `a damaged notes file is set aside, never emptied over`() {
        val dir = File(folder.root, "inspections").apply { mkdirs() }
        File(dir, "notes.json").writeText("{ not json")
        val store = DwInspectionStore(dir)
        assertTrue(store.notes().isEmpty())
        assertTrue(
            "the damaged bytes must still be on the phone",
            dir.listFiles().orEmpty().any { it.name.startsWith("notes.damaged-") && it.readText() == "{ not json" }
        )
        store.add(note())
        assertEquals(1, store.notes().size)
    }

    @Test
    fun `the kept-copy line names the day and says writing still works`() {
        val line = dwSavedInspectionSentence("2026-10-10T09:15:00.123Z")
        assertTrue(line, line.contains("2026-10-10 at 09:15"))
        assertTrue(line, line.contains("write suggestions"))
    }
}
