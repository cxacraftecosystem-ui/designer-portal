package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.designprototype.workshop.data.DwTier
import com.designprototype.workshop.data.DwReferenceStore
import com.designprototype.workshop.data.DwValues
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
import java.time.Instant
import java.util.Locale

/**
 * Choose a template, read the completeness warnings, and export a .docx or a .pdf — all of it on the
 * device, with no network at any point.
 *
 * WHY THE FILE IS BUILT HERE RATHER THAN FETCHED FROM `POST /design-workshops/{id}/report`. The
 * server can render the same report and does, for the web. But the moment a designer most needs a
 * report is at the end of a workshop, in the cluster, to hand a printed copy to the officer who came
 * to see it — which is exactly where there is no signal and no printer queue to wait on. [DocxWriter]
 * needs only `java.util.zip` and [PdfWriter] only `android.graphics`, so the same document is
 * produced from the same [ReportDocument] model on both sides. An export that required a round trip
 * would be an export that fails at the only moment it is asked for.
 *
 * The document is assembled from the REGISTRY, exactly as the forms are: every field's `reportRole`
 * decides where its answer lands, so a field added on the server appears in the report with no client
 * change. There is no per-stage report code here for the same reason there is no per-stage form code.
 */
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
    var percent by remember(workshopId) { mutableStateOf(0) }
    var loading by remember(workshopId) { mutableStateOf(true) }
    var busy by remember(workshopId) { mutableStateOf(false) }
    var result by remember(workshopId) { mutableStateOf<ReportExport.Result?>(null) }
    /**
     * What the last export could not honour — a typeface a PDF drawn here cannot carry, a section
     * this device does not build, a template this build has retired.
     *
     * Held separately from [warnings], which are about the DATA and are known before any button is
     * pressed. These are about the FILE and are only knowable once a format has been chosen, which
     * is why they appear beside the saved file rather than above the buttons.
     */
    var exportNotes by remember(workshopId) { mutableStateOf<List<String>>(emptyList()) }
    /**
     * The report's accent colour for the next export, blank for "the colour the record already has".
     *
     * Seeded below from stage 20 so the phone and the office produce the same-coloured file for the
     * same workshop, then owned by the picker. It is NOT written back to the draft: an export is not
     * an edit, and a colour tried once in a cluster must not arrive at the office as a saved
     * decision the next sync silently applies to everything.
     */
    var accent by remember(workshopId) { mutableStateOf("") }

    LaunchedEffect(workshopId) {
        loading = true
        runCatching {
            val registry = repository.designWorkshopSchema(appContext)
            val stored = WorkshopDraftStore.load(appContext, workshopId)
            val scores = computeWorkshopCompleteness(registry, stored)
            schema = registry
            draft = stored
            percent = overallPercent(scores)
            // The catalogue is the offline answer, not the registry's REPORT_TEMPLATE enum: the enum
            // carries the ids and the names and no descriptions, so a designer choosing a format
            // with no signal was choosing between six titles with nothing to say what any contains.
            templates = repository.designWorkshopTemplates(appContext).ifEmpty {
                templateChoices().map { ReportTemplateDto(it.id, it.name, it.description) }
            }
            val settings = stored?.stages?.get("REPORT_GENERATION")?.values.orEmpty()
            // STAGE 20'S ANSWER SEEDS THE PICKER, not the workshop header, because stage 20 is what
            // the report is actually built from. Seeding from the header alone showed a designer
            // "DCH standard" on this screen while their own saved answer said "Photo catalogue",
            // and then obeyed the screen — so the field they filled in was inert twice over.
            templateId = resolveTemplateId(null, settings, stored?.templateId.orEmpty())
                .ifEmpty { "DCH_STANDARD" }
            // Stage 20's saved colour, read in the order the server reads it: the hex is the
            // authority and the preset name is consulted only when it is blank. A hex the registry
            // has never heard of still renders, because it is a colour and not a token.
            accent = resolveAccent(null, settings).orEmpty()
            // One warning per stage rather than one per missing field. A report generated from a
            // half-filled workshop is still worth having in the field — the warnings are there so the
            // designer knows what the officer will notice, not to block the export.
            warnings = scores.filter { it.missing.isNotEmpty() }.map { stage ->
                "Stage ${stage.number} — ${stage.title}: ${stage.missing.size} required field(s) " +
                    "missing (${stage.missing.take(3).joinToString(", ")}" +
                    (if (stage.missing.size > 3) ", …" else "") + ")"
            }
        }.onFailure { onError(it.message ?: "Unable to prepare the report.") }
        loading = false
    }

    fun export(format: String) {
        val registry = schema ?: return
        busy = true
        result = null
        scope.launch {
            runCatching {
                val stored = draft ?: WorkshopDraftStore.load(appContext, workshopId)
                // RESOLVED ONCE, here, and handed to the builder. What this screen tells the
                // designer about the file and what the builder writes into it then cannot come
                // from two readings of the same stage-20 entry.
                val plan = reportPlanFor(
                    schema = registry,
                    draft = stored,
                    workshopId = workshopId,
                    requestedTemplateId = templateId,
                    requestedAccent = accent,
                    format = format,
                    generatedAt = Instant.now().toString(),
                )
                exportNotes = plan.warnings
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
                    )
                }
                val loader = deviceImageLoader()
                val exported = if (format == "PDF") {
                    ReportExport.exportPdf(appContext, repository, document, loader)
                } else {
                    ReportExport.exportDocx(appContext, repository, document, loader)
                }
                // Record the fact — never the bytes. A designer on a metered field connection should
                // not be charged for a thirty-megabyte report merely to prove one was made, and the
                // checksum is enough to match the file later.
                //
                // QUEUED, NOT BEST-EFFORT. This used to be a bare call inside `runCatching`, so with
                // no signal the record was dropped and the office's export log stayed empty for a
                // report that had been handed over — and the close of a workshop in a village is
                // precisely when there is no signal. `recordDesignWorkshopExport` now puts the row in
                // the offline outbox on a transient failure and it drains with everything else. The
                // `runCatching` stays as the last guard: an export that HAPPENED must not be reported
                // as failed because the bookkeeping around it threw.
                val remoteId = stored?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
                if (remoteId != null) {
                    runCatching {
                        repository.recordDesignWorkshopExport(
                            appContext,
                            remoteId,
                            ExportRecordBody(
                                format = format,
                                // The template the file was BUILT from, which is not necessarily the
                                // one the picker asked for — a retired id falls back — and the
                                // export log is what an office matches a delivered file against.
                                templateId = document.meta.templateId,
                                fileName = exported.fileName,
                                generatedAt = Instant.now().toString(),
                                // THE CHECKSUM IS THE POINT OF THE RECORD, and until this was passed
                                // it was the one thing the record did not carry. `DwReportExport` has
                                // had the column since the feature shipped and the web's report
                                // history is built on it: `reportDiff.identicalFile` and `sameFileAs`
                                // — "the revised copy you sent was the same file as last time" —
                                // answer from this and from nothing else. Every report this app
                                // generated used to land in that history as a row the comparison
                                // could say nothing about. Both come from ReportExport, which is the
                                // only place the finished bytes exist as a file it can read.
                                fileSizeBytes = exported.sizeBytes,
                                checksumSha256 = exported.checksumSha256,
                                warnings = (warnings + plan.warnings).joinToString("\n")
                                    .takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }
                exported
            }.onSuccess { exported ->
                result = exported
                onMessage("Report saved to ${exported.displayLocation}")
            }.onFailure { error ->
                onError(error.message ?: "The report could not be generated.")
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Report", display = true, color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp)

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Preparing…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }
            return@Column
        }

        SearchableSelectField(
            label = "Template",
            options = templateOptions(templates),
            selectedValue = templateId,
            includeNone = false,
            onSelect = { picked -> if (picked.isNotBlank()) templateId = picked }
        )

        ReportAccentPicker(accent = accent, onAccent = { accent = it })

        Text(
            "$percent% of the required fields across the 22 stages are filled in.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        if (warnings.isEmpty()) {
            Text(
                "Every required field is answered.",
                color = MaterialTheme.field.success,
                fontSize = 12.sp
            )
        } else {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.warningContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${warnings.size} stage(s) are incomplete",
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // The export is NOT blocked by these. A workshop report missing four Advanced
                    // measurements is still the document the officer standing in the room is asking
                    // for, and a client that refused to produce it would simply be worked around.
                    Text(
                        "The report can still be generated. These fields will print as blanks.",
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 11.sp
                    )
                    warnings.forEach {
                        Text("· $it", color = MaterialTheme.field.onWarningContainer, fontSize = 11.sp)
                    }
                }
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { export("DOCX") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export .docx")
            }
            Button(onClick = { export("PDF") }, enabled = !busy, modifier = Modifier.weight(1f)) {
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
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Rendering the report on this device…", color = MaterialTheme.field.muted, fontSize = 13.sp)
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
                Text("Saved", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(exported.displayLocation, color = MaterialTheme.field.body, fontSize = 12.sp)
                // WHAT THIS FILE DOES NOT CARRY, said beside the file rather than written into it.
                // A designer who is told the PDF is not in their chosen typeface can send the .docx
                // instead; the same difference unmentioned is a defect somebody else notices first,
                // in an office, about a document that has already been submitted.
                exportNotes.forEach { note ->
                    Text("· $note", color = MaterialTheme.field.warning, fontSize = 11.sp)
                }
                if (exported.droppedImages.isNotEmpty()) {
                    // Reported, not thrown. A report missing one photograph is still worth having;
                    // an export that aborted after twenty minutes of rendering is not.
                    Text(
                        "${exported.droppedImages.size} photograph(s) could not be embedded and were left out.",
                        color = MaterialTheme.field.warning,
                        fontSize = 11.sp
                    )
                }
                if (exported.shareUri != null) {
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = exported.mimeType
                                putExtra(Intent.EXTRA_STREAM, exported.shareUri)
                                // Without this the receiving app gets a Uri it has no permission to
                                // read, and the share silently produces an empty attachment.
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
                    // On Android 10 and above the file lands in the public Downloads collection and
                    // every app on the device can already open it from there, so there is no Uri to
                    // grant and nothing to share FROM this app. Saying where it is beats offering a
                    // share button that would have to re-copy the file to work.
                    Text(
                        "Open it from the Downloads folder, or attach it from any app's file picker.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

// --------------------------------------------------------------------------------------
// The report's colour
// --------------------------------------------------------------------------------------

/**
 * Twelve named colours and a hex box — the whole colour choice, on a phone.
 *
 * ONE ACCENT, SEVEN DERIVED. `themeFromAccent` computes the soft accent, the ink, the muted grey,
 * the rules, the zebra fill and — by measured contrast, never by hue — the table header's text, so
 * there is no combination of separately-chosen colours here to get wrong. That is what makes it
 * safe to offer this on a screen with no preview: a designer cannot produce an unreadable table
 * header from this control, whatever they pick.
 *
 * NO COLOUR WHEEL, DELIBERATELY, and it is worth saying rather than half-building one. A hue/
 * saturation canvas on a five-inch screen held in a workshop is a control nobody can hit a
 * specific colour with, and the specific colour is the entire point — an implementing agency's
 * brand hex has to be reproduced exactly, not approximated with a fingertip. So the twelve
 * presets cover the cases anybody chooses in practice and the box below takes the exact value for
 * the case they do not. The web report page has the platform's own picker for anyone who wants to
 * browse colours, and the answer it saves to stage 20 is read straight back in here.
 *
 * The swatch shows the DERIVED accent, not the requested one: a colour too pale to read as a
 * heading is darkened by the derivation, and a chip showing the pale version while the file gets
 * the dark one would be lying at the moment it is being trusted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportAccentPicker(accent: String, onAccent: (String) -> Unit) {
    val normalised = normaliseHex(accent)
    val derived = if (normalised == null) null else themeFromAccent(normalised).accent
    // Typing is held locally so a half-typed "#1F3" does not repaint the chips on every keystroke
    // and does not overwrite the choice with rubbish; only a complete, parseable colour commits.
    var typed by remember(accent) { mutableStateOf(normalised?.let { "#$it" } ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Report colour", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "One accent colour. The headings, the table headers, the rules and the figures are all " +
                "derived from it. These twelve stay apart when the report is printed on a " +
                "black-and-white printer.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
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
                    Text(preset.label, color = MaterialTheme.field.body, fontSize = 12.sp)
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
                    // Only a colour commits. An incomplete one leaves the previous choice standing
                    // rather than clearing it, so backspacing one digit does not silently reset the
                    // report to the default.
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
                "That colour is too pale to read as a heading on white paper, so the report uses " +
                    "#$derived — the same colour, darkened just enough to be legible.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (normalised == null) {
                    "No colour chosen — the report is written in the standard indigo."
                } else {
                    "This export only. Stage 20's saved colour is unchanged."
                },
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            if (normalised != null) {
                OutlinedButton(onClick = { onAccent("") }) { Text("Clear") }
            }
        }
    }
}

/** A colour chip. Ringed, because half of the twelve are dark enough to merge into a dark theme. */
@Composable
private fun Swatch(hex: String) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(Color(android.graphics.Color.parseColor("#$hex")), RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(4.dp))
    )
}

// --------------------------------------------------------------------------------------
// Building the document from the registry
// --------------------------------------------------------------------------------------

/**
 * Resolve an [ImageRef] to bytes by reading the durable copy at its absolute path.
 *
 * Returning null rather than throwing is the contract [ImageLoader] states: a photograph whose bytes
 * have gone missing costs the report one picture and one warning, not the whole export.
 */
private fun deviceImageLoader(): ImageLoader = { ref ->
    runCatching { File(ref.source).takeIf { it.exists() }?.readBytes() }.getOrNull()
}

/**
 * Walk the registry and turn one workshop's stored answers into a [ReportDocument].
 *
 * DISPATCH IS ON `reportRole`, NOT ON FIELD NAME, which is what keeps this from becoming 22 hand-written
 * report sections that drift from the 22 hand-written forms nobody wrote either. The roles come
 * straight from the registry:
 *
 *   NARRATIVE  → a paragraph of prose            METRIC   → a headline number in a metric row
 *   BULLETS    → a bulleted list                 GALLERY  → a photo grid, captioned by `captionFor`
 *   KEY_VALUE  → a label/value pair              HIDDEN   → captured, retained, never printed
 *   COVER_FIELD→ a row of the cover-page table   CAPTION  → drawn with its media, never alone
 *
 * A field with no answer prints NOTHING — [DocumentBuilder.para] and `keyValues` both drop empties —
 * so a workshop that only captured the Basic tier produces a short report rather than a long one full
 * of blank lines.
 */
private fun buildWorkshopDocument(
    context: Context,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    workshopId: String,
    templateId: String,
    warnings: List<String>,
    accent: String,
    format: String = "DOCX",
    plan: ReportPlan? = null,
): ReportDocument {
    val mediaById = draft?.media.orEmpty().associateBy { it.id }
    return buildWorkshopDocument(
        format = format,
        plan = plan,
        schema = schema,
        draft = draft,
        workshopId = workshopId,
        templateId = templateId,
        warnings = warnings,
        accent = accent,
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
                    // Carried through so a portrait photograph is not printed on its side. The
                    // rotation was resolved from EXIF at import; re-reading it here would be
                    // re-reading metadata a later re-encode may already have stripped.
                    rotationDeg = descriptor.rotationDegrees,
                    mimeType = descriptor.mimeType,
                )
            }
        },
    )
}

/**
 * The same build, with the ONE Android dependency — turning a media id into a file on disk — lifted
 * out into [imageFor].
 *
 * Split so the document can be built on a desktop JVM and asserted against. It is not a refactor for
 * its own sake: this function had no test of any kind, and it silently dropped all 98 RICH_TEXT
 * fields — every narrative and bulleted list in the report — for as long as it has existed, because
 * the omission is invisible from the outside. The prose is on screen in the editor right up to the
 * moment of export, the headings still print, and only the paragraphs beneath them are missing. A
 * `Context` in the signature was the whole reason nothing could assert on the output.
 */
internal fun buildWorkshopDocument(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    workshopId: String,
    templateId: String,
    /**
     * Accepted and ignored. THE CATALOGUE IS THE AUTHORITY FOR A TEMPLATE'S NAME now.
     *
     * That name is printed — it is half of the running foot and the document's own template line —
     * so taking it from an API response, or from a picker label that came from a registry enum,
     * is how a file could come to name one template while being built from another's sections.
     * Kept in the signature so that no existing caller has to change.
     */
    @Suppress("UNUSED_PARAMETER") templateName: String = "",
    warnings: List<String>,
    accent: String,
    imageFor: (String) -> ImageRef?,
    /** "DOCX" or "PDF". Only the WARNINGS differ between them — see [reportPlanFor]. */
    format: String = "DOCX",
    /**
     * Supplied, never clock-read inside the writers, so re-exporting the same workshop twice
     * produces two identical files rather than two that differ only in a hidden timestamp.
     */
    generatedAt: String = Instant.now().toString(),
    /**
     * The already-resolved plan, when the caller has one.
     *
     * The report screen resolves it before it renders so that what it TELLS the designer and what
     * it WRITES cannot come from two different resolutions of the same settings — the failure the
     * server's `_SECTION_TOGGLES` comment records, where two call sites each decided for themselves
     * which stage-20 keys mattered and the designer approved one document and submitted another.
     */
    plan: ReportPlan? = null,
): ReportDocument {
    val resolved = plan ?: reportPlanFor(
        schema = schema,
        draft = draft,
        workshopId = workshopId,
        requestedTemplateId = templateId,
        requestedAccent = accent,
        format = format,
        generatedAt = generatedAt,
    )
    val template = resolved.template
    val builder = DocumentBuilder(meta = resolved.meta, theme = resolved.theme)
    warnings.forEach { builder.warn(it) }
    resolved.warnings.forEach { builder.warn(it) }

    // Built ONCE for the whole document. A cost sheet resolves its product reference on every row and
    // a stage can carry two hundred lines, so re-walking the draft per cell is quadratic in exactly
    // the stage that is largest — the same reason `ReportBuilder._rows_by_id` is built in __init__.
    val refs = DwRefLabels(schema, draft)
    // ONE PER DOCUMENT, and that is what makes a figure print once. DCH_STANDARD asks for the yield
    // chart at the front AND carries the outcomes stage that owns it; a builder rebuilt per section
    // would print the same picture twice — see [DwFigures].
    val figures = DwFigures(schema, draft)
    val stages = schema.stages.associateBy { it.key }

    // THE TEMPLATE'S SECTION LIST IS THE DOCUMENT, and this loop is the whole of `ReportBuilder.build`.
    // What it replaces is `schema.stages.sortedBy { it.number }`, which printed CAPTURE order — the
    // designer's — where a reviewing officer expects the narrative order, printed stages 20 and 21
    // back at the ministry (the report describing its own generation, export checksums and all), and
    // gave all six templates the same twenty-two sections under the same headings.
    template.sections.forEach { section ->
        val special = section.special
        if (special != null) {
            renderSpecialSection(
                builder, special, section, resolved, schema, draft, imageFor, refs, figures,
            )
        } else {
            stages[section.stageKey]?.let { stage ->
                renderStageSection(
                    builder, stage, section, resolved, schema, draft, imageFor, refs, figures,
                )
            }
        }
    }

    return builder.build()
}

/**
 * One of the 22 stages, as the template's section for it asks for it — `ReportBuilder._render_stage`.
 *
 * Everything a section can say is honoured here: its own heading in place of the stage's title (which
 * is most of the difference between the DCH and the DIC formats), a page break before it, an intro
 * line, the entity subset, whether photographs print and how many to a row, the capture tier, and
 * whether every heading it emits is numbered.
 *
 * THE STAGE'S `purpose` IS NO LONGER PRINTED, and its absence is the one deliberate content change in
 * this area. That text is addressed to the DESIGNER — stage 20's reads "Choose the template, decide
 * what the report contains, generate it" — and it was appearing as the lead paragraph of a section in
 * a document submitted to a ministry, on the phone's copy only. `section.intro` is what the server
 * prints in that position and it is what prints here.
 */
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
) {
    val template = plan.template
    val options = RenderOptions(
        maxTier = template.maxTier,
        includePhotos = section.includePhotos,
        photoColumns = section.photoColumns,
        maxPhotos = section.maxPhotos,
        numbered = template.numberHeadings,
    )
    val stored = draft?.stages?.get(stage.key)
    val singletonValues = stored?.values.orEmpty()
    val hasCollections = stage.collections.any { stored?.rowsFor(it.key).orEmpty().isNotEmpty() }
    // A stage with nothing in it is skipped entirely. Printing 22 headings with nothing under
    // them turns a five-page report into a twenty-page one that says the same amount.
    if (singletonValues.isEmpty() && !hasCollections && section.omitIfEmpty) return

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { stage.title },
        level = 1,
        numbered = template.numberHeadings,
    )
    if (section.intro.isNotBlank()) builder.para(section.intro, style = ParaStyle.LEAD)

    var wrote = false
    stage.singleton?.let { entity ->
        if (singletonValues.isNotEmpty()) {
            wrote = renderEntity(builder, entity, singletonValues, imageFor, refs, options) || wrote
        }
    }

    stage.collections.forEach { entity ->
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
        // Only where the stage has more than one, because a lone collection is already named by the
        // stage heading directly above it.
        if (stage.collections.size > 1) {
            builder.heading(entity.title, level = 2, numbered = template.numberHeadings)
        }

        // A CHILD COLLECTION IS PRINTED UNDER ITS PARENTS, one sub-heading each — see
        // [dwParentGroups] for the document that could not be read without it. `null` is the
        // answer for 38 of the registry's 43 entities and it takes the identical single call
        // this loop has always made, so nothing about a parent-free stage moves.
        //
        // The guard on emptiness is belt and braces, not a case that can arise: [rows] is
        // non-empty here and every row lands in exactly one bucket. But this runs in a cluster
        // with nothing to report a bug to, and a mistake in there dropping a whole collection
        // out of a delivered file is far worse than printing it flat.
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
        // ONE LEVEL BELOW WHATEVER NAMED THIS COLLECTION, which is the entity heading above when the
        // stage has several collections and the STAGE's own heading when it has one — stage 14
        // declares only `prototypeIteration`, so its groups answer to the H1. A group at the same
        // level as the thing it is part of would read as a new collection and would renumber the
        // rest of the report. Both levels are ones the two writers already style and outline
        // (`DocxWriter.emitHeading` indexes a four-element table by level and
        // `DocumentBuilder.heading` coerces to 1..4); a fifth would be an index out of bounds in the
        // middle of an export a designer is waiting on.
        val groupLevel = if (stage.collections.size > 1) 3 else 2
        groups.forEach { group ->
            builder.heading(group.heading, level = groupLevel, numbered = template.numberHeadings)
            wrote = renderCollection(
                builder, entity, group.rows, imageFor, refs,
                // A record falls BELOW its group rather than beside it. Capped at 4 for the same
                // reason the server caps it there: `min(4, level + 1)`.
                rowLevel = minOf(4, groupLevel + 1),
                options = options.copy(presentation = section.presentation),
            ) || wrote
        }
    }

    if (section.includeFigures) {
        // AFTER the stage's own content, never before it. A figure is a summary of the table above
        // it, and a reader who meets the picture first reads the numbers in the table as a breakdown
        // of the chart rather than as the record the chart was derived from.
        //
        // `includeFigures` is the switch PHOTO_CATALOGUE turns OFF on its price list, and it is not
        // decoration: the cost-by-head figure prints the maker's material and labour cost beside the
        // price the buyer is being quoted, so honouring it is what stops a handset handing a buyer
        // the cluster's margin.
        figures.chartsFor(stage.key).forEach { chart ->
            builder.add(chart)
            wrote = true
        }
    }

    // A heading with nothing under it is the commonest way a generated report looks broken. Saying
    // so beats a blank half page, and a template can turn it off.
    if (!wrote && template.showEmptyNote) builder.para("Not recorded.", style = ParaStyle.NOTE)
}

