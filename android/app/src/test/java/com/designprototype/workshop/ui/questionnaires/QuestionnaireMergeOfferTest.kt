package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.QuestionnaireMergeConflictQuestion
import com.designprototype.workshop.data.QuestionnaireSaveRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE FOLD OFFER'S WORDS, AND THE DECISION OF WHETHER THERE IS AN OFFER AT ALL.
 *
 * ── THE RULING THIS SERVES (owner, 2026-09-20) ───────────────────────────────────────────────────
 *
 * Two researchers recorded ONE artisan set as TWO interviews, titled by the sections they covered.
 * The "F" sitting omitted an artisan; adding that artisan makes its artisan-set key equal the "D"
 * one's, and the correction died as a 409 with nowhere to go. The ruling: **let an edit fold, as a
 * create already does — explicitly, never silently**, and only on a confirmation that NAMES the
 * interview being folded into.
 *
 * ── WHY THESE ARE TESTS ──────────────────────────────────────────────────────────────────────────
 *
 * This is a confirmation whose button deletes a row, and every way of getting it wrong renders
 * perfectly:
 *
 *  - an offer made on the CODE alone posts to `merge-into/` with an empty path segment, so the
 *    researcher is promised a fold and handed a 404;
 *  - an offer that cannot name the holder asks somebody to delete an interview they cannot identify,
 *    which is precisely what the ruling forbids;
 *  - a dialog that says "move" without saying the source is removed hides the one irreversible
 *    consequence behind a verb;
 *  - a conflict refusal reporting a COUNT instead of the questions is a refusal nobody can act on —
 *    and the route named every question specifically so somebody could go and read both wordings.
 */
class QuestionnaireMergeOfferTest {

    private fun duplicateSet(id: String? = "iv-d", title: String? = "D Black Pottery") =
        QuestionnaireSaveRefusal(
            message = "Another interview already covers exactly this set of artisans.",
            code = "duplicate_artisan_set",
            holderId = id,
            holderTitle = title,
        )

    // ── 1. When there is an offer ────────────────────────────────────────────────────────────────

    @Test
    fun `the offer names the holder, in the heading and on the button`() {
        val offer = questionnaireMergeOffer(duplicateSet(), answeredQuestions = 4, pendingUploads = 0)
        assertNotNull(offer)
        offer!!
        assertEquals("iv-d", offer.targetId)
        assertEquals("D Black Pottery", offer.targetTitle)
        assertTrue(offer.heading.contains("D Black Pottery"))
        // NAMED ON THE BUTTON TOO. A confirm reading only "Move" asks somebody to delete a row on
        // the strength of a heading they have already scrolled past.
        assertTrue(offer.confirmLabel.contains("D Black Pottery"))
    }

    @Test
    fun `the offer says what moves and that this interview is removed`() {
        val offer = questionnaireMergeOffer(duplicateSet(), answeredQuestions = 4, pendingUploads = 0)!!
        assertTrue(offer.body.contains("4 typed answers"))
        assertTrue(offer.body.contains("every saved recording"))
        // THE IRREVERSIBLE HALF, IN THE CONFIRMATION ITSELF.
        assertTrue(offer.body.contains("removed"))
        // And the reassurance that goes with it, because "removed" without this reads as data loss.
        assertTrue(offer.body.contains("nothing"))
    }

    @Test
    fun `one answer is not counted as answers`() {
        val offer = questionnaireMergeOffer(duplicateSet(), answeredQuestions = 1, pendingUploads = 0)!!
        assertTrue(offer.body.contains("1 typed answer,"))
    }

    @Test
    fun `clips still on the phone are promised out loud`() {
        // The save was refused on its FIRST statement, so anything recorded this session is still
        // unlinked on this device. The caller uploads it onto this interview before the fold and the
        // route repoints it; a researcher who has just recorded a section needs to be told that.
        val offer = questionnaireMergeOffer(duplicateSet(), answeredQuestions = 0, pendingUploads = 3)!!
        assertTrue(offer.body.contains("3 files still uploading"))

        val quiet = questionnaireMergeOffer(duplicateSet(), answeredQuestions = 0, pendingUploads = 0)!!
        assertTrue(!quiet.body.contains("still uploading"))
    }

    // ── 2. When there is none ────────────────────────────────────────────────────────────────────

    @Test
    fun `no holder id, no offer`() {
        // `duplicate_set_detail` answers null ids for a holder deleted between the violation and its
        // read. Offering anyway would draw a confirm button that posts to `merge-into/`.
        assertNull(questionnaireMergeOffer(duplicateSet(id = null), answeredQuestions = 2, pendingUploads = 0))
    }

    @Test
    fun `a holder that cannot be named is not offered either`() {
        // The ruling is that the fold happens only on a confirmation that NAMES it. "Move this one
        // into the other one" is not that, so the refusal's own sentence is printed instead.
        assertNull(questionnaireMergeOffer(duplicateSet(title = null), answeredQuestions = 2, pendingUploads = 0))
        assertNull(questionnaireMergeOffer(duplicateSet(title = "   "), answeredQuestions = 2, pendingUploads = 0))
    }

    @Test
    fun `some other refusal is never turned into a fold`() {
        // A 403, a 422, a plain-string 409 from an older API. Branching on prose rather than on the
        // code is how an unrelated refusal grows a button that deletes an interview.
        val unrelated = QuestionnaireSaveRefusal(
            message = "Another interview already covers exactly this set of artisans.",
            code = null,
            holderId = "iv-d",
            holderTitle = "D Black Pottery",
        )
        assertNull(questionnaireMergeOffer(unrelated, answeredQuestions = 2, pendingUploads = 0))
    }

    // ── 3. The merge's own refusal, listed ───────────────────────────────────────────────────────

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
        // An API older than the `conflicts` key, or a body something rewrote in transit. The sentence
        // already names the questions, run together; losing it to an empty dialog would be worse.
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
        // `merge_cross_scope` and `merge_artisan_coverage` are 422s carrying complete sentences of
        // their own; a null here is what sends them to the plain message channel.
        assertNull(
            questionnaireMergeConflictReport(
                QuestionnaireSaveRefusal(message = "…filed under different workshops…", code = "merge_cross_scope")
            )
        )
        assertNull(questionnaireMergeConflictReport(QuestionnaireSaveRefusal(message = "Record not found")))
    }

    // ── 4. After the fold ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the success sentence names where the interview went`() {
        // The screen navigates away and the row they were editing is gone; without this the interview
        // simply vanishes from the list they come back to.
        val said = questionnaireMergedSentence("D Black Pottery")
        assertTrue(said.contains("D Black Pottery"))
        assertTrue(said.contains("removed"))
    }
}
