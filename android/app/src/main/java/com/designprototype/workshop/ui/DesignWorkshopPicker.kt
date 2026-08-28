package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.WorkshopRepository
import kotlinx.coroutines.CancellationException

/**
 * "Which design and prototype workshop is this record filed under?" — the handset's picker.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT IS A SECOND PICKER BESIDE `WorkshopField` AND NOT A SECOND KIND OF ROW IN IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `WorkshopField` picks a `Workshop` — the ordinary field workshop, gated by `WorkshopAssignment`
 * through `resolve_workshop_access`, carrying a submission window and a late-submission dialog.
 * This picks a `DesignWorkshop` — the 22-stage design and prototype record, gated by
 * `load_workshop_or_404`: creator, admin, or a `DesignWorkshopViewer` grant.
 *
 * Two tables, two scopes, two access systems. `Artisan.designWorkshopId` in `schema.prisma` carries
 * the argument at length; the short version is that the link was already EXPRESSIBLE through a
 * `Workshop` typed `DESIGN_PROTOTYPE` and was not USABLE, because that hop is optional at both ends,
 * is not one-to-one, and would have put two access systems on one column.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT IT DELIBERATELY DOES NOT DO
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * NO SUBMISSION PRE-FLIGHT. `WorkshopPickerState` asks `GET /workshops/{id}/submission-check` because
 * a `Workshop` has an assignment roster and a window, and a researcher has to learn about both BEFORE
 * saving rather than after. A design workshop has neither: the only question is "may you open it",
 * which the save itself answers. A pre-flight here would be a request that could only ever say yes.
 *
 * NO LOCAL CACHE AND NO FALLBACK LIST, for the reason `WorkshopSelect.tsx` states on the web and
 * `rememberWorkshopPicker` repeats on this client: a stale copy of an access list is wrong in the
 * PERMISSIVE direction — a revoked grant still reads as a grant — and this is the one control whose
 * whole job is offering. An empty list is drawn as an empty list, with a sentence.
 *
 * IT NEVER REFUSES A SAVE. A failed list leaves the picker empty and the record saves unfiled, which
 * is better than blocking a capture in a courtyard on a list request. Same call `rememberWorkshopPicker`
 * makes and for the same reason.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE DEFAULT, AND WHY THE SERVER DECIDES IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The owner's instruction of 2026-08-28: *"Whenever a designer goes to create/record any particular
 * record type, the most recently allocated Design and Prototype Workshop should be populated by
 * default."*
 *
 * "Most recently allocated" is `DesignWorkshopViewer.createdAt`, which NO CLIENT CAN SEE — it is not
 * on `DesignWorkshopDto` and there is no endpoint that publishes it per row. Deriving a default here
 * would therefore mean guessing from `createdAt` or `startDate`, which answers a different question
 * and answers it differently from the web. So `GET /design-workshops/default-for-me` decides, once,
 * and both clients read the answer. See that route for what it reads and why "none" is a 200.
 *
 * PREFILL ONLY ON A CREATE, AND ONLY WHILE UNTOUCHED. On an edit the stored value wins outright: a
 * form opened on a record filed last month must not silently re-file it under this month's workshop
 * because somebody fixed a typo in the notes. [isEdit] is what says which.
 *
 * AND A PREFILL IS NOT AN EDIT. [DesignWorkshopPickerState.isDirty] compares against the BASELINE the
 * prefill also moves, exactly as `WorkshopPickerState.applyDefault` does, so a form that opens with a
 * workshop filled in is not a form with unsaved changes. A blank new form announcing unsaved work
 * before anybody types is what teaches a designer to click through the guard that has to still mean
 * something an hour later.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * ONE LIMITATION, STATED RATHER THAN HIDDEN: THIS CLIENT CANNOT UNFILE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [value] returns null for "none", `ApiClient.json` has `explicitNulls = false`, so the key is
 * OMITTED from the body and the API's `exclude_unset=True` reads an absent key as "leave the stored
 * value alone". The server accepts an explicit null as "unfile" — `designWorkshopId` is in
 * `records.CLEARABLE_KEYS` and the web sends one — and this transport cannot express it.
 *
 * IT IS INHERITED, NOT INTRODUCED: `WorkshopPickerState.value()` has exactly the same shape and the
 * same consequence for `workshopId` today. It is written down here because the alternative is a
 * designer clearing the box, pressing Save, being told it saved, and finding the workshop still
 * there — which is the "exit zero is not evidence" class of defect wearing a form. Closing it means
 * a sentinel on the wire for both columns at once, which is a transport change and not this one's.
 */
