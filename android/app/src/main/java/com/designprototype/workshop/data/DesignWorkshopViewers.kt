package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

/**
 * Who — besides the designer who pressed "New" — may open one design & prototype workshop, from the
 * handset.
 *
 * ── WHAT THIS FINISHES ───────────────────────────────────────────────────────────────────────────
 *
 * The phone already honours a grant on the READ side: `visibleDesignWorkshops` walks every page of
 * `GET /design-workshops` precisely so a workshop somebody was let into is not lost off the end of
 * page one (see [DesignWorkshopListing]). What the phone could not do was ISSUE one. An admin
 * standing in a courtyard on day three of a fortnight, next to the second designer who cannot open
 * the record at all, had to find a laptop.
 *
 * ── THE PERMISSION, READ OFF THE ROUTES AND NOT GUESSED ──────────────────────────────────────────
 *
 * There are exactly two rules here and they are DIFFERENT rules. Conflating them is the whole trap.
 *
 * 1. **WHO MAY ADMINISTER — `require_admin`.** All three routes in
 *    `backend/app/api/routes/design_workshop_viewers.py` are `Depends(require_admin)`, which is
 *    `deps.is_admin`: role in {ADMIN, MASTER_ADMIN}, and nothing else. **Not** the creator of the
 *    workshop, and **not** `can_run_design_workshops` — a DESIGNER cannot hand out access to their
 *    own workshop, deliberately. `services/design_workshop_viewers.py` argues the point at length:
 *    letting the owner choose their own readers freezes a workshop's access the moment the owner
 *    leaves, which is the handover problem the whole feature exists to solve, reintroduced one level
 *    up. An admin's grant has an administrator behind it who is still here.
 *    The Kotlin mirror is `FieldPermissions.isAdmin`, and NOTHING in this file re-derives it.
 *
 * 2. **WHO MAY BE GRANTED — the server's set, fetched, never re-derived.**
 *    `GET /design-workshops/eligible-viewers` answers with the accounts a viewer row may name:
 *    `DESIGN_WORKSHOP_ROLES` = {DESIGNER, ADMIN, MASTER_ADMIN} — a SET and not a rank threshold, so
 *    a PROFESSOR is excluded despite outranking a designer — narrowed further by the DesignerRoster,
 *    because a designer whose roster row is suspended cannot sign in at all. That second clause is
 *    invisible from here and would drift within one release if this client tried to compute it. The
 *    drift shows as an admin granting access that the next sign-in refuses, with nothing on either
 *    screen saying why. So the picker's options come off the wire, and the write is refused by the
 *    server a second time regardless — a picker is a suggestion, the write is the rule.
 *
 * ── THREE THINGS ABOUT THIS WIRE THAT WILL TRIP A READER ─────────────────────────────────────────
 *
 * - **The PUT REPLACES the whole set.** There is no add route and no remove route; taking somebody
 *   off is sending the list WITHOUT them. So a caller that posts only what it just ticked has
 *   silently revoked everybody else. [DwViewerSelection.payload] is the only thing in this app that
 *   builds that body, and it is named for the whole set for that reason.
 * - **The creator is not in this set's gift.** They hold the workshop through `createdById`, a
 *   different clause entirely, and `_deduplicate` drops them from any payload that names them. An
 *   empty viewer list therefore means "nobody but the creator", never "nobody at all", and any
 *   screen over this has to say so — an admin who reads the empty list as the complete answer will
 *   conclude they have locked a designer out when they have not.
 * - **A 404 from `/design-workshops/eligible-viewers` means the DEPLOYMENT predates the feature.**
 *   That endpoint carries no id, so a 404 from it cannot mean a missing record; on a server without
 *   the route FastAPI matches it against `GET /design-workshops/{workshop_id}` and answers 404
 *   "Record not found". [dwViewerAdministrationMissing] exists to tell that apart, and is pinned to
 *   the id-less call because asking it of `/{id}/viewers` would be unanswerable.
 *
 * ── AND IT NEEDS A CONNECTION, WHICH IN THIS APP IS WORTH SAYING OUT LOUD ─────────────────────────
 *
 * Everything else a designer does on this handset works in a courtyard with no signal: the 22 stages
 * are a local draft, the report is generated on the device, the workshop itself can be created
 * offline. Access is not that kind of fact. A grant is a row in the repository's database that a
 * colleague's sign-in reads on the other side of the country, and a queued grant would be an app
 * telling an admin their co-designer is in while the co-designer is still refused. So the whole
 * surface refuses honestly when it cannot reach the server (see [dwViewerFailureMessage]) rather
 * than accepting the edit and stranding it — and it says so on screen rather than failing obscurely.
 */

