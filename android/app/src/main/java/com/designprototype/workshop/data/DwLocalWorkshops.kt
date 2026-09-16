package com.designprototype.workshop.data

import android.content.Context
import com.designprototype.workshop.ui.RegisterLoad
import com.designprototype.workshop.ui.RegisterSource
import com.designprototype.workshop.ui.designWorkshopStanding
import com.designprototype.workshop.ui.fieldWorkshopStatusWord
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.LocalDate

/**
 * THE WORKSHOPS A DESIGNER IS ALLOTTED TO, ON THE PHONE, SO THAT WORK CAN BE ATTRIBUTED TO ONE WITH
 * NO SIGNAL.
 *
 * The owner, 2026-09-16: *"The info for the designer workshop needs to be saved locally for the
 * upcoming/ongoing workshops the particular designer has been allotted to, so that they can attribute
 * their work to the same, but that is about it."*
 *
 * The last clause is the scope and it is enforced here rather than trusted to a caller. What is kept
 * is the ROWS A RECORD FORM'S WORKSHOP PICKER DRAWS — id, title, type, the dates, and the two or
 * three words of hint beside them — for the workshops this account may file into that have NOT yet
 * ended. What is NOT kept: workshops that are over, workshops this account cannot file into, the rest
 * of the deployment's `Workshop` and `DesignWorkshop` tables, and every stage document (those are
 * [WorkshopDraftStore]'s, and a stage document is fieldwork rather than a list).
 *
 * ── IT REUSES [DwReferenceStore]. THERE IS NO SECOND CACHE HERE ───────────────────────────────
 *
 * This app has no Room, no SQLDelight and no `SQLiteOpenHelper`, and the record forms' craft, artisan,
 * product and tool registers already answered the "where do offline lists live" question by taking
 * new KEYS into the one `filesDir` store the design-workshop stage pickers use (`MainActivity.kt`,
 * the `REGISTER_CRAFT` block). This file is the same move for a fifth and sixth list: the same
 * `load`/`store`/`cacheKey` API, the same write-then-rename, the same `fetchedAt` stamp, the same
 * refusal to let an empty answer overwrite a populated file. Everything below is keys, a filter and
 * two encode/decode pairs.
 *
 * ── R6 IS NOT REPEALED, IT IS NARROWED, AND THE NARROWING IS THE WHOLE SAFETY ARGUMENT ────────
 *
 * `DROPDOWN_DESIGN.md` R6 ruled caching FORBIDDEN for both workshop pickers — *"a stale ACCESS list
 * is wrong in the permissive direction: a cached 'which workshops may I submit to' reads a revoked
 * grant as a grant"* — and [WorkshopRepository.workshopsIMaySubmitTo] said the same in its own KDoc,
 * which is rewritten in this change rather than left contradicting its code. That reasoning was and
 * is correct about WHAT GOES WRONG. It was wrong about the remedy, in the same shape
 * [DwReferenceStore]'s own header records for REF fields: *"right about the constraint and wrong
 * about the conclusion."* The constraint is real; the answer is not to leave a designer in a
 * courtyard with an empty picker, it is to bound what the phone is allowed to remember and to keep
 * the ACCESS DECISION on the server, where it always was:
 *
 *  · THE SERVER STILL DECIDES. Nothing here grants anything. `GET /workshops?accessibleOnly=true`
 *    and `GET /design-workshops` are still the only things that compose this list, the
 *    `submission-check` pre-flight still runs on a selection when there is a connection, and the save
 *    is still refused by `enforce_workshop_submission` with a 403 that names the next move. A cached
 *    row is an OFFER, never a permission, and the offer is what a designer needs in order to
 *    attribute a record they are typing in a village.
 *  · THE WINDOW IS RE-TESTED ON EVERY READ, against today's date rather than the fetch's — see
 *    [dwLocalWorkshopEnded]. A workshop that ends while the phone is out of coverage drops off the
 *    list on the morning after it ends, with no request and no refresh.
 *  · THE SET IS THE SMALL ONE. Upcoming and ongoing only, so the window in which a stale grant can be
 *    shown is bounded by the workshop's own end date, not by how long the phone has been away.
 *  · THE READER IS TOLD. Every answer carries [RegisterLoad] — the same provenance the four record
 *    registers already return — so the screen can print `cachedListLine`'s dated sentence instead of
 *    passing a nine-day-old list off as today's.
 *
 * ── THE FOUR QUESTIONS AN OFFLINE CACHE IS GOT WRONG BY, ANSWERED HERE ────────────────────────
 *
 * 1. WHEN IS IT REFRESHED? BY THE PICKERS THEMSELVES, on the reads they were already issuing.
 *    `rememberWorkshopPicker` (`MainActivity.kt`) and `rememberDesignWorkshopPicker`
 *    (`ui/DesignWorkshopPicker.kt`) each answer from the disk first and then replace their own half
 *    of the file with whatever the server said — [loadAllottedFieldWorkshops] and
 *    [loadAllottedDesignWorkshops] are that, and [refreshAllottedWorkshops] is the same two halves
 *    composed for a caller with no screen. There is no TTL, no background job and no second idea of
 *    freshness: a fetch that answers is the truth, a fetch that does not leaves yesterday's answer
 *    standing. That is [WorkshopRepository.designWorkshopReferences]'s cache-first order of
 *    operations and `loadCachedRegister`'s, reused rather than restated.
 * 2. WHAT IF A WORKSHOP ENDS WHILE IT IS CACHED? It stops being offered — at the next read, from the
 *    device clock, offline. It is NOT removed from any record that already names it: the id stays on
 *    the row and `offPageWorkshopRow` draws *"The workshop already on this record"*, which is the
 *    existing answer for a stored id the list cannot show and the reason a picker must not confuse
 *    "not offered" with "not filed".
 * 3. WHAT IF THE SERVER LATER SAYS THE DESIGNER IS NOT ON IT? Two halves, and neither loses work. The
 *    next successful refresh REPLACES the file, so the row is gone from the next offer. The record
 *    that was already saved against it is refused by the server — online, at the save; from the
 *    outbox, at the drain — and the outbox keeps the record, its photographs and the server's own
 *    sentence rather than dropping it. `PendingEntry.danglingField` is the fifth outcome for the 404
 *    half of that (a design workshop deleted or made invisible); the 403 half — a `WorkshopAssignment`
 *    revoked — is an answered refusal that waits for a person, and widening it to offer the same
 *    Re-pick is `WorkshopSync.isMissingReferenceRefusal`'s to do, not this file's.
 * 4. HOW IS A STALE ENTRY TOLD APART FROM A DELIBERATE "NOT LINKED"? By three states where a cache
 *    usually has two, and they are the store's own: NO FILE means this device has never been given
 *    the list ([RegisterSource.NONE] → `offlineListLine`); A FILE WITH NO ROWS means the server
 *    answered and this account is on nothing current ([RegisterSource.CACHED] with an empty list →
 *    `scopedEmptyLine`); ROWS mean pick one, and the date beside them says how old they are. On the
 *    record itself the distinction is already made and is not duplicated here: `unfiledLinkReason`
 *    writes [UNFILED_BY_CHOICE] when somebody emptied the box and [UNFILED_NO_OPTIONS] when there was
 *    nothing to empty, and a cleared link only reaches the wire as an explicit null in the first case.
 */

