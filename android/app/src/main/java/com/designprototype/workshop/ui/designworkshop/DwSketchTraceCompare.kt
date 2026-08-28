package com.designprototype.workshop.ui.designworkshop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.LocalAppPreferences
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **"DID THE TRACE LOSE THE FAINT CONSTRUCTION LINE I DREW?" — the only question this answers.**
 *
 * ── WHAT THE PORTAL DOES, AND WHY IT IS NOT ENOUGH HERE ───────────────────────────────────────
 *
 * `frontend/components/ui/reveal1.tsx` stacks two images in one frame and clips the BEFORE layer at a
 * dragged position. That is the right primitive and this keeps it: a wipe is the only affordance that
 * puts both pictures in the SAME PIXELS, and side by side on a 360 dp screen halves both.
 *
 * But a wipe can never show either picture whole, and on a laptop that does not matter because the
 * designer can drag to an end and read what is there. On a phone it matters twice: the picture is six
 * inches, and **the finger doing the dragging is sitting on the part of the drawing being compared**.
 * So three things change, and each is a handset answer to a handset problem:
 *
 *  1. **A named-state control above the frame — Drawing · Wipe · Photograph · Difference.** Wipe is
 *     the default and opens exactly as the portal does; the two end states are one tap each and show
 *     their picture whole. This is also the accessible equivalent of the portal's arrow-key handling
 *     (`reveal1.tsx:190-196`), which has no counterpart on a touchscreen — a `role="slider"` with no
 *     key handler advertises a role it does not honour, and a handset has no keys to honour it with.
 *     The fourth state is new work on BOTH clients and is described at [DwTraceCompareMode.DIFFERENCE];
 *     it is the only one that is not one or both of the two plates the runtime already built.
 *  2. **The wipe handle lives in a strip BELOW the frame, not on it.** The portal's grip is a 40 px
 *     circle, under Material's 48 dp minimum, and it had to be clamped a grip-radius inside the frame
 *     because at position 0 half of it was clipped away (`reveal1.tsx:65-71`). Moving it out solves
 *     both — and solves the one the portal cannot have noticed, which is that a thumb on the seam is a
 *     thumb over the drawing.
 *  3. **Press and hold anywhere in the frame peeks at the photograph; release returns.** One thumb, no
 *     aim, no handle to acquire. It is the gesture the wipe is standing in for.
 *
 * Pinch-zoom is added for a reason that is not convenience: a pencil line on a 1024 px plate shown at
 * ~360 dp is sub-pixel, so **the failure this comparator exists to catch is invisible at
 * fit-to-screen**. The transform is computed once and both layers are drawn through it, which is the
 * portal's own invariant — a comparison whose two layers are transformed independently misattributes
 * every line it draws.
 *
 * ── THE PLATE CONTRACT, CARRIED VERBATIM ──────────────────────────────────────────────────────
 *
 * `frontend/components/sketches/upload/comparisonPlates.ts:9-42` states four decisions, each of which
 * is a bug if reversed. Three of them are the runtime's to honour and are stated at
 * [DwTraceResult.tracePlate] and [DwTraceResult.photographPlate]: the photograph comes from the
 * DECODED PIXELS the engine was handed (two decoders hold different EXIF opinions, so one layer can
 * arrive rotated and the other upright, which reads as "the trace came out sideways"); the trace is
 * painted on OPAQUE WHITE (a transparent AFTER layer over the photograph shows the photograph through
 * both layers, so the divider moves and nothing changes, which is indistinguishable from a broken
 * slider); and neither plate may ever reach the record.
 *
 * **The fourth is this file's, and it is enforced rather than assumed:** both plates are the same
 * size, and a mismatch is a REFUSAL. A comparator that quietly letterboxed one layer would be
 * comparing two different framings of the drawing and reporting the difference as a tracing error.
 *
 * A fifth decision is the panel's and is stated here because this is where it is read: the plates are
 * capped at `DW_TRACE_PLATE_LONG_EDGE_PX` and are usually SMALLER than the drawing, so this file says
 * so under the frame ([dwTraceComparisonReduction]). Both numbers, or a designer judging lost line
 * weight cannot tell whether the loss is the trace's or the plate's.
 *
 * ── REDUCED MOTION ────────────────────────────────────────────────────────────────────────────
 *
 * `LocalAppPreferences.current.reducedMotion`, read the way `MapScreen.kt:1793` and
 * `DwQrLiveScanner.kt:397` read it. There is exactly one animation here — the seam sliding when the
 * three-state control jumps it from one end to the other — and with stillness on it jumps instead.
 * **Nothing is lost**: every signal in this comparator is static (the badges, the seam, the state
 * description), and the slide exists only so a designer can see WHICH way the frame just moved.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * State
 * ──────────────────────────────────────────────────────────────────────────── */

