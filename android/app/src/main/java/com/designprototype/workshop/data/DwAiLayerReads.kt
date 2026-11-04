package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

@Serializable
data class DwAiVerbAllowanceDto(
    val aiVerbsLimit: Int? = null,
    val aiVerbsUsed: Int = 0,
    val aiVerbsRemaining: Int? = null,
    val aiVerbDay: String? = null,
    val aiVerbsByVerb: Map<String, Int> = emptyMap(),
)

fun dwAiVerbAllowanceOf(dto: DwAiVerbAllowanceDto, userId: String?): DwAiVerbAllowance? {
    val day = dto.aiVerbDay?.trim().orEmpty()
    if (day.isEmpty() || userId.isNullOrBlank()) return null
    return DwAiVerbAllowance(
        userId = userId,
        day = day,
        limit = dto.aiVerbsLimit,
        remaining = dto.aiVerbsRemaining,
        byVerb = dto.aiVerbsByVerb,
    )
}

@Serializable
data class DwAiDecisionHistoryItemDto(
    val id: String = "",
    val layerId: String = "",
    val decision: String = "",
    val note: String? = null,
    val actorId: String? = null,
    val actorName: String? = null,
    val layerKind: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class DwAiDecisionHistoryDto(
    val items: List<DwAiDecisionHistoryItemDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class DwAiLayerReadDto(
    val layer: DwAiLayerDto = DwAiLayerDto(),
    val decisions: List<DwAiDecisionHistoryItemDto> = emptyList(),
)

@Serializable
data class DwAiLayerRegisterBody(
    val sourceMediaId: String,
)

@Serializable
data class DwAiLayerRegisterResultDto(
    val items: List<DwAiLayerDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class DwTranscriptItemDto(
    val mediaId: String = "",
    val stageTitle: String = "",
    val stageNumber: Int = 0,
    val label: String = "",
    val filename: String = "",
    val durationText: String = "",
)

@Serializable
data class DwTranscriptListDto(
    val items: List<DwTranscriptItemDto> = emptyList(),
    val total: Int = 0,
)

fun dwRegisterableTranscripts(
    layers: List<DwAiLayerDto>,
    transcripts: List<DwTranscriptItemDto>,
): List<DwTranscriptItemDto> {
    val already = layers
        .filter { it.source?.kind == "MEDIA" && it.deletedAt == null }
        .mapNotNull { it.source?.id }
        .toSet()
    return transcripts.filter { it.mediaId.isNotBlank() && it.mediaId !in already }
}

fun dwRegisteredNotice(total: Int): String =
    if (total > 1) "Registered as $total layers. Accept them to print." else "Registered. Accept it to print."

fun dwDecisionLabel(decision: String): String = when (decision.uppercase()) {
    "ACCEPTED" -> "Accepted"
    "WITHDRAWN" -> "Acceptance withdrawn"
    "DECLINED" -> "Declined"
    else -> decision.lowercase().replaceFirstChar { it.uppercase() }.ifBlank { "Decision" }
}

fun dwDecisionLine(item: DwAiDecisionHistoryItemDto): String {
    val who = item.actorName?.takeIf { it.isNotBlank() } ?: "A former account"
    val kind = item.layerKind?.takeIf { it.isNotBlank() }?.let { " · ${dwLayerKindWords(it)}" }.orEmpty()
    return "${dwDecisionLabel(item.decision)} by $who$kind"
}

fun dwLayerKindWords(kind: String): String =
    kind.lowercase().split('_').filter { it.isNotBlank() }.joinToString(" ").replaceFirstChar { it.uppercase() }

object DwAiVerbAllowancePreflight {
    private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    fun shouldAsk(userId: String?, today: String): Boolean {
        if (userId.isNullOrBlank() || today.isBlank()) return false
        return asked.add("$userId|$today")
    }

    fun answered() {
        _version.value = _version.value + 1
    }

    fun reset() {
        asked.clear()
    }
}
