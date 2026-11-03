package com.designprototype.workshop.data

import com.designprototype.workshop.report.toPlain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

private val DW_TOKEN_RUN = Regex("[\\p{L}\\p{M}]+|\\p{N}+")

private val DW_COMBINING_DIACRITICS = Regex("[\\u0300-\\u036F]")

private val DW_JS_SPACE = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u00A0', '\u1680',
    '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
)

private val DW_JS_SPACE_RUN = Regex(
    "[\\u0009\\u000A\\u000B\\u000C\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A" +
        "\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+"
)

private fun dwIsJsSpace(ch: Char): Boolean =
    ch in DW_JS_SPACE || ch in '\u2000'..'\u200A'

private fun dwJsTrim(text: String): String = text.trim(::dwIsJsSpace)

const val DW_MIN_QUERY_CHARS = 2

const val DW_MAX_HITS = 60

private const val DW_SNIPPET_RADIUS = 90

private const val DW_SNIPPET_SNAP = 24

private const val DW_MAX_HIGHLIGHTS = 12

private const val DW_SCORE_EXACT = 2
private const val DW_SCORE_PREFIX = 1

data class DwSnippetSegment(val text: String, val match: Boolean)

data class DwSearchSnippet(
    val segments: List<DwSnippetSegment>,
    val clippedStart: Boolean,
    val clippedEnd: Boolean,
)

data class DwWorkshopSearchHit(
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val entityKey: String,
    val entityTitle: String,
    val recordTitle: String?,
    val rowKey: String?,
    val fieldKey: String,
    val fieldLabel: String,
    val anchorFieldKey: String,
    val snippet: DwSearchSnippet,
    val score: Int,
)

enum class DwSearchStatus { IDLE, TOO_SHORT, NO_TERMS, SEARCHED }

data class DwWorkshopSearchResult(
    val hits: List<DwWorkshopSearchHit>,
    val total: Int,
    val truncated: Boolean,
    val status: DwSearchStatus,
    val terms: List<String>,
)

internal data class DwIndexEntry(
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val entityKey: String,
    val entityTitle: String,
    val recordTitle: String?,
    val rowKey: String?,
    val fieldKey: String,
    val fieldLabel: String,
    val anchorFieldKey: String,
    val text: String,
    val folded: String,
    val order: Int,
)

class DwWorkshopSearchIndex internal constructor(
    internal val entries: List<DwIndexEntry>,
    internal val tokens: List<String>,
    internal val postings: List<IntArray>,
    val fieldCount: Int,
    val stageCount: Int,
    val buildMs: Long,
)

object DwWorkshopSearch {

    private class FoldOffsets {
        val startAt = ArrayList<Int>()
        val endAt = ArrayList<Int>()
    }

    private fun fold(text: String, offsets: FoldOffsets?): String {
        val out = StringBuilder(text.length)
        var index = 0
        val length = text.length

        while (index < length) {
            var ascii = index
            while (ascii < length && text[ascii].code < 0x80) ascii += 1
            if (ascii > index) {
                out.append(text.substring(index, ascii).lowercase())
                if (offsets != null) {
                    for (at in index until ascii) {
                        offsets.startAt.add(at)
                        offsets.endAt.add(at + 1)
                    }
                }
                index = ascii
                continue
            }

            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            val single = String(Character.toChars(codePoint))
            val piece = DW_COMBINING_DIACRITICS
                .replace(Normalizer.normalize(single, Normalizer.Form.NFKD), "")
                .lowercase()
            out.append(piece)
            if (offsets != null) {
                for (unit in 0 until piece.length) {
                    offsets.startAt.add(index)
                    offsets.endAt.add(index + width)
                }
            }
            index += width
        }
        return out.toString()
    }

    fun foldForSearch(text: String): String = fold(text, null)

    fun tokenise(text: String): List<String> = tokensOf(foldForSearch(text))

    private fun tokensOf(folded: String): List<String> =
        DW_TOKEN_RUN.findAll(folded).map { it.value }.toList()

    private fun inputValue(value: JsonElement?): String = when {
        value == null || value is JsonNull -> ""
        value is JsonPrimitive -> value.content
        else -> ""
    }

    private fun enumLabel(schema: SchemaResponse, field: FieldDto, token: String): String {
        val options = field.options.ifEmpty { schema.enums[field.enumName].orEmpty() }
        return options.firstOrNull { it.value == token }?.label ?: token
    }

    private fun entitiesByModel(schema: SchemaResponse): Map<String, EntityDto> {
        val out = HashMap<String, EntityDto>()
        for (stage in schema.stages) for (entity in stage.entities) out[entity.name] = entity
        return out
    }

