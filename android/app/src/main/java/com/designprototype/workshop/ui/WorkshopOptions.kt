package com.designprototype.workshop.ui

import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.WorkshopDetailDto
import java.time.LocalDate

/**
 * ONE VOCABULARY FOR EVERY WORKSHOP PICKER ON THIS HANDSET — the labels, the order, the "none" row
 * and the six sentences an empty one is allowed to say.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS FILE EXISTS, WHICH IS NOT TIDINESS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Eleven controls on this client offer a workshop, and until this file they assembled the label
 * eleven times. Three copies of the same hint builder are still in the tree as this is written —
 * `DesignWorkshopPicker`'s own, `dwChooserWorkshopHint` (`designworkshop/DwSketchChooserRows.kt:428`)
 * and `designWorkshopOption` (`questionnaires/QuestionnaireWorkshopPicker.kt:63`) — and every one of
 * them carries a comment saying it matches the others. That is exactly the condition under which the
 * three email validators in this app were all believed to agree and did not (see the note above the
 * artisan record form in `MainActivity.kt`). A workshop that reads "Chanderi weaving · Bagru" in one
 * picker and "Chanderi weaving · 2026-07-12" in the next is two workshops as far as the designer
 * reading them is concerned.
 *
 * The far more expensive half is the SENTENCES. `DROPDOWN_DESIGN.md` §3.5 fixes six of them and
 * says, in as many words, that both clients use them byte for byte. They are here as functions
 * rather than as strings at eleven call sites because five of the six are unreachable on any
 * database an author can look at — a list that failed while online and a list this device has never
 * received look identical on a desk with a working connection — so a sentence chosen inline in a
 * composable is only ever exercised by somebody standing in a courtyard with no signal. That is the
 * argument `cappedList.ts` and `dwViewerOfferNotice` already make, and this file copies their shape
 * deliberately.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE FAILURE EVERY RULE BELOW PREVENTS, STATED ONCE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * **A silently empty picker reads as "there are none."** It is named by the frontend contract as the
 * single most repeated bug class in this product, and it has already been paid for twice: 353
 * eligible accounts invisible in the design-workshop viewer picker, indistinguishable from
 * colleagues who had never been empanelled; and `OFFLINE_STATES` in `LocationFields`, where a
 * REQUIRED closed list with no members offline meant native validation refused the submit, the
 * offline outbox was never reached, and *"the interview and its photographs die with the tab"*.
 *
 * Two rules came out of that second one and both bind everything here:
 *
 *   R2 — a field may only be mandatory where it is answerable.
 *   R3 — the control must SAY which of the six cases it is in.
 *
 * An empty list has six causes with six different next moves: a vocabulary that is genuinely short,
 * a cached list with a refresh date on it, a list this device has not been given yet, a read that
 * failed while online, a scope with nothing in it, and a repository with nothing in it. Only the
 * CALLER knows which, which is why `SearchableSelectField.emptyMessage` is the caller's string and
 * why these are functions the caller picks between rather than a default the primitive guesses.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE WEB TWIN, AND WHAT "TWIN" MEANS HERE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `frontend/lib/workshopOptions.ts` is the same four constants, the same label shape and the same
 * six sentences. It is being written in parallel with this file. **The strings are the contract, not
 * the code**: a divergence in the words is a divergence a designer meets when they do the same job
 * on the laptop and on the phone and are told two different things about the same workshop, which
 * teaches them that neither screen means much.
 *
 * Two things are deliberately NOT mirrored, and both are Kotlin's constraint rather than a choice:
 *
 * 1. **The group headings of §2.4.** [SelectOption] is `(value, label, hint)` and has no group slot;
 *    the web's `groupRows` lives in `selectFilter.ts` with no Kotlin counterpart, and adding one
 *    means editing `SearchableSelect.kt`, which is finished and single-owner. What the headings
 *    carry on the web — *open workshops first, then the ones that are over* — is carried here by
 *    the SORT ([designWorkshopStanding], [fieldWorkshopStanding]), so the reading order is the same
 *    on both clients and only the horizontal rules are missing. The status word still reaches the
 *    reader, in the hint, which is where §2.3 puts everything that tells two workshops apart.
 * 2. **Off-page recovery (§2.9) is by id, not by row.** The web re-fetches the stored workshop
 *    through `GET /workshops/{id}` and merges the real row in. This client has no such call wired
 *    into a record form, so [offPageWorkshopRow] draws a row that says what it is instead of a row
 *    that pretends to be the workshop. It exists for one reason: see its own note.
 */

