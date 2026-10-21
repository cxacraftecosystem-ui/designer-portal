package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.dwRefId
import com.designprototype.workshop.data.plural
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * ADD MANY ROWS FROM ONE PICK LIST — the handset's half of the web `EntityForm`'s bulk picker.
 *
 * The rule that decides WHICH lists offer it is the web's `bulkField`, restated condition for
 * condition, so the two clients offer it on the same lists and nowhere else:
 *
 *  * a live REF field (not deprecated, not a caption) with a `refModel`;
 *  * tier BASIC — the registry's own word for "this is what the row is for";
 *  * no `refFilterBy` — a cascade depends on a row that does not exist yet;
 *  * EXACTLY ONE such field — two means the row is a pairing (stage 13's prototype names a sketch
 *    and an artisan), and thirty half-answered rows look finished.
 *
 * On the current registry that is the roster, sketch reviews, validation, cost sheets and their
 * lines, stage logs, material usage, iterations, certificates and follow-ups.
 *
 * Kept pure (no Compose) so `DwBulkPickTest` can hold it.
 */

/** The server's `MAX_STAGE_ROWS` and the web's `STAGE_ROW_CAP`: entries one stage save may carry. */
internal const val DW_STAGE_ENTRY_CAP = 500

/**
 * How many options the bulk add asks the server for: its `REFERENCE_LIMIT_MAX` and the web's
 * `REFERENCE_PAGE_MAX`. The single pickers read every page (`dwReadAllReferencePages`).
 */
internal const val DW_BULK_PAGE = 200

/** How many refused names a bulk-add sentence lists before "and N more". The web's `DROPPED_NAMES_SHOWN`. */
internal const val DW_DROPPED_NAMES_SHOWN = 6

/** The one field a collection's rows are picked by, or null where the list is not built that way. */
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

/**
 * The one-tap "everything" shortcut's label, or null where none is offered.
 *
 * ONLY FOR THE WORKSHOP'S OWN RECORDS. These lists are the whole of a small, closed set — the
 * participants in the room, the products this workshop made — so "all of them" is a meaningful
 * answer. The artisan REGISTER is not: it is the cluster's, it is served a page at a time, and
 * "add all" there would put strangers on the roster. Same table as the web's `BULK_ALL_LABEL`.
 */
internal fun dwBulkAllLabel(refModel: String): String? = when (refModel) {
    "DwParticipant" -> "All participants"
    "DwFinalProduct" -> "All final products"
    "DwSketch" -> "All sketches"
    "DwPrototype" -> "All prototypes"
    else -> null
}

/** The record ids already on this list, so a record cannot be added twice. */
internal fun dwBulkChosenIds(rows: List<Map<String, JsonElement>>, field: FieldDto): Set<String> =
    rows.mapNotNull { values -> dwRefId(values[field.key]).takeIf { it.isNotBlank() } }.toSet()

/** What one bulk add does: the new rows' values, in pick order, and the options that did not fit. */
internal data class DwBulkAdd(
    val created: List<Map<String, JsonElement>>,
    val dropped: List<DwReferenceOption>,
    val room: Int,
    val asked: Int,
)

/**
 * Turn picked options into rows — UP TO WHAT FITS, and keep the rest to name.
 *
 * Each row is hydrated exactly as a single pick is ([hydratedValues], the registry's own mapping),
 * so a roster built in one go and one built name by name hold identical records.
 *
 * [stageEntries] is every entry this stage would carry now (all lists plus the singleton and the
 * custom answers). Options already on the list are skipped, never counted as refused.
 */
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

/** The sentence after a bulk add that did not fit, or null when everything was added. The web's words. */
internal fun dwBulkRefusal(outcome: DwBulkAdd): String? {
    if (outcome.dropped.isEmpty()) return null
    val names = outcome.dropped.take(DW_DROPPED_NAMES_SHOWN).joinToString(", ") { it.label }
    val more = outcome.dropped.size - DW_DROPPED_NAMES_SHOWN
    return "Added ${outcome.created.size} of ${outcome.asked} — room for ${outcome.room} on this stage. " +
        "Not added: $names${if (more > 0) " and $more more" else ""}. Delete rows to make room."
}

/**
 * The web's trigger wording for the bulk picker ("Add several from participants"), which its
 * cut-short sentence quotes. Kept here so the two clients' sentences match word for word.
 */
internal fun dwBulkTriggerPhrase(fieldLabel: String): String = "add several from ${fieldLabel.lowercase()}"

/**
 * The one line after a bulk add. The refusal wins (some rows did not fit, so "the first N" would
 * contradict it); otherwise a list the server cut short says so (rule 10), with the web's words;
 * otherwise the count.
 */
internal fun dwBulkAddedSentence(outcome: DwBulkAdd, truncated: Boolean, fieldLabel: String): String =
    dwBulkRefusal(outcome)
        ?: if (truncated && outcome.created.isNotEmpty()) {
            "Added the first ${outcome.created.size}. Use ${dwBulkTriggerPhrase(fieldLabel)} for the rest."
        } else {
            "Added ${plural(outcome.created.size, "row")}."
        }

/** Said inside the bulk sheet when the list it holds is one page of a longer one. */
internal fun dwBulkCutShortNote(shown: Int): String = "Showing the first $shown. Search to find the rest."

/** Said when the phone has no copy of the list and the server could not be reached. */
internal const val DW_BULK_NO_LIST = "This list is not on this phone yet. Connect and try again."

/**
 * The sheet's options for a search when the server could not answer: the held list, filtered on
 * the label and sublabel the same way the sheet's own box would have filtered it.
 */
internal fun dwBulkLocalSearch(options: List<DwReferenceOption>, term: String): List<DwReferenceOption> {
    val words = term.trim().lowercase().split(' ', '\t', '\n').filter { it.isNotEmpty() }
    if (words.isEmpty()) return options
    return options.filter { option ->
        val haystack = "${option.label} ${option.hint}".lowercase()
        words.all { it in haystack }
    }
}