/** What the frame is showing. */
enum class DwTraceCompareMode {
    /** The trace, whole. */
    DRAWING,

    /** Both, split by a draggable seam. The default. */
    WIPE,

    /** The photograph, whole. */
    PHOTOGRAPH,

    /**
     * The two subtracted from each other — black where they agree, bright where they do not.
     *
     * **NEW WORK ON BOTH CLIENTS, AND THE SAME NAME ON BOTH.** The portal is growing this mode at the
     * same time; the label is "Difference" there too, and the arithmetic both implement is the one
     * stated at [dwTraceDifferenceRow] — an absolute difference per channel, deliberately not a
     * luminance difference, because a luminance difference needs a set of weights and there is more
     * than one standard set.
     *
     * IT IS FOURTH AND NOT FIRST. The wipe answers the question this comparator exists for one strip
     * at a time and is what a designer reaches for; the difference answers it everywhere at once and
     * is what they reach for when the wipe has left them unsure. It is also the only one of the four
     * that costs a third bitmap, so it is built on the first press rather than with the other two.
     */
    DIFFERENCE,
}

/**
 * Where the seam sits when the comparator opens: **0, with the drawing filling the frame.**
 *
 * Mirrors the portal's `COMPARE_START_POSITION` (`SketchTraceField.tsx:242`), and the reason is worth
 * keeping: the thing being judged is the TRACE, so the trace is what a designer should be looking at
 * before they touch anything. Dragging then reveals the photograph underneath it. Passing the two the
 * other way round is the obvious mistake and `reveal1.tsx:27-32` says so in its own header.
 */
const val DW_TRACE_COMPARE_START: Float = 0f

/**
 * How long a finger must stay down before "peek at the photograph" starts.
 *
 * Not zero, and the reason is the pinch: a two-finger gesture puts one finger down first, and a peek
 * that began on contact would flash the photograph at the start of every zoom. 220 ms is under
 * Android's own long-press threshold (500 ms) because this is not a long press — the designer is
 * holding to look, not holding to open a menu, and half a second of nothing happening reads as the
 * gesture not existing.
 */
private const val DW_TRACE_PEEK_HOLD_MS: Long = 220L

/** The most a designer may magnify a plate. Beyond this a 1024 px plate is showing its own pixels. */
private const val DW_TRACE_MAX_ZOOM: Float = 6f

/** Milliseconds the seam takes to travel when a mode button moves it. Zero under stillness. */
private const val DW_TRACE_SEAM_SLIDE_MS: Int = 180

