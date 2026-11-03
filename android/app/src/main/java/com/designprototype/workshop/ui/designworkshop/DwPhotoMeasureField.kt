package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwDisplayImage
import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.DwImageDecode
import com.designprototype.workshop.data.DwKnownRectangle
import com.designprototype.workshop.data.DwMeasureResult
import com.designprototype.workshop.data.DwPhotoMeasure
import com.designprototype.workshop.data.DwPoint
import com.designprototype.workshop.data.DwRoundedValue
import com.designprototype.workshop.data.DwScaleReference
import com.designprototype.workshop.data.DwSegment
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DwMeasureTarget(val field: FieldDto, val unit: String)

internal fun dwMeasurableLengthFields(siblings: Map<String, FieldDto>): List<DwMeasureTarget> =
    siblings.values.mapNotNull { field ->
        if (field.deprecated) return@mapNotNull null
        val type = DwFieldType.of(field.type)
        if (type != DwFieldType.DECIMAL && type != DwFieldType.INT) return@mapNotNull null
        val unit = field.unit.trim().lowercase(Locale.ROOT)
        if (!DwPhotoMeasure.LENGTH_UNITS.containsKey(unit)) return@mapNotNull null
        DwMeasureTarget(field, unit)
    }

internal fun dwOffersPhotoMeasure(field: FieldDto, siblings: Map<String, FieldDto>): Boolean {
    if (field.deprecated) return false
    val type = DwFieldType.of(field.type)
    if (type != DwFieldType.IMAGE && type != DwFieldType.IMAGE_LIST) return false
    return dwMeasurableLengthFields(siblings).isNotEmpty()
}

private enum class DwMeasureMode { SCALE, RECTIFY }

private enum class DwMarkId { REF_A, REF_B, C0, C1, C2, C3, TGT_A, TGT_B }

private data class DwMark(
    val point: DwPoint,
    val sigma: Double,
    val placed: Boolean,
)

private val SCALE_MARKS = listOf(DwMarkId.REF_A, DwMarkId.REF_B, DwMarkId.TGT_A, DwMarkId.TGT_B)
private val RECTIFY_MARKS =
    listOf(DwMarkId.C0, DwMarkId.C1, DwMarkId.C2, DwMarkId.C3, DwMarkId.TGT_A, DwMarkId.TGT_B)

private val MARK_BADGE: Map<DwMarkId, String> = mapOf(
    DwMarkId.REF_A to "R1",
    DwMarkId.REF_B to "R2",
    DwMarkId.C0 to "1",
    DwMarkId.C1 to "2",
    DwMarkId.C2 to "3",
    DwMarkId.C3 to "4",
    DwMarkId.TGT_A to "A",
    DwMarkId.TGT_B to "B",
)

private val MARK_NAME: Map<DwMarkId, String> = mapOf(
    DwMarkId.REF_A to "Reference, first end",
    DwMarkId.REF_B to "Reference, second end",
    DwMarkId.C0 to "Rectangle corner 1",
    DwMarkId.C1 to "Rectangle corner 2 (along the width)",
    DwMarkId.C2 to "Rectangle corner 3 (opposite corner 1)",
    DwMarkId.C3 to "Rectangle corner 4",
    DwMarkId.TGT_A to "The dimension, first end",
    DwMarkId.TGT_B to "The dimension, second end",
)

private val SEEDS: Map<DwMarkId, DwPoint> = mapOf(
    DwMarkId.REF_A to DwPoint(0.14, 0.84),
    DwMarkId.REF_B to DwPoint(0.52, 0.84),
    DwMarkId.C0 to DwPoint(0.20, 0.20),
    DwMarkId.C1 to DwPoint(0.80, 0.22),
    DwMarkId.C2 to DwPoint(0.82, 0.78),
    DwMarkId.C3 to DwPoint(0.18, 0.76),
    DwMarkId.TGT_A to DwPoint(0.30, 0.42),
    DwMarkId.TGT_B to DwPoint(0.72, 0.44),
)

private data class DwScalePreset(val label: String, val length: Double, val unit: String)
private data class DwRectPreset(val label: String, val width: Double, val height: Double, val unit: String)

private val SCALE_PRESETS = listOf(
    DwScalePreset("Scale card, 100 mm", 100.0, "mm"),
    DwScalePreset("Steel rule, 300 mm", 300.0, "mm"),
    DwScalePreset("A4 short edge, 210 mm", 210.0, "mm"),
    DwScalePreset("₹5 coin, 23 mm", 23.0, "mm"),
)

