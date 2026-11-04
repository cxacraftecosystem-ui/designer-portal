package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

const val DW_PARTICIPANTS_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"

@Serializable
data class DwImportProblemDto(
    val sheet: String = "",
    val row: Int? = null,
    val severity: String = "error",
    val reason: String = "",
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

fun dwRosterTallies(report: DwArtisanImportReportDto): List<Pair<String, Int>> = listOf(
    "rows read" to report.rowsRead,
    "artisans created" to report.artisansCreated,
    "already recorded, linked to this workshop" to report.artisansLinked,
    "rows not imported" to report.rowsRefused,
    "participants added to stage 3" to report.participantsCreated,
)

fun dwRosterProblemWhere(problem: DwImportProblemDto): String =
    if (problem.row != null) "${problem.sheet} · row ${problem.row}" else problem.sheet

fun dwRosterHistoryLine(entry: DwArtisanImportDto): String = buildString {
    entry.createdAt?.take(10)?.takeIf { it.length == 10 }?.let { append(it).append(" · ") }
    entry.uploadedBy?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
    append("${entry.rowsRead} rows read · ${entry.artisansCreated} created · ")
    append("${entry.artisansLinked} linked · ${entry.rowsRefused} not added")
}
