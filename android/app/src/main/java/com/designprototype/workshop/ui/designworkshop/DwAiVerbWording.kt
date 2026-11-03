package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DW_AI_VERBS_SPENT
import com.designprototype.workshop.data.DW_AI_VERB_COUNTDOWN_FROM
import com.designprototype.workshop.data.DW_VERBS_NEED_A_CONNECTION
import com.designprototype.workshop.data.DwAiVerbCapRefused
import com.designprototype.workshop.data.DwAiVerbCapView
import com.designprototype.workshop.data.DwAiVerbNotConfigured
import com.designprototype.workshop.data.DwAiVerbRefused
import com.designprototype.workshop.data.dwAiIsUnrecorded
import com.designprototype.workshop.report.RichDoc
import java.io.IOException

internal fun dwVerbPassageOf(doc: RichDoc, range: DocRange): String {
    val ordered = normaliseRange(doc, range)
    val block = doc.blocks.getOrNull(ordered.anchor.block) ?: return ""
    val text = block.text
    val from = ordered.anchor.offset.coerceIn(0, text.length)
    val to = ordered.focus.offset.coerceIn(0, text.length)
    if (from >= to) return text
    return text.substring(from, to)
}

internal const val DW_VERB_PREVIEW_CHARS: Int = 160

internal fun dwVerbPassagePreview(passage: String): String {
    val flat = StringBuilder(DW_VERB_PREVIEW_CHARS + 1)
    var gap = false
    for (ch in passage) {
        if (ch.isWhitespace()) {
            if (flat.isNotEmpty()) gap = true
            continue
        }
        if (gap) {
            flat.append(' ')
            gap = false
        }
        flat.append(ch)
        if (flat.length > DW_VERB_PREVIEW_CHARS) break
    }
    if (flat.length <= DW_VERB_PREVIEW_CHARS) return flat.toString()
    return flat.substring(0, DW_VERB_PREVIEW_CHARS).trimEnd() + "…"
}

internal const val DW_NO_PARAGRAPH_TO_WORK_ON: String =
    "Place the cursor in a paragraph, or select text."

internal const val MEDIA_VERB_CAPTION: String = "CAPTION"
internal const val MEDIA_VERB_SUBTITLES: String = "SUBTITLES"

internal fun dwMediaVerbsFor(mediaType: String?): Set<String> {
    val stored = (mediaType ?: "").trim().uppercase()
    if (stored.isEmpty()) return emptySet()
    val verbs = mutableSetOf<String>()
    if (stored.endsWith("IMAGE") || stored.endsWith("VIDEO")) verbs += MEDIA_VERB_CAPTION
    if (stored.endsWith("AUDIO") || stored.endsWith("VIDEO")) verbs += MEDIA_VERB_SUBTITLES
    return verbs
}

internal fun dwAiVerbCountdownLine(remaining: Int?, day: String?): String? {
    if (remaining == null || remaining > DW_AI_VERB_COUNTDOWN_FROM) return null
    val runs = if (remaining == 1) "1 run" else "$remaining runs"
    val on = day?.trim().orEmpty()
    val where = if (on.isEmpty()) "left today" else "left for $on"
    return "$runs AI runs $where. Dictation has its own separate allowance."
}

internal fun dwAiVerbAllowanceNote(cap: DwAiVerbCapView): String? = when {
    !cap.told ->
        null

    cap.limit == null && cap.remaining == null ->
        "There is no daily limit on these runs. Dictation is separate."

    else -> null
}

internal fun dwAiVerbProblem(error: Throwable): String = when (error) {
    is DwAiVerbNotConfigured -> error.detail
    is DwAiVerbCapRefused -> error.detail ?: DW_AI_VERBS_SPENT
    is DwAiVerbRefused -> error.detail
        ?: "Couldn't do that. Nothing was written. Try again or ask an admin."
    is IOException -> DW_VERBS_NEED_A_CONNECTION
    else -> "Something went wrong. Nothing changed. Try again."
}

private val DW_LAYER_KIND_LABELS: Map<String, String> = mapOf(
    "RAW_TRANSCRIPT" to "Machine transcript",
    "CLEANED_TRANSCRIPT" to "AI-cleaned transcript",
    "SUMMARY" to "AI summary",
    "OCR_TEXT" to "Text read off a photograph",
    "STRUCTURED_TEXT" to "Fields read off a photograph",
    "TAGS" to "Suggested tags",
    "METADATA" to "Extracted details",
    "PROOFREAD" to "AI-corrected spelling and punctuation",
    "EXPANDED" to "Prose written by AI from a designer's note",
    "TRANSLATION" to "AI translation",
    "CAPTION" to "AI description of a photograph or video",
    "SUBTITLES" to "AI subtitles, with their timings",
)

internal fun dwLayerKindLabel(kind: String?): String {
    DW_LAYER_KIND_LABELS[kind.orEmpty()]?.let { return it }
    return if (!kind.isNullOrBlank()) {
        "AI result"
    } else {
        "A layer with no kind recorded"
    }
}

internal fun dwLayerKindNoun(kind: String?): String =
    DW_LAYER_KIND_LABELS[kind.orEmpty()]?.lowercase() ?: "layer"

internal fun dwLayerKindNote(kind: String?): String? = when (kind) {
    "PROOFREAD" ->
        "Fixes spelling, grammar and punctuation only. Craft words are kept. The original stays."

    "EXPANDED" ->
        "Machine-written from a short note — the one kind that INVENTS details."

    "TRANSLATION" ->
        "Shown beside the original, with both languages recorded."

    "CAPTION" ->
        "A one-line description of the photo or video. Check it against the original."

    "SUBTITLES" ->
        "Timed captions for each line."

    else -> null
}

internal fun dwTierLabel(tier: String?): String = when (tier) {
    "TIER_1" -> "On the handset"
    "TIER_2" -> "On the handset, small model"
    "TIER_3" -> "In the cloud"
    null, "" -> "Where it ran was not recorded"
    else -> "Where it ran is not shown"
}

internal fun dwTierSentence(tier: String?): String = when (tier) {
    "TIER_1" ->
        "Made on this device; nothing left it."

    "TIER_2" ->
        "Made by an on-device model; nothing left it."

    "TIER_3" ->
        "Made by a cloud service using the craft word list."

    null, "" -> "Source not recorded."

    else -> "Update the app to see the source."
}

internal fun dwProvenanceWord(raw: String?): String {
    val value = raw?.trim().orEmpty()
    return if (value.isEmpty() || dwAiIsUnrecorded(value)) "not recorded" else value
}

internal fun dwModelWords(modelId: String?, modelVersion: String?): String {
    val id = dwProvenanceWord(modelId)
    val version = modelVersion?.trim().orEmpty()
    return if (id == "not recorded" || version.isEmpty() || dwAiIsUnrecorded(version)) {
        id
    } else {
        "$id · $version"
    }
}

internal fun dwLanguageWords(raw: String?): String {
    val value = raw?.trim().orEmpty()
    return when {
        value.isEmpty() || dwAiIsUnrecorded(value) -> "not recorded"
        value.equals("multi", ignoreCase = true) -> "multi — mixed, code-switched speech"
        else -> value
    }
}
