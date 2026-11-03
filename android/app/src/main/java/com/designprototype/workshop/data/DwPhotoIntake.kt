package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

object DwPhotoIntake {

    const val OUTSIDE_GRACE_DAYS: Int = 2

    const val DEFAULT_TIMEZONE: String = "Asia/Kolkata"

    const val MAX_PROPOSALS: Int = 3

    private val DATE_RANGES: List<Pair<String, String>> = listOf(
        "startDate" to "endDate",
        "startDate" to "completedDate",
        "surveyStartDate" to "surveyEndDate",
    )

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    private val EXIF_STAMP = Regex("^(\\d{4})[:-](\\d{2})[:-](\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?")

    private val EXIF_OFFSET = Regex("^(?:(Z)|([+-])(\\d{2}):?(\\d{2}))$")

    private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

    data class DwExifParts(
        val y: Int,
        val mo: Int,
        val d: Int,
        val hh: Int,
        val mm: Int,
        val ss: Int,
    )

    fun parseExifStamp(raw: String?): DwExifParts? {
        if (raw.isNullOrEmpty()) return null
        val match = EXIF_STAMP.find(jsTrim(raw)) ?: return null
        val groups = match.groupValues
        val parts = DwExifParts(
            y = groups[1].toInt(),
            mo = groups[2].toInt(),
            d = groups[3].toInt(),
            hh = groups[4].toInt(),
            mm = groups[5].toInt(),
            ss = groups[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0,
        )
        if (parts.y < 1900 || parts.mo < 1 || parts.mo > 12 || parts.d < 1) return null
        if (parts.d > daysInMonth(parts.y, parts.mo)) return null
        if (parts.hh > 23 || parts.mm > 59 || parts.ss > 59) return null
        return parts
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        if (month == 2) return if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        return if (month == 4 || month == 6 || month == 9 || month == 11) 30 else 31
    }

    private fun parseExifOffset(raw: String?): Int? {
        if (raw.isNullOrEmpty()) return null
        val match = EXIF_OFFSET.find(jsTrim(raw)) ?: return null
        val groups = match.groupValues
        if (groups[1] == "Z") return 0
        val sign = if (groups[2] == "-") -1 else 1
        return sign * (groups[3].toInt() * 60 + groups[4].toInt())
    }

    private fun zoneOffsetMinutesAt(timeZone: String, utcMs: Long): Int? = runCatching {
        ZoneId.of(timeZone).rules.getOffset(Instant.ofEpochMilli(utcMs)).totalSeconds / 60
    }.getOrNull()

    fun resolveStamp(photo: DwIntakePhoto, timeZone: String): DwPhotoStamp? {
        val parts = parseExifStamp(photo.takenAt) ?: return null

        val raw = "${pad4(parts.y)}-${pad2(parts.mo)}-${pad2(parts.d)} ${pad2(parts.hh)}:${pad2(parts.mm)}"
        val plain = DwPhotoStamp(
            date = "${pad4(parts.y)}-${pad2(parts.mo)}-${pad2(parts.d)}",
            minutes = parts.hh * 60 + parts.mm,
            raw = raw,
            shiftedFrom = null,
        )

        val declared = parseExifOffset(photo.takenAtOffset) ?: return plain

        val utcMs = LocalDateTime.of(parts.y, parts.mo, parts.d, parts.hh, parts.mm, parts.ss)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli() - declared * 60_000L
        val local = zoneOffsetMinutesAt(timeZone, utcMs)
        if (local == null || local == declared) return plain

        val shifted = Instant.ofEpochMilli(utcMs + local * 60_000L).atOffset(ZoneOffset.UTC)
        return DwPhotoStamp(
            date = "${pad4(shifted.year)}-${pad2(shifted.monthValue)}-${pad2(shifted.dayOfMonth)}",
            minutes = shifted.hour * 60 + shifted.minute,
            raw = raw,
            shiftedFrom = photo.takenAtOffset,
        )
    }

    fun photoDayIndex(date: String?): Int? {
        if (date.isNullOrEmpty()) return null
        val match = ISO_DATE.find(jsTrim(date)) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) return null
        val effectiveYear = if (year in 0..99) year + 1900 else year
        return LocalDate.of(effectiveYear, month, 1).plusDays((day - 1).toLong()).toEpochDay().toInt()
    }

