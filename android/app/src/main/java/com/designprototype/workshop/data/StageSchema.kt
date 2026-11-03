package com.designprototype.workshop.data

import android.content.Context
import com.designprototype.workshop.report.isEmptyDocument
import com.designprototype.workshop.report.fromJson as richTextFromJson
import com.designprototype.workshop.report.toJson as richTextToJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class EnumOption(
    val value: String = "",
    val label: String = "",
)

const val DW_DEFAULT_MAX_ITEMS: Int = 200

fun dwEffectiveMaxItems(declared: Int): Int = declared.takeIf { it > 0 } ?: DW_DEFAULT_MAX_ITEMS

fun dwDeclaredMinItems(declared: Int): Int? = declared.takeIf { it > 0 }

@Serializable
data class FieldDto(
    val key: String = "",
    val label: String = "",
    val type: String = "TEXT",
    val tier: String = "STANDARD",
    val required: Boolean = false,
    val help: String = "",
    val details: String = "",
    val unit: String = "",
    @SerialName("enum") val enumName: String = "",
    val options: List<EnumOption> = emptyList(),
    val refModel: String = "",
    val refFilterBy: String = "",
    val refScope: String = "",
    val refHydration: Map<String, String> = emptyMap(),
    val maxLength: Int = 0,
    val maxItems: Int = 0,
    val minItems: Int = 0,
    val storeMasked: Boolean = false,
    val format: String = "",
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val reportRole: String = "KEY_VALUE",
    val derivedKind: String = "",
    val derivedFrom: List<String> = emptyList(),
    val columnWidthPct: Float = 0f,
    val captionFor: String = "",
    val deprecated: Boolean = false,
    val replacedBy: String = "",
    val autoNumber: String = "",
    val autoNumberWithin: String = "",
    val autoDateFromDay: String = "",
    val computedKind: String = "",
    val widget: String = "",
    val refSuggest: RefSuggestDto? = null,
)

@Serializable
data class RefSuggestDto(
    val fill: Map<String, String> = emptyMap(),
    val hint: Map<String, String> = emptyMap(),
)

@Serializable
data class EntityDto(
    val key: String = "",
    val name: String = "",
    val cardinality: String = "SINGLETON",
    val title: String = "",
    val description: String = "",
    val parent: String = "",
    val labelField: String = "",
    val fields: List<FieldDto> = emptyList(),
)

@Serializable
data class StageDto(
    val number: Int = 0,
    val key: String = "",
    val title: String = "",
    val purpose: String = "",
    val notes: String = "",
    val optionalStage: Boolean = false,
    val entities: List<EntityDto> = emptyList(),
)

@Serializable
data class SchemaResponse(
    val version: String = "",
    val enums: Map<String, List<EnumOption>> = emptyMap(),
    val stages: List<StageDto> = emptyList(),
)

enum class DwFieldType {
    TEXT, LONG_TEXT, RICH_TEXT, INT, DECIMAL, MONEY, PERCENT, DATE, TIME, BOOL, ENUM, MULTI_ENUM,
    TAGS, IMAGE, IMAGE_LIST, FILE, AUDIO, VIDEO, GEO, REF, URL, PHONE, EMAIL;

    val isMedia: Boolean
        get() = this == IMAGE || this == IMAGE_LIST || this == FILE || this == AUDIO || this == VIDEO

    val isNumeric: Boolean get() = this == INT || this == DECIMAL || this == MONEY || this == PERCENT

    val isFreeText: Boolean get() = this == TEXT || this == LONG_TEXT || this == RICH_TEXT

    val isMulti: Boolean get() = this == MULTI_ENUM || this == TAGS || this == IMAGE_LIST

    companion object {
        fun of(raw: String): DwFieldType =
            entries.firstOrNull { it.name == raw } ?: TEXT

        fun known(raw: String): DwFieldType? = entries.firstOrNull { it.name == raw }
    }
}

enum class DwTier {
    BASIC, STANDARD, ADVANCED;

    companion object {
        fun of(raw: String): DwTier = entries.firstOrNull { it.name == raw } ?: STANDARD
    }
}

