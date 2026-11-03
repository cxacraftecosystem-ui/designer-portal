package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

@Serializable
data class DwExportRecordDto(
    val id: String = "",
    val generation: Int = 0,
    val format: String = "",
    val templateId: String = "",
    val fileName: String = "",
    val fileSizeBytes: Long? = null,
    val pageCount: Int? = null,
    val checksumSha256: String? = null,
    val generatedOnDevice: Boolean = false,
    val schemaVersion: String? = null,
    val warnings: String? = null,
    val generatedAt: String? = null,
    val generatedById: String? = null,
    val generatedByName: String? = null,
)

@Serializable
data class DwEntryTimestampDto(
    val id: String = "",
    val stageKey: String = "",
    val entityKey: String = "",
    val ordinal: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
)

@Serializable
data class DwReportHistoryDto(
    val workshopId: String = "",
    val workshopUpdatedAt: String? = null,
    val serverTime: String = "",
    val completeness: Map<String, StageCompletenessDto> = emptyMap(),
    val exports: List<DwExportRecordDto> = emptyList(),
    val exportsTruncated: Boolean = false,
    val entries: List<DwEntryTimestampDto> = emptyList(),
    val entriesTruncated: Boolean = false,
)

data class DwStageChange(
    val stageKey: String,
    val rowsAdded: Int = 0,
    val rowsRewritten: Int = 0,
    val rowsRemoved: Int = 0,
    val rowsTransient: Int = 0,
    val touched: Boolean = false,
    val currentReflectsBoth: Boolean = true,
)

data class DwReportDiff(
    val earlier: DwExportRecordDto,
    val later: DwExportRecordDto,
    val earlierGeneration: Int,
    val laterGeneration: Int,
    val windowFrom: String,
    val windowTo: String,
    val byStage: Map<String, DwStageChange>,
    val touchedStageKeys: List<String>,
    val untouchedStageKeys: List<String>,
    val headerRowWritten: Boolean?,
    val identicalFile: Boolean,
    val checksumComparable: Boolean,
    val templateChanged: Boolean,
    val formatDiffers: Boolean,
    val schemaVersionChanged: Boolean,
    val sizeDelta: Long?,
    val pageDelta: Int?,
    val deviceClockInvolved: Boolean,
    val timelineComplete: Boolean,
)

