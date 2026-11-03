package com.designprototype.workshop.data

import android.content.Context
import com.designprototype.workshop.report.groupIndian
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class DwAiVerb(val path: String, val human: String) {
    PROOFREAD("proofread", "proofreading"),
    EXPAND("expand", "expanding a note"),
    TRANSLATE("translate", "translation"),
    CAPTION("caption", "describing a photograph"),
    SUBTITLES("subtitles", "subtitling"),
}

const val DW_VERB_MAX_TEXT_CHARS: Int = 20_000

const val DW_VERB_MAX_LANGUAGE_CHARS: Int = 40

const val DW_LAYER_NOTE_MAX_CHARS: Int = 500

private val DW_VERB_LANGUAGE_TOKEN = Regex("""^[A-Za-z][A-Za-z0-9 \-_()']{0,39}$""")

fun dwAiIsUnrecorded(raw: String?): Boolean =
    raw?.trim().orEmpty().equals(DW_TIER2_UNRECORDED, ignoreCase = true)

fun dwVerbWorkshopId(workshopId: String): String {
    require(workshopId.isNotBlank() && !isLocalOnlyWorkshop(workshopId)) {
        "A verb runs on the server's copy of a workshop, and this id names one the server has never " +
            "seen ($workshopId). Gate the control with dwVerbGate before offering it; a local id " +
            "would answer 404 after the press."
    }
    return workshopId
}

sealed interface DwVerbSource {
    data class Passage(val text: String) : DwVerbSource

    data class StoredLayer(val layerId: String) : DwVerbSource
}

@Serializable
data class DwProofreadBody(
    val text: String? = null,
    val language: String? = null,
    val sourceLayerId: String? = null,
)

@Serializable
data class DwExpandBody(
    val text: String,
    val language: String? = null,
)

@Serializable
data class DwTranslateBody(
    val text: String? = null,
    val sourceLayerId: String? = null,
    val targetLanguage: String,
    val sourceLanguage: String? = null,
)

@Serializable
data class DwMediaVerbBody(
    val sourceMediaId: String,
    val language: String? = null,
)

fun dwProofreadBody(source: DwVerbSource, language: String? = null): DwProofreadBody = when (source) {
    is DwVerbSource.Passage -> DwProofreadBody(text = source.text, language = dwVerbLanguage(language))
    is DwVerbSource.StoredLayer ->
        DwProofreadBody(sourceLayerId = source.layerId, language = dwVerbLanguage(language))
}

fun dwExpandBody(note: String, language: String? = null): DwExpandBody =
    DwExpandBody(text = note, language = dwVerbLanguage(language))

fun dwTranslateBody(
    source: DwVerbSource,
    targetLanguage: String,
    sourceLanguage: String? = null,
): DwTranslateBody {
    val target = targetLanguage.trim()
    val from = dwVerbLanguage(sourceLanguage)
    return when (source) {
        is DwVerbSource.Passage ->
            DwTranslateBody(text = source.text, targetLanguage = target, sourceLanguage = from)
        is DwVerbSource.StoredLayer ->
            DwTranslateBody(sourceLayerId = source.layerId, targetLanguage = target, sourceLanguage = from)
    }
}

