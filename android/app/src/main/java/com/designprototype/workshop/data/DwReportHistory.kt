package com.designprototype.workshop.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/**
 * WHAT CHANGED IN THE RECORD BETWEEN TWO GENERATED REPORTS — and, just as importantly, what cannot
 * be known from what is stored. The handset's port of `frontend/lib/reportDiff.ts`.
 *
 * ── THE QUESTION THIS ANSWERS ────────────────────────────────────────────────────────────────────
 *
 * A report goes to a ministry and comes back for revision three or four times. Months later a
 * reviewer asks "did you update the cost sheet before you resubmitted?" and until the web's history
 * page shipped the system had no answer at all: four files existed, each with a checksum, and
 * nothing said what was different about them. The handset had no answer for longer — the endpoint
 * (`GET /design-workshops/{id}/report-history`) is client-agnostic and the arithmetic is pure, so
 * the only thing missing on this surface was a client. This file and [DwReportHistoryApi] are it.
 *
 * ── WHY IT IS NOT A DIFF OF TWO DOCUMENTS ────────────────────────────────────────────────────────
 *
 * Comparing the .docx bytes would answer a question nobody asked. Two files generated an hour apart
 * differ in their generation date, their embedded photo ordering and, if the accent colour was
 * changed, in every heading — none of which is a change to the fieldwork. What a reviewer means by
 * "did it change" is whether the RECORD changed, so this works on the record's own timestamps.
 *
 * ── WHAT IS GENUINELY KNOWABLE, AND WHAT IS NOT. Read this before extending anything below ───────
 *
 * NO SNAPSHOT OF THE STAGE DATA IS KEPT AT EXPORT TIME. `DwReportExport` records the file — its
 * checksum, size, page count, template and registry version — and not one field of what the file
 * said. Every stage row carries `createdAt` / `updatedAt` / `deletedAt`, and that is the entire
 * evidence base. So:
 *
 *   KNOWABLE, and stated as fact:
 *     * whether a stage was touched at all between two exports — and therefore, when it was not,
 *       that the two files carried IDENTICAL data for that stage. That is a proof, not a guess, and
 *       it is the honest answer to "did you change the cost sheet": no, provably not.
 *     * how many rows were added, REWRITTEN, or removed, per stage.
 *     * whether the workshop header (title, craft, cluster, dates — the cover page) was left alone.
 *       Only in that direction: see [DwReportDiff.headerRowWritten] for why the positive case cannot
 *       be told apart from an ordinary stage save.
 *     * whether the two files are byte-identical, from the checksums the records already carry.
 *     * whether anything has been edited SINCE a file was generated, i.e. whether the copy on
 *       somebody's desk is already out of date.
 *
 *   NOT KNOWABLE, and therefore never claimed:
 *     * WHICH FIELD changed, or what it changed from. A rewritten row reports "rewritten", full stop.
 *     * WHETHER A REWRITTEN ROW'S ANSWERS ACTUALLY DIFFER. A stage is saved WHOLE — `save_stage`
 *       issues an update for every row the payload names, without comparing it to what is stored —
 *       so correcting one word in one description stamps every row of that stage with a fresh
 *       `updatedAt`. The count is therefore rows SAVED, never rows whose content differs, and the
 *       vocabulary on screen is "written" rather than "changed" for exactly that reason. This is
 *       also why the strong claim runs the other way: a stage nobody saved is a stage whose data is
 *       provably identical, and that direction has no such loophole.
 *     * how many times a row changed inside a window — `updatedAt` remembers only the last write.
 *     * what a stage's completeness was AT either export. Today's percentage is today's. It is
 *       reported only for stages proven untouched since the earlier file, where today's figure IS
 *       both files' figure; everywhere else it is withheld rather than presented as a delta.
 *     * anything about records the workshop merely POINTS AT — an artisan renamed, a linked product
 *       edited, a photograph's caption changed. Those rows live outside the workshop and carry their
 *       own timestamps, which this payload does not include.
 *
 * ── TWO CLOCKS, AND ONE OF THEM IS THIS PHONE'S ──────────────────────────────────────────────────
 *
 * Stage timestamps are written by the server. An export recorded by `POST /{id}/exports` carries the
 * timestamp the DEVICE reported, because that is when the file was made and the device had no
 * network at the time — and on this surface that device is very often the one reading this screen.
 * A handset whose clock is a day out therefore shifts one end of the window by a day.
 * [DwReportDiff.deviceClockInvolved] says when a comparison rests on a device's clock so the screen
 * can say so, because the alternative is a confident sentence about a window that never existed.
 *
 * ── PURE, AND WHY THAT MATTERS TWICE HERE ────────────────────────────────────────────────────────
 *
 * No Compose, no Retrofit, no storage — the same shape as [DwProvenanceReportDto]'s helpers and
 * `DwMarketAnalysis`, and for the same reason: a designer flipping between generation 1 and
 * generation 4 does it with arithmetic over data already in hand, with no further request on a
 * metered rural connection. The second reason is this file's own correctness. Every decision below
 * is a `when` that a JVM test can reach with no device — `DwReportHistoryTest` mirrors the web's
 * claims case for case — and the alternative, a `when` inside a composable, is only ever exercised
 * by somebody looking at a phone holding a workshop with four exports on it.
 *
 * THE HISTORY ITSELF MUST BE FETCHED. The export table records files made on other devices by other
 * people, so unlike a stage form this cannot be served from the local draft, and the screen says so
 * rather than pretending otherwise. See [DW_REPORT_HISTORY_OFFLINE].
 */

