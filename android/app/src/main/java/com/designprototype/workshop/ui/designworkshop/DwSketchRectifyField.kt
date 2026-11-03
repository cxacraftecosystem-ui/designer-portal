package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwDisplayImage
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwImageDecode
import com.designprototype.workshop.data.DwPlateOptions
import com.designprototype.workshop.data.DwPlateResult
import com.designprototype.workshop.data.DwPoint
import com.designprototype.workshop.data.DwSketchPlate
import com.designprototype.workshop.data.DwSketchRectify
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.GreyPlane
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun dwFolded(value: String): String =
    value.lowercase(Locale.ROOT).filter { it in 'a'..'z' }

private val DW_LINE_ART_KEY = Regex("(lineart|linedrawing|vector|plate|artwork)")

internal fun dwSketchSourceFields(siblings: Map<String, FieldDto>): List<FieldDto> =
    siblings.values.filter { candidate ->
        if (candidate.deprecated) return@filter false
        val type = DwFieldType.of(candidate.type)
        type == DwFieldType.IMAGE || type == DwFieldType.IMAGE_LIST
    }

internal fun dwOffersSketchRectify(field: FieldDto, siblings: Map<String, FieldDto>): Boolean {
    if (field.deprecated) return false
    if (DwFieldType.of(field.type) != DwFieldType.FILE) return false
    if (!DW_LINE_ART_KEY.containsMatchIn(dwFolded("${field.key} ${field.label}"))) return false
    return dwSketchSourceFields(siblings).isNotEmpty()
}

@Immutable
internal data class DwSketchSource(val fieldLabel: String, val item: DwMediaItem)

internal const val DW_RECTIFY_CARD_TITLE: String = "Straighten a photographed sketch"

internal const val DW_RECTIFY_EXPAND_ACTION: String = "Expand the straightening card"

internal const val DW_RECTIFY_COLLAPSE_ACTION: String = "Collapse the straightening card"

private data class DwSheetShape(val label: String, val aspect: Double?)

private val SHEET_SHAPES = listOf(
    DwSheetShape("As marked", null),
    DwSheetShape("A-series portrait (A3/A4/A5)", 210.0 / 297.0),
    DwSheetShape("A-series landscape", 297.0 / 210.0),
)

private const val SKETCH_MIN_ZOOM = 1f

private const val SKETCH_MAX_ZOOM = 12f

private val CORNER_SEEDS = listOf(
    0.12 to 0.12,
    0.88 to 0.12,
    0.88 to 0.88,
    0.12 to 0.88,
)

private val CORNER_BADGE = listOf("1", "2", "3", "4")

private val CORNER_NAME = listOf(
    "Sheet corner 1",
    "Sheet corner 2",
    "Sheet corner 3",
    "Sheet corner 4",
)

