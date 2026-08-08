package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.data.DesignWorkshopPageDto
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.walkDesignWorkshopPages
import com.designprototype.workshop.ui.SelectOption

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
 */

/**
 * One workshop as the picker shows it: the title a designer recognises, with the craft, cluster and
 * state under it.
 *
 * The hint is what disambiguates the rows that matter most here. A granted workshop is somebody
 * else's fieldwork, so its title is one the reader did not choose and may not recognise — the craft
 * and the cluster are how they tell it apart from their own.
 */
internal fun designWorkshopOption(workshop: DesignWorkshopDto): SelectOption = SelectOption(
    value = workshop.id,
    label = workshop.title.ifBlank { "Untitled workshop" },
    hint = listOfNotNull(workshop.craftName, workshop.clusterName, workshop.state)
        .joinToString(" · ")
        .takeIf { it.isNotBlank() }
)

/**
 * Every attachable workshop, gathered across as many pages as the server reports.
 *
 * [fetch] is the only IO, which is what lets the paging rule above be pinned by a plain JVM test with
 * no Retrofit, no repository and no coroutine dispatcher.
 */
internal suspend fun designWorkshopOptionsAcrossPages(
    fetch: suspend (page: Int, pageSize: Int) -> DesignWorkshopPageDto,
): List<SelectOption> =
    walkDesignWorkshopPages(fetch = fetch).items.map(::designWorkshopOption)
