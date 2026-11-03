package com.designprototype.workshop.data

import com.designprototype.workshop.report.formatReportDate
import java.util.concurrent.ConcurrentHashMap

const val DW_FROZEN_APPROVED_SENTENCE =
    "This report has been approved, so it can no longer be changed. If something in it needs " +
        "correcting, ask the Ministry Admin to withdraw the approval; it then comes back to its " +
        "designers to correct and hand in again."

const val DW_FROZEN_HANDED_ON_SENTENCE =
    "This report has been approved and handed on to the office, so it can no longer be changed. If " +
        "something in it needs correcting, ask the Ministry Admin to return it to its designers."

fun dwIsFrozen(status: String, handedOnAt: String?): Boolean {
    val token = status.trim().uppercase()
    return token == "APPROVED" || (token == "SUBMITTED" && !handedOnAt.isNullOrBlank())
}

fun dwFrozenSentence(status: String, handedOnAt: String?): String? = when {
    !dwIsFrozen(status, handedOnAt) -> null
    status.trim().uppercase() == "APPROVED" -> DW_FROZEN_APPROVED_SENTENCE
    else -> DW_FROZEN_HANDED_ON_SENTENCE
}

object DwReportFreeze {
    private val sentences = ConcurrentHashMap<String, String>()
    private val signOffs = ConcurrentHashMap<String, DwReportSignOff>()

    fun rememberRead(workshopId: String, detail: DesignWorkshopDetailDto) {
        if (workshopId.isBlank()) return
        val sentence = dwFrozenSentence(detail.status, detail.handedOnAt)
        if (sentence == null) sentences.remove(workshopId) else sentences[workshopId] = sentence
        val signOff = dwReportSignOffOf(detail)
        if (signOff == null) signOffs.remove(workshopId) else signOffs[workshopId] = signOff
    }

    fun sentenceFor(workshopId: String): String? = sentences[workshopId]

    fun signOffFor(workshopId: String): DwReportSignOff? = signOffs[workshopId]
}

data class DwReportSignOff(
    val approvedByName: String? = null,
    val approvedByRole: String? = null,
    val approvedAt: String? = null,
    val handedOnTo: String? = null,
    val handedOnAt: String? = null,
)

fun dwReportSignOffOf(detail: DesignWorkshopDetailDto?): DwReportSignOff? {
    if (detail == null || !dwIsFrozen(detail.status, detail.handedOnAt)) return null
    if (detail.approvedAt.isNullOrBlank()) return null
    return DwReportSignOff(
        approvedByName = detail.approvedByName,
        approvedByRole = detail.approvedByRole,
        approvedAt = detail.approvedAt,
        handedOnTo = detail.handedOnTo,
        handedOnAt = detail.handedOnAt,
    )
}

fun dwApproverRoleLabel(role: String?): String? {
    val token = role?.trim().orEmpty()
    if (token.isEmpty()) return null
    return when (token) {
        "MINISTRY_ADMIN" -> "Ministry Admin"
        "MASTER_ADMIN" -> "Master Admin"
        else -> token.replace('_', ' ').split(' ').joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }
}

fun dwReportSignOffLines(signOff: DwReportSignOff?): List<String> {
    if (signOff == null) return emptyList()
    val approvedAt = signOff.approvedAt?.takeIf { it.isNotBlank() } ?: return emptyList()
    val lines = ArrayList<String>(2)
    val name = signOff.approvedByName?.trim().orEmpty()
    val role = dwApproverRoleLabel(signOff.approvedByRole)
    val approvedOn = formatReportDate(approvedAt)
    lines += when {
        name.isEmpty() -> "Approved on $approvedOn."
        role == null -> "Approved by $name on $approvedOn."
        else -> "Approved by $name ($role) on $approvedOn."
    }
    signOff.handedOnAt?.takeIf { it.isNotBlank() }?.let { handedOnAt ->
        val office = signOff.handedOnTo?.trim()?.takeIf { it.isNotEmpty() } ?: "the office"
        lines += "Handed on to $office on ${formatReportDate(handedOnAt)}."
    }
    return lines
}
