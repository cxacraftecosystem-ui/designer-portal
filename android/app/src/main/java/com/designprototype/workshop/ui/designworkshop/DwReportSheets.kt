package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.ui.ActionRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.report.Block
import com.designprototype.workshop.report.ChartBlock
import com.designprototype.workshop.report.MapBlock
import com.designprototype.workshop.report.ReportDocument
import com.designprototype.workshop.ui.FieldLightColorScheme
import com.designprototype.workshop.ui.LightFieldTokens
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.LocalFieldTokens
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

private const val DW_HEAD_CLEARANCE_MM = 6f

private const val DW_FOOT_RESERVE_MM = 10f

private val DW_FURNITURE_STYLE: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal)

private val DW_SHEET_BLOCK_GAP = 10.dp

private val DW_PAPER = Color(0xFFFFFFFF)

internal val LocalDwFlowProbe = staticCompositionLocalOf<DwFlowProbe?> { null }

internal class DwFlowProbe {
    private val blockTopPx = HashMap<Int, Float>()
    private val blockHeightPx = HashMap<Int, Float>()
    private val stopsPx = HashMap<Int, HashMap<Int, Float>>()
    private val textTopPx = HashMap<Int, Float>()
    private val textLinesPx = HashMap<Int, List<Float>>()
    private val headerBottomPx = HashMap<Int, Float>()

    fun blockBox(key: Int, topPx: Float, heightPx: Float) {
        blockTopPx[key] = topPx
        blockHeightPx[key] = heightPx
    }

    fun stop(key: Int, ordinal: Int, bottomPx: Float) {
        stopsPx.getOrPut(key) { HashMap() }[ordinal] = bottomPx
    }

    fun textTop(key: Int, topPx: Float) {
        textTopPx[key] = topPx
    }

    fun textLines(key: Int, result: TextLayoutResult) {
        textLinesPx[key] = (0 until result.lineCount).map { result.getLineBottom(it) }
    }

    fun headerBottom(key: Int, bottomPx: Float) {
        headerBottomPx[key] = bottomPx
    }

    fun harvest(density: Density, blockCount: Int): DwFlowMeasurement? {
        if (blockCount == 0) return DwFlowMeasurement(emptyMap(), emptyMap(), emptyMap())
        if (blockHeightPx.size < blockCount) return null
        val d = density.density
        if (d <= 0f) return null

        val heights = HashMap<Int, Float>(blockCount)
        val offsets = HashMap<Int, List<Float>>()
        val headers = HashMap<Int, Float>()

        for (key in 0 until blockCount) {
            val top = blockTopPx[key] ?: return null
            heights[key] = (blockHeightPx[key] ?: return null) / d

            val cuts = ArrayList<Float>()
            stopsPx[key]?.let { byOrdinal ->
                for (ordinal in byOrdinal.keys.sorted()) cuts.add((byOrdinal.getValue(ordinal) - top) / d)
            }
            val textTop = textTopPx[key]
            val lines = textLinesPx[key]
            if (textTop != null && lines != null) {
                for (bottom in lines) cuts.add((textTop - top + bottom) / d)
            }
            if (cuts.isNotEmpty()) offsets[key] = cuts.sorted()

            headerBottomPx[key]?.let { headers[key] = (it - top) / d }
        }

        return DwFlowMeasurement(heights, offsets, headers)
    }
}

internal data class DwFlowMeasurement(
    val heightPx: Map<Int, Float>,
    val offsetsPx: Map<Int, List<Float>>,
    val headerPx: Map<Int, Float>,
)

internal fun Modifier.dwBreakStop(probe: DwFlowProbe?, key: Int, ordinal: Int): Modifier =
    if (probe == null) {
        this
    } else {
        this.onGloballyPositioned { probe.stop(key, ordinal, it.positionInRoot().y + it.size.height) }
    }

internal fun Modifier.dwTextTop(probe: DwFlowProbe?, key: Int): Modifier =
    if (probe == null) this else this.onGloballyPositioned { probe.textTop(key, it.positionInRoot().y) }

