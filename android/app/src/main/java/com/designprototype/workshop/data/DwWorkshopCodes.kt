package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

const val WORKSHOP_CODE_NAMESPACE = "DPW"

const val WORKSHOP_CODE_VERSION = 1

val SUPPORTED_VERSIONS: Set<Int> = setOf(1)

enum class DwWorkshopRecordType(
    val wire: String,
    val letter: String,
    val label: String,
    val plural: String,
) {
    ARTISAN("artisan", "A", "Artisan", "artisans"),
    CRAFT("craft", "C", "Craft", "crafts"),
    WORKSHOP("workshop", "W", "Workshop", "workshops"),

    PRODUCT("product", "D", "Product", "products"),

    PROCESS("process", "S", "Process", "processes"),
    TOOL("tool", "T", "Tool", "tools"),

    QUESTIONNAIRE("questionnaire", "Q", "Interview", "interviews"),
    MEDIA("media", "M", "Media file", "media files"),

    DESIGN_WORKSHOP("designWorkshop", "G", "Design workshop", "design workshops"),
    PROTOTYPE("prototype", "P", "Prototype", "prototypes");

    companion object {
        fun ofWire(raw: String): DwWorkshopRecordType? = entries.firstOrNull { it.wire == raw }

        fun ofLetter(raw: String): DwWorkshopRecordType? = entries.firstOrNull { it.letter == raw }
    }
}

private fun knownRecordTypesSentence(): String {
    val words = DwWorkshopRecordType.entries.map { it.plural }
    return words.dropLast(1).joinToString(", ") + " and " + words.last()
}

data class DwWorkshopCodeRef(
    val recordType: DwWorkshopRecordType,
    val id: String,
)

private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{7,63}$")

private const val WEB_DEVICE_LOCAL_ID_PREFIX = "dwlocal-"

private fun isDeviceLocalId(recordType: DwWorkshopRecordType, id: String): Boolean {
    if (id.startsWith(WEB_DEVICE_LOCAL_ID_PREFIX)) return true
    return recordType == DwWorkshopRecordType.DESIGN_WORKSHOP && id.startsWith(DW_LOCAL_ID_PREFIX)
}

private const val CHECK_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val CHECK_LENGTH = 4

private val CHECK_CONFUSABLES: Map<Char, Char> = mapOf('I' to '1', 'L' to '1', 'O' to '0')

private fun isJsSpace(c: Char): Boolean = when (c.code) {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20 -> true
    0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    else -> c.code in 0x2000..0x200A
}

private fun jsTrim(text: String): String = text.trim { isJsSpace(it) }

private const val FNV_PRIME = 16777619

fun workshopCodeCheck(prefix: String): String {
    var hash = 0x811c9dc5.toInt()
    for (index in prefix.indices) {
        hash = hash xor prefix[index].code
        hash *= FNV_PRIME
    }
    val value = hash and 0xfffff
    val out = StringBuilder(CHECK_LENGTH)
    for (shift in (CHECK_LENGTH - 1) * 5 downTo 0 step 5) {
        out.append(CHECK_ALPHABET[(value ushr shift) and 31])
    }
    return out.toString()
}

enum class DwEncodeRefusal {
    NO_ID,

    UNKNOWN_RECORD_TYPE,

    ID_NOT_AN_IDENTIFIER,

    ID_LOOKS_SENSITIVE,

    ID_IS_DEVICE_LOCAL,
}

sealed interface DwEncodeResult {
    data class Ok(val code: String, val ref: DwWorkshopCodeRef) : DwEncodeResult

    data class Refused(val reason: DwEncodeRefusal, val message: String) : DwEncodeResult
}

