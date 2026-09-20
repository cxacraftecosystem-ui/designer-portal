package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.QuestionnaireSaveRefusal

/*
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE WORDS OF THE MERGE OFFER, AND THE DECISION OF WHETHER THERE IS ONE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ── THE RULING (owner, 2026-09-20) ─────────────────────────────────────────────────────────────────
 *
 * An edit may fold into the interview already holding an artisan set, as a create already does —
 * **explicitly, never silently**, and *"only on a confirmation that names it"*. The server's half is
 * `duplicate_set_detail` (which now sends the holder) and
 * `POST /questionnaire/interviews/{id}/merge-into/{targetId}` (which does the fold and refuses to
 * guess). This file is the sentence the researcher reads before saying yes, and the rule for when
 * there is no yes to be had.
 *
 * ── WHY THE COPY IS HERE AND NOT AT THE DIALOG ─────────────────────────────────────────────────────
 *
 * Same reason as `QuestionnaireTranscripts` and `AccessRefusalCopy`: this is a confirmation whose
 * button DELETES A ROW. Every way of getting it wrong renders perfectly — an offer made when the
 * server did not name a holder (a confirm button that calls `merge-into//` and 404s), a dialog that
 * says "move" without saying the source is removed, a conflict refusal that reports a number instead
 * of the questions. None of that is visible in a diff or on screen, and there is no Compose renderer
 * in this project's unit tests, so the judgements live where `QuestionnaireMergeOfferTest` can call
 * them.
 */

/**
 * An offer to fold this interview into the one already holding its artisan set.
 *
 * [targetId] and [targetTitle] are the two things a confirmation needs: something to call, and
 * something to name. See [questionnaireMergeOffer] for why an offer exists only when both arrived.
 */
data class QuestionnaireMergeOffer(
    val targetId: String,
    val targetTitle: String,
    val heading: String,
    val body: String,
    val confirmLabel: String,
)

/**
 * The offer this refusal makes possible, or null when the only honest response is the sentence.
 *
 * ⚠ **NO HOLDER, NO OFFER — and the title is as load-bearing as the id.** `duplicate_set_detail`
 * keeps both keys present but answers null for a holder that has vanished between the unique
 * violation and its read, so a client that offered on the CODE alone would draw a confirm button
 * that posts to `merge-into/` with an empty path segment. And the ruling is that the fold happens
 * *"only on a confirmation that names it"*: an offer reading "move this into the other one" asks a
 * researcher to delete a row they cannot identify. Either absence falls back to [QuestionnaireSaveRefusal.message],
 * which is the complete sentence every client printed before these keys existed.
 *
 * ── WHAT THE BODY PROMISES, AND WHY EACH CLAUSE IS IN IT ───────────────────────────────────────────
 *
 *  - **The count of typed answers** is [answeredQuestions] — computed from the form, not from the
 *    refusal, because the server's 409 is about the artisan roster and says nothing about answers.
 *  - **"every saved recording"** WITHOUT A NUMBER, deliberately. The screen's `savedMedia` spans
 *    every interview covering the same artisan set (that is what makes a sibling's recording visible
 *    here at all), so its size is not this interview's recording count and printing it would be a
 *    precise-looking lie. The route moves every `MediaFile` pointing at this row, which is what the
 *    clause says.
 *  - **[pendingUploads]**, when there are any, because those clips are still on this phone: the save
 *    was refused before they were linked, so the caller uploads them onto THIS interview first and
 *    the merge then repoints them. Said out loud because a researcher who has just recorded a
 *    section needs to know it is coming with them.
 *  - **"This interview is then removed"**, in the confirmation itself. It is the one irreversible
 *    consequence, and a dialog that only said "move" would be hiding it behind a verb.
 */
fun questionnaireMergeOffer(
    refusal: QuestionnaireSaveRefusal,
    answeredQuestions: Int,
    pendingUploads: Int,
): QuestionnaireMergeOffer? {
    if (!refusal.isDuplicateSet) return null
    val id = refusal.holderId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val title = refusal.holderTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val moving = buildList {
        add(if (answeredQuestions == 1) "1 typed answer" else "$answeredQuestions typed answers")
        add("every saved recording")
        if (pendingUploads > 0) {
            add(if (pendingUploads == 1) "the 1 file still uploading" else "the $pendingUploads files still uploading")
        }
    }
    return QuestionnaireMergeOffer(
        targetId = id,
        targetTitle = title,
        heading = "“$title” already covers these artisans",
        body = "Two interviews cannot cover the same set of artisans. Move this interview's " +
            "${moving.joinToString(", ")} into “$title”? This interview is then removed — nothing " +
            "recorded in it is deleted.",
        confirmLabel = "Move into “$title”",
    )
}

/**
 * A merge the server refused because both interviews answer the same question differently.
 *
 * [questions] is the whole point. The route names every conflicting question precisely so a person
 * can go and read both wordings — *"Silently picking a winner would destroy a researcher's words
 * under a 200"* — and a client that printed the count alone would hand back a refusal nobody can act
 * on.
 */
data class QuestionnaireMergeConflictReport(
    val heading: String,
    val questions: List<String>,
    val remedy: String,
)

/**
 * The refusal above, as a heading and a list — or null when this was not that refusal.
 *
 * **THE SERVER'S OWN SENTENCE IS NOT REPRINTED, AND THAT IS THE ONE DEPARTURE HERE.** It already
 * names every conflicting question, joined with semicolons, inside one sentence: on a browser that
 * is a readable paragraph, and on a handset five two-thousand-character prompts in one run-on line
 * is a wall nobody reads to the end of. The count, the instruction and the wording are the server's;
 * only the layout is this client's, and the fallback below prints the server's sentence verbatim
 * whenever the structured list is missing — an API older than the `conflicts` key, or a body
 * something rewrote in transit.
 */
fun questionnaireMergeConflictReport(refusal: QuestionnaireSaveRefusal): QuestionnaireMergeConflictReport? {
    if (!refusal.isAnswerConflict) return null
    val labels = refusal.conflicts.map { it.label }
    if (labels.isEmpty()) {
        return QuestionnaireMergeConflictReport(
            heading = "Nothing was moved.",
            questions = emptyList(),
            remedy = refusal.message,
        )
    }
    return QuestionnaireMergeConflictReport(
        heading = if (labels.size == 1) {
            "Nothing was moved. 1 question is answered differently in the two interviews:"
        } else {
            "Nothing was moved. ${labels.size} questions are answered differently in the two interviews:"
        },
        questions = labels,
        remedy = "Open each one, keep the wording that is right, then merge.",
    )
}

/**
 * What to tell the researcher once the fold has happened.
 *
 * SAID AT ALL because this form navigates away on success exactly as a save does, and the row they
 * were editing no longer exists. Without a sentence the interview would simply be gone from the list
 * they return to, which is indistinguishable from having deleted it by accident.
 */
fun questionnaireMergedSentence(targetTitle: String): String =
    "Moved into “$targetTitle”. Its answers and recordings are now on that interview, and this one " +
        "has been removed."
