package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.dwRefId
import com.designprototype.workshop.data.plural
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal const val DW_STAGE_ENTRY_CAP = 500

internal const val DW_BULK_PAGE = 200

internal const val DW_DROPPED_NAMES_SHOWN = 6

internal fun dwBulkField(entity: EntityDto): FieldDto? {
    if (entity.cardinality != "COLLECTION") return null
    val refs = entity.fields.filter { field ->
        !field.deprecated &&
            field.captionFor.isBlank() &&
            field.type == "REF" &&
            field.refModel.isNotBlank() &&
            field.refFilterBy.isBlank() &&
            field.tier == "BASIC"
    }
    return refs.singleOrNull()
}

internal fun dwBulkAllLabel(refModel: String): String? = when (refModel) {
    "DwParticipant" -> "All participants"
    "DwFinalProduct" -> "All final products"
    "DwSketch" -> "All sketches"
    "DwPrototype" -> "All prototypes"
    else -> null
}

internal fun dwBulkChosenIds(rows: List<Map<String, JsonElement>>, field: FieldDto): Set<String> =
    rows.mapNotNull { values -> dwRefId(values[field.key]).takeIf { it.isNotBlank() } }.toSet()

internal data class DwBulkAdd(
    val created: List<Map<String, JsonElement>>,
    val dropped: List<DwReferenceOption>,
    val room: Int,
    val asked: Int,
)

internal fun dwBulkAdd(
    options: List<DwReferenceOption>,
    field: FieldDto,
    writable: Map<String, FieldDto>,
    alreadyChosen: Set<String>,
    stageEntries: Int,
): DwBulkAdd {
    val fresh = options.filter { it.id.isNotBlank() && it.id !in alreadyChosen }.distinctBy { it.id }
    val room = (DW_STAGE_ENTRY_CAP - stageEntries).coerceAtLeast(0)
    val taken = fresh.take(room)
    val created = taken.map { option ->
        val values = LinkedHashMap<String, JsonElement>()
        values.putAll(hydratedValues(option, field.refHydration, writable))
        values[field.key] = JsonPrimitive(option.id)
        values
    }
    return DwBulkAdd(created = created, dropped = fresh.drop(room), room = room, asked = fresh.size)
}

internal fun dwBulkRefusal(outcome: DwBulkAdd): String? {
    if (outcome.dropped.isEmpty()) return null
    val names = outcome.dropped.take(DW_DROPPED_NAMES_SHOWN).joinToString(", ") { it.label }
    val more = outcome.dropped.size - DW_DROPPED_NAMES_SHOWN
    return "Added ${outcome.created.size} of ${outcome.asked} — room for ${outcome.room} on this stage. " +
        "Not added: $names${if (more > 0) " and $more more" else ""}. Delete rows to make room."
}

internal fun dwBulkTriggerPhrase(fieldLabel: String): String = "add several from ${fieldLabel.lowercase()}"

internal fun dwBulkAddedSentence(outcome: DwBulkAdd, truncated: Boolean, fieldLabel: String): String =
    dwBulkRefusal(outcome)
        ?: if (truncated && outcome.created.isNotEmpty()) {
            "Added the first ${outcome.created.size}. Use ${dwBulkTriggerPhrase(fieldLabel)} for the rest."
        } else {
            "Added ${plural(outcome.created.size, "row")}."
        }

internal fun dwBulkCutShortNote(shown: Int): String =
    "Showing the first $shown, more than a stage holds ($DW_STAGE_ENTRY_CAP). Search for others."

internal const val DW_BULK_NO_LIST = "This list is not on this phone yet. Connect and try again."

internal fun dwBulkLocalSearch(options: List<DwReferenceOption>, term: String): List<DwReferenceOption> {
    val words = term.trim().lowercase().split(' ', '\t', '\n').filter { it.isNotEmpty() }
    if (words.isEmpty()) return options
    return options.filter { option ->
        val haystack = "${option.label} ${option.hint}".lowercase()
        words.all { it in haystack }
    }
}
