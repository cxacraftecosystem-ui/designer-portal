package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
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
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt. Every file
// in this feature imports it, or its headings are quietly set in the body face.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import retrofit2.HttpException

/**
 * **REPORT HISTORY, GENERATION NUMBERING AND THE DIFF BETWEEN TWO FILES, ON THE HANDSET.**
 *
 * The phone's counterpart of `frontend/app/(protected)/design-workshops/[id]/report/history/page.tsx`.
 *
 * ── WHY THIS SCREEN EXISTS ───────────────────────────────────────────────────────────────────────
 *
 * A report submitted to a ministry comes back for revision three or four times. Every one of those
 * files was already recorded — `DwReportExport` has carried the checksum, the size, the page count,
 * the template, the registry version and the timestamp of each, INCLUDING the ones this app produced
 * with no network — and on this surface none of it was on screen anywhere. So a designer standing in
 * an office being asked "did you update the cost sheet before you resubmitted?" had, on the device
 * the fieldwork was captured on, no answer: four files existed, and nothing said what was different
 * about them. The endpoint has always been client-agnostic and the arithmetic is pure and shared;
 * what was missing here was only the client.
 *
 * ── A SIBLING OF THE REPORT SCREEN, NOT PART OF IT ───────────────────────────────────────────────
 *
 * [ReportScreen] is about producing the NEXT file: template, colour, transcripts, preview, generate.
 * This is about the files already produced. They are read at different moments by people asking
 * different questions, and merging them would bury the record of four submissions under the controls
 * for making a fifth — on a screen that is already the longest in the app.
 *
 * ── WHAT THE DIFF CAN AND CANNOT SAY IS THE SUBSTANCE OF THE FEATURE ─────────────────────────────
 *
 * And it is stated ON SCREEN rather than left for a reader to infer. No snapshot of the stage data is
 * kept at export time, so `data/DwReportHistory.kt` works from the only evidence there is — every
 * stage row's createdAt / updatedAt / deletedAt. That supports a genuinely strong claim in one
 * direction ("no row of the costing stage was written to between these two files, so both carry
 * identical data — provably, not probably") and refuses the other ("stage 15 was written to", and NOT
 * which field, because nothing stored can say). Read that file's header before changing anything
 * here; the temptation this screen has to resist is inventing a field-level diff, which would be a
 * confident answer to the exact question a ministry reviewer is asking.
 *
 * THE VERB IS "WRITTEN", NEVER "CHANGED", and that is not fussiness. A stage is saved whole:
 * `save_stage` updates every row the payload names without comparing it to what is stored, so
 * correcting one word stamps the entire stage. Only the negative direction — nothing was written —
 * supports the word "identical". Every sentence on this screen is a constant or a pure function in
 * `data/DwReportHistory.kt`, pinned by `DwReportHistoryTest`, for that reason and for the reason
 * `DwProvenanceScreen` gives: one product must not say two things.
 *
 * ── IT NEEDS A CONNECTION, AND SAYS SO INSTEAD OF FAILING AT THE TAP ─────────────────────────────
 *
 * This is the rare design-workshop screen with no offline form, and the reason is not laziness: the
 * export table records files made on OTHER devices by OTHER people, so unlike a stage form it cannot
 * be served from this phone's draft, and a cached copy would show a history with a colleague's
 * revisions missing from it. What IS offline-shaped about it is the COMPARING — the history arrives
 * in one request and every pair a designer flips between afterwards is arithmetic here, with no
 * further round trip, which is what makes it usable on a metered rural connection.
 *
 * The two "no" states are told apart and never merged. A workshop that has never reached the
 * repository has no log at all and is told what that means for the files it can still generate
 * ([DW_REPORT_HISTORY_LOCAL_ONLY]); a phone with no signal is told the history is unread and the
 * fieldwork is safe. Rule 10 of this repo, and the reason `dwDesignerPickerStandDown` exists: a
 * screen that quietly shows nothing is indistinguishable from a workshop nobody ever exported.
 *
 * ── NOTHING ON THIS SCREEN WRITES ────────────────────────────────────────────────────────────────
 *
 * There is no control that edits or removes an export row — the checksum is what makes the record
 * evidence, and evidence that can be tidied up is not evidence. The only control is "Try again".
 */