/**
 * The [DwReferenceStore] model keys, and the account they are filed under.
 *
 * THE ACCOUNT IS PART OF THE KEY AND THAT IS NOT OPTIONAL. Two designers share one handset — the case
 * `PendingEntry.ownerUserId` and `WorkshopDraft.ownerUserId` are both written for. A list of "the
 * workshops you may file into" cached under a bare model name would be A's allotment offered to B the
 * moment B signs in, which is R6's permissive failure arriving by the one door the server cannot
 * shut: B would be offered a workshop B was never on, pick it, and the refusal would land after the
 * record was typed.
 *
 * NOT `"Workshop"` AND NOT `"DesignWorkshop"`. Those bare names are already spoken for by the
 * design-workshop stage's REF pickers, and the record registers' own keys (`REGISTER_CRAFT` and its
 * three siblings) carry the full argument for why a shared key between two callers that store
 * different shapes under it is a silent corruption rather than an error. The same argument applies
 * unchanged here.
 *
 * THE GENERATION IS [dwReferenceCacheOwner]'S DEVICE, for its reason: there is no way to tell a
 * correctly filtered cached list from a wrongly filtered one after the fact, so if what a stored row
 * MEANS ever changes — a different definition of allotted, a different window — bump it rather than
 * writing a migration, and every file written under the old meaning becomes unreachable at once.
 */
internal const val DW_LOCAL_FIELD_WORKSHOPS = "AllottedFieldWorkshop"

/** See [DW_LOCAL_FIELD_WORKSHOPS]. */
internal const val DW_LOCAL_DESIGN_WORKSHOPS = "AllottedDesignWorkshop"

/** See [DW_LOCAL_FIELD_WORKSHOPS]. Generation 1 is the first build that cached either list. */
private const val DW_LOCAL_WORKSHOP_GENERATION = 1

/**
 * `model:account-generation`, folded into the MODEL segment of the store's three-segment key.
 *
 * FOLDED INTO THE MODEL AND NOT INTO THE OWNER SEGMENT, deliberately. [DwReferenceStore.cacheKey]
 * only fills the owner segment from the workshop id when the scope is `WORKSHOP`, so putting an
 * ACCOUNT there would mean passing the word "WORKSHOP" about a list that has nothing to do with one —
 * a vocabulary lie the next reader of `cacheKey` would have to unpick. Its `safeName` reduces the
 * separator to a single `_`, so the composed segment holds no `__` and the key still parses back as
 * `model__owner__filter`, which is the invariant that file's KDoc says must never be broken.
 *
 * [DwReferenceStore.anyForModel] IS NEVER CALLED ON THESE KEYS, and must not be. That fallback exists
 * to merge one model's differently-filtered lists; merging anything into an access list is exactly
 * the cross-owner leak it had to be fenced to stop, one table over.
 */
internal fun dwLocalWorkshopKey(model: String, userId: String): String =
    DwReferenceStore.cacheKey("$model:$userId-$DW_LOCAL_WORKSHOP_GENERATION", "ALL", "", "")

/**
 * THE ROW THAT MEANS "THE SERVER ANSWERED, AND IT IS NONE" — one reserved id, never drawn.
 *
 * [DwReferenceStore.store] REFUSES to let an empty fetch overwrite a populated file, and that rule is
 * right for a register: a server blip that answers `[]` must not wipe the artisan list off a phone
 * about to lose signal for three days. It is WRONG, in the permissive direction, for an access list:
 * a designer taken off their last workshop, or whose last workshop ended, would keep being offered
 * yesterday's rows for ever, because the only answer that could retire them is the one answer the
 * store will not write.
 *
 * So an empty answer is stored as one option that decodes to nothing. The file is then present and
 * holds no workshops, which is the third state question 4 in the header turns on — "the server said
 * none" as distinct from "this device has never asked" — and it costs one constant and one filter.
 * The alternative was a `clear(key)` on the store; that is a wider change to a file three other
 * features depend on, and it is named in this slice's report rather than taken unilaterally.
 */
private const val DW_LOCAL_ANSWERED_NONE = "__none__"

/**
 * One read of the two cached lists, with the provenance of each.
 *
 * TWO [RegisterLoad]s AND NOT ONE. The reads succeed and fail independently — a phone can hold a
 * fresh field list and a fortnight-old design list — and the picker draws ONE of the two at a time
 * (the type box decides which, per the owner's ruling R3), so it needs the provenance of the half it
 * is actually showing. Collapsing them would make every sentence about age wrong half the time.
 *
 * [RegisterLoad] IS THE REGISTERS' OWN TYPE, imported rather than copied. It already carries the
 * three facts a §3.5 sentence is chosen from — what answered, how old it is, and whether the last
 * failure was an answered refusal or an unreachable server — and a second provenance type would be a
 * second vocabulary for one question, with two sets of sentences drifting apart.
 */