val StageDto.singleton: EntityDto?
    get() = entities.firstOrNull { it.cardinality == "SINGLETON" }

val StageDto.collections: List<EntityDto>
    get() = entities.filter { it.cardinality != "SINGLETON" }

fun StageDto.entity(key: String): EntityDto? = entities.firstOrNull { it.key == key }

fun EntityDto.field(key: String): FieldDto? = fields.firstOrNull { it.key == key }

val EntityDto.liveFields: List<FieldDto>
    get() = fields.filter { !it.deprecated }

val EntityDto.rowTitleField: FieldDto?
    get() = field(labelField)
        ?: liveFields.firstOrNull { DwFieldType.of(it.type).let { t -> t == DwFieldType.TEXT || t == DwFieldType.LONG_TEXT } }
        ?: liveFields.firstOrNull()

object DwValues {

    private val TRUE_TOKENS = setOf("true", "yes", "y", "1", "on")
    private val FALSE_TOKENS = setOf("false", "no", "n", "0", "off")

    data class Coerced(val value: JsonElement?, val error: String?)

    fun text(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString(", ") { (it as? JsonPrimitive)?.content.orEmpty() }
        is JsonObject -> ""
    }

    fun list(value: JsonElement?): List<String> = when (value) {
        null, JsonNull -> emptyList()
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> listOfNotNull(value.content.takeIf(String::isNotBlank))
        is JsonObject -> emptyList()
    }

    fun bool(value: JsonElement?): Boolean? {
        val primitive = value as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        val token = primitive.content.trim().lowercase(Locale.ROOT)
        return when {
            token in TRUE_TOKENS -> true
            token in FALSE_TOKENS -> false
            else -> null
        }
    }