internal fun dwHistoryMillis(iso: String?): Long? {
    val text = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

internal fun dwEntryPresentAt(row: DwEntryTimestampDto, at: Long): Boolean {
    val created = dwHistoryMillis(row.createdAt) ?: return false
    if (created > at) return false
    val deleted = dwHistoryMillis(row.deletedAt) ?: return true
    return deleted > at
}

fun dwStageChangesBetween(
    entries: List<DwEntryTimestampDto>,
    fromIso: String?,
    toIso: String?,
): Map<String, DwStageChange> {
    val from = dwHistoryMillis(fromIso)
    val to = dwHistoryMillis(toIso)
    val byStage = LinkedHashMap<String, DwStageChange>()
    if (from == null || to == null) return byStage

    for (row in entries) {
        val change = byStage[row.stageKey] ?: DwStageChange(stageKey = row.stageKey)

        val inEarlier = dwEntryPresentAt(row, from)
        val inLater = dwEntryPresentAt(row, to)
        val written = dwHistoryMillis(row.updatedAt)
        val insideWindow = written != null && written > from && written <= to

        var next = when {
            !inEarlier && inLater -> change.copy(rowsAdded = change.rowsAdded + 1)
            inEarlier && !inLater -> change.copy(rowsRemoved = change.rowsRemoved + 1)
            inEarlier && insideWindow -> change.copy(rowsRewritten = change.rowsRewritten + 1)
            !inEarlier && insideWindow -> change.copy(rowsTransient = change.rowsTransient + 1)
            else -> change
        }

        if (written != null && written > from) next = next.copy(currentReflectsBoth = false)
        byStage[row.stageKey] = next
    }

    for ((key, change) in byStage) {
        byStage[key] = change.copy(
            touched = change.rowsAdded > 0 || change.rowsRewritten > 0 || change.rowsRemoved > 0
        )
    }
    return byStage
}

fun dwInGenerationOrder(history: DwReportHistoryDto): List<DwExportRecordDto> =
    history.exports
        .filter { dwHistoryMillis(it.generatedAt) != null }
        .sortedBy { dwHistoryMillis(it.generatedAt) ?: 0L }

fun dwGenerationsAreAbsolute(history: DwReportHistoryDto): Boolean =
    history.exports.isNotEmpty() && history.exports.all { it.generation > 0 }

fun dwGenerationOf(history: DwReportHistoryDto, exportId: String): Int {
    val record = history.exports.firstOrNull { it.id == exportId } ?: return 0
    if (record.generation > 0) return record.generation
    return dwInGenerationOrder(history).indexOfFirst { it.id == exportId } + 1
}

fun dwDiffExports(
    history: DwReportHistoryDto,
    firstId: String,
    secondId: String,
): DwReportDiff? {
    val ordered = dwInGenerationOrder(history)
    val a = ordered.firstOrNull { it.id == firstId } ?: return null
    val b = ordered.firstOrNull { it.id == secondId } ?: return null

    val aAt = dwHistoryMillis(a.generatedAt) ?: 0L
    val bAt = dwHistoryMillis(b.generatedAt) ?: 0L
    val earlier = if (aAt <= bAt) a else b
    val later = if (aAt <= bAt) b else a

    val windowFrom = earlier.generatedAt.orEmpty()
    val windowTo = later.generatedAt.orEmpty()
    val byStage = dwStageChangesBetween(history.entries, windowFrom, windowTo)

    val headerWritten = dwHistoryMillis(history.workshopUpdatedAt)
    val from = dwHistoryMillis(windowFrom) ?: 0L
    val to = dwHistoryMillis(windowTo) ?: 0L
    val bothChecksums = !earlier.checksumSha256.isNullOrBlank() && !later.checksumSha256.isNullOrBlank()

    return DwReportDiff(
        earlier = earlier,
        later = later,
        earlierGeneration = dwGenerationOf(history, earlier.id),
        laterGeneration = dwGenerationOf(history, later.id),
        windowFrom = windowFrom,
        windowTo = windowTo,
        byStage = byStage,
        touchedStageKeys = byStage.values.filter { it.touched }.map { it.stageKey },
        untouchedStageKeys = byStage.values.filterNot { it.touched }.map { it.stageKey },
        headerRowWritten = headerWritten?.let { it > from && it <= to },
        identicalFile = bothChecksums && earlier.checksumSha256 == later.checksumSha256,
        checksumComparable = bothChecksums,
        templateChanged = earlier.templateId != later.templateId,
        formatDiffers = earlier.format != later.format,
        schemaVersionChanged =
            !earlier.schemaVersion.isNullOrBlank() && !later.schemaVersion.isNullOrBlank() &&
                earlier.schemaVersion != later.schemaVersion,
        sizeDelta = if (earlier.fileSizeBytes != null && later.fileSizeBytes != null) {
            later.fileSizeBytes - earlier.fileSizeBytes
        } else {
            null
        },
        pageDelta = if (earlier.pageCount != null && later.pageCount != null) {
            later.pageCount - earlier.pageCount
        } else {
            null
        },
        deviceClockInvolved = earlier.generatedOnDevice || later.generatedOnDevice,
        timelineComplete = !history.entriesTruncated,
    )
}

fun dwStagesTouchedSince(history: DwReportHistoryDto, exportId: String): List<String> {
    val record = history.exports.firstOrNull { it.id == exportId } ?: return emptyList()
    if (record.generatedAt.isNullOrBlank()) return emptyList()
    return dwStageChangesBetween(history.entries, record.generatedAt, history.serverTime)
        .values.filter { it.touched }.map { it.stageKey }
}

fun dwSameFileAs(history: DwReportHistoryDto, exportId: String): List<DwExportRecordDto> {
    val record = history.exports.firstOrNull { it.id == exportId } ?: return emptyList()
    val checksum = record.checksumSha256?.takeIf { it.isNotBlank() } ?: return emptyList()
    return history.exports.filter { it.id != record.id && it.checksumSha256 == checksum }
}

const val DW_REPORT_HISTORY_TITLE = "Report history"

const val DW_REPORT_HISTORY_SUBTITLE =
    "Every generated file, and what changed between them."

const val DW_REPORT_HISTORY_OFFLINE =
    "Offline — report history needs a connection, because it lists files from other devices by other people. Everything you have captured is still here."

const val DW_REPORT_HISTORY_LOCAL_ONLY =
    "Reports are listed here once the workshop has been uploaded. A report is still made on this phone."

const val DW_REPORT_HISTORY_EMPTY_TITLE = "No report has been generated yet"
const val DW_REPORT_HISTORY_EMPTY_BODY =
    "Generate a .docx or .pdf from the report page to see it here."

const val DW_REPORT_HISTORY_ONE_FILE =
    "Only one file — nothing to compare yet."

const val DW_REPORT_HISTORY_NONE_DATED =
    "These files have no dates, so they can't be compared."

const val DW_REPORT_DIFF_TITLE = "What changed between two files"
const val DW_REPORT_HISTORY_LIST_TITLE = "Every file generated"

const val DW_REPORT_DIFF_WRITTEN_NOT_CHANGED =
    "Stages save whole, so “rewritten” counts rows saved, not answers changed."

const val DW_REPORT_DIFF_LIMITS_TITLE = "What this comparison cannot tell you"

fun dwGenerationLabel(generation: Int, absolute: Boolean, windowTruncated: Boolean): String = when {
    generation <= 0 -> "Undated file — it cannot be compared"
    !absolute && windowTruncated -> "Generation $generation of the 100 most recent"
    else -> "Generation $generation"
}

fun dwExportOptionLabel(record: DwExportRecordDto, generation: Int): String =
    "Generation $generation · ${dwExportMoment(record.generatedAt)} · ${record.format}"

fun dwDiffHeadline(
    diff: DwReportDiff,
    absolute: Boolean,
    windowTruncated: Boolean,
): String {
    val touched = diff.touchedStageKeys.size
    val untouched = diff.untouchedStageKeys.size
    val caveat = if (!absolute && windowTruncated) " (of the 100 most recent)" else ""
    val verdict = if (touched == 0) {
        "no stage of the workshop was written to between these files."
    } else {
        "$touched stage${if (touched == 1) " was" else "s were"} written to between these files."
    }
    val identical = if (diff.timelineComplete && untouched > 0) {
        " $untouched other stage${if (untouched == 1) "" else "s"} carried identical data in both."
    } else {
        ""
    }
    return "Generation ${diff.earlierGeneration} → generation ${diff.laterGeneration}$caveat: " +
        "$verdict$identical"
}

fun dwHeaderVerdict(diff: DwReportDiff): String? = when (diff.headerRowWritten) {
    false -> "Cover details were not written at all between these files."
    true -> "Workshop row re-saved — any stage save does this, so it does not mean the cover details changed."
    null -> null
}

fun dwFileFacts(diff: DwReportDiff, templateName: (String) -> String): List<String> {
    val facts = mutableListOf<String>()

    if (diff.identicalFile) {
        facts += "These two files are byte-for-byte identical (same checksum)."
    } else if (diff.checksumComparable) {
        facts += "Files differ (checksums differ)."
    } else {
        facts += "One file has no checksum, so the documents cannot be compared by their contents."
    }

    if (diff.formatDiffers) {
        facts += "Different formats: ${diff.earlier.format} and ${diff.later.format} — different files by construction, not by revision."
    }
    if (diff.templateChanged) {
        facts += "Template changed: ${templateName(diff.earlier.templateId)} → ${templateName(diff.later.templateId)}."
    }
    if (diff.schemaVersionChanged) {
        facts += "Form layout changed between these files."
    }
    val pages = diff.pageDelta
    if (pages != null && pages != 0) {
        val n = if (pages < 0) -pages else pages
        facts += "$n page${if (n == 1) "" else "s"} ${if (pages > 0) "longer" else "shorter"} " +
            "(${diff.earlier.pageCount} → ${diff.later.pageCount})."
    }
    val size = diff.sizeDelta
    if (size != null && size != 0L) {
        val n = if (size < 0) -size else size
        facts += "${dwExportSize(n)} ${if (size > 0) "larger" else "smaller"} " +
            "(${dwExportSize(diff.earlier.fileSizeBytes)} → ${dwExportSize(diff.later.fileSizeBytes)})."
    }
    return facts
}

fun dwDiffLimits(diff: DwReportDiff, history: DwReportHistoryDto): List<String> {
    val limits = mutableListOf(
        "Which field changed — only the file's checksum, size, pages and template are stored.",
        "Whether a rewritten row's answers actually differ.",
        "Rows written twice count once.",
        "Changes to linked records (artisans, products, photo captions).",
    )
    if (diff.deviceClockInvolved) {
        limits += "One file was made offline, so its time uses that phone’s clock."
    }
    if (!diff.timelineComplete) {
        limits += "The stage timeline was capped, so some rows are missing."
    }
    if (history.exportsTruncated) {
        limits += if (dwGenerationsAreAbsolute(history)) {
            "Only the most recent 100 files are listed."
        } else {
            "Only the most recent 100 files are listed; the numbers count only those hundred."
        }
    }
    return limits
}

fun dwDuplicateFileNote(generations: List<Int>): String? {
    val named = generations.filter { it > 0 }
    if (named.isEmpty()) return null
    return "Byte-for-byte the same file as generation ${named.joinToString(", ")}."
}

fun dwStaleSinceNote(stagesTouched: Int): String? {
    if (stagesTouched <= 0) return null
    return "$stagesTouched stage${if (stagesTouched == 1) " has" else "s have"} been written to since this file — copies sent may be out of date."
}

fun dwStageCompletenessNote(change: DwStageChange, score: StageCompletenessDto?): String? {
    if (score == null || !change.currentReflectsBoth) return null
    return "${score.percent}% of required fields, unchanged"
}

fun dwReportHistoryFailure(unreachable: Boolean, status: Int?, served: String?): String = when {
    unreachable || status == null -> DW_REPORT_HISTORY_OFFLINE
    status == 404 -> "Not available online, so no history. Nothing has been deleted from this phone."
    status == 403 -> served ?: "You can't view this report history."
    else -> served ?: "Couldn't load report history. Nothing you have captured is affected."
}

fun dwExportSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "-"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

fun dwExportMoment(iso: String?): String = readableStamp(iso) ?: "Time not recorded"
