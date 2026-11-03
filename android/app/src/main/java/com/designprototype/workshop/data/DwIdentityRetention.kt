package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

const val DW_RETENTION_DISCARD = "DISCARD"

const val DW_RETENTION_STORE = "STORE"

const val DW_RETENTION_DEFAULT = DW_RETENTION_DISCARD

val DW_RETENTION_DECISIONS = listOf(DW_RETENTION_DISCARD, DW_RETENTION_STORE)

fun parseRetention(raw: String?): String {
    val cleaned = raw?.trim()?.uppercase()
    return if (cleaned != null && cleaned in DW_RETENTION_DECISIONS) cleaned else DW_RETENTION_DISCARD
}

@Serializable
data class DwIdentityPhotographDto(
    val stored: Boolean? = null,
    val retention: String? = null,
    val decisionRoute: String? = null,
)

fun photographWasNotStored(result: DwIdentityOcrDto): Boolean = result.photograph?.stored == false

@Serializable
data class DwRetentionStampDto(
    val decision: String = DW_RETENTION_DISCARD,
    val decidedById: String? = null,
    val decidedByName: String? = null,
    val decidedAt: String? = null,
)

@Serializable
data class DwRetentionDecisionBody(
    val mediaId: String,
    val decision: String,
)

@Serializable
data class DwRetentionResultDto(
    val mediaId: String = "",
    val decision: String = DW_RETENTION_DISCARD,
    val deleted: Boolean = false,
    val objectDeleted: Boolean = false,
    val retention: DwRetentionStampDto? = null,
)

fun retentionOutcomeSentence(result: DwRetentionResultDto): String {
    if (result.deleted) {
        return "Photo deleted — the file and its record are both gone. The confirmed number is unaffected."
    }
    val who = result.retention?.decidedByName?.trim()?.takeIf { it.isNotEmpty() }
    return if (who != null) {
        "Photo kept on this record (by $who). It's unmasked — anyone who can open the record can see it."
    } else {
        "Photo kept on this record. It's unmasked — anyone who can open the record can see it."
    }
}

fun retentionChoiceBlurb(keep: Boolean): String = if (keep) {
    "This ID photo will be KEPT on the record, unmasked, under your name."
} else {
    "The photo is not kept. Only the confirmed number is saved."
}