internal data class DwAllottedWorkshops(
    val fieldWorkshops: List<WorkshopDetailDto> = emptyList(),
    val designWorkshops: List<DesignWorkshopDto> = emptyList(),
    val fieldLoad: RegisterLoad = RegisterLoad(),
    val designLoad: RegisterLoad = RegisterLoad(),
)

// ---------------------------------------------------------------------------------------------
// WHICH ROWS MAY BE KEPT — the scope limit, in two predicates
// ---------------------------------------------------------------------------------------------

/**
 * Has the day this workshop was last running gone past?
 *
 * THE WHOLE OF THE END DAY IS STILL IN WINDOW — a workshop that ends today has not ended. That is the
 * backend rule, the web's `endedLocally`, and `fieldWorkshopStatusWord`'s, and getting it wrong by one
 * day marks a workshop the designer is standing in as over. ISO-8601 compared as strings, which is
 * chronological for this format and keeps this pure: no parse, no zone, nothing that can throw on a
 * value the server sent.
 *
 * A ROW WITH NO USABLE DATE HAS NOT ENDED. A `DesignWorkshop` whose stage 1 has not been filled in
 * carries no dates at all and is the most ongoing thing in the list; answering "ended" for it would
 * drop the row on the day a designer most needs it. Absence is not evidence here, exactly as
 * `fieldWorkshopStatusWord` returns no word rather than the wrong one.
 *
 * THIS IS THE DESIGN-WORKSHOP HALF ONLY. The field half asks `fieldWorkshopStatusWord` directly, so
 * there is one rule and not two for the table that already has one; `DwLocalWorkshopsTest` pins these
 * two against each other on the boundary day, which is the only place a second implementation of one
 * rule could quietly disagree.
 */
internal fun dwLocalWorkshopEnded(lastDay: String?, today: LocalDate): Boolean {
    val end = lastDay?.take(10)?.takeIf { it.length == 10 } ?: return false
    return end < today.toString()
}

/**
 * May this `Workshop` row be kept? — upcoming or ongoing, nothing else.
 *
 * ASKS `fieldWorkshopStatusWord` RATHER THAN RE-DECIDING. That function is the app's one answer to
 * "is this field workshop over", it is what the picker's hint prints and what its sort groups by, and
 * a cache that used a second rule would file a workshop the picker calls Ended or drop one it calls
 * open. The import direction — `data` reading a `ui` predicate — is the one
 * [WorkshopRepository] already takes for `ConsolidatedQuestionnaireDto`, and it is the cheap price of
 * there being exactly one definition of ended on this handset.
 *
 * THE ROW'S OWN STATUS IS NOT CONSULTED. `Workshop.status` is the review state of the workshop ROW
 * (PENDING/APPROVED), not a statement about whether the workshop is running; the window is the only
 * thing that says that, which is why `fieldWorkshopStatusWord` reads dates and nothing else.
 */
internal fun dwLocalFieldWorkshopIsCurrent(workshop: WorkshopDetailDto, today: LocalDate): Boolean =
    fieldWorkshopStatusWord(workshop, today) == null

/**
 * May this `DesignWorkshop` row be kept? — still open, and not past its end date.
 *
 * TWO TESTS BECAUSE THIS TABLE ANSWERS "OVER" TWO WAYS. `designWorkshopStanding` is the app's existing
 * partition of the statuses into open and finished-with — SUBMITTED, ARCHIVED and APPROVED sort down,
 * everything else including NEEDS_REVISION is open, and that function's own KDoc argues why a report
 * sent back for corrections is the most open thing in the list. A report already handed in is not
 * somewhere new fieldwork is filed, so it is not cached. The date half is [dwLocalWorkshopEnded],
 * because a workshop can be over by the calendar while its report is still a draft.
 *
 * A record ALREADY filed under a submitted workshop keeps it and stays editable — nothing here touches
 * a stored id, and `offPageWorkshopRow` is what draws it when the list cannot.
 */
internal fun dwLocalDesignWorkshopIsCurrent(workshop: DesignWorkshopDto, today: LocalDate): Boolean =
    designWorkshopStanding(workshop) == 0 &&
        !dwLocalWorkshopEnded(workshop.endDate ?: workshop.startDate, today)

// ---------------------------------------------------------------------------------------------
// WHAT IS STORED PER ROW — the picker's own fields, and nothing else
// ---------------------------------------------------------------------------------------------

/**
 * A `Workshop` reduced to what the picker draws and what the type box narrows by, and back.
 *
 * WHAT SURVIVES THE ROUND TRIP IS WHAT `fieldWorkshopOptions` READS, and that was grepped rather than
 * guessed: `fieldWorkshopLabel` reads `title`; `fieldWorkshopHint` reads `place` and the occurrence
 * day; `fieldWorkshopOccurrence` reads `startDate ?: date ?: createdAt`; `fieldWorkshopStatusWord`
 * reads `endDate ?: date ?: startDate`. Those, plus the id, are stored. `description`, `notes`,
 * `location`, `artisans`, `crafts`, `createdBy`, `status` and `extraMetadata` are dropped, and
 * dropping them is safe only while nothing downstream of a cached list reads them — this comment is
 * the flag that should catch a future caller that starts to.
 *
 * ── `workshopType` IS CARRIED AS A FACT AND NOT AS A CASCADE KEY, AND THE DIFFERENCE IS A BUG ──
 *
 * `DwReferenceOption.filterValue` is the store's slot for *"the value of the parent field this option
 * belongs under, for cascading pickers"*, and the type box above the workshop box looks exactly like
 * such a parent. It is not one, and putting the type there would produce the worst class of wrong
 * answer this repository has:
 *
 *  · `Workshop.workshopType` is the LEGACY TWO-MEMBER enum — `DESIGN_PROTOTYPE | OTHER` — not one of
 *    the six `WorkshopTypeOption.key` tokens the record form's type box now offers. A cached row
 *    would carry `OTHER` and a designer choosing *Skill Upgradation* would match nothing.
 *  · THE TYPE CHOOSES THE TABLE, NOT A SUBSET OF ONE. `WorkshopTypeOption.routesToDesignWorkshop`
 *    decides whether the second dropdown is filled from `DesignWorkshop` or from `Workshop`, and
 *    `GET /workshops` takes no type parameter at all, so the ONLINE list for every non-design type is
 *    the whole scoped list. A cache that narrowed would answer *"no workshops of this type"* about a
 *    table full of them — absence read as non-existence, offline only, where nobody can check it.
 *
 * So the token goes in [data], beside the dates, as one more thing the row says about itself, and
 * `filterValue` stays empty on both tables. Nothing calls `DwReferenceList.narrowedTo` on these lists.
 */