fun encodeWorkshopCode(recordType: String, id: String?): DwEncodeResult {
    val type = DwWorkshopRecordType.ofWire(recordType)
        ?: return DwEncodeResult.Refused(
            DwEncodeRefusal.UNKNOWN_RECORD_TYPE,
            "No code can be printed for this kind of record — codes exist for ${knownRecordTypesSentence()}."
        )

    val trimmed = jsTrim(id ?: "")
    if (trimmed.isEmpty()) {
        return DwEncodeResult.Refused(
            DwEncodeRefusal.NO_ID,
            "This record has no identifier yet, so there is nothing to print on a code. Save it first."
        )
    }

    val stripped = trimmed.filterNot { isJsSpace(it) || it == '-' }
    if (stripped.isNotEmpty() && stripped.all { it in '0'..'9' }) {
        return DwEncodeResult.Refused(
            DwEncodeRefusal.ID_LOOKS_SENSITIVE,
            "That is a number, not a record identifier. Aadhaar and Pehchan card numbers must never " +
                "be printed in a QR code — a card is a public object once it leaves the room."
        )
    }
    if (stripped.isNotEmpty() &&
        stripped.all { it in 'A'..'Z' || it in '0'..'9' } &&
        stripped.length <= 20 &&
        stripped.any { it in 'A'..'Z' }
    ) {
        return DwEncodeResult.Refused(
            DwEncodeRefusal.ID_LOOKS_SENSITIVE,
            "That looks like a card number rather than a record identifier. Aadhaar and Pehchan card " +
                "numbers must never be printed in a QR code."
        )
    }

    if (isDeviceLocalId(type, trimmed)) {
        return DwEncodeResult.Refused(
            DwEncodeRefusal.ID_IS_DEVICE_LOCAL,
            "Not uploaded yet — sync, then print its code."
        )
    }

    if (!ID_PATTERN.matches(trimmed)) {
        return DwEncodeResult.Refused(
            DwEncodeRefusal.ID_NOT_AN_IDENTIFIER,
            "That is not a record identifier, so no code can be printed for it. Save the record " +
                "first, then print its code."
        )
    }

    val prefix = "$WORKSHOP_CODE_NAMESPACE$WORKSHOP_CODE_VERSION:${type.letter}:${trimmed.uppercase()}"
    return DwEncodeResult.Ok(
        code = "$prefix:${workshopCodeCheck(prefix)}",
        ref = DwWorkshopCodeRef(recordType = type, id = trimmed)
    )
}

fun encodeWorkshopCode(recordType: DwWorkshopRecordType, id: String?): DwEncodeResult =
    encodeWorkshopCode(recordType.wire, id)

enum class DwDecodeRefusal {
    EMPTY,

    NOT_A_WORKSHOP_CODE,

    NEWER_VERSION,

    UNKNOWN_RECORD_TYPE,

    MALFORMED,

    DEVICE_LOCAL,

    CHECK_FAILED,

    JOIN_CARD,
}

sealed interface DwDecodeResult {
    data class Ok(val ref: DwWorkshopCodeRef, val code: String) : DwDecodeResult

    data class Refused(val reason: DwDecodeRefusal, val message: String) : DwDecodeResult
}

fun decodeWorkshopCode(input: String?): DwDecodeResult {
    val raw = (input ?: "").filterNot { isJsSpace(it) }.uppercase()
    if (raw.isEmpty()) {
        return DwDecodeResult.Refused(DwDecodeRefusal.EMPTY, "Nothing was scanned or typed.")
    }

    val parts = raw.split(":")
    if (parts.size != 4 || !parts[0].startsWith(WORKSHOP_CODE_NAMESPACE)) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.NOT_A_WORKSHOP_CODE,
            "That is not a workshop card or tag. Workshop codes begin “DPW”; a shop barcode, a " +
                "payment code or a web address will not open a record here."
        )
    }

    val versionText = parts[0].substring(WORKSHOP_CODE_NAMESPACE.length)
    if (versionText.isEmpty() || !versionText.all { it in '0'..'9' }) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.NOT_A_WORKSHOP_CODE,
            "That is not a workshop card or tag. Workshop codes begin “DPW” followed by a version number."
        )
    }

    if (parts[1] == WORKSHOP_JOIN_LETTER) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.JOIN_CARD,
            "That is a join card, not a card that names a record — scanning it puts you on the " +
                "workshop rather than opening anything. Scan it from a design workshop's Cards & " +
                "tags screen, or from the code panel on Search."
        )
    }
    val version = versionText.toDouble()
    val canonicalVersion = jsNumber(version)
    if (SUPPORTED_VERSIONS.none { it.toDouble() == version }) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.NEWER_VERSION,
            "This card was printed by a newer version of the app (code format $canonicalVersion) than " +
                "the one on this device. Update the app to read it, or open the record from the list instead."
        )
    }

    val recordType = DwWorkshopRecordType.ofLetter(parts[1])
        ?: return DwDecodeResult.Refused(
            DwDecodeRefusal.UNKNOWN_RECORD_TYPE,
            "This is a workshop code for a kind of record that needs a newer version of the app. " +
                "Update the app, then scan it again."
        )

    val id = parts[2].lowercase()
    if (isDeviceLocalId(recordType, id)) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.DEVICE_LOCAL,
            "This code was made before the workshop uploaded. Ask its creator to sync and send a new code."
        )
    }
    if (!ID_PATTERN.matches(id)) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.MALFORMED,
            "This code is damaged or was typed incompletely — the identifier in it is not a whole one. " +
                "Check it against the card."
        )
    }

    val typedCheck = buildString(parts[3].length) {
        for (character in parts[3]) append(CHECK_CONFUSABLES[character] ?: character)
    }
    val prefix = "$WORKSHOP_CODE_NAMESPACE$canonicalVersion:${parts[1]}:${parts[2]}"
    if (typedCheck.length != CHECK_LENGTH || typedCheck != workshopCodeCheck(prefix)) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.CHECK_FAILED,
            "This code does not check out, so one of its characters is wrong. Read it off the card " +
                "again, character by character — a single wrong character points at a different record, " +
                "and nothing later would notice."
        )
    }

    return DwDecodeResult.Ok(
        ref = DwWorkshopCodeRef(recordType = recordType, id = id),
        code = "$prefix:$typedCheck"
    )
}

