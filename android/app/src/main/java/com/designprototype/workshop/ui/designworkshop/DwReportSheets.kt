package com.designprototype.workshop.ui.designworkshop

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
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
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

/**
 * The report drawn as A4 SHEETS on the handset — the same document a laptop shows, at phone size.
 *
 * Asked for on 2026-08-27: *"The preview of rendered report on the web UI is absolutely shit, it
 * should render as if on actual a4 sheet, and in addition to it, it should also be there on
 * android."*
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS IS NOT: A SECOND BLOCK RENDERER, AND NOT A SECOND PAGINATOR EITHER
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * There are four renderers of this document — `backend/app/services/report_docx.py`,
 * `report_pdf.py`, and this device's own `DocxWriter.kt` and `PdfWriter.kt` — and a ministry receives
 * what they produce, so they must agree line for line. This file adds neither a fifth renderer nor a
 * second layout rule:
 *
 *  · EVERY BLOCK IS DRAWN BY `DwReportBlock` IN [DwReportPreview]. Not a copy of it, not a
 *    "page-shaped variant" — the same composable, called with the same arguments. Nothing here
 *    decides what a block SAYS; it decides only where the paper runs out.
 *  · THE PAGINATION IS A PORT of `frontend/components/designworkshop/report/reportPagination.ts`,
 *    living in `DwReportPagination.kt` as `packPages`, and `DwReportPaginationTest` runs the SAME
 *    cases as `frontend/e2e/report-pagination-unit.spec.ts` so the two clients cannot drift apart in
 *    silence. Two previews that disagreed about where a page ends would be a fifth and a sixth
 *    opinion about one document: a designer approving the layout on a laptop and a colleague
 *    checking it on a handset would each be looking at something that is not the file.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE OBJECTION THIS SUPERSEDES, CORRECTED RATHER THAN DELETED
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [DwReportPreview]'s header argued that "an A4 sheet rendered to fit is 4pt type, and rendered at
 * readable type it needs horizontal scrolling, which is a gesture nobody can aim on a phone while
 * reading". Both halves are TRUE and neither is a reason to have no page view — together they are a
 * specification for one:
 *
 *  · fit-to-width IS about 4pt type at 360dp, so the sheet ships with a zoom control and a pinch,
 *    and "Actual size" is one tap away;
 *  · at actual size the sheet IS wider than the screen, so the stage scrolls sideways ONLY once the
 *    reader has zoomed past the fit — never at rest, and never as a surprise under a finger that
 *    meant to scroll the document.
 *
 * The reading view stays, because the two answer different questions: the flow answers "does this
 * prose read correctly", and the sheets answer "does it fit on the page, and where does the break
 * fall".
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY A SHEET HERE IS THE SAME DOCUMENT AS A SHEET ON A LAPTOP
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Everything inside a page is laid out at REFERENCE SIZE — an A4 page 793.7 units wide, from
 * `dwPageGeometry` at [DW_PX_PER_MM] — and the whole page is then scaled by ONE factor to whatever
 * width the phone has. Nothing inside the page knows how big the phone is. That is what makes the
 * line breaks, the column widths and the page breaks the same on both clients rather than merely
 * similar: a 360dp handset draws the layout a 1440px browser draws, at 0.43 of the size.
 *
 * The alternative — laying the blocks out at phone width and drawing a page-shaped border round them
 * — looks like a page and is not one. That is the defect this whole change exists to end on the web,
 * where a sheet was `min-height: A4` and grew past the paper the moment real content arrived, so the
 * screen whose entire job is to answer "does this fit on the page?" answered it by making the page
 * bigger.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE PAGE GEOMETRY IS `PdfWriter.kt`'s OWN, TO THE MILLIMETRE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `PdfWriter.kt:196-197` sets `top = pageH - margin` and `bottom = margin + 10 * MM` — "room for the
 * running foot" — and `:446` starts every page after the first at `top - 6 * MM`, the clearance under
 * the running head. The running head and foot are then drawn INTO THE MARGIN and never into the text
 * column, so they move nothing. [DW_HEAD_CLEARANCE_MM] and [DW_FOOT_RESERVE_MM] are those two
 * numbers, and the content box below is `top - 6 mm - bottom` for an ordinary page and `top - bottom`
 * for the cover, which is exactly what the writer reserves.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * HOW THE MEASUREMENT WORKS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * [DwFlowMeasureHost] renders the WHOLE document once, off-screen, into a zero-sized clipped
 * [Layout] at exactly the text-column width, and reads back each block's height and each block's
 * LEGAL CUT POINTS — table rows, list items, key/value pairs and grid rows through
 * [Modifier.dwBreakStop]; paragraph and callout LINE BOXES through [Modifier.dwTextTop] plus
 * `TextLayoutResult.getLineBottom`; and a table's header height through [Modifier.dwHeaderBottom], so
 * a continuation page can RESERVE it as `place_row` does.
 *
 * A divided block is drawn as a CLIPPED WINDOW onto the same laid-out box — the slice's height,
 * `clipToBounds`, pushed up by the slice's offset — so a continuation is the same line boxes the
 * first half was measured against rather than a fresh wrap at a different width.
 */