// ---------------------------------------------------------------------------------------------
// The "none" row — four constants, four different meanings
// ---------------------------------------------------------------------------------------------

/*
 * NINE STRINGS COLLAPSE TO FOUR, and the four are not interchangeable — DROPDOWN_DESIGN §2.7.
 *
 * `SearchableSelectField` labels its `includeNone` row with the caller's `placeholder`, so on this
 * client the constant is passed as the placeholder and IS the row. The placeholder is therefore
 * written as an ANSWER and never as a prompt: "Select" over a row that means "unattached" reads as
 * an unfilled required field, and a designer who reads it that way goes looking for the workshop
 * they are supposed to pick.
 *
 * "All workshops" is deliberately absent from this list. A control that FILTERS a screen expresses
 * "everything" by ABSENCE and never by a none-row (R1) — that is `WorkshopScope`'s convention and
 * the filter chips', and a none-row there would give one state two spellings.
 */

/** `""` on a record's `designWorkshopId`: it is filed under no design workshop. */
const val NO_DESIGN_WORKSHOP: String = "Not filed under a design workshop"

/** `""` on a record's `workshopId`: it is linked to no field workshop. */
const val NO_FIELD_WORKSHOP: String = "Not linked to a workshop"

/** A COPY operation where the answer can be deferred — the questionnaire reuse dialog. */
const val ATTACH_LATER: String = "Don't attach it yet"

/** A create flow where typing the details is the alternative to linking a workshop. */
const val TYPE_DETAILS_INSTEAD: String = "Do not link a workshop — type the details below"

// ---------------------------------------------------------------------------------------------
// Which list, and what it is called in a sentence
// ---------------------------------------------------------------------------------------------

/**
 * The two workshop tables, which are never merged and never share a list.
 *
 * `DesignWorkshop` is the 22-stage design and prototype record, gated by `load_workshop_or_404`:
 * creator, admin, or a `DesignWorkshopViewer` grant. `Workshop` is the ordinary field workshop,
 * gated by `WorkshopAssignment` through `resolve_workshop_access`, carrying a submission window and
 * a late-submission dialog. Two tables, two scopes, two access systems — §2.11 C5 rules that the
 * control and the vocabulary unify and the LISTS never do.
 *
 * [noun] is what goes into §3.5's `{noun}` slot. It is plural and lower-case because every sentence
 * it appears in has it mid-clause.
 *
 * [searchDestination] is the screen that can reach past a truncated page, named in [workshopCapLine].
 * A cap notice that does not name a next action tells the reader they have a problem and not what to
 * do about it, which leaves them where the silent version did except now distrusting the screen.
 */
enum class WorkshopListKind(val noun: String, val searchDestination: String) {
    DESIGN(noun = "design workshops", searchDestination = "Design workshops"),
    FIELD(noun = "workshops", searchDestination = "Workshops"),
}

/**
 * What happened when this control asked for its list — the three answers a picker can be looking at.
 *
 * NULL-VS-EMPTY, TOLD APART, which is the whole reason this is a type rather than a `List` that is
 * sometimes empty. "The read has not answered yet", "the read failed" and "the read answered and the
 * answer is none" are three different facts with three different next moves, and a bare
 * `List<T>` — which is what every one of these pickers held before — spells all three `emptyList()`.
 * That single collapse is what let a failed fetch on a phone with no signal render as a confident
 * claim that the repository holds nothing.
 *
 * Mirrors `WorkshopListState` in `frontend/lib/workshopOptions.ts` arm for arm.
 */