private val RECT_PRESETS = listOf(
    DwRectPreset("A4 sheet", 210.0, 297.0, "mm"),
    DwRectPreset("A5 sheet", 148.0, 210.0, "mm"),
    DwRectPreset("Bank/ID card", 85.6, 54.0, "mm"),
)

private const val MIN_ZOOM = 1f

private const val MAX_ZOOM = 12f

internal fun dwFormatRounded(rounded: DwRoundedValue): String =
    String.format(Locale.ROOT, "%.${rounded.decimals}f", rounded.value)

private const val MIN_VIEWPORT_RATIO = 1f
private const val MAX_VIEWPORT_RATIO = 2f

private val EMPTY_VIEWPORT_MIN_HEIGHT = 96.dp

private fun dwFirstMark(mode: DwMeasureMode): DwMarkId =
    if (mode == DwMeasureMode.SCALE) DwMarkId.REF_A else DwMarkId.C0

internal const val DW_PANEL_COLLAPSE_WORD = "Close"

internal const val DW_MEASURE_COLLAPSE_ACTION = "Collapse the measuring card"

internal const val DW_MEASURE_EXPAND_ACTION = "Expand the measuring card"

internal const val DW_MEASURE_CARD_TITLE = "Measure a dimension from a photograph"

internal fun dwMeasureSpansFieldsClause(photoFieldLabels: List<String>): String {
    val named = photoFieldLabels.map { it.trim() }.filter { it.isNotEmpty() }
    if (named.size < 2) return ""
    return " These are every photograph on the record — " +
        named.joinToString(" and ") { "“$it”" } +
        " together, so the photo with the ruler counts wherever it was attached."
}

internal enum class DwPanelHolds {
    FILE,

    VALUE,
}

internal fun dwPanelReplaceWarning(current: String?, holds: DwPanelHolds): String? {
    val trimmed = current?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val where = when (holds) {
        DwPanelHolds.FILE -> "is attached here now"
        DwPanelHolds.VALUE -> "is in this field now"
    }
    return "“$trimmed” $where. This replaces it."
}

@Stable
private class DwMeasureConfig(initialPhotoId: String) {

    var photoId by mutableStateOf(initialPhotoId)

    var marksPhotoId by mutableStateOf(initialPhotoId)
        private set

    var mode by mutableStateOf(DwMeasureMode.SCALE)
        private set

    var active by mutableStateOf(dwFirstMark(DwMeasureMode.SCALE))

    val marks = mutableStateMapOf<DwMarkId, DwMark>()

    var referenceLength by mutableStateOf("")
    var referenceUnit by mutableStateOf("mm")
    var rectWidth by mutableStateOf("")
    var rectHeight by mutableStateOf("")
    var rectUnit by mutableStateOf("mm")

    var substituteId by mutableStateOf("")
        private set

    val needed: List<DwMarkId>
        get() = if (mode == DwMeasureMode.SCALE) SCALE_MARKS else RECTIFY_MARKS

    val placedCount: Int
        get() = needed.count { marks[it]?.placed == true }

    fun chooseMode(next: DwMeasureMode) {
        if (next == mode) return
        mode = next
        active = dwFirstMark(next)
    }

    fun usePhotograph(id: String) {
        photoId = id
        if (id == marksPhotoId) return
        marksPhotoId = id
        marks.clear()
        active = dwFirstMark(mode)
    }

    fun followShared(id: String) {
        if (substituteId.isNotBlank()) return
        usePhotograph(id)
    }

    fun measureInstead(id: String) {
        substituteId = id
        usePhotograph(id)
    }

    fun backToShared(sharedId: String) {
        substituteId = ""
        usePhotograph(sharedId)
    }
}