    fun geo(value: JsonElement?): Pair<Double, Double>? {
        val obj = value as? JsonObject ?: return null
        val lat = (obj["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (obj["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return lat to lon
    }

    fun geoOf(lat: Double, lon: Double, accuracy: Double? = null): JsonObject = JsonObject(
        buildMap {
            put("lat", JsonPrimitive(lat))
            put("lon", JsonPrimitive(lon))
            if (accuracy != null) put("accuracy", JsonPrimitive(accuracy))
        }
    )

    fun ofList(items: List<String>): JsonElement? =
        if (items.isEmpty()) null else JsonArray(items.map { JsonPrimitive(it) })

    fun isFilled(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> value.content.isNotBlank()
        is JsonArray -> value.isNotEmpty()
        is JsonObject ->
            if ("blocks" in value) !isEmptyDocument(value)
            else value.isNotEmpty()
    }

    fun coerce(field: FieldDto, raw: String): Coerced {
        val type = DwFieldType.of(field.type)
        val trimmed = if (type == DwFieldType.LONG_TEXT) raw else raw.trim()
        if (trimmed.isBlank()) return Coerced(null, null)

        return when (type) {
            DwFieldType.INT -> {
                val parsed = trimmed.replace(",", "").toLongOrNull()
                    ?: return Coerced(null, "${field.label} is not a valid whole number")
                rangeChecked(field, parsed.toDouble())?.let { return Coerced(null, it) }
                Coerced(JsonPrimitive(parsed), null)
            }

            DwFieldType.DECIMAL, DwFieldType.PERCENT -> {
                val parsed = trimmed.replace(",", "").replace("₹", "").toDoubleOrNull()
                    ?.takeIf { it.isFinite() }
                    ?: return Coerced(null, "${field.label} is not a valid number")
                rangeChecked(field, parsed)?.let { return Coerced(null, it) }
                Coerced(JsonPrimitive(parsed), null)
            }

            DwFieldType.MONEY -> {
                val parsed = trimmed.replace(",", "").replace("₹", "").toDoubleOrNull()
                    ?.takeIf { it.isFinite() }
                    ?: return Coerced(null, "${field.label} is not a valid amount")
                rangeChecked(field, parsed)?.let { return Coerced(null, it) }
                Coerced(JsonPrimitive(BigDecimal(parsed).setScale(2, RoundingMode.HALF_EVEN).toPlainString()), null)
            }

            DwFieldType.BOOL -> when (trimmed.lowercase(Locale.ROOT)) {
                in TRUE_TOKENS -> Coerced(JsonPrimitive(true), null)
                in FALSE_TOKENS -> Coerced(JsonPrimitive(false), null)
                else -> Coerced(null, "${field.label} must be yes or no")
            }

            DwFieldType.DATE -> try {
                Coerced(JsonPrimitive(LocalDate.parse(trimmed.take(10)).toString()), null)
            } catch (_: DateTimeParseException) {
                Coerced(null, "${field.label} is not a valid date")
            }

            DwFieldType.TIME -> {
                val parts = trimmed.split(":")
                val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                    Coerced(null, "${field.label} is not a valid time")
                } else {
                    Coerced(JsonPrimitive(String.format(Locale.ROOT, "%02d:%02d", hour, minute)), null)
                }
            }

            DwFieldType.ENUM ->
                if (field.options.isNotEmpty() && field.options.none { it.value == trimmed }) {
                    Coerced(null, "${field.label}: “$trimmed” is not one of the options")
                } else {
                    Coerced(JsonPrimitive(trimmed), null)
                }

            DwFieldType.URL ->
                if (trimmed.contains(' ')) {
                    Coerced(null, "${field.label} must not contain spaces")
                } else {
                    scalarText(field, trimmed)
                }

            else -> scalarText(field, trimmed)
        }
    }

    private fun scalarText(field: FieldDto, text: String): Coerced {
        if (field.maxLength > 0 && text.length > field.maxLength) {
            return Coerced(null, "${field.label} is longer than ${field.maxLength} characters")
        }

        DwTextFormats.error(field.format, text)?.let { return Coerced(null, it) }

        val stored = if (field.storeMasked) ArtisanIdentity.mask(text) ?: text else text
        return Coerced(JsonPrimitive(stored), null)
    }

    private fun rangeChecked(field: FieldDto, value: Double): String? {
        val min = field.minValue
        val max = field.maxValue
        return when {
            min != null && value < min -> "${field.label} must be at least ${trimNumber(min)}"
            max != null && value > max -> "${field.label} must be at most ${trimNumber(max)}"
            else -> null
        }
    }

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    fun coerceHydrated(field: FieldDto, raw: JsonElement): JsonElement? {
        if (!isFilled(raw)) return null
        val type = DwFieldType.of(field.type)

        if (type.isMulti) {
            val items = (raw as? JsonArray ?: JsonArray(listOf(raw)))
                .map { text(it).trim() }
                .filter { it.isNotBlank() }
            if (items.isEmpty()) return null
            if (type == DwFieldType.MULTI_ENUM && field.options.isNotEmpty() &&
                items.any { token -> field.options.none { it.value == token } }
            ) return null
            if (items.size > dwEffectiveMaxItems(field.maxItems)) return null
            return JsonArray(items.map { JsonPrimitive(it) })
        }

        if (type.isMedia) return raw

        if (type == DwFieldType.GEO) return raw as? JsonObject

        if (type == DwFieldType.RICH_TEXT) {
            val doc = richTextFromJson(raw)
            return if (doc.isEmpty) null else richTextToJson(doc)
        }

        return coerce(field, text(raw)).value
    }

    fun validate(
        entity: EntityDto,
        data: Map<String, JsonElement>,
        enforceRequired: Boolean,
    ): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        for (field in entity.liveFields) {
            val stored = data[field.key]
            if (!isFilled(stored)) {
                if (enforceRequired && field.required) errors[field.key] = "${field.label} is required"
                continue
            }
            val type = DwFieldType.of(field.type)
            if (!type.isMulti && type != DwFieldType.GEO && !type.isMedia) {
                coerce(field, text(stored)).error?.let { errors[field.key] = it }
            }
        }
        checkConditional(entity, data, errors)
        return errors
    }

    private val CONDITIONALLY_REQUIRED: Map<String, Pair<String, List<String>>> = mapOf(
        "outcomes" to ("countOverrideReason" to listOf("designsCountOverride", "prototypesCountOverride")),
    )

