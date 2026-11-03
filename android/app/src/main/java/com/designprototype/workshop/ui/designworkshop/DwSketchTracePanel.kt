package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.AppToggle
import com.designprototype.workshop.ui.ActionRow
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import androidx.compose.material3.Icon
import com.designprototype.workshop.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

const val DW_TRACE_PREVIEW_DEBOUNCE_MS: Long = 450L

const val DW_TRACE_AUTO_PREVIEW_BUDGET_MS: Long = 2500L

const val DW_TRACE_DISCLOSURE_TURN_MS: Int = 180

internal const val DW_TRACE_EXPAND_ACTION: String = "Expand the tracing card"

internal const val DW_TRACE_COLLAPSE_ACTION: String = "Collapse the tracing card"

internal const val DW_TRACE_CARD_TITLE: String = "Trace a sketch into line art"

@Stable
internal class DwTraceCardMemory {

    var tracedName by mutableStateOf("")
        private set

    var shapeCount by mutableIntStateOf(0)
        private set

    var nodeCount by mutableIntStateOf(0)
        private set

    var wasPreview by mutableStateOf(true)
        private set

    var attachedName by mutableStateOf("")
        private set

    fun recordTrace(sourceName: String, shapes: Int, nodes: Int, preview: Boolean) {
        tracedName = sourceName
        shapeCount = shapes
        nodeCount = nodes
        wasPreview = preview
    }

    fun recordAttachment(fileLabel: String) {
        attachedName = fileLabel
    }

    fun forgetTrace() {
        tracedName = ""
        shapeCount = 0
        nodeCount = 0
        wasPreview = true
    }
}

internal fun dwTraceCardSummary(
    tracedName: String,
    shapeCount: Int,
    nodeCount: Int,
    wasPreview: Boolean,
    attachedName: String,
): String? {
    val parts = mutableListOf<String>()
    if (tracedName.isNotBlank()) {
        parts += if (wasPreview) {
            "a preview traced from “${tracedName.trim()}”"
        } else {
            "traced from “${tracedName.trim()}”"
        }
        parts += "$shapeCount paths · $nodeCount nodes"
    }
    if (attachedName.isNotBlank()) parts += "attached as “${attachedName.trim()}”"
    if (parts.isEmpty()) return null
    return parts.joinToString(" · ")
}

@Immutable
class DwTraceExportSlot(
    val result: DwTraceResult,
    val documentBackground: Int?,
    val sourceName: String,
    val busy: Boolean,
    val onBusyChange: (Boolean) -> Unit,
    val onBackgroundChange: (Boolean) -> Unit,
    val onNeedFullResolution: () -> Unit,
)

internal fun dwOffersSketchTrace(field: FieldDto, siblings: Map<String, FieldDto>): Boolean =
    dwOffersSketchRectify(field, siblings)

