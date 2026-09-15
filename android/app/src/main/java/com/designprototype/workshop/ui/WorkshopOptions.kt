package com.designprototype.workshop.ui

import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.SchemaResponse
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

/**
 * THE ROW THAT TAKES A TYPE FILTER OFF — which is not a "none" row, and is filed apart from the four
 * above for exactly that reason.
 *
 * The block above rules that a control which FILTERS a screen expresses "everything" by ABSENCE and
 * never through `includeNone`. This constant does not contradict that: the empty value still travels
 * as an ABSENT `workshopKind`, and what is drawn is an ordinary FIRST OPTION carrying `""` rather
 * than the primitive's none-row. The reader has to be able to see the way back on the same list they
 * used to get here, and an option is the only place a way back can be seen.
 *
 * ── ONE ROW, FOUR SURFACES, AND WHICH WORDING SURVIVED ─────────────────────────────────────
 *
 * Each client's list filter and each client's record-form cascade carried this row, and they were
 * not one string. `WorkshopListScreen` and `design-workshops/page.tsx` — its twin, same `kindFilter`
 * state — both said "Any type"; `DesignWorkshopCascade.tsx` said "Any type of workshop".
 *
 * ONLY THE FIRST CARRIED A COMMENT CLAIMING TO SPELL IT "exactly as the web spells it", and that
 * claim was TRUE of its own twin. An earlier version of this note said the cascade made the same
 * claim. It does not — it claims the same SHAPE ("the same shape the list filter uses"), which is a
 * different and correct statement, and saying otherwise made a wrong edit read as a correction.
 *
 * The longer form survives, for two reasons and not by seniority: it is the one that reads correctly
 * when a screen reader speaks the row on its own, out of the labelled box it belongs to; and the
 * record forms mount this box directly above a SECOND picker, where a bare "Any type" is a row a
 * designer can reasonably read as being about the workshop rather than about the type. The web's
 * list page moved onto it rather than the handset moving back, so all four now agree.
 *
 * One constant, so the list screen's filter and the record forms' cascade cannot part company again.
 */