    private fun checkConditional(
        entity: EntityDto,
        data: Map<String, JsonElement>,
        errors: MutableMap<String, String>,
    ) {
        val (dependent, triggers) = CONDITIONALLY_REQUIRED[entity.key] ?: return
        if (isFilled(data[dependent]) || errors.containsKey(dependent)) return
        if (triggers.none { isFilled(data[it]) }) return
        val spec = entity.field(dependent) ?: return
        val labels = triggers.mapNotNull { entity.field(it)?.label }
        errors[dependent] = "${spec.label} is required once ${labels.joinToString(" or ")} is filled in."
    }
}

object DwDerived {

    fun isDerived(field: FieldDto): Boolean =
        field.derivedKind.isNotBlank() && field.derivedFrom.isNotEmpty()

    fun value(field: FieldDto, row: Map<String, JsonElement>): JsonElement? {
        if (!isDerived(field)) return null
        return when (field.derivedKind) {
            "DAYS_BETWEEN" -> daysBetween(field, row)
            "PRODUCT" -> product(field, row)
            "SUM" -> sum(field, row)
            else -> null
        }
    }

    private fun daysBetween(field: FieldDto, row: Map<String, JsonElement>): JsonElement? {
        val start = isoDate(row[field.derivedFrom.getOrNull(0)]) ?: return null
        val end = isoDate(row[field.derivedFrom.getOrNull(1)]) ?: return null
        val days = ChronoUnit.DAYS.between(start, end) + 1
        return if (days > 0) JsonPrimitive(days) else null
    }

    private fun product(field: FieldDto, row: Map<String, JsonElement>): JsonElement? {
        var total = 1.0
        for (key in field.derivedFrom) {
            total *= numeric(row[key]) ?: return null
        }
        return formatted(field, total)
    }

    private fun sum(field: FieldDto, row: Map<String, JsonElement>): JsonElement? {
        var total = 0.0
        var seen = false
        for (key in field.derivedFrom) {
            val raw = row[key]
            if (isAbsent(raw)) continue
            total += numeric(raw) ?: return null
            seen = true
        }
        return if (seen) formatted(field, total) else null
    }

    private fun isAbsent(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> true
        is JsonPrimitive -> value.content.isEmpty()
        else -> false
    }

