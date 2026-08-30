package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DesignWorkshopPageDto
import com.designprototype.workshop.data.walkDesignWorkshopPages
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.WorkshopListKind
import com.designprototype.workshop.ui.WorkshopListState
import com.designprototype.workshop.ui.designWorkshopOptions
import com.designprototype.workshop.ui.workshopCapLine
import com.designprototype.workshop.ui.workshopListNotice

/**
 * The design workshops a questionnaire may be ATTACHED to — every one this account may open, not the
 * first hundred.
 *
 * ── THE FAILURE THIS ENDS ──────────────────────────────────────────────────────────────────────────
 *
 * `designWorkshopOptions` asked for one page of a hundred and mapped `items`. Its comment defended
 * that with "a designer runs a handful of workshops, not a hundred", and against the running API that
 * premise is simply false: signed in as designer@example.org, `GET /design-workshops?pageSize=100`
 * answers `total: 121`. Asking for more does not help — `normalize_pagination` clamps at
 * `MAX_PAGE_SIZE = 100` and echoes the clamp back in a `pageSize` the caller never read.
 *
 * It matters here for the same reason it mattered on the list screen, and worse. An admin can put a
 * second designer on a workshop (`DesignWorkshopViewer`), and a grant WIDENS this list while the rows
 * stay ordered `createdAt desc`. A grant is issued against a workshop that ALREADY EXISTS — the
 * colleague joining mid-season, the handover from a designer who left — so it is older than anything
 * the grantee started themselves and sorts to the very end. Verified live rather than reasoned about:
 * with one viewer grant on cmsik2jg8000eh8xc1lcy661a the designer's list reports `total: 121` and the
 * granted workshop is row 121 — page 2, index 20. One page of a hundred cannot reach it.
 *
 * The consequence was a dead end with no error in it. The server ACCEPTS the attachment: the create
 * and patch routes validate `designWorkshopId` with `require_record`, an existence check and nothing
 * more, so `POST /questionnaires` with the granted workshop's id answers 201 (confirmed live). And the
 * questionnaire list already honours the grant the other way round —
 * `_works_on_this_questionnaires_workshop` in `routes/questionnaire_forms.py` admits a grant-holder to
 * questionnaires attached to that workshop, which is why the same designer can READ a questionnaire
 * owned by a third account. So the co-designer could see the workshop's questionnaires and could not
 * add one to it, because the picker never offered the workshop. Nothing failed; the row was absent.
 *
 * ── WHY A WALK AND NOT A BIGGER PAGE ───────────────────────────────────────────────────────────────
 *
 * There is no `GET /design-workshops/options`. The questionnaire API has exactly that escape hatch for
 * its own dropdown — see the note above `customQuestionnaireOptions` in WorkshopRepositoryApi.kt, "a
 * dropdown that silently stops at page one is a designer who cannot find the questionnaire they
 * uploaded this morning" — but the design-workshop router has no equivalent (probed live: the path
 * falls through to `/{workshop_id}` and 404s). The paged walk the list screen already uses is the only
 * way to the second page, so this reuses it rather than inventing a second paging policy that could
 * drift from it.
 *
 * BOUNDED, and honestly so: [com.designprototype.workshop.data.DW_LIST_MAX_PAGES] stops the walk at
 * 500 workshops. Beyond that this is still a prefix. That bound is deliberate — an unbounded walk on
 * one bar of signal is a picker that spins instead of opening — and it is the same bound the list
 * screen reports as `truncated`, so the two screens cannot disagree about what the account can see.
 * A designer under the ceiling still pays exactly ONE request, as before: page 2 is asked for only
 * when the server has already said it exists.
 *
 * ── WHAT THE 2026-08-29 PASS CHANGED, AND WHY IT IS NOT A REFACTOR ─────────────────────────────────
 *
 * This file used to return a bare `List<SelectOption>`, and the loader above it turned EVERY failure
 * into `emptyList()`. So the three questionnaire screens could not tell "the walk has not answered
 * yet" from "the walk failed" from "this account is on no workshop" — all three arrived as an empty
 * list, and all three drew a picker containing one row that says "Not attached" and no words
 * whatsoever about why. A designer in a courtyard with no signal read that as the repository's
 * answer. That is the defect DROPDOWN_DESIGN §3.5 names, and the fix is [AttachableWorkshops]: the
 * walk now hands back WHAT HAPPENED as well as what arrived, and the sentence each state prints is
 * `WorkshopOptions.kt`'s, shared with the record forms and byte-parallel with the web's.
 *
 * The truncation is reported for the first time here too. A walk that stopped at the ceiling, or
 * that lost the connection at page three, previously returned its prefix silently — which is R4's
 * failure exactly: a list that quietly stops is indistinguishable from a place with no records.
 */