// --------------------------------------------------------------------------------------
// The page, as the writers reserve it
// --------------------------------------------------------------------------------------

/** `PdfWriter.kt:446` — `if (pageNo > 1) y = top - 6 * MM`, the clearance under the running head. */
private const val DW_HEAD_CLEARANCE_MM = 6f

/** `PdfWriter.kt:197` — `bottom = margin + 10 * MM`, "room for the running foot". */
private const val DW_FOOT_RESERVE_MM = 10f

/**
 * The running head and foot are set at 7.8 pt against a 10.5 pt body in `PdfWriter.drawFurniture`.
 * This preview's body is 13 sp (`DwReportBlock`'s PARAGRAPH arm), so the furniture is
 * 13 × 7.8 ÷ 10.5 = 9.66 sp. Rounded to one decimal rather than to a round number, because the
 * running foot's width is what decides whether the page label and the footer text share a line.
 */
private val DW_FURNITURE_SIZE = 9.7.sp

/** The space between two blocks in the flow. Used to LAY OUT and to PLAN, so the two cannot drift. */
private val DW_SHEET_BLOCK_GAP = 10.dp

/**
 * Paper.
 *
 * A LITERAL WHITE, and the one place in this app where a neutral does not go through the ink / line /
 * surface tokens. Those invert with the theme, which is right for every surface a reader looks AT and
 * wrong for the one surface a reader looks THROUGH: this rectangle is a picture of a sheet of paper
 * that is going to be printed, and paper does not invert. [DwPaperPalette] moves the ink with it, so
 * the two halves of the decision cannot come apart.
 */
private val DW_PAPER = Color(0xFFFFFFFF)

// --------------------------------------------------------------------------------------
// The probe — how a block tells the paginator where it may be cut
// --------------------------------------------------------------------------------------

/**
 * Set only while [DwFlowMeasureHost] is composing the off-screen flow; null everywhere else.
 *
 * `DwReportBlock` reads it and, when it is non-null, attaches position probes to its repeating
 * children. WITH NO PROBE THE BLOCKS COMPOSE EXACTLY AS THEY DID BEFORE — [DwReportPreview]'s reading
 * view provides none and is unchanged — which is what keeps this a measurement bolted onto one
 * renderer rather than a second renderer that measures.
 */
internal val LocalDwFlowProbe = staticCompositionLocalOf<DwFlowProbe?> { null }