const val ANY_WORKSHOP_KIND: String = "Any type of workshop"

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
 * GENUINELY EMPTY, BUT ONLY BECAUSE THE READER NARROWED IT — the seventh sentence, and the one that
 * stops [scopedEmptyLine] being said about a read the designer themselves filtered.
 *
 * ── THIS IS THE WEB'S OWN RULE, APPLIED TO THE NARROWING THE HANDSET HAS ──────────────────
 *
 * `DesignWorkshopSelect.tsx` states it in as many words for its SEARCH box: *"THE NOTICE IS ASKED OF
 * THE UNNARROWED LIST. With a term typed, an empty answer means the term matched nothing — and
 * printing 'No design workshops are open to this account. An administrator can give you access to
 * one.' underneath a search box somebody has just typed into is a claim about a grant table produced
 * by a filtered read."* That is precisely right, and the browser applies it to the term only,
 * because until the cascade landed the term was the only narrowing it had.
 *
 * The record-form picker on this handset has no search box — `searchable = false`, see
 * [workshopCapLine] — so the TYPE is the only narrowing it has, and it is the same act. A designer
 * on four Skill Upgradation workshops who taps "Cluster Development" and is told that no design
 * workshops are open to their account has been told something false about a grant table, by a
 * sentence whose whole purpose is to send them to an administrator. They would go.
 *
 * ── AND WHY IT NAMES THE WAY BACK ─────────────────────────────────────────────────
 *
 * R2 stands the workshop box down over an empty list, so in this state the only control the reader
 * can still operate is the type box above it. A notice that named no next action would leave them
 * looking at two boxes, one dead, with nothing saying which one to touch — the cap sentence's own
 * argument ([workshopCapLine]: *"a cap notice that does not name a next action tells the reader they
 * have a problem and not what to do about it"*), one control over.
 *
 * [ANY_WORKSHOP_KIND] is quoted rather than described so the sentence names the row as it is drawn.
 */
internal fun narrowedEmptyLine(noun: String): String =
    "No $noun of the type chosen above are open to this account. That is not a claim about your " +
        "other $noun — choose “$ANY_WORKSHOP_KIND” to see them all."

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
// The four REGISTERS, and the provenance that makes their cached sentence sayable
// ---------------------------------------------------------------------------------------------

/**
 * WHERE A REGISTER'S OPTIONS CAME FROM — and, when they came off the disk, WHEN.
 *
 * ── WHY THIS EXISTS AT ALL: A SENTENCE WITH EXACTLY ONE CALLER ─────────────────────
 *
 * [cachedListLine] is written for the register-scoped lists — artisans, crafts, tools, products —
 * and until this type existed it could not be reached from a single one of them. The record forms
 * load their registers through `loadCachedRegister`, which returned a bare `Boolean` and DISCARDED
 * the [com.designprototype.workshop.data.DwReferenceList] it had just read, `fetchedAt` and all.
 * `DwReferenceStore` stamps that date on every write, for a reason its own header spends a paragraph
 * on — *"A list last refreshed an hour ago that does not contain Ram Kumar means Ram Kumar has no
 * artisan record and one should be created; the same list refreshed nine days ago means nothing of
 * the kind"* — and the stage REF fields have printed it since that store landed
 * (`DwReferenceField`'s provenance block). The record forms could not, because the one value that
 * makes the sentence true was thrown away one function short of the screen.
 *
 * So this is the return type `loadCachedRegister` should always have had: not "did it work" but
 * "what answered, and how old is it".
 *
 * ── AND WHY [online] IS A CLASSIFICATION RATHER THAN A PROBE ──────────────────────
 *
 * `WorkshopRepository.isTransient` is the app's one reading of a failure, and it is the outbox's:
 * an `IOException` or a 401/408/429/5xx means this device could not reach the server, and anything
 * else means the server answered and refused. §3.5 says so in as many words — *"Not from a network
 * probe. It is the classification the outbox already makes."* A second idea of what offline means is
 * how one screen comes to call a dead tunnel a server fault while the queue behind it calls the same
 * throwable worth retrying.
 */
internal enum class RegisterSource {
    /** No answer yet from either source. The one state in which "Looking for your …" is true. */
    PENDING,

    /** The network answered this session. Nothing to report and nothing to apologise for. */
    LIVE,

    /**
     * Only the device's own copy answered. The rows are real and pickable and the field stays
     * REQUIRED — it is answerable — but a name missing from them proves nothing, so the date is owed.
     */
    CACHED,

    /** Neither answered. The field stands down (R2) and says which of the two silences this is. */
    NONE
}

/**
 * One register's provenance. See [RegisterSource].
 *
 * DEFAULTED TO PENDING so a composable can hold one from its first frame, before the coroutine that
 * fills it has been scheduled — which is the frame in which "Looking for your crafts…" is the only
 * true sentence available.
 */
internal data class RegisterLoad(
    val source: RegisterSource = RegisterSource.PENDING,
    /** ISO-8601 from `DwReferenceList.fetchedAt`; null unless [source] is [RegisterSource.CACHED]. */
    val fetchedAt: String? = null,
    /**
     * The last failure was an ANSWERED refusal rather than a device that could not reach the server.
     * False while nothing has failed, which is the safe direction: it picks [offlineListLine], whose
     * next move is a connection, over [couldNotListLine], whose next move is to wonder what broke.
     */
    val online: Boolean = false
) {
    /**
     * Did EITHER source produce a list?
     *
     * The old `Boolean` return, kept under a name that says which question it answers, so the three
     * call sites that only ever asked "may I treat this scope as loaded" read exactly as they did.
     */
    val loaded: Boolean
        get() = source == RegisterSource.LIVE || source == RegisterSource.CACHED
}

/**
 * The §3.5 sentence for the state one of the four REGISTERS is in, or null when it has nothing to
 * say. The register twin of [addressListNotice], which is the same five branches over class (b).
 *
 * ── THE CACHED BRANCH IS THE POINT, AND IT IS THE ONE THE ADDRESS CARD ALREADY HAD ─────────
 *
 * A register with rows in it that came off the disk is NOT a picker with nothing to say. It is the
 * one state where a designer needs a sentence over a control that is working perfectly: the list is
 * complete as of a date, and whether a missing name means "create this artisan" or "refresh first"
 * turns entirely on what that date is. Everything else here is the four empty states, worded exactly
 * as every other picker in this app words them.
 *
 * ── AND WHY IT IS NOT PRINTED FROM `emptyMessage` ─────────────────────────────
 *
 * `SearchableSelectField` draws `emptyMessage` when the list is EMPTY, which is right for the four
 * branches below it and is exactly wrong for the cached one — a list with forty artisans in it is
 * not empty and its sentence must still be read. A caller therefore hands the whole result to
 * `emptyMessage` (harmless: with rows, that slot is never reached) AND prints it beside the field.
 *
 * @param rows how many options the picker is actually offering.
 * @param load what this device knows, from `loadCachedRegister`.
 */
internal fun registerListNotice(noun: String, rows: Int, load: RegisterLoad): String? = when {
    // The list arrived this session. The only branch allowed to say nothing.
    rows > 0 && load.source == RegisterSource.LIVE -> null
    /*
     * CACHED, AND ONLY WHERE A REAL DATE CAN BE PRINTED. [cachedListLine]'s own note refuses the
     * sentence without one and is right to: the date IS the sentence. A list described as "last
     * refreshed" with no date is the one form of this that stops a designer judging it. Every
     * register cached by a build older than `DwReferenceList.fetchedAt` is in exactly that state,
     * and it says nothing rather than guessing.
     */
    rows > 0 -> load.fetchedAt
        ?.let { readableStamp(it) }
        ?.takeIf { it.isNotEmpty() }
        ?.let { cachedListLine(rows, noun, it) }
    // Nothing to offer. WHICH of the three empty states this is decides both the sentence and
    // whether the field stands down, so it may not be collapsed into one "no options" branch.
    load.source == RegisterSource.PENDING -> loadingListLine(noun)
    load.online -> couldNotListLine(noun)
    else -> offlineListLine(noun)
}

/**
 * A stored ISO-8601 stamp as a person would read it, or "" when the string is not a timestamp.
 *
 * MOVED HERE FROM `LocationFields.kt`, WHERE IT WAS PRIVATE, and the move is what makes the sentence
 * above sayable at all: [cachedListLine] takes a FORMATTED date, the address card had the only
 * formatter, and a second copy of the parsing below is a second chance to get the fraction wrong in
 * the way the block comment inside it describes. One formatter, two classes of list, one date shape.
 *
 * Tolerant on the way in on purpose: these values are round-tripped through Postgres, JSON and a
 * file on disk, and a provenance line is not worth an exception.
 */
internal fun readableStamp(iso: String?): String {
    val raw = iso?.trim().orEmpty()
    if (raw.length < 19) return ""
    /*
     * The fraction is dropped rather than parsed. Postgres hands back MICROseconds
     * ("2026-06-20T06:22:56.518000Z") and SimpleDateFormat's `S` is milliseconds however many digits
     * it is given, so "518000" reads as 518 seconds and the line would quietly claim the list was
     * refreshed eight and a half minutes later than it was. Nothing here needs sub-second
     * resolution, and a wrong minute on a provenance line is worse than no fraction at all.
     */
    val instant = raw.substring(0, 19)
    val tail = raw.substring(19)
    val zone = when {
        tail.endsWith("Z") || tail.isEmpty() -> java.util.TimeZone.getTimeZone("UTC")
        else -> java.util.TimeZone.getTimeZone("GMT" + tail.takeLast(6))
    }
    val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.UK).apply { timeZone = zone }
    val parsed = runCatching { parser.parse(instant) }.getOrNull() ?: return ""
    // Shown in the reader's own zone: a designer checking a Kutch register in Kolkata wants the time
    // they would have looked at a watch and seen.
    return java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.UK).format(parsed)
}

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
 * @param narrowed whether the READER narrowed this read — on this client, whether a `workshopKind`
 *   was sent. It changes ONE arm: an empty answer to a filtered read is [narrowedEmptyLine] and not
 *   [scopedEmptyLine], because the second is a claim about a grant table and a filtered read cannot
 *   support one. Defaulted to `false` so every control that narrows nothing reads exactly as it did.
 *   It deliberately does NOT touch the failure arms: a read that never answered failed for reasons
 *   that have nothing to do with the filter on it, and dressing that as "none of this type" would
 *   hide a dead connection behind a control the designer would then go on fiddling with.
 *
 * @return null ONLY when the list arrived with rows in it. A caller may print the result
 *   unconditionally with `?.let`, and a null is the state in which the control needs no explanation
 *   because it is doing the obvious thing.
 */
