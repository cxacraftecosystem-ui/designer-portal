package com.designprototype.workshop.data

import com.designprototype.workshop.report.summary as richSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

data class DwReadinessAddress(
    val entityKey: String,
    val entityTitle: String,
    val fieldKey: String,
    val fieldLabel: String,
    val anchorFieldKey: String,
    val rowKey: String?,
    val rowTitle: String?,
    val occurrences: Int,
    val rowCount: Int,
)

data class DwReadinessItem(
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val label: String,
    val address: DwReadinessAddress?,
    val href: String,
)

data class DwStageFocus(
    val stageKey: String,
    val entityKey: String,
    val fieldKey: String,
    val rowKey: String? = null,
)

data class DwReadinessCheck(
    val id: String,
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val title: String,
    val detail: String,
    val href: String,
)

data class DwReadinessStageGap(
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val optionalTotal: Int,
    val optionalFilled: Int,
    val href: String,
)

data class DwWorkshopReadiness(
    val stagesTotal: Int,
    val stagesComplete: Int,
    val requiredTotal: Int,
    val requiredFilled: Int,
    val blockedStages: Int,
    val blocking: List<DwReadinessItem>,
    val checks: List<DwReadinessCheck>,
    val advisory: List<DwReadinessStageGap>,
    val isSubmittable: Boolean,
)

object DwSubmissionReadiness {

    private const val REPORT_STAGE_KEY = "REPORT_GENERATION"
    private const val REPORT_SETTINGS_ENTITY = "reportSettings"
    private const val TEMPLATE_FIELD_KEY = "templateId"
    private const val EXCLUDED_STAGES_FIELD_KEY = "excludedStages"

    const val FOCUS_FIELD_PARAM = "find"

    const val FOCUS_ROW_PARAM = "row"

    fun stageHref(workshopId: String, stageKey: String): String =
        "/design-workshops/$workshopId/stages/$stageKey"