/**
 * The sections that are not one of the 22 stages.
 *
 * NINE OF THE TEN ARE BUILT HERE — the cover, the contents, the metric row, the acknowledgement, the
 * photographic record, the completeness table, the sign-off, the locator map and the infographics.
 * The tenth, the transcript annexure, is SKIPPED IN SILENCE HERE AND NAMED IN THE WARNINGS, which
 * [reportPlanFor] assembled from this same template. That split is deliberate: a warning belongs to
 * the act of generating and not to the document, so the officer who opens the .docx next month does
 * not find a note about what a handset could not draw on the day, while the designer standing beside
 * them on the day is told plainly. The document itself carries one provenance line on its cover
 * ([fieldCopyNote]) naming what the office's copy additionally has — which is a statement of fact
 * about the file, and belongs in it.
 */
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
            renderMediaAnnexure(builder, section, plan, schema, draft, imageFor)
        SpecialSection.COMPLETENESS ->
            renderCompletenessAnnexure(builder, section, plan, schema, draft, refs)
        SpecialSection.MAP -> renderMap(builder, section, plan, draft)
        SpecialSection.CHART -> renderCharts(builder, section, plan, figures)

        // The one section this device still does not emit, and the reason is the TEXT and not the
        // drawing: design-workshop audio is transcribed server-side by the media queue and the words
        // land on `MediaFile.transcriptText`, which no draft on this handset carries and no endpoint
        // this client binds ever asks for. The recordings are here; their transcripts are not — see
        // the ledger entry for `includeTranscripts`. Emitting half of one — a numbered heading over
        // nothing — would be worse than the honest gap, because a heading in the contents that leads
        // nowhere reads as a corrupt file.
        SpecialSection.ANNEXURE_TRANSCRIPTS -> Unit

        // The second, for the same KIND of reason and a sharper version of it: the answers recorded
        // against a questionnaire live in `QuestionnaireFormAnswer` on the server, and
        // `WorkshopRepository`'s "Custom questionnaires" block falls back to the device for NOTHING —
        // deliberately, because a cached form cannot know that a question was retired an hour ago and
        // an answer given under superseded wording is fabricated evidence. So there is nothing here
        // to draw, and there is not supposed to be. The designer is told at the export screen (see
        // `UNSUPPORTED_SECTIONS`); the cover note stays silent about it because this device cannot
        // tell an unattached workshop from an attached one offline, and a report apologising for the
        // absence of a questionnaire nobody attached is a false alarm on most exports.
        SpecialSection.ANNEXURE_QUESTIONNAIRES -> Unit
    }
}