internal fun dwMeasureSummary(
    marksPlaced: Int,
    marksNeeded: Int,
    fourCorner: Boolean,
    referenceLength: String,
    referenceUnit: String,
    rectWidth: String,
    rectHeight: String,
    rectUnit: String,
    photographName: String?,
): String? {
    val size = if (fourCorner) {
        val width = rectWidth.trim()
        val height = rectHeight.trim()
        if (width.isEmpty() || height.isEmpty()) null else "$width × $height $rectUnit rectangle"
    } else {
        val length = referenceLength.trim()
        if (length.isEmpty()) null else "$length $referenceUnit reference"
    }
    if (marksPlaced <= 0 && size == null) return null

    val parts = mutableListOf<String>()
    parts += if (marksPlaced <= 0) "no marks placed yet" else "$marksPlaced of $marksNeeded marks placed"
    parts += size ?: if (fourCorner) "no rectangle size yet" else "no reference length yet"
    parts += if (fourCorner) "four-corner method" else "same-plane method"
    if (!photographName.isNullOrBlank()) parts += "photograph “${photographName.trim()}”"
    return parts.joinToString(" · ")
}

@Composable
internal fun DwPhotoMeasurePanel(
    photos: List<DwMediaItem>,
    photoFieldLabels: List<String> = emptyList(),
    targets: List<DwMeasureTarget>,
    rowValues: Map<String, JsonElement>,
    enabled: Boolean,
    supply: DwSketchPhotographSupply = DwSketchPhotographSupply.OwnChoice,
    onPropose: (String, JsonElement?, String?) -> Unit,
) {
    if (photos.isEmpty() || targets.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val config = remember { DwMeasureConfig(photos.first().id) }

    val sharedId = (supply as? DwSketchPhotographSupply.Hosted)?.source?.item?.id
    LaunchedEffect(sharedId) {
        if (sharedId != null) config.followShared(sharedId)
    }

    val substituteGone = config.substituteId.isNotBlank() &&
        photos.none { it.id == config.substituteId }
    LaunchedEffect(substituteGone, sharedId) {
        if (substituteGone && sharedId != null) config.backToShared(sharedId)
    }

    if (open) {
        DwPhotoMeasureOpen(
            photos = photos,
            targets = targets,
            rowValues = rowValues,
            enabled = enabled,
            config = config,
            sharedId = sharedId,
            onCollapse = { open = false },
            onPropose = onPropose,
        )
        return
    }

    val chosen = photos.firstOrNull { it.id == config.photoId }
    val summary = dwMeasureSummary(
        marksPlaced = config.placedCount,
        marksNeeded = config.needed.size,
        fourCorner = config.mode == DwMeasureMode.RECTIFY,
        referenceLength = config.referenceLength,
        referenceUnit = config.referenceUnit,
        rectWidth = config.rectWidth,
        rectHeight = config.rectHeight,
        rectUnit = config.rectUnit,
        photographName = if (photos.size > 1) chosen?.displayName else null,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DwPanelDisclosureHeader(
            icon = Icons.Filled.Straighten,
            title = DW_MEASURE_CARD_TITLE,
            expanded = false,
            toggleEnabled = enabled,
            expandAction = DW_MEASURE_EXPAND_ACTION,
            collapseAction = DW_MEASURE_COLLAPSE_ACTION,
            onToggle = { open = true },
        )

        if (sharedId != null && config.substituteId.isNotBlank() && config.substituteId != sharedId) {
            Text(
                "$DW_MEASURE_ELSEWHERE_TITLE — “${chosen?.displayName.orEmpty()}”.",
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        if (summary == null) {
            Text(
                "Measure from a ruler or scale card in a photo, into " +
                    targets.joinToString(", ") { it.field.label } +
                    ". Works offline." +
                    dwMeasureSpansFieldsClause(photoFieldLabels),
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
                "Your marks are kept when closed.",
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
                Icon(Icons.Filled.Straighten, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (summary == null) "Measure from a photograph" else "Open the measuring card again",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
internal fun DwPanelDisclosureHeader(
    icon: ImageVector,
    title: String,
    expanded: Boolean,
    toggleEnabled: Boolean,
    expandAction: String,
    collapseAction: String,
    onToggle: () -> Unit,
) {
    val reduceMotion = LocalAppPreferences.current.reducedMotion
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 180),
        label = "derivationCardChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(
                enabled = toggleEnabled,
                onClickLabel = if (expanded) collapseAction else expandAction,
                role = Role.Button,
                onClick = onToggle,
            )
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.field.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (expanded) DwPanelCollapseButton(prominent = false, title = title, onClick = onToggle)
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.field.muted,
            modifier = Modifier
                .size(20.dp)
                .rotate(turn),
        )
    }
}

@Composable
internal fun DwPanelCollapseButton(prominent: Boolean, title: String, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (prominent) "$DW_PANEL_COLLAPSE_WORD “$title”" else DW_PANEL_COLLAPSE_WORD,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (prominent) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            content = content,
        )
    } else {
        TextButton(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwPhotoMeasureOpen(
    photos: List<DwMediaItem>,
    targets: List<DwMeasureTarget>,
    rowValues: Map<String, JsonElement>,
    enabled: Boolean,
    config: DwMeasureConfig,
    sharedId: String?,
    onCollapse: () -> Unit,
    onPropose: (String, JsonElement?, String?) -> Unit,
) {
    val photo = photos.firstOrNull { it.id == config.photoId } ?: photos.first()
    val marks = config.marks
    val needed = config.needed

    var image by remember { mutableStateOf<DwDisplayImage?>(null) }
    var decoding by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    var proposeError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(photo.id) {
        config.usePhotograph(photo.id)
        image = null
        decoding = true
        val decoded = withContext(Dispatchers.Default) { DwImageDecode.decodeForDisplay(photo.absolutePath) }
        decoding = false
        image = decoded
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

    LaunchedEffect(image, config.mode) {
        if (imageWidth <= 0f || imageHeight <= 0f) return@LaunchedEffect
        for (id in needed) {
            if (marks[id] != null) continue
            val seed = SEEDS.getValue(id)
            marks[id] = DwMark(
                point = DwPoint(seed.x * imageWidth, seed.y * imageHeight),
                sigma = DwPhotoMeasure.DEFAULT_MARK_SIGMA_PX,
                placed = false,
            )
        }
    }

    fun imageToView(point: DwPoint): Offset =
        Offset(pan.x + (point.x * displayScale).toFloat(), pan.y + (point.y * displayScale).toFloat())

    fun viewToImage(offset: Offset): DwPoint = DwPoint(
        ((offset.x - pan.x) / displayScale).toDouble(),
        ((offset.y - pan.y) / displayScale).toDouble(),
    )

    fun setMark(id: DwMarkId, point: DwPoint) {
        if (imageWidth <= 0f || imageHeight <= 0f) return
        val clamped = DwPoint(
            x = min(imageWidth.toDouble(), max(0.0, point.x)),
            y = min(imageHeight.toDouble(), max(0.0, point.y)),
        )
        marks[id] = DwMark(
            point = clamped,
            sigma = DwPhotoMeasure.markSigmaForDisplayScale(displayScale.toDouble()),
            placed = true,
        )
        proposeError = null
    }

    fun zoomBy(factor: Float, centre: Offset?) {
        if (fit <= 0f) return
        val current = zoom
        val next = min(MAX_ZOOM, max(MIN_ZOOM, current * factor))
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

    val allPlaced = needed.all { marks[it]?.placed == true }

    val markSigmaPx = needed.mapNotNull { marks[it]?.sigma }.maxOrNull()

    val result: DwMeasureResult? = when {
        !allPlaced || markSigmaPx == null -> null
        config.mode == DwMeasureMode.SCALE -> {
            val length = config.referenceLength.trim().replace(",", "").toDoubleOrNull()
            val refA = marks[DwMarkId.REF_A]
            val refB = marks[DwMarkId.REF_B]
            if (length == null || refA == null || refB == null) {
                null
            } else {
                DwPhotoMeasure.measureBySameScale(
                    reference = DwScaleReference(refA.point, refB.point, length, config.referenceUnit),
                    target = DwSegment(marks.getValue(DwMarkId.TGT_A).point, marks.getValue(DwMarkId.TGT_B).point),
                    markSigmaPx = markSigmaPx,
                )
            }
        }
        else -> {
            val width = config.rectWidth.trim().replace(",", "").toDoubleOrNull()
            val height = config.rectHeight.trim().replace(",", "").toDoubleOrNull()
            if (width == null || height == null) {
                null
            } else {
                DwPhotoMeasure.measureByRectification(
                    corners = listOf(DwMarkId.C0, DwMarkId.C1, DwMarkId.C2, DwMarkId.C3)
                        .map { marks.getValue(it).point },
                    rectangle = DwKnownRectangle(width, height, config.rectUnit),
                    target = DwSegment(marks.getValue(DwMarkId.TGT_A).point, marks.getValue(DwMarkId.TGT_B).point),
                    markSigmaPx = markSigmaPx,
                )
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
            icon = Icons.Filled.Straighten,
            title = DW_MEASURE_CARD_TITLE,
            expanded = true,
            toggleEnabled = true,
            expandAction = DW_MEASURE_EXPAND_ACTION,
            collapseAction = DW_MEASURE_COLLAPSE_ACTION,
            onToggle = onCollapse,
        )

        val overrideInForce = config.substituteId.isNotBlank() && config.substituteId != sharedId
        if (sharedId != null && (photos.size > 1 || overrideInForce)) {
            DwMeasureDifferentPhotograph(
                photos = photos,
                config = config,
                sharedId = sharedId,
                chosen = photo,
                enabled = enabled,
            )
        } else if (sharedId == null && photos.size > 1) {
            DwPanelLabel("Photograph")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                photos.forEach { candidate ->
                    DwPanelChip(
                        label = candidate.displayName,
                        selected = candidate.id == photo.id,
                        enabled = enabled,
                        onClick = { config.photoId = candidate.id },
                    )
                }
            }
        }

        DwPanelLabel("Method")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                DwMeasureMode.SCALE to "Same plane (2 marks)",
                DwMeasureMode.RECTIFY to "Tilted — rectify a rectangle (4 corners)",
            ).forEach { (value, label) ->
                DwPanelChip(
                    label = label,
                    selected = config.mode == value,
                    enabled = enabled,
                    onClick = {
                        config.chooseMode(value)
                        proposeError = null
                    },
                )
            }
        }

        if (config.mode == DwMeasureMode.SCALE) {
            DwPanelNote(
                warning = true,
                text = "Only accurate when the reference and object are flat, in one plane, facing the camera. For angled photos, use the four-corner method.",
            )
        } else {
            DwPanelNote(
                warning = false,
                text = "Mark the four corners in order around the rectangle. The object must lie on that surface.",
            )
        }

        DwPanelLabel("Marks — tap to place, drag or nudge to adjust")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            needed.forEach { id ->
                DwPanelChip(
                    label = "${MARK_BADGE.getValue(id)} · ${if (marks[id]?.placed == true) "placed" else "not placed"}",
                    selected = config.active == id,
                    enabled = enabled,
                    onClick = { config.active = id },
                )
            }
        }

        val viewportRatio = if (imageWidth > 0f && imageHeight > 0f) {
            (imageWidth / imageHeight).coerceIn(MIN_VIEWPORT_RATIO, MAX_VIEWPORT_RATIO)
        } else {
            null
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (viewportRatio != null) {
                        Modifier.aspectRatio(viewportRatio)
                    } else {
                        Modifier.heightIn(min = EMPTY_VIEWPORT_MIN_HEIGHT)
                    }
                )
                .clipToBounds()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(10.dp))
                .onSizeChanged { boxSize = it }
                .semantics {
                    contentDescription =
                        "Photo being measured. Tap to place the selected mark; drag to move; pinch to zoom."
                }
                .pointerInput(enabled, displayScale, needed) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        if (displayScale <= 0f) return@detectTapGestures
                        setMark(config.active, viewToImage(offset))
                        val index = needed.indexOf(config.active)
                        if (index in 0 until needed.size - 1) config.active = needed[index + 1]
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
                    "Couldn't open this photo. You can still type the dimension.",
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
                        if (config.mode == DwMeasureMode.SCALE) {
                            val a = marks[DwMarkId.REF_A]
                            val b = marks[DwMarkId.REF_B]
                            if (a != null && b != null) {
                                drawLine(
                                    color = markColor,
                                    start = imageToView(a.point),
                                    end = imageToView(b.point),
                                    strokeWidth = 4f,
                                    pathEffect = dashes,
                                )
                            }
                        } else {
                            val corners = listOf(DwMarkId.C0, DwMarkId.C1, DwMarkId.C2, DwMarkId.C3)
                                .mapNotNull { marks[it] }
                            if (corners.size == 4) {
                                for (index in 0 until 4) {
                                    drawLine(
                                        color = markColor,
                                        start = imageToView(corners[index].point),
                                        end = imageToView(corners[(index + 1) % 4].point),
                                        strokeWidth = 4f,
                                        pathEffect = dashes,
                                    )
                                }
                            }
                        }
                        val tgtA = marks[DwMarkId.TGT_A]
                        val tgtB = marks[DwMarkId.TGT_B]
                        if (tgtA != null && tgtB != null) {
                            drawLine(
                                color = markColor,
                                start = imageToView(tgtA.point),
                                end = imageToView(tgtB.point),
                                strokeWidth = 6f,
                                cap = StrokeCap.Round,
                            )
                        }
                    }

                    needed.forEach { id ->
                        val mark = marks[id] ?: return@forEach
                        DwMarkHandle(
                            key = id,
                            badge = MARK_BADGE.getValue(id),
                            name = MARK_NAME.getValue(id),
                            placed = mark.placed,
                            outlined = id == DwMarkId.TGT_A || id == DwMarkId.TGT_B,
                            enabled = enabled,
                            isActive = config.active == id,
                            markColor = markColor,
                            discColor = discColor,
                            positionInView = { imageToView(marks[id]?.point ?: mark.point) },
                            onSelect = { config.active = id },
                            onDrag = { delta ->
                                val current = marks[id]?.point ?: return@DwMarkHandle
                                if (displayScale <= 0f) return@DwMarkHandle
                                setMark(
                                    id,
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

        Text(
            buildString {
                append("Zoom ")
                append(String.format(Locale.ROOT, "%.1f", zoom))
                append("×. ")
                if (displayScale > 0f) {
                    append("At this zoom a mark can be placed to about ±")
                    append(
                        String.format(
                            Locale.ROOT,
                            "%.1f",
                            DwPhotoMeasure.markSigmaForDisplayScale(displayScale.toDouble()),
                        )
                    )
                    append(" px in the working copy. ")
                    append("Zooming in genuinely narrows it.")
                } else {
                    append("Opening the photograph…")
                }
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        image?.let { decoded ->
            val shownWidth = decoded.bitmap.width
            if (shownWidth < decoded.sourceWidth) {
                Text(
                    "Marked on a ${shownWidth}×${decoded.bitmap.height} working copy of a ${decoded.sourceWidth}×${decoded.sourceHeight} photo.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }

        if (bitmap != null) {
            DwNudgePad(active = config.active, enabled = enabled, displayScale = displayScale) { delta ->
                val current = marks[config.active]?.point ?: return@DwNudgePad
                setMark(config.active, DwPoint(current.x + delta.x, current.y + delta.y))
            }
        }

        if (config.mode == DwMeasureMode.SCALE) {
            DwPanelLabel("How long is the reference, really?")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SCALE_PRESETS.forEach { preset ->
                    DwPanelChip(
                        label = preset.label,
                        selected = false,
                        enabled = enabled,
                        isChoice = false,
                        onClick = {
                            config.referenceLength = dwTrimNumber(preset.length)
                            config.referenceUnit = preset.unit
                        },
                    )
                }
            }
            OutlinedTextField(
                value = config.referenceLength,
                onValueChange = { config.referenceLength = it },
                label = { Text("Reference length") },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            DwUnitChips(selected = config.referenceUnit, enabled = enabled) { config.referenceUnit = it }
        } else {
            DwPanelLabel("How big is the rectangle, really?")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RECT_PRESETS.forEach { preset ->
                    DwPanelChip(
                        label = preset.label,
                        selected = false,
                        enabled = enabled,
                        isChoice = false,
                        onClick = {
                            config.rectWidth = dwTrimNumber(preset.width)
                            config.rectHeight = dwTrimNumber(preset.height)
                            config.rectUnit = preset.unit
                        },
                    )
                }
            }
            OutlinedTextField(
                value = config.rectWidth,
                onValueChange = { config.rectWidth = it },
                label = { Text("Width, corner 1 to corner 2") },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = config.rectHeight,
                onValueChange = { config.rectHeight = it },
                label = { Text("Height, corner 2 to corner 3") },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            DwUnitChips(selected = config.rectUnit, enabled = enabled) { config.rectUnit = it }
            Text(
                "Width: corner 1 → 2. Height: corner 2 → 3.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        DwMeasurementReadout(
            result = result,
            allPlaced = allPlaced,
            needed = needed,
            marks = marks,
            mode = config.mode,
            targets = targets,
            rowValues = rowValues,
            enabled = enabled,
            proposeError = proposeError,
            onPropose = { target, text ->
                val coerced = DwValues.coerce(target.field, text)
                if (coerced.error != null) {
                    proposeError = coerced.error
                } else {
                    proposeError = null
                    onPropose(
                        target.field.key,
                        coerced.value,
                        (result as? DwMeasureResult.Measurement)?.method,
                    )
                }
            },
        )

        Text(
            "Your marks are kept when closed.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
        DwPanelCollapseButton(prominent = true, title = DW_MEASURE_CARD_TITLE, onClick = onCollapse)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwMeasureDifferentPhotograph(
    photos: List<DwMediaItem>,
    config: DwMeasureConfig,
    sharedId: String,
    chosen: DwMediaItem,
    enabled: Boolean,
) {
    val inForce = config.substituteId.isNotBlank() && config.substituteId != sharedId
    var open by remember { mutableStateOf(false) }
    val showing = open || inForce

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (inForce) {
            Text(
                DW_MEASURE_ELSEWHERE_TITLE,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                "Measuring “${chosen.displayName}”. The tracing panel is unaffected.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (showing) "Expanded" else "Collapsed"
                    }
                    .clickable(
                        enabled = enabled,
                        onClickLabel = if (showing) {
                            "Fold away the different-photograph chooser"
                        } else {
                            "Show the different-photograph chooser"
                        },
                        role = Role.Button,
                    ) { open = !open },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Straighten,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(14.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        DW_MEASURE_DIFFERENT_PHOTOGRAPH,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        DW_MEASURE_DIFFERENT_WHY,
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp,
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.field.muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (showing) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                photos.forEach { candidate ->
                    DwPanelChip(
                        label = if (candidate.id == sharedId) {
                            "${candidate.displayName} · chosen above"
                        } else {
                            candidate.displayName
                        },
                        selected = candidate.id == chosen.id,
                        enabled = enabled,
                        onClick = {
                            if (candidate.id == sharedId) {
                                config.backToShared(sharedId)
                            } else {
                                config.measureInstead(candidate.id)
                            }
                        },
                    )
                }
            }
            Text(
                "Changing the photo clears the marks.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        if (inForce) {
            OutlinedButton(
                onClick = { config.backToShared(sharedId) },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(DW_MEASURE_BACK_TO_SHARED, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwMeasurementReadout(
    result: DwMeasureResult?,
    allPlaced: Boolean,
    needed: List<DwMarkId>,
    marks: Map<DwMarkId, DwMark>,
    mode: DwMeasureMode,
    targets: List<DwMeasureTarget>,
    rowValues: Map<String, JsonElement>,
    enabled: Boolean,
    proposeError: String?,
    onPropose: (DwMeasureTarget, String) -> Unit,
) {
    if (!allPlaced) {
        val remaining = needed.filter { marks[it]?.placed != true }
        Text(
            "${remaining.size} of ${needed.size} marks left (${remaining.joinToString(", ") { MARK_BADGE.getValue(it) }}).",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
        return
    }

    if (result == null) {
        Text(
            if (mode == DwMeasureMode.SCALE) {
                "Enter the reference's real length."
            } else {
                "Enter the rectangle's real size."
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    if (result is DwMeasureResult.Refusal) {
        DwPanelNote(warning = true, text = result.reason, polite = true)
        return
    }

    val measurement = result as DwMeasureResult.Measurement
    val shown = DwPhotoMeasure.roundToUncertainty(measurement.value, measurement.uncertainty)
    val doubt = DwPhotoMeasure.roundToUncertainty(measurement.uncertainty, measurement.uncertainty)

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
            "Measured — not saved yet",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "${dwFormatRounded(shown)} ± ${dwFormatRounded(doubt)} ${measurement.unit}",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "±${String.format(Locale.ROOT, "%.1f", measurement.relativeUncertainty * 100)}% (reference ${measurement.referencePixels.roundToInt()} px, object ${measurement.targetPixels.roundToInt()} px). Assumes the reference length is correct.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )

        measurement.tiltCorrection?.let { tilt ->
            Text(
                buildString {
                    append("Tilt correction changed this by ")
                    append(String.format(Locale.ROOT, "%.1f", tilt * 100))
                    append("%")
                    measurement.uncorrectedValue?.let { uncorrected ->
                        append(" — two marks alone would have read ")
                        append(String.format(Locale.ROOT, "%.1f", uncorrected))
                        append(" ")
                        append(measurement.unit)
                    }
                    append(". ")
                    append(
                        if (tilt < 0.01) {
                            "Small — two marks would have done."
                        } else {
                            "The four corners were worth it."
                        }
                    )
                },
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
            )
        }

        DwPanelLabel("Propose this into")
        targets.forEach { target ->
            val converted = DwPhotoMeasure.convertLength(measurement.value, measurement.unit, target.unit)
            val convertedDoubt =
                DwPhotoMeasure.convertLength(measurement.uncertainty, measurement.unit, target.unit)
            if (converted == null || convertedDoubt == null) {
                Text(
                    "${target.field.label} uses ${target.unit}, which can't be converted.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                return@forEach
            }
            val proposal = DwPhotoMeasure.roundToUncertainty(converted, convertedDoubt)
            val text = dwFormatRounded(proposal)
            val current = DwValues.text(rowValues[target.field.key])
            ActionRow {
                Button(
                    onClick = { onPropose(target, text) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${target.field.label}: $text ${target.unit}", style = MaterialTheme.typography.labelLarge)
                }
            }
            dwPanelReplaceWarning(current, DwPanelHolds.VALUE)?.let {
                Text(
                    it,
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                )
            }
        }

        proposeError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "Rounded to the precision of its error bar.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
    }
}

@Composable
internal fun DwMarkHandle(
    key: Any,
    badge: String,
    name: String,
    placed: Boolean,
    outlined: Boolean,
    enabled: Boolean,
    isActive: Boolean,
    markColor: Color,
    discColor: Color,
    positionInView: () -> Offset,
    onSelect: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset {
                val view = positionInView()
                IntOffset((view.x - 24.dp.toPx()).roundToInt(), (view.y - 24.dp.toPx()).roundToInt())
            }
            .size(48.dp)
            .semantics {
                contentDescription = name +
                    (if (placed) "" else ", not placed yet") +
                    ". Drag it, or use the nudge arrows."
            }
            .pointerInput(key, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { onSelect() }
            }
            .pointerInput(key, enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                )
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(if (isActive) 30.dp else 26.dp)
                .background(if (outlined) discColor else markColor, CircleShape)
                .border(
                    BorderStroke(if (isActive) 3.dp else 2.dp, if (outlined) markColor else discColor),
                    CircleShape,
                ),
        ) {
            Text(
                badge,
                color = if (outlined) markColor else discColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun DwNudgePad(
    active: DwMarkId,
    enabled: Boolean,
    displayScale: Float,
    onNudge: (DwPoint) -> Unit,
) {
    val step = if (displayScale > 0f) max(1.0, (4.0 / displayScale).roundToInt().toDouble()) else 1.0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Nudge ${MARK_BADGE.getValue(active)}",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(4.dp))
        DwNudgeButton(Icons.Filled.KeyboardArrowLeft, "Nudge ${MARK_BADGE.getValue(active)} left", enabled) {
            onNudge(DwPoint(-step, 0.0))
        }
        DwNudgeButton(Icons.Filled.KeyboardArrowRight, "Nudge ${MARK_BADGE.getValue(active)} right", enabled) {
            onNudge(DwPoint(step, 0.0))
        }
        DwNudgeButton(Icons.Filled.KeyboardArrowUp, "Nudge ${MARK_BADGE.getValue(active)} up", enabled) {
            onNudge(DwPoint(0.0, -step))
        }
        DwNudgeButton(Icons.Filled.KeyboardArrowDown, "Nudge ${MARK_BADGE.getValue(active)} down", enabled) {
            onNudge(DwPoint(0.0, step))
        }
    }
}

@Composable
internal fun DwNudgeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun DwZoomButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp)),
    ) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun DwPanelLabel(text: String) {
    Text(text, color = MaterialTheme.field.muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
}

@Composable
internal fun DwPanelChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    isChoice: Boolean = true,
    onClick: () -> Unit,
) {
    val announce = if (isChoice) {
        Modifier.semantics { stateDescription = if (selected) "Selected" else "Not selected" }
    } else {
        Modifier
    }
    val shape = Modifier
        .heightIn(min = 40.dp)
        .then(announce)
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = shape) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = shape) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun DwPanelNote(warning: Boolean, text: String, polite: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (warning) MaterialTheme.field.warningContainer else MaterialTheme.field.surface50,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (warning) MaterialTheme.field.warning else MaterialTheme.field.hairline,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp)
            .then(if (polite) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text,
            color = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.field.body,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 16.sp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DwUnitChips(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DwPhotoMeasure.LENGTH_UNITS.keys.forEach { unit ->
            DwPanelChip(
                label = unit,
                selected = unit == selected,
                enabled = enabled,
                onClick = { onSelect(unit) },
            )
        }
    }
}

private fun dwTrimNumber(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else String.format(Locale.ROOT, "%s", value)