// --------------------------------------------------------------------------------------
// The wire
// --------------------------------------------------------------------------------------

/**
 * One recorded export, exactly as `GET /design-workshops/{id}/report-history` serialises it.
 *
 * A SUPERSET of what `GET /{id}/exports` returns, and the differences are why the screen reads this
 * one: it names WHO generated each file (the column has always been populated and no other endpoint
 * returned it) and it carries the registry version in force at generation.
 */
@Serializable
data class DwExportRecordDto(
    val id: String = "",
    /**
     * THE FILE'S PLACE IN THE WORKSHOP'S WHOLE EXPORT RECORD — one-based, oldest first — as the
     * SERVER computed it. Zero means the field was not on the wire.
     *
     * **IT IS THE SERVER'S TO COMPUTE AND THIS CLIENT MUST NOT PREFER ITS OWN.** The export list is
     * capped at the newest hundred, so a client numbering the files it was sent restarts at 1 on
     * whichever file survived the cut and every "Generation N" on the screen is off by the number of
     * files dropped — off by a different amount each time somebody generates another one, on a
     * number a designer quotes into a covering email to a ministry. The web page derived it that way
     * and `_export_payload` was added to close it.
     *
     * ZERO IS AN ORDINARY STATE AND NOT AN ERROR: a repository one deploy behind this build answers
     * without the field. [dwGenerationOf] then falls back to the position inside the window, which is
     * exactly right until the cap bites and is DISCLOSED on screen the moment it might not be. See
     * [dwGenerationsAreAbsolute].
     */
    val generation: Int = 0,
    val format: String = "",
    val templateId: String = "",
    val fileName: String = "",
    val fileSizeBytes: Long? = null,
    val pageCount: Int? = null,
    /** The SHA-256 of the bytes. THE ONLY THING THAT PROVES TWO FILES ARE THE SAME FILE. */
    val checksumSha256: String? = null,
    /** True when a phone produced the file with no network — see the clock note in the file header. */
    val generatedOnDevice: Boolean = false,
    /** The field registry's digest at generation. A change here means the field list itself moved. */
    val schemaVersion: String? = null,
    val warnings: String? = null,
    val generatedAt: String? = null,
    val generatedById: String? = null,
    /** Null when the account has since been deleted. NEVER substituted with the workshop's owner. */
    val generatedByName: String? = null,
)

/** One stage row's timestamps. No data — see the header for why there is none to have. */
@Serializable
data class DwEntryTimestampDto(
    val id: String = "",
    val stageKey: String = "",
    val entityKey: String = "",
    val ordinal: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Set when the row was removed. A removed cost line is a change, so deleted rows are included. */
    val deletedAt: String? = null,
)

/**
 * The whole history payload.
 *
 * `completeness` reuses [StageCompletenessDto] rather than declaring a fourth shape for the same
 * server dictionary: `workshop_completeness` is the one function every score in this product comes
 * out of, and a second Kotlin spelling of its output is a second thing to drift. It arrives EMPTY
 * when [entriesTruncated] — the server withholds it rather than scoring a truncated set, because a
 * percentage computed over rows that did not fit is not a slightly-off percentage, it is a wrong one
 * that looks exactly like a right one.
 */
@Serializable
data class DwReportHistoryDto(
    val workshopId: String = "",
    /** The workshop header's last write. One timestamp, so it knows only the LAST edit. */
    val workshopUpdatedAt: String? = null,
    /** The server's own clock, because "since" must not be measured against this handset's. */
    val serverTime: String = "",
    val completeness: Map<String, StageCompletenessDto> = emptyMap(),
    val exports: List<DwExportRecordDto> = emptyList(),
    val exportsTruncated: Boolean = false,
    val entries: List<DwEntryTimestampDto> = emptyList(),
    /** When true, no "provably unchanged" claim may be made — rows are missing from the evidence. */
    val entriesTruncated: Boolean = false,
)

