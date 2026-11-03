@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

const val QUESTIONNAIRE_BUNDLE_FORMAT = "designprototype.questionnaire"

const val QUESTIONNAIRE_BUNDLE_SCHEMA_VERSION = 1

const val QUESTIONNAIRE_BUNDLE_EXTENSION = "dpwq"

const val QUESTIONNAIRE_BUNDLE_MIME = "application/vnd.designprototype.questionnaire+gzip"

@Serializable
data class QuestionnaireBundleQuestion(
    val prompt: String = "",
    val helpText: String? = null,
    val isRequired: Boolean = false,
    val sortOrder: Int = 0,
)

@Serializable
data class QuestionnaireBundleSection(
    val code: String = "",
    val title: String = "",
    val sortOrder: Int = 0,
    val questions: List<QuestionnaireBundleQuestion> = emptyList(),
)

@Serializable
data class QuestionnaireBundle(
    val format: String = QUESTIONNAIRE_BUNDLE_FORMAT,
    val schemaVersion: Int = QUESTIONNAIRE_BUNDLE_SCHEMA_VERSION,
    val title: String = "",
    val description: String? = null,
    val sourceVersion: Int = 0,
    val sections: List<QuestionnaireBundleSection> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val kind: String? = null,
) {
    val questionCount: Int get() = sections.sumOf { it.questions.size }
}

fun questionnaireBundleOf(form: CustomQuestionnaireDto): QuestionnaireBundle = QuestionnaireBundle(
    title = form.title.trim(),
    description = form.description?.trim()?.takeIf { it.isNotEmpty() },
    sourceVersion = form.version,
    kind = form.kind?.trim()?.takeIf { it.isNotEmpty() },
    sections = form.sections
        .filter { it.isActive }
        .sortedBy { it.sortOrder }
        .map { section ->
            QuestionnaireBundleSection(
                code = section.code.trim(),
                title = section.title.trim(),
                sortOrder = section.sortOrder,
                questions = section.questions
                    .filter { it.isActive && it.supersededById == null }
                    .filter { it.prompt.isNotBlank() }
                    .sortedBy { it.sortOrder }
                    .map { question ->
                        QuestionnaireBundleQuestion(
                            prompt = question.prompt.trim(),
                            helpText = question.helpText?.trim()?.takeIf { it.isNotEmpty() },
                            isRequired = question.isRequired,
                            sortOrder = question.sortOrder,
                        )
                    },
            )
        },
)

private val bundleJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

fun encodeQuestionnaireBundle(bundle: QuestionnaireBundle): ByteArray {
    val json = canonicalQuestionnaireBundleJson(bundle).toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(json.size / 3)
    GZIPOutputStream(out).use { it.write(json) }
    return out.toByteArray()
}

fun canonicalQuestionnaireBundleJson(bundle: QuestionnaireBundle): String = bundleJson.encodeToString(bundle)

enum class QuestionnaireBundleRefusal {
    EMPTY,

    DAMAGED,

    NOT_A_QUESTIONNAIRE,

    NEWER_VERSION,

    NOTHING_IN_IT,

    TOO_LARGE,
}

const val QUESTIONNAIRE_BUNDLE_MAX_INFLATED = 5 * 1024 * 1024

sealed interface QuestionnaireBundleRead {
    data class Ok(val bundle: QuestionnaireBundle, val bundleId: String? = null) : QuestionnaireBundleRead
    data class Refused(val reason: QuestionnaireBundleRefusal, val message: String) : QuestionnaireBundleRead
}

fun readQuestionnaireBundle(bytes: ByteArray?): QuestionnaireBundleRead {
    if (bytes == null || bytes.isEmpty()) {
        return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.EMPTY,
            "That file is empty. Ask for it again."
        )
    }
    val text = runCatching {
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buffer = ByteArray(64 * 1024)
            val out = ByteArrayOutputStream(bytes.size * 3)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (out.size() + read > QUESTIONNAIRE_BUNDLE_MAX_INFLATED) {
                    throw IllegalStateException("inflated past the ceiling")
                }
                out.write(buffer, 0, read)
            }
            out.toString("UTF-8")
        }
    }.getOrElse { error ->
        return if (error.message == "inflated past the ceiling") {
            QuestionnaireBundleRead.Refused(
                QuestionnaireBundleRefusal.TOO_LARGE,
                "That file is too big to be a questionnaire."
            )
        } else {
            QuestionnaireBundleRead.Refused(
                QuestionnaireBundleRefusal.DAMAGED,
                "That file is damaged or incomplete — Bluetooth and Nearby Share cannot resume a transfer. Ask for it again."
            )
        }
    }
    val element = runCatching { bundleJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
    if (element != null && element.containsKey("v") && !element.containsKey("format")) return readV2(text, element)
    val bundle = runCatching { bundleJson.decodeFromString<QuestionnaireBundle>(text) }.getOrNull()
        ?: return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NOT_A_QUESTIONNAIRE,
            "Not a questionnaire file. Ask for the .$QUESTIONNAIRE_BUNDLE_EXTENSION file."
        )
    if (bundle.format != QUESTIONNAIRE_BUNDLE_FORMAT) {
        return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NOT_A_QUESTIONNAIRE,
            "Not a questionnaire file. Ask for the .$QUESTIONNAIRE_BUNDLE_EXTENSION file."
        )
    }
    if (bundle.schemaVersion > QUESTIONNAIRE_BUNDLE_SCHEMA_VERSION) {
        return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NEWER_VERSION,
            "Update the app to open this, or ask for it as a spreadsheet."
        )
    }
    if (bundle.sections.none { it.questions.isNotEmpty() }) {
        return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NOTHING_IN_IT,
            "This questionnaire has no questions. Check it's the right file."
        )
    }
    return QuestionnaireBundleRead.Ok(bundle)
}

