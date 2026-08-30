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
import com.designprototype.workshop.data.DesignWorkshopPageDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.unfiledLinkReason
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
 * THE LIMITATION THAT USED TO BE STATED HERE — "THIS CLIENT CANNOT UNFILE" — IS CLOSED
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * What stood here said that [value] returns null for "none", that `ApiClient.json` has
 * `explicitNulls = false` so the key is OMITTED from the body, that the API's `exclude_unset=True`
 * reads an absent key as *leave the stored value alone*, and that this transport therefore could not
 * spell the un-filing the server has always accepted (`designWorkshopId` is in
 * `services/records.CLEARABLE_KEYS`, and the web sends it). It named the cost exactly: *"a designer
 * clearing the box, pressing Save, being told it saved, and finding the workshop still there — the
 * 'exit zero is not evidence' class of defect wearing a form."*
 *
 * IT IS SPELLABLE NOW, ON BOTH PATHS, AND FOR BOTH COLUMNS AT ONCE — which is the condition that
 * note set for closing it, because `WorkshopPickerState.value()` has the identical shape and a fix
 * reaching one box and not the other is worse than a fix reaching neither:
 *
 *  - ONLINE, `WorkshopRepository.patchBodyWithClearances` puts an explicit `null` back for every
 *    column in `WORKSHOP_LINK_KEYS` that the encoder dropped, having first asked the request class
 *    whether it declares that column at all (`APIModel` is `extra="forbid"`, so a key posted to a
 *    route that has no such field is a 422 this queue would then re-attempt once per app run).
 *  - OFFLINE, the null cannot simply be assumed, because a queued correction may have been written
 *    a fortnight ago by a build that had never heard of this picker, and its silence about the
 *    column is not evidence that anybody asked to clear it. So the ENTRY carries the reason —
 *    `PendingEntry.unfiled`, read back as `clearedLinkKeys` — and the replay sends a null only for
 *    a column this build wrote down a decision about.
 *
 * WHICH LEAVES THIS FILE ONE OBLIGATION, AND IT IS [unfiledReason]. The queue cannot work out which
 * of the two absences an empty box was: by the time the entry drains, days later, the picker that
 * was empty in a courtyard with no signal is full again, and nothing on the device can reconstruct
 * it. Only the form knows, and only while it is on screen. See `unfiledLinkReason`, which holds the
 * rule for both pickers so the two cannot drift.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * AND ONE THING IT USED TO DO SILENTLY, WHICH IT NO LONGER DOES
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A LIST THAT COULD NOT BE LOADED SAID NOTHING AT ALL. The old gate read
 * `if (state.listed && state.workshops.isEmpty())`, so a failed read left `listed` false and the
 * field drew an empty picker with no sentence under it and no sentence in it. Three facts —
 * "still asking", "the read failed", "you are on none" — were all spelled `emptyList()`, and the
 * one the designer saw was the one that reads as *there are none*. The comment defending it argued
 * that the failure had cost the designer nothing because the record still saves; that is true of
 * the SAVE and false of the SCREEN, which had just told a designer standing in a courtyard that
 * they are on no workshop when in fact the phone could not ask.
 *
 * [listState] is what tells the three apart, and [WorkshopListKind.DESIGN]'s sentences in
 * `WorkshopOptions.kt` are what each of them says. See DROPDOWN_DESIGN §3.5.
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

    /**
     * What happened when the list was asked for — the three answers, told apart.
     *
     * This replaces a `listed: Boolean` that could only say "answered" or "not answered yet" and
     * therefore filed a FAILED read under the same word as a read still in flight. The picker then
     * had no way to tell the designer which, so it told them nothing, which reads as the third
     * thing again: there are none.
     */
    var listState by mutableStateOf<WorkshopListState>(WorkshopListState.Loading)
        private set

    /**
     * Whether the device reached the server at all, when [listState] is [WorkshopListState.Failed].
     *
     * NOT A NETWORK PROBE — see `workshopListNotice`. It is `WorkshopRepository.isTransient`'s
     * verdict on the throwable, which is the same classification the offline outbox uses to decide
     * whether an entry is worth retrying. One idea of "offline" per app; a second one would let this
     * screen call a dead tunnel a server fault while the queue behind it calls the same throwable
     * worth retrying.
     *
     * Meaningless while the state is [WorkshopListState.Loading] or [WorkshopListState.Listed], and
     * ignored by the notice in both.
     */
    var online by mutableStateOf(true)
        private set

    /**
     * What the SERVER said this account holds, which is not what arrived.
     *
     * The picker asks for one page (see [DESIGN_WORKSHOP_PAGE]); keeping `total` beside the rows is
     * the only thing that lets the cap sentence print both numbers, and *"Showing the 20 most
     * recent"* on its own leaves a designer guessing whether that is most of their workshops or a
     * sixth of them. Eleven call sites in this app have shipped the version that keeps `items` and
     * throws `total` away.
     */
    var total by mutableStateOf(0)
        private set

    /** The value to put in a create/update body. Null when nothing is chosen — see the class note. */
    fun value(): String? = selectedId.ifBlank { null }

    /**
     * WHY THIS BOX IS EMPTY, when it is — the one fact a queued record cannot reconstruct later.
     *
     * `UNFILED_BY_CHOICE` when a person emptied it, `UNFILED_NO_OPTIONS` when there was never
     * anything to pick, null when something is chosen. The rule is `unfiledLinkReason`'s and not
     * this file's, because `WorkshopPickerState` in `MainActivity.kt` owes the identical answer for
     * `workshopId` and two copies of a rule that decides whether a link is DESTROYED is two rules.
     *
     * [workshops] and not the rendered options is the third argument on purpose: the options list
     * carries the off-page row and the "None" row, so it is non-empty in states where nothing was
     * ever ON OFFER. What is being asked here is whether the register answered, and that is the
     * rows.
     *
     * Feed it to `WorkshopRepository.queueOffline(unfiled = …)` through `workshopUnfiledReasons`.
     * A form that does not pass it queues exactly as every earlier build did — the column is omitted
     * and the stored link stands — so forgetting it loses a clearance rather than inventing one.
     */
    fun unfiledReason(): String? = unfiledLinkReason(
        selectedId = selectedId,
        baselineId = baselineId,
        hadOptions = workshops.isNotEmpty(),
    )

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

    /** The read answered. An empty page is an ANSWER and is recorded as one. */
    internal fun markListed(page: DesignWorkshopPageDto) {
        workshops = page.items
        total = page.total
        listState = WorkshopListState.Listed(count = page.items.size, total = page.total)
    }

    /**
     * The read did not answer, and whether the phone ever reached the server.
     *
     * The rows already held are deliberately NOT cleared. On a re-open of a form whose list arrived
     * once, blanking what is on screen because a later request failed would take away the one thing
     * that still works; and on the ordinary path there is nothing to clear anyway.
     */
    internal fun markFailed(transient: Boolean) {
        online = !transient
        listState = WorkshopListState.Failed
    }
}

