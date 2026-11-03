package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

@Serializable
data class DwViewerDto(
    val userId: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",
    val grantedAt: String? = null,
)

@Serializable
data class DwViewerListDto(val viewers: List<DwViewerDto> = emptyList())

@Serializable
data class DwEligibleViewerDto(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",
)

@Serializable
data class DwEligibleViewerListDto(
    val users: List<DwEligibleViewerDto> = emptyList(),
    val truncated: Boolean = false,
)

data class DwEligibleViewers(
    val users: List<DwEligibleViewerDto> = emptyList(),
    val truncated: Boolean = false,
    val search: String? = null,
) {
    val complete: Boolean get() = !truncated && search == null
}

fun dwViewerOfferNotice(offer: DwEligibleViewers): String? = when {
    offer.truncated && offer.users.isEmpty() ->
        "Some accounts couldn't be listed. Try again later."
    offer.truncated && offer.search == null ->
        "Too many to list — search by name or email."
    offer.truncated -> "Too many matches — narrow the search."
    offer.search != null && offer.users.isEmpty() -> "No eligible account matches that search."
    else -> null
}

fun dwViewerSearchTerm(typed: String?): String? {
    val term = DwPy.strip(typed.orEmpty())
    if (term.isEmpty()) return null
    if (term.length <= DW_VIEWER_SEARCH_MAX) return term
    val cut = term.take(DW_VIEWER_SEARCH_MAX)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

const val DW_VIEWER_SEARCH_MAX = 120

@Serializable
data class DwViewersBody(val userIds: List<String>)

const val DW_VIEWER_LIMIT = 100

data class DwViewerChoice(
    val userId: String,
    val name: String,
    val email: String,
    val role: String,
    val grantedButIneligible: Boolean = false,
)

fun dwPersonLabel(name: String?, email: String?): String =
    dwJsTrim(name.orEmpty()).ifEmpty { dwJsTrim(email.orEmpty()) }.ifEmpty { "Unknown user" }

private fun dwJsTrim(text: String): String = text.trim { c ->
    when (c.code) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20 -> true
        0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        else -> c.code in 0x2000..0x200A
    }
}

fun dwViewerChoices(
    eligible: List<DwEligibleViewerDto>,
    viewers: List<DwViewerDto>,
    creatorId: String,
    readerId: String?,
    eligibleListComplete: Boolean,
    retained: List<DwEligibleViewerDto> = emptyList(),
): List<DwViewerChoice> {
    val reader = readerId?.takeIf { it.isNotBlank() }
    val choices = ArrayList<DwViewerChoice>(eligible.size + retained.size + viewers.size)
    val seen = HashSet<String>()
    (eligible + retained).forEach { person ->
        if (person.id.isBlank() || person.id == creatorId || person.id == reader || !seen.add(person.id)) {
            return@forEach
        }
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
            grantedButIneligible = eligibleListComplete && row.userId != reader,
        )
    }
    return choices
}

data class DwViewerSelection(
    val creatorId: String = "",
    val creatorHasRow: Boolean = false,
    val baseline: Set<String> = emptySet(),
    val selected: Set<String> = emptySet(),
) {
    val added: Set<String> get() = selected - baseline

    val removed: Set<String> get() = baseline - selected

    val dirty: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()

    val resultingCount: Int get() = selected.size

    val overLimit: Boolean get() = payload().size > DW_VIEWER_LIMIT

    fun payload(): List<String> =
        if (creatorHasRow && creatorId.isNotBlank()) selected.toList() + creatorId else selected.toList()

    fun withSelection(ids: Set<String>): DwViewerSelection =
        copy(selected = ids - creatorId - "")

    fun discard(): DwViewerSelection = copy(selected = baseline)

    companion object {
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

fun dwViewerAdministrationMissing(status: Int?): Boolean = status == 404

enum class DwViewerAttempt { READ, SAVE }

fun dwViewerFailureMessage(
    status: Int?,
    serverMessage: String?,
    attempt: DwViewerAttempt = DwViewerAttempt.READ,
    readerIsAdmin: Boolean,
): String {
    val said = serverMessage?.trim()?.takeIf { it.isNotEmpty() }
    val unknownOutcome = attempt == DwViewerAttempt.SAVE
    return when {
        status == null ->
            "Could not connect — this needs a connection. Try again when connected. " +
                if (unknownOutcome) {
                    "Your change may already have been saved. Reopen online to check before changing more."
                } else {
                    "Nothing has been changed."
                }

        status == 401 ->
            "Signed out — nothing was sent. Sign in and try again."

        status == 403 && readerIsAdmin ->
            (said?.asSentence() ?: "This was not allowed. ") + "Nothing was changed."

        status == 403 ->
            said.asSentence() +
                "Only admins can change access. Nothing was changed."

        status == 404 ->
            said.asSentence() +
                "Workshop not found. Nothing changed."

        status == 422 ->
            "This could not be saved. " +
                (said ?: "One account can't be given access. Nothing changed.")

        status >= 500 ->
            (said ?: "Something went wrong.") +
                " This is not a connection problem. " +
                if (unknownOutcome) {
                    "Part of the change may have been saved — reopen to check."
                } else {
                    "Nothing was changed. Try again in a moment."
                }

        else -> said ?: "That did not go through."
    }
}

private fun String?.asSentence(): String = when {
    this == null -> ""
    isEmpty() -> ""
    last() in ".!?:" -> "$this "
    else -> "$this. "
}
