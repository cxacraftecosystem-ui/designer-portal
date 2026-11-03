package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.QuestionnaireMergeConflictQuestion
import com.designprototype.workshop.data.QuestionnaireSaveRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionnaireMergeOfferTest {

    private fun duplicateSet(id: String? = "iv-d", title: String? = "D Black Pottery") =
        QuestionnaireSaveRefusal(
            message = "Another interview already covers exactly this set of artisans.",
            code = "duplicate_artisan_set",
            holderId = id,
            holderTitle = title,
        )

    @Test
    fun `the offer names the holder, in the heading and on the button`() {
        val offer = questionnaireMergeOffer(duplicateSet(), answeredQuestions = 4, pendingUploads = 0)
        assertNotNull(offer)
        offer!!
        assertEquals("iv-d", offer.targetId)
        assertEquals("D Black Pottery", offer.targetTitle)
        assertTrue(offer.heading.contains("D Black Pottery"))
        assertTrue(offer.confirmLabel.contains("D Black Pottery"))
    }

    @Test
    fun `the offer says what moves and that this interview is removed`() {
        val offer = questionnaireMergeOffer(duplicateSet(), answeredQuestions = 4, pendingUploads = 0)!!
        assertTrue(offer.body.contains("4 typed answers"))
        assertTrue(offer.body.contains("every saved recording"))
        assertTrue(offer.body.contains("removed"))
        assertTrue(offer.body.contains("nothing"))
    }

    @Test
    fun `one answer is not counted as answers`() {
        val offer = questionnaireMergeOffer(duplicateSet(), answeredQuestions = 1, pendingUploads = 0)!!
        assertTrue(offer.body.contains("1 typed answer,"))
    }

    @Test
    fun `clips still on the phone are promised out loud`() {
        val offer = questionnaireMergeOffer(duplicateSet(), answeredQuestions = 0, pendingUploads = 3)!!
        assertTrue(offer.body.contains("3 files still uploading"))

        val quiet = questionnaireMergeOffer(duplicateSet(), answeredQuestions = 0, pendingUploads = 0)!!
        assertTrue(!quiet.body.contains("still uploading"))
    }

    @Test
    fun `no holder id, no offer`() {
        assertNull(questionnaireMergeOffer(duplicateSet(id = null), answeredQuestions = 2, pendingUploads = 0))
    }

    @Test
    fun `a holder that cannot be named is not offered either`() {
        assertNull(questionnaireMergeOffer(duplicateSet(title = null), answeredQuestions = 2, pendingUploads = 0))
        assertNull(questionnaireMergeOffer(duplicateSet(title = "   "), answeredQuestions = 2, pendingUploads = 0))
    }

    @Test
    fun `some other refusal is never turned into a fold`() {
        val unrelated = QuestionnaireSaveRefusal(
            message = "Another interview already covers exactly this set of artisans.",
            code = null,
            holderId = "iv-d",
            holderTitle = "D Black Pottery",
        )
        assertNull(questionnaireMergeOffer(unrelated, answeredQuestions = 2, pendingUploads = 0))
    }

    @Test
    fun `every conflicting question is listed, not counted`() {
        val refusal = QuestionnaireSaveRefusal(
            message = "Nothing was moved. 2 question(s) are answered differently…",
            code = "merge_answer_conflict",
            conflicts = listOf(
                QuestionnaireMergeConflictQuestion("q-f3", "F", "How long have you worked at this?"),
                QuestionnaireMergeConflictQuestion("q-d1", "D", "Who taught you"),
            ),
        )
        val report = questionnaireMergeConflictReport(refusal)
        assertNotNull(report)
        report!!
        assertTrue(report.heading.contains("2 questions"))
        assertTrue(report.heading.contains("Nothing was moved"))
        assertEquals(
            listOf("F — How long have you worked at this?", "D — Who taught you"),
            report.questions,
        )
        assertTrue(report.remedy.contains("keep the wording that is right"))
    }

    @Test
    fun `one conflicting question is reported in the singular`() {
        val refusal = QuestionnaireSaveRefusal(
            message = "Nothing was moved.",
            code = "merge_answer_conflict",
            conflicts = listOf(QuestionnaireMergeConflictQuestion("q-f3", "F", "How long have you worked at this?")),
        )
        val report = questionnaireMergeConflictReport(refusal)!!
        assertTrue(report.heading.contains("1 question is"))
        assertEquals(1, report.questions.size)
    }

    @Test
    fun `a conflict refusal with no list prints the server's own sentence`() {
        val refusal = QuestionnaireSaveRefusal(
            message = "Nothing was moved. 2 question(s) are answered differently in “F” and “D”: …",
            code = "merge_answer_conflict",
        )
        val report = questionnaireMergeConflictReport(refusal)!!
        assertTrue(report.questions.isEmpty())
        assertEquals(refusal.message, report.remedy)
    }

    @Test
    fun `the other refusals are not dressed up as answer conflicts`() {
        assertNull(
            questionnaireMergeConflictReport(
                QuestionnaireSaveRefusal(message = "…filed under different workshops…", code = "merge_cross_scope")
            )
        )
        assertNull(questionnaireMergeConflictReport(QuestionnaireSaveRefusal(message = "Record not found")))
    }

    @Test
    fun `the success sentence names where the interview went`() {
        val said = questionnaireMergedSentence("D Black Pottery")
        assertTrue(said.contains("D Black Pottery"))
        assertTrue(said.contains("removed"))
    }
}
