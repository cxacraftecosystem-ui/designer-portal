package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.plural
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.data.userMessage

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_ROW_KEY_SEPARATOR
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwQuestionnaireCache
import com.designprototype.workshop.data.DwQuestionnaireStore
import com.designprototype.workshop.data.DwTranscriptCache
import com.designprototype.workshop.data.DwTranscriptStore
import com.designprototype.workshop.data.dwTranscriptSelection
import com.designprototype.workshop.data.dwTranscriptsPendingLine
import com.designprototype.workshop.report.appendTranscriptAnnexure
import com.designprototype.workshop.report.transcriptAnnexureWarnings
import com.designprototype.workshop.report.transcriptRefusedWarning
import com.designprototype.workshop.data.DwReadinessCheck
import com.designprototype.workshop.data.DwReportFreeze
import com.designprototype.workshop.data.DwSubmissionReadiness
import com.designprototype.workshop.data.DwTier
import com.designprototype.workshop.data.DwReferenceStore
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.dwReportSignOffLines
import com.designprototype.workshop.data.designWorkshopQuestionnaires
import com.designprototype.workshop.data.DwCustomCache
import com.designprototype.workshop.data.DwCustomSectionStore
import com.designprototype.workshop.data.customFieldsForStage
import com.designprototype.workshop.data.customSectionEntity
import com.designprototype.workshop.data.customSectionsForReport
import com.designprototype.workshop.data.dwCustomCopy
import com.designprototype.workshop.data.dwCustomDefinition
import com.designprototype.workshop.data.dwCustomSectionWarnings
import com.designprototype.workshop.data.retiredCustomFieldsWithAnswers
import com.designprototype.workshop.data.undrawableCustomFieldsWithValues
import com.designprototype.workshop.data.dwQuestionnaireCopy
import com.designprototype.workshop.data.dwQuestionnaireWarnings
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.ExportRecordBody
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.ReportTemplateDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.collections
import com.designprototype.workshop.data.computeStageCompleteness
import com.designprototype.workshop.data.computeWorkshopCompleteness
import com.designprototype.workshop.data.dwRefId
import com.designprototype.workshop.data.entityKey
import com.designprototype.workshop.data.field
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.overallPercent
import com.designprototype.workshop.data.rowsFor
import com.designprototype.workshop.data.singleton
import com.designprototype.workshop.report.ACCENT_PRESETS
import com.designprototype.workshop.report.Align
import com.designprototype.workshop.report.CoverBlock
import com.designprototype.workshop.report.DocumentBuilder
import com.designprototype.workshop.report.ImageBlock
import com.designprototype.workshop.report.ImageGridBlock
import com.designprototype.workshop.report.ImageLoader
import com.designprototype.workshop.report.ImageRef
import com.designprototype.workshop.report.MetricRowBlock
import com.designprototype.workshop.report.PageBreakBlock
import com.designprototype.workshop.report.ParaStyle
import com.designprototype.workshop.report.Presentation
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.ReportExport
import com.designprototype.workshop.report.ReportTemplate
import com.designprototype.workshop.report.Run
import com.designprototype.workshop.report.SignatureBlock
import com.designprototype.workshop.report.SpecialSection
import com.designprototype.workshop.report.TableBlock
import com.designprototype.workshop.report.TableColumn
import com.designprototype.workshop.report.TemplateSection
import com.designprototype.workshop.report.TocBlock
import com.designprototype.workshop.report.cleanText
import com.designprototype.workshop.report.fieldCopyNote
import com.designprototype.workshop.report.formatReportDate
import com.designprototype.workshop.report.groupIndian
import com.designprototype.workshop.report.normaliseHex
import com.designprototype.workshop.report.plainRuns
import com.designprototype.workshop.report.resolveAccent
import com.designprototype.workshop.report.resolveTemplateId
import com.designprototype.workshop.report.runsOf
import com.designprototype.workshop.report.settingText
import com.designprototype.workshop.report.submissionLine
import com.designprototype.workshop.report.templateChoices
import com.designprototype.workshop.report.themeFromAccent
import com.designprototype.workshop.report.toPlain
import com.designprototype.workshop.report.toReportBlocks
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

