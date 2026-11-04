package com.designprototype.workshop.ui

import com.designprototype.workshop.data.DwConnection
import com.designprototype.workshop.data.DwDictationConditions
import com.designprototype.workshop.data.DwDictationPlan
import com.designprototype.workshop.data.DwDictationRung
import com.designprototype.workshop.data.DwPackOffer
import com.designprototype.workshop.data.dwPackOffer
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.EMPTY_RICH_DOC
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.report.fromJson
import com.designprototype.workshop.report.toJson
import com.designprototype.workshop.report.toPlain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

fun recordDictationRungs(plan: DwDictationPlan): List<DwDictationRung> =
    plan.rungs.filter { it != DwDictationRung.SERVER_DICTATE }

fun recordDictationConditions(
    languageLabel: String,
    packState: com.designprototype.workshop.data.DwPackState,
    onDeviceEngine: Boolean,
    networkRecogniser: Boolean,
    online: Boolean,
    deviceRefusedLanguage: Boolean,
    appModelServesLanguage: Boolean,
    appModelRefusedLanguage: Boolean,
): DwDictationConditions = DwDictationConditions(
    languageLabel = languageLabel,
    packState = packState,
    onDeviceEngine = onDeviceEngine,
    networkRecogniser = networkRecogniser,
    online = online,
    serverRouteUnavailable = true,
    deviceRefusedLanguage = deviceRefusedLanguage,
    appModelServesLanguage = appModelServesLanguage,
    appModelRefusedLanguage = appModelRefusedLanguage,
    tier3Consent = com.designprototype.workshop.data.DwTier3Consent.NOT_RECORDED,
    dailyCapSpent = false,
    dailyCapLimit = null,
    workshopOnServer = false,
)

fun recordDictationNothingLeftSentence(conditions: DwDictationConditions): String {
    val label = conditions.languageLabel
    return when {
        !conditions.online && conditions.appModelRefusedLanguage ->
            "Can't dictate $label offline. Type for now."

        !conditions.online && conditions.onDeviceEngine &&
            dwPackOffer(conditions.packState, DwConnection.NONE) == DwPackOffer.NO_CONNECTION ->
            "$label pack missing and you're offline. Type for now; add the pack in Settings › Offline dictation languages."

        !conditions.online && conditions.onDeviceEngine ->
            "$label needs a connection. Type for now."

        !conditions.online ->
            "$label dictation needs a connection. Type for now."

        !conditions.networkRecogniser && !conditions.onDeviceEngine ->
            "There is no speech recogniser on this phone. Type the answer in."

        conditions.deviceRefusedLanguage ->
            "$label isn't supported here. Pick another language or type."

        else ->
            "$label dictation unavailable. Type or try another language."
    }
}

fun appendSpokenToRecord(existing: String, spoken: String): String = when {
    spoken.isBlank() -> existing
    existing.isBlank() -> spoken
    existing.last().isWhitespace() -> existing + spoken
    else -> "$existing $spoken"
}

internal fun looksLikeRichDocument(stored: String): Boolean {
    val trimmed = stored.trimStart()
    return trimmed.startsWith("{") && trimmed.contains("\"blocks\"")
}

private data class ParsedMarker(val kind: BlockKind, val level: Int, val text: String)

private val ORDERED_MARKER = Regex("^(\\d{1,3})\\. (.*)$")

private fun parseMarker(line: String): ParsedMarker? {
    var level = 0
    var rest = line
    while (rest.startsWith("  ")) {
        level += 1
        rest = rest.substring(2)
    }
    if (rest.startsWith("• ")) return ParsedMarker(BlockKind.BULLET_ITEM, level, rest.substring(2))
    val ordered = ORDERED_MARKER.matchEntire(rest) ?: return null
    return ParsedMarker(BlockKind.ORDERED_ITEM, level, ordered.groupValues[2])
}

fun recordDocFromStored(stored: String?): RichDoc {
    val text = stored?.takeIf { it.isNotBlank() } ?: return EMPTY_RICH_DOC
    if (looksLikeRichDocument(text)) {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (parsed is JsonObject) return fromJson(parsed)
    }
    val blocks = text.split("\n")
        .filter { it.isNotBlank() }
        .map { line ->
            val marker = parseMarker(line)
            if (marker == null) {
                RichBlock(spans = listOf(RichSpan(line.trim())))
            } else {
                RichBlock(
                    kind = marker.kind,
                    level = marker.level,
                    spans = listOf(RichSpan(marker.text.trim())),
                )
            }
        }
    return if (blocks.isEmpty()) EMPTY_RICH_DOC else RichDoc(blocks = blocks)
}

fun recordStoredFromDoc(doc: RichDoc): String? = toPlain(doc).takeIf { it.isNotBlank() }

fun recordStoredKeepingFormat(doc: RichDoc): String? {
    if (doc.isEmpty) return null
    val plainProse = doc.blocks.all { block ->
        block.kind == BlockKind.PARAGRAPH &&
            block.level == 0 &&
            block.align == com.designprototype.workshop.report.Align.LEFT &&
            block.media.isEmpty() &&
            block.rows.isEmpty() &&
            block.spans.all { it.marks.isEmpty() }
    }
    return if (plainProse) toPlain(doc).takeIf { it.isNotBlank() } else toJson(doc).toString()
}

const val RECORD_RICH_TEXT_NOTE: String =
    "Lists and paragraphs are saved; bold and italic aren't."