/**
 * Every photograph in the record, in stage order, as a contact sheet —
 * `ReportBuilder._render_media_annexure`.
 *
 * IT WALKS THE REGISTRY AND NOT THE TEMPLATE'S SECTION LIST, exactly as the server does. The
 * annexure's claim is "this is the photographic record of the workshop", so it is drawn from what
 * was CAPTURED rather than from what this template chose to print in its narrative. A designer who
 * excluded a stage, or a template that reduces the cluster background to a two-line annexure, still
 * has those photographs in the record and the office's copy still prints every one of them here;
 * walking the sections instead would hand two contact sheets of the same workshop different numbers
 * of plates and nothing in either file would admit it.
 *
 * TWO SERVER BEHAVIOURS ARE REPRODUCED RATHER THAN IMPROVED ON, because a difference here is a
 * difference between the phone's copy and the office's, which is the whole defect this area exists
 * to end. `_render_media_annexure` reads neither `section.include_photos` nor `section.photo_columns`
 * — the contact sheet is three across whatever stage 20 said about photo columns, and it still
 * prints when `includePhotographs` is off. `includeMediaAnnexure` is the switch that removes it, and
 * that one is honoured in [applyReportSettings] before this is ever reached.
 *
 * ONE BITMAP AT A TIME, WHICH IS WHY THE WHOLE SET GOES INTO ONE [ImageGridBlock] rather than being
 * decoded here. `PdfWriter.drawImage` decodes each photograph downsampled to the cell it will
 * actually occupy and recycles it immediately instead of leaving it to the GC — a sixty-photo
 * annexure is sixty sequential decodes and never sixty live bitmaps, which is the difference between
 * an annexure and an OutOfMemoryError on the cheapest phone in the room.
 */