const val WORKSHOP_JOIN_CODE_VERSION = 2

const val WORKSHOP_JOIN_LETTER = "J"

private const val JOIN_SECRET_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

private const val JOIN_SECRET_LENGTH = 22

private val JOIN_SECRET_PATTERN = Regex("^[" + JOIN_SECRET_ALPHABET + "]{" + JOIN_SECRET_LENGTH + "}$")

enum class DwJoinCardRefusal {
    EMPTY,

    NOT_A_JOIN_CARD,

    RECORD_TAG,

    NEWER_VERSION,

    DEVICE_LOCAL,

    MALFORMED,

    CHECK_FAILED,
}

sealed interface DwJoinCardDecode {
    data class Ok(val workshopId: String, val code: String) : DwJoinCardDecode

    data class Refused(val reason: DwJoinCardRefusal, val message: String) : DwJoinCardDecode
}

fun looksLikeJoinCard(input: String?): Boolean {
    val raw = (input ?: "").filterNot { isJsSpace(it) }.uppercase()
    val parts = raw.split(":")
    return parts.size == 4 &&
        parts[0].startsWith(WORKSHOP_CODE_NAMESPACE) &&
        parts[1] == WORKSHOP_JOIN_LETTER
}

fun decodeWorkshopJoinCard(input: String?): DwJoinCardDecode {
    val raw = (input ?: "").filterNot { isJsSpace(it) }.uppercase()
    if (raw.isEmpty()) {
        return DwJoinCardDecode.Refused(DwJoinCardRefusal.EMPTY, "Nothing was scanned or typed.")
    }

    val parts = raw.split(":")
    if (parts.size != 4 || !parts[0].startsWith(WORKSHOP_CODE_NAMESPACE)) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.NOT_A_JOIN_CARD,
            "That is not a workshop join card. Join cards begin “DPW”; a shop barcode, a payment " +
                "code or a web address will not let you into a workshop."
        )
    }

    val versionText = parts[0].substring(WORKSHOP_CODE_NAMESPACE.length)
    if (versionText.isEmpty() || !versionText.all { it in '0'..'9' }) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.NOT_A_JOIN_CARD,
            "That is not a workshop join card. Join cards begin “DPW” followed by a version number."
        )
    }
    val version = versionText.toDouble()

    if (parts[1] != WORKSHOP_JOIN_LETTER) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.RECORD_TAG,
            "That code belongs to this application but is not a join card — it names a record. " +
                "Scan the join card you were handed, the one printed to let somebody in."
        )
    }
    if (version != WORKSHOP_JOIN_CODE_VERSION.toDouble()) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.NEWER_VERSION,
            "That join card needs a newer version of the app. Update the app, or ask an " +
                "administrator to add you from the workshop's viewers screen."
        )
    }

    val body = parts[2]
    if (body.count { it == '.' } != 1) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.MALFORMED,
            "This join card is damaged or was typed incompletely. Check it against the card, " +
                "character by character."
        )
    }
    val idText = body.substringBefore('.')
    val secretText = body.substringAfter('.')

    val workshopId = idText.lowercase()
    if (workshopId.startsWith(WEB_DEVICE_LOCAL_ID_PREFIX) || workshopId.startsWith(DW_LOCAL_ID_PREFIX)) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.DEVICE_LOCAL,
            "That card names a workshop that had not been shared yet when it was printed — it only " +
                "ever meant anything on the device that made it. Ask whoever created the workshop " +
                "to sync their device and print a fresh card."
        )
    }
    if (!ID_PATTERN.matches(workshopId)) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.MALFORMED,
            "This join card is damaged or was typed incompletely — the identifier in it is not a " +
                "whole one. Check it against the card."
        )
    }

    val secret = buildString(secretText.length) {
        for (character in secretText) append(CHECK_CONFUSABLES[character] ?: character)
    }
    if (!JOIN_SECRET_PATTERN.matches(secret)) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.MALFORMED,
            "This join card is damaged or was typed incompletely — the part after the full stop is " +
                "not a whole one. Read it off the card again, character by character."
        )
    }

    val typedCheck = buildString(parts[3].length) {
        for (character in parts[3]) append(CHECK_CONFUSABLES[character] ?: character)
    }
    val prefix = WORKSHOP_CODE_NAMESPACE + WORKSHOP_JOIN_CODE_VERSION + ":" +
        WORKSHOP_JOIN_LETTER + ":" + idText + "." + secret
    if (typedCheck.length != CHECK_LENGTH || typedCheck != workshopCodeCheck(prefix)) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.CHECK_FAILED,
            "This join card does not check out, so one of its characters is wrong. Read it off the " +
                "card again, character by character."
        )
    }

    return DwJoinCardDecode.Ok(workshopId = workshopId, code = prefix + ":" + typedCheck)
}

