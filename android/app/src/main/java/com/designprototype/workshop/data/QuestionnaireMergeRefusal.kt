package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException

/*
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE TWO 409s THE QUESTIONNAIRE FORM CAN DO SOMETHING ABOUT — read once, into facts
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ── THE DEFECT THIS CLOSES ─────────────────────────────────────────────────────────────────────────
 *
 * Two researchers recorded ONE artisan set as TWO interviews, each titled by the sections it covered
 * — "D Black Pottery" and an "F" one. The F sitting omitted an artisan. Adding that artisan makes F's
 * artisan-set key EQUAL D's, the unique index fires, and until now the correction died on this
 * handset as the literal string **"HTTP 409 Conflict"**: `QuestionnaireForm`'s save handler prints
 * `it.message`, which for a Retrofit failure is the status line and nothing else. The researcher was
 * told a number, about a refusal whose whole point is that it knows WHICH interview is in the way.
 *
 * `backend/app/api/routes/questionnaire.py::duplicate_set_detail` now answers with the half a client
 * could not have — the holder's id and title — precisely so the phone can offer to FOLD:
 * *"D Black Pottery already covers this set. Move this interview's sections and recordings into it?"*
 * That offer is the reason this file exists. Without the id there is nothing to call
 * `POST /questionnaire/interviews/{id}/merge-into/{targetId}` with, and with only prose there is no
 * sentence to put the holder's name into.
 *
 * ── WHY ONE FUNCTION AND NOT TWO ───────────────────────────────────────────────────────────────────
 *
 * ⚠ **RETROFIT BUFFERS THE ERROR BODY AND `string()` CONSUMES THE BUFFER.** This is stated three
 * times in `WorkshopRepository` (`ApiRefusal`, `serverDetail`, `dictationCapRefusal`) because it is
 * the trap every second reader of an error body falls into: asking the same exception two questions
 * silently answers the second one with nothing. So "what shall I print?" and "may I offer a merge?"
 * come out of ONE read, in [QuestionnaireSaveRefusal], and a caller asks this exactly once per
 * failure — exactly as it would have asked [apiErrorMessage] once.
 *
 * ── THE CODES ARE THE SERVER'S OWN, COPIED, NOT INVENTED ───────────────────────────────────────────
 *
 * Every constant below is checked against `backend/app/api/routes/questionnaire.py` rather than
 * remembered. Branching on prose would break the moment somebody rewords a sentence written for a
 * person to read, which is the discipline `_DUPLICATE_SET_CODE`'s own comment states.
 */

/** `_DUPLICATE_SET_CODE` — a PATCH refused because another interview already holds this artisan set. */
const val QUESTIONNAIRE_DUPLICATE_SET_CODE = "duplicate_artisan_set"

/** `_MERGE_CONFLICT_CODE` — a merge refused because both rows answer a question differently. */
const val QUESTIONNAIRE_MERGE_CONFLICT_CODE = "merge_answer_conflict"

/**
 * ONE QUESTION THE TWO INTERVIEWS ANSWER DIFFERENTLY, as the merge route named it.
 *
 * The route refuses rather than guessing a winner — *"Silently picking a winner would destroy a
 * researcher's words under a 200"* — and NAMES every such question so the person can go and read
 * both. A client that printed only the count would be handing back a refusal nobody can act on.
 *
 * [prompt] and [sectionCode] are nullable because the server reads them off an included relation and
 * a row whose question has since been deleted yields null for both; [questionId] is the one field
 * always present, and [label] falls back to it so a line is never blank.
 */
data class QuestionnaireMergeConflictQuestion(
    val questionId: String,
    val sectionCode: String?,
    val prompt: String?,
) {
    /** "F3 — How many hours…", or the bare id when the server could not name the question. */
    val label: String
        get() {
            val code = sectionCode?.trim()?.takeIf { it.isNotEmpty() }
            val text = prompt?.trim()?.takeIf { it.isNotEmpty() } ?: questionId
            return if (code == null) text else "$code — $text"
        }
}

/**
 * A refused questionnaire save, read out of the error body ONCE.
 *
 * [message] is ALWAYS something a person can read: the server's own sentence where it sent one, the
 * exception's text otherwise, and only then the caller's fallback. Everything else is the
 * machine-readable half, empty on every refusal that did not carry it — which is exactly what a
 * client talking to an API older than this feature sees, and why the fallback arm is not an error
 * path but the ordinary one.
 */