@Composable
fun DwReportHistoryScreen(
    repository: WorkshopRepository,
    /**
     * The DRAFT STORE's id, like every other screen in this feature — which for a workshop started in
     * a courtyard is a local id no server has ever seen. The server id is resolved here from the
     * draft's `remoteId`, exactly as [DwProvenanceScreen] and [WorkshopViewersScreen] do it.
     */
    workshopId: String,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    var loading by remember(workshopId) { mutableStateOf(true) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var history by remember(workshopId) { mutableStateOf<DwReportHistoryDto?>(null) }
    /**
     * The registry, held only so stage KEYS can be shown as the words a person recognises, and so the
     * two lists can be ordered by stage number rather than by whichever row the server happened to
     * return first.
     *
     * Null is an ordinary state and never an error, exactly as on [DwProvenanceScreen]: the raw key
     * is readable, and a registry that would not load must not cost the designer the comparison.
     */
    var registry by remember(workshopId) { mutableStateOf<SchemaResponse?>(null) }
    /**
     * Template NAMES, and nothing else. Fetched separately and allowed to fail on its own — the id is
     * a legible fallback, and `designWorkshopTemplates` answers from the cached registry offline in
     * any case. It is deliberately not folded into the history payload, for the same reason stage
     * titles are not: every client already caches `/templates`, and a second copy of a name on that
     * wire is a second thing to drift.
     */
    var templates by remember(workshopId) { mutableStateOf<List<ReportTemplateDto>>(emptyList()) }
    var loadError by remember(workshopId) { mutableStateOf<String?>(null) }
    /** This workshop exists on the SERVER — a different question from whether it could be reached. */
    var onServer by remember(workshopId) { mutableStateOf(true) }
    var leftId by remember(workshopId) { mutableStateOf("") }
    var rightId by remember(workshopId) { mutableStateOf("") }

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

        // The registry and the templates first and separately, so neither failure can take the
        // history with it. Neither call throws on a network failure — both answer from the device —
        // so this is belt and braces against a build packaged without the bundled asset.
        runCatching { repository.designWorkshopSchema(appContext) }.onSuccess { registry = it }
        runCatching { repository.designWorkshopTemplates(appContext) }.onSuccess { templates = it }

        runCatching { repository.designWorkshopReportHistory(remoteId) }
            .onSuccess { loaded ->
                history = loaded
                loadError = null
                // The newest two, OLDEST ON THE LEFT — the comparison a designer opening this screen
                // is almost always making. With one file there is nothing to compare and the panel
                // says so rather than leaving two pickers set to the same row.
                val ordered = dwInGenerationOrder(loaded)
                if (ordered.size >= 2) {
                    leftId = ordered[ordered.size - 2].id
                    rightId = ordered[ordered.size - 1].id
                }
            }
            .onFailure { error ->
                history = null
                // ONE READ OF THE BODY. `apiErrorMessage` consumes the error body, so it is called
                // exactly once here and the three facts are handed to the pure decision — see
                // `dwReportHistoryFailure`. `isConnectionFailure` is this app's ONE definition of
                // "the network", not a fourth private copy of it.
                loadError = dwReportHistoryFailure(
                    unreachable = repository.isConnectionFailure(error),
                    status = (error as? HttpException)?.code(),
                    // A bare status line ("HTTP 500 Internal Server Error") is not a sentence and is
                    // filtered out here rather than shown to a designer — the same filter
                    // `DwProvenanceScreen` and `WorkshopViewersScreen` apply.
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
            fontSize = 22.sp
        )
        Text(DW_REPORT_HISTORY_SUBTITLE, color = MaterialTheme.field.muted, fontSize = 13.sp)

        if (!onServer) {
            Text(DW_REPORT_HISTORY_LOCAL_ONLY, color = MaterialTheme.field.muted, fontSize = 13.sp)
            return@Column
        }

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("Reading the history…", color = MaterialTheme.field.muted, fontSize = 13.sp)
            }
            return@Column
        }

        val served = history
        if (served == null) {
            // The failure is rendered IN PLACE, beside the control that can retry it, rather than
            // handed to a snackbar that slides away: this screen has exactly one request and its
            // failure is the whole state, which is why it takes no `onError` from the router.
            Text(
                loadError ?: "The report history could not be read.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
            OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
            return@Column
        }

        val ordered = remember(served) { dwInGenerationOrder(served) }
        val absolute = remember(served) { dwGenerationsAreAbsolute(served) }

        if (served.exports.isEmpty()) {
            // A FIRST-CLASS RENDERING and not an empty list. Nothing generated yet is the ordinary
            // state of a workshop in its first fortnight, and a blank screen under a heading reads as
            // a load that failed.
            Text(
                DW_REPORT_HISTORY_EMPTY_TITLE,
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp
            )
            Text(DW_REPORT_HISTORY_EMPTY_BODY, color = MaterialTheme.field.muted, fontSize = 13.sp)
            return@Column
        }

        val stageNumber: (String) -> Int = { key ->
            registry?.stages?.firstOrNull { it.key == key }?.number ?: 999
        }
        val stageTitle: (String) -> String = { key ->
            // The registry is the single declaration of what a stage is called, so an unknown key
            // means this phone's registry is older than the row — say the raw key rather than invent
            // a title.
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

        // ── Every file generated, newest first ───────────────────────────────────────────────────
        Text(
            DW_REPORT_HISTORY_LIST_TITLE,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp
        )
        Text(
            buildString {
                append("Newest first. ${served.exports.size} file")
                append(if (served.exports.size == 1) "" else "s")
                append(" recorded.")
                if (served.exportsTruncated) append(" Only the most recent 100 are shown.")
            },
            color = MaterialTheme.field.muted,
            fontSize = 13.sp
        )
        served.exports.forEach { record ->
            DwExportCard(
                record = record,
                generation = dwGenerationOf(served, record.id),
                absolute = absolute,
                windowTruncated = served.exportsTruncated,
                templateName = templateName(record.templateId),
                duplicates = dwSameFileAs(served, record.id).map { dwGenerationOf(served, it.id) },
                // Only for the NEWEST file. "Written to since this was generated" is interesting
                // about the copy somebody is holding right now; computed for every row it would say
                // the same thing about every older file and mean nothing.
                staleStages = if (ordered.isNotEmpty() && record.id == ordered.last().id) {
                    staleAfterNewest.size
                } else {
                    0
                },
            )
        }
    }
}

// --------------------------------------------------------------------------------------
// The comparison
// --------------------------------------------------------------------------------------

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
                fontSize = 16.sp
            )

            // A file with no recorded generation time is a file no comparison can include, and the
            // two cases are told apart: "there is only one" and "none of them can be placed in time"
            // have different remedies and merging them would send a designer looking for a second
            // file that is already listed above.
            if (ordered.isEmpty()) {
                Text(DW_REPORT_HISTORY_NONE_DATED, color = MaterialTheme.field.muted, fontSize = 13.sp)
                return@Column
            }
            if (ordered.size < 2) {
                Text(DW_REPORT_HISTORY_ONE_FILE, color = MaterialTheme.field.muted, fontSize = 13.sp)
                return@Column
            }

            // NEWEST FIRST in the picker, because the file a designer is asking about is almost
            // always one of the last two. `SearchableSelectField` switches itself to a searchable
            // sheet once the list is long enough, which is the right behaviour for the one list in
            // this app whose length is nobody's design decision: it grows by one row per export,
            // forever, and finding "the file from the 14th" among thirty is what typing answers and
            // scrolling does not.
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
                Text("Choose two different files.", color = MaterialTheme.field.muted, fontSize = 13.sp)
                return@Column
            }
            if (diff == null) return@Column

            // BY STAGE NUMBER, not by whichever row the server returned first: a designer reads
            // these against the 22-stage order they filled in, and a list in `updatedAt` order is a
            // list nobody can check against anything.
            //
            // NOT wrapped in `remember`. It would have to be keyed on `stageNumber` as well as on
            // the diff — the registry arrives asynchronously and a lookup remembered before it
            // landed would keep sorting by the 999 fallback — and a lambda parameter is a fresh
            // instance every recomposition, so such a key recomputes every time in any case. Two
            // sorts of at most 22 rows is not worth a subtle staleness.
            val touched = diff.touchedStageKeys.mapNotNull { diff.byStage[it] }
                .sortedBy { stageNumber(it.stageKey) }
            val untouched = diff.untouchedStageKeys.mapNotNull { diff.byStage[it] }
                .sortedBy { stageNumber(it.stageKey) }

            Text(
                dwDiffHeadline(diff, absolute = absolute, windowTruncated = history.exportsTruncated),
                color = MaterialTheme.field.body,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )

            dwFileFacts(diff, templateName).forEach { fact ->
                Text("• $fact", color = MaterialTheme.field.body, fontSize = 13.sp)
            }

            dwHeaderVerdict(diff)?.let { verdict ->
                Text(
                    verdict,
                    // The PROOF is body ink; the "the row was touched" case is muted, because it is
                    // the weaker statement and reading it as "the cover changed" is the misreading
                    // this whole field exists to prevent.
                    color = if (diff.headerRowWritten == false) {
                        MaterialTheme.field.body
                    } else {
                        MaterialTheme.field.muted
                    },
                    fontSize = 13.sp
                )
            }

            touched.forEach { stage ->
                DwTouchedStageRow(stage = stage, title = stageTitle(stage.stageKey))
            }
            if (touched.isNotEmpty()) {
                Text(
                    DW_REPORT_DIFF_WRITTEN_NOT_CHANGED,
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
                )
            }

            if (untouched.isNotEmpty()) {
                Text(
                    if (diff.timelineComplete) {
                        "Identical in both files"
                    } else {
                        "Nothing written (the timeline was capped — see below)"
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                untouched.forEach { stage ->
                    Column {
                        Text(stageTitle(stage.stageKey), color = MaterialTheme.field.body, fontSize = 13.sp)
                        dwStageCompletenessNote(stage, history.completeness[stage.stageKey])?.let { note ->
                            Text(note, color = MaterialTheme.field.muted, fontSize = 11.sp)
                        }
                    }
                }
            }

            // ── What this comparison cannot tell you ─────────────────────────────────────────────
            //
            // ON SCREEN AND NOT IN A COMMENT. The reader of this panel is about to answer a
            // ministry's question from it, and a limit nobody is told about is indistinguishable
            // from a fact. It is deliberately not behind a disclosure control for the same reason.
            HorizontalDivider(color = MaterialTheme.field.hairline)
            Text(
                DW_REPORT_DIFF_LIMITS_TITLE,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            dwDiffLimits(diff, history).forEach { limit ->
                Text("• $limit", color = MaterialTheme.field.muted, fontSize = 11.sp)
            }
        }
    }
}