// --------------------------------------------------------------------------------------
// The arithmetic
// --------------------------------------------------------------------------------------

/** What happened to one stage's rows inside one window. */
data class DwStageChange(
    val stageKey: String,
    /** In the later file and not the earlier one. */
    val rowsAdded: Int = 0,
    /**
     * In both files, and written in between.
     *
     * SAVED, NOT NECESSARILY DIFFERENT. A stage is saved whole and every row in the payload is
     * updated without comparison, so one corrected word stamps the lot. Never render this as
     * "changed" — see the file header.
     */
    val rowsRewritten: Int = 0,
    /** In the earlier file and not the later one. */
    val rowsRemoved: Int = 0,
    /**
     * Created AND removed inside the window: real work, present in neither file. Counted separately
     * because it is not a difference between the two documents and must not be reported as one — but
     * it is not nothing either, and a designer who remembers doing it deserves to see it acknowledged
     * rather than be told the stage was untouched.
     */
    val rowsTransient: Int = 0,
    /**
     * Something was written to this stage between the two files. NOT "the data differs" — see
     * [rowsRewritten]. Its FALSE is the strong statement: nothing was written, so both files carried
     * identical data for this stage.
     */
    val touched: Boolean = false,
    /**
     * Nothing has touched this stage from the earlier file until now — so today's stage data, and
     * therefore today's completeness score, is exactly what BOTH files carried. This is the only
     * circumstance in which a percentage may honestly be attached to a past export.
     */
    val currentReflectsBoth: Boolean = true,
)

/** The comparison between two recorded exports. Every field is a fact, not an inference. */
data class DwReportDiff(
    val earlier: DwExportRecordDto,
    val later: DwExportRecordDto,
    /** 1-based positions in the workshop's own history, oldest first — "generation 1", "generation 4". */
    val earlierGeneration: Int,
    val laterGeneration: Int,
    /** The window, as ISO strings: `(windowFrom, windowTo]`. */
    val windowFrom: String,
    val windowTo: String,
    /** Only stages that hold rows at all. A stage absent here has never held data in either file. */
    val byStage: Map<String, DwStageChange>,
    /** Written to inside the window. Say "written", not "changed". */
    val touchedStageKeys: List<String>,
    /** Not written to at all, so both files carried identical data for them. The provable half. */
    val untouchedStageKeys: List<String>,
    /**
     * The workshop's OWN row was written inside the window — and read this carefully, because the
     * obvious reading of it is wrong.
     *
     * `save_stage` stamps the workshop row on EVERY stage save (it always writes `schemaVersion`,
     * and promotes stage 1's craft and cluster onto the header), so a true here means "the row was
     * touched", NOT "the cover details were edited". Presenting it as the latter would put a
     * confident sentence about the title and dates on screen every time a designer saved any stage —
     * which is most windows.
     *
     * FALSE, HOWEVER, IS A PROOF: nothing wrote the workshop row, so the title, craft, cluster and
     * dates that print on the cover are identical in both files. That asymmetry is the whole value of
     * the field, and [dwHeaderVerdict] states the two cases differently.
     *
     * Null when the row carries no `updatedAt` at all.
     */
    val headerRowWritten: Boolean?,
    /** Both checksums present and equal: the two files are byte-for-byte the same file. */
    val identicalFile: Boolean,
    /** Both checksums present, so [identicalFile] is a fact either way rather than an absence. */
    val checksumComparable: Boolean,
    val templateChanged: Boolean,
    /** A .docx and a .pdf of the same data are different files by construction, not by revision. */
    val formatDiffers: Boolean,
    /** The registry moved between the two files: a field may have been added, dropped or retyped. */
    val schemaVersionChanged: Boolean,
    val sizeDelta: Long?,
    val pageDelta: Int?,
    /** At least one end of the window is a device's clock reading. Say so on screen. */
    val deviceClockInvolved: Boolean,
    /** False when the entry timeline was capped: no "unchanged" claim may be made from it. */
    val timelineComplete: Boolean,
)

/**
 * Milliseconds since epoch, or null for a missing/unparseable timestamp — the port of the web's
 * `ms()`, which is `Date.parse`.
 *
 * BOTH OFFSET AND INSTANT FORMS, the idiom every other stamp reader in this app uses
 * (`readableStamp`, `dwConsentDay`, `WorkshopSync`'s parser): the server sends `+00:00` and this
 * device writes `Instant.now().toString()`, which ends in `Z`, and both spellings reach this
 * function from the same payload. A shape neither parser recognises is NULL rather than a guess, and
 * every caller below is written so that null means "no claim" rather than "no change": an
 * unreadable `createdAt` makes a row ABSENT (see [dwEntryPresentAt]) instead of present since the
 * beginning of time, and an unreadable `updatedAt` counts as no write rather than as one. Inventing
 * either direction would manufacture a difference between two documents that are identical.
 */