sealed interface WorkshopListState {

    /** Asked for, not yet answered. Says so; never draws as "there are none". */
    data object Loading : WorkshopListState

    /**
     * The read did not answer. WHY is not in here on purpose — see [workshopListNotice]'s `online`.
     *
     * The split between "this device is offline" and "the server refused" is the outbox's existing
     * classification (`WorkshopRepository.isTransient`) and not a second idea of what offline means.
     * A second implementation of that judgement is how one screen comes to call a dead tunnel a
     * server fault while the queue behind it calls the same throwable worth retrying.
     */
    data object Failed : WorkshopListState

    /**
     * The read answered. [count] rows arrived out of the server's [total] — which MAY be zero, and a
     * zero here is a fact rather than a failure.
     *
     * [total] is kept even when it equals [count] because the difference between the two is the only
     * thing that can make [workshopCapLine] honest, and eleven call sites in this app have already
     * shipped the bug of keeping `items` and throwing `total` away.
     */
    data class Listed(val count: Int, val total: Int) : WorkshopListState
}

// ---------------------------------------------------------------------------------------------
// The six sentences (DROPDOWN_DESIGN §3.5)
// ---------------------------------------------------------------------------------------------

/*
 * THESE STRINGS ARE THE CONTRACT. Both clients print them byte for byte. `{noun}` is the caller's
 * plural — "design workshops", "workshops", "artisans", "districts" — which is why they take it as
 * a parameter rather than being written out per list: the reasoning is identical for the artisan
 * register and the district list, and a second wording of the same fact is a second fact as far as
 * a reader is concerned.
 *
 * They are `internal` and not `private` so that the record forms, the address card and the reference
 * pickers can reach them as their own waves land. Nothing outside this module may write a seventh.
 */

/**
 * BUNDLED — a vocabulary compiled into the APK. There is no sentence, because there is no fact to
 * report: the list is always answerable, so the field MAY be required and the control stays enabled.
 * Recorded here as a named absence rather than left out, so that a caller reading this file for its
 * case finds it and does not invent a sentence for a state that must not have one.
 */
internal val BUNDLED_LIST_HAS_NO_SENTENCE: String? = null

/**
 * CACHED AND STALE — the list is on the device from an earlier connection, with the date it landed.
 *
 * THE DATE IS THE WHOLE SENTENCE. `DwReferenceStore` states the argument this rests on: *"A list
 * last refreshed an hour ago that does not contain Ram Kumar means Ram Kumar has no artisan record
 * and one should be created; the same list refreshed nine days ago means nothing of the kind."* So a
 * caller that cannot produce a real [refreshedOn] must not use this sentence — a made-up or omitted
 * date turns the one sentence that lets a designer judge the list into the one that stops them.
 *
 * NOT USED BY EITHER WORKSHOP PICKER, and that is R6 rather than an oversight: a stale ACCESS list
 * is wrong in the PERMISSIVE direction — a revoked grant still reads as a grant — so caching is
 * FORBIDDEN for both workshop lists, not merely unattractive. It is here for the register-scoped
 * lists (artisans, crafts, tools, products), where §3.3 rules the opposite way and where
 * `DwReferenceStore` already stamps `fetchedAt` on every write.
 */
internal fun cachedListLine(count: Int, noun: String, refreshedOn: String): String =
    "$count $noun on this device, last refreshed $refreshedOn. If the one you want is missing, " +
        "refresh with a connection before concluding it is not on record."

/**
 * EMPTY BECAUSE OFFLINE — this device has never been given the list. The field stands down.
 *
 * The middle clause is not padding and may not be trimmed: *"That is not a claim that there are
 * none."* is the entire difference between this sentence and the defect it replaces. Everything else
 * on the screen is telling the reader the list is empty; one clause has to be doing the work of
 * saying what emptiness means here.
 */
internal fun offlineListLine(noun: String): String =
    "This device has not received the $noun list yet, so there is nothing to pick here. That is not " +
        "a claim that there are none. Connect once and the list is kept on the device from then on."