/**
 * How many workshops the picker asks for.
 *
 * The same 20 `SketchesAndPrototypesScreen` uses, and deliberately not the server's ceiling: a longer
 * list on a phone picker is a longer scroll to the same answer, and the row below the list names what
 * was left out and where to search for it.
 *
 * ── THE SENTENCE THAT USED TO END THIS NOTE WAS THE DEFECT ──────────────────────────────────────
 *
 * It read: *"`SearchableSelectField` grows its own filter box at eight options, so a designer on
 * twenty is not scrolling blind."* Twenty is over the threshold, so the box appeared — **over one
 * server-truncated page**. A designer who typed the title of a workshop sitting on page four was
 * answered `Nothing matches "…"` about a workshop that exists, in the one control whose whole job is
 * to say what exists. That is absence read as non-existence, produced by a control that looked like
 * it was helping.
 *
 * The web refuses to draw that box for exactly this reason (`DesignWorkshopSelect.tsx`,
 * `searchable={false}` plus a `capHint`), and since A1 this file can refuse too: [DesignWorkshopField]
 * passes `searchable = false` and pays the debt that comes with it — **a caller that switches the box
 * off owes the reader the sentence naming what does reach the rest**, which is `workshopCapLine`'s,
 * printed with both numbers. DROPDOWN_DESIGN §3.6.
 *
 * KEEP THIS PAGE-SIZED IF IT EVER MOVES. With `searchable = false` the anchored menu builds every row
 * eagerly inside a scrolling column, which is right for twenty and is not where two hundred belong.
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
            .onSuccess { page -> state.markListed(page) }
            .onFailure { error ->
                // Leaving the screen is not a failure. Rethrown, as every other load on this client
                // does, so a dead composable never writes state — AND, since this arm now writes a
                // sentence, so that a cancelled load never reports "the list could not be loaded"
                // about a connection that was fine. `walkDesignWorkshopPages` documents the same
                // trap: a `runCatching` that catches Throwable turns every abandoned keystroke into
                // a truncation notice.
                if (error is CancellationException) throw error
                /*
                  WHICH FAILURE, BECAUSE THE TWO HAVE DIFFERENT NEXT MOVES. `isTransient` is the
                  outbox's own classification — an IOException or a 401/408/429/5xx means this
                  device could not get an answer, and anything else means the server answered and
                  refused. The first is "connect once and this list stays on the phone"; the second
                  is "this is not showing what exists". Asking the same question a second way, with
                  a connectivity probe, would give this app two ideas of what offline means.
                */
                state.markFailed(transient = repository.isTransient(error))
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
    /*
      THE LABEL, THE HINT AND THE ORDER ARE `WorkshopOptions.kt`'S AND NOT THIS FILE'S.

      They used to be assembled here, and two more copies of the same assembly are still in the tree
      (`dwChooserWorkshopHint`, `designWorkshopOption`), each carrying a comment claiming to match
      this one. They did not all match: this one had no status word in it, so a SUBMITTED workshop
      and one still running read as the same kind of row, and nothing on the phone put the open ones
      first. Requirement 20 is that the two clients must not disagree about any of this; three
      copies on ONE client cannot honour it even in principle. See DROPDOWN_DESIGN §2.3, §2.5, §2.6.
    */
    val options = remember(state.workshops, state.selectedId) {
        designWorkshopOptions(rows = state.workshops, offPageId = state.selectedId)
    }

    /*
      WHICH OF THE STATES THIS PICKER IS IN, IN WORDS — R3, and the reason this field exists in the
      shape it now has. `SearchableSelectField` cannot guess it: the primitive knows the list is
      empty and knows nothing whatever about WHY, and the five whys that have words have five
      different next moves. Only this composable holds `listState`, so only this composable can say.
    */
    val notice = workshopListNotice(state.listState, WorkshopListKind.DESIGN, state.online)

    /*
      R2 — A FIELD MAY ONLY BE MANDATORY WHERE IT IS ANSWERABLE, and its Android half: a control with
      nothing in it may not be opened. This field is never REQUIRED, so there is no validator to
      stand down; what stands down is the trigger, which otherwise opens a popup whose entire content
      is the "none" row and which reads, to anybody who taps it, as the repository's answer.

      `options.isNotEmpty()` and not `state.workshops.isNotEmpty()`, deliberately: the off-page row
      counts. A record already filed under a workshop this device cannot list still has one true
      thing to show and one reversible choice to offer, and disabling over that would hide the row
      that keeps the trigger honest.
    */
    val enabled = !saving && listIsAnswerable(options)

    /*
      SAID ONCE, NOT TWICE. `SearchableSelectField` prints `emptyMessage` on the form itself when the
      list is empty AND the control is disabled — because in that state neither surface can be
      opened, so a sentence that lives only inside the popup can never be read. That is exactly the
      state below, so the same sentence printed again by this file would put it on screen twice.
    */
    val standDown = options.isEmpty() && !enabled

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "Design & prototype workshop",
            options = options,
            selectedValue = state.selectedId,
            placeholder = NO_DESIGN_WORKSHOP,
            // `includeNone` is what puts "None" in the list, and it must stay: a record filed by
            // mistake has to be de-selectable, and the clearance now reaches the server on both
            // paths (see the class note). The row was kept even while it could not be honoured,
            // because hiding it would have made a transport limitation look like a decision; it
            // stays now for the ordinary reason, which is that unfiling a record is a real answer.
            includeNone = true,
            enabled = enabled,
            /*
              THE FILTER BOX IS OFF, AND THE SENTENCE BELOW IS THE PRICE OF SWITCHING IT OFF.

              [options] is ONE SERVER-TRUNCATED PAGE of twenty. A box over it filters the page, so a
              designer typing the title of their twenty-first workshop was told nothing matched —
              about a workshop that exists, in the control that is least allowed to say so. §3.6
              rules that the threshold does not move and simply stops deciding for record-backed
              lists; `searchable = false` is this call site making that ruling, and `workshopCapLine`
              names the screen whose box does reach the whole table.
            */
            searchable = false,
            // The caller's sentence, never the primitive's. Null here means "the list arrived with
            // rows in it", which is the one state that needs no explanation.
            emptyMessage = notice,
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
        /*
          RULE 10: EVERY CAP SAYS SO, WITH BOTH NUMBERS — and only when it bites, so an ordinary
          designer on four workshops never reads a sentence about a ceiling they cannot reach.

          It used to compare `size >= DESIGN_WORKSHOP_PAGE` and print the page size alone, which
          said "showing your 20 most recent" to a designer with exactly twenty workshops and nothing
          hidden, and said the same to one with a hundred and twenty. `total` is what the server
          reports for this account, so the sentence now states the arithmetic — and it is worded
          exactly as the web words it, because a designer who meets one wording on the laptop and
          another on the phone learns that the numbers are approximate.
        */
        workshopCapLine(state.workshops.size, state.total, WorkshopListKind.DESIGN)?.let { cap ->
            Text(cap, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        /*
          AND THE ONE SENTENCE ABOUT THE LIST ITSELF — the replacement for a gate that could only
          speak when the read had SUCCEEDED and was therefore silent in the two states that most
          needed a sentence.

          Skipped when the field has been stood down, because `SearchableSelectField` has already
          printed this exact string on the form for that case (see [standDown] above) and one fact
          may not appear twice under one control.
        */
        if (!standDown) {
            notice?.let { line ->
                Text(line, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}