const val QUESTIONNAIRE_HANDOFF_CODE_VERSION = 3

const val QUESTIONNAIRE_HANDOFF_LETTER = "H"

private const val HANDOFF_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

private const val HANDOFF_DIGEST_LENGTH = 11

fun questionnaireHandoffCode(bundle: QuestionnaireBundle): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonicalQuestionnaireBundleJson(bundle).toByteArray(Charsets.UTF_8))
    val body = buildString(HANDOFF_DIGEST_LENGTH) {
        var bitBuffer = 0L
        var bits = 0
        var index = 0
        while (length < HANDOFF_DIGEST_LENGTH) {
            if (bits < 5) {
                bitBuffer = (bitBuffer shl 8) or (digest[index].toLong() and 0xFF)
                bits += 8
                index++
            }
            val shift = bits - 5
            append(HANDOFF_ALPHABET[((bitBuffer shr shift) and 0x1F).toInt()])
            bits = shift
        }
    }
    val prefix = "$WORKSHOP_CODE_NAMESPACE$QUESTIONNAIRE_HANDOFF_CODE_VERSION:" +
        "$QUESTIONNAIRE_HANDOFF_LETTER:$body"
    return "$prefix:${workshopCodeCheck(prefix)}"
}

fun looksLikeQuestionnaireHandoffCode(input: String?): Boolean {
    val raw = (input ?: "").filterNot { it.isWhitespace() || it.code == 0xA0 || it.code == 0xFEFF }.uppercase()
    val parts = raw.split(":")
    return parts.size == 4 &&
        parts[0].startsWith(WORKSHOP_CODE_NAMESPACE) &&
        parts[1] == QUESTIONNAIRE_HANDOFF_LETTER
}

sealed interface QuestionnaireHandoffRead {
    data class Ok(val digest: String, val code: String) : QuestionnaireHandoffRead
    data class Refused(val message: String) : QuestionnaireHandoffRead
}

fun readQuestionnaireHandoffCode(input: String?): QuestionnaireHandoffRead {
    val raw = (input ?: "").filterNot { it.isWhitespace() || it.code == 0xA0 || it.code == 0xFEFF }.uppercase()
    if (raw.isEmpty()) return QuestionnaireHandoffRead.Refused("Nothing was scanned or typed.")
    val parts = raw.split(":")
    if (parts.size != 4 || !parts[0].startsWith(WORKSHOP_CODE_NAMESPACE)) {
        return QuestionnaireHandoffRead.Refused(
            "Not a questionnaire check code. They start with “DPW”."
        )
    }
    val versionText = parts[0].substring(WORKSHOP_CODE_NAMESPACE.length)
    if (versionText.isEmpty() || !versionText.all { it in '0'..'9' }) {
        return QuestionnaireHandoffRead.Refused(
            "Not a questionnaire check code. They start with “DPW” and a version."
        )
    }
    if (parts[1] != QUESTIONNAIRE_HANDOFF_LETTER) {
        return QuestionnaireHandoffRead.Refused(
            "Not a questionnaire check code. Scan the one shown with the questionnaire."
        )
    }
    if (versionText.toDouble() != QUESTIONNAIRE_HANDOFF_CODE_VERSION.toDouble()) {
        return QuestionnaireHandoffRead.Refused(
            "Update the app to read this check code."
        )
    }
    val digest = buildString(parts[2].length) {
        for (c in parts[2]) append(when (c) { 'I', 'L' -> '1'; 'O' -> '0'; else -> c })
    }
    if (digest.length != HANDOFF_DIGEST_LENGTH || digest.any { it !in HANDOFF_ALPHABET }) {
        return QuestionnaireHandoffRead.Refused(
            "Check code incomplete. Re-read it carefully."
        )
    }
    val typedCheck = buildString(parts[3].length) {
        for (c in parts[3]) append(when (c) { 'I', 'L' -> '1'; 'O' -> '0'; else -> c })
    }
    val prefix = "$WORKSHOP_CODE_NAMESPACE$QUESTIONNAIRE_HANDOFF_CODE_VERSION:" +
        "$QUESTIONNAIRE_HANDOFF_LETTER:$digest"
    if (typedCheck.length != 4 || typedCheck != workshopCodeCheck(prefix)) {
        return QuestionnaireHandoffRead.Refused(
            "Check code doesn't match — a character is wrong. Re-read it."
        )
    }
    return QuestionnaireHandoffRead.Ok(digest = digest, code = "$prefix:$typedCheck")
}