    fun stageFieldHref(
        workshopId: String,
        stageKey: String,
        entityKey: String,
        anchorFieldKey: String,
        rowKey: String? = null,
    ): String {
        val query = StringBuilder()
            .append(FOCUS_FIELD_PARAM).append('=').append(encode("$entityKey.$anchorFieldKey"))
        if (!rowKey.isNullOrEmpty()) {
            query.append('&').append(FOCUS_ROW_PARAM).append('=').append(encode(rowKey))
        }
        return "${stageHref(workshopId, stageKey)}?$query"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun focusOf(item: DwReadinessItem): DwStageFocus? {
        val address = item.address ?: return null
        return DwStageFocus(
            stageKey = item.stageKey,
            entityKey = address.entityKey,
            fieldKey = address.anchorFieldKey,
            rowKey = address.rowKey,
        )
    }

    fun addressBook(readiness: DwWorkshopReadiness): Map<String, Map<String, DwStageFocus>> =
        readiness.blocking
            .mapNotNull { item -> focusOf(item)?.let { item.stageKey to (item.label to it) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, entries) -> entries.toMap() }

    private fun missingLabel(entity: EntityDto, field: FieldDto, value: JsonElement?): String {
        val own = dwShortfallLabel(field, value)
        return if (entity.cardinality == "SINGLETON") own else "${entity.title}: $own"
    }

    private fun rowKeyOf(row: DraftRow): String? {
        val clientKey = row.id.substringAfter(DW_ROW_KEY_SEPARATOR, "")
        if (clientKey.isNotEmpty()) return clientKey
        return (row.values["_entryId"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    }

    private fun rowTitleOf(entity: EntityDto, row: DwDataRow, index: Int): String {
        if (entity.labelField.isNotEmpty()) {
            val labelled = DwPy.strip(DwValues.text(row[entity.labelField]))
            if (labelled.isNotEmpty()) return labelled
        }
        for (field in entity.liveFields) {
            when (DwFieldType.of(field.type)) {
                DwFieldType.RICH_TEXT ->
                    richSummary(row[field.key], 80).takeIf { it.isNotEmpty() }?.let { return it }
                DwFieldType.TEXT, DwFieldType.LONG_TEXT ->
                    DwPy.strip(DwValues.text(row[field.key])).takeIf { it.isNotEmpty() }?.let { return it }
                else -> Unit
            }
        }
        return "${entity.title} ${index + 1}"
    }

    private fun stageAddresses(
        spec: StageDto,
        stored: StageDraft?,
        customBlocks: List<DwCustomStageBlock> = emptyList(),
    ): Map<String, DwReadinessAddress> {
        val singleton = stored?.values.orEmpty()
        val found = LinkedHashMap<String, DwReadinessAddress>()

        fun note(label: String, address: DwReadinessAddress) {
            val seen = found[label]
            if (seen != null) found[label] = seen.copy(occurrences = seen.occurrences + 1)
            else found[label] = address
        }

        for (entity in spec.entities) {
            if (entity.cardinality == "SINGLETON") {
                for (field in entity.liveFields) {
                    if (!dwCountsAsRequired(field)) continue
                    val value = singleton[field.key]
                    if (DwValues.isFilled(value) && dwMeetsMinimum(field, value)) continue
                    note(
                        missingLabel(entity, field, value),
                        DwReadinessAddress(
                            entityKey = entity.key,
                            entityTitle = entity.title,
                            fieldKey = field.key,
                            fieldLabel = field.label,
                            anchorFieldKey = field.captionFor.ifEmpty { field.key },
                            rowKey = null,
                            rowTitle = null,
                            occurrences = 1,
                            rowCount = 1,
                        ),
                    )
                }
                continue
            }

            val rows = stored?.rowsFor(entity.key).orEmpty()
            rows.forEachIndexed { index, row ->
                for (field in entity.liveFields) {
                    if (!dwCountsAsRequired(field)) continue
                    val value = row.values[field.key]
                    if (DwValues.isFilled(value) && dwMeetsMinimum(field, value)) continue
                    note(
                        missingLabel(entity, field, value),
                        DwReadinessAddress(
                            entityKey = entity.key,
                            entityTitle = entity.title,
                            fieldKey = field.key,
                            fieldLabel = field.label,
                            anchorFieldKey = field.captionFor.ifEmpty { field.key },
                            rowKey = rowKeyOf(row),
                            rowTitle = rowTitleOf(entity, row.values, index),
                            occurrences = 1,
                            rowCount = rows.size,
                        ),
                    )
                }
            }
        }

        for (block in customBlocks) {
            for (field in block.fields) {
                if (field.retired || !field.required) continue
                if (DwValues.isFilled(stored?.custom.orEmpty()[field.key])) continue
                note(
                    field.label.ifBlank { field.key },
                    DwReadinessAddress(
                        entityKey = customSectionEntityKey(block.section),
                        entityTitle = block.section.title,
                        fieldKey = field.key,
                        fieldLabel = field.label,
                        anchorFieldKey = field.key,
                        rowKey = null,
                        rowTitle = null,
                        occurrences = 1,
                        rowCount = 1,
                    ),
                )
            }
        }

        return found
    }

    fun reportChecks(
        schema: SchemaResponse,
        draft: WorkshopDraft?,
        workshopId: String,
        scores: List<DwStageCompleteness>,
    ): List<DwReadinessCheck> = reportChecks(schema, draft, workshopId, scores.associateBy { it.stageKey })

    private fun reportChecks(
        schema: SchemaResponse,
        draft: WorkshopDraft?,
        workshopId: String,
        scores: Map<String, DwStageCompleteness>,
    ): List<DwReadinessCheck> {
        val spec = schema.stages.firstOrNull { it.key == REPORT_STAGE_KEY } ?: return emptyList()
        val entity = spec.entity(REPORT_SETTINGS_ENTITY) ?: return emptyList()

        val settings = draft?.stages?.get(spec.key)?.values.orEmpty()
        val out = ArrayList<DwReadinessCheck>()
        fun at(fieldKey: String) = stageFieldHref(workshopId, spec.key, entity.key, fieldKey)

        val templateField = entity.field(TEMPLATE_FIELD_KEY)
        val chosen = (settings[TEMPLATE_FIELD_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val token = chosen?.let { DwPy.strip(it) }.orEmpty()
        if (templateField != null && token.isNotEmpty()) {
            val options = templateField.options.ifEmpty { schema.enums[templateField.enumName].orEmpty() }
            if (options.isNotEmpty() && options.none { it.value == token }) {
                out.add(
                    DwReadinessCheck(
                        id = "template-unknown",
                        stageKey = spec.key,
                        stageNumber = spec.number,
                        stageTitle = spec.title,
                        title = "Stage 20's report template isn't available",
                        detail =
                            "Stage 20 names \"$token\", which isn't available. The workshop's default template will be used. Re-pick it to fix.",
                        href = at(TEMPLATE_FIELD_KEY),
                    )
                )
            }
        }

        val excluded = settings[EXCLUDED_STAGES_FIELD_KEY]
        val excludedKeys = (excluded as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        for (stageKey in excludedKeys) {
            val target = schema.stages.firstOrNull { it.key == stageKey } ?: continue
            val score = scores[target.key] ?: continue
            if (score.requiredFilled == 0 && score.optionalFilled == 0) continue
            out.add(
                DwReadinessCheck(
                    id = "stage-excluded:${target.key}",
                    stageKey = target.key,
                    stageNumber = target.number,
                    stageTitle = target.title,
                    title = "Stage ${target.number} is excluded from the report but has answers",
                    detail =
                        "${plural(score.requiredFilled + score.optionalFilled, "field")} filled on \"${target.title}\", but stage 20 excludes it from the report.",
                    href = at(EXCLUDED_STAGES_FIELD_KEY),
                )
            )
        }

        return out
    }

    fun assess(
        schema: SchemaResponse,
        draft: WorkshopDraft?,
        workshopId: String,
        definition: DwCustomCache? = null,
    ): DwWorkshopReadiness {
        val scores = computeWorkshopCompleteness(schema, draft, definition)
        val specs = schema.stages.associateBy { it.key }

        val blocking = ArrayList<DwReadinessItem>()
        val advisory = ArrayList<DwReadinessStageGap>()
        var requiredTotal = 0
        var requiredFilled = 0
        var blockedStages = 0

        for (score in scores) {
            val spec = specs[score.stageKey] ?: continue

            requiredTotal += score.requiredTotal
            requiredFilled += score.requiredFilled

            if (score.missing.isNotEmpty()) {
                blockedStages += 1
                val addresses = stageAddresses(
                    spec, draft?.stages?.get(spec.key), customStageBlocks(definition, spec.key),
                )
                for (label in score.missing) {
                    val address = addresses[label]
                    blocking.add(
                        DwReadinessItem(
                            stageKey = spec.key,
                            stageNumber = spec.number,
                            stageTitle = spec.title,
                            label = label,
                            address = address,
                            href = if (address != null) {
                                stageFieldHref(
                                    workshopId, spec.key, address.entityKey, address.anchorFieldKey, address.rowKey,
                                )
                            } else {
                                stageHref(workshopId, spec.key)
                            },
                        )
                    )
                }
            }

            if (score.optionalFilled < score.optionalTotal) {
                advisory.add(
                    DwReadinessStageGap(
                        stageKey = spec.key,
                        stageNumber = spec.number,
                        stageTitle = spec.title,
                        optionalTotal = score.optionalTotal,
                        optionalFilled = score.optionalFilled,
                        href = stageHref(workshopId, spec.key),
                    )
                )
            }
        }

        return DwWorkshopReadiness(
            stagesTotal = schema.stages.size,
            stagesComplete = dwStageProgress(scores, schema.stages.size).stagesComplete,
            requiredTotal = requiredTotal,
            requiredFilled = requiredFilled,
            blockedStages = blockedStages,
            blocking = blocking,
            checks = reportChecks(schema, draft, workshopId, scores.associateBy { it.stageKey }),
            advisory = advisory,
            isSubmittable = blocking.isEmpty(),
        )
    }

    fun summary(readiness: DwWorkshopReadiness): String {
        val stages = "${readiness.stagesComplete} of ${readiness.stagesTotal} stages complete"
        if (readiness.blocking.isEmpty()) return "$stages; no required fields remain."
        val count = readiness.blocking.size
        val fields = "$count required field${if (count == 1) "" else "s"}"
        val where = "${readiness.blockedStages} stage${if (readiness.blockedStages == 1) "" else "s"}"
        return "$stages; $fields remain${if (count == 1) "s" else ""} in $where."
    }

    fun fieldName(item: DwReadinessItem): String {
        val address = item.address ?: return item.label
        val rowTitle = address.rowTitle ?: return address.fieldLabel
        return "${address.fieldLabel} · $rowTitle"
    }
}
