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
import com.designprototype.workshop.data.DwQuestionnaireCache
import com.designprototype.workshop.data.DwQuestionnaireStore
import com.designprototype.workshop.data.DwReadinessCheck
import com.designprototype.workshop.data.DwSubmissionReadiness
import com.designprototype.workshop.data.DwTier
import com.designprototype.workshop.data.DwReferenceStore
import com.designprototype.workshop.data.DwValues
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
    /**
     * What stage 20 does to the delivered document — [DwSubmissionReadiness.reportChecks].
     *
     * ── HELD SEPARATELY FROM [warnings], AND THAT IS THE POINT ────────────────────────────────────
     *
     * `warnings` is a list of stages with unanswered REQUIRED fields, drawn under the heading "N
     * stage(s) are incomplete" and the line "these fields will print as blanks". Neither sentence is
     * true of these. A stage named in `excludedStages` is not incomplete — it is complete and
     * deliberately left out — and nothing about it will print as a blank, because the whole section
     * is absent from the file. Folding one into the other would file the most consequential thing
     * this screen can say under a heading that mis-states it, next to nineteen entries about
     * optional boxes.
     *
     * These were computed on every stage-index open and rendered on no screen at all: the only
     * caller of `assess` in the app reads `blocking` and discards `checks`, `advisory` and
     * `isSubmittable`. A designer therefore exported a workshop with stage 17 excluded — set on the
     * web weeks earlier while trialling a buyer-facing copy — and handed over a file with every cost
     * sheet missing, while the Data completeness annexure inside that same file scored stage 17 as
     * complete.
     */
    var reportChecks by remember(workshopId) { mutableStateOf<List<DwReadinessCheck>>(emptyList()) }
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
     * How many distinct photographs the last export referenced and could not find on this device.
     *
     * BESIDE [ReportExport.Result.droppedImages] AND NOT FOLDED INTO IT, because they are different
     * facts with different remedies. `droppedImages` is a file this device HAS whose bytes would not
     * load — a corrupt or deleted capture, and something is wrong with this handset. This is a media
     * id this device never held at all, which is the ordinary state of a workshop captured on
     * somebody else's phone and read back through [reportSourceFor]: nothing is wrong anywhere, the
     * bytes are on the server, and the remedy is to generate the office's copy. Counting them
     * together would send a designer looking for a fault that does not exist, and — worse — the
     * common case would swamp the rare one that really is a fault.
     */
    var unresolvedMedia by remember(workshopId) { mutableStateOf(0) }
    /**
     * The report's accent colour for the next export, blank for "the colour the record already has".
     *
     * Seeded below from stage 20 so the phone and the office produce the same-coloured file for the
     * same workshop, then owned by the picker. It is NOT written back to the draft: an export is not
     * an edit, and a colour tried once in a cluster must not arrive at the office as a saved
     * decision the next sync silently applies to everything.
     */
    var accent by remember(workshopId) { mutableStateOf("") }
    /**
     * The warning that this file cannot hold more than this device does — see [ReportSource].
     *
     * Above the export buttons rather than beside the saved file, because it is knowable before the
     * button is pressed and it is the one thing a designer must read BEFORE the document is in an
     * officer's hand.
     */
    var deviceOnlyNote by remember(workshopId) { mutableStateOf<String?>(null) }
    /** Which copy the next export will be built from, said in all three states. */
    var builtFromLine by remember(workshopId) { mutableStateOf("") }
    /**
     * The same fact for the FILE rather than for the screen — see [ReportSource.serverCopyUnread].
     *
     * The notice above is read by the designer on the day and is gone the moment they leave the
     * screen; the officer who opens the .docx next month was never here. This is what carries "this
     * copy may be short" onto the cover, through [reportPlanFor] and `fieldCopyNote`.
     */
    var serverCopyUnread by remember(workshopId) { mutableStateOf(false) }

    /**
     * The answers recorded against this workshop's own questionnaires, as this device holds them.
     *
     * NULL IS A THIRD ANSWER and not an empty one — see `DwQuestionnaireStore`. Null means this
     * handset has never read the list for this workshop and cannot tell an unattached workshop from
     * an attached one; a cache with no items means the server said none is attached. The annexure,
     * the export warnings and the file's own notes all turn on that distinction, so it is carried
     * here as a nullable rather than flattened to `emptyList()` on the way in.
     */
    var questionnaires by remember(workshopId) { mutableStateOf<DwQuestionnaireCache?>(null) }
    /**
     * This workshop's own questions, as this device holds them. NULL IS A STATE — see [DwCustomCopy].
     *
     * It reaches the document itself and not only the warnings: the answers are rendered into the
     * stage they belong to, so a field copy exported in a courtyard carries the designer's own
     * questions and the answers recorded against them. Before this it carried neither, and said
     * nothing about either.
     */
    var customSections by remember(workshopId) { mutableStateOf<DwCustomCache?>(null) }

    LaunchedEffect(workshopId) {
        loading = true
        runCatching {
            val registry = repository.designWorkshopSchema(appContext)
            val local = WorkshopDraftStore.load(appContext, workshopId)
            // THE STAGES ARE FETCHED BEFORE ANYTHING IS BUILT. This screen used to read local
            // storage and stop, so the document could only ever contain the stages this handset had
            // itself opened — a workshop with 22 stages on the server exported as ten paragraphs,
            // with a correct ministry cover page on the front of it. Same call, same failure
            // handling and same three-state note as [WorkshopCodesScreen]; the merge that keeps
            // this device's unsynced work is in [reportSourceFor].
            val remoteId = local?.remoteId ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
            val remote = remoteId?.let { runCatching { repository.designWorkshopStages(it) }.getOrNull() }
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
            // Scored from the MERGED draft, so the percentage and the warnings describe the document
            // that is about to be written. Scoring the local draft while exporting the merged one is
            // how a screen comes to say "18% of the required fields are filled in" over a report
            // that is in fact complete — and, before this, the other way round.
            /*
              THE DEFINITION, BEFORE ANYTHING IS SCORED FROM IT.

              BOTH IDS ARE TRIED, for the reason the questionnaire read further down tries both: a
              workshop created offline keeps its LOCAL id on this screen for ever, while the
              definition is fetched under the SERVER's, and [dwCustomDefinition] files a fresh read
              under the id it was asked to cache it as.

              It is read HERE rather than beside the questionnaires because the percentage under the
              export buttons is computed on the next line and must count the designer's own required
              questions — a screen that said "94% of the required fields are filled in" over a report
              missing two required custom answers would be the one number the export screen exists to
              print, and wrong.
            */
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
            /*
              WHAT STAGE 20 DOES TO THE FILE, said BEFORE the buttons.

              Both checks were already written, already ported from the web and already computed on
              every stage-index open — and discarded there, because that screen reads `blocking` and
              nothing else. This is the screen they were written for: a template token this build has
              retired, and a stage deliberately excluded from the report while holding answers.

              Scored from the SAME `scores` the percentage above is printed from, which is the merged
              draft — local work plus the stages downloaded for this export. A second scoring pass
              would be a second opinion about the one document, and the disagreement would land
              exactly on the workshop captured on somebody else's handset: the stage looks empty
              here, so the exclusion looks harmless, so nothing is said.
            */
            reportChecks = DwSubmissionReadiness.reportChecks(registry, stored, workshopId, scores)
            // One warning per stage rather than one per missing field. A report generated from a
            // half-filled workshop is still worth having in the field — the warnings are there so the
            // designer knows what the officer will notice, not to block the export.
            warnings = scores.filter { it.missing.isNotEmpty() }.map { stage ->
                "Stage ${stage.number} — ${stage.title}: ${stage.missing.size} required field(s) " +
                    "missing (${stage.missing.take(3).joinToString(", ")}" +
                    (if (stage.missing.size > 3) ", …" else "") + ")"
            }
            // APPENDED AFTER the assignment above and never before it — that line REPLACES the
            // list rather than adding to it, so a warning written earlier in this effect is simply
            // gone. The questionnaire warning further down is appended for the same reason.
            warnings = warnings + dwCustomSectionWarnings(
                copy = dwCustomCopy(customSections),
                // Sharpens the sentence rather than gating it: answers held on this phone with no
                // definition to label them with is a strictly worse state than no answers at all,
                // and the designer standing at the export screen can act on either.
                answersHeld = stored?.stages.orEmpty().values.any { stage ->
                    stage.custom.values.any { DwValues.isFilled(it) }
                },
            )

            // ── the questionnaire annexure's answers ─────────────────────────────────────────────
            //
            // THE CACHE FIRST, THE NETWORK AS A REFRESH — the discipline `DwReferenceStore` set, and
            // the reason this screen is worth touching at all: the moment a report is most needed is
            // the end of a workshop, in a cluster, with no signal. What this phone was shown while it
            // had signal is what it prints now.
            //
            // THE FETCH IS ITS OWN runCatching AND IS BEST-EFFORT. It is the ONLY network call on a
            // screen whose whole argument is that it needs none, so it must not be able to fail the
            // load: an offline export that lost the template picker and the completeness figures
            // because a questionnaire lookup timed out would be a worse screen than the one that
            // never fetched. A failure leaves whatever is cached exactly where it is.
            // THE SAME `remoteId` RESOLVED ABOVE, deliberately not recomputed. This block used to
            // derive its own from `stored`, which reads as the safer choice and is in fact the same
            // value in every case: `reportSourceFor` either copies the local draft, keeping its
            // `remoteId` untouched, or builds one carrying the very id passed in, or returns null
            // and leaves both expressions falling through to the same `takeUnless`. Two spellings of
            // one value in one scope is how the cache and the stage fetch would come to disagree
            // about which workshop this is.
            // BOTH KEYS ARE TRIED, and the second one is not defensive padding. A workshop created
            // offline keeps its LOCAL id on this screen for ever, while a questionnaire's
            // `designWorkshopId` is necessarily the SERVER's — so the copy `QuestionnaireDetailScreen`
            // files when the designer reads their sittings on Monday is under the remote id, and a
            // read on Thursday under the local one alone would miss it. Thursday, in a courtyard with
            // no signal, is the export this whole cache exists for.
            var held = DwQuestionnaireStore.load(appContext, workshopId)
                ?: remoteId?.let { DwQuestionnaireStore.load(appContext, it) }
            if (remoteId != null) {
                runCatching { repository.designWorkshopQuestionnaires(remoteId) }
                    .onSuccess { fetched ->
                        held = DwQuestionnaireStore.store(
                            appContext,
                            // Stored under the id this SCREEN reads back with, which is the local
                            // workshop id and not necessarily the remote one — a workshop created
                            // offline keeps its local id here for ever, and filing the answers under
                            // the server's would hide them from the one device that fetched them.
                            fetched.copy(workshopId = workshopId),
                        )
                    }
            }
            questionnaires = held
            warnings = warnings + dwQuestionnaireWarnings(held)

        }.onFailure { onError(it.message ?: "Unable to prepare the report.") }
        loading = false
    }

    fun export(format: String) {
        val registry = schema ?: return
        busy = true
        result = null
        scope.launch {
            runCatching {
                // THE MERGED DRAFT AND NOTHING ELSE. There was a `?: WorkshopDraftStore.load(…)`
                // here, and it is exactly the read this whole path exists to stop being the report's
                // source: it can only ever return what this handset already had. It was unreachable
                // in any case that mattered — `draft` is assigned in the same `runCatching` that
                // assigns `schema`, and the guard above returns on a null `schema`, so a null
                // `draft` here means the merge found nothing on either side and the re-read would
                // have returned null too. Removing it changes no behaviour today and leaves exactly
                // ONE place the report's data comes from, which is what stops a later edit quietly
                // restoring the defect.
                val stored = draft
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
                    // Carried into the FILE, not only onto the screen — see [ReportPlan.serverCopyUnread].
                    serverCopyUnread = serverCopyUnread,
                    // The same copy the document is drawn from, so the warning above the buttons and
                    // the section in the file cannot come from two different answers to "does this
                    // phone have the answers".
                    questionnaires = dwQuestionnaireCopy(questionnaires),
                    // The same definition the document is drawn from, for the same reason the
                    // questionnaire copy is threaded here: the template this plan resolves is what
                    // decides WHERE each of the designer's blocks prints, and a second read could
                    // straddle a definition edit.
                    customSections = customSections,
                )
                exportNotes = plan.warnings
                // Collected on the render thread and read back on this one AFTER `withContext`
                // returns, which is what makes the hand-off ordered. Assigning the Compose state
                // from inside the lambda would be a write from Dispatchers.Default to a value this
                // composable reads on the main thread — snapshot state tolerates it, but the
                // ordering would then be the snapshot system's business rather than this function's,
                // and the count would be applied on a frame nobody can name.
                var unresolvedIds: List<String> = emptyList()
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
                        onUnresolvedMedia = { ids -> unresolvedIds = ids },
                    )
                }
                // ASSIGNED ON EVERY EXPORT, including the one that resolves everything — the builder
                // hands back an empty list there and this clears. A designer who re-exports after
                // opening the workshop with a connection must see the count go, or the notice beside
                // the saved file is describing the previous file.
                unresolvedMedia = unresolvedIds.size
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
                                // THE STAGE-20 CHECKS AND THE UNRESOLVED PHOTOGRAPHS GO INTO THE
                                // OFFICE'S LOG TOO, for one reason: this row is where somebody at a
                                // desk decides whether the file that was handed over is the whole of
                                // the record, and a row that says nothing is a row asserting there
                                // was nothing to say. Both are also the two things the FILE cannot
                                // show — an excluded section is absent, so its absence is
                                // indistinguishable from a stage nobody captured, and a photograph
                                // that never resolved leaves no gap on the page.
                                warnings = (
                                    warnings + plan.warnings + reportChecks.map { it.title } +
                                        listOfNotNull(
                                            unresolvedIds.size.takeIf { it > 0 }
                                                ?.let { unresolvedMediaNote(it) },
                                        )
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
                // NAMED, BECAUSE THIS WAIT IS NOW A NETWORK WAIT. Preparing the report used to be a
                // local file read and was over before the spinner drew; it now reads the workshop's
                // stages, and on a handset attached to a cell that accepts packets and answers none
                // — a courtyard on the edge of coverage, not a phone with no bars — OkHttp spends
                // its connect timeout and its retries before giving up. A designer with an officer
                // waiting must be able to see WHY the buttons have not appeared, and that waiting is
                // what buys the other nineteen stages. Said only where a read is certain: an id with
                // no server form never attempts one.
                Text(
                    if (isLocalOnlyWorkshop(workshopId)) "Preparing…"
                    else "Reading this workshop from the server…",
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp,
                )
            }
            return@Column
        }

        // WHICH COPY THIS FILE WILL BE BUILT FROM, said before the buttons and not after the file.
        // The report is the document handed to a visiting officer at the close of the workshop, and
        // "this holds only what the phone has" is a thing the designer can act on beforehand —
        // wait for signal, or say it out loud when handing it over — and can do nothing about once
        // the .docx is in somebody else's hands.
        deviceOnlyNote?.let { DwWorkshopNotice(it) }

        SearchableSelectField(
            label = "Template",
            options = templateOptions(templates),
            selectedValue = templateId,
            includeNone = false,
            onSelect = { picked -> if (picked.isNotBlank()) templateId = picked }
        )

        ReportAccentPicker(accent = accent, onAccent = { accent = it })

        // THE COUNT COMES FROM THE REGISTRY THAT WAS SCORED, not from a literal 22. `percent` is
        // computed over `schema.stages` — whatever the server served on the last refresh — while
        // this sentence said "the 22 stages" from a hard-coded number. The screen eight lines below
        // renders `unknownStageWarning`, which exists precisely because a stage added on the server
        // reaches the phone's registry while this build's template catalogue is compiled into the
        // APK: on the day it fires, this line and that card disagree about how many stages the
        // workshop has, on the one screen a designer reads to decide whether the file is fit to hand
        // over. `StageIndexScreen` already words it from `stages.size`; this now matches it.
        Text(
            "$percent% of the required fields across the ${schema?.stages?.size ?: 0} stages are " +
                "filled in.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp
        )

        // The same fact in its unalarming form: even when the server answered, the designer is told
        // how much of this document came from where. A count is what makes "the workshop has 22
        // stages and this file has three" visible on the screen instead of in the printed copy.
        if (builtFromLine.isNotEmpty()) {
            Text(builtFromLine, color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
        }

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

        /*
          WHAT STAGE 20 IS ABOUT TO DO TO THIS FILE — its own card, above the buttons.

          ABOVE THE BUTTONS AND NOT BESIDE THE SAVED FILE, which is the difference between this and
          `exportNotes`. Every one of these is knowable before a button is pressed and every one of
          them is actionable: re-pick a template the build has retired, or clear an exclusion set on
          the web six weeks ago. Said after the .docx exists, the same sentence is only an
          explanation of a document that is already in somebody's hand.

          Its own card, and NOT folded into the incomplete-stages card above, because that card's
          heading counts stages that are INCOMPLETE and its subtitle promises the missing fields
          "will print as blanks". An excluded stage is neither: it is complete, and nothing of it
          prints at all — no heading, no blanks, no entry in the contents.
        */
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    reportChecks.forEach { check ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "· ${check.title}",
                                color = MaterialTheme.field.onWarningContainer,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                            // THE DETAIL, NOT ONLY THE TITLE. The title names the condition; the
                            // detail is the half that says how many fields are being left out of the
                            // submitted document and that the choice was a legitimate one — which is
                            // what makes this a decision the designer can take rather than an alarm.
                            Text(
                                check.detail,
                                color = MaterialTheme.field.onWarningContainer,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
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
                // THE OTHER HALF OF THAT COUNT, and until this line existed there was no half at all:
                // `droppedImages` counts an image the writer HAD and could not load, so every media
                // id this device never held — the ordinary shape of a workshop captured on a
                // colleague's phone — fell out of the document with no counter, no notice here and
                // no line in the file. See [unresolvedMediaNote] and [buildWorkshopDocument]'s
                // `onUnresolvedMedia`.
                if (unresolvedMedia > 0) {
                    Text(
                        unresolvedMediaNote(unresolvedMedia),
                        color = MaterialTheme.field.warning,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
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
    questionnaires: DwQuestionnaireCache? = null,
    /** See the internal overload below - null is "this device has never read a definition". */
    customSections: DwCustomCache? = null,
    /** See the internal overload below — the media tokens this device could not resolve to a file. */
    onUnresolvedMedia: (List<String>) -> Unit = {},
): ReportDocument {
    val mediaById = draft?.media.orEmpty().associateBy { it.id }
    return buildWorkshopDocument(
        format = format,
        plan = plan,
        onUnresolvedMedia = onUnresolvedMedia,
        schema = schema,
        draft = draft,
        workshopId = workshopId,
        templateId = templateId,
        warnings = warnings,
        accent = accent,
        questionnaires = questionnaires,
        customSections = customSections,
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
    /**
     * This workshop's questionnaire answers, as this device holds them — see `DwQuestionnaireStore`.
     *
     * NULL AND EMPTY ARE DIFFERENT and both are legitimate. Null is "this handset has never read the
     * list", which is what every caller that does not consult the store means, and which prints no
     * annexure at all. A cache with no items is "the server says none is attached", which prints no
     * annexure either — but for a reason the file and the export screen are allowed to state.
     */
    questionnaires: DwQuestionnaireCache? = null,
    /**
     * This workshop's custom definition, as this device holds it - see [DwCustomSectionStore].
     *
     * NULL AND EMPTY ARE DIFFERENT and both are legitimate. Null is "this handset has never read the
     * definition", which is what every caller that does not consult the store means, and which prints
     * no custom questions at all; a cache with no sections is "the server says this workshop has
     * none", which also prints nothing - but for a reason the export screen is allowed to state.
     */
    customSections: DwCustomCache? = null,
    /**
     * Every media token [imageFor] answered null for, deduplicated, in first-use order — handed back
     * ONCE, after the whole template has been walked.
     *
     * ── THE DEFECT THIS CLOSES ────────────────────────────────────────────────────────────────────
     *
     * Both consumers of [imageFor] used to be `ids.mapNotNull(imageFor)`, and a `mapNotNull` is the
     * shape this repository has now paid for four times: the residue is dropped where it is produced
     * and no counter, no screen and no line in the file ever learns of it. It is ordinary, not
     * exotic — [reportSourceFor] fills stages this handset has never opened straight from the
     * server, and those answers carry the SERVER's `MediaFile` ids, which resolve against nothing in
     * `draft.media`. So the phone's copy of a colleague's workshop came out with every table, every
     * paragraph, no photographs, no 'Photographic record' annexure (`gathered.isEmpty()` deleted the
     * heading as well), and `ReportExport.Result.droppedImages` empty — because that list counts an
     * `ImageRef` whose BYTES failed to load, and an id that never became an `ImageRef` never reaches
     * a writer at all. Nothing anywhere said the file was short.
     *
     * Counted here rather than at either call site because there are three of them (`renderEntity`,
     * [imagesOf] and the inline images inside RICH_TEXT), and a count kept by one of them would be a
     * count of a third of the document.
     */
    onUnresolvedMedia: (List<String>) -> Unit = {},
): ReportDocument {
    val resolved = plan ?: reportPlanFor(
        schema = schema,
        draft = draft,
        workshopId = workshopId,
        requestedTemplateId = templateId,
        requestedAccent = accent,
        format = format,
        generatedAt = generatedAt,
        // WITHOUT THIS THE FALLBACK PLAN SPLICES NO CUSTOM SECTION, so a caller that hands over a
        // definition but no plan — every test, and the internal overload's own default — would build
        // a template with no CUSTOM_SECTION in it and the designer's questions would be missing from
        // exactly the documents nothing else checks.
        customSections = customSections,
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

    /*
      THE ONE RESOLVER EVERY SECTION GETS, so that what could not be resolved is counted once and in
      one place — see [onUnresolvedMedia] for the defect this closes.

      DEDUPLICATED BY TOKEN, and that is not tidiness: the media annexure re-resolves every id the
      stage sections already tried, so a set is the difference between "3 photographs are missing"
      and "6 photographs are missing" on the same three files. A LinkedHashSet keeps first-use order
      for the same reason `collectImages` does — a list of ids a support engineer can match against
      the stage that referenced them.
    */
    val unresolvedMedia = LinkedHashSet<String>()
    val resolveImage: (String) -> ImageRef? = { token ->
        imageFor(token).also { if (it == null && token.isNotBlank()) unresolvedMedia.add(token) }
    }

    // THE TEMPLATE'S SECTION LIST IS THE DOCUMENT, and this loop is the whole of `ReportBuilder.build`.
    // What it replaces is `schema.stages.sortedBy { it.number }`, which printed CAPTURE order — the
    // designer's — where a reviewing officer expects the narrative order, printed stages 20 and 21
    // back at the ministry (the report describing its own generation, export checksums and all), and
    // gave all six templates the same twenty-two sections under the same headings.
    template.sections.forEach { section ->
        val special = section.special
        if (special != null) {
            renderSpecialSection(
                builder, special, section, resolved, schema, draft, resolveImage, refs, figures,
                questionnaires, customSections,
            )
        } else {
            stages[section.stageKey]?.let { stage ->
                renderStageSection(
                    builder, stage, section, resolved, schema, draft, resolveImage, refs, figures,
                )
            }
        }
    }

    /*
      WHAT THE FILE DOES NOT CARRY, SAID IN THE FILE — and said exactly once.

      [renderMediaAnnexure] already prints this sentence under its own heading when the template
      carries that section, because that is where a reader goes looking for the photographs and
      where their absence is a hole they can see. Four of the six templates do not carry it, and for
      those the note goes at the foot of the document rather than nowhere: a short report is
      internally consistent — right cover, right contents, fewer plates — so the only reader who can
      ever detect the gap is the one holding the file, and only if it says so.

      `builder.warn` in BOTH cases, because that is what carries the count back to the export screen
      through [ReportDocument.warnings]; the paragraph is for the officer who opens the .docx next
      month and was never standing at the phone.
    */
    if (unresolvedMedia.isNotEmpty()) {
        builder.warn(unresolvedMediaNote(unresolvedMedia.size))
        if (template.sections.none { it.special == SpecialSection.ANNEXURE_MEDIA }) {
            builder.para(unresolvedMediaNote(unresolvedMedia.size), style = ParaStyle.NOTE)
        }
    }
    // ALWAYS CALLED, empty list and all: a caller that assigns this to a screen counter must be able
    // to CLEAR that counter on the export that resolved everything. Handing back nothing on the
    // clean path is how a stale "3 photographs are missing" survives onto a file that is whole.
    onUnresolvedMedia(unresolvedMedia.toList())

    return builder.build()
}

/**
 * The one sentence for "this workshop references photographs this device does not hold".
 *
 * ONE SPELLING, THREE SURFACES — the document's own note, the media annexure's note and the line
 * beside the saved file on the export screen all call this. The alternative was three sentences that
 * agree today; `reportPlanFor` exists because two readings of one stage-20 answer had already
 * produced a screen and a file that disagreed about the same document, and a count is exactly the
 * kind of thing that drifts.
 *
 * IT NAMES WHERE THE BYTES ARE, and that is the load-bearing half. Nothing is lost here: a media id
 * that reaches this path is a `MediaFile` on the SERVER — [stageDraftFromRemote] copies the server's
 * answers verbatim and does not download their pictures — so the office's copy of this report prints
 * every one of them. A designer told only "3 photographs are missing" would go hunting for a fault
 * on the handset; what they can actually act on is "generate this one from the web, or hand it over
 * saying so".
 */
internal fun unresolvedMediaNote(count: Int): String {
    val photographs = if (count == 1) "1 photograph" else "$count photographs"
    val they = if (count == 1) "it is" else "they are"
    return "$photographs referenced by this workshop " +
        (if (count == 1) "is" else "are") +
        " not stored on the handset that generated this file, so $they not in it. The bytes are on " +
        "the server and the office's copy of this report carries them — generate that copy, or say " +
        "so when handing this one over."
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
        // ALL SIX TEMPLATES SET THIS TRUE on both surfaces — `grep -rn showEmptyNote
        // android/app/src/main` and `grep -rn show_empty_note backend/app` each return the
        // declaration and the use site and nothing else. It is threaded rather than assumed because
        // a template that turned it off and still printed "Not recorded." would be a template
        // setting that lies, and this is the field the setting is about.
        showEmptyNote = template.showEmptyNote,
        // A STAGE SINGLETON'S FIELDS HEAD AT LEVEL 2, under the stage's own level-1 heading. This is
        // `_render_narrative(single, singleton_data, 1)` and its `min(4, level + 1)`.
        level = 1,
    )
    val stored = draft?.stages?.get(stage.key)
    val singletonValues = stored?.values.orEmpty()
    /*
      THE DESIGNER'S ANSWERS, WITH THE PROTOCOL'S OWN KEYS TAKEN OUT — and it is the same question
      `ReportSource.holdsWork` was fixed to ask, asked in the second place that was asking it wrong.

      A stage's singleton map is not purely the registry's: `DwRecordingPlaceCard` writes
      [DW_RECORDING_PLACE_KEY] — "where were you when this stage was filled in?" — straight into it
      under an underscore, which is the sync protocol's marker for "this never goes on the wire"
      (`WorkshopSync.wireData` strips every one of them). So `singletonValues.isEmpty()` was
      answering "does this stage hold anything at all", where the two tests below need "does this
      stage hold anything the DOCUMENT can print".

      They diverge on an ordinary case. A designer opens a stage, answers only the recording-place
      card and leaves: the map holds one key, `isEmpty()` is false, the section is not skipped, and
      the file prints a numbered stage heading with "Not recorded." under it — because
      [renderEntity] walks `entity.liveFields` and an underscore key is unreachable by construction
      on that path. On a fortnight's fieldwork that is several stage headings in the submitted
      document standing over nothing, each one occupying a line in the contents page.

      Handed to [renderEntity] as well as to the tests, so the emptiness question and the printing
      question can never again be asked of two different maps. Nothing moves for the registry's own
      fields: every read in there is keyed by a `FieldDto.key`, and no field in the registry begins
      with an underscore.
     */
    val answered = singletonValues.filterKeys { !it.startsWith("_") }
    val hasCollections = stage.collections.any { stored?.rowsFor(it.key).orEmpty().isNotEmpty() }
    /*
      A STAGE WITH NOTHING IN IT IS SKIPPED ENTIRELY. Printing 22 headings with nothing under them
      turns a five-page report into a twenty-page one that says the same amount.

      A DESIGNER'S OWN QUESTIONS ARE NOT COUNTED HERE ANY MORE, AND THAT IS THE POINT RATHER THAN A
      REGRESSION. This test used to carry a third term, `hasCustom`, because the custom questions
      were drawn INSIDE this function — so a collections-only stage whose only content was custom
      would have been dropped, heading and all. They are now their own [SpecialSection.CUSTOM_SECTION]
      spliced by `applyReportSettings`, which is where the server puts them and which is the only
      placement that can also reach a stage this template does not print at all. So the stage section
      is empty in exactly the way `_render_stage` means it — `has_any` there asks about singleton and
      collection data and knows nothing about custom sections either — and the designer's block still
      prints, under its own level-1 heading, immediately after.
    */
    if (answered.isEmpty() && !hasCollections && section.omitIfEmpty) return

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { stage.title },
        level = 1,
        numbered = template.numberHeadings,
    )
    if (section.intro.isNotBlank()) builder.para(section.intro, style = ParaStyle.LEAD)

    /*
      WHERE THE STAGE WAS WRITTEN DOWN — the one answer on this form that no field-list walk can
      ever reach, printed by name because that is the only way it can be printed at all.

      `DwRecordingPlaceCard` is offered on all 22 stages and its own KDoc promised this: "It survives
      in the local draft, which is what the report is generated from, so the provenance prints even
      though it never leaves the phone." It did not print. A repo-wide grep for the key found the
      constant, the card, one write in `StageScreen` and no reader anywhere — a question asked
      twenty-two times a workshop, over a fortnight, consumed by nothing. It is asked because a
      ministry reviewer's "was stage 14 written at the cluster, or typed up afterwards in Jaipur?"
      had nothing to read; that is exactly a thing a report answers.

      IMMEDIATELY UNDER THE HEADING, BEFORE ANY ANSWER, because it is a statement about the record
      below it rather than a part of that record. A provenance line at the foot would read as the
      last thing the designer wrote about the craft.

      IT DOES NOT SET [wrote]. `wrote` decides whether the section gets the "Not recorded." note, and
      a stage whose only content is a note about where somebody was standing IS a stage with nothing
      recorded — claiming otherwise would let one provenance line suppress the note on a blank
      section. The `answered.isEmpty()` test above already means this line never prints alone under
      `omitIfEmpty`: no place is named without a stage to be the provenance OF.

      THE SENTENCE SAYS THE OFFICE'S COPY LACKS IT, in full, every time. This is the one line in the
      document that the server CANNOT reproduce — the key never reaches the wire — so the phone's
      copy and the office's copy of one workshop differ by exactly it. Every other divergence between
      the two has been treated in this file as a defect; this one is deliberate and therefore has to
      be legible from inside the file, by a reader holding both copies and no access to this comment.
     */
    recordingPlaceLine(singletonValues[DW_RECORDING_PLACE_KEY])?.let { line ->
        builder.para(line, style = ParaStyle.NOTE)
    }

    var wrote = false
    stage.singleton?.let { entity ->
        if (answered.isNotEmpty()) {
            // THE ONE PLACE A METRIC ROW IS ASKED FOR — `_render_stage` is the only renderer on the
            // server that builds one. See [RenderOptions.metricRow].
            wrote = renderEntity(builder, entity, answered, imageFor, refs, options.copy(metricRow = true)) || wrote
        }
    }

    /*
      THE DESIGNER'S OWN QUESTIONS ARE NOT DRAWN HERE ANY MORE. They are their own template section,
      spliced by `applyReportSettings` and drawn by [renderCustomSection], which is where the server
      puts them and — more importantly — the only placement that can reach a section whose anchor
      stage this template does not print at all.

      THE ARGUMENT THAT USED TO STAND HERE WAS "THE POSITION IS THE SCORER'S": `computeStageCompleteness`
      counts custom fields between the singleton and the collections, the completeness annexure prints
      `missing.take(3)` in that order, and the stage form draws them in that order, so printing them
      last would put the questions in an order matching neither. That is true of the SCORER and it is
      still true — nothing about `computeStageCompleteness` moved — but it was never an argument about
      the DOCUMENT, and it was made without reference to the surface it had to agree with. The server
      scores in exactly the same order and still prints the block after the whole stage section, at
      level 1, because `apply_report_settings` is the single arbiter of the running order and was made
      one after three call sites decided for themselves and disagreed. A fourth arbiter on the handset
      gave one workshop two documents with different heading numbers, different contents pages and
      different pagination from the first custom section onwards.

      WHAT WAS PRESERVED FROM THE INLINE VERSION is the drawing: [renderCustomSection] still renders
      through [renderEntity] over `customSectionEntity`'s synthetic singleton, still prints retired
      answers under their marker, still names an undrawable type rather than faking it, and still
      applies the template's tier cap to all three. Only WHERE the block sits changed in that edit.

      TWO DRAWING DIFFERENCES HAVE SINCE BEEN CORRECTED THERE, and neither was the inline version's
      invention to keep: the section description prints at `ParaStyle.LEAD` as `append_custom_section`
      writes it (it was NOTE), and the heading has no third `definition.key` fallback (the server has
      none, and a stored section's title is `min_length=1`). See [renderCustomSection].
    */

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
 * The stage-provenance sentence, or null when the card was never answered — see [DW_RECORDING_PLACE_KEY].
 *
 * ── WHY THIS IS NOT `describePlace` ───────────────────────────────────────────────────────────────
 *
 * The card's own summary (`DwLocationField.describePlace`) prints the names when it has them and the
 * coordinate only when it has not, because it is one 11sp line under a collapsed disclosure and has to
 * choose. A page has no such constraint and a submitted document has the opposite requirement: the
 * NAMES are what a reviewer reads and the COORDINATE is what an auditor checks, and a provenance line
 * that carried only the first is a claim nobody can verify. So both print where both exist. This is a
 * different rendering of the same value for a different reader, not a second copy of the card's rule —
 * if it were the same rule stated twice, the right fix would be to call the card's.
 *
 * FIVE DECIMAL PLACES, matching the card, which is about a metre — enough to say which courtyard and
 * not enough to imply the handset knew more than it did. [Locale.ROOT] because a document generated on
 * a phone set to a comma-decimal locale must not print "20,36790" into a coordinate pair separated by
 * commas.
 */
private fun recordingPlaceLine(stored: JsonElement?): String? {
    val place = dwLocationFromValue(stored) ?: return null
    val named = listOfNotNull(
        place.village?.takeIf { it.isNotBlank() },
        place.district?.takeIf { it.isNotBlank() },
        place.state?.takeIf { it.isNotBlank() },
    ).joinToString(", ")
    val coordinate = String.format(Locale.ROOT, "%.5f, %.5f", place.latitude, place.longitude)
    val where = if (named.isBlank()) coordinate else "$named ($coordinate)"
    return "Recorded at $where. Noted on the handset this stage was filled in on; it is not sent " +
        "to the server, so the office's copy of this report does not carry this line."
}

/**
 * The sections that are not one of the 22 stages.
 *
 * ELEVEN OF THE TWELVE ARE BUILT HERE — the cover, the contents, the metric row, the acknowledgement,
 * the photographic record, the completeness table, the sign-off, the locator map, the infographics,
 * the questionnaire annexure since this device gained a copy of the answers, and now the designer's
 * own custom sections. The twelfth, the
 * transcript annexure, is SKIPPED IN SILENCE HERE AND NAMED IN THE WARNINGS, which [reportPlanFor]
 * assembled from this same template. That split is deliberate: a warning belongs to the act of
 * generating and not to the document, so the officer who opens the .docx next month does not find a
 * note about what a handset could not draw on the day, while the designer standing beside them on the
 * day is told plainly. The document itself carries one provenance line on its cover ([fieldCopyNote])
 * naming what the office's copy additionally has — which is a statement of fact about the file, and
 * belongs in it.
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
    questionnaires: DwQuestionnaireCache?,
    /**
     * This workshop's custom definition - the completeness annexure counts the same things, and
     * [SpecialSection.CUSTOM_SECTION] draws one block out of it.
     */
    customSections: DwCustomCache? = null,
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

        // The one section this device still does not emit, and the reason is the TEXT and not the
        // drawing: design-workshop audio is transcribed server-side by the media queue and the words
        // land on `MediaFile.transcriptText`, which no draft on this handset carries and no endpoint
        // this client binds ever asks for. The recordings are here; their transcripts are not — see
        // the ledger entry for `includeTranscripts`. Emitting half of one — a numbered heading over
        // nothing — would be worse than the honest gap, because a heading in the contents that leads
        // nowhere reads as a corrupt file.
        SpecialSection.ANNEXURE_TRANSCRIPTS -> Unit

        // DRAWN NOW, from the copy of the answers this device keeps — `DwQuestionnaireStore`. It used
        // to sit beside the transcripts as the second section this handset could not build, and the
        // reason given was correct: the answers live in `QuestionnaireFormAnswer` on the server and
        // nothing under `data/` held one. That is what changed, and only that. The rule the old
        // comment leaned on — `WorkshopRepository`'s "Custom questionnaires" block falls back to the
        // device for NOTHING — is about ANSWERING offline, where a cached form cannot know a question
        // was retired an hour ago and a queued batch would be either lost or re-attached to wording
        // that replaced it. That rule stands untouched; this is a read-only copy for printing, which
        // no save path can reach.
        //
        // With no copy on the device it still draws nothing and still warns, exactly as before.
        SpecialSection.ANNEXURE_QUESTIONNAIRES ->
            renderQuestionnaireAnnexure(builder, section, plan, questionnaires)

        // THE SECOND SECTION THIS DEVICE DOES NOT EMIT, and — like the transcripts above — the gap
        // is the DATA and not the drawing. An AI layer is a row on the server carrying which model
        // produced a piece of text and which person accepted it; nothing under `data/` holds one,
        // no endpoint this client binds asks for one, and no screen here offers to fetch them. So
        // there is nothing to lay out, and laying out a numbered heading over nothing would be
        // worse than the honest gap for the reason the transcript arm gives: a heading in the
        // contents that leads nowhere reads as a corrupt file.
        //
        // IN PRACTICE THIS ARM IS UNREACHABLE TODAY, and it is written anyway. No template in
        // `REPORT_TEMPLATES` carries the section and this handset never asks the server to splice
        // it in, so nothing puts it in a plan. But `renderSpecialSection` is exhaustive by
        // construction — that is what made the compiler stop the build when the enum member was
        // added, which is exactly the forcing function this port wants — and an arm that silently
        // did the wrong thing would be indistinguishable from one nobody thought about. The
        // sentence a designer would read is in `ReportSettings.UNSUPPORTED_SECTIONS`, and it is
        // unconditional there because, unlike a transcript or a questionnaire copy, there is no
        // action on this phone that could ever close this gap.
        SpecialSection.ANNEXURE_AI_LAYERS -> Unit

        // THE DESIGNER'S OWN BLOCK, wherever `applyReportSettings` decided it belongs. Drawn here
        // rather than inside `renderStageSection` because that is the only position that can also be
        // a back annexure — see [SpecialSection.CUSTOM_SECTION] for what the inline version dropped.
        SpecialSection.CUSTOM_SECTION ->
            renderCustomSection(builder, section, plan, draft, imageFor, refs, customSections)
    }
}

/**
 * One block of questions a designer added to this workshop — `report_custom_sections.append_custom_section`.
 *
 * AT LEVEL 1, which is the whole placement half of this port: the server draws
 * `doc.heading(heading or item.title, 1, numbered=numbered)`, so the designer's "Loom audit" is a
 * top-level numbered section with a contents-page entry of its own. It used to be a level-2
 * sub-heading buried inside the stage, which gave one workshop two documents with different heading
 * numbers and different pagination for everything after them.
 *
 * NOTHING IS APPENDED FOR A SECTION WITH NOTHING TO SAY — not even the page break, which is why the
 * emptiness test sits above `section.pageBreakBefore` exactly as `append_custom_section`'s does. A
 * workshop that never reached those questions produces exactly the report it would have produced
 * without them, and a blank page before a heading that is not there is worse than either.
 *
 * A KEY THAT NAMES NOTHING IS AN ORDINARY OUTCOME AND NOT AN ERROR. `applyReportSettings` built the
 * template from the definition it was handed; a definition that changed between the plan and the
 * render — or a section retired while a report was being generated — leaves a section naming
 * nothing, and the silence here is the same silence a stage section with no data produces.
 *
 * ── ONE DIFFERENCE FROM THE SERVER THAT THIS EDIT DID NOT CLOSE ───────────────────────────────────
 *
 * The server's `section_prints` is `has_content_at`, which is true for an answered section OR one
 * carrying a LIVE REQUIRED field — deliberately, so an unanswered required question prints
 * "Not recorded." and its absence is visible in the document. This handset has always required an
 * actual answer (or a retired one), so a section whose only question is required and unanswered
 * prints nothing here and a heading plus "Not recorded." at the office. That predicate is older than
 * this change and is left exactly as it was: moving it in the same edit as the placement would have
 * made two behaviours change under one test, and it deserves its own case.
 */
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
    // Through the same door the form and the scorer read the definition by, so a retired section's
    // fields are forced retired here too — see `sectionsForStage`.
    val definition = customSectionsForReport(customSections) { stageKey ->
        draft?.stages?.get(stageKey)?.custom.orEmpty()
    }.firstOrNull { it.key == section.customKey } ?: return

    val values = draft?.stages?.get(definition.stageKey)?.custom.orEmpty()
    /*
      THE TEMPLATE'S TIER CAP IS ASKED WHEREVER THIS FILE DECIDES WHETHER SOMETHING IS EMPTY, OR THE
      EMPTINESS TEST IS ANSWERING A DIFFERENT QUESTION FROM THE ONE THE WRITER ANSWERS.

      [renderEntity] prints only `tier <= maxTier` — COMPACT_SUMMARY caps at BASIC — while the test
      below could easily ask whether ANY custom field holds an answer at ANY tier. STANDARD is the
      server's DEFAULT tier for a custom field, so the ordinary section is entirely STANDARD: under
      that template the two questions have different answers, and the cap-blind one produces a
      numbered heading, a description, and then nothing at all.
    */
    fun withinTier(tier: String): Boolean = DwTier.of(tier).ordinal <= template.maxTier.ordinal

    val entity = customSectionEntity(definition)
    val retired = retiredCustomFieldsWithAnswers(definition, values).filter { withinTier(it.tier) }
    val anyLive = entity.fields.any { withinTier(it.tier) && DwValues.isFilled(values[it.key]) }
    if (!anyLive && retired.isEmpty()) return

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    // `heading or item.title`, with NO third fallback — the server has none, and the key can never be
    // reached anyway: `CustomSectionIn.title` is `min_length=1`, so a stored section always has one.
    // The `.ifBlank { definition.key }` that used to sit here was therefore a divergence that could
    // only ever have printed a different heading from the office's if it fired at all.
    builder.heading(
        section.heading.ifBlank { definition.title },
        level = 1,
        numbered = template.numberHeadings,
    )
    if (definition.description.isNotBlank()) {
        // LEAD, which is `append_custom_section`'s style and the same one [renderStageSection] gives
        // a stage's `section.intro` directly above. NOTE is the register this file uses for a
        // statement ABOUT the document — "Not recorded.", the retired-wording marker, the provenance
        // line — and a designer's own description of their section is not that; it is the section's
        // opening sentence, and it printed a size and a colour smaller here than at the office.
        builder.para(definition.description, style = ParaStyle.LEAD)
    }
    if (anyLive) {
        // RENDERED THROUGH [renderEntity] AND NOT BY A SECOND WRITER. `customSectionEntity` turns one
        // section into a synthetic SINGLETON [EntityDto] carrying only its live, drawable fields, so
        // a custom answer gets the identical label/value grid, tier cap, unit suffix, enum-label
        // lookup, date formatting and MONEY grouping the stage's own answers get — which is exactly
        // what `report_custom_sections.display_value` buys by handing everything but its own ENUM
        // lists to `format_value`. A second writer would be a second set of answers to all of that,
        // and the day the two disagreed one question would print unlike every other question beside
        // it.
        //
        // `level = 1` because the section's own heading is now level 1, so its questions head at 2 —
        // the same rule the stage singleton follows under the stage's level-1 heading.
        val options = RenderOptions(
            maxTier = template.maxTier,
            includePhotos = false,   // a v1 custom question has no media type; nothing can be gathered
            numbered = template.numberHeadings,
            showEmptyNote = template.showEmptyNote,
            level = 1,
        )
        renderEntity(builder, entity, values, imageFor, refs, options)
    }
    // RETIRED FIELDS ARE PRINTED WHERE THEY HOLD AN ANSWER, under their own note, because that answer
    // was given under a wording that appears nowhere else and dropping it is how two copies of one
    // report come to disagree about the fieldwork. The cap is applied to these and to the unreadable
    // notes below as well as to the grid, so one export cannot omit a live STANDARD answer while
    // printing a retired one beside it.
    retired.forEach { field ->
        builder.para(
            (field.label.ifBlank { field.key }) + ": " +
                DwValues.text(values[field.key]) +
                " (recorded under a wording this workshop no longer asks)",
            style = ParaStyle.NOTE,
        )
    }
    // NAMED RATHER THAN DROPPED, and its VALUE deliberately not printed. This build has no way to
    // read a type it does not know, so anything it printed here would be a raw stored shape an
    // officer would read as the designer's words. Saying the question exists and that this copy
    // cannot carry its answer is the honest version — and it is the same register the export
    // screen's own unsupported-section sentences use.
    undrawableCustomFieldsWithValues(definition, values)
        .filter { withinTier(it.tier) }
        .forEach { field ->
            builder.para(
                (field.label.ifBlank { field.key }) + ": recorded, but this version of the app " +
                    "cannot read an answer of this kind (" + field.type + "), so it is not " +
                    "reproduced here. The office's copy of this report carries it.",
                style = ParaStyle.NOTE,
            )
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
    /**
     * The same label index the stage sections use — [imagesOf]'s second pass needs it.
     *
     * The contact sheet has to be gathered by the identical function the stage sections gather by,
     * WITH the identical arguments, or the two disagree about what was photographed: the server's
     * `_render_media_annexure` calls the whole of `_images`, REF pass included, so an annexure built
     * here from pass one alone is short by exactly the borrowed pictures — which is the divergence
     * this annexure's own KDoc says it walks the registry rather than the template to avoid.
     */
    refs: DwRefLabels,
) {
    val gathered = ArrayList<Pair<ImageRef, String>>()
    /*
      WHAT THIS WALK ASKED FOR AND DID NOT GET, counted here as well as by the document-wide resolver.

      The outer count decides whether the DOCUMENT says anything at all; this one decides whether
      THIS SECTION exists. They are different questions and the answers differ: a template can print
      photographs in its stage sections and carry no annexure, and — the case that matters — this
      walk can come back with nothing whatever while every id it asked about was real.

      `if (gathered.isEmpty()) return` used to be the whole of that decision, and it deleted the
      heading, the plates and the contents entry together. A reader of the resulting file cannot tell
      "this workshop was never photographed" from "this handset does not hold the pictures" — the
      first is a fact about the fieldwork and the second is a fact about the phone, and the file
      presented both as the first.
    */
    val unresolvedHere = LinkedHashSet<String>()
    val resolve: (String) -> ImageRef? = { token ->
        imageFor(token).also { if (it == null && token.isNotBlank()) unresolvedHere.add(token) }
    }
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
                imagesOf(entity, values, resolve, plan.template.maxTier, refs = refs)
                    .forEach { (ref, caption) ->
                        // The stage's own title where the photograph has no caption of its own. A
                        // plate with no line under it is a picture, not evidence.
                        gathered += ref to caption.ifBlank { stage.title }
                    }
            }
        }
    }
    // Nothing was photographed and nothing was asked for: the section is genuinely empty and stays
    // out of the file, which is the behaviour this annexure has always had and the only case it was
    // ever right for.
    if (gathered.isEmpty() && unresolvedHere.isEmpty()) return

    if (section.pageBreakBefore) builder.add(PageBreakBlock)
    builder.heading(
        section.heading.ifBlank { "Photographic record" },
        level = 1,
        numbered = plan.template.numberHeadings,
    )
    // The note goes ABOVE the plates that did resolve, not below them: a reader who counts twelve
    // photographs against a workshop they know held fifteen must meet the explanation before they
    // start counting, and a reader of a section with no plates at all must meet it instead of a
    // blank half page.
    if (unresolvedHere.isNotEmpty()) {
        builder.para(unresolvedMediaNote(unresolvedHere.size), style = ParaStyle.NOTE)
    }
    if (gathered.isNotEmpty()) builder.add(ImageGridBlock(images = gathered, columns = 3))
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
    /** This workshop's custom definition; null scores exactly as this annexure did before it existed. */
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
            // NO REF MASKING FOR THESE, and none is possible: v1 declares no REF type at all, so
            // there is no reference a custom answer could hold that the report could fail to resolve.
            // That exclusion is the whole reason - a dangling custom reference would read FILLED on
            // every form and UNFILLED in the document, which is the defect this annexure measures.
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
        imagesOf(setupEntity, setupValues, imageFor, plan.template.maxTier, limit = 1, refs = refs)
            .firstOrNull()?.first
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
    /**
     * Whether an unfilled REQUIRED field prints "Not recorded." — `TemplateSection`'s
     * `show_empty_note`, which is `true` on all six templates on both surfaces.
     *
     * Defaulted true because that is what every template says and what the server does. A missing
     * REQUIRED field is information — it says the record is incomplete — and a missing optional one
     * is not, which is why the substitution is keyed on `required` and not on emptiness.
     */
    val showEmptyNote: Boolean = true,
    /**
     * The heading level a FIELD's own sub-heading sits at, before the `min(4, level + 1)` the two
     * writers cap everything by. 1 for a stage singleton, the record's own level inside a card.
     *
     * Defaulted to 1 so no existing call site moves — see [renderEntity] for what the level is for.
     */
    val level: Int = 1,
    /**
     * Photographs BEFORE the fields rather than after them.
     *
     * The server's asymmetry, reproduced rather than tidied away: `_render_cards` places a record's
     * pictures immediately under its heading and before any field, and `_render_stage` places a
     * singleton's after everything. It reads correctly both ways round — a card IS its photograph
     * and a stage's photographs are an appendix to its prose — and the point of this port is that
     * the phone's copy and the office's copy of one workshop are the same document, not that this
     * file has a tidier rule than `report_builder.py`.
     */
    val photosFirst: Boolean = false,
    /**
     * Whether a [MetricRowBlock] is emitted for this record's METRIC fields.
     *
     * ONLY A STAGE SINGLETON GETS ONE, which is where the server puts it: `_render_stage` builds the
     * metric row itself, from `_printable(single, …, {METRIC})`, and NEITHER `_render_cards` nor
     * `_render_narrative` nor the per-row block under `_render_table` emits one for a collection row.
     * [renderEntity] emitted it for every record it drew, so the day a METRIC field is declared on a
     * collection the office's copy would print an ordinary key-value pair and the handset's a band of
     * big numbers per row — different heights, different page boundaries, one workshop, two files.
     *
     * DEFAULTED FALSE, i.e. to the collection behaviour, so the divergence cannot come back by
     * omission at a call site added later; the ONE place that wants a metric row asks for it. Latent
     * today — all four METRIC fields in the shipped registry are on SINGLETON entities — which is
     * exactly why it is worth pinning before it is not.
     */
    val metricRow: Boolean = false,
)