/**
 * COULD NOT BE LISTED — the device is online and the read failed. The field stands down.
 *
 * The second clause exists because this sentence appears on a FORM. A designer who reads that
 * something failed while they are halfway through an interview will reasonably assume their typing
 * is at risk and start again somewhere safer; saying plainly that the record saves without this
 * field is what stops a list request costing an interview.
 */
internal fun couldNotListLine(noun: String): String =
    "The $noun list could not be loaded, so this is not showing what exists. Nothing you have " +
        "entered is at risk — this record can be saved without it."

/**
 * GENUINELY EMPTY, SCOPED — the read succeeded and this ACCOUNT has none. The next move is an admin.
 *
 * Only a control whose list is narrowed by a grant may say this, and it may only be said from a read
 * that ANSWERED. It is the sentence `"No workshops to request yet."` should have been and was not:
 * that one is a claim about the repository made from a read that may simply have timed out.
 */
internal fun scopedEmptyLine(noun: String): String =
    "No $noun are open to this account. An administrator can give you access to one."

/**
 * GENUINELY EMPTY, UNSCOPED — the read succeeded and the REPOSITORY has none. The next move is to
 * create one.
 *
 * Deliberately a different sentence from [scopedEmptyLine], and collapsing the two is what produced
 * `"No crafts available."`: one is a statement about a scope whose remedy is an administrator, the
 * other a statement about the repository whose remedy is a record. A reader given the wrong one goes
 * looking for the wrong person.
 */
internal fun unscopedEmptyLine(noun: String): String = "No $noun have been recorded yet."

/**
 * STILL LOADING — asked for, no answer yet.
 *
 * Present tense and honest: it is the one state where waiting IS the next move. It must never be
 * shown for a list that will never arrive — `"Loading the state list…"` on a phone that has never
 * been online is false for ever and reads as something to wait through, which is the defect §3.2's
 * B2 exists to close on the address card. A caller prints this only while a request is genuinely in
 * flight.
 *
 * Worded as the two shipping screens already word it (`SketchesAndPrototypesScreen`,
 * `DesignReviewScreen`), because those are the sentences designers on this handset have already
 * learned.
 */
internal fun loadingListLine(noun: String): String = "Looking for your $noun…"

// ---------------------------------------------------------------------------------------------
// The one sentence a workshop picker prints
// ---------------------------------------------------------------------------------------------

/**
 * The §3.5 sentence for the state this workshop picker is actually in, or null when it has nothing
 * to say.
 *
 * ONE FUNCTION FOR BOTH SLOTS, and that is deliberate. The web needs two exports — `emptyLabel` goes
 * inside the panel and `workshopListNotice` goes under the control — because those are two different
 * places on that client. On this one they are the same string by construction:
 * `SearchableSelectField` draws `emptyMessage` inside whichever surface opens AND speaks it as part
 * of the trigger's accessibility name AND prints it on the form when the field has been stood down.
 * Two functions here would be two chances to word one fact differently, and the screen and the
 * screen reader would then be saying different things — which is the exact defect the primitive's
 * own `emptyMessage` note was written about.
 *
 * @param online what the OUTBOX thinks, not a network probe. `WorkshopRepository.isTransient` is the
 *   classification: an `IOException` or a 401/408/429/5xx is transient and means this device could
 *   not reach the server, which is [offlineListLine]; anything else is an answered refusal, which is
 *   [couldNotListLine]. Passing a probe's answer here instead would give this app a second idea of
 *   what "offline" means, and the one that was wrong would either strand fieldwork or shout about a
 *   server fault on a phone in a tunnel.
 *
 * @return null ONLY when the list arrived with rows in it. A caller may print the result
 *   unconditionally with `?.let`, and a null is the state in which the control needs no explanation
 *   because it is doing the obvious thing.
 */
