package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

@Serializable
data class QFormProblemDto(
    val sheet: String? = null,
    val row: Int? = null,
    val severity: String = "warning",
    val reason: String = "",
    val value: String? = null,
)

@Serializable
data class QFormDetailDto(
    val action: String = "",
    val questionId: String? = null,
    val replacementId: String? = null,
    val before: String? = null,
    val after: String? = null,
    val reason: String = "",
)

@Serializable
data class QFormProvenanceDto(
    val action: String = "",
    val sourceQuestionnaireId: String? = null,
    val answersImported: Int = 0,
    val answersSkipped: Int = 0,
    val entriesCreated: Int = 0,
    val reason: String = "",
)

@Serializable
data class QFormChangeReportDto(
    val created: Int = 0,
    val updated: Int = 0,
    val superseded: Int = 0,
    val retired: Int = 0,
    val removed: Int = 0,
    val unchanged: Int = 0,
    val sections: Int = 0,
    val entriesCreated: Int = 0,
    val answersImported: Int = 0,
    val answersSkipped: Int = 0,
    val kept: Int = 0,
    val answersNotImported: Int = 0,
    val provenance: QFormProvenanceDto? = null,
    val versionBefore: Int = 0,
    val versionAfter: Int = 0,
    val problems: List<QFormProblemDto> = emptyList(),
    val details: List<QFormDetailDto> = emptyList(),
)

@Serializable
data class QFormUploadResultDto(
    val questionnaire: CustomQuestionnaireDto,
    val report: QFormChangeReportDto = QFormChangeReportDto(),
)

enum class DwQuestionnaireArtefact {
    PRO_FORMA,

    QUESTION_SET,

    FULL_WORKBOOK,
}

fun artefactCarriesRespondents(artefact: DwQuestionnaireArtefact): Boolean =
    artefact == DwQuestionnaireArtefact.FULL_WORKBOOK

fun artefactContents(artefact: DwQuestionnaireArtefact): String = when (artefact) {
    DwQuestionnaireArtefact.PRO_FORMA ->
        "A blank workbook to type your questions into."
    DwQuestionnaireArtefact.QUESTION_SET ->
        "Questions only — no answers, no respondents. Send this to another designer — it creates their own questionnaire."
    DwQuestionnaireArtefact.FULL_WORKBOOK ->
        "Everything: questions, sittings, names and answers. Share only with people who need the data."
}

const val QFORM_ANSWERS_NOT_IMPORTED = "answersNotImported"

const val QFORM_ANSWERS_IMPORTED = "answersImported"

const val QFORM_REUSED = "reused"

fun qFormUploadSummary(report: QFormChangeReportDto): String {
    val parts = listOfNotNull(
        report.created.takeIf { it > 0 }?.let { "${plural(it, "question")} added" },
        report.updated.takeIf { it > 0 }?.let { "$it edited" },
        report.superseded.takeIf { it > 0 }?.let { "$it superseded" },
        report.retired.takeIf { it > 0 }?.let { "$it retired" },
        report.removed.takeIf { it > 0 }?.let { "$it removed" },
        report.kept.takeIf { it > 0 }?.let { "$it left out of the file and kept" },
        report.answersNotImported.takeIf { it > 0 }?.let { "${plural(it, "answer")} in the file not imported" },
        report.sections.takeIf { it > 0 }?.let { plural(it, "section") },
        report.entriesCreated.takeIf { it > 0 }?.let { "${plural(it, "sitting")} recorded" },
        report.unchanged.takeIf { it > 0 }?.let { "$it unchanged" },
    )
    return if (parts.isEmpty()) {
        "Read; nothing needed changing."
    } else {
        parts.joinToString(", ")
    }
}

data class QFormProvenanceNotice(
    val warn: Boolean,
    val heading: String,
    val reason: String,
    val tally: String?,
)

fun qFormProvenanceNotice(report: QFormChangeReportDto): QFormProvenanceNotice? {
    val provenance = report.provenance ?: return null

    if (provenance.action == QFORM_REUSED) {
        return QFormProvenanceNotice(
            warn = false,
            heading = "Questions copied; no answers.",
            reason = provenance.reason,
            tally = null,
        )
    }

    val skipped = provenance.action == QFORM_ANSWERS_NOT_IMPORTED
    val count = if (skipped) {
        provenance.answersSkipped.takeIf { it > 0 } ?: report.answersSkipped
    } else {
        provenance.answersImported.takeIf { it > 0 } ?: report.answersImported
    }
    val entries = provenance.entriesCreated.takeIf { it > 0 } ?: report.entriesCreated
    return QFormProvenanceNotice(
        warn = skipped,
        heading = if (skipped) "The answers in this file were NOT imported" else "The answers in this file were imported",
        reason = provenance.reason,
        tally = when {
            skipped && count > 0 -> "${plural(count, "answer")} left where ${if (count == 1) "it already is" else "they already are"}"
            !skipped && count > 0 && entries > 0 -> "${plural(count, "answer")} in ${plural(entries, "sitting")}, credited to you"
            !skipped && count > 0 -> "${plural(count, "answer")}, attributed to you"
            else -> null
        },
    )
}

fun qFormProblemsToShow(report: QFormChangeReportDto): List<QFormProblemDto> {
    val duplicated = report.provenance?.reason?.trim().orEmpty()
    if (duplicated.isEmpty()) return report.problems
    return report.problems.filterNot { it.reason.trim() == duplicated }
}

fun qFormProblemLine(problem: QFormProblemDto): String {
    val where = listOfNotNull(
        problem.sheet?.takeIf { it.isNotBlank() },
        problem.row?.let { "row $it" },
    ).joinToString(" · ")
    val value = problem.value?.takeIf { it.isNotBlank() }?.let { " (“$it”)" }.orEmpty()
    return if (where.isBlank()) problem.reason + value else "$where — ${problem.reason}$value"
}

fun filenameFromContentDisposition(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val extended = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.get(1)?.trim()
    val fromExtended = extended?.substringAfter("''", missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
    val plain = Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        .find(header)?.groupValues?.get(1)
        ?: Regex("""filename\s*=\s*([^;"]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.trim()
    return safeDownloadName(fromExtended ?: plain)
}

internal fun safeDownloadName(raw: String?): String? {
    val cleaned = raw?.trim()
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.replace(Regex("""[\x00-\x1f]"""), "")
        ?.trim()
        ?.trim('.')
        ?: return null
    return cleaned.takeIf { it.isNotEmpty() }?.take(120)
}
