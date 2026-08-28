package com.designprototype.workshop.data

import com.designprototype.workshop.report.summary as richSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/**
 * **THE INSPECTOR / REVIEWER SURFACE ON THE HANDSET: five routes, every one of them a GET.**
 *
 * ── WHAT THIS FINISHES ───────────────────────────────────────────────────────────────────────────
 *
 * The backend landed the whole of this tier — rank 37, a fifth scope system (`DesignWorkshopInspector`),
 * a forward-only migration and machine-checked parity over the ladder — and NO CLIENT CALLED ANY OF
 * IT. Until the web half landed there was nothing an inspector could open and no way for an admin to
 * appoint one; until this file there was nothing on a phone. `grep -rn "design-workshop-inspections"
 * android/app/src` answered nothing at all before this wave; the tier appeared on this handset only
 * as ladder mirrors (`MainActivity.kt`, `ui/AppNavigation.kt`, `ui/TaskAdminScreen.kt`,
 * `ui/AccessRosterScreen.kt`).
 *
 * ── THE THREE FACTS FROM THE SERVER, MIRRORED AND NEVER REINVENTED ───────────────────────────────
 *
 * 1. **`INSPECTOR` is rank 37**, between DESIGNER (35) and PROFESSOR (40), and the UI label is
 *    "Inspector / Reviewer" — both words, because `canReview` already owns "review" in its
 *    RELATIONAL sense ("may review anyone strictly below me") and one word cannot be a rank and a
 *    relation at once. Both of those already live in [com.designprototype.workshop.ui.FieldPermissions];
 *    nothing here re-declares either.
 * 2. **INSPECTOR IS NOT IN `DESIGN_WORKSHOP_ROLES`**, which is a frozenset and not a rank floor, so
 *    every `/design-workshops`-family route refuses an inspector exactly as it refuses a professor.
 *    An inspector's access comes ONLY from a row in the fifth scope. Do not "fix" that by widening
 *    [com.designprototype.workshop.ui.FieldPermissions.canRunDesignWorkshops] — that predicate
 *    carries STAGE WRITES, because `load_workshop_or_404(..., for_edit=True)` performs no role check
 *    at all.
 * 3. **THE READ CARRIES `readOnly: true` ON THE WIRE**, deliberately, because both clients will
 *    eventually render this payload through the same screen as the designer's read and a screen that
 *    cannot tell the two apart offers a Save button the API answers 404 to. [dwInspectionIsReadOnly]
 *    is where that is honoured, and it fails CLOSED — see its own note.
 *
 * ── THE GATE IS A ONE-MEMBER SET, AND ADMINS ARE REFUSED. THIS IS THE SURPRISING ROW ─────────────
 *
 * `INSPECTION_ROLES = frozenset({"INSPECTOR"})` and `assert_inspection_surface` answers **403 to an
 * ADMIN and to a MASTER ADMIN by name**, with its own docstring arguing why: an admin scoped by their
 * own inspection rows sees an empty page and reads it as a broken feature, and an admin scoped by
 * "everything" turns this prefix into a second full read of the archive. So [canInspectDesignWorkshops]
 * is set membership on INSPECTOR alone, and it is the ONLY route rule on this handset whose refusal
 * is **not monotonic in rank**: a MASTER_ADMIN is refused where an INSPECTOR is admitted. Reading the
 * ladder for this row gives the wrong answer every single time — which is why the predicate is a set
 * and why `InspectionGateTest` walks all eight tiers rather than sampling.
 *
 * What an admin gets INSTEAD is the appointment screen, and the refusal copy names it.
 *
 * ── OFFLINE: AN INSPECTION IS NOT CACHED, AND THAT IS A DECISION ─────────────────────────────────
 *
 * Everything else a designer does on this handset survives a courtyard with no signal, and the whole
 * design-workshop block of [WorkshopRepository] falls back to the device rather than throwing. This
 * feature deliberately does neither, and there are three reasons, in descending order of weight:
 *
 *  1. **THE SCOPE IS A ROW ON THE SERVER, AND IT CAN BE TAKEN AWAY.** An admin who ends an
 *     inspection this morning has ended it. A cached copy would keep a fortnight of somebody else's
 *     fieldwork readable on a phone whose access was withdrawn — which is the one failure a
 *     read-only scope must not have, and it cannot be repaired by anything this app does later.
 *  2. **AN INSPECTION IS A JUDGEMENT ABOUT WHAT THE RECORD SAYS NOW.** The provenance names in this
 *     payload are resolved server-side at read time; a stale copy would have an inspector reviewing
 *     — and possibly signing off on — a state of the workshop that no longer exists, with nothing on
 *     screen to say the two had diverged.
 *  3. **THERE IS NO WRITE PATH AND NONE MAY BE INVENTED.** `saveOrQueue` will not queue a 4xx, so a
 *     queued inspector write would LOSE the record: the server has no route to accept it, the outbox
 *     has no way to retry it into existence, and the inspector would be told their note was saved.
 *     Every route in this file is a GET for that reason, and the caching decision is downstream of
 *     it: there is no draft to hold, because there is nothing to send.
 *
 * So the repository methods THROW, exactly as the three viewer-administration calls do, and the
 * screens say "this needs a connection" in words before anything is attempted rather than after it
 * fails.
 *
 * ── WHAT WAS COPIED FROM `DesignWorkshopViewers.kt`, AND WHAT DELIBERATELY WAS NOT ───────────────
 *
 * COPIED, because two spellings of one rule is how two screens drift apart: the whole-set PUT and a
 * body named for the whole set; the anti-revocation rule that an account already holding a row is
 * offered even when the server no longer offers it; explicit Save with a baseline adopted from the
 * ANSWER rather than the payload; the `truncated` contract; the id-less 404 probe; the failure
 * sentences split by status with the server's own `detail` passed through.
 *
 * NOT COPIED:
 *
 *  * **THE CREATOR CARD, AND THE CREATOR BEING SILENTLY DROPPED FROM THE PAYLOAD.** The viewers'
 *    `_deduplicate` removes the creator as a harmless no-op because they already hold what is being
 *    granted. Here the creator is REFUSED BY NAME with a 422 — "an independent review by somebody
 *    who worked on it is not a review" — so there is no creator to hold out of the picker, no card
 *    saying they always have access, and no `creatorHasRow` to re-attach. [DwInspectorSelection] is
 *    the viewers' type with that whole arm deleted.
 *  * **THE FOURTH OFFER NOTICE.** `eligible_viewers` folds in the DesignerRoster, so its `truncated`
 *    covers a cut no search can reach. `eligible_inspectors` reads no roster at all, so a cut here
 *    is always a ceiling and a ceiling is always reachable by typing. See [dwInspectorOfferNotice].
 *  * **`visible_to_clause` / `has_viewer_grant` AS NAMES.** The server refuses to spell them that
 *    way in its inspection module so that autocomplete cannot write this scope into
 *    `records._design_workshop_media_ids`, which follows the VIEWER clause on its own written
 *    instruction. Nothing here is named after the viewers' predicates either.
 *
 * Current as of 2026-08-27, against `backend/app/api/routes/design_workshop_inspections.py` (five
 * routes) and `backend/app/services/design_workshop_inspectors.py`.
 */