fun questionnaireHandoffVerdict(expected: String, fileDigest: String): String =
    if (expected == fileDigest) {
        "This is the same questionnaire, whole."
    } else {
        "This is NOT the file that code was made for — it's incomplete, or was changed after the code was shown. Ask them to resend it with a fresh code."
    }

fun isQuestionnaireBundleDelivery(action: String?, mimeType: String?, filename: String?): Boolean {
    val normalisedType = mimeType?.substringBefore(';')?.trim()?.lowercase()
    val looksNamedRight = hasQuestionnaireBundleExtension(filename)
    val ourType = normalisedType == QUESTIONNAIRE_BUNDLE_MIME || normalisedType == QUESTIONNAIRE_BUNDLE_MIME_FR
    return when (action) {
        "android.intent.action.SEND" -> ourType
        "android.intent.action.VIEW" ->
            ourType || (looksNamedRight && normalisedType in BROAD_BUNDLE_TYPES)
        else -> false
    }
}

private val BROAD_BUNDLE_TYPES: Set<String?> =
    setOf("application/octet-stream", "application/gzip", "application/x-gzip")

fun questionnaireBundleFilename(title: String, version: Int, digest: String = ""): String {
    val stem = title.trim()
        .replace(Regex("[^A-Za-z0-9\\u0900-\\u097F ._-]+"), "")
        .replace(Regex("\\s+"), "-")
        .trim('-', '.', '_')
        .take(60)
        .ifBlank { "questionnaire" }
    val mark = digest.filter { it in HANDOFF_ALPHABET }.take(HANDOFF_DIGEST_LENGTH)
    val marked = if (mark.isEmpty()) "" else "-$mark"
    return "$stem-questions-v$version$marked.$QUESTIONNAIRE_BUNDLE_EXTENSION"
}

fun questionnaireHandoffDigest(bundle: QuestionnaireBundle): String =
    questionnaireHandoffCode(bundle).split(":").getOrNull(2).orEmpty()

fun readableStamp(
    iso: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? {
    val text = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val moment = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(text) }.getOrNull()
        ?: return text
    return runCatching {
        DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", locale).format(moment.atZone(zone))
    }.getOrNull() ?: text
}

const val QUESTIONNAIRE_BUNDLE_CONTENTS_NOTICE: String =
    "Questions only — no answers, no respondents' names, no author. Can be sent offline."

const val QUESTIONNAIRE_BUNDLE_ADOPT_NOTICE: String =
    "Creates a NEW questionnaire on your account with the questions only — no answers. It is recorded as uploaded by you; the sender's copy is unchanged."


const val QUESTIONNAIRE_BUNDLE_EXTENSION_FR = "frwq"

val QUESTIONNAIRE_BUNDLE_EXTENSIONS: Set<String> = setOf(QUESTIONNAIRE_BUNDLE_EXTENSION, QUESTIONNAIRE_BUNDLE_EXTENSION_FR)

const val QUESTIONNAIRE_BUNDLE_MIME_FR = "application/vnd.fieldrepository.questionnaire+gzip"

const val QUESTIONNAIRE_BUNDLE_V2 = 2

fun hasQuestionnaireBundleExtension(filename: String?): Boolean {
    val name = filename?.substringBefore('?')?.trim()?.lowercase() ?: return false
    return QUESTIONNAIRE_BUNDLE_EXTENSIONS.any { name.endsWith(".$it") }
}

@Serializable
data class QuestionnaireBundleV2Form(val title: String = "", val description: String? = null, val kind: String? = null)

@Serializable
data class QuestionnaireBundleV2Section(val code: String = "", val title: String = "", val sortOrder: Int = 0)

