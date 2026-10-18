package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * LIKELY DUPLICATES OF AN ARTISAN BEING SAVED — the handset's half of `POST /artisan-duplicates/check`
 * and of the 409 `artisan_possible_duplicate` hold on `POST /artisans` (backend
 * `services/artisan_duplicates.py`).
 *
 * THE SERVER IS THE ONLY SCORER. Nothing here matches names or reads a score other than to decide
 * whether to show a row: an offline create is checked when it syncs, never on the phone. Pure, so
 * `OutboxPossibleDuplicateTest` can hold every sentence and decision on the JVM.
 */
object PossibleDuplicates {
    /** The server's error code for a create held as a likely duplicate. */
    const val HOLD_CODE = "artisan_possible_duplicate"

    /** Below this the server does not return a candidate; the form shows nothing under it either. */
    const val SHOW_THRESHOLD = 0.60

    /** The rows the sheet draws at most. */
    const val SHOWN_MAX = 3

    /** `ArtisanCreate.duplicateAcknowledged` takes at most 20 ids. */
    const val ACKNOWLEDGED_MAX = 20

    private val json = Json { ignoreUnknownKeys = true }

    /** The candidates the sheet shows: at or above the show threshold, not already set aside. */
    fun toShow(candidates: List<DuplicateCandidateDto>, acknowledged: Set<String>): List<DuplicateCandidateDto> =
        candidates
            .filter { it.score >= SHOW_THRESHOLD && it.artisan.id !in acknowledged }
            .sortedByDescending { it.score }
            .take(SHOWN_MAX)

    /** What a create sends: the ids set aside as "Different person", or `[]` when nothing was shown. */
    fun acknowledged(ids: Collection<String>): List<String> = ids.distinct().take(ACKNOWLEDGED_MAX)

    /**
     * The candidates of a 409 `artisan_possible_duplicate`, or null for any other answer (a clash,
     * a stale correction, anything that is not the hold). [body] is the raw error body.
     */
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

    /** One line per candidate: name · place · craft. */
    fun rowLine(candidate: DuplicateCandidateDto): String =
        listOfNotNull(
            candidate.artisan.name.trim().ifBlank { null },
            candidate.artisan.place?.trim()?.ifBlank { null },
            candidate.artisan.craft?.trim()?.ifBlank { null },
        ).joinToString(" · ")

    /** The tray's sentence for a held create. */
    fun traySentence(candidates: List<OutboxDuplicateCandidate>): String {
        val top = candidates.firstOrNull() ?: return "Not saved yet. This may already be recorded."
        val place = top.place?.trim()?.ifBlank { null }
        return "Not saved yet. This looks like ${top.name}${if (place != null) " ($place)" else ""}."
    }

    /** The queued body with [ids] added to `duplicateAcknowledged` (at most 20). Unparseable: as is. */
    fun payloadWithAcknowledged(payload: String, ids: List<String>): String {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return payload
        val before = (root["duplicateAcknowledged"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        val merged = acknowledged(before + ids)
        return JsonObject(root + ("duplicateAcknowledged" to JsonArray(merged.map { JsonPrimitive(it) }))).toString()
    }

    /** The candidates as the outbox keeps them: id, name and place only. */
    fun forOutbox(candidates: List<DuplicateCandidateDto>): List<OutboxDuplicateCandidate> =
        candidates.take(5).map { OutboxDuplicateCandidate(it.artisan.id, it.artisan.name, it.artisan.place) }
}

/** A likely duplicate as a queued entry keeps it. No identifier, no score: what the tray prints. */
@Serializable
data class OutboxDuplicateCandidate(val id: String, val name: String, val place: String? = null)