// --------------------------------------------------------------------------------------
// The wire
// --------------------------------------------------------------------------------------

/**
 * One account with a viewer row, as `GET /design-workshops/{id}/viewers` serves it.
 *
 * `name`/`email`/`role` travel WITH the row rather than being joined against the eligible list this
 * screen also holds, and that is load-bearing rather than convenient: a viewer whose account has
 * since dropped off the eligible list — a designer suspended last month — is exactly the row an
 * admin most needs to see and act on, and a join against the eligible list would render it as a bare
 * cuid.
 */
@Serializable
data class DwViewerDto(
    val userId: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",
    /** When the row was written. Null-tolerant: an older row may carry no timestamp. */
    val grantedAt: String? = null,
)

@Serializable
data class DwViewerListDto(val viewers: List<DwViewerDto> = emptyList())

/**
 * One account `GET /design-workshops/eligible-viewers` offers.
 *
 * Deliberately NOT [UserDto]. The endpoint answers four fields because the caller is choosing a
 * reader and has no business receiving the capability flags or the auth provider — and decoding it
 * as a `UserDto` would put a permission-bearing object on screen whose flags are all defaulted to
 * false, which is a lie waiting for somebody to read it.
 */
@Serializable
data class DwEligibleViewerDto(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",
)

@Serializable
data class DwEligibleViewerListDto(val users: List<DwEligibleViewerDto> = emptyList())

/**
 * The body of `PUT /design-workshops/{id}/viewers`: the COMPLETE intended membership.
 *
 * Never built by hand. [DwViewerSelection.payload] is the one place it comes from, because the field
 * name says "userIds" and means "everybody, and only everybody, who should hold a row when this
 * returns".
 */
@Serializable
data class DwViewersBody(val userIds: List<String>)

/**
 * The server's own ceiling on one save — `MAX_DESIGN_WORKSHOP_VIEWERS` in
 * `backend/app/schemas/design_workshop_viewers.py`.
 *
 * Mirrored so the refusal is a sentence about designers rather than Pydantic's "List should have at
 * most 100 items", which names a shape and not a workshop. A real workshop is run by four people, so
 * nobody reaches this by working.
 */
const val DW_VIEWER_LIMIT = 100

// --------------------------------------------------------------------------------------
// The picker's options
// --------------------------------------------------------------------------------------

/**
 * One row the picker offers, already resolved to the two lines a handset draws.
 *
 * @property grantedButIneligible this account HOLDS a row today and the server no longer offers it
 *   — a designer suspended from the roster since the grant. Rendered, ticked, and marked; see
 *   [dwViewerChoices] for why leaving it out would be a silent revocation.
 */
data class DwViewerChoice(
    val userId: String,
    val name: String,
    val email: String,
    val role: String,
    val grantedButIneligible: Boolean = false,
)

/** A person's name without ever leaking an id: their name, else their email, else a neutral word. */
fun dwPersonLabel(name: String?, email: String?): String =
    name?.trim().orEmpty().ifBlank { email?.trim().orEmpty() }.ifBlank { "Unknown user" }

/**
 * Everyone the picker offers: the eligible accounts, plus anybody who already HOLDS a row and has
 * since dropped off that list. The creator is in neither — their access is not on offer here.
 *
 * **THE SECOND GROUP IS THE LOAD-BEARING ONE.** The PUT replaces the whole set, so an option that is
 * not rendered is a row the next Save silently deletes. A designer whose roster row was suspended
 * last month is precisely that person: the server stops OFFERING them while their existing row
 * stands, and dropping them from the options would revoke them as a side effect of adding somebody
 * unrelated — an admin ticking one new name and taking a colleague off the workshop without either
 * of them ever seeing it happen.
 *
 * Order is the server's for the eligible group (it sorts by name), then the extras. Not re-sorted
 * here: `sortedBy` on a Kotlin String orders by UTF-16 code unit, which disagrees with Postgres's
 * collation on exactly the names this repository is full of, and a picker whose order changes
 * between the phone and the browser is a picker two admins describe differently.
 */