internal fun dwLocalFieldWorkshopToOption(workshop: WorkshopDetailDto): DwReferenceOption =
    DwReferenceOption(
        id = workshop.id,
        label = workshop.title,
        hint = workshop.place,
        data = dwLocalFacts(
            "workshopType" to workshop.workshopType,
            "date" to workshop.date,
            "startDate" to workshop.startDate,
            "endDate" to workshop.endDate,
            "createdAt" to workshop.createdAt,
        ),
    )

/** See [dwLocalFieldWorkshopToOption]. Null for a row with no id, which cannot be filed against. */
internal fun dwLocalOptionToFieldWorkshop(option: DwReferenceOption): WorkshopDetailDto? =
    option.id.takeIf { it.isNotBlank() }?.let { id ->
        WorkshopDetailDto(
            id = id,
            title = option.label,
            // RESTORED BLANK WHEN IT WAS BLANK, rather than falling to the DTO's own "OTHER" default.
            // A row whose type the server never stated must not come back off the disk asserting one.
            workshopType = option.data.dwLocalText("workshopType").orEmpty(),
            place = option.hint,
            date = option.data.dwLocalText("date"),
            startDate = option.data.dwLocalText("startDate"),
            endDate = option.data.dwLocalText("endDate"),
            createdAt = option.data.dwLocalText("createdAt"),
        )
    }

/**
 * A `DesignWorkshop` reduced the same way — see [dwLocalFieldWorkshopToOption].
 *
 * `designWorkshopLabel` reads `title`; `designWorkshopHint` reads the status word, `craftName`,
 * `clusterName ?: state` and the start day; `designWorkshopOccurrence` reads `startDate ?: createdAt`;
 * `designWorkshopStanding` reads `status`; the window reads `endDate ?: startDate`. Those are stored.
 *
 * NO TYPE TOKEN IS STORED AT ALL ON THIS SIDE, AND THAT IS NOT AN OMISSION. `DesignWorkshopDto` does
 * not carry `workshopKind` — the six-token vocabulary lives on the server's column and the narrowing
 * is done by `GET /design-workshops?workshopKind=…`, a request this list cannot make offline. It does
 * not need to: the type box chooses the TABLE (`WorkshopTypeOption.routesToDesignWorkshop`), so when
 * these rows are what is being shown, the type has already been honoured by asking this half of the
 * cache rather than the other. Inventing a token here would be a second, guessed copy of a column the
 * row does not carry.
 */
internal fun dwLocalDesignWorkshopToOption(workshop: DesignWorkshopDto): DwReferenceOption =
    DwReferenceOption(
        id = workshop.id,
        label = workshop.title,
        hint = workshop.craftName.orEmpty(),
        data = dwLocalFacts(
            "status" to workshop.status,
            "startDate" to workshop.startDate,
            "endDate" to workshop.endDate,
            "createdAt" to workshop.createdAt,
            "clusterName" to workshop.clusterName,
            "state" to workshop.state,
        ),
    )

/** See [dwLocalDesignWorkshopToOption]. */
internal fun dwLocalOptionToDesignWorkshop(option: DwReferenceOption): DesignWorkshopDto? =
    option.id.takeIf { it.isNotBlank() }?.let { id ->
        DesignWorkshopDto(
            id = id,
            title = option.label,
            // DRAFT ONLY WHERE THE FILE HOLDS NOTHING, which is true of no row this build writes.
            // `status` decides both the hint's word and the open/over sort, so a blank would sort a
            // cached workshop as unknown; DRAFT is the server's own default for a workshop nobody has
            // moved, and it is the reading that OFFERS the row rather than hiding it — the same
            // direction `designWorkshopStatusWord` takes for a status it cannot name.
            status = option.data.dwLocalText("status") ?: "DRAFT",
            craftName = option.hint.ifBlank { null },
            clusterName = option.data.dwLocalText("clusterName"),
            state = option.data.dwLocalText("state"),
            startDate = option.data.dwLocalText("startDate"),
            endDate = option.data.dwLocalText("endDate"),
            createdAt = option.data.dwLocalText("createdAt"),
        )
    }

/** The non-blank pairs as a JSON object. A key nobody has a value for is absent rather than null. */
private fun dwLocalFacts(vararg pairs: Pair<String, String?>): JsonObject = JsonObject(
    pairs.mapNotNull { (key, value) ->
        value?.takeIf { it.isNotBlank() }?.let { key to JsonPrimitive(it) }
    }.toMap()
)