internal fun Modifier.dwHeaderBottom(probe: DwFlowProbe?, key: Int): Modifier =
    if (probe == null) {
        this
    } else {
        this.onGloballyPositioned { probe.headerBottom(key, it.positionInRoot().y + it.size.height) }
    }

@Composable
private fun DwFlowMeasureHost(
    document: ReportDocument,
    accent: Color?,
    resolveImage: (String) -> File?,
    probe: DwFlowProbe,
    textWidthPx: Float,
) {
    CompositionLocalProvider(LocalDwFlowProbe provides probe) {
        Layout(
            content = {
                document.blocks.forEachIndexed { index, block ->
                    Box(
                        modifier = Modifier.onGloballyPositioned {
                            probe.blockBox(index, it.positionInRoot().y, it.size.height.toFloat())
                        }
                    ) {
                        DwReportBlock(block = block, accent = accent, resolveImage = resolveImage, key = index)
                    }
                }
            },
            modifier = Modifier.size(0.dp).clipToBounds(),
        ) { measurables, _ ->
            val width = textWidthPx.roundToInt().coerceAtLeast(1)
            val placeables = measurables.map {
                it.measure(
                    Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = Constraints.Infinity)
                )
            }
            layout(0, 0) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.place(0, y)
                    y += placeable.height
                }
            }
        }
    }
}

@Composable
private fun rememberDwFlowMeasurement(
    probe: DwFlowProbe,
    blockCount: Int,
    paperDensity: Density,
): DwFlowMeasurement? {
    var measurement by remember(probe) { mutableStateOf<DwFlowMeasurement?>(null) }

    LaunchedEffect(probe, blockCount, paperDensity) {
        var settled = 0
        while (true) {
            if (settled < 12) withFrameNanos { } else delay(300)
            val now = probe.harvest(paperDensity, blockCount)
            if (now != null && now != measurement) {
                measurement = now
                settled = 0
            } else {
                settled += 1
            }
        }
    }
    return measurement
}