/**
 * The answer a questionnaire screen needs about its attach control: the rows, WHAT HAPPENED, and
 * whether the walk covered the account.
 *
 * ROWS AND NOT OPTIONS, deliberately. The picker's rows depend on one fact this loader cannot know —
 * the workshop the questionnaire is ALREADY attached to, which may be past the walk's ceiling or
 * outside a list that failed. Building [SelectOption]s here would mean building them without it, and
 * the control would then draw "Not attached" over a questionnaire that IS attached. [options] takes
 * that id, so every mount point supplies its own.
 *
 * @property total what the SERVER said this account may open. Kept even when it equals the rows in
 *   hand, because the difference between the two numbers is the only thing that can make the cap
 *   sentence honest.
 * @property truncated the walk ended before it had covered [total] — the ceiling, or a connection
 *   that dropped mid-walk. Advisory and never blocking: the rows gathered are real.
 */
internal data class AttachableWorkshops(
    val rows: List<DesignWorkshopDto> = emptyList(),
    val state: WorkshopListState = WorkshopListState.Loading,
    /** See `DesignWorkshopPickerState.online`: the OUTBOX's classification, never a network probe. */
    val online: Boolean = true,
    val total: Int = 0,
    val truncated: Boolean = false,
) {
    /**
     * The rows as the picker draws them, with the already-attached workshop kept even when the walk
     * could not list it.
     *
     * @param attachedId the questionnaire's stored `designWorkshopId`, or `""` on a create.
     */
    fun options(attachedId: String = ""): List<SelectOption> =
        designWorkshopOptions(rows = rows, offPageId = attachedId)

    /**
     * The one §3.5 sentence for the state this walk is in, or null when the list simply arrived.
     *
     * Handed to `SearchableSelectField.emptyMessage` AND printed under the control, which on this
     * client are the same string by construction — see `workshopListNotice`.
     */
    fun notice(): String? = workshopListNotice(state, WorkshopListKind.DESIGN, online)

    /** R4: the walk stopped short and says so, with both numbers. Null when it covered everything. */
    fun capNotice(): String? =
        if (truncated) workshopCapLine(rows.size, total, WorkshopListKind.DESIGN) else null
}

/**
 * Every attachable workshop, gathered across as many pages as the server reports.
 *
 * [fetch] is the only IO, which is what lets the paging rule above be pinned by a plain JVM test with
 * no Retrofit, no repository and no coroutine dispatcher.
 *
 * A THROW REACHES THE CALLER. `walkDesignWorkshopPages` swallows a failure on any page AFTER the
 * first — something was already read, and throwing it away would turn a connection that dropped at
 * page three into the same blank screen as no connection at all — and it rethrows a failure on page
 * one, because nothing was read and reporting an empty account would be a lie. The caller is what
 * turns that throw into [WorkshopListState.Failed] and the sentence that goes with it; it is not
 * caught here, because only the caller holds the repository whose `isTransient` decides WHICH
 * failure sentence is true.
 */
internal suspend fun walkAttachableDesignWorkshops(
    fetch: suspend (page: Int, pageSize: Int) -> DesignWorkshopPageDto,
): AttachableWorkshops {
    val listing = walkDesignWorkshopPages(fetch = fetch)
    return AttachableWorkshops(
        rows = listing.items,
        state = WorkshopListState.Listed(count = listing.items.size, total = listing.total),
        online = true,
        total = listing.total,
        truncated = listing.truncated,
    )
}
