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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DesignWorkshopPageDto
import com.designprototype.workshop.data.StageSchemaStore
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
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════════
 * THE TYPE OF WORKSHOP, WHICH IS A LENS OVER THIS LIST AND IS NEVER PART OF THE RECORD
 * ════════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [kind] is the handset's half of `DesignWorkshopCascade.tsx`, and every ruling that file argues is
 * honoured here for the reason it gives there rather than because it is what the browser does:
 *
 *  - **ONE MOUNT, ONE DECISION.** The web puts both boxes in one component because pairing them at
 *    each form would be six copies of a decision with one right answer and five wrong ones, and it
 *    names the precedent — the field repository's tracer, wired form by form, MISSED ON FOUR OF
 *    NINE MOUNTS, reported by a researcher as the feature simply being absent. On this client the
 *    equivalent is that the type lives in this STATE and is drawn by [DesignWorkshopField]: all six
 *    forms pass `state` and `saving` and nothing else, so a seventh form gets the cascade by
 *    mounting the field, and there is no way to mount half of it.
 *  - **IT IS NEVER SAVED.** [value] returns [selectedId] and nothing else. A record's type is a
 *    fact about the workshop it is filed under (`DesignWorkshop.workshopKind`, answered in stage 1),
 *    so a second copy beside the record is a denormalisation that disagrees with its own source the
 *    first time somebody corrects that stage.
 *  - **THE NARROWING GOES TO THE SERVER.** [DESIGN_WORKSHOP_PAGE] is twenty rows out of a table
 *    that is much larger, so a filter applied to the page in hand answers *"no workshops of this
 *    type"* about types that plainly have some — R5, and the same absence-read-as-non-existence
 *    this control is least allowed to say. `GET /design-workshops` has taken `workshopKind` since
 *    the column landed and `WorkshopRepository.designWorkshops` already folds a blank to an absent
 *    parameter.
 *  - **CHANGING IT DOES NOT CLEAR A CHOSEN WORKSHOP.** [chooseKind] cannot touch [selectedId], and
 *    the narrowed read that follows cannot either — [markListed] writes rows and counts only. A
 *    product filed last season under a Skill Upgradation workshop opens with that workshop chosen
 *    and the type box on its default, and `designWorkshopOptions` keeps it visible through
 *    `offPageWorkshopRow`. Narrowing a list is not the same act as discarding an answer.
 */
class DesignWorkshopPickerState(initialId: String) {
    /** The workshops this account may file under. Empty until the list lands, or if it never does. */
    var workshops by mutableStateOf<List<DesignWorkshopDto>>(emptyList())

    var selectedId by mutableStateOf(initialId)

    /**
     * The `WORKSHOP_KIND` token the list is narrowed by, or `""` for every type.
     *
     * OPENS ON [DEFAULT_WORKSHOP_KIND], which is the owner's instruction and not this file's taste.
     * It is a FILTER and never an answer: nothing about it is written to the record, and no
     * capability is tested by it — that question was settled upstream by `canRunDesignWorkshops`
     * before the form rendered, and re-asking it inside a control would be a second, drifting copy
     * of a role set `test_role_ladder_parity.py` exists to keep singular.
     *
     * `""` AND NEVER A BLANK TOKEN ON THE WIRE. R1: empty means everything, BY ABSENCE.
     * `WorkshopRepository.designWorkshops` folds the blank to null and Retrofit then omits the
     * parameter, which is the same journey the list screen's own filter makes.
     */
    var kind by mutableStateOf(DEFAULT_WORKSHOP_KIND)
        private set

    /**
     * The types on offer, off the registry — empty only where the enum has been RETIRED server-side.
     *
     * `workshopKindOptions` is the single vocabulary and it needs no floor on this client, for the
     * reason set out at its own declaration. An empty list here therefore means one thing rather
     * than the browser's two, and [DesignWorkshopField] draws no type box at all — the same ruling
     * `WorkshopListScreen` already shipped for its filter.
     */
    var kindChoices by mutableStateOf<List<SelectOption>>(emptyList())
        private set