private fun renderMediaAnnexure(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    imageFor: (String) -> ImageRef?,
) {
    val gathered = ArrayList<Pair<ImageRef, String>>()
    // Stage order, and the registry's own order within a stage. A contact sheet whose plates run in
    // the order the phone happened to store them cannot be read against the report it belongs to.
    schema.stages.sortedBy { it.number }.forEach { stage ->
        val stored = draft?.stages?.get(stage.key)
        stage.entities.forEach { entity ->
            val sources = if (entity.cardinality == "SINGLETON") {
                listOf(stored?.values.orEmpty())
            } else {
                stored?.rowsFor(entity.key).orEmpty().map { it.values }
            }
            sources.forEach { values ->
                // The SAME resolver the stage sections use, deliberately: a photograph that prints
                // on page 20 and is missing from the annexure — or the reverse — is one document
                // disagreeing with itself about what was photographed.
                imagesOf(entity, values, imageFor, plan.template.maxTier).forEach { (ref, caption) ->
                    // The stage's own title where the photograph has no caption of its own. A plate
                    // with no line under it is a picture, not evidence.
                    gathered += ref to caption.ifBlank { stage.title }
                }
            }
        }
    }
    if (gathered.isEmpty()) return

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { "Photographic record" },
        level = 1,
        numbered = plan.template.numberHeadings,
    )
    builder.add(ImageGridBlock(images = gathered, columns = 3))
}