    private class RowRef(val entity: EntityDto, val row: DraftRow, val index: Int)

    private fun rowKeyOf(row: DraftRow): String? {
        val clientKey = row.id.substringAfter(DW_ROW_KEY_SEPARATOR, "")
        if (clientKey.isNotEmpty()) return clientKey
        return (row.values["_entryId"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    }

    private fun rowsById(schema: SchemaResponse, draft: WorkshopDraft?): Map<String, RowRef> {
        val out = HashMap<String, RowRef>()
        for (stage in schema.stages) {
            val stored = draft?.stages?.get(stage.key) ?: continue
            for (entity in stage.collections) {
                stored.rowsFor(entity.key).forEachIndexed { index, row ->
                    val ref = RowRef(entity, row, index)
                    (row.values["_entryId"] as? JsonPrimitive)
                        ?.takeIf { it.isString && it.content.isNotEmpty() }
                        ?.let { out[it.content] = ref }
                    rowKeyOf(row)?.let { out[it] = ref }
                }
            }
        }
        return out
    }

    private fun referenceDisplayHint(
        models: Map<String, EntityDto>,
        entity: EntityDto,
        field: FieldDto,
        record: Map<String, JsonElement>,
    ): String {
        if (field.refModel.isEmpty() || models.containsKey(field.refModel)) return ""
        val stem = field.key.removeSuffix("Ref")
        val derived = stem + "Name"
        val documented = "documented" + stem.replaceFirstChar { it.uppercase() } + "Name"
        val titled = stem + "Title"
        val cascadeParent = entity.liveFields.any { it.refFilterBy == field.key }
        val candidates = buildList {
            add(derived)
            add(documented)
            add(titled)
            if (cascadeParent) add("documentedFor")
            add("name")
        }
        for (key in candidates) {
            val target = entity.field(key)?.takeIf { !it.deprecated } ?: continue
            val type = DwFieldType.of(target.type)
            if (type != DwFieldType.TEXT && type != DwFieldType.LONG_TEXT) continue
            val text = dwJsTrim(inputValue(record[key]))
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun refLabel(
        models: Map<String, EntityDto>,
        rows: Map<String, RowRef>,
        entity: EntityDto,
        field: FieldDto,
        record: Map<String, JsonElement>,
        value: String,
    ): String {
        val target = rows[value]
        if (target != null && (field.refModel.isEmpty() || models[field.refModel]?.key == target.entity.key)) {
            return dwCardRowTitle(target.entity, target.row.values, target.index)
        }
        return referenceDisplayHint(models, entity, field, record)
    }

    private fun searchableText(
        schema: SchemaResponse,
        models: Map<String, EntityDto>,
        rows: Map<String, RowRef>,
        entity: EntityDto,
        field: FieldDto,
        record: Map<String, JsonElement>,
    ): String {
        val value = record[field.key]
        if (value == null || value is JsonNull) return ""
        return when (DwFieldType.of(field.type)) {
            DwFieldType.TEXT,
            DwFieldType.LONG_TEXT,
            DwFieldType.URL,
            DwFieldType.EMAIL,
            DwFieldType.PHONE -> dwJsTrim(inputValue(value))

            DwFieldType.RICH_TEXT -> dwJsTrim(toPlain(value))

            DwFieldType.TAGS -> DwValues.list(value).joinToString(" · ")
            DwFieldType.ENUM -> enumLabel(schema, field, dwJsTrim(inputValue(value)))
            DwFieldType.MULTI_ENUM ->
                DwValues.list(value).joinToString(" · ") { enumLabel(schema, field, it) }

            DwFieldType.REF ->
                refLabel(models, rows, entity, field, record, dwJsTrim(inputValue(value)))

            else -> ""
        }
    }

    fun emptyIndex(): DwWorkshopSearchIndex =
        DwWorkshopSearchIndex(emptyList(), emptyList(), emptyList(), 0, 0, 0L)

    fun buildIndex(schema: SchemaResponse, draft: WorkshopDraft?): DwWorkshopSearchIndex {
        val startedAt = System.nanoTime()
        val models = entitiesByModel(schema)
        val rows = rowsById(schema, draft)

        val entries = ArrayList<DwIndexEntry>()
        val stagesWithText = HashSet<String>()
        var order = 0

        for (stage in schema.stages) {
            val stored = draft?.stages?.get(stage.key) ?: continue
            val before = entries.size
            for (entity in stage.entities) {
                if (entity.cardinality == "SINGLETON") {
                    if (entity.key != stage.singleton?.key) continue
                    order = collect(
                        schema, models, rows, stage, entity, stored.values, null, null, entries, order,
                    )
                } else {
                    stored.rowsFor(entity.key).forEachIndexed { index, row ->
                        order = collect(
                            schema, models, rows, stage, entity, row.values,
                            recordTitleOf(models, rows, entity, row, index),
                            rowKeyOf(row), entries, order,
                        )
                    }
                }
            }
            if (entries.size > before) stagesWithText.add(stage.key)
        }

        val postingsByToken = HashMap<String, MutableList<Int>>()
        entries.forEachIndexed { entryIndex, entry ->
            val seen = HashSet<String>()
            for (token in tokensOf(entry.folded)) {
                if (!seen.add(token)) continue
                postingsByToken.getOrPut(token) { ArrayList() }.add(entryIndex)
            }
        }

        val tokens = postingsByToken.keys.sorted()
        val postings = tokens.map { postingsByToken.getValue(it).toIntArray() }

        return DwWorkshopSearchIndex(
            entries = entries,
            tokens = tokens,
            postings = postings,
            fieldCount = entries.size,
            stageCount = stagesWithText.size,
            buildMs = (System.nanoTime() - startedAt) / 1_000_000L,
        )
    }

    private fun recordTitleOf(
        models: Map<String, EntityDto>,
        rows: Map<String, RowRef>,
        entity: EntityDto,
        row: DraftRow,
        index: Int,
    ): String {
        val labelField = entity.field(entity.labelField)
        if (labelField != null && DwFieldType.of(labelField.type) == DwFieldType.REF) {
            val resolved = refLabel(
                models, rows, entity, labelField, row.values,
                dwJsTrim(inputValue(row.values[labelField.key])),
            )
            if (dwJsTrim(resolved).isNotEmpty()) return dwJsTrim(resolved)
        }
        return dwCardRowTitle(entity, row.values, index)
    }

    private fun collect(
        schema: SchemaResponse,
        models: Map<String, EntityDto>,
        rows: Map<String, RowRef>,
        stage: StageDto,
        entity: EntityDto,
        record: Map<String, JsonElement>,
        recordTitle: String?,
        rowKey: String?,
        out: MutableList<DwIndexEntry>,
        order: Int,
    ): Int {
        var next = order
        for (field in entity.liveFields) {
            val text = searchableText(schema, models, rows, entity, field, record)
            if (text.isEmpty()) continue
            out.add(
                DwIndexEntry(
                    stageKey = stage.key,
                    stageNumber = stage.number,
                    stageTitle = stage.title,
                    entityKey = entity.key,
                    entityTitle = entity.title,
                    recordTitle = recordTitle,
                    rowKey = rowKey,
                    fieldKey = field.key,
                    fieldLabel = field.label,
                    anchorFieldKey = field.captionFor.ifEmpty { field.key },
                    text = text,
                    folded = foldForSearch(text),
                    order = next,
                )
            )
            next += 1
        }
        return next
    }

    private fun nothing(status: DwSearchStatus, terms: List<String> = emptyList()) =
        DwWorkshopSearchResult(emptyList(), 0, false, status, terms)

    fun search(index: DwWorkshopSearchIndex, query: String): DwWorkshopSearchResult {
        val raw = dwJsTrim(query)
        if (raw.isEmpty()) return nothing(DwSearchStatus.IDLE)
        if (raw.codePointCount(0, raw.length) < DW_MIN_QUERY_CHARS) return nothing(DwSearchStatus.TOO_SHORT)

        val terms = tokenise(raw)
        if (terms.isEmpty()) return nothing(DwSearchStatus.NO_TERMS, terms)

        var surviving: Map<Int, Int>? = null
        for (term in terms) {
            val perTerm = HashMap<Int, Int>()
            for (tokenIndex in prefixRange(index.tokens, term)) {
                val weight = if (index.tokens[tokenIndex] == term) DW_SCORE_EXACT else DW_SCORE_PREFIX
                for (entryIndex in index.postings[tokenIndex]) {
                    val existing = perTerm[entryIndex]
                    if (existing == null || existing < weight) perTerm[entryIndex] = weight
                }
            }
            val previous = surviving
            surviving = if (previous == null) {
                perTerm
            } else {
                val merged = HashMap<Int, Int>()
                val small = if (previous.size <= perTerm.size) previous else perTerm
                val large = if (small === previous) perTerm else previous
                for ((entryIndex, score) in small) {
                    val other = large[entryIndex] ?: continue
                    merged[entryIndex] = score + other
                }
                merged
            }
            if (surviving.isEmpty()) break
        }

        val matched = surviving ?: emptyMap()
        val ranked = matched.entries.sortedWith(
            compareByDescending<Map.Entry<Int, Int>> { it.value }
                .thenBy { index.entries[it.key].order }
        )

        val hits = ranked.take(DW_MAX_HITS).map { (entryIndex, score) ->
            val entry = index.entries[entryIndex]
            DwWorkshopSearchHit(
                stageKey = entry.stageKey,
                stageNumber = entry.stageNumber,
                stageTitle = entry.stageTitle,
                entityKey = entry.entityKey,
                entityTitle = entry.entityTitle,
                recordTitle = entry.recordTitle,
                rowKey = entry.rowKey,
                fieldKey = entry.fieldKey,
                fieldLabel = entry.fieldLabel,
                anchorFieldKey = entry.anchorFieldKey,
                snippet = buildSnippet(entry.text, terms),
                score = score,
            )
        }

        return DwWorkshopSearchResult(
            hits = hits,
            total = ranked.size,
            truncated = ranked.size > hits.size,
            status = DwSearchStatus.SEARCHED,
            terms = terms,
        )
    }

    private fun prefixRange(tokens: List<String>, term: String): List<Int> {
        var low = 0
        var high = tokens.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (tokens[middle] < term) low = middle + 1 else high = middle
        }
        val out = ArrayList<Int>()
        var index = low
        while (index < tokens.size && tokens[index].startsWith(term)) {
            out.add(index)
            index += 1
        }
        return out
    }

    private fun buildSnippet(text: String, terms: List<String>): DwSearchSnippet {
        val offsets = FoldOffsets()
        val folded = fold(text, offsets)

        val from = ArrayList<Int>()
        val to = ArrayList<Int>()
        var ceiling = Int.MAX_VALUE
        for (match in DW_TOKEN_RUN.findAll(folded)) {
            val start = match.range.first
            if (start > ceiling || from.size >= DW_MAX_HIGHLIGHTS) break
            if (terms.none { match.value.startsWith(it) }) continue
            from.add(start)
            to.add(start + match.value.length)
            if (from.size == 1) ceiling = start + match.value.length + DW_SNIPPET_RADIUS
        }

        if (from.isEmpty()) {
            val head = text.take(DW_SNIPPET_RADIUS * 2)
            return DwSearchSnippet(
                segments = listOfNotNull(segment(collapse(head), false)),
                clippedStart = false,
                clippedEnd = head.length < text.length,
            )
        }

        val windowStart = snapForward(folded, maxOf(0, from[0] - DW_SNIPPET_RADIUS), from[0])
        val windowEnd = snapBack(folded, minOf(folded.length, to[0] + DW_SNIPPET_RADIUS), to[0])

        val segments = ArrayList<DwSnippetSegment>()
        var cursor = windowStart
        for (i in from.indices) {
            if (to[i] <= windowStart || from[i] >= windowEnd) continue
            val start = maxOf(from[i], windowStart)
            val end = minOf(to[i], windowEnd)
            if (start > cursor) segment(collapse(slice(text, offsets, cursor, start)), false)?.let(segments::add)
            segment(collapse(slice(text, offsets, start, end)), true)?.let(segments::add)
            cursor = end
        }
        if (cursor < windowEnd) {
            segment(collapse(slice(text, offsets, cursor, windowEnd)), false)?.let(segments::add)
        }

        return DwSearchSnippet(
            segments = segments,
            clippedStart = windowStart > 0,
            clippedEnd = windowEnd < folded.length,
        )
    }

    private fun segment(text: String, match: Boolean): DwSnippetSegment? =
        if (text.isEmpty()) null else DwSnippetSegment(text, match)

    private fun slice(text: String, offsets: FoldOffsets, from: Int, to: Int): String {
        if (to <= from) return ""
        val start = offsets.startAt.getOrElse(from) { 0 }
        val end = offsets.endAt.getOrElse(to - 1) { text.length }
        return text.substring(start, end)
    }

    private fun collapse(text: String): String = DW_JS_SPACE_RUN.replace(text, " ")

    private fun snapForward(folded: String, from: Int, limit: Int): Int {
        if (from == 0) return 0
        var index = from
        val stop = minOf(limit, from + DW_SNIPPET_SNAP)
        while (index < stop) {
            if (dwIsJsSpace(folded[index])) return index + 1
            index += 1
        }
        return from
    }

    private fun snapBack(folded: String, to: Int, limit: Int): Int {
        if (to == folded.length) return to
        var index = to
        val stop = maxOf(limit, to - DW_SNIPPET_SNAP)
        while (index > stop) {
            if (dwIsJsSpace(folded[index - 1])) return index - 1
            index -= 1
        }
        return to
    }

    fun focusOf(hit: DwWorkshopSearchHit): DwStageFocus = DwStageFocus(
        stageKey = hit.stageKey,
        entityKey = hit.entityKey,
        fieldKey = hit.anchorFieldKey,
        rowKey = hit.rowKey,
    )
}
