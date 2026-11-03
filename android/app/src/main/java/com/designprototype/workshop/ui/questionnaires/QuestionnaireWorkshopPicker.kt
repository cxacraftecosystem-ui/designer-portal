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

internal data class AttachableWorkshops(
    val rows: List<DesignWorkshopDto> = emptyList(),
    val state: WorkshopListState = WorkshopListState.Loading,
    val online: Boolean = true,
    val total: Int = 0,
    val truncated: Boolean = false,
) {
    fun options(attachedId: String = ""): List<SelectOption> =
        designWorkshopOptions(rows = rows, offPageId = attachedId)

    fun notice(): String? = workshopListNotice(state, WorkshopListKind.DESIGN, online)

    fun capNotice(): String? =
        if (truncated) workshopCapLine(rows.size, total, WorkshopListKind.DESIGN) else null
}

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