sealed interface DwWorkshopScan {
    data class RecordCode(val ref: DwWorkshopCodeRef, val code: String) : DwWorkshopScan

    data class JoinCard(val card: DwJoinCardDecode.Ok) : DwWorkshopScan

    data class Refused(val message: String) : DwWorkshopScan
}

fun readWorkshopScan(input: String?): DwWorkshopScan {
    if (looksLikeJoinCard(input)) {
        return when (val decoded = decodeWorkshopJoinCard(input)) {
            is DwJoinCardDecode.Ok -> DwWorkshopScan.JoinCard(decoded)
            is DwJoinCardDecode.Refused -> DwWorkshopScan.Refused(decoded.message)
        }
    }
    return when (val decoded = decodeWorkshopCode(input)) {
        is DwDecodeResult.Ok -> DwWorkshopScan.RecordCode(decoded.ref, decoded.code)
        is DwDecodeResult.Refused -> DwWorkshopScan.Refused(decoded.message)
    }
}

fun formatWorkshopCodeForPrint(code: String): String = code.chunked(4).joinToString(" ")

fun workshopRecordTypeLabel(recordType: DwWorkshopRecordType): String = recordType.label

fun unresolvedWorkshopCodeMessage(recordType: DwWorkshopRecordType): String {
    if (recordType == DwWorkshopRecordType.PROTOTYPE) {
        return "No prototype in this workshop matches that tag. It may belong to another workshop, or " +
            "it may not be on this phone yet — open the workshop that made it, or find the " +
            "prototype in the list."
    }
    if (recordType == DwWorkshopRecordType.DESIGN_WORKSHOP) {
        return "No workshop you can open matches this code. Ask an admin to add you — your work here is kept."
    }
    val noun = recordType.label.lowercase()
    return "No $noun you can open matches that code. It may not exist, or it may " +
        "belong to work you do not have access to — search for the $noun by name instead."
}

fun designWorkshopCodeNotOpenableMessage(): String =
    "This is a design workshop's code. Design workshops are opened from the Design workshops list. " +
        "If a colleague has just shared this one with you and it is not in your list yet, only an " +
        "admin can add you to it, so send them the code and ask to be put on it."

fun designWorkshopJoinAskingMessage(): String =
    "That card is for a design workshop. Asking to be put on it…"

fun designWorkshopCardPurposeMessage(): String =
    "This is the workshop's own tag, not a join card. Scanning it asks an administrator to put " +
        "somebody on the workshop — it does not let them in by itself, and it is not a password: " +
        "anybody who has the code can only ask. To put somebody on straight away, print a join " +
        "card below instead."

fun designWorkshopJoinCardPurposeMessage(): String =
    "This card lets ONE person onto this workshop, straight away and with no administrator " +
        "involved. Treat it like a key: hand it to the person it is for, do not photograph it or " +
        "send it in a message, and do not leave it where it can be copied. Once somebody has used " +
        "it, it will not let anybody else in, and it stops working after a few weeks."

fun designWorkshopJoinCardRedeemingMessage(): String =
    "That is a join card. Putting you on the workshop…"

private fun DwDataRow.bookkeepingText(key: String): String? {
    val value = this[key]
    if (value !is JsonPrimitive || value is JsonNull || !value.isString) return null
    return value.content
}

fun workshopCodeIdForRow(row: DwDataRow): String? =
    row.bookkeepingText("_entryId") ?: row.bookkeepingText("_clientKey")

fun workshopCodeMatchesRow(ref: DwWorkshopCodeRef, row: DwDataRow): Boolean {
    if (ref.recordType != DwWorkshopRecordType.PROTOTYPE) return false
    return ref.id == row.bookkeepingText("_entryId") || ref.id == row.bookkeepingText("_clientKey")
}