/**
 * The two media types that have a PICTURE path, as against the three that only have a sentence.
 *
 * [DwFieldType.isMedia] answers for all five and is the right question when the subject is capture —
 * a FILE, an AUDIO clip and a VIDEO are all drawn by the capture surface. It is the WRONG question in
 * the report, and asking it here is what dropped seventeen fields off the phone's copy: `_images`
 * filters on IMAGE and IMAGE_LIST, and `format_value` prints a count and a noun for the other three.
 * Anywhere this renderer means "can be placed as a plate", it must ask this and not [isMedia].
 */
private val DwFieldType.isPicture: Boolean
    get() = this == DwFieldType.IMAGE || this == DwFieldType.IMAGE_LIST

/**
 * Every resolvable image on one record, paired with its caption — `ReportBuilder._images`.
 *
 * [limit] of 0 is no cap. Split out of [renderEntity] because the cover's hero photograph, a GALLERY
 * section and the photographic annexure all need the same list without the rest of a record's fields
 * — and because [renderEntity] used to repeat the walk inline, which is how the two came to disagree.
 *
 * ── TWO SOURCES, IN THIS ORDER, AND THE ORDER IS THE POINT ────────────────────────────────────────
 *
 * PASS ONE is the row's own media fields. PASS TWO is the photograph of whatever each REF field on
 * the row points at ([DwRefLabels.photoOf]) — the half of the registry where the picture was never
 * copied onto the row. Eleven REF fields in the registry are printed and five entities name a REF as
 * their `labelField`, so this is not a corner: a prototype with no progress shots of its own printed
 * no picture on the handset and its sketch on the server, and the two contact sheets of one workshop
 * came out with different numbers of plates with nothing in either file admitting it.
 *
 * SEPARATE PASSES AND NOT ONE WALK, for the reason `_images` states in as many words: the registry's
 * field ORDER decides nothing here and a single walk would let it decide everything. `prototype`
 * declares its REF fields above its photo fields, so one pass would lead a prototype's sub-section
 * with a borrowed catalogue picture and bury the four progress shots the artisan actually took. The
 * designer's own photographs come first and the reference's is an addition, never a substitute.
 *
 * DEDUPLICATED BY MEDIA ID, first caption winning, insertion-ordered — the `wanted` map in `_images`.
 * Hydration already copies an artisan's picture onto `participant.photo` at save time, so the
 * `artisanRef` beside it can resolve to the very same file; without the map that participant's
 * photograph prints twice on one card.
 *
 * A caller with no [refs] gets pass one only. Nothing in `main/` passes null — it is there so a test
 * that only cares about a row's own pictures does not have to build a label index for it.
 */