// --------------------------------------------------------------------------------------
// The rule
// --------------------------------------------------------------------------------------

/**
 * The roles that may hold a `DesignWorkshopInspector` row — `INSPECTION_ROLES`, byte for byte.
 *
 * A SET OF ONE, and that is the shape rather than an oversight. Every design-workshop gate in this
 * product is set membership; a rank floor written here would read "INSPECTOR and everything above
 * it", which is PROFESSOR, ADMIN and MASTER_ADMIN — two of whom already see every workshop by a
 * shorter route and the third of whom deliberately sees none.
 */
private val INSPECTION_ROLES = setOf("INSPECTOR")

/**
 * May this account reach the inspector's own read surface at all?
 *
 * **MIRRORS `assert_inspection_surface`, WHICH 403s AN ADMIN BY NAME.** Not a rank comparison, not
 * `>= RANK_INSPECTOR`, and not `isAdmin(user) || …`. Written against the string rather than against
 * the rank ladder so that this file is still correct on a build whose ladder has not been updated:
 * it simply answers false for everybody, which is the fail-closed direction.
 *
 * IN THE DATA LAYER AND NOT IN `FieldPermissions`, matching [canCreateDesignWorkshops]: the rule has
 * to be askable from a place that must not import a UI type. The typed front door for screens is
 * [com.designprototype.workshop.ui.FieldPermissions.canInspectDesignWorkshops], which calls this and
 * adds nothing.
 */
fun canInspectDesignWorkshops(role: String?): Boolean = role in INSPECTION_ROLES

// --------------------------------------------------------------------------------------
// The admin's screen: who inspects what
// --------------------------------------------------------------------------------------

/**
 * One inspection row as `inspector_payload` serves it.
 *
 * `name`/`email`/`role` travel WITH the row rather than being joined against the eligible list the
 * screen also holds — an inspector whose account has since been barred is precisely the row an admin
 * most needs to see and act on, and a join would render it as a bare cuid.
 *
 * [assignedAt] and not `grantedAt`: nothing was granted to anybody. An admin assigned an examiner to
 * a piece of work, which is why the column beside it on the server is `assignedById`. Nullable
 * because an older row may carry no timestamp, and because it is the only answer anybody has to "how
 * long has this workshop been under inspection" — `replace_inspectors` writes only the difference
 * precisely so re-saving an unchanged screen does not restamp it.
 */
@Serializable
data class DwInspectorDto(
    val userId: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",
    val assignedAt: String? = null,
)

@Serializable
data class DwInspectorListDto(val inspectors: List<DwInspectorDto> = emptyList())

/**
 * One account `GET /design-workshop-inspections/eligible-inspectors` offers.
 *
 * Deliberately NOT [UserDto], for [DwEligibleViewerDto]'s reason: the endpoint answers four fields
 * because the caller is choosing an examiner and has no business receiving the capability flags,
 * and decoding it as a `UserDto` would put a permission-bearing object on screen whose flags are all
 * defaulted to false — a lie waiting for somebody to read it.
 */
@Serializable
data class DwEligibleInspectorDto(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",
)

/**
 * The picker's options as the server serves them, and whether it had to cut the list.
 *
 * `truncated` defaults to `false` for the direction of skew this app actually has: it ships
 * separately from the API, so a handset updated ahead of the server decodes an answer with no
 * `truncated` at all, and `false` makes that phone say nothing about truncation — the only honest
 * answer when the server has not been asked to have an opinion. The shared Retrofit `Json` in
 * `data/ApiClient.kt` sets `ignoreUnknownKeys` and `coerceInputValues`, so the other direction (a
 * server one release ahead, or an explicit null) does not throw either. That was checked rather than
 * assumed: decoders in this package are NOT uniformly lenient — `WorkshopSync.kt`'s signature `Json`
 * sets no such flag — so "it is only an extra field" is not safe anywhere here.
 */
@Serializable
data class DwEligibleInspectorListDto(
    val users: List<DwEligibleInspectorDto> = emptyList(),
    val truncated: Boolean = false,
)

/**
 * One answer from the eligible-inspectors endpoint, PAIRED WITH THE QUESTION IT ANSWERS.
 *
 * [search] travels with the users rather than being held beside them on the screen, for
 * [DwEligibleViewers]'s reason: with a debounced search box there are always two terms in play — the
 * one in the field and the one this list came back for — and a screen that read the field's text
 * while rendering a list fetched for the previous keystroke would label the list with a search
 * nobody ran.
 */