    /**
     * WHETHER THE REGISTER HAS EVER ANSWERED THIS FORM WITH ROWS — sticky, and load-bearing.
     *
     * ── THE FAILURE THIS CLOSES, WHICH THE CASCADE CREATED AND THE WEB CANNOT WARN ABOUT ─────
     *
     * [unfiledReason] used to read `workshops.isNotEmpty()` straight off the current rows, and that
     * was exact while the rows could only ever GROW: the list read was issued once, [markFailed]
     * deliberately keeps what it has, so "there are rows now" and "there were ever rows" were the
     * same sentence. A type filter breaks that. A designer on nine design workshops who taps a type
     * that happens to have none leaves this state with an EMPTY [workshops], and a record they
     * simply never filed would then be queued as `UNFILED_NO_OPTIONS` — *"there was nothing to
     * pick"* — when in fact there were nine and they did not pick one.
     *
     * That is not a cosmetic difference. `unfiledLinkReason` decides which SENTENCE the outbox drain
     * prints about a record that went up filed under nothing, and the whole point of that sentence
     * is to let a record missing from a workshop's lists be told apart from a record deliberately
     * filed under none. A lens over the list must not change what the outbox RECORDS about why a
     * link is absent; that is the class of silent damage `Offline.kt` and `OutboxUnfiledSentinelTest`
     * were written to close, and a filter is no excuse to reopen it.
     *
     * So the question is asked of the UNNARROWED history rather than of this moment's rows, which is
     * what [unfiledReason] meant all along and what it accidentally said correctly until now.
     */
    var everListedRows by mutableStateOf(false)
        private set

    /**
     * Settle [everListedRows] from a read that was NOT narrowed by a type.
     *
     * ── WHY THE STICKY FLAG ABOVE IS NOT ENOUGH ON ITS OWN ────────────────────────────────────
     *
     * It is written only from the rows a LIST READ returned, and every list read this picker issues
     * carries the current type — which opens at `DEFAULT_WORKSHOP_KIND` and is retained across
     * records. So THE FIRST READ IS ALREADY NARROWED and there is no unnarrowed read at all unless
     * somebody taps "Any type of workshop".
     *
     * That leaves the exact hole the flag was introduced to close, reached WITHOUT a tap: a designer
     * holding nine design workshops, none of them of the default type, opens a record filed under
     * none, the one read returns zero rows, `everListedRows` stays false, and a record they simply
     * never filed is queued as `UNFILED_NO_OPTIONS` — *"there was nothing to pick"* — about an
     * account with nine. Worse than the original defect, because it needs no interaction to happen.
     *
     * So when a narrowed read comes back empty the question is asked again without the filter, and
     * only ever to answer "does this account have any at all". It does not touch [workshops],
     * [total] or [listState]: what is drawn stays the answer to the question the designer asked.
     */
    fun noteUnnarrowedRows(any: Boolean) {
        if (any) everListedRows = true
    }

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
        // [everListedRows] and NOT `workshops.isNotEmpty()`. The two were the same expression until
        // the type box landed and they are not any more; the sticky one is the one that answers the
        // question being asked. See its declaration for what the live version would have queued.
        hadOptions = everListedRows,
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

    /**
     * A PERSON CHANGED THE TYPE. It narrows the list and it touches nothing else.
     *
     * NOT [selectedId], NOT [baselineId], NOT [prefillNote]. This function's inability to reach the
     * chosen workshop is the whole of *"changing the type does not clear a chosen workshop"* — it is
     * a property of the code rather than a promise in a comment, which is what the web's own test
     * settles for (it greps its cascade for `setWorkshopId(`) because TypeScript could not make it
     * structural. The prefill note stays too: it explains why the WORKSHOP box filled itself in, and
     * that is still exactly as true after the type box moves.
     */
    internal fun chooseKind(next: String) {
        kind = next
    }

    /**
     * The registry answered with the types on offer, and the held type is checked against them.
     *
     * The retirement rule is `retainedWorkshopKind`'s and not this file's, because the reason for it
     * is the SERVER's (`enum_filter_or_422`) and it must read the same on both clients. Called once
     * on open; calling it again with the same list is a no-op, which is what lets the loader seed
     * from the process cache and then confirm off disk without a second read going out.
     */
    internal fun offerKinds(options: List<SelectOption>) {
        kindChoices = options
        kind = retainedWorkshopKind(kind, options)
    }

