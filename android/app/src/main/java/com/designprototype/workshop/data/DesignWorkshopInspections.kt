package com.designprototype.workshop.data

import com.designprototype.workshop.report.summary as richSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

private val INSPECTION_ROLES = setOf("INSPECTOR")

fun canInspectDesignWorkshops(role: String?): Boolean = role in INSPECTION_ROLES

@Serializable
data class DwInspectorDto(
    val userId: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",
    val assignedAt: String? = null,
)

@Serializable
data class DwInspectorListDto(val inspectors: List<DwInspectorDto> = emptyList())

@Serializable
data class DwEligibleInspectorDto(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "",
)

@Serializable
data class DwEligibleInspectorListDto(
    val users: List<DwEligibleInspectorDto> = emptyList(),
    val truncated: Boolean = false,
)

data class DwEligibleInspectors(
    val users: List<DwEligibleInspectorDto> = emptyList(),
    val truncated: Boolean = false,
    val search: String? = null,
) {
    val complete: Boolean get() = !truncated && search == null
}

fun dwInspectorOfferNotice(offer: DwEligibleInspectors): String? = when {
    offer.truncated && offer.search == null ->
        "Too many to list — search by name or email."
    offer.truncated -> "Too many matches — narrow the search."
    offer.search != null && offer.users.isEmpty() ->
        "No account that may inspect matches that search."
    else -> null
}