data class DwEligibleInspectors(
    val users: List<DwEligibleInspectorDto> = emptyList(),
    /** The server cut the answer; see [DwEligibleInspectorListDto]. */
    val truncated: Boolean = false,
    /** The term this was fetched with, or null when the whole (capped) list was asked for. */
    val search: String? = null,
) {
    /**
     * Is this THE WHOLE ELIGIBLE SET — every account the server would offer, with nothing left out?
     *
     * ONE THING DEPENDS ON IT, and it is a sentence that must never be printed on a guess.
     * [dwInspectorChoices] marks an assigned account the eligible list does not contain as "assigned,
     * no longer eligible" — an inspector barred by the platform access list since their assignment.
     * That inference is only valid over a COMPLETE list: over a search result it would label every
     * colleague who did not match the typed term as barred, on the screen where an admin decides
     * whether to take somebody off an examination.
     */
    val complete: Boolean get() = !truncated && search == null
}

/**
 * THE ONE SENTENCE UNDER THE ADMIN'S SEARCH BOX, or null when the screen must say nothing at all.
 *
 * Silence is a real answer and the common one: a complete list has nothing to explain, and a
 * standing note about pagination on every visit is padding this app has twice been asked not to
 * have. It lives in the data layer, not in the composable, because it is a DECISION and not a
 * layout — a `when` inside a `@Composable` is only ever exercised by somebody looking at a phone.
 *
 * **THREE STATES, WHERE [dwViewerOfferNotice] HOLDS FOUR, AND THE MISSING ONE IS DELIBERATE.** That
 * function's first case is `truncated` with an EMPTY list: `eligible_viewers` folds in the
 * DesignerRoster, and when the ROSTER read is what was cut, eligible designers are absent from every
 * possible search and no narrowing can reach them. `eligible_inspectors` reads no roster at all —
 * it is one role and the platform allow-list — so `truncated` here can only mean the account list
 * hit `ELIGIBLE_INSPECTOR_LIMIT`, and a ceiling is always reachable by typing. Copying the fourth
 * sentence across would print advice about a cut that cannot happen on this endpoint.
 *
 * The three sentences that ARE here are shared verbatim with
 * `frontend/components/settings/DesignWorkshopInspectorsPanel.tsx`, which carries the same three in
 * the same order — an admin moves between the two apps, and one shared vocabulary is why the server
 * sends one flag instead of each client inventing its own wording. The first two are also word for
 * word the viewers' own, deliberately: it is the same cut with the same remedy, and two spellings of
 * one sentence is how an admin comes to believe they are two different problems.
 */
fun dwInspectorOfferNotice(offer: DwEligibleInspectors): String? = when {
    offer.truncated && offer.search == null ->
        "Too many accounts to show them all — search a name or email to reach the rest."
    offer.truncated -> "Too many matches to show them all — narrow the search."
    offer.search != null && offer.users.isEmpty() ->
        "No Inspector / Reviewer account matches that search."
    else -> null
}

/**
 * The `search` term to send, or null for "no search" — the whole (capped) list.
 *
 * **PYTHON'S `strip()`, DELIBERATELY**, for [dwViewerSearchTerm]'s reason: the server decides what
 * counts as an empty search with `(search or "").strip()`, and Python calls the no-break space
 * U+00A0 whitespace while `Char.isWhitespace` does not. Sending a lone U+00A0 — what a name pasted
 * out of a PDF leaves behind — would be a request the server treats as "no search", so this client
 * would be holding a search box with something in it beside a list of everybody.
 *
 * Clamped to the server's own `Query(None, max_length=120)`; over that the endpoint answers 422, and
 * a validation body on a person-picker reaches an admin as "The repository would not accept this" —
 * a refusal about a shape, on a screen about people. The clamp will not split a surrogate pair.
 */
