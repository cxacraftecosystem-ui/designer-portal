package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

object PossibleDuplicates {
    const val HOLD_CODE = "artisan_possible_duplicate"

    const val SHOW_THRESHOLD = 0.60

    const val SHOWN_MAX = 3

    const val ACKNOWLEDGED_MAX = 20

    private val json = Json { ignoreUnknownKeys = true }

    fun toShow(candidates: List<DuplicateCandidateDto>, acknowledged: Set<String>): List<DuplicateCandidateDto> =
        candidates
            .filter { it.score >= SHOW_THRESHOLD && it.artisan.id !in acknowledged }
            .sortedByDescending { it.score }
            .take(SHOWN_MAX)

    fun acknowledged(ids: Collection<String>): List<String> = ids.distinct().take(ACKNOWLEDGED_MAX)

    fun heldCandidates(code: Int, body: String?): List<DuplicateCandidateDto>? {
        if (code != 409 || body.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val detail = root["detail"] as? JsonObject ?: return null
        if ((detail["code"] as? JsonPrimitive)?.content != HOLD_CODE) return null
        val list = detail["candidates"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<DuplicateCandidateDto>(element) }.getOrNull()
        }
    }

    fun rowLine(candidate: DuplicateCandidateDto): String =
        listOfNotNull(
            candidate.artisan.name.trim().ifBlank { null },
            candidate.artisan.place?.trim()?.ifBlank { null },
            candidate.artisan.craft?.trim()?.ifBlank { null },
        ).joinToString(" · ")

    fun traySentence(candidates: List<OutboxDuplicateCandidate>): String {
        val top = candidates.firstOrNull() ?: return "Not saved yet. This may already be recorded."
        val place = top.place?.trim()?.ifBlank { null }
        return "Not saved yet. This looks like ${top.name}${if (place != null) " ($place)" else ""}."
    }

    fun payloadWithAcknowledged(payload: String, ids: List<String>): String {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return payload
        val before = (root["duplicateAcknowledged"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        val merged = acknowledged(before + ids)
        return JsonObject(root + ("duplicateAcknowledged" to JsonArray(merged.map { JsonPrimitive(it) }))).toString()
    }

    fun forOutbox(candidates: List<DuplicateCandidateDto>): List<OutboxDuplicateCandidate> =
        candidates.take(5).map { OutboxDuplicateCandidate(it.artisan.id, it.artisan.name, it.artisan.place) }
}

@Serializable
data class OutboxDuplicateCandidate(val id: String, val name: String, val place: String? = null)