private fun dwVerbLanguage(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

@Serializable
data class DwAiLayerSourceDto(
    val kind: String = "",
    val id: String? = null,
    val text: String? = null,
)

@Serializable
data class DwAiLayerDto(
    val id: String = "",
    val designWorkshopId: String = "",
    val kind: String = "",
    val tier: String = "",
    val source: DwAiLayerSourceDto? = null,
    val provider: String = "",
    val modelId: String = "",
    val modelVersion: String? = null,
    val language: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val producedAt: String? = null,
    val createdAt: String? = null,
    val createdById: String? = null,
    val accepted: Boolean = false,
    val acceptedAt: String? = null,
    val acceptedById: String? = null,
    val textChars: Int? = null,
    val preview: String? = null,
    val payload: JsonElement? = null,
    val textWithheld: Boolean = false,
    val deletedAt: String? = null,
    val text: String? = null,
)

@Serializable
data class DwAiVerbResultDto(
    val layer: DwAiLayerDto = DwAiLayerDto(),
    val accepted: Boolean = false,
    val acceptanceRequired: Boolean = true,
    val aiVerbsLimit: Int? = null,
    val aiVerbsUsed: Int = 0,
    val aiVerbsRemaining: Int? = null,
    val aiVerbDay: String? = null,
    val aiVerbsByVerb: Map<String, Int> = emptyMap(),
)

@Serializable
data class DwAiLayerListDto(
    val items: List<DwAiLayerDto> = emptyList(),
    val total: Int = 0,
    val accepted: Int = 0,
)

@Serializable
data class DwAiLayerDecisionDto(
    val id: String = "",
    val layerId: String = "",
    val decision: String = "",
    val note: String? = null,
    val actorId: String = "",
    val createdAt: String? = null,
)

@Serializable
data class DwAiLayerDecisionResultDto(
    val layer: DwAiLayerDto = DwAiLayerDto(),
    val decisions: List<DwAiLayerDecisionDto> = emptyList(),
)

@Serializable
data class DwAiLayerDecisionBody(
    val note: String? = null,
)

@Serializable
data class DwAiVerbAllowance(
    val userId: String = "",
    val day: String = "",
    val limit: Int? = null,
    val remaining: Int? = null,
    val byVerb: Map<String, Int> = emptyMap(),
)

data class DwAiVerbCapView(
    val told: Boolean,
    val spent: Boolean,
    val limit: Int?,
    val remaining: Int?,
    val byVerb: Map<String, Int> = emptyMap(),
)

private val DW_AI_VERB_CAP_UNKNOWN =
    DwAiVerbCapView(told = false, spent = false, limit = null, remaining = null)

fun dwAiVerbCapView(
    stored: DwAiVerbAllowance?,
    userId: String?,
    today: String,
): DwAiVerbCapView {
    if (stored == null || userId.isNullOrBlank()) return DW_AI_VERB_CAP_UNKNOWN
    if (stored.userId != userId || stored.day.isBlank() || stored.day != today) {
        return DW_AI_VERB_CAP_UNKNOWN
    }
    val remaining = stored.remaining
        ?: return DwAiVerbCapView(
            told = true,
            spent = false,
            limit = stored.limit,
            remaining = null,
            byVerb = stored.byVerb,
        )
    return DwAiVerbCapView(
        told = true,
        spent = remaining <= 0,
        limit = stored.limit,
        remaining = remaining,
        byVerb = stored.byVerb,
    )
}

fun dwAiVerbAllowanceOf(dto: DwAiVerbResultDto, userId: String?): DwAiVerbAllowance? {
    val day = dto.aiVerbDay?.trim().orEmpty()
    if (day.isEmpty() || userId.isNullOrBlank()) return null
    return DwAiVerbAllowance(
        userId = userId,
        day = day,
        limit = dto.aiVerbsLimit,
        remaining = dto.aiVerbsRemaining,
        byVerb = dto.aiVerbsByVerb,
    )
}

fun dwAiVerbCapSpentRecord(
    previous: DwAiVerbAllowance?,
    userId: String,
    today: String,
): DwAiVerbAllowance {
    val fresh = previous?.takeIf { it.userId == userId && it.day == today }
    return DwAiVerbAllowance(
        userId = userId,
        day = today,
        limit = fresh?.limit,
        remaining = 0,
        byVerb = fresh?.byVerb.orEmpty(),
    )
}

const val DW_AI_VERB_COUNTDOWN_FROM: Int = 3

object DwAiVerbAllowanceStore {

    private const val PREFS = "dw_ai_verb_allowance"
    private const val KEY_PREFIX = "allowance:"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context, userId: String?): DwAiVerbAllowance? {
        if (userId.isNullOrBlank()) return null
        val raw = preferences(context).getString(key(userId), null) ?: return null
        val row = runCatching {
            json.decodeFromString(DwAiVerbAllowance.serializer(), raw)
        }.getOrNull()
        if (row == null || row.userId != userId) {
            clear(context, userId)
            return null
        }
        return row
    }

    fun write(context: Context, allowance: DwAiVerbAllowance) {
        if (allowance.userId.isBlank()) return
        preferences(context).edit()
            .putString(
                key(allowance.userId),
                json.encodeToString(DwAiVerbAllowance.serializer(), allowance)
            )
            .apply()
    }

    fun clear(context: Context, userId: String?) {
        if (userId.isNullOrBlank()) return
        preferences(context).edit().remove(key(userId)).apply()
    }

    private fun key(userId: String) = "$KEY_PREFIX$userId"
}