internal fun workshopListNotice(
    state: WorkshopListState,
    kind: WorkshopListKind,
    online: Boolean,
): String? = when (state) {
    WorkshopListState.Loading -> loadingListLine(kind.noun)
    WorkshopListState.Failed -> if (online) couldNotListLine(kind.noun) else offlineListLine(kind.noun)
    is WorkshopListState.Listed ->
        // ANSWERED, AND THE ANSWER IS NONE. Both workshop lists are scoped by a grant — a
        // `DesignWorkshopViewer` row on one, a `WorkshopAssignment` on the other — so the honest
        // sentence names an administrator and never the repository. Neither picker may ever print
        // [unscopedEmptyLine]: this account seeing none is not the platform holding none, and a
        // designer told to "create one" when the real remedy is a grant goes and makes a duplicate.
        if (state.count == 0) scopedEmptyLine(kind.noun) else null
}

/**
 * Whether the field may be REQUIRED, and whether the control may be opened at all — R2 in one place.
 *
 * A field is answerable when there is something in it to answer with. Every other state in §3.5's
 * table stands the field down and disables the control, and the sentence [workshopListNotice] just
 * produced is what makes the disabled control legible instead of merely dead.
 *
 * It is one line, and it is a function because the expression is the rule: `LocationFields.tsx:880`
 * on the web is the same `&& options.length > 0` and its file explains why it is written out even
 * where the bundled list means it can never fire — *"the invariant is what matters — this card never
 * demands an answer it is not offering — and a later change that narrowed or dropped the bundled
 * list would otherwise reintroduce a lost interview in silence."*
 */
internal fun listIsAnswerable(options: List<SelectOption>): Boolean = options.isNotEmpty()

/**
 * THE CAP SENTENCE — one page of a list drawn as though it were the list, and the words that stop it.
 *
 * R4: every cap, truncation or narrowing is stated on screen, WITH THE NUMBER. Both numbers, always:
 * *"Showing the first 20"* alone leaves the reader guessing whether that is most of their workshops
 * or a sixth of them, and the difference is whether they go looking elsewhere or conclude the
 * workshop was never created.
 *
 * IT NAMES A SCREEN AND NOT A BOX, and that is the point of §3.6. A picker over one server-truncated
 * page passes `searchable = false`, so there is no box to point at — and pointing at one would be
 * the same lie one layer down, because a filter box over twenty rows answers "Nothing matches" about
 * a workshop sitting on page four. The destination named here is the one screen that searches the
 * whole table on the server.
 *
 * Word for word the web's sentence (`DesignWorkshopSelect.tsx`), because a designer who reads one
 * wording on the laptop and another on the phone learns that the numbers are approximate.
 *
 * @return null when nothing was cut, so an ordinary designer on four workshops never reads a
 *   sentence about a ceiling they cannot reach.
 */
internal fun workshopCapLine(shown: Int, total: Int, kind: WorkshopListKind): String? {
    if (shown <= 0 || total <= shown) return null
    return "Showing the $shown most recent of $total. Open ${kind.searchDestination} to search the " +
        "whole list, then come back."
}

// ---------------------------------------------------------------------------------------------
// The label, the hint and the order (DROPDOWN_DESIGN §2.3, §2.5, §2.6)
// ---------------------------------------------------------------------------------------------

/**
 * THE LABEL IS THE TITLE ALONE. Everything that tells two workshops apart goes in the hint.
 *
 * Not `title · date`, and the reason is how the filter ranks rows: a label-prefix match beats a
 * word-prefix beats a mid-word beats a hint match. Folding the date into the label gives every row
 * the same suffix and demotes nothing, makes the label the wrong length for a handset row, and
 * leaves nowhere for a third fact. Keeping the title alone is what makes typing a title beat a
 * coincidental craft match — and the hint is SEARCHED as well as shown, so nothing becomes
 * unreachable by moving it there.
 *
 * `"Untitled workshop"` rather than a blank row, because `title` is denormalised from stage 1 by
 * `promoted_values()` and a workshop whose stage 1 is unfinished legitimately has none. A blank row
 * is a choice a reader cannot make.
 */
