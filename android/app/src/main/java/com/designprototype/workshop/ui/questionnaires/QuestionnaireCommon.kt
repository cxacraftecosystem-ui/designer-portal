package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.CustomEntryDto
import com.designprototype.workshop.data.CustomQuestionnaireDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.WorkshopListState
import com.designprototype.workshop.ui.field

/*
 * Shared by the three custom-questionnaire screens: the list, the form, and the answer sheet.
 *
 * THE ONE THING WORTH READING BEFORE THE SCREENS. A designer builds their questionnaire in the .xlsx
 * pro-forma on a laptop and ANSWERS it on this handset. So the screens here are weighted for the
 * answering: the phone's job is the one a designer does sitting in a room with the person answering,
 * and the editor is what they reach for afterwards to correct a wording. The upload half of the API
 * is not wired to this client at all — see the note in WorkshopRepositoryApi.kt.
 */

/**
 * May this account change the QUESTIONS on this questionnaire?
 *
 * Mirrors `_require_owner` in backend/app/api/routes/questionnaire_forms.py: the designer who created
 * it, or an admin. Everybody with design-workshop access can still READ it and RECORD ANSWERS against
 * it — that split is deliberate on the server ("what lets a designer hand a colleague a form to fill
 * in without also handing them the ability to reword it halfway through the fieldwork"), and a client
 * that collapsed the two would either hide the answer sheet from the colleague the form was handed to
 * or put an editor in front of them that answers 403 on every save.
 *
 * This hides EDITING CONTROLS, never data. The endpoint behind each of them is checked again on the
 * server, so this is an honesty measure — do not add a guard here over anything that is only readable.
 */
internal fun mayEditQuestionnaire(repository: WorkshopRepository, ownerId: String?): Boolean {
    val user = repository.cachedUser() ?: return false
    if (FieldPermissions.isAdmin(user)) return true
    return !ownerId.isNullOrBlank() && ownerId == user.id
}

/** The signed-in account's id, for deciding whose answers this device may overwrite. */
internal fun signedInUserId(repository: WorkshopRepository): String? = repository.cachedUser()?.id

internal fun isAdminAccount(repository: WorkshopRepository): Boolean =
    repository.cachedUser()?.let { FieldPermissions.isAdmin(it) } ?: false

/**
 * A small status pill.
 *
 * Hand-drawn rather than an `AssistChip` because these carry no action: an M3 chip is a control, and
 * a row of controls that do nothing when pressed is a row of dead ends on the screen where a designer
 * is looking for what they may still change.
 */
@Composable
internal fun QChip(text: String, container: Color, content: Color) {
    Text(
        text = text,
        modifier = Modifier
            .background(container, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = content,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium
    )
}

/** How many of [total] questions this sitting has a non-blank answer for. */
internal fun answeredCount(entry: CustomEntryDto): Int =
    entry.answers.count { !it.answerText.isNullOrBlank() }

/**
 * The line under a sitting's name: who recorded it, where it came from, when.
 *
 * `source` is stated rather than hidden because a sitting the spreadsheet parser created ("UPLOAD")
 * holds answers nobody on this phone typed — and a designer looking at answers they do not remember
 * giving needs the app to say where they came from before they decide it has invented them.
 */
internal fun entrySubtitle(entry: CustomEntryDto, total: Int): String = listOfNotNull(
    "${answeredCount(entry)} of $total answered",
    entry.createdByName?.takeIf { it.isNotBlank() }?.let { "by $it" },
    when (entry.source) {
        "UPLOAD" -> "from the spreadsheet"
        "APP" -> null
        else -> entry.source?.takeIf { it.isNotBlank() }
    },
    entry.createdAt?.take(10)?.takeIf { it.isNotBlank() }
).joinToString(" · ")

/** Every ACTIVE question on the form, in the order a designer reads them. */
internal fun activeQuestionCount(form: CustomQuestionnaireDto): Int =
    form.sections.filter { it.isActive }.sumOf { section -> section.questions.count { it.isActive } }

/**
 * The design workshops this questionnaire could be attached to, and what happened when we asked.
 *
 * EVERY PAGE, not the first. This asked for one page of a hundred on the premise that "a designer
 * runs a handful of workshops, not a hundred" — which the running API refutes: designer@example.org
 * answers `total: 121`, and a workshop the designer was GRANTED sorts to the very end, so the picker
 * could not offer the one workshop a co-designer most needs it for. The paging rule and the evidence
 * are in QuestionnaireWorkshopPicker.kt; it reuses the list screen's walk so the two cannot disagree
 * about what this account can see.
 *
 * Still no search box wired to the server: [com.designprototype.workshop.ui.SearchableSelectField]
 * filters what it is given in memory, so a per-keystroke request would buy nothing here except a
 * spinner in the middle of a two-second decision on a metered connection. The list IS the whole
 * answer — that is what a walk buys — so the box stays on and the threshold decides, which is
 * DROPDOWN_DESIGN §3.6's second row and the opposite ruling from the record forms' picker, where the
 * options are one truncated page and the box is switched off.
 *
 * A CancellationException is rethrown rather than swallowed. These pickers load from a
 * `LaunchedEffect`, so a screen left mid-load cancels the walk; catching that like a network failure
 * would report "no workshops" for a connection that was fine — the same confusion the list screen
 * documents at WorkshopListScreen.kt.
 *
 * ── AND THE FAILURE ARM IS NO LONGER AN EMPTY LIST ─────────────────────────────────────────────
 *
 * It used to `return emptyList()`, with the note that "an empty picker is therefore a degraded but
 * honest offline state". The first half was true and the second was not: the picker said nothing at
 * all, and a picker that opens on nothing reads as *there are none*. The attach control being
 * optional is an argument for not BLOCKING the screen — which nothing here does — and not an
 * argument for leaving a designer to infer why their workshops are missing. So the failure is
 * carried out as a state with a sentence attached, and `isTransient` decides which of §3.5's two
 * failure sentences is the true one: no signal, or a server that answered and refused.
 */
internal suspend fun attachableDesignWorkshops(repository: WorkshopRepository): AttachableWorkshops =
    try {
        walkAttachableDesignWorkshops { page, pageSize ->
            repository.designWorkshops(page = page, pageSize = pageSize)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        AttachableWorkshops(
            rows = emptyList(),
            state = WorkshopListState.Failed,
            online = !repository.isTransient(failure),
        )
    }

/** The muted "nothing here yet" line every one of these screens needs in at least two places. */
@Composable
internal fun EmptyNote(text: String) {
    Text(text, color = MaterialTheme.field.muted, fontSize = 12.sp)
}
