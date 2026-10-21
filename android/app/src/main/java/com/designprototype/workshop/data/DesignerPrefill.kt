package com.designprototype.workshop.data

import android.content.Context
import com.designprototype.workshop.report.toPlain
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The designer's profile, copied into the matching boxes of a design workshop's stages.
 *
 * The server owns the rule (`backend/app/services/designers.py`, `PREFILL_MAP` and
 * `prefill_from_profile`) and answers it per workshop at `GET design-workshops/{id}/designer-prefill`.
 * This file is the handset's copy of the same rule, used for one case only: a workshop this phone
 * started for its own signed-in designer, opened with no connection and no cached server answer.
 * `DesignerPrefillParityTest` pins the map below to the Python one, pair for pair.
 *
 * A stage only ever takes these values into EMPTY boxes — see [designerPrefillIntoEmpty].
 */
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

/** Columns typeset on a report cover as ONE line (the server's `_SINGLE_LINE_PREFILL_COLUMNS`). */
private val SINGLE_LINE_COLUMNS = setOf("addressLine")

/** A stored rich-text document as its words; any other string unchanged (`rich_text.plain_from_stored`). */
internal fun designerPrefillPlain(stored: String): String {
    val trimmed = stored.trim()
    if (!trimmed.startsWith("{") || !trimmed.contains("\"blocks\"")) return stored
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
    return if (parsed is JsonObject && parsed["blocks"] != null) toPlain(parsed) else stored
}

/** Every line break as one space, each line trimmed, blank lines dropped (`_fold_to_one_line`). */
internal fun designerPrefillOneLine(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n')
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/** `"+91 9876543210"`, exactly as `standard_values.compose_phone`; a number that will not normalise stays as typed. */
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

/** The profile's stated `Location`, used only where the flat column is blank — the server's fallback. */
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

/**
 * `{registry field key: value}` for every [DESIGNER_PREFILL_MAP] pair the profile can answer.
 * Blank values are skipped. `designerName` falls back to [accountName].
 */
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

/**
 * The values that may go into this stage: keys that are live fields of its SINGLETON entities and
 * whose box is empty in [current]. A box holding anything is never overwritten.
 */
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

/** The update body as the profile it will become once sent — for the cache while it is queued. */
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

/**
 * Two small files: the signed-in designer's own profile (for offline use) and the server's last
 * answer per workshop. Written atomically, read leniently; a failure is never surfaced.
 */
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

    /** The cached own profile, only when it belongs to [userId] (a shared handset never mixes designers). */
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

/** `GET design-workshops/{id}/designer-prefill`. */
@Serializable
data class DesignerPrefillDto(
    val designerId: String? = null,
    val values: JsonObject = JsonObject(emptyMap()),
)
