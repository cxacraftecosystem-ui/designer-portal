package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val CUSTOM_ENTITY_KEY: String = "_custom"

val V1_CUSTOM_TYPES: List<String> = listOf(
    "TEXT", "LONG_TEXT", "INT", "DECIMAL", "MONEY", "PERCENT",
    "DATE", "TIME", "BOOL", "ENUM", "MULTI_ENUM", "TAGS",
)

private val V1_CUSTOM_TYPE_SET: Set<String> = V1_CUSTOM_TYPES.toSet()

fun isV1CustomType(type: String): Boolean = type in V1_CUSTOM_TYPE_SET

fun dwCustomFieldDrawable(type: String): Boolean =
    isV1CustomType(type) && DwFieldType.known(type) != null

val CUSTOM_KEY_PATTERN: Regex = Regex("^[a-z][A-Za-z0-9]{0,39}$")

@Serializable
data class DwCustomOptionDto(
    val value: String = "",
    val label: String = "",
)

@Serializable
data class DwCustomFieldDto(
    val id: String = "",
    val key: String = "",
    val label: String = "",
    val type: String = "TEXT",
    val tier: String = "STANDARD",
    val required: Boolean = false,
    val help: String = "",
    val unit: String = "",
    val options: List<DwCustomOptionDto> = emptyList(),
    val maxLength: Int = 0,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val sortOrder: Int = 0,
    val retired: Boolean = false,
    val supersededById: String? = null,
)

@Serializable
data class DwCustomSectionDto(
    val id: String = "",
    val key: String = "",
    val stageKey: String = "",
    val title: String = "",
    val description: String = "",
    val sortOrder: Int = 0,
    val revision: Int = 1,
    val retired: Boolean = false,
    val fields: List<DwCustomFieldDto> = emptyList(),
)

@Serializable
data class DwCustomDefinitionDto(
    val customSchemaVersion: String = "",
    val sections: List<DwCustomSectionDto> = emptyList(),
    val fetchedAt: String = "",
)

@Serializable
data class DwCustomCache(
    val workshopId: String = "",
    val customSchemaVersion: String = "",
    val sections: List<DwCustomSectionDto> = emptyList(),
    val complete: Boolean = false,
    val fetchedAt: String = "",
)

enum class DwCustomCopy {
    UNKNOWN,

    NONE_DEFINED,

    DEFINED,
}

fun dwCustomCopy(cache: DwCustomCache?): DwCustomCopy = when {
    cache == null -> DwCustomCopy.UNKNOWN
    cache.sections.isNotEmpty() -> DwCustomCopy.DEFINED
    cache.complete -> DwCustomCopy.NONE_DEFINED
    else -> DwCustomCopy.UNKNOWN
}

val DwCustomCache?.isHeld: Boolean
    get() = dwCustomCopy(this) != DwCustomCopy.UNKNOWN

data class DwCustomStageBlock(
    val section: DwCustomSectionDto,
    val fields: List<DwCustomFieldDto>,
) {
    val live: List<DwCustomFieldDto> get() = fields.filter { !it.retired }
}

fun sectionsForStage(cache: DwCustomCache?, stageKey: String): List<DwCustomSectionDto> {
    if (cache == null || stageKey.isBlank()) return emptyList()
    return cache.sections
        .filter { it.stageKey == stageKey }
        .sortedWith(compareBy({ it.sortOrder }, { it.key }))
        .map(::withSectionRetirementForced)
}

private fun withSectionRetirementForced(section: DwCustomSectionDto): DwCustomSectionDto =
    if (!section.retired || section.fields.none { !it.retired }) section
    else section.copy(fields = section.fields.map { if (it.retired) it else it.copy(retired = true) })

fun customSectionsForReport(
    cache: DwCustomCache?,
    answersFor: (stageKey: String) -> Map<String, JsonElement>,
): List<DwCustomSectionDto> {
    if (cache == null) return emptyList()
    return cache.sections
        .map(::withSectionRetirementForced)
        .filter { section ->
            if (!section.retired) return@filter true
            val values = answersFor(section.stageKey)
            section.fields.any { DwValues.isFilled(values[it.key]) }
        }
}

fun customStageBlocks(cache: DwCustomCache?, stageKey: String): List<DwCustomStageBlock> =
    sectionsForStage(cache, stageKey).map { section ->
        DwCustomStageBlock(
            section = section,
            fields = section.fields.sortedWith(compareBy({ it.sortOrder }, { it.key })),
        )
    }

fun customFieldsForStage(cache: DwCustomCache?, stageKey: String): List<DwCustomFieldDto> {
    val out = ArrayList<DwCustomFieldDto>()
    val slotOf = HashMap<String, Int>()
    customStageBlocks(cache, stageKey).forEach { block ->
        block.fields.forEach { field ->
            val slot = slotOf[field.key]
            if (slot == null) {
                slotOf[field.key] = out.size
                out += field
            } else if (out[slot].retired && !field.retired) {
                out[slot] = field
            }
        }
    }
    return out
}

fun liveCustomFields(section: DwCustomSectionDto): List<DwCustomFieldDto> =
    section.fields.filter { !it.retired }.sortedWith(compareBy({ it.sortOrder }, { it.key }))

fun retiredCustomFieldsWithAnswers(
    section: DwCustomSectionDto,
    values: Map<String, JsonElement>,
): List<DwCustomFieldDto> = section.fields
    .filter { it.retired && DwValues.isFilled(values[it.key]) }
    .sortedWith(compareBy({ it.sortOrder }, { it.key }))

fun customFieldToFieldDto(field: DwCustomFieldDto): FieldDto = FieldDto(
    key = field.key,
    label = field.label,
    type = field.type,
    tier = field.tier,
    required = field.required,
    help = field.help,
    unit = field.unit,
    options = field.options.map { EnumOption(value = it.value, label = it.label) },
    maxLength = field.maxLength,
    minValue = field.minValue,
    maxValue = field.maxValue,
)

fun customSectionEntityKey(section: DwCustomSectionDto): String = "$CUSTOM_ENTITY_KEY:${section.key}"

fun customSectionEntity(section: DwCustomSectionDto): EntityDto = EntityDto(
    key = customSectionEntityKey(section),
    name = "DwCustomSection",
    cardinality = "SINGLETON",
    title = section.title,
    description = section.description,
    fields = liveCustomFields(section).filter { dwCustomFieldDrawable(it.type) }.map(::customFieldToFieldDto),
)

fun undrawableCustomFieldsWithValues(
    section: DwCustomSectionDto,
    values: Map<String, JsonElement>,
): List<DwCustomFieldDto> = liveCustomFields(section)
    .filter { !dwCustomFieldDrawable(it.type) && DwValues.isFilled(values[it.key]) }

fun dwCustomUnsupportedNote(type: String): String =
    "Update the app to answer this question. Existing answers are kept."

fun dwCustomSectionWarnings(copy: DwCustomCopy, answersHeld: Boolean = false): List<String> {
    if (copy != DwCustomCopy.UNKNOWN) return emptyList()
    val tail = "Open this workshop once with a connection to include them."
    return listOf(
        if (answersHeld) {
            "This device holds answers to this workshop's own questions, but not the questions, so they aren't in this file. $tail"
        } else {
            "If this workshop has its own questions, their answers may be missing. $tail"
        }
    )
}