@Composable
fun ReportScreen(
    repository: WorkshopRepository,
    workshopId: String,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()

    var schema by remember(workshopId) { mutableStateOf<SchemaResponse?>(null) }
    var draft by remember(workshopId) { mutableStateOf<WorkshopDraft?>(null) }
    var templates by remember(workshopId) { mutableStateOf<List<ReportTemplateDto>>(emptyList()) }
    var templateId by remember(workshopId) { mutableStateOf("DCH_STANDARD") }
    var warnings by remember(workshopId) { mutableStateOf<List<String>>(emptyList()) }
    var reportChecks by remember(workshopId) { mutableStateOf<List<DwReadinessCheck>>(emptyList()) }
    var percent by remember(workshopId) { mutableStateOf(0) }
    var loading by remember(workshopId) { mutableStateOf(true) }
    var busy by remember(workshopId) { mutableStateOf(false) }
    var result by remember(workshopId) { mutableStateOf<ReportExport.Result?>(null) }
    var exportNotes by remember(workshopId) { mutableStateOf<List<String>>(emptyList()) }
    var previewDocument by remember(workshopId) { mutableStateOf<ReportDocument?>(null) }
    var unresolvedMedia by remember(workshopId) { mutableStateOf(0) }
    var exportLosses by remember(workshopId) { mutableStateOf<List<String>>(emptyList()) }
    var previewAsPages by remember(workshopId) { mutableStateOf(true) }
    var previewUnresolvedMedia by remember(workshopId) { mutableStateOf(0) }
    var previewLosses by remember(workshopId) { mutableStateOf<List<String>>(emptyList()) }
    var accent by remember(workshopId) { mutableStateOf("") }
    var transcriptsAsked by remember(workshopId) { mutableStateOf(false) }
    var transcripts by remember(workshopId) { mutableStateOf<DwTranscriptCache?>(null) }
    var transcriptsPending by remember(workshopId) { mutableStateOf<String?>(null) }
    var deviceOnlyNote by remember(workshopId) { mutableStateOf<String?>(null) }
    var builtFromLine by remember(workshopId) { mutableStateOf("") }
    var serverCopyUnread by remember(workshopId) { mutableStateOf(false) }

    var questionnaires by remember(workshopId) { mutableStateOf<DwQuestionnaireCache?>(null) }
    var customSections by remember(workshopId) { mutableStateOf<DwCustomCache?>(null) }

    LaunchedEffect(workshopId) {
        loading = true
        runCatching {
            val registry = repository.designWorkshopSchema(appContext)
            val local = WorkshopDraftStore.load(appContext, workshopId)
            val remoteId = local?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            val remote = remoteId?.let { runCatching { repository.designWorkshopStages(it) }.getOrNull() }
            val viewerId = repository.cachedUser()?.id
            val heldTranscripts = remote?.let {
                DwTranscriptStore.keep(appContext, workshopId, registry, it.stages, it.transcripts, viewerId)
            } ?: DwTranscriptStore.load(appContext, workshopId, viewerId)
                ?: remoteId?.let { DwTranscriptStore.load(appContext, it, viewerId) }
            val source = reportSourceFor(
                schema = registry,
                workshopId = workshopId,
                local = local,
                remoteId = remoteId,
                remote = remote,
            )
            val stored = source.draft
            deviceOnlyNote = source.deviceOnlyNote
            builtFromLine = source.builtFromLine
            serverCopyUnread = source.serverCopyUnread
            customSections = if (remoteId != null) {
                runCatching { repository.dwCustomDefinition(appContext, workshopId, remoteId) }
                    .getOrNull()
            } else {
                DwCustomSectionStore.load(appContext, workshopId)
            }
            val scores = computeWorkshopCompleteness(registry, stored, customSections)
            schema = registry
            draft = stored
            percent = overallPercent(scores)
            templates = repository.designWorkshopTemplates(appContext).ifEmpty {
                templateChoices().map { ReportTemplateDto(it.id, it.name, it.description) }
            }
            val settings = stored?.stages?.get("REPORT_GENERATION")?.values.orEmpty()
            templateId = resolveTemplateId(null, settings, stored?.templateId.orEmpty())
                .ifEmpty { "DCH_STANDARD" }
            accent = resolveAccent(null, settings).orEmpty()
            transcriptsAsked = com.designprototype.workshop.report.wantsTranscripts(null, settings)
            transcripts = heldTranscripts
            reportChecks = DwSubmissionReadiness.reportChecks(registry, stored, workshopId, scores)
            warnings = scores.filter { it.missing.isNotEmpty() }.map { stage ->
                "Stage ${stage.number} — ${stage.title}: ${plural(stage.missing.size, "required field")} " +
                    "missing (${stage.missing.take(3).joinToString(", ")}" +
                    (if (stage.missing.size > 3) ", …" else "") + ")"
            }
            warnings = warnings + dwCustomSectionWarnings(
                copy = dwCustomCopy(customSections),
                answersHeld = stored?.stages.orEmpty().values.any { stage ->
                    stage.custom.values.any { DwValues.isFilled(it) }
                },
            )

            var held = DwQuestionnaireStore.load(appContext, workshopId, repository.cachedUser()?.id)
                ?: remoteId?.let { DwQuestionnaireStore.load(appContext, it, repository.cachedUser()?.id) }
            if (remoteId != null) {
                runCatching { repository.designWorkshopQuestionnaires(remoteId) }
                    .onSuccess { fetched ->
                        held = DwQuestionnaireStore.store(
                            appContext,
                            fetched.copy(workshopId = workshopId),
                            repository.cachedUser()?.id,
                        )
                    }
            }
            questionnaires = held
            warnings = warnings + dwQuestionnaireWarnings(held)

            if (transcriptsAsked) {
                val selection = dwTranscriptSelection(registry, stored, heldTranscripts)
                transcriptsPending = dwTranscriptsPendingLine(selection.notOnPhone)
                warnings = warnings + listOfNotNull(transcriptRefusedWarning(selection.refused)) +
                    transcriptAnnexureWarnings(selection.items)
            } else {
                transcriptsPending = null
            }

        }.onFailure { onError(it.userMessage("Unable to prepare the report.")) }
        loading = false
    }

    fun buildPreview() {
        val registry = schema ?: return
        busy = true
        scope.launch {
            runCatching {
                val stored = draft
                val plan = reportPlanFor(
                    schema = registry,
                    draft = stored,
                    workshopId = workshopId,
                    requestedTemplateId = templateId,
                    requestedAccent = accent,
                    format = "DOCX",
                    generatedAt = Instant.now().toString(),
                    serverCopyUnread = serverCopyUnread,
                    questionnaires = dwQuestionnaireCopy(questionnaires),
                    customSections = customSections,
                    signOff = DwReportFreeze.signOffFor(workshopId),
                )
                var unresolvedIds: List<String> = emptyList()
                var losses: List<String> = emptyList()
                val document = withContext(Dispatchers.Default) {
                    buildWorkshopDocument(
                        context = appContext,
                        schema = registry,
                        draft = stored,
                        workshopId = workshopId,
                        templateId = templateId,
                        warnings = warnings,
                        accent = accent,
                        format = "DOCX",
                        plan = plan,
                        questionnaires = questionnaires,
                        customSections = customSections,
                        transcripts = transcripts,
                        onUnresolvedMedia = { ids -> unresolvedIds = ids },
                        onReportLosses = { notes -> losses = notes },
                    )
                }
                Triple(document, unresolvedIds.size, losses)
            }.onSuccess { (document, missing, losses) ->
                previewDocument = document
                previewUnresolvedMedia = missing
                previewLosses = losses
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                onError(it.userMessage("Unable to build the preview."))
            }
            busy = false
        }
    }

    fun export(format: String) {
        val registry = schema ?: return
        busy = true
        result = null
        exportLosses = emptyList()
        scope.launch {
            runCatching {
                val stored = draft
                val plan = reportPlanFor(
                    schema = registry,
                    draft = stored,
                    workshopId = workshopId,
                    requestedTemplateId = templateId,
                    requestedAccent = accent,
                    format = format,
                    generatedAt = Instant.now().toString(),
                    serverCopyUnread = serverCopyUnread,
                    questionnaires = dwQuestionnaireCopy(questionnaires),
                    customSections = customSections,
                    signOff = DwReportFreeze.signOffFor(workshopId),
                )
                exportNotes = plan.warnings
                var unresolvedIds: List<String> = emptyList()
                var losses: List<String> = emptyList()
                val document = withContext(Dispatchers.Default) {
                    buildWorkshopDocument(
                        context = appContext,
                        schema = registry,
                        draft = stored,
                        workshopId = workshopId,
                        templateId = templateId,
                        warnings = warnings,
                        accent = accent,
                        format = format,
                        plan = plan,
                        questionnaires = questionnaires,
                        customSections = customSections,
                        transcripts = transcripts,
                        onUnresolvedMedia = { ids -> unresolvedIds = ids },
                        onReportLosses = { notes -> losses = notes },
                    )
                }
                exportLosses = losses
                unresolvedMedia = unresolvedIds.size
                val loader = deviceImageLoader()
                val setup = stored?.stages?.get("WORKSHOP_SETUP")?.values.orEmpty()
                fun setupText(key: String) = DwValues.text(setup[key]).trim().ifBlank { null }
                val reportName = com.designprototype.workshop.data.Naming.workshopFilename(
                    workshopCode = setupText("workshopCode"),
                    title = stored?.title,
                    clusterName = setupText("clusterName"),
                    district = setupText("district"),
                    state = setupText("state"),
                    craftName = setupText("craftName"),
                    what = "Report",
                    ext = if (format == "PDF") "pdf" else "docx",
                )
                val exported = if (format == "PDF") {
                    ReportExport.exportPdf(appContext, repository, document, loader, fileName = reportName)
                } else {
                    ReportExport.exportDocx(appContext, repository, document, loader, fileName = reportName)
                }
                val remoteId = stored?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
                if (remoteId != null) {
                    runCatching {
                        repository.recordDesignWorkshopExport(
                            appContext,
                            remoteId,
                            ExportRecordBody(
                                format = format,
                                templateId = document.meta.templateId,
                                fileName = exported.fileName,
                                generatedAt = Instant.now().toString(),
                                fileSizeBytes = exported.sizeBytes,
                                checksumSha256 = exported.checksumSha256,
                                warnings = (
                                    warnings + plan.warnings + reportChecks.map { it.title } +
                                        listOfNotNull(
                                            unresolvedIds.size.takeIf { it > 0 }
                                                ?.let { unresolvedMediaNote(it) },
                                        ) +
                                        losses
                                    ).joinToString("\n").takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }
                exported
            }.onSuccess { exported ->
                result = exported
                onMessage("Report saved to ${exported.displayLocation}")
            }.onFailure { error ->
                onError(error.userMessage("The report could not be generated."))
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Report", display = true, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text(
                    if (isLocalOnlyWorkshop(workshopId)) "Preparing…"
                    else "Loading this workshop…",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            return@Column
        }

        deviceOnlyNote?.let { DwWorkshopNotice(it) }

        SearchableSelectField(
            label = "Template",
            options = templateOptions(templates),
            selectedValue = templateId,
            includeNone = false,
            onSelect = { picked -> if (picked.isNotBlank()) templateId = picked }
        )

        ReportAccentPicker(accent = accent, onAccent = { accent = it })

        Text(
            "$percent% of required fields filled across ${schema?.stages?.size ?: 0} stages.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        if (builtFromLine.isNotEmpty()) {
            Text(builtFromLine, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
        }

        if (warnings.isEmpty()) {
            Text(
                "Every required field is answered.",
                color = MaterialTheme.field.success,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.warningContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${plural(warnings.size, "stage")} ${if (warnings.size == 1) "is" else "are"} incomplete",
                        color = MaterialTheme.field.onWarningContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "You can still generate; missing fields print blank.",
                        color = MaterialTheme.field.onWarningContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                    warnings.forEach {
                        Text("· $it", color = MaterialTheme.field.onWarningContainer, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (reportChecks.isNotEmpty()) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.warningContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (reportChecks.size == 1) "1 stage-20 setting changes this document"
                        else "${reportChecks.size} stage-20 settings change this document",
                        color = MaterialTheme.field.onWarningContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    reportChecks.forEach { check ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "· ${check.title}",
                                color = MaterialTheme.field.onWarningContainer,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 16.sp,
                            )
                            Text(
                                check.detail,
                                color = MaterialTheme.field.onWarningContainer,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        OutlinedButton(
            onClick = {
                if (previewDocument == null) {
                    buildPreview()
                } else {
                    previewDocument = null
                    previewUnresolvedMedia = 0
                    previewLosses = emptyList()
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (previewDocument == null) "Preview the document" else "Hide the preview")
        }

        previewDocument?.let { document ->
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Text(
                    "Built on this phone — exactly what the export writes.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 15.sp,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(true to "Pages", false to "Reading").forEach { (pages, label) ->
                        val selected = previewAsPages == pages
                        Text(
                            if (selected) "$label ✓" else label,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.field.body
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.field.surface100
                                    },
                                    RoundedCornerShape(999.dp),
                                )
                                .clickable { previewAsPages = pages }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                if (previewUnresolvedMedia > 0) {
                    Text(
                        "· " + unresolvedMediaNote(previewUnresolvedMedia),
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 15.sp,
                    )
                }
                previewLosses.forEach { note ->
                    Text(
                        "· $note",
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 15.sp,
                    )
                }
                val resolvePreviewImage: (String) -> File? =
                    remember { { source: String -> File(source).takeIf { it.exists() } } }

                if (previewAsPages) {
                    DwReportSheets(document = document, resolveImage = resolvePreviewImage)
                } else {
                    DwReportPreview(document = document, resolveImage = resolvePreviewImage)
                }
            }
        }

        transcriptsPending?.takeIf { transcriptsAsked }?.let { line ->
            Text(line, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        ActionRow {
            Button(onClick = { export("DOCX") }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export .docx")
            }
            Button(onClick = { export("PDF") }, enabled = !busy) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export .pdf")
            }
        }

        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Rendering the report on this device…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        result?.let { exported ->
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text("Saved", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(exported.displayLocation, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
                exportNotes.forEach { note ->
                    Text("· $note", color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall)
                }
                exportLosses.forEach { note ->
                    Text(
                        "· $note",
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 15.sp,
                    )
                }
                if (exported.droppedImages.isNotEmpty()) {
                    Text(
                        "${plural(exported.droppedImages.size, "photo")} couldn't be embedded.",
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (unresolvedMedia > 0) {
                    Text(
                        unresolvedMediaNote(unresolvedMedia),
                        color = MaterialTheme.field.warning,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 15.sp,
                    )
                }
                if (exported.shareUri != null) {
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = exported.mimeType
                                putExtra(Intent.EXTRA_STREAM, exported.shareUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share the report"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                } else {
                    Text(
                        "Find it in Downloads.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportAccentPicker(accent: String, onAccent: (String) -> Unit) {
    val normalised = normaliseHex(accent)
    val derived = if (normalised == null) null else themeFromAccent(normalised).accent
    var typed by remember(accent) { mutableStateOf(normalised?.let { "#$it" } ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Report colour", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "One accent colour drives headings, tables and figures. These twelve stay distinct in black and white.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ACCENT_PRESETS.forEach { preset ->
                val selected = normalised == preset.hex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .background(
                            if (selected) MaterialTheme.field.surface100 else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.field.hairline,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onAccent(preset.hex) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Swatch(preset.hex)
                    Text(preset.label, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { raw ->
                    typed = raw
                    normaliseHex(raw)?.let(onAccent)
                },
                label = { Text("Or a hex colour") },
                placeholder = { Text("#1F3864") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (derived != null) Swatch(derived)
        }

        if (normalised != null && derived != null && derived != normalised) {
            Text(
                "Too pale for headings — using #$derived instead.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (normalised == null) {
                    "No colour chosen — using the standard indigo."
                } else {
                    "This export only."
                },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (normalised != null) {
                OutlinedButton(onClick = { onAccent("") }) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun Swatch(hex: String) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(Color(android.graphics.Color.parseColor("#$hex")), RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(4.dp))
    )
}

private fun deviceImageLoader(): ImageLoader = { ref ->
    runCatching { File(ref.source).takeIf { it.exists() }?.readBytes() }.getOrNull()
}

internal fun buildWorkshopDocument(
    context: Context,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    workshopId: String,
    templateId: String,
    warnings: List<String>,
    accent: String,
    format: String = "DOCX",
    plan: ReportPlan? = null,
    questionnaires: DwQuestionnaireCache? = null,
    customSections: DwCustomCache? = null,
    transcripts: DwTranscriptCache? = null,
    onUnresolvedMedia: (List<String>) -> Unit = {},
    onReportLosses: (List<String>) -> Unit = {},
): ReportDocument {
    val mediaById = draft?.media.orEmpty().associateBy { it.id }
    return buildWorkshopDocument(
        format = format,
        plan = plan,
        onUnresolvedMedia = onUnresolvedMedia,
        onReportLosses = onReportLosses,
        schema = schema,
        draft = draft,
        workshopId = workshopId,
        templateId = templateId,
        warnings = warnings,
        accent = accent,
        questionnaires = questionnaires,
        customSections = customSections,
        transcripts = transcripts,
        imageFor = { mediaId ->
            val descriptor = mediaById[mediaId]
            val file = descriptor?.let { WorkshopDraftStore.mediaFile(context, workshopId, it) }
            if (descriptor == null || file == null || !file.exists()) {
                null
            } else {
                ImageRef(
                    source = file.absolutePath,
                    widthPx = descriptor.width,
                    heightPx = descriptor.height,
                    rotationDeg = descriptor.rotationDegrees,
                    mimeType = descriptor.mimeType,
                )
            }
        },
    )
}

internal fun buildWorkshopDocument(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    workshopId: String,
    templateId: String,
    @Suppress("UNUSED_PARAMETER") templateName: String = "",
    warnings: List<String>,
    accent: String,
    imageFor: (String) -> ImageRef?,
    format: String = "DOCX",
    generatedAt: String = Instant.now().toString(),
    plan: ReportPlan? = null,
    questionnaires: DwQuestionnaireCache? = null,
    customSections: DwCustomCache? = null,
    transcripts: DwTranscriptCache? = null,
    onUnresolvedMedia: (List<String>) -> Unit = {},
    onReportLosses: (List<String>) -> Unit = {},
): ReportDocument {
    val resolved = plan ?: reportPlanFor(
        schema = schema,
        draft = draft,
        workshopId = workshopId,
        requestedTemplateId = templateId,
        requestedAccent = accent,
        format = format,
        generatedAt = generatedAt,
        customSections = customSections,
    )
    val template = resolved.template
    val builder = DocumentBuilder(meta = resolved.meta, theme = resolved.theme)
    warnings.forEach { builder.warn(it) }
    resolved.warnings.forEach { builder.warn(it) }

    val refs = DwRefLabels(schema, draft)
    val figures = DwFigures(schema, draft)
    val stages = schema.stages.associateBy { it.key }

    val unresolvedMedia = LinkedHashSet<String>()
    val resolveImage: (String) -> ImageRef? = { token ->
        imageFor(token).also { if (it == null && token.isNotBlank()) unresolvedMedia.add(token) }
    }

    val losses = DwReportLosses()

    template.sections.forEach { section ->
        val special = section.special
        if (special != null) {
            renderSpecialSection(
                builder, special, section, resolved, schema, draft, resolveImage, refs, figures,
                questionnaires, customSections, transcripts,
            )
        } else {
            stages[section.stageKey]?.let { stage ->
                renderStageSection(
                    builder, stage, section, resolved, schema, draft, resolveImage, refs, figures,
                    losses = losses,
                )
            }
        }
    }

    if (unresolvedMedia.isNotEmpty()) {
        builder.warn(unresolvedMediaNote(unresolvedMedia.size))
        if (template.sections.none { it.special == SpecialSection.ANNEXURE_MEDIA }) {
            builder.para(unresolvedMediaNote(unresolvedMedia.size), style = ParaStyle.NOTE)
        }
    }
    onUnresolvedMedia(unresolvedMedia.toList())

    onReportLosses(dwReportLossNotes(schema, draft, resolved.template, losses))

    return builder.build()
}

internal fun unresolvedMediaNote(count: Int): String {
    val photographs = if (count == 1) "1 photograph" else "$count photographs"
    return "$photographs referenced by this workshop " +
        (if (count == 1) "is" else "are") +
        " held in the online workshop record."
}

internal class DwReportLosses {
    private val overCap = LinkedHashMap<String, Int>()

    var stageKey: String = ""

    fun notePhotographsOverCap(count: Int) {
        if (count <= 0 || stageKey.isEmpty()) return
        overCap[stageKey] = (overCap[stageKey] ?: 0) + count
    }

    fun photographsOverCap(schema: SchemaResponse): List<Pair<StageDto, Int>> =
        schema.stages.sortedBy { it.number }.mapNotNull { stage ->
            overCap[stage.key]?.takeIf { it > 0 }?.let { stage to it }
        }
}

private fun stagesNamed(counted: List<Pair<StageDto, Int>>): String =
    counted.take(4).joinToString(", ") { (stage, _) -> "stage ${stage.number}" } +
        (if (counted.size > 4) "…" else "")

internal fun dwPhotographsOverCapNote(
    template: ReportTemplate,
    counted: List<Pair<StageDto, Int>>,
): String? {
    if (counted.isEmpty()) return null
    val total = counted.sumOf { it.second }
    return "$total ${if (total == 1) "photograph" else "photographs"} recorded in this workshop did not fit ${template.name}'s " +
        "photograph cap and ${if (total == 1) "is" else "are"} not in this file — " + stagesNamed(counted) +
        ". Generate the report with a template that prints every photograph to include ${if (total == 1) "it" else "them"}."
}

internal fun dwAttachmentsNamedButNotCarried(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    template: ReportTemplate,
): List<Pair<StageDto, Int>> {
    val out = ArrayList<Pair<StageDto, Int>>()
    schema.stages.sortedBy { it.number }.forEach { stage ->
        if (template.sectionFor(stage.key) == null) return@forEach
        val stored = draft?.stages?.get(stage.key)
        var named = 0
        stage.entities.forEach { entity ->
            val rows = if (entity.cardinality == "SINGLETON") {
                listOf(stored?.values.orEmpty())
            } else {
                stored?.rowsFor(entity.key).orEmpty().map { it.values }
            }
            entity.liveFields.forEach { field ->
                val type = DwFieldType.of(field.type)
                if (!type.isMedia) return@forEach
                if (type.isPicture) return@forEach
                if (DwTier.of(field.tier).ordinal > template.maxTier.ordinal) return@forEach
                if (field.reportRole == "HIDDEN") return@forEach
                named += rows.sumOf { values -> mediaIdsOf(values[field.key]).size }
            }
        }
        if (named > 0) out += stage to named
    }
    return out
}

internal fun dwAttachmentsNotCarriedNote(counted: List<Pair<StageDto, Int>>): String? {
    if (counted.isEmpty()) return null
    val total = counted.sumOf { it.second }
    return "$total attached ${if (total == 1) "file is" else "files are"} named in this report but " +
        "the ${if (total == 1) "file itself is" else "files themselves are"} not " +
        "inside it — " + stagesNamed(counted) +
        ". A report file cannot carry a document, a recording or a video; send them alongside it."
}

internal fun dwReportLossNotes(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    template: ReportTemplate,
    losses: DwReportLosses,
): List<String> = listOfNotNull(
    dwPhotographsOverCapNote(template, losses.photographsOverCap(schema)),
    dwAttachmentsNotCarriedNote(dwAttachmentsNamedButNotCarried(schema, draft, template)),
)

private fun renderStageSection(
    builder: DocumentBuilder,
    stage: StageDto,
    section: TemplateSection,
    plan: ReportPlan,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    imageFor: (String) -> ImageRef?,
    refs: DwRefLabels,
    figures: DwFigures,
    losses: DwReportLosses? = null,
) {
    val template = plan.template
    losses?.stageKey = stage.key
    val options = RenderOptions(
        maxTier = template.maxTier,
        includePhotos = section.includePhotos,
        photoColumns = section.photoColumns,
        maxPhotos = section.maxPhotos,
        losses = losses,
        numbered = template.numberHeadings,
        showEmptyNote = template.showEmptyNote,
        level = 1,
    )
    val stored = draft?.stages?.get(stage.key)
    val singletonValues = stored?.values.orEmpty()
    val answered = singletonValues.filterKeys { !it.startsWith("_") }
    val hasCollections = stage.printableCollections.any { stored?.rowsFor(it.key).orEmpty().isNotEmpty() }
    if (answered.isEmpty() && !hasCollections && section.omitIfEmpty) return

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { stage.title },
        level = 1,
        numbered = template.numberHeadings,
    )
    if (section.intro.isNotBlank()) builder.para(section.intro, style = ParaStyle.LEAD)

    recordingPlaceLine(singletonValues[DW_RECORDING_PLACE_KEY])?.let { line ->
        builder.para(line, style = ParaStyle.NOTE)
    }

    var wrote = false
    stage.singleton?.let { entity ->
        if (answered.isNotEmpty()) {
            wrote = renderEntity(builder, entity, answered, imageFor, refs, options.copy(metricRow = true)) || wrote
        }
    }

    stage.printableCollections.forEach { entity ->
        if (section.entities.isNotEmpty() && entity.key !in section.entities) return@forEach
        val rows = stored?.rowsFor(entity.key).orEmpty().map { it.values }
        if (rows.isEmpty()) {
            if (!section.omitIfEmpty) {
                builder.heading(entity.title, level = 2, numbered = template.numberHeadings)
                builder.para(
                    "No ${entity.title.lowercase(Locale.ROOT)} were recorded.",
                    style = ParaStyle.NOTE,
                )
            }
            return@forEach
        }
        if (stage.printableCollections.size > 1) {
            builder.heading(entity.title, level = 2, numbered = template.numberHeadings)
        }

        val groups = dwParentGroups(
            schema = schema,
            draft = draft,
            entity = entity,
            rows = rows,
            parentHeading = { parent, values, index -> rowHeading(parent, values, index, refs) },
            refLabel = { refId -> refs.label(refId) },
        )
        if (groups.isNullOrEmpty()) {
            wrote = renderCollection(
                builder, entity, rows, imageFor, refs,
                options = options.copy(presentation = section.presentation),
            ) || wrote
            return@forEach
        }
        val groupLevel = if (stage.printableCollections.size > 1) 3 else 2
        groups.forEach { group ->
            builder.heading(group.heading, level = groupLevel, numbered = template.numberHeadings)
            wrote = renderCollection(
                builder, entity, group.rows, imageFor, refs,
                rowLevel = minOf(4, groupLevel + 1),
                options = options.copy(presentation = section.presentation),
            ) || wrote
        }
    }

    if (section.includeFigures) {
        figures.chartsFor(stage.key).forEach { chart ->
            builder.add(chart)
            wrote = true
        }
    }

    if (!wrote && template.showEmptyNote) builder.para("Not recorded.", style = ParaStyle.NOTE)
}

private fun recordingPlaceLine(stored: JsonElement?): String? {
    val place = dwLocationFromValue(stored) ?: return null
    val named = listOfNotNull(
        place.village?.takeIf { it.isNotBlank() },
        place.district?.takeIf { it.isNotBlank() },
        place.state?.takeIf { it.isNotBlank() },
    ).joinToString(", ")
    val coordinate = String.format(Locale.ROOT, "%.5f, %.5f", place.latitude, place.longitude)
    val where = if (named.isBlank()) coordinate else "$named ($coordinate)"
    return "Recorded at $where."
}

private fun renderSpecialSection(
    builder: DocumentBuilder,
    special: SpecialSection,
    section: TemplateSection,
    plan: ReportPlan,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    imageFor: (String) -> ImageRef?,
    refs: DwRefLabels,
    figures: DwFigures,
    questionnaires: DwQuestionnaireCache?,
    customSections: DwCustomCache? = null,
    transcripts: DwTranscriptCache? = null,
) {
    when (special) {
        SpecialSection.COVER -> renderCover(builder, section, plan, schema, draft, imageFor, refs)
        SpecialSection.TOC -> builder.add(TocBlock(depth = 3))
        SpecialSection.SUMMARY_METRICS -> renderSummaryMetrics(builder, section, plan, draft)
        SpecialSection.SIGNATURES -> renderSignatures(builder, section, plan, draft)
        SpecialSection.ACKNOWLEDGEMENT -> {
            val value = draft?.stages
                ?.get("INTRODUCTORY_ADMIN_DOCUMENTATION")?.values?.get("acknowledgement")
            val blocks = if (value == null) emptyList() else toReportBlocks(value, ParaStyle.BODY, imageFor)
            if (blocks.isNotEmpty()) {
                builder.heading(
                    section.heading.ifBlank { "Acknowledgement" },
                    level = 1,
                    numbered = plan.template.numberHeadings,
                )
                blocks.forEach(builder::add)
            }
        }
        SpecialSection.ANNEXURE_MEDIA ->
            renderMediaAnnexure(builder, section, plan, schema, draft, imageFor, refs)
        SpecialSection.COMPLETENESS ->
            renderCompletenessAnnexure(builder, section, plan, schema, draft, refs, customSections)
        SpecialSection.MAP -> renderMap(builder, section, plan, draft)
        SpecialSection.CHART -> renderCharts(builder, section, plan, figures)

        SpecialSection.ANNEXURE_TRANSCRIPTS ->
            renderTranscriptAnnexure(builder, section, plan, schema, draft, transcripts)

        SpecialSection.ANNEXURE_QUESTIONNAIRES ->
            renderQuestionnaireAnnexure(builder, section, plan, questionnaires, schema.stages)

        SpecialSection.ANNEXURE_AI_LAYERS -> Unit

        SpecialSection.CUSTOM_SECTION ->
            renderCustomSection(builder, section, plan, draft, imageFor, refs, customSections)
    }
}

private fun renderCustomSection(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    draft: WorkshopDraft?,
    imageFor: (String) -> ImageRef?,
    refs: DwRefLabels,
    customSections: DwCustomCache?,
) {
    val template = plan.template
    val definition = customSectionsForReport(customSections) { stageKey ->
        draft?.stages?.get(stageKey)?.custom.orEmpty()
    }.firstOrNull { it.key == section.customKey } ?: return

    val values = draft?.stages?.get(definition.stageKey)?.custom.orEmpty()
    fun withinTier(tier: String): Boolean = DwTier.of(tier).ordinal <= template.maxTier.ordinal

    val entity = customSectionEntity(definition)
    val retired = retiredCustomFieldsWithAnswers(definition, values).filter { withinTier(it.tier) }
    val anyLive = entity.fields.any { withinTier(it.tier) && DwValues.isFilled(values[it.key]) }
    if (!anyLive && retired.isEmpty()) return

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { definition.title },
        level = 1,
        numbered = template.numberHeadings,
    )
    if (definition.description.isNotBlank()) {
        builder.para(definition.description, style = ParaStyle.LEAD)
    }
    if (anyLive) {
        val options = RenderOptions(
            maxTier = template.maxTier,
            includePhotos = false,
            numbered = template.numberHeadings,
            showEmptyNote = template.showEmptyNote,
            level = 1,
        )
        renderEntity(builder, entity, values, imageFor, refs, options)
    }
    retired.forEach { field ->
        builder.para(
            (field.label.ifBlank { field.key }) + ": " +
                DwValues.text(values[field.key]) +
                " (recorded under a wording this workshop no longer asks)",
            style = ParaStyle.NOTE,
        )
    }
    undrawableCustomFieldsWithValues(definition, values)
        .filter { withinTier(it.tier) }
        .forEach { field ->
            builder.para(
                (field.label.ifBlank { field.key }) + ": answer held in the online workshop record.",
                style = ParaStyle.NOTE,
            )
        }
}

private fun renderMediaAnnexure(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    imageFor: (String) -> ImageRef?,
    refs: DwRefLabels,
) {
    val gathered = ArrayList<Pair<ImageRef, String>>()
    val unresolvedHere = LinkedHashSet<String>()
    val resolve: (String) -> ImageRef? = { token ->
        imageFor(token).also { if (it == null && token.isNotBlank()) unresolvedHere.add(token) }
    }
    schema.stages.sortedBy { it.number }.forEach { stage ->
        val stored = draft?.stages?.get(stage.key)
        stage.entities.forEach { entity ->
            val sources = if (entity.cardinality == "SINGLETON") {
                listOf(stored?.values.orEmpty())
            } else {
                stored?.rowsFor(entity.key).orEmpty().map { it.values }
            }
            sources.forEach { values ->
                imagesOf(entity, values, resolve, plan.template.maxTier, refs = refs)
                    .forEach { (ref, caption) ->
                        gathered += ref to caption.ifBlank { stage.title }
                    }
            }
        }
    }
    if (gathered.isEmpty() && unresolvedHere.isEmpty()) return

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { "Photographic record" },
        level = 1,
        numbered = plan.template.numberHeadings,
    )
    if (unresolvedHere.isNotEmpty()) {
        builder.para(unresolvedMediaNote(unresolvedHere.size), style = ParaStyle.NOTE)
    }
    if (gathered.isNotEmpty()) builder.add(ImageGridBlock(images = gathered, columns = 3))
}

private fun renderCompletenessAnnexure(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    refs: DwRefLabels,
    customSections: DwCustomCache? = null,
) {
    val rows = schema.stages.sortedBy { it.number }.map { stage ->
        val stored = draft?.stages?.get(stage.key)
        val score = computeStageCompleteness(
            stage = stage,
            singleton = stage.singleton
                ?.let { maskUnresolvableRefs(it, stored?.values.orEmpty(), refs) }
                .orEmpty(),
            collections = stage.collections.associate { entity ->
                entity.key to stored?.rowsFor(entity.key).orEmpty()
                    .map { maskUnresolvableRefs(entity, it.values, refs) }
            },
            customFields = customFieldsForStage(customSections, stage.key),
            customValues = stored?.custom.orEmpty(),
        )
        listOf(
            runsOf("${score.number}. ${score.title}"),
            runsOf("${score.requiredFilled}/${score.requiredTotal}"),
            runsOf("${score.percent}%"),
            runsOf(if (score.isComplete) "Complete" else score.missing.take(3).joinToString(", ")),
        )
    }
    if (rows.isEmpty()) return

    builder.heading(
        section.heading.ifBlank { "Data completeness" },
        level = 1,
        numbered = plan.template.numberHeadings,
    )
    builder.add(
        TableBlock(
            columns = listOf(
                TableColumn("Stage", 40.0f),
                TableColumn("Required fields", 15.0f, numeric = true),
                TableColumn("Complete", 12.0f, numeric = true),
                TableColumn("Outstanding", 33.0f),
            ),
            rows = rows,
        )
    )
}

private fun maskUnresolvableRefs(
    entity: EntityDto,
    values: Map<String, JsonElement>,
    refs: DwRefLabels,
): Map<String, JsonElement> {
    val unresolved = entity.liveFields
        .filter { DwFieldType.of(it.type) == DwFieldType.REF }
        .map { it.key }
        .filter { key ->
            val stored = values[key]
            stored != null && refs.label(dwRefId(stored).trim()).isBlank()
        }
    if (unresolved.isEmpty()) return values
    return values - unresolved.toSet()
}

private fun renderCover(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    imageFor: (String) -> ImageRef?,
    refs: DwRefLabels,
) {
    val setupEntity = schema.stages.firstOrNull { it.key == "WORKSHOP_SETUP" }?.singleton
    val setupValues = draft?.stages?.get("WORKSHOP_SETUP")?.values.orEmpty()

    val infoRows = setupEntity?.let { entity ->
        entity.liveFields
            .filter { it.reportRole == "COVER_FIELD" }
            .filter {
                DwTier.of(it.tier).ordinal <= plan.template.maxTier.ordinal ||
                    it.key in COVER_PINNED_KEYS
            }
            .mapNotNull { field ->
                displayValue(field, setupValues[field.key], refs)
                    .takeIf { it.isNotBlank() }
                    ?.let { Triple(field.key, field.label, it) }
            }
    }.orEmpty()

    val hero = if (section.includePhotos && setupEntity != null) {
        imagesOf(setupEntity, setupValues, imageFor, plan.template.maxTier, limit = 1, refs = refs)
            .firstOrNull()?.first
    } else {
        null
    }

    val logo = mediaIdsOf(plan.settings["logo"]).firstOrNull()?.let(imageFor)

    val org = plan.meta.organisation.ifEmpty { plan.template.organisation }
    val letterhead = cleanText(settingText(plan.settings, "letterheadText"))
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(6)

    val generatedOn = fieldCopyNote(
        plan.template,
        plan.settings,
        plan.meta.generatedAt.take(10).let(::formatReportDate),
        plan.serverCopyUnread,
    )

    builder.add(
        CoverBlock(
            title = plan.meta.title,
            subtitle = plan.meta.subtitle,
            orgLines = (listOf("Government of India • Ministry of Textiles", org) + letterhead)
                .filter { it.isNotEmpty() },
            logo = logo,
            heroImage = hero,
            infoRows = coverRows(infoRows),
            footerLines = listOf(submissionLine(plan.settings), generatedOn).filter { it.isNotEmpty() },
        )
    )
}

private fun renderSummaryMetrics(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    draft: WorkshopDraft?,
) {
    val outcomes = draft?.stages?.get("WORKSHOP_OUTCOMES")?.values.orEmpty()

    fun stated(key: String): Int? = if (key.isEmpty()) {
        null
    } else {
        asFiniteNumber(outcomes[key])?.takeIf { it >= 0.0 }?.toInt()
    }

    val counts = listOf(
        listOf("Artisans", "WORKSHOP_PLAN_PARTICIPANTS_OPENING", "participant", ""),
        listOf("Sketches", "SKETCH_DEVELOPMENT", "sketch", "designsCountOverride"),
        listOf("Prototypes", "PROTOTYPE_DEVELOPMENT", "prototype", "prototypesCountOverride"),
        listOf("Final products", "FINAL_PROTOTYPE_DOCUMENTATION", "finalProduct", ""),
    )
    val metrics = counts.mapNotNull { (label, stageKey, entityKey, overrideKey) ->
        val rows = draft?.stages?.get(stageKey)?.rowsFor(entityKey)?.size ?: 0
        val n = stated(overrideKey) ?: rows
        if (n == 0) null else Triple(label, n.toString(), "")
    }
    if (metrics.isEmpty()) return

    if (section.heading.isNotBlank()) {
        builder.heading(section.heading, level = 1, numbered = plan.template.numberHeadings)
    }
    builder.add(MetricRowBlock(metrics = metrics.take(4)))

    val overridden = listOf("designsCountOverride", "prototypesCountOverride")
        .any { asFiniteNumber(outcomes[it]) != null }
    val reason = if (overridden) DwValues.text(outcomes["countOverrideReason"]).trim() else ""
    if (reason.isNotEmpty()) builder.para("Stated counts: $reason", style = ParaStyle.NOTE)
}

private fun renderSignatures(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    draft: WorkshopDraft?,
) {
    val setup = draft?.stages?.get("WORKSHOP_SETUP")?.values.orEmpty()
    val closing = draft?.stages?.get("INSPECTION_CLOSING")?.values.orEmpty()
    val signatories = listOf(
        DwValues.text(setup["designerName"]).trim() to "Designer",
        DwValues.text(setup["implementingAgency"]).trim() to "Implementing Agency",
        DwValues.text(closing["inspectingOfficer"]).trim() to "Inspecting Officer",
    ).filter { it.first.isNotEmpty() }
    if (signatories.isEmpty()) return

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { "Certification" },
        level = 1,
        numbered = plan.template.numberHeadings,
    )
    builder.para(
        "Certified that the workshop was conducted and the prototypes documented above were " +
            "developed during the period stated on the cover of this report."
    )
    builder.add(SignatureBlock(signatories = signatories))
    dwReportSignOffLines(plan.signOff).forEach { builder.para(it) }
}

internal fun asFiniteNumber(value: JsonElement?): Double? {
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString && (primitive.content == "true" || primitive.content == "false")) return null
    return primitive.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun mediaIdsOf(value: JsonElement?): List<String> = when (value) {
    null -> emptyList()
    is JsonArray -> DwValues.list(value).filter { it.isNotBlank() }
    else -> listOfNotNull(DwValues.text(value).takeIf { it.isNotBlank() })
}

internal const val COVER_INFO_ROWS = 10

internal val COVER_PINNED_KEYS: Set<String> = setOf("sanctionAmount")

internal fun coverRows(keyed: List<Triple<String, String, String>>): List<Pair<String, String>> {
    var room = COVER_INFO_ROWS - keyed.count { it.first in COVER_PINNED_KEYS }
    val printed = mutableListOf<Pair<String, String>>()
    for ((key, label, text) in keyed) {
        if (key in COVER_PINNED_KEYS) {
            printed += label to text
        } else if (room > 0) {
            printed += label to text
            room -= 1
        }
    }
    return printed
}

private fun visibleFields(entity: EntityDto, template: ReportTemplate): List<FieldDto> =
    entity.liveFields.filter { DwTier.of(it.tier).ordinal <= template.maxTier.ordinal }

internal data class RenderOptions(
    val maxTier: DwTier = DwTier.ADVANCED,
    val includePhotos: Boolean = true,
    val photoColumns: Int = 2,
    val maxPhotos: Int = 0,
    val numbered: Boolean = false,
    val presentation: Presentation = Presentation.AUTO,
    val showEmptyNote: Boolean = true,
    val level: Int = 1,
    val photosFirst: Boolean = false,
    val metricRow: Boolean = false,
    val losses: DwReportLosses? = null,
)

private val DwFieldType.isPicture: Boolean
    get() = this == DwFieldType.IMAGE || this == DwFieldType.IMAGE_LIST

private fun imagesOf(
    entity: EntityDto,
    values: Map<String, JsonElement>,
    imageFor: (String) -> ImageRef?,
    maxTier: DwTier,
    limit: Int = 0,
    refs: DwRefLabels? = null,
    fallbackLabels: Boolean = true,
): List<Pair<ImageRef, String>> {
    val visible = entity.liveFields.filter { DwTier.of(it.tier).ordinal <= maxTier.ordinal }
    val captionByTarget = visible.filter { it.captionFor.isNotBlank() }.associateBy { it.captionFor }

    val wanted = LinkedHashMap<String, String>()

    visible.forEach { field ->
        if (field.reportRole == "HIDDEN" || field.captionFor.isNotBlank()) return@forEach
        val type = DwFieldType.of(field.type)
        if (!type.isPicture) return@forEach
        val stored = values[field.key]
        if (!DwValues.isFilled(stored)) return@forEach
        val caption = captionByTarget[field.key]?.let { DwValues.text(values[it.key]) }.orEmpty()
        val ids = if (type == DwFieldType.IMAGE_LIST) DwValues.list(stored) else listOf(DwValues.text(stored))
        ids.filter { it.isNotBlank() }.forEach { id ->
            wanted.putIfAbsent(id, if (fallbackLabels) caption.ifBlank { field.label } else caption)
        }
    }

    if (refs != null) {
        visible.forEach { field ->
            if (field.reportRole == "HIDDEN" || field.captionFor.isNotBlank()) return@forEach
            if (DwFieldType.of(field.type) != DwFieldType.REF) return@forEach
            val refId = dwRefId(values[field.key]).trim()
            val photo = refs.photoOf(refId)?.takeIf { it.isNotBlank() } ?: return@forEach
            wanted.putIfAbsent(photo, referenceCaption(entity, field, values, refs, refId))
        }
    }

    val gathered = ArrayList<Pair<ImageRef, String>>()
    for ((id, caption) in wanted) {
        val ref = imageFor(id) ?: continue
        gathered += ref to caption
    }
    return if (limit > 0) gathered.take(limit) else gathered
}

private val REFERENCE_NAME_SOURCES: Map<String, List<String>> = mapOf("Craft" to listOf("craftName"))

private fun referenceCaption(
    entity: EntityDto,
    spec: FieldDto,
    values: Map<String, JsonElement>,
    refs: DwRefLabels,
    refId: String,
): String {
    val sources = setOf("name") + REFERENCE_NAME_SOURCES[spec.refModel].orEmpty()
    for ((source, target) in spec.refHydration) {
        if (source !in sources) continue
        val targetSpec = entity.field(target) ?: continue
        val frozen = displayValue(targetSpec, values[target], refs)
        if (frozen.isNotBlank()) return frozen
    }
    return refs.label(refId).ifBlank { spec.label }
}

private fun placeImages(builder: DocumentBuilder, images: List<Pair<ImageRef, String>>, columns: Int) {
    if (images.isEmpty()) return
    if (images.size == 1) {
        val (ref, caption) = images.first()
        builder.add(ImageBlock(image = ref, widthPct = 62.0f, caption = caption))
        return
    }
    builder.add(ImageGridBlock(images = images, columns = columns.coerceIn(1, 4)))
}

private fun imageGroupsOf(
    entity: EntityDto,
    values: Map<String, JsonElement>,
    imageFor: (String) -> ImageRef?,
    maxTier: DwTier,
    refs: DwRefLabels? = null,
    cap: Int = 0,
    onOverCap: (Int) -> Unit = {},
): List<Pair<String, List<Pair<ImageRef, String>>>> {
    val flat = imagesOf(entity, values, imageFor, maxTier, refs = refs, fallbackLabels = false)
    if (flat.isEmpty()) return emptyList()

    val visible = entity.liveFields.filter { DwTier.of(it.tier).ordinal <= maxTier.ordinal }
    val owner = LinkedHashMap<String, String>()
    val multi = LinkedHashSet<String>()
    visible.forEach { field ->
        if (field.reportRole == "HIDDEN" || field.captionFor.isNotBlank()) return@forEach
        val type = DwFieldType.of(field.type)
        if (!type.isPicture) return@forEach
        val stored = values[field.key]
        if (!DwValues.isFilled(stored)) return@forEach
        val ids = if (type == DwFieldType.IMAGE_LIST) DwValues.list(stored) else listOf(DwValues.text(stored))
        val mine = ids.filter { it.isNotBlank() }
        mine.forEach { id -> owner.putIfAbsent(id, field.key) }
        if (mine.count { owner[it] == field.key } > 1) multi += field.key
    }
    val labelOf = visible.associate { it.key to it.label }

    val plates = LinkedHashMap<String, MutableList<Pair<ImageRef, String>>>()
    flat.forEach { entry ->
        val fieldKey = owner.entries.firstOrNull { it.key == entry.first.source }?.value
            ?: owner.entries.firstOrNull { imageFor(it.key)?.source == entry.first.source }?.value
        val plate = if (fieldKey != null && fieldKey in multi) fieldKey else ""
        val cell = entry.second.ifBlank {
            if (plate.isEmpty()) fieldKey?.let { labelOf[it] }.orEmpty() else ""
        }
        plates.getOrPut(plate) { ArrayList() } += entry.first to cell
    }

    return plates.map { (key, images) ->
        val kept = if (cap > 0) images.take(cap) else images
        onOverCap(images.size - kept.size)
        (if (key.isEmpty()) "" else labelOf[key].orEmpty()) to kept.toList()
    }.filter { it.second.isNotEmpty() }
}

private fun placeImageGroups(
    builder: DocumentBuilder,
    groups: List<Pair<String, List<Pair<ImageRef, String>>>>,
    columns: Int,
) {
    groups.forEach { (caption, images) ->
        if (images.isEmpty()) return@forEach
        if (images.size == 1) {
            val (ref, own) = images.first()
            builder.add(ImageBlock(image = ref, widthPct = 62.0f, caption = own.ifBlank { caption }))
            return@forEach
        }
        builder.add(
            ImageGridBlock(images = images, columns = columns.coerceIn(1, 4), caption = caption)
        )
    }
}

internal class DwRefLabels(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    private val externalLabel: (String) -> String? = DwReferenceStore::labelFor,
) {
    private val rowsById = HashMap<String, Pair<EntityDto, Map<String, JsonElement>>>()
    private val cache = HashMap<String, String>()

    init {
        val entities = schema.stages.flatMap { it.entities }.associateBy { it.key }
        draft?.stages?.values.orEmpty().forEach { stage ->
            stage.rows.forEach { row ->
                val entity = entities[row.entityKey()] ?: return@forEach
                row.id.substringAfter(DW_ROW_KEY_SEPARATOR, "")
                    .takeIf { it.isNotBlank() }
                    ?.let { rowsById[it] = entity to row.values }
                (row.values["_entryId"] as? JsonPrimitive)?.content
                    ?.takeIf { it.isNotBlank() }
                    ?.let { rowsById[it] = entity to row.values }
            }
        }
    }

    fun label(refId: String, seen: Set<String> = emptySet()): String {
        if (refId.isBlank()) return ""
        cache[refId]?.let { return it }
        if (refId in seen) return ""

        var label = rowsById[refId]?.let { (entity, row) -> rowLabel(entity, row, seen + refId) }.orEmpty()
        if (label.isBlank()) label = externalLabel(refId).orEmpty()

        cache[refId] = label
        return label
    }

    internal fun photoOf(refId: String): String? {
        if (refId.isBlank()) return null
        val (entity, row) = rowsById[refId] ?: return null
        entity.liveFields.forEach { spec ->
            val type = DwFieldType.of(spec.type)
            if (!type.isPicture || spec.reportRole == "HIDDEN") return@forEach
            val stored = row[spec.key]
            if (!DwValues.isFilled(stored)) return@forEach
            val ids = if (type == DwFieldType.IMAGE_LIST) DwValues.list(stored) else listOf(DwValues.text(stored))
            ids.firstOrNull { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun rowLabel(entity: EntityDto, row: Map<String, JsonElement>, seen: Set<String>): String {
        entity.field(entity.labelField)?.let { spec ->
            val text = if (DwFieldType.of(spec.type) == DwFieldType.REF) {
                label(dwRefId(row[spec.key]).trim(), seen)
            } else {
                displayValue(spec, row[spec.key], this)
            }
            if (text.isNotBlank()) return text
        }
        entity.liveFields.forEach { spec ->
            if (!DwFieldType.of(spec.type).isFreeText) return@forEach
            val text = displayValue(spec, row[spec.key], this)
            if (text.isNotBlank()) return text.take(80)
        }
        return ""
    }
}

internal val OPAQUE_ID = Regex("^[a-z0-9]{16,}$")

private const val NOT_RECORDED = "Not recorded."

private val ROLES_THE_GRID_DOES_NOT_PRINT: Set<String> =
    setOf("NARRATIVE", "BULLETS", "METRIC", "GALLERY", "CAPTION", "HIDDEN")

private val ROLES_THAT_PRINT_NO_EMPTY_NOTE: Set<String> =
    ROLES_THE_GRID_DOES_NOT_PRINT - setOf("NARRATIVE", "BULLETS")

private fun renderEntity(
    builder: DocumentBuilder,
    entity: EntityDto,
    values: Map<String, JsonElement>,
    imageFor: (String) -> ImageRef?,
    refs: DwRefLabels,
    options: RenderOptions = RenderOptions(),
): Boolean {
    val before = builder.blockCount
    val visible = entity.liveFields.filter { DwTier.of(it.tier).ordinal <= options.maxTier.ordinal }

    fun printable(match: (String) -> Boolean): List<Pair<FieldDto, String>> = visible.mapNotNull { field ->
        if (field.reportRole == "HIDDEN" || !match(field.reportRole)) return@mapNotNull null
        if (field.captionFor.isNotBlank()) return@mapNotNull null
        val text = displayValue(field, values[field.key], refs)
            .let { if (it.isNotBlank()) it + dwModelPreviewNote(field, values) else it }
        when {
            text.isNotBlank() -> field to text
            field.required && options.showEmptyNote -> field to NOT_RECORDED
            else -> null
        }
    }

    val narrative = printable { it == "NARRATIVE" }
    val bullets = printable { it == "BULLETS" }
    val metrics = printable { it == "METRIC" }
    val pairs = printable { it !in ROLES_THE_GRID_DOES_NOT_PRINT }

    val gallery = if (options.includePhotos) {
        imageGroupsOf(
            entity, values, imageFor, options.maxTier, refs = refs, cap = options.maxPhotos,
            onOverCap = { dropped -> options.losses?.notePhotographsOverCap(dropped) },
        )
    } else {
        emptyList()
    }

    fun drawImages() = placeImageGroups(builder, gallery, options.photoColumns)
    if (options.photosFirst) drawImages()

    if (pairs.isNotEmpty()) builder.keyValues(pairs.map { (field, text) -> field.label to text })

    val fieldLevel = minOf(4, options.level + 1)

    narrative.forEach { (field, text) ->
        val blocks = if (DwFieldType.of(field.type) == DwFieldType.RICH_TEXT) {
            toReportBlocks(values[field.key], ParaStyle.BODY, imageFor)
        } else {
            emptyList()
        }
        if (blocks.isNotEmpty()) {
            if (text.length > 160 || blocks.size > 1) {
                builder.heading(field.label, level = fieldLevel, numbered = options.numbered)
            } else {
                builder.para("${field.label}:")
            }
            blocks.forEach(builder::add)
            return@forEach
        }
        if (DwFieldType.of(field.type) == DwFieldType.LONG_TEXT && text.length > 160) {
            builder.heading(field.label, level = fieldLevel, numbered = options.numbered)
            builder.para(text)
        } else {
            builder.para("${field.label}: $text")
        }
    }

    bullets.forEach { (field, text) ->
        builder.heading(field.label, level = fieldLevel, numbered = options.numbered)
        val blocks = if (DwFieldType.of(field.type) == DwFieldType.RICH_TEXT) {
            toReportBlocks(values[field.key], ParaStyle.BODY, imageFor)
        } else {
            emptyList()
        }
        if (blocks.isNotEmpty()) {
            blocks.forEach(builder::add)
            return@forEach
        }
        builder.bullets(text.replace(";", "\n").split("\n").map { it.trim() })
    }

    if (options.metricRow && metrics.isNotEmpty()) {
        builder.add(
            MetricRowBlock(metrics = metrics.take(4).map { (field, text) -> Triple(field.label, text, field.unit) })
        )
    }

    if (!options.photosFirst) drawImages()
    return builder.blockCount != before
}

private fun renderCollection(
    builder: DocumentBuilder,
    entity: EntityDto,
    rows: List<Map<String, JsonElement>>,
    imageFor: (String) -> ImageRef?,
    refs: DwRefLabels,
    rowLevel: Int = 3,
    options: RenderOptions = RenderOptions(),
): Boolean {
    val before = builder.blockCount
    val visible = entity.liveFields.filter { DwTier.of(it.tier).ordinal <= options.maxTier.ordinal }
    val columns = visible
        .filter { it.reportRole == "TABLE_COLUMN" && !DwFieldType.of(it.type).isMedia }
        .take(6)
    val columnKeys = columns.map { it.key }.toSet()

    if (options.presentation == Presentation.GALLERY) {
        val every = rows.flatMap { imagesOf(entity, it, imageFor, options.maxTier, refs = refs) }
        val shown = if (options.maxPhotos > 0) every.take(options.maxPhotos) else every
        options.losses?.notePhotographsOverCap(every.size - shown.size)
        placeImages(builder, shown, options.photoColumns)
        return builder.blockCount != before
    }

    val asCards = options.presentation == Presentation.CARDS ||
        (options.presentation != Presentation.NARRATIVE &&
            options.presentation != Presentation.KEY_VALUE &&
            columns.isEmpty())
    val rowOptions = options.copy(level = rowLevel)

    if (asCards) {
        rows.forEachIndexed { index, row ->
            builder.heading(rowHeading(entity, row, index, refs), level = rowLevel, numbered = options.numbered)
            renderEntity(builder, entity, row, imageFor, refs, rowOptions.copy(photosFirst = true))
        }
        return builder.blockCount != before
    }

    if (options.presentation == Presentation.NARRATIVE || options.presentation == Presentation.KEY_VALUE) {
        rows.forEach { row -> renderEntity(builder, entity, row, imageFor, refs, rowOptions) }
        return builder.blockCount != before
    }

    builder.add(
        TableBlock(
            columns = tableColumns(columns),
            rows = rows.map { row ->
                columns.map { cellRuns(it, row, refs, showEmptyNote = options.showEmptyNote) }
            },
            caption = entity.title,
        )
    )

    val leftovers = visible.filter {
        it.key !in columnKeys && it.reportRole != "HIDDEN" && it.captionFor.isBlank()
    }
    if (leftovers.isEmpty()) return true
    val carried = leftovers.filter { options.includePhotos || !DwFieldType.of(it.type).isPicture }
    rows.forEachIndexed { index, row ->
        val hasExtra = carried.any { field ->
            DwValues.isFilled(row[field.key]) ||
                (field.required && options.showEmptyNote &&
                    field.reportRole !in ROLES_THAT_PRINT_NO_EMPTY_NOTE)
        }
        if (!hasExtra) return@forEachIndexed
        builder.heading(rowHeading(entity, row, index, refs), level = rowLevel, numbered = options.numbered)
        renderEntity(
            builder,
            entity.copy(fields = leftovers + entity.fields.filter { it.captionFor.isNotBlank() }),
            row,
            imageFor,
            refs,
            rowOptions.copy(photosFirst = true),
        )
    }
    return true
}

internal fun rowHeading(
    entity: EntityDto,
    row: Map<String, JsonElement>,
    index: Int,
    refs: DwRefLabels,
): String {
    val label = entity.field(entity.labelField)?.let { spec ->
        if (DwFieldType.of(spec.type) == DwFieldType.REF) {
            refs.label(dwRefId(row[spec.key]).trim())
        } else {
            displayValue(spec, row[spec.key], refs)
        }
    }.orEmpty()
    return if (label.isBlank()) "${entity.title} ${index + 1}" else "${entity.title} — $label"
}

private fun cellRuns(
    field: FieldDto,
    row: Map<String, JsonElement>,
    refs: DwRefLabels,
    showEmptyNote: Boolean = false,
): List<Run> {
    val stored = row[field.key]
    if (DwFieldType.of(field.type) == DwFieldType.RICH_TEXT && DwValues.isFilled(stored)) {
        return plainRuns(stored)
    }
    val text = displayValue(field, stored, refs)
    if (text.isBlank() && field.required && showEmptyNote) return runsOf(NOT_RECORDED)
    return runsOf(text)
}

private fun tableColumns(fields: List<FieldDto>): List<TableColumn> {
    val hinted = fields.sumOf { it.columnWidthPct.toDouble() }
    val unhinted = fields.count { it.columnWidthPct <= 0f }
    val share = if (unhinted > 0) ((100.0 - hinted) / unhinted).coerceAtLeast(4.0) else 0.0

    val widths = fields.map { field ->
        if (field.columnWidthPct > 0f) field.columnWidthPct.toDouble() else share
    }
    val total = widths.sum()
    val scaled = widths.map { it * 100.0 / total }
    val adjusted = scaled.toMutableList()
    adjusted[adjusted.lastIndex] = 100.0 - adjusted.dropLast(1).sum()

    return fields.mapIndexed { index, field ->
        val numeric = DwFieldType.of(field.type).isNumeric
        TableColumn(
            header = field.label,
            widthPct = adjusted[index].toFloat(),
            align = if (numeric) Align.RIGHT else Align.LEFT,
            numeric = numeric,
        )
    }
}

internal val DW_MODEL_3D_FIELD_KEYS: Map<String, Pair<String, String>> =
    mapOf("modelFile" to ("turntablePhotos" to "360° capture"))

internal fun dwModelPreviewNote(field: FieldDto, row: Map<String, JsonElement>): String {
    if (DwFieldType.of(field.type) != DwFieldType.FILE) return ""
    val (previewKey, previewLabel) = DW_MODEL_3D_FIELD_KEYS[field.key] ?: return ""
    val frames = DwValues.list(row[previewKey]).size
    if (frames == 0) return ""
    return "; $frames photograph${if (frames == 1) "" else "s"} of it under “$previewLabel”"
}

private fun displayValue(field: FieldDto, value: JsonElement?, refs: DwRefLabels): String {
    if (!DwValues.isFilled(value)) return ""
    val type = DwFieldType.of(field.type)
    return when (type) {
        DwFieldType.RICH_TEXT -> toPlain(value)

        DwFieldType.REF -> {
            val id = dwRefId(value).trim()
            val label = refs.label(id)
            when {
                label.isNotBlank() -> label
                OPAQUE_ID.matches(id) -> ""
                else -> id
            }
        }

        DwFieldType.BOOL -> when (DwValues.bool(value)) {
            true -> "Yes"
            false -> "No"
            null -> ""
        }
        DwFieldType.ENUM -> field.options.firstOrNull { it.value == DwValues.text(value) }?.label
            ?: DwValues.text(value)
        DwFieldType.MULTI_ENUM -> DwValues.list(value)
            .mapNotNull { token ->
                if (field.options.isNotEmpty()) {
                    field.options.firstOrNull { it.value == token }?.label ?: token
                } else {
                    val label = refs.label(token)
                    when {
                        label.isNotBlank() -> label
                        OPAQUE_ID.matches(token) -> null
                        else -> token
                    }
                }
            }
            .joinToString(", ")
        DwFieldType.TAGS -> DwValues.list(value).joinToString(", ")

        DwFieldType.IMAGE, DwFieldType.IMAGE_LIST -> ""

        DwFieldType.FILE, DwFieldType.AUDIO, DwFieldType.VIDEO -> {
            val count = DwValues.list(value).size
            if (count == 0) {
                ""
            } else if (type == DwFieldType.FILE && field.key in DW_MODEL_3D_FIELD_KEYS) {
                "$count 3D model${if (count == 1) "" else "s"} attached"
            } else {
                val singular = when (type) {
                    DwFieldType.AUDIO -> "recording"
                    DwFieldType.VIDEO -> "video"
                    else -> "document"
                }
                "$count ${if (count == 1) singular else singular + "s"} attached"
            }
        }

        DwFieldType.GEO -> DwValues.geo(value)?.let { (lat, lon) ->
            String.format(java.util.Locale.ROOT, "%.5f, %.5f", lat, lon)
        }.orEmpty()
        DwFieldType.MONEY -> {
            val amount = asFiniteNumber(value)
            if (amount == null) {
                DwValues.text(value)
            } else {
                val negative = amount < 0.0 || (amount == 0.0 && 1.0 / amount < 0.0)
                val fixed = pyFixed(abs(amount), 2)
                (if (negative) "-" else "") + "₹ " +
                    groupIndian(fixed.substringBefore('.')) + "." + fixed.substringAfter('.')
            }
        }

        DwFieldType.PERCENT -> asFiniteNumber(value)?.let { pyGeneral(it) + "%" } ?: DwValues.text(value)

        DwFieldType.DATE -> formatReportDate(DwValues.text(value))

        DwFieldType.INT, DwFieldType.DECIMAL -> {
            val number = asFiniteNumber(value)
            if (number == null) {
                DwValues.text(value)
            } else {
                var text = if (type == DwFieldType.INT) pyFixed(number, 0) else pyGeneral(number)
                if (abs(number) >= 10000) {
                    val fixed = pyFixed(abs(number), 2)
                    text = (if (number < 0) "-" else "") + groupIndian(fixed.substringBefore('.'))
                    val frac = fixed.substringAfter('.').trimEnd('0')
                    if (type != DwFieldType.INT && frac.isNotEmpty()) text += ".$frac"
                }
                if (field.unit.isNotBlank()) "$text ${field.unit}".trim() else text
            }
        }

        else -> {
            val text = DwValues.text(value)
            if (field.unit.isNotBlank() && text.isNotEmpty()) "$text ${field.unit}".trim() else text
        }
    }
}

private fun pyFixed(value: Double, places: Int): String =
    BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toPlainString()

private fun pyGeneral(value: Double): String {
    if (value == 0.0) return if (1.0 / value < 0.0) "-0" else "0"
    val rounded = BigDecimal(value).round(MathContext(6, RoundingMode.HALF_EVEN))
    val exponent = rounded.precision() - rounded.scale() - 1
    if (exponent >= -4 && exponent < 6) {
        val fixed = rounded.setScale(maxOf(0, 5 - exponent), RoundingMode.HALF_EVEN).toPlainString()
        if (!fixed.contains('.')) return fixed
        return fixed.trimEnd('0').trimEnd('.')
    }
    val digits = rounded.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
    val mantissa = StringBuilder()
    if (rounded.signum() < 0) mantissa.append('-')
    mantissa.append(digits[0])
    if (digits.length > 1) mantissa.append('.').append(digits, 1, digits.length)
    val sign = if (exponent < 0) "-" else "+"
    return mantissa.toString() + "e" + sign + abs(exponent).toString().padStart(2, '0')
}

private fun renderTranscriptAnnexure(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    transcripts: DwTranscriptCache?,
) {
    if (!com.designprototype.workshop.report.wantsTranscripts(null, plan.settings)) return
    appendTranscriptAnnexure(
        builder,
        dwTranscriptSelection(schema, draft, transcripts).items,
        heading = section.heading,
        numbered = plan.template.numberHeadings,
        pageBreakBefore = section.pageBreakBefore,
    )
}

internal val com.designprototype.workshop.data.StageDto.printableCollections: List<com.designprototype.workshop.data.EntityDto>
    get() = collections.filter { entity -> entity.fields.any { !it.deprecated && it.reportRole != "HIDDEN" } }
