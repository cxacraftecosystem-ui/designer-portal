package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageListDto
import com.designprototype.workshop.data.WorkshopDraft
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.report.resolveAccent
import com.designprototype.workshop.report.resolveTemplateId
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import com.designprototype.workshop.ui.LoadFailedCard
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * How long after the last edit the document is rebuilt — the web's `LIVE_DEBOUNCE_MS`, to the ms.
 * Long enough that a word typed at speed is one rebuild, short enough that the page answers before
 * the designer's eyes reach it.
 */
internal const val DW_LIVE_PREVIEW_DEBOUNCE_MS = 250L

/**
 * The most one build may take — reading the draft, fetching the server's copy and laying the document
 * out — before the panel stops saying "Building…" and offers Try again. A preview that spins for ever
 * is a failure dressed as progress.
 */
internal const val DW_LIVE_PREVIEW_TIMEOUT_MS = 20_000L

/** How long the server's copy is waited for when this device holds no draft of the workshop. */
internal const val DW_PREVIEW_SERVER_READ_MS = 8_000L

/**
 * The draft the stage preview is built from: this device's draft when it has one, otherwise the
 * server's copy of the workshop ([reportSourceFor]'s merge, with no local side) — and in both cases
 * this stage's unsaved answers, from [live], laid over it.
 *
 * NULL ONLY WHEN THERE IS GENUINELY NOTHING: no draft here, nothing on the server, and nothing typed
 * on this stage yet. That is the panel's empty state, not a reason to wait. It used to be the only
 * answer for a workshop with no draft on this device, and the panel said "Building the document…"
 * until the first keystroke wrote one.
 */
internal fun dwStagePreviewDraft(
    schema: SchemaResponse,
    workshopId: String,
    stageKey: String,
    local: WorkshopDraft?,
    remoteId: String?,
    remote: StageListDto?,
    live: (existing: StageDraft?) -> StageDraft,
): WorkshopDraft? {
    val base = local ?: reportSourceFor(schema, workshopId, local = null, remoteId = remoteId, remote = remote).draft
    val stage = live(base?.stages?.get(stageKey))
    if (base == null && !stage.holdsWork()) return null
    val draft = base ?: WorkshopDraft(workshopId = workshopId, remoteId = remoteId)
    return draft.copy(stages = draft.stages + (stageKey to stage))
}

/**
 * The server's copy of the workshop, read at most once per stage screen while it succeeds — the
 * preview rebuilds on every pause in typing, and each rebuild must not be another round trip. A read
 * that fails or times out is not remembered, so the next rebuild (or Try again) asks again.
 */
internal class DwPreviewServerCopy {
    private val lock = Mutex()
    private var copy: StageListDto? = null