    private fun numeric(value: JsonElement?): Double? {
        if (isAbsent(value)) return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.content.replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun isoDate(value: JsonElement?): LocalDate? {
        val text = (value as? JsonPrimitive)?.content?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text.take(10))
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun formatted(field: FieldDto, total: Double): JsonElement? {
        if (!total.isFinite()) return null
        val exact = BigDecimal(total)
        return if (DwFieldType.of(field.type) == DwFieldType.MONEY) {
            JsonPrimitive(exact.setScale(2, RoundingMode.HALF_EVEN).toPlainString())
        } else {
            JsonPrimitive(exact.setScale(4, RoundingMode.HALF_EVEN).toDouble())
        }
    }
}

data class DwStageCompleteness(
    val stageKey: String,
    val number: Int,
    val title: String,
    val requiredTotal: Int,
    val requiredFilled: Int,
    val optionalTotal: Int,
    val optionalFilled: Int,
    val collectionCounts: Map<String, Int>,
    val missing: List<String>,
) {
    val percent: Int get() = if (requiredTotal == 0) 100 else Math.round(100f * requiredFilled / requiredTotal)

    val isComplete: Boolean get() = requiredFilled >= requiredTotal
}

fun dwMeetsMinimum(field: FieldDto, value: JsonElement?): Boolean {
    if (field.minItems <= 0) return true
    val array = value as? JsonArray ?: return false
    return array.size >= field.minItems
}

fun dwShortfallLabel(field: FieldDto, value: JsonElement?): String {
    if (field.minItems <= 0) return field.label
    val held = (value as? JsonArray)?.size ?: 0
    return "${field.label} ($held of ${field.minItems})"
}

fun dwCountsAsRequired(field: FieldDto): Boolean = field.required || field.minItems > 0

fun computeStageCompleteness(
    stage: StageDto,
    singleton: Map<String, JsonElement>,
    collections: Map<String, List<Map<String, JsonElement>>>,
    customFields: List<DwCustomFieldDto> = emptyList(),
    customValues: Map<String, JsonElement> = emptyMap(),
): DwStageCompleteness {
    var requiredTotal = 0
    var requiredFilled = 0
    var optionalTotal = 0
    var optionalFilled = 0
    val missing = ArrayList<String>()

    stage.singleton?.liveFields?.forEach { field ->
        val value = singleton[field.key]
        val filled = DwValues.isFilled(value) && dwMeetsMinimum(field, value)
        if (dwCountsAsRequired(field)) {
            requiredTotal++
            if (filled) requiredFilled++ else missing.add(dwShortfallLabel(field, value))
        } else {
            optionalTotal++
            if (filled) optionalFilled++
        }
    }

    customFields.forEach { field ->
        if (field.retired) return@forEach
        val filled = DwValues.isFilled(customValues[field.key])
        if (field.required) {
            requiredTotal++
            if (filled) requiredFilled++ else missing.add(field.label.ifBlank { field.key })
        } else {
            optionalTotal++
            if (filled) optionalFilled++
        }
    }

    val counts = LinkedHashMap<String, Int>()
    stage.collections.forEach { entity ->
        val rows = collections[entity.key].orEmpty()
        counts[entity.key] = rows.size
        rows.forEach { row ->
            entity.liveFields.forEach { field ->
                val value = row[field.key]
                val filled = DwValues.isFilled(value) && dwMeetsMinimum(field, value)
                if (dwCountsAsRequired(field)) {
                    requiredTotal++
                    if (filled) requiredFilled++
                    else missing.add("${entity.title}: ${dwShortfallLabel(field, value)}")
                } else {
                    optionalTotal++
                    if (filled) optionalFilled++
                }
            }
        }
    }

    return DwStageCompleteness(
        stageKey = stage.key,
        number = stage.number,
        title = stage.title,
        requiredTotal = requiredTotal,
        requiredFilled = requiredFilled,
        optionalTotal = optionalTotal,
        optionalFilled = optionalFilled,
        collectionCounts = counts,
        missing = missing.distinct(),
    )
}

fun computeWorkshopCompleteness(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    definition: DwCustomCache? = null,
): List<DwStageCompleteness> = schema.stages.sortedBy { it.number }.map { stage ->
    val stored = draft?.stages?.get(stage.key)
    computeStageCompleteness(
        stage = stage,
        singleton = stored?.values.orEmpty(),
        collections = stage.collections.associate { entity ->
            entity.key to stored?.rowsFor(entity.key).orEmpty().map { it.values }
        },
        customFields = customFieldsForStage(definition, stage.key),
        customValues = stored?.custom.orEmpty(),
    )
}

fun overallPercent(stages: List<DwStageCompleteness>): Int {
    val total = stages.sumOf { it.requiredTotal }
    if (total == 0) return 100
    return Math.round(100f * stages.sumOf { it.requiredFilled } / total)
}

data class DwStageProgress(
    val stagesComplete: Int,
    val stagesTotal: Int,
    val percent: Int,
    val requiredLeft: Int,
)

fun dwStageProgressPercent(stagesComplete: Int, stagesTotal: Int): Int {
    if (stagesTotal <= 0) return 0
    return Math.round(100f * stagesComplete.coerceAtMost(stagesTotal) / stagesTotal)
}

fun dwStageProgress(stages: List<DwStageCompleteness>, stagesTotal: Int = stages.size): DwStageProgress {
    val complete = stages.count { it.isComplete }
    return DwStageProgress(
        stagesComplete = complete,
        stagesTotal = stagesTotal,
        percent = dwStageProgressPercent(complete, stagesTotal),
        requiredLeft = stages.sumOf { it.missing.size },
    )
}

fun dwStageProgressLine(progress: DwStageProgress): String {
    val stages = "${progress.stagesComplete} of ${progress.stagesTotal} stages complete"
    val left = progress.requiredLeft
    return if (left == 0) "$stages · no required fields left"
    else "$stages · $left required field${if (left == 1) "" else "s"} left"
}

@Serializable
data class ReportTemplateDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
)