@Composable
internal fun DwSketchTracePanel(
    field: FieldDto,
    sources: List<DwSketchSource>,
    runtime: DwTraceRuntime,
    media: DwMediaBridge,
    recordCategory: String?,
    currentFileName: String?,
    enabled: Boolean,
    supply: DwSketchPhotographSupply = DwSketchPhotographSupply.OwnChoice,
    onAttached: (String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    exportCard: (@Composable (DwTraceExportSlot) -> Unit)? = null,
) {
    if (sources.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val memory = remember { DwTraceCardMemory() }

    if (!open) {
        val summary = dwTraceCardSummary(
            tracedName = memory.tracedName,
            shapeCount = memory.shapeCount,
            nodeCount = memory.nodeCount,
            wasPreview = memory.wasPreview,
            attachedName = memory.attachedName,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DwPanelDisclosureHeader(
                icon = Icons.Filled.Gesture,
                title = DW_TRACE_CARD_TITLE,
                expanded = false,
                toggleEnabled = enabled,
                expandAction = DW_TRACE_EXPAND_ACTION,
                collapseAction = DW_TRACE_COLLAPSE_ACTION,
                onToggle = { open = true },
            )
            if (summary == null) {
                Text(
                    "Turns the pencil in a photo into vector line art, attached as ${field.label}. Works offline.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            } else {
                Text(
                    summary,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )
                Text(
                    "The trace isn't kept when closed; reopening traces again. Attachments stay.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
            ActionRow {
                OutlinedButton(
                    onClick = { open = true },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (summary == null) "Trace a sketch" else "Open the tracing card again",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        return
    }

    DwSketchTraceOpen(
        field = field,
        sources = sources,
        runtime = runtime,
        media = media,
        recordCategory = recordCategory,
        currentFileName = currentFileName,
        enabled = enabled,
        supply = supply,
        memory = memory,
        onClose = { open = false },
        onAttached = onAttached,
        onMessage = onMessage,
        onError = onError,
        exportCard = exportCard,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwSketchTraceOpen(
    field: FieldDto,
    sources: List<DwSketchSource>,
    runtime: DwTraceRuntime,
    media: DwMediaBridge,
    recordCategory: String?,
    currentFileName: String?,
    enabled: Boolean,
    supply: DwSketchPhotographSupply,
    memory: DwTraceCardMemory,
    onClose: () -> Unit,
    onAttached: (String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    exportCard: (@Composable (DwTraceExportSlot) -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availability = runtime.availability

    var sourceId by remember { mutableStateOf(sources.first().item.id) }
    val hosted = supply as? DwSketchPhotographSupply.Hosted
    val source = hosted?.source
        ?: sources.firstOrNull { it.item.id == sourceId }
        ?: sources.first()

    var presets by remember { mutableStateOf<DwTracePresetTables?>(null) }
    var params by remember { mutableStateOf<DwTraceValues?>(null) }

    var baseline by remember { mutableStateOf<DwTraceValues?>(null) }

    var styleId by remember { mutableStateOf("") }
    var subjectId by remember { mutableStateOf(dwTraceSubjectFor(recordCategory)) }
    var subjectSeeded by remember { mutableStateOf(true) }

    var result by remember { mutableStateOf<DwTraceResult?>(null) }
    var resultWire by remember { mutableStateOf<String?>(null) }

    var frame by remember { mutableStateOf<DwTraceFrameChoice?>(null) }

    var difference by remember { mutableStateOf<Bitmap?>(null) }
    var differenceRefusal by remember { mutableStateOf("") }
    var differenceRunning by remember { mutableStateOf(false) }

    var running by remember { mutableStateOf<DwTraceRunKind?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<DwTraceProgress?>(null) }
    var weights by remember { mutableStateOf(DwTraceProgressWeights.Unweighted) }

    var notice by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var advancedOpen by remember { mutableStateOf(false) }
    var autoPreview by remember { mutableStateOf(true) }
    var attaching by remember { mutableStateOf(false) }

    var job by remember { mutableStateOf<Job?>(null) }

    var runToken by remember { mutableIntStateOf(0) }

    val patchLock = remember { Mutex() }

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val stale = result != null && resultWire != null && resultWire != params?.wire

    LaunchedEffect(runtime) {
        loading = true
        failure = null
        try {
            val tables = runtime.presets()
            val defaults = runtime.defaults()
            presets = tables
            params = defaults
            baseline = defaults
            styleId = defaults.styleId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            failure = "Tracing didn't start. Close and reopen this panel." + traceLog(t)
        }
        loading = false
    }

    fun startRun(kind: DwTraceRunKind, debounceMs: Long) {
        val values = params ?: return
        val previous = job
        val token = runToken
        val tracedName = source.item.displayName
        job = scope.launch {
            previous?.cancelAndJoin()
            if (debounceMs > 0L) delay(debounceMs)
            running = kind
            stopping = false
            progress = null
            failure = null
            try {
                val outcome = runtime.trace(
                    DwTraceRequest(
                        photographPath = source.item.absolutePath,
                        params = values,
                        kind = kind,
                        frame = frame,
                    ),
                ) { progress = it }
                if (token != runToken) return@launch
                when (outcome) {
                    is DwTraceOutcome.Refused -> {
                        result = null
                        resultWire = null
                        difference = null
                        differenceRefusal = ""
                        failure = outcome.reason
                    }

                    is DwTraceOutcome.Done -> {
                        val traced = outcome.result
                        result = traced
                        difference = null
                        differenceRefusal = ""
                        params = traced.appliedParams
                        resultWire = traced.appliedParams.wire
                        memory.recordTrace(
                            sourceName = tracedName,
                            shapes = traced.shapeCount,
                            nodes = traced.nodeCount,
                            preview = traced.isPreview,
                        )
                        if (traced.stages.isNotEmpty()) weights = DwTraceProgressWeights.from(traced.stages)
                        if (kind == DwTraceRunKind.PREVIEW &&
                            traced.totalMillis > DW_TRACE_AUTO_PREVIEW_BUDGET_MS
                        ) {
                            autoPreview = false
                            notice = "That took ${dwTraceSeconds(traced.totalMillis)}, so auto-preview is off. Tap “Update the preview” when ready."
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (token != runToken) return@launch
                result = null
                resultWire = null
                failure = "Trace failed. Try again or use a clearer photo." + traceLog(t)
            } finally {
                running = null
                stopping = false
                progress = null
            }
        }
    }

    fun armPreview() {
        if (!autoPreview || !resumed) return
        if (running?.isFullResolution == true) return
        startRun(DwTraceRunKind.PREVIEW, DW_TRACE_PREVIEW_DEBOUNCE_MS)
    }

    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(loading, autoPreview, resumed) {
        if (loading || params == null || primed) return@LaunchedEffect
        if (!autoPreview || !resumed) return@LaunchedEffect
        primed = true
        startRun(DwTraceRunKind.PREVIEW, 0L)
    }

    fun forgetDerivations() {
        result = null
        resultWire = null
        difference = null
        differenceRefusal = ""
        frame = null
        memory.forgetTrace()
    }

    var derivedFrom by remember { mutableStateOf(source.item.id) }
    LaunchedEffect(source.item.id) {
        if (derivedFrom == source.item.id) return@LaunchedEffect
        derivedFrom = source.item.id
        runToken++
        job?.cancelAndJoin()
        job = null
        running = null
        stopping = false
        progress = null
        failure = null
        notice = null
        forgetDerivations()
        armPreview()
    }

    fun buildDifference() {
        if (differenceRunning || difference != null) return
        val traced = result ?: return
        val photograph = traced.photographPlate ?: return
        val trace = traced.tracePlate ?: return
        differenceRunning = true
        differenceRefusal = ""
        scope.launch {
            val built = withContext(Dispatchers.Default) {
                DwSketchTracePlates.differencePlate(photograph, trace)
            }
            if (result === traced) {
                difference = built
                differenceRefusal = if (built == null) DW_TRACE_DIFFERENCE_REFUSAL else ""
            }
            differenceRunning = false
        }
    }

    fun patch(over: Map<String, DwTraceValue>) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val next = try {
                    runtime.withOverrides(base, over)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "Couldn't apply that setting. Try again." + traceLog(t)
                    return@withLock
                }
                params = next
                notice = null
            }
            armPreview()
        }
    }

    fun pickStyle(id: String) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val name = dwTracePresetName(presets?.styles.orEmpty(), id)
                val next = try {
                    runtime.applyStyle(base, id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "That style could not be applied. Try again." + traceLog(t)
                    return@withLock
                }
                notice = dwTraceOverwriteNotice("The “$name” style", base, next)
                params = next
                baseline = next
                styleId = next.styleId.ifBlank { id }
            }
            armPreview()
        }
    }

    fun pickSubject(id: String) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val name = dwTracePresetName(presets?.subjects.orEmpty(), id)
                val next = try {
                    runtime.applySubject(base, id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "Couldn't apply that change. Try again." + traceLog(t)
                    return@withLock
                }
                notice = dwTraceOverwriteNotice("The “$name” adjustment", base, next)
                params = next
                subjectId = id
                subjectSeeded = false
            }
            armPreview()
        }
    }

    fun attach() {
        val traced = result ?: return
        if (traced.isPreview) return
        attaching = true
        scope.launch {
            val file = media.newCaptureFile("-$DW_TRACE_ATTACH_SUFFIX.svg")
            val written = withContext(Dispatchers.IO) {
                runCatching { file.writeText(traced.svg, Charsets.UTF_8) }.isSuccess
            }
            attaching = false
            if (!written) {
                runCatching { file.delete() }
                onError("Couldn't save the line art.")
                return@launch
            }
            media.attach(listOf(dwCaptureUri(context, file)), field) { ids ->
                val id = ids.firstOrNull()
                if (id != null) {
                    onAttached(id)
                    memory.recordAttachment(field.label)
                    onMessage("Attached as ${field.label}. Original photo unchanged.")
                }
                runCatching { file.delete() }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DwPanelDisclosureHeader(
            icon = Icons.Filled.Gesture,
            title = DW_TRACE_CARD_TITLE,
            expanded = true,
            toggleEnabled = true,
            expandAction = DW_TRACE_EXPAND_ACTION,
            collapseAction = DW_TRACE_COLLAPSE_ACTION,
            onToggle = onClose,
        )

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Starting the tracing engine…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }

        val current = params
        val tables = presets
        if (current == null || tables == null) {
            DwPanelNote(warning = true, text = failure ?: DW_TRACE_ENGINE_SILENT_SENTENCE, polite = true)
            return@Column
        }

        if (hosted != null) {
            Text(
                hosted.source?.let {
                    "Tracing “${it.item.displayName}”."
                } ?: ("Choose a photo in “$DW_SHARED_PHOTOGRAPH_LABEL” above."),
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        } else if (sources.size > 1) {
            DwPanelLabel("Photograph")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sources.forEach { candidate ->
                    DwPanelChip(
                        label = "${candidate.fieldLabel} · ${candidate.item.displayName}",
                        selected = candidate.item.id == source.item.id,
                        enabled = enabled && running == null,
                    ) {
                        sourceId = candidate.item.id
                    }
                }
            }
        }

        DwTraceFramePanel(
            sourcePath = source.item.absolutePath,
            sourceKey = source.item.id,
            applied = frame,
            enabled = enabled && running == null,
            onApply = {
                frame = it
                armPreview()
            },
        )

        val traced = result
        val photographPlate = traced?.photographPlate
        val tracePlate = traced?.tracePlate
        val comparisonStatus = dwTraceComparisonStatus(
            hasPlates = photographPlate != null && tracePlate != null,
            running = running != null,
            failed = failure != null,
            plateRefusal = traced?.plateRefusal.orEmpty(),
            hasResult = traced != null,
        )
        DwPanelLabel("The trace against the photograph")
        if (comparisonStatus.isNotEmpty()) {
            DwPanelNote(
                warning = traced?.plateRefusal?.isNotBlank() == true,
                text = comparisonStatus,
                polite = true,
            )
        }
        if (traced != null) {
            if (photographPlate != null && tracePlate != null) {
                DwSketchTraceCompare(
                    photograph = photographPlate.asImageBitmap(),
                    trace = tracePlate.asImageBitmap(),
                    tracedWidth = traced.width,
                    tracedHeight = traced.height,
                    difference = difference?.asImageBitmap(),
                    differenceRefusal = differenceRefusal,
                    onDifferenceWanted = { buildDifference() },
                    enabled = enabled && running == null,
                )
            }
            DwTraceStatsRow(traced)
            if (traced.isPreview) {
                DwPanelNote(
                    warning = false,
                    text = "Preview at ${traced.workingWidth}×${traced.workingHeight}. Tap “Trace the sketch” for full size.",
                    polite = true,
                )
            }
            if (stale) {
                DwPanelNote(
                    warning = false,
                    text = "Settings changed since this trace.",
                    polite = true,
                )
            }
            if (traced.autoSubjectId.isNotBlank()) {
                DwPanelNote(
                    warning = false,
                    text = "Auto-adjusted for “${dwTracePresetName(tables.subjects, traced.autoSubjectId)}”.",
                )
            }
            DwTraceNotes(traced.notes)
        }

        if (running != null) {
            DwTraceProgressRow(
                kind = running,
                progress = progress,
                weights = weights,
                stopping = stopping,
                enabled = enabled,
            ) {
                stopping = true
                job?.cancel()
            }
        }

        failure?.let { DwPanelNote(warning = true, text = it, polite = true) }
        notice?.let { DwPanelNote(warning = false, text = it, polite = true) }

        val missing = remember(current) { dwTraceMissingKeys(current) }
        if (missing.isNotEmpty()) {
            DwPanelNote(
                warning = true,
                text = "${missing.size} of ${DW_TRACE_PARAM_COUNT} settings hidden on this version. Update to see them.",
            )
        }

        DwTraceStylePicker(
            styles = tables.styles,
            selectedId = styleId,
            enabled = enabled && running == null,
            onPick = { pickStyle(it) },
        )
        DwTraceSubjectPicker(
            subjects = tables.subjects,
            selectedId = subjectId,
            enabled = enabled && running == null,
            seededFromRecord = subjectSeeded,
            onPick = { pickSubject(it) },
        )
        if (subjectSeeded) {
            ActionRow {
                OutlinedButton(
                    onClick = { pickSubject(subjectId) },
                    enabled = enabled && running == null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        "Adjust for ${dwTracePresetName(tables.subjects, subjectId).lowercase(Locale.ROOT)}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        traced?.let {
            DwTraceStyleSuggestion(
                styles = tables.styles,
                suggestedStyleId = it.suggestedStyleId,
                currentStyleId = styleId,
                enabled = enabled && running == null,
                onApply = { pickStyle(it) },
            )
        }

        val changedFromPreset = remember(baseline, current) {
            baseline?.let { dwTraceChangedLabels(it, current) }.orEmpty().toSet()
        }
        val exportVisible = traced != null && exportCard != null
        val visibleTiers = remember(advancedOpen, exportVisible) {
            setOfNotNull(
                DwTraceTier.PRIMARY,
                DwTraceTier.ADVANCED.takeIf { advancedOpen },
                DwTraceTier.EXPORT.takeIf { exportVisible },
            )
        }
        val hiddenChanged = remember(baseline, current, visibleTiers) {
            baseline?.let { dwTraceChangedHiddenLabels(it, current, visibleTiers) }.orEmpty()
        }
        val changedBehindDisclosure = remember(baseline, current) {
            baseline?.let { dwTraceChangedBehindDisclosure(it, current) }.orEmpty()
        }

        DW_TRACE_CONTROLS.filter { it.tier == DwTraceTier.PRIMARY }.forEach { control ->
            DwTraceControlRow(
                control = control,
                values = current,
                availability = availability,
                changed = control.label in changedFromPreset,
                enabled = enabled && running == null,
                onPatch = { patch(it) },
            )
        }

        DwTraceAdvancedSection(
            values = current,
            availability = availability,
            open = advancedOpen,
            changedFromPreset = changedFromPreset,
            hiddenChanged = hiddenChanged,
            changedBehindDisclosure = changedBehindDisclosure,
            enabled = enabled,
            rowsEnabled = enabled && running == null,
            onToggle = { advancedOpen = !advancedOpen },
            onPatch = { patch(it) },
        )

        AppToggle(
            label = "Re-trace a preview as I change settings",
            checked = autoPreview,
            onCheckedChange = { autoPreview = it },
            enabled = enabled,
            description = if (resumed) {
                "Quick preview after each change. Turn off to save battery."
            } else {
                "Paused in the background."
            },
        )
        ActionRow {
            OutlinedButton(
                onClick = { startRun(DwTraceRunKind.PREVIEW, 0L) },
                enabled = enabled && running == null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Update the preview", style = MaterialTheme.typography.labelLarge)
            }
        }

        val bar = dwTraceCostRefusal(current, availability)
        bar?.let { DwPanelNote(warning = true, text = it) }
        if (availability.measuredOn == null) {
            Text(
                "Limits are conservative for this phone.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        ActionRow {
            Button(
                onClick = { startRun(DwTraceRunKind.ATTACH, 0L) },
                enabled = enabled && running == null && bar == null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Trace the sketch", style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = { attach() },
                enabled = enabled && running == null && !attaching &&
                    traced != null && !traced.isPreview && !stale,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add the line art to “${field.label}”", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onClose, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Keep the photograph as it is", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (traced != null && exportCard != null) {
            exportCard(
                DwTraceExportSlot(
                    result = traced,
                    documentBackground = current.number("output.background")?.toLong()?.toInt(),
                    sourceName = source.item.displayName,
                    busy = running != null || attaching,
                    onBusyChange = { attaching = it },
                    onBackgroundChange = { white ->
                        val control = DW_TRACE_TOGGLES.first { it.key == "output.background" }
                        patch(control.patch(white))
                    },
                    onNeedFullResolution = { startRun(DwTraceRunKind.ATTACH, 0L) },
                ),
            )
        }

        dwPanelReplaceWarning(currentFileName, DwPanelHolds.FILE)?.let {
            DwPanelNote(warning = true, text = it)
        }
        Text(
            DW_TRACE_ATTACH_REPORT_SENTENCE,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )

        DwPanelCollapseButton(prominent = true, title = DW_TRACE_CARD_TITLE, onClick = onClose)
    }
}

internal fun dwTraceCostRefusal(
    values: DwTraceValues,
    availability: DwTraceAvailability,
): String? {
    val longEdge = values.number("preprocess.workingLongEdge")?.toInt() ?: return null
    if (longEdge > availability.maxWorkingLongEdge) {
        return "This phone is tested up to ${availability.maxWorkingLongEdge} px (set to $longEdge). Choose a lower resolution."
    }
    if (values.choice("edge.engine") == "FDOG" && longEdge > availability.fdogMaxWorkingLongEdge) {
        return "Flow edge at $longEdge px is too slow here (tested to ${availability.fdogMaxWorkingLongEdge} px). Lower the trace resolution or choose a different edge engine under “$DW_TRACE_DISCLOSURE_ACTION”. The portal agrees with either choice."
    }
    return null
}

internal fun dwTraceSeconds(millis: Long): String =
    if (millis < 1000L) {
        "$millis milliseconds"
    } else {
        String.format(Locale.ROOT, "%.1f seconds", millis / 1000.0)
    }

@Composable
private fun DwTraceProgressRow(
    kind: DwTraceRunKind?,
    progress: DwTraceProgress?,
    weights: DwTraceProgressWeights,
    stopping: Boolean,
    enabled: Boolean,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val sentence = when {
            stopping -> "Stopping…"
            progress != null -> dwTraceProgressSentence(progress, DW_TRACE_KOTLIN_STAGES)
            kind == DwTraceRunKind.PREVIEW -> "Tracing a preview…"
            else -> "Tracing…"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(sentence, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall)
        }

        if (kind != null && kind.isFullResolution && progress != null) {
            val fraction = weights.fractionAt(progress.stageId, progress.fraction)
            FieldLinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
            if (!weights.measured) {
                Text(
                    "Progress counts stages until the first trace finishes.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }

        ActionRow {
            TextButton(
                onClick = onCancel,
                enabled = enabled && !stopping,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (stopping) "Stopping…" else "Stop", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DwTraceNotes(notes: List<String>) {
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        notes.forEach { note ->
            Text(note, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun DwTraceStatsRow(result: DwTraceResult) {
    Text(
        "${result.shapeCount} paths · ${result.nodeCount} nodes · ${dwTraceSeconds(result.totalMillis)}",
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DwTraceAdvancedSection(
    values: DwTraceValues,
    availability: DwTraceAvailability,
    open: Boolean,
    changedFromPreset: Set<String>,
    hiddenChanged: List<String>,
    changedBehindDisclosure: List<String>,
    enabled: Boolean,
    rowsEnabled: Boolean,
    onToggle: () -> Unit,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    val groups = remember(values) { dwTraceAdvancedGroups(values) }
    val revealed = remember(values) { dwTraceAdvancedRevealed(values) }

    val stillness = LocalAppPreferences.current.reducedMotion
    val turn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(durationMillis = if (stillness) 0 else DW_TRACE_DISCLOSURE_TURN_MS),
        label = "trace-advanced-chevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (revealed == 0) {
            DwPanelLabel("The other settings")
            Text(
                "$DW_TRACE_ADVANCED_COUNT more settings hidden on this version. Update to see them.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) {
                    stateDescription = dwTraceDisclosureState(open)
                }
                .clickable(
                    enabled = enabled,
                    onClickLabel = dwTraceDisclosureClickLabel(open, revealed),
                    role = Role.Button,
                ) { onToggle() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = turn },
            )
            Text(
                dwTraceDisclosureLabel(open, revealed, changedBehindDisclosure.size),
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }

        if (!open) {
            Text(
                dwTraceDisclosureBlurb(revealed),
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            dwTraceHiddenChangedSentence(hiddenChanged)?.let {
                DwPanelNote(warning = false, text = it, polite = true)
            }
            return@Column
        }

        groups.forEach { (group, rows) ->
            DwPanelLabel(group)
            rows.forEach { control ->
                DwTraceControlRow(
                    control = control,
                    values = values,
                    availability = availability,
                    changed = control.label in changedFromPreset,
                    enabled = rowsEnabled,
                    onPatch = onPatch,
                )
            }
        }
    }
}

@Composable
private fun DwTraceControlRow(
    control: DwTraceControl,
    values: DwTraceValues,
    availability: DwTraceAvailability,
    changed: Boolean,
    enabled: Boolean,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    val inactive = dwTraceInactiveReason(control, values)
    when (control) {
        is DwTraceSlider -> {
            val value = control.read(values) ?: return
            DwTraceSliderRow(control, value, changed, enabled, inactive, onPatch)
        }

        is DwTraceToggle -> {
            val on = control.read(values) ?: return
            DwTraceToggleRow(control, on, changed, enabled, inactive, onPatch)
        }

        is DwTraceChoice -> {
            val selected = control.read(values) ?: return
            DwTraceChoiceRow(control, selected, changed, enabled, inactive, onPatch)
        }

        is DwTraceNumberChoice -> {
            val value = control.read(values) ?: return
            DwTraceNumberChoiceRow(control, value, availability, changed, enabled, inactive, onPatch)
        }
    }
}

@Composable
private fun DwTraceSliderRow(
    control: DwTraceSlider,
    value: Double,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    var travel by remember(value) { mutableFloatStateOf(control.fractionOf(value)) }
    val live = control.valueAt(travel)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DwTraceRowHeading(control.label, dwTraceFormatValue(live, control.step), changed)
        Slider(
            value = travel,
            onValueChange = { travel = it },
            onValueChangeFinished = { onPatch(control.patch(control.valueAt(travel))) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = "${control.label}, ${dwTraceFormatValue(live, control.step)}"
                },
            enabled = enabled,
            valueRange = 0f..1f,
        )
        DwTraceRowTail(control, inactive)
    }
}

@Composable
private fun DwTraceToggleRow(
    control: DwTraceToggle,
    on: Boolean,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppToggle(
            label = if (changed) "${control.label} · changed" else control.label,
            checked = on,
            onCheckedChange = { onPatch(control.patch(it)) },
            enabled = enabled,
        )
        DwTraceRowTail(control, inactive)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwTraceChoiceRow(
    control: DwTraceChoice,
    selected: String,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DwTraceRowHeading(control.label, null, changed)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            control.options.forEach { option ->
                DwPanelChip(
                    label = option.label,
                    selected = option.value == selected,
                    enabled = enabled,
                ) { onPatch(control.patch(option.value)) }
            }
        }
        DwTraceRowTail(control, inactive)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwTraceNumberChoiceRow(
    control: DwTraceNumberChoice,
    value: Double,
    availability: DwTraceAvailability,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, DwTraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DwTraceRowHeading(control.label, null, changed)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            control.options.forEach { option ->
                val reachable = option.value <= availability.maxWorkingLongEdge.toDouble()
                DwPanelChip(
                    label = option.label,
                    selected = abs(option.value - value) < 0.5,
                    enabled = enabled && reachable,
                ) { onPatch(control.patch(option.value)) }
            }
        }
        control.options.firstOrNull { abs(it.value - value) < 0.5 }?.let {
            Text(it.note, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
        }
        DwTraceRowTail(control, inactive)
    }
}

@Composable
private fun DwTraceRowHeading(label: String, readout: String?, changed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (changed) "$label · changed" else label,
            color = MaterialTheme.field.body,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal,
        )
        readout?.let {
            Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DwTraceRowTail(control: DwTraceControl, inactive: String?) {
    Text(control.hint, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
    control.handsetNote?.let {
        Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp)
    }
    inactive?.let {
        Text(
            "Not used by the current settings. $it",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.labelSmall,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun traceLog(t: Throwable): String {
    android.util.Log.w("SketchTrace", "trace step failed", t)
    return ""
}