fun dwInspectorSearchTerm(typed: String?): String? {
    val term = DwPy.strip(typed.orEmpty())
    if (term.isEmpty()) return null
    if (term.length <= DW_INSPECTOR_SEARCH_MAX) return term
    val cut = term.take(DW_INSPECTOR_SEARCH_MAX)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

const val DW_INSPECTOR_SEARCH_MAX = 120

@Serializable
data class DwInspectorsBody(val userIds: List<String>)

const val DW_INSPECTOR_LIMIT = 25

data class DwInspectorChoice(
    val userId: String,
    val name: String,
    val email: String,
    val role: String,
    val assignedButIneligible: Boolean = false,
)

fun dwInspectorChoices(
    eligible: List<DwEligibleInspectorDto>,
    inspectors: List<DwInspectorDto>,
    readerId: String?,
    eligibleListComplete: Boolean,
    retained: List<DwEligibleInspectorDto> = emptyList(),
): List<DwInspectorChoice> {
    val reader = readerId?.takeIf { it.isNotBlank() }
    val choices = ArrayList<DwInspectorChoice>(eligible.size + retained.size + inspectors.size)
    val seen = HashSet<String>()
    (eligible + retained).forEach { person ->
        if (person.id.isBlank() || person.id == reader || !seen.add(person.id)) return@forEach
        choices += DwInspectorChoice(
            userId = person.id,
            name = person.name,
            email = person.email,
            role = person.role,
        )
    }
    inspectors.forEach { row ->
        if (row.userId.isBlank() || !seen.add(row.userId)) return@forEach
        choices += DwInspectorChoice(
            userId = row.userId,
            name = row.name,
            email = row.email,
            role = row.role,
            assignedButIneligible = eligibleListComplete && row.userId != reader,
        )
    }
    return choices
}

data class DwInspectorSelection(
    val baseline: Set<String> = emptySet(),
    val selected: Set<String> = emptySet(),
) {
    val added: Set<String> get() = selected - baseline

    val removed: Set<String> get() = baseline - selected

    val dirty: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()

    val resultingCount: Int get() = selected.size

    val overLimit: Boolean get() = payload().size > DW_INSPECTOR_LIMIT

    fun payload(): List<String> = selected.toList()

    fun withSelection(ids: Set<String>): DwInspectorSelection = copy(selected = ids - "")

    fun discard(): DwInspectorSelection = copy(selected = baseline)

    companion object {
        fun adopt(rows: List<DwInspectorDto>): DwInspectorSelection {
            val held = rows.map { it.userId }.filter { it.isNotBlank() }.toSet()
            return DwInspectorSelection(baseline = held, selected = held)
        }
    }
}

fun dwInspectionAdministrationMissing(status: Int?): Boolean = status == 404

enum class DwInspectionAttempt { READ, SAVE }

fun dwInspectionFailureMessage(
    status: Int?,
    serverMessage: String?,
    attempt: DwInspectionAttempt = DwInspectionAttempt.READ,
): String {
    val said = serverMessage?.trim()?.takeIf { it.isNotEmpty() }
    val unknownOutcome = attempt == DwInspectionAttempt.SAVE
    return when {
        status == null ->
            "Could not connect. " +
                if (unknownOutcome) {
                    "Your change may already have been saved. Reopen online to check before changing more."
                } else {
                    "Nothing has been changed."
                }

        status == 401 ->
            "Signed out — nothing was sent. Sign in and try again."

        status == 403 ->
            said.asInspectionSentence() +
                "A workshop under inspection is read by the accounts appointed to inspect it; a Ministry Admin, an admin or the master admin decides who inspects what. Nothing was changed."

        status == 404 ->
            said.asInspectionSentence() +
                "This workshop isn't open to you. Nothing changed."

        status == 422 ->
            "This could not be saved. " +
                (said ?: "One account can't be assigned. Nothing changed.")

        status >= 500 ->
            (said ?: "Something went wrong.") +
                " This is not a connection problem. " +
                if (unknownOutcome) {
                    "Part of the change may have been saved — reopen to check."
                } else {
                    "Nothing was changed. Try again in a moment."
                }

        else -> said ?: "That did not go through."
    }
}

private fun String?.asInspectionSentence(): String = when {
    this == null -> ""
    isEmpty() -> ""
    last() in ".!?:" -> "$this "
    else -> "$this. "
}

@Serializable
data class DwInspectionDetailDto(
    val id: String = "",
    val title: String = "",
    val templateId: String = "DCH_STANDARD",
    val status: String = "DRAFT",
    val workshopCode: String? = null,
    val craftName: String? = null,
    val clusterName: String? = null,
    val state: String? = null,
    val district: String? = null,
    val venue: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val designerName: String? = null,
    val implementingAgency: String? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val schemaVersion: String = "",
    val customSchemaVersion: String = "",
    val stages: Map<String, StageBucketDto> = emptyMap(),
    val completeness: Map<String, StageCompletenessDto> = emptyMap(),
    val readOnly: Boolean? = null,
    val reviewNotes: String? = null,
    val reviewedById: String? = null,
    val reviewedAt: String? = null,
    val submissionRound: Int = 0,
    val inspectionFeedback: List<DwInspectionFeedbackDto> = emptyList(),
    val inspectionFeedbackTruncated: Boolean = false,
    val mayRecordFeedback: Boolean = false,
    val myClearedAt: String? = null,
)

fun dwInspectionIsReadOnly(readOnly: Boolean?): Boolean = readOnly != false

enum class DwInspectionDestination {
    STAGES,
    PHOTOS,
    REPORT,
    READINESS,
    CUSTOM_SECTIONS,
    AI_LAYERS,
    CODES,
    PROVENANCE,
    SKETCHES_AND_PROTOTYPES,
}

@Suppress("UNUSED_PARAMETER")
fun dwInspectionMayOpen(destination: DwInspectionDestination, readOnly: Boolean?): Boolean =
    !dwInspectionIsReadOnly(readOnly)

sealed interface DwInspectionReading {
    data class Text(val text: String) : DwInspectionReading
    data object Empty : DwInspectionReading
    data class Media(val count: Int) : DwInspectionReading
}

fun dwInspectionFieldReading(
    schema: SchemaResponse,
    entity: EntityDto,
    field: FieldDto,
    row: Map<String, JsonElement>,
): DwInspectionReading {
    val value = row[field.key]
    val type = DwFieldType.of(field.type)

    if (type.isMedia) {
        val count = when {
            value is JsonArray -> DwValues.list(value).size
            DwValues.isFilled(value) -> 1
            else -> 0
        }
        return if (count > 0) DwInspectionReading.Media(count) else DwInspectionReading.Empty
    }

    if (!DwValues.isFilled(value)) return DwInspectionReading.Empty

    return when (type) {
        DwFieldType.RICH_TEXT ->
            richSummary(value, 2000).takeIf { it.isNotEmpty() }
                ?.let { DwInspectionReading.Text(it) } ?: DwInspectionReading.Empty

        DwFieldType.BOOL -> when (DwValues.bool(value)) {
            true -> DwInspectionReading.Text("Yes")
            false -> DwInspectionReading.Text("No")
            null -> DwValues.text(value).trim().takeIf { it.isNotEmpty() }
                ?.let { DwInspectionReading.Text(it) } ?: DwInspectionReading.Empty
        }

        DwFieldType.ENUM ->
            DwInspectionReading.Text(dwInspectionOptionLabel(schema, field, DwValues.text(value).trim()))

        DwFieldType.MULTI_ENUM -> DwInspectionReading.Text(
            DwValues.list(value).joinToString(" · ") { dwInspectionOptionLabel(schema, field, it) }
        )

        DwFieldType.TAGS -> DwInspectionReading.Text(DwValues.list(value).joinToString(" · "))

        DwFieldType.GEO -> {
            val point = DwValues.geo(value)
            if (point == null) {
                DwInspectionReading.Empty
            } else {
                val accuracy = ((value as? JsonObject)?.get("accuracy") as? JsonPrimitive)
                    ?.doubleOrNull?.takeIf { it.isFinite() }
                val at = String.format(Locale.ROOT, "%.6f, %.6f", point.first, point.second)
                DwInspectionReading.Text(
                    if (accuracy == null) at else "$at (±${Math.round(accuracy)} m)"
                )
            }
        }

        DwFieldType.REF -> {
            val named = dwInspectionReferenceHint(entity, field, row)
            DwInspectionReading.Text(named.ifEmpty { "A linked record" })
        }

        else -> {
            val text = DwValues.text(value).trim()
            if (text.isEmpty()) {
                DwInspectionReading.Empty
            } else {
                DwInspectionReading.Text(if (field.unit.isNotEmpty()) "$text ${field.unit}" else text)
            }
        }
    }
}

private fun dwInspectionOptionLabel(schema: SchemaResponse, field: FieldDto, token: String): String {
    val options = field.options.ifEmpty { schema.enums[field.enumName].orEmpty() }
    return options.firstOrNull { it.value == token }?.label ?: token
}

private fun dwInspectionReferenceHint(
    entity: EntityDto,
    field: FieldDto,
    row: Map<String, JsonElement>,
): String {
    if (field.refModel.isEmpty()) return ""
    val stem = field.key.removeSuffix("Ref")
    val cascadeParent = entity.liveFields.any { it.refFilterBy == field.key }
    val candidates = buildList {
        add(stem + "Name")
        add("documented" + stem.replaceFirstChar { it.uppercase() } + "Name")
        add(stem + "Title")
        if (cascadeParent) add("documentedFor")
        add("name")
    }
    for (key in candidates) {
        val target = entity.field(key)?.takeIf { !it.deprecated } ?: continue
        val targetType = DwFieldType.of(target.type)
        if (targetType != DwFieldType.TEXT && targetType != DwFieldType.LONG_TEXT) continue
        val text = DwPy.strip(DwValues.text(row[key]))
        if (text.isNotEmpty()) return text
    }
    return ""
}

fun dwInspectionUnansweredCount(
    schema: SchemaResponse,
    entity: EntityDto,
    fields: List<FieldDto>,
    row: Map<String, JsonElement>,
): Int = fields.count { dwInspectionFieldReading(schema, entity, it, row) is DwInspectionReading.Empty }
