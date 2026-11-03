package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.ReadCache
import com.designprototype.workshop.data.readCacheNotice
import com.designprototype.workshop.data.DW_REPORT_DIFF_LIMITS_TITLE
import com.designprototype.workshop.data.DW_REPORT_DIFF_TITLE
import com.designprototype.workshop.data.DW_REPORT_DIFF_WRITTEN_NOT_CHANGED
import com.designprototype.workshop.data.DW_REPORT_HISTORY_EMPTY_BODY
import com.designprototype.workshop.data.DW_REPORT_HISTORY_EMPTY_TITLE
import com.designprototype.workshop.data.DW_REPORT_HISTORY_LIST_TITLE
import com.designprototype.workshop.data.DW_REPORT_HISTORY_LOCAL_ONLY
import com.designprototype.workshop.data.DW_REPORT_HISTORY_NONE_DATED
import com.designprototype.workshop.data.DW_REPORT_HISTORY_ONE_FILE
import com.designprototype.workshop.data.DW_REPORT_HISTORY_SUBTITLE
import com.designprototype.workshop.data.DW_REPORT_HISTORY_TITLE
import com.designprototype.workshop.data.DwExportRecordDto
import com.designprototype.workshop.data.DwReportDiff
import com.designprototype.workshop.data.DwReportHistoryDto
import com.designprototype.workshop.data.DwStageChange
import com.designprototype.workshop.data.ReportTemplateDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwDiffExports
import com.designprototype.workshop.data.dwDiffHeadline
import com.designprototype.workshop.data.dwDiffLimits
import com.designprototype.workshop.data.dwDuplicateFileNote
import com.designprototype.workshop.data.dwExportMoment
import com.designprototype.workshop.data.dwExportOptionLabel
import com.designprototype.workshop.data.dwExportSize
import com.designprototype.workshop.data.dwFileFacts
import com.designprototype.workshop.data.dwGenerationLabel
import com.designprototype.workshop.data.dwGenerationOf
import com.designprototype.workshop.data.dwGenerationsAreAbsolute
import com.designprototype.workshop.data.dwHeaderVerdict
import com.designprototype.workshop.data.dwInGenerationOrder
import com.designprototype.workshop.data.dwReportHistoryFailure
import com.designprototype.workshop.data.dwSameFileAs
import com.designprototype.workshop.data.dwStageCompletenessNote
import com.designprototype.workshop.data.dwStagesTouchedSince
import com.designprototype.workshop.data.dwStaleSinceNote
import com.designprototype.workshop.data.isConnectionFailure
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import retrofit2.HttpException