data class QuestionnaireSaveRefusal(
    /** The sentence to put on screen. Never blank. */
    val message: String,
    /** The server's machine-readable tag, or null when the body carried none. */
    val code: String? = null,
    /** `existingInterviewId` — the interview already holding this artisan set. */
    val holderId: String? = null,
    /** `existingInterviewTitle` — the holder's title, for the sentence that names it. */
    val holderTitle: String? = null,
    /** `conflicts` — every question the two rows answer differently. Empty unless [isAnswerConflict]. */
    val conflicts: List<QuestionnaireMergeConflictQuestion> = emptyList(),
) {
    /**
     * Was this the artisan-set clash a merge can resolve?
     *
     * The CODE alone, not the presence of a holder: a holder that vanished between the violation and
     * the server's read yields null ids on a body that is still this refusal (`duplicate_set_detail`
     * documents that case and keeps the keys stable for it). The offer is what needs the id, and
     * `questionnaireMergeOffer` is where that is decided.
     */
    val isDuplicateSet: Boolean get() = code == QUESTIONNAIRE_DUPLICATE_SET_CODE

    /** Did a merge refuse because both interviews answer the same question differently? */
    val isAnswerConflict: Boolean get() = code == QUESTIONNAIRE_MERGE_CONFLICT_CODE
}

/** Reader for API error bodies only — lenient, because a failing server can return anything. */
private val refusalJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * This failure, as the facts the questionnaire form branches on. **Call once per failure.**
 *
 * ── WHY 409 IS THE ONLY CODE THAT TAKES THE LONG PATH ──────────────────────────────────────────────
 *
 * `code()` can be asked without touching the body, so the branch is free, and both refusals this file
 * is about are 409s. Everything else — a 403 on somebody else's record, a 422 naming a field, a 504
 * from the gateway — is handed to [apiErrorMessage], which already unwraps FastAPI's three `detail`
 * shapes INCLUDING the pydantic validation list. Re-implementing that list here to serve one screen
 * is how two readings of one error body start disagreeing; deferring to it costs one read, the same
 * one this function would have spent.
 *
 * A NON-`HttpException` NEVER TOUCHES EITHER: no connection, a timeout, a serialization fault. The
 * platform's own message is the only thing that knows what happened and it is more informative than
 * anything this function could invent — the same reading `apiRefusal` takes at the same branch.
 */
fun Throwable.questionnaireSaveRefusal(fallback: String): QuestionnaireSaveRefusal {
    val plain = message?.takeIf { it.isNotBlank() } ?: fallback
    val http = this as? HttpException ?: return QuestionnaireSaveRefusal(message = plain)
    if (http.code() != 409) return QuestionnaireSaveRefusal(message = apiErrorMessage(fallback))
    val raw = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    return questionnaireSaveRefusal(raw, plain)
}

/**
 * The same reading, of a body a caller already holds as a String — and the form the tests pin.
 *
 * Separated from the extension above for the reason `QuestionnaireTranscripts` gives for existing at
 * all: every judgement here can be wrong in a way that renders perfectly. A missing `existingInterviewId`
 * silently turns the merge offer into a dead end, and a body parsed into the wrong branch prints a
 * refusal about the wrong thing — neither is visible in a diff, and neither is reachable from a JVM
 * test while it is wrapped around a Retrofit exception nobody can construct honestly.
 *
 * ⚠ **A BLANK STRING IS NOT AN ID.** `duplicate_set_detail` answers `None` for a holder that has
 * vanished, which decodes to a JSON null and then to Kotlin null — but a server that ever sent `""`
 * would otherwise produce an offer whose confirm button calls `merge-into/` with an empty path
 * segment. Blanks are normalised away here, once, rather than at each of the three call sites that
 * would have to remember.
 */
fun questionnaireSaveRefusal(rawBody: String?, fallback: String): QuestionnaireSaveRefusal {
    if (rawBody.isNullOrBlank()) return QuestionnaireSaveRefusal(message = fallback)
    val detail = (runCatching { refusalJson.parseToJsonElement(rawBody) }.getOrNull() as? JsonObject)
        ?.get("detail")
    // A 409 whose body is a plain string detail, or is not FastAPI's at all: there is nothing to
    // branch on, so it is a sentence and only a sentence — which is what every client did before
    // these codes existed and remains correct against an older API.
    val obj = detail as? JsonObject
        ?: return QuestionnaireSaveRefusal(
            message = (detail as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        )
    fun text(key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    return QuestionnaireSaveRefusal(
        message = text("message") ?: fallback,
        code = text("code"),
        holderId = text("existingInterviewId"),
        holderTitle = text("existingInterviewTitle"),
        conflicts = (obj["conflicts"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            fun field(key: String): String? =
                (row[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            // WITHOUT AN ID THERE IS NO QUESTION. A row the server could not identify would print as
            // a bullet reading "?" and send a researcher looking for a question that is not named.
            field("questionId")?.let {
                QuestionnaireMergeConflictQuestion(
                    questionId = it,
                    sectionCode = field("sectionCode"),
                    prompt = field("prompt"),
                )
            }
        },
    )
}