class DwAiVerbNotConfigured(val verb: DwAiVerb, val detail: String) : Exception(detail)

class DwAiVerbCapRefused(
    val detail: String?,
    val retryAfterSeconds: Int?,
) : Exception(detail) {
    val transientThrottle: Boolean get() = retryAfterSeconds != null
}

class DwAiVerbRefused(
    val status: Int,
    val detail: String?,
) : Exception(detail ?: "The server refused this run (HTTP $status).")

data class DwVerbConditions(
    val draftRead: Boolean,
    val workshopOnServer: Boolean,
    val consent: DwTier3Consent,
    val online: Boolean,
    val capSpent: Boolean,
    val capRefusal: String? = null,
)

sealed interface DwVerbGate {
    object Ready : DwVerbGate

    object StillReading : DwVerbGate

    data class Refused(val sentence: String) : DwVerbGate
}

fun dwVerbGate(conditions: DwVerbConditions): DwVerbGate {
    if (!conditions.draftRead) return DwVerbGate.StillReading
    if (conditions.consent == DwTier3Consent.REFUSED) {
        return DwVerbGate.Refused(dwVerbConsentRefusal(conditions.consent))
    }
    if (!conditions.workshopOnServer) return DwVerbGate.Refused(DW_VERBS_WORKSHOP_NOT_ON_SERVER)
    if (!conditions.online) return DwVerbGate.Refused(DW_VERBS_NEED_A_CONNECTION)
    if (conditions.capSpent) return DwVerbGate.Refused(conditions.capRefusal ?: DW_AI_VERBS_SPENT)
    return DwVerbGate.Ready
}

fun dwVerbPassageRefusal(passage: String): String? {
    if (passage.trim().isEmpty()) return DW_VERBS_NOTHING_SELECTED
    if (passage.length > DW_VERB_MAX_TEXT_CHARS) return dwVerbPassageTooLong(passage.length)
    return null
}

fun dwVerbMediaRefusal(remoteMediaId: String?): String? =
    if (remoteMediaId.isNullOrBlank()) DW_VERBS_MEDIA_NOT_UPLOADED else null

fun dwTranslationTargetRefusal(value: String): String? {
    val token = value.trim()
    if (token.isEmpty()) {
        return "Name the language, e.g. “Odia” or “or”."
    }
    if (token.equals("multi", ignoreCase = true)) {
        return "A recording can BE multi-language, but a translation needs one target. Pick one."
    }
    return dwVerbLanguageRefusal(token, what = "the target language")
}

fun dwVerbLanguageRefusal(raw: String?, what: String): String? {
    val token = raw?.trim().orEmpty()
    if (token.isEmpty()) return null
    if (DW_VERB_LANGUAGE_TOKEN.matches(token)) return null
    return "“${token.take(DW_VERB_MAX_LANGUAGE_CHARS)}” isn't a language. Use a name or code like “Odia” or “or”."
}

const val DW_VERBS_NOTHING_SELECTED: String =
    "Select some text first."

fun dwVerbPassageTooLong(chars: Int): String =
    "Selection is ${groupIndian(chars.toString())} characters; max ${groupIndian(DW_VERB_MAX_TEXT_CHARS.toString())}. Select a shorter passage."