@Serializable
data class DwInspectionFeedbackDto(
    val id: String = "",
    val designWorkshopId: String = "",
    val round: Int = 0,
    val stageKey: String? = null,
    val fieldKey: String? = null,
    val note: String = "",
    val sentBack: Boolean = false,
    val actorId: String = "",
    val actorName: String? = null,
    val recordedAt: String? = null,
    val createdAt: String? = null,
    val byApprovingAuthority: Boolean = false,
)

@Serializable
data class DesignWorkshopDto(
    val id: String = "",
    val title: String = "",
    val templateId: String = "DCH_STANDARD",
    val status: String = "DRAFT",
    val workshopCode: String? = null,
    val scheme: String? = null,
    val craftName: String? = null,
    val clusterName: String? = null,
    val state: String? = null,
    val district: String? = null,
    val venue: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val designerName: String? = null,
    val implementingAgency: String? = null,
    val sponsor: String? = null,
    val notes: String? = null,
    val workshopId: String? = null,
    val createdById: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val dictationConsent: String = DW_CONSENT_NOT_RECORDED,
    val dictationConsentAt: String? = null,
    val dictationConsentById: String? = null,
    val reviewNotes: String? = null,
    val reviewedById: String? = null,
    val reviewedAt: String? = null,
    val submissionRound: Int = 0,
    val approvedAt: String? = null,
    val approvedById: String? = null,
    val approvedRound: Int? = null,
    val handedOnAt: String? = null,
    val handedOnTo: String? = null,
)

@Serializable
data class DesignWorkshopPageDto(
    val items: List<DesignWorkshopDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val pages: Int = 0,
)

@Serializable
data class DesignWorkshopDefaultDto(
    val workshopId: String? = null,
    val title: String? = null,
    val accessAt: String? = null,
    val reason: String? = null,
)

@Serializable
data class StageCompletenessDto(
    val stageKey: String = "",
    val number: Int = 0,
    val title: String = "",
    val requiredTotal: Int = 0,
    val requiredFilled: Int = 0,
    val optionalTotal: Int = 0,
    val optionalFilled: Int = 0,
    val percent: Int = 0,
    val isComplete: Boolean = false,
    val collectionCounts: Map<String, Int> = emptyMap(),
    val missing: List<String> = emptyList(),
)

@Serializable
data class DwFieldStampDto(
    val by: String? = null,
    val byName: String? = null,
    val at: String = "",
    val source: String = "",
    val refModel: String? = null,
    val refId: String? = null,
    val refKey: String? = null,
) {
    val fromSharedRecord: Boolean get() = source == "reference"

    fun attribution(): String? {
        val byName = this.byName?.trim()
        if (fromSharedRecord) {
            if (refModel.isNullOrBlank() && byName.isNullOrBlank()) return null
            val record = "From the ${recordLabel(refModel)} record"
            return if (!byName.isNullOrBlank()) "$record, by $byName" else record
        }
        if (by.isNullOrBlank()) return null
        val person = if (!byName.isNullOrBlank()) byName else "A former colleague"
        val day = shortDay(at)
        return if (day.isNotEmpty()) "$person, $day" else person
    }
}


private fun recordLabel(refModel: String?): String = when (refModel) {
    "Artisan" -> "artisan"
    "Craft" -> "craft"
    "Process" -> "process"
    "ProductDocumentation" -> "product"
    "ToolDocumentation" -> "tool"
    "QuestionnaireInterview" -> "interview"
    else -> "linked"
}

private fun shortDay(iso: String): String {
    if (iso.isBlank()) return ""
    fun render(date: java.time.LocalDate): String =
        "${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())}"
    return runCatching {
        render(
            java.time.OffsetDateTime.parse(iso)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .toLocalDate()
        )
    }.getOrElse {
        runCatching { render(java.time.LocalDate.parse(iso.take(10))) }.getOrDefault("")
    }
}