internal fun designWorkshopLabel(workshop: DesignWorkshopDto): String =
    workshop.title.trim().ifBlank { "Untitled workshop" }

/** The same rule for a field workshop. */
internal fun fieldWorkshopLabel(workshop: WorkshopDetailDto): String =
    workshop.title.trim().ifBlank { "Untitled workshop" }

/**
 * The word that says a workshop is over, or null for one that is still open.
 *
 * IT IS A PREFIX ON THE HINT AND NOT A `disabled` ROW — §2.6. A designer legitimately corrects a
 * record already filed under a submitted workshop and the server does not refuse it, so disabling
 * the row would convert a read-only fact into a wrong write: the record would be re-filed somewhere
 * else, or not saved at all, because the only row that was true had been greyed out.
 *
 * Soft-deleted workshops are a different answer and never reach here: `list_design_workshops`
 * excludes them unless `includeDeleted`, which is admin-only and which no picker may send. A picker
 * that offered one would file live fieldwork into the trash.
 */
internal fun designWorkshopStatusWord(status: String): String? = when (status.trim().uppercase()) {
    "SUBMITTED" -> "Submitted"
    "ARCHIVED" -> "Archived"
    // DRAFT, IN_PROGRESS, COMPLETE — still open, and an unrecognised status from a newer server is
    // treated as open rather than dressed as one of the two words above. An unknown value must never
    // be printed as a known one.
    else -> null
}

/**
 * The three facts that tell two design workshops apart on a phone: what craft, where, and when.
 *
 * Assembled from what is PRESENT rather than printed with empty separators — `craftName`,
 * `clusterName` and `state` are not promoted from the stages at all, so all three are legitimately
 * null on a workshop somebody started this morning.
 *
 * `workshopCode` is deliberately not here. It is a code an admin reads off a join card, not a fact
 * that tells two workshops apart on screen, and a handset row has no width for it. It stays
 * reachable because the server's `search` already covers it — but only from a screen whose box
 * reaches the server, which is why the record forms' picker names that screen in [workshopCapLine]
 * instead of drawing a box of its own.
 */
internal fun designWorkshopHint(workshop: DesignWorkshopDto): String? = listOfNotNull(
    designWorkshopStatusWord(workshop.status),
    workshop.craftName?.takeIf { it.isNotBlank() },
    workshop.clusterName?.takeIf { it.isNotBlank() } ?: workshop.state?.takeIf { it.isNotBlank() },
    workshop.startDate?.take(10)?.takeIf { it.isNotBlank() },
).joinToString(" · ").takeIf { it.isNotBlank() }

/**
 * A field workshop's hint: whether it is over, where it happened, and when.
 *
 * `place` rather than a craft, because that is the fact a `Workshop` carries and the one a
 * researcher uses to tell two visits apart. The day is the occurrence day, not the day somebody
 * typed the record in — see [fieldWorkshopOccurrence].
 */
internal fun fieldWorkshopHint(workshop: WorkshopDetailDto, today: LocalDate = LocalDate.now()): String? =
    listOfNotNull(
        fieldWorkshopStatusWord(workshop, today),
        workshop.place.takeIf { it.isNotBlank() },
        fieldWorkshopOccurrence(workshop).take(10).takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotBlank() }

/**
 * `"Ended"`, or null while the workshop is still running.
 *
 * THE WHOLE OF THE END DAY IS STILL IN WINDOW, mirroring the backend rule and the web's
 * `endedLocally` — a workshop that ends today has not ended. Getting that boundary wrong by one day
 * marks a workshop the researcher is standing in as over, and the late-submission dialog then asks
 * them to confirm a late submission that is not late.
 *
 * ISO-8601 STRINGS COMPARED AS STRINGS, which is chronological for this format and is what both
 * clients already do. It also keeps this function pure: no parse, no zone, nothing that can throw on
 * a value the server sent, and a caller passing a fixed [today] can pin the boundary in a JVM test.
 *
 * This word is advisory and never a gate. The `Workshop` picker's real answer about a window comes
 * from `GET /workshops/{id}/submission-check` and its dialog; this only stops a reader picking an
 * ended workshop BY ACCIDENT, which is a different job from stopping them SAVING into one.
 */