class DesignWorkshopPickerState(initialId: String) {
    /** The workshops this account may file under. Empty until the list lands, or if it never does. */
    var workshops by mutableStateOf<List<DesignWorkshopDto>>(emptyList())

    var selectedId by mutableStateOf(initialId)

    /**
     * What [isDirty] compares against.
     *
     * MOVED BY THE PREFILL AND NEVER BY A TAP, which is the whole mechanism: the app filling a box in
     * must not mark the form edited, and the designer changing it must. Same split, same reason, as
     * `WorkshopPickerState.applyDefault`.
     */
    var baselineId by mutableStateOf(initialId)
        private set

    /** Why the box filled itself in, or null. Cleared the moment the designer picks. */
    var prefillNote by mutableStateOf<String?>(null)
        private set

    /** True once the list has been ASKED FOR and answered — null-vs-empty, told apart. */
    var listed by mutableStateOf(false)
        private set

    /** The value to put in a create/update body. Null when nothing is chosen — see the class note. */
    fun value(): String? = selectedId.ifBlank { null }

    /** True once the designer has changed the workshop away from the loaded/prefilled one. */
    fun isDirty(): Boolean = selectedId != baselineId

    /** A person picked. Retires the explanation, which was about a choice that is no longer the app's. */
    fun choose(id: String) {
        selectedId = id
        prefillNote = null
    }

    /** The app filled it in. Moves the baseline with it, so this is not an edit. */
    fun applyDefault(id: String, note: String?) {
        selectedId = id
        baselineId = id
        prefillNote = note
    }

    internal fun markListed(rows: List<DesignWorkshopDto>) {
        workshops = rows
        listed = true
    }
}

/**
 * How many workshops the picker asks for.
 *
 * The same 20 `SketchesAndPrototypesScreen` uses, and deliberately not the server's ceiling: a longer
 * list on a phone picker is a longer scroll to the same answer, and the row below the list names what
 * was left out and where to search for it. `SearchableSelectField` grows its own filter box at eight
 * options, so a designer on twenty is not scrolling blind.
 */
private const val DESIGN_WORKSHOP_PAGE = 20

@Composable
fun rememberDesignWorkshopPicker(
    repository: WorkshopRepository,
    isEdit: Boolean,
    initialId: String?,
    resetKey: Any? = null,
): DesignWorkshopPickerState {
    val state = remember(resetKey) { DesignWorkshopPickerState(initialId.orEmpty()) }
    LaunchedEffect(resetKey) {
        /*
          BOTH REQUESTS, AND THE DEFAULT IS ASKED FOR ONLY ON A CREATE. Issuing the second on an edit
          would spend a round trip on an answer that is discarded by the branch below — and on a
          village connection every avoidable request is one the designer waits through.

          `runCatching` on each SEPARATELY, so a refused default does not cost the list. They fail for
          different reasons: the list is a scoped read that a designer always passes, the default is a
          newer endpoint an older deployment may not have at all. A 404 from a server that predates it
          must leave the picker perfectly usable, unprefilled.
        */
        runCatching { repository.designWorkshops(page = 1, pageSize = DESIGN_WORKSHOP_PAGE) }
            .onSuccess { page -> state.markListed(page.items) }
            .onFailure { error ->
                // Leaving the screen is not a failure. Rethrown, as every other load on this client
                // does, so a dead composable never writes state.
                if (error is CancellationException) throw error
            }

        if (!isEdit && state.selectedId.isBlank()) {
            runCatching { repository.designWorkshopDefaultForMe() }
                .onSuccess { answer ->
                    val id = answer.workshopId
                    // ANSWERED-AND-NONE IS AN ANSWER. A newly onboarded designer is on no workshop and
                    // nothing is prefilled and nothing is said, which is correct: there is no decision
                    // to explain.
                    if (!id.isNullOrBlank()) {
                        state.applyDefault(id, designWorkshopPrefillNote(answer.reason, answer.accessAt))
                    }
                }
                .onFailure { error -> if (error is CancellationException) throw error }
        }
    }
    return state
}

/**
 * One sentence saying WHY the box filled itself in.
 *
 * A dropdown that fills itself in and cannot say why reads as a bug, and the two doors need different
 * sentences: "the workshop you were most recently added to" sends a designer looking for an
 * allocation that really happened, and "the one you opened most recently" does not. Null for anything
 * this function does not recognise, so a future third `reason` prints nothing rather than a wrong
 * word — an unknown value must never be dressed as one of the two known ones.
 *
 * Internal rather than private so `DesignWorkshopPickerTest` can pin the pairing without a screen.
 */