@Composable
internal fun DwReportSheets(
    document: ReportDocument,
    resolveImage: (String) -> File?,
    modifier: Modifier = Modifier,
    focusStageTitle: String? = null,
) {
    val geometry = remember(document.meta.pageSize, document.meta.marginMm) {
        dwPageGeometry(document.meta.pageSize, document.meta.marginMm)
    }

    val probe = remember(document) { DwFlowProbe() }
    val accent = remember(document.theme.accent) { dwAccentColor(document.theme.accent) }
    val deviceDensity = LocalDensity.current

    val paperDensity = remember(deviceDensity.density) { Density(deviceDensity.density, 1f) }

    val stillness = LocalAppPreferences.current.reducedMotion
    val chrome = MaterialTheme.field.muted

    val measurement = rememberDwFlowMeasurement(probe, document.blocks.size, paperDensity)

    val packed = remember(measurement, geometry, document.blocks) {
        measurement?.let { measured ->
            val items = dwPlanFlow(
                blocks = document.blocks,
                gapPx = DW_SHEET_BLOCK_GAP.value,
                heightOf = { measured.heightPx[it] },
                offsetsOf = { measured.offsetsPx[it].orEmpty() },
                headerOf = { measured.headerPx[it] ?: 0f },
            )
            packPages(
                items,
                PageBudget(
                    contentPx = geometry.textHeightPx -
                        (DW_HEAD_CLEARANCE_MM + DW_FOOT_RESERVE_MM) * DW_PX_PER_MM,
                    coverContentPx = geometry.textHeightPx - DW_FOOT_RESERVE_MM * DW_PX_PER_MM,
                )
            )
        }
    }

    var shown by remember { mutableStateOf<Pair<ReportDocument, PackedDocument>?>(null) }
    if (packed != null && shown?.first !== document) {
        SideEffect { shown = document to packed }
    }
    val drawDocument = if (packed != null) document else shown?.first ?: document
    val drawPacked = packed ?: shown?.second
    val focusPages = remember(drawPacked, drawDocument, focusStageTitle) {
        val pages = drawPacked?.pages.orEmpty()
        val range = focusStageTitle?.let { stageBlockRange(drawDocument.blocks, it) }
        if (range == null) pages else pages.filter { page -> page.slices.any { it.blockIndex in range } }.ifEmpty { pages }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (drawPacked != null && focusPages.size < drawPacked.pages.size) {
            Text(
                (if (focusPages.size == 1) "Page ${focusPages.first().pageNumber}" else "Pages ${focusPages.first().pageNumber}–${focusPages.last().pageNumber}") +
                    " of ${drawPacked.pages.size}",
                color = chrome,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 15.sp,
            )
        }
        DwSheetStrip(
            packed = drawPacked,
            geometry = geometry,
            document = drawDocument,
            fontScaleOverridden = deviceDensity.fontScale != 1f,
            color = chrome,
        )

        if (drawPacked == null) {
            Text(
                "Laying the document out onto pages…",
                color = chrome,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            DwSheetStage(
                document = drawDocument,
                packed = drawPacked,
                pages = focusPages,
                geometry = geometry,
                accent = accent,
                resolveImage = resolveImage,
                paperDensity = paperDensity,
                stillness = stillness,
                chromeColor = chrome,
            )
        }

        CompositionLocalProvider(LocalDensity provides paperDensity) {
            DwPaperPalette {
                DwFlowMeasureHost(
                    document = document,
                    accent = accent,
                    resolveImage = resolveImage,
                    probe = probe,
                    textWidthPx = with(paperDensity) { geometry.textWidthPx.dp.toPx() },
                )
            }
        }
    }
}

@Composable
private fun DwPaperPalette(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFieldTokens provides LightFieldTokens) {
        MaterialTheme(
            colorScheme = FieldLightColorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

internal fun dwPageLabel(pageNumber: Int, total: Int): String = "Page $pageNumber of $total"

@Composable
private fun DwSheetStrip(
    packed: PackedDocument?,
    geometry: DwPageGeometry,
    document: ReportDocument,
    fontScaleOverridden: Boolean,
    color: Color,
) {
    val total = packed?.pages?.size ?: 0
    val scaledPages = remember(packed) {
        packed?.scaled?.map { it.pageNumber }?.distinct()?.sorted().orEmpty()
    }
    val figures = remember(document.blocks) {
        document.blocks.count { it is ChartBlock || it is MapBlock }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            if (packed == null) {
                "Measuring pages… · ${geometry.label} · ${geometry.marginLabel}"
            } else {
                "$total page${if (total == 1) "" else "s"} · ${geometry.label} · ${geometry.marginLabel}"
            },
            color = color,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 15.sp,
        )

        if (packed != null) {
            Text(
                "Page counts are close estimates; Word and the .pdf may break lines differently.",
                color = color,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 15.sp,
            )
        }

        if (fontScaleOverridden) {
            Text(
                "Shown at the document's own text size. Pinch or tap 100% to read.",
                color = color,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 15.sp,
            )
        }

        if (figures > 0) {
            Text(
                "· $figures figure${if (figures == 1) "" else "s"} shown as a placeholder, so breaks may shift.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 15.sp,
            )
        }

        if (scaledPages.isNotEmpty()) {
            val count = packed?.scaled?.size ?: 0
            Text(
                "· $count tall block${if (count == 1) "" else "s"} scaled to fit (page${if (scaledPages.size == 1) " " else "s "}${scaledPages.joinToString(", ")}).",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 15.sp,
            )
        }

        val abandoned = packed?.abandoned.orEmpty()
        if (abandoned.isNotEmpty()) {
            Text(
                "· ${abandoned.size} block${if (abandoned.size == 1) "" else "s"} couldn't be laid out. Export to see everything.",
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 15.sp,
            )
        }

        Text(
            "In this report's accent colour.",
            color = color,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 15.sp,
        )
    }
}

internal const val DW_ZOOM_STEP = 0.15f

internal const val DW_ZOOM_MAX = 3f

@Composable
private fun DwZoomTool(label: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

internal const val DW_FIT_MIN = 0.05f

internal fun dwFitScale(availableWidth: Float, pageWidth: Float): Float {
    if (!availableWidth.isFinite() || !(pageWidth > 0f)) return 1f
    return (availableWidth / pageWidth).coerceIn(DW_FIT_MIN, 1f)
}

internal fun dwZoomTarget(chosen: Float?, fit: Float): Float {
    val requested = chosen?.takeIf { it.isFinite() && it > 0f } ?: fit
    return requested.coerceIn(fit * 0.5f, DW_ZOOM_MAX)
}

internal fun dwScaledExtent(referencePx: Int, scale: Float, minPx: Int = 0, maxPx: Int = Int.MAX_VALUE): Int =
    (referencePx * scale).roundToInt().coerceIn(minPx, maxPx)

internal fun Modifier.dwScaledPage(referenceWidth: Dp, referenceHeight: Dp, scale: Float): Modifier =
    layout { measurable, constraints ->
        val width = referenceWidth.roundToPx().coerceAtLeast(1)
        val height = referenceHeight.roundToPx().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(
            dwScaledExtent(width, scale, constraints.minWidth, constraints.maxWidth),
            dwScaledExtent(height, scale, constraints.minHeight, constraints.maxHeight),
        ) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }

@Composable
private fun DwSheetStage(
    document: ReportDocument,
    packed: PackedDocument,
    pages: List<PackedPage>,
    geometry: DwPageGeometry,
    accent: Color?,
    resolveImage: (String) -> File?,
    paperDensity: Density,
    stillness: Boolean,
    chromeColor: Color,
) {
    var chosenScale by remember { mutableStateOf<Float?>(null) }
    var animate by remember { mutableStateOf(false) }
    val hScroll = rememberScrollState()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = maxWidth
        val fit = dwFitScale(available.value, geometry.pageWidthPx)
        val target = dwZoomTarget(chosenScale, fit)

        val scale by animateFloatAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = if (stillness || !animate) 0 else 200),
            label = "report-sheet-zoom",
        )

        val pageWidth = (geometry.pageWidthPx * scale).dp
        val pageHeight = (geometry.pageHeightPx * scale).dp
        val overflows = pageWidth > available

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionRow(toolbar = true, spacing = 4.dp) {
                DwZoomTool("Smaller", "Smaller", Icons.Filled.ZoomOut) {
                    animate = true
                    chosenScale = dwZoomTarget(target - DW_ZOOM_STEP, fit)
                }
                DwZoomTool("Larger", "Larger", Icons.Filled.ZoomIn) {
                    animate = true
                    chosenScale = dwZoomTarget(target + DW_ZOOM_STEP, fit)
                }
                DwZoomTool("Fit", "Fit width", Icons.Filled.FitScreen) { animate = true; chosenScale = null }
                DwZoomTool("100%", "Actual size", Icons.Filled.CropFree) { animate = true; chosenScale = 1f }
            }

            if (scale < 0.995f || scale > 1.005f) {
                Text(
                    "Shown at ${(scale * 100).roundToInt()}%." +
                        if (overflows) " Drag sideways to see the rest of the sheet." else "",
                    color = chromeColor,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 15.sp,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .then(if (overflows) Modifier.horizontalScroll(hScroll) else Modifier.fillMaxWidth())
                    .pointerInput(fit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var event = awaitPointerEvent()
                            while (event.changes.any { it.pressed }) {
                                if (event.changes.count { it.pressed } >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    if (zoomChange > 0f && zoomChange != 1f) {
                                        animate = false
                                        chosenScale = dwZoomTarget((chosenScale ?: fit) * zoomChange, fit)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                event = awaitPointerEvent()
                            }
                        }
                    },
            ) {
                CompositionLocalProvider(LocalDensity provides paperDensity) {
                    DwPaperPalette {
                        pages.forEach { page ->
                            DwSheet(
                                document = document,
                                page = page,
                                total = packed.pages.size,
                                geometry = geometry,
                                scale = scale,
                                pageWidth = pageWidth,
                                pageHeight = pageHeight,
                                accent = accent,
                                resolveImage = resolveImage,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DwSheet(
    document: ReportDocument,
    page: PackedPage,
    total: Int,
    geometry: DwPageGeometry,
    scale: Float,
    pageWidth: Dp,
    pageHeight: Dp,
    accent: Color?,
    resolveImage: (String) -> File?,
) {
    val marginDp = (geometry.marginMm * DW_PX_PER_MM).dp
    val furniture = page.pageNumber > 1

    Box(
        modifier = Modifier
            .size(width = pageWidth, height = pageHeight)
            .background(DW_PAPER, RoundedCornerShape(2.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(2.dp))
            .clipToBounds()
            .semantics {
                contentDescription =
                    if (page.isCover) "Cover page" else dwPageLabel(page.pageNumber, total)
            },
    ) {
        Box(
            modifier = Modifier
                .dwScaledPage(geometry.pageWidthPx.dp, geometry.pageHeightPx.dp, scale)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = marginDp)) {
                Box(modifier = Modifier.fillMaxWidth().height(marginDp)) {
                    if (furniture) DwSheetHead(document)
                }

                if (!page.isCover) Spacer(Modifier.height((DW_HEAD_CLEARANCE_MM * DW_PX_PER_MM).dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(DW_SHEET_BLOCK_GAP)) {
                        page.slices.forEach { slice ->
                            DwSheetSlice(
                                block = document.blocks[slice.blockIndex],
                                slice = slice,
                                accent = accent,
                                resolveImage = resolveImage,
                            )
                        }
                    }
                }

                Spacer(Modifier.height((DW_FOOT_RESERVE_MM * DW_PX_PER_MM).dp))

                Box(modifier = Modifier.fillMaxWidth().height(marginDp)) {
                    if (furniture) DwSheetFoot(document, dwPageLabel(page.pageNumber, total))
                }
            }
        }
    }
}

@Composable
private fun DwSheetSlice(
    block: Block,
    slice: PageSlice,
    accent: Color?,
    resolveImage: (String) -> File?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (slice.repeatHeaderPx > 0f) {
            DwClippedBlock(
                block = block,
                offsetPx = 0f,
                heightPx = slice.repeatHeaderPx,
                scale = 1f,
                accent = accent,
                resolveImage = resolveImage,
                key = slice.blockIndex,
            )
        }
        DwClippedBlock(
            block = block,
            offsetPx = slice.offsetPx,
            heightPx = slice.heightPx,
            scale = slice.scale,
            accent = accent,
            resolveImage = resolveImage,
            key = slice.blockIndex,
        )
    }
}

@Composable
private fun DwClippedBlock(
    block: Block,
    offsetPx: Float,
    heightPx: Float,
    scale: Float,
    accent: Color?,
    resolveImage: (String) -> File?,
    key: Int,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((heightPx * scale).dp)
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            Box(
                modifier = Modifier
                    .offset(y = -offsetPx.dp)
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            ) {
                DwReportBlock(block = block, accent = accent, resolveImage = resolveImage, key = key)
            }
        }
    }
}

@Composable
private fun DwSheetHead(document: ReportDocument) {
    if (document.meta.headerText.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = (2.6f * DW_PX_PER_MM).dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            document.meta.headerText,
            color = MaterialTheme.field.muted,
            style = DW_FURNITURE_STYLE,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height((1.4f * DW_PX_PER_MM).dp))
        HorizontalDivider(color = MaterialTheme.field.hairline, thickness = 0.5.dp)
    }
}

@Composable
private fun DwSheetFoot(document: ReportDocument, label: String) {
    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalDivider(color = MaterialTheme.field.hairline, thickness = 0.5.dp)
        Spacer(Modifier.height((2.6f * DW_PX_PER_MM).dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                document.meta.footerText,
                color = MaterialTheme.field.muted,
                style = DW_FURNITURE_STYLE,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (document.meta.showPageNumbers) {
                Text(
                    label,
                    color = MaterialTheme.field.muted,
                    style = DW_FURNITURE_STYLE,
                )
            }
        }
    }
}