internal fun dwHistoryMillis(iso: String?): Long? {
    val text = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

/**
 * Was this row part of the record at instant [at]?
 *
 * THE WHOLE CLASSIFICATION RESTS ON THIS rather than on "was `updatedAt` inside the window", and the
 * difference is not pedantic: a row created and deleted between two exports has an `updatedAt`
 * squarely inside the window and appears in NEITHER file, so the naive test reports a difference
 * between two documents that are identical in that stage. It is counted as
 * [DwStageChange.rowsTransient] instead.
 */
internal fun dwEntryPresentAt(row: DwEntryTimestampDto, at: Long): Boolean {
    val created = dwHistoryMillis(row.createdAt) ?: return false
    if (created > at) return false
    val deleted = dwHistoryMillis(row.deletedAt) ?: return true
    return deleted > at
}

/**
 * Every stage that holds rows, and what happened to it between two instants.
 *
 * Exported in its own right because "what changed since this file was generated" is the same question
 * with [toIso] set to the server's clock — and that is how a designer learns that the copy already
 * sitting on an officer's desk no longer matches the record. See [dwStagesTouchedSince].
 *
 * A [LinkedHashMap], so the key order is first-row-seen — the same order `Object.values()` walks on
 * the other surface, and the order the screen then re-sorts by stage number.
 */
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
        // Starts with `currentReflectsBoth = true` and is cleared by the first row that has been
        // written since `from`. A stage with rows none of which moved keeps it, which is precisely
        // the claim being made.
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

        // `updatedAt` moves on every write INCLUDING the soft delete, so one comparison covers
        // created, edited and removed. Anything written after `from` — inside the window or after it
        // — means today's data is no longer what the earlier file carried.
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

/** The exports oldest first, dropping any that never recorded when they were generated. */
fun dwInGenerationOrder(history: DwReportHistoryDto): List<DwExportRecordDto> =
    history.exports
        .filter { dwHistoryMillis(it.generatedAt) != null }
        .sortedBy { dwHistoryMillis(it.generatedAt) ?: 0L }

/**
 * Is every generation number on this payload the SERVER'S — the file's place in the whole export
 * record — rather than this client's position inside a window that may have been cut?
 *
 * The screen prints the difference. When this is true, "Generation 7" means the seventh file this
 * workshop ever produced and is safe to quote into a covering email; when it is false and the window
 * was truncated, the number counts only the hundred files that were sent and the label says so. A
 * number that silently means something different from what it did last quarter is worse than no
 * number, which is the defect `_export_payload.generation` was added to close.
 *
 * ALL, not any: one payload comes from one server, so a mixture cannot arise — and if it somehow did,
 * numbering half the list one way and half the other is the one outcome with no honest label.
 */
fun dwGenerationsAreAbsolute(history: DwReportHistoryDto): Boolean =
    history.exports.isNotEmpty() && history.exports.all { it.generation > 0 }

/**
 * One file's generation number, or 0 for a file that has none.
 *
 * THE SERVER'S ANSWER FIRST, ALWAYS. The fallback — the row's position among the dated exports that
 * were actually sent — is what the web page does unconditionally, and it is correct right up to the
 * moment the hundred-file cap bites. Zero for an export that never recorded a generation time: a
 * generation number is a POSITION IN TIME, so a row without one has none, and "Generation 0" would
 * read as one.
 */
fun dwGenerationOf(history: DwReportHistoryDto, exportId: String): Int {
    val record = history.exports.firstOrNull { it.id == exportId } ?: return 0
    if (record.generation > 0) return record.generation
    return dwInGenerationOrder(history).indexOfFirst { it.id == exportId } + 1
}

/**
 * Compare two exports by id, in whichever order they were chosen.
 *
 * Returns null when either id is unknown or either export never recorded a generation time — a
 * window with an open end is not a window, and guessing one would put a confident verdict on a
 * comparison that was never made.
 */
fun dwDiffExports(
    history: DwReportHistoryDto,
    firstId: String,
    secondId: String,
): DwReportDiff? {
    val ordered = dwInGenerationOrder(history)
    val a = ordered.firstOrNull { it.id == firstId } ?: return null
    val b = ordered.firstOrNull { it.id == secondId } ?: return null

    // The designer picks two files; which is EARLIER is a fact about their timestamps, not about the
    // order the two pickers happened to be set in.
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

/**
 * Which stages have been written to since one export was generated — "the file you sent is out of
 * date".
 *
 * Measured against the SERVER's clock, which the history payload carries for exactly this reason: a
 * handset an hour behind would otherwise report an hour of edits as not having happened, or invent
 * an hour of them.
 */
fun dwStagesTouchedSince(history: DwReportHistoryDto, exportId: String): List<String> {
    val record = history.exports.firstOrNull { it.id == exportId } ?: return emptyList()
    if (record.generatedAt.isNullOrBlank()) return emptyList()
    return dwStageChangesBetween(history.entries, record.generatedAt, history.serverTime)
        .values.filter { it.touched }.map { it.stageKey }
}

/**
 * Exports sharing a checksum with this one — the same bytes recorded more than once.
 *
 * Worth surfacing rather than leaving to a reader to spot in sixty-four hex characters: it is how a
 * designer discovers that the "revised" copy they sent was the same file as last time, which is a
 * mistake nothing else in the system would catch.
 */
fun dwSameFileAs(history: DwReportHistoryDto, exportId: String): List<DwExportRecordDto> {
    val record = history.exports.firstOrNull { it.id == exportId } ?: return emptyList()
    val checksum = record.checksumSha256?.takeIf { it.isNotBlank() } ?: return emptyList()
    return history.exports.filter { it.id != record.id && it.checksumSha256 == checksum }
}

// --------------------------------------------------------------------------------------
// The copy
// --------------------------------------------------------------------------------------
//
// EVERY SENTENCE THE SCREEN SAYS IS HERE, as constants and pure functions, for the reason
// `DwProvenanceReport.kt` gives at length: a designer who reads one sentence on a laptop and a
// different one on the handset has been told two things by one product — and this is a screen whose
// whole subject is what may and may not honestly be claimed to a ministry. Each carries the file it
// was copied from, so a reword on either surface has somewhere to look.

/** The screen's own name, and the name of the control that opens it. One constant, so they agree. */
const val DW_REPORT_HISTORY_TITLE = "Report history"

/** `page.tsx`'s `PageHeader description`. */
const val DW_REPORT_HISTORY_SUBTITLE =
    "Every file generated from this workshop, and what changed in the record between any two of them."

/**
 * NO CONNECTION — the honest sentence, in the voice `dwDesignerPickerStandDown` uses.
 *
 * Two halves, and both are load-bearing. The first says WHY this screen alone cannot be served from
 * the device when twenty-two stage forms can: the export table records files made on OTHER devices
 * by OTHER people, so there is no local copy to fall back to and inventing one would show a designer
 * a history missing every file a colleague made. The second says what a designer in a courtyard
 * actually needs to hear — that nothing they captured is at risk — because a screen that says only
 * "cannot be read" beside a fortnight of fieldwork reads as a warning about the fieldwork.
 *
 * `page.tsx`'s offline banner, with "in this browser" swapped for the surface this is.
 */
const val DW_REPORT_HISTORY_OFFLINE =
    "There is no connection, so the report history cannot be read. It lists files generated on " +
        "other devices by other people, so unlike your stages it is not kept on this phone. " +
        "Everything you have captured is still here."

/**
 * A WORKSHOP THAT HAS NEVER LEFT THIS PHONE, and the one sentence on this screen that the browser
 * does not have to say.
 *
 * The web's version explains that no file has been generated because both writers are on the server.
 * On the handset that is not true and saying it would be a lie: [ReportExport] makes a real .docx or
 * .pdf on this device with no network at all, and a designer who did exactly that an hour ago would
 * be told their file does not exist. What is true is narrower and is what this says — the LOG row
 * needs the server's id for the workshop, so `ReportScreen` cannot record one until the workshop has
 * synced, and files made before then are not listed here at all. That is a real gap in the record
 * rather than a missing screen, and hiding it behind the browser's wording would leave a designer
 * believing an export log is complete when it is not.
 */
const val DW_REPORT_HISTORY_LOCAL_ONLY =
    "This workshop has not reached the repository yet, so there is no export log to read. A report " +
        "you generate now is still made on this phone and is still yours — but it is recorded in " +
        "the log only once the workshop has been sent, so anything generated before then will not " +
        "be listed here. Send the workshop from the workshop list first."

/** `page.tsx`'s `EmptyState`, for a synced workshop nobody has generated a file from yet. */
const val DW_REPORT_HISTORY_EMPTY_TITLE = "No report has been generated yet"
const val DW_REPORT_HISTORY_EMPTY_BODY =
    "Generate a .docx or a .pdf from the report page and it will be recorded here — with its " +
        "checksum, its size and who made it — including files this phone produces with no network."

/** Fewer than two files: there is nothing to compare, and an empty comparison would look broken. */
const val DW_REPORT_HISTORY_ONE_FILE =
    "Only one file has been generated, so there is nothing to compare it with yet."

/**
 * FILES EXIST, BUT NONE OF THEM CAN BE PLACED IN TIME.
 *
 * Its own sentence rather than folded into [DW_REPORT_HISTORY_ONE_FILE], because the two states have
 * different remedies and the merged wording is actively misleading: "only one file has been
 * generated" printed under a list of four would send a designer looking for files that are already
 * on the screen in front of them. `generatedAt` is written by the server on every path, so this
 * should be unreachable — which is exactly why it must not borrow another state's words if it is
 * ever reached.
 */
const val DW_REPORT_HISTORY_NONE_DATED =
    "None of the files listed below recorded when it was generated, so no two of them can be " +
        "compared — a comparison is a window between two moments, and these have none."

/** The heading over the comparison, and over the list. */
const val DW_REPORT_DIFF_TITLE = "What changed between two files"
const val DW_REPORT_HISTORY_LIST_TITLE = "Every file generated"

/**
 * The footnote under the touched-stage list. The single most important sentence on the screen after
 * the verdict itself, because it is what stops "3 rewritten" being read as "3 answers changed".
 */
const val DW_REPORT_DIFF_WRITTEN_NOT_CHANGED =
    "A stage is saved whole, so every row it holds is stamped as written even when only one answer " +
        "was corrected. “Rewritten” counts rows saved, not answers that differ."

/** The heading over the block of things this comparison cannot say. */
const val DW_REPORT_DIFF_LIMITS_TITLE = "What this comparison cannot tell you"

/**
 * One export's headline label — "Generation 4", and the caveat when the number is not the record's own.
 *
 * @param generation from [dwGenerationOf]; 0 for an export with no recorded generation time.
 * @param absolute from [dwGenerationsAreAbsolute] — whether the number came from the server.
 * @param windowTruncated the payload's `exportsTruncated`.
 */
fun dwGenerationLabel(generation: Int, absolute: Boolean, windowTruncated: Boolean): String = when {
    // A generation number is a POSITION IN TIME, so a row that never recorded when it was generated
    // has none. It is also the row no comparison can include, which the wording says rather than
    // leaving the reader to discover it in the picker.
    generation <= 0 -> "Undated file — it cannot be compared"
    !absolute && windowTruncated -> "Generation $generation of the 100 most recent"
    else -> "Generation $generation"
}

/** The picker row for one export: which generation, when, and in what format. */
fun dwExportOptionLabel(record: DwExportRecordDto, generation: Int): String =
    "Generation $generation · ${dwExportMoment(record.generatedAt)} · ${record.format}"

/**
 * THE VERDICT. "Written", never "changed" — see the file header, and do not soften it.
 *
 * The untouched clause is attached ONLY when the timeline is complete, because "carried identical
 * data" is a proof and a proof over a truncated set of rows is not one.
 */
fun dwDiffHeadline(
    diff: DwReportDiff,
    absolute: Boolean,
    windowTruncated: Boolean,
): String {
    val touched = diff.touchedStageKeys.size
    val untouched = diff.untouchedStageKeys.size
    // Same caveat as the card's, in the one other place these numbers are printed. Two labels for one
    // arithmetic must never disagree about how honest it is.
    val caveat = if (!absolute && windowTruncated) " (of the 100 most recent)" else ""
    val verdict = if (touched == 0) {
        "no stage of the workshop was written to between these two files."
    } else {
        "$touched stage${if (touched == 1) " was" else "s were"} written to between these two files."
    }
    val identical = if (diff.timelineComplete && untouched > 0) {
        " $untouched other stage${if (untouched == 1) "" else "s"} carried identical data in both."
    } else {
        ""
    }
    return "Generation ${diff.earlierGeneration} → generation ${diff.laterGeneration}$caveat: " +
        "$verdict$identical"
}

/**
 * The cover page's verdict, or null when the workshop row carries no timestamp at all.
 *
 * ASYMMETRIC ON PURPOSE, and the asymmetry is the honest part. `save_stage` stamps the workshop row
 * on every stage save, so "written" cannot be told apart from an ordinary save and must not be
 * reported as "the cover was edited" — that sentence would appear on almost every window and would be
 * wrong on almost all of them. "Not written" is a genuine proof and is worth saying plainly.
 */
fun dwHeaderVerdict(diff: DwReportDiff): String? = when (diff.headerRowWritten) {
    false -> "The cover details — title, craft, cluster and dates — were not written at all between " +
        "these two files, so both carry the same ones."
    true -> "The workshop’s own row was written in this window. Saving any stage stamps that row " +
        "too, so on its own this does not mean the cover details changed."
    null -> null
}

/**
 * The facts that come from the export ROWS rather than from the stage timestamps — checksums,
 * formats, templates, registry version, size and pages.
 *
 * @param templateName resolves a template id to its name; the id is a legible fallback and the
 *   screen passes an identity function when `/templates` could not be read. The template list is
 *   fetched separately and allowed to fail on its own, exactly as the web page does it: a template
 *   name that will not load must not cost the designer the comparison.
 */
fun dwFileFacts(diff: DwReportDiff, templateName: (String) -> String): List<String> {
    val facts = mutableListOf<String>()

    if (diff.identicalFile) {
        facts += "These two files are byte-for-byte identical. Their SHA-256 checksums match, so " +
            "whatever else differs about the record, the documents themselves are the same file."
    } else if (diff.checksumComparable) {
        facts += "The two files differ — their checksums are not the same."
    } else {
        facts += "One of these files recorded no checksum, so the documents cannot be compared by " +
            "their contents."
    }

    if (diff.formatDiffers) {
        facts += "One is a ${diff.earlier.format} and the other a ${diff.later.format}. Two formats " +
            "of the same record are different files by construction, not by revision."
    }
    if (diff.templateChanged) {
        facts += "The template changed: ${templateName(diff.earlier.templateId)} → " +
            "${templateName(diff.later.templateId)}. A template decides which stages a report " +
            "contains, so the documents can differ even where the data did not."
    }
    if (diff.schemaVersionChanged) {
        facts += "The field registry itself moved between these two files, so a field may have been " +
            "added, removed or retyped. Row counts remain comparable; the shape of what a row holds " +
            "may not be."
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

/**
 * What this comparison cannot tell you.
 *
 * ON SCREEN AND NOT IN A COMMENT, because the reader of this panel is about to answer a ministry's
 * question from it. A limit nobody is told about is indistinguishable from a fact.
 */
fun dwDiffLimits(diff: DwReportDiff, history: DwReportHistoryDto): List<String> {
    val limits = mutableListOf(
        "Which field changed, or what it changed from. No copy of the stage data is kept when a " +
            "report is generated — only the file’s checksum, size, pages and template — so a " +
            "rewritten row can be reported as rewritten and no further.",
        "Whether a rewritten row’s answers actually differ. A stage is saved in one write, and " +
            "every row in that save is stamped without being compared to what was stored. A stage " +
            "that nobody saved, on the other hand, is identical in both files with no such caveat.",
        "A row written twice between these two files counts once: each row remembers only when it " +
            "was LAST written.",
        "Records this workshop points at — an artisan’s name, a linked product, a photograph’s " +
            "caption — are not covered. They live outside the workshop and carry their own timestamps.",
    )
    if (diff.deviceClockInvolved) {
        limits += "One of these files was made on a phone with no network, so its timestamp is that " +
            "device’s clock while the stage edits are timed by the repository’s. If the handset’s " +
            "clock was wrong, this window is wrong by the same amount."
    }
    if (!diff.timelineComplete) {
        limits += "The stage timeline was capped, so rows are missing from the evidence and no stage " +
            "above can be called identical — only “nothing written that we can see”."
    }
    if (history.exportsTruncated) {
        limits += if (dwGenerationsAreAbsolute(history)) {
            // The numbers are the record's own, so only the LISTING is short. Saying the numbering is
            // relative here — as the browser does — would be a disclosure of a defect this payload no
            // longer has, and a reader who believed it would stop trusting a number that is correct.
            "Only the most recent 100 files are listed. The generation numbers are the workshop’s " +
                "own, so an older file is simply not shown rather than renumbered."
        } else {
            "Only the most recent 100 files are listed, and the generation numbers above count only " +
                "those hundred — an older file made before them is not generation 0, it is simply " +
                "not here."
        }
    }
    return limits
}

/**
 * "The same document was recorded more than once" — or null when it was not.
 *
 * [generations] is the OTHER files' generation numbers, with any undated ones already dropped: a
 * duplicate a reader cannot go and look at is a sentence with nothing behind it.
 */
fun dwDuplicateFileNote(generations: List<Int>): String? {
    val named = generations.filter { it > 0 }
    if (named.isEmpty()) return null
    return "Byte-for-byte the same file as generation ${named.joinToString(", ")}. The same " +
        "document was recorded more than once — worth checking, if one of them was meant to be a " +
        "revision."
}

/** "The copy on somebody's desk is already out of date", or null when nothing has moved since. */
fun dwStaleSinceNote(stagesTouched: Int): String? {
    if (stagesTouched <= 0) return null
    return "$stagesTouched stage${if (stagesTouched == 1) " has" else "s have"} been written to " +
        "since this file was generated, so any copy already sent may no longer match the record."
}

/**
 * A completeness figure, printed ONLY where it is true of BOTH files.
 *
 * Today's score is today's. It describes a past export only when nothing has been written to the
 * stage since that export was generated — and then it describes both exports, exactly. Everywhere
 * else it is withheld rather than shown with a caveat, because a percentage next to a date is read as
 * that date's percentage no matter what the caveat says. Null score is an ordinary state: a server
 * one deploy behind answers without `completeness`, and the honest result of that is a missing
 * percentage rather than a screen that refuses to draw.
 */
fun dwStageCompletenessNote(change: DwStageChange, score: StageCompletenessDto?): String? {
    if (score == null || !change.currentReflectsBoth) return null
    return "${score.percent}% of its required fields, in both files and still today"
}

/**
 * WHY THE HISTORY COULD NOT BE READ — the one place this screen speaks about the request rather than
 * about the data.
 *
 * A FUNCTION AND NOT A `when` INSIDE THE COMPOSABLE, and its three inputs are already-extracted facts
 * rather than the throwable, for two separate reasons. The first is the reason
 * `dwDesignerPickerStandDown` gives: a decision inside a composable is only ever exercised by
 * somebody looking at a phone. The second is specific — reading an error body CONSUMES it, so
 * `apiErrorMessage` may be called exactly once per failure, and a function that took the throwable
 * and asked twice would hand back an empty string the second time.
 *
 * @param unreachable `WorkshopRepository.isConnectionFailure` — the ONE definition of "the network"
 *   in this app, injected rather than re-decided here so there is no second opinion about it.
 * @param status the HTTP status, or null when the request never got an HTTP answer at all.
 * @param served FastAPI's own `detail`, already unwrapped, or null when the body carried none. A
 *   bare status line ("HTTP 500 Internal Server Error") is not a sentence and the caller filters it
 *   out rather than showing it to a designer.
 */
fun dwReportHistoryFailure(unreachable: Boolean, status: Int?, served: String?): String = when {
    // Told as offline because to the designer it is: nothing was refused and nothing was found
    // missing, the request simply never got an answer. Naming it as anything else sends somebody to
    // look at their signal for a fault the server already named, or the reverse.
    unreachable || status == null -> DW_REPORT_HISTORY_OFFLINE
    // `load_workshop_or_404` answers 404 rather than 403 so an id is not confirmed to somebody
    // entitled to know nothing about it — so this arm covers both "gone" and "not yours", and says
    // the half that is actionable without asserting the half that is not.
    status == 404 -> "This workshop is not on the repository, so it has no export log. Nothing has " +
        "been deleted from this phone."
    status == 403 -> served ?: "This account may not read this workshop’s report history."
    else -> served ?: "The report history could not be read just now. Nothing you have captured is " +
        "affected — this screen only reads."
}

// --------------------------------------------------------------------------------------
// Formatting
// --------------------------------------------------------------------------------------

/**
 * "1.2 MB" — the port of the web's `bytes()` in `lib/format.ts`, same 1024 base and same one-decimal
 * rounding, and the same "-" for a size that was never recorded.
 *
 * [Locale.ROOT] rather than the device's, and this is the one deliberate difference from
 * `DataBrowserScreen.formatBytes`: `toFixed(1)` on the other surface always produces a full stop, so
 * a phone set to a comma-decimal locale would print "1,2 MB" against the browser's "1.2 MB" for one
 * file. A size on this screen is quoted to an office beside a checksum, and two spellings of one
 * number is exactly the drift the rest of this file exists to prevent.
 */
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

/**
 * When a file was generated, in the READER'S OWN TIME ZONE.
 *
 * [readableStamp] and not a fourth private copy of the same three lines — the zone conversion is the
 * whole point and it is the thing this app has already got wrong once (see `shortDay` in
 * `StageSchema.kt`: the server stamps UTC and sends `+00:00`, so reading the date off the offset
 * names the wrong day for a third of every day in Asia/Kolkata). "Time not recorded" rather than a
 * dash for an absent stamp, because on this screen an export with no time is the row no comparison
 * can include and the label is the only place that is said twice.
 */
fun dwExportMoment(iso: String?): String = readableStamp(iso) ?: "Time not recorded"