/* ────────────────────────────────────────────────────────────────────────────
 * The comparator
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The two plates, in one frame.
 *
 * @param photograph the pixels the engine was handed, at the same size as [trace].
 * @param trace the trace, painted on opaque white.
 * @param tracedWidth the frame the DRAWING was traced in, so the reduction can be stated. The plates
 *   are capped at `DW_TRACE_PLATE_LONG_EDGE_PX` and are usually smaller than this.
 * @param difference the third plate, once it has been built. Null until the designer asks for it.
 * @param differenceRefusal why there is no third plate, or empty while there is still hope of one.
 * @param onDifferenceWanted called the first time the fourth chip is pressed. Building the plate is
 *   the caller's job, because this file is deliberately the one that does not know what a `Bitmap` is.
 * @param enabled false while a run is in flight; the pictures stay, the gestures stop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DwSketchTraceCompare(
    photograph: ImageBitmap,
    trace: ImageBitmap,
    tracedWidth: Int,
    tracedHeight: Int,
    modifier: Modifier = Modifier,
    difference: ImageBitmap? = null,
    differenceRefusal: String = "",
    onDifferenceWanted: () -> Unit = {},
    enabled: Boolean = true,
) {
    /*
      DECISION 3 OF THE PLATE CONTRACT, ENFORCED RATHER THAN ASSUMED. One rounding pixel is the
      tolerance `comparisonPlates.ts` allows itself; anything more and the two layers are different
      framings of the same drawing, and every difference the wipe shows would be attributed to the
      tracing.

      A DEFENSIVE GUARD, and its sentence is now true of the path that would reach it. The runtime
      builds both plates from one `dwTraceWorkingSize` result, so today they cannot disagree; the one
      condition that used to produce a mismatch is caught upstream and comes back as
      `DwTraceResult.plateRefusal` with no plates at all. What this stops is the next person wiring a
      pair from two different sources — and since the plate build stopped being fatal, "the trace
      itself is unaffected and can still be attached" is a description of what actually happens
      rather than a claim the code contradicted.
    */
    val widthGap = abs(photograph.width - trace.width)
    val heightGap = abs(photograph.height - trace.height)
    if (widthGap > 1 || heightGap > 1) {
        DwPanelNote(
            warning = true,
            text = "The two pictures came back at different sizes — the photograph at " +
                "${photograph.width}×${photograph.height} and the drawing at " +
                "${trace.width}×${trace.height} — so they cannot be laid over each other. The trace " +
                "itself is unaffected and can still be attached; only this comparison is unavailable.",
        )
        return
    }

    val stillness = LocalAppPreferences.current.reducedMotion

    var mode by remember { mutableStateOf(DwTraceCompareMode.WIPE) }
    var seam by remember { mutableFloatStateOf(DW_TRACE_COMPARE_START) }
    var peeking by remember { mutableStateOf(false) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    /*
      ONE NUMBER DECIDES WHAT IS DRAWN, whatever set it. A mode button writes the seam to an end and a
      drag writes it to wherever the thumb is, so there is no second piece of state that could
      disagree with the first about which picture is on screen — the failure two independent flags
      always eventually produce, which `SketchTraceField.tsx:223-231` records for its own busy state.
      Peek is the one exception and it is deliberately NOT written into `seam`: a peek must leave the
      seam exactly where the designer put it, or letting go would lose their place.
    */
    val target = when {
        peeking -> 1f
        mode == DwTraceCompareMode.DRAWING -> 0f
        mode == DwTraceCompareMode.PHOTOGRAPH -> 1f
        else -> seam
    }
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (stillness) 0 else DW_TRACE_SEAM_SLIDE_MS),
        label = "trace-compare-seam",
    )

    val percent = (shown * 100f).roundToInt()
    // CAPITALISED, because these two words are the BADGES drawn over the picture, and a spoken
    // description that names its controls differently from the way they are written is describing
    // something the listener cannot then point at. `reveal1.tsx:254` says "62% Photograph, 38% Traced
    // drawing" and this file's own header has always quoted that form as the thing it mirrors.
    val speech = "$percent% Photograph, ${100 - percent}% Traced drawing"

    val aspect = if (photograph.height > 0) {
        photograph.width.toFloat() / photograph.height.toFloat()
    } else {
        1f
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        /* ── The four states ────────────────────────────────────────────────────────────────── */

        // A FLOW ROW AND NOT A ROW, since the fourth chip arrived. Four Material buttons at their 40 dp
        // minimum do not fit across 360 dp, and a fixed Row would have squeezed the labels rather than
        // wrapping them — which on the narrowest handsets this programme uses is a control that reads
        // as broken.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DwPanelChip(
                label = "Drawing",
                selected = mode == DwTraceCompareMode.DRAWING,
                enabled = enabled,
            ) { mode = DwTraceCompareMode.DRAWING }
            DwPanelChip(
                label = "Wipe",
                selected = mode == DwTraceCompareMode.WIPE,
                enabled = enabled,
            ) { mode = DwTraceCompareMode.WIPE }
            DwPanelChip(
                label = "Photograph",
                selected = mode == DwTraceCompareMode.PHOTOGRAPH,
                enabled = enabled,
            ) { mode = DwTraceCompareMode.PHOTOGRAPH }
            DwPanelChip(
                label = DW_TRACE_DIFFERENCE_LABEL,
                selected = mode == DwTraceCompareMode.DIFFERENCE,
                // Pressable even after a refusal: pressing it is how a designer reads the sentence
                // saying why there is nothing there, and a chip that went dead with no explanation is
                // the state this whole panel is written against.
                enabled = enabled,
            ) {
                mode = DwTraceCompareMode.DIFFERENCE
                // Asked every press rather than once. The caller ignores it when the plate is already
                // built, and a designer who pressed it during a low-memory moment gets a second try
                // without having to re-trace.
                if (difference == null) onDifferenceWanted()
            }
        }

        /* ── The frame ──────────────────────────────────────────────────────────────────────── */

        Box(
            modifier = Modifier
                .fillMaxWidth()
                /*
                  THE PHOTOGRAPH'S OWN RATIO, and not a fixed 16:9. `SketchTraceField.tsx:1364` passes
                  it for the same reason: a portrait A4 sheet in a landscape frame loses most of the
                  drawing off the top and bottom, which is precisely the part of a sketch a designer is
                  checking.
                */
                .aspectRatio(aspect)
                .clipToBounds()
                .background(MaterialTheme.field.surface200, RoundedCornerShape(10.dp))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, DW_TRACE_MAX_ZOOM)
                        pan += panChange
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            /*
                              A HOLD, NOT A TOUCH. `tryAwaitRelease` returning null from the timeout
                              means the finger is still down, which is the only state in which peeking
                              is what the designer meant. A pinch's first finger releases as soon as
                              the transform detector consumes it, so this does not fire during a zoom.
                            */
                            val releasedEarly = withTimeoutOrNull(DW_TRACE_PEEK_HOLD_MS) {
                                tryAwaitRelease()
                            }
                            if (releasedEarly == null) {
                                peeking = true
                                tryAwaitRelease()
                                peeking = false
                            }
                        },
                    )
                }
                .semantics {
                    // The frame IS the picture. TalkBack gets the same two facts a sighted reader has:
                    // what is on screen, and in what proportion. In the difference mode the proportion
                    // is meaningless — there is one picture, not two laid over each other — so it says
                    // what that picture is instead of reading out a seam nobody can see.
                    contentDescription = if (mode == DwTraceCompareMode.DIFFERENCE) {
                        DW_TRACE_DIFFERENCE_DESCRIPTION
                    } else {
                        "The traced drawing laid over the photograph it came from. $speech."
                    }
                },
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas

                /*
                  ONE TRANSFORM, BOTH LAYERS. Computed here and used for both `drawImage` calls, so it
                  is not possible for the two pictures to be scaled or panned differently — which is
                  the invariant the whole comparison rests on. Independently transformed layers do not
                  fail loudly; they show a drawing that appears to have drifted off its own photograph.
                */
                val iw = photograph.width.toFloat()
                val ih = photograph.height.toFloat()
                val fit = min(size.width / iw, size.height / ih)
                val scale = fit * zoom
                val dw = iw * scale
                val dh = ih * scale
                // Panning is clamped to the picture's own overhang, so a plate can never be flicked
                // off the frame and left as an empty grey box the designer has to guess how to undo.
                val slackX = max(0f, (dw - size.width) / 2f)
                val slackY = max(0f, (dh - size.height) / 2f)
                val px = pan.x.coerceIn(-slackX, slackX)
                val py = pan.y.coerceIn(-slackY, slackY)
                val left = (size.width - dw) / 2f + px
                val top = (size.height - dh) / 2f + py
                val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
                val dstSize = IntSize(dw.roundToInt(), dh.roundToInt())

                /*
                  THE DIFFERENCE PLATE IS A WHOLE PICTURE, NOT A LAYER. It is already the two plates
                  combined, so there is no seam to draw and nothing to clip — and it is drawn through
                  the SAME transform as the other two, so a designer who zoomed into a corner in the
                  wipe and then pressed the fourth chip is looking at the same corner.

                  A press-and-hold still peeks at the photograph from here, and that is deliberate:
                  "is that bright patch a line I actually drew" is exactly the question the difference
                  plate provokes, and the photograph is the only thing that answers it.
                */
                if (mode == DwTraceCompareMode.DIFFERENCE && !peeking) {
                    if (difference != null) {
                        drawImage(image = difference, dstOffset = dstOffset, dstSize = dstSize)
                    }
                    return@Canvas
                }

                drawImage(image = trace, dstOffset = dstOffset, dstSize = dstSize)

                val divider = size.width * shown.coerceIn(0f, 1f)
                if (divider > 0f) {
                    clipRect(left = 0f, top = 0f, right = divider, bottom = size.height) {
                        drawImage(image = photograph, dstOffset = dstOffset, dstSize = dstSize)
                    }
                    if (divider < size.width) {
                        // The seam. Two strokes, dark under light, so it is visible against a white
                        // sheet AND against a black line without depending on either.
                        drawRect(
                            color = Color.Black.copy(alpha = 0.45f),
                            topLeft = Offset(divider - 1.5f, 0f),
                            size = Size(3f, size.height),
                        )
                        drawRect(
                            color = Color.White.copy(alpha = 0.9f),
                            topLeft = Offset(divider - 0.5f, 0f),
                            size = Size(1f, size.height),
                        )
                    }
                }
            }

            /*
              THE BADGES ARE CLIPPED BY THE SAME NUMBER AS THE LAYER THEY NAME, which is the portal's
              rule (`reveal1.tsx:49-60`) and not decoration: a badge visible over the picture it does
              not name is a label pointing at the wrong thing, and at a 40% wipe both would otherwise
              sit over the same half of the sheet.
            */
            if (mode == DwTraceCompareMode.DIFFERENCE && !peeking) {
                // One badge, because there is one picture and it is neither of the two the other
                // badges name. Without it a nearly black frame is indistinguishable from a plate that
                // failed to draw.
                DwCompareBadge(DW_TRACE_DIFFERENCE_LABEL, Modifier.align(Alignment.TopStart))
            } else {
                if (shown > 0.18f) {
                    DwCompareBadge("Photograph", Modifier.align(Alignment.TopStart))
                }
                if (shown < 0.82f) {
                    DwCompareBadge("Traced drawing", Modifier.align(Alignment.TopEnd))
                }
            }
            if (zoom > 1.01f) {
                DwCompareBadge(
                    "${(zoom * 10f).roundToInt() / 10f}× — pinch out to fit",
                    Modifier.align(Alignment.BottomStart),
                )
            }
        }

        /* ── The wipe strip ─────────────────────────────────────────────────────────────────── */

        if (mode == DwTraceCompareMode.WIPE) {
            DwTraceWipeStrip(
                position = shown,
                enabled = enabled && !peeking,
                speech = speech,
                onPosition = { seam = it.coerceIn(0f, 1f) },
            )
            Text(
                // Said once, under the control, because a gesture nobody is told about is a gesture
                // nobody uses — and this one is the reason the strip can afford to be below the frame.
                "Drag the strip to wipe between the two. Press and hold the picture to see the " +
                    "photograph, and let go to come back.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        /* ── What the pictures are, and what they are not ───────────────────────────────────── */

        if (mode == DwTraceCompareMode.DIFFERENCE) {
            if (difference == null) {
                DwPanelNote(
                    warning = differenceRefusal.isNotBlank(),
                    // A press that produced nothing must say which of the two it was. "Working on it"
                    // and "it could not be done" are the same blank frame otherwise, and a designer
                    // who waits for the first when it was the second waits forever.
                    text = differenceRefusal.ifBlank { DW_TRACE_DIFFERENCE_PENDING },
                    polite = true,
                )
            }
            Text(
                DW_TRACE_DIFFERENCE_NOTE,
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        /*
          TWO FACTS ABOUT THE PICTURES THEMSELVES, SAID NEXT TO THEM.

          The white was already stated — as the note on the "White background" toggle, which sits in
          the EXPORT group behind the disclosure on a different part of the panel. A designer who never
          opens that step sees a white drawing over their photograph with no explanation, and the
          obvious conclusion is that the trace flooded their sheet. `SketchTraceField.tsx:1412-1414`
          says it under its comparator, and now so does this.

          The reduction was stated nowhere at all, which is worse: `comparisonPlates.ts:68-70` carries
          a flag whose entire documentation is "Say so on screen", and without it a designer judging
          lost line weight at 1024 against a 4096 trace cannot tell whether the loss is the trace's or
          the plate's — the one question this comparator exists to answer, asked about the comparator.
        */
        val reduction = dwTraceComparisonReduction(
            plateWidth = photograph.width,
            plateHeight = photograph.height,
            tracedWidth = tracedWidth,
            tracedHeight = tracedHeight,
        )
        Text(
            if (reduction.isEmpty()) DW_TRACE_COMPARE_WHITE_NOTE else "$DW_TRACE_COMPARE_WHITE_NOTE $reduction",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The strip
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The seam's handle, in 48 dp of its own space below the picture.
 *
 * Below the frame and not on it, for the three reasons in this file's header. It is also the only
 * place a handle can be a full 48 dp without covering the drawing: inside the frame, Material's
 * minimum target is roughly a seventh of a 360 dp screen's width, sitting on top of the thing being
 * judged.
 */
@Composable
private fun DwTraceWipeStrip(
    position: Float,
    enabled: Boolean,
    speech: String,
    onPosition: (Float) -> Unit,
) {
    var width by remember { mutableFloatStateOf(0f) }
    val handlePx = with(LocalDensity.current) { 48.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { width = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                state = rememberDraggableState { delta ->
                    if (width > 0f) onPosition(position + delta / width)
                },
            )
            .pointerInput(enabled, width) {
                if (!enabled) return@pointerInput
                // Tap to jump. A designer who wants "mostly photograph" should not have to drag there
                // from wherever the seam happens to be.
                detectTapGestures { offset ->
                    if (width > 0f) onPosition(offset.x / width)
                }
            }
            .semantics {
                /*
                  THE PORTAL'S `role="slider"` + `aria-valuetext` ("62% Photograph, 38% Traced
                  drawing", `reveal1.tsx:254`), in Compose's dialect. `progressBarRangeInfo` is what
                  TalkBack announces as a proportion; `stateDescription` replaces the bare number with
                  the sentence a person can act on. The three-state control above the frame is the
                  route in for somebody who cannot drag at all.
                */
                progressBarRangeInfo = ProgressBarRangeInfo(position.coerceIn(0f, 1f), 0f..1f)
                stateDescription = speech
                contentDescription = "Wipe between the photograph and the traced drawing"
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.Center)
                .background(MaterialTheme.field.surface300, RoundedCornerShape(2.dp)),
        )
        // The handle, clamped so its whole 48 dp stays on screen at both ends. The portal has the same
        // clamp and had to apply it INSIDE the clipped frame, which is what cost it half a grip at
        // position 0 (`reveal1.tsx:67-71`).
        val travel = max(0f, width - handlePx)
        val offsetPx = (position.coerceIn(0f, 1f) * travel).roundToInt()
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetPx, 0) }
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.UnfoldMore,
                // Decorative: the strip's own semantics carry the meaning, and a second announcement
                // here would have TalkBack read the control twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(90f),
            )
        }
    }
}

/** A picture's name, drawn over the corner of the half it belongs to. */
@Composable
private fun DwCompareBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