    private fun indexToDate(index: Int): String {
        val at = LocalDate.ofEpochDay(index.toLong())
        return "${pad4(at.year)}-${pad2(at.monthValue)}-${pad2(at.dayOfMonth)}"
    }

    fun formatAnchorDate(date: String): String {
        val match = ISO_DATE.find(date) ?: return date
        val month = match.groupValues[2]
        return "${match.groupValues[3].toInt()} ${MONTHS.getOrNull(month.toInt() - 1) ?: month} ${match.groupValues[1]}"
    }

    fun formatStamp(stamp: DwPhotoStamp): String =
        "${formatAnchorDate(stamp.date)}, ${pad2(stamp.minutes / 60)}:${pad2(stamp.minutes % 60)}"

    private fun formatRange(anchor: DwWorkshopAnchor): String =
        if (anchor.start == anchor.end) {
            formatAnchorDate(anchor.start)
        } else {
            "${formatAnchorDate(anchor.start)} – ${formatAnchorDate(anchor.end)}"
        }

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')

    private fun pad4(value: Int): String = value.toString().padStart(4, '0')

    private fun jsTrim(text: String): String = text.trim { ch ->
        ch.code == 0x09 || ch.code == 0x0A || ch.code == 0x0B || ch.code == 0x0C || ch.code == 0x0D ||
            ch.code == 0xFEFF || Character.isSpaceChar(ch)
    }

    private fun indianGrouped(value: Int): String {
        val negative = value < 0
        val digits = value.toLong().let { if (negative) -it else it }.toString()
        if (digits.length <= 3) return if (negative) "-$digits" else digits
        val tail = digits.takeLast(3)
        var head = digits.dropLast(3)
        val groups = ArrayList<String>()
        while (head.length > 2) {
            groups.add(0, head.takeLast(2))
            head = head.dropLast(2)
        }
        if (head.isNotEmpty()) groups.add(0, head)
        val grouped = (groups + tail).joinToString(",")
        return if (negative) "-$grouped" else grouped
    }

    private fun dateValue(row: DwDataRow, key: String): String? {
        val raw = row[key] as? JsonPrimitive ?: return null
        if (!raw.isString) return null
        val trimmed = jsTrim(raw.content).take(10)
        return if (photoDayIndex(trimmed) == null) null else trimmed
    }

    internal fun rowLabelOf(labelField: String, row: DwDataRow): String? {
        if (labelField.isEmpty()) return null
        val raw = row[labelField] as? JsonPrimitive ?: return null
        if (!raw.isString) return null
        return jsTrim(raw.content).takeIf { it.isNotEmpty() }
    }