/**
 * Where every block, and every place a block may be cut, ended up in the off-screen flow.
 *
 * PLAIN MUTABLE MAPS, DELIBERATELY NOT SNAPSHOT STATE. Positions arrive from `onGloballyPositioned`,
 * which runs during layout; writing snapshot state there and reading it in composition works, but it
 * fires on every scroll of the parent too, because `positionInRoot` moves when an ancestor scrolls.
 * The values here are ABSOLUTE and are only ever read as DIFFERENCES ([harvest] subtracts each
 * block's own top), so a scroll changes every number and changes no answer — and the poll in
 * [rememberDwFlowMeasurement] sees that nothing changed and recomposes nothing.
 *
 * EVERY SLOT IS KEYED, so a second layout pass OVERWRITES rather than accumulating. An append-only
 * collector would keep the offsets from before a photograph decoded and cut the page at a row that
 * had since moved.
 */
internal class DwFlowProbe {
    private val blockTopPx = HashMap<Int, Float>()
    private val blockHeightPx = HashMap<Int, Float>()
    /** key → ordinal of the child within the block → that child's bottom, absolute. */
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

    /**
     * The measurement, in reference units, or null while it is still incomplete.
     *
     * NULL RATHER THAN A PARTIAL ANSWER. A document paginated from half a measurement shows a page
     * count that shuffles as the last photograph decodes, and a reader who is shown "12 pages" and
     * then "14" has been told a number twice. Nothing is drawn until every block has a height.
     */
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
                // A line box is measured inside its text; the text sits somewhere inside the block.
                // Both are needed — a callout, whose body is one text under a title, is exactly why
                // the two are recorded separately rather than assumed to coincide.
                for (bottom in lines) cuts.add((textTop - top + bottom) / d)
            }
            if (cuts.isNotEmpty()) offsets[key] = cuts.sorted()

            headerBottomPx[key]?.let { headers[key] = (it - top) / d }
        }

        return DwFlowMeasurement(heights, offsets, headers)
    }
}

/** Everything the packer needs about this document on this device, in reference units. */
internal data class DwFlowMeasurement(
    val heightPx: Map<Int, Float>,
    val offsetsPx: Map<Int, List<Float>>,
    val headerPx: Map<Int, Float>,
)

/**
 * Marks one repeating child of a block as a legal place to cut — a table row, a list item, a
 * key/value pair, a grid row.
 *
 * [ordinal] is the child's index within the block and is what makes the slot STABLE: the same row
 * always writes to the same slot, so a second layout pass corrects its position instead of leaving a
 * stale offset beside the new one.
 */
internal fun Modifier.dwBreakStop(probe: DwFlowProbe?, key: Int, ordinal: Int): Modifier =
    if (probe == null) {
        this
    } else {
        this.onGloballyPositioned { probe.stop(key, ordinal, it.positionInRoot().y + it.size.height) }
    }

/** Records where a divisible text starts, so its line boxes can be placed inside the block. */
internal fun Modifier.dwTextTop(probe: DwFlowProbe?, key: Int): Modifier =
    if (probe == null) this else this.onGloballyPositioned { probe.textTop(key, it.positionInRoot().y) }

/** Records the bottom of the header a table's continuation page redraws, as `place_row` does. */
internal fun Modifier.dwHeaderBottom(probe: DwFlowProbe?, key: Int): Modifier =
    if (probe == null) {
        this
    } else {
        this.onGloballyPositioned { probe.headerBottom(key, it.positionInRoot().y + it.size.height) }
    }

// --------------------------------------------------------------------------------------
// The off-screen flow
// --------------------------------------------------------------------------------------

/**
 * Renders the whole document once, at exactly the text-column width, and shows none of it.
 *
 * ZERO-SIZED AND CLIPPED, rather than `alpha(0f)`. The children must be MEASURED at the real column
 * width and PLACED — `onGloballyPositioned` only fires for a node that was placed — while the host
 * itself reports no size at all, so it takes no room in the screen's own column and paints nothing. A
 * host that reported its real height would put a second, unpaged copy of the whole document under the
 * sheets.
 */
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