const val DW_VERBS_NEED_A_CONNECTION: String =
    "Needs a connection. Nothing has been queued; your text is untouched."

fun dwVerbConsentRefusal(consent: DwTier3Consent): String = when (consent) {
    DwTier3Consent.REFUSED ->
        "The artisan declined sharing, so AI tools are off."
    DwTier3Consent.NOT_RECORDED ->
        "Sharing is on by default."
    DwTier3Consent.GRANTED ->
        "Sharing allowed."
}

const val DW_VERB_CONSENT_LINK_LABEL: String = "Change consent"

const val DW_VERBS_MEDIA_NOT_UPLOADED: String =
    "Available once this file uploads."

const val DW_VERBS_WORKSHOP_NOT_ON_SERVER: String =
    "Available once this workshop uploads."

const val DW_AI_VERBS_SPENT: String =
    "Today's AI runs are used up. Dictation has its own separate allowance."

const val DW_SUBTITLES_SECOND_UPLOAD_NOTE: String =
    "Uploads the recording again and uses one of today's runs."

enum class DwSubtitleFormat(val extension: String, val mimeType: String, val label: String) {
    SRT("srt", "application/x-subrip", "SubRip (.srt)"),

    VTT("vtt", "text/vtt", "WebVTT (.vtt) — a browser video player"),
}

data class DwSubtitleCue(
    val start: Double,
    val end: Double,
    val text: String,
    val speaker: String? = null,
    val estimated: Boolean = false,
)

data class DwSubtitleSummary(
    val count: Int,
    val estimatedCues: Int,
    val durationSeconds: Double?,
    val language: String?,
    val hasSpeakers: Boolean,
    val cues: List<DwSubtitleCue>,
    val readable: Boolean,
)

private const val DW_SUBTITLE_PAYLOAD_SCHEMA = "dw.subtitles/1"

private val DW_SUBTITLE_SUMMARY_EMPTY = DwSubtitleSummary(
    count = 0,
    estimatedCues = 0,
    durationSeconds = null,
    language = null,
    hasSpeakers = false,
    cues = emptyList(),
    readable = false,
)

fun dwSubtitleCueSummary(payload: JsonElement?): DwSubtitleSummary {
    if (payload == null) return DW_SUBTITLE_SUMMARY_EMPTY
    val wrapper = payload as? JsonObject
    val raw = (payload as? JsonArray) ?: (wrapper?.get("cues") as? JsonArray)
        ?: return DW_SUBTITLE_SUMMARY_EMPTY

    val cues = raw.mapNotNull { entry ->
        val cue = entry as? JsonObject ?: return@mapNotNull null
        val start = (cue["start"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val end = (cue["end"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        if (start == null || end == null) return@mapNotNull null
        DwSubtitleCue(
            start = start,
            end = end,
            text = (cue["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            speaker = (cue["speaker"] as? JsonPrimitive)?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() },
            estimated = (cue["estimated"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    fun wrapped(key: String): String? = (wrapper?.get(key) as? JsonPrimitive)?.contentOrNull
    return DwSubtitleSummary(
        count = wrapped("count")?.toIntOrNull() ?: cues.size,
        estimatedCues = wrapped("estimatedCues")?.toIntOrNull() ?: cues.count { it.estimated },
        durationSeconds = wrapped("durationSeconds")?.toDoubleOrNull(),
        language = wrapped("language")?.trim()?.takeIf { it.isNotEmpty() },
        hasSpeakers = cues.any { it.speaker != null },
        cues = cues,
        readable = cues.isNotEmpty() || wrapped("schema") == DW_SUBTITLE_PAYLOAD_SCHEMA,
    )
}

fun dwSubtitleTimecode(seconds: Double): String {
    val whole = maxOf(0L, seconds.toLong())
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    val secs = whole % 60
    val mm = if (hours > 0L) minutes.toString().padStart(2, '0') else minutes.toString()
    val hh = if (hours > 0L) "$hours:" else ""
    return "$hh$mm:${secs.toString().padStart(2, '0')}"
}