    fun rowKeyOf(row: DwDataRow): String? {
        val client = (row["_clientKey"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        if (client != null) return client
        return (row["_entryId"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }

    fun buildAnchors(schema: SchemaResponse, stages: Map<String, DwStageData>): List<DwWorkshopAnchor> {
        val anchors = ArrayList<DwWorkshopAnchor>()

        for (stage in schema.stages) {
            val data = stages[stage.key] ?: continue

            for (entity in stage.entities) {
                val dateKeys = entity.fields.filter { it.type == "DATE" }.map { it.key }
                if (dateKeys.isEmpty()) continue
                val labelOf = { key: String -> entity.fields.firstOrNull { it.key == key }?.label ?: key }

                val rows: List<Triple<DwDataRow, String?, String?>> =
                    if (entity.cardinality == "SINGLETON") {
                        listOf(Triple(data.singleton, null, null))
                    } else {
                        data.collections[entity.key].orEmpty().map { row ->
                            Triple(row, rowKeyOf(row), rowLabelOf(entity.labelField, row))
                        }
                    }

                for ((row, key, label) in rows) {
                    val consumed = HashSet<String>()

                    for ((startKey, endKey) in DATE_RANGES) {
                        if (startKey !in dateKeys || endKey !in dateKeys) continue
                        val start = dateValue(row, startKey) ?: continue
                        val end = dateValue(row, endKey) ?: continue
                        consumed.add(startKey)
                        consumed.add(endKey)
                        val ordered = if (photoDayIndex(start)!! <= photoDayIndex(end)!!) start to end else end to start
                        anchors.add(
                            DwWorkshopAnchor(
                                stageKey = stage.key,
                                stageNumber = stage.number,
                                stageTitle = stage.title,
                                entityKey = entity.key,
                                entityTitle = entity.title,
                                rowKey = key,
                                rowLabel = label,
                                fieldLabel = "${labelOf(startKey)} – ${labelOf(endKey)}",
                                kind = DwAnchorKind.SPAN,
                                start = ordered.first,
                                end = ordered.second,
                            )
                        )
                    }

                    for (dateKey in dateKeys) {
                        if (dateKey in consumed) continue
                        val day = dateValue(row, dateKey) ?: continue
                        anchors.add(
                            DwWorkshopAnchor(
                                stageKey = stage.key,
                                stageNumber = stage.number,
                                stageTitle = stage.title,
                                entityKey = entity.key,
                                entityTitle = entity.title,
                                rowKey = key,
                                rowLabel = label,
                                fieldLabel = labelOf(dateKey),
                                kind = DwAnchorKind.DAY,
                                start = day,
                                end = day,
                            )
                        )
                    }
                }
            }
        }

        return anchors
    }

    fun photoTargets(schema: SchemaResponse, stageKey: String, entityKey: String): List<DwPhotoTarget> {
        val stage = schema.stages.firstOrNull { it.key == stageKey }
        val entity = stage?.entities?.firstOrNull { it.key == entityKey }
        return entity?.fields.orEmpty()
            .filter { it.type == "IMAGE" || it.type == "IMAGE_LIST" }
            .map {
                DwPhotoTarget(
                    fieldKey = it.key,
                    fieldLabel = it.label,
                    multiple = it.type == "IMAGE_LIST",
                    maxItems = it.maxItems,
                )
            }
    }

    private const val UNDATED = Int.MAX_VALUE

    private fun distanceTo(anchor: DwWorkshopAnchor, day: Int): Int {
        val from = photoDayIndex(anchor.start) ?: return UNDATED
        val to = photoDayIndex(anchor.end) ?: return UNDATED
        if (day < from) return from - day
        if (day > to) return day - to
        return 0
    }

    private fun widthOf(anchor: DwWorkshopAnchor): Int {
        val from = photoDayIndex(anchor.start) ?: return UNDATED
        val to = photoDayIndex(anchor.end) ?: return UNDATED
        return to - from + 1
    }

    private fun compareCandidates(a: DwWorkshopAnchor, b: DwWorkshopAnchor, day: Int): Int {
        val distanceA = distanceTo(a, day)
        val distanceB = distanceTo(b, day)
        if (distanceA != distanceB) return distanceA.compareTo(distanceB)
        val widthA = widthOf(a)
        val widthB = widthOf(b)
        if (widthA != widthB) return widthA.compareTo(widthB)
        if (a.stageNumber != b.stageNumber) return a.stageNumber.compareTo(b.stageNumber)
        if (a.entityKey != b.entityKey) return if (a.entityKey < b.entityKey) -1 else 1
        return a.rowKey.orEmpty().compareTo(b.rowKey.orEmpty())
    }

    private fun evidenceFor(
        stamp: DwPhotoStamp,
        anchor: DwWorkshopAnchor,
        basis: DwProposalBasis,
        daysAway: Int,
    ): String {
        val where = if (anchor.rowLabel != null) {
            "stage ${anchor.stageNumber}'s ${anchor.entityTitle} row “${anchor.rowLabel}”"
        } else {
            "stage ${anchor.stageNumber}'s ${anchor.entityTitle}"
        }
        val shift = if (stamp.shiftedFrom != null) {
            " The camera recorded ${stamp.raw} at ${stamp.shiftedFrom}; that is the workshop-local time above."
        } else {
            ""
        }
        val field = anchor.fieldLabel.lowercase()

        if (basis == DwProposalBasis.ON_DAY) {
            return "Taken ${formatStamp(stamp)} — $where, $field ${formatAnchorDate(anchor.start)}.$shift"
        }
        if (basis == DwProposalBasis.IN_SPAN) {
            return "Taken ${formatStamp(stamp)} — inside $where, $field ${formatRange(anchor)}.$shift"
        }
        val side = if (photoDayIndex(stamp.date)!! < photoDayIndex(anchor.start)!!) "before" else "after"
        val days = "$daysAway day${if (daysAway == 1) "" else "s"}"
        return "Taken ${formatStamp(stamp)} — nothing recorded covers that date. " +
            "It is $days $side $where, $field ${formatRange(anchor)}.$shift"
    }

    fun rankProposals(stamp: DwPhotoStamp, anchors: List<DwWorkshopAnchor>): List<DwStageProposal> {
        val day = photoDayIndex(stamp.date) ?: return emptyList()
        val usable = anchors.filter { distanceTo(it, day) != UNDATED }
        if (usable.isEmpty()) return emptyList()

        val ordered = usable.sortedWith { a, b -> compareCandidates(a, b, day) }
        val nearest = distanceTo(ordered[0], day)
        if (nearest > OUTSIDE_GRACE_DAYS) return emptyList()

        return ordered.take(MAX_PROPOSALS).map { anchor ->
            val daysAway = distanceTo(anchor, day)
            val basis = when {
                daysAway > 0 -> DwProposalBasis.NEAREST
                anchor.kind == DwAnchorKind.DAY -> DwProposalBasis.ON_DAY
                else -> DwProposalBasis.IN_SPAN
            }
            DwStageProposal(
                anchor = anchor,
                basis = basis,
                daysAway = daysAway,
                evidence = evidenceFor(stamp, anchor, basis, daysAway),
            )
        }
    }

    fun intakePhotos(
        photos: List<DwIntakePhoto>,
        anchors: List<DwWorkshopAnchor>,
        timeZone: String = DEFAULT_TIMEZONE,
    ): List<DwPhotoIntakeRow> {
        val zone = jsTrim(timeZone).ifEmpty { DEFAULT_TIMEZONE }
        val dated = anchors.filter { photoDayIndex(it.start) != null && photoDayIndex(it.end) != null }
        val earliest = dated.mapNotNull { photoDayIndex(it.start) }.minOrNull()
        val latest = dated.mapNotNull { photoDayIndex(it.end) }.maxOrNull()

        return photos.map { photo ->
            val stamp = resolveStamp(photo, zone)
                ?: return@map DwPhotoIntakeRow(
                    fileName = photo.fileName,
                    stamp = null,
                    refusal = "No capture date in this file. Screenshots, scanned pages and anything " +
                        "sent through a messaging app arrive with it stripped — choose the stage yourself.",
                    proposals = emptyList(),
                )

            val proposals = rankProposals(stamp, dated)
            if (proposals.isNotEmpty()) {
                return@map DwPhotoIntakeRow(photo.fileName, stamp, null, proposals)
            }

            if (earliest == null || latest == null) {
                return@map DwPhotoIntakeRow(
                    fileName = photo.fileName,
                    stamp = stamp,
                    refusal = "Taken ${formatStamp(stamp)}, but this workshop has no dates recorded yet " +
                        "— fill in the start and end dates on stage 1 and the daily logs, and these " +
                        "photographs will place themselves.",
                    proposals = emptyList(),
                )
            }

            val day = photoDayIndex(stamp.date)!!
            val gap = if (day < earliest) earliest - day else day - latest
            val side = if (day < earliest) "before the first" else "after the last"
            val edge = if (day < earliest) earliest else latest
            DwPhotoIntakeRow(
                fileName = photo.fileName,
                stamp = stamp,
                refusal = "Taken ${formatStamp(stamp)} — ${indianGrouped(gap)} day${if (gap == 1) "" else "s"} " +
                    "$side date recorded for this workshop (${formatAnchorDate(indexToDate(edge))}). " +
                    "No stage is proposed; if the camera's clock was wrong, choose the stage yourself.",
                proposals = emptyList(),
            )
        }
    }

    fun intakeSummary(rows: List<DwPhotoIntakeRow>): DwIntakeSummary {
        val proposed = rows.count { it.proposals.isNotEmpty() }
        return DwIntakeSummary(total = rows.size, proposed = proposed, manual = rows.size - proposed)
    }

    fun appendMediaRef(
        value: JsonElement?,
        ref: String,
        multiple: Boolean,
        maxItems: Int = 0,
    ): JsonElement {
        if (!multiple) return JsonPrimitive(ref)
        val existing = DwValues.list(value)
        if (ref in existing) return JsonArray(existing.map { JsonPrimitive(it) })
        if (existing.size >= dwEffectiveMaxItems(maxItems)) {
            return JsonArray(existing.map { JsonPrimitive(it) })
        }
        return JsonArray((existing + ref).map { JsonPrimitive(it) })
    }

    fun mediaRefFits(value: JsonElement?, ref: String, multiple: Boolean, maxItems: Int = 0): Boolean {
        if (!multiple) return true
        val existing = DwValues.list(value)
        if (ref in existing) return true
        return existing.size < dwEffectiveMaxItems(maxItems)
    }
}

data class DwIntakePhoto(
    val fileName: String,
    val takenAt: String?,
    val takenAtOffset: String? = null,
)

enum class DwAnchorKind { DAY, SPAN }

data class DwWorkshopAnchor(
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val entityKey: String,
    val entityTitle: String,
    val rowKey: String?,
    val rowLabel: String?,
    val fieldLabel: String,
    val kind: DwAnchorKind,
    val start: String,
    val end: String,
)

enum class DwProposalBasis {
    ON_DAY,

    IN_SPAN,

    NEAREST,
}

data class DwStageProposal(
    val anchor: DwWorkshopAnchor,
    val basis: DwProposalBasis,
    val daysAway: Int,
    val evidence: String,
)

data class DwPhotoStamp(
    val date: String,
    val minutes: Int,
    val raw: String,
    val shiftedFrom: String?,
)

data class DwPhotoIntakeRow(
    val fileName: String,
    val stamp: DwPhotoStamp?,
    val refusal: String?,
    val proposals: List<DwStageProposal>,
)

data class DwPhotoTarget(
    val fieldKey: String,
    val fieldLabel: String,
    val multiple: Boolean,
    val maxItems: Int = 0,
)

data class DwIntakeSummary(val total: Int, val proposed: Int, val manual: Int)

data class DwStageData(
    val singleton: DwDataRow = emptyMap(),
    val collections: Map<String, List<DwDataRow>> = emptyMap(),
)

fun dwStageDataFrom(schema: SchemaResponse, draft: WorkshopDraft?): Map<String, DwStageData> {
    if (draft == null) return emptyMap()
    val out = LinkedHashMap<String, DwStageData>()
    for (stage in schema.stages) {
        val stored = draft.stages[stage.key] ?: continue
        out[stage.key] = DwStageData(
            singleton = stored.values,
            collections = stage.collections.associate { entity ->
                entity.key to stored.rowsFor(entity.key).map { it.codeRow() }
            },
        )
    }
    return out
}