/**
 * What the record does and does not contain, stage by stage — `ReportBuilder._render_completeness`.
 *
 * It goes through the same [computeStageCompleteness] the stage screens score themselves with, so
 * there is one definition of "filled" on this device and not two — with ONE addition that only a
 * report can make, and the difference is deliberate on the server too. [maskUnresolvableRefs] applies
 * `ref_resolves`, so a reference whose row was deleted counts as unfilled HERE while the percentage
 * above the export buttons still counts it as filled. That is not the two disagreeing by accident:
 * the headline figure is the form's own score, computed with nothing to resolve an id against, and
 * the annexure's job is to agree with the rest of THIS DOCUMENT, which blanks that same reference
 * eighteen pages earlier. The server splits it identically — `stage_completeness` takes
 * `ref_resolves` only from the report.
 *
 * NO PAGE BREAK EVEN WHERE THE SECTION ASKS FOR ONE, because the server's renderer does not read
 * `section.page_break_before` here and the only template that carries this section does not set it.
 * Honouring it would move a page boundary on the phone's copy alone.
 */
private fun renderCompletenessAnnexure(
    builder: DocumentBuilder,
    section: TemplateSection,
    plan: ReportPlan,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    refs: DwRefLabels,
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

/**
 * One record's values with every REF that no longer resolves REMOVED, so the scorer counts a field
 * exactly as the renderer prints it — the port of `ReportBuilder.ref_resolves`.
 *
 * THE SCORER AND THE RENDERER MUST AGREE, and the server's own comment records what happens when
 * they do not: the completeness annexure read "13. Prototype Development | 144/144 | 100% |
 * Complete" while eighteen pages earlier the same submitted document printed "Prototype | Not
 * recorded." thirty-six times, for the very fields it had just counted as filled. The renderer
 * blanks an id whose row was deleted ([displayValue]) and the plain scorer only checks that the
 * string is non-empty. One document, two answers about one field.
 *
 * Masking the value rather than teaching [computeStageCompleteness] a second rule is what keeps the
 * counting in one place: the stage form on this phone scores itself with no document around it and
 * nothing to resolve references against, so "does this id still point at something" is a question
 * only the REPORT can ask — which is exactly why the server passes it in as an argument too.
 *
 * `refs.label(...)` and NOT [displayValue] is the resolution test, matching the server's
 * `bool(self._ref_label(value))`. The two differ for a REF holding hand-typed text like "SK-01",
 * which the renderer prints and the scorer counts as unfilled; that asymmetry is the server's and
 * copying it is what keeps the two completeness tables identical.
 */
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
    // The common case by far — 38 of the registry's 43 entities name no REF at all — and returning
    // the map itself avoids copying every record of every stage to change nothing.
    if (unresolved.isEmpty()) return values
    return values - unresolved.toSet()
}

/**
 * The title page — `ReportBuilder._render_cover`.
 *
 * WHAT THIS ENDS. A report exported on the handset — the copy handed to the visiting officer at the
 * close of the workshop, which is the entire reason on-device export exists — opened on a table of
 * contents. No title page, no organisation line, no "submitted to", no submission date and no logo,
 * while the office's copy of the same workshop had all of it. [CoverBlock] was fully rendered by both
 * writers the whole time and was simply never constructed.
 *
 * `meta.organisation` and NOT `template.organisation`: the template's constant is the LAST of three
 * and [reportMetaFor] has already applied the precedence — stage 20's `organisationLine`, whose own
 * help text says "Printed above the title on the cover", then the workshop's implementing agency,
 * then the template's. Reading the template directly here is exactly what made that field inert on
 * the server: a designer typed their institute's name and the cover printed the ministry's.
 */
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
        visibleFields(entity, plan.template)
            .filter { it.reportRole == "COVER_FIELD" }
            .mapNotNull { field ->
                displayValue(field, setupValues[field.key], refs)
                    .takeIf { it.isNotBlank() }
                    ?.let { field.label to it }
            }
    }.orEmpty()

    val hero = if (section.includePhotos && setupEntity != null) {
        imagesOf(setupEntity, setupValues, imageFor, plan.template.maxTier, limit = 1).firstOrNull()?.first
    } else {
        null
    }

    // THE LOGO STAGE 20 ASKS FOR, which was declared, chosen, stored on this device — and never
    // resolved, so the cover of every report carried no mark at all. It comes from the workshop's
    // own media copy, which is why it prints with no network.
    val logo = mediaIdsOf(plan.settings["logo"]).firstOrNull()?.let(imageFor)

    val org = plan.meta.organisation.ifEmpty { plan.template.organisation }
    // The block of address lines an institution puts above its own name, one paragraph per line.
    // Capped so a pasted signature block cannot push the title off the page.
    val letterhead = cleanText(settingText(plan.settings, "letterheadText"))
        .split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(6)

    // THE ONE LINE THAT SAYS WHICH COPY THIS IS, in the slot the server prints "Generated on <date>"
    // in and carrying that same date. See [fieldCopyNote] for why it is in the FILE and not only in
    // the export warnings: the warnings are dismissed on the day, and the person who needs to know
    // that the office's copy of the same workshop carries more is the officer opening the .docx next
    // month. It is a cover footer line, so it is never a heading and never enters the contents.
    val generatedOn = fieldCopyNote(
        plan.template,
        plan.settings,
        plan.meta.generatedAt.take(10).let(::formatReportDate),
    )

    builder.add(
        CoverBlock(
            title = plan.meta.title,
            subtitle = plan.meta.subtitle,
            orgLines = (listOf("Government of India • Ministry of Textiles", org) + letterhead)
                .filter { it.isNotEmpty() },
            logo = logo,
            heroImage = hero,
            // A cover table longer than this stops being a cover.
            infoRows = infoRows.take(10),
            footerLines = listOf(submissionLine(plan.settings), generatedOn).filter { it.isNotEmpty() },
        )
    )
}

/**
 * Headline counts, derived from the records unless stage 18 states otherwise —
 * `ReportBuilder._render_summary_metrics`.
 *
 * Deriving them means the report and the data can never disagree; a hand-typed count is a second
 * source of truth that goes stale the moment one more sketch is added. The stage's OVERRIDE is the
 * exception the stage exists to allow, and the designer's reason for it is printed directly under
 * the row rather than forty pages away beside a raw field label.
 */
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

    // UNDER THE ROW, not in the metric's unit slot: that slot is drawn inline after the big number
    // and a sentence there reads as a unit of measurement.
    val overridden = listOf("designsCountOverride", "prototypesCountOverride")
        .any { asFiniteNumber(outcomes[it]) != null }
    val reason = if (overridden) DwValues.text(outcomes["countOverrideReason"]).trim() else ""
    if (reason.isNotEmpty()) builder.para("Stated counts: $reason", style = ParaStyle.NOTE)
}

/** The sign-off block — `ReportBuilder._render_signatures`. Nothing at all when nobody is named. */
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
}

/**
 * A stored value as a finite number, or null for anything that is not one — `report_builder._as_number`.
 *
 * A boolean is rejected before the numeric read: in Python `bool` is an `int` subclass, so a BOOL
 * field would otherwise contribute 1 to a stated count. NaN and the infinities are rejected because
 * they survive a parse happily and then poison every total they touch.
 *
 * INTERNAL because the infographics read every number they chart through it ([DwFigures]). One
 * definition, so the cost-by-head figure and the metric row above it cannot disagree about whether a
 * cell holding "true" or "NaN" is a number.
 */