/** One stored fact, or null when the key is absent, not a primitive, or blank. */
private fun JsonObject.dwLocalText(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

// ---------------------------------------------------------------------------------------------
// THE CACHE ITSELF — read, and refresh
// ---------------------------------------------------------------------------------------------

/** The rows a decoded file actually offers: the tombstone and any id-less row are not workshops. */
private fun DwReferenceList.dwLocalRows(): List<DwReferenceOption> =
    items.filter { it.id.isNotBlank() && it.id != DW_LOCAL_ANSWERED_NONE }

/**
 * What is written for an answer of none. See [DW_LOCAL_ANSWERED_NONE].
 *
 * INTERNAL rather than private so `DwLocalWorkshopsTest` writes through the same function the refresh
 * writes through. A test that composed the retirement row itself would be pinning its own copy of the
 * one rule that keeps a revoked allotment from surviving on the disk for ever.
 */
internal fun dwLocalStored(model: String, options: List<DwReferenceOption>): DwReferenceList =
    DwReferenceList(
        model = model,
        items = options.ifEmpty { listOf(DwReferenceOption(id = DW_LOCAL_ANSWERED_NONE)) },
    )

/** A file that is present answers CACHED even when it holds no rows — see the header, question 4. */
private fun dwLocalLoad(list: DwReferenceList?): RegisterLoad =
    if (list == null) {
        RegisterLoad(source = RegisterSource.NONE)
    } else {
        RegisterLoad(source = RegisterSource.CACHED, fetchedAt = list.fetchedAt.takeIf { it.isNotBlank() })
    }

/**
 * THE ROWS ONE CACHED FILE ACTUALLY OFFERS — decoded, and re-tested against today's window.
 *
 * ONE PLACE, BECAUSE THERE ARE NOW TWO READERS OF THE SAME FILE. [dwLocalRead] answers *"what does
 * this device hold for both tables"* and [dwLoadAllotted] answers *"what should this ONE picker draw
 * while the network is silent"*, and both have to apply the same two rules in the same order: drop
 * the tombstone and the id-less rows, then drop whatever has ENDED since the file was written. Two
 * copies of that is how a workshop comes to be offered by one path and hidden by the other on the
 * same morning, offline, where nobody can compare them.
 */
private fun <T> dwLocalCurrent(
    list: DwReferenceList?,
    decode: (DwReferenceOption) -> T?,
    isCurrent: (T) -> Boolean,
): List<T> = list?.dwLocalRows().orEmpty().mapNotNull(decode).filter(isCurrent)

/**
 * What this device holds for [userId] right now — disk only, no network, never throws.
 *
 * BOTH HALVES, ALWAYS, AND NEITHER IS NARROWED BY THE TYPE. The type box chooses which of the two
 * this caller is going to draw — `WorkshopTypeOption.routesToDesignWorkshop`, the flag that also
 * decides which column the answer is saved to — so honouring it is a matter of reading the right
 * field of this result, not of filtering either list. See [dwLocalFieldWorkshopToOption] for the
 * wrong answer a narrowing here would produce, and why it would only ever appear offline.
 *
 * [today] IS A PARAMETER so the window boundary can be pinned in a JVM test, and so a screen left open
 * overnight is not re-deciding what "ended" means halfway through a recomposition — the same reason
 * `fieldWorkshopOptions` takes one.
 */
private suspend fun dwLocalRead(
    load: suspend (String) -> DwReferenceList?,
    userId: String,
    today: LocalDate,
): DwAllottedWorkshops {
    if (userId.isBlank()) return DwAllottedWorkshops()
    val fieldList = load(dwLocalWorkshopKey(DW_LOCAL_FIELD_WORKSHOPS, userId))
    val designList = load(dwLocalWorkshopKey(DW_LOCAL_DESIGN_WORKSHOPS, userId))
    return DwAllottedWorkshops(
        // THE WINDOW IS RE-TESTED HERE AND NOT ONLY AT THE WRITE. This is the line that answers the
        // header's question 2: a workshop cached on the 3rd whose last day is the 5th stops being
        // offered on the 6th, on a phone that has not seen a connection since the 2nd.
        fieldWorkshops = dwLocalCurrent(fieldList, ::dwLocalOptionToFieldWorkshop) {
            dwLocalFieldWorkshopIsCurrent(it, today)
        },
        designWorkshops = dwLocalCurrent(designList, ::dwLocalOptionToDesignWorkshop) {
            dwLocalDesignWorkshopIsCurrent(it, today)
        },
        fieldLoad = dwLocalLoad(fieldList),
        designLoad = dwLocalLoad(designList),
    )
}

/**
 * The cached answer for the signed-in account. See [dwLocalRead].
 *
 * NO SIGNED-IN ACCOUNT MEANS NO ANSWER, never the last account's. [dwLocalRead] returns an empty
 * [DwAllottedWorkshops] for a blank id, whose two [RegisterLoad]s are `PENDING` — nothing has been
 * asked and nothing is claimed.
 */
internal suspend fun WorkshopRepository.cachedAllottedWorkshops(
    context: Context,
    today: LocalDate = LocalDate.now(),
): DwAllottedWorkshops = dwLocalRead(
    load = { key -> DwReferenceStore.load(context, key) },
    userId = cachedUser()?.id.orEmpty(),
    today = today,
)

/** The same read against a directory rather than a Context — see [DwReferenceStore.load] for why. */
internal suspend fun dwCachedAllottedWorkshops(
    root: File,
    userId: String,
    today: LocalDate = LocalDate.now(),
): DwAllottedWorkshops = dwLocalRead(
    load = { key -> DwReferenceStore.load(root, key) },
    userId = userId,
    today = today,
)

// ---------------------------------------------------------------------------------------------
// THE READER THE TWO RECORD-FORM PICKERS CALL — the disk first, then the server
// ---------------------------------------------------------------------------------------------

/**
 * WHAT ONE HALF OF THE CACHE ENDED UP WITH after a cache-first read — the answer a picker applies.
 *
 * ── [page] IS WHY THIS IS A TYPE AND NOT A LIST, AND IT IS NULL EXACTLY WHEN THE FETCH DID NOT
 * ANSWER ──────────────────────────────────────────────────────────────────────────────────────
 *
 * A picker draws [page] and ignores [rows], and the difference between the two is a rule rather than
 * a convenience:
 *
 *  · [page] IS THE SERVER'S OWN ANSWER, UNFILTERED AND WITH ITS `total`. `total` is the only thing
 *    that makes `workshopCapLine` honest — eleven call sites in this app have shipped the version
 *    that keeps `items` and throws it away — and UNFILTERED matters just as much: the live picker
 *    draws an ended workshop with the word *"Ended"* beside it and sorts it to the bottom
 *    (`fieldWorkshopStatusWord`, `designWorkshopStanding`), because a designer correcting last
 *    month's record has to be able to see the workshop it is filed under. Narrowing the LIVE list to
 *    what the cache is allowed to KEEP would take that row away from a phone with a perfect
 *    connection.
 *  · [rows] IS WHAT THE DEVICE NOW HOLDS: the fetch's rows when it answered, the disk's when it did
 *    not, filtered both times to the upcoming and ongoing, because that is the whole of what this
 *    cache is for. [refreshAllottedWorkshops] returns exactly this and has no screen to show it on.
 *
 * [load] carries the provenance of the answer — `LIVE` when the fetch answered, otherwise whatever
 * the disk said with [RegisterLoad.online] recording which SILENCE this is. See
 * [dwLocalRefreshLoad]: it is the distinction `loadCachedRegister` makes for the four record
 * registers, in the same vocabulary, so one screen cannot call a dead tunnel a server fault while the
 * queue behind it calls the same throwable worth retrying.
 */
internal data class DwAllottedHalf<T, P>(
    val rows: List<T>,
    val page: P?,
    val load: RegisterLoad,
)

/**
 * THE ORDER OF OPERATIONS, ONCE, FOR BOTH TABLES AND BOTH PICKERS: answer from the disk, then let the
 * server's answer replace it and the file.
 *
 * `loadCachedRegister`'s shape, which is [WorkshopRepository.designWorkshopReferences]'s shape, reused
 * rather than restated — and for the identical reason: a record form must open with something in the
 * box rather than sitting empty until a request that may never complete. [onCached] is therefore
 * called BEFORE the fetch is even attempted, which is the difference between a designer in a
 * courtyard seeing four workshops immediately and seeing an empty dropdown for a thirty-second
 * timeout and then still an empty dropdown.
 *
 * ── THE FOUR RULES THIS FUNCTION IS, STATED WHERE THEY ARE ENFORCED ───────────────────────────
 *
 * 1. THE LIVE ANSWER OUTRANKS THE CACHED ONE IN BOTH DIRECTIONS, INCLUDING WHEN IT IS SHORTER, AND
 *    THE TWO ARE NEVER MERGED. A designer taken off a workshop since the last fetch gets a shorter
 *    list the moment a fetch answers, and the file is REPLACED with that shorter list — the opposite
 *    of the registers' rule, and the whole reason [DW_LOCAL_ANSWERED_NONE] exists, because
 *    [DwReferenceStore.store] would otherwise refuse the one write that can retire a revoked
 *    allotment. A union of the two lists would make the cache a place a revoked grant could hide for
 *    ever, which is precisely what R6 was written against.
 * 2. ONLY THE UPCOMING AND ONGOING ARE KEPT, on the way in and again on the way out. `isCurrent` runs
 *    over the fetched rows before they are stored, and [dwLocalCurrent] runs over the decoded ones on
 *    every read, against the device's own today. So a workshop that ends while the phone is out of
 *    coverage stops being offered on the morning after it ends, with no request and no refresh.
 * 3. A CACHED ROW IS AN OFFER AND NEVER A PERMISSION, so this function hands back rows and nothing
 *    else: it selects nothing and prefills nothing. That is the half of R6 that survives — a cached
 *    list may be OFFERED to a person who then chooses from it, and a stale answer may never be
 *    WRITTEN onto a record nobody looked at. Both pickers keep their prefill on the LIVE path for
 *    that reason; [WorkshopRepository.designWorkshopDefaultForMe]'s KDoc carries the argument.
 * 4. A CANCELLED LOAD IS NOT A FAILED ONE. `CancellationException` is rethrown rather than classified,
 *    so a form the designer navigated away from mid-fetch never writes a sentence about a request
 *    that was never really one, and never stores a half-read list either. Both composables did this
 *    for themselves before this function existed; doing it here is what keeps them doing it.
 *
 * ── AND THE ONE CASE NONE OF THE FOUR CAN CLOSE, SAID OUT LOUD ───────────────────────────────
 *
 * A grant revoked while the phone has no signal CANNOT be known about offline. There is no rule that
 * closes it, because closing it would mean the device deciding access, which is the thing this file
 * refuses to do. What is done instead, and what each part of it buys:
 *
 *  · THE SET IS BOUNDED, so the row can only be offered while the workshop itself is still running —
 *    the exposure ends at the workshop's own end date rather than at the next connection.
 *  · THE OFFER IS LABELLED. `workshopListNotice` prints `cachedListLine` over a list that came off
 *    the disk — *"N workshops on this device, last refreshed …"* — so the designer is choosing from
 *    a dated copy and can see that they are. A row offered with no such sentence would be the
 *    silent version of the same offer, and that is the part that is actually forbidden.
 *  · NOTHING IS CHOSEN FOR THEM. See rule 3: a person picks, so a wrong row costs a refusal they are
 *    told about rather than a month of records filed by a default nobody saw.
 *  · THE SAVE STILL ASKS. `submission-check` runs on the selection when there is a connection and
 *    `enforce_workshop_submission` refuses at the save; from the outbox, the drain keeps the record,
 *    its photographs and the server's own sentence. The record is never lost to this.
 *
 * The alternative — an empty picker — does not avoid the problem, it moves the cost onto every
 * designer who is still on the workshop, which is all of them except the rare revoked one.
 *
 * [key] IS NULL WHEN THERE IS NO ACCOUNT TO FILE THE LIST UNDER, and then the disk is neither read
 * nor written. A key with a blank owner would be one list shared by every designer who has ever used
 * this handset, which is the leak [dwLocalWorkshopKey] exists to prevent. The fetch still happens,
 * because that is what the picker did before any of this and a signed-out record form is not this
 * file's to invent behaviour for.
 */
private suspend fun <T, P> dwLoadAllotted(
    load: suspend (String) -> DwReferenceList?,
    store: suspend (String, DwReferenceList) -> Unit,
    key: String?,
    model: String,
    decode: (DwReferenceOption) -> T?,
    encode: (T) -> DwReferenceOption,
    isCurrent: (T) -> Boolean,
    fetch: suspend () -> P,
    rowsOf: (P) -> List<T>,
    isTransient: (Throwable) -> Boolean,
    onCached: (List<T>, RegisterLoad) -> Unit,
): DwAllottedHalf<T, P> {
    var held = emptyList<T>()
    var heldLoad = RegisterLoad()
    if (key != null) {
        val file = load(key)
        held = dwLocalCurrent(file, decode, isCurrent)
        heldLoad = dwLocalLoad(file)
        // SAID EVEN WHEN IT IS EMPTY, because a file with no rows and no file at all are two
        // different facts and the caller is the only thing that can tell a reader which — see the
        // header's question 4, and `workshopListNotice`, where the two become two sentences.
        onCached(held, heldLoad)
    }
    val attempt = runCatching { fetch() }
    attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    val page = attempt.getOrNull()
    val fresh = page?.let(rowsOf)?.filter(isCurrent)
    if (key != null && fresh != null) store(key, dwLocalStored(model, fresh.map(encode)))
    return DwAllottedHalf(
        rows = fresh ?: held,
        page = page,
        load = dwLocalRefreshLoad(attempt, heldLoad, isTransient),
    )
}

/**
 * The `Workshop` half, for the signed-in account. See [dwLoadAllotted] for every rule in it.
 *
 * [fetch] IS THE CALLER'S OWN READ AND NOT ONE THIS FILE ISSUES, which is what keeps the refresh
 * free: the record form's picker already asks [WorkshopRepository.workshopsIMaySubmitToPage] on every
 * open, so keeping the file up to date costs no extra request at all. A read of its own here would
 * have doubled the traffic of every record form, on a connection charged by the megabyte.
 */
internal suspend fun <P> WorkshopRepository.loadAllottedFieldWorkshops(
    context: Context,
    fetch: suspend () -> P,
    rowsOf: (P) -> List<WorkshopDetailDto>,
    today: LocalDate = LocalDate.now(),
    onCached: (List<WorkshopDetailDto>, RegisterLoad) -> Unit = { _, _ -> },
): DwAllottedHalf<WorkshopDetailDto, P> = dwLoadAllotted(
    load = { key -> DwReferenceStore.load(context, key) },
    store = { key, list -> DwReferenceStore.store(context, key, list) },
    key = dwLocalKeyOrNull(DW_LOCAL_FIELD_WORKSHOPS, cachedUser()?.id.orEmpty()),
    model = DW_LOCAL_FIELD_WORKSHOPS,
    decode = ::dwLocalOptionToFieldWorkshop,
    encode = ::dwLocalFieldWorkshopToOption,
    isCurrent = { dwLocalFieldWorkshopIsCurrent(it, today) },
    fetch = fetch,
    rowsOf = rowsOf,
    isTransient = ::isTransient,
    onCached = onCached,
)

/**
 * The same read against a directory rather than a Context — see [DwReferenceStore.load] for why, and
 * [DwLocalWorkshopsTest], which is the only place a failing network can be pointed at this function.
 */
internal suspend fun <P> dwLoadAllottedFieldWorkshops(
    root: File,
    userId: String,
    fetch: suspend () -> P,
    rowsOf: (P) -> List<WorkshopDetailDto>,
    isTransient: (Throwable) -> Boolean,
    today: LocalDate = LocalDate.now(),
    onCached: (List<WorkshopDetailDto>, RegisterLoad) -> Unit = { _, _ -> },
): DwAllottedHalf<WorkshopDetailDto, P> = dwLoadAllotted(
    load = { key -> DwReferenceStore.load(root, key) },
    store = { key, list -> DwReferenceStore.store(root, key, list) },
    key = dwLocalKeyOrNull(DW_LOCAL_FIELD_WORKSHOPS, userId),
    model = DW_LOCAL_FIELD_WORKSHOPS,
    decode = ::dwLocalOptionToFieldWorkshop,
    encode = ::dwLocalFieldWorkshopToOption,
    isCurrent = { dwLocalFieldWorkshopIsCurrent(it, today) },
    fetch = fetch,
    rowsOf = rowsOf,
    isTransient = isTransient,
    onCached = onCached,
)

/** The `DesignWorkshop` half, for the signed-in account. See [dwLoadAllotted]. */
internal suspend fun <P> WorkshopRepository.loadAllottedDesignWorkshops(
    context: Context,
    fetch: suspend () -> P,
    rowsOf: (P) -> List<DesignWorkshopDto>,
    today: LocalDate = LocalDate.now(),
    onCached: (List<DesignWorkshopDto>, RegisterLoad) -> Unit = { _, _ -> },
): DwAllottedHalf<DesignWorkshopDto, P> = dwLoadAllotted(
    load = { key -> DwReferenceStore.load(context, key) },
    store = { key, list -> DwReferenceStore.store(context, key, list) },
    key = dwLocalKeyOrNull(DW_LOCAL_DESIGN_WORKSHOPS, cachedUser()?.id.orEmpty()),
    model = DW_LOCAL_DESIGN_WORKSHOPS,
    decode = ::dwLocalOptionToDesignWorkshop,
    encode = ::dwLocalDesignWorkshopToOption,
    isCurrent = { dwLocalDesignWorkshopIsCurrent(it, today) },
    fetch = fetch,
    rowsOf = rowsOf,
    isTransient = ::isTransient,
    onCached = onCached,
)

/** The same read against a directory rather than a Context — see [dwLoadAllottedFieldWorkshops]. */
internal suspend fun <P> dwLoadAllottedDesignWorkshops(
    root: File,
    userId: String,
    fetch: suspend () -> P,
    rowsOf: (P) -> List<DesignWorkshopDto>,
    isTransient: (Throwable) -> Boolean,
    today: LocalDate = LocalDate.now(),
    onCached: (List<DesignWorkshopDto>, RegisterLoad) -> Unit = { _, _ -> },
): DwAllottedHalf<DesignWorkshopDto, P> = dwLoadAllotted(
    load = { key -> DwReferenceStore.load(root, key) },
    store = { key, list -> DwReferenceStore.store(root, key, list) },
    key = dwLocalKeyOrNull(DW_LOCAL_DESIGN_WORKSHOPS, userId),
    model = DW_LOCAL_DESIGN_WORKSHOPS,
    decode = ::dwLocalOptionToDesignWorkshop,
    encode = ::dwLocalDesignWorkshopToOption,
    isCurrent = { dwLocalDesignWorkshopIsCurrent(it, today) },
    fetch = fetch,
    rowsOf = rowsOf,
    isTransient = isTransient,
    onCached = onCached,
)

/** The cache key for this account, or null when there is no account — see [dwLoadAllotted]'s `key`. */
private fun dwLocalKeyOrNull(model: String, userId: String): String? =
    userId.takeIf { it.isNotBlank() }?.let { dwLocalWorkshopKey(model, it) }

/**
 * Answer from the disk, then replace both files with what the server says — the cache-first order
 * [WorkshopRepository.designWorkshopReferences] states at length, and never throwing for its reason:
 * a record form must open on a dead connection.
 *
 * ── WHAT IT ASKS FOR, AND WHY IT IS NOT A NEW REQUEST ─────────────────────────────────────────
 *
 * The two reads are [WorkshopRepository.workshopsIMaySubmitToPage] and [visibleDesignWorkshops], the
 * scoped lists whose narrowing is the SERVER'S. Nothing here composes a list of its own or invents a
 * second definition of allotted: whatever those endpoints answer for this account is what gets kept,
 * minus the rows that are over.
 *
 * ONLY THE FIRST OF THE TWO IS THE READ A PICKER ALREADY ISSUES, and the difference is not a detail.
 * `rememberWorkshopPicker` asks [WorkshopRepository.workshopsIMaySubmitToPage] itself, so that half
 * is free wherever it is called from. `rememberDesignWorkshopPicker` asks
 * `designWorkshops(page = 1, pageSize = 20)` — ONE page, the page it draws — where this function asks
 * for the WALK below. That is the whole of why a picker may not call this one: see "WHO CALLS IT".
 *
 * BOTH TABLES ON EVERY REFRESH, even though a given form shows one of them at a time. The type box can
 * be changed offline, and a cache that held only the half that happened to be on screen when the
 * signal was last good would put the other half's empty picker in front of a designer who did have a
 * connection an hour ago.
 *
 * THE DESIGN LIST IS WALKED, at 100 rows a page, because a colleague added to a workshop mid-season
 * sits past the end of page one of a `createdAt desc` list — the failure `DesignWorkshopListing`'s
 * header measured against the running API. For an ordinary caseload that is a single request; the
 * walk's own 500-row ceiling is far past any designer's allotment. That ceiling is deliberately NOT
 * re-stated on the cached path: what arrived is what is kept, and the LIVE picker still prints its own
 * cap sentence from the read it made.
 *
 * ── WHO CALLS IT, AND WHY IT IS NOT WHAT FILLS THE FILE ON A RECORD FORM ──────────────────────
 *
 * NO PICKER CALLS THIS, and that is deliberate rather than the wiring being unfinished. Each picker
 * refreshes ITS OWN half through [loadAllottedFieldWorkshops] or [loadAllottedDesignWorkshops], off
 * the read it was already issuing, so opening a record form costs exactly the requests it always
 * cost — where calling this from either picker would have issued the OTHER table's read as well, and
 * the design walk on top. This function is those same two halves composed for a caller that wants
 * both tables from one place and has no screen to draw them on, and it is the only path that walks
 * the design list past page one. The file holds whatever the last answer said, whichever of the two
 * asked: *a fetch that answers is the truth, a fetch that does not leaves yesterday's answer
 * standing.*
 *
 * A FAILURE IS SILENT AND LEAVES THE PREVIOUS FILE STANDING, which is the whole point of the cache;
 * the returned [RegisterLoad] records whether the failure was an ANSWERED refusal or an unreachable
 * server, using [WorkshopRepository.isTransient] — the outbox's one reading of a throwable — so the
 * screen picks between "could not be listed" and "this device has not received the list yet" without a
 * second idea of what offline means.
 */
internal suspend fun WorkshopRepository.refreshAllottedWorkshops(
    context: Context,
    today: LocalDate = LocalDate.now(),
): DwAllottedWorkshops {
    // NOTHING IS WRITTEN WITHOUT AN ACCOUNT TO FILE IT UNDER, and nothing is asked for either: this
    // is a background refresh with no screen behind it, so a signed-out call has nobody to answer to.
    if (cachedUser()?.id.orEmpty().isBlank()) return DwAllottedWorkshops()

    val field = loadAllottedFieldWorkshops(
        context = context,
        fetch = { workshopsIMaySubmitToPage() },
        rowsOf = { it.items },
        today = today,
    )
    val design = loadAllottedDesignWorkshops(
        context = context,
        fetch = { visibleDesignWorkshops() },
        rowsOf = { it.items },
        today = today,
    )
    return DwAllottedWorkshops(
        // LIVE OUTRANKS CACHED, and for an access list it outranks it in BOTH directions: a SHORTER
        // answer is the real one. That is the opposite of the registers' rule, and it is why the
        // tombstone exists — see [DW_LOCAL_ANSWERED_NONE].
        fieldWorkshops = field.rows,
        designWorkshops = design.rows,
        fieldLoad = field.load,
        designLoad = design.load,
    )
}

/** What one half of a refresh ended up with: the fetch when it answered, otherwise what was held. */
private fun dwLocalRefreshLoad(
    attempt: Result<*>,
    held: RegisterLoad,
    isTransient: (Throwable) -> Boolean,
): RegisterLoad {
    if (attempt.isSuccess) return RegisterLoad(source = RegisterSource.LIVE, online = true)
    val cause = attempt.exceptionOrNull()
    // ANSWERED-AND-REFUSED versus COULD-NOT-BE-REACHED, recorded even where the cache answered: a
    // caller with rows on screen still prints the dated sentence, and a caller with none has to know
    // which of the two silences it is in. `loadCachedRegister` makes the identical distinction.
    return held.copy(online = cause != null && !isTransient(cause))
}
