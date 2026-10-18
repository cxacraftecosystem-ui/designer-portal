package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

/*
 * C11 (owner decision, 2026-10-17): the designer imports their own workshop's artisan list on stage 3
 * — the handset's twin of the web's `RosterImportPanel` over the three `/design-workshops/{id}/roster` routes. The
 * wire shapes are the server's `run_artisan_import` / `artisan_import_history` answers, shared with
 * the officers' door; the counts and every problem sentence are printed as the server wrote them
 * (identity numbers arrive already masked). The workbook is read once by the server and never stored.
 */

/** The stage the import sits on — stage 3, where the participant roster is built. */
const val DW_PARTICIPANTS_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"

@Serializable
data class DwImportProblemDto(
    val sheet: String = "",
    /** The 1-based worksheet row; null when the problem is about the sheet rather than a row. */
    val row: Int? = null,
    /** "error" — nothing created for that row; "warning" — imported, something assumed. */
    val severity: String = "error",
    val reason: String = "",
    /** The offending cell, already masked by the server. */
    val value: String? = null,
)

@Serializable
data class DwArtisanImportReportDto(
    val importId: String = "",
    val sheet: String? = null,
    val rowsRead: Int = 0,
    val artisansCreated: Int = 0,
    val artisansLinked: Int = 0,
    val rowsRefused: Int = 0,
    val participantsCreated: Int = 0,
    val problems: List<DwImportProblemDto> = emptyList(),
)

@Serializable
data class DwArtisanImportDto(
    val id: String = "",
    val sourceFilename: String? = null,
    val sheetName: String? = null,
    val rowsRead: Int = 0,
    val artisansCreated: Int = 0,
    val artisansLinked: Int = 0,
    val rowsRefused: Int = 0,
    val participantsCreated: Int = 0,
    val problems: List<DwImportProblemDto> = emptyList(),
    val uploadedBy: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class DwArtisanImportListDto(val imports: List<DwArtisanImportDto> = emptyList())

/** Every count, zeroes included, in the web's order — the five have to add up for a reader. */
fun dwRosterTallies(report: DwArtisanImportReportDto): List<Pair<String, Int>> = listOf(
    "rows read" to report.rowsRead,
    "artisans created" to report.artisansCreated,
    "already recorded, linked to this workshop" to report.artisansLinked,
    "rows not imported" to report.rowsRefused,
    "participants added to stage 3" to report.participantsCreated,
)

/** One problem's heading: the row as Excel's gutter shows it, or the sheet when it is about the sheet. */
fun dwRosterProblemWhere(problem: DwImportProblemDto): String =
    if (problem.row != null) "${problem.sheet} · row ${problem.row}" else problem.sheet

/** One earlier upload, as the web's history line reads. */
fun dwRosterHistoryLine(entry: DwArtisanImportDto): String = buildString {
    entry.createdAt?.take(10)?.takeIf { it.length == 10 }?.let { append(it).append(" · ") }
    entry.uploadedBy?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
    append("${entry.rowsRead} rows read · ${entry.artisansCreated} created · ")
    append("${entry.artisansLinked} linked · ${entry.rowsRefused} not added")
}