fun dwInspectorSearchTerm(typed: String?): String? {
    val term = DwPy.strip(typed.orEmpty())
    if (term.isEmpty()) return null
    if (term.length <= DW_INSPECTOR_SEARCH_MAX) return term
    val cut = term.take(DW_INSPECTOR_SEARCH_MAX)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** The server's `max_length` on `search` — `Query(None, max_length=120)`. Over it, 422. */
const val DW_INSPECTOR_SEARCH_MAX = 120

/**
 * The body of `PUT /design-workshop-inspections/{id}/inspectors`: the COMPLETE intended set.
 *
 * Never built by hand. [DwInspectorSelection.payload] is the one place it comes from, because the
 * field name says "userIds" and means "everybody, and only everybody, who should be inspecting this
 * workshop when this returns".
 */
@Serializable
data class DwInspectorsBody(val userIds: List<String>)

/**
 * The server's own ceiling on one save — `MAX_DESIGN_WORKSHOP_INSPECTORS`.
 *
 * TWENTY-FIVE, and LOWER THAN THE VIEWERS' HUNDRED because the two hold different quantities: that
 * list holds a field TEAM, this one holds examiners, and an inspection panel is one person and
 * occasionally two. Mirrored so the refusal is a sentence about accounts rather than Pydantic's
 * "List should have at most 25 items", which names a shape and not a workshop.
 */
const val DW_INSPECTOR_LIMIT = 25

// --------------------------------------------------------------------------------------
// The picker's options
// --------------------------------------------------------------------------------------

/**
 * One row the picker offers.
 *
 * @property assignedButIneligible this account HOLDS an inspection today and the server, ASKED FOR
 *   THE WHOLE ELIGIBLE SET, did not offer it — an inspector barred or suspended by the platform
 *   access list since the assignment. Rendered, ticked, and marked; see [dwInspectorChoices] for why
 *   leaving it out would be a silent revocation, and why it goes unmarked when the list was searched
 *   or cut instead of complete.
 */
data class DwInspectorChoice(
    val userId: String,
    val name: String,
    val email: String,
    val role: String,
    val assignedButIneligible: Boolean = false,
)

/**
 * Everyone the picker offers: the accounts this answer names, anybody an EARLIER answer named who is
 * still ticked, plus anybody who already HOLDS an inspection row.
 *
 * **EVERY GROUP AFTER THE FIRST EXISTS TO STOP A SILENT REVOCATION.** The PUT replaces the whole
 * set, so an option that is not rendered is a row the next Save deletes:
 *
 *  - [inspectors] catches the account the platform access list barred last month. The server stops
 *    OFFERING them — deliberately, so an admin cannot assign an inspection the next sign-in refuses
 *    — while their existing row stands, and dropping them from the options would end their
 *    inspection as a side effect of adding somebody unrelated.
 *  - [retained] catches the account a SEARCH has narrowed out from under a tick. Reaching past the
 *    server's ceiling means the eligible list on screen is whatever the last search returned, so
 *    ticking a colleague found under one surname and then typing a second would drop the first from
 *    the options, from the chips, from the "will be assigned" line, and from any chance of the admin
 *    noticing before they saved.
 *
 * **THERE IS NO `creatorId` PARAMETER, UNLIKE [dwViewerChoices], AND ITS ABSENCE IS THE FEATURE.**
 * The workshop's creator is not held quietly out of this list — they are REFUSED BY NAME, with a 422
 * reading "an independent review by somebody who worked on it is not a review", and so is any
 * co-designer holding a `DesignWorkshopViewer` row. Filtering them out here would hide a MISTAKE an
 * admin needs to be told about behind a silent no-op. The two role sets are disjoint today, so the
 * case is reachable only through a promotion — a DESIGNER holding a viewer row who is later made an
 * INSPECTOR — and that is exactly the case nothing else in the codebase would notice.
 *
 * @param eligibleListComplete this answer is the WHOLE eligible set — not a search result, not cut
 *   at the ceiling. **NO DEFAULT, DELIBERATELY**, because it decides whether an assigned account
 *   absent from [eligible] is marked "assigned, no longer eligible", and that mark is a claim about
 *   the platform access list that only a complete list can support.
 *
 * Order is the server's within each group (it sorts by name, then id, so the sort key is TOTAL),
 * groups in the order above. Not re-sorted here: `sortedBy` on a Kotlin String orders by UTF-16 code
 * unit, which disagrees with Postgres's collation on exactly the names this repository is full of.
 */
fun dwInspectorChoices(
    eligible: List<DwEligibleInspectorDto>,
    inspectors: List<DwInspectorDto>,
    eligibleListComplete: Boolean,
    retained: List<DwEligibleInspectorDto> = emptyList(),
): List<DwInspectorChoice> {
    val choices = ArrayList<DwInspectorChoice>(eligible.size + retained.size + inspectors.size)
    val seen = HashSet<String>()
    (eligible + retained).forEach { person ->
        if (person.id.isBlank() || !seen.add(person.id)) return@forEach
        choices += DwInspectorChoice(
            userId = person.id,
            name = person.name,
            email = person.email,
            role = person.role,
        )
    }
    inspectors.forEach { row ->
        if (row.userId.isBlank() || !seen.add(row.userId)) return@forEach
        choices += DwInspectorChoice(
            userId = row.userId,
            name = row.name,
            email = row.email,
            role = row.role,
            // OFFERED EITHER WAY — that is what stops the revocation — but only MARKED when the
            // absence proves something. Over a search result or a cut list it proves nothing.
            assignedButIneligible = eligibleListComplete,
        )
    }
    return choices
}

// --------------------------------------------------------------------------------------
// The pending set
// --------------------------------------------------------------------------------------

/**
 * What the admin has ticked, against what the repository currently holds.
 *
 * **SAVING IS EXPLICIT, and this type is what makes that possible.** A picker that wrote through on
 * every toggle would mean an admin who mis-taps a name has already ended somebody's examination for
 * as long as the round trip takes.
 *
 * SIMPLER THAN [DwViewerSelection] BY ONE WHOLE CONCEPT, and the difference is not tidying. That
 * type carries `creatorId` and `creatorHasRow` because the viewers PUT silently drops the creator
 * from any payload naming them, so the screen has to hold their row out of the diff on both sides
 * and re-attach it on the way out. There is no such row here: the creator cannot be an inspector at
 * all, and naming them is a 422 rather than a no-op. So [payload] is exactly what is ticked.
 */
data class DwInspectorSelection(
    val baseline: Set<String> = emptySet(),
    val selected: Set<String> = emptySet(),
) {
    /** Ticked and not yet saved. */
    val added: Set<String> get() = selected - baseline

    /** Saved, unticked, and whose inspection ends the moment Save is pressed. */
    val removed: Set<String> get() = baseline - selected

    val dirty: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()

    /** How many accounts would be inspecting this workshop after a save. */
    val resultingCount: Int get() = selected.size

    /** Over the server's ceiling, so the save is refused here with a sentence about accounts. */
    val overLimit: Boolean get() = payload().size > DW_INSPECTOR_LIMIT

    /** The COMPLETE set to send. Exactly what is ticked — see the class note on why nothing is added. */
    fun payload(): List<String> = selected.toList()

    fun withSelection(ids: Set<String>): DwInspectorSelection = copy(selected = ids - "")

    fun discard(): DwInspectorSelection = copy(selected = baseline)

    companion object {
        /**
         * Adopt what the server says it holds as the new truth.
         *
         * Called both after the initial load AND after every save, and the second one is the point:
         * the ANSWER becomes the baseline, never the payload that was sent. Another admin may have
         * assigned somebody between this screen opening and Save being pressed, and a client that
         * treated its own request as the outcome would show a panel nobody has.
         */
        fun adopt(rows: List<DwInspectorDto>): DwInspectorSelection {
            val held = rows.map { it.userId }.filter { it.isNotBlank() }.toSet()
            return DwInspectorSelection(baseline = held, selected = held)
        }
    }
}

// --------------------------------------------------------------------------------------
// Reading a failure honestly
// --------------------------------------------------------------------------------------

/**
 * Is this failure "the server has no such route" rather than "no such workshop"?
 *
 * ONLY ever asked of `GET /design-workshop-inspections/eligible-inspectors`, and only that call. It
 * is the one request in the family that carries no id, so a 404 from it cannot mean a missing record
 * and therefore means a missing ROUTE — this repository ships its halves separately, so a phone
 * updated from the store before the API rolled out is a real state rather than a hypothesis. On a
 * server without the route FastAPI matches the path against `GET /{workshop_id}` — declared second
 * in the module precisely so it does not swallow the literal one — and answers 404 "Record not
 * found". Asking the same question of `/{id}/inspectors` would be unanswerable: a 404 there is
 * genuinely either.
 *
 * @param status the HTTP status, or null when nothing answered at all.
 */
fun dwInspectionAdministrationMissing(status: Int?): Boolean = status == 404

/**
 * Which of the two things was being attempted when it failed — see [dwInspectionFailureMessage].
 *
 * It exists because "nothing has changed" is a CLAIM, and it is only true for one of them. A read
 * that fails changed nothing by construction; a write that fails may have changed everything, part
 * of it, or nothing at all, and which of those it was is not always knowable from this side.
 */
enum class DwInspectionAttempt { READ, SAVE }

/**
 * What went wrong, in words an administrator — or an inspector — can act on.
 *
 * **A REFUSAL IS NOT A DISCONNECTION, AND A DISCONNECTION IS NOT A REFUSAL.** This app has shipped
 * that confusion twice (the report download and the workshop create, both in SESSION_HANDOVER.md),
 * and it costs the same thing each time. So the arms below are split by STATUS first and the
 * server's own `detail` is passed through wherever there is one, because that text is the only thing
 * that knows WHICH account was refused and why.
 *
 * **THE 403 ARM IS THE ONE THAT DIFFERS FROM THE VIEWERS', AND IT DIFFERS BECAUSE THE RULE DOES.**
 * There, a 403 means "you are not an admin" and the remedy is to ask one. Here a 403 can mean either
 * of two opposite things depending on which door was knocked on — an admin refused the inspector's
 * READ surface, or a non-admin refused the appointment routes — and the server's own
 * `NOT_AN_INSPECTOR_DETAIL` is written to name the other door for exactly that reason. It is passed
 * through first and the clause after it says what this client knows: that the two doors are
 * different, and that neither is a fault.
 *
 * **NO ARM CLAIMS "NOTHING WAS CHANGED" UNLESS IT IS TRUE.** For a 4xx it is: `replace_inspectors`
 * validates every id to completion before it touches a row. For a dropped connection or a 5xx on a
 * SAVE it is not: the request may have landed and the answer been lost, and that function issues its
 * `delete_many` and its `create_many` as two statements — a fault between them leaves the removals
 * applied and the additions not.
 *
 * Pure, and split from the Retrofit exception on purpose: reading the error body CONSUMES it, so
 * `status`/`serverMessage` are extracted by the one caller holding the `HttpException`, which leaves
 * every sentence here assertable in a JVM test with no HTTP stack.
 *
 * @param status the HTTP status, or null for "nothing answered": no signal, DNS, a socket dropped.
 * @param serverMessage FastAPI's `detail`, already unwrapped by `apiErrorMessage`, or null.
 */
fun dwInspectionFailureMessage(
    status: Int?,
    serverMessage: String?,
    attempt: DwInspectionAttempt = DwInspectionAttempt.READ,
): String {
    val said = serverMessage?.trim()?.takeIf { it.isNotEmpty() }
    val unknownOutcome = attempt == DwInspectionAttempt.SAVE
    return when {
        status == null ->
            "This phone could not reach the repository. An inspection is read from the server every " +
                "time, so unlike the 22 stages it cannot be opened without a connection. " +
                if (unknownOutcome) {
                    "The request may still have landed — open this screen again on a connection to " +
                        "see who is inspecting this workshop before you save anything else."
                } else {
                    "Nothing has been changed."
                }

        status == 401 ->
            "This phone is no longer signed in, so nothing was sent and nothing has changed. Sign " +
                "in again and try once more."

        // The server's own words FIRST, because they name which of the two doors was refused and
        // where the other one is; the clause after says that this is a rule rather than a fault.
        status == 403 ->
            said.asInspectionSentence() +
                "The inspection surface and the screen that appoints inspectors are two different " +
                "doors: only an Inspector / Reviewer can read a workshop under inspection, and only " +
                "an admin decides who inspects what. Neither is a fault. Nothing was changed."

        status == 404 ->
            said.asInspectionSentence() +
                "This workshop is not open to you. Either it is not assigned to you to inspect, or " +
                "it has been deleted since this screen was opened. Nothing was changed."

        // The server's own 422 already names the offending account and already ends with "Nothing
        // was changed." — see `_assert_every_id_may_inspect`, whose refusals STACK, so this may be
        // several sentences naming several people. Repeating either half would be this screen
        // talking over the one message written for exactly this moment.
        status == 422 ->
            "The repository would not accept this. " +
                (said ?: "One of the accounts named cannot be assigned this inspection. Nothing was changed.")

        status >= 500 ->
            (said ?: "The repository had a problem answering.") +
                " This is not a connection problem. " +
                if (unknownOutcome) {
                    "Part of the change may have landed — open this screen again to see who is " +
                        "inspecting this workshop."
                } else {
                    "Nothing was changed; it is worth trying again in a moment."
                }

        else -> said ?: "That did not go through."
    }
}

/**
 * The server's sentence with a full stop and a space after it, or nothing at all.
 *
 * FastAPI's `detail` is written by whoever raised it and is inconsistent about its final punctuation
 * — `require_admin` says "Admin access required" with no stop, while `NOT_AN_INSPECTOR_DETAIL` ends
 * in one. Concatenating either directly onto the clause that follows produces "Admin access required
 * The inspection surface …", which reads as a truncated string and makes a reader distrust the whole
 * message.
 *
 * A private twin of the viewers' `asSentence`, spelled here rather than shared: that one is
 * file-private in `DesignWorkshopViewers.kt`, and a shared spelling belongs in a change that moves
 * both at once rather than in the file that happens to need it second.
 */
private fun String?.asInspectionSentence(): String = when {
    this == null -> ""
    isEmpty() -> ""
    last() in ".!?:" -> "$this "
    else -> "$this. "
}

// --------------------------------------------------------------------------------------
// The inspector's own read
// --------------------------------------------------------------------------------------

/**
 * ONE WORKSHOP UNDER INSPECTION — `GET /design-workshop-inspections/{id}`.
 *
 * ── ITS OWN TYPE, AND NOT [DesignWorkshopDetailDto] WITH A NULLABLE FLAG ─────────────────────────
 *
 * The server builds this payload from the same `workshop_summary` and the same `_stages_payload` the
 * designer's read uses, so one shared DTO would decode both perfectly well. It is deliberately two
 * types, and the reason is [dwInspectionIsReadOnly]: absence of `readOnly` MEANS read-only on this
 * route, and it means the exact opposite on the designer's, which carries no such key either. One
 * type would put a field on the designer's payload whose only honest reading is "ask somebody which
 * route this came from" — and the first person to answer that question wrong draws a Save button
 * over a prefix that has no write route at all. Two types make the question unaskable.
 *
 * ── WHAT IS DELIBERATELY ABSENT FROM THIS PAYLOAD, AND WHY IT IS NOT AN OMISSION ─────────────────
 *
 *  * **`transcripts`.** The designer's read fills that key from `owned_or_granted_where`, which
 *    admits an account below professor only for media it uploaded, media whose owner granted it a
 *    `DataAccessGrant`, or media tagged to a workshop it holds through `DesignWorkshopViewer` /
 *    `createdById`. An inspector holds none of those. Calling it here would cost a query to produce
 *    an empty list and — worse — would put this route on the media path at all, so that the next
 *    person widening that predicate widens this surface without noticing.
 *  * **`dictationConsentByName`.** Resolved by the designer's single-record read alone.
 *  * **Anything that writes.** There is no `for_edit`, no PATCH twin, no stage save and no report
 *    route on this prefix, and `load_inspectable_workshop_or_404` takes no `for_edit` parameter — so
 *    there is no argument a request could carry that turns this read into a write.
 *
 * Whether an inspector SHOULD see the workshop's photographs and recordings is an owner's decision
 * that has not been made. Today the answer is no, stated in one place; see [DwInspectionReading.Media],
 * which says the true thing on screen rather than drawing a tile that cannot load.
 *
 * ── PROVENANCE NAMES *ARE* RESOLVED, AND THEY ARE MOST OF THE POINT ──────────────────────────────
 *
 * "Who wrote this field" is what an inspection is largely for, and the ids without names are
 * unreadable. They arrive inside [StageBucketDto.provenance] as the same [DwFieldStampDto] the stage
 * form already draws, so an inspector and the designer being inspected read one interpretation and
 * one authorship sentence.
 *
 * EVERY FIELD DEFAULTS, so a handset a fortnight behind the server still decodes an answer that has
 * grown a key, and a handset AHEAD of the server still decodes one that has not.
 */
@Serializable
data class DwInspectionDetailDto(
    val id: String = "",
    val title: String = "",
    val templateId: String = "DCH_STANDARD",
    val status: String = "DRAFT",
    val workshopCode: String? = null,
    val craftName: String? = null,
    val clusterName: String? = null,
    val state: String? = null,
    val district: String? = null,
    val venue: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val designerName: String? = null,
    val implementingAgency: String? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val schemaVersion: String = "",
    /** See [DesignWorkshopDetailDto.customSchemaVersion]. Never folded into [schemaVersion]. */
    val customSchemaVersion: String = "",
    val stages: Map<String, StageBucketDto> = emptyMap(),
    /**
     * The per-stage scores, keyed by stage key.
     *
     * **THIS IS THE ONLY PLACE A SCORE COMES FROM ON THIS SCREEN.** [StageBucketDto.completeness]
     * exists on the type and is EMPTY on this payload — only the single-stage route attaches one,
     * and an inspector cannot reach that route — so reading it instead would print "Nothing
     * recorded" beside every stage of a finished workshop.
     */
    val completeness: Map<String, StageCompletenessDto> = emptyMap(),
    /**
     * The wire's own statement that this payload may not be written back. See
     * [dwInspectionIsReadOnly], which is the only thing that may read it.
     *
     * NULLABLE, and null is not "unknown, carry on": it is a server that predates the key, and it is
     * treated as `true`.
     */
    val readOnly: Boolean? = null,
)

/**
 * IS THIS PAYLOAD A READ? Absent means yes.
 *
 * **THE ABSENT CASE IS THE WHOLE FUNCTION**, and it is the OPPOSITE of how `truncated` is treated in
 * [DwEligibleInspectorListDto]. There, an unknown flag must say nothing rather than cry truncation
 * at a complete list, so absence falls to the quiet side. Here the quiet side is the DANGEROUS one:
 * a deployment that predates the key hands a screen a payload with no flag, and a `== true` test
 * would then draw a Save button on a prefix that has no write route at all — the exact bug the
 * boolean was put on the wire to prevent. So absence fails CLOSED.
 *
 * `!= false` and not a hardcoded `true`: an explicit `false` is honoured, because that is the value
 * that will arrive the day this screen is shared with the designer's read, and a function that
 * ignored the flag would have to be found and rewritten by somebody who did not know it existed.
 * **That day this predicate gains a second argument naming which route the payload came from** — the
 * designer's read carries no `readOnly` key either, and absence cannot mean two things. This is the
 * one place that change goes, and [DwInspectionDetailDto] being its own type is what keeps the
 * ambiguity from existing before then.
 */
fun dwInspectionIsReadOnly(readOnly: Boolean?): Boolean = readOnly != false

/**
 * Every page the DESIGNER's workshop has, named rather than remembered.
 *
 * Each of these is a real screen on this handset under a workshop id, and each is reached through a
 * route that answers 404 to an inspector, because `load_workshop_or_404` refuses anybody outside
 * `DESIGN_WORKSHOP_ROLES` before it looks at the row. They are enumerated so that a renderer asks
 * [dwInspectionMayOpen] once rather than nine screen authors reasoning it out again — and so that a
 * screen ADDED to the workshop is one entry away from being correctly refused here, instead of
 * shipping as a control that 404s.
 *
 * [STAGES] is the load-bearing one: the inspection view draws a heading per stage, and the obvious
 * thing to make each heading is a tap through to the stage screen a designer edits it on.
 *
 * The nine mirror `DESIGN_WORKSHOP_DESTINATIONS` in `frontend/lib/designWorkshopInspections.ts`
 * one for one, which is why READINESS and CUSTOM_SECTIONS are here although this handset reaches
 * both from inside the stage flow rather than as pages of their own: the register is of ROUTES an
 * inspector is refused, not of this client's navigation.
 */
enum class DwInspectionDestination {
    STAGES,
    PHOTOS,
    REPORT,
    READINESS,
    CUSTOM_SECTIONS,
    AI_LAYERS,
    CODES,
    PROVENANCE,
    SKETCHES_AND_PROTOTYPES,
}

/**
 * May a screen over this payload offer that destination? On a read, never.
 *
 * A function rather than a constant `false` so that the CALL SITE reads as a question about the
 * payload it holds — and so that the day `readOnly` can be false the answer changes in one place
 * rather than in nine `if`s nobody can enumerate. The [destination] argument is unused today and is
 * not decoration: it is what makes the call site name which control it is about to draw, and it is
 * what `InspectionReadOnlyTest` walks — [DwInspectionDestination] in full, asserting every one is
 * refused, which is what stops a tenth being added and quietly linked from here.
 */
@Suppress("UNUSED_PARAMETER")
fun dwInspectionMayOpen(destination: DwInspectionDestination, readOnly: Boolean?): Boolean =
    !dwInspectionIsReadOnly(readOnly)

// --------------------------------------------------------------------------------------
// Reading one stored answer, for a surface that cannot edit it
// --------------------------------------------------------------------------------------

/**
 * What one field of one row has to say on a read.
 *
 * THREE ANSWERS AND NOT ONE STRING, because the third is a different FACT and collapsing it into a
 * sentence would put a lie in the same slot as a value. [Media] is not "this field is empty" and it
 * is not "here is the photograph": it is "this field holds N files and this read does not carry
 * them", which the screen says in its own words beside the field's label.
 */
sealed interface DwInspectionReading {
    data class Text(val text: String) : DwInspectionReading
    data object Empty : DwInspectionReading
    data class Media(val count: Int) : DwInspectionReading
}

/**
 * ONE STORED ANSWER, AS AN INSPECTOR SHOULD SEE IT.
 *
 * ── WHY THIS EXISTS RATHER THAN MOUNTING `FieldRenderer` DISABLED ────────────────────────────────
 *
 * The obvious reuse is the designer's own control with `enabled = false`, and it is refused for
 * three reasons rather than for tidiness:
 *
 *  1. **IT DRAWS CONTROLS THAT WOULD 404.** `FieldRenderer` mounts the media capture card, the
 *     reference picker, the dictation button, the sketch-rectify panel and an embedded record form.
 *     Every one of those reaches a route an inspector is refused. A disabled upload button is still
 *     an upload button on a screen whose entire premise is that nothing here can be written.
 *  2. **IT CANNOT RESOLVE THE MEDIA ANYWAY.** `GET /media/{id}` is entitled per file and an
 *     inspector holds no upload, no `DataAccessGrant` and no viewer row, so every image tile would
 *     render its "could not be read" state — which is indistinguishable from a photograph that
 *     failed to load, and is not what happened. [DwInspectionReading.Media] says the true thing.
 *  3. **IT NEEDS A DRAFT TO WRITE INTO.** Every capture control on this handset edits a
 *     [WorkshopDraft] through the draft store, and there is no draft here — this workshop was never
 *     opened for editing on this phone and must not be. Faking one is how a read-only surface
 *     acquires a write path by accident, and the draft store is what `WorkshopSync` pushes.
 *
 * ── WHAT IT DOES REUSE, WHICH IS EVERY PART THAT INTERPRETS A VALUE ──────────────────────────────
 *
 * [DwValues.isFilled], [DwValues.text], [DwValues.list], [DwValues.bool] and
 * [com.designprototype.workshop.report.summary] are the same functions the stage form and the
 * offline search index read a value through, so an inspector and the designer who typed it read one
 * interpretation of the bytes. Nothing here re-decides what a MONEY value is, what counts as filled,
 * or what a narrative says.
 *
 * [entity] and [row] are taken rather than just the value because a REF is resolved from the SIBLING
 * keys hydration wrote onto the same row — the artisan's name sits on the row beside the id — which
 * is the only way to name a linked record without a lookup this surface cannot make.
 */
fun dwInspectionFieldReading(
    schema: SchemaResponse,
    entity: EntityDto,
    field: FieldDto,
    row: Map<String, JsonElement>,
): DwInspectionReading {
    val value = row[field.key]
    val type = DwFieldType.of(field.type)

    if (type.isMedia) {
        // COUNTED BEFORE THE FILLED TEST, because "no photographs" and "photographs this read does
        // not carry" are the two states this whole branch exists to keep apart.
        val count = when {
            value is JsonArray -> DwValues.list(value).size
            DwValues.isFilled(value) -> 1
            else -> 0
        }
        return if (count > 0) DwInspectionReading.Media(count) else DwInspectionReading.Empty
    }

    if (!DwValues.isFilled(value)) return DwInspectionReading.Empty

    return when (type) {
        // A generous limit rather than the 80 a row TITLE uses: this is the narrative an inspection
        // is largely about, and truncating it to a title's length would hide the paragraph being
        // inspected. Still bounded — `summary` appends an ellipsis when it cuts, which is the only
        // signal there is that more was written, so it is not stripped at the call site.
        DwFieldType.RICH_TEXT ->
            richSummary(value, 2000).takeIf { it.isNotEmpty() }
                ?.let { DwInspectionReading.Text(it) } ?: DwInspectionReading.Empty

        // `DwValues.bool` answers null for a token neither list knows, and null is NOT false — a
        // value nobody can read as yes or no is not an answer of "no". It falls to the raw text
        // rather than being asserted either way.
        DwFieldType.BOOL -> when (DwValues.bool(value)) {
            true -> DwInspectionReading.Text("Yes")
            false -> DwInspectionReading.Text("No")
            null -> DwValues.text(value).trim().takeIf { it.isNotEmpty() }
                ?.let { DwInspectionReading.Text(it) } ?: DwInspectionReading.Empty
        }

        DwFieldType.ENUM ->
            DwInspectionReading.Text(dwInspectionOptionLabel(schema, field, DwValues.text(value).trim()))

        DwFieldType.MULTI_ENUM -> DwInspectionReading.Text(
            DwValues.list(value).joinToString(" · ") { dwInspectionOptionLabel(schema, field, it) }
        )

        DwFieldType.TAGS -> DwInspectionReading.Text(DwValues.list(value).joinToString(" · "))

        DwFieldType.GEO -> {
            val point = DwValues.geo(value)
            if (point == null) {
                DwInspectionReading.Empty
            } else {
                // Six decimals is about 10 cm — more than a handset fix is worth and enough that two
                // villages are never one number. The accuracy is printed when the device reported
                // it, because a fix with a 2 km radius and one with a 5 m radius are different
                // evidence about the same claim.
                val accuracy = ((value as? JsonObject)?.get("accuracy") as? JsonPrimitive)
                    ?.doubleOrNull?.takeIf { it.isFinite() }
                val at = String.format(Locale.ROOT, "%.6f, %.6f", point.first, point.second)
                DwInspectionReading.Text(
                    if (accuracy == null) at else "$at (±${Math.round(accuracy)} m)"
                )
            }
        }

        DwFieldType.REF -> {
            val named = dwInspectionReferenceHint(entity, field, row)
            // NEVER the raw id as a fallback. A cuid asks an inspector to recognise a record they
            // cannot possibly recognise, and on this surface there is no picker to open and check it
            // against.
            DwInspectionReading.Text(named.ifEmpty { "A linked record this read cannot name" })
        }

        else -> {
            val text = DwValues.text(value).trim()
            if (text.isEmpty()) {
                DwInspectionReading.Empty
            } else {
                DwInspectionReading.Text(if (field.unit.isNotEmpty()) "$text ${field.unit}" else text)
            }
        }
    }
}

/**
 * One ENUM token as a human sees it, falling back to the raw token rather than dropping it.
 *
 * `ifEmpty` and not a null check: the server omits `options` entirely at its default, so an absent
 * list and an empty one are the same payload here and both have to fall through to the shared table.
 *
 * A token with no option left in the registry is NOT dropped. It is a real answer a designer gave
 * against a list that has since changed, and hiding it from an INSPECTION is the one place that
 * would be least forgivable — an inspector reads this precisely to check what was recorded. The raw
 * token is the only name the answer still has, so it is printed.
 */
private fun dwInspectionOptionLabel(schema: SchemaResponse, field: FieldDto, token: String): String {
    val options = field.options.ifEmpty { schema.enums[field.enumName].orEmpty() }
    return options.firstOrNull { it.value == token }?.label ?: token
}

/**
 * The name a REF stands for, read off the sibling keys hydration wrote onto the same row, or "" when
 * this payload cannot honestly produce one.
 *
 * A DELIBERATE NARROWING of `DwWorkshopSearch`'s two-step resolution, and the narrowing is what
 * makes it correct here. That one first looks the referenced ROW up in the draft — a prototype names
 * its sketch, and the sketch row is in the same draft — and only then falls back to the hydrated
 * name. There is no draft on this surface and the referenced row may be on a stage this reader has
 * not walked, so the row lookup is not available; what IS available is the name the picker copied
 * onto this row at the moment the designer chose it, which is a fact stored beside the id.
 *
 * THE FOUR SPELLINGS ARE THE SERVER'S AND THE WEB'S, in order: `<stem>Name`, `documented<Stem>Name`,
 * `<stem>Title` (for a model whose label column is a title — `interviewRef` → `interviewTitle`), and
 * `documentedFor` ONLY for a cascade PARENT. That last condition is not optional: `processStep`
 * declares BOTH `documentedFor` and its own `name`, and reading `documentedFor` unconditionally
 * would label every step's process with its PRODUCT — a missing hint turned into a wrong one, which
 * is the worse outcome. Whether a field is a cascade parent is read off the registry
 * (`refFilterBy`), so nothing here needs editing when the next cascade is declared.
 *
 * `name` is the last candidate rather than the first for the same reason.
 */
private fun dwInspectionReferenceHint(
    entity: EntityDto,
    field: FieldDto,
    row: Map<String, JsonElement>,
): String {
    if (field.refModel.isEmpty()) return ""
    val stem = field.key.removeSuffix("Ref")
    val cascadeParent = entity.liveFields.any { it.refFilterBy == field.key }
    val candidates = buildList {
        add(stem + "Name")
        add("documented" + stem.replaceFirstChar { it.uppercase() } + "Name")
        add(stem + "Title")
        if (cascadeParent) add("documentedFor")
        add("name")
    }
    for (key in candidates) {
        val target = entity.field(key)?.takeIf { !it.deprecated } ?: continue
        val targetType = DwFieldType.of(target.type)
        if (targetType != DwFieldType.TEXT && targetType != DwFieldType.LONG_TEXT) continue
        val text = DwPy.strip(DwValues.text(row[key]))
        if (text.isNotEmpty()) return text
    }
    return ""
}

/**
 * How many of [fields] this row left unanswered, and how many were asked.
 *
 * SAID ON SCREEN RATHER THAN SILENTLY OMITTED, which is this repository's most repeated bug class in
 * the other direction: a read that lists only the boxes with something in them tells an inspector
 * nothing about the ones that are blank, and "unanswered" is most of what an inspection is looking
 * for. The screen prints the pair; drawing forty empty rows instead would bury the answers that
 * exist.
 *
 * MEDIA COUNTS AS ANSWERED when the field holds files, because it does — the files exist and this
 * read simply does not carry them. Counting them as unanswered would accuse a designer of a gap
 * that is a limit of the payload.
 */
fun dwInspectionUnansweredCount(
    schema: SchemaResponse,
    entity: EntityDto,
    fields: List<FieldDto>,
    row: Map<String, JsonElement>,
): Int = fields.count { dwInspectionFieldReading(schema, entity, it, row) is DwInspectionReading.Empty }