fun dwViewerChoices(
    eligible: List<DwEligibleViewerDto>,
    viewers: List<DwViewerDto>,
    creatorId: String,
): List<DwViewerChoice> {
    val choices = ArrayList<DwViewerChoice>(eligible.size + viewers.size)
    val seen = HashSet<String>()
    eligible.forEach { person ->
        if (person.id.isBlank() || person.id == creatorId || !seen.add(person.id)) return@forEach
        choices += DwViewerChoice(
            userId = person.id,
            name = person.name,
            email = person.email,
            role = person.role,
        )
    }
    viewers.forEach { row ->
        if (row.userId.isBlank() || row.userId == creatorId || !seen.add(row.userId)) return@forEach
        choices += DwViewerChoice(
            userId = row.userId,
            name = row.name,
            email = row.email,
            role = row.role,
            grantedButIneligible = true,
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
 * every toggle would mean an admin who mis-taps a name has already revoked it for as long as the
 * round trip takes, and on a rural link that is seconds — on a screen whose entire subject is
 * whether a colleague can open a fortnight of work. So the picker edits [selected], the screen says
 * what is unsent, and one button sends it.
 *
 * @property creatorId the workshop's `createdById`. Held out of [baseline] and [selected] on both
 *   sides, so the creator's own access can never read as an unsaved change.
 * @property creatorHasRow the server reported a viewer row for the creator. It is re-attached by
 *   [payload] rather than re-derived: the PUT replaces the whole set, and dropping a row the server
 *   itself told us about is the one edit this screen may not make. (The server drops the creator
 *   from any payload naming them — `_deduplicate` — so this can only ever preserve a row some other
 *   path wrote, never create one.)
 */
data class DwViewerSelection(
    val creatorId: String = "",
    val creatorHasRow: Boolean = false,
    val baseline: Set<String> = emptySet(),
    val selected: Set<String> = emptySet(),
) {
    /** Ticked and not yet saved. */
    val added: Set<String> get() = selected - baseline

    /** Saved, unticked, and gone the moment Save is pressed. */
    val removed: Set<String> get() = baseline - selected

    val dirty: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()

    /** How many rows this workshop would hold after a save — the number the screen announces. */
    val resultingCount: Int get() = selected.size

    /** Over the server's ceiling, so the save is refused here with a sentence about people. */
    val overLimit: Boolean get() = payload().size > DW_VIEWER_LIMIT

    /**
     * The COMPLETE membership to send. Everything ticked, plus the creator's own row when the server
     * reported one.
     */
    fun payload(): List<String> =
        if (creatorHasRow && creatorId.isNotBlank()) selected.toList() + creatorId else selected.toList()

    fun withSelection(ids: Set<String>): DwViewerSelection =
        copy(selected = ids - creatorId - "")

    fun discard(): DwViewerSelection = copy(selected = baseline)

    companion object {
        /**
         * Adopt what the server says it holds as the new truth.
         *
         * Called both after the initial load AND after every save, and the second one is the point:
         * the ANSWER becomes the baseline, never the payload that was sent. Another admin may have
         * added somebody between this screen opening and Save being pressed, and a client that
         * treated its own request as the outcome would show a membership nobody has.
         */
        fun adopt(rows: List<DwViewerDto>, creatorId: String): DwViewerSelection {
            val editable = rows.map { it.userId }.filter { it.isNotBlank() && it != creatorId }.toSet()
            return DwViewerSelection(
                creatorId = creatorId,
                creatorHasRow = creatorId.isNotBlank() && rows.any { it.userId == creatorId },
                baseline = editable,
                selected = editable,
            )
        }
    }
}

// --------------------------------------------------------------------------------------
// Reading a failure honestly
// --------------------------------------------------------------------------------------

/**
 * Is this failure "the server has no such route" rather than "no such workshop"?
 *
 * ONLY ever asked of `GET /design-workshops/eligible-viewers`, and only that call. It is the one
 * request in the family that carries no id, so a 404 from it cannot mean a missing record and
 * therefore means a missing ROUTE — this repository ships its halves separately, so a phone updated
 * from the store before the API rolled out is a real state rather than a hypothesis. Asking the same
 * question of `/{id}/viewers` would be unanswerable: a 404 there is genuinely either.
 *
 * @param status the HTTP status, or null when nothing answered at all.
 */
fun dwViewerAdministrationMissing(status: Int?): Boolean = status == 404

/**
 * Which of the two things was being attempted when it failed — see [dwViewerFailureMessage].
 *
 * It exists because "nothing has changed" is a claim, and it is only TRUE for one of them. A read
 * that fails changed nothing by construction; a write that fails may have changed everything, part
 * of it, or nothing at all, and which of those it was is not always knowable from this side.
 */
enum class DwViewerAttempt { READ, SAVE }

/**
 * What went wrong, in words an administrator standing in a courtyard can act on.
 *
 * **A REFUSAL IS NOT A DISCONNECTION, AND A DISCONNECTION IS NOT A REFUSAL.** This app has shipped
 * that confusion twice — the report download and the workshop create, both in SESSION_HANDOVER.md —
 * and it costs the same thing each time: somebody goes looking for signal because a permission
 * failure reached them wearing a network message, or retypes work because a dropped connection
 * reached them wearing a rejection. So the arms below are split by STATUS first and the server's own
 * `detail` is passed through wherever there is one, because that text is the only thing that knows
 * WHICH account was refused and why.
 *
 * A 5xx is deliberately NOT reported as being offline. The repository answered; something inside it
 * failed. Calling that a connection problem sends an admin to look at their bars and leaves a real
 * fault wearing a message that will never be investigated.
 *
 * **AND NO ARM CLAIMS "NOTHING WAS CHANGED" UNLESS IT IS TRUE.** For a 4xx it is: the server refused
 * before writing, and `replace_viewers` validates every id to completion before it touches a row, so
 * a 422 really did apply none of it. For a dropped connection or a 5xx on a SAVE it is not: the
 * request may have landed and the answer been lost, and `replace_viewers` issues its `delete_many`
 * and its `create_many` as two statements — a fault between them leaves the removals applied and the
 * additions not. Telling an admin their revocation did not happen when it did is the worst sentence
 * this screen could print, so those arms send them back to look instead.
 *
 * Pure, and split from the Retrofit exception on purpose: `status`/`serverMessage` are extracted by
 * the one caller that has an `HttpException` in its hand (reading the error body CONSUMES it, so it
 * can only be read once), which leaves every sentence here assertable in a JVM test with no HTTP
 * stack — the same shape as `classifyCreate`.
 *
 * @param status the HTTP status, or null for "nothing answered": no signal, DNS, a socket dropped.
 * @param serverMessage FastAPI's `detail`, already unwrapped by `apiErrorMessage`, or null.
 */
fun dwViewerFailureMessage(
    status: Int?,
    serverMessage: String?,
    attempt: DwViewerAttempt = DwViewerAttempt.READ,
): String {
    val said = serverMessage?.trim()?.takeIf { it.isNotEmpty() }
    val unknownOutcome = attempt == DwViewerAttempt.SAVE
    return when {
        status == null ->
            "This phone could not reach the repository. Deciding who may open a workshop is a " +
                "change on the server, so unlike the 22 stages it cannot be done offline. " +
                if (unknownOutcome) {
                    "The request may still have landed — open this screen again on a connection to " +
                        "see where the workshop stands before you save anything else."
                } else {
                    "Nothing has been changed."
                }

        status == 401 ->
            "This phone is no longer signed in, so nothing was sent and nothing has changed. Sign " +
                "in again and try once more."

        // The server's own words FIRST, because "Admin access required" is what was actually
        // decided; the clause after it is why, and who to ask.
        status == 403 ->
            said.asSentence() +
                "Deciding who may open a design & prototype workshop is administration, so it is " +
                "open to admins and the master admin only — not to the designer who created it. " +
                "Nothing was changed."

        status == 404 ->
            said.asSentence() +
                "This workshop is not on the server — it may have been deleted since this screen " +
                "was opened. Nothing was changed."

        // The server's own 422 already names the offending account and already ends with "Nothing
        // was changed." — see `_assert_every_id_may_be_granted`. Repeating either would be this
        // screen talking over the one message written for exactly this moment.
        status == 422 ->
            "The repository would not accept this. " +
                (said ?: "One of the accounts named cannot be given access. Nothing was changed.")

        status >= 500 ->
            (said ?: "The repository had a problem answering.") +
                " This is not a connection problem. " +
                if (unknownOutcome) {
                    "Part of the change may have landed — open this screen again to see where the " +
                        "workshop stands."
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
 * — `require_admin` says "Admin access required" with no stop, while every message in
 * `services/design_workshop_viewers` ends in one. Concatenating either directly onto the clause that
 * follows produced "Admin access required This workshop is …", which reads as a truncated string
 * and makes an administrator distrust the whole message.
 */
private fun String?.asSentence(): String = when {
    this == null -> ""
    isEmpty() -> ""
    last() in ".!?:" -> "$this "
    else -> "$this. "
}
