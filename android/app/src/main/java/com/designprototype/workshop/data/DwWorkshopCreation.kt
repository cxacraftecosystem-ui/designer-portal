package com.designprototype.workshop.data

val DW_WORKSHOP_CREATOR_ROLES = setOf("ADMIN", "MASTER_ADMIN")

const val DW_WORKSHOP_CREATE_REFUSAL =
    "Only admins can start a workshop. Ask an admin to create it and add you; you can then fill in all 22 stages. Any workshop you already have access to is open to you now."

fun canCreateDesignWorkshops(role: String?): Boolean = role in DW_WORKSHOP_CREATOR_ROLES

fun mayMintLocalWorkshop(known: Boolean, role: String?): Boolean {
    if (!known) return true
    return canCreateDesignWorkshops(role)
}

fun localDraftNeedsAWorkshop(draft: WorkshopDraft): Boolean = remoteWorkshopIdOf(draft) == null

fun remoteWorkshopIdOf(draft: WorkshopDraft): String? =
    draft.remoteId?.takeIf { it.isNotBlank() }
        ?: draft.workshopId.takeIf { it.isNotBlank() && !isLocalOnlyWorkshop(it) }

fun createMustBeDeclined(alreadyOnServer: Boolean, sessionMayCreate: Boolean): Boolean {
    if (alreadyOnServer) return false
    return !sessionMayCreate
}

const val DW_WORKSHOP_CREATE_DECLINED_BY_APP =
    DW_WORKSHOP_CREATE_REFUSAL +
        " Nothing here has been deleted or changed. Once it exists, use “Move into a workshop” on this row."

enum class DwDraftStart {
    CREATE,

    LINK_LATER,

    REFUSED,
}

fun dwClassifyDraftStart(
    mayCreate: Boolean,
    mayRunWorkshops: Boolean,
    reachable: Boolean,
): DwDraftStart = when {
    mayCreate -> DwDraftStart.CREATE
    mayRunWorkshops && !reachable -> DwDraftStart.LINK_LATER
    else -> DwDraftStart.REFUSED
}

const val DW_LOCAL_START_ACTION = "Start one on this device"

const val DW_LOCAL_START_NOTE =
    "Not a workshop yet. Link it to one when online."

const val DW_LOCAL_DRAFT_UNLINKED = "Started on this device. Not a workshop yet."

const val DW_LOCAL_DRAFT_LINK_PROMPT = "Choose which of your workshops this belongs to."

const val DW_MAX_NAMED_DESIGNERS: Int = DW_VIEWER_LIMIT

data class DwNamedDesigners(val lead: String?, val team: List<String>)

fun dwNamedDesignerTeam(chosen: List<String>?, lead: String?): DwNamedDesigners {
    val ticked = LinkedHashSet<String>()
    chosen.orEmpty().forEach { raw -> dwNamedDesignerId(raw)?.let { ticked.add(it) } }
    val preferred = dwNamedDesignerId(lead)
    if (ticked.isEmpty()) {
        return if (preferred == null) {
            DwNamedDesigners(lead = null, team = emptyList())
        } else {
            DwNamedDesigners(lead = preferred, team = listOf(preferred))
        }
    }
    val resolved = preferred?.takeIf { it in ticked } ?: ticked.first()
    return DwNamedDesigners(
        lead = resolved,
        team = listOf(resolved) + ticked.filter { it != resolved },
    )
}

data class DwDesignerCreateFields(
    val designerUserId: String?,
    val designerUserIds: List<String>?,
)

fun dwDesignerCreateFields(chosen: List<String>?, lead: String?): DwDesignerCreateFields {
    val resolved = dwNamedDesignerTeam(chosen, lead)
    return when {
        resolved.team.isEmpty() -> DwDesignerCreateFields(null, null)
        resolved.team.size == 1 -> DwDesignerCreateFields(resolved.lead, null)
        else -> DwDesignerCreateFields(resolved.lead, resolved.team)
    }
}

fun dwOrderedDesignerPicks(
    previous: List<String>,
    picked: Set<String>,
    offered: List<String>,
): List<String> {
    val kept = previous.filter { it in picked }
    val already = HashSet(kept)
    val added = offered.filter { it in picked && it !in already }
    already.addAll(added)
    return kept + added + picked.filter { it !in already }
}

fun dwNamedDesignerId(picked: String?): String? = DwPy.strip(picked.orEmpty()).ifEmpty { null }

fun adoptedIntoWorkshop(draft: WorkshopDraft, remoteId: String, now: String): WorkshopDraft =
    draft.copy(
        remoteId = remoteId,
        updatedAt = now,
        stages = draft.stages.mapValues { (_, stage) ->
            stage.copy(
                stageSeen = false,
                emptiedEntities = emptyList(),
                deletedRowKeys = emptyList(),
            )
        },
        sync = draft.sync.copy(
            createFailure = null,
            createFailedAt = null,
            createSkewRun = null,
            lastError = null,
            lastSuccessAt = null,
            stages = emptyMap(),
        ),
    )

fun dwAdoptCandidateNotice(
    offline: Boolean,
    searched: Boolean,
    listTruncated: Boolean,
): String? = when {
    offline ->
        "Could not connect — showing workshops on this phone only. Moving waits for a connection, and nothing is sent before then."
    searched ->
        "Filtered by the search box on the workshops screen. Clear it to see all."
    listTruncated ->
        "There are more workshops than this screen could read. Search for the one you want."
    else -> null
}

fun dwAdoptNoCandidatesMessage(offline: Boolean): String = if (offline) {
    "No workshops here and the app could not connect. Ask an admin to create one, then try again online."
} else {
    "No workshop is open to you yet — only designers named on it can see it. Ask an admin to add you, or use a join card from “Workshop access”. Nothing on this phone is at risk."
}