@Serializable
data class DwStageProvenanceDto(
    val singleton: Map<String, DwFieldStampDto> = emptyMap(),
    val collections: Map<String, Map<String, Map<String, DwFieldStampDto>>> = emptyMap(),
    val custom: Map<String, DwFieldStampDto> = emptyMap(),
) {
    fun forRow(entityKey: String, entryId: String?): Map<String, DwFieldStampDto> =
        if (entryId.isNullOrBlank()) emptyMap()
        else collections[entityKey].orEmpty()[entryId].orEmpty()
}

@Serializable
data class DwCanonicalComparisonDto(
    val stored: JsonElement? = null,
    val canonical: JsonElement? = null,
    val diverged: Boolean = false,
    val recordDeleted: Boolean = false,
)

@Serializable
data class DwProvenanceEntryDto(
    val entryId: String = "",
    val stageKey: String = "",
    val entityKey: String = "",
    val ordinal: Int = 0,
    val createdById: String? = null,
    val fields: Map<String, DwFieldStampDto> = emptyMap(),
    val canonical: Map<String, DwCanonicalComparisonDto> = emptyMap(),
)

@Serializable
data class DwProvenanceReportDto(
    val entries: List<DwProvenanceEntryDto> = emptyList(),
)

@Serializable
data class StageBucketDto(
    val singleton: JsonObject = JsonObject(emptyMap()),
    val collections: Map<String, List<JsonObject>> = emptyMap(),
    val custom: JsonObject = JsonObject(emptyMap()),
    val provenance: DwStageProvenanceDto = DwStageProvenanceDto(),
    val completeness: StageCompletenessDto? = null,
    val customSchemaVersion: String = "",
)

@Serializable
data class DesignWorkshopDetailDto(
    val id: String = "",
    val title: String = "",
    val templateId: String = "DCH_STANDARD",
    val status: String = "DRAFT",
    val workshopCode: String? = null,
    val craftName: String? = null,
    val clusterName: String? = null,
    val state: String? = null,
    val district: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val updatedAt: String? = null,
    val createdById: String? = null,
    val schemaVersion: String = "",
    val customSchemaVersion: String = "",
    val stages: Map<String, StageBucketDto> = emptyMap(),
    val completeness: Map<String, StageCompletenessDto> = emptyMap(),
    val transcripts: Map<String, DwTranscriptItem>? = null,
    val dictationConsent: String = DW_CONSENT_NOT_RECORDED,
    val dictationConsentAt: String? = null,
    val dictationConsentById: String? = null,
    val dictationConsentByName: String? = null,
    val reviewNotes: String? = null,
    val reviewedById: String? = null,
    val reviewedAt: String? = null,
    val submissionRound: Int = 0,
    val approvedAt: String? = null,
    val approvedById: String? = null,
    val approvedRound: Int? = null,
    val handedOnAt: String? = null,
    val handedOnTo: String? = null,
    val approvedByName: String? = null,
    val approvedByRole: String? = null,
    val handedOnByName: String? = null,
    val inspectionFeedback: List<DwInspectionFeedbackDto> = emptyList(),
    val inspectionFeedbackTruncated: Boolean = false,
    val mayRecordFeedback: Boolean = false,
)

@Serializable
data class StageListDto(
    val stages: Map<String, StageBucketDto> = emptyMap(),
    val completeness: Map<String, StageCompletenessDto> = emptyMap(),
    val schemaVersion: String = "",
    val customSchemaVersion: String = "",
    val transcripts: Map<String, DwTranscriptItem>? = null,
)

@Serializable
data class DesignWorkshopCreateBody(
    val title: String,
    val templateId: String = "DCH_STANDARD",
    val workshopKind: String? = null,
    val designerUserId: String? = null,
    val designerUserIds: List<String>? = null,
    val craftName: String? = null,
    val clusterName: String? = null,
    val state: String? = null,
    val district: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val notes: String? = null,
)