private fun imagesOf(
    entity: EntityDto,
    values: Map<String, JsonElement>,
    imageFor: (String) -> ImageRef?,
    maxTier: DwTier,
    limit: Int = 0,
    refs: DwRefLabels? = null,
): List<Pair<ImageRef, String>> {
    val visible = entity.liveFields.filter { DwTier.of(it.tier).ordinal <= maxTier.ordinal }
    val captionByTarget = visible.filter { it.captionFor.isNotBlank() }.associateBy { it.captionFor }

    // (media id -> caption). `LinkedHashMap` + `putIfAbsent` IS the dedup, and it is keyed on the id
    // rather than on the field for the participant case above.
    val wanted = LinkedHashMap<String, String>()

    visible.forEach { field ->
        if (field.reportRole == "HIDDEN" || field.captionFor.isNotBlank()) return@forEach
        val type = DwFieldType.of(field.type)
        // [isPicture] AND NOT `isMedia` — `_images` filters on IMAGE and IMAGE_LIST. Asking `isMedia`
        // offered a FILE's, an AUDIO clip's and a VIDEO's media ids to [imageFor] as though they were
        // photographs, so a stage-1 sanction order resolving in the media cache would have been
        // placed as a plate in the report with the field's label under it.
        if (!type.isPicture) return@forEach
        val stored = values[field.key]
        if (!DwValues.isFilled(stored)) return@forEach
        val caption = captionByTarget[field.key]?.let { DwValues.text(values[it.key]) }.orEmpty()
        val ids = if (type == DwFieldType.IMAGE_LIST) DwValues.list(stored) else listOf(DwValues.text(stored))
        ids.filter { it.isNotBlank() }.forEach { id ->
            wanted.putIfAbsent(id, caption.ifBlank { field.label })
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
    // EVERY ID IS OFFERED TO [imageFor] EVEN WHEN [limit] WILL DISCARD THE ANSWER, which is where
    // this deliberately diverges from `_images` (it breaks out of the resolve loop once it has
    // enough). `imageFor` is not a pure lookup on this client: `buildWorkshopDocument` wraps it so
    // that a token it cannot resolve is recorded, and that record is the only thing on any surface
    // that says the file is short of photographs. Stopping early would make the count depend on how
    // many plates the cover happened to ask for — four of the six templates carry no photographic
    // annexure, so for those the cover's `limit = 1` would be the ONLY walk of the setup entity and
    // the rest of its unresolvable ids would go unmentioned. The cost is a `HashMap` lookup per
    // extra id; the benefit is that "3 photographs are missing" means three photographs.
    return if (limit > 0) gathered.take(limit) else gathered
}

/**
 * Reference model -> the extra hydration SOURCE keys under which it publishes its DISPLAY NAME —
 * `report_builder._REFERENCE_NAME_SOURCES`.
 *
 * `"name"` is always accepted and is not repeated here; this table is only for a model whose `data`
 * payload calls the label column something else.
 *
 * ONE MODEL NEEDS IT AND IT IS THE ONE THAT REACHES THE COVER PAGE. `REFERENCE_MODELS["Craft"].data`
 * emits `{"craftName": …, "craftLocalName": …}` while its label is the craft's `name` — the same
 * column under a different key, because stage 1's cover asks for "Craft name" and the mapping is
 * one-to-one with the boxes it fills rather than with the record's columns. `Craft` also declares a
 * `media_field`, so a craft photograph really does reach [imagesOf]'s second pass; matching the
 * literal "name" alone left that one picture captioned from the LIVE record while
 * `workshopSetup.craftName` — a COVER_FIELD — printed the frozen copy, on the same page.
 *
 * Declared here rather than derived, exactly as the server declares it: neither surface may reach
 * the reference models to ask, and this one has no network at all.
 */
private val REFERENCE_NAME_SOURCES: Map<String, List<String>> = mapOf("Craft" to listOf("craftName"))

/**
 * What to print under a photograph borrowed from the record a REF field points at —
 * `ReportBuilder._reference_caption`.
 *
 * ── THE ROW'S OWN FROZEN NAME FIRST ───────────────────────────────────────────────────────────────
 *
 * This used to be `refs.label(refId).ifBlank { field.label }` — and it carried, verbatim, the very
 * Python comment the server deleted when it stopped doing that. [DwRefLabels.label] resolves out of
 * the picker's cached snapshot of the referenced record AS IT STANDS TODAY, which is not the row's
 * frozen copy, so this was the single place on this surface where a live re-resolved name reached a
 * printed page. The visible failure has one page carrying both answers: a prototype's sub-section
 * printing "Developed from: Sambalpuri Saree" out of the frozen `productName` that hydration copied
 * at save time, and directly beneath it the borrowed catalogue photograph captioned "Sambalpuri Ikat
 * Saree — revised 2027", because somebody renamed the product record after the workshop closed. One
 * product, two names, and nothing on the page to say which the workshop actually worked from.
 *
 * GENERIC, WITH NO PER-ENTITY CODE. [FieldDto.refHydration] is the server's own
 * `REFERENCE_HYDRATION` mapping, published per field by `field_to_dict` precisely so the clients need
 * no copy of it — it already says which box on THIS row the referenced record's `name` was copied
 * into, so the caption asks it rather than guessing.
 *
 * THE FALLBACKS ARE THE SERVER'S, IN ORDER. Where the mapping seeds no name — and where it seeded one
 * into a box the designer left the picker to fill and it never arrived — the reference's own label is
 * still the right caption, for the reason the line it replaced gave: the field's label is the
 * RELATIONSHIP and not the subject, so "Artisan" under a photograph is a category where "Bhikari
 * Meher" is a caption. The field label remains the last resort, for a row whose label never loaded.
 */
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
        // Through [displayValue] and not `DwValues.text`, exactly as the server goes through
        // `_value`: the frozen box can itself be a REF, and a caption is not a place for a cuid.
        val frozen = displayValue(targetSpec, values[target], refs)
        if (frozen.isNotBlank()) return frozen
    }
    return refs.label(refId).ifBlank { spec.label }
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

    /**
     * The first photograph on the row a reference points at — `ReportData.reference(...).photo`.
     *
     * PASS TWO OF [imagesOf] IS BUILT ON THIS, and it is the whole of "a photograph appears beside
     * the thing it is a photograph of" for the half of the registry where the picture was never
     * copied onto the row. `prototype.sketchRef` points at a sketch row whose own photographs live in
     * stage 11 of the same draft; without this the phone's copy of stage 13 prints no picture for
     * every prototype that carries none of its own, while the office's copy prints the sketch.
     *
     * ONLY ROWS OF THIS WORKSHOP, never an external record, and that is a deliberate narrowing of the
     * server's behaviour rather than an oversight. The server resolves an artisan through the
     * database and finds a `MediaFile` it can read; the phone's only knowledge of an external record
     * is whatever a picker cached ([DwReferenceStore]), whose media ids belong to the SERVER's id
     * space and resolve against nothing in `draft.media`. Asking [imageFor] for one would not produce
     * a picture — it would produce a token counted as unresolved by `buildWorkshopDocument`, so the
     * export screen would report "4 photographs are missing" for four pictures that were never on
     * this handset and were never going to be. A silent gap is wrong; a wrong count is worse.
     *
     * The tier cap is deliberately NOT applied to the referenced row's own fields: the cap asks what
     * THIS template prints of THIS record, and the borrowed picture is being printed as context for
     * the referring row, which has already passed the cap. `_images` makes the same choice — it tests
     * `_visible` on the REF spec and never on the reference's photo.
     */
    internal fun photoOf(refId: String): String? {
        if (refId.isBlank()) return null
        val (entity, row) = rowsById[refId] ?: return null
        entity.liveFields.forEach { spec ->
            val type = DwFieldType.of(spec.type)
            // A PHOTOGRAPH, which is what this function's name promises: `isMedia` would have let the
            // referenced record's attached PDF stand in as its picture, whichever media field the
            // registry happens to declare first.
            if (!type.isPicture || spec.reportRole == "HIDDEN") return@forEach
            val stored = row[spec.key]
            if (!DwValues.isFilled(stored)) return@forEach
            val ids = if (type == DwFieldType.IMAGE_LIST) DwValues.list(stored) else listOf(DwValues.text(stored))
            ids.firstOrNull { it.isNotBlank() }?.let { return it }
        }
        return null
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
 * The substitute an unfilled REQUIRED field prints — `_printable`'s "Not recorded."
 *
 * ONE SPELLING, because it is compared as well as printed: the section-level note at the foot of an
 * empty stage uses the same string, and the two saying nearly-the-same-thing is how a reader comes to
 * believe they mean different things.
 */
private const val NOT_RECORDED = "Not recorded."

/**
 * The registry roles the key-value grid does NOT print, because `_render_narrative` places them
 * itself — and the ONE name whose absence from this set was a regression on paper.
 *
 * ── WHY A NEGATIVE TEST AT ALL ────────────────────────────────────────────────────────────────
 *
 * The server asks positively: `_printable(entity, row, {KEY_VALUE, COVER_FIELD, TABLE_COLUMN})`.
 * This file asks negatively, and keeps doing so on the argument that has always stood here: a role
 * a registry one version ahead of this build carries must reach paper under its own label rather
 * than be dropped, which is the same shape of silent loss the RICH_TEXT drop was. Naming the three
 * positively would turn every future role into that drop. So the set below is the roles this build
 * KNOWS are placed elsewhere, and anything unrecognised still lands in the grid.
 *
 * ── AND THE NAME THAT WAS MISSING WAS GALLERY ─────────────────────────────────────────────────
 *
 * The negative test used to read `!= NARRATIVE && != BULLETS && != METRIC`, which is three of the
 * six roles that are placed elsewhere. That cost nothing while `printable` skipped media-typed
 * fields wholesale; the day that skip was removed — correctly, because it was withholding the
 * "1 document attached" sentence for seventeen FILE/AUDIO/VIDEO fields — GALLERY fields started
 * reaching this lambda, [displayValue] answered "" for an IMAGE as it must, and the
 * `required && showEmptyNote` arm fired. `stage_definitions`' sketch entity declares
 * `f("image", "Sketch image", IMG, B, required=True, report_role=GALLERY)`, so every sketch whose
 * photograph the designer HAD taken printed "Sketch image: Not recorded." in the grid, directly
 * under the plate [imagesOf] had just placed. The office's copy printed neither line, because
 * GALLERY is not in `_render_narrative`'s role set and never reaches `_printable` there at all.
 * Two copies of one workshop, one of them calling the designer's own photograph missing.
 *
 * CAPTION is in the set for completeness rather than for effect — a caption field is already
 * withheld by its `captionFor`, and `_printable` skips it on the same test. HIDDEN likewise: the
 * lambda's caller refuses it first. They are named because a reader checking this set against
 * `ReportRole` should find every value accounted for, and a role that is excluded by two
 * independent tests is safer than one excluded by the test somebody is about to refactor.
 *
 * ── AND TABLE_COLUMN IS ABSENT FROM THIS SET ON PURPOSE ───────────────────────────────────────
 *
 * Keeping TABLE_COLUMN in the grid is what makes the CARDS presentation lossless: a sketch's
 * number, category and expected price are TABLE_COLUMNs, and omitting them from every presentation
 * that draws no table would be a filter on the designer's work rather than a layout of it. It is in
 * `_render_narrative`'s three-role set for the same reason. Adding it here to "tidy" the set would
 * reintroduce a silent drop that a stage read as 100% complete.
 */
private val ROLES_THE_GRID_DOES_NOT_PRINT: Set<String> =
    setOf("NARRATIVE", "BULLETS", "METRIC", "GALLERY", "CAPTION", "HIDDEN")

/**
 * The roles for which an unfilled REQUIRED field produces no "Not recorded." anywhere.
 *
 * DERIVED FROM [ROLES_THE_GRID_DOES_NOT_PRINT] RATHER THAN RETYPED, because the two sets have to
 * move together: NARRATIVE and BULLETS are placed outside the grid and DO print the note (their own
 * loops in [renderEntity] fall through to it), and every other role placed outside the grid prints
 * nothing for an empty answer — a METRIC row is suppressed on a collection row by
 * [RenderOptions.metricRow], a GALLERY has only a plate, a CAPTION is withheld with its image.
 *
 * It is asked in exactly one place, [renderCollection]'s per-row `hasExtra`, and it is what stops a
 * numbered sub-heading being drawn over nothing. `_render_table` states the same set positively —
 * `_printable(entity, row, {NARRATIVE, TABLE_COLUMN, KEY_VALUE, COVER_FIELD, BULLETS})` — and this
 * subtraction is the negative form of it, kept negative so an unrecognised role still counts, for
 * [ROLES_THE_GRID_DOES_NOT_PRINT]'s reason.
 */
private val ROLES_THAT_PRINT_NO_EMPTY_NOTE: Set<String> =
    ROLES_THE_GRID_DOES_NOT_PRINT - setOf("NARRATIVE", "BULLETS")

/**
 * One record's fields, sorted into the report roles the registry declares — `_render_narrative`.
 *
 * Returns whether anything at all reached the document, which is what decides between "Not recorded."
 * and a bare heading over half a blank page. It is measured from [DocumentBuilder.blockCount] rather
 * than tracked by hand because `para`, `bullets` and `keyValues` all silently drop empty content —
 * a record can go through every branch below and add nothing.
 *
 * ── FOUR PASSES IN THE SERVER'S ORDER, NOT ONE WALK OF THE FIELD LIST ─────────────────────────────
 *
 * This used to be a single walk that emitted a RICH_TEXT field's blocks INLINE the moment it met one
 * and buffered everything else, so one record came out as prose, then metrics, then the key-value
 * grid, then photographs, while `_render_narrative` emits the grid, then the prose, then the bullets,
 * then (from its caller) the metrics and the photographs. Every record in the document was laid out
 * in a different order in the two copies of one workshop — so the page boundaries moved, and with
 * them the contents page's page numbers. A reader comparing the phone's copy against the office's,
 * which is the case this whole port exists to serve, sees a document that has been rearranged.
 *
 * ── EVERY PRINTED FIELD CARRIES ITS LABEL, WHICH 98 OF THEM DID NOT ───────────────────────────────
 *
 * The old RICH_TEXT branch emitted `toReportBlocks(...)` and nothing else. All 81 NARRATIVE and 17
 * BULLETS fields in the bundled registry are RICH_TEXT, so ALL 98 printed as anonymous prose: stage
 * 2 came out of the handset as one heading followed by eight consecutive unlabelled runs of text,
 * where the office's copy prints eight numbered sub-headings that also appear in its contents page.
 * An officer reading the handset's copy could not tell which pro-forma question any paragraph
 * answered. `_render_narrative`'s rule is ported exactly: a heading when the flattened text runs past
 * 160 characters or the field produced more than one block, a `"Label:"` lead-in otherwise, and for
 * BULLETS a heading unconditionally.
 *
 * ── AND AN UNFILLED REQUIRED FIELD IS SAID RATHER THAN SKIPPED ────────────────────────────────────
 *
 * `if (!DwValues.isFilled(stored)) return@forEach` dropped required and optional fields alike, while
 * `_printable` substitutes [NOT_RECORDED] for a required one on all six templates. The handset's
 * report therefore understated incompleteness in its body while the Data completeness annexure in
 * the SAME file stated it — one document, two arithmetics, which is the failure [maskUnresolvableRefs]
 * exists to prevent, reached through the other door. It fires whenever a required field is unfilled,
 * which is the normal mid-workshop state.
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

    /*
      `_printable`, PORTED WHOLE — the one place emptiness is decided, asked four times below.

      A field answers with its text, or with [NOT_RECORDED] when it is REQUIRED and the template
      wants the note, or with nothing at all. Printing "Not recorded." for every unfilled ADVANCED
      field would bury the report in negatives, which is why the substitution keys on `required`.

      MEDIA-TYPED FIELDS ARE ASKED, AND [displayValue] DECIDES WHAT THEY SAY. This used to skip them
      wholesale, under a comment asserting that "`_printable` is only ever asked for non-media roles
      on the server". That was false when it was written — `_printable` applies no type filter at all
      and leans entirely on `format_value` to answer "" for a picture — and once the server grew its
      count-and-noun branch for FILE/AUDIO/VIDEO, the skip became a straight divergence: the office
      printed "Sanction order: 1 document attached" and the phone printed nothing, for all seventeen
      such fields in the registry. A designer attaching the ministry's sanction order at stage 1 got
      two documents that disagree about whether it exists.

      IMAGE and IMAGE_LIST still reach paper only through [imagesOf] — [displayValue] answers "" for
      them, so a photograph that WAS taken adds nothing here. What the substitution therefore covers
      is a REQUIRED media field that is EMPTY **and whose role is one the grid prints anyway**: an
      unattached `sanctionDocument` says "Not recorded." exactly as `_printable` says it. Which roles
      those are is [ROLES_THE_GRID_DOES_NOT_PRINT]'s business and not this lambda's — see the defect
      recorded there, which is what a role filter left one name short costs.
     */
    fun printable(match: (String) -> Boolean): List<Pair<FieldDto, String>> = visible.mapNotNull { field ->
        if (field.reportRole == "HIDDEN" || !match(field.reportRole)) return@mapNotNull null
        if (field.captionFor.isNotBlank()) return@mapNotNull null   // placed with their image
        val text = displayValue(field, values[field.key], refs)
        when {
            text.isNotBlank() -> field to text
            field.required && options.showEmptyNote -> field to NOT_RECORDED
            else -> null
        }
    }

    val narrative = printable { it == "NARRATIVE" }
    val bullets = printable { it == "BULLETS" }
    val metrics = printable { it == "METRIC" }
    // EVERYTHING THE REGISTRY DOES NOT PLACE SOMEWHERE ELSE IS A PAIR — see
    // [ROLES_THE_GRID_DOES_NOT_PRINT] for why this stays a negative test and for the name whose
    // absence from it printed "Sketch image: Not recorded." under a sketch that had one.
    val pairs = printable { it !in ROLES_THE_GRID_DOES_NOT_PRINT }

    val gallery = if (options.includePhotos) {
        // `includePhotographs = false` and the templates that set it per section — the price list a
        // buyer sees, the narrative stages of the compact summary — gather nothing at all. The
        // photographs are not merely hidden by the writer, they never enter the document, so a
        // report that excludes them does not carry their bytes either.
        val every = imagesOf(entity, values, imageFor, options.maxTier, refs = refs)
        if (options.maxPhotos > 0) every.take(options.maxPhotos) else every
    } else {
        emptyList()
    }

    fun drawImages() = placeImages(builder, gallery, options.photoColumns)
    if (options.photosFirst) drawImages()

    // THE GRID FIRST, which is the whole of the ordering fix — `_render_narrative` emits its
    // `KeyValueBlock` before any prose. Still through [DocumentBuilder.keyValues] and still at one
    // column: this change is about WHERE the grid sits, and widening it to the server's two columns
    // would move every page boundary in the document on top of that. See the ledger note.
    if (pairs.isNotEmpty()) builder.keyValues(pairs.map { (field, text) -> field.label to text })

    // THE HEADING LEVEL IS THE CALLER'S PLUS ONE, capped at 4 — `min(4, level + 1)`. Both writers
    // index a four-element table by level (`DocxWriter.emitHeading`), so a fifth is an index out of
    // bounds in the middle of an export a designer is waiting on.
    val fieldLevel = minOf(4, options.level + 1)

    narrative.forEach { (field, text) ->
        // `toReportBlocks` is the same function the server calls (`rich_text.to_report_blocks`) and
        // the same one the editor's own preview calls, which is why the prose looked right on screen
        // right up to the moment of export. It already merges consecutive BULLET_ITEM/ORDERED_ITEM
        // runs into one list block, so BULLETS needs no separate treatment below — the difference
        // between the two roles is only whether the label is a heading or a lead-in.
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
        // No blocks and yet [printable] gave us something means either a plain LONG_TEXT answer or a
        // REQUIRED field that is unfilled and `text` is the note. Falling through rather than
        // skipping is the difference between a gap the reader can see and one they cannot.
        if (DwFieldType.of(field.type) == DwFieldType.LONG_TEXT && text.length > 160) {
            builder.heading(field.label, level = fieldLevel, numbered = options.numbered)
            builder.para(text)
        } else {
            builder.para("${field.label}: $text")
        }
    }

    bullets.forEach { (field, text) ->
        // UNCONDITIONALLY A HEADING, where NARRATIVE decides by length. A bulleted answer is a list
        // the designer built in the editor and a list with no title above it reads as a continuation
        // of the paragraph before it.
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
        // THE PRE-PROMOTION PATH, still reached by a plain LONG_TEXT bullets field, by a rich field
        // whose value is a bare string written before the promotion, and by the [NOT_RECORDED] note.
        // Semicolons are treated as line breaks because that is how these fields were filled in for
        // two seasons — `_render_narrative` says the same and splits on the same characters.
        builder.bullets(text.replace(";", "\n").split("\n").map { it.trim() })
    }

    // [RenderOptions.metricRow] gates this, and it is off by default: `_render_stage` is the only
    // renderer on the server that builds a metric row, so a collection row must not grow one here.
    if (options.metricRow && metrics.isNotEmpty()) {
        // FOUR, as the server caps it. A metric row is a band of big numbers across the text column
        // and the fifth one is unreadable at any width the page can give it.
        builder.add(
            MetricRowBlock(metrics = metrics.take(4).map { (field, text) -> Triple(field.label, text, field.unit) })
        )
    }

    if (!options.photosFirst) drawImages()
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
    /*
      SIX COLUMNS, AND NO MORE — `_table_columns`' `return columns[:6]`, which this had no equivalent
      of at all.

      Six on A4 is about the limit before a cell is too narrow to hold a craft name. THREE live
      collections in the bundled registry declare seven TABLE_COLUMN fields — `existingProduct`
      (stage 6), `prototypeValidation` (stage 15) and `followUp` (stage 22) — and every one of the
      21 columns is BASIC or STANDARD, so no template's tier cap trims them under seven either. All
      three reached the table path, so stage 22 printed as seven columns on the phone and as six plus
      a per-row key-value line at the office: different column counts, different row heights,
      different pagination, and nothing in either file admitting it.

      Nothing is LOST by capping, because the overflow lands in `leftovers` below — which is exactly
      what `_render_table`'s `skip=column_keys` makes of it.
    */
    val columns = visible
        .filter { it.reportRole == "TABLE_COLUMN" && !DwFieldType.of(it.type).isMedia }
        .take(6)
    val columnKeys = columns.map { it.key }.toSet()

    // GALLERY prints the pictures and nothing else: every image on every row, under one heading.
    if (options.presentation == Presentation.GALLERY) {
        val every = rows.flatMap { imagesOf(entity, it, imageFor, options.maxTier, refs = refs) }
        placeImages(builder, if (options.maxPhotos > 0) every.take(options.maxPhotos) else every, options.photoColumns)
        return builder.blockCount != before
    }

    // One sub-section per record: heading, then its fields. This is also where a TABLE lands when
    // the registry declares no columns to build one from, exactly as the server falls through.
    val asCards = options.presentation == Presentation.CARDS ||
        (options.presentation != Presentation.NARRATIVE &&
            options.presentation != Presentation.KEY_VALUE &&
            columns.isEmpty())
    // A RECORD'S OWN LEVEL IS [rowLevel], so its FIELDS' sub-headings sit one below it. Threaded
    // rather than defaulted, or every narrative on every card would head at level 2 beside the
    // record's own level-3 heading and read as a sibling of the record rather than as part of it.
    val rowOptions = options.copy(level = rowLevel)

    if (asCards) {
        rows.forEachIndexed { index, row ->
            builder.heading(rowHeading(entity, row, index, refs), level = rowLevel, numbered = options.numbered)
            // PHOTOGRAPHS BEFORE THE FIELDS, which is `_render_cards`' order and not this file's old
            // one — see [RenderOptions.photosFirst]. A card IS its photograph: the office's copy of
            // stage 13 reads heading, prototype photographs, grid, prose, and the handset's read
            // heading, prose, grid, photographs.
            renderEntity(builder, entity, row, imageFor, refs, rowOptions.copy(photosFirst = true))
        }
        return builder.blockCount != before
    }

    // NARRATIVE and KEY_VALUE: the rows run on as prose or as label/value blocks, with no table and
    // no per-record heading. It is what the agency format asks for on the outcomes stage, where a
    // heading per record would put a numbered sub-section around a single paragraph.
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

    /*
      Whatever the table could not carry — the overflow columns, the photographs, the prose — follows
      underneath, per row, rather than being dropped. A sketch table with no sketches in it is a
      table of file names.

      MEMBERSHIP OF THE DRAWN COLUMNS, NOT THE TABLE_COLUMN ROLE. This tested the role, which meant
      two silent drops. The seventh TABLE_COLUMN of stage 22 could not reach the per-row block even
      once the cap above existed, because the role test excluded it exactly as it excluded the six
      that WERE drawn; and a media-typed TABLE_COLUMN was excluded from `columns` by `!isMedia` above
      AND from the leftovers by the role test, so it would have been dropped from the document
      entirely. The registry declares no such field today, so that half is latent — but it is a
      silent drop, not a layout difference, and it costs one word to close.

      `_render_table` says the same thing with `skip=column_keys`: what the narrative block prints is
      everything the TABLE did not.

      THE DIVERGENCE THAT USED TO BE RECORDED HERE IS CLOSED, and the paragraph is rewritten rather
      than deleted because a reader who remembers it would otherwise go looking for it. It said
      `_table_columns` applied no media filter, so a media TABLE_COLUMN was a column at the office
      and a per-row line here, and that the agreement "has to be made on the server side first".
      It was: `_table_columns` now reads `... and not f.type.is_media` and its docstring says why
      ("A MEDIA FIELD IS NEVER A COLUMN, WHATEVER ROLE IT DECLARES"). The two surfaces build the
      same column set, and the `!isMedia` above is the handset's half of one rule rather than a
      unilateral one.

      WHAT IS STILL A DIVERGENCE, AND IS A DIFFERENT ONE, is the column WIDTHS: five entities are
      knowingly on the proportional fallback rather than on declared percentages, and
      `test_the_tables_whose_declared_widths_govern_still_do` pins that deliberately. Do not read the
      closed media question as licence to rebalance those.
    */
    val leftovers = visible.filter {
        it.key !in columnKeys && it.reportRole != "HIDDEN" && it.captionFor.isBlank()
    }
    if (leftovers.isEmpty()) return true
    // A PICTURE leftover in a section that prints no photographs is not a leftover at all. Counting
    // it would head a sub-section for every row of a photographs-off table and then print nothing.
    //
    // [isPicture] AND NOT [DwFieldType.isMedia], which is the same correction the `printable` walk
    // above needed. `includePhotographs = false` is a statement about PLATES — the price list a buyer
    // sees, the compact summary's narrative stages — and it has never had anything to say about
    // whether a sanction order is attached. Asking `isMedia` here withheld the FILE/AUDIO/VIDEO
    // sentence from exactly the templates that print the least, which are the ones an officer is most
    // likely to be handed.
    val carried = leftovers.filter { options.includePhotos || !DwFieldType.of(it.type).isPicture }
    rows.forEachIndexed { index, row ->
        // A REQUIRED leftover that is unfilled IS something to print — it prints [NOT_RECORDED] —
        // so it has to count here too, or the heading is suppressed over the one note the reviewing
        // officer needs. `_render_table` computes `has_extra` from `_printable`, which is the same
        // statement: the substitution is part of what a row has to say.
        //
        // NO TYPE GUARD ON THE SUBSTITUTION, AND A ROLE GUARD INSTEAD. It used to read `&& !isMedia`,
        // which meant a required sanction order that was never attached suppressed the very heading
        // its [NOT_RECORDED] note needed. Dropping the type test was right; leaving nothing in its
        // place was not, because it counts a row as having something to say on the strength of a note
        // [renderEntity] will not print. A required GALLERY photograph is the case: no note (see
        // [ROLES_THE_GRID_DOES_NOT_PRINT]) and, with the picture never taken, no plate either — so the
        // row got a numbered sub-heading over nothing at all. `_render_table` asks the same question
        // the same way round: its `has_extra` role set is every role EXCEPT GALLERY, CAPTION, METRIC
        // and HIDDEN, which is exactly [ROLES_THAT_PRINT_NO_EMPTY_NOTE].
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
            // Photographs first here too: `_render_table` calls `_place_images` before
            // `_render_narrative` on the per-row block, exactly as `_render_cards` does.
            rowOptions.copy(photosFirst = true),
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
 *
 * ── AN UNFILLED REQUIRED CELL SAYS SO, WHICH IT DID NOT ───────────────────────────────────────────
 *
 * `_printable` substitutes [NOT_RECORDED] for a required field with no answer, and every other
 * presentation on the server carries that substitute through. The table did not, on either surface,
 * until the server's `_render_table` was fixed to route its cells through `_printable`; this is the
 * Android half of the same repair. The case that shows it worst is eighteen prototype rows with one
 * required column left blank on several of them: a reviewing officer reading a blank cell has no way
 * to tell "not answered" from "not applicable", and the same file's completeness annexure states the
 * shortfall three pages later — one document, two arithmetics.
 *
 * AN EMPTY RICH VALUE FALLS THROUGH TO THE NOTE rather than to `plainRuns`, which is the guard
 * `_cell_runs`'s own docstring has always described ("would replace the note with a blank cell —
 * turning a visible gap into an invisible one"). It was a true statement about a note this path never
 * produced: the guard was correct and unreachable. Now it is reached.
 */
private fun cellRuns(
    field: FieldDto,
    row: Map<String, JsonElement>,
    refs: DwRefLabels,
    /** The template's `showEmptyNote`. Defaulted false so a caller with no template substitutes nothing. */
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

/**
 * Column widths that sum to EXACTLY 100.
 *
 * [TableBlock] throws when they do not, on construction AND on deserialization, and it is right to:
 * a renderer that trusts 118% draws a table wider than the page, which Word silently rescales and the
 * PDF layout silently clips — two different wrong answers from one bad set of numbers. The registry's
 * `columnWidthPct` is a HINT (0 means "share the remainder"), so the hints are honoured, the
 * remainder is split evenly, and the last column absorbs the rounding error rather than letting a
 * 33.33 × 3 = 99.99 fail the export a designer has been waiting on.
 *
 * ── THIS IS NOT THE SERVER'S ALGORITHM, AND THAT IS AN OPEN DIVERGENCE ────────────────────────────
 *
 * `report_builder._render_table` does something else: when the six drawn columns' declared widths do
 * not sum to 100 it DISCARDS every declared width and re-weights, 2.0 for a free-text column and 1.0
 * for everything else. The two agree only where the declared sum is already 100, and for five
 * entities it is not — prototype is 25/25/12.5/12.5/12.5/12.5 at the office and 10.7/21.4/14.3/17.9/
 * 25/10.7 on the handset; finalProduct, sketch, prototypeValidation and followUp diverge the same
 * way. Same workshop, same registry, same six columns, two documents that paginate differently and
 * wrap different cells.
 *
 * IT IS LEFT ALONE ON PURPOSE. Converging costs one surface its already-printed layout whichever way
 * it goes — porting this to the server, porting the free-text fallback here, or rebalancing the five
 * entities to 100 — and `tests/test_reference_carry.py`'s `_WIDTHS_THAT_DO_NOT_GOVERN` records that
 * those five are knowingly on the server's fallback and pins them there. That is the owner of the
 * printed page's decision, not a renderer's. What both surfaces DO agree on is the six-column cap
 * ([tableColumns]'s callers `.take(6)`, `_table_columns`' `columns[:6]`), which is this divergence's
 * twin and was closed after stage 22 printed as seven columns on the phone and six at the office.
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
            // THE BARE LABEL, AND THE UNIT IS IN THE CELL. This used to be
            // `field.label + " (${field.unit})"`, which was a reasonable compensation while
            // [displayValue] dropped `field.unit` altogether — but the server's header is
            // `TableColumn(header=spec.label, …)` and its CELLS carry the unit, so the compensation
            // was itself a second divergence: one workshop's two .docx files headed the same column
            // "Cost (INR)" and "Cost". Nineteen of the twenty-two unit-bearing TABLE_COLUMNs are
            // MONEY with unit "INR", which `format_value` never suffixes on either surface, so those
            // headers simply stop shouting a currency the "₹" in every cell below already states.
            //
            // MOVED IN THE SAME EDIT THAT PUT THE UNIT IN THE CELL AND NEVER BEFORE IT: taken out
            // first, a measurement table would have lost "hours" and "cm" from both places at once.
            header = field.label,
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
 *
 * ── THE FOUR ARMS THIS FUNCTION USED TO BE MISSING ────────────────────────────────────────────────
 *
 * The paragraph above says this IS the port of `format_value`, and for a long time it was not: there
 * was no DATE arm, no INT/DECIMAL arm and no read of [FieldDto.unit] anywhere in it, and MONEY was
 * `"₹" + DwValues.text(value)` — the stored string, verbatim, with no grouping and no decimals. So
 * one workshop's two .docx files disagreed in every table and grid carrying a date, a price or a
 * measurement. The office printed "Documented on: 10 Feb 2026", "Age: 45 years", "Length: 12.5 cm",
 * "Cost: ₹ 1,20,000.00"; the handset printed "2026-02-10", "45", "12.5", "₹120000". Counted off the
 * shipped registry that is 25 DATE, 34 MONEY and 36 unit-bearing INT/DECIMAL fields, plus every
 * MONEY, DATE and measurement question a designer adds themselves — `report_custom_sections`'
 * `display_value` hands everything but its own ENUM lists to this same `format_value`, and
 * [com.designprototype.workshop.data.customFieldToFieldDto] already carries `unit` into the
 * [FieldDto], so the two halves close together.
 *
 * THE UNIT LOSS WAS THE DANGEROUS HALF. A bare "12.5" in a dimensions row is unreadable as
 * centimetres and "45" under a column headed Age is ambiguous where the office copy says years. The
 * table header used to carry `label + " (unit)"` as a partial compensation; that suffix is gone in
 * the same edit that put the unit in the cell (see [tableColumns]), because the server's header is a
 * bare `spec.label` and leaving both would have made the two documents' HEADERS differ instead. Only
 * 22 of the 70 unit-bearing fields are TABLE_COLUMNs in any case, so the header trick never reached
 * the other 48.
 *
 * ── AND THE MEDIA SPLIT, WHICH IS THE FIFTH ─────────────────────────────────────────────────────
 *
 * There was no media arm at all, and [renderEntity]'s `printable` compensated by dropping every
 * media-typed field before it got here — under a comment claiming `_printable` is "only ever asked
 * for non-media roles on the server", which was never true: `_printable` applies no type filter and
 * leans on `format_value` to answer "" for a picture. Once the server split its own media branch,
 * that compensation became a divergence: the office's copy prints "Sanction order: 1 document
 * attached" and the handset's printed nothing, for the seventeen FILE/AUDIO/VIDEO fields the
 * registry declares. See the two arms below.
 *
 * NOTHING HERE RE-READS A LIVE ROW. Formatting is applied to the value already stored on the entry —
 * hydration's copy included — so a record edited after submission still cannot move a printed
 * document.
 *
 * A METRIC field's unit is passed SEPARATELY to [MetricRowBlock] as well, and that double is the
 * server's: `_render_stage` builds `(s.label, v, s.unit)` where `v` already went through
 * `format_value`. It is invisible today because no METRIC field in the registry declares a unit;
 * reproduced rather than tidied away, because the point of this file is that the two copies agree.
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

        // A PICTURE NEVER PRINTS AS TEXT; it is placed by [imagesOf]. `format_value`'s own arm, and
        // the reason the `else` below must never be allowed to catch a media type: [DwValues.text]
        // of a media value is a media id, and a cuid in a ministry's grid is the defect [OPAQUE_ID]
        // exists to keep off paper.
        DwFieldType.IMAGE, DwFieldType.IMAGE_LIST -> ""

        /*
          A COUNT AND A NOUN FOR THE THREE MEDIA TYPES THAT HAVE NO PICTURE PATH.

          FILE, AUDIO and VIDEO are not gathered by [imagesOf] — it filters on IMAGE and IMAGE_LIST —
          so before the server grew this arm they printed on NO surface at all: a designer attached
          the ministry's sanction order at stage 1 and the .docx the officer received did not mention
          that a sanction order existed. The registry declares eight FILE, five AUDIO and four VIDEO
          fields, and the tier warning made it worse rather than better, naming a field that no
          template could carry.

          THE NOUN AND THE STRINGS ARE `format_value`'s, EXACTLY. "1 document attached" /
          "2 documents attached", "recording(s)" for AUDIO, "video(s)" for VIDEO, and everything else
          in the media family is a document. A different word here is a different sentence in one of
          the two copies of one workshop's report.

          NOT A FILENAME, for the same reason the server gives: the stored value is a media id and
          the name the designer uploaded lives on the `MediaFile` row, which neither this renderer nor
          `report_builder` may query — this one has no network at all when it matters. The honest
          minimum is that an attachment exists and how many.
        */
        DwFieldType.FILE, DwFieldType.AUDIO, DwFieldType.VIDEO -> {
            val count = DwValues.list(value).size
            if (count == 0) {
                ""
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
        // ₹ 1,20,000.00 — two decimals, Indian grouping, a space after the sign. A cost sheet is
        // read by an officer who writes lakhs, and a Western-grouped figure is misread at a glance;
        // for a number that becomes a sanctioned amount that is a real error, not a stylistic one.
        //
        // A VALUE THAT IS NOT A NUMBER FALLS THROUGH WITHOUT THE RUPEE SIGN, which is the server's
        // arm and not a shortcut. MONEY is stored as a string, so a stage saved before `coerce_value`
        // rejected non-finite input holds the literal "nan" — and "₹ nan." on a document submitted to
        // a ministry dresses an unreadable cell up as an amount. The charts have always dropped those
        // rows ([asFiniteNumber] is what they use), so printing the stored text as the unreadable
        // thing it is makes the table and the figure beside it agree.
        DwFieldType.MONEY -> {
            val amount = asFiniteNumber(value)
            if (amount == null) {
                DwValues.text(value)
            } else {
                // The sign comes off the FORMATTED string in Python (`whole.startswith("-")`), so a
                // value that rounds to zero from below still prints "-₹ 0.00". [BigDecimal] has no
                // negative zero to carry that, hence the explicit test.
                val negative = amount < 0.0 || (amount == 0.0 && 1.0 / amount < 0.0)
                val fixed = pyFixed(abs(amount), 2)
                (if (negative) "-" else "") + "₹ " +
                    groupIndian(fixed.substringBefore('.')) + "." + fixed.substringAfter('.')
            }
        }

        DwFieldType.PERCENT -> asFiniteNumber(value)?.let { pyGeneral(it) + "%" } ?: DwValues.text(value)

        // `2026-02-10` -> `10 Feb 2026`, through the port that already exists. NOT a second date
        // formatter: [formatReportDate] reproduces `_format_date` by value INCLUDING the negative
        // index that makes month 0 print "Dec", and that quirk is pinned by a case table — a
        // bounds-checked rewrite here would put a different month on the phone's cover.
        DwFieldType.DATE -> formatReportDate(DwValues.text(value))

        DwFieldType.INT, DwFieldType.DECIMAL -> {
            val number = asFiniteNumber(value)
            if (number == null) {
                DwValues.text(value)
            } else {
                // GROUPED ONLY FROM FIVE FIGURES UP, which is the server's threshold and not a
                // rounded-off version of it: 9,999 pieces reads as a count and 1,00,000 reads as a
                // quantity, and grouping the small ones would have made every four-digit answer in
                // the report look like money.
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

        // The trailing `return f"{text} {spec.unit}".strip() if spec.unit and text else text`. No
        // field in the shipped registry reaches it — all 70 unit-bearing ones are numeric — but a
        // designer's own TEXT question may declare a unit, and this is the arm it lands in.
        else -> {
            val text = DwValues.text(value)
            if (field.unit.isNotBlank() && text.isNotEmpty()) "$text ${field.unit}".trim() else text
        }
    }
}

/**
 * A double as Python's `f"{value:.<places>f}"` renders it — half-to-EVEN on the exact binary value.
 *
 * NOT `String.format("%.2f", value)`. Java's `Formatter` rounds the shortest decimal representation
 * half AWAY from zero and Python rounds the exact binary value half to EVEN, so a cost of 1500.5
 * prints as "1,501" on the phone and "1,500" on the server — one figure, two numbers, in two
 * documents for the same workshop. Constructing the [BigDecimal] from the DOUBLE rather than from
 * its string form is equally deliberate: it takes the exact binary value, which is the value
 * Python's formatter is also looking at, so the two agree on which side of a tie a
 * representable-looking number such as 2.675 actually falls.
 *
 * ONE KNOWN DIVERGENCE, STATED RATHER THAN HIDDEN: [BigDecimal] has no negative zero, so a stored
 * INT of `-0` prints "0" here and "-0" on the server. Every other numeric path in this file routes
 * the sign around this function for exactly that reason (see the MONEY arm); the INT arm does not,
 * because "-0" is not a number a designer types into a count and inventing a sign test for it would
 * cost more than the character it saves.
 *
 * A THIRD COPY OF THE SAME TWO LINES, and knowingly. `ReportChart.fixed` and `ReportFigures.fixedZero`
 * are the other two, each private to the file that draws with it. Sharing one would mean either a
 * public helper in the report package that every caller must remember to reach for, or an import
 * from `ui.designworkshop` into `report` that inverts this port's dependency direction. The rule is
 * the comment on all three, not the function.
 */
private fun pyFixed(value: Double, places: Int): String =
    BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).toPlainString()

/**
 * A double as Python's `f"{value:g}"` renders it — the format `format_value` gives every DECIMAL.
 *
 * `:g` is not "drop the trailing zeros". It rounds to six SIGNIFICANT digits, then prints fixed
 * notation while the decimal exponent is in `-4 <= x < 6` and exponential notation outside it, and
 * strips trailing zeros from whichever it chose. `12.50` is "12.5", `1234567.0` is "1.23457e+06",
 * and `0.00001` is "1e-05" — a naive `trimEnd('0')` port agrees on the first and disagrees on the
 * other two.
 *
 * IN PRACTICE THE EXPONENTIAL BRANCH IS ALMOST UNREACHABLE HERE, because `format_value` re-formats
 * anything of magnitude 10,000 or more through the Indian grouping and throws this result away. It
 * is written out in full anyway: the reachable half is the tiny values (a decimal below 0.0001 — a
 * per-unit material rate in a costing sheet is exactly that shape), and a port that got the
 * unreachable half wrong would be a trap for whoever changes the grouping threshold.
 */
private fun pyGeneral(value: Double): String {
    // Python prints "0" for 0.0 and "-0" for -0.0; BigDecimal has no negative zero to represent the
    // second, so it is decided here before the rounding.
    if (value == 0.0) return if (1.0 / value < 0.0) "-0" else "0"
    val rounded = BigDecimal(value).round(MathContext(6, RoundingMode.HALF_EVEN))
    // The decimal exponent of the ROUNDED value — BigDecimal's own "adjusted exponent", which it
    // does not expose. Rounding first is what makes 999999.5 exponential rather than fixed, exactly
    // as Python decides it.
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
    // Python's exponent is always signed and at least two digits: "1e+20", "1.5e-07".
    val sign = if (exponent < 0) "-" else "+"
    return mantissa.toString() + "e" + sign + abs(exponent).toString().padStart(2, '0')
}