    suspend fun get(fetch: suspend () -> StageListDto): StageListDto? = lock.withLock {
        copy ?: withTimeoutOrNull(DW_PREVIEW_SERVER_READ_MS) {
            try {
                fetch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }.also { copy = it }
    }
}

/**
 * THE STAGE'S PAGES, LIVE, BESIDE THE FORM — the handset's counterpart of the web's
 * `StageDocumentPreview`.
 *
 * COMPOSE-NATIVE, NOT A WEBVIEW, and the choice is about fidelity as much as about the network. The
 * handset already lays the report out on A4 sheets ([DwReportSheets]) with the same packing rules
 * and the same block renderer it uses on the report screen, from the same Kotlin builder that writes
 * its .docx and .pdf ([buildWorkshopDocument]) — so the pages here are the pages the phone's own file
 * will have. A WebView would need the web app's renderer bundled into the APK to work with no
 * signal, and would then be drawing a SECOND layout of a document this device already knows how to
 * lay out, with a different text engine and different line breaks.
 *
 * Built from [liveDraft] — the draft on disk with this stage's unsaved answers laid over it —
 * [DW_LIVE_PREVIEW_DEBOUNCE_MS] after the last edit, on `Dispatchers.Default`. The sheets keep the
 * previous document up until the new one is measured, so typing never flashes an empty panel.
 */
@Composable
internal fun DwStageDocumentPreview(
    workshopId: String,
    stageTitle: String,
    schema: SchemaResponse?,
    /** Bumped on every edit; a new value restarts the debounce. */
    revision: Int,
    /**
     * The draft as it would be if this stage were saved now — see [dwStagePreviewDraft]. Null means
     * there is genuinely nothing to show yet.
     */
    liveDraft: suspend () -> WorkshopDraft?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    var open by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var document by remember(workshopId) { mutableStateOf<ReportDocument?>(null) }
    var failed by remember(workshopId) { mutableStateOf(false) }
    // Nothing anywhere to build from — see [dwStagePreviewDraft]. Cleared by the next build that finds something.
    var empty by remember(workshopId) { mutableStateOf(false) }
    var retry by remember(workshopId) { mutableStateOf(0) }

    LaunchedEffect(open, revision, schema, retry) {
        if (!open || schema == null) return@LaunchedEffect
        // The first build after opening is immediate; every later one waits for typing to pause.
        if (document != null || empty) delay(DW_LIVE_PREVIEW_DEBOUNCE_MS)
        // `Result` rather than a nullable, so "nothing to show" (null draft) and "took too long"
        // (null from the timeout) cannot be mistaken for each other.
        val outcome: Result<ReportDocument?>? = withTimeoutOrNull(DW_LIVE_PREVIEW_TIMEOUT_MS) {
            try {
                val draft = liveDraft()
                Result.success(
                    draft?.let {
                        withContext(Dispatchers.Default) {
                            val settings = it.stages["REPORT_GENERATION"]?.values.orEmpty()
                            buildWorkshopDocument(
                                context = appContext,
                                schema = schema,
                                draft = it,
                                workshopId = workshopId,
                                templateId = resolveTemplateId(null, settings, it.templateId).ifEmpty { "DCH_STANDARD" },
                                warnings = emptyList(),
                                accent = resolveAccent(null, settings).orEmpty(),
                            )
                        }
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
        val built = outcome?.getOrNull()
        when {
            built != null -> {
                document = built
                empty = false
                failed = false
            }
            outcome != null && outcome.isSuccess -> {
                document = null
                empty = true
                failed = false
            }
            // Timed out or threw. The last good pages stay up with the line below saying the newest
            // edit is not in them; with no pages yet, the panel offers Try again instead of spinning.
            else -> failed = true
        }
    }

    val resolveImage: (String) -> File? = remember { { source: String -> File(source).takeIf { it.exists() } } }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Document preview", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Hide the document preview" else "Show the document preview",
            )
        }
        if (open) {
            Text(
                "Updates as you type — the pages this stage falls on, as this device writes the report.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            if (failed && document != null) {
                Text(
                    "The newest change couldn't be laid out just now; the pages below are from the change before it.",
                    color = MaterialTheme.field.warning,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            val current = document
            if (current == null) {
                when {
                    failed -> LoadFailedCard(
                        message = "The preview couldn't be built.",
                        onRetry = { failed = false; retry++ },
                    )
                    empty -> Text(
                        "Nothing to preview yet. Fill in a field and its page appears here.",
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    else -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Building the document…", color = MaterialTheme.field.muted, fontSize = 12.sp)
                    }
                }
            } else {
                val hasStage = remember(current, stageTitle) { stageBlockRange(current.blocks, stageTitle) != null }
                if (!hasStage) {
                    Text(
                        "This stage has no heading of its own in the document yet — usually because nothing in it has " +
                            "been filled in. The whole document is shown instead.",
                        color = MaterialTheme.field.warning,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                } else {
                    Text(
                        if (showAll) "Show only this stage's pages" else "Show every page",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showAll = !showAll }.padding(vertical = 4.dp),
                    )
                }
                DwReportSheets(
                    document = current,
                    resolveImage = resolveImage,
                    focusStageTitle = if (showAll || !hasStage) null else stageTitle,
                )
            }
        }
    }
}