@Composable
fun DwReportHistoryScreen(
    repository: WorkshopRepository,
    workshopId: String,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    var loading by remember(workshopId) { mutableStateOf(true) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var history by remember(workshopId) { mutableStateOf<DwReportHistoryDto?>(null) }
    var registry by remember(workshopId) { mutableStateOf<SchemaResponse?>(null) }
    var templates by remember(workshopId) { mutableStateOf<List<ReportTemplateDto>>(emptyList()) }
    var loadError by remember(workshopId) { mutableStateOf<String?>(null) }
    var onServer by remember(workshopId) { mutableStateOf(true) }
    var leftId by remember(workshopId) { mutableStateOf("") }
    var rightId by remember(workshopId) { mutableStateOf("") }
    var cachedNote by remember(workshopId) { mutableStateOf<String?>(null) }

    LaunchedEffect(workshopId, reload) {
        loading = true
        loadError = null

        val remoteId = WorkshopDraftStore.load(appContext, workshopId)?.remoteId
            ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
        if (remoteId == null) {
            onServer = false
            loading = false
            return@LaunchedEffect
        }
        onServer = true

        runCatching { repository.designWorkshopSchema(appContext) }.onSuccess { registry = it }
        runCatching { repository.designWorkshopTemplates(appContext) }.onSuccess { templates = it }

        cachedNote = null
        runCatching {
            ReadCache.read(appContext, "report-history:$remoteId", DwReportHistoryDto.serializer()) {
                repository.designWorkshopReportHistory(remoteId)
            }.also { read -> cachedNote = if (read.fromCache) readCacheNotice(read.cachedAt) else null }.value
        }
            .onSuccess { loaded ->
                history = loaded
                loadError = null
                val ordered = dwInGenerationOrder(loaded)
                if (ordered.size >= 2) {
                    leftId = ordered[ordered.size - 2].id
                    rightId = ordered[ordered.size - 1].id
                }
            }
            .onFailure { error ->
                history = null
                loadError = dwReportHistoryFailure(
                    unreachable = repository.isConnectionFailure(error),
                    status = (error as? HttpException)?.code(),
                    served = error.apiErrorMessage("").takeIf { it.isNotBlank() && !it.startsWith("HTTP ") },
                )
            }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            DW_REPORT_HISTORY_TITLE,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(DW_REPORT_HISTORY_SUBTITLE, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)

        if (!onServer) {
            Text(DW_REPORT_HISTORY_LOCAL_ONLY, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading the history…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        val served = history
        cachedNote?.let { com.designprototype.workshop.ui.CachedCopyNote(it) }
        if (served == null) {
            Text(
                loadError ?: "The report history could not be read.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
            return@Column
        }

        val ordered = remember(served) { dwInGenerationOrder(served) }
        val absolute = remember(served) { dwGenerationsAreAbsolute(served) }

        if (served.exports.isEmpty()) {
            Text(
                DW_REPORT_HISTORY_EMPTY_TITLE,
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium
            )
            Text(DW_REPORT_HISTORY_EMPTY_BODY, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        val stageNumber: (String) -> Int = { key ->
            registry?.stages?.firstOrNull { it.key == key }?.number ?: 999
        }
        val stageTitle: (String) -> String = { key ->
            val stage = registry?.stages?.firstOrNull { it.key == key }
            if (stage != null && stage.title.isNotBlank()) "${stage.number}. ${stage.title}" else key
        }
        val templateName: (String) -> String = { id ->
            templates.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id
        }

        val diff = remember(served, leftId, rightId) {
            if (leftId.isNotBlank() && rightId.isNotBlank() && leftId != rightId) {
                dwDiffExports(served, leftId, rightId)
            } else {
                null
            }
        }
        val staleAfterNewest = remember(served, ordered) {
            if (ordered.isEmpty()) emptyList<String>() else dwStagesTouchedSince(served, ordered.last().id)
        }

        DwDiffPanel(
            history = served,
            diff = diff,
            ordered = ordered,
            absolute = absolute,
            leftId = leftId,
            rightId = rightId,
            onLeft = { leftId = it },
            onRight = { rightId = it },
            stageTitle = stageTitle,
            stageNumber = stageNumber,
            templateName = templateName,
        )

        Text(
            DW_REPORT_HISTORY_LIST_TITLE,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            buildString {
                append("Newest first. ${served.exports.size} file")
                append(if (served.exports.size == 1) "" else "s")
                append(" recorded.")
                if (served.exportsTruncated) append(" Only the most recent 100 are shown.")
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        served.exports.forEach { record ->
            DwExportCard(
                record = record,
                generation = dwGenerationOf(served, record.id),
                absolute = absolute,
                windowTruncated = served.exportsTruncated,
                templateName = templateName(record.templateId),
                duplicates = dwSameFileAs(served, record.id).map { dwGenerationOf(served, it.id) },
                staleStages = if (ordered.isNotEmpty() && record.id == ordered.last().id) {
                    staleAfterNewest.size
                } else {
                    0
                },
            )
        }
    }
}

@Composable
private fun DwDiffPanel(
    history: DwReportHistoryDto,
    diff: DwReportDiff?,
    ordered: List<DwExportRecordDto>,
    absolute: Boolean,
    leftId: String,
    rightId: String,
    onLeft: (String) -> Unit,
    onRight: (String) -> Unit,
    stageTitle: (String) -> String,
    stageNumber: (String) -> Int,
    templateName: (String) -> String,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                DW_REPORT_DIFF_TITLE,
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium
            )

            if (ordered.isEmpty()) {
                Text(DW_REPORT_HISTORY_NONE_DATED, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            if (ordered.size < 2) {
                Text(DW_REPORT_HISTORY_ONE_FILE, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            val options = remember(ordered, history) {
                ordered.asReversed().map { record ->
                    SelectOption(
                        value = record.id,
                        label = dwExportOptionLabel(record, dwGenerationOf(history, record.id)),
                        hint = record.generatedByName ?: "Account no longer exists",
                    )
                }
            }

            SearchableSelectField(
                label = "Compare",
                options = options,
                selectedValue = leftId,
                includeNone = false,
                onSelect = onLeft,
            )
            SearchableSelectField(
                label = "With",
                options = options,
                selectedValue = rightId,
                includeNone = false,
                onSelect = onRight,
            )

            if (leftId == rightId) {
                Text("Choose two different files.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            if (diff == null) return@Column

            val touched = diff.touchedStageKeys.mapNotNull { diff.byStage[it] }
                .sortedBy { stageNumber(it.stageKey) }
            val untouched = diff.untouchedStageKeys.mapNotNull { diff.byStage[it] }
                .sortedBy { stageNumber(it.stageKey) }

            Text(
                dwDiffHeadline(diff, absolute = absolute, windowTruncated = history.exportsTruncated),
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            dwFileFacts(diff, templateName).forEach { fact ->
                Text("• $fact", color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
            }

            dwHeaderVerdict(diff)?.let { verdict ->
                Text(
                    verdict,
                    color = if (diff.headerRowWritten == false) {
                        MaterialTheme.field.body
                    } else {
                        MaterialTheme.field.muted
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            touched.forEach { stage ->
                DwTouchedStageRow(stage = stage, title = stageTitle(stage.stageKey))
            }
            if (touched.isNotEmpty()) {
                Text(
                    DW_REPORT_DIFF_WRITTEN_NOT_CHANGED,
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (untouched.isNotEmpty()) {
                Text(
                    if (diff.timelineComplete) {
                        "Identical in both files"
                    } else {
                        "Nothing written (timeline capped)"
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                untouched.forEach { stage ->
                    Column {
                        Text(stageTitle(stage.stageKey), color = MaterialTheme.field.body, style = MaterialTheme.typography.bodyMedium)
                        dwStageCompletenessNote(stage, history.completeness[stage.stageKey])?.let { note ->
                            Text(note, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.field.hairline)
            Text(
                DW_REPORT_DIFF_LIMITS_TITLE,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            dwDiffLimits(diff, history).forEach { limit ->
                Text("• $limit", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwTouchedStageRow(stage: DwStageChange, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (stage.rowsAdded > 0) DwHistoryChip("${stage.rowsAdded} added", tone = DwChipTone.Good)
            if (stage.rowsRewritten > 0) DwHistoryChip("${stage.rowsRewritten} rewritten", tone = DwChipTone.Warn)
            if (stage.rowsRemoved > 0) DwHistoryChip("${stage.rowsRemoved} removed", tone = DwChipTone.Warn)
        }
        if (stage.rowsTransient > 0) {
            Text(
                "${stage.rowsTransient} row${if (stage.rowsTransient == 1) "" else "s"} added and " +
                    "removed again between the two files, so neither document contains " +
                    (if (stage.rowsTransient == 1) "it" else "them"),
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwExportCard(
    record: DwExportRecordDto,
    generation: Int,
    absolute: Boolean,
    windowTruncated: Boolean,
    templateName: String,
    duplicates: List<Int>,
    staleStages: Int,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                dwGenerationLabel(generation, absolute = absolute, windowTruncated = windowTruncated),
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DwHistoryChip(record.format, tone = DwChipTone.Neutral)
                DwHistoryChip(
                    if (record.generatedOnDevice) "Made on a phone, offline" else "Made online",
                    tone = if (record.generatedOnDevice) DwChipTone.Warn else DwChipTone.Neutral,
                )
            }
            Text(dwExportMoment(record.generatedAt), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)

            DwFact("Template", templateName)
            DwFact("Pages", record.pageCount?.toString() ?: "Not recorded")
            DwFact("Size", dwExportSize(record.fileSizeBytes))
            DwFact("Generated by", record.generatedByName ?: "Account no longer exists")

            var showFingerprint by remember(record.id) { mutableStateOf(false) }
            ActionRow {
                TextButton(onClick = { showFingerprint = !showFingerprint }) {
                    Text(if (showFingerprint) "Hide file fingerprint" else "File fingerprint")
                }
            }
            if (showFingerprint) {
                Text("SHA-256 of the file", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                DwChecksum(value = record.checksumSha256, onDevice = record.generatedOnDevice)
            }

            Text(
                "File name: ${record.fileName}",
                color = MaterialTheme.field.placeholder,
                style = MaterialTheme.typography.bodySmall
            )

            dwDuplicateFileNote(duplicates)?.let { note -> DwWarnNote(note) }
            dwStaleSinceNote(staleStages)?.let { note -> DwWarnNote(note) }

            record.warnings?.takeIf { it.isNotBlank() }?.let { warnings ->
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Text(
                    "Warnings recorded when this file was made",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(warnings, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DwChecksum(value: String?, onDevice: Boolean) {
    if (value.isNullOrBlank()) {
        Text(
            "No checksum recorded." +
                if (onDevice) {
                    " Made with an earlier app version."
                } else {
                    ""
                },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    SelectionContainer {
        Text(
            value,
            color = MaterialTheme.field.body,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun DwFact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(2f))
    }
}

@Composable
private fun DwWarnNote(text: String) {
    Text(
        text,
        color = MaterialTheme.field.onWarningContainer,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.warningContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

private enum class DwChipTone { Neutral, Warn, Good }

@Composable
private fun DwHistoryChip(label: String, tone: DwChipTone) {
    val background: Color = when (tone) {
        DwChipTone.Neutral -> MaterialTheme.field.surface200
        DwChipTone.Warn -> MaterialTheme.field.warningContainer
        DwChipTone.Good -> MaterialTheme.field.successContainer
    }
    val ink: Color = when (tone) {
        DwChipTone.Neutral -> MaterialTheme.field.body
        DwChipTone.Warn -> MaterialTheme.field.onWarningContainer
        DwChipTone.Good -> MaterialTheme.field.onSuccessContainer
    }
    Text(
        label,
        color = ink,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