@Composable
internal fun DwSketchRectifyPanel(
    field: FieldDto,
    sources: List<DwSketchSource>,
    media: DwMediaBridge,
    currentFileName: String?,
    enabled: Boolean,
    supply: DwSketchPhotographSupply = DwSketchPhotographSupply.OwnChoice,
    onAttached: (String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    if (sources.isEmpty()) return
    var open by remember { mutableStateOf(false) }

    if (!open) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DwPanelDisclosureHeader(
                icon = Icons.Filled.AutoFixHigh,
                title = DW_RECTIFY_CARD_TITLE,
                expanded = false,
                toggleEnabled = enabled,
                expandAction = DW_RECTIFY_EXPAND_ACTION,
                collapseAction = DW_RECTIFY_COLLAPSE_ACTION,
                onToggle = { open = true },
            )
            Text(
                "Mark the sheet's corners to square it up as black-on-white line art, attached as ${field.label}. Works offline.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
            ActionRow {
                OutlinedButton(
                    onClick = { open = true },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Straighten a sketch", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        return
    }

    DwSketchRectifyOpen(
        field = field,
        sources = sources,
        media = media,
        currentFileName = currentFileName,
        enabled = enabled,
        supply = supply,
        onClose = { open = false },
        onAttached = onAttached,
        onMessage = onMessage,
        onError = onError,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwSketchRectifyOpen(
    field: FieldDto,
    sources: List<DwSketchSource>,
    media: DwMediaBridge,
    currentFileName: String?,
    enabled: Boolean,
    supply: DwSketchPhotographSupply,
    onClose: () -> Unit,
    onAttached: (String) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceId by remember { mutableStateOf(sources.first().item.id) }
    val hosted = supply as? DwSketchPhotographSupply.Hosted
    val source = hosted?.source
        ?: sources.firstOrNull { it.item.id == sourceId }
        ?: sources.first()

    val currentSourceId by rememberUpdatedState(source.item.id)

    var image by remember { mutableStateOf<DwDisplayImage?>(null) }
    var sourcePlane by remember { mutableStateOf<GreyPlane?>(null) }
    var decoding by remember { mutableStateOf(false) }
    val corners = remember { mutableStateMapOf<Int, DwPoint>() }
    var active by remember { mutableStateOf(0) }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    var shape by remember { mutableStateOf(SHEET_SHAPES.first()) }
    var lineArt by remember { mutableStateOf(true) }

    var plate by remember { mutableStateOf<ImageBitmap?>(null) }
    var plateBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var plateNote by remember { mutableStateOf<String?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    var guess by remember { mutableStateOf<DwCornerGuess?>(null) }
    var guessNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source.item.id) {
        image = null
        sourcePlane = null
        plate = null
        plateBitmap = null
        plateNote = null
        refusal = null
        guess = null
        guessNote = null
        corners.clear()
        active = 0
        decoding = true
        val decoded = withContext(Dispatchers.Default) { DwImageDecode.decodeForDisplay(source.item.absolutePath) }
        val grey = decoded?.let { withContext(Dispatchers.Default) { DwSketchPlate.greyPlaneOf(it.bitmap) } }
        decoding = false
        image = decoded
        sourcePlane = grey
    }

    val bitmap: ImageBitmap? = remember(image) { image?.bitmap?.asImageBitmap() }
    val imageWidth = image?.bitmap?.width?.toFloat() ?: 0f
    val imageHeight = image?.bitmap?.height?.toFloat() ?: 0f

    val fit = if (imageWidth > 0f && imageHeight > 0f && boxSize.width > 0 && boxSize.height > 0) {
        min(boxSize.width / imageWidth, boxSize.height / imageHeight)
    } else {
        0f
    }
    val displayScale = fit * zoom

    fun clampPan(next: Offset, atZoom: Float): Offset {
        if (fit <= 0f) return next
        val spanX = imageWidth * fit * atZoom
        val spanY = imageHeight * fit * atZoom
        fun axis(value: Float, span: Float, container: Float): Float =
            if (span <= container) (container - span) / 2f else min(0f, max(container - span, value))
        return Offset(
            axis(next.x, spanX, boxSize.width.toFloat()),
            axis(next.y, spanY, boxSize.height.toFloat()),
        )
    }

    LaunchedEffect(image, boxSize) {
        zoom = 1f
        pan = clampPan(Offset.Zero, 1f)
    }

    LaunchedEffect(image) {
        if (imageWidth <= 0f || imageHeight <= 0f) return@LaunchedEffect
        for (index in 0 until 4) {
            if (corners[index] != null) continue
            val (fx, fy) = CORNER_SEEDS[index]
            corners[index] = DwPoint(fx * imageWidth, fy * imageHeight)
        }
    }

    fun imageToView(point: DwPoint): Offset =
        Offset(pan.x + (point.x * displayScale).toFloat(), pan.y + (point.y * displayScale).toFloat())

    fun viewToImage(offset: Offset): DwPoint = DwPoint(
        ((offset.x - pan.x) / displayScale).toDouble(),
        ((offset.y - pan.y) / displayScale).toDouble(),
    )

    fun setCorner(index: Int, point: DwPoint) {
        if (imageWidth <= 0f || imageHeight <= 0f) return
        corners[index] = DwPoint(
            x = min(imageWidth.toDouble(), max(0.0, point.x)),
            y = min(imageHeight.toDouble(), max(0.0, point.y)),
        )
        plate = null
        plateBitmap = null
        plateNote = null
        refusal = null
    }

    fun zoomBy(factor: Float, centre: Offset?) {
        if (fit <= 0f) return
        val current = zoom
        val next = min(SKETCH_MAX_ZOOM, max(SKETCH_MIN_ZOOM, current * factor))
        if (next == current) return
        val anchor = centre ?: Offset(boxSize.width / 2f, boxSize.height / 2f)
        zoom = next
        pan = clampPan(
            Offset(
                anchor.x - (anchor.x - pan.x) * next / current,
                anchor.y - (anchor.y - pan.y) * next / current,
            ),
            next,
        )
    }

    fun findTheSheet() {
        val plane = sourcePlane ?: return
        val searchedId = source.item.id
        working = true
        guess = null
        guessNote = null
        scope.launch {
            val found = withContext(Dispatchers.Default) { dwGuessSheetCorners(plane) }
            working = false
            if (searchedId != currentSourceId) return@launch
            if (found == null) {
                guessNote = "Couldn't find the sheet's edges — place the handles yourself."
                return@launch
            }
            guess = found
            guessNote = "Found a sheet (${(found.frameShare * 100).roundToInt()}% of the frame). Handles unchanged."
        }
    }

    fun acceptGuess() {
        val found = guess ?: return
        if (found.corners.size != 4) return
        for (index in 0 until 4) setCorner(index, found.corners[index])
        active = 0
        guess = null
        guessNote = "Handles moved to the detected sheet. Check and adjust each one."
    }

    fun build() {
        val plane = sourcePlane ?: return
        val marked = (0 until 4).mapNotNull { corners[it] }
        if (marked.size != 4) return
        val builtFrom = source.item.id
        working = true
        plate = null
        plateBitmap = null
        plateNote = null
        refusal = null
        scope.launch {
            val ordered = DwSketchRectify.orderCorners(marked)
            if (ordered == null) {
                working = false
                refusal = "Handles don't enclose a sheet. Drag them to the paper's corners."
                return@launch
            }
            val outcome = withContext(Dispatchers.Default) {
                DwSketchRectify.makePlate(
                    plane,
                    ordered,
                    DwPlateOptions(aspect = shape.aspect, lineArt = lineArt),
                )
            }
            if (builtFrom != currentSourceId) {
                working = false
                return@launch
            }
            when (outcome) {
                is DwPlateResult.Refusal -> {
                    working = false
                    refusal = outcome.reason
                }

                is DwPlateResult.Plate -> {
                    val rendered = withContext(Dispatchers.Default) { DwSketchPlate.bitmapOf(outcome.plane) }
                    working = false
                    if (builtFrom != currentSourceId) return@launch
                    if (rendered == null) {
                        refusal = "Not enough memory. Close other photos and retry."
                        return@launch
                    }
                    plateBitmap = rendered
                    plate = rendered.asImageBitmap()
                    plateNote = "${outcome.plane.width}×${outcome.plane.height} px in ${outcome.elapsedMs} ms."
                }
            }
        }
    }

    fun attach() {
        val rendered = plateBitmap ?: return
        working = true
        scope.launch {
            val file = media.newCaptureFile(if (lineArt) "-lineart.png" else "-straightened.png")
            val ok = withContext(Dispatchers.IO) { DwSketchPlate.platePng(rendered, file) }
            working = false
            if (!ok) {
                runCatching { file.delete() }
                onError("Couldn't save the straightened image.")
                return@launch
            }
            media.attach(listOf(dwCaptureUri(context, file)), field) { ids ->
                val id = ids.firstOrNull()
                if (id != null) {
                    onAttached(id)
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
            icon = Icons.Filled.AutoFixHigh,
            title = DW_RECTIFY_CARD_TITLE,
            expanded = true,
            toggleEnabled = true,
            expandAction = DW_RECTIFY_EXPAND_ACTION,
            collapseAction = DW_RECTIFY_COLLAPSE_ACTION,
            onToggle = onClose,
        )

        if (hosted != null) {
            Text(
                hosted.source?.let {
                    "Straightening “${it.item.displayName}”."
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
                        enabled = enabled,
                        onClick = { sourceId = candidate.item.id },
                    )
                }
            }
        }

        DwPanelLabel("Corners — tap to place the selected handle, or drag. Any order.")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (0 until 4).forEach { index ->
                DwPanelChip(
                    label = "Corner ${CORNER_BADGE[index]}",
                    selected = active == index,
                    enabled = enabled,
                    onClick = { active = index },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clipToBounds()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(10.dp))
                .onSizeChanged { boxSize = it }
                .semantics {
                    contentDescription =
                        "Sheet photo. Tap to place the selected corner; drag to move; pinch to zoom."
                }
                .pointerInput(enabled, displayScale) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        if (displayScale <= 0f) return@detectTapGestures
                        setCorner(active, viewToImage(offset))
                        if (active < 3) active += 1
                    }
                }
                .pointerInput(enabled, fit, boxSize) {
                    if (!enabled) return@pointerInput
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        if (fit <= 0f) return@detectTransformGestures
                        if (zoomChange != 1f) zoomBy(zoomChange, centroid)
                        if (panChange != Offset.Zero) pan = clampPan(pan + panChange, zoom)
                    }
                },
        ) {
            when {
                decoding -> Text(
                    "Opening the photograph…",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center),
                )

                bitmap == null -> Text(
                    "Couldn't open this photo. You can still attach line art above.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )

                else -> {
                    val markColor = MaterialTheme.colorScheme.primary
                    val discColor = MaterialTheme.field.surface50
                    val guessColor = MaterialTheme.field.success
                    Canvas(modifier = Modifier.matchParentSize()) {
                        if (displayScale <= 0f) return@Canvas
                        drawImage(
                            image = bitmap,
                            dstOffset = IntOffset(pan.x.roundToInt(), pan.y.roundToInt()),
                            dstSize = IntSize(
                                (imageWidth * displayScale).roundToInt(),
                                (imageHeight * displayScale).roundToInt(),
                            ),
                        )
                        val dashes = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))
                        val placed = (0 until 4).mapNotNull { corners[it] }
                        if (placed.size == 4) {
                            for (index in 0 until 4) {
                                drawLine(
                                    color = markColor,
                                    start = imageToView(placed[index]),
                                    end = imageToView(placed[(index + 1) % 4]),
                                    strokeWidth = 4f,
                                    pathEffect = dashes,
                                )
                            }
                        }

                        val proposed = guess?.corners
                        if (proposed != null && proposed.size == 4) {
                            val dots = PathEffect.dashPathEffect(floatArrayOf(3f, 7f))
                            for (index in 0 until 4) {
                                drawLine(
                                    color = guessColor,
                                    start = imageToView(proposed[index]),
                                    end = imageToView(proposed[(index + 1) % 4]),
                                    strokeWidth = 3f,
                                    pathEffect = dots,
                                )
                            }
                        }
                    }

                    (0 until 4).forEach { index ->
                        val corner = corners[index] ?: return@forEach
                        DwMarkHandle(
                            key = index,
                            badge = CORNER_BADGE[index],
                            name = CORNER_NAME[index],
                            placed = true,
                            outlined = false,
                            enabled = enabled,
                            isActive = active == index,
                            markColor = markColor,
                            discColor = discColor,
                            positionInView = { imageToView(corners[index] ?: corner) },
                            onSelect = { active = index },
                            onDrag = { delta ->
                                val current = corners[index] ?: return@DwMarkHandle
                                if (displayScale <= 0f) return@DwMarkHandle
                                setCorner(
                                    index,
                                    DwPoint(
                                        current.x + delta.x / displayScale,
                                        current.y + delta.y / displayScale,
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DwZoomButton(Icons.Filled.Remove, "Zoom out", enabled) { zoomBy(1f / 1.5f, null) }
                DwZoomButton(Icons.Filled.Add, "Zoom in", enabled) { zoomBy(1.5f, null) }
            }
        }

        if (bitmap != null) {
            ActionRow {
                OutlinedButton(
                    onClick = { findTheSheet() },
                    enabled = enabled && !working && sourcePlane != null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Find the sheet", style = MaterialTheme.typography.labelLarge)
                }
            }

            guessNote?.let { note ->
                Text(
                    note,
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            if (guess != null) {
                ActionRow {
                    Button(
                        onClick = { acceptGuess() },
                        enabled = enabled && !working,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Use these corners", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(
                        onClick = {
                            guess = null
                            guessNote = null
                        },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Leave the handles alone", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (bitmap != null) {
            DwSketchNudgePad(
                badge = CORNER_BADGE[active],
                enabled = enabled,
                displayScale = displayScale,
            ) { delta ->
                val current = corners[active] ?: return@DwSketchNudgePad
                setCorner(active, DwPoint(current.x + delta.x, current.y + delta.y))
            }
        }

        DwPanelLabel("Sheet proportion")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SHEET_SHAPES.forEach { candidate ->
                DwPanelChip(
                    label = candidate.label,
                    selected = candidate.label == shape.label,
                    enabled = enabled,
                    onClick = {
                        shape = candidate
                        plate = null
                        plateBitmap = null
                        plateNote = null
                        refusal = null
                    },
                )
            }
        }
        Text(
            "“As marked” uses the sides as they appear. Choose a paper size if you know it.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )

        DwPanelLabel("What to produce")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(true to "Line art — black on white", false to "Straightened only — keep the tones")
                .forEach { (value, label) ->
                    DwPanelChip(
                        label = label,
                        selected = lineArt == value,
                        enabled = enabled,
                        onClick = {
                            lineArt = value
                            plate = null
                            plateBitmap = null
                            plateNote = null
                            refusal = null
                        },
                    )
                }
        }
        Text(
            "Soft shading may be lost in black-and-white. Try both and compare.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )

        ActionRow {
            Button(
                onClick = { build() },
                enabled = enabled && !working && sourcePlane != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (working) "Working…" else "Make the plate", style = MaterialTheme.typography.labelLarge)
            }
        }

        refusal?.let { reason ->
            DwPanelNote(warning = true, text = reason, polite = true)
        }

        plate?.let { preview ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "The plate — not attached yet",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp)),
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val scale = min(
                            size.width / preview.width.toFloat(),
                            size.height / preview.height.toFloat(),
                        )
                        if (scale <= 0f) return@Canvas
                        val drawWidth = (preview.width * scale).roundToInt()
                        val drawHeight = (preview.height * scale).roundToInt()
                        drawImage(
                            image = preview,
                            dstOffset = IntOffset(
                                ((size.width - drawWidth) / 2f).roundToInt(),
                                ((size.height - drawHeight) / 2f).roundToInt(),
                            ),
                            dstSize = IntSize(drawWidth, drawHeight),
                        )
                    }
                }
                plateNote?.let { note ->
                    Text(note, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Check it against the original sheet before attaching.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
                ActionRow {
                    Button(
                        onClick = { attach() },
                        enabled = enabled && !working,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add to “${field.label}”", style = MaterialTheme.typography.labelLarge)
                    }
                }
                dwPanelReplaceWarning(currentFileName, DwPanelHolds.FILE)?.let {
                    DwPanelNote(warning = true, text = it)
                }
                Text(
                    "The original photo is kept unchanged.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }

        image?.let { decoded ->
            if (decoded.bitmap.width < decoded.sourceWidth) {
                Text(
                    "Marked on a ${decoded.bitmap.width}×${decoded.bitmap.height} working copy of a ${decoded.sourceWidth}×${decoded.sourceHeight} photo.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }

        DwPanelCollapseButton(prominent = true, title = DW_RECTIFY_CARD_TITLE, onClick = onClose)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun DwSketchNudgePad(
    badge: String,
    enabled: Boolean,
    displayScale: Float,
    onNudge: (DwPoint) -> Unit,
) {
    val step = if (displayScale > 0f) max(1.0, (4.0 / displayScale).roundToInt().toDouble()) else 1.0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Nudge corner $badge", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(4.dp))
        DwNudgeButton(Icons.Filled.KeyboardArrowLeft, "Nudge corner $badge left", enabled) {
            onNudge(DwPoint(-step, 0.0))
        }
        DwNudgeButton(Icons.Filled.KeyboardArrowRight, "Nudge corner $badge right", enabled) {
            onNudge(DwPoint(step, 0.0))
        }
        DwNudgeButton(Icons.Filled.KeyboardArrowUp, "Nudge corner $badge up", enabled) {
            onNudge(DwPoint(0.0, -step))
        }
        DwNudgeButton(Icons.Filled.KeyboardArrowDown, "Nudge corner $badge down", enabled) {
            onNudge(DwPoint(0.0, step))
        }
    }
}