internal fun designWorkshopPrefillNote(reason: String?, accessAt: String?): String? {
    val day = formatIsoDay(accessAt)
    val tail = if (day == null) "" else " on $day"
    return when (reason) {
        "GRANTED" ->
            "Filled in because it is the design workshop you were most recently added to$tail. " +
                "Change it if this record belongs somewhere else."
        "CREATED" ->
            "Filled in because it is the design workshop you most recently opened$tail. " +
                "Change it if this record belongs somewhere else."
        else -> null
    }
}

/**
 * The day out of an ISO timestamp, or null.
 *
 * NOTHING IS ECHOED ON FAILURE, which is the opposite of what this app does with a date a designer
 * TYPED. This one is the server's, so a value that will not parse is a defect rather than something
 * to put in front of a reader — and the sentence above reads perfectly well without it.
 */
private fun formatIsoDay(iso: String?): String? {
    val text = iso?.takeIf { it.length >= 10 } ?: return null
    val day = text.substring(0, 10)
    return if (day.getOrNull(4) == '-' && day.getOrNull(7) == '-') day else null
}

/**
 * The field every record form mounts directly under its `WorkshopField`.
 *
 * THE TWO PICKERS SIT TOGETHER AND ARE LABELLED APART, because a designer who reads two boxes both
 * saying "Workshop" will fill in whichever they reach first. The labels name the two things the
 * repository actually calls them, and the sentence under the second one says what filing does — and,
 * more importantly, what it does NOT do.
 */
@Composable
fun DesignWorkshopField(
    state: DesignWorkshopPickerState,
    saving: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val options = remember(state.workshops) {
        state.workshops.map { workshop ->
            SelectOption(
                value = workshop.id,
                label = workshop.title.ifBlank { "Untitled workshop" },
                // The three facts that tell two workshops apart on a phone: what craft, where, and
                // when. Assembled from what is present, so a workshop whose stage 1 is unfinished
                // does not print empty separators. `hint` is SEARCHED as well as shown, which is what
                // makes typing a craft or a place find the row.
                hint = listOfNotNull(
                    workshop.craftName?.takeIf { it.isNotBlank() },
                    workshop.clusterName?.takeIf { it.isNotBlank() }
                        ?: workshop.state?.takeIf { it.isNotBlank() },
                    workshop.startDate?.take(10)?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").takeIf { it.isNotBlank() },
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "Design & prototype workshop",
            options = options,
            selectedValue = state.selectedId,
            placeholder = "Not filed under a design workshop",
            // `includeNone` is what puts "None" in the list, and it must stay: a record filed by
            // mistake has to be de-selectable on screen even though this transport cannot yet SEND
            // the clearance (see the class note). Hiding the row as well would make the limitation
            // look like a decision.
            includeNone = true,
            enabled = !saving,
            onSelect = { state.choose(it) },
        )
        state.prefillNote?.let { note ->
            Text(note, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        /*
          SAID ON THE CONTROL THAT COULD BE MISREAD AS A PERMISSION. A designer who believes this box
          narrows who may READ the record will use it as though it does, and it does not:
          `records.viewable_where` returns an empty filter and every signed-in account may already
          read every artisan, product, process, tool and interview in the repository. Stating it here
          costs one line and stops a filing label being trusted as an access rule.
        */
        Text(
            "Files this record under a design and prototype workshop so it appears in that " +
                "workshop's lists. It does not change who can read the record.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        // RULE 10: EVERY CAP SAYS SO. Only when it bites, so an ordinary designer on four workshops
        // never reads a sentence about a ceiling they cannot reach.
        if (state.listed && state.workshops.size >= DESIGN_WORKSHOP_PAGE) {
            Text(
                "Showing your $DESIGN_WORKSHOP_PAGE most recent design workshops. Open Design " +
                    "workshops to find an older one.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
            )
        }
        // ANSWERED, AND THE ANSWER IS NONE — the ordinary state of a newly onboarded designer rather
        // than a fault, and the only sentence here entitled to name an administrator. A list that
        // could not be loaded leaves `listed` false and says nothing at all, because this screen is a
        // form and the failure has cost the designer nothing: the record still saves.
        if (state.listed && state.workshops.isEmpty()) {
            Text(
                "You are on no design workshop yet. Once an administrator adds you to one, it can " +
                    "be chosen here.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
