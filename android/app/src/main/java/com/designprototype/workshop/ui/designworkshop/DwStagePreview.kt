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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

/** How long one read of the server's copy of the workshop may take before it counts as failed. */
internal const val DW_PREVIEW_SERVER_READ_MS = 8_000L

/**
 * What became of the read of the server's copy for one build of the preview. Kept apart from the
 * draft because "the server answered with nothing" and "the server was not reached" look the same
 * once they are both a missing remote — and only the first is an empty workshop.
 */
internal sealed interface DwServerRead {
    /** The workshop has never been uploaded, so there is no server copy to read. */
    data object NotNeeded : DwServerRead

    data class Read(val copy: StageListDto) : DwServerRead

    /** Not attempted: this device is offline and holds no earlier read. */
    data object Offline : DwServerRead

    /** Attempted, and it failed or ran past [DW_PREVIEW_SERVER_READ_MS]. */
    data object Failed : DwServerRead
}

/** The preview's input: the draft to lay out, and whether the server's side of it is in it. */
internal data class DwStagePreviewSource(val draft: WorkshopDraft?, val serverRead: DwServerRead)

/**
 * The draft the stage preview is built from — [reportSourceFor]'s merge of this device's draft with
 * the server's copy, exactly as the report screen merges them (a stage this device holds work for is
 * the device's; every other stage is the server's), with this stage's unsaved answers, from [live],
 * laid over it.
 *
 * ALWAYS THE MERGE, NEVER "LOCAL WHEN IT EXISTS". The first debounced save writes a draft holding
 * only this stage; preferring it over the server's copy made the cover and every other stage vanish
 * from the preview a moment after the first keystroke.
 *
 * Null only when there is genuinely nothing: no draft here, nothing from the server, and nothing
 * typed on this stage yet.
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
    val base = reportSourceFor(schema, workshopId, local = local, remoteId = remoteId, remote = remote).draft
    val stage = live(base?.stages?.get(stageKey))
    if (base == null && !stage.holdsWork()) return null
    val draft = base ?: WorkshopDraft(workshopId = workshopId, remoteId = remoteId)
    return draft.copy(stages = draft.stages + (stageKey to stage))
}

/**
 * The server's copy of the workshop, read at most once per stage screen while it succeeds — the
 * preview rebuilds on every pause in typing, and each rebuild must not be another round trip. A read
 * that fails or times out is not remembered, so the next rebuild (or Try again) asks again.
 *
 * THE READ RUNS IN [scope], NOT IN THE CALLER. Each keystroke restarts the preview's debounce and so
 * cancels the build that is waiting here; were the fetch the caller's, continuous typing would cancel
 * every read before it finished and the server's stages would never arrive. The next build picks up
 * the same read where it is.
 */
internal class DwPreviewServerCopy(private val scope: CoroutineScope) {
    private val lock = Mutex()
    private var copy: StageListDto? = null
    private var inFlight: Deferred<StageListDto?>? = null

    suspend fun read(online: Boolean, fetch: suspend () -> StageListDto): DwServerRead {
        val pending = lock.withLock {
            copy?.let { return DwServerRead.Read(it) }
            inFlight ?: run {
                if (!online) return DwServerRead.Offline
                scope.async {
                    withTimeoutOrNull(DW_PREVIEW_SERVER_READ_MS) {
                        try {
                            fetch()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.also { inFlight = it }
            }
        }
        val result = try {
            pending.await()
        } catch (cancelled: CancellationException) {
            // The read itself was cancelled (the screen is going) — or this caller was.
            if (currentCoroutineContext().isActive) null else throw cancelled
        }
        lock.withLock {
            if (inFlight === pending) {
                inFlight = null
                if (result != null) copy = result
            }
        }
        return result?.let { DwServerRead.Read(it) } ?: DwServerRead.Failed
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
     * The draft as it would be if this stage were saved now — see [dwStagePreviewDraft] — and whether
     * the server's copy is in it. A null draft with the server read is genuinely nothing to show.
     */
    liveDraft: suspend () -> DwStagePreviewSource,
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
    // How the last build's server read went; Offline or Failed means the server's stages are missing.
    var serverRead by remember(workshopId) { mutableStateOf<DwServerRead?>(null) }
    var retry by remember(workshopId) { mutableStateOf(0) }

    LaunchedEffect(open, revision, schema, retry) {
        if (!open || schema == null) return@LaunchedEffect
        // The first build after opening is immediate; every later one waits for typing to pause.
        if (document != null || empty) delay(DW_LIVE_PREVIEW_DEBOUNCE_MS)
        // `Result` rather than a nullable, so "nothing to show" (null draft) and "took too long"
        // (null from the timeout) cannot be mistaken for each other.
        val outcome: Result<Pair<DwServerRead, ReportDocument?>>? = withTimeoutOrNull(DW_LIVE_PREVIEW_TIMEOUT_MS) {
            try {
                val source = liveDraft()
                Result.success(
                    source.serverRead to source.draft?.let {
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
        val read = outcome?.getOrNull()?.first
        val built = outcome?.getOrNull()?.second
        when {
            built != null -> {
                document = built
                serverRead = read
                empty = false
                failed = false
            }
            read != null -> {
                document = null
                serverRead = read
                // Empty only when the server was asked and answered (or there is none to ask).
                empty = read !is DwServerRead.Offline && read !is DwServerRead.Failed
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
                "Live preview of this stage's pages.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            if (failed && document != null) {
                Text(
                    "Latest change couldn't render yet; showing the previous one.",
                    color = MaterialTheme.field.warning,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            if (document != null && (serverRead is DwServerRead.Offline || serverRead is DwServerRead.Failed)) {
                Text(
                    if (serverRead is DwServerRead.Offline) {
                        "Offline — showing only what this device holds."
                    } else {
                        "Showing only what this device holds; the server couldn't be reached."
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            val current = document
            if (current == null) {
                val tryAgain: () -> Unit = { failed = false; serverRead = null; retry++ }
                when {
                    failed -> LoadFailedCard(
                        message = "The preview couldn't be built.",
                        onRetry = tryAgain,
                    )
                    serverRead is DwServerRead.Offline -> LoadFailedCard(
                        message = "You're offline, and this workshop isn't saved on this device yet.",
                        onRetry = tryAgain,
                    )
                    serverRead is DwServerRead.Failed -> LoadFailedCard(
                        message = "This workshop couldn't be read from the server.",
                        onRetry = tryAgain,
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
                        "This stage has no heading yet, so the whole document is shown.",
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