internal fun asFiniteNumber(value: JsonElement?): Double? {
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString && (primitive.content == "true" || primitive.content == "false")) return null
    return primitive.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** An IMAGE or IMAGE_LIST value as media ids — `report_builder._media_ids`. */
private fun mediaIdsOf(value: JsonElement?): List<String> = when (value) {
    null -> emptyList()
    is JsonArray -> DwValues.list(value).filter { it.isNotBlank() }
    else -> listOfNotNull(DwValues.text(value).takeIf { it.isNotBlank() })
}

/**
 * The fields this template's capture tier lets print — `ReportBuilder._visible`.
 *
 * The compact summary caps at BASIC, which is what makes it a few pages rather than sixty. Ignoring
 * the cap was most of the reason the "Compact summary" template produced the same document as the
 * detailed technical one.
 */
private fun visibleFields(entity: EntityDto, template: ReportTemplate): List<FieldDto> =
    entity.liveFields.filter { DwTier.of(it.tier).ordinal <= template.maxTier.ordinal }

/**
 * Everything one section needs to say about HOW a record prints, carried as one argument.
 *
 * Defaulted throughout so a call site that has no section — a test, or a caller that only wants a
 * record's fields — behaves exactly as this file behaved before templates reached it.
 */
internal data class RenderOptions(
    val maxTier: DwTier = DwTier.ADVANCED,
    val includePhotos: Boolean = true,
    val photoColumns: Int = 2,
    /** 0 = no cap; a photo catalogue wants every one. */
    val maxPhotos: Int = 0,
    /** Whether the sub-headings a collection emits carry section numbers. */
    val numbered: Boolean = false,
    val presentation: Presentation = Presentation.AUTO,
)

/**
 * Every resolvable image on one record, paired with its caption.
 *
 * [limit] of 0 is no cap. Split out of [renderEntity] because the cover's hero photograph and a
 * GALLERY section need the same list without the rest of a record's fields.
 */
private fun imagesOf(
    entity: EntityDto,
    values: Map<String, JsonElement>,
    imageFor: (String) -> ImageRef?,
    maxTier: DwTier,
    limit: Int = 0,
): List<Pair<ImageRef, String>> {
    val visible = entity.liveFields.filter { DwTier.of(it.tier).ordinal <= maxTier.ordinal }
    val captionByTarget = visible.filter { it.captionFor.isNotBlank() }.associateBy { it.captionFor }
    val gathered = ArrayList<Pair<ImageRef, String>>()
    visible.forEach { field ->
        if (field.reportRole == "HIDDEN" || field.captionFor.isNotBlank()) return@forEach
        val type = DwFieldType.of(field.type)
        if (!type.isMedia) return@forEach
        val stored = values[field.key]
        if (!DwValues.isFilled(stored)) return@forEach
        val caption = captionByTarget[field.key]?.let { DwValues.text(values[it.key]) }.orEmpty()
        val ids = if (type == DwFieldType.IMAGE_LIST) DwValues.list(stored) else listOf(DwValues.text(stored))
        ids.mapNotNull(imageFor).forEach { ref -> gathered += ref to caption.ifBlank { field.label } }
    }
    return if (limit > 0) gathered.take(limit) else gathered
}

/**
 * One picture large, several as a grid — `ReportBuilder._place_images`.
 *
 * A single photograph in a one-column grid is drawn at the grid's cell width with a grid's spacing
 * around it; as an [ImageBlock] it is drawn at 62% of the text column, which is what one prototype
 * photograph under its own heading should look like.
 */
private fun placeImages(builder: DocumentBuilder, images: List<Pair<ImageRef, String>>, columns: Int) {
    if (images.isEmpty()) return
    if (images.size == 1) {
        val (ref, caption) = images.first()
        builder.add(ImageBlock(image = ref, widthPct = 62.0f, caption = caption))
        return
    }
    builder.add(ImageGridBlock(images = images, columns = columns.coerceIn(1, 4)))
}

/**
 * What a REF value should PRINT: the name of the thing it points at, never its record id.
 *
 * THIS IS WHY A COST SHEET ON THE PHONE WAS HEADED `cmsik2jg8000eh8xc1lcy661a`. Eleven REF fields in
 * the registry are printed — ten TABLE_COLUMNs and one key/value — and five entities name a REF as
 * their `labelField`, which is the right design (a cost sheet IS labelled by its product, a review by
 * its sketch). But [displayValue] had no REF arm, so every one of them fell through to
 * `DwValues.text`, which returns a JsonPrimitive's content verbatim, and the raw cuid went into a
 * document submitted to a ministry — with whole sub-sections titled by one. The server grew
 * `ReportBuilder._ref_label` to fix precisely this; the phone, which writes the same report from the
 * same registry, never got it. This is that function.
 *
 * ROWS ARE INDEXED UNDER BOTH IDS A REFERENCE CAN BE HOLDING. A picker fills in the id the server
 * serves for the row (`_entryId`), but a row created in a courtyard has no server id yet, so a
 * reference made between two unsynced rows holds the local [DraftRow] key instead. Indexing only the
 * first would leave exactly the offline-authored workshop — the one this whole export path exists for
 * — still printing ids.
 *
 * RESOLUTION IS RECURSIVE, because the labels chain: a cost sheet's label is its `productRef`, and a
 * final product's label is its `name`. [seen] breaks a cycle rather than trusting the data not to
 * contain one — two rows referring to each other is a stack overflow in the middle of generating a
 * report, and the data comes from a phone.
 */