internal fun workshopListNotice(
    state: WorkshopListState,
    kind: WorkshopListKind,
    online: Boolean,
    narrowed: Boolean = false,
): String? = when (state) {
    WorkshopListState.Loading -> loadingListLine(kind.noun)
    WorkshopListState.Failed -> if (online) couldNotListLine(kind.noun) else offlineListLine(kind.noun)
    is WorkshopListState.Listed ->
        // ANSWERED, AND THE ANSWER IS NONE. Both workshop lists are scoped by a grant — a
        // `DesignWorkshopViewer` row on one, a `WorkshopAssignment` on the other — so the honest
        // sentence names an administrator and never the repository. Neither picker may ever print
        // [unscopedEmptyLine]: this account seeing none is not the platform holding none, and a
        // designer told to "create one" when the real remedy is a grant goes and makes a duplicate.
        //
        // UNLESS THE READER NARROWED IT, in which case neither claim is available: the read that
        // answered "none" answered about one type, and what the account holds under the others was
        // not asked. See [narrowedEmptyLine], which is the whole of the difference.
        if (state.count > 0) null
        else if (narrowed) narrowedEmptyLine(kind.noun)
        else scopedEmptyLine(kind.noun)
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
 * The word for where a workshop stands, or null for a plain open one that needs no word.
 *
 * IT IS A PREFIX ON THE HINT AND NOT A `disabled` ROW — §2.6. A designer legitimately corrects a
 * record already filed under a submitted workshop and the server does not refuse it, so disabling
 * the row would convert a read-only fact into a wrong write: the record would be re-filed somewhere
 * else, or not saved at all, because the only row that was true had been greyed out.
 *
 * Soft-deleted workshops are a different answer and never reach here: `list_design_workshops`
 * excludes them unless `includeDeleted`, which is admin-only and which no picker may send. A picker
 * that offered one would file live fieldwork into the trash.
 *
 * ── THE THREE ADDED 2026-09-14, AND WHY THIS IS NO LONGER "THE WORD THAT SAYS IT IS OVER" ──────
 *
 * The review loop put PRE_SUBMISSION, NEEDS_REVISION and APPROVED into `DesignWorkshopStatus` on
 * 2026-09-13 and this function knew about none of them, so all three fell to the `else` arm and
 * printed NO WORD AT ALL: a report sitting on an inspecting officer's desk, and a report an officer
 * has sent back with four corrections, were both drawn exactly like a draft nobody has opened.
 *
 * Adding them forced a split that should have existed anyway. [designWorkshopStanding] sorted on
 * `word != null`, so "has a word" and "is finished with" were one fact — fine while the only two
 * words were Submitted and Archived, and WRONG the moment NEEDS_REVISION needs a word, because a
 * report sent back for corrections is the most open thing in the list and would have sorted to the
 * bottom with the archived ones. The word and the standing are now two functions, and the standing
 * is stated as its own `when` rather than derived from the presence of a string.
 *
 * AN UNRECOGNISED STATUS STILL GETS NO WORD. A value from a newer server must never be dressed as
 * one this build understands, and `WorkshopOptionsTest` pins that.
 */
internal fun designWorkshopStatusWord(status: String): String? = when (status.trim().uppercase()) {
    // Handed in and waiting on the inspecting officers. NOT "Submitted": since 2026-09-13 that word
    // means the approved report has gone to the office, and the two must not share a label on a
    // screen where a designer is deciding what to do next.
    "PRE_SUBMISSION" -> "In pre-submission"
    // The one a designer must not be able to miss — officers have asked for corrections.
    "NEEDS_REVISION" -> "Needs revision"
    "APPROVED" -> "Approved"
    "SUBMITTED" -> "Submitted"
    "ARCHIVED" -> "Archived"
    // DRAFT, IN_PROGRESS, COMPLETE — still open and needing no word, and an unrecognised status from
    // a newer server is treated the same way rather than dressed as one of the five above. An unknown
    // value must never be printed as a known one.
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
    // STATED, NOT DERIVED FROM [designWorkshopStatusWord]. This read `if (word == null) 0 else 1`
    // until 2026-09-14, which made "this row carries a word" and "this workshop is finished with"
    // one fact. They are not: a report in PRE_SUBMISSION can be withdrawn, and one in NEEDS_REVISION
    // is waiting on the designer reading this very list — both need a word in the hint and both
    // belong with the open ones, at the top. Only the two terminal states sort down. APPROVED joins
    // them: its remaining moves are the sanctioning authority's, so no new fieldwork is filed under
    // it by the person holding this phone.
    when (workshop.status.trim().uppercase()) {
        "SUBMITTED", "ARCHIVED", "APPROVED" -> 1
        // DRAFT, IN_PROGRESS, COMPLETE, PRE_SUBMISSION, NEEDS_REVISION — and an unknown status from a
        // newer server, which sorts with the open ones rather than being hidden at the bottom of a
        // list by a build that does not recognise it.
        else -> 0
    }

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
 *
 * ── THE SECOND HINT, AND WHY THE FIRST ONE BECAME A LIE THE DAY THE TYPE BOX LANDED ─────────
 *
 * *"this device could not list it just now"* was true of every way this row could previously be
 * reached: a failed read, an offline phone, or a workshop sitting past the end of one server
 * truncated page. The cascade adds a way that is none of those. A product filed last season under a
 * Skill Upgradation workshop, opened with the type box on its default, produces this row while the
 * device is online, the read succeeded, and the workshop is sitting in the register perfectly
 * listable — it simply was not asked for. Printing "could not list it" there is a comment that
 * contradicts the code, on screen, in front of the one reader who cannot check it; and its obvious
 * reading — *something is wrong with this phone* — sends a designer to look for a fault instead of
 * at the box two lines above that is doing exactly what it says.
 *
 * @param narrowed whether a type filter was in force on the read that did not return this row. The
 *   caller decides, because only the caller knows whether the read ANSWERED: a failed read with a
 *   type chosen is still the old sentence, since the filter is not why the row is missing.
 */
internal fun offPageWorkshopRow(
    id: String,
    kind: WorkshopListKind,
    narrowed: Boolean = false,
): SelectOption = SelectOption(
    value = id,
    label = when (kind) {
        WorkshopListKind.DESIGN -> "The design workshop already on this record"
        WorkshopListKind.FIELD -> "The workshop already on this record"
    },
    // NEITHER SENTENCE CLAIMS THE TYPE IS WRONG, and the narrowed one is worded around what is
    // actually known. A stored workshop absent from a filtered page is either of another type or
    // past the end of that type's first page, and this row cannot tell which without a request it
    // is not allowed to make (see the note above on §2.9). "Not in the list for the type chosen
    // above" is true of both, and it points at the control that puts it back.
    hint = if (narrowed) {
        "Filed earlier · not in the list for the type chosen above, so its name is not shown"
    } else {
        "Filed earlier · this device could not list it just now, so its name is not shown"
    },
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
 *
 * @param narrowed whether [rows] are the answer to a read a TYPE filter was in force on. It reaches
 *   nothing but the off-page row's hint, and it is threaded through rather than decided here because
 *   this function cannot see whether the read answered at all — see [offPageWorkshopRow].
 */
internal fun designWorkshopOptions(
    rows: List<DesignWorkshopDto>,
    offPageId: String = "",
    narrowed: Boolean = false,
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
    return listOf(offPageWorkshopRow(wanted, WorkshopListKind.DESIGN, narrowed)) + listed
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

// ---------------------------------------------------------------------------------------------
// The TYPE of workshop — the vocabulary the cascade's first box offers
// ---------------------------------------------------------------------------------------------

/**
 * The six workshop KINDS, off the served registry — the list screen's type filter, the create
 * dialog's type box, and the first box of the record forms' cascade.
 *
 * ── IT MOVED HERE ON 2026-09-15, AND THE MOVE IS THE POINT OF THIS FILE ─────────────────
 *
 * It was `internal` in `ui.designworkshop` (`WorkshopListScreen.kt`) while it had one screen's worth
 * of callers. The record-form cascade is the third caller and it lives in `ui`, so leaving it where
 * it was would have run the dependency ui → ui.designworkshop, the reverse of the direction the rest
 * of the tree runs in — `WorkshopListScreen` fully-qualifies `com.designprototype.workshop.ui
 * .SelectOption` at every use precisely because it depends on `ui`. Same module, so `internal` would
 * have RESOLVED; it would simply have been a cycle nobody had written down. This file is already the
 * declared single home for "one vocabulary for every workshop picker on this handset", and a
 * vocabulary offered by three pickers on two screens is exactly what that sentence is about.
 *
 * ── NO COMPILED-IN FLOOR ON THIS CLIENT, AND THE CLAIM WAS CHECKED RATHER THAN ASSUMED ─────
 *
 * `DROPDOWN_DESIGN.md` §3.1 files a served enum as a class-(a) vocabulary on Android — *"always
 * answerable, may be required, says nothing, no work"* — and gives the reason: `StageSchemaStore`
 * resolves memory, then `filesDir`, then the BUNDLED APK ASSET, and a build shipped without that
 * asset throws rather than degrading to an empty registry. The web needs `WORKSHOP_KIND_FLOOR`
 * because a browser that has never reached this API holds nothing at all; a handset always holds the
 * copy that shipped with it.
 *
 * VERIFIED ON THIS TREE, 2026-08-31 and re-checked 2026-09-15 link by link, rather than taken on the
 * document's word: `assets/design-workshop-schema.json` carries `enums.WORKSHOP_KIND` with all six
 * members; [SchemaResponse.enums] decodes it; `StageSchemaStore.load` falls through to `readAsset`
 * when both memory and disk miss, and `readAsset` RAISES rather than returning an empty registry;
 * and `WorkshopRepository.designWorkshopSchema` ends in `StageSchemaStore.load(context)` whether or
 * not the network answered. So a fresh install with no signal draws all six, and the claim holds.
 * The one thing that could break it is the bundled asset going stale, which is what the regenerate
 * step and `backend/tests/test_controlled_vocabularies.py` already hold.
 *
 * THE CONSEQUENCE FOR THE CASCADE, because it is a place the web must NOT be copied. The browser's
 * `workshopKindOptions` returns a `served` boolean and the cascade prints a second hint off it —
 * *"These are this app's built-in workshop types — connect once to refresh them"* (R3, since a
 * silently short list reads as "there are only these"). There is no handset state that sentence is
 * true in: the registry always resolves, so a short list here is a short list on the server. Copying
 * it across would put a permanent, false apology under a box that is right.
 *
 * AN EMPTY LIST IS STILL RETURNED HONESTLY rather than substituted for, because the one state this
 * cannot rule out is a registry that has RETIRED the enum — and quietly drawing six members the
 * server no longer accepts would offer a token every save refuses. The callers draw nothing then.
 */
internal fun workshopKindOptions(schema: SchemaResponse?): List<SelectOption> =
    schema?.enums?.get("WORKSHOP_KIND").orEmpty().map { option ->
        SelectOption(value = option.value, label = option.label)
    }

/**
 * The type the cascade should be SHOWING, given the type it holds and the types actually offered.
 *
 * ── THE WEB'S RULE 3, WHICH IS ABOUT A 422 ON A READ NOBODY ASKED FOR ─────────────────
 *
 * `DesignWorkshopCascade.tsx` states it: *"A vocabulary the server has retired must not be sent back
 * to it as a filter: `workshopKind` is validated server-side, and a token it no longer knows is a
 * 422 on a read the designer did not ask for. If the registry arrives without
 * `DESIGN_PROTOTYPE_DEVELOPMENT`, the box falls back to showing every kind rather than to a token
 * nothing can answer."* `design_workshops.py` is where that is true — `enum_filter_or_422` refuses
 * an unknown token rather than answering with an empty list, which is the RIGHT server behaviour and
 * is exactly why the client must not send one.
 *
 * FALLING BACK TO `""` AND NOT TO THE FIRST OFFERED TYPE. "Every type" is the only answer that is
 * certainly correct without knowing what replaced the retired one, and it is the answer that hides
 * nothing: a default that quietly picked whatever now sits first in the registry would narrow a
 * designer's list to a type somebody chose for them in a migration.
 *
 * AN EMPTY [offered] CHANGES NOTHING, which is the handset's half of the web's `if (!served) return`
 * guard. There, an unserved list means the floor list is on screen and has no standing to retire
 * anything. Here it means the registry has retired the whole enum, the type box is not drawn at all
 * ([workshopKindOptions]), and blanking the held token would be a second read issued for a control
 * nobody can see.
 */
internal fun retainedWorkshopKind(chosen: String, offered: List<SelectOption>): String = when {
    offered.isEmpty() -> chosen
    offered.any { it.value == chosen } -> chosen
    else -> ""
}