@Serializable
data class QuestionnaireBundleV2Question(
    val sectionCode: String = "",
    val prompt: String = "",
    val helpText: String? = null,
    val isRequired: Boolean = false,
    val artisanField: String? = null,
    val questionCode: String? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class QuestionnaireBundleV2(
    val v: Int = QUESTIONNAIRE_BUNDLE_V2,
    val bundleId: String = "",
    val createdAt: String = "",
    val portal: String = "designer-portal",
    val form: QuestionnaireBundleV2Form = QuestionnaireBundleV2Form(),
    val sections: List<QuestionnaireBundleV2Section> = emptyList(),
    val questions: List<QuestionnaireBundleV2Question> = emptyList(),
)

const val QUESTIONNAIRE_BUNDLE_MAX_SECTIONS = 200
const val QUESTIONNAIRE_BUNDLE_MAX_QUESTIONS_PER_SECTION = 500
const val QUESTIONNAIRE_BUNDLE_MAX_TEXT = 4000

fun questionnaireBundleV2Of(bundle: QuestionnaireBundle, bundleId: String, createdAt: String): QuestionnaireBundleV2 =
    QuestionnaireBundleV2(
        bundleId = bundleId,
        createdAt = createdAt,
        form = QuestionnaireBundleV2Form(bundle.title, bundle.description, bundle.kind),
        sections = bundle.sections.map { QuestionnaireBundleV2Section(it.code, it.title, it.sortOrder) },
        questions = bundle.sections.flatMap { s ->
            s.questions.map { q -> QuestionnaireBundleV2Question(s.code, q.prompt, q.helpText, q.isRequired, sortOrder = q.sortOrder) }
        },
    )

fun questionnaireBundleFromV2(envelope: QuestionnaireBundleV2): QuestionnaireBundle = QuestionnaireBundle(
    title = envelope.form.title.trim(),
    description = envelope.form.description?.trim()?.takeIf { it.isNotEmpty() },
    kind = envelope.form.kind?.trim()?.takeIf { it.isNotEmpty() },
    sections = envelope.sections.map { s ->
        QuestionnaireBundleSection(
            code = s.code.trim(),
            title = s.title.trim(),
            sortOrder = s.sortOrder,
            questions = envelope.questions.filter { it.sectionCode.trim() == s.code.trim() }.map { q ->
                QuestionnaireBundleQuestion(
                    prompt = q.prompt.trim(),
                    helpText = q.helpText?.trim()?.takeIf { it.isNotEmpty() },
                    isRequired = q.isRequired,
                    sortOrder = q.sortOrder,
                )
            },
        )
    },
)

fun encodeQuestionnaireBundleV2(bundle: QuestionnaireBundle, bundleId: String, createdAt: String): ByteArray {
    val json = bundleJson.encodeToString(questionnaireBundleV2Of(bundle, bundleId, createdAt)).toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(json.size / 3)
    GZIPOutputStream(out).use { it.write(json) }
    return out.toByteArray()
}

fun newQuestionnaireBundleId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

fun questionnaireHandoffFilename(title: String, day: String): String =
    Naming.exportFilename(entity = "Questionnaire", what = "Handoff", ext = QUESTIONNAIRE_BUNDLE_EXTENSION, subject = title.trim().ifBlank { null }, day = day)

private fun readV2(text: String, element: kotlinx.serialization.json.JsonObject): QuestionnaireBundleRead {
    val major = (element["v"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
    if (major > QUESTIONNAIRE_BUNDLE_V2) {
        return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NEWER_VERSION,
            "Update the app to open this, or ask for it as a spreadsheet."
        )
    }
    val envelope = runCatching { bundleJson.decodeFromString<QuestionnaireBundleV2>(text) }.getOrNull()
        ?: return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NOT_A_QUESTIONNAIRE,
            "Not a questionnaire file. Ask for the .$QUESTIONNAIRE_BUNDLE_EXTENSION file."
        )
    val tooBig = envelope.sections.size > QUESTIONNAIRE_BUNDLE_MAX_SECTIONS ||
        envelope.questions.groupBy { it.sectionCode }.values.any { it.size > QUESTIONNAIRE_BUNDLE_MAX_QUESTIONS_PER_SECTION } ||
        envelope.questions.any { it.prompt.length > QUESTIONNAIRE_BUNDLE_MAX_TEXT || (it.helpText?.length ?: 0) > QUESTIONNAIRE_BUNDLE_MAX_TEXT } ||
        envelope.form.title.length > QUESTIONNAIRE_BUNDLE_MAX_TEXT
    if (tooBig) {
        return QuestionnaireBundleRead.Refused(QuestionnaireBundleRefusal.TOO_LARGE, "That file is too big to be a questionnaire.")
    }
    val bundle = questionnaireBundleFromV2(envelope)
    if (bundle.sections.none { it.questions.isNotEmpty() }) {
        return QuestionnaireBundleRead.Refused(
            QuestionnaireBundleRefusal.NOTHING_IN_IT,
            "This questionnaire has no questions. Check it's the right file."
        )
    }
    return QuestionnaireBundleRead.Ok(bundle, bundleId = envelope.bundleId.ifBlank { null })
}