internal class DwRefLabels(
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    /**
     * The label for an id that belongs to an EXTERNAL record — an artisan, a documented product —
     * rather than to a row of this workshop. The server reads these from the database; the phone can
     * only offer what some picker has already cached, which is best-effort by nature and correct to
     * treat as such: an id that resolves to nothing prints nothing, never itself.
     */
    private val externalLabel: (String) -> String? = DwReferenceStore::labelFor,
) {
    private val rowsById = HashMap<String, Pair<EntityDto, Map<String, JsonElement>>>()
    private val cache = HashMap<String, String>()

    init {
        val entities = schema.stages.flatMap { it.entities }.associateBy { it.key }
        draft?.stages?.values.orEmpty().forEach { stage ->
            stage.rows.forEach { row ->
                val entity = entities[row.entityKey()] ?: return@forEach
                // The local key first and the server's id second, so that where a row somehow answers
                // to both the SERVER's id wins the slot — it is the one a synced reference holds.
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
        // Checked AFTER the cache and before anything is written to it, so a cycle yields "" for this
        // one hop without that "" being remembered as the answer for the whole document.
        if (refId in seen) return ""

        var label = rowsById[refId]?.let { (entity, row) -> rowLabel(entity, row, seen + refId) }.orEmpty()
        if (label.isBlank()) label = externalLabel(refId).orEmpty()

        // Cached even when empty: an unresolvable id is looked up once per document rather than once
        // per row, and an empty answer is a real answer — the row it named was deleted.
        cache[refId] = label
        return label
    }

    /** The label field's printed value, following a REF label through to a name. */
    private fun rowLabel(entity: EntityDto, row: Map<String, JsonElement>, seen: Set<String>): String {
        entity.field(entity.labelField)?.let { spec ->
            val text = if (DwFieldType.of(spec.type) == DwFieldType.REF) {
                label(dwRefId(row[spec.key]).trim(), seen)
            } else {
                displayValue(spec, row[spec.key], this)
            }
            if (text.isNotBlank()) return text
        }
        // The first free-text answer, truncated. A row whose label field was never filled in is far
        // better headed by the first thing the designer typed into it than by nothing at all.
        entity.liveFields.forEach { spec ->
            if (!DwFieldType.of(spec.type).isFreeText) return@forEach
            val text = displayValue(spec, row[spec.key], this)
            if (text.isNotBlank()) return text.take(80)
        }
        return ""
    }
}

/**
 * Whether a resolved-to-nothing REF is holding an opaque record id or something a person typed.
 *
 * Byte-for-byte `report_builder._OPAQUE_ID`. Deliberately broader than "is this a cuid", because the
 * question is not that — it is "would a reader recognise this as a name". "SK-01", "Runner v2" and
 * "प्रोटोटाइप 3" all fail it and are printed; "cmsik2jg8000eh8xc1lcy661a" passes it and is not.
 */
private val OPAQUE_ID = Regex("^[a-z0-9]{16,}$")

/**
 * One record's fields, sorted into the report roles the registry declares.
 *
 * Returns whether anything at all reached the document, which is what decides between "Not recorded."
 * and a bare heading over half a blank page. It is measured from [DocumentBuilder.blockCount] rather
 * than tracked by hand because `para`, `bullets` and `keyValues` all silently drop empty content —
 * a record can go through every branch below and add nothing.
 */
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
    val captionByTarget = visible.filter { it.captionFor.isNotBlank() }.associateBy { it.captionFor }
    val printable = visible.filter { it.reportRole != "HIDDEN" && it.captionFor.isBlank() }

    val keyValues = ArrayList<Pair<String, Any?>>()
    val metrics = ArrayList<Triple<String, String, String>>()
    val gallery = ArrayList<Pair<ImageRef, String>>()

    printable.forEach { field ->
        val stored = values[field.key]
        if (!DwValues.isFilled(stored)) return@forEach
        val type = DwFieldType.of(field.type)

        if (type.isMedia) {
            // `includePhotographs = false` and the templates that set it per section — the price
            // list a buyer sees, the narrative stages of the compact summary — stop here. The
            // photographs are not merely hidden by the writer, they never enter the document, so a
            // report that excludes them does not carry their bytes either.
            if (!options.includePhotos) return@forEach
            val caption = captionByTarget[field.key]?.let { DwValues.text(values[it.key]) }.orEmpty()
            val ids = if (type == DwFieldType.IMAGE_LIST) DwValues.list(stored) else listOf(DwValues.text(stored))
            ids.mapNotNull(imageFor).forEach { ref -> gallery += ref to caption.ifBlank { field.label } }
            return@forEach
        }

        // ── RICH_TEXT IS DISPATCHED BEFORE THE ROLE SWITCH, and it has to be ────────────────────
        //
        // A rich value is a JsonObject, and every scalar path below flattens it to nothing:
        // `DwValues.text` answers "" for a JsonObject and `DwValues.list` answers an empty list
        // (StageSchema.kt). So NARRATIVE printed a blank paragraph, which `builder.para` then
        // dropped entirely, and BULLETS printed an empty list, which `builder.bullets` also
        // dropped — all 98 narrative and bulleted fields in the registry, which is the entire prose
        // of the report: the acknowledgement, the brief, the purpose, the cluster background, the
        // designer's comments, the problems faced, the artisan feedback, the recommendations, the
        // marketing strategy. Nothing warned. The headings printed with nothing under them, and the
        // same workshop rendered by the server came out with all of it — two files, one officer's
        // desk, different documents.
        //
        // `toReportBlocks` is the same function the server calls (`rich_text.to_report_blocks`) and
        // the same one the editor's own preview calls, which is why the prose looked right on screen
        // right up to the moment of export. It already merges consecutive BULLET_ITEM/ORDERED_ITEM
        // runs into one list block, so BULLETS needs no separate treatment — the difference between
        // the two roles is only the paragraph style a plain block falls back to.
        if (type == DwFieldType.RICH_TEXT) {
            toReportBlocks(stored, ParaStyle.BODY, imageFor).forEach(builder::add)
            return@forEach
        }

        when (field.reportRole) {
            "NARRATIVE" -> builder.para(displayValue(field, stored, refs))
            "BULLETS" -> builder.bullets(DwValues.list(stored).ifEmpty { listOf(DwValues.text(stored)) })
            "METRIC" -> metrics += Triple(field.label, displayValue(field, stored, refs), field.unit)
            else -> keyValues += field.label to displayValue(field, stored, refs)
        }
    }

    if (metrics.isNotEmpty()) builder.add(MetricRowBlock(metrics = metrics))
    if (keyValues.isNotEmpty()) builder.keyValues(keyValues)
    placeImages(
        builder,
        if (options.maxPhotos > 0) gallery.take(options.maxPhotos) else gallery,
        options.photoColumns,
    )
    return builder.blockCount != before
}

/**
 * A repeating entity in the presentation the template asked for — `ReportBuilder._render_rows`.
 *
 * AUTO is a table where the registry names TABLE_COLUMN fields and cards where it does not, and that
 * fallback is not decoration: a [TableBlock] is only readable while its columns fit the page, and an
 * entity whose fields are mostly LONG_TEXT — a SWOT list, an interview transcript — produces four
 * columns of prose that are unreadable on A4. Where the registry names no TABLE_COLUMN it is saying
 * exactly that.
 *
 * The template can override AUTO, and that override is most of what distinguishes the six formats:
 * the same final-products stage is a CARDS catalogue with one large photograph per product in the
 * photo catalogue and a TABLE in the compact summary.
 */
private fun renderCollection(
    builder: DocumentBuilder,
    entity: EntityDto,
    rows: List<Map<String, JsonElement>>,
    imageFor: (String) -> ImageRef?,
    refs: DwRefLabels,
    /**
     * The level a single RECORD's own heading sits at.
     *
     * Parameterised so that a collection printed under a parent group ([dwParentGroups]) puts its
     * records BELOW that group's heading instead of beside it. A record heading at the group's own
     * level would read as a second group and would renumber everything after it. Defaulted to 3,
     * which is what every ungrouped collection has always used, so this argument changes nothing
     * for the 38 entities that declare no parent.
     */
    rowLevel: Int = 3,
    options: RenderOptions = RenderOptions(),
): Boolean {
    val before = builder.blockCount
    val visible = entity.liveFields.filter { DwTier.of(it.tier).ordinal <= options.maxTier.ordinal }
    val columns = visible.filter { it.reportRole == "TABLE_COLUMN" && !DwFieldType.of(it.type).isMedia }

    // GALLERY prints the pictures and nothing else: every image on every row, under one heading.
    if (options.presentation == Presentation.GALLERY) {
        val every = rows.flatMap { imagesOf(entity, it, imageFor, options.maxTier) }
        placeImages(builder, if (options.maxPhotos > 0) every.take(options.maxPhotos) else every, options.photoColumns)
        return builder.blockCount != before
    }

    // One sub-section per record: heading, then its fields. This is also where a TABLE lands when
    // the registry declares no columns to build one from, exactly as the server falls through.
    val asCards = options.presentation == Presentation.CARDS ||
        (options.presentation != Presentation.NARRATIVE &&
            options.presentation != Presentation.KEY_VALUE &&
            columns.isEmpty())
    if (asCards) {
        rows.forEachIndexed { index, row ->
            builder.heading(rowHeading(entity, row, index, refs), level = rowLevel, numbered = options.numbered)
            renderEntity(builder, entity, row, imageFor, refs, options)
        }
        return builder.blockCount != before
    }

    // NARRATIVE and KEY_VALUE: the rows run on as prose or as label/value blocks, with no table and
    // no per-record heading. It is what the agency format asks for on the outcomes stage, where a
    // heading per record would put a numbered sub-section around a single paragraph.
    if (options.presentation == Presentation.NARRATIVE || options.presentation == Presentation.KEY_VALUE) {
        rows.forEach { row -> renderEntity(builder, entity, row, imageFor, refs, options) }
        return builder.blockCount != before
    }

    builder.add(
        TableBlock(
            columns = tableColumns(columns),
            rows = rows.map { row -> columns.map { cellRuns(it, row, refs) } },
            caption = entity.title,
        )
    )

    // Whatever the table could not carry — the photographs, the prose — follows underneath, per row,
    // rather than being dropped. A sketch table with no sketches in it is a table of file names.
    val leftovers = visible.filter {
        it.reportRole != "TABLE_COLUMN" && it.reportRole != "HIDDEN" && it.captionFor.isBlank()
    }
    if (leftovers.isEmpty()) return true
    // A media leftover in a section that prints no photographs is not a leftover at all. Counting it
    // would head a sub-section for every row of a photographs-off table and then print nothing in it.
    val carried = leftovers.filter { options.includePhotos || !DwFieldType.of(it.type).isMedia }
    rows.forEachIndexed { index, row ->
        if (carried.none { DwValues.isFilled(row[it.key]) }) return@forEachIndexed
        builder.heading(rowHeading(entity, row, index, refs), level = rowLevel, numbered = options.numbered)
        renderEntity(
            builder,
            entity.copy(fields = leftovers + entity.fields.filter { it.captionFor.isNotBlank() }),
            row,
            imageFor,
            refs,
            options,
        )
    }
    return true
}

/**
 * One row's own heading — its label field where it has one, "Cost sheet 3" where it has not.
 *
 * Five entities in the registry name a REF as their `labelField`, so this is the second place the raw
 * id used to reach paper: without it a sub-section of the report is headed by a record id even when
 * the table above it has been fixed. The ordinal fallback is kept for a row whose label field is
 * empty, because a heading is a navigation aid and an unlabelled one is worse than a numbered one.
 */
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

/**
 * One table or key-value cell, as runs rather than as a string.
 *
 * A cell holds RUNS and cannot hold a block, so a RICH_TEXT value in a column cannot go through
 * [toReportBlocks] — it goes through `plainRuns`, which keeps the marks a designer applied and
 * flattens the block structure to one line with the bullet markers still on it. Routing it through
 * [displayValue] instead would drop the marks, and routing it through `runsOf(DwValues.text(...))` —
 * which is what this did — dropped the entire value, because `DwValues.text` answers "" for the
 * JsonObject a rich value is. This is `ReportBuilder._cell_runs`.
 */
private fun cellRuns(
    field: FieldDto,
    row: Map<String, JsonElement>,
    refs: DwRefLabels,
): List<Run> {
    val stored = row[field.key]
    if (DwFieldType.of(field.type) == DwFieldType.RICH_TEXT) return plainRuns(stored)
    return runsOf(displayValue(field, stored, refs))
}

/**
 * Column widths that sum to EXACTLY 100.
 *
 * [TableBlock] throws when they do not, on construction AND on deserialization, and it is right to:
 * a renderer that trusts 118% draws a table wider than the page, which Word silently rescales and the
 * PDF layout silently clips — two different wrong answers from one bad set of numbers. The registry's
 * `columnWidthPct` is a HINT (0 means "share the remainder"), so the hints are honoured, the
 * remainder is split evenly, and the last column absorbs the rounding error rather than letting a
 * 33.33 × 3 = 99.99 fail the export a designer has been waiting on.
 */
private fun tableColumns(fields: List<FieldDto>): List<TableColumn> {
    val hinted = fields.sumOf { it.columnWidthPct.toDouble() }
    val unhinted = fields.count { it.columnWidthPct <= 0f }
    val share = if (unhinted > 0) ((100.0 - hinted) / unhinted).coerceAtLeast(4.0) else 0.0

    val widths = fields.map { field ->
        if (field.columnWidthPct > 0f) field.columnWidthPct.toDouble() else share
    }
    val total = widths.sum()
    // Normalise first (a registry whose hints already overflow must not produce a >100 total), then
    // hand the residue to the last column so the sum is exactly 100 to the renderer's tolerance.
    val scaled = widths.map { it * 100.0 / total }
    val adjusted = scaled.toMutableList()
    adjusted[adjusted.lastIndex] = 100.0 - adjusted.dropLast(1).sum()

    return fields.mapIndexed { index, field ->
        val numeric = DwFieldType.of(field.type).isNumeric
        TableColumn(
            header = field.label + if (field.unit.isNotBlank()) " (${field.unit})" else "",
            widthPct = adjusted[index].toFloat(),
            align = if (numeric) Align.RIGHT else Align.LEFT,
            numeric = numeric,
        )
    }
}

/**
 * A stored value as the report should read it.
 *
 * Enum tokens are resolved to their LABELS, and that is the difference between a report and a data
 * dump: a document that says "TIE_AND_DYE" is not a document anyone will submit to a ministry. The
 * label lookup falls back to the raw token rather than failing, because a draft written by a phone
 * one registry ahead can carry an option this build has never heard of, and printing the token beats
 * failing an export in the field.
 *
 * A REF is resolved to the NAME of the record it points at, and a RICH_TEXT value is flattened to one
 * string. Both arms exist because their absence was not a formatting shortfall but a silent loss: the
 * `else` branch below is `DwValues.text`, which prints a record id verbatim and answers "" for the
 * JsonObject a rich value is. Callers that can carry more than one string reach past this function —
 * [cellRuns] for a cell's runs, [renderEntity] for a narrative's blocks — exactly as the server's
 * renderers reach past `format_value`.
 */
private fun displayValue(field: FieldDto, value: JsonElement?, refs: DwRefLabels): String {
    if (!DwValues.isFilled(value)) return ""
    val type = DwFieldType.of(field.type)
    return when (type) {
        // THE MARKS ARE DROPPED HERE ON PURPOSE, and only here: this function's contract is one
        // string. What the branch prevents is the value vanishing altogether.
        DwFieldType.RICH_TEXT -> toPlain(value)

        DwFieldType.REF -> {
            val id = dwRefId(value).trim()
            val label = refs.label(id)
            // Nothing resolved. What is printed now depends on WHAT the field is holding, and both
            // answers matter:
            //
            //   an opaque id  -> nothing. The row it named was deleted after this one cited it, and
            //                    a bare cuid in a ministry's table is worse than a visible gap.
            //   anything else -> itself. A REF may hold text a designer typed by hand — a sketch
            //                    number like "SK-01", a value migrated before the picker existed —
            //                    and blanking that would silently drop a field somebody filled in.
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
        DwFieldType.MULTI_ENUM -> DwValues.list(value).joinToString(", ") { token ->
            field.options.firstOrNull { it.value == token }?.label ?: token
        }
        DwFieldType.TAGS -> DwValues.list(value).joinToString(", ")
        DwFieldType.GEO -> DwValues.geo(value)?.let { (lat, lon) ->
            String.format(java.util.Locale.ROOT, "%.5f, %.5f", lat, lon)
        }.orEmpty()
        DwFieldType.MONEY -> "₹" + DwValues.text(value)
        DwFieldType.PERCENT -> DwValues.text(value) + "%"
        else -> DwValues.text(value)
    }
}