/**
 * Polls the probe until it settles, and again whenever it moves.
 *
 * A POLL RATHER THAN A STATE WRITE FROM LAYOUT, for the reason [DwFlowProbe] gives: positions are
 * absolute, so an ancestor scrolling moves every one of them and changes no answer. Comparing
 * NORMALISED harvests is what tells a real change (a photograph finished decoding) apart from a
 * scroll.
 *
 * It watches every frame while things are still moving and then drops to a check every 300 ms — a map
 * build and an equality test, which is what stops a late-decoding photograph from leaving the page
 * count wrong forever. The cost of the idle poll was chosen over the cost of being silently stale; a
 * preview that is wrong and stays wrong is the one thing this screen may not be.
 */
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

// --------------------------------------------------------------------------------------
// The screen
// --------------------------------------------------------------------------------------

@Composable
internal fun DwReportSheets(
    document: ReportDocument,
    /** The same resolver [DwReportPreview] takes, handed on unchanged to the same block renderer. */
    resolveImage: (String) -> File?,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(document.meta.pageSize, document.meta.marginMm) {
        // THE PAYLOAD'S GEOMETRY, NEVER THIS SCREEN'S. `PdfWriter.kt:190-197` sizes its canvas from
        // exactly `meta.pageSize.sizeMm` and `meta.marginMm`, and `report_pdf.py:509-512` does the
        // same on the server — so a template that asks for Letter at 20 mm is previewed on Letter at
        // 20 mm rather than on the A4 this file would otherwise have assumed.
        dwPageGeometry(document.meta.pageSize, document.meta.marginMm)
    }

    val probe = remember(document) { DwFlowProbe() }
    val accent = remember(document.theme.accent) { dwAccentColor(document.theme.accent) }
    val deviceDensity = LocalDensity.current

    /*
      THE SHEET IS DRAWN AT THE DOCUMENT'S OWN TYPE SIZE, NOT THE READER'S.

      `LocalDensity` carries the phone's font scale, and everywhere else in this app that scale is
      honoured — a designer who has set larger text has set it because they need it. The paper is the
      one exception, and the exception is the point of the screen: at a font scale of 1.3 this would
      paginate a document nobody will ever receive, and would answer "does this fit on the page"
      about a page that does not exist. So the type inside the sheet is pinned and the reader is
      given a ZOOM instead, which magnifies the real document rather than reflowing a different one.
      The chrome around the sheet keeps the reader's own scale, and the strip says so when the two
      differ — a screen that quietly ignores an accessibility setting is worse than one that says why.
     */
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
                    // `top - 6 mm - bottom`, which is what `PdfWriter` gives an ordinary page.
                    contentPx = geometry.textHeightPx -
                        (DW_HEAD_CLEARANCE_MM + DW_FOOT_RESERVE_MM) * DW_PX_PER_MM,
                    // `top - bottom` for page one: the head clearance is applied only when
                    // `pageNo > 1`, and no writer draws furniture on the cover at all.
                    coverContentPx = geometry.textHeightPx - DW_FOOT_RESERVE_MM * DW_PX_PER_MM,
                )
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DwSheetStrip(
            packed = packed,
            geometry = geometry,
            document = document,
            fontScaleOverridden = deviceDensity.fontScale != 1f,
            color = chrome,
        )

        if (packed == null) {
            Text(
                "Laying the document out onto pages…",
                color = chrome,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            DwSheetStage(
                document = document,
                packed = packed,
                geometry = geometry,
                accent = accent,
                resolveImage = resolveImage,
                paperDensity = paperDensity,
                stillness = stillness,
                chromeColor = chrome,
            )
        }

        // The off-screen flow, LAST in the column so that nothing above it can be pushed by a host
        // that is meant to have no size at all — and inside the same density and the same palette the
        // sheets are drawn in, or the measurement would be of a document the sheets never show.
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

/**
 * Paper stays paper, in both themes.
 *
 * A sheet is a picture of something that will be PRINTED, and a preview that inverts to a black page
 * in dark mode is a preview of nothing anybody will ever hold. But the block composables draw their
 * ink from `MaterialTheme.colorScheme` and their panel tints from `MaterialTheme.field`, so a white
 * sheet under the dark palette would be near-white text on white. Both halves have to move together:
 * the sheet is white and the palette inside it is the LIGHT one, which is the palette the file is
 * written in. The rest of the screen — the strip, the zoom control, the app around it — stays in the
 * reader's chosen theme, exactly as the web keeps its chrome themed and its paper paper.
 */
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

/** "Page 3 of 12" — the label all four FILE renderers print in their own running foot. */
internal fun dwPageLabel(pageNumber: Int, total: Int): String = "Page $pageNumber of $total"

// --------------------------------------------------------------------------------------
// What the reader is being shown, said above the paper
// --------------------------------------------------------------------------------------

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
                "Measuring the pages… · ${geometry.label} · ${geometry.marginLabel}"
            } else {
                "$total page${if (total == 1) "" else "s"} · ${geometry.label} · ${geometry.marginLabel}"
            },
            color = color,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )

        /*
          ─ WHAT THE PAGE COUNT IS WORTH, NOW THAT THERE IS ONE ────────────────────────────────

          This screen had NO page count at all until now, and the web's said the count was a FLOOR
          because only the breaks the template declared were honoured. Neither claim survives: the
          blocks are measured and the sheets below are real pages. But they are measured with THIS
          DEVICE's fonts, while the .pdf is laid out by its writer in whichever face it resolved and
          the .docx is paginated by Word when the file is opened. Those disagree about where a line
          wraps, and a document that disagrees about a line can disagree about a page.

          MAKING THE PREVIEW TRUSTWORTHY RAISES THE COST OF OVERCLAIMING RATHER THAN REMOVING IT, so
          the sentence gets more precise rather than going away.

          WORD FOR WORD THE WEB'S. `DwReportPaginationTest` reads both files and fails if the two
          clients start saying different things about the same number — a designer who checks the
          report on a laptop and a colleague who checks it on a handset must be given one caveat, not
          two.
         */
        if (packed != null) {
            Text(
                "Pages are measured here, with this device's own fonts, so a line that wraps differently in Word or in the " +
                    "generated .pdf can move a break: treat the “of $total” in each running foot below as a " +
                    "close estimate of the file's own count rather than as the file's own count.",
                color = color,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        if (fontScaleOverridden) {
            Text(
                "This device is set to a larger text size. The sheets are drawn at the document's own " +
                    "type size, so that they show where the file's pages really break — pinch, or use " +
                    "Actual size, to read them.",
                color = color,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        // WHAT THIS SCREEN DOES NOT DRAW, AND WHAT IT COSTS THE PAGINATION. `DwReportBlock` names a
        // chart or a map rather than redrawing it — see `DwReportPreview`'s header for why a second
        // chart engine on one device is not worth having — and a named card is much shorter than the
        // picture the file puts there, which `PdfWriter.blockFigure` caps at 0.58 of the text column
        // (`PdfWriter.kt:1645`). So the pages AFTER a figure are the ones to check in the file.
        if (figures > 0) {
            Text(
                "· $figures figure${if (figures == 1) "" else "s"} (charts and maps) " +
                    "${if (figures == 1) "is" else "are"} named here rather than drawn, and a named " +
                    "card is shorter than the picture the file puts in its place — up to 0.58 of the " +
                    "text column. Breaks after a figure can therefore fall later here than in the file.",
                color = MaterialTheme.field.warning,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        // Never silent about a block the preview had to shrink. The .pdf caps a photograph at 0.62 of
        // the text column and a chart at 0.58 so neither can be too tall; anything else that overruns
        // a whole page is CUT by the file and scaled here, and a designer approving a figure has to
        // know which of the two they are looking at.
        if (scaledPages.isNotEmpty()) {
            val count = packed?.scaled?.size ?: 0
            Text(
                "· $count block${if (count == 1) "" else "s"} taller than one page " +
                    "${if (count == 1) "is" else "are"} shown scaled down to fit " +
                    "(page${if (scaledPages.size == 1) " " else "s "}${scaledPages.joinToString(", ")}) — " +
                    "nothing is cut off here, and the file divides such a block across pages instead.",
                color = MaterialTheme.field.warning,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        val abandoned = packed?.abandoned.orEmpty()
        if (abandoned.isNotEmpty()) {
            Text(
                "· ${abandoned.size} block${if (abandoned.size == 1) "" else "s"} could not be laid " +
                    "out and ${if (abandoned.size == 1) "is" else "are"} missing from the sheets " +
                    "below. Export the file to see the document in full.",
                color = MaterialTheme.field.warning,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        // The accent here is the DOCUMENT's own — `document.theme.accent`, which is what the writers
        // put in the file — so unlike the web, which has to guess when the template's accent is not
        // on the preview wire, this one is exact and is worth saying.
        Text(
            "Drawn in the accent colour this report will be written in.",
            color = color,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

// --------------------------------------------------------------------------------------
// The stage: the sheets, the zoom, and the one scroll that only exists when it is needed
// --------------------------------------------------------------------------------------

@Composable
private fun DwSheetStage(
    document: ReportDocument,
    packed: PackedDocument,
    geometry: DwPageGeometry,
    accent: Color?,
    resolveImage: (String) -> File?,
    paperDensity: Density,
    stillness: Boolean,
    chromeColor: Color,
) {
    /*
      ONE NUMBER DECIDES HOW BIG THE PAPER IS, whatever set it.

      `null` means "fit the width", which is NOT the same as a scale that happens to equal the fit: a
      rotation or a split-screen resize must re-fit, and a reader who chose 120% must keep 120%.
      Everything else — the two buttons, the pinch — writes an absolute scale into the same slot, so
      there is no second piece of state that could disagree with the first about how big the sheet on
      screen is.
     */
    var chosenScale by remember { mutableStateOf<Float?>(null) }
    // Whether the CURRENT change should animate. A button press should; a pinch must not, or every
    // frame of the gesture chases a tween and the paper lags behind the fingers.
    var animate by remember { mutableStateOf(false) }
    val hScroll = rememberScrollState()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = maxWidth
        val fit = (available / geometry.pageWidthPx.dp).coerceIn(0.05f, 1f)
        // Never scaled UP by the FIT. A designer looking at a 140%-enlarged page would be judging
        // type at a size the paper will never be printed at; enlarging is a choice the reader makes.
        val target = (chosenScale ?: fit).coerceIn(fit * 0.5f, 3f)

        val scale by animateFloatAsState(
            targetValue = target,
            // REDUCED MOTION IS HONOURED IN THE BRANCH THAT ACTUALLY MOVES, not only in a stylesheet:
            // `LocalAppPreferences.current.reducedMotion` collapses the tween to nothing, exactly as
            // `DwSketchTraceCompare` and `MapScreen` read it.
            animationSpec = tween(durationMillis = if (stillness || !animate) 0 else 200),
            label = "report-sheet-zoom",
        )

        val pageWidth = (geometry.pageWidthPx * scale).dp
        val pageHeight = (geometry.pageHeightPx * scale).dp
        val overflows = pageWidth > available

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = {
                        animate = true
                        chosenScale = (target - 0.15f).coerceAtLeast(fit * 0.5f)
                    }
                ) { Text("Smaller", fontSize = 12.sp) }
                TextButton(
                    onClick = {
                        animate = true
                        chosenScale = (target + 0.15f).coerceAtMost(3f)
                    }
                ) { Text("Larger", fontSize = 12.sp) }
                TextButton(onClick = { animate = true; chosenScale = null }) {
                    Text("Fit width", fontSize = 12.sp)
                }
                TextButton(onClick = { animate = true; chosenScale = 1f }) {
                    Text("Actual size", fontSize = 12.sp)
                }
            }

            // Said only when it is true, and in the same register as the web's. At the fit there is
            // nothing to disclose; away from it the reader is judging type at a size the paper will
            // not be printed at, and has to be told which.
            if (scale < 0.995f || scale > 1.005f) {
                Text(
                    "Shown at ${(scale * 100).roundToInt()}% of the paper's size; the file is at 100%." +
                        if (overflows) " Drag sideways to see the rest of the sheet." else "",
                    color = chromeColor,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    /*
                      A HORIZONTAL SCROLL ONLY WHEN THE SHEET IS WIDER THAN THE SCREEN. At rest the
                      page fits and there is nothing to scroll, so the gesture does not exist and
                      cannot fire under a finger that meant to move down the document. It appears once
                      the reader has zoomed past the fit, which is the one moment they asked for it.

                      AND `fillMaxWidth` IS THE OTHER HALF OF THE SAME BRANCH, not an unconditional
                      modifier above it. A scrolling parent measures its child with an UNBOUNDED
                      width, and `fillMaxWidth` under an unbounded constraint falls back to the
                      minimum — zero — so the column would report no width, the scroll range would
                      compute to nothing, and the sheet would sit half off the screen with the drag
                      that is supposed to reach the rest of it doing nothing at all.
                     */
                    .then(if (overflows) Modifier.horizontalScroll(hScroll) else Modifier.fillMaxWidth())
                    /*
                      PINCH, WITHOUT STEALING THE VERTICAL SCROLL. `detectTransformGestures` claims a
                      one-finger drag as a pan the moment it passes touch slop, which on a screen that
                      lives inside a scrolling column means the report stops scrolling. This waits for
                      a SECOND finger and only then consumes — one finger still scrolls the document,
                      two fingers resize the paper.
                     */
                    .pointerInput(fit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var event = awaitPointerEvent()
                            while (event.changes.any { it.pressed }) {
                                if (event.changes.count { it.pressed } >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    if (zoomChange > 0f && zoomChange != 1f) {
                                        animate = false
                                        chosenScale =
                                            ((chosenScale ?: fit) * zoomChange).coerceIn(fit * 0.5f, 3f)
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
                        packed.pages.forEach { page ->
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

// --------------------------------------------------------------------------------------
// One sheet
// --------------------------------------------------------------------------------------

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
    // `drawFurniture` opens with `if (pageNo <= 1) return`. Every template in `ReportTemplates.kt`
    // makes its cover block 0, so page one IS the cover and the two conditions name the same sheet;
    // this follows the writer's own test rather than the preview's idea of what a cover is.
    val furniture = page.pageNumber > 1

    Box(
        modifier = Modifier
            /*
              THE FIXED BOX. This one call is the whole of the visual defect on the web, where the
              sheet was `min-height: A4` and therefore grew past the paper it was drawing. A page is a
              page: it ends where the paper ends, and the `clipToBounds` below is what makes an
              arithmetic error show as a clipped page rather than as a sheet that quietly got taller.
             */
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
                // The page's contents, laid out at REFERENCE size and then scaled by one factor.
                // `requiredSize` and not `size`, because the parent is the SMALLER scaled box and
                // would otherwise squeeze the reference layout into it — at which point the phone's
                // width would be back inside the layout and the sheet would stop being the document.
                .requiredSize(width = geometry.pageWidthPx.dp, height = geometry.pageHeightPx.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = marginDp)) {
                // THE TOP MARGIN BAND. The running head is drawn INTO it and never into the text
                // column — `drawFurniture` stacks its lines upward from `pageH - margin + 4 * MM`,
                // "so nothing here moves `y` and pagination is untouched".
                Box(modifier = Modifier.fillMaxWidth().height(marginDp)) {
                    if (furniture) DwSheetHead(document)
                }

                // `PdfWriter.kt:446` — the clearance under the running head, on every page but the
                // first. The cover starts at `top`, which is why its content box is the taller one.
                if (!page.isCover) Spacer(Modifier.height((DW_HEAD_CLEARANCE_MM * DW_PX_PER_MM).dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // The body IS the content box. It clips, so nothing can push the fixed sheet
                        // open from the inside; the strip says out loud when a block had to be scaled
                        // to fit, so a clip here would be a fault rather than a silent truncation.
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

                // `bottom = margin + 10 * MM`, "room for the running foot".
                Spacer(Modifier.height((DW_FOOT_RESERVE_MM * DW_PX_PER_MM).dp))

                Box(modifier = Modifier.fillMaxWidth().height(marginDp)) {
                    if (furniture) DwSheetFoot(document, dwPageLabel(page.pageNumber, total))
                }
            }
        }
    }
}

/**
 * One block, or one window onto one block.
 *
 * A CLIPPED WINDOW ONTO THE SAME LAID-OUT BOX, never a re-render of "the rest of the block". The
 * second half of a paragraph is the same line boxes the first half was measured against, pushed up by
 * the slice's offset — which is what makes a continuation exact instead of a fresh wrap that happens
 * to look similar.
 */
@Composable
private fun DwSheetSlice(
    block: Block,
    slice: PageSlice,
    accent: Color?,
    resolveImage: (String) -> File?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        /*
          THE REPEATED TABLE HEADER, drawn as a second clipped window onto the SAME table rather than
          as a header-only table of its own. `place_row` calls `place_header()` after `_new_page()`,
          "exactly as Word repeats a `<w:tblHeader/>` row" — and drawing the whole table clipped to
          its header height is what guarantees that the continuation's columns are the identical
          widths, because they are the same table. The cost is one extra copy in the composition per
          continuation page, which is the price of the columns lining up.
         */
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
                    // A block taller than any page is SCALED onto one, which is what the writers
                    // already do to every picture (0.62 / 0.58 / 0.30 caps). Reported on the strip,
                    // never silent — see `DwReportPagination`'s header for why scaling and not a cut.
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            Box(
                modifier = Modifier
                    // THE WINDOW. A layout offset and not a translation, so it is applied inside the
                    // scaled layer above and therefore moves by `offset × scale` — which is what a
                    // window onto a shrunk block has to do. `wrapContentHeight(unbounded)` is what
                    // stops the clip's own height being handed to the block as a maximum, which
                    // would make the block re-lay itself to fit the window: the opposite of a window.
                    .offset(y = -offsetPx.dp)
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            ) {
                DwReportBlock(block = block, accent = accent, resolveImage = resolveImage, key = key)
            }
        }
    }
}

// --------------------------------------------------------------------------------------
// The running furniture — drawn in the MARGIN, exactly as the writers draw it
// --------------------------------------------------------------------------------------

@Composable
private fun DwSheetHead(document: ReportDocument) {
    // `drawFurniture` draws nothing at all when `headerText` is empty — not even the rule. A preview
    // that drew a rule the file will not print would be describing a different page.
    if (document.meta.headerText.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxSize()
            // The rule sits at `pageH - margin + 2.6 * MM`, i.e. 2.6 mm above the top of the text
            // column, with the head's lines stacked above it.
            .padding(bottom = (2.6f * DW_PX_PER_MM).dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            document.meta.headerText,
            color = MaterialTheme.field.muted,
            fontSize = DW_FURNITURE_SIZE,
            lineHeight = DW_FURNITURE_SIZE * 1.3f,
            // `drawLine(line, margin, textW, Align.RIGHT, …)` — the running head is right-aligned.
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
        // The foot's rule is drawn at `cy(footY + 4 * MM)` = `cy(margin)` — exactly on the bottom of
        // the text column — and the text sits 4 mm below it, in the margin.
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
                fontSize = DW_FURNITURE_SIZE,
                lineHeight = DW_FURNITURE_SIZE * 1.3f,
                modifier = Modifier.weight(1f, fill = false),
            )
            // `showPageNumbers` is the template's own switch and the writers obey it; a preview that
            // printed a number the file will not print would be answering a question about another
            // document.
            if (document.meta.showPageNumbers) {
                Text(
                    label,
                    color = MaterialTheme.field.muted,
                    fontSize = DW_FURNITURE_SIZE,
                    lineHeight = DW_FURNITURE_SIZE * 1.3f,
                )
            }
        }
    }
}