@Serializable
data class StageEntryBody(
    val entityKey: String,
    val entryId: String? = null,
    val ordinal: Int? = null,
    val data: JsonObject = JsonObject(emptyMap()),
    val merge: Boolean = false,
)

@Serializable
data class StageSaveBody(
    val entries: List<StageEntryBody> = emptyList(),
    val replaceCollections: Boolean,
    val emptiedEntities: List<String> = emptyList(),
    val submit: Boolean = false,
    val copiedFrom: Map<String, String> = emptyMap(),
)

@Serializable
data class StageSaveResultDto(
    val stageKey: String = "",
    val saved: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val errors: Map<String, JsonElement> = emptyMap(),
    val droppedKeys: List<String> = emptyList(),
    val droppedCustomKeys: List<String> = emptyList(),
    val completeness: StageCompletenessDto? = null,
    val schemaVersion: String = "",
    val customSchemaVersion: String = "",
)

@Serializable
data class ExportRecordBody(
    val format: String,
    val templateId: String,
    val fileName: String,
    val generatedAt: String,
    val fileSizeBytes: Long? = null,
    val pageCount: Int? = null,
    val checksumSha256: String? = null,
    val warnings: String? = null,
)

@Serializable
data class PendingExportRecord(
    val workshopId: String,
    val body: ExportRecordBody
)

object StageSchemaStore {

    private const val CACHE_FILE = "design-workshop-schema.json"
    private const val ASSET_FILE = "design-workshop-schema.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val mutex = Mutex()

    private val memory = AtomicReference<SchemaResponse?>(null)

    fun cachedVersion(): String? = memory.get()?.version

    fun peek(): SchemaResponse? = memory.get()

    suspend fun load(context: Context): SchemaResponse = withContext(Dispatchers.IO) {
        memory.get()?.let { return@withContext it }
        mutex.withLock {
            memory.get()?.let { return@withLock it }
            val fromDisk = readCacheFile(context)
            val resolved = fromDisk ?: readAsset(context)
            memory.set(resolved)
            resolved
        }
    }

    suspend fun store(context: Context, fetched: SchemaResponse): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = memory.get()?.version ?: readCacheFile(context)?.version
            writeCacheFile(context, fetched)
            memory.set(fetched)
            previous != null && previous != fetched.version
        }
    }

    private fun readCacheFile(context: Context): SchemaResponse? {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(SchemaResponse.serializer(), file.readText())
        }.getOrNull()?.takeIf { it.stages.isNotEmpty() }
            ?: run {
                runCatching { file.delete() }
                null
            }
    }

    private fun readAsset(context: Context): SchemaResponse {
        val text = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrElse {
            throw IOException(
                "Couldn't load the form layout. Update or reinstall the app.",
                it
            )
        }
        return json.decodeFromString(SchemaResponse.serializer(), text)
    }

    private fun writeCacheFile(context: Context, schema: SchemaResponse) {
        val target = File(context.filesDir, CACHE_FILE)
        val temp = File(context.filesDir, "$CACHE_FILE.writing")
        val bytes = json.encodeToString(SchemaResponse.serializer(), schema).toByteArray()
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("Unable to replace ${target.name}")
        } catch (e: Throwable) {
            runCatching { temp.delete() }
            throw e
        }
    }
}

const val DW_ROW_KEY_SEPARATOR: Char = '#'

const val DW_LOCAL_ID_PREFIX: String = "local-"

fun isLocalOnlyWorkshop(workshopId: String): Boolean = workshopId.startsWith(DW_LOCAL_ID_PREFIX)

fun dwRowId(entityKey: String, rowId: String): String = "$entityKey$DW_ROW_KEY_SEPARATOR$rowId"

fun DraftRow.entityKey(): String = id.substringBefore(DW_ROW_KEY_SEPARATOR, "")

fun StageDraft.rowsFor(entityKey: String): List<DraftRow> =
    rows.filter { it.entityKey() == entityKey }

fun StageDraft.holdsAnswers(): Boolean =
    values.keys.any { !it.startsWith("_") } || rows.isNotEmpty() || custom.isNotEmpty()