internal fun fieldWorkshopStatusWord(
    workshop: WorkshopDetailDto,
    today: LocalDate = LocalDate.now(),
): String? {
    val end = (workshop.endDate ?: workshop.date ?: workshop.startDate)?.take(10)?.takeIf { it.length == 10 }
        ?: return null
    return if (end < today.toString()) "Ended" else null
}

/**
 * WHEN THE WORKSHOP HAPPENED, which is not when it was typed in.
 *
 * *"A workshop entered into the system last is not the workshop that ran last"* — the rule is
 * already written in `WorkshopSelect.tsx` for field workshops, and §2.5 extends it to design
 * workshops, which today inherit `createdAt desc` from the server and are re-sorted by nobody.
 * `createdAt` is the last resort and not the answer: it is what a row falls back to when nobody has
 * yet said when the workshop ran.
 */
internal fun designWorkshopOccurrence(workshop: DesignWorkshopDto): String =
    workshop.startDate ?: workshop.createdAt ?: ""

/** The same rule, over the three date columns a `Workshop` has. */
internal fun fieldWorkshopOccurrence(workshop: WorkshopDetailDto): String =
    workshop.startDate ?: workshop.date ?: workshop.createdAt ?: ""

/**
 * OPEN WORKSHOPS FIRST, THEN THE ONES THAT ARE OVER — the sort key that carries §2.4's headings.
 *
 * On the web this is a group heading; here it is the first sort key, because [SelectOption] has no
 * group slot (see this file's header). The axis is the one a reader must ACT on: new fieldwork does
 * not belong in a submitted workshop, so the still-open ones are what the picker opens on. Grouping
 * by DOOR instead — "workshops you created" versus "workshops you were added to" — is derivable and
 * is rejected on both clients, because the two doors are indistinguishable in CONSEQUENCE: both open
 * the same workshop with the same filing rights, so the split would separate rows on a fact the
 * reader cannot do anything with.
 */
internal fun designWorkshopStanding(workshop: DesignWorkshopDto): Int =
    if (designWorkshopStatusWord(workshop.status) == null) 0 else 1

/** The same, over a field workshop's window. */
internal fun fieldWorkshopStanding(workshop: WorkshopDetailDto, today: LocalDate = LocalDate.now()): Int =
    if (fieldWorkshopStatusWord(workshop, today) == null) 0 else 1

/**
 * THE ROW FOR A WORKSHOP THIS DEVICE COULD NOT LIST BUT THE RECORD IS ALREADY FILED UNDER.
 *
 * ── THE LIE THIS EXISTS TO STOP ───────────────────────────────────────────────────────────────
 *
 * A picker draws its trigger from `options.firstOrNull { it.value == selectedValue }?.label`, and
 * falls back to the placeholder when it finds nothing. So a record filed last month under a
 * workshop, opened for an edit on a phone with no signal, drew the words **"Not filed under a
 * design workshop"** over a record that IS filed. That is not a missing feature; it is the screen
 * stating the opposite of the stored value, and a designer who believes it will file the record
 * somewhere else and quietly move a month of fieldwork.
 *
 * It becomes reachable the moment the field stands down on an empty list, which is why it is landing
 * in the same change: `enabled = false` over zero options is correct for a NEW record and would have
 * silently trapped the wrong label on an edit.
 *
 * ── WHY A SENTENCE AND NOT THE WORKSHOP'S NAME ────────────────────────────────────────────────
 *
 * The web recovers the real row by asking `GET /workshops/{id}` for that one id, outside the access
 * scope, and files it under the heading "Already on this record" — *"withholding it does not
 * withhold anything… hiding the row would convert a read-only fact into a wrong write."* This client
 * has no such call wired into a record form and this file will not invent one behind a picker: an
 * extra request per form open, on a village connection, is a cost §5's A2 did not sanction. What is
 * honest without it is to say what the row IS. It never claims to be the workshop's title, so it
 * cannot be mistaken for one.
 *
 * It is FIRST in the list and it is never counted as one of the listed rows, so [workshopCapLine]'s
 * arithmetic is unaffected by it.
 */