    /**
     * A READ IS GOING OUT AND WHAT IS ON SCREEN IS THE ANSWER TO A DIFFERENT QUESTION.
     *
     * Only reachable from the list effect, which runs on open and on every type change. On open it
     * clears nothing because there is nothing to clear; on a type change it is the difference
     * between a picker that is honestly empty for a moment and one that goes on drawing the previous
     * type's workshops under the new type's label. The second is what the web's rule 2 is about:
     * *"confidently wrong rather than merely stale, because nothing on screen says the list did not
     * move."*
     *
     * DELIBERATELY NOT THE SAME CHOICE [markFailed] MAKES. That one keeps its rows, because a
     * dropped connection is no reason to take away the one thing still working. Here the rows are
     * not stale, they are about something else — the identical split `DwWorkshopNameField` makes
     * between a failed read and a re-read under a new search term.
     *
     * [selectedId] and [everListedRows] survive it, and both matter: the first is the record's link
     * and no filter may drop it, the second is the outbox's memory of whether anything was ever on
     * offer.
     */
    internal fun markReading() {
        workshops = emptyList()
        total = 0
        listState = WorkshopListState.Loading
    }

    /** The read answered. An empty page is an ANSWER and is recorded as one. */
    internal fun markListed(page: DesignWorkshopPageDto) {
        workshops = page.items
        total = page.total
        // STICKY, AND SET FROM THE ROWS RATHER THAN FROM `total`. `total` is the server's count for
        // the query that was asked, which under a type filter is that type's count; the question
        // this flag answers is whether this form has ever HAD something to offer. See its
        // declaration for the outbox sentinel that reads it.
        if (page.items.isNotEmpty()) everListedRows = true
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

/**
 * The type of workshop the first box opens on.
 *
 * The owner's instruction is that it *"should be design and prototype workshop by default for all
 * with the privileges for the same"*. The second half of that sentence is answered upstream and not
 * here: everyone who can reach a record form can reach this control, and `canRunDesignWorkshops` had
 * already decided whether the form rendered at all. What the default DOES is narrow the list to the
 * type nearly every record belongs to.
 *
 * `DesignWorkshopCascade.DEFAULT_WORKSHOP_KIND` is the same token on the web and this must stay
 * byte-identical to it: a designer whose laptop opens on one type and whose phone opens on another
 * is reading two different lists under one label, on the same record, in the same afternoon.
 *
 * IT MUST BE A `WORKSHOP_KIND` THE VOCABULARY CARRIES. `backend/app/services/stage_schema.ENUMS` is
 * the one definition; `design_workshops.py` validates the parameter with `enum_filter_or_422`, so a
 * token nothing knows is a 422 on a read the designer did not ask for. `DesignWorkshopPickerTest`
 * checks it against the registry this APK actually ships rather than against a copy of the list.
 */
internal const val DEFAULT_WORKSHOP_KIND: String = "DESIGN_PROTOTYPE_DEVELOPMENT"

@Composable
fun rememberDesignWorkshopPicker(
    repository: WorkshopRepository,
    isEdit: Boolean,
    initialId: String?,
    resetKey: Any? = null,
): DesignWorkshopPickerState {
    val appContext = LocalContext.current.applicationContext
    /*
      SEEDED FROM THE PROCESS CACHE ON THE FIRST COMPOSITION, which is what `DesignWorkshopCascade`
      does with `peekStageRegistry()` and for the same reason: the type box is the only thing on
      screen that EXPLAINS why the workshop list below it is short, so a form that paints the
      narrowed list first and the box that narrowed it a moment later has told the reader the wrong
      thing in the interval. `StageSchemaStore.peek` is synchronous and touches neither disk nor
      network, so on any session where a stage screen or the workshop list has already run — which
      is nearly all of them — the box is there on the first frame. Null on a cold process, which the
      effect below answers within a frame or two.
    */
    val state = remember(resetKey) {
        DesignWorkshopPickerState(initialId.orEmpty())
            .also { fresh -> fresh.offerKinds(workshopKindOptions(StageSchemaStore.peek())) }
    }

    /*
      ── THE TYPES, OFF DISK, NEVER OFF THE NETWORK ─────────────────────────────────────

      `StageSchemaStore.load` and NOT `repository.designWorkshopSchema`, which is the deliberate
      choice and the one a later reader is most likely to want to "fix". The repository entry point
      peeks the same cache and then, when the cache is cold, AWAITS A NETWORK REQUEST before falling
      through to exactly the answer this line already has. Six record forms are opened dozens of
      times a day, and putting a round trip in front of a vocabulary that is COMPILED INTO THE APK
      would mean that on a village connection the type box — the control that explains the narrowing
      already in force — is the last thing to arrive, after the list it is meant to account for.
      `StageSchemaStore.load`'s own note is the licence: *"Deliberately network-free so that opening
      a stage screen cannot block on a request. Freshness is refresh's job and it runs beside this,
      not in front of it."* `InspectionDetailScreen` reaches for it the same way.

      FRESHNESS IS NOT LOST BY THIS. Any successful fetch anywhere in the app writes through to
      `filesDir` and to the process cache, so this reads whatever the last connected session learned.

      `runCatching` because the one thing `load` can still throw is a build shipped without the
      bundled asset. That is a packaging error rather than a field condition, and the honest handling
      on a record form is an absent type box rather than a crash in the middle of an interview.
    */
    LaunchedEffect(resetKey) {
        runCatching { workshopKindOptions(StageSchemaStore.load(appContext)) }
            .onSuccess { offered -> state.offerKinds(offered) }
            .onFailure { error -> if (error is CancellationException) throw error }
    }

    /*
      ── THE LIST, RE-READ WHENEVER THE CHOSEN TYPE CHANGES ───────────────────────────

      `state.kind` IS IN THE KEY, and that is rule 2 of the cascade: without it the box changes, no
      request is sent, and the picker goes on drawing the previous type's workshops under the new
      type's label — confidently wrong rather than merely stale, because nothing on screen says the
      list did not move.

      AND NOT DEBOUNCED. A type is a TAP on a picker and not typing, so there is no run of
      intermediate values to absorb; `DwWorkshopNameField` makes the same split for the same reason
      and `DesignWorkshopSelect.tsx` keys its debounce off the search term alone.

      THIS EFFECT USED TO ALSO CARRY THE DEFAULT-FOR-ME READ, and splitting them is the only
      non-obvious piece of this change. Re-keying one effect on the type would have re-issued
      `GET /design-workshops/default-for-me` on every tap — a request that can only give the same
      answer, on a connection where, as the note this replaced put it, *"every avoidable request is
      one the designer waits through"*. Two effects, two keys, each stating which. They still leave
      together: both launch on the same composition, so the two requests go out at once rather than
      in series, which is what the browser's own pair of effects achieves and what the single
      coroutine here did NOT.

      AND YES, READING `state.kind` HERE SUBSCRIBES THE WHOLE FORM TO IT. This function is inlined
      into the record form's scope, so a type tap recomposes that form. That is accepted rather than
      overlooked: the same form already recomposes on every keystroke into any of its text fields,
      which happens some thousands of times more often than somebody taps a type. Moving the read
      into a nested composable to avoid it would buy nothing measurable and would put the key of the
      effect somewhere other than beside the effect, which is where a reader looks for it.
    */
    LaunchedEffect(resetKey, state.kind) {
        // What is on screen is the previous type's answer. See [DesignWorkshopPickerState.markReading];
        // on the first run it clears nothing.
        state.markReading()
        runCatching {
            repository.designWorkshops(
                page = 1,
                pageSize = DESIGN_WORKSHOP_PAGE,
                // THE SERVER IS THE FILTER. One page is twenty rows out of a much larger table, so
                // narrowing the rows in hand would answer "no workshops of this type" about types
                // that have some. Blank folds to an ABSENT parameter inside `designWorkshops` — R1,
                // empty means everything by absence — so `""` asks the unfiltered question.
                workshopKind = state.kind.takeIf { it.isNotBlank() },
            )
        }
            .onSuccess { page ->
                state.markListed(page)
                /*
                  THE UNNARROWED PROBE, AND IT IS THE CHEAP CASE THAT MATTERS.
                  It runs only when a TYPE-FILTERED read came back with nothing and nothing has ever
                  been listed — so on the ordinary path, where the default type has workshops, it
                  never runs at all. One row is asked for because the only question is "any at all";
                  the answer is fed to `noteUnnarrowedRows`, which touches nothing that is drawn.
                  Without it `hadOptions` is false for every account holding no workshops of the
                  default type, and the outbox mislabels a deliberate non-filing as "there was
                  nothing to pick". See `noteUnnarrowedRows` for the full trace.
                */
                if (page.items.isEmpty() && !state.everListedRows && state.kind.isNotBlank()) {
                    runCatching {
                        repository.designWorkshops(page = 1, pageSize = 1, workshopKind = null)
                    }
                        .onSuccess { state.noteUnnarrowedRows(it.items.isNotEmpty()) }
                        .onFailure { probeError ->
                            // A probe that does not answer leaves the flag alone. Cancellation is
                            // rethrown for the reason the arm below gives; any other failure simply
                            // means the question stays unanswered, which is the status quo and never
                            // a worse answer than guessing.
                            if (probeError is CancellationException) throw probeError
                        }
                }
            }
            .onFailure { error ->
                // Leaving the screen is not a failure. Rethrown, as every other load on this client
                // does, so a dead composable never writes state — AND, since this arm now writes a
                // sentence, so that a cancelled load never reports "the list could not be loaded"
                // about a connection that was fine. `walkDesignWorkshopPages` documents the same
                // trap: a `runCatching` that catches Throwable turns every abandoned keystroke into
                // a truncation notice. THE EFFECT IS KEYED NOW, so this arm also meets the
                // cancellation of a read a designer overtook by tapping a second type.
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
    }

    /*
      ── THE DEFAULT, ON A CREATE ONLY, AND EXACTLY ONCE ──────────────────────────────

      KEYED ON `resetKey` ALONE. The type must not appear here: this endpoint answers "which workshop
      were you most recently given", which is not a question the type box has any bearing on, and
      asking it again per tap would spend a round trip to be told the same thing.

      Issuing it on an edit would spend one to be told something the branch below discards outright.

      `runCatching` separately from the list, so a refused default does not cost the list. They fail
      for different reasons: the list is a scoped read that a designer always passes, the default is
      a newer endpoint an older deployment may not have at all. A 404 from a server that predates it
      must leave the picker perfectly usable, unprefilled.

      IT CAN STILL PREFILL A WORKSHOP OF ANOTHER TYPE, and that is correct rather than an oversight.
      The server answers with the workshop this account was most recently ALLOCATED; narrowing that
      answer to the type box's default would substitute a filter for an allocation and file the
      record under a workshop nobody gave them. `designWorkshopOptions` keeps the prefilled row
      visible through `offPageWorkshopRow`, which is the same mechanism that protects an edit.
    */
    LaunchedEffect(resetKey) {
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
 *
 * ── IT IS TWO BOXES NOW, AND THAT IS WHY IT IS STILL ONE FUNCTION ──────────────────────
 *
 * The TYPE box sits above the WORKSHOP box and narrows it. The two are drawn by one composable, off
 * one state, for the reason `DesignWorkshopCascade.tsx` gives: pairing them at each mount would be
 * six copies of a decision with one right answer, and the field repository has already paid for that
 * shape once — its tracer was wired form by form, four of nine mounts were missed, and a researcher
 * reported the feature as simply absent. Every mount of this field is the whole cascade because
 * there is nothing else to mount; all six call sites pass `state` and `saving` and always have.
 *
 * NOTHING ABOUT THE TYPE IS SENT ANYWHERE. It is a lens over the list; the record carries
 * `designWorkshopId` and nothing else. `DesignWorkshopPickerTest` sweeps `MainActivity.kt` for any
 * form that has learned to read it.
 */
@Composable
fun DesignWorkshopField(
    state: DesignWorkshopPickerState,
    saving: Boolean = false,
    modifier: Modifier = Modifier,
) {
    /*
      NARROWED, FOR THE TWO SENTENCES THAT CHANGE UNDER A TYPE FILTER — and read off BOTH the type
      and the list state, because either half alone gets it wrong.

      A type with no read behind it (still loading, or the read failed) has narrowed nothing that a
      reader can be told about: the rows are missing because there was no answer, not because of the
      box. `workshopListNotice` refuses the narrowed arm for the failure states on its own; the
      off-page row cannot, because it has no idea a read happened at all. So the caller decides once,
      here, and both get the same answer — which is the whole reason `WorkshopOptions.kt` insists
      there is exactly one sentence per fact.
    */
    val narrowed = state.kind.isNotBlank() && state.listState is WorkshopListState.Listed

    /*
      THE LABEL, THE HINT AND THE ORDER ARE `WorkshopOptions.kt`'S AND NOT THIS FILE'S.

      They used to be assembled here, and two more copies of the same assembly are still in the tree
      (`dwChooserWorkshopHint`, `designWorkshopOption`), each carrying a comment claiming to match
      this one. They did not all match: this one had no status word in it, so a SUBMITTED workshop
      and one still running read as the same kind of row, and nothing on the phone put the open ones
      first. Requirement 20 is that the two clients must not disagree about any of this; three
      copies on ONE client cannot honour it even in principle. See DROPDOWN_DESIGN §2.3, §2.5, §2.6.
    */
    val options = remember(state.workshops, state.selectedId, narrowed) {
        designWorkshopOptions(rows = state.workshops, offPageId = state.selectedId, narrowed = narrowed)
    }

    /*
      WHICH OF THE STATES THIS PICKER IS IN, IN WORDS — R3, and the reason this field exists in the
      shape it now has. `SearchableSelectField` cannot guess it: the primitive knows the list is
      empty and knows nothing whatever about WHY, and the five whys that have words have five
      different next moves. Only this composable holds `listState`, so only this composable can say.
    */
    val notice = workshopListNotice(state.listState, WorkshopListKind.DESIGN, state.online, narrowed)

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
        /*
          THE TYPE OF WORKSHOP — first, because it narrows what is below it and a filter read after
          the list it filtered is a filter the reader has to go back and re-read the list for.

          DRAWN ONLY WHEN THERE ARE TYPES TO OFFER, which on this client means one thing and not the
          browser's two: the registry always resolves — memory, then `filesDir`, then the bundled
          APK asset — so an empty list here is an enum RETIRED server-side, and a filter whose only
          row is "any" is a control that cannot do anything. `WorkshopListScreen` already ruled this
          way for its own filter and this is the same ruling, not a second one.

          AND THE WEB'S SECOND HINT IS DELIBERATELY NOT COPIED. `DesignWorkshopCascade.tsx` prints
          *"These are this app's built-in workshop types — connect once to refresh them"* when its
          floor list is showing, which is R3 doing exactly its job in a browser that can hold
          nothing. There is no handset state in which that sentence is true, so copying it across
          would be a permanent apology under a box that is right. See `workshopKindOptions`.

          `searchable` IS NOT PASSED. Six compiled-in members are under the shared threshold anyway,
          and overruling a count that is already right is how a threshold stops meaning anything —
          the same words `WorkshopListScreen` uses at its own type filter. Note that this is the
          OPPOSITE call from the workshop box below it, and the difference is real: that list is one
          server-truncated page and this one is the whole vocabulary.
        */
        if (state.kindChoices.isNotEmpty()) {
            SearchableSelectField(
                label = "Type of workshop",
                // [ANY_WORKSHOP_KIND] carries `""` and is FIRST — an ordinary option rather than
                // `includeNone`, because this is the row that takes the filter off and the reader
                // has to be able to see the way back on the same list they used to get here. The
                // empty value reaches the wire as an ABSENT parameter (R1), never as a blank token.
                options = listOf(SelectOption("", ANY_WORKSHOP_KIND)) + state.kindChoices,
                selectedValue = state.kind,
                includeNone = false,
                enabled = !saving,
                onSelect = { picked -> state.chooseKind(picked) },
            )
            // WORD FOR WORD THE WEB'S, and both halves earn their place: the first says what the box
            // does, and the second stops a designer hunting the saved record for a type it never
            // carried. `DesignWorkshop.workshopKind` is answered in stage 1 and is the only source
            // of that fact about a record.
            Text(
                "Narrows the workshops below. It is not saved on this record.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
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

          IT STAYS HONEST UNDER A TYPE FILTER AND MUST NOT BE "FIXED" TO ACCOUNT FOR ONE. A narrowed
          read returns that type's `total` alongside that type's rows, so both numbers in the
          sentence are answers to the same question; subtracting or annotating anything here would
          be mixing a count of one type with a count of all of them. The sentence points at the
          Design workshops screen, whose search box reaches the whole table — and that screen has
          the same type filter on it, so the reader arrives somewhere they can ask the question they
          were already asking.
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