/** One stage that was written to, with its counts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwTouchedStageRow(stage: DwStageChange, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (stage.rowsAdded > 0) DwHistoryChip("${stage.rowsAdded} added", tone = DwChipTone.Good)
            // "Rewritten" and never "changed" — see `DW_REPORT_DIFF_WRITTEN_NOT_CHANGED`, which is
            // printed under this list precisely so the chip cannot be read as the stronger claim.
            if (stage.rowsRewritten > 0) DwHistoryChip("${stage.rowsRewritten} rewritten", tone = DwChipTone.Warn)
            if (stage.rowsRemoved > 0) DwHistoryChip("${stage.rowsRemoved} removed", tone = DwChipTone.Warn)
        }
        if (stage.rowsTransient > 0) {
            // NOT a difference between the two documents, and not nothing either: a designer who
            // remembers doing the work deserves to see it acknowledged rather than be told the stage
            // was untouched.
            Text(
                "${stage.rowsTransient} row${if (stage.rowsTransient == 1) "" else "s"} added and " +
                    "removed again between the two files, so neither document contains " +
                    (if (stage.rowsTransient == 1) "it" else "them"),
                color = MaterialTheme.field.muted,
                fontSize = 11.sp
            )
        }
    }
}

// --------------------------------------------------------------------------------------
// One recorded export
// --------------------------------------------------------------------------------------

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
                fontSize = 14.sp
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DwHistoryChip(record.format, tone = DwChipTone.Neutral)
                // Where the file came from is a fact about the archive, not trivia: a file a phone
                // made offline exists on exactly one device until somebody copies it off.
                DwHistoryChip(
                    if (record.generatedOnDevice) "Made on a phone, offline" else "Made by the repository",
                    tone = if (record.generatedOnDevice) DwChipTone.Warn else DwChipTone.Neutral,
                )
            }
            Text(dwExportMoment(record.generatedAt), color = MaterialTheme.field.muted, fontSize = 12.sp)

            DwFact("Template", templateName)
            DwFact("Pages", record.pageCount?.toString() ?: "Not recorded")
            DwFact("Size", dwExportSize(record.fileSizeBytes))
            // NEVER substituted with the workshop's owner: `generatedBy` is SetNull, so a deleted
            // account means nobody is named rather than the wrong person being named against a file
            // they never produced.
            DwFact("Generated by", record.generatedByName ?: "Account no longer exists")

            Text("SHA-256 of the file", color = MaterialTheme.field.muted, fontSize = 11.sp)
            DwChecksum(value = record.checksumSha256, onDevice = record.generatedOnDevice)

            Text(
                "File name: ${record.fileName}",
                color = MaterialTheme.field.placeholder,
                fontSize = 11.sp
            )

            dwDuplicateFileNote(duplicates)?.let { note -> DwWarnNote(note) }
            dwStaleSinceNote(staleStages)?.let { note -> DwWarnNote(note) }

            record.warnings?.takeIf { it.isNotBlank() }?.let { warnings ->
                // The warnings never travel inside the document — an officer opening the .docx next
                // month must not find a note about what was missing on the day — so this record is
                // the only place they can still be read. Shown rather than collapsed behind a
                // disclosure: this list is what a desk uses to decide whether the file that was
                // handed over is the whole of the record, and a row that says nothing is a row
                // asserting there was nothing to say.
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Text(
                    "Warnings recorded when this file was made",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(warnings, color = MaterialTheme.field.muted, fontSize = 11.sp)
            }
        }
    }
}

/**
 * The checksum, IN FULL and selectable.
 *
 * SHOWN ENTIRE, not abbreviated to eight characters. It is the one field on this screen that PROVES
 * something — that the file in somebody's hand is this row and not the revision after it — and a
 * truncated hash cannot be compared against the output of `sha256sum` on a laptop in a ministry
 * office, which is the only way anybody ever checks one. [SelectionContainer] is what makes that
 * possible on a handset: long-press, copy, paste it into the covering email.
 */
@Composable
private fun DwChecksum(value: String?, onDevice: Boolean) {
    if (value.isNullOrBlank()) {
        Text(
            "No checksum recorded — this file cannot be matched to a copy by its contents." +
                if (onDevice) {
                    " A report this phone generates does send one, so a row without it was made by " +
                        "a build that predates that."
                } else {
                    ""
                },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )
        return
    }
    SelectionContainer {
        Text(
            value,
            color = MaterialTheme.field.body,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun DwFact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.field.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = MaterialTheme.field.body, fontSize = 12.sp, modifier = Modifier.weight(2f))
    }
}

@Composable
private fun DwWarnNote(text: String) {
    Text(
        text,
        color = MaterialTheme.field.onWarningContainer,
        fontSize = 11.sp,
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
        fontSize = 11.sp,
        modifier = Modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
