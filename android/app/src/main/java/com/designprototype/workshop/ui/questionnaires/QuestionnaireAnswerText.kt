package com.designprototype.workshop.ui.questionnaires

import com.designprototype.workshop.report.Align
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.EMPTY_RICH_DOC
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.report.fromJson
import com.designprototype.workshop.report.fromPlain
import com.designprototype.workshop.report.toJson
import com.designprototype.workshop.report.toPlain
import com.designprototype.workshop.ui.appendSpokenToRecord
import com.designprototype.workshop.ui.looksLikeRichDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal fun questionnaireAnswerIsProse(doc: RichDoc): Boolean =
    doc.blocks.all { block ->
        block.kind == BlockKind.PARAGRAPH &&
            block.level == 0 &&
            block.align == Align.LEFT &&
            block.media.isEmpty() &&
            block.rows.isEmpty() &&
            block.spans.all { it.marks.isEmpty() }
    }

fun questionnaireAnswerStored(doc: RichDoc): String {
    if (doc.isEmpty) return ""
    if (!questionnaireAnswerIsProse(doc)) return toJson(doc).toString()
    return toPlain(doc)
}

fun questionnaireAnswerDoc(stored: String?): RichDoc {
    val text = stored?.takeIf { it.isNotBlank() } ?: return EMPTY_RICH_DOC
    if (looksLikeRichDocument(text)) {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (parsed is JsonObject) return fromJson(parsed)
    }
    return fromPlain(text)
}

fun questionnaireAnswerPlain(stored: String?): String {
    val text = stored?.takeIf { it.isNotBlank() } ?: return ""
    if (!looksLikeRichDocument(text)) return text
    val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    if (parsed !is JsonObject) return text
    return toPlain(fromJson(parsed))
}

internal fun questionnaireAnswerSeed(stored: String): JsonElement? {
    val doc = questionnaireAnswerDoc(stored)
    return if (doc.blocks.isEmpty()) null else toJson(doc)
}

fun questionnaireAnswerAppend(stored: String, addition: String): String {
    val extra = addition.trim()
    if (extra.isEmpty()) return stored
    if (!looksLikeRichDocument(stored)) return appendSpokenToRecord(stored, extra)
    val doc = questionnaireAnswerDoc(stored)
    val added = extra.split("\n")
        .filter { it.isNotBlank() }
        .map { RichBlock(spans = listOf(RichSpan(it.trim()))) }
    return questionnaireAnswerStored(RichDoc(blocks = doc.blocks + added))
}
