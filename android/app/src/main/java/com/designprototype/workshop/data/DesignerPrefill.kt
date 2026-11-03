package com.designprototype.workshop.data

import android.content.Context
import com.designprototype.workshop.report.toPlain
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

val DESIGNER_PREFILL_MAP: List<Pair<String, String>> = listOf(
    "displayName" to "designerName",
    "institution" to "designerInstitution",
    "biography" to "designerProfile",
    "experienceYears" to "designerExperience",
    "localName" to "designerLocalName",
    "designation" to "designerDesignation",
    "department" to "designerDepartment",
    "qualification" to "designerQualification",
    "specialisation" to "designerSpecialisation",
    "phone" to "designerPhone",
    "email" to "designerEmail",
    "website" to "designerWebsite",
    "addressLine" to "designerAddress",
    "city" to "designerCity",
    "state" to "designerState",
    "pincode" to "designerPincode",
    "empanelmentNo" to "designerEmpanelmentNo",
    "empanelmentDate" to "designerEmpanelmentDate",
    "photoMediaId" to "designerPhoto",
    "signatureMediaId" to "designerSignature",
    "cvMediaId" to "designerCv",
)

private val SINGLE_LINE_COLUMNS = setOf("addressLine")

internal fun designerPrefillPlain(stored: String): String {
    val trimmed = stored.trim()
    if (!trimmed.startsWith("{") || !trimmed.contains("\"blocks\"")) return stored
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
    return if (parsed is JsonObject && parsed["blocks"] != null) toPlain(parsed) else stored
}

internal fun designerPrefillOneLine(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n')
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

internal fun designerPrefillPhone(countryCode: String?, number: String?): String? {
    if (number.isNullOrBlank()) return null
    val result = StandardValues.normalizePhone(countryCode, number)
    if (result.error != null || result.code == null || result.digits == null) return number.trim()
    return "${result.code} ${result.digits}"
}

private fun columnValue(profile: DesignerProfileDto, column: String): Any? = when (column) {
    "displayName" -> profile.displayName
    "institution" -> profile.institution
    "biography" -> profile.biography
    "experienceYears" -> profile.experienceYears
    "localName" -> profile.localName
    "designation" -> profile.designation
    "department" -> profile.department
    "qualification" -> profile.qualification
    "specialisation" -> profile.specialisation
    "phone" -> designerPrefillPhone(profile.phoneCountryCode, profile.phone)
    "email" -> profile.email
    "website" -> profile.website
    "addressLine" -> profile.addressLine
    "city" -> profile.city
    "state" -> profile.state
    "pincode" -> profile.pincode
    "empanelmentNo" -> profile.empanelmentNo
    "empanelmentDate" -> profile.empanelmentDate?.trim()?.take(10)
    "photoMediaId" -> profile.photoMediaId
    "signatureMediaId" -> profile.signatureMediaId
    "cvMediaId" -> profile.cvMediaId
    else -> null
}

private fun locationFallback(profile: DesignerProfileDto, column: String): String? {
    val location = profile.location ?: return null
    return when (column) {
        "addressLine" -> location.address
        "city" -> location.district?.takeIf { it.isNotBlank() } ?: location.placeName
        "state" -> location.state
        "pincode" -> location.pincode
        else -> null
    }?.takeIf { it.isNotBlank() }
}

fun designerPrefillFromProfile(profile: DesignerProfileDto, accountName: String?): Map<String, JsonElement> {
    val values = LinkedHashMap<String, JsonElement>()
    for ((column, fieldKey) in DESIGNER_PREFILL_MAP) {
        var raw = columnValue(profile, column)
        if (raw == null || (raw is String && raw.isBlank())) raw = locationFallback(profile, column)
        when (raw) {
            null -> Unit
            is Number -> values[fieldKey] = JsonPrimitive(raw)
            is String -> {
                var text = designerPrefillPlain(raw)
                if (column in SINGLE_LINE_COLUMNS) text = designerPrefillOneLine(text)
                text = text.trim()
                if (text.isNotEmpty()) values[fieldKey] = JsonPrimitive(text)
            }
        }
    }
    if ("designerName" !in values) {
        accountName?.trim()?.takeIf { it.isNotEmpty() }?.let { values["designerName"] = JsonPrimitive(it) }
    }
    return values
}

fun designerPrefillIntoEmpty(
    stage: StageDto,
    current: Map<String, JsonElement>,
    values: Map<String, JsonElement>,
): Map<String, JsonElement> {
    val keys = stage.entities
        .filter { it.cardinality == "SINGLETON" }
        .flatMap { entity -> entity.fields.filter { !it.deprecated }.map { it.key } }
        .toSet()
    return dwCopyIntoEmpty(current, values.filterKeys { it in keys })
}

fun DesignerProfileUpdateBody.asProfile(previous: DesignerProfileDto?): DesignerProfileDto =
    (previous ?: DesignerProfileDto()).copy(
        displayName = displayName,
        localName = localName,
        designation = designation,
        institution = institution,
        department = department,
        qualification = qualification,
        specialisation = specialisation,
        experienceYears = experienceYears,
        experienceMonths = experienceMonths,
        biography = biography,
        phone = phone,
        phoneCountryCode = phoneCountryCode,
        email = email,
        website = website,
        addressLine = addressLine,
        city = city,
        state = state,
        pincode = pincode,
        photoMediaId = photoMediaId,
        signatureMediaId = signatureMediaId,
        cvMediaId = cvMediaId,
        empanelmentNo = empanelmentNo,
        empanelmentDate = empanelmentDate,
    )

@Serializable
private data class StoredOwnProfile(val userId: String = "", val profile: DesignerProfileDto = DesignerProfileDto())

object DesignerPrefillCache {
    private const val OWN_FILE = "designer_profile_me.json"
    private const val WORKSHOP_DIR = "designer_prefill"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private fun writeAtomically(target: File, text: String) {
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    fun saveOwnProfile(context: Context, userId: String, profile: DesignerProfileDto) {
        if (userId.isBlank()) return
        writeAtomically(
            File(context.filesDir, OWN_FILE),
            json.encodeToString(StoredOwnProfile.serializer(), StoredOwnProfile(userId, profile)),
        )
    }

    fun ownProfile(context: Context, userId: String): DesignerProfileDto? {
        if (userId.isBlank()) return null
        val file = File(context.filesDir, OWN_FILE)
        if (!file.isFile) return null
        val stored = runCatching { json.decodeFromString(StoredOwnProfile.serializer(), file.readText()) }.getOrNull()
        return stored?.takeIf { it.userId == userId }?.profile
    }

    private fun workshopFile(context: Context, serverId: String): File =
        File(File(context.filesDir, WORKSHOP_DIR), serverId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun saveWorkshop(context: Context, serverId: String, values: JsonObject) {
        if (serverId.isBlank()) return
        writeAtomically(workshopFile(context, serverId), values.toString())
    }

    fun workshop(context: Context, serverId: String): Map<String, JsonElement>? {
        if (serverId.isBlank()) return null
        val file = workshopFile(context, serverId)
        if (!file.isFile) return null
        return runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
    }
}

@Serializable
data class DesignerPrefillDto(
    val designerId: String? = null,
    val values: JsonObject = JsonObject(emptyMap()),
)