internal fun offPageWorkshopRow(id: String, kind: WorkshopListKind): SelectOption = SelectOption(
    value = id,
    label = when (kind) {
        WorkshopListKind.DESIGN -> "The design workshop already on this record"
        WorkshopListKind.FIELD -> "The workshop already on this record"
    },
    hint = "Filed earlier · this device could not list it just now, so its name is not shown",
)

/**
 * Every design workshop this control may offer, labelled, sorted and ready for the picker.
 *
 * THE ORDER IS THE ONE ANSWER OF §2.5: standing first (open before over), then by occurrence newest
 * first, then title ascending, then id ascending. The last two are not decoration — a page of
 * workshops that share a start date would otherwise come out in whatever order the server's
 * non-total sort happened to produce, and a picker whose rows move between two openings is a picker
 * a designer stops trusting. `id` is the final tiebreak for the same reason `with_id_tiebreak`
 * exists on the server: it is the only key guaranteed unique.
 *
 * A NOTE ON THE SERVER HALF, because this sort cannot fix it. `GET /design-workshops` pages with
 * `order = {"createdAt": "desc"}` and NO id tiebreak, and offset paging over a non-total order
 * *"misses rows and repeats others, and both are silent"*. Re-sorting here cannot recover a row the
 * server never sent. The fix is one call on the route (W-B1); until it lands, a walked list is a
 * prefix that may have a hole in it, which is what `DesignWorkshopListing.truncated` is for.
 *
 * @param offPageId the workshop already stored on the record, if any. When it is not among [rows] it
 *   gets [offPageWorkshopRow] at the head of the list. Pass `""` from a control that is not editing
 *   a stored value — a FILTER must not grow a row for something it cannot show.
 */
internal fun designWorkshopOptions(
    rows: List<DesignWorkshopDto>,
    offPageId: String = "",
): List<SelectOption> {
    val listed = rows
        .sortedWith(
            compareBy<DesignWorkshopDto> { designWorkshopStanding(it) }
                .thenByDescending { designWorkshopOccurrence(it) }
                .thenBy { designWorkshopLabel(it) }
                .thenBy { it.id }
        )
        .map { workshop ->
            SelectOption(
                value = workshop.id,
                label = designWorkshopLabel(workshop),
                hint = designWorkshopHint(workshop),
            )
        }
    val wanted = offPageId.trim()
    if (wanted.isEmpty() || listed.any { it.value == wanted }) return listed
    return listOf(offPageWorkshopRow(wanted, WorkshopListKind.DESIGN)) + listed
}

/**
 * The same, for the field-workshop table.
 *
 * [today] is a parameter rather than a call to the clock inside, so the "has it ended" boundary can
 * be pinned in a JVM test — and so a screen left open overnight is not re-deciding what "ended"
 * means halfway through a recomposition it did not ask for.
 */
internal fun fieldWorkshopOptions(
    rows: List<WorkshopDetailDto>,
    offPageId: String = "",
    today: LocalDate = LocalDate.now(),
): List<SelectOption> {
    val listed = rows
        .sortedWith(
            compareBy<WorkshopDetailDto> { fieldWorkshopStanding(it, today) }
                .thenByDescending { fieldWorkshopOccurrence(it) }
                .thenBy { fieldWorkshopLabel(it) }
                .thenBy { it.id }
        )
        .map { workshop ->
            SelectOption(
                value = workshop.id,
                label = fieldWorkshopLabel(workshop),
                hint = fieldWorkshopHint(workshop, today),
            )
        }
    val wanted = offPageId.trim()
    if (wanted.isEmpty() || listed.any { it.value == wanted }) return listed
    return listOf(offPageWorkshopRow(wanted, WorkshopListKind.FIELD)) + listed
}
