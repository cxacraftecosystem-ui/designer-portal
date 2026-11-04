package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DW_ROW_KEY_SEPARATOR
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.dwRefId
import com.designprototype.workshop.data.rowsFor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

internal data class DwParentGroup(
    val heading: String,
    val rows: List<Map<String, JsonElement>>,
)

internal fun dwParentGroups(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    entity: EntityDto,
    rows: List<Map<String, JsonElement>>,
    parentHeading: (EntityDto, Map<String, JsonElement>, Int) -> String,
    refLabel: (String) -> String,
): List<DwParentGroup>? {
    if (entity.parent.isBlank()) return null

    var parentEntity: EntityDto? = null
    var parentStageKey = ""
    for (stage in schema.stages) {
        val found = stage.entities.firstOrNull { it.key == entity.parent } ?: continue
        parentEntity = found
        parentStageKey = stage.key
        break
    }
    val parent = parentEntity ?: return null

    val link = entity.fields.firstOrNull {
        DwFieldType.of(it.type) == DwFieldType.REF && it.refModel == parent.name
    } ?: return null

    val parentRows = draft?.stages?.get(parentStageKey)?.rowsFor(parent.key).orEmpty()

    val ownerOf = HashMap<String, Int>()
    parentRows.forEachIndexed { index, parentRow ->
        val localKey = parentRow.id.substringAfter(DW_ROW_KEY_SEPARATOR, "")
        val entryId = (parentRow.values["_entryId"] as? JsonPrimitive)?.content.orEmpty()
        for (id in listOf(localKey, entryId)) {
            if (id.isBlank()) continue
            if (!ownerOf.containsKey(id)) ownerOf[id] = index
        }
    }

    val claimed = List(parentRows.size) { ArrayList<Map<String, JsonElement>>() }
    val named = LinkedHashMap<String, ArrayList<Map<String, JsonElement>>>()
    val orphans = ArrayList<Map<String, JsonElement>>()
    for (row in rows) {
        val ref = dwRefId(row[link.key]).trim()
        val owner = ownerOf[ref]
        when {
            owner != null -> claimed[owner].add(row)
            ref.isEmpty() -> orphans.add(row)
            else -> named.getOrPut(ref) { ArrayList() }.add(row)
        }
    }

    val groups = ArrayList<DwParentGroup>()
    parentRows.forEachIndexed { index, parentRow ->
        val taken = claimed[index]
        if (taken.isNotEmpty()) {
            groups += DwParentGroup(parentHeading(parent, parentRow.values, index), taken)
        }
    }

    for ((ref, taken) in named) {
        val label = refLabel(ref)
        if (label.isNotBlank()) groups += DwParentGroup(label, taken) else orphans += taken
    }

    if (orphans.isNotEmpty()) {
        groups += DwParentGroup("No ${link.label.lowercase(Locale.ROOT)} recorded", orphans)
    }
    return groups
}
